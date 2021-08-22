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

import static android.Manifest.permission.PACKAGE_USAGE_STATS;
import static android.Manifest.permission.READ_RUNTIME_PROFILES;

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.content.Context;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Slog;

import java.io.File;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.Executor;

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

    /** Constant used for applications profiles. */
    public static final int PROFILE_APPS = 0;
    /** Constant used for the boot image profile. */
    public static final int PROFILE_BOOT_IMAGE = 1;

    /** @hide */
    @IntDef(flag = true, prefix = { "PROFILE_" }, value = {
            PROFILE_APPS,
            PROFILE_BOOT_IMAGE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ProfileType {}

    private final Context mContext;
    private final IArtManager mArtManager;

    /**
     * @hide
     */
    public ArtManager(@NonNull Context context, @NonNull IArtManager manager) {
        mContext = context;
        mArtManager = manager;
    }

    /**
     * Snapshots a runtime profile according to the {@code profileType} parameter.
     *
     * If {@code profileType} is {@link ArtManager#PROFILE_APPS} the method will snapshot
     * the profile for for an apk belonging to the package {@code packageName}.
     * The apk is identified by {@code codePath}.
     *
     * If {@code profileType} is {@code ArtManager.PROFILE_BOOT_IMAGE} the method will snapshot
     * the profile for the boot image. In this case {@code codePath can be null}. The parameters
     * {@code packageName} and {@code codePath} are ignored.
     *u
     * The calling process must have {@code android.permission.READ_RUNTIME_PROFILE} permission.
     *
     * The result will be posted on the {@code executor} using the given {@code callback}.
     * The profile will be available as a read-only {@link android.os.ParcelFileDescriptor}.
     *
     * This method will throw {@link IllegalStateException} if
     * {@link ArtManager#isRuntimeProfilingEnabled(int)} does not return true for the given
     * {@code profileType}.
     *
     * @param profileType the type of profile that should be snapshot (boot image or app)
     * @param packageName the target package name or null if the target is the boot image
     * @param codePath the code path for which the profile should be retrieved or null if
     *                 the target is the boot image
     * @param callback the callback which should be used for the result
     * @param executor the executor which should be used to post the result
     */
    @RequiresPermission(allOf = { READ_RUNTIME_PROFILES, PACKAGE_USAGE_STATS })
    public void snapshotRuntimeProfile(@ProfileType int profileType, @Nullable String packageName,
            @Nullable String codePath, @NonNull @CallbackExecutor Executor executor,
            @NonNull SnapshotRuntimeProfileCallback callback) {
        Slog.d(TAG, "Requesting profile snapshot for " + packageName + ":" + codePath);

        SnapshotRuntimeProfileCallbackDelegate delegate =
                new SnapshotRuntimeProfileCallbackDelegate(callback, executor);
        try {
            mArtManager.snapshotRuntimeProfile(profileType, packageName, codePath, delegate,
                    mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Returns true if runtime profiles are enabled for the given type, false otherwise.
     *
     * The calling process must have {@code android.permission.READ_RUNTIME_PROFILE} permission.
     *
     * @param profileType can be either {@link ArtManager#PROFILE_APPS}
     *                    or {@link ArtManager#PROFILE_BOOT_IMAGE}
     */
    @RequiresPermission(allOf = { READ_RUNTIME_PROFILES, PACKAGE_USAGE_STATS })
    public boolean isRuntimeProfilingEnabled(@ProfileType int profileType) {
        try {
            return mArtManager.isRuntimeProfilingEnabled(profileType, mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
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
            extends android.content.pm.dex.ISnapshotRuntimeProfileCallback.Stub {
        private final ArtManager.SnapshotRuntimeProfileCallback mCallback;
        private final Executor mExecutor;

        private SnapshotRuntimeProfileCallbackDelegate(
                ArtManager.SnapshotRuntimeProfileCallback callback, Executor executor) {
            mCallback = callback;
            mExecutor = executor;
        }

        @Override
        public void onSuccess(final ParcelFileDescriptor profileReadFd) {
            mExecutor.execute(() -> mCallback.onSuccess(profileReadFd));
        }

        @Override
        public void onError(int errCode) {
            mExecutor.execute(() -> mCallback.onError(errCode));
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
     * Return the path to the current profile corresponding to given package and split.
     *
     * @hide
     */
    public static String getReferenceProfilePath(String packageName, int userId, String splitName) {
        File profileDir = Environment.getDataRefProfilesDePackageDirectory(packageName);
        return new File(profileDir, getProfileName(splitName)).getAbsolutePath();
    }

    /**
     * Return the snapshot profile file for the given package and profile name.
     *
     * KEEP in sync with installd dexopt.cpp.
     * TODO(calin): inject the snapshot profile name from PM to avoid the dependency.
     *
     * @hide
     */
    public static File getProfileSnapshotFileForName(String packageName, String profileName) {
        File profileDir = Environment.getDataRefProfilesDePackageDirectory(packageName);
        return new File(profileDir, profileName  + ".snapshot");
    }
}
