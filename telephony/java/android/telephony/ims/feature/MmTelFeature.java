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
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Bundle;
import android.os.Message;
import android.os.RemoteException;
import android.telecom.TelecomManager;
import android.telephony.ims.ImsCallProfile;
import android.telephony.ims.ImsCallSession;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.ImsService;
import android.telephony.ims.RtpHeaderExtensionType;
import android.telephony.ims.aidl.IImsCapabilityCallback;
import android.telephony.ims.aidl.IImsMmTelFeature;
import android.telephony.ims.aidl.IImsMmTelListener;
import android.telephony.ims.aidl.IImsSmsListener;
import android.telephony.ims.stub.ImsCallSessionImplBase;
import android.telephony.ims.stub.ImsEcbmImplBase;
import android.telephony.ims.stub.ImsMultiEndpointImplBase;
import android.telephony.ims.stub.ImsRegistrationImplBase;
import android.telephony.ims.stub.ImsSmsImplBase;
import android.telephony.ims.stub.ImsUtImplBase;
import android.util.ArraySet;

import com.android.ims.internal.IImsCallSession;
import com.android.ims.internal.IImsEcbm;
import com.android.ims.internal.IImsMultiEndpoint;
import com.android.ims.internal.IImsUt;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Set;

/**
 * Base implementation for Voice and SMS (IR-92) and Video (IR-94) IMS support.
 *
 * Any class wishing to use MmTelFeature should extend this class and implement all methods that the
 * service supports.
 */
public class MmTelFeature extends ImsFeature {

    private static final String LOG_TAG = "MmTelFeature";

    /**
     * @hide
     */
    @SystemApi
    public MmTelFeature() {
    }

    private final IImsMmTelFeature mImsMMTelBinder = new IImsMmTelFeature.Stub() {

        @Override
        public void setListener(IImsMmTelListener l) {
            MmTelFeature.this.setListener(l);
        }

        @Override
        public int getFeatureState() throws RemoteException {
            try {
                return MmTelFeature.this.getFeatureState();
            } catch (Exception e) {
                throw new RemoteException(e.getMessage());
            }
        }


        @Override
        public ImsCallProfile createCallProfile(int callSessionType, int callType)
                throws RemoteException {
            synchronized (mLock) {
                try {
                    return MmTelFeature.this.createCallProfile(callSessionType, callType);
                } catch (Exception e) {
                    throw new RemoteException(e.getMessage());
                }
            }
        }

        @Override
        public void changeOfferedRtpHeaderExtensionTypes(List<RtpHeaderExtensionType> types)
                throws RemoteException {
            synchronized (mLock) {
                try {
                    MmTelFeature.this.changeOfferedRtpHeaderExtensionTypes(new ArraySet<>(types));
                } catch (Exception e) {
                    throw new RemoteException(e.getMessage());
                }
            }
        }

        @Override
        public IImsCallSession createCallSession(ImsCallProfile profile) throws RemoteException {
            synchronized (mLock) {
                return createCallSessionInterface(profile);
            }
        }

        @Override
        public int shouldProcessCall(String[] numbers) {
            synchronized (mLock) {
                return MmTelFeature.this.shouldProcessCall(numbers);
            }
        }

        @Override
        public IImsUt getUtInterface() throws RemoteException {
            synchronized (mLock) {
                return MmTelFeature.this.getUtInterface();
            }
        }

        @Override
        public IImsEcbm getEcbmInterface() throws RemoteException {
            synchronized (mLock) {
                return MmTelFeature.this.getEcbmInterface();
            }
        }

        @Override
        public void setUiTtyMode(int uiTtyMode, Message onCompleteMessage) throws RemoteException {
            synchronized (mLock) {
                try {
                    MmTelFeature.this.setUiTtyMode(uiTtyMode, onCompleteMessage);
                } catch (Exception e) {
                    throw new RemoteException(e.getMessage());
                }
            }
        }

        @Override
        public IImsMultiEndpoint getMultiEndpointInterface() throws RemoteException {
            synchronized (mLock) {
                return MmTelFeature.this.getMultiEndpointInterface();
            }
        }

        @Override
        public int queryCapabilityStatus() {
            return MmTelFeature.this.queryCapabilityStatus().mCapabilities;
        }

        @Override
        public void addCapabilityCallback(IImsCapabilityCallback c) {
            // no need to lock, structure already handles multithreading.
            MmTelFeature.this.addCapabilityCallback(c);
        }

        @Override
        public void removeCapabilityCallback(IImsCapabilityCallback c) {
            // no need to lock, structure already handles multithreading.
            MmTelFeature.this.removeCapabilityCallback(c);
        }

        @Override
        public void changeCapabilitiesConfiguration(CapabilityChangeRequest request,
                IImsCapabilityCallback c) {
            MmTelFeature.this.requestChangeEnabledCapabilities(request, c);
        }

        @Override
        public void queryCapabilityConfiguration(int capability, int radioTech,
                IImsCapabilityCallback c) {
            queryCapabilityConfigurationInternal(capability, radioTech, c);
        }

        @Override
        public void setSmsListener(IImsSmsListener l) {
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

        @Override
        public void onSmsReady() {
            synchronized (mLock) {
                MmTelFeature.this.onSmsReady();
            }
        }
    };

    /**
     * Contains the capabilities defined and supported by a MmTelFeature in the form of a Bitmask.
     * The capabilities that are used in MmTelFeature are defined as
     * {@link MmTelCapabilities#CAPABILITY_TYPE_VOICE},
     * {@link MmTelCapabilities#CAPABILITY_TYPE_VIDEO},
     * {@link MmTelCapabilities#CAPABILITY_TYPE_UT},
     * {@link MmTelCapabilities#CAPABILITY_TYPE_SMS}, and
     * {@link MmTelCapabilities#CAPABILITY_TYPE_CALL_COMPOSER}.
     *
     * The capabilities of this MmTelFeature will be set by the framework.
     */
    public static class MmTelCapabilities extends Capabilities {

        /**
         * Create a new empty {@link MmTelCapabilities} instance.
         * @see #addCapabilities(int)
         * @see #removeCapabilities(int)
         * @hide
         */
        @SystemApi
        public MmTelCapabilities() {
            super();
        }

        /**@deprecated Use {@link MmTelCapabilities} to construct a new instance instead.
         * @hide
         */
        @Deprecated
        @SystemApi
        public MmTelCapabilities(Capabilities c) {
            mCapabilities = c.mCapabilities;
        }

        /**
         * Create a new {link @MmTelCapabilities} instance with the provided capabilities.
         * @param capabilities The capabilities that are supported for MmTel in the form of a
         *                     bitfield.
         * @hide
         */
        @SystemApi
        public MmTelCapabilities(@MmTelCapability int capabilities) {
            super(capabilities);
        }

        /** @hide */
        @IntDef(flag = true,
                value = {
                        CAPABILITY_TYPE_VOICE,
                        CAPABILITY_TYPE_VIDEO,
                        CAPABILITY_TYPE_UT,
                        CAPABILITY_TYPE_SMS,
                        CAPABILITY_TYPE_CALL_COMPOSER
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

        /**
         * This MmTelFeature supports Call Composer (section 2.4 of RC.20)
         */
        public static final int CAPABILITY_TYPE_CALL_COMPOSER = 1 << 4;

        /**
         * @hide
         */
        @Override
        @SystemApi
        public final void addCapabilities(@MmTelCapability int capabilities) {
            super.addCapabilities(capabilities);
        }

        /**
         * @hide
         */
        @Override
        @SystemApi
        public final void removeCapabilities(@MmTelCapability int capability) {
            super.removeCapabilities(capability);
        }

        /**
         * @param capabilities a bitmask of one or more capabilities.
         *
         * @return true if all queried capabilities are true, otherwise false.
         */
        @Override
        public final boolean isCapable(@MmTelCapability int capabilities) {
            return super.isCapable(capabilities);
        }

        /**
         * @hide
         */
        @NonNull
        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder("MmTel Capabilities - [");
            builder.append("Voice: ");
            builder.append(isCapable(CAPABILITY_TYPE_VOICE));
            builder.append(" Video: ");
            builder.append(isCapable(CAPABILITY_TYPE_VIDEO));
            builder.append(" UT: ");
            builder.append(isCapable(CAPABILITY_TYPE_UT));
            builder.append(" SMS: ");
            builder.append(isCapable(CAPABILITY_TYPE_SMS));
            builder.append(" CALL_COMPOSER: ");
            builder.append(isCapable(CAPABILITY_TYPE_CALL_COMPOSER));
            builder.append("]");
            return builder.toString();
        }
    }

    /**
     * Listener that the framework implements for communication from the MmTelFeature.
     * @hide
     */
    public static class Listener extends IImsMmTelListener.Stub {

        /**
         * Called when the IMS provider receives an incoming call.
         * @param c The {@link ImsCallSession} associated with the new call.
         * @hide
         */
        @Override
        public void onIncomingCall(IImsCallSession c, Bundle extras) {

        }

        /**
         * Called when the IMS provider implicitly rejects an incoming call during setup.
         * @param callProfile An {@link ImsCallProfile} with the call details.
         * @param reason The {@link ImsReasonInfo} reason for call rejection.
         * @hide
         */
        @Override
        public void onRejectedCall(ImsCallProfile callProfile, ImsReasonInfo reason) {

        }

        /**
         * Updates the Listener when the voice message count for IMS has changed.
         * @param count an integer representing the new message count.
         * @hide
         */
        @Override
        public void onVoiceMessageCountUpdate(int count) {

        }
    }

    /**
     * To be returned by {@link #shouldProcessCall(String[])} when the ImsService should process the
     * outgoing call as IMS.
     * @hide
     */
    @SystemApi
    public static final int PROCESS_CALL_IMS = 0;
    /**
     * To be returned by {@link #shouldProcessCall(String[])} when the telephony framework should
     * not process the outgoing call as IMS and should instead use circuit switch.
     * @hide
     */
    @SystemApi
    public static final int PROCESS_CALL_CSFB = 1;

    /** @hide */
    @IntDef(flag = true,
            value = {
                    PROCESS_CALL_IMS,
                    PROCESS_CALL_CSFB
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ProcessCallResult {}

    /**
     * If the flag is present and true, it indicates that the incoming call is for USSD.
     * <p>
     * This is an optional boolean flag.
     * @hide
     */
    @SystemApi
    public static final String EXTRA_IS_USSD = "android.telephony.ims.feature.extra.IS_USSD";

    /**
     * If this flag is present and true, this call is marked as an unknown dialing call instead
     * of an incoming call. An example of such a call is a call that is originated by sending
     * commands (like AT commands) directly to the modem without Android involvement or dialing
     * calls appearing over IMS when the modem does a silent redial from circuit-switched to IMS in
     * certain situations.
     * <p>
     * This is an optional boolean flag.
     * @hide
     */
    @SystemApi
    public static final String EXTRA_IS_UNKNOWN_CALL =
            "android.telephony.ims.feature.extra.IS_UNKNOWN_CALL";

    private IImsMmTelListener mListener;

    /**
     * @param listener A {@link Listener} used when the MmTelFeature receives an incoming call and
     *     notifies the framework.
     */
    private void setListener(IImsMmTelListener listener) {
        synchronized (mLock) {
            mListener = listener;
            if (mListener != null) {
                onFeatureReady();
            }
        }
    }

    /**
     * @return the listener associated with this MmTelFeature. May be null if it has not been set
     * by the framework yet.
     */
    private IImsMmTelListener getListener() {
        synchronized (mLock) {
            return mListener;
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
     * @hide
     */
    @Override
    @SystemApi
    public @NonNull final MmTelCapabilities queryCapabilityStatus() {
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
     * @hide
     */
    @SystemApi
    public final void notifyCapabilitiesStatusChanged(@NonNull MmTelCapabilities c) {
        if (c == null) {
            throw new IllegalArgumentException("MmTelCapabilities must be non-null!");
        }
        super.notifyCapabilitiesStatusChanged(c);
    }

    /**
     * Notify the framework of an incoming call.
     * @param c The {@link ImsCallSessionImplBase} of the new incoming call.
     * @param extras A bundle containing extra parameters related to the call. See
     * {@link #EXTRA_IS_UNKNOWN_CALL} and {@link #EXTRA_IS_USSD} above.
      * @hide
     */
    @SystemApi
    public final void notifyIncomingCall(@NonNull ImsCallSessionImplBase c,
            @NonNull Bundle extras) {
        if (c == null || extras == null) {
            throw new IllegalArgumentException("ImsCallSessionImplBase and Bundle can not be "
                    + "null.");
        }
        IImsMmTelListener listener = getListener();
        if (listener == null) {
            throw new IllegalStateException("Session is not available.");
        }
        try {
            listener.onIncomingCall(c.getServiceImpl(), extras);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Notify the framework that a call has been implicitly rejected by this MmTelFeature
     * during call setup.
     * @param callProfile The {@link ImsCallProfile} IMS call profile with details.
     *        This can be null if no call information is available for the rejected call.
     * @param reason The {@link ImsReasonInfo} call rejection reason.
     * @hide
     */
    @SystemApi
    public final void notifyRejectedCall(@NonNull ImsCallProfile callProfile,
            @NonNull ImsReasonInfo reason) {
        if (callProfile == null || reason == null) {
            throw new IllegalArgumentException("ImsCallProfile and ImsReasonInfo must not be "
                    + "null.");
        }
        IImsMmTelListener listener = getListener();
        if (listener == null) {
            throw new IllegalStateException("Session is not available.");
        }
        try {
            listener.onRejectedCall(callProfile, reason);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     *
     * @hide
     */
    public final void notifyIncomingCallSession(IImsCallSession c, Bundle extras) {
        IImsMmTelListener listener = getListener();
        if (listener == null) {
            throw new IllegalStateException("Session is not available.");
        }
        try {
            listener.onIncomingCall(c, extras);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Notify the framework of a change in the Voice Message count.
     * @link count the new Voice Message count.
     * @hide
     */
    @SystemApi
    public final void notifyVoiceMessageCountUpdate(int count) {
        IImsMmTelListener listener = getListener();
        if (listener == null) {
            throw new IllegalStateException("Session is not available.");
        }
        try {
            listener.onVoiceMessageCountUpdate(count);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Provides the MmTelFeature with the ability to return the framework Capability Configuration
     * for a provided Capability. If the framework calls {@link #changeEnabledCapabilities} and
     * includes a capability A to enable or disable, this method should return the correct enabled
     * status for capability A.
     * @param capability The capability that we are querying the configuration for.
     * @return true if the capability is enabled, false otherwise.
     * @hide
     */
    @Override
    @SystemApi
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
     * * @hide
     */
    @Override
    @SystemApi
    public void changeEnabledCapabilities(@NonNull CapabilityChangeRequest request,
            @NonNull CapabilityCallbackProxy c) {
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
     * @hide
     */
    @SystemApi
    public @Nullable ImsCallProfile createCallProfile(int callSessionType, int callType) {
        // Base Implementation - Should be overridden
        return null;
    }

    /**
     * Called by the framework to report a change to the RTP header extension types which should be
     * offered during SDP negotiation (see RFC8285 for more information).
     * <p>
     * The {@link ImsService} should report the RTP header extensions which were accepted during
     * SDP negotiation using {@link ImsCallProfile#setAcceptedRtpHeaderExtensionTypes(Set)}.
     *
     * @param extensionTypes The RTP header extensions the framework wishes to offer during
     *                       outgoing and incoming call setup.  An empty list indicates that there
     *                       are no framework defined RTP header extension types to offer.
     * @hide
     */
    @SystemApi
    public void changeOfferedRtpHeaderExtensionTypes(
            @NonNull Set<RtpHeaderExtensionType> extensionTypes) {
        // Base implementation - should be overridden if RTP header extension handling is supported.
    }

    /**
     * @hide
     */
    public IImsCallSession createCallSessionInterface(ImsCallProfile profile)
            throws RemoteException {
        ImsCallSessionImplBase s = MmTelFeature.this.createCallSession(profile);
        return s != null ? s.getServiceImpl() : null;
    }

    /**
     * Creates an {@link ImsCallSession} with the specified call profile.
     * Use other methods, if applicable, instead of interacting with
     * {@link ImsCallSession} directly.
     *
     * @param profile a call profile to make the call
     * @hide
     */
    @SystemApi
    public @Nullable ImsCallSessionImplBase createCallSession(@NonNull ImsCallProfile profile) {
        // Base Implementation - Should be overridden
        return null;
    }

    /**
     * Called by the framework to determine if the outgoing call, designated by the outgoing
     * {@link String}s, should be processed as an IMS call or CSFB call. If this method's
     * functionality is not overridden, the platform will process every call as IMS as long as the
     * MmTelFeature reports that the {@link MmTelCapabilities#CAPABILITY_TYPE_VOICE} capability is
     * available.
     * @param numbers An array of {@link String}s that will be used for placing the call. There can
     *         be multiple {@link String}s listed in the case when we want to place an outgoing
     *         call as a conference.
     * @return a {@link ProcessCallResult} to the framework, which will be used to determine if the
     *        call will be placed over IMS or via CSFB.
     * @hide
     */
    @SystemApi
    public @ProcessCallResult int shouldProcessCall(@NonNull String[] numbers) {
        return PROCESS_CALL_IMS;
    }

    /**
     *
     * @hide
     */
    protected IImsUt getUtInterface() throws RemoteException {
        ImsUtImplBase utImpl = getUt();
        return utImpl != null ? utImpl.getInterface() : null;
    }

    /**
     * @hide
     */
    protected IImsEcbm getEcbmInterface() throws RemoteException {
        ImsEcbmImplBase ecbmImpl = getEcbm();
        return ecbmImpl != null ? ecbmImpl.getImsEcbm() : null;
    }

    /**
     * @hide
     */
    public IImsMultiEndpoint getMultiEndpointInterface() throws RemoteException {
        ImsMultiEndpointImplBase multiendpointImpl = getMultiEndpoint();
        return multiendpointImpl != null ? multiendpointImpl.getIImsMultiEndpoint() : null;
    }

    /**
     * @return The {@link ImsUtImplBase} Ut interface implementation for the supplementary service
     * configuration.
     * @hide
     */
    @SystemApi
    public @NonNull ImsUtImplBase getUt() {
        // Base Implementation - Should be overridden
        return new ImsUtImplBase();
    }

    /**
     * @return The {@link ImsEcbmImplBase} Emergency call-back mode interface for emergency VoLTE
     * calls that support it.
     * @hide
     */
    @SystemApi
    public @NonNull ImsEcbmImplBase getEcbm() {
        // Base Implementation - Should be overridden
        return new ImsEcbmImplBase();
    }

    /**
     * @return The {@link ImsMultiEndpointImplBase} implementation for implementing Dialog event
     * package processing for multi-endpoint.
     * @hide
     */
    @SystemApi
    public @NonNull ImsMultiEndpointImplBase getMultiEndpoint() {
        // Base Implementation - Should be overridden
        return new ImsMultiEndpointImplBase();
    }

    /**
     * Sets the current UI TTY mode for the MmTelFeature.
     * @param mode An integer containing the new UI TTY Mode, can consist of
     *         {@link TelecomManager#TTY_MODE_OFF},
     *         {@link TelecomManager#TTY_MODE_FULL},
     *         {@link TelecomManager#TTY_MODE_HCO},
     *         {@link TelecomManager#TTY_MODE_VCO}
     * @param onCompleteMessage If non-null, this MmTelFeature should call this {@link Message} when
     *         the operation is complete by using the associated {@link android.os.Messenger} in
     *         {@link Message#replyTo}. For example:
     * {@code
     *     // Set UI TTY Mode and other operations...
     *     try {
     *         // Notify framework that the mode was changed.
     *         Messenger uiMessenger = onCompleteMessage.replyTo;
     *         uiMessenger.send(onCompleteMessage);
     *     } catch (RemoteException e) {
     *         // Remote side is dead
     *     }
     * }
     * @hide
     */
    @SystemApi
    public void setUiTtyMode(int mode, @Nullable Message onCompleteMessage) {
        // Base Implementation - Should be overridden
    }

    private void setSmsListener(IImsSmsListener listener) {
        getSmsImplementation().registerSmsListener(listener);
    }

    private void sendSms(int token, int messageRef, String format, String smsc, boolean isRetry,
            byte[] pdu) {
        getSmsImplementation().sendSms(token, messageRef, format, smsc, isRetry, pdu);
    }

    private void acknowledgeSms(int token, int messageRef,
            @ImsSmsImplBase.DeliverStatusResult int result) {
        getSmsImplementation().acknowledgeSms(token, messageRef, result);
    }

    private void acknowledgeSmsReport(int token, int messageRef,
            @ImsSmsImplBase.StatusReportResult int result) {
        getSmsImplementation().acknowledgeSmsReport(token, messageRef, result);
    }

    private void onSmsReady() {
        getSmsImplementation().onReady();
    }

    /**
     * Must be overridden by IMS Provider to be able to support SMS over IMS. Otherwise a default
     * non-functional implementation is returned.
     *
     * @return an instance of {@link ImsSmsImplBase} which should be implemented by the IMS
     * Provider.
     * @hide
     */
    @SystemApi
    public @NonNull ImsSmsImplBase getSmsImplementation() {
        return new ImsSmsImplBase();
    }

    private String getSmsFormat() {
        return getSmsImplementation().getSmsFormat();
    }

    /**
     * {@inheritDoc}
     * @hide
     */
    @Override
    @SystemApi
    public void onFeatureRemoved() {
        // Base Implementation - Should be overridden
    }

    /**
     * {@inheritDoc}
     * @hide
     */
    @Override
    @SystemApi
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
