package android.view.inputmethod;

import android.graphics.Rect;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.android.internal.view.IInputContext;
import com.android.internal.view.IInputMethod;
import com.android.internal.view.IInputMethodCallback;
import com.android.internal.view.IInputMethodSession;
import com.android.internal.view.InputConnectionWrapper;

/**
 * This is the default input method that runs in the same context of the
 * application that requests text input. It does nothing but returns false for
 * any key events, so that all key events will be processed by the key listener
 * of the focused text box.
 * {@hide}
 */
public class DefaultInputMethod implements InputMethod, InputMethodSession {
    private static IInputMethod sInstance = new SimpleInputMethod(
            new DefaultInputMethod());

    private static InputMethodInfo sProperty = new InputMethodInfo(
            "android.text.inputmethod", DefaultInputMethod.class.getName(),
            "Default", "android.text.inputmethod.defaultImeSettings");

    private InputConnection mInputConnection;

    public static IInputMethod getInstance() {
        return sInstance;
    }

    public static InputMethodInfo getMetaInfo() {
        return sProperty;
    }

    public void bindInput(InputBinding binding) {
        mInputConnection = binding.getConnection();
    }

    public void unbindInput() {
    }

    public void createSession(SessionCallback callback) {
        callback.sessionCreated(this);
    }

    public void setSessionEnabled(InputMethodSession session, boolean enabled) {
    }

    public void revokeSession(InputMethodSession session) {
    }

    public void finishInput() {
        mInputConnection.hideStatusIcon();
    }

    public void displayCompletions(CompletionInfo[] completions) {
    }
    
    public void updateExtractedText(int token, ExtractedText text) {
    }
    
    public void updateSelection(int oldSelStart, int oldSelEnd,
            int newSelStart, int newSelEnd, int candidatesStart, int candidatesEnd) {
    }

    public void updateCursor(Rect newCursor) {
    }

    public void dispatchKeyEvent(int seq, KeyEvent event, EventCallback callback) {
        callback.finishedEvent(seq, false);
    }

    public void dispatchTrackballEvent(int seq, MotionEvent event, EventCallback callback) {
        callback.finishedEvent(seq, false);
    }

    public void restartInput(EditorInfo attribute) {
    }

    public void attachToken(IBinder token) {
    }

    public void startInput(EditorInfo attribute) {
        mInputConnection
                .showStatusIcon("android", com.android.internal.R.drawable.ime_qwerty);
    }

    public void appPrivateCommand(String action, Bundle data) {
    }
    
    public void hideSoftInput() {
    }

    public void showSoftInput() {
    }
}

// ----------------------------------------------------------------------

class SimpleInputMethod extends IInputMethod.Stub {
    final InputMethod mInputMethod;

    static class Session extends IInputMethodSession.Stub {
        final InputMethodSession mSession;
        
        Session(InputMethodSession session) {
            mSession = session;
        }
        
        public void finishInput() {
            mSession.finishInput();
        }
        
        public void updateSelection(int oldSelStart, int oldSelEnd,
                int newSelStart, int newSelEnd, int candidatesStart, int candidatesEnd) {
            mSession.updateSelection(oldSelStart, oldSelEnd,
                    newSelStart, newSelEnd, candidatesStart, candidatesEnd);
        }

        public void updateCursor(Rect newCursor) {
            mSession.updateCursor(newCursor);
        }

        static class InputMethodEventCallbackWrapper implements InputMethodSession.EventCallback {
            final IInputMethodCallback mCb;
            InputMethodEventCallbackWrapper(IInputMethodCallback cb) {
                mCb = cb;
            }
            public void finishedEvent(int seq, boolean handled) {
                try {
                    mCb.finishedEvent(seq, handled);
                } catch (RemoteException e) {
                }
            }
        }
        
        public void dispatchKeyEvent(int seq, KeyEvent event, IInputMethodCallback callback) {
            mSession.dispatchKeyEvent(seq, event,
                    new InputMethodEventCallbackWrapper(callback));
        }

        public void dispatchTrackballEvent(int seq, MotionEvent event, IInputMethodCallback callback) {
            mSession.dispatchTrackballEvent(seq, event,
                    new InputMethodEventCallbackWrapper(callback));
        }
        
        public void displayCompletions(CompletionInfo[] completions) {
            mSession.displayCompletions(completions);
        }
        
        public void updateExtractedText(int token, ExtractedText text) {
            mSession.updateExtractedText(token, text);
        }
        
        public void appPrivateCommand(String action, Bundle data) {
            mSession.appPrivateCommand(action, data);
        }
    }
    
    public SimpleInputMethod(InputMethod inputMethod) {
        mInputMethod = inputMethod;
    }

    public InputMethod getInternalInputMethod() {
        return mInputMethod;
    }

    public void attachToken(IBinder token) {
        mInputMethod.attachToken(token);
    }
    
    public void bindInput(InputBinding binding) {
        InputConnectionWrapper ic = new InputConnectionWrapper(
                IInputContext.Stub.asInterface(binding.getConnectionToken()));
        InputBinding nu = new InputBinding(ic, binding);
        mInputMethod.bindInput(nu);
    }

    public void unbindInput() {
        mInputMethod.unbindInput();
    }

    public void restartInput(EditorInfo attribute) {
        mInputMethod.restartInput(attribute);
    }

    public void startInput(EditorInfo attribute) {
        mInputMethod.startInput(attribute);
    }

    static class InputMethodSessionCallbackWrapper implements InputMethod.SessionCallback {
        final IInputMethodCallback mCb;
        InputMethodSessionCallbackWrapper(IInputMethodCallback cb) {
            mCb = cb;
        }
        
        public void sessionCreated(InputMethodSession session) {
            try {
                mCb.sessionCreated(new Session(session));
            } catch (RemoteException e) {
            }
        }
    }
    
    public void createSession(IInputMethodCallback callback) throws RemoteException {
        mInputMethod.createSession(new InputMethodSessionCallbackWrapper(callback));
    }

    public void setSessionEnabled(IInputMethodSession session, boolean enabled) throws RemoteException {
        try {
            InputMethodSession ls = ((Session)session).mSession;
            mInputMethod.setSessionEnabled(ls, enabled);
        } catch (ClassCastException e) {
            Log.w("SimpleInputMethod", "Incoming session not of correct type: " + session, e);
        }
    }

    public void revokeSession(IInputMethodSession session) throws RemoteException {
        try {
            InputMethodSession ls = ((Session)session).mSession;
            mInputMethod.revokeSession(ls);
        } catch (ClassCastException e) {
            Log.w("SimpleInputMethod", "Incoming session not of correct type: " + session, e);
        }
    }

    public void showSoftInput() {
    }
    
    public void hideSoftInput() {
    }
}
