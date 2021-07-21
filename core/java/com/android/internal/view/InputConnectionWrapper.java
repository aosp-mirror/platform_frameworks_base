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
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.inputmethodservice.AbstractInputMethodService;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.util.imetracing.ImeTracing;
import android.util.imetracing.InputConnectionHelper;
import android.util.proto.ProtoOutputStream;
import android.view.KeyEvent;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.CorrectionInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionInspector;
import android.view.inputmethod.InputConnectionInspector.MissingMethodFlags;
import android.view.inputmethod.InputContentInfo;
import android.view.inputmethod.SurroundingText;

import com.android.internal.inputmethod.CancellationGroup;
import com.android.internal.inputmethod.Completable;
import com.android.internal.inputmethod.ResultCallbacks;

import java.lang.ref.WeakReference;

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

    /**
     * See {@link InputConnection#getTextAfterCursor(int, int)}.
     */
    @Nullable
    @AnyThread
    public CharSequence getTextAfterCursor(@IntRange(from = 0) int length, int flags) {
        if (length < 0 || mCancellationGroup.isCanceled()) {
            return null;
        }

        final Completable.CharSequence value = Completable.createCharSequence();
        try {
            mIInputContext.getTextAfterCursor(length, flags, ResultCallbacks.of(value));
        } catch (RemoteException e) {
            return null;
        }
        CharSequence result = Completable.getResultOrNull(
                value, TAG, "getTextAfterCursor()", mCancellationGroup, MAX_WAIT_TIME_MILLIS);

        final AbstractInputMethodService inputMethodService = mInputMethodService.get();
        if (inputMethodService != null && ImeTracing.getInstance().isEnabled()) {
            ProtoOutputStream icProto = InputConnectionHelper.buildGetTextAfterCursorProto(length,
                    flags, result);
            ImeTracing.getInstance().triggerServiceDump(TAG + "#getTextAfterCursor",
                    inputMethodService, icProto);
        }

        return result;
    }

    /**
     * See {@link InputConnection#getTextBeforeCursor(int, int)}.
     */
    @Nullable
    @AnyThread
    public CharSequence getTextBeforeCursor(@IntRange(from = 0) int length, int flags) {
        if (length < 0 || mCancellationGroup.isCanceled()) {
            return null;
        }

        final Completable.CharSequence value = Completable.createCharSequence();
        try {
            mIInputContext.getTextBeforeCursor(length, flags, ResultCallbacks.of(value));
        } catch (RemoteException e) {
            return null;
        }
        CharSequence result = Completable.getResultOrNull(
                value, TAG, "getTextBeforeCursor()", mCancellationGroup, MAX_WAIT_TIME_MILLIS);

        final AbstractInputMethodService inputMethodService = mInputMethodService.get();
        if (inputMethodService != null && ImeTracing.getInstance().isEnabled()) {
            ProtoOutputStream icProto = InputConnectionHelper.buildGetTextBeforeCursorProto(length,
                    flags, result);
            ImeTracing.getInstance().triggerServiceDump(TAG + "#getTextBeforeCursor",
                    inputMethodService, icProto);
        }

        return result;
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
        final Completable.CharSequence value = Completable.createCharSequence();
        try {
            mIInputContext.getSelectedText(flags, ResultCallbacks.of(value));
        } catch (RemoteException e) {
            return null;
        }
        CharSequence result = Completable.getResultOrNull(
                value, TAG, "getSelectedText()", mCancellationGroup, MAX_WAIT_TIME_MILLIS);

        final AbstractInputMethodService inputMethodService = mInputMethodService.get();
        if (inputMethodService != null && ImeTracing.getInstance().isEnabled()) {
            ProtoOutputStream icProto = InputConnectionHelper.buildGetSelectedTextProto(flags,
                    result);
            ImeTracing.getInstance().triggerServiceDump(TAG + "#getSelectedText",
                    inputMethodService, icProto);
        }

        return result;
    }

    /**
     * Get {@link SurroundingText} around the current cursor, with <var>beforeLength</var>
     * characters of text before the cursor, <var>afterLength</var> characters of text after the
     * cursor, and all of the selected text.
     * @param beforeLength The expected length of the text before the cursor
     * @param afterLength The expected length of the text after the cursor
     * @param flags Supplies additional options controlling how the text is returned. May be either
     *              0 or {@link #GET_TEXT_WITH_STYLES}.
     * @return the surrounding text around the cursor position; the length of the returned text
     * might be less than requested.  It could also be {@code null} when the editor or system could
     * not support this protocol.
     */
    @AnyThread
    public SurroundingText getSurroundingText(
            @IntRange(from = 0) int beforeLength, @IntRange(from = 0) int afterLength, int flags) {
        if (beforeLength < 0 || afterLength < 0 || mCancellationGroup.isCanceled()) {
            return null;
        }

        if (isMethodMissing(MissingMethodFlags.GET_SURROUNDING_TEXT)) {
            // This method is not implemented.
            return null;
        }
        final Completable.SurroundingText value = Completable.createSurroundingText();
        try {
            mIInputContext.getSurroundingText(beforeLength, afterLength, flags,
                    ResultCallbacks.of(value));
        } catch (RemoteException e) {
            return null;
        }
        SurroundingText result = Completable.getResultOrNull(
                value, TAG, "getSurroundingText()", mCancellationGroup, MAX_WAIT_TIME_MILLIS);

        final AbstractInputMethodService inputMethodService = mInputMethodService.get();
        if (inputMethodService != null && ImeTracing.getInstance().isEnabled()) {
            ProtoOutputStream icProto = InputConnectionHelper.buildGetSurroundingTextProto(
                    beforeLength, afterLength, flags, result);
            ImeTracing.getInstance().triggerServiceDump(TAG + "#getSurroundingText",
                    inputMethodService, icProto);
        }

        return result;
    }

    @AnyThread
    public int getCursorCapsMode(int reqModes) {
        if (mCancellationGroup.isCanceled()) {
            return 0;
        }

        final Completable.Int value = Completable.createInt();
        try {
            mIInputContext.getCursorCapsMode(reqModes, ResultCallbacks.of(value));
        } catch (RemoteException e) {
            return 0;
        }
        int result = Completable.getResultOrZero(
                value, TAG, "getCursorCapsMode()", mCancellationGroup, MAX_WAIT_TIME_MILLIS);

        final AbstractInputMethodService inputMethodService = mInputMethodService.get();
        if (inputMethodService != null && ImeTracing.getInstance().isEnabled()) {
            ProtoOutputStream icProto = InputConnectionHelper.buildGetCursorCapsModeProto(
                    reqModes, result);
            ImeTracing.getInstance().triggerServiceDump(TAG + "#getCursorCapsMode",
                    inputMethodService, icProto);
        }

        return result;
    }

    @AnyThread
    public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
        if (mCancellationGroup.isCanceled()) {
            return null;
        }

        final Completable.ExtractedText value = Completable.createExtractedText();
        try {
            mIInputContext.getExtractedText(request, flags, ResultCallbacks.of(value));
        } catch (RemoteException e) {
            return null;
        }
        ExtractedText result = Completable.getResultOrNull(
                value, TAG, "getExtractedText()", mCancellationGroup, MAX_WAIT_TIME_MILLIS);

        final AbstractInputMethodService inputMethodService = mInputMethodService.get();
        if (inputMethodService != null && ImeTracing.getInstance().isEnabled()) {
            ProtoOutputStream icProto = InputConnectionHelper.buildGetExtractedTextProto(
                    request, flags, result);
            ImeTracing.getInstance().triggerServiceDump(TAG + "#getExtractedText",
                    inputMethodService, icProto);
        }

        return result;
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
    @Override
    public boolean performSpellCheck() {
        try {
            mIInputContext.performSpellCheck();
            return true;
        } catch (RemoteException e) {
            return false;
        }
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
        final Completable.Int value = Completable.createInt();
        try {
            mIInputContext.requestUpdateCursorAnchorInfo(cursorUpdateMode,
                    ResultCallbacks.of(value));
        } catch (RemoteException e) {
            return false;
        }
        return Completable.getResultOrZero(value, TAG, "requestUpdateCursorAnchorInfo()",
                mCancellationGroup, MAX_WAIT_TIME_MILLIS) != 0;
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

        final Completable.Int value = Completable.createInt();
        try {
            mIInputContext.commitContent(inputContentInfo, flags, opts, ResultCallbacks.of(value));
        } catch (RemoteException e) {
            return false;
        }
        return Completable.getResultOrZero(
                value, TAG, "commitContent()", mCancellationGroup, MAX_WAIT_TIME_MILLIS) != 0;
    }

    /**
     * See {@link InputConnection#setImeConsumesInput(boolean)}.
     */
    @AnyThread
    public boolean setImeConsumesInput(boolean imeConsumesInput) {
        try {
            mIInputContext.setImeConsumesInput(imeConsumesInput);
            return true;
        } catch (RemoteException e) {
            return false;
        }
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
