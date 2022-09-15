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

package android.inputmethodservice;

import android.annotation.AnyThread;
import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.view.KeyEvent;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.CorrectionInfo;
import android.view.inputmethod.DeleteGesture;
import android.view.inputmethod.DeleteRangeGesture;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.HandwritingGesture;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputContentInfo;
import android.view.inputmethod.InsertGesture;
import android.view.inputmethod.JoinOrSplitGesture;
import android.view.inputmethod.RemoveSpaceGesture;
import android.view.inputmethod.SelectGesture;
import android.view.inputmethod.SelectRangeGesture;
import android.view.inputmethod.SurroundingText;
import android.view.inputmethod.TextAttribute;

import com.android.internal.infra.AndroidFuture;
import com.android.internal.inputmethod.IRemoteInputConnection;
import com.android.internal.inputmethod.InputConnectionCommandHeader;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.IntConsumer;

/**
 * A stateless wrapper of {@link com.android.internal.inputmethod.IRemoteInputConnection} to
 * encapsulate boilerplate code around {@link AndroidFuture} and {@link RemoteException}.
 */
final class IRemoteInputConnectionInvoker {

    @NonNull
    private final IRemoteInputConnection mConnection;
    private final int mSessionId;

    private IRemoteInputConnectionInvoker(@NonNull IRemoteInputConnection inputConnection,
            int sessionId) {
        mConnection = inputConnection;
        mSessionId = sessionId;
    }

    /**
     * Subclass of {@link ResultReceiver} used by
     * {@link #performHandwritingGesture(HandwritingGesture, Executor, IntConsumer)} for providing
     * callback.
     */
    private static final class IntResultReceiver extends ResultReceiver {
        @NonNull
        private IntConsumer mConsumer;
        @NonNull
        private Executor mExecutor;

        IntResultReceiver(@NonNull Executor executor, @NonNull IntConsumer consumer) {
            super(null);
            mExecutor = executor;
            mConsumer = consumer;
        }

        @Override
        protected void onReceiveResult(int resultCode, Bundle resultData) {
            if (mExecutor != null && mConsumer != null) {
                mExecutor.execute(() -> mConsumer.accept(resultCode));
                // provide callback only once.
                clear();
            }
        }

        private void clear() {
            mExecutor = null;
            mConsumer = null;
        }
    };

    /**
     * Creates a new instance of {@link IRemoteInputConnectionInvoker} for the given
     * {@link IRemoteInputConnection}.
     *
     * @param connection {@link IRemoteInputConnection} to be wrapped.
     * @return A new instance of {@link IRemoteInputConnectionInvoker}.
     */
    public static IRemoteInputConnectionInvoker create(@NonNull IRemoteInputConnection connection) {
        Objects.requireNonNull(connection);
        return new IRemoteInputConnectionInvoker(connection, 0);
    }

    /**
     * Creates a new instance of {@link IRemoteInputConnectionInvoker} with the given
     * {@code sessionId}.
     *
     * @param sessionId the new session ID to be used.
     * @return A new instance of {@link IRemoteInputConnectionInvoker}.
     */
    @NonNull
    public IRemoteInputConnectionInvoker cloneWithSessionId(int sessionId) {
        return new IRemoteInputConnectionInvoker(mConnection, sessionId);
    }

    /**
     * @param connection {@code IRemoteInputConnection} to be compared with
     * @return {@code true} if the underlying {@code IRemoteInputConnection} is the same.
     *         {@code false} if {@code inputContext} is {@code null}.
     */
    @AnyThread
    public boolean isSameConnection(@NonNull IRemoteInputConnection connection) {
        if (connection == null) {
            return false;
        }
        return mConnection.asBinder() == connection.asBinder();
    }

    @NonNull
    InputConnectionCommandHeader createHeader() {
        return new InputConnectionCommandHeader(mSessionId);
    }

    /**
     * Invokes {@link IRemoteInputConnection#getTextAfterCursor(InputConnectionCommandHeader, int,
     * int, AndroidFuture)}.
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
            mConnection.getTextAfterCursor(createHeader(), length, flags, future);
        } catch (RemoteException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * Invokes {@link IRemoteInputConnection#getTextBeforeCursor(InputConnectionCommandHeader, int,
     * int, AndroidFuture)}.
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
            mConnection.getTextBeforeCursor(createHeader(), length, flags, future);
        } catch (RemoteException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * Invokes {@link IRemoteInputConnection#getSelectedText(InputConnectionCommandHeader, int,
     * AndroidFuture)}.
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
            mConnection.getSelectedText(createHeader(), flags, future);
        } catch (RemoteException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * Invokes
     * {@link IRemoteInputConnection#getSurroundingText(InputConnectionCommandHeader, int, int, int,
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
            mConnection.getSurroundingText(createHeader(), beforeLength, afterLength, flags,
                    future);
        } catch (RemoteException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * Invokes {@link IRemoteInputConnection#getCursorCapsMode(InputConnectionCommandHeader, int,
     * AndroidFuture)}.
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
            mConnection.getCursorCapsMode(createHeader(), reqModes, future);
        } catch (RemoteException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * Invokes {@link IRemoteInputConnection#getExtractedText(InputConnectionCommandHeader,
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
            mConnection.getExtractedText(createHeader(), request, flags, future);
        } catch (RemoteException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * Invokes
     * {@link IRemoteInputConnection#commitText(InputConnectionCommandHeader, CharSequence, int)}.
     *
     * @param text {@code text} parameter to be passed.
     * @param newCursorPosition {@code newCursorPosition} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean commitText(CharSequence text, int newCursorPosition) {
        try {
            mConnection.commitText(createHeader(), text, newCursorPosition);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IRemoteInputConnection#commitTextWithTextAttribute(
     * InputConnectionCommandHeader, int, CharSequence)}.
     *
     * @param text {@code text} parameter to be passed.
     * @param newCursorPosition {@code newCursorPosition} parameter to be passed.
     * @param textAttribute The extra information about the text.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean commitText(CharSequence text, int newCursorPosition,
            @Nullable TextAttribute textAttribute) {
        try {
            mConnection.commitTextWithTextAttribute(
                    createHeader(), text, newCursorPosition, textAttribute);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IRemoteInputConnection#commitCompletion(InputConnectionCommandHeader,
     * CompletionInfo)}.
     *
     * @param text {@code text} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean commitCompletion(CompletionInfo text) {
        try {
            mConnection.commitCompletion(createHeader(), text);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IRemoteInputConnection#commitCorrection(InputConnectionCommandHeader,
     * CorrectionInfo)}.
     *
     * @param correctionInfo {@code correctionInfo} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean commitCorrection(CorrectionInfo correctionInfo) {
        try {
            mConnection.commitCorrection(createHeader(), correctionInfo);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IRemoteInputConnection#setSelection(InputConnectionCommandHeader, int, int)}.
     *
     * @param start {@code start} parameter to be passed.
     * @param end {@code start} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean setSelection(int start, int end) {
        try {
            mConnection.setSelection(createHeader(), start, end);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes
     * {@link IRemoteInputConnection#performEditorAction(InputConnectionCommandHeader, int)}.
     *
     * @param actionCode {@code start} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean performEditorAction(int actionCode) {
        try {
            mConnection.performEditorAction(createHeader(), actionCode);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes
     * {@link IRemoteInputConnection#performContextMenuAction(InputConnectionCommandHeader, int)}.
     *
     * @param id {@code id} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean performContextMenuAction(int id) {
        try {
            mConnection.performContextMenuAction(createHeader(), id);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes
     * {@link IRemoteInputConnection#setComposingRegion(InputConnectionCommandHeader, int, int)}.
     *
     * @param start {@code id} parameter to be passed.
     * @param end {@code id} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean setComposingRegion(int start, int end) {
        try {
            mConnection.setComposingRegion(createHeader(), start, end);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IRemoteInputConnection#setComposingRegionWithTextAttribute(
     * InputConnectionCommandHeader, int, int, TextAttribute)}.
     *
     * @param start {@code id} parameter to be passed.
     * @param end {@code id} parameter to be passed.
     * @param textAttribute The extra information about the text.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean setComposingRegion(int start, int end, @Nullable TextAttribute textAttribute) {
        try {
            mConnection.setComposingRegionWithTextAttribute(
                    createHeader(), start, end, textAttribute);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IRemoteInputConnection#setComposingText(InputConnectionCommandHeader,
     * CharSequence, int)}.
     *
     * @param text {@code text} parameter to be passed.
     * @param newCursorPosition {@code newCursorPosition} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean setComposingText(CharSequence text, int newCursorPosition) {
        try {
            mConnection.setComposingText(createHeader(), text, newCursorPosition);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IRemoteInputConnection#setComposingTextWithTextAttribute(
     * InputConnectionCommandHeader, CharSequence, int, TextAttribute)}.
     *
     * @param text {@code text} parameter to be passed.
     * @param newCursorPosition {@code newCursorPosition} parameter to be passed.
     * @param textAttribute The extra information about the text.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean setComposingText(CharSequence text, int newCursorPosition,
            @Nullable TextAttribute textAttribute) {
        try {
            mConnection.setComposingTextWithTextAttribute(
                    createHeader(), text, newCursorPosition, textAttribute);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IRemoteInputConnection#finishComposingText(InputConnectionCommandHeader)}.
     *
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean finishComposingText() {
        try {
            mConnection.finishComposingText(createHeader());
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IRemoteInputConnection#beginBatchEdit(InputConnectionCommandHeader)}.
     *
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean beginBatchEdit() {
        try {
            mConnection.beginBatchEdit(createHeader());
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IRemoteInputConnection#endBatchEdit(InputConnectionCommandHeader)}.
     *
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean endBatchEdit() {
        try {
            mConnection.endBatchEdit(createHeader());
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IRemoteInputConnection#sendKeyEvent(InputConnectionCommandHeader, KeyEvent)}.
     *
     * @param event {@code event} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean sendKeyEvent(KeyEvent event) {
        try {
            mConnection.sendKeyEvent(createHeader(), event);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IRemoteInputConnection#clearMetaKeyStates(InputConnectionCommandHeader, int)}.
     *
     * @param states {@code states} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean clearMetaKeyStates(int states) {
        try {
            mConnection.clearMetaKeyStates(createHeader(), states);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes
     * {@link IRemoteInputConnection#deleteSurroundingText(InputConnectionCommandHeader, int, int)}.
     *
     * @param beforeLength {@code beforeLength} parameter to be passed.
     * @param afterLength {@code afterLength} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean deleteSurroundingText(int beforeLength, int afterLength) {
        try {
            mConnection.deleteSurroundingText(createHeader(), beforeLength, afterLength);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IRemoteInputConnection#deleteSurroundingTextInCodePoints(
     * InputConnectionCommandHeader, int, int)}.
     *
     * @param beforeLength {@code beforeLength} parameter to be passed.
     * @param afterLength {@code afterLength} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
        try {
            mConnection.deleteSurroundingTextInCodePoints(createHeader(), beforeLength,
                    afterLength);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IRemoteInputConnection#performSpellCheck(InputConnectionCommandHeader)}.
     *
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean performSpellCheck() {
        try {
            mConnection.performSpellCheck(createHeader());
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes {@link IRemoteInputConnection#performPrivateCommand(InputConnectionCommandHeader,
     * String, Bundle)}.
     *
     * @param action {@code action} parameter to be passed.
     * @param data {@code data} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean performPrivateCommand(String action, Bundle data) {
        try {
            mConnection.performPrivateCommand(createHeader(), action, data);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Invokes one of {@link IRemoteInputConnection#performHandwritingSelectGesture},
     * {@link IRemoteInputConnection#performHandwritingSelectRangeGesture},
     * {@link IRemoteInputConnection#performHandwritingDeleteGesture},
     * {@link IRemoteInputConnection#performHandwritingDeleteRangeGesture},
     * {@link IRemoteInputConnection#performHandwritingInsertGesture},
     * {@link IRemoteInputConnection#performHandwritingRemoveSpaceGesture},
     * {@link IRemoteInputConnection#performHandwritingJoinOrSplitGesture}.
     */
    @AnyThread
    public void performHandwritingGesture(
            @NonNull HandwritingGesture gesture, @Nullable @CallbackExecutor Executor executor,
            @Nullable IntConsumer consumer) {

        ResultReceiver resultReceiver = null;
        if (consumer != null) {
            Objects.requireNonNull(executor);
            resultReceiver = new IntResultReceiver(executor, consumer);
        }
        try {
            if (gesture instanceof SelectGesture) {
                mConnection.performHandwritingSelectGesture(
                        createHeader(), (SelectGesture) gesture, resultReceiver);
            } else if (gesture instanceof SelectRangeGesture) {
                mConnection.performHandwritingSelectRangeGesture(
                        createHeader(), (SelectRangeGesture) gesture, resultReceiver);
            } else if (gesture instanceof InsertGesture) {
                mConnection.performHandwritingInsertGesture(
                        createHeader(), (InsertGesture) gesture, resultReceiver);
            } else if (gesture instanceof DeleteGesture) {
                mConnection.performHandwritingDeleteGesture(
                        createHeader(), (DeleteGesture) gesture, resultReceiver);
            } else if (gesture instanceof DeleteRangeGesture) {
                mConnection.performHandwritingDeleteRangeGesture(
                        createHeader(), (DeleteRangeGesture) gesture, resultReceiver);
            } else if (gesture instanceof RemoveSpaceGesture) {
                mConnection.performHandwritingRemoveSpaceGesture(
                        createHeader(), (RemoveSpaceGesture) gesture, resultReceiver);
            } else if (gesture instanceof JoinOrSplitGesture) {
                mConnection.performHandwritingJoinOrSplitGesture(
                        createHeader(), (JoinOrSplitGesture) gesture, resultReceiver);
            } else if (consumer != null && executor != null) {
                executor.execute(()
                        -> consumer.accept(InputConnection.HANDWRITING_GESTURE_RESULT_UNSUPPORTED));
            }
        } catch (RemoteException e) {
            if (consumer != null && executor != null) {
                executor.execute(() -> consumer.accept(
                        InputConnection.HANDWRITING_GESTURE_RESULT_CANCELLED));
            }
        }
    }

    /**
     * Invokes {@link IRemoteInputConnection#requestCursorUpdates(InputConnectionCommandHeader, int,
     * int, AndroidFuture)}.
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
            mConnection.requestCursorUpdates(createHeader(), cursorUpdateMode, imeDisplayId,
                    future);
        } catch (RemoteException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * Invokes {@link IRemoteInputConnection#requestCursorUpdatesWithFilter(
     * InputConnectionCommandHeader, int, int, int, AndroidFuture)}.
     *
     * @param cursorUpdateMode {@code cursorUpdateMode} parameter to be passed.
     * @param cursorUpdateFilter {@code cursorUpdateFilter} parameter to be passed.
     * @param imeDisplayId the display ID that is associated with the IME.
     * @return {@link AndroidFuture<Boolean>} that can be used to retrieve the invocation
     *         result. {@link RemoteException} will be treated as an error.
     */
    @AnyThread
    @NonNull
    public AndroidFuture<Boolean> requestCursorUpdates(int cursorUpdateMode, int cursorUpdateFilter,
            int imeDisplayId) {
        final AndroidFuture<Boolean> future = new AndroidFuture<>();
        try {
            mConnection.requestCursorUpdatesWithFilter(createHeader(), cursorUpdateMode,
                    cursorUpdateFilter, imeDisplayId, future);
        } catch (RemoteException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * Invokes {@link IRemoteInputConnection#commitContent(InputConnectionCommandHeader,
     * InputContentInfo, int, Bundle, AndroidFuture)}.
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
            mConnection.commitContent(createHeader(), inputContentInfo, flags, opts, future);
        } catch (RemoteException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * Invokes
     * {@link IRemoteInputConnection#setImeConsumesInput(InputConnectionCommandHeader, boolean)}.
     *
     * @param imeConsumesInput {@code imeConsumesInput} parameter to be passed.
     * @return {@code true} if the invocation is completed without {@link RemoteException}.
     *         {@code false} otherwise.
     */
    @AnyThread
    public boolean setImeConsumesInput(boolean imeConsumesInput) {
        try {
            mConnection.setImeConsumesInput(createHeader(), imeConsumesInput);
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }
}
