/*
 * Copyright (C) 2007-2008 The Android Open Source Project
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.view.inputmethod;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import com.android.internal.view.IInputConnectionWrapper;
import com.android.internal.view.IInputContext;
import com.android.internal.view.IInputMethodCallback;
import com.android.internal.view.IInputMethodClient;
import com.android.internal.view.IInputMethodManager;
import com.android.internal.view.IInputMethodSession;
import com.android.internal.view.InputBindResult;

import java.util.List;

/**
 * Public interface to the global input method manager.  You can retrieve
 * an instance of this interface with
 * {@link Context#getSystemService(String) Context.getSystemService()}.
 */
public final class InputMethodManager {
    static final boolean DEBUG = false;
    static final String TAG = "InputMethodManager";

    /**
     * The package name of the build-in input method.
     * {@hide}
     */
    public static final String BUILDIN_INPUTMETHOD_PACKAGE = "android.text.inputmethod";

    static final Object mInstanceSync = new Object();
    static InputMethodManager mInstance;
    
    final IInputMethodManager mService;
    final Looper mMainLooper;
    
    // For scheduling work on the main thread.  This also serves as our
    // global lock.
    final H mH;
    
    // The currently active input connection.
    final MutableInputConnectionWrapper mInputConnectionWrapper;
    final IInputContext mIInputContext;

    /**
     * True if this input method client is active, initially false.
     */
    boolean mActive = false;
    
    /**
     * The current base input connection, used when mActive is true.
     */
    InputConnection mCurrentInputConnection;

    // -----------------------------------------------------------
    
    /**
     * This is the view that should currently be served by an input method,
     * regardless of the state of setting that up.
     */
    View mServedView;
    /**
     * For evaluating the state after a focus change, this is the view that
     * had focus.
     */
    View mLastServedView;
    /**
     * This is set when we are in the process of connecting, to determine
     * when we have actually finished.
     */
    boolean mServedConnecting;
    /**
     * This is non-null when we have connected the served view; it holds
     * the attributes that were last retrieved from the served view and given
     * to the input connection.
     */
    EditorInfo mCurrentTextBoxAttribute;
    /**
     * The InputConnection that was last retrieved from the served view.
     */
    InputConnection mServedInputConnection;
    /**
     * The completions that were last provided by the served view.
     */
    CompletionInfo[] mCompletions;
    
    // Cursor position on the screen.
    Rect mTmpCursorRect = new Rect();
    Rect mCursorRect = new Rect();
    int mCursorSelStart;
    int mCursorSelEnd;

    // -----------------------------------------------------------
    
    /**
     * Sequence number of this binding, as returned by the server.
     */
    int mBindSequence = -1;
    /**
     * ID of the method we are bound to.
     */
    String mCurId;
    /**
     * The actual instance of the method to make calls on it.
     */
    IInputMethodSession mCurMethod;

    // -----------------------------------------------------------
    
    static final int MSG_CHECK_FOCUS = 1;
    
    class H extends Handler {
        H(Looper looper) {
            super(looper);
        }
        
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_CHECK_FOCUS:
                    checkFocus();
                    return;
            }
        }
    }
    
    static class NoOpInputConnection implements InputConnection {

        public boolean clearMetaKeyStates(int states) {
            return false;
        }

        public boolean commitCompletion(CompletionInfo text) {
            return false;
        }

        public boolean commitText(CharSequence text, int newCursorPosition) {
            return false;
        }

        public boolean deleteSurroundingText(int leftLength, int rightLength) {
            return false;
        }

        public int getCursorCapsMode(int reqModes) {
            return 0;
        }

        public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
            return null;
        }

        public CharSequence getTextAfterCursor(int n) {
            return null;
        }

        public CharSequence getTextBeforeCursor(int n) {
            return null;
        }

        public boolean hideStatusIcon() {
            return false;
        }

        public boolean performPrivateCommand(String action, Bundle data) {
            return false;
        }

        public boolean sendKeyEvent(KeyEvent event) {
            return false;
        }

        public boolean setComposingText(CharSequence text, int newCursorPosition) {
            return false;
        }

        public boolean showStatusIcon(String packageName, int resId) {
            return false;
        }
    }
    
    final NoOpInputConnection mNoOpInputConnection = new NoOpInputConnection();

    final IInputMethodClient.Stub mClient = new IInputMethodClient.Stub() {
        public void setUsingInputMethod(boolean state) {
            
        }
        
        public void onBindMethod(InputBindResult res) {
            synchronized (mH) {
                if (mBindSequence < 0 || mBindSequence != res.sequence) {
                    Log.w(TAG, "Ignoring onBind: cur seq=" + mBindSequence
                            + ", given seq=" + res.sequence);
                    return;
                }
                
                mCurMethod = res.method;
                mCurId = res.id;
                mBindSequence = res.sequence;
            }
            startInputInner();
        }
        
        public void onUnbindMethod(int sequence) {
            synchronized (mH) {
                if (mBindSequence == sequence) {
                    if (false) {
                        // XXX the server has already unbound!
                        if (mCurMethod != null && mCurrentTextBoxAttribute != null) {
                            try {
                                mCurMethod.finishInput();
                            } catch (RemoteException e) {
                                Log.w(TAG, "IME died: " + mCurId, e);
                            }
                        }
                    }
                    clearBindingLocked();
                    
                    // If we were actively using the last input method, then
                    // we would like to re-connect to the next input method.
                    if (mServedView != null && mServedView.isFocused()) {
                        mServedConnecting = true;
                    }
                }
                startInputInner();
            }
        }
        
        public void setActive(boolean active) {
            mActive = active;
            mInputConnectionWrapper.setBaseInputConnection(active
                    ? mCurrentInputConnection : mNoOpInputConnection);
        }
    };    
    
    final InputConnection mDummyInputConnection = new BaseInputConnection(this) {
        public boolean commitText(CharSequence text, int newCursorPosition) {
            return false;
        }
        public boolean commitCompletion(CompletionInfo text) {
            return false;
        }
        public boolean deleteSurroundingText(int leftLength, int rightLength) {
            return false;
        }
        public ExtractedText getExtractedText(ExtractedTextRequest request,
                int flags) {
            return null;
        }
        public CharSequence getTextAfterCursor(int n) {
            return null;
        }
        public CharSequence getTextBeforeCursor(int n) {
            return null;
        }
        public int getCursorCapsMode(int reqModes) {
            return 0;
        }
        public boolean clearMetaKeyStates(int states) {
            return false;
        }
        public boolean performPrivateCommand(String action, Bundle data) {
            return false;
        }
        public boolean setComposingText(CharSequence text, int newCursorPosition) {
            return false;
        }
    };
    
    InputMethodManager(IInputMethodManager service, Looper looper) {
        mService = service;
        mMainLooper = looper;
        mH = new H(looper);
        mInputConnectionWrapper = new MutableInputConnectionWrapper(mNoOpInputConnection);
        mIInputContext = new IInputConnectionWrapper(looper,
                mInputConnectionWrapper);
        setCurrentInputConnection(mDummyInputConnection);
        
        if (mInstance == null) {
            mInstance = this;
        }
    }

    /**
     * Retrieve the global InputMethodManager instance, creating it if it
     * doesn't already exist.
     * @hide
     */
    static public InputMethodManager getInstance(Context context) {
        synchronized (mInstanceSync) {
            if (mInstance != null) {
                return mInstance;
            }
            IBinder b = ServiceManager.getService(Context.INPUT_METHOD_SERVICE);
            IInputMethodManager service = IInputMethodManager.Stub.asInterface(b);
            mInstance = new InputMethodManager(service, context.getMainLooper());
        }
        return mInstance;
    }
    
    /**
     * Private optimization: retrieve the global InputMethodManager instance,
     * if it exists.
     * @hide
     */
    static public InputMethodManager peekInstance() {
        return mInstance;
    }
    
    /** @hide */
    public IInputMethodClient getClient() {
        return mClient;
    }
    
    /** @hide */
    public IInputContext getInputContext() {
        return mIInputContext;
    }
    
    public List<InputMethodInfo> getInputMethodList() {
        try {
            return mService.getInputMethodList();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    public List<InputMethodInfo> getEnabledInputMethodList() {
        try {
            return mService.getEnabledInputMethodList();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    public void updateStatusIcon(int iconId, String iconPackage) {
        try {
            mService.updateStatusIcon(iconId, iconPackage);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Return true if the given view is the currently active view for the
     * input method.
     */
    public boolean isActive(View view) {
        synchronized (mH) {
            return mServedView == view && mCurrentTextBoxAttribute != null;
        }
    }
    
    /**
     * Return true if any view is currently active in the input method.
     */
    public boolean isActive() {
        synchronized (mH) {
            return mServedView != null && mCurrentTextBoxAttribute != null;
        }
    }
    
    /**
     * Return true if the currently served view is accepting full text edits.
     * If false, it has no input connection, so can only handle raw key events.
     */
    public boolean isAcceptingText() {
        return mServedInputConnection != null;
    }

    /**
     * Reset all of the state associated with being bound to an input method.
     */
    void clearBindingLocked() {
        clearConnectionLocked();
        mBindSequence = -1;
        mCurId = null;
        mCurMethod = null;
    }
    
    /**
     * Record the desired input connection, but only set it if mActive is true.
     */
    void setCurrentInputConnection(InputConnection connection) {
        mCurrentInputConnection = connection;
        mInputConnectionWrapper.setBaseInputConnection(mActive
                ? connection : mNoOpInputConnection);
    }
    
    /**
     * Reset all of the state associated with a served view being connected
     * to an input method
     */
    void clearConnectionLocked() {
        mCurrentTextBoxAttribute = null;
        mServedInputConnection = null;
        setCurrentInputConnection(mDummyInputConnection);
    }
    
    /**
     * Disconnect any existing input connection, clearing the served view.
     */
    void finishInputLocked() {
        synchronized (mH) {
            if (mServedView != null) {
                if (DEBUG) Log.v(TAG, "FINISH INPUT: " + mServedView);
                updateStatusIcon(0, null);
                
                if (mCurrentTextBoxAttribute != null) {
                    try {
                        mService.finishInput(mClient);
                    } catch (RemoteException e) {
                    }
                }
                
                mServedView = null;
                mCompletions = null;
                mServedConnecting = false;
                clearConnectionLocked();
            }
        }
    }
    
    public void displayCompletions(View view, CompletionInfo[] completions) {
        synchronized (mH) {
            if (mServedView != view) {
                return;
            }
            
            mCompletions = completions;
            if (mCurMethod != null) {
                try {
                    mCurMethod.displayCompletions(mCompletions);
                } catch (RemoteException e) {
                }
            }
        }
    }
    
    public void updateExtractedText(View view, int token, ExtractedText text) {
        synchronized (mH) {
            if (mServedView != view) {
                return;
            }
            
            if (mCurMethod != null) {
                try {
                    mCurMethod.updateExtractedText(token, text);
                } catch (RemoteException e) {
                }
            }
        }
    }
    
    /**
     * Explicitly request that the current input method's soft input area be
     * shown to the user, if needed.  Call this if the user interacts with
     * your view in such a way that they have expressed they would like to
     * start performing input into it.
     * 
     * @param view The currently focused view, which would like to receive
     * soft keyboard input.
     */
    public void showSoftInput(View view) {
        synchronized (mH) {
            if (mServedView != view) {
                return;
            }

            try {
                mService.showSoftInput(mClient);
            } catch (RemoteException e) {
            }
        }
    }
    
    /**
     * Request to hide the soft input window from the context of the window
     * that is currently accepting input.  This should be called as a result
     * of the user doing some actually than fairly explicitly requests to
     * have the input window hidden.
     * 
     * @param windowToken The token of the window that is making the request,
     * as returned by {@link View#getWindowToken() View.getWindowToken()}.
     */
    public void hideSoftInputFromWindow(IBinder windowToken) {
        synchronized (mH) {
            if (mServedView == null || mServedView.getWindowToken() != windowToken) {
                return;
            }

            try {
                mService.hideSoftInput(mClient);
            } catch (RemoteException e) {
            }
        }
    }
    
    /**
     * If the input method is currently connected to the given view,
     * restart it with its new contents.  You should call this when the text
     * within your view changes outside of the normal input method or key
     * input flow, such as when an application calls TextView.setText().
     * 
     * @param view The view whose text has changed.
     */
    public void restartInput(View view) {
        synchronized (mH) {
            if (mServedView != view) {
                return;
            }
            
            mServedConnecting = true;
        }
        
        startInputInner();
    }
    
    void startInputInner() {
        final View view;
        synchronized (mH) {
            view = mServedView;
            
            // Make sure we have a window token for the served view.
            if (DEBUG) Log.v(TAG, "Starting input: view=" + view);
            if (view == null) {
                if (DEBUG) Log.v(TAG, "ABORT input: no served view!");
                return;
            }
        }
        
        // Now we need to get an input connection from the served view.
        // This is complicated in a couple ways: we can't be holding our lock
        // when calling out to the view, and we need to make sure we call into
        // the view on the same thread that is driving its view hierarchy.
        Handler vh = view.getHandler();
        if (vh == null) {
            // If the view doesn't have a handler, something has changed out
            // from under us, so just bail.
            if (DEBUG) Log.v(TAG, "ABORT input: no handler for view!");
            return;
        }
        if (vh.getLooper() != Looper.myLooper()) {
            // The view is running on a different thread than our own, so
            // we need to reschedule our work for over there.
            if (DEBUG) Log.v(TAG, "Starting input: reschedule to view thread");
            vh.post(new Runnable() {
                public void run() {
                    startInputInner();
                }
            });
        }
        
        // Okay we are now ready to call into the served view and have it
        // do its stuff.
        // Life is good: let's hook everything up!
        EditorInfo tba = new EditorInfo();
        InputConnection ic = view.createInputConnection(tba);
        if (DEBUG) Log.v(TAG, "Starting input: tba=" + tba + " ic=" + ic);
        
        synchronized (mH) {
            // Now that we are locked again, validate that our state hasn't
            // changed.
            if (mServedView != view || !mServedConnecting) {
                // Something else happened, so abort.
                if (DEBUG) Log.v(TAG, 
                        "Starting input: finished by someone else (view="
                        + mServedView + " conn=" + mServedConnecting + ")");
                return;
            }
            
            // If we already have a text box, then this view is already
            // connected so we want to restart it.
            final boolean initial = mCurrentTextBoxAttribute == null;
            
            // Hook 'em up and let 'er rip.
            mCurrentTextBoxAttribute = tba;
            mServedConnecting = false;
            mServedInputConnection = ic;
            if (ic != null) {
                mCursorSelStart = tba.initialSelStart;
                mCursorSelEnd = tba.initialSelEnd;
                mCursorRect.setEmpty();
                setCurrentInputConnection(ic);
            } else {
                setCurrentInputConnection(mDummyInputConnection);
            }
            
            try {
                if (DEBUG) Log.v(TAG, "START INPUT: " + view + " ic="
                        + ic + " tba=" + tba);
                InputBindResult res = mService.startInput(mClient, tba, initial,
                        mCurMethod == null);
                if (DEBUG) Log.v(TAG, "Starting input: Bind result=" + res);
                if (res != null) {
                    if (res.id != null) {
                        mBindSequence = res.sequence;
                        mCurMethod = res.method;
                    } else {
                        // This means there is no input method available.
                        if (DEBUG) Log.v(TAG, "ABORT input: no input method!");
                        return;
                    }
                }
                if (mCurMethod != null && mCompletions != null) {
                    try {
                        mCurMethod.displayCompletions(mCompletions);
                    } catch (RemoteException e) {
                    }
                }
            } catch (RemoteException e) {
                Log.w(TAG, "IME died: " + mCurId, e);
            }
        }
    }

    /**
     * When the focused window is dismissed, this method is called to finish the
     * input method started before.
     * @hide
     */
    public void windowDismissed(IBinder appWindowToken) {
        synchronized (mH) {
            if (mServedView != null &&
                    mServedView.getWindowToken() == appWindowToken) {
                finishInputLocked();
            }
        }
    }

    /**
     * Call this when a view receives focus.
     * @hide
     */
    public void focusIn(View view) {
        synchronized (mH) {
            if (DEBUG) Log.v(TAG, "focusIn: " + view);
            // Okay we have a new view that is being served.
            mServedView = view;
            mCompletions = null;
            mServedConnecting = true;
        }
        
        startInputInner();
    }

    /**
     * Call this when a view loses focus.
     * @hide
     */
    public void focusOut(View view) {
        synchronized (mH) {
            if (DEBUG) Log.v(TAG, "focusOut: " + view
                    + " mServedView=" + mServedView
                    + " winFocus=" + view.hasWindowFocus());
            if (mServedView == view && view.hasWindowFocus()) {
                mLastServedView = view;
                mH.removeMessages(MSG_CHECK_FOCUS);
                mH.sendEmptyMessage(MSG_CHECK_FOCUS);
            }
        }
    }

    void checkFocus() {
        synchronized (mH) {
            if (DEBUG) Log.v(TAG, "checkFocus: view=" + mServedView
                    + " last=" + mLastServedView);
            if (mServedView == mLastServedView) {
                finishInputLocked();
                // In this case, we used to have a focused view on the window,
                // but no longer do.  We should make sure the input method is
                // no longer shown, since it serves no purpose.
                closeCurrentInput();
            }
            mLastServedView = null;
        }
    }
    
    void closeCurrentInput() {
        try {
            mService.hideSoftInput(mClient);
        } catch (RemoteException e) {
        }
    }
    
    /**
     * Called by ViewRoot the first time it gets window focus.
     */
    public void onWindowFocus(View focusedView, int softInputMode,
            boolean first, int windowFlags) {
        synchronized (mH) {
            if (DEBUG) Log.v(TAG, "onWindowFocus: " + focusedView
                    + " softInputMode=" + softInputMode
                    + " first=" + first + " flags=#"
                    + Integer.toHexString(windowFlags));
            try {
                mService.windowGainedFocus(mClient, focusedView != null,
                        softInputMode, first, windowFlags);
            } catch (RemoteException e) {
            }
        }
    }
    
    /**
     * Report the current selection range.
     */
    public void updateSelection(View view, int selStart, int selEnd) {
        synchronized (mH) {
            if (mServedView != view || mCurrentTextBoxAttribute == null
                    || mCurMethod == null) {
                return;
            }
            
            if (mCursorSelStart != selStart || mCursorSelEnd != selEnd) {
                if (DEBUG) Log.d(TAG, "updateSelection");

                try {
                    if (DEBUG) Log.v(TAG, "SELECTION CHANGE: " + mCurMethod);
                    mCurMethod.updateSelection(mCursorSelStart, mCursorSelEnd,
                            selStart, selEnd);
                    mCursorSelStart = selStart;
                    mCursorSelEnd = selEnd;
                } catch (RemoteException e) {
                    Log.w(TAG, "IME died: " + mCurId, e);
                }
            }
        }
    }

    /**
     * Returns true if the current input method wants to watch the location
     * of the input editor's cursor in its window.
     */
    public boolean isWatchingCursor(View view) {
        return false;
    }
    
    /**
     * Report the current cursor location in its window.
     */
    public void updateCursor(View view, int left, int top, int right, int bottom) {
        synchronized (mH) {
            if (mServedView != view || mCurrentTextBoxAttribute == null
                    || mCurMethod == null) {
                return;
            }
            
            mTmpCursorRect.set(left, top, right, bottom);
            if (!mCursorRect.equals(mTmpCursorRect)) {
                if (DEBUG) Log.d(TAG, "updateCursor");

                try {
                    if (DEBUG) Log.v(TAG, "CURSOR CHANGE: " + mCurMethod);
                    mCurMethod.updateCursor(mTmpCursorRect);
                    mCursorRect.set(mTmpCursorRect);
                } catch (RemoteException e) {
                    Log.w(TAG, "IME died: " + mCurId, e);
                }
            }
        }
    }

    /**
     * Force switch to a new input method component.  This can only be called
     * from the currently active input method, as validated by the given token.
     * @param token Supplies the identifying token given to an input method
     * when it was started, which allows it to perform this operation on
     * itself.
     * @param id The unique identifier for the new input method to be switched to.
     */
    public void setInputMethod(IBinder token, String id) {
        try {
            mService.setInputMethod(token, id);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }
    
    /**
     * Close/hide the input method's soft input area, so the user no longer
     * sees it or can interact with it.  This can only be called
     * from the currently active input method, as validated by the given token.
     * @param token Supplies the identifying token given to an input method
     * when it was started, which allows it to perform this operation on
     * itself.
     */
    public void hideSoftInputFromInputMethod(IBinder token) {
        try {
            mService.hideMySoftInput(token);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }
    
    /**
     * @hide
     */
    public void dispatchKeyEvent(Context context, int seq, KeyEvent key,
            IInputMethodCallback callback) {
        synchronized (mH) {
            if (DEBUG) Log.d(TAG, "dispatchKeyEvent");
    
            if (mCurMethod == null || mCurrentTextBoxAttribute == null) {
                try {
                    callback.finishedEvent(seq, false);
                } catch (RemoteException e) {
                }
                return;
            }
    
            if (key.getAction() == KeyEvent.ACTION_DOWN
                    && key.getKeyCode() == KeyEvent.KEYCODE_SYM) {
                showInputMethodPicker();
                try {
                    callback.finishedEvent(seq, true);
                } catch (RemoteException e) {
                }
                return;
            }
            try {
                if (DEBUG) Log.v(TAG, "DISPATCH KEY: " + mCurMethod);
                mCurMethod.dispatchKeyEvent(seq, key, callback);
            } catch (RemoteException e) {
                Log.w(TAG, "IME died: " + mCurId + " dropping: " + key, e);
                try {
                    callback.finishedEvent(seq, false);
                } catch (RemoteException ex) {
                }
            }
        }
    }

    /**
     * @hide
     */
    void dispatchTrackballEvent(Context context, int seq, MotionEvent motion,
            IInputMethodCallback callback) {
        synchronized (mH) {
            if (DEBUG) Log.d(TAG, "dispatchTrackballEvent");
    
            if (mCurMethod == null || mCurrentTextBoxAttribute == null) {
                try {
                    callback.finishedEvent(seq, false);
                } catch (RemoteException e) {
                }
                return;
            }
    
            try {
                if (DEBUG) Log.v(TAG, "DISPATCH TRACKBALL: " + mCurMethod);
                mCurMethod.dispatchTrackballEvent(seq, motion, callback);
            } catch (RemoteException e) {
                Log.w(TAG, "IME died: " + mCurId + " dropping trackball: " + motion, e);
                try {
                    callback.finishedEvent(seq, false);
                } catch (RemoteException ex) {
                }
            }
        }
    }
    
    public void showInputMethodPicker() {
        synchronized (mH) {
            try {
                mService.showInputMethodPickerFromClient(mClient);
            } catch (RemoteException e) {
                Log.w(TAG, "IME died: " + mCurId, e);
            }
        }
    }
}
