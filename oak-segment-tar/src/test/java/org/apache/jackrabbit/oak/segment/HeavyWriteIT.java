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

import static org.apache.jackrabbit.oak.commons.CIHelper.travis;
import static org.apache.jackrabbit.oak.commons.FixturesHelper.Fixture.SEGMENT_MK;
import static org.junit.Assume.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.jackrabbit.oak.api.Blob;
import org.apache.jackrabbit.oak.api.CommitFailedException;
import org.apache.jackrabbit.oak.commons.FixturesHelper;
import org.apache.jackrabbit.oak.commons.FixturesHelper.Fixture;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.EmptyHook;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class HeavyWriteIT {
    private static final Set<Fixture> FIXTURES = FixturesHelper.getFixtures();

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File getFileStoreFolder() {
        return folder.getRoot();
    }

    @BeforeClass
    public static void checkFixtures() {
        assumeTrue(!travis());  // FIXME OAK-2375. Often fails on Travis
        assumeTrue(FIXTURES.contains(SEGMENT_MK));
    }

    @Test
    public void heavyWrite() throws IOException, CommitFailedException, InterruptedException {
        final FileStore store = FileStore.builder(getFileStoreFolder()).withMaxFileSize(128).withMemoryMapping(false).build();
        final SegmentNodeStore nodeStore = SegmentNodeStore.builder(store).build();

        int writes = 100;
        final AtomicBoolean run = new AtomicBoolean(true);
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                for (int k = 1; run.get(); k++) {
                    store.gc();
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        });
        thread.start();

        try {
            for (int k = 1; k<=writes; k++) {
                NodeBuilder root = nodeStore.getRoot().builder();
                NodeBuilder test = root.setChildNode("test");
                createNodes(nodeStore, test, 10, 2);
                nodeStore.merge(root, EmptyHook.INSTANCE, CommitInfo.EMPTY);

                root = nodeStore.getRoot().builder();
                root.getChildNode("test").remove();
                nodeStore.merge(root, EmptyHook.INSTANCE, CommitInfo.EMPTY);
            }

        } finally {
            run.set(false);
            thread.join();
            store.close();
        }
    }

    private static void createNodes(NodeStore nodeStore, NodeBuilder builder, int count, int depth) throws IOException {
        if (depth > 0) {
            for (int k = 0; k < count; k++) {
                NodeBuilder child = builder.setChildNode("node" + k);
                createProperties(nodeStore, child, count);
                createNodes(nodeStore, child, count, depth - 1);
            }
        }
    }

    private static void createProperties(NodeStore nodeStore, NodeBuilder builder, int count) throws IOException {
        for (int k = 0; k < count; k++) {
            builder.setProperty("property-" + k, createBlob(nodeStore, 100000));
        }
    }

    private static Blob createBlob(NodeStore nodeStore, int size) throws IOException {
        byte[] data = new byte[size];
        new Random().nextBytes(data);
        return nodeStore.createBlob(new ByteArrayInputStream(data));
    }

}
