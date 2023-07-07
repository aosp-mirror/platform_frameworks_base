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

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.DebugUtils;
import android.util.Log;

import com.android.internal.app.IVoiceInteractor;
import com.android.internal.app.IVoiceInteractorCallback;
import com.android.internal.app.IVoiceInteractorRequest;
import com.android.internal.os.HandlerCaller;
import com.android.internal.os.SomeArgs;
import com.android.internal.util.function.pooled.PooledLambda;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
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
public final class VoiceInteractor {
    static final String TAG = "VoiceInteractor";
    static final boolean DEBUG = false;

    static final Request[] NO_REQUESTS = new Request[0];

    /** @hide */
    public static final String KEY_CANCELLATION_SIGNAL = "key_cancellation_signal";

    @Nullable IVoiceInteractor mInteractor;

    @Nullable Context mContext;
    @Nullable Activity mActivity;
    boolean mRetaining;

    final HandlerCaller mHandlerCaller;
    final HandlerCaller.Callback mHandlerCallerCallback = new HandlerCaller.Callback() {
        @Override
        public void executeMessage(Message msg) {
            SomeArgs args = (SomeArgs)msg.obj;
            Request request;
            boolean complete;
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
                case MSG_PICK_OPTION_RESULT:
                    complete = msg.arg1 != 0;
                    request = pullRequest((IVoiceInteractorRequest)args.arg1, complete);
                    if (DEBUG) Log.d(TAG, "onPickOptionResult: req="
                            + ((IVoiceInteractorRequest)args.arg1).asBinder() + "/" + request
                            + " finished=" + complete + " selection=" + args.arg2
                            + " result=" + args.arg3);
                    if (request != null) {
                        ((PickOptionRequest)request).onPickOptionResult(complete,
                                (PickOptionRequest.Option[]) args.arg2, (Bundle) args.arg3);
                        if (complete) {
                            request.clear();
                        }
                    }
                    break;
                case MSG_COMPLETE_VOICE_RESULT:
                    request = pullRequest((IVoiceInteractorRequest)args.arg1, true);
                    if (DEBUG) Log.d(TAG, "onCompleteVoice: req="
                            + ((IVoiceInteractorRequest)args.arg1).asBinder() + "/" + request
                            + " result=" + args.arg2);
                    if (request != null) {
                        ((CompleteVoiceRequest)request).onCompleteResult((Bundle) args.arg2);
                        request.clear();
                    }
                    break;
                case MSG_ABORT_VOICE_RESULT:
                    request = pullRequest((IVoiceInteractorRequest)args.arg1, true);
                    if (DEBUG) Log.d(TAG, "onAbortVoice: req="
                            + ((IVoiceInteractorRequest)args.arg1).asBinder() + "/" + request
                            + " result=" + args.arg2);
                    if (request != null) {
                        ((AbortVoiceRequest)request).onAbortResult((Bundle) args.arg2);
                        request.clear();
                    }
                    break;
                case MSG_COMMAND_RESULT:
                    complete = msg.arg1 != 0;
                    request = pullRequest((IVoiceInteractorRequest)args.arg1, complete);
                    if (DEBUG) Log.d(TAG, "onCommandResult: req="
                            + ((IVoiceInteractorRequest)args.arg1).asBinder() + "/" + request
                            + " completed=" + msg.arg1 + " result=" + args.arg2);
                    if (request != null) {
                        ((CommandRequest)request).onCommandResult(msg.arg1 != 0,
                                (Bundle) args.arg2);
                        if (complete) {
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
        public void deliverConfirmationResult(IVoiceInteractorRequest request, boolean finished,
                Bundle result) {
            mHandlerCaller.sendMessage(mHandlerCaller.obtainMessageIOO(
                    MSG_CONFIRMATION_RESULT, finished ? 1 : 0, request, result));
        }

        @Override
        public void deliverPickOptionResult(IVoiceInteractorRequest request,
                boolean finished, PickOptionRequest.Option[] options, Bundle result) {
            mHandlerCaller.sendMessage(mHandlerCaller.obtainMessageIOOO(
                    MSG_PICK_OPTION_RESULT, finished ? 1 : 0, request, options, result));
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
        public void deliverCancel(IVoiceInteractorRequest request) {
            mHandlerCaller.sendMessage(mHandlerCaller.obtainMessageOO(
                    MSG_CANCEL_RESULT, request, null));
        }

        @Override
        public void destroy() {
            mHandlerCaller.getHandler().sendMessage(PooledLambda.obtainMessage(
                    VoiceInteractor::destroy, VoiceInteractor.this));
        }
    };

    final ArrayMap<IBinder, Request> mActiveRequests = new ArrayMap<>();
    final ArrayMap<Runnable, Executor> mOnDestroyCallbacks = new ArrayMap<>();

    static final int MSG_CONFIRMATION_RESULT = 1;
    static final int MSG_PICK_OPTION_RESULT = 2;
    static final int MSG_COMPLETE_VOICE_RESULT = 3;
    static final int MSG_ABORT_VOICE_RESULT = 4;
    static final int MSG_COMMAND_RESULT = 5;
    static final int MSG_CANCEL_RESULT = 6;

    /**
     * Base class for voice interaction requests that can be submitted to the interactor.
     * Do not instantiate this directly -- instead, use the appropriate subclass.
     */
    public static abstract class Request {
        IVoiceInteractorRequest mRequestInterface;
        Context mContext;
        Activity mActivity;
        String mName;

        Request() {
        }

        /**
         * Return the name this request was submitted through
         * {@link #submitRequest(android.app.VoiceInteractor.Request, String)}.
         */
        public String getName() {
            return mName;
        }

        /**
         * Cancel this active request.
         */
        public void cancel() {
            if (mRequestInterface == null) {
                throw new IllegalStateException("Request " + this + " is no longer active");
            }
            try {
                mRequestInterface.cancel();
            } catch (RemoteException e) {
                Log.w(TAG, "Voice interactor has died", e);
            }
        }

        /**
         * Return the current {@link Context} this request is associated with.  May change
         * if the activity hosting it goes through a configuration change.
         */
        public Context getContext() {
            return mContext;
        }

        /**
         * Return the current {@link Activity} this request is associated with.  Will change
         * if the activity is restarted such as through a configuration change.
         */
        public Activity getActivity() {
            return mActivity;
        }

        /**
         * Report from voice interaction service: this operation has been canceled, typically
         * as a completion of a previous call to {@link #cancel} or when the user explicitly
         * cancelled.
         */
        public void onCancel() {
        }

        /**
         * The request is now attached to an activity, or being re-attached to a new activity
         * after a configuration change.
         */
        public void onAttached(Activity activity) {
        }

        /**
         * The request is being detached from an activity.
         */
        public void onDetached() {
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(128);
            DebugUtils.buildShortClassTag(this, sb);
            sb.append(" ");
            sb.append(getRequestTypeName());
            sb.append(" name=");
            sb.append(mName);
            sb.append('}');
            return sb.toString();
        }

        void dump(String prefix, FileDescriptor fd, PrintWriter writer, String[] args) {
            writer.print(prefix); writer.print("mRequestInterface=");
            writer.println(mRequestInterface.asBinder());
            writer.print(prefix); writer.print("mActivity="); writer.println(mActivity);
            writer.print(prefix); writer.print("mName="); writer.println(mName);
        }

        String getRequestTypeName() {
            return "Request";
        }

        void clear() {
            mRequestInterface = null;
            mContext = null;
            mActivity = null;
            mName = null;
        }

        abstract IVoiceInteractorRequest submit(IVoiceInteractor interactor,
                String packageName, IVoiceInteractorCallback callback) throws RemoteException;
    }

    /**
     * Confirms an operation with the user via the trusted system
     * VoiceInteractionService.  This allows an Activity to complete an unsafe operation that
     * would require the user to touch the screen when voice interaction mode is not enabled.
     * The result of the confirmation will be returned through an asynchronous call to
     * either {@link #onConfirmationResult(boolean, android.os.Bundle)} or
     * {@link #onCancel()} - these methods should be overridden to define the application specific
     *  behavior.
     *
     * <p>In some cases this may be a simple yes / no confirmation or the confirmation could
     * include context information about how the action will be completed
     * (e.g. booking a cab might include details about how long until the cab arrives)
     * so the user can give a confirmation.
     */
    public static class ConfirmationRequest extends Request {
        final Prompt mPrompt;
        final Bundle mExtras;

        /**
         * Create a new confirmation request.
         * @param prompt Optional confirmation to speak to the user or null if nothing
         *     should be spoken.
         * @param extras Additional optional information or null.
         */
        public ConfirmationRequest(@Nullable Prompt prompt, @Nullable Bundle extras) {
            mPrompt = prompt;
            mExtras = extras;
        }

        /**
         * Create a new confirmation request.
         * @param prompt Optional confirmation to speak to the user or null if nothing
         *     should be spoken.
         * @param extras Additional optional information or null.
         * @hide
         */
        public ConfirmationRequest(CharSequence prompt, Bundle extras) {
            mPrompt = (prompt != null ? new Prompt(prompt) : null);
            mExtras = extras;
        }

        /**
         * Handle the confirmation result. Override this method to define
         * the behavior when the user confirms or rejects the operation.
         * @param confirmed Whether the user confirmed or rejected the operation.
         * @param result Additional result information or null.
         */
        public void onConfirmationResult(boolean confirmed, Bundle result) {
        }

        void dump(String prefix, FileDescriptor fd, PrintWriter writer, String[] args) {
            super.dump(prefix, fd, writer, args);
            writer.print(prefix); writer.print("mPrompt="); writer.println(mPrompt);
            if (mExtras != null) {
                writer.print(prefix); writer.print("mExtras="); writer.println(mExtras);
            }
        }

        String getRequestTypeName() {
            return "Confirmation";
        }

        IVoiceInteractorRequest submit(IVoiceInteractor interactor, String packageName,
                IVoiceInteractorCallback callback) throws RemoteException {
            return interactor.startConfirmation(packageName, callback, mPrompt, mExtras);
        }
    }

    /**
     * Select a single option from multiple potential options with the user via the trusted system
     * VoiceInteractionService. Typically, the application would present this visually as
     * a list view to allow selecting the option by touch.
     * The result of the confirmation will be returned through an asynchronous call to
     * either {@link #onPickOptionResult} or {@link #onCancel()} - these methods should
     * be overridden to define the application specific behavior.
     */
    public static class PickOptionRequest extends Request {
        final Prompt mPrompt;
        final Option[] mOptions;
        final Bundle mExtras;

        /**
         * Represents a single option that the user may select using their voice. The 
         * {@link #getIndex()} method should be used as a unique ID to identify the option
         * when it is returned from the voice interactor.
         */
        public static final class Option implements Parcelable {
            final CharSequence mLabel;
            final int mIndex;
            ArrayList<CharSequence> mSynonyms;
            Bundle mExtras;

            /**
             * Creates an option that a user can select with their voice by matching the label
             * or one of several synonyms.
             * @param label The label that will both be matched against what the user speaks
             *     and displayed visually.
             * @hide
             */
            public Option(CharSequence label) {
                mLabel = label;
                mIndex = -1;
            }

            /**
             * Creates an option that a user can select with their voice by matching the label
             * or one of several synonyms.
             * @param label The label that will both be matched against what the user speaks
             *     and displayed visually.
             * @param index The location of this option within the overall set of options.
             *     Can be used to help identify the option when it is returned from the
             *     voice interactor.
             */
            public Option(CharSequence label, int index) {
                mLabel = label;
                mIndex = index;
            }

            /**
             * Add a synonym term to the option to indicate an alternative way the content
             * may be matched.
             * @param synonym The synonym that will be matched against what the user speaks,
             *     but not displayed.
             */
            public Option addSynonym(CharSequence synonym) {
                if (mSynonyms == null) {
                    mSynonyms = new ArrayList<>();
                }
                mSynonyms.add(synonym);
                return this;
            }

            public CharSequence getLabel() {
                return mLabel;
            }

            /**
             * Return the index that was supplied in the constructor.
             * If the option was constructed without an index, -1 is returned.
             */
            public int getIndex() {
                return mIndex;
            }

            public int countSynonyms() {
                return mSynonyms != null ? mSynonyms.size() : 0;
            }

            public CharSequence getSynonymAt(int index) {
                return mSynonyms != null ? mSynonyms.get(index) : null;
            }

            /**
             * Set optional extra information associated with this option.  Note that this
             * method takes ownership of the supplied extras Bundle.
             */
            public void setExtras(Bundle extras) {
                mExtras = extras;
            }

            /**
             * Return any optional extras information associated with this option, or null
             * if there is none.  Note that this method returns a reference to the actual
             * extras Bundle in the option, so modifications to it will directly modify the
             * extras in the option.
             */
            public Bundle getExtras() {
                return mExtras;
            }

            Option(Parcel in) {
                mLabel = in.readCharSequence();
                mIndex = in.readInt();
                mSynonyms = in.readCharSequenceList();
                mExtras = in.readBundle();
            }

            @Override
            public int describeContents() {
                return 0;
            }

            @Override
            public void writeToParcel(Parcel dest, int flags) {
                dest.writeCharSequence(mLabel);
                dest.writeInt(mIndex);
                dest.writeCharSequenceList(mSynonyms);
                dest.writeBundle(mExtras);
            }

            public static final @android.annotation.NonNull Parcelable.Creator<Option> CREATOR
                    = new Parcelable.Creator<Option>() {
                public Option createFromParcel(Parcel in) {
                    return new Option(in);
                }

                public Option[] newArray(int size) {
                    return new Option[size];
                }
            };
        };

        /**
         * Create a new pick option request.
         * @param prompt Optional question to be asked of the user when the options are
         *     presented or null if nothing should be asked.
         * @param options The set of {@link Option}s the user is selecting from.
         * @param extras Additional optional information or null.
         */
        public PickOptionRequest(@Nullable Prompt prompt, Option[] options,
                @Nullable Bundle extras) {
            mPrompt = prompt;
            mOptions = options;
            mExtras = extras;
        }

        /**
         * Create a new pick option request.
         * @param prompt Optional question to be asked of the user when the options are
         *     presented or null if nothing should be asked.
         * @param options The set of {@link Option}s the user is selecting from.
         * @param extras Additional optional information or null.
         * @hide
         */
        public PickOptionRequest(CharSequence prompt, Option[] options, Bundle extras) {
            mPrompt = (prompt != null ? new Prompt(prompt) : null);
            mOptions = options;
            mExtras = extras;
        }

        /**
         * Called when a single option is confirmed or narrowed to one of several options. Override
         * this method to define the behavior when the user selects an option or narrows down the
         * set of options.
         * @param finished True if the voice interaction has finished making a selection, in
         *     which case {@code selections} contains the final result.  If false, this request is
         *     still active and you will continue to get calls on it.
         * @param selections Either a single {@link Option} or one of several {@link Option}s the
         *     user has narrowed the choices down to.
         * @param result Additional optional information.
         */
        public void onPickOptionResult(boolean finished, Option[] selections, Bundle result) {
        }

        void dump(String prefix, FileDescriptor fd, PrintWriter writer, String[] args) {
            super.dump(prefix, fd, writer, args);
            writer.print(prefix); writer.print("mPrompt="); writer.println(mPrompt);
            if (mOptions != null) {
                writer.print(prefix); writer.println("Options:");
                for (int i=0; i<mOptions.length; i++) {
                    Option op = mOptions[i];
                    writer.print(prefix); writer.print("  #"); writer.print(i); writer.println(":");
                    writer.print(prefix); writer.print("    mLabel="); writer.println(op.mLabel);
                    writer.print(prefix); writer.print("    mIndex="); writer.println(op.mIndex);
                    if (op.mSynonyms != null && op.mSynonyms.size() > 0) {
                        writer.print(prefix); writer.println("    Synonyms:");
                        for (int j=0; j<op.mSynonyms.size(); j++) {
                            writer.print(prefix); writer.print("      #"); writer.print(j);
                            writer.print(": "); writer.println(op.mSynonyms.get(j));
                        }
                    }
                    if (op.mExtras != null) {
                        writer.print(prefix); writer.print("    mExtras=");
                        writer.println(op.mExtras);
                    }
                }
            }
            if (mExtras != null) {
                writer.print(prefix); writer.print("mExtras="); writer.println(mExtras);
            }
        }

        String getRequestTypeName() {
            return "PickOption";
        }

        IVoiceInteractorRequest submit(IVoiceInteractor interactor, String packageName,
                IVoiceInteractorCallback callback) throws RemoteException {
            return interactor.startPickOption(packageName, callback, mPrompt, mOptions, mExtras);
        }
    }

    /**
     * Reports that the current interaction was successfully completed with voice, so the
     * application can report the final status to the user. When the response comes back, the
     * voice system has handled the request and is ready to switch; at that point the
     * application can start a new non-voice activity or finish.  Be sure when starting the new
     * activity to use {@link android.content.Intent#FLAG_ACTIVITY_NEW_TASK
     * Intent.FLAG_ACTIVITY_NEW_TASK} to keep the new activity out of the current voice
     * interaction task.
     */
    public static class CompleteVoiceRequest extends Request {
        final Prompt mPrompt;
        final Bundle mExtras;

        /**
         * Create a new completed voice interaction request.
         * @param prompt Optional message to speak to the user about the completion status of
         *     the task or null if nothing should be spoken.
         * @param extras Additional optional information or null.
         */
        public CompleteVoiceRequest(@Nullable Prompt prompt, @Nullable Bundle extras) {
            mPrompt = prompt;
            mExtras = extras;
        }

        /**
         * Create a new completed voice interaction request.
         * @param message Optional message to speak to the user about the completion status of
         *     the task or null if nothing should be spoken.
         * @param extras Additional optional information or null.
         * @hide
         */
        public CompleteVoiceRequest(CharSequence message, Bundle extras) {
            mPrompt = (message != null ? new Prompt(message) : null);
            mExtras = extras;
        }

        public void onCompleteResult(Bundle result) {
        }

        void dump(String prefix, FileDescriptor fd, PrintWriter writer, String[] args) {
            super.dump(prefix, fd, writer, args);
            writer.print(prefix); writer.print("mPrompt="); writer.println(mPrompt);
            if (mExtras != null) {
                writer.print(prefix); writer.print("mExtras="); writer.println(mExtras);
            }
        }

        String getRequestTypeName() {
            return "CompleteVoice";
        }

        IVoiceInteractorRequest submit(IVoiceInteractor interactor, String packageName,
                IVoiceInteractorCallback callback) throws RemoteException {
            return interactor.startCompleteVoice(packageName, callback, mPrompt, mExtras);
        }
    }

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
     */
    public static class AbortVoiceRequest extends Request {
        final Prompt mPrompt;
        final Bundle mExtras;

        /**
         * Create a new voice abort request.
         * @param prompt Optional message to speak to the user indicating why the task could
         *     not be completed by voice or null if nothing should be spoken.
         * @param extras Additional optional information or null.
         */
        public AbortVoiceRequest(@Nullable Prompt prompt, @Nullable Bundle extras) {
            mPrompt = prompt;
            mExtras = extras;
        }

        /**
         * Create a new voice abort request.
         * @param message Optional message to speak to the user indicating why the task could
         *     not be completed by voice or null if nothing should be spoken.
         * @param extras Additional optional information or null.
         * @hide
         */
        public AbortVoiceRequest(CharSequence message, Bundle extras) {
            mPrompt = (message != null ? new Prompt(message) : null);
            mExtras = extras;
        }

        public void onAbortResult(Bundle result) {
        }

        void dump(String prefix, FileDescriptor fd, PrintWriter writer, String[] args) {
            super.dump(prefix, fd, writer, args);
            writer.print(prefix); writer.print("mPrompt="); writer.println(mPrompt);
            if (mExtras != null) {
                writer.print(prefix); writer.print("mExtras="); writer.println(mExtras);
            }
        }

        String getRequestTypeName() {
            return "AbortVoice";
        }

        IVoiceInteractorRequest submit(IVoiceInteractor interactor, String packageName,
                IVoiceInteractorCallback callback) throws RemoteException {
            return interactor.startAbortVoice(packageName, callback, mPrompt, mExtras);
        }
    }

    /**
     * Execute a vendor-specific command using the trusted system VoiceInteractionService.
     * This allows an Activity to request additional information from the user needed to
     * complete an action (e.g. booking a table might have several possible times that the
     * user could select from or an app might need the user to agree to a terms of service).
     * The result of the confirmation will be returned through an asynchronous call to
     * either {@link #onCommandResult(boolean, android.os.Bundle)} or
     * {@link #onCancel()}.
     *
     * <p>The command is a string that describes the generic operation to be performed.
     * The command will determine how the properties in extras are interpreted and the set of
     * available commands is expected to grow over time.  An example might be
     * "com.google.voice.commands.REQUEST_NUMBER_BAGS" to request the number of bags as part of
     * airline check-in.  (This is not an actual working example.)
     */
    public static class CommandRequest extends Request {
        final String mCommand;
        final Bundle mArgs;

        /**
         * Create a new generic command request.
         * @param command The desired command to perform.
         * @param args Additional arguments to control execution of the command.
         */
        public CommandRequest(String command, Bundle args) {
            mCommand = command;
            mArgs = args;
        }

        /**
         * Results for CommandRequest can be returned in partial chunks.
         * The isCompleted is set to true iff all results have been returned, indicating the
         * CommandRequest has completed.
         */
        public void onCommandResult(boolean isCompleted, Bundle result) {
        }

        void dump(String prefix, FileDescriptor fd, PrintWriter writer, String[] args) {
            super.dump(prefix, fd, writer, args);
            writer.print(prefix); writer.print("mCommand="); writer.println(mCommand);
            if (mArgs != null) {
                writer.print(prefix); writer.print("mArgs="); writer.println(mArgs);
            }
        }

        String getRequestTypeName() {
            return "Command";
        }

        IVoiceInteractorRequest submit(IVoiceInteractor interactor, String packageName,
                IVoiceInteractorCallback callback) throws RemoteException {
            return interactor.startCommand(packageName, callback, mCommand, mArgs);
        }
    }

    /**
     * A set of voice prompts to use with the voice interaction system to confirm an action, select
     * an option, or do similar operations. Multiple voice prompts may be provided for variety. A
     * visual prompt must be provided, which might not match the spoken version. For example, the
     * confirmation "Are you sure you want to purchase this item?" might use a visual label like
     * "Purchase item".
     */
    public static class Prompt implements Parcelable {
        // Mandatory voice prompt. Must contain at least one item, which must not be null.
        private final CharSequence[] mVoicePrompts;

        // Mandatory visual prompt.
        private final CharSequence mVisualPrompt;

        /**
         * Constructs a prompt set.
         * @param voicePrompts An array of one or more voice prompts. Must not be empty or null.
         * @param visualPrompt A prompt to display on the screen. Must not be null.
         */
        public Prompt(@NonNull CharSequence[] voicePrompts, @NonNull CharSequence visualPrompt) {
            if (voicePrompts == null) {
                throw new NullPointerException("voicePrompts must not be null");
            }
            if (voicePrompts.length == 0) {
                throw new IllegalArgumentException("voicePrompts must not be empty");
            }
            if (visualPrompt == null) {
                throw new NullPointerException("visualPrompt must not be null");
            }
            this.mVoicePrompts = voicePrompts;
            this.mVisualPrompt = visualPrompt;
        }

        /**
         * Constructs a prompt set with single prompt used for all interactions. This is most useful
         * in test apps. Non-trivial apps should prefer the detailed constructor.
         */
        public Prompt(@NonNull CharSequence prompt) {
            this.mVoicePrompts = new CharSequence[] { prompt };
            this.mVisualPrompt = prompt;
        }

        /**
         * Returns a prompt to use for voice interactions.
         */
        @NonNull
        public CharSequence getVoicePromptAt(int index) {
            return mVoicePrompts[index];
        }

        /**
         * Returns the number of different voice prompts.
         */
        public int countVoicePrompts() {
            return mVoicePrompts.length;
        }

        /**
         * Returns the prompt to use for visual display.
         */
        @NonNull
        public CharSequence getVisualPrompt() {
            return mVisualPrompt;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(128);
            DebugUtils.buildShortClassTag(this, sb);
            if (mVisualPrompt != null && mVoicePrompts != null && mVoicePrompts.length == 1
                && mVisualPrompt.equals(mVoicePrompts[0])) {
                sb.append(" ");
                sb.append(mVisualPrompt);
            } else {
                if (mVisualPrompt != null) {
                    sb.append(" visual="); sb.append(mVisualPrompt);
                }
                if (mVoicePrompts != null) {
                    sb.append(", voice=");
                    for (int i=0; i<mVoicePrompts.length; i++) {
                        if (i > 0) sb.append(" | ");
                        sb.append(mVoicePrompts[i]);
                    }
                }
            }
            sb.append('}');
            return sb.toString();
        }

        /** Constructor to support Parcelable behavior. */
        Prompt(Parcel in) {
            mVoicePrompts = in.readCharSequenceArray();
            mVisualPrompt = in.readCharSequence();
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeCharSequenceArray(mVoicePrompts);
            dest.writeCharSequence(mVisualPrompt);
        }

        public static final @android.annotation.NonNull Creator<Prompt> CREATOR
                = new Creator<Prompt>() {
            public Prompt createFromParcel(Parcel in) {
                return new Prompt(in);
            }

            public Prompt[] newArray(int size) {
                return new Prompt[size];
            }
        };
    }

    VoiceInteractor(IVoiceInteractor interactor, Context context, Activity activity,
            Looper looper) {
        mInteractor = interactor;
        mContext = context;
        mActivity = activity;
        mHandlerCaller = new HandlerCaller(context, looper, mHandlerCallerCallback, true);
        try {
            mInteractor.setKillCallback(new KillCallback(this));
        } catch (RemoteException e) {
            /* ignore */
        }
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
        ArrayList<Request> list = new ArrayList<>(N);
        for (int i=0; i<N; i++) {
            list.add(mActiveRequests.valueAt(i));
        }
        return list;
    }

    void attachActivity(Activity activity) {
        mRetaining = false;
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

    void retainInstance() {
        mRetaining = true;
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
        if (!mRetaining) {
            reqs = makeRequestList();
            if (reqs != null) {
                for (int i=0; i<reqs.size(); i++) {
                    Request req = reqs.get(i);
                    req.cancel();
                }
            }
            mActiveRequests.clear();
        }
        mContext = null;
        mActivity = null;
    }

    void destroy() {
        final int requestCount = mActiveRequests.size();
        for (int i = requestCount - 1; i >= 0; i--) {
            final Request request = mActiveRequests.valueAt(i);
            mActiveRequests.removeAt(i);
            request.cancel();
        }

        final int callbackCount = mOnDestroyCallbacks.size();
        for (int i = callbackCount - 1; i >= 0; i--) {
            final Runnable callback = mOnDestroyCallbacks.keyAt(i);
            final Executor executor = mOnDestroyCallbacks.valueAt(i);
            executor.execute(callback);
            mOnDestroyCallbacks.removeAt(i);
        }

        // destroyed now
        mInteractor = null;
        if (mActivity != null) {
            mActivity.setVoiceInteractor(null);
        }
    }

    public boolean submitRequest(Request request) {
        return submitRequest(request, null);
    }

    /**
     * Submit a new {@link Request} to the voice interaction service.  The request must be
     * one of the available subclasses -- {@link ConfirmationRequest}, {@link PickOptionRequest},
     * {@link CompleteVoiceRequest}, {@link AbortVoiceRequest}, or {@link CommandRequest}.
     *
     * @param request The desired request to submit.
     * @param name An optional name for this request, or null. This can be used later with
     * {@link #getActiveRequests} and {@link #getActiveRequest} to find the request.
     *
     * @return Returns true of the request was successfully submitted, else false.
     */
    public boolean submitRequest(Request request, String name) {
        if (isDestroyed()) {
            Log.w(TAG, "Cannot interact with a destroyed voice interactor");
            return false;
        }
        try {
            if (request.mRequestInterface != null) {
                throw new IllegalStateException("Given " + request + " is already active");
            }
            IVoiceInteractorRequest ireq = request.submit(mInteractor,
                    mContext.getOpPackageName(), mCallback);
            request.mRequestInterface = ireq;
            request.mContext = mContext;
            request.mActivity = mActivity;
            request.mName = name;
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
     * Return all currently active requests.
     */
    public Request[] getActiveRequests() {
        if (isDestroyed()) {
            Log.w(TAG, "Cannot interact with a destroyed voice interactor");
            return null;
        }
        synchronized (mActiveRequests) {
            final int N = mActiveRequests.size();
            if (N <= 0) {
                return NO_REQUESTS;
            }
            Request[] requests = new Request[N];
            for (int i=0; i<N; i++) {
                requests[i] = mActiveRequests.valueAt(i);
            }
            return requests;
        }
    }

    /**
     * Return any currently active request that was submitted with the given name.
     *
     * @param name The name used to submit the request, as per
     * {@link #submitRequest(android.app.VoiceInteractor.Request, String)}.
     * @return Returns the active request with that name, or null if there was none.
     */
    public Request getActiveRequest(String name) {
        if (isDestroyed()) {
            Log.w(TAG, "Cannot interact with a destroyed voice interactor");
            return null;
        }
        synchronized (mActiveRequests) {
            final int N = mActiveRequests.size();
            for (int i=0; i<N; i++) {
                Request req = mActiveRequests.valueAt(i);
                if (name == req.getName() || (name != null && name.equals(req.getName()))) {
                    return req;
                }
            }
        }
        return null;
    }

    /**
     * Queries the supported commands available from the VoiceInteractionService.
     * The command is a string that describes the generic operation to be performed.
     * An example might be "org.example.commands.PICK_DATE" to ask the user to pick
     * a date.  (Note: This is not an actual working example.)
     *
     * @param commands The array of commands to query for support.
     * @return Array of booleans indicating whether each command is supported or not.
     */
    public boolean[] supportsCommands(String[] commands) {
        if (isDestroyed()) {
            Log.w(TAG, "Cannot interact with a destroyed voice interactor");
            return new boolean[commands.length];
        }
        try {
            boolean[] res = mInteractor.supportsCommands(mContext.getOpPackageName(), commands);
            if (DEBUG) {
                Log.d(TAG, "supportsCommands: cmds=" + Arrays.toString(commands) + " res="
                        + Arrays.toString(res));
            }
            return res;
        } catch (RemoteException e) {
            throw new RuntimeException("Voice interactor has died", e);
        }
    }

    /**
     * @return whether the voice interactor is destroyed. You should not interact
     * with a destroyed voice interactor.
     */
    public boolean isDestroyed() {
        return mInteractor == null;
    }

    /**
     * Registers a callback to be called when the VoiceInteractor is destroyed.
     *
     * @param executor Executor on which to run the callback.
     * @param callback The callback to run.
     * @return whether the callback was registered.
     */
    public boolean registerOnDestroyedCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull Runnable callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        if (isDestroyed()) {
            Log.w(TAG, "Cannot interact with a destroyed voice interactor");
            return false;
        }
        mOnDestroyCallbacks.put(callback, executor);
        return true;
    }

    /**
     * Unregisters a previously registered onDestroy callback
     *
     * @param callback The callback to remove.
     * @return whether the callback was unregistered.
     */
    public boolean unregisterOnDestroyedCallback(@NonNull Runnable callback) {
        Objects.requireNonNull(callback);
        if (isDestroyed()) {
            Log.w(TAG, "Cannot interact with a destroyed voice interactor");
            return false;
        }
        return mOnDestroyCallbacks.remove(callback) != null;
    }

    /**
     * Notifies the assist framework that the direct actions supported by the app changed.
     */
    public void notifyDirectActionsChanged() {
        if (isDestroyed()) {
            Log.w(TAG, "Cannot interact with a destroyed voice interactor");
            return;
        }
        try {
            mInteractor.notifyDirectActionsChanged(mActivity.getTaskId(),
                    mActivity.getAssistToken());
        } catch (RemoteException e) {
            Log.w(TAG, "Voice interactor has died", e);
        }
    }

    /**
     * @return the package name of the service providing the VoiceInteractionService.
     */
    @NonNull
    public String getPackageName() {
        String packageName = null;
        if (mActivity != null && mInteractor != null) {
            try {
                packageName = ActivityTaskManager.getService()
                    .getVoiceInteractorPackageName(mInteractor.asBinder());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return packageName == null ? "" : packageName;
    }

    void dump(String prefix, FileDescriptor fd, PrintWriter writer, String[] args) {
        String innerPrefix = prefix + "    ";
        if (mActiveRequests.size() > 0) {
            writer.print(prefix); writer.println("Active voice requests:");
            for (int i=0; i<mActiveRequests.size(); i++) {
                Request req = mActiveRequests.valueAt(i);
                writer.print(prefix); writer.print("  #"); writer.print(i);
                writer.print(": ");
                writer.println(req);
                req.dump(innerPrefix, fd, writer, args);
            }
        }
        writer.print(prefix); writer.println("VoiceInteractor misc state:");
        writer.print(prefix); writer.print("  mInteractor=");
        writer.println(mInteractor.asBinder());
        writer.print(prefix); writer.print("  mActivity="); writer.println(mActivity);
    }

    private static final class KillCallback extends ICancellationSignal.Stub {
        private final WeakReference<VoiceInteractor> mInteractor;

        KillCallback(VoiceInteractor interactor) {
            mInteractor= new WeakReference<>(interactor);
        }

        @Override
        public void cancel() {
            final VoiceInteractor voiceInteractor = mInteractor.get();
            if (voiceInteractor != null) {
                voiceInteractor.mHandlerCaller.getHandler().sendMessage(PooledLambda
                        .obtainMessage(VoiceInteractor::destroy, voiceInteractor));
            }
        }
    }
}
