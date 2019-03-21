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
import android.content.Context;
import android.content.Intent;
import android.os.IInterface;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.telephony.SubscriptionManager;
import android.telephony.ims.aidl.IImsCapabilityCallback;
import android.telephony.ims.stub.ImsRegistrationImplBase;
import android.util.Log;

import com.android.ims.internal.IImsFeatureStatusCallback;
import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import java.util.WeakHashMap;

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
     * Action to broadcast when ImsService is up.
     * Internal use only.
     * Only defined here separately for compatibility purposes with the old ImsService.
     *
     * @hide
     */
    public static final String ACTION_IMS_SERVICE_UP =
            "com.android.ims.IMS_SERVICE_UP";

    /**
     * Action to broadcast when ImsService is down.
     * Internal use only.
     * Only defined here separately for compatibility purposes with the old ImsService.
     *
     * @hide
     */
    public static final String ACTION_IMS_SERVICE_DOWN =
            "com.android.ims.IMS_SERVICE_DOWN";

    /**
     * Part of the ACTION_IMS_SERVICE_UP or _DOWN intents.
     * A long value; the phone ID corresponding to the IMS service coming up or down.
     * Only defined here separately for compatibility purposes with the old ImsService.
     *
     * @hide
     */
    public static final String EXTRA_PHONE_ID = "android:phone_id";

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
     */
    public static final int FEATURE_EMERGENCY_MMTEL = 0;
    /**
     * This feature supports the MMTEL feature.
     */
    public static final int FEATURE_MMTEL = 1;
    /**
     * This feature supports the RCS feature.
     */
    public static final int FEATURE_RCS = 2;
    /**
     * Total number of features defined
     * @hide
     */
    public static final int FEATURE_MAX = 3;

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
     * This {@link ImsFeature}'s state is unavailable and should not be communicated with.
     */
    public static final int STATE_UNAVAILABLE = 0;
    /**
     * This {@link ImsFeature} state is initializing and should not be communicated with.
     */
    public static final int STATE_INITIALIZING = 1;
    /**
     * This {@link ImsFeature} is ready for communication.
     */
    public static final int STATE_READY = 2;

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
     */
    public static final int CAPABILITY_ERROR_GENERIC = -1;
    /**
     * The capability was able to be changed.
     */
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
         * {@link ImsRegistrationImplBase#REGISTRATION_TECH_LTE} or
         * {@link ImsRegistrationImplBase#REGISTRATION_TECH_IWLAN}.
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
     * Contains the capabilities defined and supported by an ImsFeature in the form of a bit mask.
     * @hide
     * @deprecated
     */
    @SystemApi  // SystemApi only because it was leaked through type usage in a previous release.
    public static class Capabilities {
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

    private final Set<IImsFeatureStatusCallback> mStatusCallbacks = Collections.newSetFromMap(
            new WeakHashMap<IImsFeatureStatusCallback, Boolean>());
    private @ImsState int mState = STATE_UNAVAILABLE;
    private int mSlotId = SubscriptionManager.INVALID_SIM_SLOT_INDEX;
    private final RemoteCallbackList<IImsCapabilityCallback> mCapabilityCallbacks
            = new RemoteCallbackList<>();
    private Capabilities mCapabilityStatus = new Capabilities();

    /**
     * @hide
     */
    public final void initialize(Context context, int slotId) {
        mContext = context;
        mSlotId = slotId;
    }

    /**
     * @return The current state of the feature, defined as {@link #STATE_UNAVAILABLE},
     * {@link #STATE_INITIALIZING}, or {@link #STATE_READY}.
     * @hide
     */
    public int getFeatureState() {
        synchronized (mLock) {
            return mState;
        }
    }

    /**
     * Set the state of the ImsFeature. The state is used as a signal to the framework to start or
     * stop communication, depending on the state sent.
     * @param state The ImsFeature's state, defined as {@link #STATE_UNAVAILABLE},
     * {@link #STATE_INITIALIZING}, or {@link #STATE_READY}.
     */
    public final void setFeatureState(@ImsState int state) {
        synchronized (mLock) {
            if (mState != state) {
                mState = state;
                notifyFeatureState(state);
            }
        }
    }

    /**
     * Not final for testing, but shouldn't be extended!
     * @hide
     */
    @VisibleForTesting
    public void addImsFeatureStatusCallback(@NonNull IImsFeatureStatusCallback c) {
        try {
            // If we have just connected, send queued status.
            c.notifyImsFeatureStatus(getFeatureState());
            // Add the callback if the callback completes successfully without a RemoteException.
            synchronized (mLock) {
                mStatusCallbacks.add(c);
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
        synchronized (mLock) {
            mStatusCallbacks.remove(c);
        }
    }

    /**
     * Internal method called by ImsFeature when setFeatureState has changed.
     */
    private void notifyFeatureState(@ImsState int state) {
        synchronized (mLock) {
            for (Iterator<IImsFeatureStatusCallback> iter = mStatusCallbacks.iterator();
                    iter.hasNext(); ) {
                IImsFeatureStatusCallback callback = iter.next();
                try {
                    Log.i(LOG_TAG, "notifying ImsFeatureState=" + state);
                    callback.notifyImsFeatureStatus(state);
                } catch (RemoteException e) {
                    // remove if the callback is no longer alive.
                    iter.remove();
                    Log.w(LOG_TAG, "Couldn't notify feature state: " + e.getMessage());
                }
            }
        }
        sendImsServiceIntent(state);
    }

    /**
     * Provide backwards compatibility using deprecated service UP/DOWN intents.
     */
    private void sendImsServiceIntent(@ImsState int state) {
        if (mContext == null || mSlotId == SubscriptionManager.INVALID_SIM_SLOT_INDEX) {
            return;
        }
        Intent intent;
        switch (state) {
            case ImsFeature.STATE_UNAVAILABLE:
            case ImsFeature.STATE_INITIALIZING:
                intent = new Intent(ACTION_IMS_SERVICE_DOWN);
                break;
            case ImsFeature.STATE_READY:
                intent = new Intent(ACTION_IMS_SERVICE_UP);
                break;
            default:
                intent = new Intent(ACTION_IMS_SERVICE_DOWN);
        }
        intent.putExtra(EXTRA_PHONE_ID, mSlotId);
        mContext.sendBroadcast(intent);
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
    public final void removeCapabilityCallback(IImsCapabilityCallback c) {
        mCapabilityCallbacks.unregister(c);
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
     * @param c A {@link Capabilities} containing the new Capabilities status.
     *
     * @hide
     */
    protected final void notifyCapabilitiesStatusChanged(Capabilities c) {
        synchronized (mLock) {
            mCapabilityStatus = c.copy();
        }
        int count = mCapabilityCallbacks.beginBroadcast();
        try {
            for (int i = 0; i < count; i++) {
                try {
                    mCapabilityCallbacks.getBroadcastItem(i).onCapabilitiesStatusChanged(
                            c.mCapabilities);
                } catch (RemoteException e) {
                    Log.w(LOG_TAG, e + " " + "notifyCapabilitiesStatusChanged() - Skipping " +
                            "callback.");
                }
            }
        } finally {
            mCapabilityCallbacks.finishBroadcast();
        }
    }

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
     * Called when the feature has been initialized and communication with the framework is set up.
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
    protected abstract IInterface getBinder();
}
