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
import android.app.Service;
import android.app.assist.AssistStructure;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import com.android.internal.os.HandlerCaller;
import com.android.internal.os.SomeArgs;

/**
 * Top-level service of the current auto-fill service for a given user.
 */
// TODO: expand documentation
public abstract class AutoFillService extends Service {

    private static final String TAG = "AutoFillService";
    private static final boolean DEBUG = true; // TODO: set to false once stable

    /**
     * The {@link Intent} that must be declared as handled by the service.
     * To be supported, the service must also require the
     * {@link android.Manifest.permission#BIND_AUTO_FILL} permission so
     * that other applications can not abuse it.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE = "android.service.autofill.AutoFillService";

    private static final int MSG_READY = 1;
    private static final int MSG_NEW_SESSION = 2;
    private static final int MSG_SESSION_FINISHED = 3;
    private static final int MSG_SHUTDOWN = 4;

    // TODO: add metadata?

    private final IAutoFillService mInterface = new IAutoFillService.Stub() {
        @Override
        public void ready() {
            mHandlerCaller.sendMessage(mHandlerCaller.obtainMessage(MSG_READY));
        }

        @Override
        public void newSession(String token, Bundle data, int flags,
                AssistStructure structure) {
            mHandlerCaller.sendMessage(mHandlerCaller.obtainMessageIOOO(MSG_NEW_SESSION,
                    flags, token, data, structure));
        }

        @Override
        public void finishSession(String token) {
            mHandlerCaller.sendMessage(mHandlerCaller.obtainMessageO(MSG_SESSION_FINISHED, token));
        }

        @Override
        public void shutdown() {
            mHandlerCaller.sendMessage(mHandlerCaller.obtainMessage(MSG_SHUTDOWN));
        }
    };

    private final HandlerCaller.Callback mHandlerCallback = new HandlerCaller.Callback() {

        @Override
        public void executeMessage(Message msg) {
            switch (msg.what) {
                case MSG_READY: {
                    onReady();
                    break;
                } case MSG_NEW_SESSION: {
                    final SomeArgs args = (SomeArgs) msg.obj;
                    final int flags = args.argi1;
                    final String token = (String) args.arg1;
                    final Bundle data = (Bundle) args.arg2;
                    final AssistStructure assistStructure = (AssistStructure) args.arg3;
                    onNewSession(token, data, flags, assistStructure);
                    break;
                } case MSG_SESSION_FINISHED: {
                    final String token = (String) msg.obj;
                    onSessionFinished(token);
                    break;
                } case MSG_SHUTDOWN: {
                    onShutdown();
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
        return null;
    }

    /**
     * Called during service initialization to tell you when the system is ready
     * to receive interaction from it.
     *
     * <p>You should generally do initialization here rather than in {@link #onCreate}.
     *
     * <p>Sub-classes should call it first, since it sets the reference to the sytem-server service.
     */
    // TODO: rename to onConnected() / add onDisconnected()?
    public void onReady() {
        if (DEBUG) Log.d(TAG, "onReady()");
    }

    /**
     * Called to receive data from the application that the user was requested auto-fill for.
     *
     * @param token unique token identifying the auto-fill session, it should be used when providing
     * the auto-filled fields.
     * @param data Arbitrary data supplied by the app through
     * {@link android.app.Activity#onProvideAssistData Activity.onProvideAssistData}.
     * May be {@code null} if data has been disabled by the user or device policy.
     * @param startFlags currently always 0.
     * @param structure If available, the structure definition of all windows currently
     * displayed by the app.  May be {@code null} if auto-fill data has been disabled by the user
     * or device policy; will be an empty stub if the application has disabled auto-fill
     * by marking its window as secure.
     */
    @SuppressWarnings("unused")
    // TODO: take the factory approach where this method return a session, and move the callback
    // methods (like autofill()) to the session.
    public void onNewSession(String token, Bundle data, int startFlags, AssistStructure structure) {
        if (DEBUG) Log.d(TAG, "onNewSession(): token=" + token);
    }

    /**
     * Called when an auto-fill session is finished.
     */
    @SuppressWarnings("unused")
    public void onSessionFinished(String token) {
        if (DEBUG) Log.d(TAG, "onSessionFinished(): token=" + token);
    }

    /**
     * Called during service de-initialization to tell you when the system is shutting the
     * service down.
     *
     * <p> At this point this service may no longer be an active {@link AutoFillService}.
     */
    public void onShutdown() {
        if (DEBUG) Log.d(TAG, "onShutdown()");
    }
}
