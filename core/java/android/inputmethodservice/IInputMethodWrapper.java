package android.inputmethodservice;

import com.android.internal.os.HandlerCaller;
import com.android.internal.view.IInputContext;
import com.android.internal.view.IInputMethod;
import com.android.internal.view.IInputMethodCallback;
import com.android.internal.view.IInputMethodSession;
import com.android.internal.view.InputConnectionWrapper;

import android.content.Context;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputBinding;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethod;
import android.view.inputmethod.InputMethodSession;

/**
 * Implements the internal IInputMethod interface to convert incoming calls
 * on to it back to calls on the public InputMethod interface, scheduling
 * them on the main thread of the process.
 */
class IInputMethodWrapper extends IInputMethod.Stub
        implements HandlerCaller.Callback {
    private static final String TAG = "InputMethodWrapper";
    private static final boolean DEBUG = false;
    
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
   
    final HandlerCaller mCaller;
    final InputMethod mInputMethod;
    
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
    
    public IInputMethodWrapper(Context context, InputMethod inputMethod) {
        mCaller = new HandlerCaller(context, this);
        mInputMethod = inputMethod;
    }

    public InputMethod getInternalInputMethod() {
        return mInputMethod;
    }

    public void executeMessage(Message msg) {
        switch (msg.what) {
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
            case DO_START_INPUT:
                mInputMethod.startInput((EditorInfo)msg.obj);
                return;
            case DO_RESTART_INPUT:
                mInputMethod.restartInput((EditorInfo)msg.obj);
                return;
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
                mInputMethod.showSoftInput(
                        msg.arg1 != 0 ? InputMethod.SHOW_EXPLICIT : 0);
                return;
            case DO_HIDE_SOFT_INPUT:
                mInputMethod.hideSoftInput();
                return;
        }
        Log.w(TAG, "Unhandled message code: " + msg.what);
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

    public void startInput(EditorInfo attribute) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(DO_START_INPUT, attribute));
    }

    public void restartInput(EditorInfo attribute) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageO(DO_RESTART_INPUT, attribute));
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
    
    public void showSoftInput(boolean explicit) {
        mCaller.executeOrSendMessage(mCaller.obtainMessageI(DO_SHOW_SOFT_INPUT,
                explicit ? 1 : 0));
    }
    
    public void hideSoftInput() {
        mCaller.executeOrSendMessage(mCaller.obtainMessage(DO_HIDE_SOFT_INPUT));
    }
}
