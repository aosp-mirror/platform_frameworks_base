/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.telecomm;

import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;

import com.android.internal.os.SomeArgs;
import com.android.internal.telecomm.IInCallAdapter;
import com.android.internal.telecomm.IInCallService;

/**
 * This service is implemented by any app that wishes to provide the user-interface for managing
 * phone calls. Telecomm binds to this service while there exists a live (active or incoming)
 * call, and uses it to notify the in-call app of any live and and recently disconnected calls.
 *
 * TODO(santoscordon): What happens if two or more apps on a given device implement this interface?
 */
public abstract class InCallService {
    private static final int MSG_SET_IN_CALL_ADAPTER = 1;
    private static final int MSG_ADD_CALL = 2;
    private static final int MSG_UPDATE_CALL = 3;
    private static final int MSG_SET_POST_DIAL = 4;
    private static final int MSG_SET_POST_DIAL_WAIT = 5;
    private static final int MSG_ON_AUDIO_STATE_CHANGED = 6;
    private static final int MSG_BRING_TO_FOREGROUND = 7;

    /** Default Handler used to consolidate binder method calls onto a single thread. */
    private final Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_SET_IN_CALL_ADAPTER:
                    mPhone = new Phone(new InCallAdapter((IInCallAdapter) msg.obj));
                    onPhoneCreated(mPhone);
                    break;
                case MSG_ADD_CALL:
                    mPhone.internalAddCall((InCallCall) msg.obj);
                    break;
                case MSG_UPDATE_CALL:
                    mPhone.internalUpdateCall((InCallCall) msg.obj);
                    break;
                case MSG_SET_POST_DIAL: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        String callId = (String) args.arg1;
                        String remaining = (String) args.arg2;
                        mPhone.internalSetPostDial(callId, remaining);
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_SET_POST_DIAL_WAIT: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        String callId = (String) args.arg1;
                        String remaining = (String) args.arg2;
                        mPhone.internalSetPostDialWait(callId, remaining);
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_ON_AUDIO_STATE_CHANGED:
                    mPhone.internalAudioStateChanged((CallAudioState) msg.obj);
                    break;
                case MSG_BRING_TO_FOREGROUND:
                    mPhone.internalBringToForeground(msg.arg1 == 1);
                    break;
                default:
                    break;
            }
        }
    };

    /** Manages the binder calls so that the implementor does not need to deal with it. */
    private final class InCallServiceBinder extends IInCallService.Stub {
        /** {@inheritDoc} */
        @Override
        public void setInCallAdapter(IInCallAdapter inCallAdapter) {
            mHandler.obtainMessage(MSG_SET_IN_CALL_ADAPTER, inCallAdapter).sendToTarget();
        }

        /** {@inheritDoc} */
        @Override
        public void addCall(InCallCall call) {
            mHandler.obtainMessage(MSG_ADD_CALL, call).sendToTarget();
        }

        /** {@inheritDoc} */
        @Override
        public void updateCall(InCallCall call) {
            mHandler.obtainMessage(MSG_UPDATE_CALL, call).sendToTarget();
        }

        @Override
        public void setPostDial(String callId, String remaining) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = callId;
            args.arg2 = remaining;
            mHandler.obtainMessage(MSG_SET_POST_DIAL, args).sendToTarget();
        }

        @Override
        public void setPostDialWait(String callId, String remaining) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = callId;
            args.arg2 = remaining;
            mHandler.obtainMessage(MSG_SET_POST_DIAL_WAIT, args).sendToTarget();
        }

        /** {@inheritDoc} */
        @Override
        public void onAudioStateChanged(CallAudioState audioState) {
            mHandler.obtainMessage(MSG_ON_AUDIO_STATE_CHANGED, audioState).sendToTarget();
        }

        /** {@inheritDoc} */
        @Override
        public void bringToForeground(boolean showDialpad) {
            mHandler.obtainMessage(MSG_BRING_TO_FOREGROUND, showDialpad ? 1 : 0, 0).sendToTarget();
        }
    }

    private Phone mPhone;

    protected InCallService() {}

    public final IBinder getBinder() {
        return new InCallServiceBinder();
    }

    /**
     * Obtain the {@code Phone} associated with this {@code InCallService}.
     *
     * @return The {@code Phone} object associated with this {@code InCallService}, or {@code null}
     * if the {@code InCallService} is not in a state where it has an associated {@code Phone}.
     */
    public Phone getPhone() {
        return mPhone;
    }

    /**
     * Invoked when the {@code Phone} has been created. This is a signal to the in-call experience
     * to start displaying in-call information to the user. Each instance of {@code InCallService}
     * will have only one {@code Phone}, and this method will be called exactly once in the
     * lifetime of the {@code InCallService}.
     *
     * @param phone The {@code Phone} object associated with this {@code InCallService}.
     */
    public void onPhoneCreated(Phone phone) { }

    /**
     * Invoked when a {@code Phone} has been destroyed. This is a signal to the in-call experience
     * to stop displaying in-call information to the user. This method will be called exactly once
     * in the lifetime of the {@code InCallService}, and it will always be called after a previous
     * call to {@link #onPhoneCreated(Phone)}.
     *
     * @param phone The {@code Phone} object associated with this {@code InCallService}.
     */
    public void onPhoneDestroyed(Phone phone) { }
}
