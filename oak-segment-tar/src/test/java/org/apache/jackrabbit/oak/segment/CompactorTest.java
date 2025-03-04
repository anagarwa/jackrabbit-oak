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

import java.io.IOException;

import org.apache.jackrabbit.oak.Oak;
import org.apache.jackrabbit.oak.api.CommitFailedException;
import org.apache.jackrabbit.oak.segment.SegmentStore;
import org.apache.jackrabbit.oak.segment.memory.MemoryStore;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.EmptyHook;
import org.apache.jackrabbit.oak.spi.security.OpenSecurityProvider;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.junit.After;
import org.junit.Before;

public class CompactorTest {

    private SegmentStore segmentStore;

    @Before
    public void openSegmentStore() throws IOException {
        segmentStore = new MemoryStore();
    }

    @After
    public void closeSegmentStore() {
        segmentStore.close();
    }

    private NodeState addChild(NodeState current, String name) {
        NodeBuilder builder = current.builder();
        builder.child(name);
        return builder.getNodeState();
    }

    private static void init(NodeStore store) {
        new Oak(store).with(new OpenSecurityProvider())
                .createContentRepository();
    }

    private static void addTestContent(NodeStore store, int index)
            throws CommitFailedException {
        NodeBuilder builder = store.getRoot().builder();
        builder.child("test" + index);
        builder.child("child" + index);
        store.merge(builder, EmptyHook.INSTANCE, CommitInfo.EMPTY);
    }

}
