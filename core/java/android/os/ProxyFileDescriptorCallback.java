/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.os;

import android.system.ErrnoException;
import android.system.OsConstants;

/**
 * Callback that handles file system requests from ProxyFileDescriptor.
 */
public abstract class ProxyFileDescriptorCallback {
    /**
     * Returns size of bytes provided by the file descriptor.
     * @return Size of bytes
     * @throws ErrnoException
     */
    public long onGetSize() throws ErrnoException {
        throw new ErrnoException("onGetSize", OsConstants.EBADF);
    }

    /**
     * Provides bytes read from file descriptor.
     * It needs to return exact requested size of bytes unless it reaches file end.
     * @param offset Where to read bytes from.
     * @param size Size for read bytes.
     * @param data Byte array to store read bytes.
     * @return Size of bytes returned by the function.
     * @throws ErrnoException
     */
    public int onRead(long offset, int size, byte[] data) throws ErrnoException {
        throw new ErrnoException("onRead", OsConstants.EBADF);
    }

    /**
     * Handles bytes written to file descriptor.
     * @param offset Where to write bytes to.
     * @param size Size for write bytes.
     * @param data Byte array to be written to somewhere.
     * @return Size of bytes processed by the function.
     * @throws ErrnoException
     */
    public int onWrite(long offset, int size, byte[] data) throws ErrnoException {
        throw new ErrnoException("onWrite", OsConstants.EBADF);
    }

    /**
     * Processes fsync request.
     * @throws ErrnoException
     */
    public void onFsync() throws ErrnoException {
        throw new ErrnoException("onFsync", OsConstants.EINVAL);
    }

    /**
     * Invoked after the file is closed.
     */
    abstract public void onRelease();
}
