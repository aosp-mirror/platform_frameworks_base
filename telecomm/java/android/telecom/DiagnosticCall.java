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

package android.telecom;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.telephony.Annotation;
import android.telephony.CallQuality;
import android.telephony.ims.ImsReasonInfo;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A {@link DiagnosticCall} provides a way for a {@link CallDiagnosticService} to receive diagnostic
 * information about a mobile call on the device.  The {@link CallDiagnosticService} can generate
 * mid-call diagnostic messages using the {@link #displayDiagnosticMessage(int, CharSequence)} API
 * which provides the user with valuable information about conditions impacting their call and
 * corrective actions.  For example, if the {@link CallDiagnosticService} determines that conditions
 * on the call are degrading, it can inform the user that the call may soon drop and that they
 * can try using a different calling method (e.g. VOIP or WIFI).
 * @hide
 */
@SystemApi
public abstract class DiagnosticCall {

    /**
     * @hide
     */
    public interface Listener {
        void onSendDeviceToDeviceMessage(DiagnosticCall diagnosticCall, int message, int value);
        void onDisplayDiagnosticMessage(DiagnosticCall diagnosticCall, int messageId,
                CharSequence message);
        void onClearDiagnosticMessage(DiagnosticCall diagnosticCall, int messageId);
    }

    /**
     * Device to device message sent via {@link #sendDeviceToDeviceMessage(int, int)} (received via
     * {@link #onReceiveDeviceToDeviceMessage(int, int)}) which communicates the radio access type
     * used for the current call.  Based loosely on the
     * {@link android.telephony.TelephonyManager#getNetworkType(int)} for the call, provides a
     * high level summary of the call radio access type.
     * <p>
     * Valid values:
     * <UL>
     *     <LI>{@link #NETWORK_TYPE_LTE}</LI>
     *     <LI>{@link #NETWORK_TYPE_IWLAN}</LI>
     *     <LI>{@link #NETWORK_TYPE_NR}</LI>
     * </UL>
     */
    public static final int MESSAGE_CALL_NETWORK_TYPE = 1;

    /**
     * Device to device message sent via {@link #sendDeviceToDeviceMessage(int, int)} (received via
     * {@link #onReceiveDeviceToDeviceMessage(int, int)}) which communicates the call audio codec
     * used for the current call.  Based loosely on the {@link Connection#EXTRA_AUDIO_CODEC} for a
     * call.
     * <p>
     * Valid values:
     * <UL>
     *     <LI>{@link #AUDIO_CODEC_EVS}</LI>
     *     <LI>{@link #AUDIO_CODEC_AMR_WB}</LI>
     *     <LI>{@link #AUDIO_CODEC_AMR_NB}</LI>
     * </UL>
     */
    public static final int MESSAGE_CALL_AUDIO_CODEC = 2;

    /**
     * Device to device message sent via {@link #sendDeviceToDeviceMessage(int, int)} (received via
     * {@link #onReceiveDeviceToDeviceMessage(int, int)}) which communicates the battery state of
     * the device.  Will typically mirror battery state reported via intents such as
     * {@link android.content.Intent#ACTION_BATTERY_LOW}.
     * <p>
     * Valid values:
     * <UL>
     *     <LI>{@link #BATTERY_STATE_LOW}</LI>
     *     <LI>{@link #BATTERY_STATE_GOOD}</LI>
     *     <LI>{@link #BATTERY_STATE_CHARGING}</LI>
     * </UL>
     */
    public static final int MESSAGE_DEVICE_BATTERY_STATE = 3;

    /**
     * Device to device message sent via {@link #sendDeviceToDeviceMessage(int, int)} (received via
     * {@link #onReceiveDeviceToDeviceMessage(int, int)}) which communicates the overall network
     * coverage as it pertains to the current call.  A {@link CallDiagnosticService} should signal
     * poor coverage if the network coverage reaches a level where there is a high probability of
     * the call dropping as a result.
     * <p>
     * Valid values:
     * <UL>
     *     <LI>{@link #COVERAGE_POOR}</LI>
     *     <LI>{@link #COVERAGE_GOOD}</LI>
     * </UL>
     */
    public static final int MESSAGE_DEVICE_NETWORK_COVERAGE = 4;

    /**@hide*/
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "MESSAGE_", value = {
            MESSAGE_CALL_NETWORK_TYPE,
            MESSAGE_CALL_AUDIO_CODEC,
            MESSAGE_DEVICE_BATTERY_STATE,
            MESSAGE_DEVICE_NETWORK_COVERAGE
    })
    public @interface MessageType {}

    /**
     * Used with {@link #MESSAGE_CALL_NETWORK_TYPE} to indicate an LTE network is being used for the
     * call.
     */
    public static final int NETWORK_TYPE_LTE = 1;

    /**
     * Used with {@link #MESSAGE_CALL_NETWORK_TYPE} to indicate WIFI calling is in use for the call.
     */
    public static final int NETWORK_TYPE_IWLAN = 2;

    /**
     * Used with {@link #MESSAGE_CALL_NETWORK_TYPE} to indicate a 5G NR (new radio) network is in
     * used for the call.
     */
    public static final int NETWORK_TYPE_NR = 3;

    /**
     * Used with {@link #MESSAGE_CALL_AUDIO_CODEC} to indicate call audio is using the
     * Enhanced Voice Services (EVS) codec for the call.
     */
    public static final int AUDIO_CODEC_EVS = 1;

    /**
     * Used with {@link #MESSAGE_CALL_AUDIO_CODEC} to indicate call audio is using the AMR
     * (adaptive multi-rate) WB (wide band) audio codec.
     */
    public static final int AUDIO_CODEC_AMR_WB = 2;

    /**
     * Used with {@link #MESSAGE_CALL_AUDIO_CODEC} to indicate call audio is using the AMR
     * (adaptive multi-rate) NB (narrow band) audio codec.
     */
    public static final int AUDIO_CODEC_AMR_NB = 3;

    /**
     * Used with {@link #MESSAGE_DEVICE_BATTERY_STATE} to indicate that the battery is low.
     */
    public static final int BATTERY_STATE_LOW = 1;

    /**
     * Used with {@link #MESSAGE_DEVICE_BATTERY_STATE} to indicate that the battery is not low.
     */
    public static final int BATTERY_STATE_GOOD = 2;

    /**
     * Used with {@link #MESSAGE_DEVICE_BATTERY_STATE} to indicate that the battery is charging.
     */
    public static final int BATTERY_STATE_CHARGING = 3;

    /**
     * Used with {@link #MESSAGE_DEVICE_NETWORK_COVERAGE} to indicate that the coverage is poor.
     */
    public static final int COVERAGE_POOR = 1;

    /**
     * Used with {@link #MESSAGE_DEVICE_NETWORK_COVERAGE} to indicate that the coverage is good.
     */
    public static final int COVERAGE_GOOD = 2;

    private Listener mListener;
    private String mCallId;
    private Call.Details mCallDetails;

    /**
     * @hide
     */
    public void setListener(@NonNull Listener listener) {
        mListener = listener;
    }

    /**
     * Sets the call ID for this {@link DiagnosticCall}.
     * @param callId
     * @hide
     */
    public void setCallId(@NonNull String callId) {
        mCallId = callId;
    }

    /**
     * @return the Telecom call ID for this {@link DiagnosticCall}.
     * @hide
     */
    public @NonNull String getCallId() {
        return mCallId;
    }

    /**
     * Returns the latest {@link Call.Details} associated with this {@link DiagnosticCall} as
     * reported by {@link #onCallDetailsChanged(Call.Details)}.
     * @return The latest {@link Call.Details}.
     */
    public @NonNull Call.Details getCallDetails() {
        return mCallDetails;
    }

    /**
     * Telecom calls this method when the details of a call changes.
     */
    public abstract void onCallDetailsChanged(@NonNull android.telecom.Call.Details details);

    /**
     * The {@link CallDiagnosticService} implements this method to handle messages received via
     * device to device communication.
     * <p>
     * See {@link #sendDeviceToDeviceMessage(int, int)} for background on device to device
     * communication.
     * <p>
     * The underlying device to device communication protocol assumes that where there the two
     * devices communicating are using a different version of the protocol, messages the recipient
     * are not aware of are silently discarded.  This means an older client talking to a new client
     * will not receive newer messages and values sent by the new client.
     */
    public abstract void onReceiveDeviceToDeviceMessage(
            @MessageType int message,
            int value);

    /**
     * Sends a device to device message to the device on the other end of this call.
     * <p>
     * Device to device communication is an Android platform feature which supports low bandwidth
     * communication between Android devices while they are in a call.  The device to device
     * communication leverages DTMF tones or RTP header extensions to pass messages.  The
     * messages are unacknowledged and sent in a best-effort manner.  The protocols assume that the
     * nature of the message are informational only and are used only to convey basic state
     * information between devices.
     * <p>
     * Device to device messages are intentional simplifications of more rich indicators in the
     * platform due to the extreme bandwidth constraints inherent with underlying device to device
     * communication transports used by the telephony framework.  Device to device communication is
     * either accomplished by adding RFC8285 compliant RTP header extensions to the audio packets
     * for a call, or using the DTMF digits A-D as a communication pathway.  Signalling requirements
     * for DTMF digits place a significant limitation on the amount of information which can be
     * communicated during a call.
     * <p>
     * Allowed message types and values are:
     * <ul>
     *     <li>{@link #MESSAGE_CALL_NETWORK_TYPE}
     *         <ul>
     *             <li>{@link #NETWORK_TYPE_LTE}</li>
     *             <li>{@link #NETWORK_TYPE_IWLAN}</li>
     *             <li>{@link #NETWORK_TYPE_NR}</li>
     *         </ul>
     *     </li>
     *     <li>{@link #MESSAGE_CALL_AUDIO_CODEC}
     *         <ul>
     *             <li>{@link #AUDIO_CODEC_EVS}</li>
     *             <li>{@link #AUDIO_CODEC_AMR_WB}</li>
     *             <li>{@link #AUDIO_CODEC_AMR_NB}</li>
     *         </ul>
     *     </li>
     *     <li>{@link #MESSAGE_DEVICE_BATTERY_STATE}
     *         <ul>
     *             <li>{@link #BATTERY_STATE_LOW}</li>
     *             <li>{@link #BATTERY_STATE_GOOD}</li>
     *             <li>{@link #BATTERY_STATE_CHARGING}</li>
     *         </ul>
     *     </li>
     *     <li>{@link #MESSAGE_DEVICE_NETWORK_COVERAGE}
     *         <ul>
     *             <li>{@link #COVERAGE_POOR}</li>
     *             <li>{@link #COVERAGE_GOOD}</li>
     *         </ul>
     *     </li>
     * </ul>
     * @param message The message type to send.
     * @param value The message value corresponding to the type.
     */
    public final void sendDeviceToDeviceMessage(int message, int value) {
        if (mListener != null) {
            mListener.onSendDeviceToDeviceMessage(this, message, value);
        }
    }

    /**
     * Telecom calls this method when a GSM or CDMA call disconnects.
     * The CallDiagnosticService can return a human readable disconnect message which will be passed
     * to the Dialer app as the {@link DisconnectCause#getDescription()}.  A dialer app typically
     * shows this message at the termination of the call.  If {@code null} is returned, the
     * disconnect message generated by the telephony stack will be shown instead.
     * <p>
     * @param disconnectCause the disconnect cause for the call.
     * @param preciseDisconnectCause the precise disconnect cause for the call.
     * @return the disconnect message to use in place of the default Telephony message, or
     * {@code null} if the default message will not be overridden.
     */
    // TODO: Wire in Telephony support for this.
    public abstract @Nullable CharSequence onCallDisconnected(
            @Annotation.DisconnectCauses int disconnectCause,
            @Annotation.PreciseDisconnectCauses int preciseDisconnectCause);

    /**
     * Telecom calls this method when an IMS call disconnects and Telephony has already
     * provided the disconnect reason info and disconnect message for the call.  The
     * {@link CallDiagnosticService} can intercept the raw IMS disconnect reason at this point and
     * combine it with other call diagnostic information it is aware of to override the disconnect
     * call message if desired.
     *
     * @param disconnectReason The {@link ImsReasonInfo} associated with the call disconnection.
     * @return A user-readable call disconnect message to use in place of the platform-generated
     * disconnect message, or {@code null} if the disconnect message should not be overridden.
     */
    // TODO: Wire in Telephony support for this.
    public abstract @Nullable CharSequence onCallDisconnected(
            @NonNull ImsReasonInfo disconnectReason);

    /**
     * Telecom calls this method when a {@link CallQuality} report is received from the telephony
     * stack for a call.
     * @param callQuality The call quality report for this call.
     */
    public abstract void onCallQualityReceived(@NonNull CallQuality callQuality);

     /**
      * Signals the active default dialer app to display a call diagnostic message.  This can be
      * used to report problems encountered during the span of a call.
      * <p>
      * The {@link CallDiagnosticService} provides a unique client-specific identifier used to
      * identify the specific diagnostic message type.
      * <p>
      * The {@link CallDiagnosticService} should call {@link #clearDiagnosticMessage(int)} when the
      * diagnostic condition has cleared.
      * @param messageId the unique message identifier.
      * @param message a human-readable, localized message to be shown to the user indicating a
      *                call issue which has occurred, along with potential mitigating actions.
     */
    public final void displayDiagnosticMessage(int messageId, @NonNull
            CharSequence message) {
        if (mListener != null) {
            mListener.onDisplayDiagnosticMessage(this, messageId, message);
        }
    }

    /**
     * Signals to the active default dialer that the diagnostic message previously signalled using
     * {@link #displayDiagnosticMessage(int, CharSequence)} with the specified messageId is no
     * longer applicable (e.g. service has improved, for example.
     * @param messageId the message identifier for a message previously shown via
     *                  {@link #displayDiagnosticMessage(int, CharSequence)}.
     */
    public final void clearDiagnosticMessage(int messageId) {
        if (mListener != null) {
            mListener.onClearDiagnosticMessage(this, messageId);
        }
    }

    /**
     * Called by the {@link CallDiagnosticService} to update the call details for this
     * {@link DiagnosticCall} based on an update received from Telecom.
     * @param newDetails the new call details.
     * @hide
     */
    public void handleCallUpdated(@NonNull Call.Details newDetails) {
        mCallDetails = newDetails;
        onCallDetailsChanged(newDetails);
    }
}
