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
import android.util.ArrayMap;
import android.util.Log;
import com.android.internal.app.IVoiceInteractor;
import com.android.internal.app.IVoiceInteractorCallback;
import com.android.internal.app.IVoiceInteractorRequest;
import com.android.internal.os.HandlerCaller;
import com.android.internal.os.SomeArgs;

import java.util.WeakHashMap;

/**
 * Interface for an {@link Activity} to interact with the user through voice.
 * @hide
 */
public class VoiceInteractor {
    static final String TAG = "VoiceInteractor";
    static final boolean DEBUG = true;

    final Context mContext;
    final Activity mActivity;
    final IVoiceInteractor mInteractor;
    final HandlerCaller mHandlerCaller;
    final HandlerCaller.Callback mHandlerCallerCallback = new HandlerCaller.Callback() {
        @Override
        public void executeMessage(Message msg) {
            SomeArgs args = (SomeArgs)msg.obj;
            Request request;
            switch (msg.what) {
                case MSG_CONFIRMATION_RESULT:
                    request = pullRequest((IVoiceInteractorRequest)args.arg1, true);
                    if (DEBUG) Log.d(TAG, "onConfirmResult: req="
                            + ((IVoiceInteractorRequest)args.arg1).asBinder() + "/" + request
                            + " confirmed=" + msg.arg1 + " result=" + args.arg2);
                    if (request != null) {
                        ((ConfirmationRequest)request).onConfirmationResult(msg.arg1 != 0,
                                (Bundle) args.arg2);
                        request.clear();
                    }
                    break;
                case MSG_COMMAND_RESULT:
                    request = pullRequest((IVoiceInteractorRequest)args.arg1, msg.arg1 != 0);
                    if (DEBUG) Log.d(TAG, "onCommandResult: req="
                            + ((IVoiceInteractorRequest)args.arg1).asBinder() + "/" + request
                            + " result=" + args.arg2);
                    if (request != null) {
                        ((CommandRequest)request).onCommandResult((Bundle) args.arg2);
                        if (msg.arg1 != 0) {
                            request.clear();
                        }
                    }
                    break;
                case MSG_CANCEL_RESULT:
                    request = pullRequest((IVoiceInteractorRequest)args.arg1, true);
                    if (DEBUG) Log.d(TAG, "onCancelResult: req="
                            + ((IVoiceInteractorRequest)args.arg1).asBinder() + "/" + request);
                    if (request != null) {
                        request.onCancel();
                        request.clear();
                    }
                    break;
            }
        }
    };

    final IVoiceInteractorCallback.Stub mCallback = new IVoiceInteractorCallback.Stub() {
        @Override
        public void deliverConfirmationResult(IVoiceInteractorRequest request, boolean confirmed,
                Bundle result) {
            mHandlerCaller.sendMessage(mHandlerCaller.obtainMessageIOO(
                    MSG_CONFIRMATION_RESULT, confirmed ? 1 : 0, request, result));
        }

        @Override
        public void deliverCommandResult(IVoiceInteractorRequest request, boolean complete,
                Bundle result) {
            mHandlerCaller.sendMessage(mHandlerCaller.obtainMessageIOO(
                    MSG_COMMAND_RESULT, complete ? 1 : 0, request, result));
        }

        @Override
        public void deliverCancel(IVoiceInteractorRequest request) throws RemoteException {
            mHandlerCaller.sendMessage(mHandlerCaller.obtainMessageO(
                    MSG_CANCEL_RESULT, request));
        }
    };

    final ArrayMap<IBinder, Request> mActiveRequests = new ArrayMap<IBinder, Request>();

    static final int MSG_CONFIRMATION_RESULT = 1;
    static final int MSG_COMMAND_RESULT = 2;
    static final int MSG_CANCEL_RESULT = 3;

    public static abstract class Request {
        IVoiceInteractorRequest mRequestInterface;
        Context mContext;
        Activity mActivity;

        public Request() {
        }

        public void cancel() {
            try {
                mRequestInterface.cancel();
            } catch (RemoteException e) {
                Log.w(TAG, "Voice interactor has died", e);
            }
        }

        public Context getContext() {
            return mContext;
        }

        public Activity getActivity() {
            return mActivity;
        }

        public void onCancel() {
        }

        void clear() {
            mRequestInterface = null;
            mContext = null;
            mActivity = null;
        }

        abstract IVoiceInteractorRequest submit(IVoiceInteractor interactor,
                String packageName, IVoiceInteractorCallback callback) throws RemoteException;
    }

    public static class ConfirmationRequest extends Request {
        final CharSequence mPrompt;
        final Bundle mExtras;

        /**
         * Confirms an operation with the user via the trusted system
         * VoiceInteractionService.  This allows an Activity to complete an unsafe operation that
         * would require the user to touch the screen when voice interaction mode is not enabled.
         * The result of the confirmation will be returned through an asynchronous call to
         * either {@link #onConfirmationResult(boolean, android.os.Bundle)} or
         * {@link #onCancel()}.
         *
         * <p>In some cases this may be a simple yes / no confirmation or the confirmation could
         * include context information about how the action will be completed
         * (e.g. booking a cab might include details about how long until the cab arrives)
         * so the user can give a confirmation.
         * @param prompt Optional confirmation text to read to the user as the action being
         * confirmed.
         * @param extras Additional optional information.
         */
        public ConfirmationRequest(CharSequence prompt, Bundle extras) {
            mPrompt = prompt;
            mExtras = extras;
        }

        public void onConfirmationResult(boolean confirmed, Bundle result) {
        }

        IVoiceInteractorRequest submit(IVoiceInteractor interactor, String packageName,
                IVoiceInteractorCallback callback) throws RemoteException {
            return interactor.startConfirmation(packageName, callback, mPrompt.toString(), mExtras);
        }
   }

    public static class CommandRequest extends Request {
        final String mCommand;
        final Bundle mArgs;

        /**
         * Execute a command using the trusted system VoiceInteractionService.
         * This allows an Activity to request additional information from the user needed to
         * complete an action (e.g. booking a table might have several possible times that the
         * user could select from or an app might need the user to agree to a terms of service).
         * The result of the confirmation will be returned through an asynchronous call to
         * either {@link #onCommandResult(android.os.Bundle)} or
         * {@link #onCancel()}.
         *
         * <p>The command is a string that describes the generic operation to be performed.
         * The command will determine how the properties in extras are interpreted and the set of
         * available commands is expected to grow over time.  An example might be
         * "com.google.voice.commands.REQUEST_NUMBER_BAGS" to request the number of bags as part of
         * airline check-in.  (This is not an actual working example.)
         *
         * @param command The desired command to perform.
         * @param args Additional arguments to control execution of the command.
         */
        public CommandRequest(String command, Bundle args) {
            mCommand = command;
            mArgs = args;
        }

        public void onCommandResult(Bundle result) {
        }

        IVoiceInteractorRequest submit(IVoiceInteractor interactor, String packageName,
                IVoiceInteractorCallback callback) throws RemoteException {
            return interactor.startConfirmation(packageName, callback, mCommand, mArgs);
        }
   }

    VoiceInteractor(Context context, Activity activity, IVoiceInteractor interactor,
            Looper looper) {
        mContext = context;
        mActivity = activity;
        mInteractor = interactor;
        mHandlerCaller = new HandlerCaller(context, looper, mHandlerCallerCallback, true);
    }

    Request pullRequest(IVoiceInteractorRequest request, boolean complete) {
        synchronized (mActiveRequests) {
            Request req = mActiveRequests.get(request.asBinder());
            if (req != null && complete) {
                mActiveRequests.remove(request.asBinder());
            }
            return req;
        }
    }

    public boolean submitRequest(Request request) {
        try {
            IVoiceInteractorRequest ireq = request.submit(mInteractor,
                    mContext.getOpPackageName(), mCallback);
            request.mRequestInterface = ireq;
            request.mContext = mContext;
            request.mActivity = mActivity;
            synchronized (mActiveRequests) {
                mActiveRequests.put(ireq.asBinder(), request);
            }
            return true;
        } catch (RemoteException e) {
            Log.w(TAG, "Remove voice interactor service died", e);
            return false;
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
