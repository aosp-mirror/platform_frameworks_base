/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.internal.inputmethod;

import android.annotation.AnyThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.RemoteException;
import android.view.KeyEvent;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.CorrectionInfo;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputContentInfo;

import com.android.internal.view.IInputContext;

import java.util.Objects;

/**
 * A stateless wrapper of {@link com.android.internal.view.IInputContext} to encapsulate boilerplate
 * code around {@link InputConnectionCommand}, {@link Completable} and {@link RemoteException}.
 */
public final class IInputContextInvoker {

    @NonNull
    private final IInputContext mIInputContext;

    private IInputContextInvoker(@NonNull IInputContext inputContext) {
        mIInputContext = inputContext;
    }

    /**
     * Creates a new instance of {@link IInputContextInvoker} for the given {@link IInputContext}.
     *
     * @param inputContext {@link IInputContext} to be wrapped.
     * @return A new instance of {@link IInputContextInvoker}.
     */
    public static IInputContextInvoker create(@NonNull IInputContext inputContext) {
        Objects.requireNonNull(inputContext);
        return new IInputContextInvoker(inputContext);
    }

    /**
     * Implements {@link android.view.inputmethod.InputConnection#getTextAfterCursor(int, int)}.
     *
     * @param length {@code length} parameter to be passed.
     * @param flags {@code flags} parameter to be passed.
     * @return {@link Completable.CharSequence} that can be used to retrieve the invocation result.
     *         {@link RemoteException} will be treated as an error.
     */
    @AnyThread
    @NonNull
    public Completable.CharSequence getTextAfterCursor(int length, int flags) {
        final Completable.CharSequence value = Completable.createCharSequence();
        final InputConnectionCommand command = Commands.getTextAfterCursor(length, flags, value);
        try {
            mIInputContext.doEdit(command);
        } catch (RemoteException e) {
            value.onError(ThrowableHolder.of(e));
        }
        return value;
    }

    /**
     * Implements {@link android.view.inputmethod.InputConnection#getTextBeforeCursor(int, int)}.
     *
     * @param length {@code length} parameter to be passed.
     * @param flags {@code flags} parameter to be passed.
     * @return {@link Completable.CharSequence} that can be used to retrieve the invocation result.
     *         {@link RemoteException} will be treated as an error.
     */
    @AnyThread
    @NonNull
    public Completable.CharSequence getTextBeforeCursor(int length, int flags) {
        final Completable.CharSequence value = Completable.createCharSequence();
        final InputConnectionCommand command = Commands.getTextBeforeCursor(length, flags, value);
        try {
            mIInputContext.doEdit(command);
        } catch (RemoteException e) {
            value.onError(ThrowableHolder.of(e));
        }
        return value;
    }

    /**
     * Implements {@link android.view.inputmethod.InputConnection#getSelectedText(int)}.
     *
     * @param flags {@code flags} parameter to be passed.
     * @return {@link Completable.CharSequence} that can be used to retrieve the invocation result.
     *         {@link RemoteException} will be treated as an error.
     */
    @AnyThread
    @NonNull
    public Completable.CharSequence getSelectedText(int flags) {
        final Completable.CharSequence value = Completable.createCharSequence();
        final InputConnectionCommand command = Commands.getSelectedText(flags, value);
        try {
            mIInputContext.doEdit(command);
        } catch (RemoteException e) {
            value.onError(ThrowableHolder.of(e));
        }
        return value;
    }

    /**
     * Implements
     * {@link android.view.inputmethod.InputConnection#getSurroundingText(int, int, int)}.
     *
     * @param beforeLength {@code beforeLength} parameter to be passed.
     * @param afterLength {@code afterLength} parameter to be passed.
     * @param flags {@code flags} parameter to be passed.
     * @return {@link Completable.SurroundingText} that can be used to retrieve the invocation
     *         result. {@link RemoteException} will be treated as an error.
     */
    @AnyThread
    @NonNull
    public Completable.SurroundingText getSurroundingText(int beforeLength, int afterLength,
            int flags) {
        final Completable.SurroundingText value = Completable.createSurroundingText();
        final InputConnectionCommand command =
                Commands.getSurroundingText(beforeLength, afterLength, flags, value);
        try {
            mIInputContext.doEdit(command);
        } catch (RemoteException e) {
            value.onError(ThrowableHolder.of(e));
        }
        return value;
    }

    /**
     * Implements {@link android.view.inputmethod.InputConnection#getCursorCapsMode(int)}.
     *
     * @param reqModes {@code reqModes} parameter to be passed.
     * @return {@link Completable.Int} that can be used to retrieve the invocation result.
     *         {@link RemoteException} will be treated as an error.
     */
    @AnyThread
    @NonNull
    public Completable.Int getCursorCapsMode(int reqModes) {
        final Completable.Int value = Completable.createInt();
        final InputConnectionCommand command = Commands.getCursorCapsMode(reqModes, value);
        try {
            mIInputContext.doEdit(command);
        } catch (RemoteException e) {
            value.onError(ThrowableHolder.of(e));
        }
        return value;
    }

    /**
     * Implements
     * {@link android.view.inputmethod.InputConnection#getExtractedText(ExtractedTextRequest, int)}.
     *
     * @param request {@code request} parameter to be passed.
     * @param flags {@code flags} parameter to be passed.
     * @return {@link Completable.ExtractedText} that can be used to retrieve the invocation result.
     *         {@link RemoteException} will be treated as an error.
     */
    @AnyThread
    @NonNull
    public Completable.ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
        final Completable.ExtractedText value = Completable.createExtractedText();
        final InputConnectionCommand command = Commands.getExtractedText(request, flags, value);
        try {
            mIInputContext.doEdit(command);
        } catch (RemoteException e) {
            value.onError(ThrowableHolder.of(e));
        }
        return value;
    }

    /**
     * Implements {@link android.view.inputmethod.InputConnection#commitText(CharSequence, int)}.
     *
     * @param text {@code text} parameter to be passed.
     * @param newCursorPosition {@code newCursorPosition} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean commitText(CharSequence text, int newCursorPosition) {
        final InputConnectionCommand command = Commands.commitText(text, newCursorPosition);
        try {
            mIInputContext.doEdit(command);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Implements {@link android.view.inputmethod.InputConnection#commitCompletion(CompletionInfo)}.
     *
     * @param text {@code text} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean commitCompletion(CompletionInfo text) {
        final InputConnectionCommand command = Commands.commitCompletion(text);
        try {
            mIInputContext.doEdit(command);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Implements {@link android.view.inputmethod.InputConnection#commitCorrection(CorrectionInfo)}.
     *
     * @param correctionInfo {@code correctionInfo} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean commitCorrection(CorrectionInfo correctionInfo) {
        final InputConnectionCommand command = Commands.commitCorrection(correctionInfo);
        try {
            mIInputContext.doEdit(command);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Implements {@link android.view.inputmethod.InputConnection#setSelection(int, int)}.
     *
     * @param start {@code start} parameter to be passed.
     * @param end {@code start} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean setSelection(int start, int end) {
        final InputConnectionCommand command = Commands.setSelection(start, end);
        try {
            mIInputContext.doEdit(command);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Implements {@link android.view.inputmethod.InputConnection#performEditorAction(int)}.
     *
     * @param actionCode {@code start} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean performEditorAction(int actionCode) {
        final InputConnectionCommand command = Commands.performEditorAction(actionCode);
        try {
            mIInputContext.doEdit(command);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Implements {@link android.view.inputmethod.InputConnection#performContextMenuAction(int)}.
     *
     * @param id {@code id} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean performContextMenuAction(int id) {
        final InputConnectionCommand command = Commands.performContextMenuAction(id);
        try {
            mIInputContext.doEdit(command);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Implements {@link android.view.inputmethod.InputConnection#setComposingRegion(int, int)}.
     *
     * @param start {@code id} parameter to be passed.
     * @param end {@code id} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean setComposingRegion(int start, int end) {
        final InputConnectionCommand command = Commands.setComposingRegion(start, end);
        try {
            mIInputContext.doEdit(command);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Implements
     * {@link android.view.inputmethod.InputConnection#setComposingText(CharSequence, int)}.
     *
     * @param text {@code text} parameter to be passed.
     * @param newCursorPosition {@code newCursorPosition} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean setComposingText(CharSequence text, int newCursorPosition) {
        final InputConnectionCommand command = Commands.setComposingText(text, newCursorPosition);
        try {
            mIInputContext.doEdit(command);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Implements {@link android.view.inputmethod.InputConnection#finishComposingText()}.
     *
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean finishComposingText() {
        final InputConnectionCommand command = Commands.finishComposingText();
        try {
            mIInputContext.doEdit(command);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Implements {@link android.view.inputmethod.InputConnection#beginBatchEdit()}.
     *
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean beginBatchEdit() {
        final InputConnectionCommand command = Commands.beginBatchEdit();
        try {
            mIInputContext.doEdit(command);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Implements {@link android.view.inputmethod.InputConnection#endBatchEdit()}.
     *
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean endBatchEdit() {
        final InputConnectionCommand command = Commands.endBatchEdit();
        try {
            mIInputContext.doEdit(command);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Implements {@link android.view.inputmethod.InputConnection#sendKeyEvent(KeyEvent)}.
     *
     * @param event {@code event} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean sendKeyEvent(KeyEvent event) {
        final InputConnectionCommand command = Commands.sendKeyEvent(event);
        try {
            mIInputContext.doEdit(command);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Implements {@link android.view.inputmethod.InputConnection#clearMetaKeyStates(int)}.
     *
     * @param states {@code states} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean clearMetaKeyStates(int states) {
        final InputConnectionCommand command = Commands.clearMetaKeyStates(states);
        try {
            mIInputContext.doEdit(command);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Implements {@link android.view.inputmethod.InputConnection#deleteSurroundingText(int, int)}.
     *
     * @param beforeLength {@code beforeLength} parameter to be passed.
     * @param afterLength {@code afterLength} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean deleteSurroundingText(int beforeLength, int afterLength) {
        final InputConnectionCommand command =
                Commands.deleteSurroundingText(beforeLength, afterLength);
        try {
            mIInputContext.doEdit(command);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Implements
     * {@link android.view.inputmethod.InputConnection#deleteSurroundingTextInCodePoints(int, int)}.
     *
     * @param beforeLength {@code beforeLength} parameter to be passed.
     * @param afterLength {@code afterLength} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
        final InputConnectionCommand command =
                Commands.deleteSurroundingTextInCodePoints(beforeLength, afterLength);
        try {
            mIInputContext.doEdit(command);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Implements {@link android.view.inputmethod.InputConnection#performSpellCheck()}.
     *
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean performSpellCheck() {
        final InputConnectionCommand command = Commands.performSpellCheck();
        try {
            mIInputContext.doEdit(command);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Implements
     * {@link android.view.inputmethod.InputConnection#performPrivateCommand(String, Bundle)}.
     *
     * @param action {@code action} parameter to be passed.
     * @param data {@code data} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean performPrivateCommand(String action, Bundle data) {
        final InputConnectionCommand command = Commands.performPrivateCommand(action, data);
        try {
            mIInputContext.doEdit(command);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Implements {@link android.view.inputmethod.InputConnection#requestCursorUpdates(int)}.
     *
     * @param cursorUpdateMode {@code cursorUpdateMode} parameter to be passed.
     * @return {@link Completable.Boolean} that can be used to retrieve the invocation result.
     *         {@link RemoteException} will be treated as an error.
     */
    @AnyThread
    @NonNull
    public Completable.Boolean requestCursorUpdates(int cursorUpdateMode) {
        final Completable.Boolean value = Completable.createBoolean();
        final InputConnectionCommand command =
                Commands.requestCursorUpdates(cursorUpdateMode, value);
        try {
            mIInputContext.doEdit(command);
        } catch (RemoteException e) {
            value.onError(ThrowableHolder.of(e));
        }
        return value;
    }

    /**
     * Implements {@link android.view.inputmethod.InputConnection#commitContent(InputContentInfo,
     * int, Bundle)}.
     *
     * @param inputContentInfo {@code inputContentInfo} parameter to be passed.
     * @param flags {@code flags} parameter to be passed.
     * @param opts {@code opts} parameter to be passed.
     * @return {@link Completable.Boolean} that can be used to retrieve the invocation result.
     *         {@link RemoteException} will be treated as an error.
     */
    @AnyThread
    @NonNull
    public Completable.Boolean commitContent(InputContentInfo inputContentInfo, int flags,
            Bundle opts) {
        final Completable.Boolean value = Completable.createBoolean();
        final InputConnectionCommand command =
                Commands.commitContent(inputContentInfo, flags, opts, value);
        try {
            mIInputContext.doEdit(command);
        } catch (RemoteException e) {
            value.onError(ThrowableHolder.of(e));
        }
        return value;
    }

    /**
     * Implements {@link android.view.inputmethod.InputConnection#setImeConsumesInput(boolean)}.
     *
     * @param imeConsumesInput {@code imeConsumesInput} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean setImeConsumesInput(boolean imeConsumesInput) {
        final InputConnectionCommand command = Commands.setImeConsumesInput(imeConsumesInput);
        try {
            mIInputContext.doEdit(command);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Defines the data packing rules from {@link android.view.inputmethod.InputConnection} API
     * params into {@link InputConnectionCommand} fields.
     *
     * Rules need to be in sync with {@link com.android.internal.view.IInputConnectionWrapper} and
     * {@link InputMethodDebug#dumpInputConnectionCommand(InputConnectionCommand)}.
     */
    private static final class Commands {
        /**
         * Not intended to be instantiated.
         */
        private Commands() { }

        @AnyThread
        @NonNull
        static InputConnectionCommand getTextAfterCursor(int n, int flags,
                @NonNull Completable.CharSequence returnValue) {
            return InputConnectionCommand.create(
                    InputConnectionCommandType.GET_TEXT_AFTER_CURSOR,
                    n,
                    0,
                    flags,
                    null,
                    null,
                    null,
                    InputConnectionCommand.ParcelableType.NULL,
                    null,
                    returnValue);
        }

        @AnyThread
        @NonNull
        static InputConnectionCommand getTextBeforeCursor(int n, int flags,
                @NonNull Completable.CharSequence returnValue) {
            return InputConnectionCommand.create(
                    InputConnectionCommandType.GET_TEXT_BEFORE_CURSOR,
                    n,
                    0,
                    flags,
                    null,
                    null,
                    null,
                    InputConnectionCommand.ParcelableType.NULL,
                    null,
                    returnValue);
        }

        @AnyThread
        @NonNull
        static InputConnectionCommand getSelectedText(int flags,
                @NonNull Completable.CharSequence returnValue) {
            return InputConnectionCommand.create(
                    InputConnectionCommandType.GET_SELECTED_TEXT,
                    0,
                    0,
                    flags,
                    null,
                    null,
                    null,
                    InputConnectionCommand.ParcelableType.NULL,
                    null,
                    returnValue);
        }

        @AnyThread
        @NonNull
        static InputConnectionCommand getSurroundingText(int beforeLength, int afterLength,
                int flags, @NonNull Completable.SurroundingText returnValue) {
            return InputConnectionCommand.create(
                    InputConnectionCommandType.GET_SURROUNDING_TEXT,
                    beforeLength,
                    afterLength,
                    flags,
                    null,
                    null,
                    null,
                    InputConnectionCommand.ParcelableType.NULL,
                    null,
                    returnValue);
        }

        @AnyThread
        @NonNull
        static InputConnectionCommand getCursorCapsMode(int reqModes,
                @NonNull Completable.Int returnValue) {
            return InputConnectionCommand.create(
                    InputConnectionCommandType.GET_CURSOR_CAPS_MODE,
                    reqModes,
                    0,
                    0,
                    null,
                    null,
                    null,
                    InputConnectionCommand.ParcelableType.NULL,
                    null,
                    returnValue);
        }

        @AnyThread
        @NonNull
        static InputConnectionCommand getExtractedText(@Nullable ExtractedTextRequest request,
                int flags, @NonNull Completable.ExtractedText returnValue) {
            return InputConnectionCommand.create(
                    InputConnectionCommandType.GET_EXTRACTED_TEXT,
                    0,
                    0,
                    flags,
                    null,
                    null,
                    null,
                    InputConnectionCommand.ParcelableType.EXTRACTED_TEXT_REQUEST,
                    request,
                    returnValue);
        }

        @AnyThread
        @NonNull
        static InputConnectionCommand commitText(@Nullable CharSequence text,
                int newCursorPosition) {
            return InputConnectionCommand.create(
                    InputConnectionCommandType.COMMIT_TEXT,
                    newCursorPosition,
                    0,
                    0,
                    text);
        }

        @AnyThread
        @NonNull
        static InputConnectionCommand commitCompletion(@Nullable CompletionInfo text) {
            return InputConnectionCommand.create(
                    InputConnectionCommandType.COMMIT_COMPLETION,
                    0,
                    0,
                    0,
                    null,
                    null,
                    null,
                    InputConnectionCommand.ParcelableType.COMPLETION_INFO,
                    text);
        }

        @AnyThread
        @NonNull
        static InputConnectionCommand commitCorrection(@Nullable CorrectionInfo correctionInfo) {
            return InputConnectionCommand.create(
                    InputConnectionCommandType.COMMIT_CORRECTION,
                    0,
                    0,
                    0,
                    null,
                    null,
                    null,
                    InputConnectionCommand.ParcelableType.CORRECTION_INFO,
                    correctionInfo);
        }

        @AnyThread
        @NonNull
        static InputConnectionCommand setSelection(int start, int end) {
            return InputConnectionCommand.create(
                    InputConnectionCommandType.SET_SELECTION,
                    start,
                    end);
        }

        @AnyThread
        @NonNull
        static InputConnectionCommand performEditorAction(int actionCode) {
            return InputConnectionCommand.create(
                    InputConnectionCommandType.PERFORM_EDITOR_ACTION,
                    actionCode);
        }

        @AnyThread
        @NonNull
        static InputConnectionCommand performContextMenuAction(int id) {
            return InputConnectionCommand.create(
                    InputConnectionCommandType.PERFORM_CONTEXT_MENU_ACTION,
                    id);
        }

        @AnyThread
        @NonNull
        static InputConnectionCommand setComposingRegion(int start, int end) {
            return InputConnectionCommand.create(
                    InputConnectionCommandType.SET_COMPOSING_REGION,
                    start,
                    end);
        }

        @AnyThread
        @NonNull
        static InputConnectionCommand setComposingText(@Nullable CharSequence text,
                int newCursorPosition) {
            return InputConnectionCommand.create(
                    InputConnectionCommandType.SET_COMPOSING_TEXT,
                    newCursorPosition,
                    0,
                    0,
                    text);
        }

        @AnyThread
        @NonNull
        static InputConnectionCommand finishComposingText() {
            return InputConnectionCommand.create(
                    InputConnectionCommandType.FINISH_COMPOSING_TEXT);
        }

        @AnyThread
        @NonNull
        static InputConnectionCommand beginBatchEdit() {
            return InputConnectionCommand.create(
                    InputConnectionCommandType.BEGIN_BATCH_EDIT);
        }

        @AnyThread
        @NonNull
        static InputConnectionCommand endBatchEdit() {
            return InputConnectionCommand.create(
                    InputConnectionCommandType.END_BATCH_EDIT);
        }

        @AnyThread
        @NonNull
        static InputConnectionCommand sendKeyEvent(@Nullable KeyEvent event) {
            return InputConnectionCommand.create(
                    InputConnectionCommandType.SEND_KEY_EVENT,
                    0,
                    0,
                    0,
                    null,
                    null,
                    null,
                    InputConnectionCommand.ParcelableType.KEY_EVENT,
                    event);
        }

        @AnyThread
        @NonNull
        static InputConnectionCommand clearMetaKeyStates(int states) {
            return InputConnectionCommand.create(
                    InputConnectionCommandType.CLEAR_META_KEY_STATES,
                    states);
        }

        @AnyThread
        @NonNull
        static InputConnectionCommand deleteSurroundingText(int beforeLength, int afterLength) {
            return InputConnectionCommand.create(
                    InputConnectionCommandType.DELETE_SURROUNDING_TEXT,
                    beforeLength,
                    afterLength);
        }

        @AnyThread
        @NonNull
        static InputConnectionCommand deleteSurroundingTextInCodePoints(int beforeLength,
                int afterLength) {
            return InputConnectionCommand.create(
                    InputConnectionCommandType.DELETE_SURROUNDING_TEXT_IN_CODE_POINTS,
                    beforeLength,
                    afterLength);
        }

        @AnyThread
        @NonNull
        static InputConnectionCommand performSpellCheck() {
            return InputConnectionCommand.create(InputConnectionCommandType.PERFORM_SPELL_CHECK);
        }

        @AnyThread
        @NonNull
        static InputConnectionCommand performPrivateCommand(@Nullable String action,
                @Nullable Bundle data) {
            return InputConnectionCommand.create(
                    InputConnectionCommandType.PERFORM_PRIVATE_COMMAND,
                    0,
                    0,
                    0,
                    null,
                    action,
                    data);
        }

        @AnyThread
        @NonNull
        static InputConnectionCommand requestCursorUpdates(int cursorUpdateMode,
                @NonNull Completable.Boolean returnValue) {
            return InputConnectionCommand.create(
                    InputConnectionCommandType.REQUEST_CURSOR_UPDATES,
                    cursorUpdateMode,
                    0,
                    0,
                    null,
                    null,
                    null,
                    InputConnectionCommand.ParcelableType.NULL,
                    null,
                    returnValue);
        }

        @AnyThread
        @NonNull
        static InputConnectionCommand commitContent(@Nullable InputContentInfo inputContentInfo,
                int flags, @Nullable Bundle opts, @NonNull Completable.Boolean returnValue) {
            return InputConnectionCommand.create(
                    InputConnectionCommandType.COMMIT_CONTENT,
                    0,
                    0,
                    flags,
                    null,
                    null,
                    opts,
                    InputConnectionCommand.ParcelableType.INPUT_CONTENT_INFO,
                    inputContentInfo,
                    returnValue);
        }

        @AnyThread
        @NonNull
        static InputConnectionCommand setImeConsumesInput(boolean imeConsumesInput) {
            return InputConnectionCommand.create(
                    InputConnectionCommandType.SET_IME_CONSUMES_INPUT,
                    imeConsumesInput ? 1 : 0);
        }
    }
}
