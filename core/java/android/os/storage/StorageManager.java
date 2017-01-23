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

import static android.net.TrafficStats.MB_IN_BYTES;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.app.ActivityThread;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.IPackageMoveObserver;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.ProxyFileDescriptorCallback;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.AppFuseMount;
import com.android.internal.os.FuseAppLoop;
import com.android.internal.os.RoSystemProperties;
import com.android.internal.os.SomeArgs;
import com.android.internal.util.Preconditions;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadFactory;
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
 * <p>
 * Get an instance of this class by calling
 * {@link android.content.Context#getSystemService(java.lang.String)} with an
 * argument of {@link android.content.Context#STORAGE_SERVICE}.
 */
public class StorageManager {
    private static final String TAG = "StorageManager";

    /** {@hide} */
    public static final String PROP_PRIMARY_PHYSICAL = "ro.vold.primary_physical";
    /** {@hide} */
    public static final String PROP_HAS_ADOPTABLE = "vold.has_adoptable";
    /** {@hide} */
    public static final String PROP_FORCE_ADOPTABLE = "persist.fw.force_adoptable";
    /** {@hide} */
    public static final String PROP_EMULATE_FBE = "persist.sys.emulate_fbe";
    /** {@hide} */
    public static final String PROP_SDCARDFS = "persist.sys.sdcardfs";

    /** {@hide} */
    public static final String UUID_PRIVATE_INTERNAL = null;
    /** {@hide} */
    public static final String UUID_PRIMARY_PHYSICAL = "primary_physical";


    /**
     * Activity Action: Allows the user to manage their storage. This activity provides the ability
     * to free up space on the device by deleting data such as apps.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstant.SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_MANAGE_STORAGE
            = "android.os.storage.action.MANAGE_STORAGE";

    /** {@hide} */
    public static final int DEBUG_FORCE_ADOPTABLE = 1 << 0;
    /** {@hide} */
    public static final int DEBUG_EMULATE_FBE = 1 << 1;
    /** {@hide} */
    public static final int DEBUG_SDCARDFS_FORCE_ON = 1 << 2;
    /** {@hide} */
    public static final int DEBUG_SDCARDFS_FORCE_OFF = 1 << 3;

    // NOTE: keep in sync with installd
    /** {@hide} */
    public static final int FLAG_STORAGE_DE = 1 << 0;
    /** {@hide} */
    public static final int FLAG_STORAGE_CE = 1 << 1;

    /** {@hide} */
    public static final int FLAG_FOR_WRITE = 1 << 8;
    /** {@hide} */
    public static final int FLAG_REAL_STATE = 1 << 9;
    /** {@hide} */
    public static final int FLAG_INCLUDE_INVISIBLE = 1 << 10;

    /** {@hide} */
    public static final int FSTRIM_FLAG_DEEP = 1 << 0;
    /** {@hide} */
    public static final int FSTRIM_FLAG_BENCHMARK = 1 << 1;

    /** @hide The volume is not encrypted. */
    public static final int ENCRYPTION_STATE_NONE = 1;

    /** @hide The volume has been encrypted succesfully. */
    public static final int ENCRYPTION_STATE_OK = 0;

    /** @hide The volume is in a bad state.*/
    public static final int ENCRYPTION_STATE_ERROR_UNKNOWN = -1;

    /** @hide Encryption is incomplete */
    public static final int ENCRYPTION_STATE_ERROR_INCOMPLETE = -2;

    /** @hide Encryption is incomplete and irrecoverable */
    public static final int ENCRYPTION_STATE_ERROR_INCONSISTENT = -3;

    /** @hide Underlying data is corrupt */
    public static final int ENCRYPTION_STATE_ERROR_CORRUPT = -4;

    private static volatile IStorageManager sStorageManager = null;

    // TODO: the location of the primary storage block varies from device to device, so we need to
    // try the most likely candidates - a long-term solution would be a device-specific vold
    // function that returns the calculated size.
    private static final String[] INTERNAL_STORAGE_SIZE_PATHS = {
            "/sys/block/mmcblk0/size",
            "/sys/block/sda/size"
    };
    private static final int INTERNAL_STORAGE_SECTOR_SIZE = 512;

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
    public void enableUsbMassStorage() {
    }

    /**
     * Disables USB Mass Storage (UMS) on the device.
     *
     * @hide
     */
    @Deprecated
    public void disableUsbMassStorage() {
    }

    /**
     * Query if a USB Mass Storage (UMS) host is connected.
     * @return true if UMS host is connected.
     *
     * @hide
     */
    @Deprecated
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
            mStorageManager.mountObb(rawPath, canonicalPath, key, mObbActionListener, nonce);
            return true;
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to resolve path: " + rawPath, e);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
    public @NonNull List<DiskInfo> getDisks() {
        try {
            return Arrays.asList(mStorageManager.getDisks());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** {@hide} */
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

    /** {@hide} */
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
    public void unmount(String volId) {
        try {
            mStorageManager.unmount(volId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** {@hide} */
    public void format(String volId) {
        try {
            mStorageManager.format(volId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** {@hide} */
    public long benchmark(String volId) {
        try {
            return mStorageManager.benchmark(volId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** {@hide} */
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
     * Return the {@link StorageVolume} that contains the given file, or {@code null} if none.
     */
    public @Nullable StorageVolume getStorageVolume(File file) {
        return getStorageVolume(getVolumeList(), file);
    }

    /** {@hide} */
    public static @Nullable StorageVolume getStorageVolume(File file, int userId) {
        return getStorageVolume(getVolumeList(userId, 0), file);
    }

    /** {@hide} */
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
                getVolumeList(UserHandle.myUserId(), FLAG_REAL_STATE | FLAG_INCLUDE_INVISIBLE));
        return res;
    }

    /**
     * Return the primary shared/external storage volume available to the
     * current user. This volume is the same storage device returned by
     * {@link Environment#getExternalStorageDirectory()} and
     * {@link Context#getExternalFilesDir(String)}.
     */
    public @NonNull StorageVolume getPrimaryStorageVolume() {
        return getVolumeList(UserHandle.myUserId(), FLAG_REAL_STATE | FLAG_INCLUDE_INVISIBLE)[0];
    }

    /** {@hide} */
    public static Pair<String, Long> getPrimaryStoragePathAndSize() {
        for (String path : INTERNAL_STORAGE_SIZE_PATHS) {
            final long numberBlocks = readLong(path);
            if (numberBlocks > 0) {
                return new Pair<>(path, Long.valueOf(numberBlocks * INTERNAL_STORAGE_SECTOR_SIZE));
            }
        }
        return null;
    }


    /** {@hide} */
    public long getPrimaryStorageSize() {
        final Pair<String, Long> pair = getPrimaryStoragePathAndSize();
        return pair == null ? 0 : pair.second.longValue();
    }

    private static long readLong(String path) {
        try (final FileInputStream fis = new FileInputStream(path);
                final BufferedReader reader = new BufferedReader(new InputStreamReader(fis));) {
            return Long.parseLong(reader.readLine());
        } catch (FileNotFoundException e) {
            // This is expected since we are trying to parse multiple paths.
            Slog.i(TAG, "readLong(): Path doesn't exist: " + path + ": " + e);
            return 0;
        } catch (NumberFormatException e) {
            Slog.e(TAG, "readLong(): Could not parse " + path + ": " + e);
            return 0;
        } catch (Exception e) {
            Slog.e(TAG, "readLong(): Unknown exception while opening " + path + ": " + e);
            return 0;
       }
    }

    /** @removed */
    public @NonNull StorageVolume[] getVolumeList() {
        return getVolumeList(mContext.getUserId(), 0);
    }

    /** {@hide} */
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

    /** {@hide} */
    private static final int DEFAULT_THRESHOLD_PERCENTAGE = 10;
    private static final long DEFAULT_THRESHOLD_MAX_BYTES = 500 * MB_IN_BYTES;
    private static final long DEFAULT_FULL_THRESHOLD_BYTES = MB_IN_BYTES;

    /**
     * Return the number of available bytes until the given path is considered
     * running low on storage.
     *
     * @hide
     */
    public long getStorageBytesUntilLow(File path) {
        return path.getUsableSpace() - getStorageFullBytes(path);
    }

    /**
     * Return the number of available bytes at which the given path is
     * considered running low on storage.
     *
     * @hide
     */
    public long getStorageLowBytes(File path) {
        final long lowPercent = Settings.Global.getInt(mResolver,
                Settings.Global.SYS_STORAGE_THRESHOLD_PERCENTAGE, DEFAULT_THRESHOLD_PERCENTAGE);
        final long lowBytes = (path.getTotalSpace() * lowPercent) / 100;

        final long maxLowBytes = Settings.Global.getLong(mResolver,
                Settings.Global.SYS_STORAGE_THRESHOLD_MAX_BYTES, DEFAULT_THRESHOLD_MAX_BYTES);

        return Math.min(lowBytes, maxLowBytes);
    }

    /**
     * Return the number of available bytes at which the given path is
     * considered full.
     *
     * @hide
     */
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
        final String state = SystemProperties.get("vold.encrypt_progress", "");
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
        final String status = SystemProperties.get("vold.decrypt");
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
    public static File maybeTranslateEmulatedPathToInternal(File path) {
        final IStorageManager storageManager = IStorageManager.Stub.asInterface(
                ServiceManager.getService("mount"));
        try {
            final VolumeInfo[] vols = storageManager.getVolumes(0);
            for (VolumeInfo vol : vols) {
                if ((vol.getType() == VolumeInfo.TYPE_EMULATED
                        || vol.getType() == VolumeInfo.TYPE_PUBLIC) && vol.isMountedReadable()) {
                    final File internalPath = FileUtils.rewriteAfterRename(vol.getPath(),
                            vol.getInternalPath(), path);
                    if (internalPath != null && internalPath.exists()) {
                        return internalPath;
                    }
                }
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return path;
    }

    /** {@hide} */
    @VisibleForTesting
    public @NonNull ParcelFileDescriptor openProxyFileDescriptor(
            int mode, ProxyFileDescriptorCallback callback, ThreadFactory factory)
                    throws IOException {
        // Retry is needed because the mount point mFuseAppLoop is using may be unmounted before
        // invoking StorageManagerService#openProxyFileDescriptor. In this case, we need to re-mount
        // the bridge by calling mountProxyFileDescriptorBridge.
        int retry = 3;
        while (retry-- > 0) {
            try {
                synchronized (mFuseAppLoopLock) {
                    if (mFuseAppLoop == null) {
                        final AppFuseMount mount = mStorageManager.mountProxyFileDescriptorBridge();
                        if (mount == null) {
                            Log.e(TAG, "Failed to open proxy file bridge.");
                            throw new IOException("Failed to open proxy file bridge.");
                        }
                        mFuseAppLoop = FuseAppLoop.open(mount.mountPointId, mount.fd, factory);
                    }

                    try {
                        final int fileId = mFuseAppLoop.registerCallback(callback);
                        final ParcelFileDescriptor pfd =
                                mStorageManager.openProxyFileDescriptor(
                                        mFuseAppLoop.getMountPointId(), fileId, mode);
                        if (pfd != null) {
                            return pfd;
                        }
                        // Probably the bridge is being unmounted but mFuseAppLoop has not been
                        // noticed it yet.
                        mFuseAppLoop.unregisterCallback(fileId);
                    } catch (FuseAppLoop.UnmountedException error) {
                        Log.d(TAG, "mFuseAppLoop has been already unmounted.");
                        mFuseAppLoop = null;
                        continue;
                    }
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    break;
                }
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        }

        throw new IOException("Failed to mount bridge.");
    }

    /**
     * Opens seekable ParcelFileDescriptor that routes file operation requests to
     * ProxyFileDescriptorCallback.
     *
     * @param mode The desired access mode, must be one of
     *     {@link ParcelFileDescriptor#MODE_READ_ONLY},
     *     {@link ParcelFileDescriptor#MODE_WRITE_ONLY}, or
     *     {@link ParcelFileDescriptor#MODE_READ_WRITE}
     * @param callback Callback to process file operation requests issued on returned file
     *     descriptor. The callback is invoked on a thread managed by the framework.
     * @return Seekable ParcelFileDescriptor.
     * @throws IOException
     */
    public @NonNull ParcelFileDescriptor openProxyFileDescriptor(
            int mode, ProxyFileDescriptorCallback callback)
                    throws IOException {
        return openProxyFileDescriptor(mode, callback, null);
    }

    /** {@hide} */
    @VisibleForTesting
    public int getProxyFileDescriptorMountPointId() {
        synchronized (mFuseAppLoopLock) {
            return mFuseAppLoop != null ? mFuseAppLoop.getMountPointId() : -1;
        }
    }

    private final Object mFuseAppLoopLock = new Object();

    @GuardedBy("mFuseAppLoopLock")
    private @Nullable FuseAppLoop mFuseAppLoop = null;

    /// Consts to match the password types in cryptfs.h
    /** @hide */
    public static final int CRYPT_TYPE_PASSWORD = 0;
    /** @hide */
    public static final int CRYPT_TYPE_DEFAULT = 1;
    /** @hide */
    public static final int CRYPT_TYPE_PATTERN = 2;
    /** @hide */
    public static final int CRYPT_TYPE_PIN = 3;

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
