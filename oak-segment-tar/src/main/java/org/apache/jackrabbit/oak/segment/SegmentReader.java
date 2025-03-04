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

import javax.annotation.Nonnull;

import org.apache.jackrabbit.oak.cache.CacheStats;

public interface SegmentReader {
    @Nonnull
    String readString(@Nonnull RecordId id);

    @Nonnull
    MapRecord readMap(@Nonnull RecordId id);

    @Nonnull
    Template readTemplate(@Nonnull RecordId id);

    // FIXME OAK-4373 remove from this interface
    @Nonnull
    CacheStats getStringCacheStats();
}
