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

import com.android.internal.os.HandlerCaller;
import com.android.internal.view.IInputContext;
import com.android.internal.view.IInputMethod;
import com.android.internal.view.IInputMethodCallback;
import com.android.internal.view.IInputMethodSession;
import com.android.internal.view.InputConnectionWrapper;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.util.Log;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputBinding;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethod;
import android.view.inputmethod.InputMethodSession;

import java.io.FileDescriptor;
import java.io.PrintWriter;
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
    private static final boolean DEBUG = false;
    
    private static final int DO_DUMP = 1;
    private static final int DO_ATTACH_TOKEN = 10;
    private static final int DO_SET_INPUT_CONTEXT = 20;
    private static final int DO_UNSET_INPUT_CONTEXT = 30;
    private static final int DO_START_INPUT = 32;
    private static final int DO_RESTART_INPUT = 34;
    private static final int DO_CREATE_SESSION = 40;
    private static final int DO_SET_SESSION_ENABLED = 45;
    private static final int DO_REVOKE_SESSION = 50;
    private static final int DO_SHOW_SOFT_INPUT = 60;
    private static final int DO_HIDE_SOFT_INPUT = 70;
   
    final AbstractInputMethodService mTarget;
    final HandlerCaller mCaller;
    final InputMethod mInputMethod;
    
    static class Notifier {
        boolean notified;
    }
    
    // NOTE: we should have a cache of these.
    static class InputMethodSessionCallbackWrapper implements InputMethod.SessionCallback {
        final Context mContext;
        final IInputMethodCallback mCb;
        InputMethodSessionCallbackWrapper(Context context, IInputMethodCallback cb) {
            mContext = context;
            mCb = cb;
        }
        public void sessionCreated(InputMethodSession session) {
            try {
                if (session != null) {
                    IInputMethodSessionWrapper wrap =
                            new IInputMethodSessionWrapper(mContext, session);
                    mCb.sessionCreated(wrap);
                } else {
                    mCb.sessionCreated(null);
                }
            } catch (RemoteException e) {
            }
        }
    }
    
    public IInputMethodWrapper(AbstractInputMethodService context,
            InputMethod inputMethod) {
        mTarget = context;
        mCaller = new HandlerCaller(context, this);
        mInputMethod = inputMethod;
    }

    public InputMethod getInternalInputMethod() {
        return mInputMethod;
    }

    public void executeMessage(Message msg) {
        switch (msg.what) {
            case DO_DUMP: {
                HandlerCaller.SomeArgs args = (HandlerCaller.SomeArgs)msg.obj;
                try {
                    mTarget.dump((FileDescriptor)args.arg1,
                            (PrintWriter)args.arg2, (String[])args.arg3);
                } catch (RuntimeException e) {
                    ((PrintWriter)args.arg2).println("Exception: " + e);
                }
                synchronized (args.arg4) {
                    ((CountDownLatch)args.arg4).countDown();
                }
                return;
            }
            
            case DO_ATTACH_TOKEN: {
                mInputMethod.attachToken((IBinder)msg.obj);
                return;
            }
            case DO_SET_INPUT_CONTEXT: {
                mInputMethod.bindInput((InputBinding)msg.obj);
                return;
            }
            case DO_UNSET_INPUT_CONTEXT:
                mInputMethod.unbindInput();
                return;
            case DO_START_INPUT: {
                HandlerCaller.SomeArgs args = (HandlerCaller.SomeArgs)msg.obj;
                IInputContext inputContext = (IInputContext)args.arg1;
                InputConnection ic = inputContext != null
                        ? new InputConnectionWrapper(inputContext) : null;
                mInputMethod.startInput(ic, (EditorInfo)args.arg2);
                return;
            }
            case DO_RESTART_INPUT: {
                HandlerCaller.SomeArgs args = (HandlerCaller.SomeArgs)msg.obj;
                IInputContext inputContext = (IInputContext)args.arg1;
                InputConnection ic = inputContext != null
                        ? new InputConnectionWrapper(inputContext) : null;
                mInputMethod.restartInput(ic, (EditorInfo)args.arg2);
                return;
            }
            case DO_CREATE_SESSION: {
                mInputMethod.createSession(new InputMethodSessionCallbackWrapper(
                        mCaller.mContext, (IInputMethodCallback)msg.obj));
                return;
            }
            case DO_SET_SESSION_ENABLED:
                mInputMethod.setSessionEnabled((InputMethodSession)msg.obj,
                        msg.arg1 != 0);
                return;
            case DO_REVOKE_SESSION:
                mInputMethod.revokeSession((InputMethodSession)msg.obj);
                return;
            case DO_SHOW_SOFT_INPUT:
                mInputMethod.showSoftInput(msg.arg1, (ResultReceiver)msg.obj);
                return;
            case DO_HIDE_SOFT_INPUT:
                mInputMethod.hideSoftInput(msg.arg1, (ResultReceiver)msg.obj);
                return;
        }
        Log.w(TAG, "Unhandled message code: " + msg.what);
    }
    
    @Override protected void dump(FileDescriptor fd, PrintWriter fout, String[] args) {
        if (mTarget.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
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

    public void attachToken(IBinder token) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(DO_ATTACH_TOKEN, token));
    }
    
    public void bindInput(InputBinding binding) {
        InputConnection ic = new InputConnectionWrapper(
                IInputContext.Stub.asInterface(binding.getConnectionToken()));
        InputBinding nu = new InputBinding(ic, binding);
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(DO_SET_INPUT_CONTEXT, nu));
    }

    public void unbindInput() {
        mCaller.executeOrSendMessage(mCaller.obtainMessage(DO_UNSET_INPUT_CONTEXT));
    }

    public void startInput(IInputContext inputContext, EditorInfo attribute) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageOO(DO_START_INPUT,
                inputContext, attribute));
    }

    public void restartInput(IInputContext inputContext, EditorInfo attribute) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageOO(DO_RESTART_INPUT,
                inputContext, attribute));
    }

    public void createSession(IInputMethodCallback callback) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(DO_CREATE_SESSION, callback));
    }

    public void setSessionEnabled(IInputMethodSession session, boolean enabled) {
        try {
            InputMethodSession ls = ((IInputMethodSessionWrapper)
                    session).getInternalInputMethodSession();
            mCaller.executeOrSendMessage(mCaller.obtainMessageIO(
                    DO_SET_SESSION_ENABLED, enabled ? 1 : 0, ls));
        } catch (ClassCastException e) {
            Log.w(TAG, "Incoming session not of correct type: " + session, e);
        }
    }
    
    public void revokeSession(IInputMethodSession session) {
        try {
            InputMethodSession ls = ((IInputMethodSessionWrapper)
                    session).getInternalInputMethodSession();
            mCaller.executeOrSendMessage(mCaller.obtainMessageO(DO_REVOKE_SESSION, ls));
        } catch (ClassCastException e) {
            Log.w(TAG, "Incoming session not of correct type: " + session, e);
        }
    }
    
    public void showSoftInput(int flags, ResultReceiver resultReceiver) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageIO(DO_SHOW_SOFT_INPUT,
                flags, resultReceiver));
    }
    
    public void hideSoftInput(int flags, ResultReceiver resultReceiver) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageIO(DO_HIDE_SOFT_INPUT,
                flags, resultReceiver));
    }
}
