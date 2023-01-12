/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.view.inputmethod;

import android.annotation.CallbackExecutor;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.view.KeyEvent;

import com.android.internal.util.Preconditions;

import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * <p>Wrapper class for proxying calls to another InputConnection.  Subclass and have fun!
 */
public class InputConnectionWrapper implements InputConnection {
    private InputConnection mTarget;
    final boolean mMutable;

    /**
     * Initializes a wrapper.
     *
     * <p><b>Caveat:</b> Although the system can accept {@code (InputConnection) null} in some
     * places, you cannot emulate such a behavior by non-null {@link InputConnectionWrapper} that
     * has {@code null} in {@code target}.</p>
     * @param target the {@link InputConnection} to be proxied.
     * @param mutable set {@code true} to protect this object from being reconfigured to target
     * another {@link InputConnection}.  Note that this is ignored while the target is {@code null}.
     */
    public InputConnectionWrapper(InputConnection target, boolean mutable) {
        mMutable = mutable;
        mTarget = target;
    }

    /**
     * Change the target of the input connection.
     *
     * <p><b>Caveat:</b> Although the system can accept {@code (InputConnection) null} in some
     * places, you cannot emulate such a behavior by non-null {@link InputConnectionWrapper} that
     * has {@code null} in {@code target}.</p>
     * @param target the {@link InputConnection} to be proxied.
     * @throws SecurityException when this wrapper has non-null target and is immutable.
     */
    public void setTarget(InputConnection target) {
        if (mTarget != null && !mMutable) {
            throw new SecurityException("not mutable");
        }
        mTarget = target;
    }

    /**
     * {@inheritDoc}
     * @throws NullPointerException if the target is {@code null}.
     * @throws IllegalArgumentException if {@code length} is negative.
     */
    @Nullable
    @Override
    public CharSequence getTextBeforeCursor(@IntRange(from = 0) int n, int flags) {
        Preconditions.checkArgumentNonnegative(n);
        return mTarget.getTextBeforeCursor(n, flags);
    }

    /**
     * {@inheritDoc}
     * @throws NullPointerException if the target is {@code null}.
     * @throws IllegalArgumentException if {@code length} is negative.
     */
    @Nullable
    @Override
    public CharSequence getTextAfterCursor(@IntRange(from = 0) int n, int flags) {
        Preconditions.checkArgumentNonnegative(n);
        return mTarget.getTextAfterCursor(n, flags);
    }

    /**
     * {@inheritDoc}
     * @throws NullPointerException if the target is {@code null}.
     */
    @Override
    public CharSequence getSelectedText(int flags) {
        return mTarget.getSelectedText(flags);
    }

    /**
     * {@inheritDoc}
     * @throws NullPointerException if the target is {@code null}.
     * @throws IllegalArgumentException if {@code beforeLength} or {@code afterLength} is negative.
     */
    @Nullable
    @Override
    public SurroundingText getSurroundingText(int beforeLength, int afterLength, int flags) {
        Preconditions.checkArgumentNonnegative(beforeLength);
        Preconditions.checkArgumentNonnegative(afterLength);
        return mTarget.getSurroundingText(beforeLength, afterLength, flags);
    }

    /**
     * {@inheritDoc}
     * @throws NullPointerException if the target is {@code null}.
     */
    @Override
    public int getCursorCapsMode(int reqModes) {
        return mTarget.getCursorCapsMode(reqModes);
    }

    /**
     * {@inheritDoc}
     * @throws NullPointerException if the target is {@code null}.
     */
    @Override
    public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
        return mTarget.getExtractedText(request, flags);
    }

    /**
     * {@inheritDoc}
     * @throws NullPointerException if the target is {@code null}.
     */
    @Override
    public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
        return mTarget.deleteSurroundingTextInCodePoints(beforeLength, afterLength);
    }

    /**
     * {@inheritDoc}
     * @throws NullPointerException if the target is {@code null}.
     */
    @Override
    public boolean deleteSurroundingText(int beforeLength, int afterLength) {
        return mTarget.deleteSurroundingText(beforeLength, afterLength);
    }

    /**
     * {@inheritDoc}
     * @throws NullPointerException if the target is {@code null}.
     */
    @Override
    public boolean setComposingText(CharSequence text, int newCursorPosition) {
        return mTarget.setComposingText(text, newCursorPosition);
    }

    /**
     * {@inheritDoc}
     * @throws NullPointerException if the target is {@code null}.
     */
    @Override
    public boolean setComposingText(@NonNull CharSequence text,
            int newCursorPosition, @Nullable TextAttribute textAttribute) {
        return mTarget.setComposingText(text, newCursorPosition, textAttribute);
    }

    /**
     * {@inheritDoc}
     * @throws NullPointerException if the target is {@code null}.
     */
    @Override
    public boolean setComposingRegion(int start, int end) {
        return mTarget.setComposingRegion(start, end);
    }

    /**
     * {@inheritDoc}
     * @throws NullPointerException if the target is {@code null}.
     */
    @Override
    public boolean setComposingRegion(int start, int end, @Nullable TextAttribute textAttribute) {
        return mTarget.setComposingRegion(start, end, textAttribute);
    }

    /**
     * {@inheritDoc}
     * @throws NullPointerException if the target is {@code null}.
     */
    @Override
    public boolean finishComposingText() {
        return mTarget.finishComposingText();
    }

    /**
     * {@inheritDoc}
     * @throws NullPointerException if the target is {@code null}.
     */
    @Override
    public boolean commitText(CharSequence text, int newCursorPosition) {
        return mTarget.commitText(text, newCursorPosition);
    }

    /**
     * {@inheritDoc}
     * @throws NullPointerException if the target is {@code null}.
     */
    @Override
    public boolean commitText(@NonNull CharSequence text, int newCursorPosition,
            @Nullable TextAttribute textAttribute) {
        return mTarget.commitText(text, newCursorPosition, textAttribute);
    }

    /**
     * {@inheritDoc}
     * @throws NullPointerException if the target is {@code null}.
     */
    @Override
    public boolean commitCompletion(CompletionInfo text) {
        return mTarget.commitCompletion(text);
    }

    /**
     * {@inheritDoc}
     * @throws NullPointerException if the target is {@code null}.
     */
    @Override
    public boolean commitCorrection(CorrectionInfo correctionInfo) {
        return mTarget.commitCorrection(correctionInfo);
    }

    /**
     * {@inheritDoc}
     * @throws NullPointerException if the target is {@code null}.
     */
    @Override
    public boolean setSelection(int start, int end) {
        return mTarget.setSelection(start, end);
    }

    /**
     * {@inheritDoc}
     * @throws NullPointerException if the target is {@code null}.
     */
    @Override
    public boolean performEditorAction(int editorAction) {
        return mTarget.performEditorAction(editorAction);
    }

    /**
     * {@inheritDoc}
     * @throws NullPointerException if the target is {@code null}.
     */
    @Override
    public boolean performContextMenuAction(int id) {
        return mTarget.performContextMenuAction(id);
    }

    /**
     * {@inheritDoc}
     * @throws NullPointerException if the target is {@code null}.
     */
    @Override
    public boolean beginBatchEdit() {
        return mTarget.beginBatchEdit();
    }

    /**
     * {@inheritDoc}
     * @throws NullPointerException if the target is {@code null}.
     */
    @Override
    public boolean endBatchEdit() {
        return mTarget.endBatchEdit();
    }

    /**
     * {@inheritDoc}
     * @throws NullPointerException if the target is {@code null}.
     */
    @Override
    public boolean sendKeyEvent(KeyEvent event) {
        return mTarget.sendKeyEvent(event);
    }

    /**
     * {@inheritDoc}
     * @throws NullPointerException if the target is {@code null}.
     */
    @Override
    public boolean clearMetaKeyStates(int states) {
        return mTarget.clearMetaKeyStates(states);
    }

    /**
     * {@inheritDoc}
     * @throws NullPointerException if the target is {@code null}.
     */
    @Override
    public boolean reportFullscreenMode(boolean enabled) {
        return mTarget.reportFullscreenMode(enabled);
    }

    /**
     * {@inheritDoc}
     * @throws NullPointerException if the target is {@code null}.
     */
    @Override
    public boolean performSpellCheck() {
        return mTarget.performSpellCheck();
    }

    /**
     * {@inheritDoc}
     * @throws NullPointerException if the target is {@code null}.
     */
    @Override
    public boolean performPrivateCommand(String action, Bundle data) {
        return mTarget.performPrivateCommand(action, data);
    }

    /**
     * {@inheritDoc}
     * @throws NullPointerException if the target is {@code null}.
     */
    @Override
    public void performHandwritingGesture(
            @NonNull HandwritingGesture gesture, @Nullable @CallbackExecutor Executor executor,
            @Nullable IntConsumer consumer) {
        mTarget.performHandwritingGesture(gesture, executor, consumer);
    }

    /**
     * {@inheritDoc}
     * @throws NullPointerException if the target is {@code null}.
     */
    @Override
    public boolean previewHandwritingGesture(
            @NonNull PreviewableHandwritingGesture gesture,
            @Nullable CancellationSignal cancellationSignal) {
        return mTarget.previewHandwritingGesture(gesture, cancellationSignal);
    }

    /**
     * {@inheritDoc}
     * @throws NullPointerException if the target is {@code null}.
     */
    @Override
    public boolean requestCursorUpdates(int cursorUpdateMode) {
        return mTarget.requestCursorUpdates(cursorUpdateMode);
    }

    /**
     * {@inheritDoc}
     * @throws NullPointerException if the target is {@code null}.
     */
    @Override
    public void requestTextBoundsInfo(
            @NonNull RectF bounds, @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<TextBoundsInfoResult> consumer) {
        mTarget.requestTextBoundsInfo(bounds, executor, consumer);
    }

    /**
     * {@inheritDoc}
     * @throws NullPointerException if the target is {@code null}.
     */
    @Override
    public Handler getHandler() {
        return mTarget.getHandler();
    }

    /**
     * {@inheritDoc}
     * @throws NullPointerException if the target is {@code null}.
     */
    @Override
    public void closeConnection() {
        mTarget.closeConnection();
    }

    /**
     * {@inheritDoc}
     * @throws NullPointerException if the target is {@code null}.
     */
    @Override
    public boolean commitContent(InputContentInfo inputContentInfo, int flags, Bundle opts) {
        return mTarget.commitContent(inputContentInfo, flags, opts);
    }

    /**
     * {@inheritDoc}
     * @throws NullPointerException if the target is {@code null}.
     */
    @Override
    public boolean setImeConsumesInput(boolean imeConsumesInput) {
        return mTarget.setImeConsumesInput(imeConsumesInput);
    }
}
