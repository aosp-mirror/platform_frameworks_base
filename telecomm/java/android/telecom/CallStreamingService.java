/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.telecom;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;

import androidx.annotation.Nullable;

import com.android.internal.telecom.ICallStreamingService;
import com.android.internal.telecom.IStreamingCallAdapter;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * This service is implemented by an app that wishes to provide functionality for a general call
 * streaming sender for voip calls.
 * <p>
 * Below is an example manifest registration for a {@code CallStreamingService}.
 * <pre>
 * {@code
 * <service android:name=".EgCallStreamingService"
 *     android:permission="android.permission.BIND_CALL_STREAMING_SERVICE" >
 *     ...
 *     <intent-filter>
 *         <action android:name="android.telecom.CallStreamingService" />
 *     </intent-filter>
 * </service>
 * }
 * </pre>
 *
 * @hide
 */
@SystemApi
public abstract class CallStreamingService extends Service {
    /**
     * The {@link android.content.Intent} that must be declared as handled by the service.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE = "android.telecom.CallStreamingService";

    private static final int MSG_SET_STREAMING_CALL_ADAPTER = 1;
    private static final int MSG_CALL_STREAMING_STARTED = 2;
    private static final int MSG_CALL_STREAMING_STOPPED = 3;
    private static final int MSG_CALL_STREAMING_STATE_CHANGED = 4;

    /** Default Handler used to consolidate binder method calls onto a single thread. */
    private final Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            if (mStreamingCallAdapter == null && msg.what != MSG_SET_STREAMING_CALL_ADAPTER) {
                Log.i(this, "handleMessage: null adapter!");
                return;
            }

            switch (msg.what) {
                case MSG_SET_STREAMING_CALL_ADAPTER:
                    if (msg.obj != null) {
                        Log.i(this, "MSG_SET_STREAMING_CALL_ADAPTER");
                        mStreamingCallAdapter = new StreamingCallAdapter(
                                (IStreamingCallAdapter) msg.obj);
                    }
                    break;
                case MSG_CALL_STREAMING_STARTED:
                    Log.i(this, "MSG_CALL_STREAMING_STARTED");
                    mCall = (StreamingCall) msg.obj;
                    mCall.setAdapter(mStreamingCallAdapter);
                    CallStreamingService.this.onCallStreamingStarted(mCall);
                    break;
                case MSG_CALL_STREAMING_STOPPED:
                    Log.i(this, "MSG_CALL_STREAMING_STOPPED");
                    mCall = null;
                    mStreamingCallAdapter = null;
                    CallStreamingService.this.onCallStreamingStopped();
                    break;
                case MSG_CALL_STREAMING_STATE_CHANGED:
                    int state = (int) msg.obj;
                    if (mStreamingCallAdapter != null) {
                        mCall.requestStreamingState(state);
                        CallStreamingService.this.onCallStreamingStateChanged(state);
                    }
                    break;
                default:
                    break;
            }
        }
    };

    @Nullable
    @Override
    public IBinder onBind(@NonNull Intent intent) {
        Log.i(this, "onBind");
        return new CallStreamingServiceBinder();
    }

    /** Manages the binder calls so that the implementor does not need to deal with it. */
    private final class CallStreamingServiceBinder extends ICallStreamingService.Stub {
        @Override
        public void setStreamingCallAdapter(IStreamingCallAdapter streamingCallAdapter)
                throws RemoteException {
            Log.i(this, "setCallStreamingAdapter");
            mHandler.obtainMessage(MSG_SET_STREAMING_CALL_ADAPTER, streamingCallAdapter)
                    .sendToTarget();
        }

        @Override
        public void onCallStreamingStarted(StreamingCall call) throws RemoteException {
            Log.i(this, "onCallStreamingStarted");
            mHandler.obtainMessage(MSG_CALL_STREAMING_STARTED, call).sendToTarget();
        }

        @Override
        public void onCallStreamingStopped() throws RemoteException {
            mHandler.obtainMessage(MSG_CALL_STREAMING_STOPPED).sendToTarget();
        }

        @Override
        public void onCallStreamingStateChanged(int state) throws RemoteException {
            mHandler.obtainMessage(MSG_CALL_STREAMING_STATE_CHANGED, state).sendToTarget();
        }
    }

    /**
     * Call streaming request reject reason used with
     * {@link CallEventCallback#onCallStreamingFailed(int)} to indicate that telecom is rejecting a
     * call streaming request due to unknown reason.
     */
    public static final int STREAMING_FAILED_UNKNOWN = 0;

    /**
     * Call streaming request reject reason used with
     * {@link CallEventCallback#onCallStreamingFailed(int)} to indicate that telecom is rejecting a
     * call streaming request because there's an ongoing streaming call on this device.
     */
    public static final int STREAMING_FAILED_ALREADY_STREAMING = 1;

    /**
     * Call streaming request reject reason used with
     * {@link CallEventCallback#onCallStreamingFailed(int)} to indicate that telecom is rejecting a
     * call streaming request because telecom can't find existing general streaming sender on this
     * device.
     */
    public static final int STREAMING_FAILED_NO_SENDER = 2;

    /**
     * Call streaming request reject reason used with
     * {@link CallEventCallback#onCallStreamingFailed(int)} to indicate that telecom is rejecting a
     * call streaming request because telecom can't bind to the general streaming sender app.
     */
    public static final int STREAMING_FAILED_SENDER_BINDING_ERROR = 3;

    private StreamingCallAdapter mStreamingCallAdapter;
    private StreamingCall mCall;

    /**
     * @hide
     */
    @IntDef(prefix = {"STREAMING_FAILED"},
            value = {
                    STREAMING_FAILED_UNKNOWN,
                    STREAMING_FAILED_ALREADY_STREAMING,
                    STREAMING_FAILED_NO_SENDER,
                    STREAMING_FAILED_SENDER_BINDING_ERROR
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface StreamingFailedReason {
    }

    ;

    /**
     * Called when a {@code StreamingCall} has been added to this call streaming session. The call
     * streaming sender should start to intercept the device audio using audio records and audio
     * tracks from Audio frameworks.
     *
     * @param call a newly added {@code StreamingCall}.
     */
    public void onCallStreamingStarted(@NonNull StreamingCall call) {
    }

    /**
     * Called when a current {@code StreamingCall} has been removed from this call streaming
     * session. The call streaming sender should notify the streaming receiver that the call is
     * stopped streaming and stop the device audio interception.
     */
    public void onCallStreamingStopped() {
    }

    /**
     * Called when the streaming state of current {@code StreamingCall} changed. General streaming
     * sender usually get notified of the holding/unholding from the original owner voip app of the
     * call.
     */
    public void onCallStreamingStateChanged(@StreamingCall.StreamingCallState int state) {
    }
}
