/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.jackrabbit.oak.fixture;

import java.io.File;

import org.apache.commons.io.FileUtils;
import org.apache.jackrabbit.oak.Oak;
import org.apache.jackrabbit.oak.plugins.memory.EmptyNodeState;
import org.apache.jackrabbit.oak.segment.SegmentNodeStore;
import org.apache.jackrabbit.oak.segment.SegmentStore;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.spi.blob.BlobStore;

class SegmentTarFixture extends OakFixture {

    private FileStore[] stores;

    private BlobStoreFixture[] blobStoreFixtures = new BlobStoreFixture[0];

    private final File base;

    private final int maxFileSizeMB;

    private final int cacheSizeMB;

    private final boolean memoryMapping;

    private final boolean useBlobStore;

    public SegmentTarFixture(String name, File base, int maxFileSizeMB, int cacheSizeMB, boolean memoryMapping, boolean useBlobStore) {
        super(name);
        this.base = base;
        this.maxFileSizeMB = maxFileSizeMB;
        this.cacheSizeMB = cacheSizeMB;
        this.memoryMapping = memoryMapping;
        this.useBlobStore = useBlobStore;
    }

    @Override
    public Oak getOak(int clusterId) throws Exception {
        FileStore fs = FileStore.builder(base)
                .withMaxFileSize(maxFileSizeMB)
                .withCacheSize(cacheSizeMB)
                .withMemoryMapping(memoryMapping)
                .build();
        return newOak(SegmentNodeStore.builder(fs).build());
    }

    @Override
    public Oak[] setUpCluster(int n) throws Exception {
        Oak[] cluster = new Oak[n];
        stores = new FileStore[cluster.length];
        if (useBlobStore) {
            blobStoreFixtures = new BlobStoreFixture[cluster.length];
        }

        for (int i = 0; i < cluster.length; i++) {
            BlobStore blobStore = null;
            if (useBlobStore) {
                blobStoreFixtures[i] = BlobStoreFixture.create(base, true);
                blobStore = blobStoreFixtures[i].setUp();
            }

            FileStore.Builder builder = FileStore.builder(new File(base, unique));
            if (blobStore != null) {
                builder.withBlobStore(blobStore);
            }
            stores[i] = builder.withRoot(EmptyNodeState.EMPTY_NODE)
                    .withMaxFileSize(maxFileSizeMB)
                    .withCacheSize(cacheSizeMB)
                    .withMemoryMapping(memoryMapping)
                    .build();
            cluster[i] = newOak(SegmentNodeStore.builder(stores[i]).build());
        }
        return cluster;
    }

    @Override
    public void tearDownCluster() {
        for (SegmentStore store : stores) {
            store.close();
        }
        for (BlobStoreFixture blobStore : blobStoreFixtures) {
            blobStore.tearDown();
        }
        FileUtils.deleteQuietly(new File(base, unique));
    }

    public BlobStoreFixture[] getBlobStoreFixtures() {
        return blobStoreFixtures;
    }

    public FileStore[] getStores() {
        return stores;
    }

}
