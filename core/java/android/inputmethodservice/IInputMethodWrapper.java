/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.inputmethodservice;

import android.annotation.BinderThread;
import android.annotation.DurationMillisLong;
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.util.Log;
import android.view.InputChannel;
import android.view.MotionEvent;
import android.view.inputmethod.ImeTracker;
import android.view.inputmethod.InputBinding;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethod;
import android.view.inputmethod.InputMethodSession;
import android.view.inputmethod.InputMethodSubtype;

import com.android.internal.inputmethod.CancellationGroup;
import com.android.internal.inputmethod.IInlineSuggestionsRequestCallback;
import com.android.internal.inputmethod.IInputMethod;
import com.android.internal.inputmethod.IInputMethodSession;
import com.android.internal.inputmethod.IInputMethodSessionCallback;
import com.android.internal.inputmethod.IRemoteInputConnection;
import com.android.internal.inputmethod.InlineSuggestionsRequestInfo;
import com.android.internal.inputmethod.InputMethodNavButtonFlags;
import com.android.internal.os.HandlerCaller;
import com.android.internal.os.SomeArgs;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Implements the internal IInputMethod interface to convert incoming calls
 * on to it back to calls on the public InputMethod interface, scheduling
 * them on the main thread of the process.
 */
class IInputMethodWrapper extends IInputMethod.Stub
        implements HandlerCaller.Callback {
    private static final String TAG = "InputMethodWrapper";

    private static final int DO_DUMP = 1;
    private static final int DO_INITIALIZE_INTERNAL = 10;
    private static final int DO_SET_INPUT_CONTEXT = 20;
    private static final int DO_UNSET_INPUT_CONTEXT = 30;
    private static final int DO_START_INPUT = 32;
    private static final int DO_ON_NAV_BUTTON_FLAGS_CHANGED = 35;
    private static final int DO_CREATE_SESSION = 40;
    private static final int DO_SET_SESSION_ENABLED = 45;
    private static final int DO_SHOW_SOFT_INPUT = 60;
    private static final int DO_HIDE_SOFT_INPUT = 70;
    private static final int DO_CHANGE_INPUTMETHOD_SUBTYPE = 80;
    private static final int DO_CREATE_INLINE_SUGGESTIONS_REQUEST = 90;
    private static final int DO_CAN_START_STYLUS_HANDWRITING = 100;
    private static final int DO_START_STYLUS_HANDWRITING = 110;
    private static final int DO_INIT_INK_WINDOW = 120;
    private static final int DO_FINISH_STYLUS_HANDWRITING = 130;
    private static final int DO_UPDATE_TOOL_TYPE = 140;
    private static final int DO_REMOVE_STYLUS_HANDWRITING_WINDOW = 150;
    private static final int DO_SET_STYLUS_WINDOW_IDLE_TIMEOUT = 160;

    final WeakReference<InputMethodServiceInternal> mTarget;
    final Context mContext;
    @UnsupportedAppUsage
    final HandlerCaller mCaller;
    final WeakReference<InputMethod> mInputMethod;
    final int mTargetSdkVersion;

    /**
     * This is not {@code null} only between {@link #bindInput(InputBinding)} and
     * {@link #unbindInput()} so that {@link RemoteInputConnection} can query if
     * {@link #unbindInput()} has already been called or not, mainly to avoid unnecessary
     * blocking operations.
     *
     * <p>This field must be set and cleared only from the binder thread(s), where the system
     * guarantees that {@link #bindInput(InputBinding)},
     * {@link #startInput(IInputMethod.StartInputParams)}, and {@link #unbindInput()} are called
     * with the same order as the original calls in
     * {@link com.android.server.inputmethod.InputMethodManagerService}.
     * See {@link IBinder#FLAG_ONEWAY} for detailed semantics.</p>
     */
    @Nullable
    CancellationGroup mCancellationGroup = null;

    // NOTE: we should have a cache of these.
    static final class InputMethodSessionCallbackWrapper implements InputMethod.SessionCallback {
        final Context mContext;
        final InputChannel mChannel;
        final IInputMethodSessionCallback mCb;

        InputMethodSessionCallbackWrapper(Context context, InputChannel channel,
                IInputMethodSessionCallback cb) {
            mContext = context;
            mChannel = channel;
            mCb = cb;
        }

        @Override
        public void sessionCreated(InputMethodSession session) {
            try {
                if (session != null) {
                    IInputMethodSessionWrapper wrap =
                            new IInputMethodSessionWrapper(mContext, session, mChannel);
                    mCb.sessionCreated(wrap);
                } else {
                    if (mChannel != null) {
                        mChannel.dispose();
                    }
                    mCb.sessionCreated(null);
                }
            } catch (RemoteException e) {
            }
        }
    }

    IInputMethodWrapper(InputMethodServiceInternal imsInternal, InputMethod inputMethod) {
        mTarget = new WeakReference<>(imsInternal);
        mContext = imsInternal.getContext().getApplicationContext();
        mCaller = new HandlerCaller(mContext, null, this, true /*asyncHandler*/);
        mInputMethod = new WeakReference<>(inputMethod);
        mTargetSdkVersion = imsInternal.getContext().getApplicationInfo().targetSdkVersion;
    }

    @MainThread
    @Override
    public void executeMessage(Message msg) {
        final InputMethod inputMethod = mInputMethod.get();
        final InputMethodServiceInternal target = mTarget.get();
        switch (msg.what) {
            case DO_DUMP: {
                SomeArgs args = (SomeArgs) msg.obj;
                if (isValid(inputMethod, target, "DO_DUMP")) {
                    final FileDescriptor fd = (FileDescriptor) args.arg1;
                    final PrintWriter fout = (PrintWriter) args.arg2;
                    final String[] dumpArgs = (String[]) args.arg3;
                    final CountDownLatch latch = (CountDownLatch) args.arg4;
                    try {
                        target.dump(fd, fout, dumpArgs);
                    } catch (RuntimeException e) {
                        fout.println("Exception: " + e);
                    } finally {
                        latch.countDown();
                    }
                }
                args.recycle();
                return;
            }
            case DO_INITIALIZE_INTERNAL:
                if (isValid(inputMethod, target, "DO_INITIALIZE_INTERNAL")) {
                    inputMethod.initializeInternal((IInputMethod.InitParams) msg.obj);
                }
                return;
            case DO_SET_INPUT_CONTEXT: {
                if (isValid(inputMethod, target, "DO_SET_INPUT_CONTEXT")) {
                    inputMethod.bindInput((InputBinding) msg.obj);
                }
                return;
            }
            case DO_UNSET_INPUT_CONTEXT:
                if (isValid(inputMethod, target, "DO_UNSET_INPUT_CONTEXT")) {
                    inputMethod.unbindInput();
                }
                return;
            case DO_START_INPUT: {
                final SomeArgs args = (SomeArgs) msg.obj;
                if (isValid(inputMethod, target, "DO_START_INPUT")) {
                    final InputConnection inputConnection = (InputConnection) args.arg1;
                    final IInputMethod.StartInputParams params =
                            (IInputMethod.StartInputParams) args.arg2;
                    inputMethod.dispatchStartInput(inputConnection, params);
                }
                args.recycle();
                return;
            }
            case DO_ON_NAV_BUTTON_FLAGS_CHANGED:
                if (isValid(inputMethod, target, "DO_ON_NAV_BUTTON_FLAGS_CHANGED")) {
                    inputMethod.onNavButtonFlagsChanged(msg.arg1);
                }
                return;
            case DO_CREATE_SESSION: {
                SomeArgs args = (SomeArgs) msg.obj;
                if (isValid(inputMethod, target, "DO_CREATE_SESSION")) {
                    inputMethod.createSession(new InputMethodSessionCallbackWrapper(
                            mContext, (InputChannel) args.arg1,
                            (IInputMethodSessionCallback) args.arg2));
                }
                args.recycle();
                return;
            }
            case DO_SET_SESSION_ENABLED:
                if (isValid(inputMethod, target, "DO_SET_SESSION_ENABLED")) {
                    inputMethod.setSessionEnabled((InputMethodSession) msg.obj, msg.arg1 != 0);
                }
                return;
            case DO_SHOW_SOFT_INPUT: {
                final SomeArgs args = (SomeArgs) msg.obj;
                final ImeTracker.Token statsToken = (ImeTracker.Token) args.arg3;
                if (isValid(inputMethod, target, "DO_SHOW_SOFT_INPUT")) {
                    ImeTracker.forLogging().onProgress(
                            statsToken, ImeTracker.PHASE_IME_WRAPPER_DISPATCH);
                    inputMethod.showSoftInputWithToken(
                            msg.arg1, (ResultReceiver) args.arg2, (IBinder) args.arg1, statsToken);
                } else {
                    ImeTracker.forLogging().onFailed(
                            statsToken, ImeTracker.PHASE_IME_WRAPPER_DISPATCH);
                }
                args.recycle();
                return;
            }
            case DO_HIDE_SOFT_INPUT: {
                final SomeArgs args = (SomeArgs) msg.obj;
                final ImeTracker.Token statsToken = (ImeTracker.Token) args.arg3;
                if (isValid(inputMethod, target, "DO_HIDE_SOFT_INPUT")) {
                    ImeTracker.forLogging().onProgress(
                            statsToken, ImeTracker.PHASE_IME_WRAPPER_DISPATCH);
                    inputMethod.hideSoftInputWithToken(msg.arg1, (ResultReceiver) args.arg2,
                            (IBinder) args.arg1, statsToken);
                } else {
                    ImeTracker.forLogging().onFailed(
                            statsToken, ImeTracker.PHASE_IME_WRAPPER_DISPATCH);
                }
                args.recycle();
                return;
            }
            case DO_CHANGE_INPUTMETHOD_SUBTYPE:
                if (isValid(inputMethod, target, "DO_CHANGE_INPUTMETHOD_SUBTYPE")) {
                    inputMethod.changeInputMethodSubtype((InputMethodSubtype) msg.obj);
                }
                return;
            case DO_CREATE_INLINE_SUGGESTIONS_REQUEST: {
                final SomeArgs args = (SomeArgs) msg.obj;
                if (isValid(inputMethod, target, "DO_CREATE_INLINE_SUGGESTIONS_REQUEST")) {
                    inputMethod.onCreateInlineSuggestionsRequest(
                            (InlineSuggestionsRequestInfo) args.arg1,
                            (IInlineSuggestionsRequestCallback) args.arg2);
                }
                args.recycle();
                return;
            }
            case DO_CAN_START_STYLUS_HANDWRITING: {
                if (isValid(inputMethod, target, "DO_CAN_START_STYLUS_HANDWRITING")) {
                    inputMethod.canStartStylusHandwriting(msg.arg1);
                }
                return;
            }
            case DO_UPDATE_TOOL_TYPE: {
                if (isValid(inputMethod, target, "DO_UPDATE_TOOL_TYPE")) {
                    inputMethod.updateEditorToolType(msg.arg1);
                }
                return;
            }
            case DO_START_STYLUS_HANDWRITING: {
                final SomeArgs args = (SomeArgs) msg.obj;
                if (isValid(inputMethod, target, "DO_START_STYLUS_HANDWRITING")) {
                    inputMethod.startStylusHandwriting(msg.arg1, (InputChannel) args.arg1,
                            (List<MotionEvent>) args.arg2);
                }
                args.recycle();
                return;
            }
            case DO_INIT_INK_WINDOW: {
                if (isValid(inputMethod, target, "DO_INIT_INK_WINDOW")) {
                    inputMethod.initInkWindow();
                }
                return;
            }
            case DO_FINISH_STYLUS_HANDWRITING: {
                if (isValid(inputMethod, target, "DO_FINISH_STYLUS_HANDWRITING")) {
                    inputMethod.finishStylusHandwriting();
                }
                return;
            }
            case DO_REMOVE_STYLUS_HANDWRITING_WINDOW: {
                if (isValid(inputMethod, target, "DO_REMOVE_STYLUS_HANDWRITING_WINDOW")) {
                    inputMethod.removeStylusHandwritingWindow();
                }
                return;
            }
            case DO_SET_STYLUS_WINDOW_IDLE_TIMEOUT: {
                inputMethod.setStylusWindowIdleTimeoutForTest((long) msg.obj);
                return;
            }
        }
        Log.w(TAG, "Unhandled message code: " + msg.what);
    }

    @BinderThread
    @Override
    protected void dump(FileDescriptor fd, PrintWriter fout, String[] args) {
        InputMethodServiceInternal target = mTarget.get();
        if (target == null) {
            return;
        }
        if (target.getContext().checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {

            fout.println("Permission Denial: can't dump InputMethodManager from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }

        CountDownLatch latch = new CountDownLatch(1);
        mCaller.getHandler().sendMessageAtFrontOfQueue(mCaller.obtainMessageOOOO(DO_DUMP,
                fd, fout, args, latch));
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                fout.println("Timeout waiting for dump");
            }
        } catch (InterruptedException e) {
            fout.println("Interrupted waiting for dump");
        }
    }

    @BinderThread
    @Override
    public void initializeInternal(@NonNull IInputMethod.InitParams params) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(DO_INITIALIZE_INTERNAL, params));
    }

    @BinderThread
    @Override
    public void onCreateInlineSuggestionsRequest(InlineSuggestionsRequestInfo requestInfo,
            IInlineSuggestionsRequestCallback cb) {
        mCaller.executeOrSendMessage(
                mCaller.obtainMessageOO(DO_CREATE_INLINE_SUGGESTIONS_REQUEST, requestInfo, cb));
    }

    @BinderThread
    @Override
    public void bindInput(InputBinding binding) {
        if (mCancellationGroup != null) {
            Log.e(TAG, "bindInput must be paired with unbindInput.");
        }
        mCancellationGroup = new CancellationGroup();
        InputConnection ic = new RemoteInputConnection(mTarget,
                IRemoteInputConnection.Stub.asInterface(binding.getConnectionToken()),
                mCancellationGroup);
        InputBinding nu = new InputBinding(ic, binding);
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(DO_SET_INPUT_CONTEXT, nu));
    }

    @BinderThread
    @Override
    public void unbindInput() {
        if (mCancellationGroup != null) {
            // Signal the flag then forget it.
            mCancellationGroup.cancelAll();
            mCancellationGroup = null;
        } else {
            Log.e(TAG, "unbindInput must be paired with bindInput.");
        }
        mCaller.executeOrSendMessage(mCaller.obtainMessage(DO_UNSET_INPUT_CONTEXT));
    }

    @BinderThread
    @Override
    public void startInput(@NonNull IInputMethod.StartInputParams params) {
        if (mCancellationGroup == null) {
            Log.e(TAG, "startInput must be called after bindInput.");
            mCancellationGroup = new CancellationGroup();
        }

        params.editorInfo.makeCompatible(mTargetSdkVersion);

        final InputConnection ic = params.remoteInputConnection == null ? null
                : new RemoteInputConnection(mTarget, params.remoteInputConnection,
                        mCancellationGroup);

        mCaller.executeOrSendMessage(mCaller.obtainMessageOO(DO_START_INPUT, ic, params));
    }

    @BinderThread
    @Override
    public void onNavButtonFlagsChanged(@InputMethodNavButtonFlags int navButtonFlags) {
        mCaller.executeOrSendMessage(
                mCaller.obtainMessageI(DO_ON_NAV_BUTTON_FLAGS_CHANGED, navButtonFlags));
    }

    @BinderThread
    @Override
    public void createSession(InputChannel channel, IInputMethodSessionCallback callback) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageOO(DO_CREATE_SESSION,
                channel, callback));
    }

    @BinderThread
    @Override
    public void setSessionEnabled(IInputMethodSession session, boolean enabled) {
        try {
            InputMethodSession ls = ((IInputMethodSessionWrapper)
                    session).getInternalInputMethodSession();
            if (ls == null) {
                Log.w(TAG, "Session is already finished: " + session);
                return;
            }
            mCaller.executeOrSendMessage(mCaller.obtainMessageIO(
                    DO_SET_SESSION_ENABLED, enabled ? 1 : 0, ls));
        } catch (ClassCastException e) {
            Log.w(TAG, "Incoming session not of correct type: " + session, e);
        }
    }

    @BinderThread
    @Override
    public void showSoftInput(IBinder showInputToken, @Nullable ImeTracker.Token statsToken,
            int flags, ResultReceiver resultReceiver) {
        ImeTracker.forLogging().onProgress(statsToken, ImeTracker.PHASE_IME_WRAPPER);
        mCaller.executeOrSendMessage(mCaller.obtainMessageIOOO(DO_SHOW_SOFT_INPUT,
                flags, showInputToken, resultReceiver, statsToken));
    }

    @BinderThread
    @Override
    public void hideSoftInput(IBinder hideInputToken, @Nullable ImeTracker.Token statsToken,
            int flags, ResultReceiver resultReceiver) {
        ImeTracker.forLogging().onProgress(statsToken, ImeTracker.PHASE_IME_WRAPPER);
        mCaller.executeOrSendMessage(mCaller.obtainMessageIOOO(DO_HIDE_SOFT_INPUT,
                flags, hideInputToken, resultReceiver, statsToken));
    }

    @BinderThread
    @Override
    public void changeInputMethodSubtype(InputMethodSubtype subtype) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(DO_CHANGE_INPUTMETHOD_SUBTYPE,
                subtype));
    }

    @BinderThread
    @Override
    public void canStartStylusHandwriting(int requestId)
            throws RemoteException {
        mCaller.executeOrSendMessage(
                mCaller.obtainMessageI(DO_CAN_START_STYLUS_HANDWRITING, requestId));
    }

    @BinderThread
    @Override
    public void updateEditorToolType(int toolType)
            throws RemoteException {
        mCaller.executeOrSendMessage(
                mCaller.obtainMessageI(DO_UPDATE_TOOL_TYPE, toolType));
    }

    @BinderThread
    @Override
    public void startStylusHandwriting(int requestId, @NonNull InputChannel channel,
            @Nullable List<MotionEvent> stylusEvents)
            throws RemoteException {
        mCaller.executeOrSendMessage(
                mCaller.obtainMessageIOO(DO_START_STYLUS_HANDWRITING, requestId, channel,
                        stylusEvents));
    }

    @BinderThread
    @Override
    public void initInkWindow() {
        mCaller.executeOrSendMessage(mCaller.obtainMessage(DO_INIT_INK_WINDOW));
    }

    @BinderThread
    @Override
    public void finishStylusHandwriting() {
        mCaller.executeOrSendMessage(mCaller.obtainMessage(DO_FINISH_STYLUS_HANDWRITING));
    }

    @BinderThread
    @Override
    public void removeStylusHandwritingWindow() {
        mCaller.executeOrSendMessage(mCaller.obtainMessage(DO_REMOVE_STYLUS_HANDWRITING_WINDOW));
    }

    @BinderThread
    @Override
    public void setStylusWindowIdleTimeoutForTest(@DurationMillisLong long timeout) {
        mCaller.executeOrSendMessage(
                mCaller.obtainMessageO(DO_SET_STYLUS_WINDOW_IDLE_TIMEOUT, timeout));
    }

    private static boolean isValid(InputMethod inputMethod, InputMethodServiceInternal target,
            String msg) {
        if (inputMethod != null && target != null && !target.isServiceDestroyed()) {
            return true;
        } else {
            Log.w(TAG, "Ignoring " + msg + ", InputMethod:" + inputMethod
                    + ", InputMethodServiceInternal:" + target);
            return false;
        }
    }
}
