/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.action.support.replication;

import org.apache.lucene.util.SetOnce;
import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.action.shard.ShardStateAction;
import org.elasticsearch.cluster.block.ClusterBlock;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.block.ClusterBlocks;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.IndexRoutingTable;
import org.elasticsearch.cluster.routing.IndexShardRoutingTable;
import org.elasticsearch.cluster.routing.RecoverySource;
import org.elasticsearch.cluster.routing.RoutingTable;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.ShardRoutingState;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.lease.Releasable;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.engine.InternalEngineFactory;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.index.shard.IndexShardTestCase;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.test.transport.MockTransportService;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportChannel;
import org.elasticsearch.transport.TransportResponse;
import org.elasticsearch.transport.TransportService;
import org.junit.After;
import org.junit.Before;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;

import static java.util.Collections.emptyMap;
import static org.elasticsearch.cluster.metadata.IndexMetaData.SETTING_CREATION_DATE;
import static org.elasticsearch.cluster.metadata.IndexMetaData.SETTING_INDEX_UUID;
import static org.elasticsearch.cluster.metadata.IndexMetaData.SETTING_NUMBER_OF_REPLICAS;
import static org.elasticsearch.cluster.metadata.IndexMetaData.SETTING_NUMBER_OF_SHARDS;
import static org.elasticsearch.cluster.metadata.IndexMetaData.SETTING_VERSION_CREATED;
import static org.elasticsearch.cluster.routing.TestShardRouting.newShardRouting;
import static org.elasticsearch.test.ClusterServiceUtils.createClusterService;
import static org.elasticsearch.test.ClusterServiceUtils.setState;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;


/**
 * This test tests the concurrent execution of several transport replication actions. All of these actions (except one) acquire a single
 * permit during their execution on shards and are expected to fail if a global level or index level block is present in the cluster state.
 * These actions are all started at the same time, but some are delayed until one last action.
 *
 * This last action is special because it acquires all the permits on shards, adds the block to the cluster state and then "releases" the
 * previously delayed single permit actions. This way, there is a clear transition between the single permit actions executed before the
 * all permit action that sets the block and those executed afterwards that are doomed to fail because of the block.
 */
public class TransportReplicationAllPermitsAcquisitionTests extends IndexShardTestCase {

    private ClusterService clusterService;
    private TransportService transportService;
    private ShardStateAction shardStateAction;
    private ShardId shardId;
    private IndexShard primary;
    private IndexShard replica;
    private boolean globalBlock;
    private ClusterBlock block;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        globalBlock = randomBoolean();
        RestStatus restStatus = randomFrom(RestStatus.values());
        block = new ClusterBlock(randomIntBetween(1, 10), randomAlphaOfLength(5), false, true, false, restStatus, ClusterBlockLevel.ALL);
        clusterService = createClusterService(threadPool);

        final ClusterState.Builder state = ClusterState.builder(clusterService.state());
        Set<DiscoveryNode.Role> roles = new HashSet<>(Arrays.asList(DiscoveryNode.Role.values()));
        DiscoveryNode node1 = new DiscoveryNode("_name1", "_node1", buildNewFakeTransportAddress(), emptyMap(), roles, Version.CURRENT);
        DiscoveryNode node2 = new DiscoveryNode("_name2", "_node2", buildNewFakeTransportAddress(), emptyMap(), roles, Version.CURRENT);
        state.nodes(DiscoveryNodes.builder()
            .add(node1)
            .add(node2)
            .localNodeId(node1.getId())
            .masterNodeId(node1.getId()));

        shardId = new ShardId("index", UUID.randomUUID().toString(), 0);
        ShardRouting shardRouting =
            newShardRouting(shardId, node1.getId(), true, ShardRoutingState.INITIALIZING, RecoverySource.EmptyStoreRecoverySource.INSTANCE);

        Settings indexSettings = Settings.builder()
            .put(SETTING_VERSION_CREATED, Version.CURRENT)
            .put(SETTING_INDEX_UUID, shardId.getIndex().getUUID())
            .put(SETTING_NUMBER_OF_SHARDS, 1)
            .put(SETTING_NUMBER_OF_REPLICAS, 1)
            .put(SETTING_CREATION_DATE, System.currentTimeMillis())
            .build();

        primary = newStartedShard(p -> newShard(shardRouting, indexSettings, new InternalEngineFactory()), true);
        for (int i = 0; i < 10; i++) {
            final String id = Integer.toString(i);
            indexDoc(primary, "_doc", id, "{\"value\":" + id + "}");
        }

        IndexMetaData indexMetaData = IndexMetaData.builder(shardId.getIndexName())
            .settings(indexSettings)
            .primaryTerm(shardId.id(), primary.getOperationPrimaryTerm())
            .putMapping("_doc","{ \"properties\": { \"value\":  { \"type\": \"short\"}}}")
            .build();
        state.metaData(MetaData.builder().put(indexMetaData, false).generateClusterUuidIfNeeded());

        replica = newShard(primary.shardId(), false, node2.getId(), indexMetaData, null);
        recoverReplica(replica, primary, true);

        IndexRoutingTable.Builder routing = IndexRoutingTable.builder(indexMetaData.getIndex());
        routing.addIndexShard(new IndexShardRoutingTable.Builder(shardId)
            .addShard(primary.routingEntry())
            .build());
        state.routingTable(RoutingTable.builder().add(routing.build()).build());

        setState(clusterService, state.build());

        final Settings transportSettings = Settings.builder().put("node.name", node1.getId()).build();
        transportService = MockTransportService.createNewService(transportSettings, Version.CURRENT, threadPool, null);
        transportService.start();
        transportService.acceptIncomingRequests();
        shardStateAction = new ShardStateAction(clusterService, transportService, null, null, threadPool);
    }

    @Override
    @After
    public void tearDown() throws Exception {
        closeShards(primary, replica);
        transportService.stop();
        clusterService.close();
        super.tearDown();
    }

    public void testTransportReplicationActionWithAllPermits() throws Exception {
        final int numOperations = scaledRandomIntBetween(4, 32);
        final int delayedOperations = randomIntBetween(1, numOperations);
        logger.trace("starting [{}] operations, among which the first [{}] started ops should be blocked by [{}]",
            numOperations, delayedOperations, block);

        final CyclicBarrier delayedOperationsBarrier = new CyclicBarrier(delayedOperations + 1);
        final List<Thread> threads = new ArrayList<>(delayedOperationsBarrier.getParties());

        @SuppressWarnings("unchecked")
        final PlainActionFuture<Response>[] futures = new PlainActionFuture[numOperations];
        final TestAction[] actions = new TestAction[numOperations];

        for (int i = 0; i < numOperations; i++) {
            final int threadId = i;
            final boolean delayed = (threadId < delayedOperations);

            final PlainActionFuture<Response> listener = new PlainActionFuture<>();
            futures[threadId] = listener;

            final TestAction singlePermitAction = new SinglePermitWithBlocksAction(Settings.EMPTY, "internalSinglePermit[" + threadId + "]",
                transportService, clusterService, shardStateAction, threadPool, shardId, primary, replica, globalBlock);
            actions[threadId] = singlePermitAction;

            Thread thread = new Thread(() -> {
                TransportReplicationAction.AsyncPrimaryAction asyncPrimaryAction =
                    singlePermitAction.new AsyncPrimaryAction(request(), allocationId(), primaryTerm(), transportChannel(listener), null) {
                        @Override
                        protected void doRun() throws Exception {
                            if (delayed) {
                                logger.trace("op [{}] has started and will resume execution once allPermitsAction is terminated", threadId);
                                delayedOperationsBarrier.await();
                            }
                            super.doRun();
                        }

                        @Override
                        void runWithPrimaryShardReference(final TransportReplicationAction.PrimaryShardReference reference) {
                            assertThat(reference.indexShard.getActiveOperationsCount(), greaterThan(0));
                            assertSame(primary, reference.indexShard);
                            assertBlockIsPresentForDelayedOp();
                            super.runWithPrimaryShardReference(reference);
                        }

                        @Override
                        public void onFailure(Exception e) {
                            assertBlockIsPresentForDelayedOp();
                            super.onFailure(e);
                        }

                        private void assertBlockIsPresentForDelayedOp() {
                            if (delayed) {
                                final ClusterState clusterState = clusterService.state();
                                if (globalBlock) {
                                    assertTrue("Global block must exist", clusterState.blocks().hasGlobalBlock(block));
                                } else {
                                    String indexName = primary.shardId().getIndexName();
                                    assertTrue("Index block must exist", clusterState.blocks().hasIndexBlock(indexName, block));
                                }
                            }
                        }
                    };
                asyncPrimaryAction.run();
            });
            threads.add(thread);
            thread.start();
        }

        logger.trace("now starting the operation that acquires all permits and sets the block in the cluster state");

        // An action which acquires all operation permits during execution and set a block
        final TestAction allPermitsAction = new AllPermitsThenBlockAction(Settings.EMPTY, "internalAllPermits", transportService,
            clusterService, shardStateAction, threadPool, shardId, primary, replica);

        final PlainActionFuture<Response> allPermitFuture = new PlainActionFuture<>();
        Thread thread = new Thread(() -> {
            TransportReplicationAction.AsyncPrimaryAction asyncPrimaryAction =
                allPermitsAction.new AsyncPrimaryAction(request(), allocationId(), primaryTerm(), transportChannel(allPermitFuture), null) {
                    @Override
                    void runWithPrimaryShardReference(final TransportReplicationAction.PrimaryShardReference reference) {
                        assertEquals("All permits must be acquired", 0, reference.indexShard.getActiveOperationsCount());
                        assertSame(primary, reference.indexShard);

                        final ClusterState clusterState = clusterService.state();
                        final ClusterBlocks.Builder blocks = ClusterBlocks.builder();
                        if (globalBlock) {
                            assertFalse("Global block must not exist yet", clusterState.blocks().hasGlobalBlock(block));
                            blocks.addGlobalBlock(block);
                        } else {
                            String indexName = reference.indexShard.shardId().getIndexName();
                            assertFalse("Index block must not exist yet", clusterState.blocks().hasIndexBlock(indexName, block));
                            blocks.addIndexBlock(indexName, block);
                        }

                        logger.trace("adding test block to cluster state {}", block);
                        setState(clusterService, ClusterState.builder(clusterState).blocks(blocks));

                        try {
                            logger.trace("releasing delayed operations");
                            delayedOperationsBarrier.await();
                        } catch (InterruptedException | BrokenBarrierException e) {
                            onFailure(e);
                        }
                        super.runWithPrimaryShardReference(reference);
                    }
                };
            asyncPrimaryAction.run();
        });
        threads.add(thread);
        thread.start();

        logger.trace("waiting for all operations to terminate");
        for (Thread t : threads) {
            t.join();
        }

        final Response allPermitsResponse = allPermitFuture.get();
        assertSuccessfulOperation(allPermitsAction, allPermitsResponse);

        for (int i = 0; i < numOperations; i++) {
            final PlainActionFuture<Response> future = futures[i];
            final TestAction action = actions[i];

            if (i < delayedOperations) {
                ExecutionException exception = expectThrows(ExecutionException.class, "delayed operation should have failed", future::get);
                assertFailedOperation(action, exception);
            } else {
                // non delayed operation might fail depending on the order they were executed
                try {
                    assertSuccessfulOperation(action, futures[i].get());
                } catch (final ExecutionException e) {
                    assertFailedOperation(action, e);
                }
            }
        }
    }

    private void assertSuccessfulOperation(final TestAction action, final Response response) {
        final String name = action.getActionName();
        assertThat(name + " operation should have been executed on primary", action.executedOnPrimary.get(), is(true));
        assertThat(name + " operation should have been executed on replica", action.executedOnReplica.get(), is(true));
        assertThat(name + " operation must have a non null result", response, notNullValue());
        assertThat(name + " operation should have been successful on 2 shards", response.getShardInfo().getSuccessful(), equalTo(2));
    }

    private void assertFailedOperation(final TestAction action,final ExecutionException exception) {
        final String name = action.getActionName();
        assertThat(name + " operation should not have been executed on primary", action.executedOnPrimary.get(), nullValue());
        assertThat(name + " operation should not have been executed on replica", action.executedOnReplica.get(), nullValue());
        assertThat(exception.getCause(), instanceOf(ClusterBlockException.class));
        ClusterBlockException clusterBlockException = (ClusterBlockException) exception.getCause();
        assertThat(clusterBlockException.blocks(), hasItem(equalTo(block)));
    }

    private long primaryTerm() {
        return primary.getOperationPrimaryTerm();
    }

    private String allocationId() {
        return primary.routingEntry().allocationId().getId();
    }

    private Request request() {
        return new Request().setShardId(primary.shardId());
    }

    /**
     * A type of {@link TransportReplicationAction} that allows to use the primary and replica shards passed to the constructor for the
     * execution of the replication action. Also records if the operation is executed on the primary and the replica.
     */
    private abstract class TestAction extends TransportReplicationAction<Request, Request, Response> {

        protected final ShardId shardId;
        protected final IndexShard primary;
        protected final IndexShard replica;
        protected final SetOnce<Boolean> executedOnPrimary = new SetOnce<>();
        protected final SetOnce<Boolean> executedOnReplica = new SetOnce<>();

        TestAction(Settings settings, String actionName, TransportService transportService, ClusterService clusterService,
                   ShardStateAction shardStateAction, ThreadPool threadPool, ShardId shardId, IndexShard primary, IndexShard replica) {
            super(settings, actionName, transportService, clusterService, null, threadPool, shardStateAction,
                new ActionFilters(new HashSet<>()), new IndexNameExpressionResolver(), Request::new, Request::new, ThreadPool.Names.SAME);
            this.shardId = Objects.requireNonNull(shardId);
            this.primary = Objects.requireNonNull(primary);
            assertEquals(shardId, primary.shardId());
            this.replica = Objects.requireNonNull(replica);
            assertEquals(shardId, replica.shardId());
        }

        @Override
        protected Response newResponseInstance() {
            return new Response();
        }

        public String getActionName() {
            return this.actionName;
        }

        @Override
        protected PrimaryResult<Request, Response> shardOperationOnPrimary(Request shardRequest, IndexShard shard) throws Exception {
            executedOnPrimary.set(true);
            // The TransportReplicationAction.getIndexShard() method is overridden for testing purpose but we double check here
            // that the permit has been acquired on the primary shard
            assertSame(primary, shard);
            return new PrimaryResult<>(shardRequest, new Response());
        }

        @Override
        protected ReplicaResult shardOperationOnReplica(Request shardRequest, IndexShard shard) throws Exception {
            executedOnReplica.set(true);
            // The TransportReplicationAction.getIndexShard() method is overridden for testing purpose but we double check here
            // that the permit has been acquired on the replica shard
            assertSame(replica, shard);
            return new ReplicaResult();
        }

        @Override
        protected IndexShard getIndexShard(final ShardId shardId) {
            if (this.shardId.equals(shardId) == false) {
                throw new AssertionError("shard id differs from " + shardId);
            }
            return (executedOnPrimary.get() == null) ? primary : replica;
        }

        @Override
        protected void sendReplicaRequest(final ConcreteReplicaRequest<Request> replicaRequest,
                                          final DiscoveryNode node,
                                          final ActionListener<ReplicationOperation.ReplicaResponse> listener) {
            assertEquals("Replica is always assigned to node 2 in this test", clusterService.state().nodes().get("_node2"), node);
            ReplicaOperationTransportHandler replicaOperationTransportHandler = new ReplicaOperationTransportHandler();
            try {
                replicaOperationTransportHandler.messageReceived(replicaRequest, new TransportChannel() {
                    @Override
                    public String getProfileName() {
                        return null;
                    }

                    @Override
                    public String getChannelType() {
                        return null;
                    }

                    @Override
                    public void sendResponse(TransportResponse response) throws IOException {
                        listener.onResponse((ReplicationOperation.ReplicaResponse) response);
                    }

                    @Override
                    public void sendResponse(Exception exception) throws IOException {
                        listener.onFailure(exception);
                    }
                }, null);
            } catch (Exception e) {
                listener.onFailure(e);
            }
        }
    }

    /**
     * A type of {@link TransportReplicationAction} that acquires a single permit during execution and that blocks
     * on {@link ClusterBlockLevel#WRITE}. The block can be a global level or an index level block depending of the
     * value of the {@code globalBlock} parameter in the constructor. When the operation is executed on shards it
     * verifies that at least 1 permit is acquired and that there is no blocks in the cluster state.
     */
    private class SinglePermitWithBlocksAction extends TestAction {

        private final boolean globalBlock;

        SinglePermitWithBlocksAction(Settings settings, String actionName, TransportService transportService, ClusterService clusterService,
                                     ShardStateAction shardStateAction, ThreadPool threadPool,
                                     ShardId shardId, IndexShard primary, IndexShard replica, boolean globalBlock) {
            super(settings, actionName, transportService, clusterService, shardStateAction, threadPool, shardId, primary, replica);
            this.globalBlock = globalBlock;
        }

        @Override
        protected ClusterBlockLevel globalBlockLevel() {
            return globalBlock ? ClusterBlockLevel.WRITE : super.globalBlockLevel();
        }

        @Override
        protected ClusterBlockLevel indexBlockLevel() {
            return globalBlock == false ? ClusterBlockLevel.WRITE : super.indexBlockLevel();
        }

        @Override
        protected PrimaryResult<Request, Response> shardOperationOnPrimary(Request shardRequest, IndexShard shard) throws Exception {
            assertNoBlocks("block must not exist when executing the operation on primary shard: it should have been blocked before");
            assertThat(shard.getActiveOperationsCount(), greaterThan(0));
            return super.shardOperationOnPrimary(shardRequest, shard);
        }

        @Override
        protected ReplicaResult shardOperationOnReplica(Request shardRequest, IndexShard shard) throws Exception {
            assertNoBlocks("block must not exist when executing the operation on replica shard: it should have been blocked before");
            assertThat(shard.getActiveOperationsCount(), greaterThan(0));
            return super.shardOperationOnReplica(shardRequest, shard);
        }

        private void assertNoBlocks(final String error) {
            final ClusterState clusterState = clusterService.state();
            assertFalse("Global level " + error, clusterState.blocks().hasGlobalBlock(block));
            assertFalse("Index level " + error, clusterState.blocks().hasIndexBlock(shardId.getIndexName(), block));
        }
    }

    /**
     * A type of {@link TransportReplicationAction} that acquires all permits during execution.
     */
    private class AllPermitsThenBlockAction extends TestAction {

        private final TimeValue timeout = TimeValue.timeValueSeconds(30L);

        AllPermitsThenBlockAction(Settings settings, String actionName, TransportService transportService, ClusterService clusterService,
                                     ShardStateAction shardStateAction, ThreadPool threadPool,
                                     ShardId shardId, IndexShard primary, IndexShard replica) {
            super(settings, actionName, transportService, clusterService, shardStateAction, threadPool, shardId, primary, replica);
        }

        @Override
        protected void acquirePrimaryOperationPermit(IndexShard shard, Request request, ActionListener<Releasable> onAcquired) {
            shard.acquireAllPrimaryOperationsPermits(onAcquired, timeout);
        }

        @Override
        protected void acquireReplicaOperationPermit(IndexShard shard, Request request, ActionListener<Releasable> onAcquired,
                                                     long primaryTerm, long globalCheckpoint, long maxSeqNo) {
            shard.acquireAllReplicaOperationsPermits(primaryTerm, globalCheckpoint, maxSeqNo, onAcquired, timeout);
        }

        @Override
        protected PrimaryResult<Request, Response> shardOperationOnPrimary(Request shardRequest, IndexShard shard) throws Exception {
            assertEquals("All permits must be acquired", 0, shard.getActiveOperationsCount());
            return super.shardOperationOnPrimary(shardRequest, shard);
        }

        @Override
        protected ReplicaResult shardOperationOnReplica(Request shardRequest, IndexShard shard) throws Exception {
            assertEquals("All permits must be acquired", 0, shard.getActiveOperationsCount());
            return super.shardOperationOnReplica(shardRequest, shard);
        }
    }

    static class Request extends ReplicationRequest<Request> {
        @Override
        public String toString() {
            return getTestClass().getName() + ".Request";
        }
    }

    static class Response extends ReplicationResponse {
    }

    /**
     * Transport channel that is needed for replica operation testing.
     */
    public TransportChannel transportChannel(final PlainActionFuture<Response> listener) {
        return new TransportChannel() {

            @Override
            public String getProfileName() {
                return "";
            }

            @Override
            public void sendResponse(TransportResponse response) throws IOException {
                listener.onResponse(((Response) response));
            }

            @Override
            public void sendResponse(Exception exception) throws IOException {
                listener.onFailure(exception);
            }

            @Override
            public String getChannelType() {
                return "replica_test";
            }
        };
    }
}
