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
import android.content.ContentResolver;
import android.content.Context;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.os.SomeArgs;
import com.android.internal.util.Preconditions;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
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

    private final Context mContext;
    private final ContentResolver mResolver;

    private final IMountService mMountService;
    private final Looper mLooper;
    private final AtomicInteger mNextNonce = new AtomicInteger(0);

    private final ArrayList<StorageEventListenerDelegate> mDelegates = new ArrayList<>();

    private static class StorageEventListenerDelegate extends IMountServiceListener.Stub implements
            Handler.Callback {
        private static final int MSG_STORAGE_STATE_CHANGED = 1;
        private static final int MSG_VOLUME_STATE_CHANGED = 2;

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
    public static StorageManager from(Context context) {
        return (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
    }

    /**
     * Constructs a StorageManager object through which an application can
     * can communicate with the systems mount service.
     * 
     * @param tgtLooper The {@link android.os.Looper} which events will be received on.
     *
     * <p>Applications can get instance of this class by calling
     * {@link android.content.Context#getSystemService(java.lang.String)} with an argument
     * of {@link android.content.Context#STORAGE_SERVICE}.
     *
     * @hide
     */
    public StorageManager(Context context, Looper looper) {
        mContext = context;
        mResolver = context.getContentResolver();
        mLooper = looper;
        mMountService = IMountService.Stub.asInterface(ServiceManager.getService("mount"));
        if (mMountService == null) {
            Log.e(TAG, "Unable to connect to mount service! - is it running yet?");
            return;
        }
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
                mMountService.registerListener(delegate);
            } catch (RemoteException e) {
                throw e.rethrowAsRuntimeException();
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
                        mMountService.unregisterListener(delegate);
                    } catch (RemoteException e) {
                        throw e.rethrowAsRuntimeException();
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
            mMountService.mountObb(rawPath, canonicalPath, key, mObbActionListener, nonce);
            return true;
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to resolve path: " + rawPath, e);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to mount OBB", e);
        }

        return false;
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
            mMountService.unmountObb(rawPath, force, mObbActionListener, nonce);
            return true;
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to mount OBB", e);
        }

        return false;
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
            return mMountService.isObbMounted(rawPath);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to check if OBB is mounted", e);
        }

        return false;
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
            return mMountService.getMountedObbPath(rawPath);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to find mounted path for OBB", e);
        }

        return null;
    }

    /** {@hide} */
    public @NonNull List<DiskInfo> getDisks() {
        try {
            return Arrays.asList(mMountService.getDisks());
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /** {@hide} */
    public @NonNull List<VolumeInfo> getVolumes() {
        try {
            return Arrays.asList(mMountService.getVolumes());
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /** {@hide} */
    public void mount(String volId) {
        try {
            mMountService.mount(volId);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /** {@hide} */
    public void unmount(String volId) {
        try {
            mMountService.unmount(volId);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /** {@hide} */
    public void format(String volId) {
        try {
            mMountService.format(volId);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /** {@hide} */
    public void partitionPublic(String diskId) {
        try {
            mMountService.partitionPublic(diskId);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /** {@hide} */
    public void partitionPrivate(String diskId) {
        try {
            mMountService.partitionPrivate(diskId);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /** {@hide} */
    public void partitionMixed(String diskId, int ratio) {
        try {
            mMountService.partitionMixed(diskId, ratio);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /** {@hide} */
    public @Nullable StorageVolume getStorageVolume(File file) {
        return getStorageVolume(getVolumeList(), file);
    }

    /** {@hide} */
    public static @Nullable StorageVolume getStorageVolume(File file, int userId) {
        return getStorageVolume(getVolumeList(userId), file);
    }

    /** {@hide} */
    private static @Nullable StorageVolume getStorageVolume(StorageVolume[] volumes, File file) {
        File canonicalFile = null;
        try {
            canonicalFile = file.getCanonicalFile();
        } catch (IOException ignored) {
            canonicalFile = null;
        }
        for (StorageVolume volume : volumes) {
            if (volume.getPathFile().equals(file)) {
                return volume;
            }
            if (FileUtils.contains(volume.getPathFile(), canonicalFile)) {
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

    /** {@hide} */
    public @NonNull StorageVolume[] getVolumeList() {
        return getVolumeList(mContext.getUserId());
    }

    /** {@hide} */
    public static @NonNull StorageVolume[] getVolumeList(int userId) {
        final IMountService mountService = IMountService.Stub.asInterface(
                ServiceManager.getService("mount"));
        try {
            return mountService.getVolumeList(userId);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
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

    /** {@hide} */
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

    /// Consts to match the password types in cryptfs.h
    /** @hide */
    public static final int CRYPT_TYPE_PASSWORD = 0;
    /** @hide */
    public static final int CRYPT_TYPE_DEFAULT = 1;
    /** @hide */
    public static final int CRYPT_TYPE_PATTERN = 2;
    /** @hide */
    public static final int CRYPT_TYPE_PIN = 3;

    // Constants for the data available via MountService.getField.
    /** @hide */
    public static final String SYSTEM_LOCALE_KEY = "SystemLocale";
    /** @hide */
    public static final String OWNER_INFO_KEY = "OwnerInfo";
    /** @hide */
    public static final String PATTERN_VISIBLE_KEY = "PatternVisible";
}
