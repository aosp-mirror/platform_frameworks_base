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
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputContentInfo;

import com.android.internal.view.IInputContext;

import java.util.Objects;

/**
 * A stateless wrapper of {@link com.android.internal.view.IInputContext} to encapsulate boilerplate
 * code around {@link Completable} and {@link RemoteException}.
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
     * Invokes {@link IInputContext#getTextAfterCursor(int, int,
     * com.android.internal.inputmethod.ICharSequenceResultCallback)}.
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
        try {
            mIInputContext.getTextAfterCursor(length, flags, ResultCallbacks.of(value));
        } catch (RemoteException e) {
            value.onError(ThrowableHolder.of(e));
        }
        return value;
    }

    /**
     * Invokes {@link IInputContext#getTextBeforeCursor(int, int, ICharSequenceResultCallback)}.
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
        try {
            mIInputContext.getTextBeforeCursor(length, flags, ResultCallbacks.of(value));
        } catch (RemoteException e) {
            value.onError(ThrowableHolder.of(e));
        }
        return value;
    }

    /**
     * Invokes {@link IInputContext#getSelectedText(int, ICharSequenceResultCallback)}.
     *
     * @param flags {@code flags} parameter to be passed.
     * @return {@link Completable.CharSequence} that can be used to retrieve the invocation result.
     *         {@link RemoteException} will be treated as an error.
     */
    @AnyThread
    @NonNull
    public Completable.CharSequence getSelectedText(int flags) {
        final Completable.CharSequence value = Completable.createCharSequence();
        try {
            mIInputContext.getSelectedText(flags, ResultCallbacks.of(value));
        } catch (RemoteException e) {
            value.onError(ThrowableHolder.of(e));
        }
        return value;
    }

    /**
     * Invokes
     * {@link IInputContext#getSurroundingText(int, int, int, ISurroundingTextResultCallback)}.
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
        try {
            mIInputContext.getSurroundingText(beforeLength, afterLength, flags,
                    ResultCallbacks.of(value));
        } catch (RemoteException e) {
            value.onError(ThrowableHolder.of(e));
        }
        return value;
    }

    /**
     * Invokes {@link IInputContext#getCursorCapsMode(int, IIntResultCallback)}.
     *
     * @param reqModes {@code reqModes} parameter to be passed.
     * @return {@link Completable.Int} that can be used to retrieve the invocation result.
     *         {@link RemoteException} will be treated as an error.
     */
    @AnyThread
    @NonNull
    public Completable.Int getCursorCapsMode(int reqModes) {
        final Completable.Int value = Completable.createInt();
        try {
            mIInputContext.getCursorCapsMode(reqModes, ResultCallbacks.of(value));
        } catch (RemoteException e) {
            value.onError(ThrowableHolder.of(e));
        }
        return value;
    }

    /**
     * Invokes {@link IInputContext#getExtractedText(ExtractedTextRequest, int,
     * IExtractedTextResultCallback)}.
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
        try {
            mIInputContext.getExtractedText(request, flags, ResultCallbacks.of(value));
        } catch (RemoteException e) {
            value.onError(ThrowableHolder.of(e));
        }
        return value;
    }

    /**
     * Invokes {@link IInputContext#commitText(CharSequence, int)}.
     *
     * @param text {@code text} parameter to be passed.
     * @param newCursorPosition {@code newCursorPosition} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean commitText(CharSequence text, int newCursorPosition) {
        try {
            mIInputContext.commitText(text, newCursorPosition);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IInputContext#commitCompletion(CompletionInfo)}.
     *
     * @param text {@code text} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean commitCompletion(CompletionInfo text) {
        try {
            mIInputContext.commitCompletion(text);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IInputContext#commitCorrection(CorrectionInfo)}.
     *
     * @param correctionInfo {@code correctionInfo} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean commitCorrection(CorrectionInfo correctionInfo) {
        try {
            mIInputContext.commitCorrection(correctionInfo);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IInputContext#setSelection(int, int)}.
     *
     * @param start {@code start} parameter to be passed.
     * @param end {@code start} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean setSelection(int start, int end) {
        try {
            mIInputContext.setSelection(start, end);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IInputContext#performEditorAction(int)}.
     *
     * @param actionCode {@code start} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean performEditorAction(int actionCode) {
        try {
            mIInputContext.performEditorAction(actionCode);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IInputContext#performContextMenuAction(id)}.
     *
     * @param id {@code id} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean performContextMenuAction(int id) {
        try {
            mIInputContext.performContextMenuAction(id);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IInputContext#setComposingRegion(int, int)}.
     *
     * @param start {@code id} parameter to be passed.
     * @param end {@code id} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean setComposingRegion(int start, int end) {
        try {
            mIInputContext.setComposingRegion(start, end);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IInputContext#setComposingText(CharSequence, int)}.
     *
     * @param text {@code text} parameter to be passed.
     * @param newCursorPosition {@code newCursorPosition} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean setComposingText(CharSequence text, int newCursorPosition) {
        try {
            mIInputContext.setComposingText(text, newCursorPosition);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IInputContext#finishComposingText()}.
     *
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean finishComposingText() {
        try {
            mIInputContext.finishComposingText();
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IInputContext#beginBatchEdit()}.
     *
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean beginBatchEdit() {
        try {
            mIInputContext.beginBatchEdit();
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IInputContext#endBatchEdit()}.
     *
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean endBatchEdit() {
        try {
            mIInputContext.endBatchEdit();
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IInputContext#sendKeyEvent(KeyEvent)}.
     *
     * @param event {@code event} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean sendKeyEvent(KeyEvent event) {
        try {
            mIInputContext.sendKeyEvent(event);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IInputContext#clearMetaKeyStates(int)}.
     *
     * @param states {@code states} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean clearMetaKeyStates(int states) {
        try {
            mIInputContext.clearMetaKeyStates(states);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IInputContext#deleteSurroundingText(int, int)}.
     *
     * @param beforeLength {@code beforeLength} parameter to be passed.
     * @param afterLength {@code afterLength} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean deleteSurroundingText(int beforeLength, int afterLength) {
        try {
            mIInputContext.deleteSurroundingText(beforeLength, afterLength);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IInputContext#deleteSurroundingTextInCodePoints(int, int)}.
     *
     * @param beforeLength {@code beforeLength} parameter to be passed.
     * @param afterLength {@code afterLength} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
        try {
            mIInputContext.deleteSurroundingTextInCodePoints(beforeLength, afterLength);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IInputContext#performSpellCheck()}.
     *
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean performSpellCheck() {
        try {
            mIInputContext.performSpellCheck();
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IInputContext#performPrivateCommand(String, Bundle)}.
     *
     * @param action {@code action} parameter to be passed.
     * @param data {@code data} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean performPrivateCommand(String action, Bundle data) {
        try {
            mIInputContext.performPrivateCommand(action, data);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IInputContext#requestUpdateCursorAnchorInfo(int, IIntResultCallback)}.
     *
     * @param cursorUpdateMode {@code cursorUpdateMode} parameter to be passed.
     * @return {@link Completable.Int} that can be used to retrieve the invocation result.
     *         {@link RemoteException} will be treated as an error.
     */
    @AnyThread
    @NonNull
    public Completable.Int requestUpdateCursorAnchorInfo(int cursorUpdateMode) {
        final Completable.Int value = Completable.createInt();
        try {
            mIInputContext.requestUpdateCursorAnchorInfo(cursorUpdateMode,
                    ResultCallbacks.of(value));
        } catch (RemoteException e) {
            value.onError(ThrowableHolder.of(e));
        }
        return value;
    }

    /**
     * Invokes
     * {@link IInputContext#commitContent(InputContentInfo, int, Bundle, IIntResultCallback)}.
     *
     * @param inputContentInfo {@code inputContentInfo} parameter to be passed.
     * @param flags {@code flags} parameter to be passed.
     * @param opts {@code opts} parameter to be passed.
     * @return {@link Completable.Int} that can be used to retrieve the invocation result.
     *         {@link RemoteException} will be treated as an error.
     */
    @AnyThread
    @NonNull
    public Completable.Int commitContent(InputContentInfo inputContentInfo, int flags,
            Bundle opts) {
        final Completable.Int value = Completable.createInt();
        try {
            mIInputContext.commitContent(inputContentInfo, flags, opts, ResultCallbacks.of(value));
        } catch (RemoteException e) {
            value.onError(ThrowableHolder.of(e));
        }
        return value;
    }

    /**
     * Invokes {@link IInputContext#setImeConsumesInput(boolean)}.
     *
     * @param imeConsumesInput {@code imeConsumesInput} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
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
}
