/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.service.storage;

import android.annotation.BytesLong;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.ParcelableException;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import com.android.internal.os.BackgroundThread;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.UUID;

/**
 * A service to handle filesystem I/O from other apps.
 *
 * <p>To extend this class, you must declare the service in your manifest file with the
 * {@link android.Manifest.permission#BIND_EXTERNAL_STORAGE_SERVICE} permission,
 * and include an intent filter with the {@link #SERVICE_INTERFACE} action.
 * For example:</p>
 * <pre>
 *     &lt;service android:name=".ExternalStorageServiceImpl"
 *             android:exported="true"
 *             android:priority="100"
 *             android:permission="android.permission.BIND_EXTERNAL_STORAGE_SERVICE"&gt;
 *         &lt;intent-filter&gt;
 *             &lt;action android:name="android.service.storage.ExternalStorageService" /&gt;
 *         &lt;/intent-filter&gt;
 *     &lt;/service&gt;
 * </pre>
 * @hide
 */
@SystemApi
public abstract class ExternalStorageService extends Service {
    /**
     * The Intent action that a service must respond to. Add it as an intent filter in the
     * manifest declaration of the implementing service.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE = "android.service.storage.ExternalStorageService";
    /**
     * Whether the session associated with the device file descriptor when calling
     * {@link #onStartSession} is a FUSE session.
     */
    public static final int FLAG_SESSION_TYPE_FUSE = 1 << 0;

    /**
     * Whether the upper file system path specified when calling {@link #onStartSession}
     * should be indexed.
     */
    public static final int FLAG_SESSION_ATTRIBUTE_INDEXABLE = 1 << 1;

    /**
     * {@link Bundle} key for a {@link String} value.
     *
     * {@hide}
     */
    public static final String EXTRA_SESSION_ID =
            "android.service.storage.extra.session_id";
    /**
     * {@link Bundle} key for a {@link ParcelableException} value.
     *
     * {@hide}
     */
    public static final String EXTRA_ERROR =
            "android.service.storage.extra.error";

    /**
     * {@link Bundle} key for a package name {@link String} value.
     *
     * {@hide}
     */
    public static final String EXTRA_PACKAGE_NAME = "android.service.storage.extra.package_name";

    /** @hide */
    @IntDef(flag = true, prefix = {"FLAG_SESSION_"},
        value = {FLAG_SESSION_TYPE_FUSE, FLAG_SESSION_ATTRIBUTE_INDEXABLE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface SessionFlag {}

    private final ExternalStorageServiceWrapper mWrapper = new ExternalStorageServiceWrapper();
    private final Handler mHandler = BackgroundThread.getHandler();

    /**
     * Called when the system starts a session associated with {@code deviceFd}
     * identified by {@code sessionId} to handle filesystem I/O for other apps. The type of
     * session and other attributes are passed in {@code flag}.
     *
     * <p> I/O is received as requests originating from {@code upperFileSystemPath} on
     * {@code deviceFd}. Implementors should handle the I/O by responding to these requests
     * using the data on the {@code lowerFileSystemPath}.
     *
     * <p> Additional calls to start a session for the same {@code sessionId} while the session
     * is still starting or already started should have no effect.
     *
     * @param sessionId uniquely identifies a running session and used in {@link #onEndSession}
     * @param flag specifies the type or additional attributes of a session
     * @param deviceFd for intercepting IO from other apps
     * @param upperFileSystemPath is the root path on which we are intercepting IO from other apps
     * @param lowerFileSystemPath is the root path matching {@code upperFileSystemPath} containing
     * the actual data apps are trying to access
     */
    public abstract void onStartSession(@NonNull String sessionId, @SessionFlag int flag,
            @NonNull ParcelFileDescriptor deviceFd, @NonNull File upperFileSystemPath,
            @NonNull File lowerFileSystemPath) throws IOException;

    /**
     * Called when the system ends the session identified by {@code sessionId}. Implementors should
     * stop handling filesystem I/O and clean up resources from the ended session.
     *
     * <p> Additional calls to end a session for the same {@code sessionId} while the session
     * is still ending or has not started should have no effect.
     */
    public abstract void onEndSession(@NonNull String sessionId) throws IOException;

    /**
     * Called when any volume's state changes.
     *
     * <p> This is required to communicate volume state changes with the Storage Service before
     * broadcasting to other apps. The Storage Service needs to process any change in the volume
     * state (before other apps receive a broadcast for the same) to update the database so that
     * other apps have the correct view of the volume.
     *
     * <p> Blocks until the Storage Service processes/scans the volume or fails in doing so.
     *
     * @param vol name of the volume that was changed
     */
    public abstract void onVolumeStateChanged(@NonNull StorageVolume vol) throws IOException;

    /**
     * Called when any cache held by the ExternalStorageService needs to be freed.
     *
     * <p> Blocks until the service frees the cache or fails in doing so.
     *
     * @param volumeUuid uuid of the {@link StorageVolume} from which cache needs to be freed
     * @param bytes number of bytes which need to be freed
     */
    public void onFreeCache(@NonNull UUID volumeUuid, @BytesLong long bytes) throws IOException {
        throw new UnsupportedOperationException("onFreeCacheRequested not implemented");
    }

    /**
     * Called when {@code packageName} is about to ANR. The {@link ExternalStorageService} can
     * show a progress dialog for the {@code reason}.
     *
     * @param packageName the package name of the ANR'ing app
     * @param uid the uid of the ANR'ing app
     * @param tid the thread id of the ANR'ing app
     * @param reason the reason the app is ANR'ing
     */
    public void onAnrDelayStarted(@NonNull String packageName, int uid, int tid,
            @StorageManager.AppIoBlockedReason int reason) {
        throw new UnsupportedOperationException("onAnrDelayStarted not implemented");
    }

    @Override
    @NonNull
    public final IBinder onBind(@NonNull Intent intent) {
        return mWrapper;
    }

    private class ExternalStorageServiceWrapper extends IExternalStorageService.Stub {
        @Override
        public void startSession(String sessionId, @SessionFlag int flag,
                ParcelFileDescriptor deviceFd, String upperPath, String lowerPath,
                RemoteCallback callback) throws RemoteException {
            mHandler.post(() -> {
                try {
                    onStartSession(sessionId, flag, deviceFd, new File(upperPath),
                            new File(lowerPath));
                    sendResult(sessionId, null /* throwable */, callback);
                } catch (Throwable t) {
                    sendResult(sessionId, t, callback);
                }
            });
        }

        @Override
        public void notifyVolumeStateChanged(String sessionId, StorageVolume vol,
                RemoteCallback callback) {
            mHandler.post(() -> {
                try {
                    onVolumeStateChanged(vol);
                    sendResult(sessionId, null /* throwable */, callback);
                } catch (Throwable t) {
                    sendResult(sessionId, t, callback);
                }
            });
        }

        @Override
        public void freeCache(String sessionId, String volumeUuid, long bytes,
                RemoteCallback callback) {
            mHandler.post(() -> {
                try {
                    onFreeCache(StorageManager.convert(volumeUuid), bytes);
                    sendResult(sessionId, null /* throwable */, callback);
                } catch (Throwable t) {
                    sendResult(sessionId, t, callback);
                }
            });
        }

        @Override
        public void endSession(String sessionId, RemoteCallback callback) throws RemoteException {
            mHandler.post(() -> {
                try {
                    onEndSession(sessionId);
                    sendResult(sessionId, null /* throwable */, callback);
                } catch (Throwable t) {
                    sendResult(sessionId, t, callback);
                }
            });
        }

        @Override
        public void notifyAnrDelayStarted(String packageName, int uid, int tid, int reason)
                throws RemoteException {
            mHandler.post(() -> {
                try {
                    onAnrDelayStarted(packageName, uid, tid, reason);
                } catch (Throwable t) {
                    // Ignored
                }
            });
        }

        private void sendResult(String sessionId, Throwable throwable, RemoteCallback callback) {
            Bundle bundle = new Bundle();
            bundle.putString(EXTRA_SESSION_ID, sessionId);
            if (throwable != null) {
                bundle.putParcelable(EXTRA_ERROR, new ParcelableException(throwable));
            }
            callback.sendResult(bundle);
        }
    }
}
