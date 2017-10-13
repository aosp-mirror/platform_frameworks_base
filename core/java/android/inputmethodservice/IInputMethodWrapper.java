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
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.util.Log;
import android.view.InputChannel;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputBinding;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionInspector;
import android.view.inputmethod.InputMethod;
import android.view.inputmethod.InputMethodSession;
import android.view.inputmethod.InputMethodSubtype;

import com.android.internal.os.HandlerCaller;
import com.android.internal.os.SomeArgs;
import com.android.internal.view.IInputContext;
import com.android.internal.view.IInputMethod;
import com.android.internal.view.IInputMethodSession;
import com.android.internal.view.IInputSessionCallback;
import com.android.internal.view.InputConnectionWrapper;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Implements the internal IInputMethod interface to convert incoming calls
 * on to it back to calls on the public InputMethod interface, scheduling
 * them on the main thread of the process.
 */
class IInputMethodWrapper extends IInputMethod.Stub
        implements HandlerCaller.Callback {
    private static final String TAG = "InputMethodWrapper";

    private static final int DO_DUMP = 1;
    private static final int DO_ATTACH_TOKEN = 10;
    private static final int DO_SET_INPUT_CONTEXT = 20;
    private static final int DO_UNSET_INPUT_CONTEXT = 30;
    private static final int DO_START_INPUT = 32;
    private static final int DO_CREATE_SESSION = 40;
    private static final int DO_SET_SESSION_ENABLED = 45;
    private static final int DO_REVOKE_SESSION = 50;
    private static final int DO_SHOW_SOFT_INPUT = 60;
    private static final int DO_HIDE_SOFT_INPUT = 70;
    private static final int DO_CHANGE_INPUTMETHOD_SUBTYPE = 80;

    final WeakReference<AbstractInputMethodService> mTarget;
    final Context mContext;
    final HandlerCaller mCaller;
    final WeakReference<InputMethod> mInputMethod;
    final int mTargetSdkVersion;

    /**
     * This is not {@null} only between {@link #bindInput(InputBinding)} and {@link #unbindInput()}
     * so that {@link InputConnectionWrapper} can query if {@link #unbindInput()} has already been
     * called or not, mainly to avoid unnecessary blocking operations.
     *
     * <p>This field must be set and cleared only from the binder thread(s), where the system
     * guarantees that {@link #bindInput(InputBinding)},
     * {@link #startInput(IBinder, IInputContext, int, EditorInfo, boolean)}, and
     * {@link #unbindInput()} are called with the same order as the original calls
     * in {@link com.android.server.InputMethodManagerService}.  See {@link IBinder#FLAG_ONEWAY}
     * for detailed semantics.</p>
     */
    AtomicBoolean mIsUnbindIssued = null;

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

    public IInputMethodWrapper(AbstractInputMethodService context, InputMethod inputMethod) {
        mTarget = new WeakReference<>(context);
        mContext = context.getApplicationContext();
        mCaller = new HandlerCaller(mContext, null, this, true /*asyncHandler*/);
        mInputMethod = new WeakReference<>(inputMethod);
        mTargetSdkVersion = context.getApplicationInfo().targetSdkVersion;
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
                AbstractInputMethodService target = mTarget.get();
                if (target == null) {
                    return;
                }
                SomeArgs args = (SomeArgs)msg.obj;
                try {
                    target.dump((FileDescriptor)args.arg1,
                            (PrintWriter)args.arg2, (String[])args.arg3);
                } catch (RuntimeException e) {
                    ((PrintWriter)args.arg2).println("Exception: " + e);
                }
                synchronized (args.arg4) {
                    ((CountDownLatch)args.arg4).countDown();
                }
                args.recycle();
                return;
            }
            
            case DO_ATTACH_TOKEN: {
                inputMethod.attachToken((IBinder)msg.obj);
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
                final int missingMethods = msg.arg1;
                final boolean restarting = msg.arg2 != 0;
                final IBinder startInputToken = (IBinder) args.arg1;
                final IInputContext inputContext = (IInputContext) args.arg2;
                final EditorInfo info = (EditorInfo) args.arg3;
                final AtomicBoolean isUnbindIssued = (AtomicBoolean) args.arg4;
                final InputConnection ic = inputContext != null
                        ? new InputConnectionWrapper(
                                mTarget, inputContext, missingMethods, isUnbindIssued) : null;
                info.makeCompatible(mTargetSdkVersion);
                inputMethod.dispatchStartInputWithToken(ic, info, restarting /* restarting */,
                        startInputToken);
                args.recycle();
                return;
            }
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
            case DO_REVOKE_SESSION:
                inputMethod.revokeSession((InputMethodSession)msg.obj);
                return;
            case DO_SHOW_SOFT_INPUT:
                inputMethod.showSoftInput(msg.arg1, (ResultReceiver)msg.obj);
                return;
            case DO_HIDE_SOFT_INPUT:
                inputMethod.hideSoftInput(msg.arg1, (ResultReceiver)msg.obj);
                return;
            case DO_CHANGE_INPUTMETHOD_SUBTYPE:
                inputMethod.changeInputMethodSubtype((InputMethodSubtype)msg.obj);
                return;
        }
        Log.w(TAG, "Unhandled message code: " + msg.what);
    }

    @BinderThread
    @Override
    protected void dump(FileDescriptor fd, PrintWriter fout, String[] args) {
        AbstractInputMethodService target = mTarget.get();
        if (target == null) {
            return;
        }
        if (target.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            
            fout.println("Permission Denial: can't dump InputMethodManager from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }

        CountDownLatch latch = new CountDownLatch(1);
        mCaller.executeOrSendMessage(mCaller.obtainMessageOOOO(DO_DUMP,
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
    public void attachToken(IBinder token) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(DO_ATTACH_TOKEN, token));
    }

    @BinderThread
    @Override
    public void bindInput(InputBinding binding) {
        if (mIsUnbindIssued != null) {
            Log.e(TAG, "bindInput must be paired with unbindInput.");
        }
        mIsUnbindIssued = new AtomicBoolean();
        // This IInputContext is guaranteed to implement all the methods.
        final int missingMethodFlags = 0;
        InputConnection ic = new InputConnectionWrapper(mTarget,
                IInputContext.Stub.asInterface(binding.getConnectionToken()), missingMethodFlags,
                mIsUnbindIssued);
        InputBinding nu = new InputBinding(ic, binding);
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(DO_SET_INPUT_CONTEXT, nu));
    }

    @BinderThread
    @Override
    public void unbindInput() {
        if (mIsUnbindIssued != null) {
            // Signal the flag then forget it.
            mIsUnbindIssued.set(true);
            mIsUnbindIssued = null;
        } else {
            Log.e(TAG, "unbindInput must be paired with bindInput.");
        }
        mCaller.executeOrSendMessage(mCaller.obtainMessage(DO_UNSET_INPUT_CONTEXT));
    }

    @BinderThread
    @Override
    public void startInput(IBinder startInputToken, IInputContext inputContext,
            @InputConnectionInspector.MissingMethodFlags final int missingMethods,
            EditorInfo attribute, boolean restarting) {
        if (mIsUnbindIssued == null) {
            Log.e(TAG, "startInput must be called after bindInput.");
            mIsUnbindIssued = new AtomicBoolean();
        }
        mCaller.executeOrSendMessage(mCaller.obtainMessageIIOOOO(DO_START_INPUT,
                missingMethods, restarting ? 1 : 0, startInputToken, inputContext, attribute,
                mIsUnbindIssued));
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
    public void revokeSession(IInputMethodSession session) {
        try {
            InputMethodSession ls = ((IInputMethodSessionWrapper)
                    session).getInternalInputMethodSession();
            if (ls == null) {
                Log.w(TAG, "Session is already finished: " + session);
                return;
            }
            mCaller.executeOrSendMessage(mCaller.obtainMessageO(DO_REVOKE_SESSION, ls));
        } catch (ClassCastException e) {
            Log.w(TAG, "Incoming session not of correct type: " + session, e);
        }
    }

    @BinderThread
    @Override
    public void showSoftInput(int flags, ResultReceiver resultReceiver) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageIO(DO_SHOW_SOFT_INPUT,
                flags, resultReceiver));
    }

    @BinderThread
    @Override
    public void hideSoftInput(int flags, ResultReceiver resultReceiver) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageIO(DO_HIDE_SOFT_INPUT,
                flags, resultReceiver));
    }

    @BinderThread
    @Override
    public void changeInputMethodSubtype(InputMethodSubtype subtype) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(DO_CHANGE_INPUTMETHOD_SUBTYPE,
                subtype));
    }
}
