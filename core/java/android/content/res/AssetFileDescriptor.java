/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.content.res;

import android.os.ParcelFileDescriptor;

import java.io.FileDescriptor;
import java.io.IOException;

/**
 * File descriptor of an entry in the AssetManager.  This provides your own
 * opened FileDescriptor that can be used to read the data, as well as the
 * offset and length of that entry's data in the file.
 */
public class AssetFileDescriptor {
    private final ParcelFileDescriptor mFd;
    private final long mStartOffset;
    private final long mLength;
    
    /**
     * Create a new AssetFileDescriptor from the given values.
     */
    public AssetFileDescriptor(ParcelFileDescriptor fd, long startOffset,
            long length) {
        mFd = fd;
        mStartOffset = startOffset;
        mLength = length;
    }
    
    /**
     * The AssetFileDescriptor contains its own ParcelFileDescriptor, which
     * in addition to the normal FileDescriptor object also allows you to close
     * the descriptor when you are done with it.
     */
    public ParcelFileDescriptor getParcelFileDescriptor() {
        return mFd;
    }
    
    /**
     * Returns the FileDescriptor that can be used to read the data in the
     * file.
     */
    public FileDescriptor getFileDescriptor() {
        return mFd.getFileDescriptor();
    }
    
    /**
     * Returns the byte offset where this asset entry's data starts.
     */
    public long getStartOffset() {
        return mStartOffset;
    }
    
    /**
     * Returns the total number of bytes of this asset entry's data.
     */
    public long getLength() {
        return mLength;
    }
    
    /**
     * Convenience for calling <code>getParcelFileDescriptor().close()</code>.
     */
    public void close() throws IOException {
        mFd.close();
    }
}
