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

package android.view.inputmethod;

import static com.android.internal.inputmethod.InputConnectionProtoDumper.buildGetCursorCapsModeProto;
import static com.android.internal.inputmethod.InputConnectionProtoDumper.buildGetExtractedTextProto;
import static com.android.internal.inputmethod.InputConnectionProtoDumper.buildGetSelectedTextProto;
import static com.android.internal.inputmethod.InputConnectionProtoDumper.buildGetSurroundingTextProto;
import static com.android.internal.inputmethod.InputConnectionProtoDumper.buildGetTextAfterCursorProto;
import static com.android.internal.inputmethod.InputConnectionProtoDumper.buildGetTextBeforeCursorProto;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.AnyThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UiThread;
import android.app.UriGrantsManager;
import android.content.ContentProvider;
import android.content.Intent;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.CancellationSignalBeamer;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.Trace;
import android.os.UserHandle;
import android.util.Log;
import android.util.proto.ProtoOutputStream;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewRootImpl;

import com.android.internal.infra.AndroidFuture;
import com.android.internal.inputmethod.IRemoteAccessibilityInputConnection;
import com.android.internal.inputmethod.IRemoteInputConnection;
import com.android.internal.inputmethod.ImeTracing;
import com.android.internal.inputmethod.InputConnectionCommandHeader;

import java.lang.annotation.Retention;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Takes care of remote method invocations of {@link InputConnection} in the IME client side.
 *
 * <p>{@link android.inputmethodservice.RemoteInputConnection} code is executed in the IME process.
 * It makes {@link IRemoteInputConnection} binder calls under the hood.
 * {@link RemoteInputConnectionImpl} receives {@link IRemoteInputConnection} binder calls in the IME
 * client (editor app) process, and forwards them to {@link InputConnection} that the IME client
 * provided, on the {@link Looper} associated to the {@link InputConnection}.</p>
 *
 * <p>{@link com.android.internal.inputmethod.RemoteAccessibilityInputConnection} code is executed
 * in the {@link android.accessibilityservice.AccessibilityService} process. It makes
 * {@link com.android.internal.inputmethod.IRemoteAccessibilityInputConnection} binder calls under
 * the hood. {@link #mAccessibilityInputConnection} receives the binder calls in the IME client
 * (editor app) process, and forwards them to {@link InputConnection} that the IME client provided,
 * on the {@link Looper} associated to the {@link InputConnection}.</p>
 */
final class RemoteInputConnectionImpl extends IRemoteInputConnection.Stub {
    private static final String TAG = "RemoteInputConnectionImpl";
    private static final boolean DEBUG = false;

    /**
     * An upper limit of calling {@link InputConnection#endBatchEdit()}.
     *
     * <p>This is a safeguard against broken {@link InputConnection#endBatchEdit()} implementations,
     * which are real as we've seen in Bug 208941904.  If the retry count reaches to the number
     * defined here, we fall back into {@link InputMethodManager#restartInput(View)} as a
     * workaround.</p>
     */
    private static final int MAX_END_BATCH_EDIT_RETRY = 16;

    /**
     * A lightweight per-process type cache to remember classes that never returns {@code false}
     * from {@link InputConnection#endBatchEdit()}.  The implementation is optimized for simplicity
     * and speed with accepting false-negatives in {@link #contains(Class)}.
     */
    private static final class KnownAlwaysTrueEndBatchEditCache {
        @Nullable
        private static volatile Class<?> sElement;
        @Nullable
        private static volatile Class<?>[] sArray;

        /**
         * Query if the specified {@link InputConnection} implementation is known to be broken, with
         * allowing false-negative results.
         *
         * @param klass An implementation class of {@link InputConnection} to be tested.
         * @return {@code true} if the specified type was passed to {@link #add(Class)}.
         *         Note that there is a chance that you still receive {@code false} even if you
         *         called {@link #add(Class)} (false-negative).
         */
        @AnyThread
        static boolean contains(@NonNull Class<? extends InputConnection> klass) {
            if (klass == sElement) {
                return true;
            }
            final Class<?>[] array = sArray;
            if (array == null) {
                return false;
            }
            for (Class<?> item : array) {
                if (item == klass) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Try to remember the specified {@link InputConnection} implementation as a known bad.
         *
         * <p>There is a chance that calling this method can accidentally overwrite existing
         * cache entries. See the document of {@link #contains(Class)} for details.</p>
         *
         * @param klass The implementation class of {@link InputConnection} to be remembered.
         */
        @AnyThread
        static void add(@NonNull Class<? extends InputConnection> klass) {
            if (sElement == null) {
                // OK to accidentally overwrite an existing element that was set by another thread.
                sElement = klass;
                return;
            }

            final Class<?>[] array = sArray;
            final int arraySize = array != null ? array.length : 0;
            final Class<?>[] newArray = new Class<?>[arraySize + 1];
            for (int i = 0; i < arraySize; ++i) {
                newArray[i] = array[i];
            }
            newArray[arraySize] = klass;

            // OK to accidentally overwrite an existing array that was set by another thread.
            sArray = newArray;
        }
    }

    @Retention(SOURCE)
    private @interface Dispatching {
        boolean cancellable();
    }

    @NonNull
    private final AtomicReference<InputConnection> mInputConnectionRef;
    @NonNull
    private final AtomicBoolean mDeactivateRequested = new AtomicBoolean(false);

    @NonNull
    private final Looper mLooper;
    private final Handler mH;

    private final InputMethodManager mParentInputMethodManager;
    private final WeakReference<View> mServedView;

    private final AtomicInteger mCurrentSessionId = new AtomicInteger(0);
    private final AtomicBoolean mHasPendingInvalidation = new AtomicBoolean();

    private final AtomicBoolean mIsCursorAnchorInfoMonitoring = new AtomicBoolean(false);
    private final AtomicBoolean mHasPendingImmediateCursorAnchorInfoUpdate =
            new AtomicBoolean(false);

    private CancellationSignalBeamer.Receiver mBeamer;

    RemoteInputConnectionImpl(@NonNull Looper looper,
            @NonNull InputConnection inputConnection,
            @NonNull InputMethodManager inputMethodManager, @Nullable View servedView) {
        mInputConnectionRef = new AtomicReference<>(inputConnection);
        mLooper = looper;
        mH = new Handler(mLooper);
        mParentInputMethodManager = inputMethodManager;
        mServedView = new WeakReference<>(servedView);
    }

    /**
     * @return {@link InputConnection} to which incoming IPCs will be dispatched.
     */
    @Nullable
    public InputConnection getInputConnection() {
        return mInputConnectionRef.get();
    }

    /**
     * @return {@code true} if there is a pending {@link InputMethodManager#invalidateInput(View)}
     * call.
     */
    public boolean hasPendingInvalidation() {
        return mHasPendingInvalidation.get();
    }

    /**
     * @return {@code true} until the target {@link InputConnection} receives
     * {@link InputConnection#closeConnection()} as a result of {@link #deactivate()}.
     */
    private boolean isFinished() {
        return mInputConnectionRef.get() == null;
    }

    private View getServedView() {
        return mServedView.get();
    }

    /**
     * Queries if the given {@link View} is associated with this {@link RemoteInputConnectionImpl}
     * or not.
     *
     * @param view {@link View}.
     * @return {@code true} if the given {@link View} is not null and is associated with this
     *         {@link RemoteInputConnectionImpl}.
     */
    @AnyThread
    public boolean isAssociatedWith(@Nullable View view) {
        if (view == null) {
            return false;
        }
        return mServedView.refersTo(view);
    }

    /**
     * Gets and resets {@link #mHasPendingImmediateCursorAnchorInfoUpdate}.
     *
     * <p>Calling this method resets {@link #mHasPendingImmediateCursorAnchorInfoUpdate}. This
     * means that the second call of this method returns {@code false} unless the IME requests
     * {@link android.view.inputmethod.CursorAnchorInfo} again with
     * {@link InputConnection#CURSOR_UPDATE_IMMEDIATE} flag.</p>
     *
     * @return {@code true} if there is any pending request for
     *         {@link android.view.inputmethod.CursorAnchorInfo} with
     *         {@link InputConnection#CURSOR_UPDATE_IMMEDIATE} flag.
     */
    @AnyThread
    public boolean resetHasPendingImmediateCursorAnchorInfoUpdate() {
        return mHasPendingImmediateCursorAnchorInfoUpdate.getAndSet(false);
    }

    /**
     * @return {@code true} if there is any active request for
     *         {@link android.view.inputmethod.CursorAnchorInfo} with
     *         {@link InputConnection#CURSOR_UPDATE_MONITOR} flag.
     */
    @AnyThread
    public boolean isCursorAnchorInfoMonitoring() {
        return mIsCursorAnchorInfoMonitoring.get();
    }

    /**
     * Schedule a task to execute
     * {@link InputMethodManager#doInvalidateInput(RemoteInputConnectionImpl, TextSnapshot, int)}
     * on the associated Handler if not yet scheduled.
     *
     * <p>By calling {@link InputConnection#takeSnapshot()} directly from the message loop, we can
     * make sure that application code is not modifying text context in a reentrant manner.</p>
     */
    public void scheduleInvalidateInput() {
        if (mHasPendingInvalidation.compareAndSet(false, true)) {
            final int nextSessionId = mCurrentSessionId.incrementAndGet();
            // By calling InputConnection#takeSnapshot() directly from the message loop, we can make
            // sure that application code is not modifying text context in a reentrant manner.
            // e.g. We may see methods like EditText#setText() in the callstack here.
            mH.post(() -> {
                try {
                    if (isFinished()) {
                        // This is a stale request, which can happen.  No need to show a warning
                        // because this situation itself is not an error.
                        return;
                    }
                    final InputConnection ic = getInputConnection();
                    if (ic == null) {
                        // This is a stale request, which can happen.  No need to show a warning
                        // because this situation itself is not an error.
                        return;
                    }
                    final View view = getServedView();
                    if (view == null) {
                        // This is a stale request, which can happen.  No need to show a warning
                        // because this situation itself is not an error.
                        return;
                    }

                    final Class<? extends InputConnection> icClass = ic.getClass();

                    boolean alwaysTrueEndBatchEditDetected =
                            KnownAlwaysTrueEndBatchEditCache.contains(icClass);

                    if (!alwaysTrueEndBatchEditDetected) {
                        // Clean up composing text and batch edit.
                        final boolean supportsBatchEdit = ic.beginBatchEdit();
                        ic.finishComposingText();
                        if (supportsBatchEdit) {
                            // Also clean up batch edit.
                            int retryCount = 0;
                            while (true) {
                                if (!ic.endBatchEdit()) {
                                    break;
                                }
                                ++retryCount;
                                if (retryCount > MAX_END_BATCH_EDIT_RETRY) {
                                    Log.e(TAG, icClass.getTypeName() + "#endBatchEdit() still"
                                            + " returns true even after retrying "
                                            + MAX_END_BATCH_EDIT_RETRY + " times.  Falling back to"
                                            + " InputMethodManager#restartInput(View)");
                                    alwaysTrueEndBatchEditDetected = true;
                                    KnownAlwaysTrueEndBatchEditCache.add(icClass);
                                    break;
                                }
                            }
                        }
                    }

                    if (!alwaysTrueEndBatchEditDetected) {
                        final TextSnapshot textSnapshot = ic.takeSnapshot();
                        if (textSnapshot != null && mParentInputMethodManager.doInvalidateInput(
                                this, textSnapshot, nextSessionId)) {
                            return;
                        }
                    }

                    mParentInputMethodManager.restartInput(view);
                } finally {
                    mHasPendingInvalidation.set(false);
                }
            });
        }
    }

    /**
     * Called when this object needs to be permanently deactivated.
     *
     * <p>Multiple invocations will be simply ignored.</p>
     */
    @Dispatching(cancellable = false)
    public void deactivate() {
        if (mDeactivateRequested.getAndSet(true)) {
            // This is a small performance optimization.  Still only the 1st call of
            // deactivate() will take effect.
            return;
        }
        dispatch(() -> {
            Trace.traceBegin(Trace.TRACE_TAG_INPUT, "InputConnection#closeConnection");
            try {
                InputConnection ic = getInputConnection();
                if (ic == null) {
                    return;
                }
                try {
                    ic.closeConnection();
                } catch (AbstractMethodError ignored) {
                    // TODO(b/199934664): See if we can remove this by providing a default impl.
                }
            } finally {
                mInputConnectionRef.set(null);
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

                final ViewRootImpl viewRoot = servedView.getViewRootImpl();
                if (viewRoot != null) {
                    viewRoot.getHandwritingInitiator().onInputConnectionClosed(servedView);
                }
            }
        });
    }

    @Dispatching(cancellable = false)
    @Override
    public void cancelCancellationSignal(IBinder token) {
        if (mBeamer == null) {
            return;
        }
        dispatch(() -> {
            mBeamer.cancel(token);
        });
    }

    @Override
    public void forgetCancellationSignal(IBinder token) {
        if (mBeamer == null) {
            return;
        }
        dispatch(() -> {
            mBeamer.forget(token);
        });
    }

    @Override
    public String toString() {
        return "RemoteInputConnectionImpl{"
                + "connection=" + getInputConnection()
                + " mDeactivateRequested=" + mDeactivateRequested.get()
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
        final InputConnection ic = mInputConnectionRef.get();
        // Check that the call is initiated in the target thread of the current InputConnection
        // {@link InputConnection#getHandler} since the messages to IInputConnectionWrapper are
        // executed on this thread. Otherwise the messages are dispatched to the correct thread
        // in IInputConnectionWrapper, but this is not wanted while dumping, for performance
        // reasons.
        if ((ic instanceof DumpableInputConnection) && mLooper.isCurrentThread()) {
            ((DumpableInputConnection) ic).dumpDebug(proto, fieldId);
        }
    }

    /**
     * Invoke {@link InputConnection#reportFullscreenMode(boolean)} or schedule it on the target
     * thread associated with {@link InputConnection#getHandler()}.
     *
     * @param enabled the parameter to be passed to
     *                {@link InputConnection#reportFullscreenMode(boolean)}.
     */
    @Dispatching(cancellable = false)
    public void dispatchReportFullscreenMode(boolean enabled) {
        dispatch(() -> {
            final InputConnection ic = getInputConnection();
            if (ic == null || mDeactivateRequested.get()) {
                return;
            }
            ic.reportFullscreenMode(enabled);
        });
    }

    @Dispatching(cancellable = true)
    @Override
    public void getTextAfterCursor(InputConnectionCommandHeader header, int length, int flags,
            AndroidFuture future /* T=CharSequence */) {
        dispatchWithTracing("getTextAfterCursor", future, () -> {
            if (header.mSessionId != mCurrentSessionId.get()) {
                return null;  // cancelled
            }
            final InputConnection ic = getInputConnection();
            if (ic == null || mDeactivateRequested.get()) {
                Log.w(TAG, "getTextAfterCursor on inactive InputConnection");
                return null;
            }
            if (length < 0) {
                Log.i(TAG, "Returning null to getTextAfterCursor due to an invalid length="
                        + length);
                return null;
            }
            return ic.getTextAfterCursor(length, flags);
        }, useImeTracing() ? result -> buildGetTextAfterCursorProto(length, flags, result) : null);
    }

    @Dispatching(cancellable = true)
    @Override
    public void getTextBeforeCursor(InputConnectionCommandHeader header, int length, int flags,
            AndroidFuture future /* T=CharSequence */) {
        dispatchWithTracing("getTextBeforeCursor", future, () -> {
            if (header.mSessionId != mCurrentSessionId.get()) {
                return null;  // cancelled
            }
            final InputConnection ic = getInputConnection();
            if (ic == null || mDeactivateRequested.get()) {
                Log.w(TAG, "getTextBeforeCursor on inactive InputConnection");
                return null;
            }
            if (length < 0) {
                Log.i(TAG, "Returning null to getTextBeforeCursor due to an invalid length="
                        + length);
                return null;
            }
            return ic.getTextBeforeCursor(length, flags);
        }, useImeTracing() ? result -> buildGetTextBeforeCursorProto(length, flags, result) : null);
    }

    @Dispatching(cancellable = true)
    @Override
    public void getSelectedText(InputConnectionCommandHeader header, int flags,
            AndroidFuture future /* T=CharSequence */) {
        dispatchWithTracing("getSelectedText", future, () -> {
            if (header.mSessionId != mCurrentSessionId.get()) {
                return null;  // cancelled
            }
            final InputConnection ic = getInputConnection();
            if (ic == null || mDeactivateRequested.get()) {
                Log.w(TAG, "getSelectedText on inactive InputConnection");
                return null;
            }
            try {
                return ic.getSelectedText(flags);
            } catch (AbstractMethodError ignored) {
                // TODO(b/199934664): See if we can remove this by providing a default impl.
                return null;
            }
        }, useImeTracing() ? result -> buildGetSelectedTextProto(flags, result) : null);
    }

    @Dispatching(cancellable = true)
    @Override
    public void getSurroundingText(InputConnectionCommandHeader header, int beforeLength,
            int afterLength, int flags, AndroidFuture future /* T=SurroundingText */) {
        dispatchWithTracing("getSurroundingText", future, () -> {
            if (header.mSessionId != mCurrentSessionId.get()) {
                return null;  // cancelled
            }
            final InputConnection ic = getInputConnection();
            if (ic == null || mDeactivateRequested.get()) {
                Log.w(TAG, "getSurroundingText on inactive InputConnection");
                return null;
            }
            if (beforeLength < 0) {
                Log.i(TAG, "Returning null to getSurroundingText due to an invalid"
                        + " beforeLength=" + beforeLength);
                return null;
            }
            if (afterLength < 0) {
                Log.i(TAG, "Returning null to getSurroundingText due to an invalid"
                        + " afterLength=" + afterLength);
                return null;
            }
            return ic.getSurroundingText(beforeLength, afterLength, flags);
        }, useImeTracing() ? result -> buildGetSurroundingTextProto(
                beforeLength, afterLength, flags, result) : null);
    }

    @Dispatching(cancellable = true)
    @Override
    public void getCursorCapsMode(InputConnectionCommandHeader header, int reqModes,
            AndroidFuture future /* T=Integer */) {
        dispatchWithTracing("getCursorCapsMode", future, () -> {
            if (header.mSessionId != mCurrentSessionId.get()) {
                return 0;  // cancelled
            }
            final InputConnection ic = getInputConnection();
            if (ic == null || mDeactivateRequested.get()) {
                Log.w(TAG, "getCursorCapsMode on inactive InputConnection");
                return 0;
            }
            return ic.getCursorCapsMode(reqModes);
        }, useImeTracing() ? result -> buildGetCursorCapsModeProto(reqModes, result) : null);
    }

    @Dispatching(cancellable = true)
    @Override
    public void getExtractedText(InputConnectionCommandHeader header, ExtractedTextRequest request,
            int flags, AndroidFuture future /* T=ExtractedText */) {
        dispatchWithTracing("getExtractedText", future, () -> {
            if (header.mSessionId != mCurrentSessionId.get()) {
                return null;  // cancelled
            }
            final InputConnection ic = getInputConnection();
            if (ic == null || mDeactivateRequested.get()) {
                Log.w(TAG, "getExtractedText on inactive InputConnection");
                return null;
            }
            return ic.getExtractedText(request, flags);
        }, useImeTracing() ? result -> buildGetExtractedTextProto(request, flags, result) : null);
    }

    @Dispatching(cancellable = true)
    @Override
    public void commitText(InputConnectionCommandHeader header, CharSequence text,
            int newCursorPosition) {
        dispatchWithTracing("commitText", () -> {
            if (header.mSessionId != mCurrentSessionId.get()) {
                return;  // cancelled
            }
            InputConnection ic = getInputConnection();
            if (ic == null || mDeactivateRequested.get()) {
                Log.w(TAG, "commitText on inactive InputConnection");
                return;
            }
            ic.commitText(text, newCursorPosition);
        });
    }

    @Dispatching(cancellable = true)
    @Override
    public void commitTextWithTextAttribute(InputConnectionCommandHeader header, CharSequence text,
            int newCursorPosition, @Nullable TextAttribute textAttribute) {
        dispatchWithTracing("commitTextWithTextAttribute", () -> {
            if (header.mSessionId != mCurrentSessionId.get()) {
                return;  // cancelled
            }
            InputConnection ic = getInputConnection();
            if (ic == null || mDeactivateRequested.get()) {
                Log.w(TAG, "commitText on inactive InputConnection");
                return;
            }
            ic.commitText(text, newCursorPosition, textAttribute);
        });
    }

    @Dispatching(cancellable = true)
    @Override
    public void commitCompletion(InputConnectionCommandHeader header, CompletionInfo text) {
        dispatchWithTracing("commitCompletion", () -> {
            if (header.mSessionId != mCurrentSessionId.get()) {
                return;  // cancelled
            }
            InputConnection ic = getInputConnection();
            if (ic == null || mDeactivateRequested.get()) {
                Log.w(TAG, "commitCompletion on inactive InputConnection");
                return;
            }
            ic.commitCompletion(text);
        });
    }

    @Dispatching(cancellable = true)
    @Override
    public void commitCorrection(InputConnectionCommandHeader header, CorrectionInfo info) {
        dispatchWithTracing("commitCorrection", () -> {
            if (header.mSessionId != mCurrentSessionId.get()) {
                return;  // cancelled
            }
            InputConnection ic = getInputConnection();
            if (ic == null || mDeactivateRequested.get()) {
                Log.w(TAG, "commitCorrection on inactive InputConnection");
                return;
            }
            try {
                ic.commitCorrection(info);
            } catch (AbstractMethodError ignored) {
                // TODO(b/199934664): See if we can remove this by providing a default impl.
            }
        });
    }

    @Dispatching(cancellable = true)
    @Override
    public void setSelection(InputConnectionCommandHeader header, int start, int end) {
        dispatchWithTracing("setSelection", () -> {
            if (header.mSessionId != mCurrentSessionId.get()) {
                return;  // cancelled
            }
            InputConnection ic = getInputConnection();
            if (ic == null || mDeactivateRequested.get()) {
                Log.w(TAG, "setSelection on inactive InputConnection");
                return;
            }
            ic.setSelection(start, end);
        });
    }

    @Dispatching(cancellable = true)
    @Override
    public void performEditorAction(InputConnectionCommandHeader header, int id) {
        dispatchWithTracing("performEditorAction", () -> {
            if (header.mSessionId != mCurrentSessionId.get()) {
                return;  // cancelled
            }
            InputConnection ic = getInputConnection();
            if (ic == null || mDeactivateRequested.get()) {
                Log.w(TAG, "performEditorAction on inactive InputConnection");
                return;
            }
            ic.performEditorAction(id);
        });
    }

    @Dispatching(cancellable = true)
    @Override
    public void performContextMenuAction(InputConnectionCommandHeader header, int id) {
        dispatchWithTracing("performContextMenuAction", () -> {
            if (header.mSessionId != mCurrentSessionId.get()) {
                return;  // cancelled
            }
            InputConnection ic = getInputConnection();
            if (ic == null || mDeactivateRequested.get()) {
                Log.w(TAG, "performContextMenuAction on inactive InputConnection");
                return;
            }
            ic.performContextMenuAction(id);
        });
    }

    @Dispatching(cancellable = true)
    @Override
    public void setComposingRegion(InputConnectionCommandHeader header, int start, int end) {
        dispatchWithTracing("setComposingRegion", () -> {
            if (header.mSessionId != mCurrentSessionId.get()) {
                return;  // cancelled
            }
            InputConnection ic = getInputConnection();
            if (ic == null || mDeactivateRequested.get()) {
                Log.w(TAG, "setComposingRegion on inactive InputConnection");
                return;
            }
            try {
                ic.setComposingRegion(start, end);
            } catch (AbstractMethodError ignored) {
                // TODO(b/199934664): See if we can remove this by providing a default impl.
            }
        });
    }

    @Dispatching(cancellable = true)
    @Override
    public void setComposingRegionWithTextAttribute(InputConnectionCommandHeader header, int start,
            int end, @Nullable TextAttribute textAttribute) {
        dispatchWithTracing("setComposingRegionWithTextAttribute", () -> {
            if (header.mSessionId != mCurrentSessionId.get()) {
                return;  // cancelled
            }
            InputConnection ic = getInputConnection();
            if (ic == null || mDeactivateRequested.get()) {
                Log.w(TAG, "setComposingRegion on inactive InputConnection");
                return;
            }
            ic.setComposingRegion(start, end, textAttribute);
        });
    }

    @Dispatching(cancellable = true)
    @Override
    public void setComposingText(InputConnectionCommandHeader header, CharSequence text,
            int newCursorPosition) {
        dispatchWithTracing("setComposingText", () -> {
            if (header.mSessionId != mCurrentSessionId.get()) {
                return;  // cancelled
            }
            InputConnection ic = getInputConnection();
            if (ic == null || mDeactivateRequested.get()) {
                Log.w(TAG, "setComposingText on inactive InputConnection");
                return;
            }
            ic.setComposingText(text, newCursorPosition);
        });
    }

    @Dispatching(cancellable = true)
    @Override
    public void setComposingTextWithTextAttribute(InputConnectionCommandHeader header,
            CharSequence text, int newCursorPosition, @Nullable TextAttribute textAttribute) {
        dispatchWithTracing("setComposingTextWithTextAttribute", () -> {
            if (header.mSessionId != mCurrentSessionId.get()) {
                return;  // cancelled
            }
            InputConnection ic = getInputConnection();
            if (ic == null || mDeactivateRequested.get()) {
                Log.w(TAG, "setComposingText on inactive InputConnection");
                return;
            }
            ic.setComposingText(text, newCursorPosition, textAttribute);
        });
    }

    /**
     * Dispatches {@link InputConnection#finishComposingText()}.
     *
     * <p>This method is intended to be called only from {@link InputMethodManager}.</p>
     */
    @Dispatching(cancellable = true)
    public void finishComposingTextFromImm() {
        final int currentSessionId = mCurrentSessionId.get();
        dispatchWithTracing("finishComposingTextFromImm", () -> {
            if (isFinished()) {
                // In this case, #finishComposingText() is guaranteed to be called already.
                // There should be no negative impact if we ignore this call silently.
                if (DEBUG) {
                    Log.w(TAG, "Bug 35301295: Redundant finishComposingTextFromImm.");
                }
                return;
            }
            if (currentSessionId != mCurrentSessionId.get()) {
                return;  // cancelled
            }
            InputConnection ic = getInputConnection();
            if (ic == null || mDeactivateRequested.get()) {
                Log.w(TAG, "finishComposingTextFromImm on inactive InputConnection");
                return;
            }
            ic.finishComposingText();
        });
    }

    @Dispatching(cancellable = true)
    @Override
    public void finishComposingText(InputConnectionCommandHeader header) {
        dispatchWithTracing("finishComposingText", () -> {
            if (isFinished()) {
                // In this case, #finishComposingText() is guaranteed to be called already.
                // There should be no negative impact if we ignore this call silently.
                if (DEBUG) {
                    Log.w(TAG, "Bug 35301295: Redundant finishComposingText.");
                }
                return;
            }
            if (header.mSessionId != mCurrentSessionId.get()) {
                return;  // cancelled
            }
            InputConnection ic = getInputConnection();
            if (ic == null && mDeactivateRequested.get()) {
                Log.w(TAG, "finishComposingText on inactive InputConnection");
                return;
            }
            ic.finishComposingText();
        });
    }

    @Dispatching(cancellable = true)
    @Override
    public void sendKeyEvent(InputConnectionCommandHeader header, KeyEvent event) {
        dispatchWithTracing("sendKeyEvent", () -> {
            if (header.mSessionId != mCurrentSessionId.get()) {
                return;  // cancelled
            }
            InputConnection ic = getInputConnection();
            if (ic == null || mDeactivateRequested.get()) {
                Log.w(TAG, "sendKeyEvent on inactive InputConnection");
                return;
            }
            ic.sendKeyEvent(event);
        });
    }

    @Dispatching(cancellable = true)
    @Override
    public void clearMetaKeyStates(InputConnectionCommandHeader header, int states) {
        dispatchWithTracing("clearMetaKeyStates", () -> {
            if (header.mSessionId != mCurrentSessionId.get()) {
                return;  // cancelled
            }
            InputConnection ic = getInputConnection();
            if (ic == null || mDeactivateRequested.get()) {
                Log.w(TAG, "clearMetaKeyStates on inactive InputConnection");
                return;
            }
            ic.clearMetaKeyStates(states);
        });
    }

    @Dispatching(cancellable = true)
    @Override
    public void deleteSurroundingText(InputConnectionCommandHeader header, int beforeLength,
            int afterLength) {
        dispatchWithTracing("deleteSurroundingText", () -> {
            if (header.mSessionId != mCurrentSessionId.get()) {
                return;  // cancelled
            }
            InputConnection ic = getInputConnection();
            if (ic == null || mDeactivateRequested.get()) {
                Log.w(TAG, "deleteSurroundingText on inactive InputConnection");
                return;
            }
            ic.deleteSurroundingText(beforeLength, afterLength);
        });
    }

    @Dispatching(cancellable = true)
    @Override
    public void deleteSurroundingTextInCodePoints(InputConnectionCommandHeader header,
            int beforeLength, int afterLength) {
        dispatchWithTracing("deleteSurroundingTextInCodePoints", () -> {
            if (header.mSessionId != mCurrentSessionId.get()) {
                return;  // cancelled
            }
            InputConnection ic = getInputConnection();
            if (ic == null || mDeactivateRequested.get()) {
                Log.w(TAG, "deleteSurroundingTextInCodePoints on inactive InputConnection");
                return;
            }
            try {
                ic.deleteSurroundingTextInCodePoints(beforeLength, afterLength);
            } catch (AbstractMethodError ignored) {
                // TODO(b/199934664): See if we can remove this by providing a default impl.
            }
        });
    }

    @Dispatching(cancellable = true)
    @Override
    public void beginBatchEdit(InputConnectionCommandHeader header) {
        dispatchWithTracing("beginBatchEdit", () -> {
            if (header.mSessionId != mCurrentSessionId.get()) {
                return;  // cancelled
            }
            InputConnection ic = getInputConnection();
            if (ic == null || mDeactivateRequested.get()) {
                Log.w(TAG, "beginBatchEdit on inactive InputConnection");
                return;
            }
            ic.beginBatchEdit();
        });
    }

    @Dispatching(cancellable = true)
    @Override
    public void endBatchEdit(InputConnectionCommandHeader header) {
        dispatchWithTracing("endBatchEdit", () -> {
            if (header.mSessionId != mCurrentSessionId.get()) {
                return;  // cancelled
            }
            InputConnection ic = getInputConnection();
            if (ic == null || mDeactivateRequested.get()) {
                Log.w(TAG, "endBatchEdit on inactive InputConnection");
                return;
            }
            ic.endBatchEdit();
        });
    }

    @Dispatching(cancellable = true)
    @Override
    public void performSpellCheck(InputConnectionCommandHeader header) {
        dispatchWithTracing("performSpellCheck", () -> {
            if (header.mSessionId != mCurrentSessionId.get()) {
                return;  // cancelled
            }
            InputConnection ic = getInputConnection();
            if (ic == null || mDeactivateRequested.get()) {
                Log.w(TAG, "performSpellCheck on inactive InputConnection");
                return;
            }
            ic.performSpellCheck();
        });
    }

    @Dispatching(cancellable = true)
    @Override
    public void performPrivateCommand(InputConnectionCommandHeader header, String action,
            Bundle data) {
        dispatchWithTracing("performPrivateCommand", () -> {
            if (header.mSessionId != mCurrentSessionId.get()) {
                return;  // cancelled
            }
            InputConnection ic = getInputConnection();
            if (ic == null || mDeactivateRequested.get()) {
                Log.w(TAG, "performPrivateCommand on inactive InputConnection");
                return;
            }
            ic.performPrivateCommand(action, data);
        });
    }

    @Dispatching(cancellable = true)
    @Override
    public void performHandwritingGesture(
            InputConnectionCommandHeader header, ParcelableHandwritingGesture gestureContainer,
            ResultReceiver resultReceiver) {
        final HandwritingGesture gesture = gestureContainer.get();
        if (gesture instanceof CancellableHandwritingGesture) {
            // For cancellable gestures, unbeam and save the CancellationSignal.
            CancellableHandwritingGesture cancellableGesture =
                    (CancellableHandwritingGesture) gesture;
            cancellableGesture.unbeamCancellationSignal(getCancellationSignalBeamer());
            if (cancellableGesture.getCancellationSignal() != null
                    && cancellableGesture.getCancellationSignal().isCanceled()) {
                // Send result for canceled operations.
                if (resultReceiver != null) {
                    resultReceiver.send(
                            InputConnection.HANDWRITING_GESTURE_RESULT_CANCELLED, null);
                }
                return;
            }
        }
        dispatchWithTracing("performHandwritingGesture", () -> {
            if (header.mSessionId != mCurrentSessionId.get()) {
                if (resultReceiver != null) {
                    resultReceiver.send(
                            InputConnection.HANDWRITING_GESTURE_RESULT_CANCELLED, null);
                }
                return;  // cancelled
            }
            InputConnection ic = getInputConnection();
            if (ic == null || mDeactivateRequested.get()) {
                Log.w(TAG, "performHandwritingGesture on inactive InputConnection");
                if (resultReceiver != null) {
                    resultReceiver.send(
                            InputConnection.HANDWRITING_GESTURE_RESULT_CANCELLED, null);
                }
                return;
            }

            // TODO(210039666): implement Cleaner to return HANDWRITING_GESTURE_RESULT_UNKNOWN if
            //  editor doesn't return any type.
            ic.performHandwritingGesture(
                    gesture,
                    resultReceiver != null ? Runnable::run : null,
                    resultReceiver != null
                            ? (resultCode) -> resultReceiver.send(resultCode, null /* resultData */)
                            : null);
        });
    }

    @Dispatching(cancellable = true)
    @Override
    public void previewHandwritingGesture(
            InputConnectionCommandHeader header, ParcelableHandwritingGesture gestureContainer,
            IBinder cancellationSignalToken) {
        final CancellationSignal cancellationSignal =
                cancellationSignalToken != null
                        ? getCancellationSignalBeamer().unbeam(cancellationSignalToken) : null;

        // Previews always use PreviewableHandwritingGesture but if incorrectly wrong class is
        // passed, ClassCastException will be sent back to caller.
        final PreviewableHandwritingGesture gesture =
                (PreviewableHandwritingGesture) gestureContainer.get();

        dispatchWithTracing("previewHandwritingGesture", () -> {
            if (header.mSessionId != mCurrentSessionId.get()
                    || (cancellationSignal != null && cancellationSignal.isCanceled())) {
                return;  // cancelled
            }
            InputConnection ic = getInputConnection();
            if (ic == null || mDeactivateRequested.get()) {
                Log.w(TAG, "previewHandwritingGesture on inactive InputConnection");
                return; // cancelled
            }

            ic.previewHandwritingGesture(gesture, cancellationSignal);
        });
    }

    private CancellationSignalBeamer.Receiver getCancellationSignalBeamer() {
        if (mBeamer != null) {
            return mBeamer;
        }
        mBeamer = new CancellationSignalBeamer.Receiver(true /* cancelOnSenderDeath */);
        return mBeamer;
    }

    @Dispatching(cancellable = true)
    @Override
    public void requestCursorUpdates(InputConnectionCommandHeader header, int cursorUpdateMode,
            int imeDisplayId, AndroidFuture future /* T=Boolean */) {
        dispatchWithTracing("requestCursorUpdates", future, () -> {
            if (header.mSessionId != mCurrentSessionId.get()) {
                return false;  // cancelled
            }
            return requestCursorUpdatesInternal(
                    cursorUpdateMode, 0 /* cursorUpdateFilter */, imeDisplayId);
        });
    }

    @Dispatching(cancellable = true)
    @Override
    public void requestCursorUpdatesWithFilter(InputConnectionCommandHeader header,
            int cursorUpdateMode, int cursorUpdateFilter, int imeDisplayId,
            AndroidFuture future /* T=Boolean */) {
        dispatchWithTracing("requestCursorUpdates", future, () -> {
            if (header.mSessionId != mCurrentSessionId.get()) {
                return false;  // cancelled
            }
            return requestCursorUpdatesInternal(
                    cursorUpdateMode, cursorUpdateFilter, imeDisplayId);
        });
    }

    private boolean requestCursorUpdatesInternal(
            @InputConnection.CursorUpdateMode int cursorUpdateMode,
            @InputConnection.CursorUpdateFilter int cursorUpdateFilter, int imeDisplayId) {
        final InputConnection ic = getInputConnection();
        if (ic == null || mDeactivateRequested.get()) {
            Log.w(TAG, "requestCursorUpdates on inactive InputConnection");
            return false;
        }
        if (mParentInputMethodManager.mRequestCursorUpdateDisplayIdCheck.get()
                && mParentInputMethodManager.getDisplayId() != imeDisplayId) {
            // requestCursorUpdates() is not currently supported across displays.
            return false;
        }
        final boolean hasImmediate =
                (cursorUpdateMode & InputConnection.CURSOR_UPDATE_IMMEDIATE) != 0;
        final boolean hasMonitoring =
                (cursorUpdateMode & InputConnection.CURSOR_UPDATE_MONITOR) != 0;
        boolean result = false;
        try {
            result = ic.requestCursorUpdates(cursorUpdateMode, cursorUpdateFilter);
            return result;
        } catch (AbstractMethodError ignored) {
            // TODO(b/199934664): See if we can remove this by providing a default impl.
            return false;
        } finally {
            mHasPendingImmediateCursorAnchorInfoUpdate.set(result && hasImmediate);
            mIsCursorAnchorInfoMonitoring.set(result && hasMonitoring);
        }
    }

    @Dispatching(cancellable = true)
    @Override
    public void requestTextBoundsInfo(
            InputConnectionCommandHeader header, RectF bounds,
            @NonNull ResultReceiver resultReceiver) {
        dispatchWithTracing("requestTextBoundsInfo", () -> {
            if (header.mSessionId != mCurrentSessionId.get()) {
                resultReceiver.send(TextBoundsInfoResult.CODE_CANCELLED, null);
                return;  // cancelled
            }
            InputConnection ic = getInputConnection();
            if (ic == null || mDeactivateRequested.get()) {
                Log.w(TAG, "requestTextBoundsInfo on inactive InputConnection");
                resultReceiver.send(TextBoundsInfoResult.CODE_CANCELLED, null);
                return;
            }

            ic.requestTextBoundsInfo(
                    bounds,
                    Runnable::run,
                    (textBoundsInfoResult) -> {
                        final int resultCode = textBoundsInfoResult.getResultCode();
                        final TextBoundsInfo textBoundsInfo =
                                textBoundsInfoResult.getTextBoundsInfo();
                        resultReceiver.send(resultCode,
                                textBoundsInfo == null ? null : textBoundsInfo.toBundle());
                    });
        });
    }

    @Dispatching(cancellable = true)
    @Override
    public void commitContent(InputConnectionCommandHeader header,
            InputContentInfo inputContentInfo, int flags, Bundle opts,
            AndroidFuture future /* T=Boolean */) {
        final int imeUid = Binder.getCallingUid();
        dispatchWithTracing("commitContent", future, () -> {
            // Check if the originator IME has the right permissions
            try {
                final int contentUriOwnerUserId = ContentProvider.getUserIdFromUri(
                        inputContentInfo.getContentUri(), UserHandle.getUserId(imeUid));
                final Uri contentUriWithoutUserId = ContentProvider.getUriWithoutUserId(
                        inputContentInfo.getContentUri());
                UriGrantsManager.getService().checkGrantUriPermission_ignoreNonSystem(imeUid, null,
                        contentUriWithoutUserId, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        contentUriOwnerUserId);
            } catch (Exception e) {
                Log.w(TAG, "commitContent with invalid Uri permission from IME:", e);
                return false;
            }

            if (header.mSessionId != mCurrentSessionId.get()) {
                return false;  // cancelled
            }
            final InputConnection ic = getInputConnection();
            if (ic == null || mDeactivateRequested.get()) {
                Log.w(TAG, "commitContent on inactive InputConnection");
                return false;
            }
            if (inputContentInfo == null || !inputContentInfo.validate()) {
                Log.w(TAG, "commitContent with invalid inputContentInfo=" + inputContentInfo);
                return false;
            }
            try {
                return ic.commitContent(inputContentInfo, flags, opts);
            } catch (AbstractMethodError ignored) {
                // TODO(b/199934664): See if we can remove this by providing a default impl.
                return false;
            }
        });
    }

    @Dispatching(cancellable = true)
    @Override
    public void setImeConsumesInput(InputConnectionCommandHeader header, boolean imeConsumesInput) {
        dispatchWithTracing("setImeConsumesInput", () -> {
            if (header.mSessionId != mCurrentSessionId.get()) {
                return;  // cancelled
            }
            InputConnection ic = getInputConnection();
            if (ic == null || mDeactivateRequested.get()) {
                Log.w(TAG, "setImeConsumesInput on inactive InputConnection");
                return;
            }
            ic.setImeConsumesInput(imeConsumesInput);
        });
    }

    @Dispatching(cancellable = true)
    @Override
    public void replaceText(
            InputConnectionCommandHeader header,
            int start,
            int end,
            @NonNull CharSequence text,
            int newCursorPosition,
            @Nullable TextAttribute textAttribute) {
        dispatchWithTracing(
                "replaceText",
                () -> {
                    if (header.mSessionId != mCurrentSessionId.get()) {
                        return; // cancelled
                    }
                    InputConnection ic = getInputConnection();
                    if (ic == null || mDeactivateRequested.get()) {
                        Log.w(TAG, "replaceText on inactive InputConnection");
                        return;
                    }
                    ic.replaceText(start, end, text, newCursorPosition, textAttribute);
                });
    }

    private final IRemoteAccessibilityInputConnection mAccessibilityInputConnection =
            new IRemoteAccessibilityInputConnection.Stub() {
        @Dispatching(cancellable = true)
        @Override
        public void commitText(InputConnectionCommandHeader header, CharSequence text,
                int newCursorPosition, @Nullable TextAttribute textAttribute) {
            dispatchWithTracing("commitTextFromA11yIme", () -> {
                if (header.mSessionId != mCurrentSessionId.get()) {
                    return;  // cancelled
                }
                InputConnection ic = getInputConnection();
                if (ic == null || mDeactivateRequested.get()) {
                    Log.w(TAG, "commitText on inactive InputConnection");
                    return;
                }
                // A11yIME's commitText() also triggers finishComposingText() automatically.
                ic.beginBatchEdit();
                ic.finishComposingText();
                ic.commitText(text, newCursorPosition, textAttribute);
                ic.endBatchEdit();
            });
        }

        @Dispatching(cancellable = true)
        @Override
        public void setSelection(InputConnectionCommandHeader header, int start, int end) {
            dispatchWithTracing("setSelectionFromA11yIme", () -> {
                if (header.mSessionId != mCurrentSessionId.get()) {
                    return;  // cancelled
                }
                InputConnection ic = getInputConnection();
                if (ic == null || mDeactivateRequested.get()) {
                    Log.w(TAG, "setSelection on inactive InputConnection");
                    return;
                }
                ic.setSelection(start, end);
            });
        }

        @Dispatching(cancellable = true)
        @Override
        public void getSurroundingText(InputConnectionCommandHeader header, int beforeLength,
                int afterLength, int flags, AndroidFuture future /* T=SurroundingText */) {
            dispatchWithTracing("getSurroundingTextFromA11yIme", future, () -> {
                if (header.mSessionId != mCurrentSessionId.get()) {
                    return null;  // cancelled
                }
                final InputConnection ic = getInputConnection();
                if (ic == null || mDeactivateRequested.get()) {
                    Log.w(TAG, "getSurroundingText on inactive InputConnection");
                    return null;
                }
                if (beforeLength < 0) {
                    Log.i(TAG, "Returning null to getSurroundingText due to an invalid"
                            + " beforeLength=" + beforeLength);
                    return null;
                }
                if (afterLength < 0) {
                    Log.i(TAG, "Returning null to getSurroundingText due to an invalid"
                            + " afterLength=" + afterLength);
                    return null;
                }
                return ic.getSurroundingText(beforeLength, afterLength, flags);
            }, useImeTracing() ? result -> buildGetSurroundingTextProto(
                    beforeLength, afterLength, flags, result) : null);
        }

        @Dispatching(cancellable = true)
        @Override
        public void deleteSurroundingText(InputConnectionCommandHeader header, int beforeLength,
                int afterLength) {
            dispatchWithTracing("deleteSurroundingTextFromA11yIme", () -> {
                if (header.mSessionId != mCurrentSessionId.get()) {
                    return;  // cancelled
                }
                InputConnection ic = getInputConnection();
                if (ic == null || mDeactivateRequested.get()) {
                    Log.w(TAG, "deleteSurroundingText on inactive InputConnection");
                    return;
                }
                ic.deleteSurroundingText(beforeLength, afterLength);
            });
        }

        @Dispatching(cancellable = true)
        @Override
        public void sendKeyEvent(InputConnectionCommandHeader header, KeyEvent event) {
            dispatchWithTracing("sendKeyEventFromA11yIme", () -> {
                if (header.mSessionId != mCurrentSessionId.get()) {
                    return;  // cancelled
                }
                InputConnection ic = getInputConnection();
                if (ic == null || mDeactivateRequested.get()) {
                    Log.w(TAG, "sendKeyEvent on inactive InputConnection");
                    return;
                }
                ic.sendKeyEvent(event);
            });
        }

        @Dispatching(cancellable = true)
        @Override
        public void performEditorAction(InputConnectionCommandHeader header, int id) {
            dispatchWithTracing("performEditorActionFromA11yIme", () -> {
                if (header.mSessionId != mCurrentSessionId.get()) {
                    return;  // cancelled
                }
                InputConnection ic = getInputConnection();
                if (ic == null || mDeactivateRequested.get()) {
                    Log.w(TAG, "performEditorAction on inactive InputConnection");
                    return;
                }
                ic.performEditorAction(id);
            });
        }

        @Dispatching(cancellable = true)
        @Override
        public void performContextMenuAction(InputConnectionCommandHeader header, int id) {
            dispatchWithTracing("performContextMenuActionFromA11yIme", () -> {
                if (header.mSessionId != mCurrentSessionId.get()) {
                    return;  // cancelled
                }
                InputConnection ic = getInputConnection();
                if (ic == null || mDeactivateRequested.get()) {
                    Log.w(TAG, "performContextMenuAction on inactive InputConnection");
                    return;
                }
                ic.performContextMenuAction(id);
            });
        }

        @Dispatching(cancellable = true)
        @Override
        public void getCursorCapsMode(InputConnectionCommandHeader header, int reqModes,
                AndroidFuture future /* T=Integer */) {
            dispatchWithTracing("getCursorCapsModeFromA11yIme", future, () -> {
                if (header.mSessionId != mCurrentSessionId.get()) {
                    return 0;  // cancelled
                }
                final InputConnection ic = getInputConnection();
                if (ic == null || mDeactivateRequested.get()) {
                    Log.w(TAG, "getCursorCapsMode on inactive InputConnection");
                    return 0;
                }
                return ic.getCursorCapsMode(reqModes);
            }, useImeTracing() ? result -> buildGetCursorCapsModeProto(reqModes, result) : null);
        }

        @Dispatching(cancellable = true)
        @Override
        public void clearMetaKeyStates(InputConnectionCommandHeader header, int states) {
            dispatchWithTracing("clearMetaKeyStatesFromA11yIme", () -> {
                if (header.mSessionId != mCurrentSessionId.get()) {
                    return;  // cancelled
                }
                InputConnection ic = getInputConnection();
                if (ic == null || mDeactivateRequested.get()) {
                    Log.w(TAG, "clearMetaKeyStates on inactive InputConnection");
                    return;
                }
                ic.clearMetaKeyStates(states);
            });
        }
    };

    /**
     * @return {@link IRemoteAccessibilityInputConnection} associated with this object.
     */
    public IRemoteAccessibilityInputConnection asIRemoteAccessibilityInputConnection() {
        return mAccessibilityInputConnection;
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
