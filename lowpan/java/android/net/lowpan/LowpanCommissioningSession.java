/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.net.lowpan;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.IpPrefix;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;

/**
 * Commissioning Session.
 *
 * <p>This class enables a device to learn the credential needed to join a network using a technique
 * called "in-band commissioning".
 *
 * @hide
 */
// @SystemApi
public class LowpanCommissioningSession {

    private final ILowpanInterface mBinder;
    private final LowpanBeaconInfo mBeaconInfo;
    private final ILowpanInterfaceListener mInternalCallback = new InternalCallback();
    private final Looper mLooper;
    private Handler mHandler;
    private Callback mCallback = null;
    private volatile boolean mIsClosed = false;

    /**
     * Callback base class for {@link LowpanCommissioningSession}
     *
     * @hide
     */
    // @SystemApi
    public abstract static class Callback {
        public void onReceiveFromCommissioner(@NonNull byte[] packet) {};

        public void onClosed() {};
    }

    private class InternalCallback extends ILowpanInterfaceListener.Stub {
        @Override
        public void onStateChanged(String value) {
            if (!mIsClosed) {
                switch (value) {
                    case ILowpanInterface.STATE_OFFLINE:
                    case ILowpanInterface.STATE_FAULT:
                        synchronized (LowpanCommissioningSession.this) {
                            lockedCleanup();
                        }
                }
            }
        }

        @Override
        public void onReceiveFromCommissioner(byte[] packet) {
            mHandler.post(
                    () -> {
                        synchronized (LowpanCommissioningSession.this) {
                            if (!mIsClosed && (mCallback != null)) {
                                mCallback.onReceiveFromCommissioner(packet);
                            }
                        }
                    });
        }

        // We ignore all other callbacks.
        @Override
        public void onEnabledChanged(boolean value) {}

        @Override
        public void onConnectedChanged(boolean value) {}

        @Override
        public void onUpChanged(boolean value) {}

        @Override
        public void onRoleChanged(String value) {}

        @Override
        public void onLowpanIdentityChanged(LowpanIdentity value) {}

        @Override
        public void onLinkNetworkAdded(IpPrefix value) {}

        @Override
        public void onLinkNetworkRemoved(IpPrefix value) {}

        @Override
        public void onLinkAddressAdded(String value) {}

        @Override
        public void onLinkAddressRemoved(String value) {}
    }

    LowpanCommissioningSession(
            ILowpanInterface binder, LowpanBeaconInfo beaconInfo, Looper looper) {
        mBinder = binder;
        mBeaconInfo = beaconInfo;
        mLooper = looper;

        if (mLooper != null) {
            mHandler = new Handler(mLooper);
        } else {
            mHandler = new Handler();
        }

        try {
            mBinder.addListener(mInternalCallback);

        } catch (RemoteException x) {
            throw x.rethrowAsRuntimeException();
        }
    }

    private void lockedCleanup() {
        // Note: this method is only called from synchronized contexts.

        if (!mIsClosed) {
            try {
                mBinder.removeListener(mInternalCallback);

            } catch (DeadObjectException x) {
                /* We don't care if we receive a DOE at this point.
                 * DOE is as good as success as far as we are concerned.
                 */

            } catch (RemoteException x) {
                throw x.rethrowAsRuntimeException();
            }

            if (mCallback != null) {
                mHandler.post(() -> mCallback.onClosed());
            }
        }

        mCallback = null;
        mIsClosed = true;
    }

    /** TODO: doc */
    @NonNull
    public LowpanBeaconInfo getBeaconInfo() {
        return mBeaconInfo;
    }

    /** TODO: doc */
    public void sendToCommissioner(@NonNull byte[] packet) {
        if (!mIsClosed) {
            try {
                mBinder.sendToCommissioner(packet);

            } catch (DeadObjectException x) {
                /* This method is a best-effort delivery.
                 * We don't care if we receive a DOE at this point.
                 */

            } catch (RemoteException x) {
                throw x.rethrowAsRuntimeException();
            }
        }
    }

    /** TODO: doc */
    public synchronized void setCallback(@Nullable Callback cb, @Nullable Handler handler) {
        if (!mIsClosed) {
            /* This class can be created with or without a default looper.
             * Also, this method can be called with or without a specific
             * handler. If a handler is specified, it is to always be used.
             * Otherwise, if there was a Looper specified when this object
             * was created, we create a new handle based on that looper.
             * Otherwise we just create a default handler object. Since we
             * don't really know how the previous handler was created, we
             * end up always replacing it here. This isn't a huge problem
             * because this method should be called infrequently.
             */
            if (handler != null) {
                mHandler = handler;
            } else if (mLooper != null) {
                mHandler = new Handler(mLooper);
            } else {
                mHandler = new Handler();
            }
            mCallback = cb;
        }
    }

    /** TODO: doc */
    public synchronized void close() {
        if (!mIsClosed) {
            try {
                mBinder.closeCommissioningSession();

                lockedCleanup();

            } catch (DeadObjectException x) {
                /* We don't care if we receive a DOE at this point.
                 * DOE is as good as success as far as we are concerned.
                 */

            } catch (RemoteException x) {
                throw x.rethrowAsRuntimeException();
            }
        }
    }
}
