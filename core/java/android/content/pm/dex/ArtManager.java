/**
 * Copyright 2017 The Android Open Source Project
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

package android.content.pm.dex;

import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Slog;

import java.io.File;

/**
 * Class for retrieving various kinds of information related to the runtime artifacts of
 * packages that are currently installed on the device.
 *
 * @hide
 */
@SystemApi
public class ArtManager {
    private static final String TAG = "ArtManager";

    /** The snapshot failed because the package was not found. */
    public static final int SNAPSHOT_FAILED_PACKAGE_NOT_FOUND = 0;
    /** The snapshot failed because the package code path does not exist. */
    public static final int SNAPSHOT_FAILED_CODE_PATH_NOT_FOUND = 1;
    /** The snapshot failed because of an internal error (e.g. error during opening profiles). */
    public static final int SNAPSHOT_FAILED_INTERNAL_ERROR = 2;

    private IArtManager mArtManager;

    /**
     * @hide
     */
    public ArtManager(@NonNull IArtManager manager) {
        mArtManager = manager;
    }

    /**
     * Snapshots the runtime profile for an apk belonging to the package {@code packageName}.
     * The apk is identified by {@code codePath}. The calling process must have
     * {@code android.permission.READ_RUNTIME_PROFILE} permission.
     *
     * The result will be posted on {@code handler} using the given {@code callback}.
     * The profile being available as a read-only {@link android.os.ParcelFileDescriptor}.
     *
     * @param packageName the target package name
     * @param codePath the code path for which the profile should be retrieved
     * @param callback the callback which should be used for the result
     * @param handler the handler which should be used to post the result
     */
    @RequiresPermission(android.Manifest.permission.READ_RUNTIME_PROFILES)
    public void snapshotRuntimeProfile(@NonNull String packageName, @NonNull String codePath,
            @NonNull SnapshotRuntimeProfileCallback callback, @NonNull Handler handler) {
        Slog.d(TAG, "Requesting profile snapshot for " + packageName + ":" + codePath);

        SnapshotRuntimeProfileCallbackDelegate delegate =
                new SnapshotRuntimeProfileCallbackDelegate(callback, handler.getLooper());
        try {
            mArtManager.snapshotRuntimeProfile(packageName, codePath, delegate);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    /**
     * Returns true if runtime profiles are enabled, false otherwise.
     *
     * The calling process must have {@code android.permission.READ_RUNTIME_PROFILE} permission.
     */
    @RequiresPermission(android.Manifest.permission.READ_RUNTIME_PROFILES)
    public boolean isRuntimeProfilingEnabled() {
        try {
            return mArtManager.isRuntimeProfilingEnabled();
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
        return false;
    }

    /**
     * Callback used for retrieving runtime profiles.
     */
    public abstract static class SnapshotRuntimeProfileCallback {
        /**
         * Called when the profile snapshot finished with success.
         *
         * @param profileReadFd the file descriptor that can be used to read the profile. Note that
         *                      the file might be empty (which is valid profile).
         */
        public abstract void onSuccess(ParcelFileDescriptor profileReadFd);

        /**
         * Called when the profile snapshot finished with an error.
         *
         * @param errCode the error code {@see SNAPSHOT_FAILED_PACKAGE_NOT_FOUND,
         *      SNAPSHOT_FAILED_CODE_PATH_NOT_FOUND, SNAPSHOT_FAILED_INTERNAL_ERROR}.
         */
        public abstract void onError(int errCode);
    }

    private static class SnapshotRuntimeProfileCallbackDelegate
            extends android.content.pm.dex.ISnapshotRuntimeProfileCallback.Stub
            implements Handler.Callback {
        private static final int MSG_SNAPSHOT_OK = 1;
        private static final int MSG_ERROR = 2;
        private final ArtManager.SnapshotRuntimeProfileCallback mCallback;
        private final Handler mHandler;

        private SnapshotRuntimeProfileCallbackDelegate(
                ArtManager.SnapshotRuntimeProfileCallback callback, Looper looper) {
            mCallback = callback;
            mHandler = new Handler(looper, this);
        }

        @Override
        public void onSuccess(ParcelFileDescriptor profileReadFd) {
            mHandler.obtainMessage(MSG_SNAPSHOT_OK, profileReadFd).sendToTarget();
        }

        @Override
        public void onError(int errCode) {
            mHandler.obtainMessage(MSG_ERROR, errCode, 0).sendToTarget();
        }

        @Override
        public boolean handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_SNAPSHOT_OK:
                    mCallback.onSuccess((ParcelFileDescriptor) msg.obj);
                    break;
                case MSG_ERROR:
                    mCallback.onError(msg.arg1);
                    break;
                default: return false;
            }
            return true;
        }
    }

    /**
     * Return the profile name for the given split. If {@code splitName} is null the
     * method returns the profile name for the base apk.
     *
     * @hide
     */
    public static String getProfileName(String splitName) {
        return splitName == null ? "primary.prof" : splitName + ".split.prof";
    }

    /**
     * Return the path to the current profile corresponding to given package and split.
     *
     * @hide
     */
    public static String getCurrentProfilePath(String packageName, int userId, String splitName) {
        File profileDir = Environment.getDataProfilesDePackageDirectory(userId, packageName);
        return new File(profileDir, getProfileName(splitName)).getAbsolutePath();
    }

    /**
     * Return the snapshot profile file for the given package and split.
     *
     * KEEP in sync with installd dexopt.cpp.
     * TODO(calin): inject the snapshot profile name from PM to avoid the dependency.
     *
     * @hide
     */
    public static File getProfileSnapshotFile(String packageName, String splitName) {
        File profileDir = Environment.getDataRefProfilesDePackageDirectory(packageName);
        String snapshotFile = getProfileName(splitName) + ".snapshot";
        return new File(profileDir, snapshotFile);

    }
}
