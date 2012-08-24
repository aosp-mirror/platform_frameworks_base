/*
 * Copyright (C) 2012 The Android Open Source Project
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


package android.media;

import java.io.Closeable;

/**
 * An abstraction for a media data source, e.g. a file or an http stream
 * {@hide}
 */
public interface DataSource extends Closeable {
    /**
     * Reads data from the data source at the requested position
     *
     * @param offset where in the source to read
     * @param buffer the buffer to read the data into
     * @param size how many bytes to read
     * @return the number of bytes read, or -1 if there was an error
     */
    public int readAt(long offset, byte[] buffer, int size);

    /**
     * Gets the size of the data source.
     *
     * @return size of data source, or -1 if the length is unknown
     */
    public long getSize();
}
