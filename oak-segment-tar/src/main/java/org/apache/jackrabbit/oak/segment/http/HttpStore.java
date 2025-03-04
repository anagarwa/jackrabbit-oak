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
package org.apache.jackrabbit.oak.segment.http;

import static com.google.common.base.Charsets.UTF_8;
import static org.apache.jackrabbit.oak.segment.SegmentVersion.LATEST_VERSION;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.ByteBuffer;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import com.google.common.io.ByteStreams;
import org.apache.jackrabbit.oak.api.Blob;
import org.apache.jackrabbit.oak.segment.RecordId;
import org.apache.jackrabbit.oak.segment.Segment;
import org.apache.jackrabbit.oak.segment.SegmentBufferWriterPool;
import org.apache.jackrabbit.oak.segment.SegmentId;
import org.apache.jackrabbit.oak.segment.SegmentNodeState;
import org.apache.jackrabbit.oak.segment.SegmentNotFoundException;
import org.apache.jackrabbit.oak.segment.SegmentReader;
import org.apache.jackrabbit.oak.segment.SegmentReaderImpl;
import org.apache.jackrabbit.oak.segment.SegmentStore;
import org.apache.jackrabbit.oak.segment.SegmentTracker;
import org.apache.jackrabbit.oak.segment.SegmentWriter;
import org.apache.jackrabbit.oak.spi.blob.BlobStore;

public class HttpStore implements SegmentStore {

    @Nonnull
    private final SegmentTracker tracker = new SegmentTracker(this);

    @Nonnull
    private final SegmentWriter segmentWriter = new SegmentWriter(this,
            new SegmentBufferWriterPool(this, LATEST_VERSION, "sys"));

    @Nonnull
    private final SegmentReader segmentReader = new SegmentReaderImpl(this);

    private final URL base;

    /**
     * @param base
     *            make sure the url ends with a slash "/", otherwise the
     *            requests will end up as absolute instead of relative
     */
    public HttpStore(URL base) {
        this.base = base;
    }

    @Override
    @Nonnull
    public SegmentTracker getTracker() {
        return tracker;
    }

    @Override
    @Nonnull
    public SegmentWriter getWriter() {
        return segmentWriter;
    }

    @Override
    @Nonnull
    public SegmentReader getReader() {
        return segmentReader;
    }

    /**
     * Builds a simple URLConnection. This method can be extended to add
     * authorization headers if needed.
     * 
     */
    protected URLConnection get(String fragment) throws MalformedURLException,
            IOException {
        final URL url;
        if (fragment == null) {
            url = base;
        } else {
            url = new URL(base, fragment);
        }
        return url.openConnection();
    }

    @Override
    public SegmentNodeState getHead() {
        try {
            URLConnection connection = get(null);
            InputStream stream = connection.getInputStream();
            try {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(stream, UTF_8));
                return new SegmentNodeState(this, RecordId.fromString(tracker, reader.readLine()));
            } finally {
                stream.close();
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(e);
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean setHead(SegmentNodeState base, SegmentNodeState head) {
        // TODO throw new UnsupportedOperationException();
        return true;
    }

    @Override
    // FIXME OAK-4396: HttpStore.containsSegment throws SNFE instead of returning false for non existing segments
    public boolean containsSegment(SegmentId id) {
        return id.sameStore(this) || readSegment(id) != null;
    }

    @Override
    @Nonnull
    public Segment readSegment(SegmentId id) {
        try {
            URLConnection connection = get(id.toString());
            InputStream stream = connection.getInputStream();
            try {
                byte[] data = ByteStreams.toByteArray(stream);
                return new Segment(this, id, ByteBuffer.wrap(data));
            } finally {
                stream.close();
            }
        } catch (MalformedURLException e) {
            throw new SegmentNotFoundException(id, e);
        } catch (IOException e) {
            throw new SegmentNotFoundException(id, e);
        }
    }

    @Override
    public void writeSegment(
            SegmentId id, byte[] bytes, int offset, int length) throws IOException {
        try {
            URLConnection connection = get(id.toString());
            connection.setDoInput(false);
            connection.setDoOutput(true);
            OutputStream stream = connection.getOutputStream();
            try {
                stream.write(bytes, offset, length);
            } finally {
                stream.close();
            }
        } catch (MalformedURLException e) {
            throw new IOException(e);
        }
    }

    @Override
    public void close() {
    }

    @Override @CheckForNull
    public Blob readBlob(String reference) {
        return null;
    }

    @Override @CheckForNull
    public BlobStore getBlobStore() {
        return null;
    }

    @Override
    public void gc() {
        // TODO: distributed gc
    }

}
