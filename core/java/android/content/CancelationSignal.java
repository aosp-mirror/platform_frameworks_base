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
public final class CancelationSignal {
    private boolean mIsCanceled;
    private OnCancelListener mOnCancelListener;
    private ICancelationSignal mRemote;

    /**
     * Creates a cancelation signal, initially not canceled.
     */
    public CancelationSignal() {
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
     * Cancels the operation and signals the cancelation listener.
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
     * Sets the cancelation listener to be called when canceled.
     * If {@link CancelationSignal#cancel} has already been called, then the provided
     * listener is invoked immediately.
     *
     * The listener is called while holding the cancelation signal's lock which is
     * also held while registering or unregistering the listener.  Because of the lock,
     * it is not possible for the listener to run after it has been unregistered.
     * This design choice makes it easier for clients of {@link CancelationSignal} to
     * prevent race conditions related to listener registration and unregistration.
     *
     * @param listener The cancelation listener, or null to remove the current listener.
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
    public void setRemote(ICancelationSignal remote) {
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
     * a Binder function and subsequently used to dispatch a cancelation signal.
     *
     * @return The new cancelation signal transport.
     *
     * @hide
     */
    public static ICancelationSignal createTransport() {
        return new Transport();
    }

    /**
     * Given a locally created transport, returns its associated cancelation signal.
     *
     * @param transport The locally created transport, or null if none.
     * @return The associated cancelation signal, or null if none.
     *
     * @hide
     */
    public static CancelationSignal fromTransport(ICancelationSignal transport) {
        if (transport instanceof Transport) {
            return ((Transport)transport).mCancelationSignal;
        }
        return null;
    }

    /**
     * Listens for cancelation.
     */
    public interface OnCancelListener {
        /**
         * Called when {@link CancelationSignal#cancel} is invoked.
         */
        void onCancel();
    }

    private static final class Transport extends ICancelationSignal.Stub {
        final CancelationSignal mCancelationSignal = new CancelationSignal();

        @Override
        public void cancel() throws RemoteException {
            mCancelationSignal.cancel();
        }
    }
}
