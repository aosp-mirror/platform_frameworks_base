/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.content;

import android.os.RemoteException;

/**
 * Provides the ability to cancel an operation in progress.
 */
public final class CancellationSignal {
    private boolean mIsCanceled;
    private OnCancelListener mOnCancelListener;
    private ICancellationSignal mRemote;

    /**
     * Creates a cancellation signal, initially not canceled.
     */
    public CancellationSignal() {
    }

    /**
     * Returns true if the operation has been canceled.
     *
     * @return True if the operation has been canceled.
     */
    public boolean isCanceled() {
        synchronized (this) {
            return mIsCanceled;
        }
    }

    /**
     * Throws {@link OperationCanceledException} if the operation has been canceled.
     *
     * @throws OperationCanceledException if the operation has been canceled.
     */
    public void throwIfCanceled() {
        if (isCanceled()) {
            throw new OperationCanceledException();
        }
    }

    /**
     * Cancels the operation and signals the cancellation listener.
     * If the operation has not yet started, then it will be canceled as soon as it does.
     */
    public void cancel() {
        synchronized (this) {
            if (!mIsCanceled) {
                mIsCanceled = true;
                if (mOnCancelListener != null) {
                    mOnCancelListener.onCancel();
                }
                if (mRemote != null) {
                    try {
                        mRemote.cancel();
                    } catch (RemoteException ex) {
                    }
                }
            }
        }
    }

    /**
     * Sets the cancellation listener to be called when canceled.
     *
     * This method is intended to be used by the recipient of a cancellation signal
     * such as a database or a content provider to handle cancellation requests
     * while performing a long-running operation.  This method is not intended to be
     * used by applications themselves.
     *
     * If {@link CancellationSignal#cancel} has already been called, then the provided
     * listener is invoked immediately.
     *
     * The listener is called while holding the cancellation signal's lock which is
     * also held while registering or unregistering the listener.  Because of the lock,
     * it is not possible for the listener to run after it has been unregistered.
     * This design choice makes it easier for clients of {@link CancellationSignal} to
     * prevent race conditions related to listener registration and unregistration.
     *
     * @param listener The cancellation listener, or null to remove the current listener.
     */
    public void setOnCancelListener(OnCancelListener listener) {
        synchronized (this) {
            mOnCancelListener = listener;
            if (mIsCanceled && listener != null) {
                listener.onCancel();
            }
        }
    }

    /**
     * Sets the remote transport.
     *
     * @param remote The remote transport, or null to remove.
     *
     * @hide
     */
    public void setRemote(ICancellationSignal remote) {
        synchronized (this) {
            mRemote = remote;
            if (mIsCanceled && remote != null) {
                try {
                    remote.cancel();
                } catch (RemoteException ex) {
                }
            }
        }
    }

    /**
     * Creates a transport that can be returned back to the caller of
     * a Binder function and subsequently used to dispatch a cancellation signal.
     *
     * @return The new cancellation signal transport.
     *
     * @hide
     */
    public static ICancellationSignal createTransport() {
        return new Transport();
    }

    /**
     * Given a locally created transport, returns its associated cancellation signal.
     *
     * @param transport The locally created transport, or null if none.
     * @return The associated cancellation signal, or null if none.
     *
     * @hide
     */
    public static CancellationSignal fromTransport(ICancellationSignal transport) {
        if (transport instanceof Transport) {
            return ((Transport)transport).mCancellationSignal;
        }
        return null;
    }

    /**
     * Listens for cancellation.
     */
    public interface OnCancelListener {
        /**
         * Called when {@link CancellationSignal#cancel} is invoked.
         */
        void onCancel();
    }

    private static final class Transport extends ICancellationSignal.Stub {
        final CancellationSignal mCancellationSignal = new CancellationSignal();

        @Override
        public void cancel() throws RemoteException {
            mCancellationSignal.cancel();
        }
    }
}
