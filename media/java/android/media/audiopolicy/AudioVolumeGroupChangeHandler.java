/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.media.audiopolicy;

import android.annotation.NonNull;
import android.media.AudioManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;

import com.android.internal.util.Preconditions;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * The AudioVolumeGroupChangeHandler handles AudioManager.OnAudioVolumeGroupChangedListener
 * callbacks posted from JNI
 *
 * TODO: Make use of Executor of callbacks.
 * @hide
 */
public class AudioVolumeGroupChangeHandler {
    private Handler mHandler;
    private HandlerThread mHandlerThread;
    private final ArrayList<AudioManager.VolumeGroupCallback> mListeners =
            new ArrayList<AudioManager.VolumeGroupCallback>();

    private static final String TAG = "AudioVolumeGroupChangeHandler";

    private static final int AUDIOVOLUMEGROUP_EVENT_VOLUME_CHANGED = 1000;
    private static final int AUDIOVOLUMEGROUP_EVENT_NEW_LISTENER = 4;

    /**
     * Accessed by native methods: JNI Callback context.
     */
    @SuppressWarnings("unused")
    private long mJniCallback;

    /**
     * Initialization
     */
    public void init() {
        synchronized (this) {
            if (mHandler != null) {
                return;
            }
            // create a new thread for our new event handler
            mHandlerThread = new HandlerThread(TAG);
            mHandlerThread.start();

            if (mHandlerThread.getLooper() == null) {
                mHandler = null;
                return;
            }
            mHandler = new Handler(mHandlerThread.getLooper()) {
                @Override
                public void handleMessage(Message msg) {
                    ArrayList<AudioManager.VolumeGroupCallback> listeners;
                    synchronized (this) {
                        if (msg.what == AUDIOVOLUMEGROUP_EVENT_NEW_LISTENER) {
                            listeners =
                                    new ArrayList<AudioManager.VolumeGroupCallback>();
                            if (mListeners.contains(msg.obj)) {
                                listeners.add(
                                        (AudioManager.VolumeGroupCallback) msg.obj);
                            }
                        } else {
                            listeners = (ArrayList<AudioManager.VolumeGroupCallback>)
                                    mListeners.clone();
                        }
                    }
                    if (listeners.isEmpty()) {
                        return;
                    }

                    switch (msg.what) {
                        case AUDIOVOLUMEGROUP_EVENT_VOLUME_CHANGED:
                            for (int i = 0; i < listeners.size(); i++) {
                                listeners.get(i).onAudioVolumeGroupChanged((int) msg.arg1,
                                                                           (int) msg.arg2);
                            }
                            break;

                        default:
                            break;
                    }
                }
            };
            native_setup(new WeakReference<AudioVolumeGroupChangeHandler>(this));
        }
    }

    private native void native_setup(Object moduleThis);

    @Override
    protected void finalize() {
        native_finalize();
        if (mHandlerThread.isAlive()) {
            mHandlerThread.quit();
        }
    }
    private native void native_finalize();

   /**
    * @param cb the {@link AudioManager.VolumeGroupCallback} to register
    */
    public void registerListener(@NonNull AudioManager.VolumeGroupCallback cb) {
        Preconditions.checkNotNull(cb, "volume group callback shall not be null");
        synchronized (this) {
            mListeners.add(cb);
        }
        if (mHandler != null) {
            Message m = mHandler.obtainMessage(
                    AUDIOVOLUMEGROUP_EVENT_NEW_LISTENER, 0, 0, cb);
            mHandler.sendMessage(m);
        }
    }

   /**
    * @param cb the {@link AudioManager.VolumeGroupCallback} to unregister
    */
    public void unregisterListener(@NonNull AudioManager.VolumeGroupCallback cb) {
        Preconditions.checkNotNull(cb, "volume group callback shall not be null");
        synchronized (this) {
            mListeners.remove(cb);
        }
    }

    Handler handler() {
        return mHandler;
    }

    @SuppressWarnings("unused")
    private static void postEventFromNative(Object moduleRef,
                                            int what, int arg1, int arg2, Object obj) {
        AudioVolumeGroupChangeHandler eventHandler =
                (AudioVolumeGroupChangeHandler) ((WeakReference) moduleRef).get();
        if (eventHandler == null) {
            return;
        }

        if (eventHandler != null) {
            Handler handler = eventHandler.handler();
            if (handler != null) {
                Message m = handler.obtainMessage(what, arg1, arg2, obj);
                // Do not remove previous messages, as we would lose notification of group changes
                handler.sendMessage(m);
            }
        }
    }
}
