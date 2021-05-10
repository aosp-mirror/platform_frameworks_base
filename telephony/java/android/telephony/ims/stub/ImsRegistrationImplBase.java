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
 * limitations under the License
 */

package android.telephony.ims.stub;

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.net.Uri;
import android.os.RemoteException;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.ImsRegistrationAttributes;
import android.telephony.ims.RegistrationManager;
import android.telephony.ims.aidl.IImsRegistration;
import android.telephony.ims.aidl.IImsRegistrationCallback;
import android.util.Log;

import com.android.internal.telephony.util.RemoteCallbackListExt;
import com.android.internal.util.ArrayUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Controls IMS registration for this ImsService and notifies the framework when the IMS
 * registration for this ImsService has changed status.
 * <p>
 * Note: There is no guarantee on the thread that the calls from the framework will be called on. It
 * is the implementors responsibility to handle moving the calls to a working thread if required.
 * @hide
 */
@SystemApi
public class ImsRegistrationImplBase {

    private static final String LOG_TAG = "ImsRegistrationImplBase";

    /**
     * @hide
     */
    // Defines the underlying radio technology type that we have registered for IMS over.
    @IntDef(value = {
                    REGISTRATION_TECH_NONE,
                    REGISTRATION_TECH_LTE,
                    REGISTRATION_TECH_IWLAN,
                    REGISTRATION_TECH_NR
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ImsRegistrationTech {}
    /**
     * No registration technology specified, used when we are not registered.
     */
    public static final int REGISTRATION_TECH_NONE = -1;
    /**
     * This ImsService is registered to IMS via LTE.
     */
    public static final int REGISTRATION_TECH_LTE = 0;
    /**
     * This ImsService is registered to IMS via IWLAN.
     */
    public static final int REGISTRATION_TECH_IWLAN = 1;
    // REGISTRATION_TECH = 2 omitted purposefully.
    /**
     * This ImsService is registered to IMS via NR.
     */
    public static final int REGISTRATION_TECH_NR = 3;

    // Registration states, used to notify new ImsRegistrationImplBase#Callbacks of the current
    // state.
    // The unknown state is set as the initialization state. This is so that we do not call back
    // with NOT_REGISTERED in the case where the ImsService has not updated the registration state
    // yet.
    private static final int REGISTRATION_STATE_UNKNOWN = -1;

    private final IImsRegistration mBinder = new IImsRegistration.Stub() {

        @Override
        public @ImsRegistrationTech int getRegistrationTechnology() throws RemoteException {
            synchronized (mLock) {
                return (mRegistrationAttributes == null) ? REGISTRATION_TECH_NONE
                        : mRegistrationAttributes.getRegistrationTechnology();
            }
        }

        @Override
        public void addRegistrationCallback(IImsRegistrationCallback c) throws RemoteException {
            ImsRegistrationImplBase.this.addRegistrationCallback(c);
        }

        @Override
        public void removeRegistrationCallback(IImsRegistrationCallback c) throws RemoteException {
            ImsRegistrationImplBase.this.removeRegistrationCallback(c);
        }

        @Override
        public void triggerFullNetworkRegistration(int sipCode, String sipReason) {
            ImsRegistrationImplBase.this.triggerFullNetworkRegistration(sipCode, sipReason);
        }

        @Override
        public void triggerUpdateSipDelegateRegistration() {
            ImsRegistrationImplBase.this.updateSipDelegateRegistration();
        }

        @Override
        public void triggerSipDelegateDeregistration() {
            ImsRegistrationImplBase.this.triggerSipDelegateDeregistration();
        }
    };

    private final RemoteCallbackListExt<IImsRegistrationCallback> mCallbacks =
            new RemoteCallbackListExt<>();
    private final Object mLock = new Object();
    // Locked on mLock
    private ImsRegistrationAttributes mRegistrationAttributes;
    // Locked on mLock
    private int mRegistrationState = REGISTRATION_STATE_UNKNOWN;
    // Locked on mLock, create unspecified disconnect cause.
    private ImsReasonInfo mLastDisconnectCause = new ImsReasonInfo();

    // We hold onto the uris each time they change so that we can send it to a callback when its
    // first added.
    private Uri[] mUris = new Uri[0];
    private boolean mUrisSet = false;

    /**
     * @hide
     */
    public final IImsRegistration getBinder() {
        return mBinder;
    }

    private void addRegistrationCallback(IImsRegistrationCallback c) throws RemoteException {
        mCallbacks.register(c);
        updateNewCallbackWithState(c);
    }

    private void removeRegistrationCallback(IImsRegistrationCallback c) {
        mCallbacks.unregister(c);
    }

    /**
     * Called by the framework to request that the ImsService perform the network registration
     * of all SIP delegates associated with this ImsService.
     * <p>
     * If the SIP delegate feature tag configuration has changed, then this method will be
     * called in order to let the ImsService know that it can pick up these changes in the IMS
     * registration.
     */
    public void updateSipDelegateRegistration() {
        // Stub implementation, ImsService should implement this
    }


    /**
     * Called by the framework to request that the ImsService perform the network deregistration of
     * all SIP delegates associated with this ImsService.
     * <p>
     * This is typically called in situations where the user has changed the configuration of the
     * device (for example, the default messaging application) and the framework is reconfiguring
     * the tags associated with each IMS application.
     * <p>
     * This should not affect the registration of features managed by the ImsService itself, such as
     * feature tags related to MMTEL registration.
     */
    public void triggerSipDelegateDeregistration() {
        // Stub implementation, ImsService should implement this
    }

    /**
     * Called by the framework to notify the ImsService that a SIP delegate connection has received
     * a SIP message containing a permanent failure response (such as a 403) or an indication that a
     * SIP response timer has timed out in response to an outgoing SIP message. This method will be
     * called when this condition occurs to trigger the ImsService to tear down the full IMS
     * registration and re-register again.
     *
     * @param sipCode The SIP error code that represents a permanent failure that was received in
     *    response to a request generated by the IMS application. See RFC3261 7.2 for the general
     *    classes of responses available here, however the codes that generate this condition may
     *    be carrier specific.
     * @param sipReason The reason associated with the SIP error code. {@code null} if there was no
     *    reason associated with the error.
     */
    public void triggerFullNetworkRegistration(@IntRange(from = 100, to = 699) int sipCode,
            @Nullable String sipReason) {
        // Stub implementation, ImsService should implement this
    }


    /**
     * Notify the framework that the device is connected to the IMS network.
     *
     * @param imsRadioTech the radio access technology.
     */
    public final void onRegistered(@ImsRegistrationTech int imsRadioTech) {
        onRegistered(new ImsRegistrationAttributes.Builder(imsRadioTech).build());
    }

    /**
     * Notify the framework that the device is connected to the IMS network.
     *
     * @param attributes The attributes associated with the IMS registration.
     */
    public final void onRegistered(@NonNull ImsRegistrationAttributes attributes) {
        updateToState(attributes, RegistrationManager.REGISTRATION_STATE_REGISTERED);
        mCallbacks.broadcastAction((c) -> {
            try {
                c.onRegistered(attributes);
            } catch (RemoteException e) {
                Log.w(LOG_TAG, e + "onRegistered(int, Set) - Skipping callback.");
            }
        });
    }

    /**
     * Notify the framework that the device is trying to connect the IMS network.
     *
     * @param imsRadioTech the radio access technology.
     */
    public final void onRegistering(@ImsRegistrationTech int imsRadioTech) {
        onRegistering(new ImsRegistrationAttributes.Builder(imsRadioTech).build());
    }

    /**
     * Notify the framework that the device is trying to connect the IMS network.
     *
     * @param attributes The attributes associated with the IMS registration.
     */
    public final void onRegistering(@NonNull ImsRegistrationAttributes attributes) {
        updateToState(attributes, RegistrationManager.REGISTRATION_STATE_REGISTERING);
        mCallbacks.broadcastAction((c) -> {
            try {
                c.onRegistering(attributes);
            } catch (RemoteException e) {
                Log.w(LOG_TAG, e + "onRegistering(int, Set) - Skipping callback.");
            }
        });
    }

    /**
     * Notify the framework that the device is disconnected from the IMS network.
     * <p>
     * Note: Prior to calling {@link #onDeregistered(ImsReasonInfo)}, you should ensure that any
     * changes to {@link android.telephony.ims.feature.ImsFeature} capability availability is sent
     * to the framework.  For example,
     * {@link android.telephony.ims.feature.MmTelFeature.MmTelCapabilities#CAPABILITY_TYPE_VIDEO}
     * and
     * {@link android.telephony.ims.feature.MmTelFeature.MmTelCapabilities#CAPABILITY_TYPE_VOICE}
     * may be set to unavailable to ensure the framework knows these services are no longer
     * available due to de-registration.  If you do not report capability changes impacted by
     * de-registration, the framework will not know which features are no longer available as a
     * result.
     *
     * @param info the {@link ImsReasonInfo} associated with why registration was disconnected.
     */
    public final void onDeregistered(ImsReasonInfo info) {
        updateToDisconnectedState(info);
        // ImsReasonInfo should never be null.
        final ImsReasonInfo reasonInfo = (info != null) ? info : new ImsReasonInfo();
        mCallbacks.broadcastAction((c) -> {
            try {
                c.onDeregistered(reasonInfo);
            } catch (RemoteException e) {
                Log.w(LOG_TAG, e + "onDeregistered() - Skipping callback.");
            }
        });
    }

    /**
     * Notify the framework that the handover from the current radio technology to the technology
     * defined in {@code imsRadioTech} has failed.
     * @param imsRadioTech The technology that has failed to be changed. Valid values are
     * {@link #REGISTRATION_TECH_LTE} and {@link #REGISTRATION_TECH_IWLAN}.
     * @param info The {@link ImsReasonInfo} for the failure to change technology.
     */
    public final void onTechnologyChangeFailed(@ImsRegistrationTech int imsRadioTech,
            ImsReasonInfo info) {
        final ImsReasonInfo reasonInfo = (info != null) ? info : new ImsReasonInfo();
        mCallbacks.broadcastAction((c) -> {
            try {
                c.onTechnologyChangeFailed(imsRadioTech, reasonInfo);
            } catch (RemoteException e) {
                Log.w(LOG_TAG, e + "onTechnologyChangeFailed() - Skipping callback.");
            }
        });
    }

    /**
     * Invoked when the {@link Uri}s associated to this device's subscriber have changed.
     * These {@link Uri}s' are filtered out during conference calls.
     *
     * The {@link Uri}s are not guaranteed to be different between subsequent calls.
     * @param uris changed uris
     */
    public final void onSubscriberAssociatedUriChanged(Uri[] uris) {
        synchronized (mLock) {
            mUris = ArrayUtils.cloneOrNull(uris);
            mUrisSet = true;
        }
        mCallbacks.broadcastAction((c) -> onSubscriberAssociatedUriChanged(c, uris));
    }

    private void onSubscriberAssociatedUriChanged(IImsRegistrationCallback callback, Uri[] uris) {
        try {
            callback.onSubscriberAssociatedUriChanged(uris);
        } catch (RemoteException e) {
            Log.w(LOG_TAG, e + "onSubscriberAssociatedUriChanged() - Skipping callback.");
        }
    }

    private void updateToState(ImsRegistrationAttributes attributes, int newState) {
        synchronized (mLock) {
            mRegistrationAttributes = attributes;
            mRegistrationState = newState;
            mLastDisconnectCause = null;
        }
    }

    private void updateToDisconnectedState(ImsReasonInfo info) {
        synchronized (mLock) {
            //We don't want to send this info over if we are disconnected
            mUrisSet = false;
            mUris = null;

            updateToState(new ImsRegistrationAttributes.Builder(REGISTRATION_TECH_NONE).build(),
                    RegistrationManager.REGISTRATION_STATE_NOT_REGISTERED);
            if (info != null) {
                mLastDisconnectCause = info;
            } else {
                Log.w(LOG_TAG, "updateToDisconnectedState: no ImsReasonInfo provided.");
                mLastDisconnectCause = new ImsReasonInfo();
            }
        }
    }

    /**
     * @param c the newly registered callback that will be updated with the current registration
     *         state.
     */
    private void updateNewCallbackWithState(IImsRegistrationCallback c)
            throws RemoteException {
        int state;
        ImsRegistrationAttributes attributes;
        ImsReasonInfo disconnectInfo;
        boolean urisSet;
        Uri[] uris;
        synchronized (mLock) {
            state = mRegistrationState;
            attributes = mRegistrationAttributes;
            disconnectInfo = mLastDisconnectCause;
            urisSet = mUrisSet;
            uris = mUris;
        }
        switch (state) {
            case RegistrationManager.REGISTRATION_STATE_NOT_REGISTERED: {
                c.onDeregistered(disconnectInfo);
                break;
            }
            case RegistrationManager.REGISTRATION_STATE_REGISTERING: {
                c.onRegistering(attributes);
                break;
            }
            case RegistrationManager.REGISTRATION_STATE_REGISTERED: {
                c.onRegistered(attributes);
                break;
            }
            case REGISTRATION_STATE_UNKNOWN: {
                // Do not callback if the state has not been updated yet by the ImsService.
                break;
            }
        }
        if (urisSet) {
            onSubscriberAssociatedUriChanged(c, uris);
        }
    }
}
