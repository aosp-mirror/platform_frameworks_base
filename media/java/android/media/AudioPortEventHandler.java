/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.media;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import java.util.ArrayList;
import java.lang.ref.WeakReference;

/**
 * The AudioPortEventHandler handles AudioManager.OnAudioPortUpdateListener callbacks
 * posted from JNI
 * @hide
 */

class AudioPortEventHandler {
    private Handler mHandler;
    private final ArrayList<AudioManager.OnAudioPortUpdateListener> mListeners =
            new ArrayList<AudioManager.OnAudioPortUpdateListener>();

    private static final String TAG = "AudioPortEventHandler";

    private static final int AUDIOPORT_EVENT_PORT_LIST_UPDATED = 1;
    private static final int AUDIOPORT_EVENT_PATCH_LIST_UPDATED = 2;
    private static final int AUDIOPORT_EVENT_SERVICE_DIED = 3;
    private static final int AUDIOPORT_EVENT_NEW_LISTENER = 4;

    void init() {
        synchronized (this) {
            if (mHandler != null) {
                return;
            }
            // find the looper for our new event handler
            Looper looper = Looper.getMainLooper();

            if (looper != null) {
                mHandler = new Handler(looper) {
                    @Override
                    public void handleMessage(Message msg) {
                        ArrayList<AudioManager.OnAudioPortUpdateListener> listeners;
                        synchronized (this) {
                            if (msg.what == AUDIOPORT_EVENT_NEW_LISTENER) {
                                listeners = new ArrayList<AudioManager.OnAudioPortUpdateListener>();
                                if (mListeners.contains(msg.obj)) {
                                    listeners.add((AudioManager.OnAudioPortUpdateListener)msg.obj);
                                }
                            } else {
                                listeners = mListeners;
                            }
                        }
                        if (listeners.isEmpty()) {
                            return;
                        }
                        // reset audio port cache if the event corresponds to a change coming
                        // from audio policy service or if mediaserver process died.
                        if (msg.what == AUDIOPORT_EVENT_PORT_LIST_UPDATED ||
                                msg.what == AUDIOPORT_EVENT_PATCH_LIST_UPDATED ||
                                msg.what == AUDIOPORT_EVENT_SERVICE_DIED) {
                            AudioManager.resetAudioPortGeneration();
                        }
                        ArrayList<AudioPort> ports = new ArrayList<AudioPort>();
                        ArrayList<AudioPatch> patches = new ArrayList<AudioPatch>();
                        if (msg.what != AUDIOPORT_EVENT_SERVICE_DIED) {
                            int status = AudioManager.updateAudioPortCache(ports, patches);
                            if (status != AudioManager.SUCCESS) {
                                return;
                            }
                        }

                        switch (msg.what) {
                        case AUDIOPORT_EVENT_NEW_LISTENER:
                        case AUDIOPORT_EVENT_PORT_LIST_UPDATED:
                            AudioPort[] portList = ports.toArray(new AudioPort[0]);
                            for (int i = 0; i < listeners.size(); i++) {
                                listeners.get(i).onAudioPortListUpdate(portList);
                            }
                            if (msg.what == AUDIOPORT_EVENT_PORT_LIST_UPDATED) {
                                break;
                            }
                            // FALL THROUGH

                        case AUDIOPORT_EVENT_PATCH_LIST_UPDATED:
                            AudioPatch[] patchList = patches.toArray(new AudioPatch[0]);
                            for (int i = 0; i < listeners.size(); i++) {
                                listeners.get(i).onAudioPatchListUpdate(patchList);
                            }
                            break;

                        case AUDIOPORT_EVENT_SERVICE_DIED:
                            for (int i = 0; i < listeners.size(); i++) {
                                listeners.get(i).onServiceDied();
                            }
                            break;

                        default:
                            break;
                        }
                    }
                };
                native_setup(new WeakReference<AudioPortEventHandler>(this));
            } else {
                mHandler = null;
            }
        }
    }

    private native void native_setup(Object module_this);

    @Override
    protected void finalize() {
        native_finalize();
    }
    private native void native_finalize();

    void registerListener(AudioManager.OnAudioPortUpdateListener l) {
        synchronized (this) {
            mListeners.add(l);
        }
        if (mHandler != null) {
            Message m = mHandler.obtainMessage(AUDIOPORT_EVENT_NEW_LISTENER, 0, 0, l);
            mHandler.sendMessage(m);
        }
    }

    void unregisterListener(AudioManager.OnAudioPortUpdateListener l) {
        synchronized (this) {
            mListeners.remove(l);
        }
    }

    Handler handler() {
        return mHandler;
    }

    @SuppressWarnings("unused")
    private static void postEventFromNative(Object module_ref,
                                            int what, int arg1, int arg2, Object obj) {
        AudioPortEventHandler eventHandler =
                (AudioPortEventHandler)((WeakReference)module_ref).get();
        if (eventHandler == null) {
            return;
        }

        if (eventHandler != null) {
            Handler handler = eventHandler.handler();
            if (handler != null) {
                Message m = handler.obtainMessage(what, arg1, arg2, obj);
                handler.sendMessage(m);
            }
        }
    }

}
