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
 * limitations under the License
 */

package android.telephony.ims.feature;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.content.Context;
import android.os.IInterface;
import android.os.RemoteException;
import android.telephony.SubscriptionManager;
import android.telephony.ims.aidl.IImsCapabilityCallback;
import android.telephony.ims.stub.ImsRegistrationImplBase;
import android.util.Log;

import com.android.ims.internal.IImsFeatureStatusCallback;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.util.RemoteCallbackListExt;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HashMap;
import java.util.Map;

/**
 * Base class for all IMS features that are supported by the framework. Use a concrete subclass
 * of {@link ImsFeature}, such as {@link MmTelFeature} or {@link RcsFeature}.
 *
 * @hide
 */
@SystemApi
public abstract class ImsFeature {

    private static final String LOG_TAG = "ImsFeature";

    /**
     * Invalid feature value
     * @hide
     */
    public static final int FEATURE_INVALID = -1;
    // ImsFeatures that are defined in the Manifests. Ensure that these values match the previously
    // defined values in ImsServiceClass for compatibility purposes.
    /**
     * This feature supports emergency calling over MMTEL. If defined, the framework will try to
     * place an emergency call over IMS first. If it is not defined, the framework will only use
     * CSFB for emergency calling.
     * @hide
     */
    @SystemApi
    public static final int FEATURE_EMERGENCY_MMTEL = 0;
    /**
     * This feature supports the MMTEL feature.
     * @hide
     */
    @SystemApi
    public static final int FEATURE_MMTEL = 1;
    /**
     * This feature supports the RCS feature.
     * @hide
     */
    @SystemApi
    public static final int FEATURE_RCS = 2;
    /**
     * Total number of features defined
     * @hide
     */
    public static final int FEATURE_MAX = 3;

    /**
     * Used for logging purposes.
     * @hide
     */
    public static final Map<Integer, String> FEATURE_LOG_MAP = new HashMap<Integer, String>() {{
            put(FEATURE_EMERGENCY_MMTEL, "EMERGENCY_MMTEL");
            put(FEATURE_MMTEL, "MMTEL");
            put(FEATURE_RCS, "RCS");
        }};

    /**
     * Integer values defining IMS features that are supported in ImsFeature.
     * @hide
     */
    @IntDef(flag = true,
            value = {
                    FEATURE_EMERGENCY_MMTEL,
                    FEATURE_MMTEL,
                    FEATURE_RCS
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface FeatureType {}

    /**
     * Integer values defining the state of the ImsFeature at any time.
     * @hide
     */
    @IntDef(flag = true,
            value = {
                    STATE_UNAVAILABLE,
                    STATE_INITIALIZING,
                    STATE_READY,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ImsState {}

    /**
     * This {@link ImsFeature}'s state is unavailable and should not be communicated with. This will
     * remove all bindings back to the framework. Any attempt to communicate with the framework
     * during this time will result in an {@link IllegalStateException}.
     * @hide
     */
    @SystemApi
    public static final int STATE_UNAVAILABLE = 0;
    /**
     * This {@link ImsFeature} state is initializing and should not be communicated with. This will
     * remove all bindings back to the framework. Any attempt to communicate with the framework
     * during this time will result in an {@link IllegalStateException}.
     * @hide
     */
    @SystemApi
    public static final int STATE_INITIALIZING = 1;
    /**
     * This {@link ImsFeature} is ready for communication. Do not attempt to call framework methods
     * until {@see #onFeatureReady()} is called.
     * @hide
     */
    @SystemApi
    public static final int STATE_READY = 2;

    /**
     * Used for logging purposes.
     * @hide
     */
    public static final Map<Integer, String> STATE_LOG_MAP = new HashMap<Integer, String>() {{
            put(STATE_UNAVAILABLE, "UNAVAILABLE");
            put(STATE_INITIALIZING, "INITIALIZING");
            put(STATE_READY, "READY");
        }};

    /**
     * Integer values defining the result codes that should be returned from
     * {@link #changeEnabledCapabilities} when the framework tries to set a feature's capability.
     * @hide
     */
    @IntDef(flag = true,
            value = {
                    CAPABILITY_ERROR_GENERIC,
                    CAPABILITY_SUCCESS
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ImsCapabilityError {}

    /**
     * The capability was unable to be changed.
     * @hide
     */
    @SystemApi
    public static final int CAPABILITY_ERROR_GENERIC = -1;
    /**
     * The capability was able to be changed.
     * @hide
     */
    @SystemApi
    public static final int CAPABILITY_SUCCESS = 0;

    /**
     * Used by the ImsFeature to call back to the CapabilityCallback that the framework has
     * provided.
     */
    protected static class CapabilityCallbackProxy {
        private final IImsCapabilityCallback mCallback;

        /** @hide */
        public CapabilityCallbackProxy(IImsCapabilityCallback c) {
            mCallback = c;
        }

        /**
         * This method notifies the provided framework callback that the request to change the
         * indicated capability has failed and has not changed.
         *
         * @param capability The Capability that will be notified to the framework, defined as
         * {@link MmTelFeature.MmTelCapabilities#CAPABILITY_TYPE_VOICE},
         * {@link MmTelFeature.MmTelCapabilities#CAPABILITY_TYPE_VIDEO},
         * {@link MmTelFeature.MmTelCapabilities#CAPABILITY_TYPE_UT}, or
         * {@link MmTelFeature.MmTelCapabilities#CAPABILITY_TYPE_SMS}.
         * @param radioTech The radio tech that this capability failed for, defined as
         * {@link ImsRegistrationImplBase#REGISTRATION_TECH_LTE},
         * {@link ImsRegistrationImplBase#REGISTRATION_TECH_IWLAN} or
         * {@link ImsRegistrationImplBase#REGISTRATION_TECH_CROSS_SIM}.
         * @param reason The reason this capability was unable to be changed, defined as
         * {@link #CAPABILITY_ERROR_GENERIC} or {@link #CAPABILITY_SUCCESS}.
         */
        public void onChangeCapabilityConfigurationError(int capability, int radioTech,
                @ImsCapabilityError int reason) {
            if (mCallback == null) {
                return;
            }
            try {
                mCallback.onChangeCapabilityConfigurationError(capability, radioTech, reason);
            } catch (RemoteException e) {
                Log.e(LOG_TAG, "onChangeCapabilityConfigurationError called on dead binder.");
            }
        }
    }

    /**
     * Contains the IMS capabilities defined and supported by an ImsFeature in the form of a
     * bit-mask.
     *
     * @deprecated This class is not used directly, but rather extended in subclasses of
     * {@link ImsFeature} to provide service specific capabilities.
     * @see MmTelFeature.MmTelCapabilities
     * @hide
     */
    // Not Actually deprecated, but we need to remove it from the @SystemApi surface.
    @Deprecated
    @SystemApi // SystemApi only because it was leaked through type usage in a previous release.
    @TestApi
    public static class Capabilities {
        /** @deprecated Use getters and accessors instead. */
        // Not actually deprecated, but we need to remove it from the @SystemApi surface eventually.
        protected int mCapabilities = 0;

        /**
         * @hide
         */
        public Capabilities() {
        }

        /**
         * @hide
         */
        protected Capabilities(int capabilities) {
            mCapabilities = capabilities;
        }

        /**
         * @param capabilities Capabilities to be added to the configuration in the form of a
         *     bit mask.
         * @hide
         */
        public void addCapabilities(int capabilities) {
            mCapabilities |= capabilities;
        }

        /**
         * @param capabilities Capabilities to be removed to the configuration in the form of a
         *     bit mask.
         * @hide
         */
        public void removeCapabilities(int capabilities) {
            mCapabilities &= ~capabilities;
        }

        /**
         * @return true if all of the capabilities specified are capable.
         * @hide
         */
        public boolean isCapable(int capabilities) {
            return (mCapabilities & capabilities) == capabilities;
        }

        /**
         * @return a deep copy of the Capabilites.
         * @hide
         */
        public Capabilities copy() {
            return new Capabilities(mCapabilities);
        }

        /**
         * @return a bitmask containing the capability flags directly.
         * @hide
         */
        public int getMask() {
            return mCapabilities;
        }

        /**
         * @hide
         */
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Capabilities)) return false;

            Capabilities that = (Capabilities) o;

            return mCapabilities == that.mCapabilities;
        }

        /**
         * @hide
         */
        @Override
        public int hashCode() {
            return mCapabilities;
        }

        /**
         * @hide
         */
        @Override
        public String toString() {
            return "Capabilities: " + Integer.toBinaryString(mCapabilities);
        }
    }

    /** @hide */
    protected Context mContext;
    /** @hide */
    protected final Object mLock = new Object();

    private final RemoteCallbackListExt<IImsFeatureStatusCallback> mStatusCallbacks =
            new RemoteCallbackListExt<>();
    private @ImsState int mState = STATE_UNAVAILABLE;
    private int mSlotId = SubscriptionManager.INVALID_SIM_SLOT_INDEX;
    private final RemoteCallbackListExt<IImsCapabilityCallback> mCapabilityCallbacks =
            new RemoteCallbackListExt<>();
    private Capabilities mCapabilityStatus = new Capabilities();

    /**
     * @hide
     */
    public void initialize(Context context, int slotId) {
        mContext = context;
        mSlotId = slotId;
    }

    /**
     * @return The SIM slot index associated with this ImsFeature.
     *
     * @see SubscriptionManager#getSubscriptionIds(int) for more information on getting the
     * subscription IDs associated with this slot.
     * @hide
     */
    @SystemApi
    public final int getSlotIndex() {
        return mSlotId;
    }

    /**
     * @return The current state of the ImsFeature, set previously by {@link #setFeatureState(int)}
     * or {@link #STATE_UNAVAILABLE} if it has not been updated  yet.
     * @hide
     */
    @SystemApi
    public @ImsState int getFeatureState() {
        synchronized (mLock) {
            return mState;
        }
    }

    /**
     * Set the state of the ImsFeature. The state is used as a signal to the framework to start or
     * stop communication, depending on the state sent.
     * @param state The ImsFeature's state, defined as {@link #STATE_UNAVAILABLE},
     * {@link #STATE_INITIALIZING}, or {@link #STATE_READY}.
     * @hide
     */
    @SystemApi
    public final void setFeatureState(@ImsState int state) {
        boolean isNotify = false;
        synchronized (mLock) {
            if (mState != state) {
                mState = state;
                isNotify = true;
            }
        }
        if (isNotify) {
            notifyFeatureState(state);
        }
    }

    /**
     * Not final for testing, but shouldn't be extended!
     * @hide
     */
    @VisibleForTesting
    public void addImsFeatureStatusCallback(@NonNull IImsFeatureStatusCallback c) {
        try {
            synchronized (mStatusCallbacks) {
                // Add the callback if the callback completes successfully without a RemoteException
                mStatusCallbacks.register(c);
                // If we have just connected, send queued status.
                c.notifyImsFeatureStatus(getFeatureState());
            }
        } catch (RemoteException e) {
            Log.w(LOG_TAG, "Couldn't notify feature state: " + e.getMessage());
        }
    }

    /**
     * Not final for testing, but shouldn't be extended!
     * @hide
     */
    @VisibleForTesting
    public void removeImsFeatureStatusCallback(@NonNull IImsFeatureStatusCallback c) {
        synchronized (mStatusCallbacks) {
            mStatusCallbacks.unregister(c);
        }
    }

    /**
     * Internal method called by ImsFeature when setFeatureState has changed.
     */
    private void notifyFeatureState(@ImsState int state) {
        synchronized (mStatusCallbacks) {
            mStatusCallbacks.broadcastAction((c) -> {
                try {
                    c.notifyImsFeatureStatus(state);
                } catch (RemoteException e) {
                    Log.w(LOG_TAG, e + " notifyFeatureState() - Skipping "
                            + "callback.");
                }
            });
        }
    }

    /**
     * @hide
     */
    public final void addCapabilityCallback(IImsCapabilityCallback c) {
        mCapabilityCallbacks.register(c);
        try {
            // Notify the Capability callback that was just registered of the current capabilities.
            c.onCapabilitiesStatusChanged(queryCapabilityStatus().mCapabilities);
        } catch (RemoteException e) {
            Log.w(LOG_TAG, "addCapabilityCallback: error accessing callback: " + e.getMessage());
        }
    }

    /**
     * @hide
     */
    final void removeCapabilityCallback(IImsCapabilityCallback c) {
        mCapabilityCallbacks.unregister(c);
    }

    /**@hide*/
    final void queryCapabilityConfigurationInternal(int capability, int radioTech,
            IImsCapabilityCallback c) {
        boolean enabled = queryCapabilityConfiguration(capability, radioTech);
        try {
            if (c != null) {
                c.onQueryCapabilityConfiguration(capability, radioTech, enabled);
            }
        } catch (RemoteException e) {
            Log.e(LOG_TAG, "queryCapabilityConfigurationInternal called on dead binder!");
        }
    }

    /**
     * @return the cached capabilities status for this feature.
     * @hide
     */
    @VisibleForTesting
    public Capabilities queryCapabilityStatus() {
        synchronized (mLock) {
            return mCapabilityStatus.copy();
        }
    }

    /**
     * Called internally to request the change of enabled capabilities.
     * @hide
     */
    @VisibleForTesting
    public final void requestChangeEnabledCapabilities(CapabilityChangeRequest request,
            IImsCapabilityCallback c) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "ImsFeature#requestChangeEnabledCapabilities called with invalid params.");
        }
        changeEnabledCapabilities(request, new CapabilityCallbackProxy(c));
    }

    /**
     * Called by the ImsFeature when the capabilities status has changed.
     *
     * @param caps the new {@link Capabilities} status of the {@link ImsFeature}.
     *
     * @hide
     */
    protected final void notifyCapabilitiesStatusChanged(Capabilities caps) {
        synchronized (mLock) {
            mCapabilityStatus = caps.copy();
        }

        synchronized (mCapabilityCallbacks) {
            mCapabilityCallbacks.broadcastAction((callback) -> {
                try {
                    Log.d(LOG_TAG, "ImsFeature notifyCapabilitiesStatusChanged Capabilities = "
                            + caps.mCapabilities);
                    callback.onCapabilitiesStatusChanged(caps.mCapabilities);
                } catch (RemoteException e) {
                    Log.w(LOG_TAG, e + " notifyCapabilitiesStatusChanged() - Skipping "
                            + "callback.");
                }
            });
        }
    }

    /**
     * Provides the ImsFeature with the ability to return the framework Capability Configuration
     * for a provided Capability. If the framework calls {@link #changeEnabledCapabilities} and
     * includes a capability A to enable or disable, this method should return the correct enabled
     * status for capability A.
     * @param capability The capability that we are querying the configuration for.
     * @return true if the capability is enabled, false otherwise.
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    public abstract boolean queryCapabilityConfiguration(int capability, int radioTech);

    /**
     * Features should override this method to receive Capability preference change requests from
     * the framework using the provided {@link CapabilityChangeRequest}. If any of the capabilities
     * in the {@link CapabilityChangeRequest} are not able to be completed due to an error,
     * {@link CapabilityCallbackProxy#onChangeCapabilityConfigurationError} should be called for
     * each failed capability.
     *
     * @param request A {@link CapabilityChangeRequest} containing requested capabilities to
     *     enable/disable.
     * @param c A {@link CapabilityCallbackProxy}, which will be used to call back to the framework
     * setting a subset of these capabilities fail, using
     * {@link CapabilityCallbackProxy#onChangeCapabilityConfigurationError}.
     */
    public abstract void changeEnabledCapabilities(CapabilityChangeRequest request,
            CapabilityCallbackProxy c);

    /**
     * Called when the framework is removing this feature and it needs to be cleaned up.
     */
    public abstract void onFeatureRemoved();

    /**
     * Called after this ImsFeature has been initialized and has been set to the
     * {@link ImsState#STATE_READY} state.
     * <p>
     * Any attempt by this feature to access the framework before this method is called will return
     * with an {@link IllegalStateException}.
     * The IMS provider should use this method to trigger registration for this feature on the IMS
     * network, if needed.
     */
    public abstract void onFeatureReady();

    /**
     * @return Binder instance that the framework will use to communicate with this feature.
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    protected abstract IInterface getBinder();
}
