/**
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

package android.service.voice;

import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;
import com.android.internal.app.IVoiceInteractor;
import com.android.internal.app.IVoiceInteractorCallback;
import com.android.internal.app.IVoiceInteractorRequest;
import com.android.internal.os.HandlerCaller;
import com.android.internal.os.SomeArgs;

public abstract class VoiceInteractionSession {
    static final String TAG = "VoiceInteractionSession";
    static final boolean DEBUG = true;

    final IVoiceInteractor mInteractor = new IVoiceInteractor.Stub() {
        @Override
        public IVoiceInteractorRequest startConfirmation(String callingPackage,
                IVoiceInteractorCallback callback, String prompt, Bundle extras) {
            Request request = findRequest(callback, true);
            mHandlerCaller.sendMessage(mHandlerCaller.obtainMessageOOOO(MSG_START_CONFIRMATION,
                    new Caller(callingPackage, Binder.getCallingUid()), request,
                    prompt, extras));
            return request.mInterface;
        }

        @Override
        public IVoiceInteractorRequest startCommand(String callingPackage,
                IVoiceInteractorCallback callback, String command, Bundle extras) {
            Request request = findRequest(callback, true);
            mHandlerCaller.sendMessage(mHandlerCaller.obtainMessageOOOO(MSG_START_COMMAND,
                    new Caller(callingPackage, Binder.getCallingUid()), request,
                    command, extras));
            return request.mInterface;
        }

        @Override
        public boolean[] supportsCommands(String callingPackage, String[] commands) {
            Message msg = mHandlerCaller.obtainMessageIOO(MSG_SUPPORTS_COMMANDS,
                    0, new Caller(callingPackage, Binder.getCallingUid()), commands);
            SomeArgs args = mHandlerCaller.sendMessageAndWait(msg);
            if (args != null) {
                boolean[] res = (boolean[])args.arg1;
                args.recycle();
                return res;
            }
            return new boolean[commands.length];
        }
    };

    final IVoiceInteractionSession mSession = new IVoiceInteractionSession.Stub() {
    };

    public static class Request {
        final IVoiceInteractorRequest mInterface = new IVoiceInteractorRequest.Stub() {
            @Override
            public void cancel() throws RemoteException {
                mHandlerCaller.sendMessage(mHandlerCaller.obtainMessageO(MSG_CANCEL, Request.this));
            }
        };
        final IVoiceInteractorCallback mCallback;
        final HandlerCaller mHandlerCaller;
        Request(IVoiceInteractorCallback callback, HandlerCaller handlerCaller) {
            mCallback = callback;
            mHandlerCaller = handlerCaller;
        }

        public void sendConfirmResult(boolean confirmed, Bundle result) {
            try {
                if (DEBUG) Log.d(TAG, "sendConfirmResult: req=" + mInterface
                        + " confirmed=" + confirmed + " result=" + result);
                mCallback.deliverConfirmationResult(mInterface, confirmed, result);
            } catch (RemoteException e) {
            }
        }

        public void sendCommandResult(Bundle result) {
            try {
                if (DEBUG) Log.d(TAG, "sendCommandResult: req=" + mInterface
                        + " result=" + result);
                mCallback.deliverCommandResult(mInterface, result);
            } catch (RemoteException e) {
            }
        }

        public void sendCancelResult() {
            try {
                if (DEBUG) Log.d(TAG, "sendCancelResult: req=" + mInterface);
                mCallback.deliverCancel(mInterface);
            } catch (RemoteException e) {
            }
        }
    }

    public static class Caller {
        final String packageName;
        final int uid;

        Caller(String _packageName, int _uid) {
            packageName = _packageName;
            uid = _uid;
        }
    }

    static final int MSG_START_CONFIRMATION = 1;
    static final int MSG_START_COMMAND = 2;
    static final int MSG_SUPPORTS_COMMANDS = 3;
    static final int MSG_CANCEL = 4;

    final Context mContext;
    final HandlerCaller mHandlerCaller;
    final HandlerCaller.Callback mHandlerCallerCallback = new HandlerCaller.Callback() {
        @Override
        public void executeMessage(Message msg) {
            SomeArgs args = (SomeArgs)msg.obj;
            switch (msg.what) {
                case MSG_START_CONFIRMATION:
                    if (DEBUG) Log.d(TAG, "onConfirm: req=" + ((Request) args.arg2).mInterface
                            + " prompt=" + args.arg3 + " extras=" + args.arg4);
                    onConfirm((Caller)args.arg1, (Request)args.arg2, (String)args.arg3,
                            (Bundle)args.arg4);
                    break;
                case MSG_START_COMMAND:
                    if (DEBUG) Log.d(TAG, "onCommand: req=" + ((Request) args.arg2).mInterface
                            + " command=" + args.arg3 + " extras=" + args.arg4);
                    onCommand((Caller) args.arg1, (Request) args.arg2, (String) args.arg3,
                            (Bundle) args.arg4);
                    break;
                case MSG_SUPPORTS_COMMANDS:
                    if (DEBUG) Log.d(TAG, "onGetSupportedCommands: cmds=" + args.arg2);
                    args.arg1 = onGetSupportedCommands((Caller) args.arg1, (String[]) args.arg2);
                    break;
                case MSG_CANCEL:
                    if (DEBUG) Log.d(TAG, "onCancel: req=" + ((Request) args.arg1).mInterface);
                    onCancel((Request)args.arg1);
                    break;
            }
        }
    };

    final ArrayMap<IBinder, Request> mActiveRequests = new ArrayMap<IBinder, Request>();

    public VoiceInteractionSession(Context context) {
        this(context, new Handler());
    }

    public VoiceInteractionSession(Context context, Handler handler) {
        mContext = context;
        mHandlerCaller = new HandlerCaller(context, handler.getLooper(),
                mHandlerCallerCallback, true);
    }

    Request findRequest(IVoiceInteractorCallback callback, boolean newRequest) {
        synchronized (this) {
            Request req = mActiveRequests.get(callback.asBinder());
            if (req != null) {
                if (newRequest) {
                    throw new IllegalArgumentException("Given request callback " + callback
                            + " is already active");
                }
                return req;
            }
            req = new Request(callback, mHandlerCaller);
            mActiveRequests.put(callback.asBinder(), req);
            return req;
        }
    }

    public abstract boolean[] onGetSupportedCommands(Caller caller, String[] commands);
    public abstract void onConfirm(Caller caller, Request request, String prompt, Bundle extras);
    public abstract void onCommand(Caller caller, Request request, String command, Bundle extras);
    public abstract void onCancel(Request request);
}
