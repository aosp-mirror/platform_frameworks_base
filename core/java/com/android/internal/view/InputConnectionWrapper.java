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

package com.android.internal.view;

import android.annotation.AnyThread;
import android.annotation.BinderThread;
import android.annotation.NonNull;
import android.annotation.UnsupportedAppUsage;
import android.inputmethodservice.AbstractInputMethodService;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.CorrectionInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionInspector;
import android.view.inputmethod.InputConnectionInspector.MissingMethodFlags;
import android.view.inputmethod.InputContentInfo;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;

public class InputConnectionWrapper implements InputConnection {
    private static final int MAX_WAIT_TIME_MILLIS = 2000;
    private final IInputContext mIInputContext;
    @NonNull
    private final WeakReference<AbstractInputMethodService> mInputMethodService;

    @MissingMethodFlags
    private final int mMissingMethods;

    /**
     * {@code true} if the system already decided to take away IME focus from the target app. This
     * can be signaled even when the corresponding signal is in the task queue and
     * {@link InputMethodService#onUnbindInput()} is not yet called back on the UI thread.
     */
    @NonNull
    private final AtomicBoolean mIsUnbindIssued;

    static class InputContextCallback extends IInputContextCallback.Stub {
        private static final String TAG = "InputConnectionWrapper.ICC";
        public int mSeq;
        public boolean mHaveValue;
        public CharSequence mTextBeforeCursor;
        public CharSequence mTextAfterCursor;
        public CharSequence mSelectedText;
        public ExtractedText mExtractedText;
        public int mCursorCapsMode;
        public boolean mRequestUpdateCursorAnchorInfoResult;
        public boolean mCommitContentResult;

        // A 'pool' of one InputContextCallback.  Each ICW request will attempt to gain
        // exclusive access to this object.
        private static InputContextCallback sInstance = new InputContextCallback();
        private static int sSequenceNumber = 1;
        
        /**
         * Returns an InputContextCallback object that is guaranteed not to be in use by
         * any other thread.  The returned object's 'have value' flag is cleared and its expected
         * sequence number is set to a new integer.  We use a sequence number so that replies that
         * occur after a timeout has expired are not interpreted as replies to a later request.
         */
        @UnsupportedAppUsage
        @AnyThread
        private static InputContextCallback getInstance() {
            synchronized (InputContextCallback.class) {
                // Return sInstance if it's non-null, otherwise construct a new callback
                InputContextCallback callback;
                if (sInstance != null) {
                    callback = sInstance;
                    sInstance = null;
                    
                    // Reset the callback
                    callback.mHaveValue = false;
                } else {
                    callback = new InputContextCallback();
                }
                
                // Set the sequence number
                callback.mSeq = sSequenceNumber++;
                return callback;
            }
        }
        
        /**
         * Makes the given InputContextCallback available for use in the future.
         */
        @UnsupportedAppUsage
        @AnyThread
        private void dispose() {
            synchronized (InputContextCallback.class) {
                // If sInstance is non-null, just let this object be garbage-collected
                if (sInstance == null) {
                    // Allow any objects being held to be gc'ed
                    mTextAfterCursor = null;
                    mTextBeforeCursor = null;
                    mExtractedText = null;
                    sInstance = this;
                }
            }
        }

        @BinderThread
        public void setTextBeforeCursor(CharSequence textBeforeCursor, int seq) {
            synchronized (this) {
                if (seq == mSeq) {
                    mTextBeforeCursor = textBeforeCursor;
                    mHaveValue = true;
                    notifyAll();
                } else {
                    Log.i(TAG, "Got out-of-sequence callback " + seq + " (expected " + mSeq
                            + ") in setTextBeforeCursor, ignoring.");
                }
            }
        }

        @BinderThread
        public void setTextAfterCursor(CharSequence textAfterCursor, int seq) {
            synchronized (this) {
                if (seq == mSeq) {
                    mTextAfterCursor = textAfterCursor;
                    mHaveValue = true;
                    notifyAll();
                } else {
                    Log.i(TAG, "Got out-of-sequence callback " + seq + " (expected " + mSeq
                            + ") in setTextAfterCursor, ignoring.");
                }
            }
        }

        @BinderThread
        public void setSelectedText(CharSequence selectedText, int seq) {
            synchronized (this) {
                if (seq == mSeq) {
                    mSelectedText = selectedText;
                    mHaveValue = true;
                    notifyAll();
                } else {
                    Log.i(TAG, "Got out-of-sequence callback " + seq + " (expected " + mSeq
                            + ") in setSelectedText, ignoring.");
                }
            }
        }

        @BinderThread
        public void setCursorCapsMode(int capsMode, int seq) {
            synchronized (this) {
                if (seq == mSeq) {
                    mCursorCapsMode = capsMode; 
                    mHaveValue = true;  
                    notifyAll();
                } else {
                    Log.i(TAG, "Got out-of-sequence callback " + seq + " (expected " + mSeq
                            + ") in setCursorCapsMode, ignoring.");
                }
            }
        }

        @BinderThread
        public void setExtractedText(ExtractedText extractedText, int seq) {
            synchronized (this) {
                if (seq == mSeq) {
                    mExtractedText = extractedText;
                    mHaveValue = true;
                    notifyAll();
                } else {
                    Log.i(TAG, "Got out-of-sequence callback " + seq + " (expected " + mSeq
                            + ") in setExtractedText, ignoring.");
                }
            }
        }

        @BinderThread
        public void setRequestUpdateCursorAnchorInfoResult(boolean result, int seq) {
            synchronized (this) {
                if (seq == mSeq) {
                    mRequestUpdateCursorAnchorInfoResult = result;
                    mHaveValue = true;
                    notifyAll();
                } else {
                    Log.i(TAG, "Got out-of-sequence callback " + seq + " (expected " + mSeq
                            + ") in setCursorAnchorInfoRequestResult, ignoring.");
                }
            }
        }

        @BinderThread
        public void setCommitContentResult(boolean result, int seq) {
            synchronized (this) {
                if (seq == mSeq) {
                    mCommitContentResult = result;
                    mHaveValue = true;
                    notifyAll();
                } else {
                    Log.i(TAG, "Got out-of-sequence callback " + seq + " (expected " + mSeq
                            + ") in setCommitContentResult, ignoring.");
                }
            }
        }

        /**
         * Waits for a result for up to {@link #MAX_WAIT_TIME_MILLIS} milliseconds.
         * 
         * <p>The caller must be synchronized on this callback object.
         */
        @AnyThread
        void waitForResultLocked() {
            long startTime = SystemClock.uptimeMillis();
            long endTime = startTime + MAX_WAIT_TIME_MILLIS;

            while (!mHaveValue) {
                long remainingTime = endTime - SystemClock.uptimeMillis();
                if (remainingTime <= 0) {
                    Log.w(TAG, "Timed out waiting on IInputContextCallback");
                    return;
                }
                try {
                    wait(remainingTime);
                } catch (InterruptedException e) {
                }
            }
        }
    }

    public InputConnectionWrapper(
            @NonNull WeakReference<AbstractInputMethodService> inputMethodService,
            IInputContext inputContext, @MissingMethodFlags final int missingMethods,
            @NonNull AtomicBoolean isUnbindIssued) {
        mInputMethodService = inputMethodService;
        mIInputContext = inputContext;
        mMissingMethods = missingMethods;
        mIsUnbindIssued = isUnbindIssued;
    }

    @AnyThread
    public CharSequence getTextAfterCursor(int length, int flags) {
        if (mIsUnbindIssued.get()) {
            return null;
        }

        CharSequence value = null;
        try {
            InputContextCallback callback = InputContextCallback.getInstance();
            mIInputContext.getTextAfterCursor(length, flags, callback.mSeq, callback);
            synchronized (callback) {
                callback.waitForResultLocked();
                if (callback.mHaveValue) {
                    value = callback.mTextAfterCursor;
                }
            }
            callback.dispose();
        } catch (RemoteException e) {
            return null;
        }
        return value;
    }

    @AnyThread
    public CharSequence getTextBeforeCursor(int length, int flags) {
        if (mIsUnbindIssued.get()) {
            return null;
        }

        CharSequence value = null;
        try {
            InputContextCallback callback = InputContextCallback.getInstance();
            mIInputContext.getTextBeforeCursor(length, flags, callback.mSeq, callback);
            synchronized (callback) {
                callback.waitForResultLocked();
                if (callback.mHaveValue) {
                    value = callback.mTextBeforeCursor;
                }
            }
            callback.dispose();
        } catch (RemoteException e) {
            return null;
        }
        return value;
    }

    @AnyThread
    public CharSequence getSelectedText(int flags) {
        if (mIsUnbindIssued.get()) {
            return null;
        }

        if (isMethodMissing(MissingMethodFlags.GET_SELECTED_TEXT)) {
            // This method is not implemented.
            return null;
        }
        CharSequence value = null;
        try {
            InputContextCallback callback = InputContextCallback.getInstance();
            mIInputContext.getSelectedText(flags, callback.mSeq, callback);
            synchronized (callback) {
                callback.waitForResultLocked();
                if (callback.mHaveValue) {
                    value = callback.mSelectedText;
                }
            }
            callback.dispose();
        } catch (RemoteException e) {
            return null;
        }
        return value;
    }

    @AnyThread
    public int getCursorCapsMode(int reqModes) {
        if (mIsUnbindIssued.get()) {
            return 0;
        }

        int value = 0;
        try {
            InputContextCallback callback = InputContextCallback.getInstance();
            mIInputContext.getCursorCapsMode(reqModes, callback.mSeq, callback);
            synchronized (callback) {
                callback.waitForResultLocked();
                if (callback.mHaveValue) {
                    value = callback.mCursorCapsMode;
                }
            }
            callback.dispose();
        } catch (RemoteException e) {
            return 0;
        }
        return value;
    }

    @AnyThread
    public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
        if (mIsUnbindIssued.get()) {
            return null;
        }

        ExtractedText value = null;
        try {
            InputContextCallback callback = InputContextCallback.getInstance();
            mIInputContext.getExtractedText(request, flags, callback.mSeq, callback);
            synchronized (callback) {
                callback.waitForResultLocked();
                if (callback.mHaveValue) {
                    value = callback.mExtractedText;
                }
            }
            callback.dispose();
        } catch (RemoteException e) {
            return null;
        }
        return value;
    }

    @AnyThread
    public boolean commitText(CharSequence text, int newCursorPosition) {
        try {
            mIInputContext.commitText(text, newCursorPosition);
            notifyUserActionIfNecessary();
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    @AnyThread
    private void notifyUserActionIfNecessary() {
        final AbstractInputMethodService inputMethodService = mInputMethodService.get();
        if (inputMethodService == null) {
            // This basically should not happen, because it's the the caller of this method.
            return;
        }
        inputMethodService.notifyUserActionIfNecessary();
    }

    @AnyThread
    public boolean commitCompletion(CompletionInfo text) {
        if (isMethodMissing(MissingMethodFlags.COMMIT_CORRECTION)) {
            // This method is not implemented.
            return false;
        }
        try {
            mIInputContext.commitCompletion(text);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    @AnyThread
    public boolean commitCorrection(CorrectionInfo correctionInfo) {
        try {
            mIInputContext.commitCorrection(correctionInfo);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    @AnyThread
    public boolean setSelection(int start, int end) {
        try {
            mIInputContext.setSelection(start, end);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    @AnyThread
    public boolean performEditorAction(int actionCode) {
        try {
            mIInputContext.performEditorAction(actionCode);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    @AnyThread
    public boolean performContextMenuAction(int id) {
        try {
            mIInputContext.performContextMenuAction(id);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    @AnyThread
    public boolean setComposingRegion(int start, int end) {
        if (isMethodMissing(MissingMethodFlags.SET_COMPOSING_REGION)) {
            // This method is not implemented.
            return false;
        }
        try {
            mIInputContext.setComposingRegion(start, end);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    @AnyThread
    public boolean setComposingText(CharSequence text, int newCursorPosition) {
        try {
            mIInputContext.setComposingText(text, newCursorPosition);
            notifyUserActionIfNecessary();
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    @AnyThread
    public boolean finishComposingText() {
        try {
            mIInputContext.finishComposingText();
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    @AnyThread
    public boolean beginBatchEdit() {
        try {
            mIInputContext.beginBatchEdit();
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    @AnyThread
    public boolean endBatchEdit() {
        try {
            mIInputContext.endBatchEdit();
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    @AnyThread
    public boolean sendKeyEvent(KeyEvent event) {
        try {
            mIInputContext.sendKeyEvent(event);
            notifyUserActionIfNecessary();
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    @AnyThread
    public boolean clearMetaKeyStates(int states) {
        try {
            mIInputContext.clearMetaKeyStates(states);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    @AnyThread
    public boolean deleteSurroundingText(int beforeLength, int afterLength) {
        try {
            mIInputContext.deleteSurroundingText(beforeLength, afterLength);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    @AnyThread
    public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
        if (isMethodMissing(MissingMethodFlags.DELETE_SURROUNDING_TEXT_IN_CODE_POINTS)) {
            // This method is not implemented.
            return false;
        }
        try {
            mIInputContext.deleteSurroundingTextInCodePoints(beforeLength, afterLength);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    @AnyThread
    public boolean reportFullscreenMode(boolean enabled) {
        // Nothing should happen when called from input method.
        return false;
    }

    @AnyThread
    public boolean performPrivateCommand(String action, Bundle data) {
        try {
            mIInputContext.performPrivateCommand(action, data);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    @AnyThread
    public boolean requestCursorUpdates(int cursorUpdateMode) {
        if (mIsUnbindIssued.get()) {
            return false;
        }

        boolean result = false;
        if (isMethodMissing(MissingMethodFlags.REQUEST_CURSOR_UPDATES)) {
            // This method is not implemented.
            return false;
        }
        try {
            InputContextCallback callback = InputContextCallback.getInstance();
            mIInputContext.requestUpdateCursorAnchorInfo(cursorUpdateMode, callback.mSeq, callback);
            synchronized (callback) {
                callback.waitForResultLocked();
                if (callback.mHaveValue) {
                    result = callback.mRequestUpdateCursorAnchorInfoResult;
                }
            }
            callback.dispose();
        } catch (RemoteException e) {
            return false;
        }
        return result;
    }

    @AnyThread
    public Handler getHandler() {
        // Nothing should happen when called from input method.
        return null;
    }

    @AnyThread
    public void closeConnection() {
        // Nothing should happen when called from input method.
    }

    @AnyThread
    public boolean commitContent(InputContentInfo inputContentInfo, int flags, Bundle opts) {
        if (mIsUnbindIssued.get()) {
            return false;
        }

        boolean result = false;
        if (isMethodMissing(MissingMethodFlags.COMMIT_CONTENT)) {
            // This method is not implemented.
            return false;
        }
        try {
            if ((flags & InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION) != 0) {
                final AbstractInputMethodService inputMethodService = mInputMethodService.get();
                if (inputMethodService == null) {
                    // This basically should not happen, because it's the the caller of this method.
                    return false;
                }
                inputMethodService.exposeContent(inputContentInfo, this);
            }

            InputContextCallback callback = InputContextCallback.getInstance();
            mIInputContext.commitContent(inputContentInfo, flags, opts, callback.mSeq, callback);
            synchronized (callback) {
                callback.waitForResultLocked();
                if (callback.mHaveValue) {
                    result = callback.mCommitContentResult;
                }
            }
            callback.dispose();
        } catch (RemoteException e) {
            return false;
        }
        return result;
    }

    @AnyThread
    private boolean isMethodMissing(@MissingMethodFlags final int methodFlag) {
        return (mMissingMethods & methodFlag) == methodFlag;
    }

    @AnyThread
    @Override
    public String toString() {
        return "InputConnectionWrapper{idHash=#"
                + Integer.toHexString(System.identityHashCode(this))
                + " mMissingMethods="
                + InputConnectionInspector.getMissingMethodFlagsAsString(mMissingMethods) + "}";
    }
}
