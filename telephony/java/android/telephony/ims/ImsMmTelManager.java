/*
 * Copyright (C) 2018 The Android Open Source Project
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
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.content.Context;
import android.net.Uri;
import android.os.Binder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.telephony.SubscriptionManager;
import android.telephony.ims.aidl.IImsCapabilityCallback;
import android.telephony.ims.aidl.IImsRegistrationCallback;
import android.telephony.ims.feature.ImsFeature;
import android.telephony.ims.feature.MmTelFeature;
import android.telephony.ims.stub.ImsRegistrationImplBase;

import com.android.internal.telephony.ITelephony;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.Executor;

/**
 * A manager for the MmTel (Multimedia Telephony) feature of an IMS network, given an associated
 * subscription.
 *
 * Allows a user to query the IMS MmTel feature information for a subscription, register for
 * registration and MmTel capability status callbacks, as well as query/modify user settings for the
 * associated subscription.
 *
 * @see #createForSubscriptionId(Context, int)
 * @hide
 */
public class ImsMmTelManager {

    private static final String TAG = "ImsMmTelManager";

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "WIFI_MODE_", value = {
            WIFI_MODE_WIFI_ONLY,
            WIFI_MODE_CELLULAR_PREFERRED,
            WIFI_MODE_WIFI_PREFERRED
            })
    public @interface WiFiCallingMode {}

    /**
     * Register for IMS over IWLAN if WiFi signal quality is high enough. Do not hand over to LTE
     * registration if signal quality degrades.
     * @hide
     */
    @SystemApi
    public static final int WIFI_MODE_WIFI_ONLY = 0;

    /**
     * Prefer registering for IMS over LTE if LTE signal quality is high enough.
     * @hide
     */
    @SystemApi
    public static final int WIFI_MODE_CELLULAR_PREFERRED = 1;

    /**
     * Prefer registering for IMS over IWLAN if possible if WiFi signal quality is high enough.
     * @hide
     */
    @SystemApi
    public static final int WIFI_MODE_WIFI_PREFERRED = 2;

    /**
     * Callback class for receiving Registration callback events.
     * @see #addImsRegistrationCallback(Executor, RegistrationCallback) (RegistrationCallback)
     * @see #removeImsRegistrationCallback(RegistrationCallback)
     */
    public static class RegistrationCallback {

        private static class RegistrationBinder extends IImsRegistrationCallback.Stub {

            private final RegistrationCallback mLocalCallback;
            private Executor mExecutor;

            RegistrationBinder(RegistrationCallback localCallback) {
                mLocalCallback = localCallback;
            }

            @Override
            public void onRegistered(int imsRadioTech) {
                if (mLocalCallback == null) return;

                Binder.withCleanCallingIdentity(() ->
                        mExecutor.execute(() -> mLocalCallback.onRegistered(imsRadioTech)));
            }

            @Override
            public void onRegistering(int imsRadioTech) {
                if (mLocalCallback == null) return;

                Binder.withCleanCallingIdentity(() ->
                        mExecutor.execute(() -> mLocalCallback.onRegistering(imsRadioTech)));
            }

            @Override
            public void onDeregistered(ImsReasonInfo info) {
                if (mLocalCallback == null) return;

                Binder.withCleanCallingIdentity(() ->
                        mExecutor.execute(() -> mLocalCallback.onDeregistered(info)));
            }

            @Override
            public void onTechnologyChangeFailed(int imsRadioTech, ImsReasonInfo info) {
                if (mLocalCallback == null) return;

                Binder.withCleanCallingIdentity(() ->
                        mExecutor.execute(() ->
                                mLocalCallback.onTechnologyChangeFailed(imsRadioTech, info)));
            }

            @Override
            public void onSubscriberAssociatedUriChanged(Uri[] uris) {
                if (mLocalCallback == null) return;

                Binder.withCleanCallingIdentity(() ->
                        mExecutor.execute(() ->
                                mLocalCallback.onSubscriberAssociatedUriChanged(uris)));
            }

            private void setExecutor(Executor executor) {
                mExecutor = executor;
            }
        }

        private final RegistrationBinder mBinder = new RegistrationBinder(this);

        /**
         * Notifies the framework when the IMS Provider is registered to the IMS network.
         *
         * @param imsRadioTech the radio access technology. Valid values are defined in
         * {@link ImsRegistrationImplBase.ImsRegistrationTech}.
         */
        public void onRegistered(@ImsRegistrationImplBase.ImsRegistrationTech int imsRadioTech) {
        }

        /**
         * Notifies the framework when the IMS Provider is trying to register the IMS network.
         *
         * @param imsRadioTech the radio access technology. Valid values are defined in
         * {@link ImsRegistrationImplBase.ImsRegistrationTech}.
         */
        public void onRegistering(@ImsRegistrationImplBase.ImsRegistrationTech int imsRadioTech) {
        }

        /**
         * Notifies the framework when the IMS Provider is deregistered from the IMS network.
         *
         * @param info the {@link ImsReasonInfo} associated with why registration was disconnected.
         */
        public void onDeregistered(ImsReasonInfo info) {
        }

        /**
         * A failure has occurred when trying to handover registration to another technology type,
         * defined in {@link ImsRegistrationImplBase.ImsRegistrationTech}
         *
         * @param imsRadioTech The {@link ImsRegistrationImplBase.ImsRegistrationTech} type that has
         *         failed
         * @param info A {@link ImsReasonInfo} that identifies the reason for failure.
         */
        public void onTechnologyChangeFailed(
                @ImsRegistrationImplBase.ImsRegistrationTech int imsRadioTech, ImsReasonInfo info) {
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
        public void onSubscriberAssociatedUriChanged(Uri[] uris) {
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
     * Receives IMS capability status updates from the ImsService.
     *
     * @see #addMmTelCapabilityCallback(Executor, CapabilityCallback) (CapabilityCallback)
     * @see #removeMmTelCapabilityCallback(CapabilityCallback)
     */
    public static class CapabilityCallback {

        private static class CapabilityBinder extends IImsCapabilityCallback.Stub {

            private final CapabilityCallback mLocalCallback;
            private Executor mExecutor;

            CapabilityBinder(CapabilityCallback c) {
                mLocalCallback = c;
            }

            @Override
            public void onCapabilitiesStatusChanged(int config) {
                if (mLocalCallback == null) return;

                Binder.withCleanCallingIdentity(() ->
                        mExecutor.execute(() -> mLocalCallback.onCapabilitiesStatusChanged(
                                new MmTelFeature.MmTelCapabilities(config))));
            }

            @Override
            public void onQueryCapabilityConfiguration(int capability, int radioTech,
                    boolean isEnabled) {
                // This is not used for public interfaces.
            }

            @Override
            public void onChangeCapabilityConfigurationError(int capability, int radioTech,
                    @ImsFeature.ImsCapabilityError int reason) {
                // This is not used for public interfaces
            }

            private void setExecutor(Executor executor) {
                mExecutor = executor;
            }
        }

        private final CapabilityBinder mBinder = new CapabilityBinder(this);

        /**
         * The status of the feature's capabilities has changed to either available or unavailable.
         * If unavailable, the feature is not able to support the unavailable capability at this
         * time.
         *
         * @param capabilities The new availability of the capabilities.
         */
        public void onCapabilitiesStatusChanged(
                MmTelFeature.MmTelCapabilities capabilities) {
        }

        /**@hide*/
        public final IImsCapabilityCallback getBinder() {
            return mBinder;
        }

        /**@hide*/
        // Only exposed as public method for compatibility with deprecated ImsManager APIs.
        // TODO: clean up dependencies and change back to private visibility.
        public void setExecutor(Executor executor) {
            mBinder.setExecutor(executor);
        }
    }

    private Context mContext;
    private int mSubId;

    /**
     * Create an instance of ImsManager for the subscription id specified.
     *
     * @param context
     * @param subId The ID of the subscription that this ImsManager will use.
     * @see android.telephony.SubscriptionManager#getActiveSubscriptionInfoList()
     * @throws IllegalArgumentException if the subscription is invalid or
     *         the subscription ID is not an active subscription.
     */
    public static ImsMmTelManager createForSubscriptionId(Context context, int subId) {
        if (!SubscriptionManager.isValidSubscriptionId(subId)
                || !getSubscriptionManager(context).isActiveSubscriptionId(subId)) {
            throw new IllegalArgumentException("Invalid subscription ID");
        }

        return new ImsMmTelManager(context, subId);
    }

    private ImsMmTelManager(Context context, int subId) {
        mContext = context;
        mSubId = subId;
    }

    /**
     * Registers a {@link RegistrationCallback} with the system, which will provide registration
     * updates for the subscription specified in {@link #createForSubscriptionId(Context, int)}. Use
     * {@link SubscriptionManager.OnSubscriptionsChangedListener} to listen to Subscription changed
     * events and call {@link #removeImsRegistrationCallback(RegistrationCallback)} to clean up
     * after a subscription is removed.
     * @param executor The executor the callback events should be run on.
     * @param c The {@link RegistrationCallback} to be added.
     * @see #removeImsRegistrationCallback(RegistrationCallback)
     */
    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    public void addImsRegistrationCallback(@CallbackExecutor Executor executor,
            @NonNull RegistrationCallback c) {
        if (c == null) {
            throw new IllegalArgumentException("Must include a non-null RegistrationCallback.");
        }
        if (executor == null) {
            throw new IllegalArgumentException("Must include a non-null Executor.");
        }
        c.setExecutor(executor);
        try {
            getITelephony().addImsRegistrationCallback(mSubId, c.getBinder(),
                    mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Removes an existing {@link RegistrationCallback}. Ensure to call this method when cleaning
     * up to avoid memory leaks or when the subscription is removed.
     * @param c The {@link RegistrationCallback} to be removed.
     * @see SubscriptionManager.OnSubscriptionsChangedListener
     * @see #addImsRegistrationCallback(Executor, RegistrationCallback)
     */
    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    public void removeImsRegistrationCallback(@NonNull RegistrationCallback c) {
        if (c == null) {
            throw new IllegalArgumentException("Must include a non-null RegistrationCallback.");
        }
        try {
            getITelephony().removeImsRegistrationCallback(mSubId, c.getBinder(),
                    mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Registers a {@link CapabilityCallback} with the system, which will provide MmTel capability
     * updates for the subscription specified in {@link #createForSubscriptionId(Context, int)}.
     * Use {@link SubscriptionManager.OnSubscriptionsChangedListener} to listen to
     * subscription changed events and call
     * {@link #removeImsRegistrationCallback(RegistrationCallback)} to clean up after a subscription
     * is removed.
     * @param executor The executor the callback events should be run on.
     * @param c The MmTel {@link CapabilityCallback} to be registered.
     * @see #removeMmTelCapabilityCallback(CapabilityCallback)
     */
    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    public void addMmTelCapabilityCallback(@CallbackExecutor Executor executor,
            @NonNull CapabilityCallback c) {
        if (c == null) {
            throw new IllegalArgumentException("Must include a non-null RegistrationCallback.");
        }
        if (executor == null) {
            throw new IllegalArgumentException("Must include a non-null Executor.");
        }
        c.setExecutor(executor);
        try {
            getITelephony().addMmTelCapabilityCallback(mSubId, c.getBinder(),
                    mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Removes an existing MmTel {@link CapabilityCallback}. Be sure to call this when cleaning
     * up to avoid memory leaks.
     * @param c The MmTel {@link CapabilityCallback} to be removed.
     * @see #addMmTelCapabilityCallback(Executor, CapabilityCallback)
     */
    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    public void removeMmTelCapabilityCallback(@NonNull CapabilityCallback c) {
        if (c == null) {
            throw new IllegalArgumentException("Must include a non-null RegistrationCallback.");
        }
        try {
            getITelephony().removeMmTelCapabilityCallback(mSubId, c.getBinder(),
                    mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Query the user's setting for whether or not to use MmTel capabilities over IMS,
     * such as voice and video, depending on carrier configuration for the current subscription.
     * @see #setAdvancedCallingSetting(boolean)
     * @return true if the user’s setting for advanced calling is enabled and false otherwise.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public boolean isAdvancedCallingSettingEnabled() {
        try {
            return getITelephony().isAdvancedCallingSettingEnabled(mSubId);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Modify the user’s setting for “Advanced Calling” or "Enhanced 4G LTE", which is used to
     * enable MmTel IMS features, such as voice and video calling, depending on the carrier
     * configuration for the current subscription. Modifying this value may also trigger an IMS
     * registration or deregistration, depending on the new value.
     * @see #isAdvancedCallingEnabled()
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public void setAdvancedCallingSetting(boolean isEnabled) {
        try {
            getITelephony().setAdvancedCallingSetting(mSubId, isEnabled);
            return;
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Query the IMS MmTel capability for a given registration technology. This does not
     * necessarily mean that we are registered and the capability is available, but rather the
     * subscription is capable of this service over IMS.
     *
     * @see android.telephony.CarrierConfigManager#KEY_CARRIER_VOLTE_AVAILABLE_BOOL
     * @see android.telephony.CarrierConfigManager#KEY_CARRIER_VT_AVAILABLE_BOOL
     * @see android.telephony.CarrierConfigManager#KEY_CARRIER_IMS_GBA_REQUIRED_BOOL
     * @see #isAvailable(int, int)
     *
     * @param imsRegTech The IMS registration technology, can be one of the following:
     *         {@link ImsRegistrationImplBase#REGISTRATION_TECH_LTE},
     *         {@link ImsRegistrationImplBase#REGISTRATION_TECH_IWLAN}
     * @param capability The IMS MmTel capability to query, can be one of the following:
     *         {@link MmTelFeature.MmTelCapabilities#CAPABILITY_TYPE_VOICE},
     *         {@link MmTelFeature.MmTelCapabilities#CAPABILITY_TYPE_VIDEO,
     *         {@link MmTelFeature.MmTelCapabilities#CAPABILITY_TYPE_UT},
     *         {@link MmTelFeature.MmTelCapabilities#CAPABILITY_TYPE_SMS}
     * @return {@code true} if the MmTel IMS capability is capable for this subscription, false
     *         otherwise.
     */
    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    public boolean isCapable(@MmTelFeature.MmTelCapabilities.MmTelCapability int capability,
            @ImsRegistrationImplBase.ImsRegistrationTech int imsRegTech) {
        try {
            return getITelephony().isCapable(mSubId, capability, imsRegTech,
                    mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Query the availability of an IMS MmTel capability for a given registration technology. If
     * a capability is available, IMS is registered and the service is currently available over IMS.
     *
     * @see #isCapable(int, int)
     *
     * @param imsRegTech The IMS registration technology, can be one of the following:
     *         {@link ImsRegistrationImplBase#REGISTRATION_TECH_LTE},
     *         {@link ImsRegistrationImplBase#REGISTRATION_TECH_IWLAN}
     * @param capability The IMS MmTel capability to query, can be one of the following:
     *         {@link MmTelFeature.MmTelCapabilities#CAPABILITY_TYPE_VOICE},
     *         {@link MmTelFeature.MmTelCapabilities#CAPABILITY_TYPE_VIDEO,
     *         {@link MmTelFeature.MmTelCapabilities#CAPABILITY_TYPE_UT},
     *         {@link MmTelFeature.MmTelCapabilities#CAPABILITY_TYPE_SMS}
     * @return {@code true} if the MmTel IMS capability is available for this subscription, false
     *         otherwise.
     */
    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    public boolean isAvailable(@MmTelFeature.MmTelCapabilities.MmTelCapability int capability,
            @ImsRegistrationImplBase.ImsRegistrationTech int imsRegTech) {
        try {
            return getITelephony().isAvailable(mSubId, capability, imsRegTech,
                    mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * The user's setting for whether or not they have enabled the "Video Calling" setting.
     * @return true if the user’s “Video Calling” setting is currently enabled.
     * @see #setVtSetting(boolean)
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    public boolean isVtSettingEnabled() {
        try {
            return getITelephony().isVtSettingEnabled(mSubId, mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Change the user's setting for Video Telephony and enable the Video Telephony capability.
     * @see #isVtSettingEnabled()
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public void setVtSetting(boolean isEnabled) {
        try {
            getITelephony().setVtSetting(mSubId, isEnabled);
            return;
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * @return true if the user's setting for Voice over WiFi is enabled and false if it is not.
     * @see #setVoWiFiSetting(boolean)
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public boolean isVoWiFiSettingEnabled() {
        try {
            return getITelephony().isVoWiFiSettingEnabled(mSubId);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Sets the user's setting for whether or not Voice over WiFi is enabled.
     * @param isEnabled true if the user's setting for Voice over WiFi is enabled, false otherwise=
     * @see #isVoWiFiSettingEnabled()
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public void setVoWiFiSetting(boolean isEnabled) {
        try {
            getITelephony().setVoWiFiSetting(mSubId, isEnabled);
            return;
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * @return true if the user's setting for Voice over WiFi while roaming is enabled, false
     * if disabled.
     * @see #setVoWiFiRoamingSetting(boolean)
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public boolean isVoWiFiRoamingSettingEnabled() {
        try {
            return getITelephony().isVoWiFiRoamingSettingEnabled(mSubId);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Change the user's setting for Voice over WiFi while roaming.
     * @param isEnabled true if the user's setting for Voice over WiFi while roaming is enabled,
     *     false otherwise.
     * @see #isVoWiFiRoamingSettingEnabled()
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public void setVoWiFiRoamingSetting(boolean isEnabled) {
        try {
            getITelephony().setVoWiFiRoamingSetting(mSubId, isEnabled);
            return;
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Overrides the Voice over WiFi capability to true for IMS, but do not persist the setting.
     * Typically used during the Voice over WiFi registration process for some carriers.
     *
     * @param isCapable true if the IMS stack should try to register for IMS over IWLAN, false
     *     otherwise.
     * @param mode the Voice over WiFi mode preference to set, which can be one of the following:
     * - {@link #WIFI_MODE_WIFI_ONLY}
     * - {@link #WIFI_MODE_CELLULAR_PREFERRED}
     * - {@link #WIFI_MODE_WIFI_PREFERRED}
     * @see #setVoWiFiSetting(boolean)
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public void setVoWiFiNonPersistent(boolean isCapable, int mode) {
        try {
            getITelephony().setVoWiFiNonPersistent(mSubId, isCapable, mode);
            return;
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * @return The Voice over WiFi Mode preference set by the user, which can be one of the
     * following:
     * - {@link #WIFI_MODE_WIFI_ONLY}
     * - {@link #WIFI_MODE_CELLULAR_PREFERRED}
     * - {@link #WIFI_MODE_WIFI_PREFERRED}
     * @see #setVoWiFiSetting(boolean)
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public @WiFiCallingMode int getVoWiFiModeSetting() {
        try {
            return getITelephony().getVoWiFiModeSetting(mSubId);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Set the user's preference for Voice over WiFi calling mode.
     * @param mode The user's preference for the technology to register for IMS over, can be one of
     *    the following:
     * - {@link #WIFI_MODE_WIFI_ONLY}
     * - {@link #WIFI_MODE_CELLULAR_PREFERRED}
     * - {@link #WIFI_MODE_WIFI_PREFERRED}
     * @see #getVoWiFiModeSetting()
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public void setVoWiFiModeSetting(@WiFiCallingMode int mode) {
        try {
            getITelephony().setVoWiFiModeSetting(mSubId, mode);
            return;
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Set the user's preference for Voice over WiFi calling mode while the device is roaming on
     * another network.
     *
     * @return The user's preference for the technology to register for IMS over when roaming on
     *     another network, can be one of the following:
     *     - {@link #WIFI_MODE_WIFI_ONLY}
     *     - {@link #WIFI_MODE_CELLULAR_PREFERRED}
     *     - {@link #WIFI_MODE_WIFI_PREFERRED}
     * @see #setVoWiFiRoamingSetting(boolean)
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    @WiFiCallingMode int getVoWiFiRoamingModeSetting() {
        try {
            return getITelephony().getVoWiFiRoamingModeSetting(mSubId);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Set the user's preference for Voice over WiFi mode while the device is roaming on another
     * network.
     *
     * @param mode The user's preference for the technology to register for IMS over when roaming on
     *     another network, can be one of the following:
     *     - {@link #WIFI_MODE_WIFI_ONLY}
     *     - {@link #WIFI_MODE_CELLULAR_PREFERRED}
     *     - {@link #WIFI_MODE_WIFI_PREFERRED}
     * @see #getVoWiFiRoamingModeSetting()
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public void setVoWiFiRoamingModeSetting(@WiFiCallingMode int mode) {
        try {
            getITelephony().setVoWiFiRoamingModeSetting(mSubId, mode);
            return;
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Change the user's setting for RTT capability of this device.
     * @param isEnabled if true RTT will be enabled during calls.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public void setRttCapabilitySetting(boolean isEnabled) {
        try {
            getITelephony().setRttCapabilitySetting(mSubId, isEnabled);
            return;
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * @return true if TTY over VoLTE is supported
     * @see android.telecom.TelecomManager#getCurrentTtyMode
     * @see android.telephony.CarrierConfigManager#KEY_CARRIER_VOLTE_TTY_SUPPORTED_BOOL
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    boolean isTtyOverVolteEnabled() {
        try {
            return getITelephony().isTtyOverVolteEnabled(mSubId);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    private static SubscriptionManager getSubscriptionManager(Context context) {
        SubscriptionManager manager = context.getSystemService(SubscriptionManager.class);
        if (manager == null) {
            throw new RuntimeException("Could not find SubscriptionManager.");
        }
        return manager;
    }

    private static ITelephony getITelephony() {
        ITelephony binder = ITelephony.Stub.asInterface(
                ServiceManager.getService(Context.TELEPHONY_SERVICE));
        if (binder == null) {
            throw new RuntimeException("Could not find Telephony Service.");
        }
        return binder;
    }
}
