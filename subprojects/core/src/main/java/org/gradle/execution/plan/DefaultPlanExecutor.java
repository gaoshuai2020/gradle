/*
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.execution.plan;

import org.gradle.api.Action;
import org.gradle.api.NonNullApi;
import org.gradle.api.Transformer;
import org.gradle.concurrent.ParallelismConfiguration;
import org.gradle.initialization.BuildCancellationToken;
import org.gradle.internal.MutableBoolean;
import org.gradle.internal.MutableReference;
import org.gradle.internal.concurrent.ExecutorFactory;
import org.gradle.internal.concurrent.ManagedExecutor;
import org.gradle.internal.resources.ResourceLockCoordinationService;
import org.gradle.internal.resources.ResourceLockState;
import org.gradle.internal.time.Time;
import org.gradle.internal.time.TimeFormatting;
import org.gradle.internal.time.Timer;
import org.gradle.internal.work.WorkerLeaseRegistry.WorkerLease;
import org.gradle.internal.work.WorkerLeaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

import static org.gradle.internal.resources.DefaultResourceLockCoordinationService.unlock;
import static org.gradle.internal.resources.ResourceLockState.Disposition.FINISHED;
import static org.gradle.internal.resources.ResourceLockState.Disposition.RETRY;

@NonNullApi
public class DefaultPlanExecutor implements PlanExecutor {

    public static class Stats {
        public final String executorName;
        public final long busyTime;
        public final long idleTime;
        public final long waitTime;

        Stats(String executorName, long busyTime, long idleTime, long waitTime) {
            this.executorName = executorName;
            this.busyTime = busyTime;
            this.idleTime = idleTime;
            this.waitTime = waitTime;
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultPlanExecutor.class);
    private final int executorCount;
    private final ExecutorFactory executorFactory;
    private final WorkerLeaseService workerLeaseService;
    private final BuildCancellationToken cancellationToken;
    private final ResourceLockCoordinationService coordinationService;
    private final List<Stats> stats = new ArrayList<Stats>();

    public DefaultPlanExecutor(ParallelismConfiguration parallelismConfiguration, ExecutorFactory executorFactory, WorkerLeaseService workerLeaseService, BuildCancellationToken cancellationToken, ResourceLockCoordinationService coordinationService) {
        this.executorFactory = executorFactory;
        this.cancellationToken = cancellationToken;
        this.coordinationService = coordinationService;
        int numberOfParallelExecutors = parallelismConfiguration.getMaxWorkerCount();
        if (numberOfParallelExecutors < 1) {
            throw new IllegalArgumentException("Not a valid number of parallel executors: " + numberOfParallelExecutors);
        }

        this.executorCount = numberOfParallelExecutors;
        this.workerLeaseService = workerLeaseService;
    }

    public List<Stats> getStats() {
        return stats;
    }

    @Override
    public void process(ExecutionPlan executionPlan, Collection<? super Throwable> failures, Action<Node> nodeExecutor) {
        ManagedExecutor executor = executorFactory.create("Execution worker for '" + executionPlan.getDisplayName() + "'");
        try {
            WorkerLease parentWorkerLease = workerLeaseService.getCurrentWorkerLease();
            ExecutorQueuer queuer = new ExecutorQueuer(executionPlan, cancellationToken, coordinationService);
            startWorkers(executionPlan, nodeExecutor, executor, parentWorkerLease);
            queuer.run();
            awaitCompletion(executionPlan, failures);
        } finally {
            executor.stop();
        }
    }

    /**
     * Blocks until all nodes in the plan have been processed. This method will only return when every node in the plan has either completed, failed or been skipped.
     */
    private void awaitCompletion(final ExecutionPlan executionPlan, final Collection<? super Throwable> failures) {
        coordinationService.withStateLock(new Transformer<ResourceLockState.Disposition, ResourceLockState>() {
            @Override
            public ResourceLockState.Disposition transform(ResourceLockState resourceLockState) {
                if (executionPlan.allNodesComplete()) {
                    executionPlan.collectFailures(failures);
                    return FINISHED;
                } else {
                    return RETRY;
                }
            }
        });
    }

    private void startWorkers(ExecutionPlan executionPlan, Action<? super Node> nodeExecutor, Executor executor, WorkerLease parentWorkerLease) {
        LOGGER.debug("Using {} parallel executor threads", executorCount);
        for (int i = 0; i < executorCount; i++) {
            executor.execute(new ExecutorWorker(executionPlan, nodeExecutor, parentWorkerLease, cancellationToken, coordinationService));
        }
    }

    private class ExecutorQueuer implements Runnable {
        private final ExecutionPlan executionPlan;
        private final BuildCancellationToken cancellationToken;
        private final ResourceLockCoordinationService coordinationService;

        private ExecutorQueuer(ExecutionPlan executionPlan, BuildCancellationToken cancellationToken, ResourceLockCoordinationService coordinationService) {
            this.executionPlan = executionPlan;
            this.cancellationToken = cancellationToken;
            this.coordinationService = coordinationService;
        }

        @Override
        public void run() {
            final AtomicLong wait = new AtomicLong(0);
            Timer totalTimer = Time.startTimer();
            while (!queueNodes(wait)) {
            }
            long total = totalTimer.getElapsedMillis();
            recordStatsFor(Thread.currentThread(), 0, total - wait.get(), wait.get());
        }

        private boolean queueNodes(final AtomicLong wait) {
            final MutableBoolean allNodesQueued = new MutableBoolean();
            final Timer waitTimer = Time.startTimer();

            // TODO: Gary said we may be able to get rid of this coordinationService lock
            coordinationService.withStateLock(new Transformer<ResourceLockState.Disposition, ResourceLockState>() {
                @Override
                public ResourceLockState.Disposition transform(ResourceLockState resourceLockState) {
                    if (cancellationToken.isCancellationRequested()) {
                        executionPlan.cancelExecution();
                    }

                    try {
                        waitTimer.reset();
                        executionPlan.populateReadyQueue();
                        allNodesQueued.set(executionPlan.allNodesQueued());
                        wait.addAndGet(waitTimer.getElapsedMillis());
                        coordinationService.notifyStateChange();
                    } catch (Throwable t) {
                        resourceLockState.releaseLocks();
                        executionPlan.abortAllAndFail(t);
                        allNodesQueued.set(true);
                    }

                    return allNodesQueued.get()
                        ? FINISHED
                        : RETRY;
                }
            });

            return allNodesQueued.get();
        }
    }

    private class ExecutorWorker implements Runnable {
        private final ExecutionPlan executionPlan;
        private final Action<? super Node> nodeExecutor;
        private final WorkerLease parentWorkerLease;
        private final BuildCancellationToken cancellationToken;
        private final ResourceLockCoordinationService coordinationService;

        private ExecutorWorker(ExecutionPlan executionPlan, Action<? super Node> nodeExecutor, WorkerLease parentWorkerLease, BuildCancellationToken cancellationToken, ResourceLockCoordinationService coordinationService) {
            this.executionPlan = executionPlan;
            this.nodeExecutor = nodeExecutor;
            this.parentWorkerLease = parentWorkerLease;
            this.cancellationToken = cancellationToken;
            this.coordinationService = coordinationService;
        }

        @Override
        public void run() {
            final AtomicLong busy = new AtomicLong(0);
            final AtomicLong wait = new AtomicLong(0);
            Timer totalTimer = Time.startTimer();
            final Timer executionTimer = Time.startTimer();

            WorkerLease childLease = parentWorkerLease.createChild();
            while (true) {
                boolean nodesRemaining = executeNextNode(childLease, wait, new Action<Node>() {
                    @Override
                    public void execute(Node work) {
                        LOGGER.info("{} ({}) started.", work, Thread.currentThread());
                        executionTimer.reset();
                        nodeExecutor.execute(work);
                        long duration = executionTimer.getElapsedMillis();
                        busy.addAndGet(duration);
                        if (LOGGER.isInfoEnabled()) {
                            LOGGER.info("{} ({}) completed. Took {}.", work, Thread.currentThread(), TimeFormatting.formatDurationVerbose(duration));
                        }
                    }
                });
                if (!nodesRemaining) {
                    break;
                }
            }

            long total = totalTimer.getElapsedMillis();

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Execution worker [{}] finished, busy: {}, idle: {}", Thread.currentThread(), TimeFormatting.formatDurationVerbose(busy.get()), TimeFormatting.formatDurationVerbose(total - busy.get()));
            }

            recordStatsFor(Thread.currentThread(), busy.get(), total - busy.get() - wait.get(), wait.get());
        }

        /**
         * Selects a node that's ready to execute and executes the provided action against it. If no node is ready, blocks until some
         * can be executed.
         *
         * @return {@code true} if there are more nodes waiting to execute, {@code false} if all nodes have been executed.
         */
        private boolean executeNextNode(final WorkerLease workerLease, final AtomicLong wait, final Action<Node> nodeExecutor) {
            final MutableReference<Node> selected = MutableReference.empty();
            final MutableBoolean nodesRemaining = new MutableBoolean();
            final Timer waitTimer = Time.startTimer();

            coordinationService.withStateLock(new Transformer<ResourceLockState.Disposition, ResourceLockState>() {
                @Override
                public ResourceLockState.Disposition transform(ResourceLockState resourceLockState) {
                    if (cancellationToken.isCancellationRequested()) {
                        executionPlan.cancelExecution();
                    }

                    nodesRemaining.set(executionPlan.hasNodesRemaining());
                    if (!nodesRemaining.get()) {
                        return FINISHED;
                    }

                    try {
                        waitTimer.reset();
                        selected.set(executionPlan.selectNext(workerLease, resourceLockState));
                        wait.addAndGet(waitTimer.getElapsedMillis());
                    } catch (Throwable t) {
                        resourceLockState.releaseLocks();
                        executionPlan.abortAllAndFail(t);
                        nodesRemaining.set(false);
                    }

                    if (selected.get() == null && nodesRemaining.get()) {
                        return RETRY;
                    } else {
                        return FINISHED;
                    }
                }
            });

            Node selectedNode = selected.get();
            if (selectedNode != null) {
                execute(selectedNode, workerLease, nodeExecutor);
            }
            return nodesRemaining.get();
        }

        private void execute(final Node selected, final WorkerLease workerLease, Action<Node> nodeExecutor) {
            try {
                if (!selected.isComplete()) {
                    try {
                        nodeExecutor.execute(selected);
                    } catch (Throwable e) {
                        selected.setExecutionFailure(e);
                    }
                }
            } finally {
                coordinationService.withStateLock(new Transformer<ResourceLockState.Disposition, ResourceLockState>() {
                    @Override
                    public ResourceLockState.Disposition transform(ResourceLockState state) {
                        executionPlan.nodeComplete(selected);
                        return unlock(workerLease).transform(state);
                    }
                });
            }
        }
    }

    private void recordStatsFor(Thread executorThread, long busy, long idle, long waitTime) {
        synchronized (stats) {
            stats.add(new Stats(executorThread.getName(), busy, idle, waitTime));
        }
    }
}
