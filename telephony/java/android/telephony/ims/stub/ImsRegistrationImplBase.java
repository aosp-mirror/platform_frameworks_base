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
import android.annotation.SystemApi;
import android.net.Uri;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.aidl.IImsRegistration;
import android.telephony.ims.aidl.IImsRegistrationCallback;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Controls IMS registration for this ImsService and notifies the framework when the IMS
 * registration for this ImsService has changed status.
 * @hide
 */
@SystemApi
public class ImsRegistrationImplBase {

    private static final String LOG_TAG = "ImsRegistrationImplBase";

    /**
     * @hide
     */
    // Defines the underlying radio technology type that we have registered for IMS over.
    @IntDef(flag = true,
            value = {
                    REGISTRATION_TECH_NONE,
                    REGISTRATION_TECH_LTE,
                    REGISTRATION_TECH_IWLAN
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ImsRegistrationTech {}
    /**
     * No registration technology specified, used when we are not registered.
     */
    public static final int REGISTRATION_TECH_NONE = -1;
    /**
     * IMS is registered to IMS via LTE.
     */
    public static final int REGISTRATION_TECH_LTE = 0;
    /**
     * IMS is registered to IMS via IWLAN.
     */
    public static final int REGISTRATION_TECH_IWLAN = 1;

    // Registration states, used to notify new ImsRegistrationImplBase#Callbacks of the current
    // state.
    // The unknown state is set as the initialization state. This is so that we do not call back
    // with NOT_REGISTERED in the case where the ImsService has not updated the registration state
    // yet.
    private static final int REGISTRATION_STATE_UNKNOWN = -1;
    private static final int REGISTRATION_STATE_NOT_REGISTERED = 0;
    private static final int REGISTRATION_STATE_REGISTERING = 1;
    private static final int REGISTRATION_STATE_REGISTERED = 2;

    private final IImsRegistration mBinder = new IImsRegistration.Stub() {

        @Override
        public @ImsRegistrationTech int getRegistrationTechnology() throws RemoteException {
            return getConnectionType();
        }

        @Override
        public void addRegistrationCallback(IImsRegistrationCallback c) throws RemoteException {
            ImsRegistrationImplBase.this.addRegistrationCallback(c);
        }

        @Override
        public void removeRegistrationCallback(IImsRegistrationCallback c) throws RemoteException {
            ImsRegistrationImplBase.this.removeRegistrationCallback(c);
        }
    };

    private final RemoteCallbackList<IImsRegistrationCallback> mCallbacks
            = new RemoteCallbackList<>();
    private final Object mLock = new Object();
    // Locked on mLock
    private @ImsRegistrationTech
    int mConnectionType = REGISTRATION_TECH_NONE;
    // Locked on mLock
    private int mRegistrationState = REGISTRATION_STATE_UNKNOWN;
    // Locked on mLock, create unspecified disconnect cause.
    private ImsReasonInfo mLastDisconnectCause = new ImsReasonInfo();

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
     * Notify the framework that the device is connected to the IMS network.
     *
     * @param imsRadioTech the radio access technology. Valid values are defined as
     * {@link #REGISTRATION_TECH_LTE} and {@link #REGISTRATION_TECH_IWLAN}.
     */
    public final void onRegistered(@ImsRegistrationTech int imsRadioTech) {
        updateToState(imsRadioTech, REGISTRATION_STATE_REGISTERED);
        mCallbacks.broadcast((c) -> {
            try {
                c.onRegistered(imsRadioTech);
            } catch (RemoteException e) {
                Log.w(LOG_TAG, e + " " + "onRegistrationConnected() - Skipping " +
                        "callback.");
            }
        });
    }

    /**
     * Notify the framework that the device is trying to connect the IMS network.
     *
     * @param imsRadioTech the radio access technology. Valid values are defined as
     * {@link #REGISTRATION_TECH_LTE} and {@link #REGISTRATION_TECH_IWLAN}.
     */
    public final void onRegistering(@ImsRegistrationTech int imsRadioTech) {
        updateToState(imsRadioTech, REGISTRATION_STATE_REGISTERING);
        mCallbacks.broadcast((c) -> {
            try {
                c.onRegistering(imsRadioTech);
            } catch (RemoteException e) {
                Log.w(LOG_TAG, e + " " + "onRegistrationProcessing() - Skipping " +
                        "callback.");
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
        mCallbacks.broadcast((c) -> {
            try {
                c.onDeregistered(info);
            } catch (RemoteException e) {
                Log.w(LOG_TAG, e + " " + "onRegistrationDisconnected() - Skipping " +
                        "callback.");
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
        mCallbacks.broadcast((c) -> {
            try {
                c.onTechnologyChangeFailed(imsRadioTech, info);
            } catch (RemoteException e) {
                Log.w(LOG_TAG, e + " " + "onRegistrationChangeFailed() - Skipping " +
                        "callback.");
            }
        });
    }

    /**
     * The this device's subscriber associated {@link Uri}s have changed, which are used to filter
     * out this device's {@link Uri}s during conference calling.
     * @param uris
     */
    public final void onSubscriberAssociatedUriChanged(Uri[] uris) {
        mCallbacks.broadcast((c) -> {
            try {
                c.onSubscriberAssociatedUriChanged(uris);
            } catch (RemoteException e) {
                Log.w(LOG_TAG, e + " " + "onSubscriberAssociatedUriChanged() - Skipping " +
                        "callback.");
            }
        });
    }

    /**
     * @hide
     */
    public void updateToState(@ImsRegistrationTech int connType, int newState) {
        synchronized (mLock) {
            mConnectionType = connType;
            mRegistrationState = newState;
            mLastDisconnectCause = null;
        }
    }

    private void updateToDisconnectedState(ImsReasonInfo info) {
        synchronized (mLock) {
            updateToState(REGISTRATION_TECH_NONE, REGISTRATION_STATE_NOT_REGISTERED);
            if (info != null) {
                mLastDisconnectCause = info;
            } else {
                Log.w(LOG_TAG, "updateToDisconnectedState: no ImsReasonInfo provided.");
                mLastDisconnectCause = new ImsReasonInfo();
            }
        }
    }

    /**
     * @return the current registration connection type. Valid values are
     * {@link #REGISTRATION_TECH_LTE} and {@link #REGISTRATION_TECH_IWLAN}
     * @hide
     */
    @VisibleForTesting
    public final @ImsRegistrationTech int getConnectionType() {
        synchronized (mLock) {
            return mConnectionType;
        }
    }

    /**
     * @param c the newly registered callback that will be updated with the current registration
     *         state.
     */
    private void updateNewCallbackWithState(IImsRegistrationCallback c) throws RemoteException {
        int state;
        ImsReasonInfo disconnectInfo;
        synchronized (mLock) {
            state = mRegistrationState;
            disconnectInfo = mLastDisconnectCause;
        }
        switch (state) {
            case REGISTRATION_STATE_NOT_REGISTERED: {
                c.onDeregistered(disconnectInfo);
                break;
            }
            case REGISTRATION_STATE_REGISTERING: {
                c.onRegistering(getConnectionType());
                break;
            }
            case REGISTRATION_STATE_REGISTERED: {
                c.onRegistered(getConnectionType());
                break;
            }
            case REGISTRATION_STATE_UNKNOWN: {
                // Do not callback if the state has not been updated yet by the ImsService.
                break;
            }
        }
    }
}
