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
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputBinding;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethod;
import android.view.inputmethod.InputMethodSession;
import android.view.inputmethod.InputMethodSubtype;

import com.android.internal.inputmethod.CancellationGroup;
import com.android.internal.inputmethod.IInputMethodPrivilegedOperations;
import com.android.internal.inputmethod.InputMethodNavButtonFlags;
import com.android.internal.os.HandlerCaller;
import com.android.internal.os.SomeArgs;
import com.android.internal.view.IInlineSuggestionsRequestCallback;
import com.android.internal.view.IInputContext;
import com.android.internal.view.IInputMethod;
import com.android.internal.view.IInputMethodSession;
import com.android.internal.view.IInputSessionCallback;
import com.android.internal.view.InlineSuggestionsRequestInfo;

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

    final WeakReference<InputMethodServiceInternal> mTarget;
    final Context mContext;
    @UnsupportedAppUsage
    final HandlerCaller mCaller;
    final WeakReference<InputMethod> mInputMethod;
    final int mTargetSdkVersion;

    /**
     * This is not {@null} only between {@link #bindInput(InputBinding)} and {@link #unbindInput()}
     * so that {@link RemoteInputConnection} can query if {@link #unbindInput()} has already been
     * called or not, mainly to avoid unnecessary blocking operations.
     *
     * <p>This field must be set and cleared only from the binder thread(s), where the system
     * guarantees that {@link #bindInput(InputBinding)},
     * {@link #startInput(IBinder, IInputContext, int, EditorInfo, boolean, boolean)}, and
     * {@link #unbindInput()} are called with the same order as the original calls
     * in {@link com.android.server.inputmethod.InputMethodManagerService}.
     * See {@link IBinder#FLAG_ONEWAY} for detailed semantics.</p>
     */
    @Nullable
    CancellationGroup mCancellationGroup = null;

    // NOTE: we should have a cache of these.
    static final class InputMethodSessionCallbackWrapper implements InputMethod.SessionCallback {
        final Context mContext;
        final InputChannel mChannel;
        final IInputSessionCallback mCb;

        InputMethodSessionCallbackWrapper(Context context, InputChannel channel,
                IInputSessionCallback cb) {
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
        InputMethod inputMethod = mInputMethod.get();
        // Need a valid reference to the inputMethod for everything except a dump.
        if (inputMethod == null && msg.what != DO_DUMP) {
            Log.w(TAG, "Input method reference was null, ignoring message: " + msg.what);
            return;
        }

        switch (msg.what) {
            case DO_DUMP: {
                InputMethodServiceInternal target = mTarget.get();
                if (target == null) {
                    return;
                }
                SomeArgs args = (SomeArgs)msg.obj;
                try {
                    target.dump((FileDescriptor) args.arg1,
                            (PrintWriter) args.arg2, (String[]) args.arg3);
                } catch (RuntimeException e) {
                    ((PrintWriter)args.arg2).println("Exception: " + e);
                }
                synchronized (args.arg4) {
                    ((CountDownLatch)args.arg4).countDown();
                }
                args.recycle();
                return;
            }
            case DO_INITIALIZE_INTERNAL: {
                SomeArgs args = (SomeArgs) msg.obj;
                try {
                    inputMethod.initializeInternal((IBinder) args.arg1,
                            (IInputMethodPrivilegedOperations) args.arg2, msg.arg1,
                            (boolean) args.arg3, msg.arg2);
                } finally {
                    args.recycle();
                }
                return;
            }
            case DO_SET_INPUT_CONTEXT: {
                inputMethod.bindInput((InputBinding)msg.obj);
                return;
            }
            case DO_UNSET_INPUT_CONTEXT:
                inputMethod.unbindInput();
                return;
            case DO_START_INPUT: {
                final SomeArgs args = (SomeArgs) msg.obj;
                final IBinder startInputToken = (IBinder) args.arg1;
                final IInputContext inputContext = (IInputContext) args.arg2;
                final EditorInfo info = (EditorInfo) args.arg3;
                final CancellationGroup cancellationGroup = (CancellationGroup) args.arg4;
                final boolean restarting = args.argi5 == 1;
                @InputMethodNavButtonFlags
                final int navButtonFlags = args.argi6;
                final InputConnection ic = inputContext != null
                        ? new RemoteInputConnection(mTarget, inputContext, cancellationGroup)
                        : null;
                info.makeCompatible(mTargetSdkVersion);
                inputMethod.dispatchStartInputWithToken(ic, info, restarting, startInputToken,
                        navButtonFlags);
                args.recycle();
                return;
            }
            case DO_ON_NAV_BUTTON_FLAGS_CHANGED:
                inputMethod.onNavButtonFlagsChanged(msg.arg1);
                return;
            case DO_CREATE_SESSION: {
                SomeArgs args = (SomeArgs)msg.obj;
                inputMethod.createSession(new InputMethodSessionCallbackWrapper(
                        mContext, (InputChannel)args.arg1,
                        (IInputSessionCallback)args.arg2));
                args.recycle();
                return;
            }
            case DO_SET_SESSION_ENABLED:
                inputMethod.setSessionEnabled((InputMethodSession)msg.obj,
                        msg.arg1 != 0);
                return;
            case DO_SHOW_SOFT_INPUT: {
                final SomeArgs args = (SomeArgs)msg.obj;
                inputMethod.showSoftInputWithToken(
                        msg.arg1, (ResultReceiver) args.arg2, (IBinder) args.arg1);
                args.recycle();
                return;
            }
            case DO_HIDE_SOFT_INPUT: {
                final SomeArgs args = (SomeArgs) msg.obj;
                inputMethod.hideSoftInputWithToken(msg.arg1, (ResultReceiver) args.arg2,
                        (IBinder) args.arg1);
                args.recycle();
                return;
            }
            case DO_CHANGE_INPUTMETHOD_SUBTYPE:
                inputMethod.changeInputMethodSubtype((InputMethodSubtype)msg.obj);
                return;
            case DO_CREATE_INLINE_SUGGESTIONS_REQUEST: {
                final SomeArgs args = (SomeArgs) msg.obj;
                inputMethod.onCreateInlineSuggestionsRequest(
                        (InlineSuggestionsRequestInfo) args.arg1,
                        (IInlineSuggestionsRequestCallback) args.arg2);
                args.recycle();
                return;
            }
            case DO_CAN_START_STYLUS_HANDWRITING: {
                inputMethod.canStartStylusHandwriting(msg.arg1);
                return;
            }
            case DO_START_STYLUS_HANDWRITING: {
                final SomeArgs args = (SomeArgs) msg.obj;
                inputMethod.startStylusHandwriting(msg.arg1, (InputChannel) args.arg1,
                        (List<MotionEvent>) args.arg2);
                args.recycle();
                return;
            }
            case DO_INIT_INK_WINDOW: {
                inputMethod.initInkWindow();
                return;
            }
            case DO_FINISH_STYLUS_HANDWRITING: {
                inputMethod.finishStylusHandwriting();
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
    public void initializeInternal(IBinder token, IInputMethodPrivilegedOperations privOps,
            int configChanges, boolean stylusHwSupported,
            @InputMethodNavButtonFlags int navButtonFlags) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageIIOOO(DO_INITIALIZE_INTERNAL,
                configChanges, navButtonFlags, token, privOps, stylusHwSupported));
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
                IInputContext.Stub.asInterface(binding.getConnectionToken()), mCancellationGroup);
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
    public void startInput(IBinder startInputToken, IInputContext inputContext,
            EditorInfo attribute, boolean restarting,
            @InputMethodNavButtonFlags int navButtonFlags) {
        if (mCancellationGroup == null) {
            Log.e(TAG, "startInput must be called after bindInput.");
            mCancellationGroup = new CancellationGroup();
        }
        mCaller.executeOrSendMessage(mCaller.obtainMessageOOOOII(DO_START_INPUT, startInputToken,
                inputContext, attribute, mCancellationGroup, restarting ? 1 : 0, navButtonFlags));
    }

    @BinderThread
    @Override
    public void onNavButtonFlagsChanged(@InputMethodNavButtonFlags int navButtonFlags) {
        mCaller.executeOrSendMessage(
                mCaller.obtainMessageI(DO_ON_NAV_BUTTON_FLAGS_CHANGED, navButtonFlags));
    }

    @BinderThread
    @Override
    public void createSession(InputChannel channel, IInputSessionCallback callback) {
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
    public void showSoftInput(IBinder showInputToken, int flags, ResultReceiver resultReceiver) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageIOO(DO_SHOW_SOFT_INPUT,
                flags, showInputToken, resultReceiver));
    }

    @BinderThread
    @Override
    public void hideSoftInput(IBinder hideInputToken, int flags, ResultReceiver resultReceiver) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageIOO(DO_HIDE_SOFT_INPUT,
                flags, hideInputToken, resultReceiver));
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
}
