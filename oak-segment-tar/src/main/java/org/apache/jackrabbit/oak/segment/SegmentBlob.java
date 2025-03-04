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

import static com.google.common.base.Charsets.UTF_8;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Sets.newHashSet;
import static java.util.Collections.emptySet;
import static org.apache.jackrabbit.oak.segment.Segment.MEDIUM_LIMIT;
import static org.apache.jackrabbit.oak.segment.Segment.SMALL_LIMIT;
import static org.apache.jackrabbit.oak.segment.SegmentWriter.BLOCK_SIZE;

import java.io.InputStream;
import java.util.List;
import java.util.Set;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import org.apache.jackrabbit.oak.api.Blob;
import org.apache.jackrabbit.oak.plugins.memory.AbstractBlob;
import org.apache.jackrabbit.oak.spi.blob.BlobStore;

/**
 * A BLOB (stream of bytes). This is a record of type "VALUE".
 */
public class SegmentBlob extends Record implements Blob {

    @Nonnull
    private final SegmentStore store;

    public static Iterable<SegmentId> getBulkSegmentIds(Blob blob) {
        if (blob instanceof SegmentBlob) {
            return ((SegmentBlob) blob).getBulkSegmentIds();
        } else {
            return emptySet();
        }
    }

    SegmentBlob(@Nonnull SegmentStore store, @Nonnull RecordId id) {
        super(id);
        this.store = checkNotNull(store);
    }

    private InputStream getInlineStream(
            Segment segment, int offset, int length) {
        byte[] inline = new byte[length];
        segment.readBytes(offset, inline, 0, length);
        return new SegmentStream(getRecordId(), inline);
    }

    @Override @Nonnull
    public InputStream getNewStream() {
        Segment segment = getSegment();
        int offset = getOffset();
        byte head = segment.readByte(offset);
        if ((head & 0x80) == 0x00) {
            // 0xxx xxxx: small value
            return getInlineStream(segment, offset + 1, head);
        } else if ((head & 0xc0) == 0x80) {
            // 10xx xxxx: medium value
            int length = (segment.readShort(offset) & 0x3fff) + SMALL_LIMIT;
            return getInlineStream(segment, offset + 2, length);
        } else if ((head & 0xe0) == 0xc0) {
            // 110x xxxx: long value
            long length = (segment.readLong(offset) & 0x1fffffffffffffffL) + MEDIUM_LIMIT;
            int listSize = (int) ((length + BLOCK_SIZE - 1) / BLOCK_SIZE);
            ListRecord list = new ListRecord(
                    segment.readRecordId(offset + 8), listSize);
            return new SegmentStream(getRecordId(), list, length);
        } else if ((head & 0xf0) == 0xe0) {
            // 1110 xxxx: external value, short blob ID
            return getNewStream(readShortBlobId(segment, offset, head));
        } else if ((head & 0xf8) == 0xf0) {
            // 1111 0xxx: external value, long blob ID
            return getNewStream(readLongBlobId(store, segment, offset));
        } else {
            throw new IllegalStateException(String.format(
                    "Unexpected value record type: %02x", head & 0xff));
        }
    }

    @Override
    public long length() {
        Segment segment = getSegment();
        int offset = getOffset();
        byte head = segment.readByte(offset);
        if ((head & 0x80) == 0x00) {
            // 0xxx xxxx: small value
            return head;
        } else if ((head & 0xc0) == 0x80) {
            // 10xx xxxx: medium value
            return (segment.readShort(offset) & 0x3fff) + SMALL_LIMIT;
        } else if ((head & 0xe0) == 0xc0) {
            // 110x xxxx: long value
            return (segment.readLong(offset) & 0x1fffffffffffffffL) + MEDIUM_LIMIT;
        } else if ((head & 0xf0) == 0xe0) {
            // 1110 xxxx: external value, short blob ID
            return getLength(readShortBlobId(segment, offset, head));
        } else if ((head & 0xf8) == 0xf0) {
            // 1111 0xxx: external value, long blob ID
            return getLength(readLongBlobId(store, segment, offset));
        } else {
            throw new IllegalStateException(String.format(
                    "Unexpected value record type: %02x", head & 0xff));
        }
    }

    @Override
    @CheckForNull
    public String getReference() {
        String blobId = getBlobId();
        if (blobId != null) {
            BlobStore blobStore = store.getBlobStore();
            if (blobStore != null) {
                return blobStore.getReference(blobId);
            } else {
                throw new IllegalStateException("Attempt to read external blob with blobId [" + blobId + "] " +
                        "without specifying BlobStore");
            }
        }
        return null;
    }


    @Override
    public String getContentIdentity() {
        String blobId = getBlobId();
        if (blobId != null){
            return blobId;
        }
        return getRecordId().toString();
    }

    public boolean isExternal() {
        Segment segment = getSegment();
        int offset = getOffset();
        byte head = segment.readByte(offset);
        // 1110 xxxx or 1111 0xxx: external value
        return (head & 0xf0) == 0xe0 || (head & 0xf8) == 0xf0;
    }

    @CheckForNull
    public String getBlobId() {
        return readBlobId(store, getSegment(), getOffset());
    }

    @CheckForNull
    static String readBlobId(@Nonnull SegmentStore store, @Nonnull Segment segment, int offset) {
        byte head = segment.readByte(offset);
        if ((head & 0xf0) == 0xe0) {
            // 1110 xxxx: external value, small blob ID
            return readShortBlobId(segment, offset, head);
        } else if ((head & 0xf8) == 0xf0) {
            // 1111 0xxx: external value, long blob ID
            return readLongBlobId(store, segment, offset);
        } else {
            return null;
        }
    }

    //------------------------------------------------------------< Object >--

    @Override
    public boolean equals(Object object) {
        if (Record.fastEquals(this, object)) {
            return true;
        }

        if (object instanceof SegmentBlob) {
            SegmentBlob that = (SegmentBlob) object;
            if (this.length() != that.length()) {
                return false;
            }
            List<RecordId> bulkIds = this.getBulkRecordIds();
            if (bulkIds != null && bulkIds.equals(that.getBulkRecordIds())) {
                return true;
            }
        }

        return object instanceof Blob
                && AbstractBlob.equal(this, (Blob) object);
    }

    @Override
    public int hashCode() {
        return 0;
    }

    //-----------------------------------------------------------< private >--

    private static String readShortBlobId(Segment segment, int offset, byte head) {
        int length = (head & 0x0f) << 8 | (segment.readByte(offset + 1) & 0xff);
        byte[] bytes = new byte[length];
        segment.readBytes(offset + 2, bytes, 0, length);
        return new String(bytes, UTF_8);
    }

    private static String readLongBlobId(SegmentStore store, Segment segment, int offset) {
        RecordId blobIdRecordId = segment.readRecordId(offset + 1);
        return store.getReader().readString(blobIdRecordId);
    }

    private List<RecordId> getBulkRecordIds() {
        Segment segment = getSegment();
        int offset = getOffset();
        byte head = segment.readByte(offset);
        if ((head & 0xe0) == 0xc0) {
            // 110x xxxx: long value
            long length = (segment.readLong(offset) & 0x1fffffffffffffffL) + MEDIUM_LIMIT;
            int listSize = (int) ((length + BLOCK_SIZE - 1) / BLOCK_SIZE);
            ListRecord list = new ListRecord(
                segment.readRecordId(offset + 8), listSize);
            return list.getEntries();
        } else {
            return null;
        }
    }

    private Iterable<SegmentId> getBulkSegmentIds() {
        List<RecordId> recordIds = getBulkRecordIds();
        if (recordIds == null) {
            return emptySet();
        } else {
            Set<SegmentId> ids = newHashSet();
            for (RecordId id : recordIds) {
                ids.add(id.getSegmentId());
            }
            return ids;
        }
    }

    private Blob getBlob(String blobId) {
        return store.readBlob(blobId);
    }

    private InputStream getNewStream(String blobId) {
        return getBlob(blobId).getNewStream();
    }

    private long getLength(String blobId) {
        long length = getBlob(blobId).length();

        if (length == -1) {
            throw new IllegalStateException(String.format("Unknown length of external binary: %s", blobId));
        }

        return length;
    }

}
