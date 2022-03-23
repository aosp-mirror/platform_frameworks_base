/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.view;

import static java.util.Objects.requireNonNull;

import android.annotation.BinderThread;
import android.annotation.NonNull;
import android.annotation.UiThread;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.CancellationSignal;
import android.os.ICancellationSignal;
import android.os.RemoteException;
import android.os.Trace;
import android.util.CloseGuard;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Mediator between a selected scroll capture target view and a remote process.
 * <p>
 * An instance is created to wrap the selected {@link ScrollCaptureCallback}.
 *
 * @hide
 */
public class ScrollCaptureConnection extends IScrollCaptureConnection.Stub {

    private static final String TAG = "ScrollCaptureConnection";

    private final Object mLock = new Object();
    private final Rect mScrollBounds;
    private final Point mPositionInWindow;
    private final Executor mUiThread;
    private final CloseGuard mCloseGuard = new CloseGuard();


    private ScrollCaptureCallback mLocal;
    private IScrollCaptureCallbacks mRemote;

    private ScrollCaptureSession mSession;

    private CancellationSignal mCancellation;

    private volatile boolean mActive;

    /**
     * Constructs a ScrollCaptureConnection.
     *
     * @param uiThread an executor for the UI thread of the containing View
     * @param selectedTarget  the target the client is controlling
     *
     * @hide
     */
    public ScrollCaptureConnection(
            @NonNull Executor uiThread,
            @NonNull ScrollCaptureTarget selectedTarget) {
        mUiThread = requireNonNull(uiThread, "<uiThread> must non-null");
        requireNonNull(selectedTarget, "<selectedTarget> must non-null");
        mScrollBounds = requireNonNull(Rect.copyOrNull(selectedTarget.getScrollBounds()),
                "target.getScrollBounds() must be non-null to construct a client");
        mLocal = selectedTarget.getCallback();
        mPositionInWindow = new Point(selectedTarget.getPositionInWindow());
    }

    @BinderThread
    @Override
    public ICancellationSignal startCapture(@NonNull Surface surface,
            @NonNull IScrollCaptureCallbacks remote) throws RemoteException {

        mCloseGuard.open("close");

        if (!surface.isValid()) {
            throw new RemoteException(new IllegalArgumentException("surface must be valid"));
        }
        mRemote = requireNonNull(remote, "<callbacks> must non-null");

        ICancellationSignal cancellation = CancellationSignal.createTransport();
        mCancellation = CancellationSignal.fromTransport(cancellation);
        mSession = new ScrollCaptureSession(surface, mScrollBounds, mPositionInWindow);

        Runnable listener =
                SafeCallback.create(mCancellation, mUiThread, this::onStartCaptureCompleted);
        // -> UiThread
        mUiThread.execute(() -> mLocal.onScrollCaptureStart(mSession, mCancellation, listener));
        return cancellation;
    }

    @UiThread
    private void onStartCaptureCompleted() {
        mActive = true;
        try {
            mRemote.onCaptureStarted();
        } catch (RemoteException e) {
            Log.w(TAG, "Shutting down due to error: ", e);
            close();
        }
    }

    @BinderThread
    @Override
    public ICancellationSignal requestImage(Rect requestRect) throws RemoteException {
        Trace.beginSection("requestImage");
        checkActive();

        ICancellationSignal cancellation = CancellationSignal.createTransport();
        mCancellation = CancellationSignal.fromTransport(cancellation);

        Consumer<Rect> listener =
                SafeCallback.create(mCancellation, mUiThread, this::onImageRequestCompleted);
        // -> UiThread
        mUiThread.execute(() -> mLocal.onScrollCaptureImageRequest(
                mSession, mCancellation, new Rect(requestRect), listener));
        Trace.endSection();
        return cancellation;
    }

    @UiThread
    void onImageRequestCompleted(Rect capturedArea) {
        try {
            mRemote.onImageRequestCompleted(0, capturedArea);
        } catch (RemoteException e) {
            Log.w(TAG, "Shutting down due to error: ", e);
            close();
        }
    }

    @BinderThread
    @Override
    public ICancellationSignal endCapture() throws RemoteException {
        checkActive();

        ICancellationSignal cancellation = CancellationSignal.createTransport();
        mCancellation = CancellationSignal.fromTransport(cancellation);

        Runnable listener =
                SafeCallback.create(mCancellation, mUiThread, this::onEndCaptureCompleted);
        // -> UiThread
        mUiThread.execute(() -> mLocal.onScrollCaptureEnd(listener));
        return cancellation;
    }

    @UiThread
    private void onEndCaptureCompleted() {
        mActive = false;
        try {
            if (mRemote != null) {
                mRemote.onCaptureEnded();
            }
        } catch (RemoteException e) {
            Log.w(TAG, "Caught exception confirming capture end!", e);
        } finally {
            close();
        }
    }

    @BinderThread
    @Override
    public void close() {
        if (mActive) {
            if (mCancellation != null) {
                Log.w(TAG, "close(): cancelling pending operation.");
                mCancellation.cancel();
                mCancellation = null;
            }
            Log.w(TAG, "close(): capture session still active! Ending now.");
            // -> UiThread
            final ScrollCaptureCallback callback = mLocal;
            mUiThread.execute(() -> callback.onScrollCaptureEnd(() -> { /* ignore */ }));
            mActive = false;
        }
        mActive = false;
        mSession = null;
        mRemote = null;
        mLocal = null;
        mCloseGuard.close();
        Reference.reachabilityFence(this);
    }

    @VisibleForTesting
    public boolean isActive() {
        return mActive;
    }

    private void checkActive() throws RemoteException {
        synchronized (mLock) {
            if (!mActive) {
                throw new RemoteException(new IllegalStateException("Not started!"));
            }
        }
    }

    /** @return a string representation of the state of this client */
    public String toString() {
        return "ScrollCaptureConnection{"
                + "active=" + mActive
                + ", session=" + mSession
                + ", remote=" + mRemote
                + ", local=" + mLocal
                + "}";
    }

    protected void finalize() throws Throwable {
        try {
            mCloseGuard.warnIfOpen();
            close();
        } finally {
            super.finalize();
        }
    }

    private static class SafeCallback<T> {
        private final CancellationSignal mSignal;
        private final WeakReference<T> mTargetRef;
        private final Executor mExecutor;
        private boolean mExecuted;

        protected SafeCallback(CancellationSignal signal, Executor executor, T target) {
            mSignal = signal;
            mTargetRef = new WeakReference<>(target);
            mExecutor = executor;
        }

        // Provide the target to the consumer to invoke, forward on handler thread ONCE,
        // and only if noy cancelled, and the target is still available (not collected)
        protected final void maybeAccept(Consumer<T> targetConsumer) {
            if (mExecuted) {
                return;
            }
            mExecuted = true;
            if (mSignal.isCanceled()) {
                return;
            }
            T target = mTargetRef.get();
            if (target == null) {
                return;
            }
            mExecutor.execute(() -> targetConsumer.accept(target));
        }

        static Runnable create(CancellationSignal signal, Executor executor, Runnable target) {
            return new RunnableCallback(signal, executor, target);
        }

        static <T> Consumer<T> create(CancellationSignal signal, Executor executor,
                Consumer<T> target) {
            return new ConsumerCallback<T>(signal, executor, target);
        }
    }

    private static final class RunnableCallback extends SafeCallback<Runnable> implements Runnable {
        RunnableCallback(CancellationSignal signal, Executor executor, Runnable target) {
            super(signal, executor, target);
        }

        @Override
        public void run() {
            maybeAccept(Runnable::run);
        }
    }

    private static final class ConsumerCallback<T> extends SafeCallback<Consumer<T>>
            implements Consumer<T> {
        ConsumerCallback(CancellationSignal signal, Executor executor, Consumer<T> target) {
            super(signal, executor, target);
        }

        @Override
        public void accept(T value) {
            maybeAccept((target) -> target.accept(value));
        }
    }
}
