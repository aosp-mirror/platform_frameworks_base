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
    private final CloseGuard mCloseGuard;
    private final Executor mUiThread;

    private ScrollCaptureCallback mLocal;
    private IScrollCaptureCallbacks mRemote;

    private ScrollCaptureSession mSession;

    private CancellationSignal mCancellation;

    private volatile boolean mStarted;
    private volatile boolean mConnected;

    /**
     * Constructs a ScrollCaptureConnection.
     *
     * @param selectedTarget  the target the client is controlling
     * @param remote the callbacks to reply to system requests
     *
     * @hide
     */
    public ScrollCaptureConnection(
            @NonNull Executor uiThread,
            @NonNull ScrollCaptureTarget selectedTarget,
            @NonNull IScrollCaptureCallbacks remote) {
        mUiThread = requireNonNull(uiThread, "<uiThread> must non-null");
        requireNonNull(selectedTarget, "<selectedTarget> must non-null");
        mRemote = requireNonNull(remote, "<callbacks> must non-null");
        mScrollBounds = requireNonNull(Rect.copyOrNull(selectedTarget.getScrollBounds()),
                "target.getScrollBounds() must be non-null to construct a client");

        mLocal = selectedTarget.getCallback();
        mPositionInWindow = new Point(selectedTarget.getPositionInWindow());

        mCloseGuard = new CloseGuard();
        mCloseGuard.open("close");
        mConnected = true;
    }

    @BinderThread
    @Override
    public ICancellationSignal startCapture(Surface surface) throws RemoteException {
        checkConnected();
        if (!surface.isValid()) {
            throw new RemoteException(new IllegalArgumentException("surface must be valid"));
        }

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
        mStarted = true;
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
        checkConnected();
        checkStarted();

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
        checkConnected();
        checkStarted();

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
        synchronized (mLock) {
            mStarted = false;
            try {
                mRemote.onCaptureEnded();
            } catch (RemoteException e) {
                Log.w(TAG, "Shutting down due to error: ", e);
                close();
            }
        }
    }

    @BinderThread
    @Override
    public void close() {
        if (mStarted) {
            Log.w(TAG, "close(): capture is still started?! Ending now.");

            // -> UiThread
            mUiThread.execute(() -> mLocal.onScrollCaptureEnd(() -> { /* ignore */ }));
            mStarted = false;
        }
        disconnect();
    }

    /**
     * Shuts down this client and releases references to dependent objects. No attempt is made
     * to notify the controller, use with caution!
     */
    private void disconnect() {
        synchronized (mLock) {
            mSession = null;
            mConnected = false;
            mStarted = false;
            mRemote = null;
            mLocal = null;
            mCloseGuard.close();
        }
    }

    public boolean isConnected() {
        return mConnected;
    }

    public boolean isStarted() {
        return mStarted;
    }

    private synchronized void checkConnected() throws RemoteException {
        synchronized (mLock) {
            if (!mConnected) {
                throw new RemoteException(new IllegalStateException("Not connected"));
            }
        }
    }

    private void checkStarted() throws RemoteException {
        synchronized (mLock) {
            if (!mStarted) {
                throw new RemoteException(new IllegalStateException("Not started!"));
            }
        }
    }

    /** @return a string representation of the state of this client */
    public String toString() {
        return "ScrollCaptureConnection{"
                + "connected=" + mConnected
                + ", started=" + mStarted
                + ", session=" + mSession
                + ", remote=" + mRemote
                + ", local=" + mLocal
                + "}";
    }

    @VisibleForTesting
    public CancellationSignal getCancellation() {
        return mCancellation;
    }

    protected void finalize() throws Throwable {
        try {
            if (mCloseGuard != null) {
                mCloseGuard.warnIfOpen();
            }
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
