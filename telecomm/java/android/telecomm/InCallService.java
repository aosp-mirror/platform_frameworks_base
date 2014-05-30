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

import android.app.Service;
import android.content.Intent;
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
 * TODO(santoscordon): Needs more/better description of lifecycle once the interface is better
 * defined.
 * TODO(santoscordon): What happens if two or more apps on a given device implement this interface?
 */
public abstract class InCallService extends Service {
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
                    mAdapter = new InCallAdapter((IInCallAdapter) msg.obj);
                    onAdapterAttached(mAdapter);
                    break;
                case MSG_ADD_CALL:
                    addCall((InCallCall) msg.obj);
                    break;
                case MSG_UPDATE_CALL:
                    updateCall((InCallCall) msg.obj);
                    break;
                case MSG_SET_POST_DIAL: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    try {
                        String callId = (String) args.arg1;
                        String remaining = (String) args.arg2;
                        setPostDial(callId, remaining);
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
                        setPostDialWait(callId, remaining);
                    } finally {
                        args.recycle();
                    }
                    break;
                }
                case MSG_ON_AUDIO_STATE_CHANGED:
                    onAudioStateChanged((CallAudioState) msg.obj);
                    break;
                case MSG_BRING_TO_FOREGROUND:
                    bringToForeground(msg.arg1 == 1);
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

    private final InCallServiceBinder mBinder;

    private InCallAdapter mAdapter;

    protected InCallService() {
        mBinder = new InCallServiceBinder();
    }

    @Override
    public final IBinder onBind(Intent intent) {
        return mBinder;
    }

    /**
     * @return The attached {@link CallServiceSelectorAdapter} if attached, or null otherwise.
     */
    protected final InCallAdapter getAdapter() {
        return mAdapter;
    }

    /**
     * Lifecycle callback which is called when this {@link InCallService} has been attached
     * to a {@link InCallAdapter}, indicating {@link #getAdapter()} is now safe to use.
     *
     * @param adapter The adapter now attached to this in-call service.
     */
    protected void onAdapterAttached(InCallAdapter adapter) {
    }

    /**
     * Indicates to the in-call app that a new call has been created and an appropriate
     * user-interface should be built and shown to notify the user.
     *
     * @param call Information about the new call.
     */
     protected abstract void addCall(InCallCall call);

    /**
     * Call when information about a call has changed.
     *
     * @param call Information about the new call.
     */
     protected abstract void updateCall(InCallCall call);

    /**
     * Indicates to the in-call app that the specified call is active but in a "post-dial" state
     * where Telecomm is now sending some dual-tone multi-frequency signaling (DTMF) tones appended
     * to the dialed number. Normal transitions are to {@link #setPostDialWait(String,String)} when
     * the post-dial string requires user confirmation to proceed, and {@link CallState#ACTIVE} when
     * the post-dial tones are completed.
     *
     * @param callId The identifier of the call changing state.
     * @param remaining The remaining postdial string to be dialed.
     */
    protected abstract void setPostDial(String callId, String remaining);

    /**
     * Indicates to the in-call app that the specified call was in the
     * {@link #setPostDial(String,String)} state but is now waiting for user confirmation before the
     * remaining digits can be sent. Normal transitions are to {@link #setPostDial(String,String)}
     * when the user asks Telecomm to proceed with the post-dial sequence and the in-call app
     * informs Telecomm of this by invoking {@link InCallAdapter#postDialContinue(String)}.
     *
     * @param callId The identifier of the call changing state.
     * @param remaining The remaining postdial string to be dialed.
     */
    protected abstract void setPostDialWait(String callId, String remaining);

    /**
     * Called when the audio state changes.
     *
     * @param audioState The new {@link CallAudioState}.
     */
    protected abstract void onAudioStateChanged(CallAudioState audioState);

    /**
     * Brings the in-call screen to the foreground.
     *
     * @param showDialpad If true, put up the dialpad when the screen is shown.
     */
    protected abstract void bringToForeground(boolean showDialpad);
}
