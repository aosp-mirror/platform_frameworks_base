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

package android.telephony.ims;

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Binder;

import com.android.internal.telephony.IImsStateCallback;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.concurrent.Executor;

/**
 * A callback class used for monitoring changes in IMS service connection states
 * for a specific subscription.
 * <p>
 * @see ImsMmTelManager#registerImsStateCallback(Executor, ImsStateCallback)
 * @see ImsRcsManager#registerImsStateCallback(Executor, ImsStateCallback)
 */
public abstract class ImsStateCallback {

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "REASON_", value = {
            REASON_UNKNOWN_TEMPORARY_ERROR,
            REASON_UNKNOWN_PERMANENT_ERROR,
            REASON_IMS_SERVICE_DISCONNECTED,
            REASON_NO_IMS_SERVICE_CONFIGURED,
            REASON_SUBSCRIPTION_INACTIVE,
            REASON_IMS_SERVICE_NOT_READY
    })
    public @interface DisconnectedReason {}

    /**
     * The underlying IMS service is temporarily unavailable for the
     * associated subscription.
     * {@link #onAvailable} will be called when the IMS service becomes
     * available again.
     */
    public static final int REASON_UNKNOWN_TEMPORARY_ERROR     = 1;

    /**
     * The underlying IMS service is permanently unavailable for the
     * associated subscription and there will be no Manager available for
     * this subscription.
     */
    public static final int REASON_UNKNOWN_PERMANENT_ERROR     = 2;

    /**
     * The underlying IMS service has died, is reconfiguring, or has never
     * come up yet and as a result is currently unavailable.
     * {@link #onAvailable} will be called when the IMS service becomes
     * available. All callbacks should be unregistered now and registered again
     * if the IMS service moves back to available.
     */
    public static final int REASON_IMS_SERVICE_DISCONNECTED    = 3;

    /**
     * There is no IMS service configured for the subscription ID specified.
     * This is a permanent error and there will be no Manager available for
     * this subscription.
     */
    public static final int REASON_NO_IMS_SERVICE_CONFIGURED   = 4;

    /**
     * The subscription associated with this Manager has moved to an inactive
     * state (e.g. SIM removed) and the IMS service has torn down the resources
     * related to this subscription. This has caused this callback
     * to be deregistered. The callback must be re-registered when this subscription
     * becomes active in order to continue listening to the IMS service state.
     */
    public static final int REASON_SUBSCRIPTION_INACTIVE       = 5;

    /**
     * The IMS service is connected, but in a NOT_READY state. Once the
     * service moves to ready, {@link #onAvailable} will be called.
     */
    public static final int REASON_IMS_SERVICE_NOT_READY       = 6;

    private IImsStateCallbackStub mCallback;

    /**
     * @hide
     */
    public void init(@NonNull @CallbackExecutor Executor executor) {
        if (executor == null) {
            throw new IllegalArgumentException("ImsStateCallback Executor must be non-null");
        }
        mCallback = new IImsStateCallbackStub(this, executor);
    }

    /**
     * Using a static class and weak reference here to avoid memory leak caused by the
     * IImsStateCallback.Stub callback retaining references to the outside ImsStateCallback.
     */
    private static class IImsStateCallbackStub extends IImsStateCallback.Stub {
        private WeakReference<ImsStateCallback> mImsStateCallbackWeakRef;
        private Executor mExecutor;

        IImsStateCallbackStub(ImsStateCallback imsStateCallback, Executor executor) {
            mImsStateCallbackWeakRef = new WeakReference<ImsStateCallback>(imsStateCallback);
            mExecutor = executor;
        }

        Executor getExecutor() {
            return mExecutor;
        }

        public void onAvailable() {
            ImsStateCallback callback = mImsStateCallbackWeakRef.get();
            if (callback == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> callback.onAvailable()));
        }

        public void onUnavailable(int reason) {
            ImsStateCallback callback = mImsStateCallbackWeakRef.get();
            if (callback == null) return;

            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> callback.onUnavailable(reason)));
        }
    }

    /**
     * The IMS service has disconnected or is reporting NOT_READY and is no longer
     * available to users. The user should clean up all related state and
     * unregister callbacks. If it is a temporary error, {@link #onAvailable} will
     * be called when the IMS service becomes available again.
     *
     * @param reason the specified reason
     */
    public abstract void onUnavailable(@DisconnectedReason int reason);

    /**
     * The IMS service is connected and is ready for communication over the
     * provided Manager.
     */
    public abstract void onAvailable();

    /**
     * An unexpected error has occurred and the Telephony process has crashed. This
     * has caused this callback to be deregistered. The callback must be
     * re-registered in order to continue listening to the IMS service state.
     */
    public abstract void onError();

    /**
     * The callback to notify the death of telephony process
     * @hide
     */
    public final void binderDied() {
        if (mCallback != null) {
            mCallback.getExecutor().execute(() -> onError());
        }
    }

    /**
     * Return the callback binder
     * @hide
     */
    public IImsStateCallbackStub getCallbackBinder() {
        return mCallback;
    }
}
