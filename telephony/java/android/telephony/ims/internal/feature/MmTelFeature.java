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

package android.telephony.ims.internal.feature;

import android.annotation.IntDef;
import android.os.Message;
import android.os.RemoteException;
import android.telecom.TelecomManager;
import android.telephony.ims.internal.aidl.IImsCapabilityCallback;
import android.telephony.ims.internal.aidl.IImsMmTelFeature;
import android.telephony.ims.internal.aidl.IImsMmTelListener;
import android.telephony.ims.internal.stub.SmsImplBase;
import android.telephony.ims.stub.ImsRegistrationImplBase;
import android.telephony.ims.stub.ImsEcbmImplBase;
import android.telephony.ims.stub.ImsMultiEndpointImplBase;
import android.telephony.ims.stub.ImsUtImplBase;
import android.util.Log;

import com.android.ims.ImsCallProfile;
import com.android.ims.internal.IImsCallSession;
import com.android.ims.internal.IImsEcbm;
import com.android.ims.internal.IImsMultiEndpoint;
import com.android.ims.internal.IImsSmsListener;
import com.android.ims.internal.IImsUt;
import com.android.ims.internal.ImsCallSession;
import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Base implementation for Voice and SMS (IR-92) and Video (IR-94) IMS support.
 *
 * Any class wishing to use MmTelFeature should extend this class and implement all methods that the
 * service supports.
 * @hide
 */

public class MmTelFeature extends ImsFeature {

    private static final String LOG_TAG = "MmTelFeature";

    private final IImsMmTelFeature mImsMMTelBinder = new IImsMmTelFeature.Stub() {

        @Override
        public void setListener(IImsMmTelListener l) throws RemoteException {
            synchronized (mLock) {
                MmTelFeature.this.setListener(l);
            }
        }

        @Override
        public int getFeatureState() throws RemoteException {
            synchronized (mLock) {
                return MmTelFeature.this.getFeatureState();
            }
        }


        @Override
        public ImsCallProfile createCallProfile(int callSessionType, int callType)
                throws RemoteException {
            synchronized (mLock) {
                return MmTelFeature.this.createCallProfile(callSessionType,  callType);
            }
        }

        @Override
        public IImsCallSession createCallSession(ImsCallProfile profile) throws RemoteException {
            synchronized (mLock) {
                ImsCallSession s = MmTelFeature.this.createCallSession(profile);
                return s != null ? s.getSession() : null;
            }
        }

        @Override
        public IImsUt getUtInterface() throws RemoteException {
            synchronized (mLock) {
                return MmTelFeature.this.getUt();
            }
        }

        @Override
        public IImsEcbm getEcbmInterface() throws RemoteException {
            synchronized (mLock) {
                return MmTelFeature.this.getEcbm();
            }
        }

        @Override
        public void setUiTtyMode(int uiTtyMode, Message onCompleteMessage) throws RemoteException {
            synchronized (mLock) {
                MmTelFeature.this.setUiTtyMode(uiTtyMode, onCompleteMessage);
            }
        }

        @Override
        public IImsMultiEndpoint getMultiEndpointInterface() throws RemoteException {
            synchronized (mLock) {
                return MmTelFeature.this.getMultiEndpoint();
            }
        }

        @Override
        public int queryCapabilityStatus() throws RemoteException {
            return MmTelFeature.this.queryCapabilityStatus().mCapabilities;
        }

        @Override
        public void addCapabilityCallback(IImsCapabilityCallback c) {
            MmTelFeature.this.addCapabilityCallback(c);
        }

        @Override
        public void removeCapabilityCallback(IImsCapabilityCallback c) {
            MmTelFeature.this.removeCapabilityCallback(c);
        }

        @Override
        public void changeCapabilitiesConfiguration(CapabilityChangeRequest request,
                IImsCapabilityCallback c) throws RemoteException {
            MmTelFeature.this.requestChangeEnabledCapabilities(request, c);
        }

        @Override
        public void queryCapabilityConfiguration(int capability, int radioTech,
                IImsCapabilityCallback c) {
            queryCapabilityConfigurationInternal(capability, radioTech, c);
        }

        @Override
        public void setSmsListener(IImsSmsListener l) throws RemoteException {
            MmTelFeature.this.setSmsListener(l);
        }

        @Override
        public void sendSms(int token, int messageRef, String format, String smsc, boolean retry,
                byte[] pdu) {
            synchronized (mLock) {
                MmTelFeature.this.sendSms(token, messageRef, format, smsc, retry, pdu);
            }
        }

        @Override
        public void acknowledgeSms(int token, int messageRef, int result) {
            synchronized (mLock) {
                MmTelFeature.this.acknowledgeSms(token, messageRef, result);
            }
        }

        @Override
        public void acknowledgeSmsReport(int token, int messageRef, int result) {
            synchronized (mLock) {
                MmTelFeature.this.acknowledgeSmsReport(token, messageRef, result);
            }
        }

        @Override
        public String getSmsFormat() {
            synchronized (mLock) {
                return MmTelFeature.this.getSmsFormat();
            }
        }
    };

    /**
     * Contains the capabilities defined and supported by a MmTelFeature in the form of a Bitmask.
     * The capabilities that are used in MmTelFeature are defined by {@link MmTelCapability}.
     *
     * The capabilities of this MmTelFeature will be set by the framework and can be queried with
     * {@link #queryCapabilityStatus()}.
     *
     * This MmTelFeature can then return the status of each of these capabilities (enabled or not)
     * by sending a {@link #notifyCapabilitiesStatusChanged} callback to the framework. The current
     * status can also be queried using {@link #queryCapabilityStatus()}.
     */
    public static class MmTelCapabilities extends Capabilities {

        @VisibleForTesting
        public MmTelCapabilities() {
            super();
        }

        public MmTelCapabilities(Capabilities c) {
            mCapabilities = c.mCapabilities;
        }

        @IntDef(flag = true,
                value = {
                        CAPABILITY_TYPE_VOICE,
                        CAPABILITY_TYPE_VIDEO,
                        CAPABILITY_TYPE_UT,
                        CAPABILITY_TYPE_SMS
                })
        @Retention(RetentionPolicy.SOURCE)
        public @interface MmTelCapability {}

        /**
         * This MmTelFeature supports Voice calling (IR.92)
         */
        public static final int CAPABILITY_TYPE_VOICE = 1 << 0;

        /**
         * This MmTelFeature supports Video (IR.94)
         */
        public static final int CAPABILITY_TYPE_VIDEO = 1 << 1;

        /**
         * This MmTelFeature supports XCAP over Ut for supplementary services. (IR.92)
         */
        public static final int CAPABILITY_TYPE_UT = 1 << 2;

        /**
         * This MmTelFeature supports SMS (IR.92)
         */
        public static final int CAPABILITY_TYPE_SMS = 1 << 3;

        @Override
        public final void addCapabilities(@MmTelCapability int capabilities) {
            super.addCapabilities(capabilities);
        }

        @Override
        public final void removeCapabilities(@MmTelCapability int capability) {
            super.removeCapabilities(capability);
        }

        @Override
        public final boolean isCapable(@MmTelCapability int capabilities) {
            return super.isCapable(capabilities);
        }
    }

    /**
     * Listener that the framework implements for communication from the MmTelFeature.
     */
    public static class Listener extends IImsMmTelListener.Stub {

        @Override
        public final void onIncomingCall(IImsCallSession c) {
            onIncomingCall(new ImsCallSession(c));
        }

        /**
         * Updates the Listener when the voice message count for IMS has changed.
         * @param count an integer representing the new message count.
         */
        @Override
        public void onVoiceMessageCountUpdate(int count) {

        }

        /**
         * Called when the IMS provider receives an incoming call.
         * @param c The {@link ImsCallSession} associated with the new call.
         */
        public void onIncomingCall(ImsCallSession c) {
        }
    }

    // Lock for feature synchronization
    private final Object mLock = new Object();
    private IImsMmTelListener mListener;

    /**
     * @param listener A {@link Listener} used when the MmTelFeature receives an incoming call and
     *     notifies the framework.
     */
    private void setListener(IImsMmTelListener listener) {
        synchronized (mLock) {
            mListener = listener;
        }
    }

    private void queryCapabilityConfigurationInternal(int capability, int radioTech,
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
     * The current capability status that this MmTelFeature has defined is available. This
     * configuration will be used by the platform to figure out which capabilities are CURRENTLY
     * available to be used.
     *
     * Should be a subset of the capabilities that are enabled by the framework in
     * {@link #changeEnabledCapabilities}.
     * @return A copy of the current MmTelFeature capability status.
     */
    @Override
    public final MmTelCapabilities queryCapabilityStatus() {
        return new MmTelCapabilities(super.queryCapabilityStatus());
    }

    /**
     * Notify the framework that the status of the Capabilities has changed. Even though the
     * MmTelFeature capability may be enabled by the framework, the status may be disabled due to
     * the feature being unavailable from the network.
     * @param c The current capability status of the MmTelFeature. If a capability is disabled, then
     * the status of that capability is disabled. This can happen if the network does not currently
     * support the capability that is enabled. A capability that is disabled by the framework (via
     * {@link #changeEnabledCapabilities}) should also show the status as disabled.
     */
    protected final void notifyCapabilitiesStatusChanged(MmTelCapabilities c) {
        super.notifyCapabilitiesStatusChanged(c);
    }

    /**
     * Notify the framework of an incoming call.
     * @param c The {@link ImsCallSession} of the new incoming call.
     *
     * @throws RemoteException if the connection to the framework is not available. If this happens,
     *     the call should be no longer considered active and should be cleaned up.
     * */
    protected final void notifyIncomingCall(ImsCallSession c) throws RemoteException {
        synchronized (mLock) {
            if (mListener == null) {
                throw new IllegalStateException("Session is not available.");
            }
            mListener.onIncomingCall(c.getSession());
        }
    }

    /**
     * Provides the MmTelFeature with the ability to return the framework Capability Configuration
     * for a provided Capability. If the framework calls {@link #changeEnabledCapabilities} and
     * includes a capability A to enable or disable, this method should return the correct enabled
     * status for capability A.
     * @param capability The capability that we are querying the configuration for.
     * @return true if the capability is enabled, false otherwise.
     */
    public boolean queryCapabilityConfiguration(@MmTelCapabilities.MmTelCapability int capability,
            @ImsRegistrationImplBase.ImsRegistrationTech int radioTech) {
        // Base implementation - Override to provide functionality
        return false;
    }

    /**
     * The MmTelFeature should override this method to handle the enabling/disabling of
     * MmTel Features, defined in {@link MmTelCapabilities.MmTelCapability}. The framework assumes
     * the {@link CapabilityChangeRequest} was processed successfully. If a subset of capabilities
     * could not be set to their new values,
     * {@link CapabilityCallbackProxy#onChangeCapabilityConfigurationError} must be called
     * individually for each capability whose processing resulted in an error.
     *
     * Enabling/Disabling a capability here indicates that the capability should be registered or
     * deregistered (depending on the capability change) and become available or unavailable to
     * the framework.
     */
    @Override
    public void changeEnabledCapabilities(CapabilityChangeRequest request,
            CapabilityCallbackProxy c) {
        // Base implementation, no-op
    }

    /**
     * Creates a {@link ImsCallProfile} from the service capabilities & IMS registration state.
     *
     * @param callSessionType a service type that is specified in {@link ImsCallProfile}
     *        {@link ImsCallProfile#SERVICE_TYPE_NONE}
     *        {@link ImsCallProfile#SERVICE_TYPE_NORMAL}
     *        {@link ImsCallProfile#SERVICE_TYPE_EMERGENCY}
     * @param callType a call type that is specified in {@link ImsCallProfile}
     *        {@link ImsCallProfile#CALL_TYPE_VOICE}
     *        {@link ImsCallProfile#CALL_TYPE_VT}
     *        {@link ImsCallProfile#CALL_TYPE_VT_TX}
     *        {@link ImsCallProfile#CALL_TYPE_VT_RX}
     *        {@link ImsCallProfile#CALL_TYPE_VT_NODIR}
     *        {@link ImsCallProfile#CALL_TYPE_VS}
     *        {@link ImsCallProfile#CALL_TYPE_VS_TX}
     *        {@link ImsCallProfile#CALL_TYPE_VS_RX}
     * @return a {@link ImsCallProfile} object
     */
    public ImsCallProfile createCallProfile(int callSessionType, int callType) {
        // Base Implementation - Should be overridden
        return null;
    }

    /**
     * Creates an {@link ImsCallSession} with the specified call profile.
     * Use other methods, if applicable, instead of interacting with
     * {@link ImsCallSession} directly.
     *
     * @param profile a call profile to make the call
     */
    public ImsCallSession createCallSession(ImsCallProfile profile) {
        // Base Implementation - Should be overridden
        return null;
    }

    /**
     * @return The Ut interface for the supplementary service configuration.
     */
    public ImsUtImplBase getUt() {
        // Base Implementation - Should be overridden
        return null;
    }

    /**
     * @return The Emergency call-back mode interface for emergency VoLTE calls that support it.
     */
    public ImsEcbmImplBase getEcbm() {
        // Base Implementation - Should be overridden
        return null;
    }

    /**
     * @return The Emergency call-back mode interface for emergency VoLTE calls that support it.
     */
    public ImsMultiEndpointImplBase getMultiEndpoint() {
        // Base Implementation - Should be overridden
        return null;
    }

    /**
     * Sets the current UI TTY mode for the MmTelFeature.
     * @param mode An integer containing the new UI TTY Mode, can consist of
     *         {@link TelecomManager#TTY_MODE_OFF},
     *         {@link TelecomManager#TTY_MODE_FULL},
     *         {@link TelecomManager#TTY_MODE_HCO},
     *         {@link TelecomManager#TTY_MODE_VCO}
     * @param onCompleteMessage A {@link Message} to be used when the mode has been set.
     */
    void setUiTtyMode(int mode, Message onCompleteMessage) {
        // Base Implementation - Should be overridden
    }

    public void setSmsListener(IImsSmsListener listener) {
        getSmsImplementation().registerSmsListener(listener);
    }

    public void sendSms(int token, int messageRef, String format, String smsc, boolean isRetry,
            byte[] pdu) {
        getSmsImplementation().sendSms(token, messageRef, format, smsc, isRetry, pdu);
    }

    public void acknowledgeSms(int token, int messageRef,
            @SmsImplBase.DeliverStatusResult int result) {
        getSmsImplementation().acknowledgeSms(token, messageRef, result);
    }

    public void acknowledgeSmsReport(int token, int messageRef,
            @SmsImplBase.StatusReportResult int result) {
        getSmsImplementation().acknowledgeSmsReport(token, messageRef, result);
    }

    /**
     * Must be overridden by IMS Provider to be able to support SMS over IMS. Otherwise a default
     * non-functional implementation is returned.
     *
     * @return an instance of {@link SmsImplBase} which should be implemented by the IMS
     * Provider.
     */
    public SmsImplBase getSmsImplementation() {
        return new SmsImplBase();
    }

    private String getSmsFormat() {
        return getSmsImplementation().getSmsFormat();
    }

    /**{@inheritDoc}*/
    @Override
    public void onFeatureRemoved() {
        // Base Implementation - Should be overridden
    }

    /**{@inheritDoc}*/
    @Override
    public void onFeatureReady() {
        // Base Implementation - Should be overridden
    }

    /**
     * @hide
     */
    @Override
    public final IImsMmTelFeature getBinder() {
        return mImsMMTelBinder;
    }
}
