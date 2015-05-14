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
import java.io.IOException;

/**
 * For supplying media data to the framework. Implement this if your app has
 * special requirements for the way media data is obtained.
 *
 * <p class="note">Methods of this interface may be called on multiple different
 * threads. There will be a thread synchronization point between each call to ensure that
 * modifications to the state of your MediaDataSource are visible to future calls. This means
 * you don't need to do your own synchronization unless you're modifying the
 * MediaDataSource from another thread while it's being used by the framework.</p>
 */
public abstract class MediaDataSource implements Closeable {
    /**
     * Called to request data from the given position.
     *
     * Implementations should should write up to {@code size} bytes into
     * {@code buffer}, and return the number of bytes written.
     *
     * Return {@code 0} if size is zero (thus no bytes are read).
     *
     * Return {@code -1} to indicate that end of stream is reached.
     *
     * @param position the position in the data source to read from.
     * @param buffer the buffer to read the data into.
     * @param offset the offset within buffer to read the data into.
     * @param size the number of bytes to read.
     * @throws IOException on fatal errors.
     * @return the number of bytes read, or -1 if there was an error.
     */
    public abstract int readAt(long position, byte[] buffer, int offset, int size)
            throws IOException;

    /**
     * Called to get the size of the data source.
     *
     * @throws IOException on fatal errors
     * @return the size of data source in bytes, or -1 if the size is unknown.
     */
    public abstract long getSize() throws IOException;
}
