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

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Binder;
import android.os.Bundle;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.telecom.TelecomManager;
import android.telephony.AccessNetworkConstants;
import android.telephony.ims.ImsCallProfile;
import android.telephony.ims.ImsCallSession;
import android.telephony.ims.ImsCallSessionListener;
import android.telephony.ims.ImsException;
import android.telephony.ims.ImsReasonInfo;
import android.telephony.ims.ImsService;
import android.telephony.ims.MediaQualityStatus;
import android.telephony.ims.MediaThreshold;
import android.telephony.ims.RtpHeaderExtensionType;
import android.telephony.ims.SrvccCall;
import android.telephony.ims.aidl.IImsCallSessionListener;
import android.telephony.ims.aidl.IImsCapabilityCallback;
import android.telephony.ims.aidl.IImsMmTelFeature;
import android.telephony.ims.aidl.IImsMmTelListener;
import android.telephony.ims.aidl.IImsSmsListener;
import android.telephony.ims.aidl.IImsTrafficSessionCallback;
import android.telephony.ims.aidl.ISrvccStartedCallback;
import android.telephony.ims.stub.ImsCallSessionImplBase;
import android.telephony.ims.stub.ImsEcbmImplBase;
import android.telephony.ims.stub.ImsMultiEndpointImplBase;
import android.telephony.ims.stub.ImsRegistrationImplBase;
import android.telephony.ims.stub.ImsSmsImplBase;
import android.telephony.ims.stub.ImsUtImplBase;
import android.util.ArraySet;
import android.util.Log;

import com.android.ims.internal.IImsCallSession;
import com.android.ims.internal.IImsEcbm;
import com.android.ims.internal.IImsMultiEndpoint;
import com.android.ims.internal.IImsUt;
import com.android.internal.telephony.util.TelephonyUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Base implementation for Voice and SMS (IR-92) and Video (IR-94) IMS support.
 *
 * Any class wishing to use MmTelFeature should extend this class and implement all methods that the
 * service supports.
 */
public class MmTelFeature extends ImsFeature {

    private static final String LOG_TAG = "MmTelFeature";
    private Executor mExecutor;
    private ImsSmsImplBase mSmsImpl;

    private HashMap<ImsTrafficSessionCallback, ImsTrafficSessionCallbackWrapper> mTrafficCallbacks =
            new HashMap<>();
    /**
     * Creates a new MmTelFeature using the Executor set in {@link ImsService#getExecutor}
     * @hide
     */
    @SystemApi
    public MmTelFeature() {
    }

    /**
     * Create a new MmTelFeature using the Executor specified for methods being called by the
     * framework.
     * @param executor The executor for the framework to use when executing the methods overridden
     * by the implementation of MmTelFeature.
     * @hide
     */
    @SystemApi
    public MmTelFeature(@NonNull Executor executor) {
        super();
        mExecutor = executor;
    }

    private final IImsMmTelFeature mImsMMTelBinder = new IImsMmTelFeature.Stub() {

        @Override
        public void setListener(IImsMmTelListener l) {
            executeMethodAsyncNoException(() -> MmTelFeature.this.setListener(l), "setListener");
        }

        @Override
        public int getFeatureState() throws RemoteException {
            return executeMethodAsyncForResult(() -> MmTelFeature.this.getFeatureState(),
                    "getFeatureState");
        }

        @Override
        public ImsCallProfile createCallProfile(int callSessionType, int callType)
                throws RemoteException {
            return executeMethodAsyncForResult(() -> MmTelFeature.this.createCallProfile(
                    callSessionType, callType), "createCallProfile");
        }

        @Override
        public void changeOfferedRtpHeaderExtensionTypes(List<RtpHeaderExtensionType> types)
                throws RemoteException {
            executeMethodAsync(() -> MmTelFeature.this.changeOfferedRtpHeaderExtensionTypes(
                    new ArraySet<>(types)), "changeOfferedRtpHeaderExtensionTypes");
        }

        @Override
        public IImsCallSession createCallSession(ImsCallProfile profile) throws RemoteException {
            AtomicReference<RemoteException> exceptionRef = new AtomicReference<>();
            IImsCallSession result = executeMethodAsyncForResult(() -> {
                try {
                    return createCallSessionInterface(profile);
                } catch (RemoteException e) {
                    exceptionRef.set(e);
                    return null;
                }
            }, "createCallSession");

            if (exceptionRef.get() != null) {
                throw exceptionRef.get();
            }

            return result;
        }

        @Override
        public int shouldProcessCall(String[] numbers) {
            Integer result = executeMethodAsyncForResultNoException(() ->
                    MmTelFeature.this.shouldProcessCall(numbers), "shouldProcessCall");
            if (result != null) {
                return result.intValue();
            } else {
                return PROCESS_CALL_CSFB;
            }
        }

        @Override
        public IImsUt getUtInterface() throws RemoteException {
            AtomicReference<RemoteException> exceptionRef = new AtomicReference<>();
            IImsUt result = executeMethodAsyncForResult(() -> {
                try {
                    return MmTelFeature.this.getUtInterface();
                } catch (RemoteException e) {
                    exceptionRef.set(e);
                    return null;
                }
            }, "getUtInterface");

            if (exceptionRef.get() != null) {
                throw exceptionRef.get();
            }

            return result;
        }

        @Override
        public IImsEcbm getEcbmInterface() throws RemoteException {
            AtomicReference<RemoteException> exceptionRef = new AtomicReference<>();
            IImsEcbm result = executeMethodAsyncForResult(() -> {
                try {
                    return MmTelFeature.this.getEcbmInterface();
                } catch (RemoteException e) {
                    exceptionRef.set(e);
                    return null;
                }
            }, "getEcbmInterface");

            if (exceptionRef.get() != null) {
                throw exceptionRef.get();
            }

            return result;
        }

        @Override
        public void setUiTtyMode(int uiTtyMode, Message onCompleteMessage) throws RemoteException {
            executeMethodAsync(() -> MmTelFeature.this.setUiTtyMode(uiTtyMode, onCompleteMessage),
                    "setUiTtyMode");
        }

        @Override
        public IImsMultiEndpoint getMultiEndpointInterface() throws RemoteException {
            AtomicReference<RemoteException> exceptionRef = new AtomicReference<>();
            IImsMultiEndpoint result = executeMethodAsyncForResult(() -> {
                try {
                    return MmTelFeature.this.getMultiEndpointInterface();
                } catch (RemoteException e) {
                    exceptionRef.set(e);
                    return null;
                }
            }, "getMultiEndpointInterface");

            if (exceptionRef.get() != null) {
                throw exceptionRef.get();
            }

            return result;
        }

        @Override
        public int queryCapabilityStatus() {
            Integer result = executeMethodAsyncForResultNoException(() -> MmTelFeature.this
                    .queryCapabilityStatus().mCapabilities, "queryCapabilityStatus");

            if (result != null) {
                return result.intValue();
            } else {
                return 0;
            }
        }

        @Override
        public void addCapabilityCallback(IImsCapabilityCallback c) {
            executeMethodAsyncNoException(() -> MmTelFeature.this
                    .addCapabilityCallback(c), "addCapabilityCallback");
        }

        @Override
        public void removeCapabilityCallback(IImsCapabilityCallback c) {
            executeMethodAsyncNoException(() -> MmTelFeature.this
                    .removeCapabilityCallback(c), "removeCapabilityCallback");
        }

        @Override
        public void changeCapabilitiesConfiguration(CapabilityChangeRequest request,
                IImsCapabilityCallback c) {
            executeMethodAsyncNoException(() -> MmTelFeature.this
                            .requestChangeEnabledCapabilities(request, c),
                    "changeCapabilitiesConfiguration");
        }

        @Override
        public void queryCapabilityConfiguration(int capability, int radioTech,
                IImsCapabilityCallback c) {
            executeMethodAsyncNoException(() -> queryCapabilityConfigurationInternal(
                    capability, radioTech, c), "queryCapabilityConfiguration");
        }

        @Override
        public void setMediaQualityThreshold(@MediaQualityStatus.MediaSessionType int sessionType,
                MediaThreshold mediaThreshold) {
            if (mediaThreshold != null) {
                executeMethodAsyncNoException(() -> setMediaThreshold(sessionType, mediaThreshold),
                        "setMediaQualityThreshold");
            } else {
                executeMethodAsyncNoException(() -> clearMediaThreshold(sessionType),
                        "clearMediaQualityThreshold");
            }
        }

        @Override
        public MediaQualityStatus queryMediaQualityStatus(
                @MediaQualityStatus.MediaSessionType int sessionType)
                throws RemoteException {
            return executeMethodAsyncForResult(() -> MmTelFeature.this.queryMediaQualityStatus(
                    sessionType), "queryMediaQualityStatus");
        }

        @Override
        public void setSmsListener(IImsSmsListener l) {
            executeMethodAsyncNoException(() -> MmTelFeature.this.setSmsListener(l),
                    "setSmsListener", getImsSmsImpl().getExecutor());
        }

        @Override
        public void sendSms(int token, int messageRef, String format, String smsc, boolean retry,
                byte[] pdu) {
            executeMethodAsyncNoException(() -> MmTelFeature.this
                    .sendSms(token, messageRef, format, smsc, retry, pdu), "sendSms",
                    getImsSmsImpl().getExecutor());
        }

        @Override
        public void onMemoryAvailable(int token) {
            executeMethodAsyncNoException(() -> MmTelFeature.this
                    .onMemoryAvailable(token), "onMemoryAvailable", getImsSmsImpl().getExecutor());
        }

        @Override
        public void acknowledgeSms(int token, int messageRef, int result) {
            executeMethodAsyncNoException(() -> MmTelFeature.this
                    .acknowledgeSms(token, messageRef, result), "acknowledgeSms",
                    getImsSmsImpl().getExecutor());
        }

        @Override
        public void acknowledgeSmsWithPdu(int token, int messageRef, int result, byte[] pdu) {
            executeMethodAsyncNoException(() -> MmTelFeature.this
                    .acknowledgeSms(token, messageRef, result, pdu), "acknowledgeSms",
                    getImsSmsImpl().getExecutor());
        }

        @Override
        public void acknowledgeSmsReport(int token, int messageRef, int result) {
            executeMethodAsyncNoException(() -> MmTelFeature.this
                    .acknowledgeSmsReport(token, messageRef, result), "acknowledgeSmsReport",
                    getImsSmsImpl().getExecutor());
        }

        @Override
        public String getSmsFormat() {
            return executeMethodAsyncForResultNoException(() -> MmTelFeature.this
                    .getSmsFormat(), "getSmsFormat", getImsSmsImpl().getExecutor());
        }

        @Override
        public void onSmsReady() {
            executeMethodAsyncNoException(() -> MmTelFeature.this.onSmsReady(),
                    "onSmsReady", getImsSmsImpl().getExecutor());
        }

        @Override
        public void notifySrvccStarted(final ISrvccStartedCallback cb) {
            executeMethodAsyncNoException(
                    () -> MmTelFeature.this.notifySrvccStarted(
                            (profiles) -> {
                                try {
                                    cb.onSrvccCallNotified(profiles);
                                } catch (Exception e) {
                                    Log.e(LOG_TAG, "onSrvccCallNotified e=" + e);
                                }
                            }),
                    "notifySrvccStarted");
        }

        @Override
        public void notifySrvccCompleted() {
            executeMethodAsyncNoException(
                    () -> MmTelFeature.this.notifySrvccCompleted(), "notifySrvccCompleted");
        }

        @Override
        public void notifySrvccFailed() {
            executeMethodAsyncNoException(
                    () -> MmTelFeature.this.notifySrvccFailed(), "notifySrvccFailed");
        }

        @Override
        public void notifySrvccCanceled() {
            executeMethodAsyncNoException(
                    () -> MmTelFeature.this.notifySrvccCanceled(), "notifySrvccCanceled");
        }

        @Override
        public void setTerminalBasedCallWaitingStatus(boolean enabled) throws RemoteException {
            synchronized (mLock) {
                try {
                    MmTelFeature.this.setTerminalBasedCallWaitingStatus(enabled);
                } catch (ServiceSpecificException se) {
                    throw new ServiceSpecificException(se.errorCode, se.getMessage());
                } catch (Exception e) {
                    throw new RemoteException(e.getMessage());
                }
            }
        }

        // Call the methods with a clean calling identity on the executor and wait indefinitely for
        // the future to return.
        private void executeMethodAsync(Runnable r, String errorLogName) throws RemoteException {
            try {
                CompletableFuture.runAsync(
                        () -> TelephonyUtils.runWithCleanCallingIdentity(r), mExecutor).join();
            } catch (CancellationException | CompletionException e) {
                Log.w(LOG_TAG, "MmTelFeature Binder - " + errorLogName + " exception: "
                        + e.getMessage());
                throw new RemoteException(e.getMessage());
            }
        }

        private void executeMethodAsyncNoException(Runnable r, String errorLogName) {
            try {
                CompletableFuture.runAsync(
                        () -> TelephonyUtils.runWithCleanCallingIdentity(r), mExecutor).join();
            } catch (CancellationException | CompletionException e) {
                Log.w(LOG_TAG, "MmTelFeature Binder - " + errorLogName + " exception: "
                        + e.getMessage());
            }
        }

        private void executeMethodAsyncNoException(Runnable r, String errorLogName,
                Executor executor) {
            try {
                CompletableFuture.runAsync(
                        () -> TelephonyUtils.runWithCleanCallingIdentity(r), executor).join();
            } catch (CancellationException | CompletionException e) {
                Log.w(LOG_TAG, "MmTelFeature Binder - " + errorLogName + " exception: "
                        + e.getMessage());
            }
        }

        private <T> T executeMethodAsyncForResult(Supplier<T> r,
                String errorLogName) throws RemoteException {
            CompletableFuture<T> future = CompletableFuture.supplyAsync(
                    () -> TelephonyUtils.runWithCleanCallingIdentity(r), mExecutor);
            try {
                return future.get();
            } catch (ExecutionException | InterruptedException e) {
                Log.w(LOG_TAG, "MmTelFeature Binder - " + errorLogName + " exception: "
                        + e.getMessage());
                throw new RemoteException(e.getMessage());
            }
        }

        private <T> T executeMethodAsyncForResultNoException(Supplier<T> r,
                String errorLogName) {
            CompletableFuture<T> future = CompletableFuture.supplyAsync(
                    () -> TelephonyUtils.runWithCleanCallingIdentity(r), mExecutor);
            try {
                return future.get();
            } catch (ExecutionException | InterruptedException e) {
                Log.w(LOG_TAG, "MmTelFeature Binder - " + errorLogName + " exception: "
                        + e.getMessage());
                return null;
            }
        }

        private <T> T executeMethodAsyncForResultNoException(Supplier<T> r,
                String errorLogName, Executor executor) {
            CompletableFuture<T> future = CompletableFuture.supplyAsync(
                    () -> TelephonyUtils.runWithCleanCallingIdentity(r), executor);
            try {
                return future.get();
            } catch (ExecutionException | InterruptedException e) {
                Log.w(LOG_TAG, "MmTelFeature Binder - " + errorLogName + " exception: "
                        + e.getMessage());
                return null;
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
         * Undefined capability type for initialization
         * This is used to check the upper range of MmTel capability
         * @hide
         */
        public static final int CAPABILITY_TYPE_NONE = 0;

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
         * This is used to check the upper range of MmTel capability
         * @hide
         */
        public static final int CAPABILITY_TYPE_MAX = CAPABILITY_TYPE_CALL_COMPOSER + 1;

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
         * @param callId The call ID of the session of the new incoming call.
         * @param extras A bundle containing extra parameters related to the call. See
         * {@link #EXTRA_IS_UNKNOWN_CALL} and {@link #EXTRA_IS_USSD} above.
         * @return the listener to listen to the session events. An {@link ImsCallSession} can only
         *         hold one listener at a time. see {@link ImsCallSessionListener}.
         *         If this method returns {@code null}, then the call could not be placed.
         * @hide
         */
        @Override
        @Nullable
        public IImsCallSessionListener onIncomingCall(IImsCallSession c,
                String callId, Bundle extras) {
            return null;
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

        /**
         * Called to set the audio handler for this connection.
         * @param imsAudioHandler an {@link ImsAudioHandler} used to handle the audio
         *        for this IMS call.
         * @hide
         */
        @Override
        public void onAudioModeIsVoipChanged(int imsAudioHandler) {

        }

        /**
         * Called when the IMS triggers EPS fallback procedure.
         *
         * @param reason specifies the reason that causes EPS fallback.
         * @hide
         */
        @Override
        public void onTriggerEpsFallback(@EpsFallbackReason int reason) {

        }

        /**
         * Called when the IMS notifies the upcoming traffic type to the radio.
         *
         * @param token A nonce to identify the request
         * @param trafficType The {@link ImsTrafficType} type for IMS traffic.
         * @param accessNetworkType The {@link AccessNetworkConstants#RadioAccessNetworkType}
         *        type of the radio access network.
         * @param trafficDirection Indicates whether traffic is originated by mobile originated or
         *        mobile terminated use case eg. MO/MT call/SMS etc.
         * @param callback The callback to receive the result.
         * @hide
         */
        @Override
        public void onStartImsTrafficSession(int token,
                @ImsTrafficType int trafficType,
                @AccessNetworkConstants.RadioAccessNetworkType int accessNetworkType,
                @ImsTrafficDirection int trafficDirection,
                IImsTrafficSessionCallback callback) {

        }

        /**
         * Called when the IMS notifies the traffic type has been stopped.
         *
         * @param token A nonce registered with {@link #onStartImsTrafficSession}.
         * @param accessNetworkType The {@link AccessNetworkConstants#RadioAccessNetworkType}
         *        type of the radio access network.
         * @hide
         */
        @Override
        public void onModifyImsTrafficSession(int token,
                @AccessNetworkConstants.RadioAccessNetworkType int accessNetworkType) {

        }

        /**
         * Called when the IMS notifies the traffic type has been stopped.
         *
         * @param token A nonce registered with {@link #onStartImsTrafficSession}.
         * @hide
         */
        @Override
        public void onStopImsTrafficSession(int token) {

        }

        /**
         * Called when the IMS provider notifies {@link MediaQualityStatus}.
         *
         * @param status media quality status currently measured.
         * @hide
         */
        @Override
        public void onMediaQualityStatusChanged(MediaQualityStatus status) {

        }
    }

    /**
     * A wrapper class of {@link ImsTrafficSessionCallback}.
     * @hide
     */
    public static class ImsTrafficSessionCallbackWrapper {
        public static final int INVALID_TOKEN = -1;

        private static final int MAX_TOKEN = 0x10000;

        private static final AtomicInteger sTokenGenerator = new AtomicInteger();

        /** Callback to receive the response */
        private IImsTrafficSessionCallbackStub mCallback = null;
        /** Identifier to distinguish each IMS traffic request */
        private int mToken = INVALID_TOKEN;

        private ImsTrafficSessionCallback mImsTrafficSessionCallback;

        private ImsTrafficSessionCallbackWrapper(ImsTrafficSessionCallback callback) {
            mImsTrafficSessionCallback = callback;
        }

        /**
         * Updates the callback.
         *
         * The mToken should be kept since it is used to identify the traffic notified to the modem
         * until calling {@link MmtelFEature#stopImsTrafficSession}.
         */
        final void update(@NonNull @CallbackExecutor Executor executor) {
            if (executor == null) {
                throw new IllegalArgumentException(
                        "ImsTrafficSessionCallback Executor must be non-null");
            }

            if (mCallback == null) {
                // initial start of Ims traffic.
                mCallback = new IImsTrafficSessionCallbackStub(
                        mImsTrafficSessionCallback, executor);
                mToken = generateToken();
            } else {
                // handover between cellular and Wi-Fi
                mCallback.update(executor);
            }
        }

        /**
         * Using a static class and weak reference here to avoid memory leak caused by the
         * {@link IImsTrafficSessionCallback.Stub} callback retaining references to the outside
         * {@link ImsTrafficSessionCallback}.
         */
        private static class IImsTrafficSessionCallbackStub
                extends IImsTrafficSessionCallback.Stub {
            private WeakReference<ImsTrafficSessionCallback> mImsTrafficSessionCallbackWeakRef;
            private Executor mExecutor;

            IImsTrafficSessionCallbackStub(ImsTrafficSessionCallback imsTrafficCallback,
                    Executor executor) {
                mImsTrafficSessionCallbackWeakRef =
                        new WeakReference<ImsTrafficSessionCallback>(imsTrafficCallback);
                mExecutor = executor;
            }

            void update(Executor executor) {
                mExecutor = executor;
            }

            @Override
            public void onReady() {
                ImsTrafficSessionCallback callback = mImsTrafficSessionCallbackWeakRef.get();
                if (callback == null) return;

                Binder.withCleanCallingIdentity(
                        () -> mExecutor.execute(() -> callback.onReady()));
            }

            @Override
            public void onError(ConnectionFailureInfo info) {
                ImsTrafficSessionCallback callback = mImsTrafficSessionCallbackWeakRef.get();
                if (callback == null) return;

                Binder.withCleanCallingIdentity(
                        () -> mExecutor.execute(() -> callback.onError(info)));
            }
        }

        /**
         * Returns the callback binder.
         */
        final IImsTrafficSessionCallbackStub getCallbackBinder() {
            return mCallback;
        }

        /**
         * Returns the token.
         */
        final int getToken() {
            return mToken;
        }

        /**
         * Resets the members.
         * It's called by {@link MmTelFeature#stopImsTrafficSession}.
         */
        final void reset() {
            mCallback = null;
            mToken = INVALID_TOKEN;
        }

        private static int generateToken() {
            int token = sTokenGenerator.incrementAndGet();
            if (token == MAX_TOKEN) sTokenGenerator.set(0);
            return token;
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

    /** @hide */
    @IntDef(
        prefix = "AUDIO_HANDLER_",
        value = {
            AUDIO_HANDLER_ANDROID,
            AUDIO_HANDLER_BASEBAND
        })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ImsAudioHandler {}

    /**
    * Audio Handler - Android
    * @hide
    */
    @SystemApi
    public static final int AUDIO_HANDLER_ANDROID = 0;

    /**
    * Audio Handler - Baseband
    * @hide
    */
    @SystemApi
    public static final int AUDIO_HANDLER_BASEBAND = 1;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
        prefix = "EPS_FALLBACK_REASON_",
        value = {
            EPS_FALLBACK_REASON_INVALID,
            EPS_FALLBACK_REASON_NO_NETWORK_TRIGGER,
            EPS_FALLBACK_REASON_NO_NETWORK_RESPONSE,
        })
    public @interface EpsFallbackReason {}

    /**
     * Default value. Internal use only.
     * This value should not be used to trigger EPS fallback.
     * @hide
     */
    public static final int EPS_FALLBACK_REASON_INVALID = -1;

    /**
     * If the network only supports the EPS fallback in 5G NR SA for voice calling and the EPS
     * Fallback procedure by the network during the call setup is not triggered, UE initiated
     * fallback will be triggered with this reason. The modem shall locally release the 5G NR
     * SA RRC connection and acquire the LTE network and perform a tracking area update
     * procedure. After the EPS fallback procedure is completed, the call setup for voice will
     * be established if there is no problem.
     *
     * @hide
     */
    public static final int EPS_FALLBACK_REASON_NO_NETWORK_TRIGGER = 1;

    /**
     * If the UE doesn't receive any response for SIP INVITE within a certain timeout in 5G NR
     * SA for MO voice calling, the device determines that voice call is not available in 5G and
     * terminates all active SIP dialogs and SIP requests and enters IMS non-registered state.
     * In that case, UE initiated fallback will be triggered with this reason. The modem shall
     * reset modem's data buffer of IMS PDU to prevent the ghost call. After the EPS fallback
     * procedure is completed, VoLTE call could be tried if there is no problem.
     *
     * @hide
     */
    public static final int EPS_FALLBACK_REASON_NO_NETWORK_RESPONSE = 2;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
        prefix = "IMS_TRAFFIC_TYPE_",
        value = {
            IMS_TRAFFIC_TYPE_NONE,
            IMS_TRAFFIC_TYPE_EMERGENCY,
            IMS_TRAFFIC_TYPE_EMERGENCY_SMS,
            IMS_TRAFFIC_TYPE_VOICE,
            IMS_TRAFFIC_TYPE_VIDEO,
            IMS_TRAFFIC_TYPE_SMS,
            IMS_TRAFFIC_TYPE_REGISTRATION,
            IMS_TRAFFIC_TYPE_UT_XCAP
        })
    public @interface ImsTrafficType {}

    /**
     * Default value for initialization. Internal use only.
     * @hide
     */
    public static final int IMS_TRAFFIC_TYPE_NONE = -1;
    /**
     * Emergency call
     * @hide
     */
    public static final int IMS_TRAFFIC_TYPE_EMERGENCY = 0;
    /**
     * Emergency SMS
     * @hide
     */
    public static final int IMS_TRAFFIC_TYPE_EMERGENCY_SMS = 1;
    /**
     * Voice call
     * @hide
     */
    public static final int IMS_TRAFFIC_TYPE_VOICE = 2;
    /**
     * Video call
     * @hide
     */
    public static final int IMS_TRAFFIC_TYPE_VIDEO = 3;
    /**
     * SMS over IMS
     * @hide
     */
    public static final int IMS_TRAFFIC_TYPE_SMS = 4;
    /**
     * IMS registration and subscription for reg event package (signaling)
     * @hide
     */
    public static final int IMS_TRAFFIC_TYPE_REGISTRATION = 5;
    /**
     * Ut/XCAP (XML Configuration Access Protocol)
     * @hide
     */
    public static final int IMS_TRAFFIC_TYPE_UT_XCAP = 6;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = { "IMS_TRAFFIC_DIRECTION_" },
            value = {IMS_TRAFFIC_DIRECTION_INCOMING, IMS_TRAFFIC_DIRECTION_OUTGOING})
    public @interface ImsTrafficDirection {}

    /**
     * Indicates that the traffic is an incoming traffic.
     * @hide
     */
    public static final int IMS_TRAFFIC_DIRECTION_INCOMING = 0;
    /**
     * Indicates that the traffic is an outgoing traffic.
     * @hide
     */
    public static final int IMS_TRAFFIC_DIRECTION_OUTGOING = 1;

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
     * Notify the framework that the measured media quality has crossed a threshold set by {@link
     * MmTelFeature#setMediaThreshold}
     *
     * @param status current media quality status measured.
     * @hide
     */
    @SystemApi
    public final void notifyMediaQualityStatusChanged(
            @NonNull MediaQualityStatus status) {
        if (status == null) {
            throw new IllegalArgumentException(
                    "MediaQualityStatus must be non-null!");
        }
        Log.i(LOG_TAG, "notifyMediaQualityStatusChanged " + status);
        IImsMmTelListener listener = getListener();
        if (listener == null) {
            throw new IllegalStateException("Session is not available.");
        }
        try {
            listener.onMediaQualityStatusChanged(status);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Notify the framework of an incoming call.
     * @param c The {@link ImsCallSessionImplBase} of the new incoming call.
     * @param extras A bundle containing extra parameters related to the call. See
     * {@link #EXTRA_IS_UNKNOWN_CALL} and {@link #EXTRA_IS_USSD} above.
     * @hide
     *
     * @deprecated use {@link #notifyIncomingCall(ImsCallSessionImplBase, String, Bundle)} instead
     */
    @Deprecated
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
            c.setDefaultExecutor(MmTelFeature.this.mExecutor);
            listener.onIncomingCall(c.getServiceImpl(), null, extras);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Notify the framework of an incoming call.
     * @param c The {@link ImsCallSessionImplBase} of the new incoming call.
     * @param callId The call ID of the session of the new incoming call.
     * @param extras A bundle containing extra parameters related to the call. See
     * {@link #EXTRA_IS_UNKNOWN_CALL} and {@link #EXTRA_IS_USSD} above.
     * @return The listener used by the framework to listen to call session events created
     *         from the ImsService.
     *         If this method returns {@code null}, then the call could not be placed.
     * @hide
     */
    @SystemApi
    @Nullable
    public final ImsCallSessionListener notifyIncomingCall(
            @NonNull ImsCallSessionImplBase c, @NonNull String callId, @NonNull Bundle extras) {
        if (c == null || callId == null || extras == null) {
            throw new IllegalArgumentException("ImsCallSessionImplBase, callId, and Bundle can "
                    + "not be null.");
        }
        IImsMmTelListener listener = getListener();
        if (listener == null) {
            throw new IllegalStateException("Session is not available.");
        }
        try {
            c.setDefaultExecutor(MmTelFeature.this.mExecutor);
            IImsCallSessionListener isl =
                    listener.onIncomingCall(c.getServiceImpl(), callId, extras);
            if (isl != null) {
                ImsCallSessionListener iCSL = new ImsCallSessionListener(isl);
                iCSL.setDefaultExecutor(MmTelFeature.this.mExecutor);
                return iCSL;
            } else {
                return null;
            }
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
            listener.onIncomingCall(c, null, extras);
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
     * Sets the audio handler for this connection. The vendor IMS stack will invoke this API
     * to inform Telephony/Telecom layers about which audio handlers i.e. either Android or Modem
     * shall be used for handling the IMS call audio.
     *
     * @param imsAudioHandler {@link MmTelFeature#ImsAudioHandler} used to handle the audio
     *        for this IMS call.
     * @hide
     */
    @SystemApi
    public final void setCallAudioHandler(@ImsAudioHandler int imsAudioHandler) {
        IImsMmTelListener listener = getListener();
        if (listener == null) {
            throw new IllegalStateException("Session is not available.");
        }
        try {
            listener.onAudioModeIsVoipChanged(imsAudioHandler);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Triggers the EPS fallback procedure.
     *
     * @param reason specifies the reason that causes EPS fallback.
     * @hide
     */
    public final void triggerEpsFallback(@EpsFallbackReason int reason) {
        IImsMmTelListener listener = getListener();
        if (listener == null) {
            throw new IllegalStateException("Session is not available.");
        }
        try {
            listener.onTriggerEpsFallback(reason);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Starts a new IMS traffic session with the framework.
     *
     * This API notifies the NAS and RRC layers of the modem that IMS traffic of type
     * {@link ImsTrafficType} is starting for the IMS session represented by a
     * {@link ImsTrafficSessionCallback}. The {@link ImsTrafficSessionCallback}
     * will notify the caller when IMS traffic is ready to start via the
     * {@link ImsTrafficSessionCallback#onReady()} callback. If there was an error starting
     * IMS traffic for the specified traffic type, {@link ImsTrafficSessionCallback#onError()} will
     * be called, which will also notify the caller of the reason of the failure.
     *
     * If there is a handover that changes the {@link AccessNetworkConstants#RadioAccessNetworkType}
     * of this IMS traffic session, then {@link #modifyImsTrafficSession} should be called. This is
     * used, for example, when a WiFi <-> cellular handover occurs.
     *
     * Once the IMS traffic session is finished, {@link #stopImsTrafficSession} must be called.
     *
     * Note: This API will be used to prioritize RF resources in case of DSDS. The service priority
     * is EMERGENCY > EMERGENCY SMS > VOICE > VIDEO > SMS > REGISTRATION > Ut/XCAP. RF
     * shall be prioritized to the subscription which handles the higher priority service.
     * When both subscriptions are handling the same type of service, then RF shall be
     * prioritized to the voice preferred sub.
     *
     * @param trafficType The {@link ImsTrafficType} type for IMS traffic.
     * @param accessNetworkType The {@link AccessNetworkConstants#RadioAccessNetworkType} type of
     *        the radio access network.
     * @param trafficDirection Indicates whether traffic is originated by mobile originated or
     *        mobile terminated use case eg. MO/MT call/SMS etc.
     * @param executor The Executor that will be used to call the {@link ImsTrafficSessionCallback}.
     * @param callback The session representing the IMS Session associated with a specific
     *        trafficType. This callback instance should only be used for the specified traffic type
     *        until {@link #stopImsTrafficSession} is called.
     *
     * @see modifyImsTrafficSession
     * @see stopImsTrafficSession
     *
     * @hide
     */
    public final void startImsTrafficSession(@ImsTrafficType int trafficType,
            @AccessNetworkConstants.RadioAccessNetworkType int accessNetworkType,
            @ImsTrafficDirection int trafficDirection,
            @NonNull Executor executor, @NonNull ImsTrafficSessionCallback callback) {
        IImsMmTelListener listener = getListener();
        if (listener == null) {
            throw new IllegalStateException("Session is not available.");
        }
        // TODO: retrieve from the callback list
        ImsTrafficSessionCallbackWrapper callbackWrapper = mTrafficCallbacks.get(callback);
        if (callbackWrapper == null) {
            callbackWrapper = new ImsTrafficSessionCallbackWrapper(callback);
            mTrafficCallbacks.put(callback, callbackWrapper);
        }
        try {
            callbackWrapper.update(executor);
            listener.onStartImsTrafficSession(callbackWrapper.getToken(),
                    trafficType, accessNetworkType, trafficDirection,
                    callbackWrapper.getCallbackBinder());
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Modifies an existing IMS traffic session represented by the associated
     * {@link ImsTrafficSessionCallback}.
     *
     * The {@link ImsTrafficSessionCallback} will notify the caller when IMS traffic is ready to
     * start after modification using the {@link ImsTrafficSessionCallback#onReady()} callback.
     * If there was an error modifying IMS traffic for the new radio access network type type,
     * {@link ImsTrafficSessionCallback#onError()} will be called, which will also notify the
     * caller of the reason of the failure.
     *
     * @param accessNetworkType The {@link AccessNetworkConstants#RadioAccessNetworkType} type of
     *        the radio access network.
     * @param callback The callback registered with {@link #startImsTrafficSession}.
     *
     * @see startImsTrafficSession
     * @see stopImsTrafficSession
     *
     * @hide
     */
    public final void modifyImsTrafficSession(
            @AccessNetworkConstants.RadioAccessNetworkType int accessNetworkType,
            @NonNull ImsTrafficSessionCallback callback) {
        IImsMmTelListener listener = getListener();
        if (listener == null) {
            throw new IllegalStateException("Session is not available.");
        }
        ImsTrafficSessionCallbackWrapper callbackWrapper = mTrafficCallbacks.get(callback);
        if (callbackWrapper == null) {
            // should not reach here.
            throw new IllegalStateException("Unknown ImsTrafficSessionCallback instance.");
        }
        try {
            listener.onModifyImsTrafficSession(callbackWrapper.getToken(), accessNetworkType);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Notifies the framework that the IMS traffic session represented by the associated
     * {@link ImsTrafficSessionCallback} has ended.
     *
     * @param callback The callback registered with {@link #startImsTrafficSession}.
     *
     * @see startImsTrafficSession
     * @see modifyImsTrafficSession
     *
     * @hide
     */
    public final void stopImsTrafficSession(@NonNull ImsTrafficSessionCallback callback) {
        IImsMmTelListener listener = getListener();
        if (listener == null) {
            throw new IllegalStateException("Session is not available.");
        }
        ImsTrafficSessionCallbackWrapper callbackWrapper = mTrafficCallbacks.get(callback);
        if (callbackWrapper == null) {
            // should not reach here.
            throw new IllegalStateException("Unknown ImsTrafficSessionCallback instance.");
        }
        try {
            listener.onStopImsTrafficSession(callbackWrapper.getToken());
            callbackWrapper.reset();
            mTrafficCallbacks.remove(callback);
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
     * @hide
     */
    @Override
    @SystemApi
    public void changeEnabledCapabilities(@NonNull CapabilityChangeRequest request,
            @NonNull CapabilityCallbackProxy c) {
        // Base implementation, no-op
    }

    /**
     * Called by the framework to pass {@link MediaThreshold}. The MmTelFeature should override this
     * method to get Media quality threshold. This will pass the consolidated threshold values from
     * Telephony framework. IMS provider needs to monitor media quality of active call and notify
     * media quality {@link #notifyMediaQualityStatusChanged(MediaQualityStatus)} when the measured
     * media quality crosses at least one of {@link MediaThreshold} set by this.
     *
     * @param mediaSessionType media session type for this Threshold info.
     * @param mediaThreshold media threshold information
     * @hide
     */
    @SystemApi
    public void setMediaThreshold(
            @MediaQualityStatus.MediaSessionType int mediaSessionType,
            @NonNull MediaThreshold mediaThreshold) {
        // Base Implementation - Should be overridden.
        Log.d(LOG_TAG, "setMediaThreshold is not supported." + mediaThreshold);
    }

    /**
     * The MmTelFeature should override this method to clear Media quality thresholds that were
     * registered and stop media quality status updates.
     *
     * @param mediaSessionType media session type
     * @hide
     */
    @SystemApi
    public void clearMediaThreshold(@MediaQualityStatus.MediaSessionType int mediaSessionType) {
        // Base Implementation - Should be overridden.
        Log.d(LOG_TAG, "clearMediaThreshold is not supported." + mediaSessionType);
    }

    /**
     * IMS provider should override this method to return currently measured media quality status.
     *
     * <p/>
     * If media quality status is not yet measured after call is active, it needs to notify media
     * quality status {@link #notifyMediaQualityStatusChanged(MediaQualityStatus)} when the first
     * measurement is done.
     *
     * @param mediaSessionType media session type
     * @return Current media quality status. It could be null if media quality status is not
     *         measured yet or {@link MediaThreshold} was not set corresponding to the media session
     *         type.
     *
     * @hide
     */
    @SystemApi
    @Nullable
    public MediaQualityStatus queryMediaQualityStatus(
            @MediaQualityStatus.MediaSessionType int mediaSessionType) {
        // Base Implementation - Should be overridden.
        Log.d(LOG_TAG, "queryMediaQualityStatus is not supported." + mediaSessionType);
        return null;
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
        if (s != null) {
            s.setDefaultExecutor(mExecutor);
            return s.getServiceImpl();
        } else {
            return null;
        }
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
        if (utImpl != null) {
            utImpl.setDefaultExecutor(mExecutor);
            return utImpl.getInterface();
        } else {
            return null;
        }
    }

    /**
     * @hide
     */
    protected IImsEcbm getEcbmInterface() throws RemoteException {
        ImsEcbmImplBase ecbmImpl = getEcbm();
        if (ecbmImpl != null) {
            ecbmImpl.setDefaultExecutor(mExecutor);
            return ecbmImpl.getImsEcbm();
        } else {
            return null;
        }
    }

    /**
     * @hide
     */
    public IImsMultiEndpoint getMultiEndpointInterface() throws RemoteException {
        ImsMultiEndpointImplBase multiendpointImpl = getMultiEndpoint();
        if (multiendpointImpl != null) {
            multiendpointImpl.setDefaultExecutor(mExecutor);
            return multiendpointImpl.getIImsMultiEndpoint();
        } else {
            return null;
        }
    }

    /**
     * @hide
     */
    public @NonNull ImsSmsImplBase getImsSmsImpl() {
        synchronized (mLock) {
            if (mSmsImpl == null) {
                mSmsImpl = getSmsImplementation();
                mSmsImpl.setDefaultExecutor(mExecutor);
            }
            return mSmsImpl;
        }
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

    /**
     * Notifies the MmTelFeature of the enablement status of terminal based call waiting
     *
     * If the terminal based call waiting is provisioned,
     * IMS controls the enablement of terminal based call waiting which is defined
     * in 3GPP TS 24.615.
     *
     * @param enabled user setting controlling whether or not call waiting is enabled.
     *
     * @hide
     */
    @SystemApi
    public void setTerminalBasedCallWaitingStatus(boolean enabled) {
        // Base Implementation - Should be overridden by IMS service
        throw new ServiceSpecificException(ImsException.CODE_ERROR_UNSUPPORTED_OPERATION,
                "Not implemented on device.");
    }

    /**
     * Notifies the MmTelFeature that the network has initiated an SRVCC (Single radio voice
     * call continuity) for all IMS calls. When the network initiates an SRVCC, calls from
     * the LTE domain are handed over to the legacy circuit switched domain. The modem requires
     * knowledge of ongoing calls in the IMS domain in order to complete the SRVCC operation.
     * <p>
     * @param consumer The callback used to notify the framework of the list of IMS calls and their
     * state at the time of the SRVCC.
     *
     * @hide
     */
    @SystemApi
    public void notifySrvccStarted(@NonNull Consumer<List<SrvccCall>> consumer) {
        // Base Implementation - Should be overridden by IMS service
    }

    /**
     * Notifies the MmTelFeature that the SRVCC is completed and the calls have been moved
     * over to the circuit-switched domain.
     * {@link android.telephony.CarrierConfigManager.ImsVoice#KEY_SRVCC_TYPE_INT_ARRAY}
     * specifies the calls can be moved. Other calls will be disconnected.
     * <p>
     * The MmTelFeature may now release all resources related to the IMS calls.
     *
     * @hide
     */
    @SystemApi
    public void notifySrvccCompleted() {
        // Base Implementation - Should be overridden by IMS service
    }

    /**
     * Notifies the MmTelFeature that the SRVCC has failed.
     *
     * The handover can fail by encountering a failure at the radio level
     * or temporary MSC server internal errors in handover procedure.
     * Refer to 3GPP TS 23.216 section 8 Handover Failure.
     * <p>
     * IMS service will recover and continue calls over IMS.
     * Per TS 24.237 12.2.4.2, UE shall send SIP UPDATE request containing the reason-text
     * set to "failure to transition to CS domain".
     *
     * @hide
     */
    @SystemApi
    public void notifySrvccFailed() {
        // Base Implementation - Should be overridden by IMS service
    }

    /**
     * Notifies the MmTelFeature that the SRVCC has been canceled.
     *
     * Since the state of network can be changed, the network can decide to terminate
     * the handover procedure before its completion and to return to its state before the handover
     * procedure was triggered.
     * Refer to 3GPP TS 23.216 section 8.1.3 Handover Cancellation.
     *
     * <p>
     * IMS service will recover and continue calls over IMS.
     * Per TS 24.237 12.2.4.2, UE shall send SIP UPDATE request containing the reason-text
     * set to "handover canceled".
     *
     * @hide
     */
    @SystemApi
    public void notifySrvccCanceled() {
        // Base Implementation - Should be overridden by IMS service
    }

    private void setSmsListener(IImsSmsListener listener) {
        getImsSmsImpl().registerSmsListener(listener);
    }

    private void sendSms(int token, int messageRef, String format, String smsc, boolean isRetry,
            byte[] pdu) {
        getImsSmsImpl().sendSms(token, messageRef, format, smsc, isRetry, pdu);
    }

    private void onMemoryAvailable(int token) {
        getImsSmsImpl().onMemoryAvailable(token);
    }

    private void acknowledgeSms(int token, int messageRef,
            @ImsSmsImplBase.DeliverStatusResult int result) {
        getImsSmsImpl().acknowledgeSms(token, messageRef, result);
    }

    private void acknowledgeSms(int token, int messageRef,
            @ImsSmsImplBase.DeliverStatusResult int result, byte[] pdu) {
        getImsSmsImpl().acknowledgeSms(token, messageRef, result, pdu);
    }

    private void acknowledgeSmsReport(int token, int messageRef,
            @ImsSmsImplBase.StatusReportResult int result) {
        getImsSmsImpl().acknowledgeSmsReport(token, messageRef, result);
    }

    private void onSmsReady() {
        getImsSmsImpl().onReady();
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
        return getImsSmsImpl().getSmsFormat();
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

    /**
     * Set default Executor from ImsService.
     * @param executor The default executor for the framework to use when executing the methods
     * overridden by the implementation of MmTelFeature.
     * @hide
     */
    public final void setDefaultExecutor(@NonNull Executor executor) {
        if (mExecutor == null) {
            mExecutor = executor;
        }
    }
}
