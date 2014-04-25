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

package android.app;

import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import com.android.internal.app.IVoiceInteractor;
import com.android.internal.app.IVoiceInteractorCallback;
import com.android.internal.app.IVoiceInteractorRequest;
import com.android.internal.os.HandlerCaller;
import com.android.internal.os.SomeArgs;

import java.util.WeakHashMap;

/**
 * Interface for an {@link Activity} to interact with the user through voice.
 */
public class VoiceInteractor {
    static final String TAG = "VoiceInteractor";
    static final boolean DEBUG = true;

    final Context mContext;
    final IVoiceInteractor mInteractor;
    final HandlerCaller mHandlerCaller;
    final HandlerCaller.Callback mHandlerCallerCallback = new HandlerCaller.Callback() {
        @Override
        public void executeMessage(Message msg) {
            SomeArgs args = (SomeArgs)msg.obj;
            switch (msg.what) {
                case MSG_CONFIRMATION_RESULT:
                    if (DEBUG) Log.d(TAG, "onConfirmResult: req="
                            + ((IVoiceInteractorRequest)args.arg2).asBinder()
                            + " confirmed=" + msg.arg1 + " result=" + args.arg3);
                    ((Callback)args.arg1).onConfirmationResult(
                            findRequest((IVoiceInteractorRequest)args.arg2),
                            msg.arg1 != 0, (Bundle)args.arg3);
                    break;
                case MSG_COMMAND_RESULT:
                    if (DEBUG) Log.d(TAG, "onCommandResult: req="
                            + ((IVoiceInteractorRequest)args.arg2).asBinder()
                            + " result=" + args.arg2);
                    ((Callback)args.arg1).onCommandResult(
                            findRequest((IVoiceInteractorRequest) args.arg2),
                            (Bundle) args.arg3);
                    break;
                case MSG_CANCEL_RESULT:
                    if (DEBUG) Log.d(TAG, "onCancelResult: req="
                            + ((IVoiceInteractorRequest)args.arg2).asBinder());
                    ((Callback)args.arg1).onCancel(
                            findRequest((IVoiceInteractorRequest) args.arg2));
                    break;
            }
        }
    };

    final WeakHashMap<IBinder, Request> mActiveRequests = new WeakHashMap<IBinder, Request>();

    static final int MSG_CONFIRMATION_RESULT = 1;
    static final int MSG_COMMAND_RESULT = 2;
    static final int MSG_CANCEL_RESULT = 3;

    public static class Request {
        final IVoiceInteractorRequest mRequestInterface;

        Request(IVoiceInteractorRequest requestInterface) {
            mRequestInterface = requestInterface;
        }

        public void cancel() {
            try {
                mRequestInterface.cancel();
            } catch (RemoteException e) {
                Log.w(TAG, "Voice interactor has died", e);
            }
        }
    }

    public static class Callback {
        VoiceInteractor mInteractor;

        final IVoiceInteractorCallback.Stub mWrapper = new IVoiceInteractorCallback.Stub() {
            @Override
            public void deliverConfirmationResult(IVoiceInteractorRequest request, boolean confirmed,
                    Bundle result) {
                mInteractor.mHandlerCaller.sendMessage(mInteractor.mHandlerCaller.obtainMessageIOOO(
                        MSG_CONFIRMATION_RESULT, confirmed ? 1 : 0, Callback.this, request,
                        result));
            }

            @Override
            public void deliverCommandResult(IVoiceInteractorRequest request, Bundle result) {
                mInteractor.mHandlerCaller.sendMessage(mInteractor.mHandlerCaller.obtainMessageOOO(
                        MSG_COMMAND_RESULT, Callback.this, request, result));
            }

            @Override
            public void deliverCancel(IVoiceInteractorRequest request) throws RemoteException {
                mInteractor.mHandlerCaller.sendMessage(mInteractor.mHandlerCaller.obtainMessageOO(
                        MSG_CANCEL_RESULT, Callback.this, request));
            }
        };

        public void onConfirmationResult(Request request, boolean confirmed, Bundle result) {
        }

        public void onCommandResult(Request request, Bundle result) {
        }

        public void onCancel(Request request) {
        }
    }

    VoiceInteractor(Context context, IVoiceInteractor interactor, Looper looper) {
        mContext = context;
        mInteractor = interactor;
        mHandlerCaller = new HandlerCaller(context, looper, mHandlerCallerCallback, true);
    }

    Request storeRequest(IVoiceInteractorRequest request) {
        synchronized (mActiveRequests) {
            Request req = new Request(request);
            mActiveRequests.put(request.asBinder(), req);
            return req;
        }
    }

    Request findRequest(IVoiceInteractorRequest request) {
        synchronized (mActiveRequests) {
            Request req = mActiveRequests.get(request.asBinder());
            if (req == null) {
                throw new IllegalStateException("Received callback without active request: "
                        + request);
            }
            return req;
        }
    }

    /**
     * Asynchronously confirms an operation with the user via the trusted system
     * VoiceinteractionService.  This allows an Activity to complete an unsafe operation that
     * would require the user to touch the screen when voice interaction mode is not enabled.
     * The result of the confirmation will be returned by calling the
     * {@link Callback#onConfirmationResult Callback.onConfirmationResult} method.
     *
     * In some cases this may be a simple yes / no confirmation or the confirmation could
     * include context information about how the action will be completed
     * (e.g. booking a cab might include details about how long until the cab arrives) so the user
     * can give informed consent.
     * @param callback Required callback target for interaction results.
     * @param prompt Optional confirmation text to read to the user as the action being confirmed.
     * @param extras Additional optional information.
     * @return Returns a new {@link Request} object representing this operation.
     */
    public Request startConfirmation(Callback callback, String prompt, Bundle extras) {
        try {
            callback.mInteractor = this;
            Request req = storeRequest(mInteractor.startConfirmation(
                    mContext.getOpPackageName(), callback.mWrapper, prompt, extras));
            if (DEBUG) Log.d(TAG, "startConfirmation: req=" + req.mRequestInterface.asBinder()
                    + " prompt=" + prompt + " extras=" + extras);
            return req;
        } catch (RemoteException e) {
            throw new RuntimeException("Voice interactor has died", e);
        }
    }

    /**
     * Asynchronously executes a command using the trusted system VoiceinteractionService.
     * This allows an Activity to request additional information from the user needed to
     * complete an action (e.g. booking a table might have several possible times that the
     * user could select from or an app might need the user to agree to a terms of service).
     *
     * The command is a string that describes the generic operation to be performed.
     * The command will determine how the properties in extras are interpreted and the set of
     * available commands is expected to grow over time.  An example might be
     * "com.google.voice.commands.REQUEST_NUMBER_BAGS" to request the number of bags as part of
     * airline check-in.  (This is not an actual working example.)
     * The result of the command will be returned by calling the
     * {@link Callback#onCommandResult Callback.onCommandResult} method.
     *
     * @param callback Required callback target for interaction results.
     * @param command
     * @param extras
     * @return Returns a new {@link Request} object representing this operation.
     */
    public Request startCommand(Callback callback, String command, Bundle extras) {
        try {
            callback.mInteractor = this;
            Request req = storeRequest(mInteractor.startCommand(
                    mContext.getOpPackageName(), callback.mWrapper, command, extras));
            if (DEBUG) Log.d(TAG, "startCommand: req=" + req.mRequestInterface.asBinder()
                    + " command=" + command + " extras=" + extras);
            return req;
        } catch (RemoteException e) {
            throw new RuntimeException("Voice interactor has died", e);
        }
    }

    /**
     * Queries the supported commands available from the VoiceinteractionService.
     * The command is a string that describes the generic operation to be performed.
     * An example might be "com.google.voice.commands.REQUEST_NUMBER_BAGS" to request the number
     * of bags as part of airline check-in.  (This is not an actual working example.)
     *
     * @param commands
     */
    public boolean[] supportsCommands(String[] commands) {
        try {
            boolean[] res = mInteractor.supportsCommands(mContext.getOpPackageName(), commands);
            if (DEBUG) Log.d(TAG, "supportsCommands: cmds=" + commands + " res=" + res);
            return res;
        } catch (RemoteException e) {
            throw new RuntimeException("Voice interactor has died", e);
        }
    }
}
