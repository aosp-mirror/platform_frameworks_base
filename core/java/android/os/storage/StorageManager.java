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

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import java.util.ArrayList;

/**
 * StorageManager is the interface to the systems storage service.
 * Get an instance of this class by calling
 * {@link android.content.Context#getSystemService(java.lang.String)} with an argument
 * of {@link android.content.Context#STORAGE_SERVICE}.
 */

public class StorageManager
{
    private static final String TAG = "StorageManager";

    /*
     * Our internal MountService binder reference
     */
    private IMountService mMountService;

    /*
     * The looper target for callbacks
     */
    Looper mTgtLooper;

    /*
     * Target listener for binder callbacks
     */
    private MountServiceBinderListener mBinderListener;

    /*
     * List of our listeners
     */
    private ArrayList<ListenerDelegate> mListeners = new ArrayList<ListenerDelegate>();

    private class MountServiceBinderListener extends IMountServiceListener.Stub {
        public void onUsbMassStorageConnectionChanged(boolean available) {
            final int size = mListeners.size();
            for (int i = 0; i < size; i++) {
                mListeners.get(i).sendShareAvailabilityChanged(available);
            }
        }

        public void onStorageStateChanged(String path, String oldState, String newState) {
            final int size = mListeners.size();
            for (int i = 0; i < size; i++) {
                mListeners.get(i).sendStorageStateChanged(path, oldState, newState);
            }
        }
    }

    /**
     * Binder listener for OBB action results.
     */
    private final ObbActionBinderListener mObbActionListener = new ObbActionBinderListener();
    private class ObbActionBinderListener extends IObbActionListener.Stub {
        @Override
        public void onObbResult(String filename, String status) throws RemoteException {
            Log.i(TAG, "filename = " + filename + ", result = " + status);
        }
    }

    /**
     * Private base class for messages sent between the callback thread
     * and the target looper handler.
     */
    private class StorageEvent {
        public static final int EVENT_UMS_CONNECTION_CHANGED = 1;
        public static final int EVENT_STORAGE_STATE_CHANGED   = 2;

        private Message mMessage;

        public StorageEvent(int what) {
            mMessage = Message.obtain();
            mMessage.what = what;
            mMessage.obj = this;
        }

        public Message getMessage() {
            return mMessage;
        }
    }

    /**
     * Message sent on a USB mass storage connection change.
     */
    private class UmsConnectionChangedStorageEvent extends StorageEvent {
        public boolean available;

        public UmsConnectionChangedStorageEvent(boolean a) {
            super(EVENT_UMS_CONNECTION_CHANGED);
            available = a;
        }
    }

    /**
     * Message sent on volume state change.
     */
    private class StorageStateChangedStorageEvent extends StorageEvent {
        public String path;
        public String oldState;
        public String newState;

        public StorageStateChangedStorageEvent(String p, String oldS, String newS) {
            super(EVENT_STORAGE_STATE_CHANGED);
            path = p;
            oldState = oldS;
            newState = newS;
        }
    }

    /**
     * Private class containing sender and receiver code for StorageEvents.
     */
    private class ListenerDelegate {
        final StorageEventListener mStorageEventListener;
        private final Handler mHandler;

        ListenerDelegate(StorageEventListener listener) {
            mStorageEventListener = listener;
            mHandler = new Handler(mTgtLooper) {
                @Override
                public void handleMessage(Message msg) {
                    StorageEvent e = (StorageEvent) msg.obj;

                    if (msg.what == StorageEvent.EVENT_UMS_CONNECTION_CHANGED) {
                        UmsConnectionChangedStorageEvent ev = (UmsConnectionChangedStorageEvent) e;
                        mStorageEventListener.onUsbMassStorageConnectionChanged(ev.available);
                    } else if (msg.what == StorageEvent.EVENT_STORAGE_STATE_CHANGED) {
                        StorageStateChangedStorageEvent ev = (StorageStateChangedStorageEvent) e;
                        mStorageEventListener.onStorageStateChanged(ev.path, ev.oldState, ev.newState);
                    } else {
                        Log.e(TAG, "Unsupported event " + msg.what);
                    }
                }
            };
        }

        StorageEventListener getListener() {
            return mStorageEventListener;
        }

        void sendShareAvailabilityChanged(boolean available) {
            UmsConnectionChangedStorageEvent e = new UmsConnectionChangedStorageEvent(available);
            mHandler.sendMessage(e.getMessage());
        }

        void sendStorageStateChanged(String path, String oldState, String newState) {
            StorageStateChangedStorageEvent e = new StorageStateChangedStorageEvent(path, oldState, newState);
            mHandler.sendMessage(e.getMessage());
        }
    }

    /**
     * Constructs a StorageManager object through which an application can
     * can communicate with the systems mount service.
     * 
     * @param tgtLooper The {@android.os.Looper} which events will be received on.
     *
     * <p>Applications can get instance of this class by calling
     * {@link android.content.Context#getSystemService(java.lang.String)} with an argument
     * of {@link android.content.Context#STORAGE_SERVICE}.
     *
     * @hide
     */
    public StorageManager(Looper tgtLooper) throws RemoteException {
        mMountService = IMountService.Stub.asInterface(ServiceManager.getService("mount"));
        if (mMountService == null) {
            Log.e(TAG, "Unable to connect to mount service! - is it running yet?");
            return;
        }
        mTgtLooper = tgtLooper;
        mBinderListener = new MountServiceBinderListener();
        mMountService.registerListener(mBinderListener);
    }


    /**
     * Registers a {@link android.os.storage.StorageEventListener StorageEventListener}.
     *
     * @param listener A {@link android.os.storage.StorageEventListener StorageEventListener} object.
     *
     * @hide
     */
    public void registerListener(StorageEventListener listener) {
        if (listener == null) {
            return;
        }

        synchronized (mListeners) {
            mListeners.add(new ListenerDelegate(listener));
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
        if (listener == null) {
            return;
        }

        synchronized (mListeners) {
            final int size = mListeners.size();
            for (int i=0 ; i<size ; i++) {
                ListenerDelegate l = mListeners.get(i);
                if (l.getListener() == listener) {
                    mListeners.remove(i);
                    break;
                }
            }
        }
    }

    /**
     * Enables USB Mass Storage (UMS) on the device.
     *
     * @hide
     */
    public void enableUsbMassStorage() {
        try {
            mMountService.setUsbMassStorageEnabled(true);
        } catch (Exception ex) {
            Log.e(TAG, "Failed to enable UMS", ex);
        }
    }

    /**
     * Disables USB Mass Storage (UMS) on the device.
     *
     * @hide
     */
    public void disableUsbMassStorage() {
        try {
            mMountService.setUsbMassStorageEnabled(false);
        } catch (Exception ex) {
            Log.e(TAG, "Failed to disable UMS", ex);
        }
    }

    /**
     * Query if a USB Mass Storage (UMS) host is connected.
     * @return true if UMS host is connected.
     *
     * @hide
     */
    public boolean isUsbMassStorageConnected() {
        try {
            return mMountService.isUsbMassStorageConnected();
        } catch (Exception ex) {
            Log.e(TAG, "Failed to get UMS connection state", ex);
        }
        return false;
    }

    /**
     * Query if a USB Mass Storage (UMS) is enabled on the device.
     * @return true if UMS host is enabled.
     *
     * @hide
     */
    public boolean isUsbMassStorageEnabled() {
        try {
            return mMountService.isUsbMassStorageEnabled();
        } catch (RemoteException rex) {
            Log.e(TAG, "Failed to get UMS enable state", rex);
        }
        return false;
    }

    /**
     * Mount an Opaque Binary Blob (OBB) file. If a <code>key</code> is
     * specified, it is supplied to the mounting process to be used in any
     * encryption used in the OBB.
     * <p>
     * <em>Note:</em> you can only mount OBB files for which the OBB tag on the
     * file matches a package ID that is owned by the calling program's UID.
     * That is, shared UID applications can obtain access to any other
     * application's OBB that shares its UID.
     * 
     * @param filename the path to the OBB file
     * @param key decryption key
     * @return whether the mount call was successfully queued or not
     */
    public boolean mountObb(String filename, String key) {
        try {
            mMountService.mountObb(filename, key, mObbActionListener);
            return true;
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to mount OBB", e);
        }

        return false;
    }

    /**
     * Unmount an Opaque Binary Blob (OBB) file. If the <code>force</code> flag
     * is true, it will kill any application needed to unmount the given OBB.
     * <p>
     * <em>Note:</em> you can only mount OBB files for which the OBB tag on the
     * file matches a package ID that is owned by the calling program's UID.
     * That is, shared UID applications can obtain access to any other
     * application's OBB that shares its UID.
     * 
     * @param filename path to the OBB file
     * @param force whether to kill any programs using this in order to unmount
     *            it
     * @return whether the unmount call was successfully queued or not
     * @throws IllegalArgumentException when OBB is not already mounted
     */
    public boolean unmountObb(String filename, boolean force) throws IllegalArgumentException {
        try {
            mMountService.unmountObb(filename, force, mObbActionListener);
            return true;
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to mount OBB", e);
        }

        return false;
    }

    /**
     * Check whether an Opaque Binary Blob (OBB) is mounted or not.
     * 
     * @param filename path to OBB image
     * @return true if OBB is mounted; false if not mounted or on error
     */
    public boolean isObbMounted(String filename) throws IllegalArgumentException {
        try {
            return mMountService.isObbMounted(filename);
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
     * @param filename path to OBB image
     * @return absolute path to mounted OBB image data or <code>null</code> if
     *         not mounted or exception encountered trying to read status
     */
    public String getMountedObbPath(String filename) {
        try {
            return mMountService.getMountedObbPath(filename);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to find mounted path for OBB", e);
        } catch (IllegalArgumentException e) {
            Log.d(TAG, "Couldn't read OBB file", e);
        }

        return null;
    }
}
