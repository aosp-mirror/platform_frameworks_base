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
 * limitations under the License
 */

package com.android.packageinstaller.wear;

import android.content.Context;
import android.content.IntentSender;
import android.content.pm.PackageInstaller;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Log;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Task that installs an APK. This must not be called on the main thread.
 * This code is based off the Finsky/Wearsky implementation
 */
public class InstallTask {
    private static final String TAG = "InstallTask";

    private static final int DEFAULT_BUFFER_SIZE = 8192;

    private final Context mContext;
    private String mPackageName;
    private ParcelFileDescriptor mParcelFileDescriptor;
    private PackageInstallerImpl.InstallListener mCallback;
    private PackageInstaller.Session mSession;
    private IntentSender mCommitCallback;

    private Exception mException = null;
    private int mErrorCode = 0;
    private String mErrorDesc = null;

    public InstallTask(Context context, String packageName,
            ParcelFileDescriptor parcelFileDescriptor,
            PackageInstallerImpl.InstallListener callback, PackageInstaller.Session session,
            IntentSender commitCallback) {
        mContext = context;
        mPackageName = packageName;
        mParcelFileDescriptor = parcelFileDescriptor;
        mCallback = callback;
        mSession = session;
        mCommitCallback = commitCallback;
    }

    public boolean isError() {
        return mErrorCode != InstallerConstants.STATUS_SUCCESS || !TextUtils.isEmpty(mErrorDesc);
    }

    public void execute() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("This method cannot be called from the UI thread.");
        }

        OutputStream sessionStream = null;
        try {
            sessionStream = mSession.openWrite(mPackageName, 0, -1);

            // 2b: Stream the asset to the installer. Note:
            // Note: writeToOutputStreamFromAsset() always safely closes the input stream
            writeToOutputStreamFromAsset(sessionStream);
            mSession.fsync(sessionStream);
        } catch (Exception e) {
            mException = e;
            mErrorCode = InstallerConstants.ERROR_INSTALL_COPY_STREAM;
            mErrorDesc = "Could not write to stream";
        } finally {
            if (sessionStream != null) {
                // 2c: close output stream
                try {
                    sessionStream.close();
                } catch (Exception e) {
                    // Ignore otherwise
                    if (mException == null) {
                        mException = e;
                        mErrorCode = InstallerConstants.ERROR_INSTALL_CLOSE_STREAM;
                        mErrorDesc = "Could not close session stream";
                    }
                }
            }
        }

        if (mErrorCode != InstallerConstants.STATUS_SUCCESS) {
            // An error occurred, we're done
            Log.e(TAG, "Exception while installing " + mPackageName + ": " + mErrorCode + ", "
                    + mErrorDesc + ", " + mException);
            mSession.close();
            mCallback.installFailed(mErrorCode, "[" + mPackageName + "]" + mErrorDesc);
        } else {
            // 3. Commit the session (this actually installs it.)  Session map
            // will be cleaned up in the callback.
            mCallback.installBeginning();
            mSession.commit(mCommitCallback);
            mSession.close();
        }
    }

    /**
     * {@code PackageInstaller} works with streams. Get the {@code FileDescriptor}
     * corresponding to the {@code Asset} and then write the contents into an
     * {@code OutputStream} that is passed in.
     * <br>
     * The {@code FileDescriptor} is closed but the {@code OutputStream} is not closed.
     */
    private boolean writeToOutputStreamFromAsset(OutputStream outputStream) {
        if (outputStream == null) {
            mErrorCode = InstallerConstants.ERROR_INSTALL_COPY_STREAM_EXCEPTION;
            mErrorDesc = "Got a null OutputStream.";
            return false;
        }

        if (mParcelFileDescriptor == null || mParcelFileDescriptor.getFileDescriptor() == null)  {
            mErrorCode = InstallerConstants.ERROR_COULD_NOT_GET_FD;
            mErrorDesc = "Could not get FD";
            return false;
        }

        InputStream inputStream = null;
        try {
            byte[] inputBuf = new byte[DEFAULT_BUFFER_SIZE];
            int bytesRead;
            inputStream = new ParcelFileDescriptor.AutoCloseInputStream(mParcelFileDescriptor);

            while ((bytesRead = inputStream.read(inputBuf)) > -1) {
                if (bytesRead > 0) {
                    outputStream.write(inputBuf, 0, bytesRead);
                }
            }

            outputStream.flush();
        } catch (IOException e) {
            mErrorCode = InstallerConstants.ERROR_INSTALL_APK_COPY_FAILURE;
            mErrorDesc = "Reading from Asset FD or writing to temp file failed: " + e;
            return false;
        } finally {
            safeClose(inputStream);
        }

        return true;
    }

    /**
     * Quietly close a closeable resource (e.g. a stream or file). The input may already
     * be closed and it may even be null.
     */
    public static void safeClose(Closeable resource) {
        if (resource != null) {
            try {
                resource.close();
            } catch (IOException ioe) {
                // Catch and discard the error
            }
        }
    }
}