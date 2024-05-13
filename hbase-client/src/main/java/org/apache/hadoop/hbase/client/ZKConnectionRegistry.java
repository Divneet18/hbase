/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.client;

import static org.apache.hadoop.hbase.client.RegionInfo.DEFAULT_REPLICA_ID;
import static org.apache.hadoop.hbase.client.RegionInfoBuilder.FIRST_META_REGIONINFO;
import static org.apache.hadoop.hbase.client.RegionReplicaUtil.getRegionInfoForDefaultReplica;
import static org.apache.hadoop.hbase.client.RegionReplicaUtil.getRegionInfoForReplica;
import static org.apache.hadoop.hbase.shaded.protobuf.ProtobufUtil.lengthOfPBMagic;
import static org.apache.hadoop.hbase.trace.TraceUtil.tracedFuture;
import static org.apache.hadoop.hbase.util.FutureUtils.addListener;
import static org.apache.hadoop.hbase.zookeeper.ZKMetadata.removeMetaData;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.hbase.thirdparty.io.netty.util.Timeout;
import org.apache.hbase.thirdparty.io.netty.util.TimerTask;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.ClusterId;
import org.apache.hadoop.hbase.HBaseInterfaceAudience;
import org.apache.hadoop.hbase.HRegionLocation;
import org.apache.hadoop.hbase.RegionLocations;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.exceptions.DeserializationException;
import org.apache.hadoop.hbase.master.RegionState;
import org.apache.hadoop.hbase.security.User;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.hbase.zookeeper.ReadOnlyZKClient;
import org.apache.hadoop.hbase.zookeeper.ZNodePaths;
import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hadoop.hbase.shaded.protobuf.generated.HBaseProtos;
import org.apache.hadoop.hbase.shaded.protobuf.generated.ZooKeeperProtos;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;

/**
 * Zookeeper based registry implementation.
 * @deprecated As of 2.6.0, replaced by {@link RpcConnectionRegistry}, which is the default
 *             connection mechanism as of 3.0.0. Expected to be removed in 4.0.0.
 * @see <a href="https://issues.apache.org/jira/browse/HBASE-23324">HBASE-23324</a> and its parent
 *      ticket for details.
 */
@Deprecated
@InterfaceAudience.LimitedPrivate(HBaseInterfaceAudience.CONFIG)
class ZKConnectionRegistry implements ConnectionRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(ZKConnectionRegistry.class);

  private static final Object WARN_LOCK = new Object();
  private static volatile boolean NEEDS_LOG_WARN = true;

  private final ReadOnlyZKClient zk;

  private final ZNodePaths znodePaths;

  private static final long expectedTimeout = 120000;
  private static final int maxAttempts = 5;
  private static final long pauseNs = 100000;


  // User not used, but for rpc based registry we need it
  ZKConnectionRegistry(Configuration conf, User ignored) {
    this.znodePaths = new ZNodePaths(conf);
    this.zk = new ReadOnlyZKClient(conf);
    if (NEEDS_LOG_WARN) {
      synchronized (WARN_LOCK) {
        if (NEEDS_LOG_WARN) {
          LOG.warn(
            "ZKConnectionRegistry is deprecated. See https://hbase.apache.org/book.html#client.rpcconnectionregistry");
          NEEDS_LOG_WARN = false;
        }
      }
    }
  }

  public ZNodePaths getZNodePaths() {
    return znodePaths;
  }

  private interface Converter<T> {
    T convert(byte[] data) throws Exception;
  }

  private <T> CompletableFuture<T> getAndConvert(String path, Converter<T> converter) {
    CompletableFuture<T> future = new CompletableFuture<>();
      TimerTask pollingTask = new TimerTask() {
        int tries = 0;
        long startTime = EnvironmentEdgeManager.currentTime();
        long endTime = startTime + expectedTimeout;
        long maxPauseTime = expectedTimeout / maxAttempts;

        @Override
        public void run(Timeout timeout) throws Exception {
          if (EnvironmentEdgeManager.currentTime() < endTime) {
            addListener(zk.get(path), (data, error) -> {
              if (error != null) {
                future.completeExceptionally(error);
                return;
              }
              if (data != null  && data.length > 0) {
                try {
                  future.complete(converter.convert(data));
                } catch (Exception e) {
                  future.completeExceptionally(e);
                }
              } else {
                // retry again after pauseTime.
                long pauseTime =
                  ConnectionUtils.getPauseTime(TimeUnit.NANOSECONDS.toMillis(pauseNs), ++tries);
                pauseTime = Math.min(pauseTime, maxPauseTime);
                AsyncConnectionImpl.RETRY_TIMER.newTimeout(this, pauseTime,
                  TimeUnit.MICROSECONDS);
              }
            });
          } else {
            future.completeExceptionally(new IOException("Procedure wasn't completed in "
              + "expectedTime:" + expectedTimeout + " ms"));
          }
        }
      };
      // Queue the polling task into RETRY_TIMER to poll procedure state asynchronously.
      AsyncConnectionImpl.RETRY_TIMER.newTimeout(pollingTask, 1, TimeUnit.MILLISECONDS);

    return future;
  }

  private static String getClusterId(byte[] data) throws DeserializationException {
    if (data == null || data.length == 0) {
      return null;
    }
    data = removeMetaData(data);
    return ClusterId.parseFrom(data).toString();
  }

  @Override
  public CompletableFuture<String> getClusterId() {
    return tracedFuture(
      () -> getAndConvert(znodePaths.clusterIdZNode, ZKConnectionRegistry::getClusterId),
      "ZKConnectionRegistry.getClusterId");
  }

  ReadOnlyZKClient getZKClient() {
    return zk;
  }

  private static ZooKeeperProtos.MetaRegionServer getMetaProto(byte[] data) throws IOException {
    if (data == null || data.length == 0) {
      return null;
    }
    data = removeMetaData(data);
    int prefixLen = lengthOfPBMagic();
    return ZooKeeperProtos.MetaRegionServer.parser().parseFrom(data, prefixLen,
      data.length - prefixLen);
  }

  private static void tryComplete(MutableInt remaining, HRegionLocation[] locs,
    CompletableFuture<RegionLocations> future) {
    remaining.decrement();
    if (remaining.intValue() > 0) {
      return;
    }
    future.complete(new RegionLocations(locs));
  }

  private Pair<RegionState.State, ServerName>
    getStateAndServerName(ZooKeeperProtos.MetaRegionServer proto) {
    RegionState.State state;
    if (proto.hasState()) {
      state = RegionState.State.convert(proto.getState());
    } else {
      state = RegionState.State.OPEN;
    }
    HBaseProtos.ServerName snProto = proto.getServer();
    return Pair.newPair(state,
      ServerName.valueOf(snProto.getHostName(), snProto.getPort(), snProto.getStartCode()));
  }

  private void getMetaRegionLocation(CompletableFuture<RegionLocations> future,
    List<String> metaReplicaZNodes) {
    if (metaReplicaZNodes.isEmpty()) {
      future.completeExceptionally(new IOException("No meta znode available"));
    }
    HRegionLocation[] locs = new HRegionLocation[metaReplicaZNodes.size()];
    MutableInt remaining = new MutableInt(locs.length);
    for (String metaReplicaZNode : metaReplicaZNodes) {
      int replicaId = znodePaths.getMetaReplicaIdFromZNode(metaReplicaZNode);
      String path = ZNodePaths.joinZNode(znodePaths.baseZNode, metaReplicaZNode);
      if (replicaId == DEFAULT_REPLICA_ID) {
        addListener(getAndConvert(path, ZKConnectionRegistry::getMetaProto), (proto, error) -> {
          if (error != null) {
            future.completeExceptionally(error);
            return;
          }
          if (proto == null) {
            future.completeExceptionally(new IOException("Meta znode is null"));
            return;
          }
          Pair<RegionState.State, ServerName> stateAndServerName = getStateAndServerName(proto);
          if (stateAndServerName.getFirst() != RegionState.State.OPEN) {
            LOG.warn("Meta region is in state " + stateAndServerName.getFirst());
          }
          locs[DEFAULT_REPLICA_ID] = new HRegionLocation(
            getRegionInfoForDefaultReplica(FIRST_META_REGIONINFO), stateAndServerName.getSecond());
          tryComplete(remaining, locs, future);
        });
      } else {
        addListener(getAndConvert(path, ZKConnectionRegistry::getMetaProto), (proto, error) -> {
          if (future.isDone()) {
            return;
          }
          if (error != null) {
            LOG.warn("Failed to fetch " + path, error);
            locs[replicaId] = null;
          } else if (proto == null) {
            LOG.warn("Meta znode for replica " + replicaId + " is null");
            locs[replicaId] = null;
          } else {
            Pair<RegionState.State, ServerName> stateAndServerName = getStateAndServerName(proto);
            if (stateAndServerName.getFirst() != RegionState.State.OPEN) {
              LOG.warn("Meta region for replica " + replicaId + " is in state "
                + stateAndServerName.getFirst());
              locs[replicaId] = null;
            } else {
              locs[replicaId] =
                new HRegionLocation(getRegionInfoForReplica(FIRST_META_REGIONINFO, replicaId),
                  stateAndServerName.getSecond());
            }
          }
          tryComplete(remaining, locs, future);
        });
      }
    }
  }

  @Override
  public CompletableFuture<RegionLocations> getMetaRegionLocations() {
    return tracedFuture(() -> {
      CompletableFuture<RegionLocations> future = new CompletableFuture<>();
      TimerTask pollingTask = new TimerTask() {
        int tries = 0;
        long startTime = EnvironmentEdgeManager.currentTime();
        long endTime = startTime + expectedTimeout;
        long maxPauseTime = expectedTimeout / maxAttempts;

        @Override
        public void run(Timeout timeout) throws Exception {
          if (EnvironmentEdgeManager.currentTime() < endTime) {
            addListener(
              zk.list(znodePaths.baseZNode).thenApply(children -> children.stream()
                .filter(c -> getZNodePaths().isMetaZNodePrefix(c)).collect(Collectors.toList())),
              (metaReplicaZNodes, error) -> {
              if (error != null) {
                future.completeExceptionally(error);
                return;
              }
              if (metaReplicaZNodes != null && !metaReplicaZNodes.isEmpty()) {
                getMetaRegionLocation(future, metaReplicaZNodes);
              } else {
                // retry again after pauseTime.
                long pauseTime =
                  ConnectionUtils.getPauseTime(TimeUnit.NANOSECONDS.toMillis(pauseNs), ++tries);
                pauseTime = Math.min(pauseTime, maxPauseTime);
                AsyncConnectionImpl.RETRY_TIMER.newTimeout(this, pauseTime,
                  TimeUnit.MICROSECONDS);
              }
            });
          } else {
            future.completeExceptionally(new IOException("Procedure wasn't completed in "
            + "expectedTime:" + expectedTimeout + " ms"));
          }
        }
      };
      // Queue the polling task into RETRY_TIMER to poll procedure state asynchronously.
      AsyncConnectionImpl.RETRY_TIMER.newTimeout(pollingTask, 1, TimeUnit.MILLISECONDS);

      return future;
    }, "ZKConnectionRegistry.getMetaRegionLocations");
  }

  private static ZooKeeperProtos.Master getMasterProto(byte[] data) throws IOException {
    if (data == null || data.length == 0) {
      return null;
    }
    data = removeMetaData(data);
    int prefixLen = lengthOfPBMagic();
    return ZooKeeperProtos.Master.parser().parseFrom(data, prefixLen, data.length - prefixLen);
  }

  @Override
  public CompletableFuture<ServerName> getActiveMaster() {
    return tracedFuture(
      () -> getAndConvert(znodePaths.masterAddressZNode, ZKConnectionRegistry::getMasterProto)
        .thenApply(proto -> {
          if (proto == null) {
            return null;
          }
          HBaseProtos.ServerName snProto = proto.getMaster();
          return ServerName.valueOf(snProto.getHostName(), snProto.getPort(),
            snProto.getStartCode());
        }),
      "ZKConnectionRegistry.getActiveMaster");
  }

  @Override
  public String getConnectionString() {
    final String serverList = zk.getConnectString();
    final String baseZNode = znodePaths.baseZNode;
    return serverList + ":" + baseZNode;
  }

  @Override
  public void close() {
    zk.close();
  }
}
