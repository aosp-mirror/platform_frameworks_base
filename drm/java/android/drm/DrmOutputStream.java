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

package android.drm;

import static android.drm.DrmConvertedStatus.STATUS_OK;
import static android.drm.DrmManagerClient.INVALID_SESSION;
import static android.system.OsConstants.SEEK_SET;

import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import libcore.io.IoBridge;
import libcore.io.Streams;
import libcore.util.ArrayUtils;

import java.io.FileDescriptor;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.UnknownServiceException;

/**
 * Stream that applies a {@link DrmManagerClient} transformation to data before
 * writing to disk, similar to a {@link FilterOutputStream}.
 *
 * @hide
 * @deprecated Please use {@link android.media.MediaDrm}
 */
@Deprecated
public class DrmOutputStream extends OutputStream {
    private static final String TAG = "DrmOutputStream";

    private final DrmManagerClient mClient;
    private final ParcelFileDescriptor mPfd;
    private final FileDescriptor mFd;

    private int mSessionId = INVALID_SESSION;

    /**
     * @param pfd Opened with "rw" mode.
     */
    public DrmOutputStream(DrmManagerClient client, ParcelFileDescriptor pfd, String mimeType)
            throws IOException {
        mClient = client;
        mPfd = pfd;
        mFd = pfd.getFileDescriptor();

        mSessionId = mClient.openConvertSession(mimeType);
        if (mSessionId == INVALID_SESSION) {
            throw new UnknownServiceException("Failed to open DRM session for " + mimeType);
        }
    }

    public void finish() throws IOException {
        final DrmConvertedStatus status = mClient.closeConvertSession(mSessionId);
        if (status.statusCode == STATUS_OK) {
            try {
                Os.lseek(mFd, status.offset, SEEK_SET);
            } catch (ErrnoException e) {
                e.rethrowAsIOException();
            }
            IoBridge.write(mFd, status.convertedData, 0, status.convertedData.length);
            mSessionId = INVALID_SESSION;
        } else {
            throw new IOException("Unexpected DRM status: " + status.statusCode);
        }
    }

    @Override
    public void close() throws IOException {
        if (mSessionId == INVALID_SESSION) {
            Log.w(TAG, "Closing stream without finishing");
        }

        mPfd.close();
    }

    @Override
    public void write(byte[] buffer, int offset, int count) throws IOException {
        ArrayUtils.throwsIfOutOfBounds(buffer.length, offset, count);

        final byte[] exactBuffer;
        if (count == buffer.length) {
            exactBuffer = buffer;
        } else {
            exactBuffer = new byte[count];
            System.arraycopy(buffer, offset, exactBuffer, 0, count);
        }

        final DrmConvertedStatus status = mClient.convertData(mSessionId, exactBuffer);
        if (status.statusCode == STATUS_OK) {
            IoBridge.write(mFd, status.convertedData, 0, status.convertedData.length);
        } else {
            throw new IOException("Unexpected DRM status: " + status.statusCode);
        }
    }

    @Override
    public void write(int oneByte) throws IOException {
        Streams.writeSingleByte(this, oneByte);
    }
}
