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
import android.os.Bundle;
import android.os.RemoteException;
import android.view.KeyEvent;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.CorrectionInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputContentInfo;
import android.view.inputmethod.SurroundingText;

import com.android.internal.infra.AndroidFuture;
import com.android.internal.view.IInputContext;

import java.util.Objects;

/**
 * A stateless wrapper of {@link com.android.internal.view.IInputContext} to encapsulate boilerplate
 * code around {@link AndroidFuture} and {@link RemoteException}.
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

    @NonNull
    InputConnectionCommandHeader createHeader() {
        // TODO(b/203086369): Propagate session ID for interruption
        return new InputConnectionCommandHeader(0 /* sessionId */);
    }

    /**
     * Invokes {@link IInputContext#getTextAfterCursor(InputConnectionCommandHeader, int, int,
     * AndroidFuture)}.
     *
     * @param length {@code length} parameter to be passed.
     * @param flags {@code flags} parameter to be passed.
     * @return {@link AndroidFuture<CharSequence>} that can be used to retrieve the invocation
     *         result. {@link RemoteException} will be treated as an error.
     */
    @AnyThread
    @NonNull
    public AndroidFuture<CharSequence> getTextAfterCursor(int length, int flags) {
        final AndroidFuture<CharSequence> future = new AndroidFuture<>();
        try {
            mIInputContext.getTextAfterCursor(createHeader(), length, flags, future);
        } catch (RemoteException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * Invokes {@link IInputContext#getTextBeforeCursor(InputConnectionCommandHeader, int, int,
     * AndroidFuture)}.
     *
     * @param length {@code length} parameter to be passed.
     * @param flags {@code flags} parameter to be passed.
     * @return {@link AndroidFuture<CharSequence>} that can be used to retrieve the invocation
     *         result. {@link RemoteException} will be treated as an error.
     */
    @AnyThread
    @NonNull
    public AndroidFuture<CharSequence> getTextBeforeCursor(int length, int flags) {
        final AndroidFuture<CharSequence> future = new AndroidFuture<>();
        try {
            mIInputContext.getTextBeforeCursor(createHeader(), length, flags, future);
        } catch (RemoteException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * Invokes
     * {@link IInputContext#getSelectedText(InputConnectionCommandHeader, int, AndroidFuture)}.
     *
     * @param flags {@code flags} parameter to be passed.
     * @return {@link AndroidFuture<CharSequence>} that can be used to retrieve the invocation
     *         result. {@link RemoteException} will be treated as an error.
     */
    @AnyThread
    @NonNull
    public AndroidFuture<CharSequence> getSelectedText(int flags) {
        final AndroidFuture<CharSequence> future = new AndroidFuture<>();
        try {
            mIInputContext.getSelectedText(createHeader(), flags, future);
        } catch (RemoteException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * Invokes
     * {@link IInputContext#getSurroundingText(InputConnectionCommandHeader, int, int, int,
     * AndroidFuture)}.
     *
     * @param beforeLength {@code beforeLength} parameter to be passed.
     * @param afterLength {@code afterLength} parameter to be passed.
     * @param flags {@code flags} parameter to be passed.
     * @return {@link AndroidFuture<SurroundingText>} that can be used to retrieve the
     *         invocation result. {@link RemoteException} will be treated as an error.
     */
    @AnyThread
    @NonNull
    public AndroidFuture<SurroundingText> getSurroundingText(int beforeLength, int afterLength,
            int flags) {
        final AndroidFuture<SurroundingText> future = new AndroidFuture<>();
        try {
            mIInputContext.getSurroundingText(createHeader(), beforeLength, afterLength, flags,
                    future);
        } catch (RemoteException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * Invokes
     * {@link IInputContext#getCursorCapsMode(InputConnectionCommandHeader, int, AndroidFuture)}.
     *
     * @param reqModes {@code reqModes} parameter to be passed.
     * @return {@link AndroidFuture<Integer>} that can be used to retrieve the invocation
     *         result. {@link RemoteException} will be treated as an error.
     */
    @AnyThread
    @NonNull
    public AndroidFuture<Integer> getCursorCapsMode(int reqModes) {
        final AndroidFuture<Integer> future = new AndroidFuture<>();
        try {
            mIInputContext.getCursorCapsMode(createHeader(), reqModes, future);
        } catch (RemoteException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * Invokes {@link IInputContext#getExtractedText(InputConnectionCommandHeader,
     * ExtractedTextRequest, int, AndroidFuture)}.
     *
     * @param request {@code request} parameter to be passed.
     * @param flags {@code flags} parameter to be passed.
     * @return {@link AndroidFuture<ExtractedText>} that can be used to retrieve the invocation
     *         result. {@link RemoteException} will be treated as an error.
     */
    @AnyThread
    @NonNull
    public AndroidFuture<ExtractedText> getExtractedText(ExtractedTextRequest request,
            int flags) {
        final AndroidFuture<ExtractedText> future = new AndroidFuture<>();
        try {
            mIInputContext.getExtractedText(createHeader(), request, flags, future);
        } catch (RemoteException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * Invokes {@link IInputContext#commitText(InputConnectionCommandHeader, CharSequence, int)}.
     *
     * @param text {@code text} parameter to be passed.
     * @param newCursorPosition {@code newCursorPosition} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean commitText(CharSequence text, int newCursorPosition) {
        try {
            mIInputContext.commitText(createHeader(), text, newCursorPosition);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IInputContext#commitCompletion(InputConnectionCommandHeader, CompletionInfo)}.
     *
     * @param text {@code text} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean commitCompletion(CompletionInfo text) {
        try {
            mIInputContext.commitCompletion(createHeader(), text);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IInputContext#commitCorrection(InputConnectionCommandHeader, CorrectionInfo)}.
     *
     * @param correctionInfo {@code correctionInfo} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean commitCorrection(CorrectionInfo correctionInfo) {
        try {
            mIInputContext.commitCorrection(createHeader(), correctionInfo);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IInputContext#setSelection(InputConnectionCommandHeader, int, int)}.
     *
     * @param start {@code start} parameter to be passed.
     * @param end {@code start} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean setSelection(int start, int end) {
        try {
            mIInputContext.setSelection(createHeader(), start, end);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IInputContext#performEditorAction(InputConnectionCommandHeader, int)}.
     *
     * @param actionCode {@code start} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean performEditorAction(int actionCode) {
        try {
            mIInputContext.performEditorAction(createHeader(), actionCode);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IInputContext#performContextMenuAction(InputConnectionCommandHeader, int)}.
     *
     * @param id {@code id} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean performContextMenuAction(int id) {
        try {
            mIInputContext.performContextMenuAction(createHeader(), id);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IInputContext#setComposingRegion(InputConnectionCommandHeader, int, int)}.
     *
     * @param start {@code id} parameter to be passed.
     * @param end {@code id} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean setComposingRegion(int start, int end) {
        try {
            mIInputContext.setComposingRegion(createHeader(), start, end);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes
     * {@link IInputContext#setComposingText(InputConnectionCommandHeader, CharSequence, int)}.
     *
     * @param text {@code text} parameter to be passed.
     * @param newCursorPosition {@code newCursorPosition} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean setComposingText(CharSequence text, int newCursorPosition) {
        try {
            mIInputContext.setComposingText(createHeader(), text, newCursorPosition);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IInputContext#finishComposingText(InputConnectionCommandHeader)}.
     *
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean finishComposingText() {
        try {
            mIInputContext.finishComposingText(createHeader());
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IInputContext#beginBatchEdit(InputConnectionCommandHeader)}.
     *
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean beginBatchEdit() {
        try {
            mIInputContext.beginBatchEdit(createHeader());
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IInputContext#endBatchEdit(InputConnectionCommandHeader)}.
     *
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean endBatchEdit() {
        try {
            mIInputContext.endBatchEdit(createHeader());
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IInputContext#sendKeyEvent(InputConnectionCommandHeader, KeyEvent)}.
     *
     * @param event {@code event} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean sendKeyEvent(KeyEvent event) {
        try {
            mIInputContext.sendKeyEvent(createHeader(), event);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IInputContext#clearMetaKeyStates(InputConnectionCommandHeader, int)}.
     *
     * @param states {@code states} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean clearMetaKeyStates(int states) {
        try {
            mIInputContext.clearMetaKeyStates(createHeader(), states);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IInputContext#deleteSurroundingText(InputConnectionCommandHeader, int, int)}.
     *
     * @param beforeLength {@code beforeLength} parameter to be passed.
     * @param afterLength {@code afterLength} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean deleteSurroundingText(int beforeLength, int afterLength) {
        try {
            mIInputContext.deleteSurroundingText(createHeader(), beforeLength, afterLength);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IInputContext#deleteSurroundingTextInCodePoints(InputConnectionCommandHeader,
     * int, int)}.
     *
     * @param beforeLength {@code beforeLength} parameter to be passed.
     * @param afterLength {@code afterLength} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
        try {
            mIInputContext.deleteSurroundingTextInCodePoints(createHeader(), beforeLength,
                    afterLength);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IInputContext#performSpellCheck(InputConnectionCommandHeader)}.
     *
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean performSpellCheck() {
        try {
            mIInputContext.performSpellCheck(createHeader());
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes
     * {@link IInputContext#performPrivateCommand(InputConnectionCommandHeader, String, Bundle)}.
     *
     * @param action {@code action} parameter to be passed.
     * @param data {@code data} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean performPrivateCommand(String action, Bundle data) {
        try {
            mIInputContext.performPrivateCommand(createHeader(), action, data);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IInputContext#requestCursorUpdates(InputConnectionCommandHeader, int, int,
     * AndroidFuture)}.
     *
     * @param cursorUpdateMode {@code cursorUpdateMode} parameter to be passed.
     * @param imeDisplayId the display ID that is associated with the IME.
     * @return {@link AndroidFuture<Boolean>} that can be used to retrieve the invocation
     *         result. {@link RemoteException} will be treated as an error.
     */
    @AnyThread
    @NonNull
    public AndroidFuture<Boolean> requestCursorUpdates(int cursorUpdateMode, int imeDisplayId) {
        final AndroidFuture<Boolean> future = new AndroidFuture<>();
        try {
            mIInputContext.requestCursorUpdates(createHeader(), cursorUpdateMode, imeDisplayId,
                    future);
        } catch (RemoteException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * Invokes {@link IInputContext#commitContent(InputConnectionCommandHeader, InputContentInfo,
     * int, Bundle, AndroidFuture)}.
     *
     * @param inputContentInfo {@code inputContentInfo} parameter to be passed.
     * @param flags {@code flags} parameter to be passed.
     * @param opts {@code opts} parameter to be passed.
     * @return {@link AndroidFuture<Boolean>} that can be used to retrieve the invocation
     *         result. {@link RemoteException} will be treated as an error.
     */
    @AnyThread
    @NonNull
    public AndroidFuture<Boolean> commitContent(InputContentInfo inputContentInfo, int flags,
            Bundle opts) {
        final AndroidFuture<Boolean> future = new AndroidFuture<>();
        try {
            mIInputContext.commitContent(createHeader(), inputContentInfo, flags, opts, future);
        } catch (RemoteException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * Invokes {@link IInputContext#setImeConsumesInput(InputConnectionCommandHeader, boolean)}.
     *
     * @param imeConsumesInput {@code imeConsumesInput} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean setImeConsumesInput(boolean imeConsumesInput) {
        try {
            mIInputContext.setImeConsumesInput(createHeader(), imeConsumesInput);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }
}
