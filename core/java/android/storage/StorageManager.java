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

package android.storage;

import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.Looper;
import android.os.Parcelable;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.Handler;
import android.os.Message;
import android.os.ServiceManager;
import android.os.IMountService;
import android.os.IMountServiceListener;
import android.util.Log;
import android.util.SparseArray;

import java.io.FileDescriptor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * Class that lets you access the device's storage management functions. Get an instance of this
 * class by calling {@link android.content.Context#getSystemService(java.lang.String)
 * Context.getSystemService()} with an argument of {@link android.content.Context#STORAGE_SERVICE}.
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
     * *static* list of our listeners
     */
    static final ArrayList<ListenerDelegate> sListeners = new ArrayList<ListenerDelegate>();

    private class MountServiceBinderListener extends IMountServiceListener.Stub {
        public void onShareAvailabilityChanged(String method, boolean available) {
            final int size = sListeners.size();
            for (int i = 0; i < size; i++) {
                sListeners.get(i).sendShareAvailabilityChanged(method, available);
            }
        }

        public void onMediaInserted(String label, String path, int major, int minor) {
            final int size = sListeners.size();
            for (int i = 0; i < size; i++) {
                sListeners.get(i).sendMediaInserted(label, path, major, minor);
            }
        }

        public void onMediaRemoved(String label, String path, int major, int minor, boolean clean) {
            final int size = sListeners.size();
            for (int i = 0; i < size; i++) {
                sListeners.get(i).sendMediaRemoved(label, path, major, minor, clean);
            }
        }

        public void onVolumeStateChanged(String label, String path, String oldState, String newState) {
            final int size = sListeners.size();
            for (int i = 0; i < size; i++) {
                sListeners.get(i).sendVolumeStateChanged(label, path, oldState, newState);
            }
        }
    }

    /**
     * Private base class for messages sent between the callback thread
     * and the target looper handler
     */
    private class StorageEvent {
        public static final int EVENT_SHARE_AVAILABILITY_CHANGED = 1;
        public static final int EVENT_MEDIA_INSERTED             = 2;
        public static final int EVENT_MEDIA_REMOVED              = 3;
        public static final int EVENT_VOLUME_STATE_CHANGED       = 4;

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
     * Message sent on a share availability change.
     */
    private class ShareAvailabilityChangedStorageEvent extends StorageEvent {
        public String method;
        public boolean available;

        public ShareAvailabilityChangedStorageEvent(String m, boolean a) {
            super(EVENT_SHARE_AVAILABILITY_CHANGED);
            method = m;
            available = a;
        }
    }

    /**
     * Message sent on media insertion
     */
    private class MediaInsertedStorageEvent extends StorageEvent {
        public String label;
        public String path;
        public int major;
        public int minor;

        public MediaInsertedStorageEvent(String l, String p, int maj, int min) {
            super(EVENT_MEDIA_INSERTED);
            label = l;
            path = p;
            major = maj;
            minor = min;
        }
    }

    /**
     * Message sent on media removal
     */
    private class MediaRemovedStorageEvent extends StorageEvent {
        public String label;
        public String path;
        public int major;
        public int minor;
        public boolean clean;

        public MediaRemovedStorageEvent(String l, String p, int maj, int min, boolean c) {
            super(EVENT_MEDIA_REMOVED);
            label = l;
            path = p;
            major = maj;
            minor = min;
            clean = c;
        }
    }

    /**
     * Message sent on volume state change
     */
    private class VolumeStateChangedStorageEvent extends StorageEvent {
        public String label;
        public String path;
        public String oldState;
        public String newState;

        public VolumeStateChangedStorageEvent(String l, String p, String oldS, String newS) {
            super(EVENT_VOLUME_STATE_CHANGED);
            label = l;
            path = p;
            oldState = oldS;
            newState = newS;
        }
    }

    /**
     * Private class containing sender and receiver code for StorageEvents
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

                    if (msg.what == StorageEvent.EVENT_SHARE_AVAILABILITY_CHANGED) {
                        ShareAvailabilityChangedStorageEvent ev = (ShareAvailabilityChangedStorageEvent) e;
                        mStorageEventListener.onShareAvailabilityChanged(ev.method, ev.available);
                    } else if (msg.what == StorageEvent.EVENT_MEDIA_INSERTED) {
                        MediaInsertedStorageEvent ev = (MediaInsertedStorageEvent) e;
                        mStorageEventListener.onMediaInserted(ev.label, ev.path, ev.major, ev.minor);
                    } else if (msg.what == StorageEvent.EVENT_MEDIA_REMOVED) {
                        MediaRemovedStorageEvent ev = (MediaRemovedStorageEvent) e;
                        mStorageEventListener.onMediaRemoved(ev.label, ev.path, ev.major, ev.minor, ev.clean);
                    } else if (msg.what == StorageEvent.EVENT_VOLUME_STATE_CHANGED) {
                        VolumeStateChangedStorageEvent ev = (VolumeStateChangedStorageEvent) e;
                        mStorageEventListener.onVolumeStateChanged(ev.label, ev.path, ev.oldState, ev.newState);
                    } else {
                        Log.e(TAG, "Unsupported event " + msg.what);
                    }
                }
            };
        }

        StorageEventListener getListener() {
            return mStorageEventListener;
        }

        void sendShareAvailabilityChanged(String method, boolean available) {
            ShareAvailabilityChangedStorageEvent e = new ShareAvailabilityChangedStorageEvent(method, available);
            mHandler.sendMessage(e.getMessage());
        }

        void sendMediaInserted(String label, String path, int major, int minor) {
            MediaInsertedStorageEvent e = new MediaInsertedStorageEvent(label, path, major, minor);
            mHandler.sendMessage(e.getMessage());
        }

        void sendMediaRemoved(String label, String path, int major, int minor, boolean clean) {
            MediaRemovedStorageEvent e = new MediaRemovedStorageEvent(label, path, major, minor, clean);
            mHandler.sendMessage(e.getMessage());
        }

        void sendVolumeStateChanged(String label, String path, String oldState, String newState) {
            VolumeStateChangedStorageEvent e = new VolumeStateChangedStorageEvent(label, path, oldState, newState);
            mHandler.sendMessage(e.getMessage());
        }
    }

    /**
     * {@hide}
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
     * Registers a {@link android.storage.StorageEventListener StorageEventListener}.
     *
     * @param listener A {@link android.storage.StorageEventListener StorageEventListener} object.
     *
     */
    public void registerListener(StorageEventListener listener) {
        if (listener == null) {
            return;
        }

        synchronized (sListeners) {
            sListeners.add(new ListenerDelegate(listener));
        }
    }

    /**
     * Unregisters a {@link android.storage.StorageEventListener StorageEventListener}.
     *
     * @param listener A {@link android.storage.StorageEventListener StorageEventListener} object.
     *
     */
    public void unregisterListener(StorageEventListener listener) {
        if (listener == null) {
            return;
        }
        synchronized (sListeners) {
            final int size = sListeners.size();
            for (int i=0 ; i<size ; i++) {
                ListenerDelegate l = sListeners.get(i);
                if (l.getListener() == listener) {
                    sListeners.remove(i);
                    break;
                }
            }
        }
    }
}
