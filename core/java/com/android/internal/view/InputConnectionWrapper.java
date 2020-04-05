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
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.inputmethodservice.AbstractInputMethodService;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
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

import com.android.internal.inputmethod.CancellationGroup;
import com.android.internal.inputmethod.ResultCallbacks;

import java.lang.ref.WeakReference;
import java.util.concurrent.TimeUnit;

public class InputConnectionWrapper implements InputConnection {
    private static final String TAG = "InputConnectionWrapper";

    private static final int MAX_WAIT_TIME_MILLIS = 2000;
    private final IInputContext mIInputContext;
    @NonNull
    private final WeakReference<AbstractInputMethodService> mInputMethodService;

    @MissingMethodFlags
    private final int mMissingMethods;

    /**
     * Signaled when the system decided to take away IME focus from the target app.
     *
     * <p>This is expected to be signaled immediately when the IME process receives
     * {@link IInputMethod#unbindInput()}.</p>
     */
    @NonNull
    private final CancellationGroup mCancellationGroup;

    public InputConnectionWrapper(
            @NonNull WeakReference<AbstractInputMethodService> inputMethodService,
            IInputContext inputContext, @MissingMethodFlags int missingMethods,
            @NonNull CancellationGroup cancellationGroup) {
        mInputMethodService = inputMethodService;
        mIInputContext = inputContext;
        mMissingMethods = missingMethods;
        mCancellationGroup = cancellationGroup;
    }

    @AnyThread
    private static void logInternal(@Nullable String methodName, boolean timedOut,
            @Nullable Object defaultValue) {
        if (timedOut) {
            Log.w(TAG, methodName + " didn't respond in " + MAX_WAIT_TIME_MILLIS + " msec."
                    + " Returning default: " + defaultValue);
        } else {
            Log.w(TAG, methodName + " was canceled before complete. Returning default: "
                    + defaultValue);
        }
    }

    @AnyThread
    private static int getResultOrZero(@NonNull CancellationGroup.Completable.Int value,
             @NonNull String methodName) {
        final boolean timedOut = value.await(MAX_WAIT_TIME_MILLIS,  TimeUnit.MILLISECONDS);
        if (value.hasValue()) {
            return value.getValue();
        }
        logInternal(methodName, timedOut, 0);
        return 0;
    }

    @AnyThread
    @Nullable
    private static <T> T getResultOrNull(@NonNull CancellationGroup.Completable.Values<T> value,
            @NonNull String methodName) {
        final boolean timedOut = value.await(MAX_WAIT_TIME_MILLIS,  TimeUnit.MILLISECONDS);
        if (value.hasValue()) {
            return value.getValue();
        }
        logInternal(methodName, timedOut, null);
        return null;
    }

    @AnyThread
    public CharSequence getTextAfterCursor(int length, int flags) {
        if (mCancellationGroup.isCanceled()) {
            return null;
        }

        final CancellationGroup.Completable.CharSequence value =
                mCancellationGroup.createCompletableCharSequence();
        try {
            mIInputContext.getTextAfterCursor(length, flags, ResultCallbacks.of(value));
        } catch (RemoteException e) {
            return null;
        }
        return getResultOrNull(value, "getTextAfterCursor()");
    }

    @AnyThread
    public CharSequence getTextBeforeCursor(int length, int flags) {
        if (mCancellationGroup.isCanceled()) {
            return null;
        }

        final CancellationGroup.Completable.CharSequence value =
                mCancellationGroup.createCompletableCharSequence();
        try {
            mIInputContext.getTextBeforeCursor(length, flags, ResultCallbacks.of(value));
        } catch (RemoteException e) {
            return null;
        }
        return getResultOrNull(value, "getTextBeforeCursor()");
    }

    @AnyThread
    public CharSequence getSelectedText(int flags) {
        if (mCancellationGroup.isCanceled()) {
            return null;
        }

        if (isMethodMissing(MissingMethodFlags.GET_SELECTED_TEXT)) {
            // This method is not implemented.
            return null;
        }
        final CancellationGroup.Completable.CharSequence value =
                mCancellationGroup.createCompletableCharSequence();
        try {
            mIInputContext.getSelectedText(flags, ResultCallbacks.of(value));
        } catch (RemoteException e) {
            return null;
        }
        return getResultOrNull(value, "getSelectedText()");
    }

    @AnyThread
    public int getCursorCapsMode(int reqModes) {
        if (mCancellationGroup.isCanceled()) {
            return 0;
        }

        final CancellationGroup.Completable.Int value =
                mCancellationGroup.createCompletableInt();
        try {
            mIInputContext.getCursorCapsMode(reqModes, ResultCallbacks.of(value));
        } catch (RemoteException e) {
            return 0;
        }
        return getResultOrZero(value, "getCursorCapsMode()");
    }

    @AnyThread
    public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
        if (mCancellationGroup.isCanceled()) {
            return null;
        }

        final CancellationGroup.Completable.ExtractedText value =
                mCancellationGroup.createCompletableExtractedText();
        try {
            mIInputContext.getExtractedText(request, flags, ResultCallbacks.of(value));
        } catch (RemoteException e) {
            return null;
        }
        return getResultOrNull(value, "getExtractedText()");
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
        if (mCancellationGroup.isCanceled()) {
            return false;
        }

        if (isMethodMissing(MissingMethodFlags.REQUEST_CURSOR_UPDATES)) {
            // This method is not implemented.
            return false;
        }
        final CancellationGroup.Completable.Int value = mCancellationGroup.createCompletableInt();
        try {
            mIInputContext.requestUpdateCursorAnchorInfo(cursorUpdateMode,
                    ResultCallbacks.of(value));
        } catch (RemoteException e) {
            return false;
        }
        return getResultOrZero(value, "requestUpdateCursorAnchorInfo()") != 0;
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
        if (mCancellationGroup.isCanceled()) {
            return false;
        }

        if (isMethodMissing(MissingMethodFlags.COMMIT_CONTENT)) {
            // This method is not implemented.
            return false;
        }

        if ((flags & InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION) != 0) {
            final AbstractInputMethodService inputMethodService = mInputMethodService.get();
            if (inputMethodService == null) {
                // This basically should not happen, because it's the caller of this method.
                return false;
            }
            inputMethodService.exposeContent(inputContentInfo, this);
        }

        final CancellationGroup.Completable.Int value = mCancellationGroup.createCompletableInt();
        try {
            mIInputContext.commitContent(inputContentInfo, flags, opts, ResultCallbacks.of(value));
        } catch (RemoteException e) {
            return false;
        }
        return getResultOrZero(value, "commitContent()") != 0;
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
