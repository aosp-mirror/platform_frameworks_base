/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.os.storage;

import static android.Manifest.permission.MANAGE_EXTERNAL_STORAGE;
import static android.Manifest.permission.READ_EXTERNAL_STORAGE;
import static android.app.AppOpsManager.OP_LEGACY_STORAGE;
import static android.app.AppOpsManager.OP_MANAGE_EXTERNAL_STORAGE;
import static android.app.AppOpsManager.OP_READ_EXTERNAL_STORAGE;
import static android.app.AppOpsManager.OP_READ_MEDIA_IMAGES;
import static android.content.ContentResolver.DEPRECATE_DATA_PREFIX;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.UserHandle.PER_USER_RANGE;

import android.annotation.BytesLong;
import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.annotation.WorkerThread;
import android.app.Activity;
import android.app.ActivityThread;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageMoveObserver;
import android.content.pm.PackageManager;
import android.content.res.ObbInfo;
import android.content.res.ObbScanner;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Flags;
import android.os.Handler;
import android.os.IInstalld;
import android.os.IVold;
import android.os.IVoldTaskListener;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.ParcelableException;
import android.os.PersistableBundle;
import android.os.ProxyFileDescriptorCallback;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.provider.MediaStore;
import android.provider.Settings;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.text.TextUtils;
import android.util.DataUnit;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.os.AppFuseMount;
import com.android.internal.os.FuseAppLoop;
import com.android.internal.os.FuseUnavailableMountException;
import com.android.internal.os.RoSystemProperties;
import com.android.internal.util.Preconditions;

import dalvik.system.BlockGuard;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * StorageManager is the interface to the systems storage service. The storage
 * manager handles storage-related items such as Opaque Binary Blobs (OBBs).
 * <p>
 * OBBs contain a filesystem that maybe be encrypted on disk and mounted
 * on-demand from an application. OBBs are a good way of providing large amounts
 * of binary assets without packaging them into APKs as they may be multiple
 * gigabytes in size. However, due to their size, they're most likely stored in
 * a shared storage pool accessible from all programs. The system does not
 * guarantee the security of the OBB file itself: if any program modifies the
 * OBB, there is no guarantee that a read from that OBB will produce the
 * expected output.
 */
@SystemService(Context.STORAGE_SERVICE)
public class StorageManager {
    private static final String TAG = "StorageManager";
    private static final boolean LOCAL_LOGV = Log.isLoggable(TAG, Log.VERBOSE);

    /** {@hide} */
    public static final String PROP_PRIMARY_PHYSICAL = "ro.vold.primary_physical";
    /** {@hide} */
    public static final String PROP_HAS_ADOPTABLE = "vold.has_adoptable";
    /** {@hide} */
    public static final String PROP_HAS_RESERVED = "vold.has_reserved";
    /** {@hide} */
    public static final String PROP_ADOPTABLE = "persist.sys.adoptable";
    /** {@hide} */
    public static final String PROP_SDCARDFS = "persist.sys.sdcardfs";
    /** {@hide} */
    public static final String PROP_VIRTUAL_DISK = "persist.sys.virtual_disk";
    /** {@hide} */
    public static final String PROP_FORCED_SCOPED_STORAGE_WHITELIST =
            "forced_scoped_storage_whitelist";

    /** {@hide} */
    public static final String UUID_PRIVATE_INTERNAL = null;
    /** {@hide} */
    public static final String UUID_PRIMARY_PHYSICAL = "primary_physical";
    /** {@hide} */
    public static final String UUID_SYSTEM = "system";

    // NOTE: See comments around #convert for more details.
    private static final String FAT_UUID_PREFIX = "fafafafa-fafa-5afa-8afa-fafa";

    // NOTE: UUID constants below are namespaced
    // uuid -v5 ad99aa3d-308e-4191-a200-ebcab371c0ad default
    // uuid -v5 ad99aa3d-308e-4191-a200-ebcab371c0ad primary_physical
    // uuid -v5 ad99aa3d-308e-4191-a200-ebcab371c0ad system

    /**
     * UUID representing the default internal storage of this device which
     * provides {@link Environment#getDataDirectory()}.
     * <p>
     * This value is constant across all devices and it will never change, and
     * thus it cannot be used to uniquely identify a particular physical device.
     *
     * @see #getUuidForPath(File)
     * @see ApplicationInfo#storageUuid
     */
    public static final UUID UUID_DEFAULT = UUID
            .fromString("41217664-9172-527a-b3d5-edabb50a7d69");

    /** {@hide} */
    public static final UUID UUID_PRIMARY_PHYSICAL_ = UUID
            .fromString("0f95a519-dae7-5abf-9519-fbd6209e05fd");

    /** {@hide} */
    public static final UUID UUID_SYSTEM_ = UUID
            .fromString("5d258386-e60d-59e3-826d-0089cdd42cc0");

    /**
     * Activity Action: Allows the user to manage their storage. This activity
     * provides the ability to free up space on the device by deleting data such
     * as apps.
     * <p>
     * If the sending application has a specific storage device or allocation
     * size in mind, they can optionally define {@link #EXTRA_UUID} or
     * {@link #EXTRA_REQUESTED_BYTES}, respectively.
     * <p>
     * This intent should be launched using
     * {@link Activity#startActivityForResult(Intent, int)} so that the user
     * knows which app is requesting the storage space. The returned result will
     * be {@link Activity#RESULT_OK} if the requested space was made available,
     * or {@link Activity#RESULT_CANCELED} otherwise.
     */
    @SdkConstant(SdkConstant.SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_MANAGE_STORAGE = "android.os.storage.action.MANAGE_STORAGE";

    /**
     * Activity Action: Allows the user to free up space by clearing app external cache directories.
     * The intent doesn't automatically clear cache, but shows a dialog and lets the user decide.
     * <p>
     * This intent should be launched using
     * {@link Activity#startActivityForResult(Intent, int)} so that the user
     * knows which app is requesting to clear cache. The returned result will be:
     * {@link Activity#RESULT_OK} if the activity was launched and all cache was cleared,
     * {@link OsConstants#EIO} if an error occurred while clearing the cache or
     * {@link Activity#RESULT_CANCELED} otherwise.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_EXTERNAL_STORAGE)
    @SdkConstant(SdkConstant.SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_CLEAR_APP_CACHE = "android.os.storage.action.CLEAR_APP_CACHE";

    /**
     * Extra {@link UUID} used to indicate the storage volume where an
     * application is interested in allocating or managing disk space.
     *
     * @see #ACTION_MANAGE_STORAGE
     * @see #UUID_DEFAULT
     * @see #getUuidForPath(File)
     * @see Intent#putExtra(String, java.io.Serializable)
     */
    public static final String EXTRA_UUID = "android.os.storage.extra.UUID";

    /**
     * Extra used to indicate the total size (in bytes) that an application is
     * interested in allocating.
     * <p>
     * When defined, the management UI will help guide the user to free up
     * enough disk space to reach this requested value.
     *
     * @see #ACTION_MANAGE_STORAGE
     */
    public static final String EXTRA_REQUESTED_BYTES = "android.os.storage.extra.REQUESTED_BYTES";

    /** {@hide} */
    public static final int DEBUG_ADOPTABLE_FORCE_ON = 1 << 0;
    /** {@hide} */
    public static final int DEBUG_ADOPTABLE_FORCE_OFF = 1 << 1;
    /** {@hide} */
    public static final int DEBUG_SDCARDFS_FORCE_ON = 1 << 2;
    /** {@hide} */
    public static final int DEBUG_SDCARDFS_FORCE_OFF = 1 << 3;
    /** {@hide} */
    public static final int DEBUG_VIRTUAL_DISK = 1 << 4;

    /** {@hide} */
    public static final int FLAG_STORAGE_DE = IInstalld.FLAG_STORAGE_DE;
    /** {@hide} */
    public static final int FLAG_STORAGE_CE = IInstalld.FLAG_STORAGE_CE;
    /** {@hide} */
    public static final int FLAG_STORAGE_EXTERNAL = IInstalld.FLAG_STORAGE_EXTERNAL;
    /** @hide */
    public static final int FLAG_STORAGE_SDK = IInstalld.FLAG_STORAGE_SDK;

    /** {@hide} */
    @IntDef(prefix = "FLAG_STORAGE_",  value = {
            FLAG_STORAGE_DE,
            FLAG_STORAGE_CE,
            FLAG_STORAGE_EXTERNAL,
            FLAG_STORAGE_SDK,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface StorageFlags {}

    /** {@hide} */
    public static final int FLAG_FOR_WRITE = 1 << 8;
    /** {@hide} */
    public static final int FLAG_REAL_STATE = 1 << 9;
    /** {@hide} */
    public static final int FLAG_INCLUDE_INVISIBLE = 1 << 10;
    /** {@hide} */
    public static final int FLAG_INCLUDE_RECENT = 1 << 11;
    /** {@hide} */
    public static final int FLAG_INCLUDE_SHARED_PROFILE = 1 << 12;

    /** {@hide} */
    public static final int FSTRIM_FLAG_DEEP = IVold.FSTRIM_FLAG_DEEP_TRIM;

    /** @hide The volume is not encrypted. */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final int ENCRYPTION_STATE_NONE = 1;

    private static volatile IStorageManager sStorageManager = null;

    private final Context mContext;
    private final ContentResolver mResolver;

    private final IStorageManager mStorageManager;
    private final AppOpsManager mAppOps;
    private final Looper mLooper;
    private final AtomicInteger mNextNonce = new AtomicInteger(0);

    @GuardedBy("mDelegates")
    private final ArrayList<StorageEventListenerDelegate> mDelegates = new ArrayList<>();

    private class StorageEventListenerDelegate extends IStorageEventListener.Stub {
        final Executor mExecutor;
        final StorageEventListener mListener;
        final StorageVolumeCallback mCallback;

        public StorageEventListenerDelegate(@NonNull Executor executor,
                @NonNull StorageEventListener listener, @NonNull StorageVolumeCallback callback) {
            mExecutor = executor;
            mListener = listener;
            mCallback = callback;
        }

        @Override
        public void onUsbMassStorageConnectionChanged(boolean connected) throws RemoteException {
            mExecutor.execute(() -> {
                mListener.onUsbMassStorageConnectionChanged(connected);
            });
        }

        @Override
        public void onStorageStateChanged(String path, String oldState, String newState) {
            mExecutor.execute(() -> {
                mListener.onStorageStateChanged(path, oldState, newState);

                if (path != null) {
                    for (StorageVolume sv : getStorageVolumes()) {
                        if (Objects.equals(path, sv.getPath())) {
                            mCallback.onStateChanged(sv);
                        }
                    }
                }
            });
        }

        @Override
        public void onVolumeStateChanged(VolumeInfo vol, int oldState, int newState) {
            mExecutor.execute(() -> {
                mListener.onVolumeStateChanged(vol, oldState, newState);

                final File path = vol.getPathForUser(UserHandle.myUserId());
                if (path != null) {
                    for (StorageVolume sv : getStorageVolumes()) {
                        if (Objects.equals(path.getAbsolutePath(), sv.getPath())) {
                            mCallback.onStateChanged(sv);
                        }
                    }
                }
            });
        }

        @Override
        public void onVolumeRecordChanged(VolumeRecord rec) {
            mExecutor.execute(() -> {
                mListener.onVolumeRecordChanged(rec);
            });
        }

        @Override
        public void onVolumeForgotten(String fsUuid) {
            mExecutor.execute(() -> {
                mListener.onVolumeForgotten(fsUuid);
            });
        }

        @Override
        public void onDiskScanned(DiskInfo disk, int volumeCount) {
            mExecutor.execute(() -> {
                mListener.onDiskScanned(disk, volumeCount);
            });
        }

        @Override
        public void onDiskDestroyed(DiskInfo disk) throws RemoteException {
            mExecutor.execute(() -> {
                mListener.onDiskDestroyed(disk);
            });
        }
    }

    /**
     * Binder listener for OBB action results.
     */
    private final ObbActionListener mObbActionListener = new ObbActionListener();

    private class ObbActionListener extends IObbActionListener.Stub {
        @SuppressWarnings("hiding")
        private SparseArray<ObbListenerDelegate> mListeners = new SparseArray<ObbListenerDelegate>();

        @Override
        public void onObbResult(String filename, int nonce, int status) {
            final ObbListenerDelegate delegate;
            synchronized (mListeners) {
                delegate = mListeners.get(nonce);
                if (delegate != null) {
                    mListeners.remove(nonce);
                }
            }

            if (delegate != null) {
                delegate.sendObbStateChanged(filename, status);
            }
        }

        public int addListener(OnObbStateChangeListener listener) {
            final ObbListenerDelegate delegate = new ObbListenerDelegate(listener);

            synchronized (mListeners) {
                mListeners.put(delegate.nonce, delegate);
            }

            return delegate.nonce;
        }
    }

    private int getNextNonce() {
        return mNextNonce.getAndIncrement();
    }

    /**
     * Private class containing sender and receiver code for StorageEvents.
     */
    private class ObbListenerDelegate {
        private final WeakReference<OnObbStateChangeListener> mObbEventListenerRef;
        private final Handler mHandler;

        private final int nonce;

        ObbListenerDelegate(OnObbStateChangeListener listener) {
            nonce = getNextNonce();
            mObbEventListenerRef = new WeakReference<OnObbStateChangeListener>(listener);
            mHandler = new Handler(mLooper) {
                @Override
                public void handleMessage(Message msg) {
                    final OnObbStateChangeListener changeListener = getListener();
                    if (changeListener == null) {
                        return;
                    }

                    changeListener.onObbStateChange((String) msg.obj, msg.arg1);
                }
            };
        }

        OnObbStateChangeListener getListener() {
            if (mObbEventListenerRef == null) {
                return null;
            }
            return mObbEventListenerRef.get();
        }

        void sendObbStateChanged(String path, int state) {
            mHandler.obtainMessage(0, state, 0, path).sendToTarget();
        }
    }

    /** {@hide} */
    @Deprecated
    @UnsupportedAppUsage
    public static StorageManager from(Context context) {
        return context.getSystemService(StorageManager.class);
    }

    /**
     * Constructs a StorageManager object through which an application can
     * can communicate with the systems mount service.
     *
     * @param looper The {@link android.os.Looper} which events will be received on.
     *
     * <p>Applications can get instance of this class by calling
     * {@link android.content.Context#getSystemService(java.lang.String)} with an argument
     * of {@link android.content.Context#STORAGE_SERVICE}.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public StorageManager(Context context, Looper looper) throws ServiceNotFoundException {
        mContext = context;
        mResolver = context.getContentResolver();
        mLooper = looper;
        mStorageManager = IStorageManager.Stub.asInterface(ServiceManager.getServiceOrThrow("mount"));
        mAppOps = mContext.getSystemService(AppOpsManager.class);
    }

    /**
     * Registers a {@link android.os.storage.StorageEventListener StorageEventListener}.
     *
     * @param listener A {@link android.os.storage.StorageEventListener StorageEventListener} object.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public void registerListener(StorageEventListener listener) {
        synchronized (mDelegates) {
            final StorageEventListenerDelegate delegate = new StorageEventListenerDelegate(
                    mContext.getMainExecutor(), listener, new StorageVolumeCallback());
            try {
                mStorageManager.registerListener(delegate);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            mDelegates.add(delegate);
        }
    }

    /**
     * Unregisters a {@link android.os.storage.StorageEventListener StorageEventListener}.
     *
     * @param listener A {@link android.os.storage.StorageEventListener StorageEventListener} object.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public void unregisterListener(StorageEventListener listener) {
        synchronized (mDelegates) {
            for (Iterator<StorageEventListenerDelegate> i = mDelegates.iterator(); i.hasNext();) {
                final StorageEventListenerDelegate delegate = i.next();
                if (delegate.mListener == listener) {
                    try {
                        mStorageManager.unregisterListener(delegate);
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                    i.remove();
                }
            }
        }
    }

    /**
     * Callback that delivers {@link StorageVolume} related events.
     * <p>
     * For example, this can be used to detect when a volume changes to the
     * {@link Environment#MEDIA_MOUNTED} or {@link Environment#MEDIA_UNMOUNTED}
     * states.
     *
     * @see StorageManager#registerStorageVolumeCallback
     * @see StorageManager#unregisterStorageVolumeCallback
     */
    public static class StorageVolumeCallback {
        /**
         * Called when {@link StorageVolume#getState()} changes, such as
         * changing to the {@link Environment#MEDIA_MOUNTED} or
         * {@link Environment#MEDIA_UNMOUNTED} states.
         * <p>
         * The given argument is a snapshot in time and can be used to process
         * events in the order they occurred, or you can call
         * {@link StorageManager#getStorageVolumes()} to observe the latest
         * value.
         */
        public void onStateChanged(@NonNull StorageVolume volume) { }
    }

    /**
     * Registers the given callback to listen for {@link StorageVolume} changes.
     * <p>
     * For example, this can be used to detect when a volume changes to the
     * {@link Environment#MEDIA_MOUNTED} or {@link Environment#MEDIA_UNMOUNTED}
     * states.
     *
     * @see StorageManager#unregisterStorageVolumeCallback
     */
    public void registerStorageVolumeCallback(@CallbackExecutor @NonNull Executor executor,
            @NonNull StorageVolumeCallback callback) {
        synchronized (mDelegates) {
            final StorageEventListenerDelegate delegate = new StorageEventListenerDelegate(
                    executor, new StorageEventListener(), callback);
            try {
                mStorageManager.registerListener(delegate);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            mDelegates.add(delegate);
        }
    }

    /**
     * Unregisters the given callback from listening for {@link StorageVolume}
     * changes.
     *
     * @see StorageManager#registerStorageVolumeCallback
     */
    public void unregisterStorageVolumeCallback(@NonNull StorageVolumeCallback callback) {
        synchronized (mDelegates) {
            for (Iterator<StorageEventListenerDelegate> i = mDelegates.iterator(); i.hasNext();) {
                final StorageEventListenerDelegate delegate = i.next();
                if (delegate.mCallback == callback) {
                    try {
                        mStorageManager.unregisterListener(delegate);
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                    i.remove();
                }
            }
        }
    }

    /**
     * Enables USB Mass Storage (UMS) on the device.
     *
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void enableUsbMassStorage() {
    }

    /**
     * Disables USB Mass Storage (UMS) on the device.
     *
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void disableUsbMassStorage() {
    }

    /**
     * Query if a USB Mass Storage (UMS) host is connected.
     * @return true if UMS host is connected.
     *
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean isUsbMassStorageConnected() {
        return false;
    }

    /**
     * Query if a USB Mass Storage (UMS) is enabled on the device.
     * @return true if UMS host is enabled.
     *
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage
    public boolean isUsbMassStorageEnabled() {
        return false;
    }

    /**
     * Mount an Opaque Binary Blob (OBB) file.
     * <p>
     * The OBB will remain mounted for as long as the StorageManager reference
     * is held by the application. As soon as this reference is lost, the OBBs
     * in use will be unmounted. The {@link OnObbStateChangeListener} registered
     * with this call will receive the success or failure of this operation.
     * <p>
     * <em>Note:</em> you can only mount OBB files for which the OBB tag on the
     * file matches a package ID that is owned by the calling program's UID.
     * That is, shared UID applications can attempt to mount any other
     * application's OBB that shares its UID.
     *
     * @param rawPath the path to the OBB file
     * @param key must be <code>null</code>. Previously, some Android device
     *            implementations accepted a non-<code>null</code> key to mount
     *            an encrypted OBB file. However, this never worked reliably and
     *            is no longer supported.
     * @param listener will receive the success or failure of the operation
     * @return whether the mount call was successfully queued or not
     */
    public boolean mountObb(String rawPath, String key, OnObbStateChangeListener listener) {
        Preconditions.checkNotNull(rawPath, "rawPath cannot be null");
        Preconditions.checkArgument(key == null, "mounting encrypted OBBs is no longer supported");
        Preconditions.checkNotNull(listener, "listener cannot be null");

        try {
            final String canonicalPath = new File(rawPath).getCanonicalPath();
            final int nonce = mObbActionListener.addListener(listener);
            mStorageManager.mountObb(rawPath, canonicalPath, mObbActionListener, nonce,
                    getObbInfo(canonicalPath));
            return true;
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to resolve path: " + rawPath, e);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns a {@link PendingIntent} that can be used by Apps with
     * {@link android.Manifest.permission#MANAGE_EXTERNAL_STORAGE} permission
     * to launch the manageSpaceActivity for any App that implements it, irrespective of its
     * exported status.
     * <p>
     * Caller has the responsibility of supplying a valid packageName which has
     * manageSpaceActivity implemented.
     *
     * @param packageName package name for the App for which manageSpaceActivity is to be launched
     * @param requestCode for launching the activity
     * @return PendingIntent to launch the manageSpaceActivity if successful, null if the
     * packageName doesn't have a manageSpaceActivity.
     * @throws IllegalArgumentException an invalid packageName is supplied.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_EXTERNAL_STORAGE)
    @Nullable
    public PendingIntent getManageSpaceActivityIntent(
            @NonNull String packageName, int requestCode) {
        try {
            return mStorageManager.getManageSpaceActivityIntent(packageName,
                    requestCode);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private ObbInfo getObbInfo(String canonicalPath) {
        try {
            final ObbInfo obbInfo = ObbScanner.getObbInfo(canonicalPath);
            return obbInfo;
        } catch (IOException e) {
            throw new IllegalArgumentException("Couldn't get OBB info for " + canonicalPath, e);
        }
    }

    /**
     * Unmount an Opaque Binary Blob (OBB) file asynchronously. If the
     * <code>force</code> flag is true, it will kill any application needed to
     * unmount the given OBB (even the calling application).
     * <p>
     * The {@link OnObbStateChangeListener} registered with this call will
     * receive the success or failure of this operation.
     * <p>
     * <em>Note:</em> you can only mount OBB files for which the OBB tag on the
     * file matches a package ID that is owned by the calling program's UID.
     * That is, shared UID applications can obtain access to any other
     * application's OBB that shares its UID.
     * <p>
     *
     * @param rawPath path to the OBB file
     * @param force whether to kill any programs using this in order to unmount
     *            it
     * @param listener will receive the success or failure of the operation
     * @return whether the unmount call was successfully queued or not
     */
    public boolean unmountObb(String rawPath, boolean force, OnObbStateChangeListener listener) {
        Preconditions.checkNotNull(rawPath, "rawPath cannot be null");
        Preconditions.checkNotNull(listener, "listener cannot be null");

        try {
            final int nonce = mObbActionListener.addListener(listener);
            mStorageManager.unmountObb(rawPath, force, mObbActionListener, nonce);
            return true;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Check whether an Opaque Binary Blob (OBB) is mounted or not.
     *
     * @param rawPath path to OBB image
     * @return true if OBB is mounted; false if not mounted or on error
     */
    public boolean isObbMounted(String rawPath) {
        Preconditions.checkNotNull(rawPath, "rawPath cannot be null");

        try {
            return mStorageManager.isObbMounted(rawPath);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Check the mounted path of an Opaque Binary Blob (OBB) file. This will
     * give you the path to where you can obtain access to the internals of the
     * OBB.
     *
     * @param rawPath path to OBB image
     * @return absolute path to mounted OBB image data or <code>null</code> if
     *         not mounted or exception encountered trying to read status
     */
    public String getMountedObbPath(String rawPath) {
        Preconditions.checkNotNull(rawPath, "rawPath cannot be null");

        try {
            return mStorageManager.getMountedObbPath(rawPath);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** {@hide} */
    @UnsupportedAppUsage
    public @NonNull List<DiskInfo> getDisks() {
        try {
            return Arrays.asList(mStorageManager.getDisks());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** {@hide} */
    @UnsupportedAppUsage
    public @Nullable DiskInfo findDiskById(String id) {
        Preconditions.checkNotNull(id);
        // TODO; go directly to service to make this faster
        for (DiskInfo disk : getDisks()) {
            if (Objects.equals(disk.id, id)) {
                return disk;
            }
        }
        return null;
    }

    /** {@hide} */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public @Nullable VolumeInfo findVolumeById(String id) {
        Preconditions.checkNotNull(id);
        // TODO; go directly to service to make this faster
        for (VolumeInfo vol : getVolumes()) {
            if (Objects.equals(vol.id, id)) {
                return vol;
            }
        }
        return null;
    }

    /** {@hide} */
    @UnsupportedAppUsage
    public @Nullable VolumeInfo findVolumeByUuid(String fsUuid) {
        Preconditions.checkNotNull(fsUuid);
        // TODO; go directly to service to make this faster
        for (VolumeInfo vol : getVolumes()) {
            if (Objects.equals(vol.fsUuid, fsUuid)) {
                return vol;
            }
        }
        return null;
    }

    /** {@hide} */
    public @Nullable VolumeRecord findRecordByUuid(String fsUuid) {
        Preconditions.checkNotNull(fsUuid);
        // TODO; go directly to service to make this faster
        for (VolumeRecord rec : getVolumeRecords()) {
            if (Objects.equals(rec.fsUuid, fsUuid)) {
                return rec;
            }
        }
        return null;
    }

    /** {@hide} */
    public @Nullable VolumeInfo findPrivateForEmulated(VolumeInfo emulatedVol) {
        if (emulatedVol != null) {
            String id = emulatedVol.getId();
            int idx = id.indexOf(";");
            if (idx != -1) {
                id = id.substring(0, idx);
            }
            return findVolumeById(id.replace("emulated", "private"));
        } else {
            return null;
        }
    }

    /** {@hide} */
    @UnsupportedAppUsage
    public @Nullable VolumeInfo findEmulatedForPrivate(VolumeInfo privateVol) {
        if (privateVol != null) {
            return findVolumeById(privateVol.getId().replace("private", "emulated") + ";"
                    + mContext.getUserId());
        } else {
            return null;
        }
    }

    /** {@hide} */
    public @Nullable VolumeInfo findVolumeByQualifiedUuid(String volumeUuid) {
        if (Objects.equals(StorageManager.UUID_PRIVATE_INTERNAL, volumeUuid)) {
            return findVolumeById(VolumeInfo.ID_PRIVATE_INTERNAL);
        } else if (Objects.equals(StorageManager.UUID_PRIMARY_PHYSICAL, volumeUuid)) {
            return getPrimaryPhysicalVolume();
        } else {
            return findVolumeByUuid(volumeUuid);
        }
    }

    /**
     * Return a UUID identifying the storage volume that hosts the given
     * filesystem path.
     * <p>
     * If this path is hosted by the default internal storage of the device at
     * {@link Environment#getDataDirectory()}, the returned value will be
     * {@link #UUID_DEFAULT}.
     *
     * @throws IOException when the storage device hosting the given path isn't
     *             present, or when it doesn't have a valid UUID.
     */
    public @NonNull UUID getUuidForPath(@NonNull File path) throws IOException {
        Preconditions.checkNotNull(path);
        final String pathString = path.getCanonicalPath();
        if (FileUtils.contains(Environment.getDataDirectory().getAbsolutePath(), pathString)) {
            return UUID_DEFAULT;
        }
        try {
            for (VolumeInfo vol : mStorageManager.getVolumes(0)) {
                if (vol.path != null && FileUtils.contains(vol.path, pathString)
                        && vol.type != VolumeInfo.TYPE_PUBLIC && vol.type != VolumeInfo.TYPE_STUB) {
                    // TODO: verify that emulated adopted devices have UUID of
                    // underlying volume
                    try {
                        return convert(vol.fsUuid);
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                }
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        throw new FileNotFoundException("Failed to find a storage device for " + path);
    }

    /** {@hide} */
    public @NonNull File findPathForUuid(String volumeUuid) throws FileNotFoundException {
        final VolumeInfo vol = findVolumeByQualifiedUuid(volumeUuid);
        if (vol != null) {
            return vol.getPath();
        }
        throw new FileNotFoundException("Failed to find a storage device for " + volumeUuid);
    }

    /**
     * Test if the given file descriptor supports allocation of disk space using
     * {@link #allocateBytes(FileDescriptor, long)}.
     */
    public boolean isAllocationSupported(@NonNull FileDescriptor fd) {
        try {
            getUuidForPath(ParcelFileDescriptor.getFile(fd));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** {@hide} */
    @UnsupportedAppUsage
    public @NonNull List<VolumeInfo> getVolumes() {
        try {
            return Arrays.asList(mStorageManager.getVolumes(0));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** {@hide} */
    public @NonNull List<VolumeInfo> getWritablePrivateVolumes() {
        try {
            final ArrayList<VolumeInfo> res = new ArrayList<>();
            for (VolumeInfo vol : mStorageManager.getVolumes(0)) {
                if (vol.getType() == VolumeInfo.TYPE_PRIVATE && vol.isMountedWritable()) {
                    res.add(vol);
                }
            }
            return res;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** {@hide} */
    public @NonNull List<VolumeRecord> getVolumeRecords() {
        try {
            return Arrays.asList(mStorageManager.getVolumeRecords(0));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** {@hide} */
    @UnsupportedAppUsage
    public @Nullable String getBestVolumeDescription(VolumeInfo vol) {
        if (vol == null) return null;

        // Nickname always takes precedence when defined
        if (!TextUtils.isEmpty(vol.fsUuid)) {
            final VolumeRecord rec = findRecordByUuid(vol.fsUuid);
            if (rec != null && !TextUtils.isEmpty(rec.nickname)) {
                return rec.nickname;
            }
        }

        if (!TextUtils.isEmpty(vol.getDescription())) {
            return vol.getDescription();
        }

        if (vol.disk != null) {
            return vol.disk.getDescription();
        }

        return null;
    }

    /** {@hide} */
    @UnsupportedAppUsage
    public @Nullable VolumeInfo getPrimaryPhysicalVolume() {
        final List<VolumeInfo> vols = getVolumes();
        for (VolumeInfo vol : vols) {
            if (vol.isPrimaryPhysical()) {
                return vol;
            }
        }
        return null;
    }

    /** {@hide} */
    public void mount(String volId) {
        try {
            mStorageManager.mount(volId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** {@hide} */
    @UnsupportedAppUsage
    public void unmount(String volId) {
        try {
            mStorageManager.unmount(volId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** {@hide} */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void format(String volId) {
        try {
            mStorageManager.format(volId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** {@hide} */
    @Deprecated
    public long benchmark(String volId) {
        final CompletableFuture<PersistableBundle> result = new CompletableFuture<>();
        benchmark(volId, new IVoldTaskListener.Stub() {
            @Override
            public void onStatus(int status, PersistableBundle extras) {
                // Ignored
            }

            @Override
            public void onFinished(int status, PersistableBundle extras) {
                result.complete(extras);
            }
        });
        try {
            // Convert ms to ns
            return result.get(3, TimeUnit.MINUTES).getLong("run", Long.MAX_VALUE) * 1000000;
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    /** {@hide} */
    public void benchmark(String volId, IVoldTaskListener listener) {
        try {
            mStorageManager.benchmark(volId, listener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** {@hide} */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void partitionPublic(String diskId) {
        try {
            mStorageManager.partitionPublic(diskId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** {@hide} */
    public void partitionPrivate(String diskId) {
        try {
            mStorageManager.partitionPrivate(diskId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** {@hide} */
    public void partitionMixed(String diskId, int ratio) {
        try {
            mStorageManager.partitionMixed(diskId, ratio);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** {@hide} */
    public void wipeAdoptableDisks() {
        // We only wipe devices in "adoptable" locations, which are in a
        // long-term stable slot/location on the device, where apps have a
        // reasonable chance of storing sensitive data. (Apps need to go through
        // SAF to write to transient volumes.)
        final List<DiskInfo> disks = getDisks();
        for (DiskInfo disk : disks) {
            final String diskId = disk.getId();
            if (disk.isAdoptable()) {
                Slog.d(TAG, "Found adoptable " + diskId + "; wiping");
                try {
                    // TODO: switch to explicit wipe command when we have it,
                    // for now rely on the fact that vfat format does a wipe
                    mStorageManager.partitionPublic(diskId);
                } catch (Exception e) {
                    Slog.w(TAG, "Failed to wipe " + diskId + ", but soldiering onward", e);
                }
            } else {
                Slog.d(TAG, "Ignorning non-adoptable disk " + disk.getId());
            }
        }
    }

    /** {@hide} */
    public void setVolumeNickname(String fsUuid, String nickname) {
        try {
            mStorageManager.setVolumeNickname(fsUuid, nickname);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** {@hide} */
    public void setVolumeInited(String fsUuid, boolean inited) {
        try {
            mStorageManager.setVolumeUserFlags(fsUuid, inited ? VolumeRecord.USER_FLAG_INITED : 0,
                    VolumeRecord.USER_FLAG_INITED);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** {@hide} */
    public void setVolumeSnoozed(String fsUuid, boolean snoozed) {
        try {
            mStorageManager.setVolumeUserFlags(fsUuid, snoozed ? VolumeRecord.USER_FLAG_SNOOZED : 0,
                    VolumeRecord.USER_FLAG_SNOOZED);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** {@hide} */
    public void forgetVolume(String fsUuid) {
        try {
            mStorageManager.forgetVolume(fsUuid);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * This is not the API you're looking for.
     *
     * @see PackageManager#getPrimaryStorageCurrentVolume()
     * @hide
     */
    public String getPrimaryStorageUuid() {
        try {
            return mStorageManager.getPrimaryStorageUuid();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * This is not the API you're looking for.
     *
     * @see PackageManager#movePrimaryStorage(VolumeInfo)
     * @hide
     */
    public void setPrimaryStorageUuid(String volumeUuid, IPackageMoveObserver callback) {
        try {
            mStorageManager.setPrimaryStorageUuid(volumeUuid, callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return the {@link StorageVolume} that contains the given file, or
     * {@code null} if none.
     */
    public @Nullable StorageVolume getStorageVolume(@NonNull File file) {
        return getStorageVolume(getVolumeList(), file);
    }

    /**
     * Return the {@link StorageVolume} that contains the given
     * {@link MediaStore} item.
     */
    public @NonNull StorageVolume getStorageVolume(@NonNull Uri uri) {
        String volumeName = MediaStore.getVolumeName(uri);

        // When Uri is pointing at a synthetic volume, we're willing to query to
        // resolve the actual volume name
        if (Objects.equals(volumeName, MediaStore.VOLUME_EXTERNAL)) {
            try (Cursor c = mContext.getContentResolver().query(uri,
                    new String[] { MediaStore.MediaColumns.VOLUME_NAME }, null, null)) {
                if (c.moveToFirst()) {
                    volumeName = c.getString(0);
                }
            }
        }

        switch (volumeName) {
            case MediaStore.VOLUME_EXTERNAL_PRIMARY:
                return getPrimaryStorageVolume();
            default:
                for (StorageVolume vol : getStorageVolumes()) {
                    if (Objects.equals(vol.getMediaStoreVolumeName(), volumeName)) {
                        return vol;
                    }
                }
        }
        throw new IllegalStateException("Unknown volume for " + uri);
    }

    /** {@hide} */
    public static @Nullable StorageVolume getStorageVolume(File file, int userId) {
        return getStorageVolume(getVolumeList(userId, 0), file);
    }

    /** {@hide} */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private static @Nullable StorageVolume getStorageVolume(StorageVolume[] volumes, File file) {
        if (file == null) {
            return null;
        }
        final String path = file.getAbsolutePath();
        if (path.startsWith(DEPRECATE_DATA_PREFIX)) {
            final Uri uri = ContentResolver.translateDeprecatedDataPath(path);
            return AppGlobals.getInitialApplication().getSystemService(StorageManager.class)
                    .getStorageVolume(uri);
        }
        try {
            file = file.getCanonicalFile();
        } catch (IOException ignored) {
            Slog.d(TAG, "Could not get canonical path for " + file);
            return null;
        }
        for (StorageVolume volume : volumes) {
            File volumeFile = volume.getPathFile();
            try {
                volumeFile = volumeFile.getCanonicalFile();
            } catch (IOException ignored) {
                continue;
            }
            if (FileUtils.contains(volumeFile, file)) {
                return volume;
            }
        }
        return null;
    }

    /**
     * Gets the state of a volume via its mountpoint.
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage
    public @NonNull String getVolumeState(String mountPoint) {
        final StorageVolume vol = getStorageVolume(new File(mountPoint));
        if (vol != null) {
            return vol.getState();
        } else {
            return Environment.MEDIA_UNKNOWN;
        }
    }

    /**
     * Return the list of shared/external storage volumes currently available to
     * the calling user.
     * <p>
     * These storage volumes are actively attached to the device, but may be in
     * any mount state, as returned by {@link StorageVolume#getState()}. Returns
     * both the primary shared storage device and any attached external volumes,
     * including SD cards and USB drives.
     */
    public @NonNull List<StorageVolume> getStorageVolumes() {
        final ArrayList<StorageVolume> res = new ArrayList<>();
        Collections.addAll(res,
                getVolumeList(mContext.getUserId(), FLAG_REAL_STATE | FLAG_INCLUDE_INVISIBLE));
        return res;
    }

    /**
     * Return the list of shared/external storage volumes currently available to
     * the calling user and the user it shares media with. Please refer to
     * <a href="https://source.android.com/compatibility/12/android-12-cdd#95_multi-user_support">
     *     multi-user support</a> for more details.
     *
     * <p>
     * This is similar to {@link StorageManager#getStorageVolumes()} except that the result also
     * includes the volumes belonging to any user it shares media with
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_EXTERNAL_STORAGE)
    public @NonNull List<StorageVolume> getStorageVolumesIncludingSharedProfiles() {
        final ArrayList<StorageVolume> res = new ArrayList<>();
        Collections.addAll(res,
                getVolumeList(mContext.getUserId(),
                        FLAG_REAL_STATE | FLAG_INCLUDE_INVISIBLE | FLAG_INCLUDE_SHARED_PROFILE));
        return res;
    }

    /**
     * Return the list of shared/external storage volumes both currently and
     * recently available to the calling user.
     * <p>
     * Recently available storage volumes are likely to reappear in the future,
     * so apps are encouraged to preserve any indexed metadata related to these
     * volumes to optimize user experiences.
     */
    public @NonNull List<StorageVolume> getRecentStorageVolumes() {
        final ArrayList<StorageVolume> res = new ArrayList<>();
        Collections.addAll(res,
                getVolumeList(mContext.getUserId(),
                        FLAG_REAL_STATE | FLAG_INCLUDE_INVISIBLE | FLAG_INCLUDE_RECENT));
        return res;
    }

    /**
     * Return the primary shared/external storage volume available to the
     * current user. This volume is the same storage device returned by
     * {@link Environment#getExternalStorageDirectory()} and
     * {@link Context#getExternalFilesDir(String)}.
     */
    public @NonNull StorageVolume getPrimaryStorageVolume() {
        return getVolumeList(mContext.getUserId(), FLAG_REAL_STATE | FLAG_INCLUDE_INVISIBLE)[0];
    }

    /** {@hide} */
    public static Pair<String, Long> getPrimaryStoragePathAndSize() {
        return Pair.create(null,
                FileUtils.roundStorageSize(Environment.getDataDirectory().getTotalSpace()
                    + Environment.getRootDirectory().getTotalSpace()));
    }

    /** {@hide} */
    public long getPrimaryStorageSize() {
        return FileUtils.roundStorageSize(Environment.getDataDirectory().getTotalSpace()
                + Environment.getRootDirectory().getTotalSpace());
    }

    /** {@hide} */
    public long getInternalStorageBlockDeviceSize() {
        try {
            return mStorageManager.getInternalStorageBlockDeviceSize();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** {@hide} */
    public void mkdirs(File file) {
        BlockGuard.getVmPolicy().onPathAccess(file.getAbsolutePath());
        try {
            mStorageManager.mkdirs(mContext.getOpPackageName(), file.getAbsolutePath());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @removed */
    public @NonNull StorageVolume[] getVolumeList() {
        return getVolumeList(mContext.getUserId(), 0);
    }

    /** {@hide} */
    @UnsupportedAppUsage
    public static @NonNull StorageVolume[] getVolumeList(int userId, int flags) {
        final IStorageManager storageManager = IStorageManager.Stub.asInterface(
                ServiceManager.getService("mount"));
        try {
            String packageName = ActivityThread.currentOpPackageName();
            if (packageName == null) {
                // Package name can be null if the activity thread is running but the app
                // hasn't bound yet. In this case we fall back to the first package in the
                // current UID. This works for runtime permissions as permission state is
                // per UID and permission realted app ops are updated for all UID packages.
                String[] packageNames = ActivityThread.getPackageManager().getPackagesForUid(
                        android.os.Process.myUid());
                if (packageNames == null || packageNames.length <= 0) {
                    Log.w(TAG, "Missing package names; no storage volumes available");
                    return new StorageVolume[0];
                }
                packageName = packageNames[0];
            }
            return storageManager.getVolumeList(userId, packageName, flags);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns list of paths for all mountable volumes.
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage
    public @NonNull String[] getVolumePaths() {
        StorageVolume[] volumes = getVolumeList();
        int count = volumes.length;
        String[] paths = new String[count];
        for (int i = 0; i < count; i++) {
            paths[i] = volumes[i].getPath();
        }
        return paths;
    }

    /** @removed */
    public @NonNull StorageVolume getPrimaryVolume() {
        return getPrimaryVolume(getVolumeList());
    }

    /** {@hide} */
    public static @NonNull StorageVolume getPrimaryVolume(StorageVolume[] volumes) {
        for (StorageVolume volume : volumes) {
            if (volume.isPrimary()) {
                return volume;
            }
        }
        throw new IllegalStateException("Missing primary storage");
    }

    /**
     * Devices having above STORAGE_THRESHOLD_PERCENT_HIGH of total space free are considered to be
     * in high free space category.
     *
     * @hide
     */
    public static final int DEFAULT_STORAGE_THRESHOLD_PERCENT_HIGH = 20;
    /** {@hide} */
    @TestApi
    public static final String
            STORAGE_THRESHOLD_PERCENT_HIGH_KEY = "storage_threshold_percent_high";
    /**
     * Devices having below STORAGE_THRESHOLD_PERCENT_LOW of total space free are considered to be
     * in low free space category and can be configured via
     * Settings.Global.SYS_STORAGE_THRESHOLD_PERCENTAGE.
     *
     * @hide
     */
    public static final int DEFAULT_STORAGE_THRESHOLD_PERCENT_LOW = 5;
    /**
     * For devices in high free space category, CACHE_RESERVE_PERCENT_HIGH percent of total space is
     * allocated for cache.
     *
     * @hide
     */
    public static final int DEFAULT_CACHE_RESERVE_PERCENT_HIGH = 10;
    /** {@hide} */
    @TestApi
    public static final String CACHE_RESERVE_PERCENT_HIGH_KEY = "cache_reserve_percent_high";
    /**
     * For devices in low free space category, CACHE_RESERVE_PERCENT_LOW percent of total space is
     * allocated for cache.
     *
     * @hide
     */
    public static final int DEFAULT_CACHE_RESERVE_PERCENT_LOW = 2;
    /** {@hide} */
    @TestApi
    public static final String CACHE_RESERVE_PERCENT_LOW_KEY = "cache_reserve_percent_low";

    private static final long DEFAULT_THRESHOLD_MAX_BYTES = DataUnit.MEBIBYTES.toBytes(500);

    private static final long DEFAULT_FULL_THRESHOLD_BYTES = DataUnit.MEBIBYTES.toBytes(1);

    /**
     * Return the number of available bytes until the given path is considered
     * running low on storage.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public long getStorageBytesUntilLow(File path) {
        return path.getUsableSpace() - getStorageFullBytes(path);
    }

    /**
     * Return the number of available bytes at which the given path is
     * considered running low on storage.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public long getStorageLowBytes(File path) {
        final long lowPercent = Settings.Global.getInt(mResolver,
                Settings.Global.SYS_STORAGE_THRESHOLD_PERCENTAGE,
                DEFAULT_STORAGE_THRESHOLD_PERCENT_LOW);
        final long lowBytes = (path.getTotalSpace() * lowPercent) / 100;

        final long maxLowBytes = Settings.Global.getLong(mResolver,
                Settings.Global.SYS_STORAGE_THRESHOLD_MAX_BYTES, DEFAULT_THRESHOLD_MAX_BYTES);

        return Math.min(lowBytes, maxLowBytes);
    }

    /**
     * Compute the minimum number of bytes of storage on the device that could
     * be reserved for cached data depending on the device state which is then passed on
     * to getStorageCacheBytes.
     *
     * Input File path must point to a storage volume.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @TestApi
    @SuppressLint("StreamFiles")
    public long computeStorageCacheBytes(@NonNull File path) {
        final int storageThresholdPercentHigh = DeviceConfig.getInt(
                DeviceConfig.NAMESPACE_STORAGE_NATIVE_BOOT,
                STORAGE_THRESHOLD_PERCENT_HIGH_KEY, DEFAULT_STORAGE_THRESHOLD_PERCENT_HIGH);
        final int cacheReservePercentHigh = DeviceConfig.getInt(
                DeviceConfig.NAMESPACE_STORAGE_NATIVE_BOOT,
                CACHE_RESERVE_PERCENT_HIGH_KEY, DEFAULT_CACHE_RESERVE_PERCENT_HIGH);
        final int cacheReservePercentLow = DeviceConfig.getInt(
                DeviceConfig.NAMESPACE_STORAGE_NATIVE_BOOT,
                CACHE_RESERVE_PERCENT_LOW_KEY, DEFAULT_CACHE_RESERVE_PERCENT_LOW);
        final long totalBytes = path.getTotalSpace();
        final long usableBytes = path.getUsableSpace();
        final long storageThresholdHighBytes = totalBytes * storageThresholdPercentHigh / 100;
        final long storageThresholdLowBytes = getStorageLowBytes(path);
        long result;
        if (usableBytes > storageThresholdHighBytes) {
            // If free space is >storageThresholdPercentHigh of total space,
            // reserve cacheReservePercentHigh of total space
            result = totalBytes * cacheReservePercentHigh / 100;
        } else if (usableBytes < storageThresholdLowBytes) {
            // If free space is <min(storageThresholdPercentLow of total space, 500MB),
            // reserve cacheReservePercentLow of total space
            result = totalBytes * cacheReservePercentLow / 100;
        } else {
            // Else, linearly interpolate the amount of space to reserve
            double slope = (cacheReservePercentHigh - cacheReservePercentLow) * totalBytes
                    / (100.0 * (storageThresholdHighBytes - storageThresholdLowBytes));
            double intercept = totalBytes * cacheReservePercentLow / 100.0
                    - storageThresholdLowBytes * slope;
            result = Math.round(slope * usableBytes + intercept);
        }
        return result;
    }

    /**
     * Return the minimum number of bytes of storage on the device that should
     * be reserved for cached data.
     *
     * @hide
     */
    public long getStorageCacheBytes(@NonNull File path, @AllocateFlags int flags) {
        if ((flags & StorageManager.FLAG_ALLOCATE_AGGRESSIVE) != 0) {
            return 0;
        } else if ((flags & StorageManager.FLAG_ALLOCATE_DEFY_ALL_RESERVED) != 0) {
            return 0;
        } else if ((flags & StorageManager.FLAG_ALLOCATE_DEFY_HALF_RESERVED) != 0) {
            return computeStorageCacheBytes(path) / 2;
        } else {
            return computeStorageCacheBytes(path);
        }
    }

    /**
     * Return the number of available bytes at which the given path is
     * considered full.
     *
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public long getStorageFullBytes(File path) {
        return Settings.Global.getLong(mResolver, Settings.Global.SYS_STORAGE_FULL_THRESHOLD_BYTES,
                DEFAULT_FULL_THRESHOLD_BYTES);
    }

    /**
     * Creates the keys for a user's credential-encrypted (CE) and device-encrypted (DE) storage.
     * <p>
     * This creates the user's CE key and DE key for internal storage, then adds them to the kernel.
     * Then, if the user is not ephemeral, this stores the DE key (encrypted) on flash.  (The CE key
     * is not stored until {@link IStorageManager#setCeStorageProtection()}.)
     * <p>
     * This does not create the CE and DE directories themselves.  For that, see {@link
     * #prepareUserStorage()}.
     * <p>
     * This is only intended to be called by UserManagerService, as part of creating a user.
     *
     * @param userId ID of the user
     * @param ephemeral whether the user is ephemeral
     * @throws RuntimeException on error.  The user's keys already existing is considered an error.
     * @hide
     */
    public void createUserStorageKeys(int userId, boolean ephemeral) {
        try {
            mStorageManager.createUserStorageKeys(userId, ephemeral);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Destroys the keys for a user's credential-encrypted (CE) and device-encrypted (DE) storage.
     * <p>
     * This evicts the keys from the kernel (if present), which "locks" the corresponding
     * directories.  Then, this deletes the encrypted keys from flash.  This operates on all the
     * user's CE and DE keys, for both internal and adoptable storage.
     * <p>
     * This does not destroy the CE and DE directories themselves.  For that, see {@link
     * #destroyUserStorage()}.
     * <p>
     * This is only intended to be called by UserManagerService, as part of removing a user.
     *
     * @param userId ID of the user
     * @throws RuntimeException on error.  On error, as many things as possible are still destroyed.
     * @hide
     */
    public void destroyUserStorageKeys(int userId) {
        try {
            mStorageManager.destroyUserStorageKeys(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Locks the user's credential-encrypted (CE) storage.
     *
     * @hide
     */
    public void lockCeStorage(int userId) {
        try {
            mStorageManager.lockCeStorage(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** {@hide} */
    public void prepareUserStorage(String volumeUuid, int userId, int flags) {
        try {
            mStorageManager.prepareUserStorage(volumeUuid, userId, flags);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** {@hide} */
    public void destroyUserStorage(String volumeUuid, int userId, int flags) {
        try {
            mStorageManager.destroyUserStorage(volumeUuid, userId, flags);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** {@hide} */
    @TestApi
    public static boolean isUserKeyUnlocked(int userId) {
        return isCeStorageUnlocked(userId);
    }

    /**
     * Returns true if the user's credential-encrypted (CE) storage is unlocked.
     *
     * @hide
     */
    public static boolean isCeStorageUnlocked(int userId) {
        if (sStorageManager == null) {
            sStorageManager = IStorageManager.Stub
                    .asInterface(ServiceManager.getService("mount"));
        }
        if (sStorageManager == null) {
            Slog.w(TAG, "Early during boot, assuming CE storage is locked");
            return false;
        }
        final long token = Binder.clearCallingIdentity();
        try {
            return sStorageManager.isCeStorageUnlocked(userId);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Return if data stored at or under the given path will be encrypted while
     * at rest. This can help apps avoid the overhead of double-encrypting data.
     */
    public boolean isEncrypted(File file) {
        if (FileUtils.contains(Environment.getDataDirectory(), file)) {
            return isEncrypted();
        } else if (FileUtils.contains(Environment.getExpandDirectory(), file)) {
            return true;
        }
        // TODO: extend to support shared storage
        return false;
    }

    /** {@hide}
     * Is this device encrypted?
     * <p>
     * Note: all devices launching with Android 10 (API level 29) or later are
     * required to be encrypted.  This should only ever return false for
     * in-development devices on which encryption has not yet been configured.
     *
     * @return true if encrypted, false if not encrypted
     */
    public static boolean isEncrypted() {
        return RoSystemProperties.CRYPTO_ENCRYPTED;
    }

    /** {@hide}
     * Does this device have file-based encryption (FBE) enabled?
     * @return true if the device has file-based encryption enabled.
     */
    public static boolean isFileEncrypted() {
        if (!isEncrypted()) {
            return false;
        }
        return RoSystemProperties.CRYPTO_FILE_ENCRYPTED;
    }

    /** {@hide} */
    public static boolean hasAdoptable() {
        switch (SystemProperties.get(PROP_ADOPTABLE)) {
            case "force_on":
                return true;
            case "force_off":
                return false;
            default:
                return SystemProperties.getBoolean(PROP_HAS_ADOPTABLE, false);
        }
    }

    /**
     * Return if the currently booted device has the "isolated storage" feature
     * flag enabled.
     *
     * @hide
     */
    @SystemApi
    public static boolean hasIsolatedStorage() {
        return false;
    }

    /**
     * @deprecated disabled now that FUSE has been replaced by sdcardfs
     * @hide
     */
    @Deprecated
    public static File maybeTranslateEmulatedPathToInternal(File path) {
        // Disabled now that FUSE has been replaced by sdcardfs
        return path;
    }

    /**
     * Translate given shared storage path from a path in an app sandbox
     * namespace to a path in the system namespace.
     *
     * @hide
     */
    public File translateAppToSystem(File file, int pid, int uid) {
        return file;
    }

    /**
     * Translate given shared storage path from a path in the system namespace
     * to a path in an app sandbox namespace.
     *
     * @hide
     */
    public File translateSystemToApp(File file, int pid, int uid) {
        return file;
    }

    /**
     * Check that given app holds both permission and appop.
     * @hide
     */
    public static boolean checkPermissionAndAppOp(Context context, boolean enforce, int pid,
            int uid, String packageName, @NonNull String featureId, String permission, int op) {
        return checkPermissionAndAppOp(context, enforce, pid, uid, packageName, featureId,
                permission, op, true);
    }

    /**
     * Check that given app holds both permission and appop but do not noteOp.
     * @hide
     */
    public static boolean checkPermissionAndCheckOp(Context context, boolean enforce,
            int pid, int uid, String packageName, String permission, int op) {
        return checkPermissionAndAppOp(context, enforce, pid, uid, packageName,
                null /* featureId is not needed when not noting */, permission, op, false);
    }

    /**
     * Check that given app holds both permission and appop.
     * @hide
     */
    private static boolean checkPermissionAndAppOp(Context context, boolean enforce, int pid,
            int uid, String packageName, @Nullable String featureId, String permission, int op,
            boolean note) {
        if (context.checkPermission(permission, pid, uid) != PERMISSION_GRANTED) {
            if (enforce) {
                throw new SecurityException(
                        "Permission " + permission + " denied for package " + packageName);
            } else {
                return false;
            }
        }

        AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
        final int mode;
        if (note) {
            mode = appOps.noteOpNoThrow(op, uid, packageName, featureId, null);
        } else {
            try {
                appOps.checkPackage(uid, packageName);
            } catch (SecurityException e) {
                if (enforce) {
                    throw e;
                } else {
                    return false;
                }
            }
            mode = appOps.checkOpNoThrow(op, uid, packageName);
        }
        switch (mode) {
            case AppOpsManager.MODE_ALLOWED:
                return true;
            case AppOpsManager.MODE_DEFAULT:
            case AppOpsManager.MODE_IGNORED:
            case AppOpsManager.MODE_ERRORED:
                if (enforce) {
                    throw new SecurityException("Op " + AppOpsManager.opToName(op) + " "
                            + AppOpsManager.modeToName(mode) + " for package " + packageName);
                } else {
                    return false;
                }
            default:
                throw new IllegalStateException(
                        AppOpsManager.opToName(op) + " has unknown mode "
                                + AppOpsManager.modeToName(mode));
        }
    }

    private boolean checkPermissionAndAppOp(boolean enforce, int pid, int uid, String packageName,
            @Nullable String featureId, String permission, int op) {
        return checkPermissionAndAppOp(mContext, enforce, pid, uid, packageName, featureId,
                permission, op);
    }

    private boolean noteAppOpAllowingLegacy(boolean enforce,
            int pid, int uid, String packageName, @Nullable String featureId, int op) {
        final int mode = mAppOps.noteOpNoThrow(op, uid, packageName, featureId, null);
        switch (mode) {
            case AppOpsManager.MODE_ALLOWED:
                return true;
            case AppOpsManager.MODE_DEFAULT:
            case AppOpsManager.MODE_IGNORED:
            case AppOpsManager.MODE_ERRORED:
                // Legacy apps technically have the access granted by this op,
                // even when the op is denied
                if ((mAppOps.checkOpNoThrow(OP_LEGACY_STORAGE, uid,
                        packageName) == AppOpsManager.MODE_ALLOWED)) return true;

                if (enforce) {
                    throw new SecurityException("Op " + AppOpsManager.opToName(op) + " "
                            + AppOpsManager.modeToName(mode) + " for package " + packageName);
                } else {
                    return false;
                }
            default:
                throw new IllegalStateException(
                        AppOpsManager.opToName(op) + " has unknown mode "
                                + AppOpsManager.modeToName(mode));
        }
    }

    // Callers must hold both the old and new permissions, so that we can
    // handle obscure cases like when an app targets Q but was installed on
    // a device that was originally running on P before being upgraded to Q.

    /**
     * @deprecated This method should not be used since it check slegacy permissions,
     * no longer valid. Clients should check the appropriate permissions directly
     * instead (e.g. READ_MEDIA_IMAGES).
     *
     * {@hide}
     */
    @Deprecated
    public boolean checkPermissionReadImages(boolean enforce,
            int pid, int uid, String packageName, @Nullable String featureId) {
        if (!checkExternalStoragePermissionAndAppOp(enforce, pid, uid, packageName, featureId,
                READ_EXTERNAL_STORAGE, OP_READ_EXTERNAL_STORAGE)) {
            return false;
        }
        return noteAppOpAllowingLegacy(enforce, pid, uid, packageName, featureId,
                OP_READ_MEDIA_IMAGES);
    }

    private boolean checkExternalStoragePermissionAndAppOp(boolean enforce,
            int pid, int uid, String packageName, @Nullable String featureId, String permission,
            int op) {
        // First check if app has MANAGE_EXTERNAL_STORAGE.
        final int mode = mAppOps.noteOpNoThrow(OP_MANAGE_EXTERNAL_STORAGE, uid, packageName,
                featureId, null);
        if (mode == AppOpsManager.MODE_ALLOWED) {
            return true;
        }
        if (mode == AppOpsManager.MODE_DEFAULT && mContext.checkPermission(
                  MANAGE_EXTERNAL_STORAGE, pid, uid) == PERMISSION_GRANTED) {
            return true;
        }
        // If app doesn't have MANAGE_EXTERNAL_STORAGE, then check if it has requested granular
        // permission.
        return checkPermissionAndAppOp(enforce, pid, uid, packageName, featureId, permission, op);
    }

    /** {@hide} */
    @VisibleForTesting
    public @NonNull ParcelFileDescriptor openProxyFileDescriptor(
            int mode, ProxyFileDescriptorCallback callback, Handler handler, ThreadFactory factory)
                    throws IOException {
        Preconditions.checkNotNull(callback);
        MetricsLogger.count(mContext, "storage_open_proxy_file_descriptor", 1);
        // Retry is needed because the mount point mFuseAppLoop is using may be unmounted before
        // invoking StorageManagerService#openProxyFileDescriptor. In this case, we need to re-mount
        // the bridge by calling mountProxyFileDescriptorBridge.
        while (true) {
            try {
                synchronized (mFuseAppLoopLock) {
                    boolean newlyCreated = false;
                    if (mFuseAppLoop == null) {
                        final AppFuseMount mount = mStorageManager.mountProxyFileDescriptorBridge();
                        if (mount == null) {
                            throw new IOException("Failed to mount proxy bridge");
                        }
                        mFuseAppLoop = new FuseAppLoop(mount.mountPointId, mount.fd, factory);
                        newlyCreated = true;
                    }
                    if (handler == null) {
                        handler = new Handler(Looper.getMainLooper());
                    }
                    try {
                        final int fileId = mFuseAppLoop.registerCallback(callback, handler);
                        final ParcelFileDescriptor pfd = mStorageManager.openProxyFileDescriptor(
                                mFuseAppLoop.getMountPointId(), fileId, mode);
                        if (pfd == null) {
                            mFuseAppLoop.unregisterCallback(fileId);
                            throw new FuseUnavailableMountException(
                                    mFuseAppLoop.getMountPointId());
                        }
                        return pfd;
                    } catch (FuseUnavailableMountException exception) {
                        // The bridge is being unmounted. Tried to recreate it unless the bridge was
                        // just created.
                        if (newlyCreated) {
                            throw new IOException(exception);
                        }
                        mFuseAppLoop = null;
                        continue;
                    }
                }
            } catch (RemoteException e) {
                // Cannot recover from remote exception.
                throw new IOException(e);
            }
        }
    }

    /** {@hide} */
    public @NonNull ParcelFileDescriptor openProxyFileDescriptor(
            int mode, ProxyFileDescriptorCallback callback)
                    throws IOException {
        return openProxyFileDescriptor(mode, callback, null, null);
    }

    /**
     * Opens a seekable {@link ParcelFileDescriptor} that proxies all low-level
     * I/O requests back to the given {@link ProxyFileDescriptorCallback}.
     * <p>
     * This can be useful when you want to provide quick access to a large file
     * that isn't backed by a real file on disk, such as a file on a network
     * share, cloud storage service, etc. As an example, you could respond to a
     * {@link ContentResolver#openFileDescriptor(android.net.Uri, String)}
     * request by returning a {@link ParcelFileDescriptor} created with this
     * method, and then stream the content on-demand as requested.
     * <p>
     * Another useful example might be where you have an encrypted file that
     * you're willing to decrypt on-demand, but where you want to avoid
     * persisting the cleartext version.
     *
     * @param mode The desired access mode, must be one of
     *            {@link ParcelFileDescriptor#MODE_READ_ONLY},
     *            {@link ParcelFileDescriptor#MODE_WRITE_ONLY}, or
     *            {@link ParcelFileDescriptor#MODE_READ_WRITE}
     * @param callback Callback to process file operation requests issued on
     *            returned file descriptor.
     * @param handler Handler that invokes callback methods.
     * @return Seekable ParcelFileDescriptor.
     * @throws IOException
     */
    public @NonNull ParcelFileDescriptor openProxyFileDescriptor(
            int mode, ProxyFileDescriptorCallback callback, Handler handler)
                    throws IOException {
        Preconditions.checkNotNull(handler);
        return openProxyFileDescriptor(mode, callback, handler, null);
    }

    /** {@hide} */
    @VisibleForTesting
    public int getProxyFileDescriptorMountPointId() {
        synchronized (mFuseAppLoopLock) {
            return mFuseAppLoop != null ? mFuseAppLoop.getMountPointId() : -1;
        }
    }

    /**
     * Return quota size in bytes for all cached data belonging to the calling
     * app on the given storage volume.
     * <p>
     * If your app goes above this quota, your cached files will be some of the
     * first to be deleted when additional disk space is needed. Conversely, if
     * your app stays under this quota, your cached files will be some of the
     * last to be deleted when additional disk space is needed.
     * <p>
     * This quota will change over time depending on how frequently the user
     * interacts with your app, and depending on how much system-wide disk space
     * is used.
     * <p class="note">
     * Note: if your app uses the {@code android:sharedUserId} manifest feature,
     * then cached data for all packages in your shared UID is tracked together
     * as a single unit.
     * </p>
     *
     * @param storageUuid the UUID of the storage volume that you're interested
     *            in. The UUID for a specific path can be obtained using
     *            {@link #getUuidForPath(File)}.
     * @throws IOException when the storage device isn't present, or when it
     *             doesn't support cache quotas.
     * @see #getCacheSizeBytes(UUID)
     */
    @WorkerThread
    public @BytesLong long getCacheQuotaBytes(@NonNull UUID storageUuid) throws IOException {
        try {
            final ApplicationInfo app = mContext.getApplicationInfo();
            return mStorageManager.getCacheQuotaBytes(convert(storageUuid), app.uid);
        } catch (ParcelableException e) {
            e.maybeRethrow(IOException.class);
            throw new RuntimeException(e);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return total size in bytes of all cached data belonging to the calling
     * app on the given storage volume.
     * <p>
     * Cached data tracked by this method always includes
     * {@link Context#getCacheDir()} and {@link Context#getCodeCacheDir()}, and
     * it also includes {@link Context#getExternalCacheDir()} if the primary
     * shared/external storage is hosted on the same storage device as your
     * private data.
     * <p class="note">
     * Note: if your app uses the {@code android:sharedUserId} manifest feature,
     * then cached data for all packages in your shared UID is tracked together
     * as a single unit.
     * </p>
     *
     * @param storageUuid the UUID of the storage volume that you're interested
     *            in. The UUID for a specific path can be obtained using
     *            {@link #getUuidForPath(File)}.
     * @throws IOException when the storage device isn't present, or when it
     *             doesn't support cache quotas.
     * @see #getCacheQuotaBytes(UUID)
     */
    @WorkerThread
    public @BytesLong long getCacheSizeBytes(@NonNull UUID storageUuid) throws IOException {
        try {
            final ApplicationInfo app = mContext.getApplicationInfo();
            return mStorageManager.getCacheSizeBytes(convert(storageUuid), app.uid);
        } catch (ParcelableException e) {
            e.maybeRethrow(IOException.class);
            throw new RuntimeException(e);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }


    /** @hide */
    @IntDef(prefix = { "MOUNT_MODE_" }, value = {
            MOUNT_MODE_EXTERNAL_NONE,
            MOUNT_MODE_EXTERNAL_DEFAULT,
            MOUNT_MODE_EXTERNAL_INSTALLER,
            MOUNT_MODE_EXTERNAL_PASS_THROUGH,
            MOUNT_MODE_EXTERNAL_ANDROID_WRITABLE
    })
    @Retention(RetentionPolicy.SOURCE)
    /** @hide */
    public @interface MountMode {}

    /**
     * No external storage should be mounted.
     * @hide
     */
    @SystemApi
    public static final int MOUNT_MODE_EXTERNAL_NONE = IVold.REMOUNT_MODE_NONE;
    /**
     * Default external storage should be mounted.
     * @hide
     */
    @SystemApi
    public static final int MOUNT_MODE_EXTERNAL_DEFAULT = IVold.REMOUNT_MODE_DEFAULT;
    /**
     * Mount mode for package installers which should give them access to
     * all obb dirs in addition to their package sandboxes
     * @hide
     */
    @SystemApi
    public static final int MOUNT_MODE_EXTERNAL_INSTALLER = IVold.REMOUNT_MODE_INSTALLER;
    /**
     * The lower file system should be bind mounted directly on external storage
     * @hide
     */
    @SystemApi
    public static final int MOUNT_MODE_EXTERNAL_PASS_THROUGH = IVold.REMOUNT_MODE_PASS_THROUGH;

    /**
     * Use the regular scoped storage filesystem, but Android/ should be writable.
     * Used to support the applications hosting DownloadManager and the MTP server.
     * @hide
     */
    @SystemApi
    public static final int MOUNT_MODE_EXTERNAL_ANDROID_WRITABLE =
            IVold.REMOUNT_MODE_ANDROID_WRITABLE;
    /**
     * Flag indicating that a disk space allocation request should operate in an
     * aggressive mode. This flag should only be rarely used in situations that
     * are critical to system health or security.
     * <p>
     * When set, the system is more aggressive about the data that it considers
     * for possible deletion when allocating disk space.
     * <p class="note">
     * Note: your app must hold the
     * {@link android.Manifest.permission#ALLOCATE_AGGRESSIVE} permission for
     * this flag to take effect.
     * </p>
     *
     * @see #getAllocatableBytes(UUID, int)
     * @see #allocateBytes(UUID, long, int)
     * @see #allocateBytes(FileDescriptor, long, int)
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.ALLOCATE_AGGRESSIVE)
    @SystemApi
    public static final int FLAG_ALLOCATE_AGGRESSIVE = 1 << 0;

    /**
     * Flag indicating that a disk space allocation request should be allowed to
     * clear up to all reserved disk space.
     *
     * @hide
     */
    public static final int FLAG_ALLOCATE_DEFY_ALL_RESERVED = 1 << 1;

    /**
     * Flag indicating that a disk space allocation request should be allowed to
     * clear up to half of all reserved disk space.
     *
     * @hide
     */
    public static final int FLAG_ALLOCATE_DEFY_HALF_RESERVED = 1 << 2;

    /**
     * Flag indicating that a disk space check should not take into account
     * freeable cached space when determining allocatable space.
     *
     * Intended for use with {@link #getAllocatableBytes()}.
     * @hide
     */
    public static final int FLAG_ALLOCATE_NON_CACHE_ONLY = 1 << 3;

    /**
     * Flag indicating that a disk space check should only return freeable
     * cached space when determining allocatable space.
     *
     * Intended for use with {@link #getAllocatableBytes()}.
     * @hide
     */
    public static final int FLAG_ALLOCATE_CACHE_ONLY = 1 << 4;

    /** @hide */
    @IntDef(flag = true, prefix = { "FLAG_ALLOCATE_" }, value = {
            FLAG_ALLOCATE_AGGRESSIVE,
            FLAG_ALLOCATE_DEFY_ALL_RESERVED,
            FLAG_ALLOCATE_DEFY_HALF_RESERVED,
            FLAG_ALLOCATE_NON_CACHE_ONLY,
            FLAG_ALLOCATE_CACHE_ONLY,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface AllocateFlags {}

    /**
     * Return the maximum number of new bytes that your app can allocate for
     * itself on the given storage volume. This value is typically larger than
     * {@link File#getUsableSpace()}, since the system may be willing to delete
     * cached files to satisfy an allocation request. You can then allocate
     * space for yourself using {@link #allocateBytes(UUID, long)} or
     * {@link #allocateBytes(FileDescriptor, long)}.
     * <p>
     * This method is best used as a pre-flight check, such as deciding if there
     * is enough space to store an entire music album before you allocate space
     * for each audio file in the album. Attempts to allocate disk space beyond
     * the returned value will fail.
     * <p>
     * If the returned value is not large enough for the data you'd like to
     * persist, you can launch {@link #ACTION_MANAGE_STORAGE} with the
     * {@link #EXTRA_UUID} and {@link #EXTRA_REQUESTED_BYTES} options to help
     * involve the user in freeing up disk space.
     * <p>
     * If you're progressively allocating an unbounded amount of storage space
     * (such as when recording a video) you should avoid calling this method
     * more than once every 30 seconds.
     * <p class="note">
     * Note: if your app uses the {@code android:sharedUserId} manifest feature,
     * then allocatable space for all packages in your shared UID is tracked
     * together as a single unit.
     * </p>
     *
     * @param storageUuid the UUID of the storage volume where you're
     *            considering allocating disk space, since allocatable space can
     *            vary widely depending on the underlying storage device. The
     *            UUID for a specific path can be obtained using
     *            {@link #getUuidForPath(File)}.
     * @return the maximum number of new bytes that the calling app can allocate
     *         using {@link #allocateBytes(UUID, long)} or
     *         {@link #allocateBytes(FileDescriptor, long)}.
     * @throws IOException when the storage device isn't present, or when it
     *             doesn't support allocating space.
     */
    @WorkerThread
    public @BytesLong long getAllocatableBytes(@NonNull UUID storageUuid)
            throws IOException {
        return getAllocatableBytes(storageUuid, 0);
    }

    /** @hide */
    @SystemApi
    @WorkerThread
    @SuppressLint("RequiresPermission")
    public long getAllocatableBytes(@NonNull UUID storageUuid,
            @RequiresPermission @AllocateFlags int flags) throws IOException {
        try {
            return mStorageManager.getAllocatableBytes(convert(storageUuid), flags,
                    mContext.getOpPackageName());
        } catch (ParcelableException e) {
            e.maybeRethrow(IOException.class);
            throw new RuntimeException(e);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Allocate the requested number of bytes for your application to use on the
     * given storage volume. This will cause the system to delete any cached
     * files necessary to satisfy your request.
     * <p>
     * Attempts to allocate disk space beyond the value returned by
     * {@link #getAllocatableBytes(UUID)} will fail.
     * <p>
     * Since multiple apps can be running simultaneously, this method may be
     * subject to race conditions. If possible, consider using
     * {@link #allocateBytes(FileDescriptor, long)} which will guarantee
     * that bytes are allocated to an opened file.
     * <p>
     * If you're progressively allocating an unbounded amount of storage space
     * (such as when recording a video) you should avoid calling this method
     * more than once every 60 seconds.
     *
     * @param storageUuid the UUID of the storage volume where you'd like to
     *            allocate disk space. The UUID for a specific path can be
     *            obtained using {@link #getUuidForPath(File)}.
     * @param bytes the number of bytes to allocate.
     * @throws IOException when the storage device isn't present, or when it
     *             doesn't support allocating space, or if the device had
     *             trouble allocating the requested space.
     * @see #getAllocatableBytes(UUID)
     */
    @WorkerThread
    public void allocateBytes(@NonNull UUID storageUuid, @BytesLong long bytes)
            throws IOException {
        allocateBytes(storageUuid, bytes, 0);
    }

    /** @hide */
    @SystemApi
    @WorkerThread
    @SuppressLint("RequiresPermission")
    public void allocateBytes(@NonNull UUID storageUuid, @BytesLong long bytes,
            @RequiresPermission @AllocateFlags int flags) throws IOException {
        try {
            mStorageManager.allocateBytes(convert(storageUuid), bytes, flags,
                    mContext.getOpPackageName());
        } catch (ParcelableException e) {
            e.maybeRethrow(IOException.class);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the External Storage mount mode corresponding to the given uid and packageName.
     * These mount modes specify different views and access levels for
     * different apps on external storage.
     *
     * @params uid UID of the application
     * @params packageName name of the package
     * @return {@code MountMode} for the given uid and packageName.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.WRITE_MEDIA_STORAGE)
    @SystemApi
    @MountMode
    public int getExternalStorageMountMode(int uid, @NonNull String packageName) {
        try {
            return mStorageManager.getExternalStorageMountMode(uid, packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Allocate the requested number of bytes for your application to use in the
     * given open file. This will cause the system to delete any cached files
     * necessary to satisfy your request.
     * <p>
     * Attempts to allocate disk space beyond the value returned by
     * {@link #getAllocatableBytes(UUID)} will fail.
     * <p>
     * This method guarantees that bytes have been allocated to the opened file,
     * otherwise it will throw if fast allocation is not possible. Fast
     * allocation is typically only supported in private app data directories,
     * and on shared/external storage devices which are emulated.
     * <p>
     * If you're progressively allocating an unbounded amount of storage space
     * (such as when recording a video) you should avoid calling this method
     * more than once every 60 seconds.
     *
     * @param fd the open file that you'd like to allocate disk space for.
     * @param bytes the number of bytes to allocate. This is the desired final
     *            size of the open file. If the open file is smaller than this
     *            requested size, it will be extended without modifying any
     *            existing contents. If the open file is larger than this
     *            requested size, it will be truncated.
     * @throws IOException when the storage device isn't present, or when it
     *             doesn't support allocating space, or if the device had
     *             trouble allocating the requested space.
     * @see #isAllocationSupported(FileDescriptor)
     * @see Environment#isExternalStorageEmulated(File)
     */
    @WorkerThread
    public void allocateBytes(FileDescriptor fd, @BytesLong long bytes) throws IOException {
        allocateBytes(fd, bytes, 0);
    }

    /** @hide */
    @SystemApi
    @WorkerThread
    @SuppressLint("RequiresPermission")
    public void allocateBytes(FileDescriptor fd, @BytesLong long bytes,
            @RequiresPermission @AllocateFlags int flags) throws IOException {
        final File file = ParcelFileDescriptor.getFile(fd);
        final UUID uuid = getUuidForPath(file);
        for (int i = 0; i < 3; i++) {
            try {
                final long haveBytes = Os.fstat(fd).st_blocks * 512;
                final long needBytes = bytes - haveBytes;

                if (needBytes > 0) {
                    allocateBytes(uuid, needBytes, flags);
                }

                try {
                    Os.posix_fallocate(fd, 0, bytes);
                    return;
                } catch (ErrnoException e) {
                    if (e.errno == OsConstants.ENOSYS || e.errno == OsConstants.ENOTSUP) {
                        Log.w(TAG, "fallocate() not supported; falling back to ftruncate()");
                        Os.ftruncate(fd, bytes);
                        return;
                    } else {
                        throw e;
                    }
                }
            } catch (ErrnoException e) {
                if (e.errno == OsConstants.ENOSPC) {
                    Log.w(TAG, "Odd, not enough space; let's try again?");
                    continue;
                }
                throw e.rethrowAsIOException();
            }
        }
        throw new IOException(
                "Well this is embarassing; we can't allocate " + bytes + " for " + file);
    }

    private static final String XATTR_CACHE_GROUP = "user.cache_group";
    private static final String XATTR_CACHE_TOMBSTONE = "user.cache_tombstone";


    // Project IDs below must match android_projectid_config.h
    /**
     * Default project ID for files on external storage
     *
     * {@hide}
     */
    public static final int PROJECT_ID_EXT_DEFAULT = 1000;

    /**
     * project ID for audio files on external storage
     *
     * {@hide}
     */
    public static final int PROJECT_ID_EXT_MEDIA_AUDIO = 1001;

    /**
     * project ID for video files on external storage
     *
     * {@hide}
     */
    public static final int PROJECT_ID_EXT_MEDIA_VIDEO = 1002;

    /**
     * project ID for image files on external storage
     *
     * {@hide}
     */
    public static final int PROJECT_ID_EXT_MEDIA_IMAGE = 1003;

    /**
     * Constant for use with
     * {@link #updateExternalStorageFileQuotaType(String, int)} (String, int)}, to indicate the file
     * is not a media file.
     *
     * @hide
     */
    @SystemApi
    public static final int QUOTA_TYPE_MEDIA_NONE = 0;

    /**
     * Constant for use with
     * {@link #updateExternalStorageFileQuotaType(String, int)} (String, int)}, to indicate the file
     * is an image file.
     *
     * @hide
     */
    @SystemApi
    public static final int QUOTA_TYPE_MEDIA_IMAGE = 1;

    /**
     * Constant for use with
     * {@link #updateExternalStorageFileQuotaType(String, int)} (String, int)}, to indicate the file
     * is an audio file.
     *
     * @hide
     */
    @SystemApi
    public static final int QUOTA_TYPE_MEDIA_AUDIO = 2;

    /**
     * Constant for use with
     * {@link #updateExternalStorageFileQuotaType(String, int)} (String, int)}, to indicate the file
     * is a video file.
     *
     * @hide
     */
    @SystemApi
    public static final int QUOTA_TYPE_MEDIA_VIDEO = 3;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "QUOTA_TYPE_" }, value = {
            QUOTA_TYPE_MEDIA_NONE,
            QUOTA_TYPE_MEDIA_AUDIO,
            QUOTA_TYPE_MEDIA_VIDEO,
            QUOTA_TYPE_MEDIA_IMAGE,
    })
    public @interface QuotaType {}

    private static native boolean setQuotaProjectId(String path, long projectId);

    private static long getProjectIdForUser(int userId, int projectId) {
        // Much like UserHandle.getUid(), store the user ID in the upper bits
        return userId * PER_USER_RANGE + projectId;
    }

    /**
     * Let StorageManager know that the quota type for a file on external storage should
     * be updated. Android tracks quotas for various media types. Consequently, this should be
     * called on first creation of a new file on external storage, and whenever the
     * media type of the file is updated later.
     *
     * This API doesn't require any special permissions, though typical implementations
     * will require being called from an SELinux domain that allows setting file attributes
     * related to quota (eg the GID or project ID).
     * If the calling user has MANAGE_EXTERNAL_STORAGE permissions, quota for shared profile's
     * volumes is also updated.
     *
     * The default platform user of this API is the MediaProvider process, which is
     * responsible for managing all of external storage.
     *
     * @param path the path to the file for which we should update the quota type
     * @param quotaType the quota type of the file; this is based on the
     *                  {@code QuotaType} constants, eg
     *                  {@code StorageManager.QUOTA_TYPE_MEDIA_AUDIO}
     *
     * @throws IllegalArgumentException if {@code quotaType} does not correspond to a valid
     *                                  quota type.
     * @throws IOException              if the quota type could not be updated.
     *
     * @hide
     */
    @SystemApi
    public void updateExternalStorageFileQuotaType(@NonNull File path,
            @QuotaType int quotaType) throws IOException {
        long projectId;
        final String filePath = path.getCanonicalPath();
        int volFlags = FLAG_REAL_STATE | FLAG_INCLUDE_INVISIBLE;
        // If caller has MANAGE_EXTERNAL_STORAGE permission, results from User Profile(s) are also
        // returned by enabling FLAG_INCLUDE_SHARED_PROFILE.
        if (mContext.checkSelfPermission(MANAGE_EXTERNAL_STORAGE) == PERMISSION_GRANTED) {
            volFlags |= FLAG_INCLUDE_SHARED_PROFILE;
        }
        final StorageVolume[] availableVolumes = getVolumeList(mContext.getUserId(), volFlags);
        final StorageVolume volume = getStorageVolume(availableVolumes, path);
        if (volume == null) {
            Log.w(TAG, "Failed to update quota type for " + filePath);
            return;
        }
        if (!volume.isEmulated()) {
            // We only support quota tracking on emulated filesystems
            return;
        }

        final int userId = volume.getOwner().getIdentifier();
        if (userId < 0) {
            throw new IllegalStateException("Failed to update quota type for " + filePath);
        }
        switch (quotaType) {
            case QUOTA_TYPE_MEDIA_NONE:
                projectId = getProjectIdForUser(userId, PROJECT_ID_EXT_DEFAULT);
                break;
            case QUOTA_TYPE_MEDIA_AUDIO:
                projectId = getProjectIdForUser(userId, PROJECT_ID_EXT_MEDIA_AUDIO);
                break;
            case QUOTA_TYPE_MEDIA_VIDEO:
                projectId = getProjectIdForUser(userId, PROJECT_ID_EXT_MEDIA_VIDEO);
                break;
            case QUOTA_TYPE_MEDIA_IMAGE:
                projectId = getProjectIdForUser(userId, PROJECT_ID_EXT_MEDIA_IMAGE);
                break;
            default:
                throw new IllegalArgumentException("Invalid quota type: " + quotaType);
        }
        if (!setQuotaProjectId(filePath, projectId)) {
            throw new IOException("Failed to update quota type for " + filePath);
        }
    }

    /**
     * Asks StorageManager to fixup the permissions of an application-private directory.
     *
     * On devices without sdcardfs, filesystem permissions aren't magically fixed up. This
     * is problematic mostly in application-private directories, which are owned by the
     * application itself; if another process with elevated permissions creates a file
     * in these directories, the UID will be wrong, and the owning package won't be able
     * to access the files.
     *
     * This API can be used to recursively fix up the permissions on the passed in path.
     * The default platform user of this API is the DownloadProvider, which can download
     * things in application-private directories on their behalf.
     *
     * This API doesn't require any special permissions, because it merely changes the
     * permissions of a directory to what they should anyway be.
     *
     * @param path the path for which we should fix up the permissions
     *
     * @hide
     */
    public void fixupAppDir(@NonNull File path) {
        try {
            mStorageManager.fixupAppDir(path.getCanonicalPath());
        } catch (IOException e) {
            Log.e(TAG, "Failed to get canonical path for " + path.getPath(), e);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** {@hide} */
    private static void setCacheBehavior(File path, String name, boolean enabled)
            throws IOException {
        if (!path.isDirectory()) {
            throw new IOException("Cache behavior can only be set on directories");
        }
        if (enabled) {
            try {
                Os.setxattr(path.getAbsolutePath(), name,
                        "1".getBytes(StandardCharsets.UTF_8), 0);
            } catch (ErrnoException e) {
                throw e.rethrowAsIOException();
            }
        } else {
            try {
                Os.removexattr(path.getAbsolutePath(), name);
            } catch (ErrnoException e) {
                if (e.errno != OsConstants.ENODATA) {
                    throw e.rethrowAsIOException();
                }
            }
        }
    }

    /** {@hide} */
    private static boolean isCacheBehavior(File path, String name) throws IOException {
        try {
            Os.getxattr(path.getAbsolutePath(), name);
            return true;
        } catch (ErrnoException e) {
            if (e.errno != OsConstants.ENODATA) {
                throw e.rethrowAsIOException();
            } else {
                return false;
            }
        }
    }

    /**
     * Enable or disable special cache behavior that treats this directory and
     * its contents as an entire group.
     * <p>
     * When enabled and this directory is considered for automatic deletion by
     * the OS, all contained files will either be deleted together, or not at
     * all. This is useful when you have a directory that contains several
     * related metadata files that depend on each other, such as movie file and
     * a subtitle file.
     * <p>
     * When enabled, the <em>newest</em> {@link File#lastModified()} value of
     * any contained files is considered the modified time of the entire
     * directory.
     * <p>
     * This behavior can only be set on a directory, and it applies recursively
     * to all contained files and directories.
     */
    public void setCacheBehaviorGroup(File path, boolean group) throws IOException {
        setCacheBehavior(path, XATTR_CACHE_GROUP, group);
    }

    /**
     * Read the current value set by
     * {@link #setCacheBehaviorGroup(File, boolean)}.
     */
    public boolean isCacheBehaviorGroup(File path) throws IOException {
        return isCacheBehavior(path, XATTR_CACHE_GROUP);
    }

    /**
     * Enable or disable special cache behavior that leaves deleted cache files
     * intact as tombstones.
     * <p>
     * When enabled and a file contained in this directory is automatically
     * deleted by the OS, the file will be truncated to have a length of 0 bytes
     * instead of being fully deleted. This is useful if you need to distinguish
     * between a file that was deleted versus one that never existed.
     * <p>
     * This behavior can only be set on a directory, and it applies recursively
     * to all contained files and directories.
     * <p class="note">
     * Note: this behavior is ignored completely if the user explicitly requests
     * that all cached data be cleared.
     * </p>
     */
    public void setCacheBehaviorTombstone(File path, boolean tombstone) throws IOException {
        setCacheBehavior(path, XATTR_CACHE_TOMBSTONE, tombstone);
    }

    /**
     * Read the current value set by
     * {@link #setCacheBehaviorTombstone(File, boolean)}.
     */
    public boolean isCacheBehaviorTombstone(File path) throws IOException {
        return isCacheBehavior(path, XATTR_CACHE_TOMBSTONE);
    }

    /**
     * Returns true if {@code uuid} is a FAT volume identifier. FAT Volume identifiers
     * are 32 randomly generated bits that are represented in string form as AAAA-AAAA.
     */
    private static boolean isFatVolumeIdentifier(String uuid) {
        return uuid.length() == 9 && uuid.charAt(4) == '-';
    }

    /** {@hide} */
    @TestApi
    public static @NonNull UUID convert(@Nullable String uuid) {
        // UUID_PRIVATE_INTERNAL is null, so this accepts nullable input
        if (Objects.equals(uuid, UUID_PRIVATE_INTERNAL)) {
            return UUID_DEFAULT;
        } else if (Objects.equals(uuid, UUID_PRIMARY_PHYSICAL)) {
            return UUID_PRIMARY_PHYSICAL_;
        } else if (Objects.equals(uuid, UUID_SYSTEM)) {
            return UUID_SYSTEM_;
        } else if (isFatVolumeIdentifier(uuid)) {
            // FAT volume identifiers are not UUIDs but we need to coerce them into
            // UUIDs in order to satisfy apis that take java.util.UUID arguments.
            //
            // We coerce a 32 bit fat volume identifier of the form XXXX-YYYY into
            // a UUID of form "fafafafa-fafa-5afa-8afa-fafaXXXXYYYY". This is an
            // RFC-422 UUID with Version 5, which is a namespaced UUID. The UUIDs we
            // coerce into are not true namespace UUIDs; although FAT storage volume
            // identifiers are unique names within a fixed namespace, this UUID is not
            // based on an SHA-1 hash of the name. We avoid the SHA-1 hash because
            // (a) we need this transform to be reversible (b) it's pointless to generate
            // a 128 bit hash from a 32 bit value.
            return UUID.fromString(FAT_UUID_PREFIX + uuid.replace("-", ""));
        } else {
            return UUID.fromString(uuid);
        }
    }

    /** {@hide} */
    @TestApi
    public static @NonNull String convert(@NonNull UUID storageUuid) {
        if (UUID_DEFAULT.equals(storageUuid)) {
            return UUID_PRIVATE_INTERNAL;
        } else if (UUID_PRIMARY_PHYSICAL_.equals(storageUuid)) {
            return UUID_PRIMARY_PHYSICAL;
        } else if (UUID_SYSTEM_.equals(storageUuid)) {
            return UUID_SYSTEM;
        } else {
            String uuidString = storageUuid.toString();
            // This prefix match will exclude fsUuids from private volumes because
            // (a) linux fsUuids are generally Version 4 (random) UUIDs so the prefix
            // will contain 4xxx instead of 5xxx and (b) we've already matched against
            // known namespace (Version 5) UUIDs above.
            if (uuidString.startsWith(FAT_UUID_PREFIX)) {
                String fatStr = uuidString.substring(FAT_UUID_PREFIX.length())
                        .toUpperCase(Locale.US);
                return fatStr.substring(0, 4) + "-" + fatStr.substring(4);
            }

            return storageUuid.toString();
        }
    }

    /**
     * Check whether the device supports filesystem checkpoint.
     *
     * @return true if the device supports filesystem checkpoint, false otherwise.
     */
    public boolean isCheckpointSupported() {
        try {
            return mStorageManager.supportsCheckpoint();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Reason to provide if app IO is blocked/resumed for unknown reasons
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static final int APP_IO_BLOCKED_REASON_UNKNOWN = 0;

    /**
     * Reason to provide if app IO is blocked/resumed because of transcoding
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static final int APP_IO_BLOCKED_REASON_TRANSCODING = 1;

    /**
     * Constants for use with
     * {@link #notifyAppIoBlocked} and {@link notifyAppIoResumed}, to specify the reason an app's
     * IO is blocked/resumed.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "APP_IO_BLOCKED_REASON_" }, value = {
                APP_IO_BLOCKED_REASON_TRANSCODING,
                APP_IO_BLOCKED_REASON_UNKNOWN,
    })
    public @interface AppIoBlockedReason {}

    /**
     * Notify the system that an app with {@code uid} and {@code tid} is blocked on an IO request on
     * {@code volumeUuid} for {@code reason}.
     *
     * This blocked state can be used to modify the ANR behavior for the app while it's blocked.
     * For example during transcoding.
     *
     * This can only be called by the {@link ExternalStorageService} holding the
     * {@link android.Manifest.permission#WRITE_MEDIA_STORAGE} permission.
     *
     * @param volumeUuid the UUID of the storage volume that the app IO is blocked on
     * @param uid the UID of the app blocked on IO
     * @param tid the tid of the app blocked on IO
     * @param reason the reason the app is blocked on IO
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public void notifyAppIoBlocked(@NonNull UUID volumeUuid, int uid, int tid,
            @AppIoBlockedReason int reason) {
        Objects.requireNonNull(volumeUuid);
        try {
            mStorageManager.notifyAppIoBlocked(convert(volumeUuid), uid, tid, reason);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Notify the system that an app with {@code uid} and {@code tid} has resmued a previously
     * blocked IO request on {@code volumeUuid} for {@code reason}.
     *
     * All app IO will be automatically marked as unblocked if {@code volumeUuid} is unmounted.
     *
     * This can only be called by the {@link ExternalStorageService} holding the
     * {@link android.Manifest.permission#WRITE_MEDIA_STORAGE} permission.
     *
     * @param volumeUuid the UUID of the storage volume that the app IO is resumed on
     * @param uid the UID of the app resuming IO
     * @param tid the tid of the app resuming IO
     * @param reason the reason the app is resuming IO
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public void notifyAppIoResumed(@NonNull UUID volumeUuid, int uid, int tid,
            @AppIoBlockedReason int reason) {
        Objects.requireNonNull(volumeUuid);
        try {
            mStorageManager.notifyAppIoResumed(convert(volumeUuid), uid, tid, reason);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Check if {@code uid} with {@code tid} is blocked on IO for {@code reason}.
     *
     * This requires {@link ExternalStorageService} the
     * {@link android.Manifest.permission#WRITE_MEDIA_STORAGE} permission.
     *
     * @param volumeUuid the UUID of the storage volume to check IO blocked status
     * @param uid the UID of the app to check IO blocked status
     * @param tid the tid of the app to check IO blocked status
     * @param reason the reason to check IO blocked status for
     *
     * @hide
     */
    @TestApi
    public boolean isAppIoBlocked(@NonNull UUID volumeUuid, int uid, int tid,
            @AppIoBlockedReason int reason) {
        Objects.requireNonNull(volumeUuid);
        try {
            return mStorageManager.isAppIoBlocked(convert(volumeUuid), uid, tid, reason);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Notify the system of the current cloud media provider.
     *
     * This can only be called by the {@link android.service.storage.ExternalStorageService}
     * holding the {@link android.Manifest.permission#WRITE_MEDIA_STORAGE} permission.
     *
     * @param authority the authority of the content provider
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public void setCloudMediaProvider(@Nullable String authority) {
        try {
            mStorageManager.setCloudMediaProvider(authority);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the authority of the current cloud media provider that was set by the
     * {@link android.service.storage.ExternalStorageService} holding the
     * {@link android.Manifest.permission#WRITE_MEDIA_STORAGE} permission via
     * {@link #setCloudMediaProvider(String)}.
     *
     * @hide
     */
    @Nullable
    @TestApi
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public String getCloudMediaProvider() {
        try {
            return mStorageManager.getCloudMediaProvider();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private final Object mFuseAppLoopLock = new Object();

    @GuardedBy("mFuseAppLoopLock")
    private @Nullable FuseAppLoop mFuseAppLoop = null;

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final int CRYPT_TYPE_PASSWORD = 0;
    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final int CRYPT_TYPE_DEFAULT = 1;

    /**
     * Returns the remaining lifetime of the internal storage device, as an integer percentage. For
     * example, 90 indicates that 90% of the storage device's useful lifetime remains. If no
     * information is available, -1 is returned.
     *
     * @return Percentage of the remaining useful lifetime of the internal storage device.
     *
     * @hide
     */
    @FlaggedApi(Flags.FLAG_STORAGE_LIFETIME_API)
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public int getInternalStorageRemainingLifetime() {
        try {
            return mStorageManager.getInternalStorageRemainingLifetime();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
