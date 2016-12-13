/*
 * Copyright (C) 2016 The Android Open Source Project
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
package android.service.autofill;

import static android.service.voice.VoiceInteractionSession.KEY_FLAGS;
import static android.service.voice.VoiceInteractionSession.KEY_STRUCTURE;
import static android.view.View.ASSIST_FLAG_SANITIZED_TEXT;
import static android.view.View.ASSIST_FLAG_NON_SANITIZED_TEXT;

import android.annotation.SdkConstant;
import android.app.Activity;
import android.app.Service;
import android.app.assist.AssistStructure;
import android.content.Intent;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.os.HandlerCaller;
import com.android.internal.os.IResultReceiver;
import com.android.internal.os.SomeArgs;

// TODO(b/33197203): improve javadoc (class and methods)

/**
 * Top-level service of the current auto-fill service for a given user.
 *
 * <p>Apps providing auto-fill capabilities must extend this service.
 */
public abstract class AutoFillService extends Service {

    static final String TAG = "AutoFillService";
    static final boolean DEBUG = true; // TODO: set to false once stable

    /**
     * The {@link Intent} that must be declared as handled by the service.
     * To be supported, the service must also require the
     * {@link android.Manifest.permission#BIND_AUTO_FILL} permission so
     * that other applications can not abuse it.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE = "android.service.autofill.AutoFillService";

    // Bundle keys.
    /** @hide */
    public static final String KEY_CALLBACK = "callback";

    // Handler messages.
    private static final int MSG_CONNECT = 1;
    private static final int MSG_AUTO_FILL_ACTIVITY = 2;
    private static final int MSG_DISCONNECT = 3;

    private final IResultReceiver mAssistReceiver = new IResultReceiver.Stub() {
        @Override
        public void send(int resultCode, Bundle resultData) throws RemoteException {
            final AssistStructure structure = resultData.getParcelable(KEY_STRUCTURE);
            final IBinder binder = resultData.getBinder(KEY_CALLBACK);
            final int flags = resultData.getInt(KEY_FLAGS, 0);

            mHandlerCaller
                .obtainMessageIOO(MSG_AUTO_FILL_ACTIVITY, flags, structure, binder).sendToTarget();
        }

    };

    private final IAutoFillService mInterface = new IAutoFillService.Stub() {
        @Override
        public void onConnected() {
            mHandlerCaller.sendMessage(mHandlerCaller.obtainMessage(MSG_CONNECT));
        }

        @Override
        public IResultReceiver getAssistReceiver() {
            return mAssistReceiver;
        }

        @Override
        public void onDisconnected() {
            mHandlerCaller.sendMessage(mHandlerCaller.obtainMessage(MSG_DISCONNECT));
        }
    };

    private final HandlerCaller.Callback mHandlerCallback = new HandlerCaller.Callback() {

        @Override
        public void executeMessage(Message msg) {
            switch (msg.what) {
                case MSG_CONNECT: {
                    onConnected();
                    break;
                } case MSG_AUTO_FILL_ACTIVITY: {
                    final SomeArgs args = (SomeArgs) msg.obj;
                    final AssistStructure structure = (AssistStructure) args.arg1;
                    final IBinder binder = (IBinder) args.arg2;
                    final int flags = msg.arg1;
                    requestAutoFill(structure, flags, binder);
                    break;
                } case MSG_DISCONNECT: {
                    onDisconnected();
                    break;
                } default: {
                    Log.w(TAG, "MyCallbacks received invalid message type: " + msg);
                }
            }
        }
    };

    private HandlerCaller mHandlerCaller;

    /**
     * {@inheritDoc}
     *
     * <strong>NOTE: </strong>if overridden, it must call {@code super.onCreate()}.
     */
    @Override
    public void onCreate() {
        super.onCreate();

        mHandlerCaller = new HandlerCaller(null, Looper.getMainLooper(), mHandlerCallback, true);
    }

    @Override
    public final IBinder onBind(Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return mInterface.asBinder();
        }
        Log.w(TAG, "Tried to bind to wrong intent: " + intent);
        return null;
    }

    /**
     * Called when the Android System connects to service.
     *
     * <p>You should generally do initialization here rather than in {@link #onCreate}.
     */
    public void onConnected() {
        if (DEBUG) Log.d(TAG, "onConnected()");
    }

    /**
     * Called when user requests service to auto-fill an {@link Activity}.
     *
     * @param structure {@link Activity}'s view structure .
     * @param data bundle with optional parameters (currently none) which is passed along on
     * subsequent calls (so it can be used by the service to share data).
     * @param cancellationSignal signal for observing cancel requests.
     */
    public abstract void onFillRequest(AssistStructure structure,
            Bundle data, CancellationSignal cancellationSignal, FillCallback callback);

    /**
     * Called when user requests service to save the fields of an {@link Activity}.
     *
     * @param structure {@link Activity}'s view structure.
     * @param data same bundle passed to
     * {@link #onFillRequest(AssistStructure, Bundle, CancellationSignal, FillCallback)};
     * might also contain with optional parameters (currently none).
     * @param cancellationSignal signal for observing cancel requests.
     * @param callback object used to notify the result of the request.
     */
    public abstract void onSaveRequest(AssistStructure structure,
            Bundle data, CancellationSignal cancellationSignal, SaveCallback callback);

    private void requestAutoFill(AssistStructure structure, int flags, IBinder binder) {
        // TODO(b/33197203): pass the Bundle received from mAssistReceiver instead?
        final Bundle data = new Bundle();
        switch (flags) {
            case ASSIST_FLAG_SANITIZED_TEXT:
                final FillCallback fillCallback = new FillCallback(binder);
                // TODO(b/33197203): hook up the cancelationSignal
                onFillRequest(structure, data, new CancellationSignal(), fillCallback);
                break;
            case ASSIST_FLAG_NON_SANITIZED_TEXT:
                final SaveCallback saveCallback = new SaveCallback(binder);
                // TODO(b/33197203): hook up the cancelationSignal
                onSaveRequest(structure, null, new CancellationSignal(), saveCallback);
                break;
            default:
                Log.w(TAG, "invalid flag on requestAutoFill(): " + flags);
        }
    }

    /**
     * Called when the Android System disconnects from the service.
     *
     * <p> At this point this service may no longer be an active {@link AutoFillService}.
     */
    public void onDisconnected() {
        if (DEBUG) Log.d(TAG, "onDisconnected()");
    }
}
