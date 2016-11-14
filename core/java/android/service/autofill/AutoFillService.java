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
import android.service.voice.VoiceInteractionSession;
import android.util.Log;

import com.android.internal.os.HandlerCaller;
import com.android.internal.os.IResultReceiver;
import com.android.internal.os.SomeArgs;

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

    private static final int MSG_CONNECT = 1;
    private static final int MSG_AUTO_FILL_ACTIVITY = 2;
    private static final int MSG_DISCONNECT = 3;

    private final IResultReceiver mAssistReceiver = new IResultReceiver.Stub() {
        @Override
        public void send(int resultCode, Bundle resultData) throws RemoteException {
            final AssistStructure structure = resultData
                    .getParcelable(VoiceInteractionSession.KEY_STRUCTURE);

            final IBinder binder = resultData
                    .getBinder(VoiceInteractionSession.KEY_AUTO_FILL_CALLBACK);

            mHandlerCaller
                .obtainMessageOO(MSG_AUTO_FILL_ACTIVITY, structure, binder).sendToTarget();
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
                    requestAutoFill(structure, binder);
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
     * Handles an auto-fill request.
     *
     * @param structure {@link Activity}'s view structure .
     * @param cancellationSignal signal for observing cancel requests.
     * @param callback object used to fulllfill the request.
     */
    public abstract void onFillRequest(AssistStructure structure,
            CancellationSignal cancellationSignal, FillCallback callback);

    private void requestAutoFill(AssistStructure structure, IBinder binder) {
        final FillCallback callback = new FillCallback(binder);
        // TODO: hook up the cancelationSignal
        onFillRequest(structure, new CancellationSignal(), callback);
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
