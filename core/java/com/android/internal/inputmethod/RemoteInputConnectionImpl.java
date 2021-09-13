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

package com.android.internal.inputmethod;

import static com.android.internal.inputmethod.InputConnectionProtoDumper.buildGetCursorCapsModeProto;
import static com.android.internal.inputmethod.InputConnectionProtoDumper.buildGetExtractedTextProto;
import static com.android.internal.inputmethod.InputConnectionProtoDumper.buildGetSelectedTextProto;
import static com.android.internal.inputmethod.InputConnectionProtoDumper.buildGetSurroundingTextProto;
import static com.android.internal.inputmethod.InputConnectionProtoDumper.buildGetTextAfterCursorProto;
import static com.android.internal.inputmethod.InputConnectionProtoDumper.buildGetTextBeforeCursorProto;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Trace;
import android.util.Log;
import android.util.proto.ProtoOutputStream;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.CorrectionInfo;
import android.view.inputmethod.DumpableInputConnection;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionInspector;
import android.view.inputmethod.InputConnectionInspector.MissingMethodFlags;
import android.view.inputmethod.InputContentInfo;
import android.view.inputmethod.InputMethodManager;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.infra.AndroidFuture;
import com.android.internal.view.IInputContext;

import java.lang.ref.WeakReference;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Takes care of remote method invocations of {@link InputConnection} in the IME client side.
 *
 * <p>{@link android.inputmethodservice.RemoteInputConnection} code is executed in the IME process.
 * It makes IInputContext binder calls under the hood. {@link RemoteInputConnectionImpl} receives
 * {@link IInputContext} binder calls in the IME client (editor app) process, and forwards them to
 * {@link InputConnection} that the IME client provided, on the {@link Looper} associated to the
 * {@link InputConnection}.</p>
 */
public final class RemoteInputConnectionImpl extends IInputContext.Stub {
    private static final String TAG = "RemoteInputConnectionImpl";
    private static final boolean DEBUG = false;

    @GuardedBy("mLock")
    @Nullable
    private InputConnection mInputConnection;

    @NonNull
    private final Looper mLooper;
    private final Handler mH;

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private boolean mFinished = false;

    private final InputMethodManager mParentInputMethodManager;
    private final WeakReference<View> mServedView;

    public RemoteInputConnectionImpl(@NonNull Looper looper,
            @NonNull InputConnection inputConnection,
            @NonNull InputMethodManager inputMethodManager, @Nullable View servedView) {
        mInputConnection = inputConnection;
        mLooper = looper;
        mH = new Handler(mLooper);
        mParentInputMethodManager = inputMethodManager;
        mServedView = new WeakReference<>(servedView);
    }

    /**
     * @return {@link InputConnection} to which incoming IPCs will be dispatched.
     */
    @Nullable
    private InputConnection getInputConnection() {
        synchronized (mLock) {
            return mInputConnection;
        }
    }

    /**
     * @return {@code true} until the target {@link InputConnection} receives
     * {@link InputConnection#closeConnection()} as a result of {@link #deactivate()}.
     */
    public boolean isFinished() {
        synchronized (mLock) {
            return mFinished;
        }
    }

    public boolean isActive() {
        return mParentInputMethodManager.isActive() && !isFinished();
    }

    public View getServedView() {
        return mServedView.get();
    }

    /**
     * Called when this object needs to be permanently deactivated.
     *
     * <p>Multiple invocations will be simply ignored.</p>
     */
    public void deactivate() {
        if (isFinished()) {
            // This is a small performance optimization.  Still only the 1st call of
            // reportFinish() will take effect.
            return;
        }
        dispatch(() -> {
            // Note that we do not need to worry about race condition here, because 1) mFinished is
            // updated only inside this block, and 2) the code here is running on a Handler hence we
            // assume multiple closeConnection() tasks will not be handled at the same time.
            if (isFinished()) {
                return;
            }
            Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#closeConnection");
            try {
                InputConnection ic = getInputConnection();
                // Note we do NOT check isActive() here, because this is safe
                // for an IME to call at any time, and we need to allow it
                // through to clean up our state after the IME has switched to
                // another client.
                if (ic == null) {
                    return;
                }
                @MissingMethodFlags
                final int missingMethods = InputConnectionInspector.getMissingMethodFlags(ic);
                if ((missingMethods & MissingMethodFlags.CLOSE_CONNECTION) == 0) {
                    ic.closeConnection();
                }
            } finally {
                synchronized (mLock) {
                    mInputConnection = null;
                    mFinished = true;
                }
                Trace.traceEnd(Trace.TRACE_TAG_INPUT);
            }

            // Notify the app that the InputConnection was closed.
            final View servedView = mServedView.get();
            if (servedView != null) {
                final Handler handler = servedView.getHandler();
                // The handler is null if the view is already detached. When that's the case, for
                // now, we simply don't dispatch this callback.
                if (handler != null) {
                    if (DEBUG) {
                        Log.v(TAG, "Calling View.onInputConnectionClosed: view=" + servedView);
                    }
                    if (handler.getLooper().isCurrentThread()) {
                        servedView.onInputConnectionClosedInternal();
                    } else {
                        handler.post(servedView::onInputConnectionClosedInternal);
                    }
                }
            }
        });
    }

    @Override
    public String toString() {
        return "RemoteInputConnectionImpl{"
                + "connection=" + getInputConnection()
                + " finished=" + isFinished()
                + " mParentInputMethodManager.isActive()=" + mParentInputMethodManager.isActive()
                + " mServedView=" + mServedView.get()
                + "}";
    }

    /**
     * Called by {@link InputMethodManager} to dump the editor state.
     *
     * @param proto {@link ProtoOutputStream} to which the editor state should be dumped.
     * @param fieldId the ID to be passed to
     *                {@link DumpableInputConnection#dumpDebug(ProtoOutputStream, long)}.
     */
    public void dumpDebug(ProtoOutputStream proto, long fieldId) {
        synchronized (mLock) {
            // Check that the call is initiated in the target thread of the current InputConnection
            // {@link InputConnection#getHandler} since the messages to IInputConnectionWrapper are
            // executed on this thread. Otherwise the messages are dispatched to the correct thread
            // in IInputConnectionWrapper, but this is not wanted while dumpng, for performance
            // reasons.
            if ((mInputConnection instanceof DumpableInputConnection)
                    && mLooper.isCurrentThread()) {
                ((DumpableInputConnection) mInputConnection).dumpDebug(proto, fieldId);
            }
        }
    }

    /**
     * Invoke {@link InputConnection#reportFullscreenMode(boolean)} or schedule it on the target
     * thread associated with {@link InputConnection#getHandler()}.
     *
     * @param enabled the parameter to be passed to
     *                {@link InputConnection#reportFullscreenMode(boolean)}.
     */
    public void dispatchReportFullscreenMode(boolean enabled) {
        dispatch(() -> {
            final InputConnection ic = getInputConnection();
            if (ic == null || !isActive()) {
                return;
            }
            ic.reportFullscreenMode(enabled);
        });
    }

    @Override
    public void getTextAfterCursor(int length, int flags,
            AndroidFuture future /* T=CharSequence */) {
        dispatchWithTracing("getTextAfterCursor", future, () -> {
            final InputConnection ic = getInputConnection();
            if (ic == null || !isActive()) {
                Log.w(TAG, "getTextAfterCursor on inactive InputConnection");
                return null;
            }
            return ic.getTextAfterCursor(length, flags);
        }, useImeTracing() ? result -> buildGetTextAfterCursorProto(length, flags, result) : null);
    }

    @Override
    public void getTextBeforeCursor(int length, int flags,
            AndroidFuture future /* T=CharSequence */) {
        dispatchWithTracing("getTextBeforeCursor", future, () -> {
            final InputConnection ic = getInputConnection();
            if (ic == null || !isActive()) {
                Log.w(TAG, "getTextBeforeCursor on inactive InputConnection");
                return null;
            }
            return ic.getTextBeforeCursor(length, flags);
        }, useImeTracing() ? result -> buildGetTextBeforeCursorProto(length, flags, result) : null);
    }

    @Override
    public void getSelectedText(int flags, AndroidFuture future /* T=CharSequence */) {
        dispatchWithTracing("getSelectedText", future, () -> {
            final InputConnection ic = getInputConnection();
            if (ic == null || !isActive()) {
                Log.w(TAG, "getSelectedText on inactive InputConnection");
                return null;
            }
            return ic.getSelectedText(flags);
        }, useImeTracing() ? result -> buildGetSelectedTextProto(flags, result) : null);
    }

    @Override
    public void getSurroundingText(int beforeLength, int afterLength, int flags,
            AndroidFuture future /* T=SurroundingText */) {
        dispatchWithTracing("getSurroundingText", future, () -> {
            final InputConnection ic = getInputConnection();
            if (ic == null || !isActive()) {
                Log.w(TAG, "getSurroundingText on inactive InputConnection");
                return null;
            }
            return ic.getSurroundingText(beforeLength, afterLength, flags);
        }, useImeTracing() ? result -> buildGetSurroundingTextProto(
                beforeLength, afterLength, flags, result) : null);
    }

    @Override
    public void getCursorCapsMode(int reqModes, AndroidFuture future /* T=Integer */) {
        dispatchWithTracing("getCursorCapsMode", future, () -> {
            final InputConnection ic = getInputConnection();
            if (ic == null || !isActive()) {
                Log.w(TAG, "getCursorCapsMode on inactive InputConnection");
                return 0;
            }
            return ic.getCursorCapsMode(reqModes);
        }, useImeTracing() ? result -> buildGetCursorCapsModeProto(reqModes, result) : null);
    }

    @Override
    public void getExtractedText(ExtractedTextRequest request, int flags,
            AndroidFuture future /* T=ExtractedText */) {
        dispatchWithTracing("getExtractedText", future, () -> {
            final InputConnection ic = getInputConnection();
            if (ic == null || !isActive()) {
                Log.w(TAG, "getExtractedText on inactive InputConnection");
                return null;
            }
            return ic.getExtractedText(request, flags);
        }, useImeTracing() ? result -> buildGetExtractedTextProto(request, flags, result) : null);
    }

    @Override
    public void commitText(CharSequence text, int newCursorPosition) {
        dispatchWithTracing("commitText", () -> {
            InputConnection ic = getInputConnection();
            if (ic == null || !isActive()) {
                Log.w(TAG, "commitText on inactive InputConnection");
                return;
            }
            ic.commitText(text, newCursorPosition);
        });
    }

    @Override
    public void commitCompletion(CompletionInfo text) {
        dispatchWithTracing("commitCompletion", () -> {
            InputConnection ic = getInputConnection();
            if (ic == null || !isActive()) {
                Log.w(TAG, "commitCompletion on inactive InputConnection");
                return;
            }
            ic.commitCompletion(text);
        });
    }

    @Override
    public void commitCorrection(CorrectionInfo info) {
        dispatchWithTracing("commitCorrection", () -> {
            InputConnection ic = getInputConnection();
            if (ic == null || !isActive()) {
                Log.w(TAG, "commitCorrection on inactive InputConnection");
                return;
            }
            ic.commitCorrection(info);
        });
    }

    @Override
    public void setSelection(int start, int end) {
        dispatchWithTracing("setSelection", () -> {
            InputConnection ic = getInputConnection();
            if (ic == null || !isActive()) {
                Log.w(TAG, "setSelection on inactive InputConnection");
                return;
            }
            ic.setSelection(start, end);
        });
    }

    @Override
    public void performEditorAction(int id) {
        dispatchWithTracing("performEditorAction", () -> {
            InputConnection ic = getInputConnection();
            if (ic == null || !isActive()) {
                Log.w(TAG, "performEditorAction on inactive InputConnection");
                return;
            }
            ic.performEditorAction(id);
        });
    }

    @Override
    public void performContextMenuAction(int id) {
        dispatchWithTracing("performContextMenuAction", () -> {
            InputConnection ic = getInputConnection();
            if (ic == null || !isActive()) {
                Log.w(TAG, "performContextMenuAction on inactive InputConnection");
                return;
            }
            ic.performContextMenuAction(id);
        });
    }

    @Override
    public void setComposingRegion(int start, int end) {
        dispatchWithTracing("setComposingRegion", () -> {
            InputConnection ic = getInputConnection();
            if (ic == null || !isActive()) {
                Log.w(TAG, "setComposingRegion on inactive InputConnection");
                return;
            }
            ic.setComposingRegion(start, end);
        });
    }

    @Override
    public void setComposingText(CharSequence text, int newCursorPosition) {
        dispatchWithTracing("setComposingText", () -> {
            InputConnection ic = getInputConnection();
            if (ic == null || !isActive()) {
                Log.w(TAG, "setComposingText on inactive InputConnection");
                return;
            }
            ic.setComposingText(text, newCursorPosition);
        });
    }

    @Override
    public void finishComposingText() {
        dispatchWithTracing("finishComposingText", () -> {
            if (isFinished()) {
                // In this case, #finishComposingText() is guaranteed to be called already.
                // There should be no negative impact if we ignore this call silently.
                if (DEBUG) {
                    Log.w(TAG, "Bug 35301295: Redundant finishComposingText.");
                }
                return;
            }
            InputConnection ic = getInputConnection();
            // Note we do NOT check isActive() here, because this is safe
            // for an IME to call at any time, and we need to allow it
            // through to clean up our state after the IME has switched to
            // another client.
            if (ic == null) {
                Log.w(TAG, "finishComposingText on inactive InputConnection");
                return;
            }
            ic.finishComposingText();
        });
    }

    @Override
    public void sendKeyEvent(KeyEvent event) {
        dispatchWithTracing("sendKeyEvent", () -> {
            InputConnection ic = getInputConnection();
            if (ic == null || !isActive()) {
                Log.w(TAG, "sendKeyEvent on inactive InputConnection");
                return;
            }
            ic.sendKeyEvent(event);
        });
    }

    @Override
    public void clearMetaKeyStates(int states) {
        dispatchWithTracing("clearMetaKeyStates", () -> {
            InputConnection ic = getInputConnection();
            if (ic == null || !isActive()) {
                Log.w(TAG, "clearMetaKeyStates on inactive InputConnection");
                return;
            }
            ic.clearMetaKeyStates(states);
        });
    }

    @Override
    public void deleteSurroundingText(int beforeLength, int afterLength) {
        dispatchWithTracing("deleteSurroundingText", () -> {
            InputConnection ic = getInputConnection();
            if (ic == null || !isActive()) {
                Log.w(TAG, "deleteSurroundingText on inactive InputConnection");
                return;
            }
            ic.deleteSurroundingText(beforeLength, afterLength);
        });
    }

    @Override
    public void deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
        dispatchWithTracing("deleteSurroundingTextInCodePoints", () -> {
            InputConnection ic = getInputConnection();
            if (ic == null || !isActive()) {
                Log.w(TAG, "deleteSurroundingTextInCodePoints on inactive InputConnection");
                return;
            }
            ic.deleteSurroundingTextInCodePoints(beforeLength, afterLength);
        });
    }

    @Override
    public void beginBatchEdit() {
        dispatchWithTracing("beginBatchEdit", () -> {
            InputConnection ic = getInputConnection();
            if (ic == null || !isActive()) {
                Log.w(TAG, "beginBatchEdit on inactive InputConnection");
                return;
            }
            ic.beginBatchEdit();
        });
    }

    @Override
    public void endBatchEdit() {
        dispatchWithTracing("endBatchEdit", () -> {
            InputConnection ic = getInputConnection();
            if (ic == null || !isActive()) {
                Log.w(TAG, "endBatchEdit on inactive InputConnection");
                return;
            }
            ic.endBatchEdit();
        });
    }

    @Override
    public void performSpellCheck() {
        dispatchWithTracing("performSpellCheck", () -> {
            InputConnection ic = getInputConnection();
            if (ic == null || !isActive()) {
                Log.w(TAG, "performSpellCheck on inactive InputConnection");
                return;
            }
            ic.performSpellCheck();
        });
    }

    @Override
    public void performPrivateCommand(String action, Bundle data) {
        dispatchWithTracing("performPrivateCommand", () -> {
            InputConnection ic = getInputConnection();
            if (ic == null || !isActive()) {
                Log.w(TAG, "performPrivateCommand on inactive InputConnection");
                return;
            }
            ic.performPrivateCommand(action, data);
        });
    }

    @Override
    public void requestCursorUpdates(int cursorUpdateMode, int imeDisplayId,
            AndroidFuture future /* T=Boolean */) {
        dispatchWithTracing("requestCursorUpdates", future, () -> {
            final InputConnection ic = getInputConnection();
            if (ic == null || !isActive()) {
                Log.w(TAG, "requestCursorAnchorInfo on inactive InputConnection");
                return false;
            }
            if (mParentInputMethodManager.getDisplayId() != imeDisplayId) {
                // requestCursorUpdates() is not currently supported across displays.
                return false;
            }
            return ic.requestCursorUpdates(cursorUpdateMode);
        });
    }

    @Override
    public void commitContent(InputContentInfo inputContentInfo, int flags, Bundle opts,
            AndroidFuture future /* T=Boolean */) {
        dispatchWithTracing("commitContent", future, () -> {
            final InputConnection ic = getInputConnection();
            if (ic == null || !isActive()) {
                Log.w(TAG, "commitContent on inactive InputConnection");
                return false;
            }
            if (inputContentInfo == null || !inputContentInfo.validate()) {
                Log.w(TAG, "commitContent with invalid inputContentInfo=" + inputContentInfo);
                return false;
            }
            return ic.commitContent(inputContentInfo, flags, opts);
        });
    }

    @Override
    public void setImeConsumesInput(boolean imeConsumesInput) {
        dispatchWithTracing("setImeConsumesInput", () -> {
            InputConnection ic = getInputConnection();
            if (ic == null || !isActive()) {
                Log.w(TAG, "setImeConsumesInput on inactive InputConnection");
                return;
            }
            ic.setImeConsumesInput(imeConsumesInput);
        });
    }

    private void dispatch(@NonNull Runnable runnable) {
        // If we are calling this from the target thread, then we can call right through.
        // Otherwise, we need to send the message to the target thread.
        if (mLooper.isCurrentThread()) {
            runnable.run();
            return;
        }

        mH.post(runnable);
    }

    private void dispatchWithTracing(@NonNull String methodName, @NonNull Runnable runnable) {
        final Runnable actualRunnable;
        if (Trace.isTagEnabled(Trace.TRACE_TAG_INPUT)) {
            actualRunnable = () -> {
                Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#" + methodName);
                try {
                    runnable.run();
                } finally {
                    Trace.traceEnd(Trace.TRACE_TAG_INPUT);
                }
            };
        } else {
            actualRunnable = runnable;
        }

        dispatch(actualRunnable);
    }

    private <T> void dispatchWithTracing(@NonNull String methodName,
            @NonNull AndroidFuture untypedFuture, @NonNull Supplier<T> supplier) {
        dispatchWithTracing(methodName, untypedFuture, supplier, null /* dumpProtoProvider */);
    }

    private <T> void dispatchWithTracing(@NonNull String methodName,
            @NonNull AndroidFuture untypedFuture, @NonNull Supplier<T> supplier,
            @Nullable Function<T, byte[]> dumpProtoProvider) {
        @SuppressWarnings("unchecked")
        final AndroidFuture<T> future = untypedFuture;
        dispatchWithTracing(methodName, () -> {
            final T result;
            try {
                result = supplier.get();
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
                throw throwable;
            }
            future.complete(result);
            if (dumpProtoProvider != null) {
                final byte[] icProto = dumpProtoProvider.apply(result);
                ImeTracing.getInstance().triggerClientDump(
                        TAG + "#" + methodName, mParentInputMethodManager, icProto);
            }
        });
    }

    private static boolean useImeTracing() {
        return ImeTracing.getInstance().isEnabled();
    }
}
