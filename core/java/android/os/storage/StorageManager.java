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

import android.annotation.BytesLong;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.annotation.UnsupportedAppUsage;
import android.annotation.WorkerThread;
import android.app.Activity;
import android.app.ActivityThread;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageMoveObserver;
import android.content.pm.PackageManager;
import android.content.res.ObbInfo;
import android.content.res.ObbScanner;
import android.net.Uri;
import android.os.Binder;
import android.os.Environment;
import android.os.FileUtils;
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
import android.provider.MediaStore;
import android.provider.Settings;
import android.sysprop.VoldProperties;
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
import com.android.internal.os.SomeArgs;
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
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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

    /** {@hide} */
    public static final String PROP_PRIMARY_PHYSICAL = "ro.vold.primary_physical";
    /** {@hide} */
    public static final String PROP_HAS_ADOPTABLE = "vold.has_adoptable";
    /** {@hide} */
    public static final String PROP_HAS_RESERVED = "vold.has_reserved";
    /** {@hide} */
    public static final String PROP_ADOPTABLE = "persist.sys.adoptable";
    /** {@hide} */
    public static final String PROP_EMULATE_FBE = "persist.sys.emulate_fbe";
    /** {@hide} */
    public static final String PROP_SDCARDFS = "persist.sys.sdcardfs";
    /** {@hide} */
    public static final String PROP_VIRTUAL_DISK = "persist.sys.virtual_disk";
    /** {@hide} */
    public static final String PROP_ISOLATED_STORAGE = "persist.sys.isolated_storage";
    /** {@hide} */
    public static final String PROP_ISOLATED_STORAGE_SNAPSHOT = "sys.isolated_storage_snapshot";

    /** {@hide} */
    public static final String PROP_FORCE_AUDIO = "persist.fw.force_audio";
    /** {@hide} */
    public static final String PROP_FORCE_VIDEO = "persist.fw.force_video";
    /** {@hide} */
    public static final String PROP_FORCE_IMAGES = "persist.fw.force_images";

    /** {@hide} */
    public static final String UUID_PRIVATE_INTERNAL = null;
    /** {@hide} */
    public static final String UUID_PRIMARY_PHYSICAL = "primary_physical";
    /** {@hide} */
    public static final String UUID_SYSTEM = "system";

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
    public static final int DEBUG_EMULATE_FBE = 1 << 2;
    /** {@hide} */
    public static final int DEBUG_SDCARDFS_FORCE_ON = 1 << 3;
    /** {@hide} */
    public static final int DEBUG_SDCARDFS_FORCE_OFF = 1 << 4;
    /** {@hide} */
    public static final int DEBUG_VIRTUAL_DISK = 1 << 5;
    /** {@hide} */
    public static final int DEBUG_ISOLATED_STORAGE_FORCE_ON = 1 << 6;
    /** {@hide} */
    public static final int DEBUG_ISOLATED_STORAGE_FORCE_OFF = 1 << 7;

    /** {@hide} */
    public static final int FLAG_STORAGE_DE = IInstalld.FLAG_STORAGE_DE;
    /** {@hide} */
    public static final int FLAG_STORAGE_CE = IInstalld.FLAG_STORAGE_CE;

    /** {@hide} */
    public static final int FLAG_FOR_WRITE = 1 << 8;
    /** {@hide} */
    public static final int FLAG_REAL_STATE = 1 << 9;
    /** {@hide} */
    public static final int FLAG_INCLUDE_INVISIBLE = 1 << 10;

    /** {@hide} */
    public static final int FSTRIM_FLAG_DEEP = IVold.FSTRIM_FLAG_DEEP_TRIM;

    /** @hide The volume is not encrypted. */
    @UnsupportedAppUsage
    public static final int ENCRYPTION_STATE_NONE =
            IVold.ENCRYPTION_STATE_NONE;

    /** @hide The volume has been encrypted succesfully. */
    public static final int ENCRYPTION_STATE_OK =
            IVold.ENCRYPTION_STATE_OK;

    /** @hide The volume is in a bad state. */
    public static final int ENCRYPTION_STATE_ERROR_UNKNOWN =
            IVold.ENCRYPTION_STATE_ERROR_UNKNOWN;

    /** @hide Encryption is incomplete */
    public static final int ENCRYPTION_STATE_ERROR_INCOMPLETE =
            IVold.ENCRYPTION_STATE_ERROR_INCOMPLETE;

    /** @hide Encryption is incomplete and irrecoverable */
    public static final int ENCRYPTION_STATE_ERROR_INCONSISTENT =
            IVold.ENCRYPTION_STATE_ERROR_INCONSISTENT;

    /** @hide Underlying data is corrupt */
    public static final int ENCRYPTION_STATE_ERROR_CORRUPT =
            IVold.ENCRYPTION_STATE_ERROR_CORRUPT;

    /** @hide Prefix used in sandboxIds for apps with sharedUserIds */
    public static final String SHARED_SANDBOX_PREFIX = "shared-";

    private static volatile IStorageManager sStorageManager = null;

    private final Context mContext;
    private final ContentResolver mResolver;

    private final IStorageManager mStorageManager;
    private final Looper mLooper;
    private final AtomicInteger mNextNonce = new AtomicInteger(0);

    private final ArrayList<StorageEventListenerDelegate> mDelegates = new ArrayList<>();

    private static class StorageEventListenerDelegate extends IStorageEventListener.Stub implements
            Handler.Callback {
        private static final int MSG_STORAGE_STATE_CHANGED = 1;
        private static final int MSG_VOLUME_STATE_CHANGED = 2;
        private static final int MSG_VOLUME_RECORD_CHANGED = 3;
        private static final int MSG_VOLUME_FORGOTTEN = 4;
        private static final int MSG_DISK_SCANNED = 5;
        private static final int MSG_DISK_DESTROYED = 6;

        final StorageEventListener mCallback;
        final Handler mHandler;

        public StorageEventListenerDelegate(StorageEventListener callback, Looper looper) {
            mCallback = callback;
            mHandler = new Handler(looper, this);
        }

        @Override
        public boolean handleMessage(Message msg) {
            final SomeArgs args = (SomeArgs) msg.obj;
            switch (msg.what) {
                case MSG_STORAGE_STATE_CHANGED:
                    mCallback.onStorageStateChanged((String) args.arg1, (String) args.arg2,
                            (String) args.arg3);
                    args.recycle();
                    return true;
                case MSG_VOLUME_STATE_CHANGED:
                    mCallback.onVolumeStateChanged((VolumeInfo) args.arg1, args.argi2, args.argi3);
                    args.recycle();
                    return true;
                case MSG_VOLUME_RECORD_CHANGED:
                    mCallback.onVolumeRecordChanged((VolumeRecord) args.arg1);
                    args.recycle();
                    return true;
                case MSG_VOLUME_FORGOTTEN:
                    mCallback.onVolumeForgotten((String) args.arg1);
                    args.recycle();
                    return true;
                case MSG_DISK_SCANNED:
                    mCallback.onDiskScanned((DiskInfo) args.arg1, args.argi2);
                    args.recycle();
                    return true;
                case MSG_DISK_DESTROYED:
                    mCallback.onDiskDestroyed((DiskInfo) args.arg1);
                    args.recycle();
                    return true;
            }
            args.recycle();
            return false;
        }

        @Override
        public void onUsbMassStorageConnectionChanged(boolean connected) throws RemoteException {
            // Ignored
        }

        @Override
        public void onStorageStateChanged(String path, String oldState, String newState) {
            final SomeArgs args = SomeArgs.obtain();
            args.arg1 = path;
            args.arg2 = oldState;
            args.arg3 = newState;
            mHandler.obtainMessage(MSG_STORAGE_STATE_CHANGED, args).sendToTarget();
        }

        @Override
        public void onVolumeStateChanged(VolumeInfo vol, int oldState, int newState) {
            final SomeArgs args = SomeArgs.obtain();
            args.arg1 = vol;
            args.argi2 = oldState;
            args.argi3 = newState;
            mHandler.obtainMessage(MSG_VOLUME_STATE_CHANGED, args).sendToTarget();
        }

        @Override
        public void onVolumeRecordChanged(VolumeRecord rec) {
            final SomeArgs args = SomeArgs.obtain();
            args.arg1 = rec;
            mHandler.obtainMessage(MSG_VOLUME_RECORD_CHANGED, args).sendToTarget();
        }

        @Override
        public void onVolumeForgotten(String fsUuid) {
            final SomeArgs args = SomeArgs.obtain();
            args.arg1 = fsUuid;
            mHandler.obtainMessage(MSG_VOLUME_FORGOTTEN, args).sendToTarget();
        }

        @Override
        public void onDiskScanned(DiskInfo disk, int volumeCount) {
            final SomeArgs args = SomeArgs.obtain();
            args.arg1 = disk;
            args.argi2 = volumeCount;
            mHandler.obtainMessage(MSG_DISK_SCANNED, args).sendToTarget();
        }

        @Override
        public void onDiskDestroyed(DiskInfo disk) throws RemoteException {
            final SomeArgs args = SomeArgs.obtain();
            args.arg1 = disk;
            mHandler.obtainMessage(MSG_DISK_DESTROYED, args).sendToTarget();
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
            final StorageEventListenerDelegate delegate = new StorageEventListenerDelegate(listener,
                    mLooper);
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
                if (delegate.mCallback == listener) {
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
    @UnsupportedAppUsage
    public void enableUsbMassStorage() {
    }

    /**
     * Disables USB Mass Storage (UMS) on the device.
     *
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage
    public void disableUsbMassStorage() {
    }

    /**
     * Query if a USB Mass Storage (UMS) host is connected.
     * @return true if UMS host is connected.
     *
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage
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
     * Mount an Opaque Binary Blob (OBB) file. If a <code>key</code> is
     * specified, it is supplied to the mounting process to be used in any
     * encryption used in the OBB.
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
     * @param key secret used to encrypt the OBB; may be <code>null</code> if no
     *            encryption was used on the OBB.
     * @param listener will receive the success or failure of the operation
     * @return whether the mount call was successfully queued or not
     */
    public boolean mountObb(String rawPath, String key, OnObbStateChangeListener listener) {
        Preconditions.checkNotNull(rawPath, "rawPath cannot be null");
        Preconditions.checkNotNull(listener, "listener cannot be null");

        try {
            final String canonicalPath = new File(rawPath).getCanonicalPath();
            final int nonce = mObbActionListener.addListener(listener);
            mStorageManager.mountObb(rawPath, canonicalPath, key, mObbActionListener, nonce,
                    getObbInfo(canonicalPath));
            return true;
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to resolve path: " + rawPath, e);
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
    @UnsupportedAppUsage
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
            return findVolumeById(emulatedVol.getId().replace("emulated", "private"));
        } else {
            return null;
        }
    }

    /** {@hide} */
    @UnsupportedAppUsage
    public @Nullable VolumeInfo findEmulatedForPrivate(VolumeInfo privateVol) {
        if (privateVol != null) {
            return findVolumeById(privateVol.getId().replace("private", "emulated"));
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
    @UnsupportedAppUsage
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
    @UnsupportedAppUsage
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
    public @Nullable StorageVolume getStorageVolume(File file) {
        return getStorageVolume(getVolumeList(), file);
    }

    /**
     * Return the {@link StorageVolume} that contains the given
     * {@link MediaStore} item.
     */
    public @NonNull StorageVolume getStorageVolume(@NonNull Uri uri) {
        final String volumeName = MediaStore.getVolumeName(uri);
        switch (volumeName) {
            case MediaStore.VOLUME_EXTERNAL:
                return getPrimaryStorageVolume();
            default:
                for (StorageVolume vol : getStorageVolumes()) {
                    if (Objects.equals(vol.getNormalizedUuid(), volumeName)) {
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
    @UnsupportedAppUsage
    private static @Nullable StorageVolume getStorageVolume(StorageVolume[] volumes, File file) {
        if (file == null) {
            return null;
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
     * Return the list of shared/external storage volumes available to the
     * current user. This includes both the primary shared storage device and
     * any attached external volumes including SD cards and USB drives.
     *
     * @see Environment#getExternalStorageDirectory()
     * @see StorageVolume#createAccessIntent(String)
     */
    public @NonNull List<StorageVolume> getStorageVolumes() {
        final ArrayList<StorageVolume> res = new ArrayList<>();
        Collections.addAll(res,
                getVolumeList(mContext.getUserId(), FLAG_REAL_STATE | FLAG_INCLUDE_INVISIBLE));
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
                    return new StorageVolume[0];
                }
                packageName = packageNames[0];
            }
            final int uid = ActivityThread.getPackageManager().getPackageUid(packageName,
                    PackageManager.MATCH_DEBUG_TRIAGED_MISSING, userId);
            if (uid <= 0) {
                return new StorageVolume[0];
            }
            return storageManager.getVolumeList(uid, packageName, flags);
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

    private static final int DEFAULT_THRESHOLD_PERCENTAGE = 5;
    private static final long DEFAULT_THRESHOLD_MAX_BYTES = DataUnit.MEBIBYTES.toBytes(500);

    private static final int DEFAULT_CACHE_PERCENTAGE = 10;
    private static final long DEFAULT_CACHE_MAX_BYTES = DataUnit.GIBIBYTES.toBytes(5);

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
                Settings.Global.SYS_STORAGE_THRESHOLD_PERCENTAGE, DEFAULT_THRESHOLD_PERCENTAGE);
        final long lowBytes = (path.getTotalSpace() * lowPercent) / 100;

        final long maxLowBytes = Settings.Global.getLong(mResolver,
                Settings.Global.SYS_STORAGE_THRESHOLD_MAX_BYTES, DEFAULT_THRESHOLD_MAX_BYTES);

        return Math.min(lowBytes, maxLowBytes);
    }

    /**
     * Return the minimum number of bytes of storage on the device that should
     * be reserved for cached data.
     *
     * @hide
     */
    public long getStorageCacheBytes(File path, @AllocateFlags int flags) {
        final long cachePercent = Settings.Global.getInt(mResolver,
                Settings.Global.SYS_STORAGE_CACHE_PERCENTAGE, DEFAULT_CACHE_PERCENTAGE);
        final long cacheBytes = (path.getTotalSpace() * cachePercent) / 100;

        final long maxCacheBytes = Settings.Global.getLong(mResolver,
                Settings.Global.SYS_STORAGE_CACHE_MAX_BYTES, DEFAULT_CACHE_MAX_BYTES);

        final long result = Math.min(cacheBytes, maxCacheBytes);
        if ((flags & StorageManager.FLAG_ALLOCATE_AGGRESSIVE) != 0) {
            return 0;
        } else if ((flags & StorageManager.FLAG_ALLOCATE_DEFY_ALL_RESERVED) != 0) {
            return 0;
        } else if ((flags & StorageManager.FLAG_ALLOCATE_DEFY_HALF_RESERVED) != 0) {
            return result / 2;
        } else {
            return result;
        }
    }

    /**
     * Return the number of available bytes at which the given path is
     * considered full.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public long getStorageFullBytes(File path) {
        return Settings.Global.getLong(mResolver, Settings.Global.SYS_STORAGE_FULL_THRESHOLD_BYTES,
                DEFAULT_FULL_THRESHOLD_BYTES);
    }

    /** {@hide} */
    public void createUserKey(int userId, int serialNumber, boolean ephemeral) {
        try {
            mStorageManager.createUserKey(userId, serialNumber, ephemeral);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** {@hide} */
    public void destroyUserKey(int userId) {
        try {
            mStorageManager.destroyUserKey(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** {@hide} */
    public void unlockUserKey(int userId, int serialNumber, byte[] token, byte[] secret) {
        try {
            mStorageManager.unlockUserKey(userId, serialNumber, token, secret);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** {@hide} */
    public void lockUserKey(int userId) {
        try {
            mStorageManager.lockUserKey(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** {@hide} */
    public void prepareUserStorage(String volumeUuid, int userId, int serialNumber, int flags) {
        try {
            mStorageManager.prepareUserStorage(volumeUuid, userId, serialNumber, flags);
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
    public static boolean isUserKeyUnlocked(int userId) {
        if (sStorageManager == null) {
            sStorageManager = IStorageManager.Stub
                    .asInterface(ServiceManager.getService("mount"));
        }
        if (sStorageManager == null) {
            Slog.w(TAG, "Early during boot, assuming locked");
            return false;
        }
        final long token = Binder.clearCallingIdentity();
        try {
            return sStorageManager.isUserKeyUnlocked(userId);
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
     * Is this device encryptable or already encrypted?
     * @return true for encryptable or encrypted
     *         false not encrypted and not encryptable
     */
    public static boolean isEncryptable() {
        return RoSystemProperties.CRYPTO_ENCRYPTABLE;
    }

    /** {@hide}
     * Is this device already encrypted?
     * @return true for encrypted. (Implies isEncryptable() == true)
     *         false not encrypted
     */
    public static boolean isEncrypted() {
        return RoSystemProperties.CRYPTO_ENCRYPTED;
    }

    /** {@hide}
     * Is this device file encrypted?
     * @return true for file encrypted. (Implies isEncrypted() == true)
     *         false not encrypted or block encrypted
     */
    @UnsupportedAppUsage
    public static boolean isFileEncryptedNativeOnly() {
        if (!isEncrypted()) {
            return false;
        }
        return RoSystemProperties.CRYPTO_FILE_ENCRYPTED;
    }

    /** {@hide}
     * Is this device block encrypted?
     * @return true for block encrypted. (Implies isEncrypted() == true)
     *         false not encrypted or file encrypted
     */
    public static boolean isBlockEncrypted() {
        if (!isEncrypted()) {
            return false;
        }
        return RoSystemProperties.CRYPTO_BLOCK_ENCRYPTED;
    }

    /** {@hide}
     * Is this device block encrypted with credentials?
     * @return true for crediential block encrypted.
     *         (Implies isBlockEncrypted() == true)
     *         false not encrypted, file encrypted or default block encrypted
     */
    public static boolean isNonDefaultBlockEncrypted() {
        if (!isBlockEncrypted()) {
            return false;
        }

        try {
            IStorageManager storageManager = IStorageManager.Stub.asInterface(
                    ServiceManager.getService("mount"));
            return storageManager.getPasswordType() != CRYPT_TYPE_DEFAULT;
        } catch (RemoteException e) {
            Log.e(TAG, "Error getting encryption type");
            return false;
        }
    }

    /** {@hide}
     * Is this device in the process of being block encrypted?
     * @return true for encrypting.
     *         false otherwise
     * Whether device isEncrypted at this point is undefined
     * Note that only system services and CryptKeeper will ever see this return
     * true - no app will ever be launched in this state.
     * Also note that this state will not change without a teardown of the
     * framework, so no service needs to check for changes during their lifespan
     */
    public static boolean isBlockEncrypting() {
        final String state = VoldProperties.encrypt_progress().orElse("");
        return !"".equalsIgnoreCase(state);
    }

    /** {@hide}
     * Is this device non default block encrypted and in the process of
     * prompting for credentials?
     * @return true for prompting for credentials.
     *         (Implies isNonDefaultBlockEncrypted() == true)
     *         false otherwise
     * Note that only system services and CryptKeeper will ever see this return
     * true - no app will ever be launched in this state.
     * Also note that this state will not change without a teardown of the
     * framework, so no service needs to check for changes during their lifespan
     */
    public static boolean inCryptKeeperBounce() {
        final String status = VoldProperties.decrypt().orElse("");
        return "trigger_restart_min_framework".equals(status);
    }

    /** {@hide} */
    public static boolean isFileEncryptedEmulatedOnly() {
        return SystemProperties.getBoolean(StorageManager.PROP_EMULATE_FBE, false);
    }

    /** {@hide}
     * Is this device running in a file encrypted mode, either native or emulated?
     * @return true for file encrypted, false otherwise
     */
    public static boolean isFileEncryptedNativeOrEmulated() {
        return isFileEncryptedNativeOnly()
               || isFileEncryptedEmulatedOnly();
    }

    /** {@hide} */
    public static boolean hasAdoptable() {
        return SystemProperties.getBoolean(PROP_HAS_ADOPTABLE, false);
    }

    /** {@hide} */
    @SystemApi
    @TestApi
    public static boolean hasIsolatedStorage() {
        // Prefer to use snapshot for current boot when available
        return SystemProperties.getBoolean(PROP_ISOLATED_STORAGE_SNAPSHOT,
                SystemProperties.getBoolean(PROP_ISOLATED_STORAGE, true));
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
        // We can only translate absolute paths
        if (!file.isAbsolute()) return file;

        try {
            return new File(mStorageManager.translateAppToSystem(file.getAbsolutePath(),
                    pid, uid));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Translate given shared storage path from a path in the system namespace
     * to a path in an app sandbox namespace.
     *
     * @hide
     */
    public File translateSystemToApp(File file, int pid, int uid) {
        // We can only translate absolute paths
        if (!file.isAbsolute()) return file;

        try {
            return new File(mStorageManager.translateSystemToApp(file.getAbsolutePath(),
                    pid, uid));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
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

    /** @hide */
    @IntDef(flag = true, prefix = { "FLAG_ALLOCATE_" }, value = {
            FLAG_ALLOCATE_AGGRESSIVE,
            FLAG_ALLOCATE_DEFY_ALL_RESERVED,
            FLAG_ALLOCATE_DEFY_HALF_RESERVED,
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
    @SuppressLint("Doclava125")
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
    @SuppressLint("Doclava125")
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
    @SuppressLint("Doclava125")
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

    /** {@hide} */
    public static UUID convert(String uuid) {
        if (Objects.equals(uuid, UUID_PRIVATE_INTERNAL)) {
            return UUID_DEFAULT;
        } else if (Objects.equals(uuid, UUID_PRIMARY_PHYSICAL)) {
            return UUID_PRIMARY_PHYSICAL_;
        } else if (Objects.equals(uuid, UUID_SYSTEM)) {
            return UUID_SYSTEM_;
        } else {
            return UUID.fromString(uuid);
        }
    }

    /** {@hide} */
    public static String convert(UUID storageUuid) {
        if (UUID_DEFAULT.equals(storageUuid)) {
            return UUID_PRIVATE_INTERNAL;
        } else if (UUID_PRIMARY_PHYSICAL_.equals(storageUuid)) {
            return UUID_PRIMARY_PHYSICAL;
        } else if (UUID_SYSTEM_.equals(storageUuid)) {
            return UUID_SYSTEM;
        } else {
            return storageUuid.toString();
        }
    }

    private final Object mFuseAppLoopLock = new Object();

    @GuardedBy("mFuseAppLoopLock")
    private @Nullable FuseAppLoop mFuseAppLoop = null;

    /// Consts to match the password types in cryptfs.h
    /** @hide */
    @UnsupportedAppUsage
    public static final int CRYPT_TYPE_PASSWORD = IVold.PASSWORD_TYPE_PASSWORD;
    /** @hide */
    @UnsupportedAppUsage
    public static final int CRYPT_TYPE_DEFAULT = IVold.PASSWORD_TYPE_DEFAULT;
    /** @hide */
    public static final int CRYPT_TYPE_PATTERN = IVold.PASSWORD_TYPE_PATTERN;
    /** @hide */
    public static final int CRYPT_TYPE_PIN = IVold.PASSWORD_TYPE_PIN;

    // Constants for the data available via StorageManagerService.getField.
    /** @hide */
    public static final String SYSTEM_LOCALE_KEY = "SystemLocale";
    /** @hide */
    public static final String OWNER_INFO_KEY = "OwnerInfo";
    /** @hide */
    public static final String PATTERN_VISIBLE_KEY = "PatternVisible";
    /** @hide */
    public static final String PASSWORD_VISIBLE_KEY = "PasswordVisible";
}
