/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.jackrabbit.oak.segment;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Iterables.get;
import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Sets.newConcurrentHashSet;
import static com.google.common.util.concurrent.Futures.addCallback;
import static com.google.common.util.concurrent.Futures.immediateCancelledFuture;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;
import static java.lang.Boolean.getBoolean;
import static java.lang.Integer.MAX_VALUE;
import static java.lang.String.valueOf;
import static java.lang.System.getProperty;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.commons.lang.RandomStringUtils.randomAlphabetic;
import static org.junit.Assume.assumeTrue;
import static org.slf4j.helpers.MessageFormatter.arrayFormat;
import static org.slf4j.helpers.MessageFormatter.format;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.management.ManagementFactory;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectName;

import com.google.common.base.Predicate;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListenableScheduledFuture;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import org.apache.jackrabbit.oak.api.Blob;
import org.apache.jackrabbit.oak.api.CommitFailedException;
import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.cache.CacheStats;
import org.apache.jackrabbit.oak.commons.jmx.AnnotatedStandardMBean;
import org.apache.jackrabbit.oak.plugins.commit.ConflictHook;
import org.apache.jackrabbit.oak.plugins.commit.DefaultConflictHandler;
import org.apache.jackrabbit.oak.segment.compaction.SegmentGCOptions;
import org.apache.jackrabbit.oak.segment.compaction.SegmentRevisionGC;
import org.apache.jackrabbit.oak.segment.compaction.SegmentRevisionGCMBean;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.file.FileStoreGCMonitor;
import org.apache.jackrabbit.oak.segment.file.GCMonitorMBean;
import org.apache.jackrabbit.oak.spi.commit.CommitHook;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.CompositeHook;
import org.apache.jackrabbit.oak.spi.gc.GCMonitor;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.apache.jackrabbit.oak.spi.whiteboard.CompositeRegistration;
import org.apache.jackrabbit.oak.spi.whiteboard.Registration;
import org.apache.jackrabbit.oak.stats.Clock;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>This is a longevity test for SegmentMK compaction for {@code OAK-2849 Improve revision gc on SegmentMK}</p>
 *
 * <p>The test schedules a number of readers, writers, a compactor and holds some references for a certain time.
 * All of which can be interactively modified through the accompanying
 * {@link SegmentCompactionITMBean}, the
 * {@link SegmentRevisionGC} and the
 * {@link GCMonitorMBean}.</p>
 *
 *<p>The test is <b>disabled</b> by default, to run it you need to set the {@code SegmentCompactionIT} system property:<br>
 * {@code mvn test -Dtest=SegmentCompactionIT -Dtest.opts.memory=-Xmx4G}
 * </p>
 *
 * <p>TODO Leverage longevity test support from OAK-2771 once we have it.</p>
 */
public class SegmentCompactionIT {

    /** Only run if explicitly asked to via -Dtest=SegmentCompactionIT */
    private static final boolean ENABLED =
            SegmentCompactionIT.class.getSimpleName().equals(getProperty("test"));

    private static final Logger LOG = LoggerFactory.getLogger(SegmentCompactionIT.class);

    private final MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();

    private final Random rnd = new Random();
    private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(50);
    private final ListeningScheduledExecutorService scheduler = listeningDecorator(executor);
    private final FileStoreGCMonitor fileStoreGCMonitor = new FileStoreGCMonitor(Clock.SIMPLE);
    private final TestGCMonitor gcMonitor = new TestGCMonitor(fileStoreGCMonitor);
    private final Set<ListenableScheduledFuture<?>> writers = newConcurrentHashSet();
    private final Set<ListenableScheduledFuture<?>> readers = newConcurrentHashSet();
    private final Set<ListenableScheduledFuture<?>> references = newConcurrentHashSet();
    private final SegmentCompactionITMBean segmentCompactionMBean = new SegmentCompactionITMBean();

    private FileStore fileStore;
    private SegmentNodeStore nodeStore;
    private Registration mBeanRegistration;

    private volatile ListenableFuture<?> compactor = immediateCancelledFuture();
    private volatile ReadWriteLock compactionLock = null;
    private volatile int lockWaitTime = 60;
    private volatile int maxReaders = 10;
    private volatile int maxWriters = 10;
    private volatile long maxStoreSize = 200000000000L;
    private volatile int maxBlobSize = 1000000;
    private volatile int maxStringSize = 10000;
    private volatile int maxReferences = 10;
    private volatile int maxWriteOps = 10000;
    private volatile int maxNodeCount = 1000;
    private volatile int maxPropertyCount = 1000;
    private volatile int nodeRemoveRatio = 10;
    private volatile int propertyRemoveRatio = 10;
    private volatile int nodeAddRatio = 40;
    private volatile int addStringRatio = 20;
    private volatile int addBinaryRatio = 20;
    private volatile int compactionInterval = 1;
    private volatile boolean stopping;
    private volatile Reference rootReference;
    private volatile long fileStoreSize;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    public synchronized void stop() {
        stopping = true;
        notifyAll();
    }

    public void addReaders(int count) {
        for (int c = 0; c < count; c++) {
            scheduleReader();
        }
    }

    public void removeReaders(int count) {
        remove(readers, count);
    }

    public void addWriters(int count) {
        for (int c = 0; c < count; c++) {
            scheduleWriter();
        }
    }

    public void removeWriters(int count) {
        remove(writers, count);
    }

    public void removeReferences(int count) {
        remove(references, count);
    }

    private static void remove(Set<ListenableScheduledFuture<?>> ops, int count) {
        Iterator<ListenableScheduledFuture<?>> it = ops.iterator();
        while (it.hasNext() && count-- > 0) {
            it.next().cancel(false);
        }
    }

    private Registration registerMBean(Object mBean, final ObjectName objectName)
            throws NotCompliantMBeanException, InstanceAlreadyExistsException,
            MBeanRegistrationException {
        mBeanServer.registerMBean(mBean, objectName);
        return new Registration(){
            @Override
            public void unregister() {
                try {
                    mBeanServer.unregisterMBean(objectName);
                } catch (Exception e) {
                    LOG.error("Error unregistering Segment Compaction MBean", e);
                }
            }
        };
    }

    @Before
    public void setUp() throws IOException, MalformedObjectNameException, NotCompliantMBeanException,
            InstanceAlreadyExistsException, MBeanRegistrationException {
        assumeTrue(ENABLED);

        scheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                fileStoreGCMonitor.run();
            }
        }, 1, 1, SECONDS);

        SegmentGCOptions gcOptions = SegmentGCOptions.DEFAULT.setLockWaitTime(lockWaitTime);
        fileStore = FileStore.builder(folder.getRoot())
                .withMemoryMapping(true)
                .withGCMonitor(gcMonitor)
                .withGCOptions(gcOptions)
                .build();
        nodeStore = SegmentNodeStore.builder(fileStore).build();

        CacheStats segmentCacheStats = fileStore.getSegmentCacheStats();
        CacheStats stringCacheStats = fileStore.getReader().getStringCacheStats();
        List<Registration> registrations = newArrayList();
        registrations.add(registerMBean(segmentCompactionMBean,
                new ObjectName("IT:TYPE=Segment Compaction")));
        registrations.add(registerMBean(new SegmentRevisionGCMBean(gcOptions),
                new ObjectName("IT:TYPE=Segment Revision GC")));
        registrations.add(registerMBean(fileStoreGCMonitor,
                new ObjectName("IT:TYPE=GC Monitor")));
        registrations.add(registerMBean(segmentCacheStats, new ObjectName("IT:TYPE=" + segmentCacheStats.getName())));
        if (stringCacheStats != null) {
            registrations.add(registerMBean(stringCacheStats,
                    new ObjectName("IT:TYPE=" + stringCacheStats.getName())));
        }
        mBeanRegistration = new CompositeRegistration(registrations);
    }

    @After
    public void tearDown() {
        if (mBeanRegistration != null) {
            mBeanRegistration.unregister();
        }
        remove(writers, MAX_VALUE);
        remove(readers, MAX_VALUE);
        remove(references, MAX_VALUE);
        scheduler.shutdown();
        if (fileStore != null) {
            fileStore.close();
        }
    }

    @Test
    public void run() throws InterruptedException {
        scheduleSizeMonitor();
        scheduleCompactor();
        addReaders(maxReaders);
        addWriters(maxWriters);

        synchronized (this) {
            while (!stopping) {
                wait();
            }
        }
    }

    private void scheduleSizeMonitor() {
        scheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                fileStoreSize = fileStore.size();
            }
        }, 1, 1, MINUTES);
    }

    private synchronized void scheduleCompactor() {
        LOG.info("Scheduling compaction after {} minutes", compactionInterval);
        compactor.cancel(false);
        compactor = scheduler.schedule((new Compactor(fileStore, gcMonitor)), compactionInterval, MINUTES);
        addCallback(compactor, new FutureCallback<Object>() {
            @Override
            public void onSuccess(Object result) {
                scheduleCompactor();
            }

            @Override
            public void onFailure(Throwable t) {
                segmentCompactionMBean.error("Compactor error", t);
            }
        });
    }

    private void scheduleWriter() {
        if (writers.size() < maxWriters) {
            final RandomWriter writer = new RandomWriter(rnd, nodeStore, rnd.nextInt(maxWriteOps), "W" + rnd.nextInt(5));
            final ListenableScheduledFuture<Void> futureWriter = scheduler.schedule(
                    writer, rnd.nextInt(30), SECONDS);
            writers.add(futureWriter);
            addCallback(futureWriter, new FutureCallback<Void>() {
                @Override
                public void onSuccess(Void result) {
                    writers.remove(futureWriter);
                    if (!futureWriter.isCancelled()) {
                        scheduleWriter();
                    }
                }

                @Override
                public void onFailure(Throwable t) {
                    writer.cancel();
                    writers.remove(futureWriter);
                    segmentCompactionMBean.error("Writer error", t);
                }
            });
        }
    }

    private void scheduleReader() {
        if (readers.size() < maxReaders) {
            final RandomReader<?> reader = rnd.nextBoolean()
                ? new RandomNodeReader(rnd, nodeStore)
                : new RandomPropertyReader(rnd, nodeStore);
            final ListenableScheduledFuture<?> futureReader = scheduler.schedule(
                    reader, rnd.nextInt(30), SECONDS);
            readers.add(futureReader);
            addCallback(futureReader, new FutureCallback<Object>() {
                @Override
                public void onSuccess(Object node) {
                    readers.remove(futureReader);
                    if (!futureReader.isCancelled()) {
                        if (rnd.nextBoolean()) {
                            scheduleReference(node);
                        } else {
                            scheduleReader();
                        }
                    }
                }

                @Override
                public void onFailure(Throwable t) {
                    reader.cancel();
                    readers.remove(futureReader);
                    segmentCompactionMBean.error("Node reader error", t);
                }
            });
        }
    }

    private void scheduleReference(Object object) {
        if (references.size() < maxReferences) {
            final Reference reference = new Reference(object);
            final ListenableScheduledFuture<?> futureReference = scheduler.schedule(
                    reference, rnd.nextInt(600), SECONDS);
            references.add(futureReference);
            addCallback(futureReference, new FutureCallback<Object>() {
                @Override
                public void onSuccess(Object result) {
                    references.remove(reference);
                    if (!futureReference.isCancelled()) {
                        scheduleReader();
                    }
                }

                @Override
                public void onFailure(Throwable t) {
                    reference.run();
                    references.remove(reference);
                    segmentCompactionMBean.error("Reference error", t);
                }
            });
        } else {
            scheduleReader();
        }
    }

    private class RandomWriter implements Callable<Void> {
        private final Random rnd;
        private final NodeStore nodeStore;
        private final int opCount;
        private final String itemPrefix;

        private volatile boolean cancelled;

        RandomWriter(Random rnd, NodeStore nodeStore, int opCount, String itemPrefix) {
            this.rnd = rnd;
            this.nodeStore = nodeStore;
            this.opCount = opCount;
            this.itemPrefix = itemPrefix;
        }


        public void cancel() {
            cancelled = true;
        }

        private <T> T run(Callable<T> thunk) throws Exception {
            ReadWriteLock lock = compactionLock;
            if (lock != null) {
                lock.readLock().lock();
                try {
                    return thunk.call();
                } finally {
                    lock.readLock().unlock();
                }
            } else {
                return thunk.call();
            }
        }

        @Override
        public Void call() throws Exception {
            return run(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    NodeBuilder root = nodeStore.getRoot().builder();
                    for (int k = 0; k < opCount && !cancelled; k++) {
                        modify(nodeStore, root);
                    }
                    if (!cancelled) {
                        try {
                            CommitHook commitHook = rnd.nextBoolean()
                                    ? new CompositeHook(new ConflictHook(DefaultConflictHandler.OURS))
                                    : new CompositeHook(new ConflictHook(DefaultConflictHandler.THEIRS));
                            nodeStore.merge(root, commitHook, CommitInfo.EMPTY);
                            segmentCompactionMBean.committed();
                        } catch (CommitFailedException e) {
                            LOG.warn("Commit failed: {}", e.getMessage());
                        }
                    }
                    return null;
                }
            });
        }

        private void modify(NodeStore nodeStore, NodeBuilder nodeBuilder) throws IOException {
            int p0 = nodeRemoveRatio;
            int p1 = p0 + propertyRemoveRatio;
            int p2 = p1 + nodeAddRatio;
            int p3 = p2 + addStringRatio;
            double p = p3 + addBinaryRatio;

            boolean deleteOnly = fileStoreSize > maxStoreSize;
            double k = rnd.nextDouble();
            if (k < p0/p) {
                chooseRandomNode(nodeBuilder).remove();
            } else if (k < p1/p) {
                removeRandomProperty(chooseRandomNode(nodeBuilder));
            } else if (k < p2/p && !deleteOnly)  {
                addRandomNode(nodeBuilder);
            } else if (k < p3/p && !deleteOnly) {
                addRandomValue(nodeBuilder);
            } else if (!deleteOnly) {
                addRandomBlob(nodeStore, nodeBuilder);
            }
        }

        private NodeBuilder chooseRandomNode(NodeBuilder nodeBuilder) {
            NodeBuilder childBuilder = nodeBuilder;
            for (int k = 0; k < rnd.nextInt(1000); k++) {
                childBuilder = randomStep(nodeBuilder, nodeBuilder = childBuilder);
            }
            return childBuilder;
        }

        private NodeBuilder chooseRandomNode(NodeBuilder nodeBuilder, Predicate<NodeBuilder> predicate) {
            NodeBuilder childBuilder = chooseRandomNode(nodeBuilder);
            while (!predicate.apply(childBuilder)) {
                childBuilder = randomStep(nodeBuilder, nodeBuilder = childBuilder);
            }
            return childBuilder;
        }

        private NodeBuilder randomStep(NodeBuilder parent, NodeBuilder node) {
            int count = (int) node.getChildNodeCount(Long.MAX_VALUE);
            int k = rnd.nextInt(count + 1);
            if (k == 0) {
                return parent;
            } else {
                String name = get(node.getChildNodeNames(), k - 1);
                return node.getChildNode(name);
            }
        }

        private void removeRandomProperty(NodeBuilder nodeBuilder) {
            int count = (int) nodeBuilder.getPropertyCount();
            if (count > 0) {
                PropertyState property = get(nodeBuilder.getProperties(), rnd.nextInt(count));
                nodeBuilder.removeProperty(property.getName());
            }
        }

        private void addRandomNode(NodeBuilder nodeBuilder) {
            chooseRandomNode(nodeBuilder, new Predicate<NodeBuilder>() {
                @Override
                public boolean apply(NodeBuilder builder) {
                    return builder.getChildNodeCount(maxNodeCount) < maxNodeCount;
                }
            }).setChildNode('N' + itemPrefix + rnd.nextInt(maxNodeCount));
        }

        private void addRandomValue(NodeBuilder nodeBuilder) {
            chooseRandomNode(nodeBuilder, new Predicate<NodeBuilder>() {
                @Override
                public boolean apply(NodeBuilder builder) {
                    return builder.getPropertyCount() < maxPropertyCount;
                }
            })
            .setProperty('P' + itemPrefix + rnd.nextInt(maxPropertyCount),
                    randomAlphabetic(rnd.nextInt(maxStringSize)));
        }

        private void addRandomBlob(NodeStore nodeStore, NodeBuilder nodeBuilder) throws IOException {
            chooseRandomNode(nodeBuilder, new Predicate<NodeBuilder>() {
                @Override
                public boolean apply(NodeBuilder builder) {
                    return builder.getPropertyCount() < maxPropertyCount;
                }
            })
            .setProperty('B' + itemPrefix + rnd.nextInt(maxPropertyCount),
                    createBlob(nodeStore, rnd.nextInt(maxBlobSize)));
        }

        private Blob createBlob(NodeStore nodeStore, int size) throws IOException {
            byte[] data = new byte[size];
            new Random().nextBytes(data);
            return nodeStore.createBlob(new ByteArrayInputStream(data));
        }
    }

    private abstract static class RandomReader<T> implements Callable<T> {
        protected final Random rnd;
        protected final NodeStore nodeStore;

        protected volatile boolean cancelled;

        RandomReader(Random rnd, NodeStore nodeStore) {
            this.rnd = rnd;
            this.nodeStore = nodeStore;
        }

        public void cancel() {
            cancelled = true;
        }

        private NodeState randomStep(NodeState parent, NodeState node) {
            int count = (int) node.getChildNodeCount(Long.MAX_VALUE);
            int k = rnd.nextInt(count + 1);
            if (k == 0) {
                return parent;
            } else {
                String name = get(node.getChildNodeNames(), k - 1);
                return node.getChildNode(name);
            }
        }

        protected final NodeState chooseRandomNode(NodeState parent) {
            NodeState child = parent;
            for (int k = 0; k < rnd.nextInt(1000) && !cancelled; k++) {
                child = randomStep(parent, parent = child);
            }
            return child;
        }

        protected final PropertyState chooseRandomProperty(NodeState node) {
            int count = (int) node.getPropertyCount();
            if (count > 0) {
                return get(node.getProperties(), rnd.nextInt(count));
            } else {
                return null;
            }
        }
    }

    private static class RandomNodeReader extends RandomReader<NodeState> {
        RandomNodeReader(Random rnd, NodeStore nodeStore) {
            super(rnd, nodeStore);
        }

        @Override
        public NodeState call() throws Exception {
            return chooseRandomNode(nodeStore.getRoot());
        }
    }

    private static class RandomPropertyReader extends RandomReader<PropertyState> {
        RandomPropertyReader(Random rnd, NodeStore nodeStore) {
            super(rnd, nodeStore);
        }

        @Override
        public PropertyState call() throws Exception {
            return chooseRandomProperty(chooseRandomNode(nodeStore.getRoot()));
        }
    }

    private static class Reference implements Runnable {
        private volatile Object referent;

        Reference(Object referent) {
            this.referent = referent;
        }

        @Override
        public void run() {
            referent = null;
        }
    }

    private class Compactor implements Runnable {
        private final FileStore fileStore;
        private final TestGCMonitor gcMonitor;

        Compactor(FileStore fileStore, TestGCMonitor gcMonitor) {
            this.fileStore = fileStore;
            this.gcMonitor = gcMonitor;
        }

        private <T> T run(Callable<T> thunk) throws Exception {
            ReadWriteLock lock = compactionLock;
            if (lock != null) {
                lock.writeLock().lock();
                try {
                    return thunk.call();
                } finally {
                    lock.writeLock().unlock();
                }
            } else {
                return thunk.call();
            }
        }

        @Override
        public void run() {
            if (gcMonitor.isCleaned()) {
                LOG.info("Running compaction");
                try {
                    run(new Callable<Void>() {
                        @Override
                        public Void call() throws Exception {
                            gcMonitor.resetCleaned();
                            fileStore.maybeCompact(true);
                            return null;
                        }
                    });
                } catch (Exception e) {
                    LOG.error("Error while running compaction", e);
                }
            } else {
                LOG.info("Not running compaction as no cleanup has taken place");
            }
        }
    }

    private static class TestGCMonitor implements GCMonitor {
        private final GCMonitor delegate;
        private volatile boolean cleaned = true;
        private volatile long lastCompacted;

        TestGCMonitor(GCMonitor delegate) {
            this.delegate = delegate;
        }

        @Override
        public void info(String message, Object... arguments) {
            System.out.println(arrayFormat(message, arguments).getMessage());
            delegate.info(message, arguments);
        }

        @Override
        public void warn(String message, Object... arguments) {
            System.out.println(arrayFormat(message, arguments).getMessage());
            delegate.warn(message, arguments);
        }

        @Override
        public void error(String message, Exception exception) {
            System.out.println(format(message, exception).getMessage());
            delegate.error(message, exception);
        }

        @Override
        public void skipped(String reason, Object... arguments) {
            cleaned = true;
            System.out.println(arrayFormat(reason, arguments).getMessage());
            delegate.skipped(reason, arguments);
        }

        @Override
        public void compacted(long[] segmentCounts, long[] recordCounts, long[] compactionMapWeights) {
            delegate.compacted(segmentCounts, recordCounts, compactionMapWeights);
            lastCompacted = System.currentTimeMillis();
        }

        @Override
        public void cleaned(long reclaimedSize, long currentSize) {
            cleaned = true;
            delegate.cleaned(reclaimedSize, currentSize);
        }

        public boolean isCleaned() {
            return cleaned;
        }

        public void resetCleaned() {
            cleaned = false;
        }

        public long getLastCompacted() {
            return lastCompacted;
        }
    }

    private class SegmentCompactionITMBean extends AnnotatedStandardMBean implements SegmentCompactionMBean {
        private final AtomicLong commitCount = new AtomicLong();

        private String lastError;

        SegmentCompactionITMBean() {
            super(SegmentCompactionMBean.class);
        }

        @Override
        public void stop() {
            SegmentCompactionIT.this.stop();
        }

        @Override
        public void setCorePoolSize(int corePoolSize) {
            executor.setCorePoolSize(corePoolSize);
        }

        @Override
        public int getCorePoolSize() {
            return executor.getCorePoolSize();
        }

        @Override
        public void setCompactionInterval(int minutes) {
            if (compactionInterval != minutes) {
                compactionInterval = minutes;
                scheduleCompactor();
            }
        }

        @Override
        public int getCompactionInterval() {
            return compactionInterval;
        }

        @Override
        public String getLastCompaction() {
            return valueOf(new Date(gcMonitor.getLastCompacted()));
        }

        @Override
        public void setUseCompactionLock(boolean value) {
            if (value && compactionLock == null) {
                compactionLock = new ReentrantReadWriteLock();
            } else {
                compactionLock = null;
            }
        }

        @Override
        public boolean getUseCompactionLock() {
            return compactionLock != null;
        }

        @Override
        public void setLockWaitTime(int seconds) {
            lockWaitTime = seconds;
        }

        @Override
        public int getLockWaitTime() {
            return lockWaitTime;
        }

        @Override
        public void setMaxReaders(int count) {
            checkArgument(count >= 0);
            maxReaders = count;
            if (count > readers.size()) {
                addReaders(count - readers.size());
            } else {
                removeReaders(readers.size() - count);
            }
        }

        @Override
        public int getMaxReaders() {
            return maxReaders;
        }

        @Override
        public void setMaxWriters(int count) {
            checkArgument(count >= 0);
            maxWriters = count;
            if (count > writers.size()) {
                addWriters(count - writers.size());
            } else {
                removeWriters(writers.size() - count);
            }
        }

        @Override
        public int getMaxWriters() {
            return maxWriters;
        }

        @Override
        public void setMaxStoreSize(long size) {
            maxStoreSize = size;
        }

        @Override
        public long getMaxStoreSize() {
            return maxStoreSize;
        }

        @Override
        public void setMaxStringSize(int size) {
            maxStringSize = size;
        }

        @Override
        public int getMaxStringSize() {
            return maxStringSize;
        }

        @Override
        public void setMaxBlobSize(int size) {
            maxBlobSize = size;
        }

        @Override
        public int getMaxBlobSize() {
            return maxBlobSize;
        }

        @Override
        public void setMaxReferences(int count) {
            checkArgument(count >= 0);
            maxReferences = count;
            if (count < references.size()) {
                removeReferences(references.size() - count);
            }
        }

        @Override
        public int getMaxReferences() {
            return maxReferences;
        }

        @Override
        public void setMaxWriteOps(int count) {
            checkArgument(count >= 0);
            maxWriteOps = count;
        }

        @Override
        public int getMaxWriteOps() {
            return maxWriteOps;
        }

        @Override
        public void setMaxNodeCount(int count) {
            checkArgument(count >= 0);
            maxNodeCount = count;
        }

        @Override
        public int getMaxNodeCount() {
            return maxNodeCount;
        }

        @Override
        public void setMaxPropertyCount(int count) {
            checkArgument(count >= 0);
            maxPropertyCount = count;
        }

        @Override
        public int getMaxPropertyCount() {
            return maxPropertyCount;
        }

        @Override
        public void setNodeRemoveRatio(int ratio) {
            nodeRemoveRatio = ratio;
        }

        @Override
        public int getNodeRemoveRatio() {
            return nodeRemoveRatio;
        }

        @Override
        public void setPropertyRemoveRatio(int ratio) {
            propertyRemoveRatio = ratio;
        }

        @Override
        public int getPropertyRemoveRatio() {
            return propertyRemoveRatio;
        }

        @Override
        public void setNodeAddRatio(int ratio) {
            nodeAddRatio = ratio;
        }

        @Override
        public int getNodeAddRatio() {
            return nodeAddRatio;
        }

        @Override
        public void setAddStringRatio(int ratio) {
            addStringRatio = ratio;
        }

        @Override
        public int getAddStringRatio() {
            return addStringRatio;
        }

        @Override
        public void setAddBinaryRatio(int ratio) {
            addBinaryRatio = ratio;
        }

        @Override
        public int getAddBinaryRatio() {
            return addBinaryRatio;
        }

        @Override
        public void setRootReference(boolean set) {
            if (set && rootReference == null) {
                rootReference = new Reference(nodeStore.getRoot());
            } else {
                rootReference = null;
            }
        }

        @Override
        public boolean getRootReference() {
            return rootReference != null;
        }

        @Override
        public int getReaderCount() {
            return readers.size();
        }

        @Override
        public int getWriterCount() {
            return writers.size();
        }

        @Override
        public int getReferenceCount() {
            return references.size();
        }

        @Override
        public long getFileStoreSize() {
            return fileStoreSize;
        }

        @Override
        public String getLastError() {
            return lastError;
        }

        @Override
        public long getCommitCount() {
            return commitCount.get();
        }

        void error(String message, Throwable t) {
            if (!(t instanceof CancellationException)) {
                StringWriter sw = new StringWriter();
                sw.write(message + ": ");
                t.printStackTrace(new PrintWriter(sw));
                lastError = sw.toString();

                LOG.error(message, t);
            }
        }

        void committed() {
            commitCount.incrementAndGet();
        }
    }
}
