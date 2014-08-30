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

import android.annotation.SystemApi;
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

import java.util.ArrayList;

/**
 * @hide
 * Interface for an {@link Activity} to interact with the user through voice.  Use
 * {@link android.app.Activity#getVoiceInteractor() Activity.getVoiceInteractor}
 * to retrieve the interface, if the activity is currently involved in a voice interaction.
 *
 * <p>The voice interactor revolves around submitting voice interaction requests to the
 * back-end voice interaction service that is working with the user.  These requests are
 * submitted with {@link #submitRequest}, providing a new instance of a
 * {@link Request} subclass describing the type of operation to perform -- currently the
 * possible requests are {@link ConfirmationRequest} and {@link CommandRequest}.
 *
 * <p>Once a request is submitted, the voice system will process it and eventually deliver
 * the result to the request object.  The application can cancel a pending request at any
 * time.
 *
 * <p>The VoiceInteractor is integrated with Activity's state saving mechanism, so that
 * if an activity is being restarted with retained state, it will retain the current
 * VoiceInteractor and any outstanding requests.  Because of this, you should always use
 * {@link Request#getActivity() Request.getActivity} to get back to the activity of a
 * request, rather than holding on to the activity instance yourself, either explicitly
 * or implicitly through a non-static inner class.
 */
@SystemApi
public class VoiceInteractor {
    static final String TAG = "VoiceInteractor";
    static final boolean DEBUG = true;

    final IVoiceInteractor mInteractor;

    Context mContext;
    Activity mActivity;

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
                case MSG_COMPLETE_VOICE_RESULT:
                    request = pullRequest((IVoiceInteractorRequest)args.arg1, true);
                    if (DEBUG) Log.d(TAG, "onCompleteVoice: req="
                            + ((IVoiceInteractorRequest)args.arg1).asBinder() + "/" + request
                            + " result=" + args.arg1);
                    if (request != null) {
                        ((CompleteVoiceRequest)request).onCompleteResult((Bundle) args.arg2);
                        request.clear();
                    }
                    break;
                case MSG_ABORT_VOICE_RESULT:
                    request = pullRequest((IVoiceInteractorRequest)args.arg1, true);
                    if (DEBUG) Log.d(TAG, "onAbortVoice: req="
                            + ((IVoiceInteractorRequest)args.arg1).asBinder() + "/" + request
                            + " result=" + args.arg1);
                    if (request != null) {
                        ((AbortVoiceRequest)request).onAbortResult((Bundle) args.arg2);
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
        public void deliverCompleteVoiceResult(IVoiceInteractorRequest request, Bundle result) {
            mHandlerCaller.sendMessage(mHandlerCaller.obtainMessageOO(
                    MSG_COMPLETE_VOICE_RESULT, request, result));
        }

        @Override
        public void deliverAbortVoiceResult(IVoiceInteractorRequest request, Bundle result) {
            mHandlerCaller.sendMessage(mHandlerCaller.obtainMessageOO(
                    MSG_ABORT_VOICE_RESULT, request, result));
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
    static final int MSG_COMPLETE_VOICE_RESULT = 2;
    static final int MSG_ABORT_VOICE_RESULT = 3;
    static final int MSG_COMMAND_RESULT = 4;
    static final int MSG_CANCEL_RESULT = 5;

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

        public void onAttached(Activity activity) {
        }

        public void onDetached() {
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
            return interactor.startConfirmation(packageName, callback, mPrompt, mExtras);
        }
    }

    public static class CompleteVoiceRequest extends Request {
        final CharSequence mMessage;
        final Bundle mExtras;

        /**
         * Reports that the current interaction was successfully completed with voice, so the
         * application can report the final status to the user. When the response comes back, the
         * voice system has handled the request and is ready to switch; at that point the
         * application can start a new non-voice activity or finish.  Be sure when starting the new
         * activity to use {@link android.content.Intent#FLAG_ACTIVITY_NEW_TASK
         * Intent.FLAG_ACTIVITY_NEW_TASK} to keep the new activity out of the current voice
         * interaction task.
         *
         * @param message Optional message to tell user about the completion status of the task.
         * @param extras Additional optional information.
         */
        public CompleteVoiceRequest(CharSequence message, Bundle extras) {
            mMessage = message;
            mExtras = extras;
        }

        public void onCompleteResult(Bundle result) {
        }

        IVoiceInteractorRequest submit(IVoiceInteractor interactor, String packageName,
                IVoiceInteractorCallback callback) throws RemoteException {
            return interactor.startCompleteVoice(packageName, callback, mMessage, mExtras);
        }
    }

    public static class AbortVoiceRequest extends Request {
        final CharSequence mMessage;
        final Bundle mExtras;

        /**
         * Reports that the current interaction can not be complete with voice, so the
         * application will need to switch to a traditional input UI.  Applications should
         * only use this when they need to completely bail out of the voice interaction
         * and switch to a traditional UI.  When the response comes back, the voice
         * system has handled the request and is ready to switch; at that point the application
         * can start a new non-voice activity.  Be sure when starting the new activity
         * to use {@link android.content.Intent#FLAG_ACTIVITY_NEW_TASK
         * Intent.FLAG_ACTIVITY_NEW_TASK} to keep the new activity out of the current voice
         * interaction task.
         *
         * @param message Optional message to tell user about not being able to complete
         * the interaction with voice.
         * @param extras Additional optional information.
         */
        public AbortVoiceRequest(CharSequence message, Bundle extras) {
            mMessage = message;
            mExtras = extras;
        }

        public void onAbortResult(Bundle result) {
        }

        IVoiceInteractorRequest submit(IVoiceInteractor interactor, String packageName,
                IVoiceInteractorCallback callback) throws RemoteException {
            return interactor.startAbortVoice(packageName, callback, mMessage, mExtras);
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
            return interactor.startCommand(packageName, callback, mCommand, mArgs);
        }
   }

    VoiceInteractor(IVoiceInteractor interactor, Context context, Activity activity,
            Looper looper) {
        mInteractor = interactor;
        mContext = context;
        mActivity = activity;
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

    private ArrayList<Request> makeRequestList() {
        final int N = mActiveRequests.size();
        if (N < 1) {
            return null;
        }
        ArrayList<Request> list = new ArrayList<Request>(N);
        for (int i=0; i<N; i++) {
            list.add(mActiveRequests.valueAt(i));
        }
        return list;
    }

    void attachActivity(Activity activity) {
        if (mActivity == activity) {
            return;
        }
        mContext = activity;
        mActivity = activity;
        ArrayList<Request> reqs = makeRequestList();
        if (reqs != null) {
            for (int i=0; i<reqs.size(); i++) {
                Request req = reqs.get(i);
                req.mContext = activity;
                req.mActivity = activity;
                req.onAttached(activity);
            }
        }
    }

    void detachActivity() {
        ArrayList<Request> reqs = makeRequestList();
        if (reqs != null) {
            for (int i=0; i<reqs.size(); i++) {
                Request req = reqs.get(i);
                req.onDetached();
                req.mActivity = null;
                req.mContext = null;
            }
        }
        mContext = null;
        mActivity = null;
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
