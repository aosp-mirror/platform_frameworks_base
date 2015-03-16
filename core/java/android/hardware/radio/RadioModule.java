/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.hardware.radio;

import android.annotation.SystemApi;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import java.lang.ref.WeakReference;
import java.util.UUID;

/**
 * A RadioModule implements the RadioTuner interface for a broadcast radio tuner physically
 * present on the device and exposed by the radio HAL.
 *
 * @hide
 */
public class RadioModule extends RadioTuner {
    private long mNativeContext = 0;
    private int mId;
    private NativeEventHandlerDelegate mEventHandlerDelegate;

    RadioModule(int moduleId, RadioManager.BandConfig config, boolean withAudio,
            RadioTuner.Callback callback, Handler handler) {
        mId = moduleId;
        mEventHandlerDelegate = new NativeEventHandlerDelegate(callback, handler);
        native_setup(new WeakReference<RadioModule>(this), config, withAudio);
    }
    private native void native_setup(Object module_this,
            RadioManager.BandConfig config, boolean withAudio);

    @Override
    protected void finalize() {
        native_finalize();
    }
    private native void native_finalize();

    boolean initCheck() {
        return mNativeContext != 0;
    }

    // RadioTuner implementation
    public native void close();

    public native int setConfiguration(RadioManager.BandConfig config);

    public native int getConfiguration(RadioManager.BandConfig[] config);

    public native int setMute(boolean mute);

    public native boolean getMute();

    public native int step(int direction, boolean skipSubChannel);

    public native int scan(int direction, boolean skipSubChannel);

    public native int tune(int channel, int subChannel);

    public native int cancel();

    public native int getProgramInformation(RadioManager.ProgramInfo[] info);

    public native boolean isAntennaConnected();

    public native boolean hasControl();


    /* keep in sync with radio_event_type_t in system/core/include/system/radio.h */
    static final int EVENT_HW_FAILURE = 0;
    static final int EVENT_CONFIG = 1;
    static final int EVENT_ANTENNA = 2;
    static final int EVENT_TUNED = 3;
    static final int EVENT_METADATA = 4;
    static final int EVENT_TA = 5;
    static final int EVENT_AF_SWITCH = 6;
    static final int EVENT_CONTROL = 100;
    static final int EVENT_SERVER_DIED = 101;

    private class NativeEventHandlerDelegate {
        private final Handler mHandler;

        NativeEventHandlerDelegate(final RadioTuner.Callback callback,
                                   Handler handler) {
            // find the looper for our new event handler
            Looper looper;
            if (handler != null) {
                looper = handler.getLooper();
            } else {
                looper = Looper.getMainLooper();
            }

            // construct the event handler with this looper
            if (looper != null) {
                // implement the event handler delegate
                mHandler = new Handler(looper) {
                    @Override
                    public void handleMessage(Message msg) {
                        switch (msg.what) {
                        case EVENT_HW_FAILURE:
                            if (callback != null) {
                                callback.onError(RadioTuner.ERROR_HARDWARE_FAILURE);
                            }
                            break;
                        case EVENT_CONFIG: {
                            RadioManager.BandConfig config = (RadioManager.BandConfig)msg.obj;
                            switch(msg.arg1) {
                            case RadioManager.STATUS_OK:
                                if (callback != null) {
                                    callback.onConfigurationChanged(config);
                                }
                                break;
                            default:
                                if (callback != null) {
                                    callback.onError(RadioTuner.ERROR_CONFIG);
                                }
                                break;
                            }
                        } break;
                        case EVENT_ANTENNA:
                            if (callback != null) {
                                callback.onAntennaState(msg.arg2 == 1);
                            }
                            break;
                        case EVENT_AF_SWITCH:
                        case EVENT_TUNED: {
                            RadioManager.ProgramInfo info = (RadioManager.ProgramInfo)msg.obj;
                            switch (msg.arg1) {
                            case RadioManager.STATUS_OK:
                                if (callback != null) {
                                    callback.onProgramInfoChanged(info);
                                }
                                break;
                            case RadioManager.STATUS_TIMED_OUT:
                                if (callback != null) {
                                    callback.onError(RadioTuner.ERROR_SCAN_TIMEOUT);
                                }
                                break;
                            case RadioManager.STATUS_INVALID_OPERATION:
                            default:
                                if (callback != null) {
                                    callback.onError(RadioTuner.ERROR_CANCELLED);
                                }
                                break;
                            }
                        } break;
                        case EVENT_METADATA: {
                            RadioMetadata metadata = (RadioMetadata)msg.obj;
                            if (callback != null) {
                                callback.onMetadataChanged(metadata);
                            }
                        } break;
                        case EVENT_TA:
                            if (callback != null) {
                                callback.onTrafficAnnouncement(msg.arg2 == 1);
                            }
                            break;
                        case EVENT_CONTROL:
                            if (callback != null) {
                                callback.onControlChanged(msg.arg2 == 1);
                            }
                            break;
                        case EVENT_SERVER_DIED:
                            if (callback != null) {
                                callback.onError(RadioTuner.ERROR_SERVER_DIED);
                            }
                            break;
                        default:
                            // Should not happen
                            break;
                        }
                    }
                };
            } else {
                mHandler = null;
            }
        }

        Handler handler() {
            return mHandler;
        }
    }


    @SuppressWarnings("unused")
    private static void postEventFromNative(Object module_ref,
                                            int what, int arg1, int arg2, Object obj) {
        RadioModule module = (RadioModule)((WeakReference)module_ref).get();
        if (module == null) {
            return;
        }

        NativeEventHandlerDelegate delegate = module.mEventHandlerDelegate;
        if (delegate != null) {
            Handler handler = delegate.handler();
            if (handler != null) {
                Message m = handler.obtainMessage(what, arg1, arg2, obj);
                handler.sendMessage(m);
            }
        }
    }
}

