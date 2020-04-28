/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.net.Uri;
import android.os.Binder;
import android.telephony.AccessNetworkConstants;
import android.telephony.ims.aidl.IImsRegistrationCallback;
import android.telephony.ims.feature.ImsFeature;
import android.telephony.ims.stub.ImsRegistrationImplBase;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Manages IMS Service registration state for associated {@link ImsFeature}s.
 */
public interface RegistrationManager {

    /**
     * @hide
     */
    // Defines the underlying radio technology type that we have registered for IMS over.
    @IntDef(prefix = "REGISTRATION_STATE_",
            value = {
                    REGISTRATION_STATE_NOT_REGISTERED,
                    REGISTRATION_STATE_REGISTERING,
                    REGISTRATION_STATE_REGISTERED
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ImsRegistrationState {}

    /**
     * The IMS service is currently not registered to the carrier network.
     */
    int REGISTRATION_STATE_NOT_REGISTERED = 0;

    /**
     * The IMS service is currently in the process of registering to the carrier network.
     */
    int REGISTRATION_STATE_REGISTERING = 1;

    /**
     * The IMS service is currently registered to the carrier network.
     */
    int REGISTRATION_STATE_REGISTERED = 2;


    /**@hide*/
    // Translate ImsRegistrationImplBase API to new AccessNetworkConstant because WLAN
    // and WWAN are more accurate constants.
    Map<Integer, Integer> IMS_REG_TO_ACCESS_TYPE_MAP =
            new HashMap<Integer, Integer>() {{
                // Map NONE to -1 to make sure that we handle the REGISTRATION_TECH_NONE
                // case, since it is defined.
                put(ImsRegistrationImplBase.REGISTRATION_TECH_NONE, -1);
                put(ImsRegistrationImplBase.REGISTRATION_TECH_LTE,
                        AccessNetworkConstants.TRANSPORT_TYPE_WWAN);
                put(ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN,
                        AccessNetworkConstants.TRANSPORT_TYPE_WLAN);
            }};

    /**
     * Callback class for receiving IMS network Registration callback events.
     * @see #registerImsRegistrationCallback(Executor, RegistrationCallback)
     * @see #unregisterImsRegistrationCallback(RegistrationCallback)
     */
    class RegistrationCallback {

        private static class RegistrationBinder extends IImsRegistrationCallback.Stub {

            private final RegistrationCallback mLocalCallback;
            private Executor mExecutor;

            RegistrationBinder(RegistrationCallback localCallback) {
                mLocalCallback = localCallback;
            }

            @Override
            public void onRegistered(int imsRadioTech) {
                if (mLocalCallback == null) return;

                long callingIdentity = Binder.clearCallingIdentity();
                try {
                    mExecutor.execute(() ->
                            mLocalCallback.onRegistered(getAccessType(imsRadioTech)));
                } finally {
                    restoreCallingIdentity(callingIdentity);
                }
            }

            @Override
            public void onRegistering(int imsRadioTech) {
                if (mLocalCallback == null) return;

                long callingIdentity = Binder.clearCallingIdentity();
                try {
                    mExecutor.execute(() ->
                            mLocalCallback.onRegistering(getAccessType(imsRadioTech)));
                } finally {
                    restoreCallingIdentity(callingIdentity);
                }
            }

            @Override
            public void onDeregistered(ImsReasonInfo info) {
                if (mLocalCallback == null) return;

                long callingIdentity = Binder.clearCallingIdentity();
                try {
                    mExecutor.execute(() -> mLocalCallback.onUnregistered(info));
                } finally {
                    restoreCallingIdentity(callingIdentity);
                }
            }

            @Override
            public void onTechnologyChangeFailed(int imsRadioTech, ImsReasonInfo info) {
                if (mLocalCallback == null) return;

                long callingIdentity = Binder.clearCallingIdentity();
                try {
                    mExecutor.execute(() -> mLocalCallback.onTechnologyChangeFailed(
                            getAccessType(imsRadioTech), info));
                } finally {
                    restoreCallingIdentity(callingIdentity);
                }
            }

            public void onSubscriberAssociatedUriChanged(Uri[] uris) {
                if (mLocalCallback == null) return;

                long callingIdentity = Binder.clearCallingIdentity();
                try {
                    mExecutor.execute(() -> mLocalCallback.onSubscriberAssociatedUriChanged(uris));
                } finally {
                    restoreCallingIdentity(callingIdentity);
                }
            }

            private void setExecutor(Executor executor) {
                mExecutor = executor;
            }

            private static int getAccessType(int regType) {
                if (!RegistrationManager.IMS_REG_TO_ACCESS_TYPE_MAP.containsKey(regType)) {
                    Log.w("RegistrationManager", "RegistrationBinder - invalid regType returned: "
                            + regType);
                    return -1;
                }
                return RegistrationManager.IMS_REG_TO_ACCESS_TYPE_MAP.get(regType);
            }
        }

        private final RegistrationBinder mBinder = new RegistrationBinder(this);

        /**
         * Notifies the framework when the IMS Provider is registered to the IMS network.
         *
         * @param imsTransportType the radio access technology.
         */
        public void onRegistered(@AccessNetworkConstants.TransportType int imsTransportType) {
        }

        /**
         * Notifies the framework when the IMS Provider is trying to register the IMS network.
         *
         * @param imsTransportType the radio access technology.
         */
        public void onRegistering(@AccessNetworkConstants.TransportType int imsTransportType) {
        }

        /**
         * Notifies the framework when the IMS Provider is unregistered from the IMS network.
         *
         * @param info the {@link ImsReasonInfo} associated with why registration was disconnected.
         */
        public void onUnregistered(@NonNull ImsReasonInfo info) {
        }

        /**
         * A failure has occurred when trying to handover registration to another technology type.
         *
         * @param imsTransportType The transport type that has failed to handover registration to.
         * @param info A {@link ImsReasonInfo} that identifies the reason for failure.
         */
        public void onTechnologyChangeFailed(
                @AccessNetworkConstants.TransportType int imsTransportType,
                @NonNull ImsReasonInfo info) {
        }

        /**
         * Returns a list of subscriber {@link Uri}s associated with this IMS subscription when
         * it changes. Per RFC3455, an associated URI is a URI that the service provider has
         * allocated to a user for their own usage. A user's phone number is typically one of the
         * associated URIs.
         * @param uris new array of subscriber {@link Uri}s that are associated with this IMS
         *         subscription.
         * @hide
         */
        public void onSubscriberAssociatedUriChanged(@Nullable Uri[] uris) {
        }

        /**@hide*/
        public final IImsRegistrationCallback getBinder() {
            return mBinder;
        }

        /**@hide*/
        //Only exposed as public for compatibility with deprecated ImsManager APIs.
        public void setExecutor(Executor executor) {
            mBinder.setExecutor(executor);
        }
    }

    /**
     * Registers a {@link RegistrationCallback} with the system. Use
     * @param executor The {@link Executor} that will be used to call the IMS registration state
     *                 callback.
     * @param c A callback called on the supplied {@link Executor} that will contain the
     *                      registration state of the IMS service, which will be one of the
     * {@see  SubscriptionManager.OnSubscriptionsChangedListener} to listen to Subscription changed
     * events and call {@link #unregisterImsRegistrationCallback(RegistrationCallback)} to clean up.
     *
     * When the callback is registered, it will initiate the callback c to be called with the
     * current registration state.
     *
     * @param executor The executor the callback events should be run on.
     * @param c The {@link RegistrationCallback} to be added.
     * @see #unregisterImsRegistrationCallback(RegistrationCallback)
     * @throws ImsException if the subscription associated with this callback is valid, but
     * the {@link ImsService} associated with the subscription is not available. This can happen if
     * the service crashed, for example. See {@link ImsException#getCode()} for a more detailed
     * reason.
     */
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    void registerImsRegistrationCallback(@NonNull @CallbackExecutor Executor executor,
            @NonNull RegistrationCallback c) throws ImsException;

    /**
     * Removes an existing {@link RegistrationCallback}.
     *
     * When the subscription associated with this callback is removed (SIM removed, ESIM swap,
     * etc...), this callback will automatically be removed. If this method is called for an
     * inactive subscription, it will result in a no-op.
     *
     * @param c The {@link RegistrationCallback} to be removed.
     * @see android.telephony.SubscriptionManager.OnSubscriptionsChangedListener
     * @see #registerImsRegistrationCallback(Executor, RegistrationCallback)
     */
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    void unregisterImsRegistrationCallback(@NonNull RegistrationCallback c);

    /**
     * Gets the registration state of the IMS service.
     * @param executor The {@link Executor} that will be used to call the IMS registration state
     *                 callback.
     * @param stateCallback A callback called on the supplied {@link Executor} that will contain the
 *                      registration state of the IMS service, which will be one of the
 *                      following: {@link #REGISTRATION_STATE_NOT_REGISTERED},
 *                      {@link #REGISTRATION_STATE_REGISTERING}, or
 *                      {@link #REGISTRATION_STATE_REGISTERED}.
     */
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    void getRegistrationState(@NonNull @CallbackExecutor Executor executor,
            @NonNull @ImsRegistrationState Consumer<Integer> stateCallback);

    /**
     * Gets the Transport Type associated with the current IMS registration.
     * @param executor The {@link Executor} that will be used to call the transportTypeCallback.
     * @param transportTypeCallback The transport type associated with the current IMS registration,
     * which will be one of following:
     * {@see AccessNetworkConstants#TRANSPORT_TYPE_WWAN},
     * {@see AccessNetworkConstants#TRANSPORT_TYPE_WLAN}, or
     * {@see AccessNetworkConstants#TRANSPORT_TYPE_INVALID}.
     */
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    void getRegistrationTransportType(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull @AccessNetworkConstants.TransportType Consumer<Integer> transportTypeCallback);
}
