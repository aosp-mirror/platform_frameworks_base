/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.service.carrier;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;
import android.os.RemoteException;

import com.android.internal.telephony.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

/**
 * A service that receives calls from the system when new SMS and MMS are
 * sent or received.
 * <p>To extend this class, you must declare the service in your manifest file with
 * the {@link android.Manifest.permission#BIND_CARRIER_SERVICES} permission
 * and include an intent filter with the {@link #SERVICE_INTERFACE} action. For example:</p>
 * <pre>
 * &lt;service android:name=".MyMessagingService"
 *          android:label="&#64;string/service_name"
 *          android:permission="android.permission.BIND_CARRIER_SERVICES">
 *     &lt;intent-filter>
 *         &lt;action android:name="android.service.carrier.CarrierMessagingService" />
 *     &lt;/intent-filter>
 * &lt;/service></pre>
 */
public abstract class CarrierMessagingService extends Service {
    /**
     * The {@link android.content.Intent} that must be declared as handled by the service.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE
            = "android.service.carrier.CarrierMessagingService";

    /**
     * The default bitmask value passed to the callback of {@link #onReceiveTextSms} with all
     * {@code RECEIVE_OPTIONS_x} flags cleared to indicate that the message should be kept and a
     * new message notification should be shown.
     *
     * @see #RECEIVE_OPTIONS_DROP
     * @see #RECEIVE_OPTIONS_SKIP_NOTIFY_WHEN_CREDENTIAL_PROTECTED_STORAGE_UNAVAILABLE
     */
    public static final int RECEIVE_OPTIONS_DEFAULT = 0;

    /**
     * Used to set the flag in the bitmask passed to the callback of {@link #onReceiveTextSms} to
     * indicate that the inbound SMS should be dropped.
     */
    public static final int RECEIVE_OPTIONS_DROP = 0x1;

    /**
     * Used to set the flag in the bitmask passed to the callback of {@link #onReceiveTextSms} to
     * indicate that a new message notification should not be shown to the user when the
     * credential-encrypted storage of the device is not available before the user unlocks the
     * phone. It is only applicable to devices that support file-based encryption.
     */
    public static final int RECEIVE_OPTIONS_SKIP_NOTIFY_WHEN_CREDENTIAL_PROTECTED_STORAGE_UNAVAILABLE = 0x2;

    /** @hide */
    @IntDef(flag = true, prefix = { "RECEIVE_OPTIONS_" }, value = {
            RECEIVE_OPTIONS_DEFAULT,
            RECEIVE_OPTIONS_DROP,
            RECEIVE_OPTIONS_SKIP_NOTIFY_WHEN_CREDENTIAL_PROTECTED_STORAGE_UNAVAILABLE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface FilterCompleteResult{}

    /**
     * Indicates that an SMS or MMS message was successfully sent.
     */
    public static final int SEND_STATUS_OK = 0;

    /**
     * SMS/MMS sending failed. We should retry via the carrier network.
     */
    public static final int SEND_STATUS_RETRY_ON_CARRIER_NETWORK = 1;

    /**
     * SMS/MMS sending failed due to an unspecified issue. Sending will not be retried via the
     * carrier network.
     *
     * <p>Maps to SmsManager.RESULT_RIL_GENERIC_FAILURE for SMS and SmsManager.MMS_ERROR_UNSPECIFIED
     * for MMS.
     */
    public static final int SEND_STATUS_ERROR = 2;

    /**
     * More precise error reasons for outbound SMS send requests. These will not be retried on the
     * carrier network.
     *
     * <p>Each code maps directly to an SmsManager code (e.g. SEND_STATS_RESULT_ERROR_NULL_PDU maps
     * to SmsManager.RESULT_ERROR_NULL_PDU).
     */

    /**
     * Generic failure cause.
     *
     * @see android.telephony.SmsManager.RESULT_ERROR_GENERIC_FAILURE
     */
    @FlaggedApi(Flags.FLAG_TEMPORARY_FAILURES_IN_CARRIER_MESSAGING_SERVICE)
    public static final int SEND_STATUS_RESULT_ERROR_GENERIC_FAILURE = 200;

    /**
     * Failed because no pdu provided.
     *
     * @see android.telephony.SmsManager.RESULT_ERROR_NULL_PDU
     */
    @FlaggedApi(Flags.FLAG_TEMPORARY_FAILURES_IN_CARRIER_MESSAGING_SERVICE)
    public static final int SEND_STATUS_RESULT_ERROR_NULL_PDU = 201;

    /**
     * Failed because service is currently unavailable.
     *
     * @see android.telephony.SmsManager.RESULT_ERROR_NO_SERVICE
     */
    @FlaggedApi(Flags.FLAG_TEMPORARY_FAILURES_IN_CARRIER_MESSAGING_SERVICE)
    public static final int SEND_STATUS_RESULT_ERROR_NO_SERVICE = 202;

    /**
     * Failed because we reached the sending queue limit.
     *
     * @see android.telephony.SmsManager.RESULT_ERROR_LIMIT_EXCEEDED
     */
    @FlaggedApi(Flags.FLAG_TEMPORARY_FAILURES_IN_CARRIER_MESSAGING_SERVICE)
    public static final int SEND_STATUS_RESULT_ERROR_LIMIT_EXCEEDED = 203;

    /**
     * Failed because FDN is enabled.
     *
     * @see android.telephony.SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE
     */
    @FlaggedApi(Flags.FLAG_TEMPORARY_FAILURES_IN_CARRIER_MESSAGING_SERVICE)
    public static final int SEND_STATUS_RESULT_ERROR_FDN_CHECK_FAILURE = 204;

    /**
     * Failed because user denied the sending of this short code.
     *
     * @see android.telephony.SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED
     */
    @FlaggedApi(Flags.FLAG_TEMPORARY_FAILURES_IN_CARRIER_MESSAGING_SERVICE)
    public static final int SEND_STATUS_RESULT_ERROR_SHORT_CODE_NOT_ALLOWED = 205;

    /**
     * Failed because the user has denied this app ever send premium short codes.
     *
     * @see android.telephony.SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED
     */
    @FlaggedApi(Flags.FLAG_TEMPORARY_FAILURES_IN_CARRIER_MESSAGING_SERVICE)
    public static final int SEND_STATUS_RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED = 206;

    /**
     * Failed because of network rejection.
     *
     * @see android.telephony.SmsManager.RESULT_NETWORK_REJECT
     */
    @FlaggedApi(Flags.FLAG_TEMPORARY_FAILURES_IN_CARRIER_MESSAGING_SERVICE)
    public static final int SEND_STATUS_RESULT_NETWORK_REJECT = 207;

    /**
     * Failed because of invalid arguments.
     *
     * @see android.telephony.SmsManager.RESULT_INVALID_ARGUMENTS
     */
    @FlaggedApi(Flags.FLAG_TEMPORARY_FAILURES_IN_CARRIER_MESSAGING_SERVICE)
    public static final int SEND_STATUS_RESULT_INVALID_ARGUMENTS = 208;

    /**
     * Failed because of an invalid state.
     *
     * @see android.telephony.SmsManager.RESULT_INVALID_STATE
     */
    @FlaggedApi(Flags.FLAG_TEMPORARY_FAILURES_IN_CARRIER_MESSAGING_SERVICE)
    public static final int SEND_STATUS_RESULT_INVALID_STATE = 209;

    /**
     * Failed because the sms format is not valid.
     *
     * @see android.telephony.SmsManager.RESULT_INVALID_SMS_FORMAT
     */
    @FlaggedApi(Flags.FLAG_TEMPORARY_FAILURES_IN_CARRIER_MESSAGING_SERVICE)
    public static final int SEND_STATUS_RESULT_INVALID_SMS_FORMAT = 210;

    /**
     * Failed because of a network error.
     *
     * @see android.telephony.SmsManager.RESULT_NETWORK_ERROR
     */
    @FlaggedApi(Flags.FLAG_TEMPORARY_FAILURES_IN_CARRIER_MESSAGING_SERVICE)
    public static final int SEND_STATUS_RESULT_NETWORK_ERROR = 211;

    /**
     * Failed because of an encoding error.
     *
     * @see android.telephony.SmsManager.RESULT_ENCODING_ERROR
     */
    @FlaggedApi(Flags.FLAG_TEMPORARY_FAILURES_IN_CARRIER_MESSAGING_SERVICE)
    public static final int SEND_STATUS_RESULT_ENCODING_ERROR = 212;

    /**
     * Failed because of an invalid smsc address
     *
     * @see android.telephony.SmsManager.RESULT_INVALID_SMSC_ADDRESS
     */
    @FlaggedApi(Flags.FLAG_TEMPORARY_FAILURES_IN_CARRIER_MESSAGING_SERVICE)
    public static final int SEND_STATUS_RESULT_INVALID_SMSC_ADDRESS = 213;

    /**
     * Failed because the operation is not allowed.
     *
     * @see android.telephony.SmsManager.RESULT_OPERATION_NOT_ALLOWED
     */
    @FlaggedApi(Flags.FLAG_TEMPORARY_FAILURES_IN_CARRIER_MESSAGING_SERVICE)
    public static final int SEND_STATUS_RESULT_OPERATION_NOT_ALLOWED = 214;

    /**
     * Failed because the operation was cancelled.
     *
     * @see android.telephony.SmsManager.RESULT_CANCELLED
     */
    @FlaggedApi(Flags.FLAG_TEMPORARY_FAILURES_IN_CARRIER_MESSAGING_SERVICE)
    public static final int SEND_STATUS_RESULT_CANCELLED = 215;

    /**
     * Failed because the request is not supported.
     *
     * @see android.telephony.SmsManager.RESULT_REQUEST_NOT_SUPPORTED
     */
    @FlaggedApi(Flags.FLAG_TEMPORARY_FAILURES_IN_CARRIER_MESSAGING_SERVICE)
    public static final int SEND_STATUS_RESULT_REQUEST_NOT_SUPPORTED = 216;

    /**
     * Failed sending during an emergency call.
     *
     * @see android.telephony.SmsManager.RESULT_SMS_BLOCKED_DURING_EMERGENCY
     */
    @FlaggedApi(Flags.FLAG_TEMPORARY_FAILURES_IN_CARRIER_MESSAGING_SERVICE)
    public static final int SEND_STATUS_RESULT_SMS_BLOCKED_DURING_EMERGENCY = 217;

    /**
     * Failed to send an sms retry.
     *
     * @see android.telephony.SmsManager.RESULT_SMS_SEND_RETRY_FAILED
     */
    @FlaggedApi(Flags.FLAG_TEMPORARY_FAILURES_IN_CARRIER_MESSAGING_SERVICE)
    public static final int SEND_STATUS_RESULT_SMS_SEND_RETRY_FAILED = 218;

    /**
     * More precise error reasons for outbound MMS send requests. These will not be retried on the
     * carrier network.
     *
     * <p>Each code maps directly to an SmsManager code (e.g.
     * SEND_STATUS_MMS_ERROR_UNABLE_CONNECT_MMS maps to SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS).
     */

    /**
     * Unspecific MMS error occurred during send.
     *
     * @see android.telephony.SmsManager.MMS_ERROR_UNSPECIFIED
     */
    @FlaggedApi(Flags.FLAG_TEMPORARY_FAILURES_IN_CARRIER_MESSAGING_SERVICE)
    public static final int SEND_STATUS_MMS_ERROR_UNSPECIFIED = 400;

    /**
     * ApnException occurred during MMS network setup.
     *
     * @see android.telephony.SmsManager.MMS_ERROR_INVALID_APN
     */
    @FlaggedApi(Flags.FLAG_TEMPORARY_FAILURES_IN_CARRIER_MESSAGING_SERVICE)
    public static final int SEND_STATUS_MMS_ERROR_INVALID_APN = 401;

    /**
     * An error occurred during the MMS connection setup.
     *
     * @see android.telephony.SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS
     */
    @FlaggedApi(Flags.FLAG_TEMPORARY_FAILURES_IN_CARRIER_MESSAGING_SERVICE)
    public static final int SEND_STATUS_MMS_ERROR_UNABLE_CONNECT_MMS = 402;

    /**
     * An error occurred during the HTTP client setup.
     *
     * @see android.telephony.SmsManager.MMS_ERROR_HTTP_FAILURE
     */
    @FlaggedApi(Flags.FLAG_TEMPORARY_FAILURES_IN_CARRIER_MESSAGING_SERVICE)
    public static final int SEND_STATUS_MMS_ERROR_HTTP_FAILURE = 403;

    /**
     * An I/O error occurred reading the PDU.
     *
     * @see android.telephony.SmsManager.MMS_ERROR_IO_ERROR
     */
    @FlaggedApi(Flags.FLAG_TEMPORARY_FAILURES_IN_CARRIER_MESSAGING_SERVICE)
    public static final int SEND_STATUS_MMS_ERROR_IO_ERROR = 404;

    /**
     * An error occurred while retrying sending the MMS.
     *
     * @see android.telephony.SmsManager.MMS_ERROR_RETRY
     */
    @FlaggedApi(Flags.FLAG_TEMPORARY_FAILURES_IN_CARRIER_MESSAGING_SERVICE)
    public static final int SEND_STATUS_MMS_ERROR_RETRY = 405;

    /**
     * The carrier-dependent configuration values could not be loaded.
     *
     * @see android.telephony.SmsManager.MMS_ERROR_CONFIGURATION_ERROR
     */
    @FlaggedApi(Flags.FLAG_TEMPORARY_FAILURES_IN_CARRIER_MESSAGING_SERVICE)
    public static final int SEND_STATUS_MMS_ERROR_CONFIGURATION_ERROR = 406;

    /**
     * There is neither Wi-Fi nor mobile data network.
     *
     * @see android.telephony.SmsManager.MMS_ERROR_NO_DATA_NETWORK
     */
    @FlaggedApi(Flags.FLAG_TEMPORARY_FAILURES_IN_CARRIER_MESSAGING_SERVICE)
    public static final int SEND_STATUS_MMS_ERROR_NO_DATA_NETWORK = 407;

    /**
     * The subscription id for the send is invalid.
     *
     * @see android.telephony.SmsManager.MMS_ERROR_INVALID_SUBSCRIPTION_ID
     */
    @FlaggedApi(Flags.FLAG_TEMPORARY_FAILURES_IN_CARRIER_MESSAGING_SERVICE)
    public static final int SEND_STATUS_MMS_ERROR_INVALID_SUBSCRIPTION_ID = 408;

    /**
     * The subscription id for the send is inactive.
     *
     * @see android.telephony.SmsManager.MMS_ERROR_INACTIVE_SUBSCRIPTION
     */
    @FlaggedApi(Flags.FLAG_TEMPORARY_FAILURES_IN_CARRIER_MESSAGING_SERVICE)
    public static final int SEND_STATUS_MMS_ERROR_INACTIVE_SUBSCRIPTION = 409;

    /**
     * Data is disabled for the MMS APN.
     *
     * @see android.telephony.SmsManager.MMS_ERROR_DATA_DISABLED
     */
    @FlaggedApi(Flags.FLAG_TEMPORARY_FAILURES_IN_CARRIER_MESSAGING_SERVICE)
    public static final int SEND_STATUS_MMS_ERROR_DATA_DISABLED = 410;

    /**
     * MMS is disabled by a carrier.
     *
     * @see android.telephony.SmsManager.MMS_ERROR_MMS_DISABLED_BY_CARRIER
     */
    @FlaggedApi(Flags.FLAG_TEMPORARY_FAILURES_IN_CARRIER_MESSAGING_SERVICE)
    public static final int SEND_STATUS_MMS_ERROR_MMS_DISABLED_BY_CARRIER = 411;

    /** @hide */
    @IntDef(
            prefix = {"SEND_STATUS_"},
            value = {
                SEND_STATUS_OK,
                SEND_STATUS_RETRY_ON_CARRIER_NETWORK,
                SEND_STATUS_ERROR,
                SEND_STATUS_RESULT_ERROR_GENERIC_FAILURE,
                SEND_STATUS_RESULT_ERROR_NULL_PDU,
                SEND_STATUS_RESULT_ERROR_NO_SERVICE,
                SEND_STATUS_RESULT_ERROR_LIMIT_EXCEEDED,
                SEND_STATUS_RESULT_ERROR_FDN_CHECK_FAILURE,
                SEND_STATUS_RESULT_ERROR_SHORT_CODE_NOT_ALLOWED,
                SEND_STATUS_RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED,
                SEND_STATUS_RESULT_NETWORK_REJECT,
                SEND_STATUS_RESULT_INVALID_ARGUMENTS,
                SEND_STATUS_RESULT_INVALID_STATE,
                SEND_STATUS_RESULT_INVALID_SMS_FORMAT,
                SEND_STATUS_RESULT_NETWORK_ERROR,
                SEND_STATUS_RESULT_ENCODING_ERROR,
                SEND_STATUS_RESULT_INVALID_SMSC_ADDRESS,
                SEND_STATUS_RESULT_OPERATION_NOT_ALLOWED,
                SEND_STATUS_RESULT_CANCELLED,
                SEND_STATUS_RESULT_REQUEST_NOT_SUPPORTED,
                SEND_STATUS_RESULT_SMS_BLOCKED_DURING_EMERGENCY,
                SEND_STATUS_RESULT_SMS_SEND_RETRY_FAILED,
                SEND_STATUS_MMS_ERROR_UNSPECIFIED,
                SEND_STATUS_MMS_ERROR_INVALID_APN,
                SEND_STATUS_MMS_ERROR_UNABLE_CONNECT_MMS,
                SEND_STATUS_MMS_ERROR_HTTP_FAILURE,
                SEND_STATUS_MMS_ERROR_IO_ERROR,
                SEND_STATUS_MMS_ERROR_RETRY,
                SEND_STATUS_MMS_ERROR_CONFIGURATION_ERROR,
                SEND_STATUS_MMS_ERROR_NO_DATA_NETWORK,
                SEND_STATUS_MMS_ERROR_INVALID_SUBSCRIPTION_ID,
                SEND_STATUS_MMS_ERROR_INACTIVE_SUBSCRIPTION,
                SEND_STATUS_MMS_ERROR_DATA_DISABLED,
                SEND_STATUS_MMS_ERROR_MMS_DISABLED_BY_CARRIER
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SendResult {}

    /**
     * Successfully downloaded an MMS message.
     */
    public static final int DOWNLOAD_STATUS_OK = 0;

    /**
     * MMS downloading failed. We should retry via the carrier network.
     */
    public static final int DOWNLOAD_STATUS_RETRY_ON_CARRIER_NETWORK = 1;

    /**
     * MMS downloading failed due to an unspecified issue. Downloading will not be retried via the
     * carrier network.
     *
     * <p>Maps to SmsManager.MMR_ERROR_UNSPECIFIED.
     */
    public static final int DOWNLOAD_STATUS_ERROR = 2;

    /**
     * More precise error reasons for inbound MMS download requests. These will not be retried on
     * the carrier network.
     *
     * <p>Each code maps directly to an SmsManager code (e.g.
     * DOWNLOAD_STATUS_MMS_ERROR_UNABLE_CONNECT_MMS maps to
     * SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS).
     */

    /**
     * Unspecific MMS error occurred during download.
     *
     * @see android.telephony.SmsManager.MMS_ERROR_UNSPECIFIED
     */
    @FlaggedApi(Flags.FLAG_TEMPORARY_FAILURES_IN_CARRIER_MESSAGING_SERVICE)
    public static final int DOWNLOAD_STATUS_MMS_ERROR_UNSPECIFIED = 600;

    /**
     * ApnException occurred during MMS network setup.
     *
     * @see android.telephony.SmsManager.MMS_ERROR_INVALID_APN
     */
    @FlaggedApi(Flags.FLAG_TEMPORARY_FAILURES_IN_CARRIER_MESSAGING_SERVICE)
    public static final int DOWNLOAD_STATUS_MMS_ERROR_INVALID_APN = 601;

    /**
     * An error occurred during the MMS connection setup.
     *
     * @see android.telephony.SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS
     */
    @FlaggedApi(Flags.FLAG_TEMPORARY_FAILURES_IN_CARRIER_MESSAGING_SERVICE)
    public static final int DOWNLOAD_STATUS_MMS_ERROR_UNABLE_CONNECT_MMS = 602;

    /**
     * An error occurred during the HTTP client setup.
     *
     * @see android.telephony.SmsManager.MMS_ERROR_HTTP_FAILURE
     */
    @FlaggedApi(Flags.FLAG_TEMPORARY_FAILURES_IN_CARRIER_MESSAGING_SERVICE)
    public static final int DOWNLOAD_STATUS_MMS_ERROR_HTTP_FAILURE = 603;

    /**
     * An I/O error occurred reading the PDU.
     *
     * @see android.telephony.SmsManager.MMS_ERROR_IO_ERROR
     */
    @FlaggedApi(Flags.FLAG_TEMPORARY_FAILURES_IN_CARRIER_MESSAGING_SERVICE)
    public static final int DOWNLOAD_STATUS_MMS_ERROR_IO_ERROR = 604;

    /**
     * An error occurred while retrying downloading the MMS.
     *
     * @see android.telephony.SmsManager.MMS_ERROR_RETRY
     */
    @FlaggedApi(Flags.FLAG_TEMPORARY_FAILURES_IN_CARRIER_MESSAGING_SERVICE)
    public static final int DOWNLOAD_STATUS_MMS_ERROR_RETRY = 605;

    /**
     * The carrier-dependent configuration values could not be loaded.
     *
     * @see android.telephony.SmsManager.MMS_ERROR_CONFIGURATION_ERROR
     */
    @FlaggedApi(Flags.FLAG_TEMPORARY_FAILURES_IN_CARRIER_MESSAGING_SERVICE)
    public static final int DOWNLOAD_STATUS_MMS_ERROR_CONFIGURATION_ERROR = 606;

    /**
     * There is neither Wi-Fi nor mobile data network.
     *
     * @see android.telephony.SmsManager.MMS_ERROR_NO_DATA_NETWORK
     */
    @FlaggedApi(Flags.FLAG_TEMPORARY_FAILURES_IN_CARRIER_MESSAGING_SERVICE)
    public static final int DOWNLOAD_STATUS_MMS_ERROR_NO_DATA_NETWORK = 607;

    /**
     * The subscription id for the download is invalid.
     *
     * @see android.telephony.SmsManager.MMS_ERROR_INVALID_SUBSCRIPTION_ID
     */
    @FlaggedApi(Flags.FLAG_TEMPORARY_FAILURES_IN_CARRIER_MESSAGING_SERVICE)
    public static final int DOWNLOAD_STATUS_MMS_ERROR_INVALID_SUBSCRIPTION_ID = 608;

    /**
     * The subscription id for the download is inactive.
     *
     * @see android.telephony.SmsManager.MMS_ERROR_INACTIVE_SUBSCRIPTION
     */
    @FlaggedApi(Flags.FLAG_TEMPORARY_FAILURES_IN_CARRIER_MESSAGING_SERVICE)
    public static final int DOWNLOAD_STATUS_MMS_ERROR_INACTIVE_SUBSCRIPTION = 609;

    /**
     * Data is disabled for the MMS APN.
     *
     * @see android.telephony.SmsManager.MMS_ERROR_DATA_DISABLED
     */
    @FlaggedApi(Flags.FLAG_TEMPORARY_FAILURES_IN_CARRIER_MESSAGING_SERVICE)
    public static final int DOWNLOAD_STATUS_MMS_ERROR_DATA_DISABLED = 610;

    /**
     * MMS is disabled by a carrier.
     *
     * @see android.telephony.SmsManager.MMS_ERROR_MMS_DISABLED_BY_CARRIER
     */
    @FlaggedApi(Flags.FLAG_TEMPORARY_FAILURES_IN_CARRIER_MESSAGING_SERVICE)
    public static final int DOWNLOAD_STATUS_MMS_ERROR_MMS_DISABLED_BY_CARRIER = 611;

    /** @hide */
    @IntDef(
            prefix = {"DOWNLOAD_STATUS_"},
            value = {
                DOWNLOAD_STATUS_OK,
                DOWNLOAD_STATUS_RETRY_ON_CARRIER_NETWORK,
                DOWNLOAD_STATUS_ERROR,
                DOWNLOAD_STATUS_MMS_ERROR_UNSPECIFIED,
                DOWNLOAD_STATUS_MMS_ERROR_INVALID_APN,
                DOWNLOAD_STATUS_MMS_ERROR_UNABLE_CONNECT_MMS,
                DOWNLOAD_STATUS_MMS_ERROR_HTTP_FAILURE,
                DOWNLOAD_STATUS_MMS_ERROR_IO_ERROR,
                DOWNLOAD_STATUS_MMS_ERROR_RETRY,
                DOWNLOAD_STATUS_MMS_ERROR_CONFIGURATION_ERROR,
                DOWNLOAD_STATUS_MMS_ERROR_NO_DATA_NETWORK,
                DOWNLOAD_STATUS_MMS_ERROR_INVALID_SUBSCRIPTION_ID,
                DOWNLOAD_STATUS_MMS_ERROR_INACTIVE_SUBSCRIPTION,
                DOWNLOAD_STATUS_MMS_ERROR_DATA_DISABLED,
                DOWNLOAD_STATUS_MMS_ERROR_MMS_DISABLED_BY_CARRIER
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DownloadResult {}

    /**
     * Flag to request SMS delivery status report.
     */
    public static final int SEND_FLAG_REQUEST_DELIVERY_STATUS = 0x1;

    /** @hide */
    @IntDef(flag = true, prefix = { "SEND_FLAG_" }, value = {
            SEND_FLAG_REQUEST_DELIVERY_STATUS
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SendRequest {}

    private final ICarrierMessagingWrapper mWrapper = new ICarrierMessagingWrapper();

    /**
     * Override this method to filter inbound SMS messages.
     *
     * @param pdu the PDUs of the message
     * @param format the format of the PDUs, typically "3gpp" or "3gpp2"
     * @param destPort the destination port of a binary SMS, this will be -1 for text SMS
     * @param subId SMS subscription ID of the SIM
     * @param callback result callback. Call with {@code true} to keep an inbound SMS message and
     *        deliver to SMS apps, and {@code false} to drop the message.
     * @deprecated Use {@link #onReceiveTextSms} instead.
     */
    @Deprecated
    public void onFilterSms(@NonNull MessagePdu pdu, @NonNull String format, int destPort,
            int subId, @NonNull ResultCallback<Boolean> callback) {
        // optional
        try {
            callback.onReceiveResult(true);
        } catch (RemoteException ex) {
        }
    }

    /**
     * Override this method to filter inbound SMS messages.
     *
     * <p>This method will be called once for every incoming text SMS. You can invoke the callback
     * with a bitmask to tell the platform how to handle the SMS. For a SMS received on a
     * file-based encryption capable device while the credential-encrypted storage is not available,
     * this method will be called for the second time when the credential-encrypted storage becomes
     * available after the user unlocks the phone, if the bit {@link #RECEIVE_OPTIONS_DROP} is not
     * set when invoking the callback.
     *
     * @param pdu the PDUs of the message
     * @param format the format of the PDUs, typically "3gpp" or "3gpp2"
     * @param destPort the destination port of a binary SMS, this will be -1 for text SMS
     * @param subId SMS subscription ID of the SIM
     * @param callback result callback. Call with a bitmask integer to indicate how the incoming
     *        text SMS should be handled by the platform. Use {@link #RECEIVE_OPTIONS_DROP} and
     *        {@link #RECEIVE_OPTIONS_SKIP_NOTIFY_WHEN_CREDENTIAL_PROTECTED_STORAGE_UNAVAILABLE}
     *        to set the flags in the bitmask.
     */
    public void onReceiveTextSms(@NonNull MessagePdu pdu, @NonNull String format,
            int destPort, int subId, @NonNull final ResultCallback<Integer> callback) {
        onFilterSms(pdu, format, destPort, subId, new ResultCallback<Boolean>() {
            @Override
            public void onReceiveResult(Boolean result) throws RemoteException {
                callback.onReceiveResult(result ? RECEIVE_OPTIONS_DEFAULT : RECEIVE_OPTIONS_DROP
                    | RECEIVE_OPTIONS_SKIP_NOTIFY_WHEN_CREDENTIAL_PROTECTED_STORAGE_UNAVAILABLE);
            }
        });
    }

    /**
     * Override this method to intercept text SMSs sent from the device.
     * @deprecated Override {@link #onSendTextSms} below instead.
     *
     * @param text the text to send
     * @param subId SMS subscription ID of the SIM
     * @param destAddress phone number of the recipient of the message
     * @param callback result callback. Call with a {@link SendSmsResult}.
     */
    @Deprecated
    public void onSendTextSms(
            @NonNull String text, int subId, @NonNull String destAddress,
            @NonNull ResultCallback<SendSmsResult> callback) {
        // optional
        try {
            callback.onReceiveResult(new SendSmsResult(SEND_STATUS_RETRY_ON_CARRIER_NETWORK, 0));
        } catch (RemoteException ex) {
        }
    }

    /**
     * Override this method to intercept text SMSs sent from the device.
     *
     * @param text the text to send
     * @param subId SMS subscription ID of the SIM
     * @param destAddress phone number of the recipient of the message
     * @param sendSmsFlag Flag for sending SMS. Acceptable values are 0 and
     *        {@link #SEND_FLAG_REQUEST_DELIVERY_STATUS}.
     * @param callback result callback. Call with a {@link SendSmsResult}.
     */
    public void onSendTextSms(
            @NonNull String text, int subId, @NonNull String destAddress,
            int sendSmsFlag, @NonNull ResultCallback<SendSmsResult> callback) {
        // optional
        onSendTextSms(text, subId, destAddress, callback);
    }

    /**
     * Override this method to intercept binary SMSs sent from the device.
     * @deprecated Override {@link #onSendDataSms} below instead.
     *
     * @param data the binary content
     * @param subId SMS subscription ID of the SIM
     * @param destAddress phone number of the recipient of the message
     * @param destPort the destination port
     * @param callback result callback. Call with a {@link SendSmsResult}.
     */
    @Deprecated
    public void onSendDataSms(@NonNull byte[] data, int subId,
            @NonNull String destAddress, int destPort,
            @NonNull ResultCallback<SendSmsResult> callback) {
        // optional
        try {
            callback.onReceiveResult(new SendSmsResult(SEND_STATUS_RETRY_ON_CARRIER_NETWORK, 0));
        } catch (RemoteException ex) {
        }
    }

    /**
     * Override this method to intercept binary SMSs sent from the device.
     *
     * @param data the binary content
     * @param subId SMS subscription ID of the SIM
     * @param destAddress phone number of the recipient of the message
     * @param destPort the destination port
     * @param sendSmsFlag Flag for sending SMS. Acceptable values are 0 and
     *        {@link #SEND_FLAG_REQUEST_DELIVERY_STATUS}.
     * @param callback result callback. Call with a {@link SendSmsResult}.
     */
    public void onSendDataSms(@NonNull byte[] data, int subId,
            @NonNull String destAddress, int destPort, int sendSmsFlag,
            @NonNull ResultCallback<SendSmsResult> callback) {
        // optional
        onSendDataSms(data, subId, destAddress, destPort, callback);
    }

    /**
     * Override this method to intercept long SMSs sent from the device.
     * @deprecated Override {@link #onSendMultipartTextSms} below instead.
     *
     * @param parts a {@link List} of the message parts
     * @param subId SMS subscription ID of the SIM
     * @param destAddress phone number of the recipient of the message
     * @param callback result callback. Call with a {@link SendMultipartSmsResult}.
     */
    @Deprecated
    public void onSendMultipartTextSms(@NonNull List<String> parts,
            int subId, @NonNull String destAddress,
            @NonNull ResultCallback<SendMultipartSmsResult> callback) {
        // optional
        try {
            callback.onReceiveResult(
                    new SendMultipartSmsResult(SEND_STATUS_RETRY_ON_CARRIER_NETWORK, null));
        } catch (RemoteException ex) {
        }
    }

    /**
     * Override this method to intercept long SMSs sent from the device.
     *
     * @param parts a {@link List} of the message parts
     * @param subId SMS subscription ID of the SIM
     * @param destAddress phone number of the recipient of the message
     * @param sendSmsFlag Flag for sending SMS. Acceptable values are 0 and
     *        {@link #SEND_FLAG_REQUEST_DELIVERY_STATUS}.
     * @param callback result callback. Call with a {@link SendMultipartSmsResult}.
     */
    public void onSendMultipartTextSms(@NonNull List<String> parts,
            int subId, @NonNull String destAddress, int sendSmsFlag,
            @NonNull ResultCallback<SendMultipartSmsResult> callback) {
        // optional
        onSendMultipartTextSms(parts, subId, destAddress, callback);
    }

    /**
     * Override this method to intercept MMSs sent from the device.
     *
     * @param pduUri the content provider URI of the PDU to send
     * @param subId SMS subscription ID of the SIM
     * @param location the optional URI to send this MMS PDU. If this is {code null},
     *        the PDU should be sent to the default MMSC URL.
     * @param callback result callback. Call with a {@link SendMmsResult}.
     */
    public void onSendMms(@NonNull Uri pduUri, int subId,
            @Nullable Uri location, @NonNull ResultCallback<SendMmsResult> callback) {
        // optional
        try {
            callback.onReceiveResult(new SendMmsResult(SEND_STATUS_RETRY_ON_CARRIER_NETWORK, null));
        } catch (RemoteException ex) {
        }
    }

    /**
     * Override this method to download MMSs received.
     *
     * @param contentUri the content provider URI of the PDU to be downloaded.
     * @param subId SMS subscription ID of the SIM
     * @param location the URI of the message to be downloaded.
     * @param callback result callback. Call with a status code which is one of
     *        {@link #DOWNLOAD_STATUS_OK},
     *        {@link #DOWNLOAD_STATUS_RETRY_ON_CARRIER_NETWORK}, or {@link #DOWNLOAD_STATUS_ERROR}.
     */
    public void onDownloadMms(@NonNull Uri contentUri, int subId, @NonNull Uri location,
            @NonNull ResultCallback<Integer> callback) {
        // optional
        try {
            callback.onReceiveResult(DOWNLOAD_STATUS_RETRY_ON_CARRIER_NETWORK);
        } catch (RemoteException ex) {
        }
    }

    @Override
    public @Nullable IBinder onBind(@NonNull Intent intent) {
        if (!SERVICE_INTERFACE.equals(intent.getAction())) {
            return null;
        }
        return mWrapper;
    }

    /**
     * The result of sending an MMS.
     */
    public static final class SendMmsResult {
        private int mSendStatus;
        private byte[] mSendConfPdu;

        /**
         * Constructs a SendMmsResult with the MMS send result, and the SendConf PDU.
         *
         * @param sendStatus send status, one of {@link #SEND_STATUS_OK},
         *        {@link #SEND_STATUS_RETRY_ON_CARRIER_NETWORK}, and
         *        {@link #SEND_STATUS_ERROR}
         * @param sendConfPdu a possibly {code null} SendConf PDU, which confirms that the message
         *        was sent. sendConfPdu is ignored if the {@code result} is not
         *        {@link #SEND_STATUS_OK}.
         */
        public SendMmsResult(int sendStatus, @Nullable byte[] sendConfPdu) {
            mSendStatus = sendStatus;
            mSendConfPdu = sendConfPdu;
        }

        /**
         * Returns the send status of the just-sent MMS.
         *
         * @return the send status which is one of {@link #SEND_STATUS_OK},
         *         {@link #SEND_STATUS_RETRY_ON_CARRIER_NETWORK}, and {@link #SEND_STATUS_ERROR}
         */
        public int getSendStatus() {
            return mSendStatus;
        }

        /**
         * Returns the SendConf PDU, which confirms that the message was sent.
         *
         * @return the SendConf PDU
         */
        public @Nullable byte[] getSendConfPdu() {
            return mSendConfPdu;
        }
    }

    /**
     * The result of sending an SMS.
     */
    public static final class SendSmsResult {
        private final int mSendStatus;
        private final int mMessageRef;

        /**
         * Constructs a SendSmsResult with the send status and message reference for the
         * just-sent SMS.
         *
         * @param sendStatus send status, one of {@link #SEND_STATUS_OK},
         *        {@link #SEND_STATUS_RETRY_ON_CARRIER_NETWORK}, and {@link #SEND_STATUS_ERROR}.
         * @param messageRef message reference of the just-sent SMS. This field is applicable only
         *        if send status is {@link #SEND_STATUS_OK}.
         */
        public SendSmsResult(int sendStatus, int messageRef) {
            mSendStatus = sendStatus;
            mMessageRef = messageRef;
        }

        /**
         * Returns the message reference of the just-sent SMS.
         *
         * @return the message reference
         */
        public int getMessageRef() {
            return mMessageRef;
        }

        /**
         * Returns the send status of the just-sent SMS.
         *
         * @return the send status
         */
        public int getSendStatus() {
            return mSendStatus;
        }
    }

    /**
     * The result of sending a multipart SMS.
     */
    public static final class SendMultipartSmsResult {
        private final int mSendStatus;
        private final int[] mMessageRefs;

        /**
         * Constructs a SendMultipartSmsResult with the send status and message references for the
         * just-sent multipart SMS.
         *
         * @param sendStatus send status, one of {@link #SEND_STATUS_OK},
         *        {@link #SEND_STATUS_RETRY_ON_CARRIER_NETWORK}, and {@link #SEND_STATUS_ERROR}.
         * @param messageRefs an array of message references, one for each part of the
         *        multipart SMS. This field is applicable only if send status is
         *        {@link #SEND_STATUS_OK}.
         */
        public SendMultipartSmsResult(int sendStatus, @Nullable int[] messageRefs) {
            mSendStatus = sendStatus;
            mMessageRefs = messageRefs;
        }

        /**
         * Returns the message references of the just-sent multipart SMS.
         *
         * @return the message references, one for each part of the multipart SMS
         */
        public @Nullable int[] getMessageRefs() {
            return mMessageRefs;
        }

        /**
         * Returns the send status of the just-sent SMS.
         *
         * @return the send status
         */
        public int getSendStatus() {
            return mSendStatus;
        }
    }

    /**
     * A callback interface used to provide results asynchronously.
     */
    public interface ResultCallback<T> {
        /**
         * Invoked when the result is available.
         *
         * @param result the result
         */
        public void onReceiveResult(@NonNull T result) throws RemoteException;
    };

    /**
     * A wrapper around ICarrierMessagingService to enable the carrier messaging app to implement
     * methods it cares about in the {@link ICarrierMessagingService} interface.
     */
    private class ICarrierMessagingWrapper extends ICarrierMessagingService.Stub {
        @Override
        public void filterSms(MessagePdu pdu, String format, int destPort,
                              int subId, final ICarrierMessagingCallback callback) {
            onReceiveTextSms(pdu, format, destPort, subId,
                new ResultCallback<Integer>() {
                    @Override
                    public void onReceiveResult(Integer options) throws RemoteException {
                        callback.onFilterComplete(options);
                    }
                });
        }

        @Override
        public void sendTextSms(String text, int subId, String destAddress,
                int sendSmsFlag, final ICarrierMessagingCallback callback) {
            onSendTextSms(text, subId, destAddress, sendSmsFlag,
                    new ResultCallback<SendSmsResult>() {
                    @Override
                    public void onReceiveResult(final SendSmsResult result) throws RemoteException {
                        callback.onSendSmsComplete(result.getSendStatus(), result.getMessageRef());
                    }
                });
        }

        @Override
        public void sendDataSms(byte[] data, int subId, String destAddress, int destPort,
                int sendSmsFlag, final ICarrierMessagingCallback callback) {
            onSendDataSms(data, subId, destAddress, destPort, sendSmsFlag,
                    new ResultCallback<SendSmsResult>() {
                    @Override
                    public void onReceiveResult(final SendSmsResult result) throws RemoteException {
                        callback.onSendSmsComplete(result.getSendStatus(), result.getMessageRef());
                    }
                });
        }

        @Override
        public void sendMultipartTextSms(List<String> parts, int subId, String destAddress,
                int sendSmsFlag, final ICarrierMessagingCallback callback) {
            onSendMultipartTextSms(parts, subId, destAddress, sendSmsFlag,
                        new ResultCallback<SendMultipartSmsResult>() {
                                @Override
                                public void onReceiveResult(final SendMultipartSmsResult result)
                                        throws RemoteException {
                                    callback.onSendMultipartSmsComplete(
                                            result.getSendStatus(), result.getMessageRefs());
                                }
                            });
        }

        @Override
        public void sendMms(Uri pduUri, int subId, Uri location,
                final ICarrierMessagingCallback callback) {
            onSendMms(pduUri, subId, location, new ResultCallback<SendMmsResult>() {
                    @Override
                    public void onReceiveResult(final SendMmsResult result) throws RemoteException {
                        callback.onSendMmsComplete(result.getSendStatus(), result.getSendConfPdu());
                    }
                });
        }

        @Override
        public void downloadMms(Uri pduUri, int subId, Uri location,
                final ICarrierMessagingCallback callback) {
            onDownloadMms(pduUri, subId, location, new ResultCallback<Integer>() {
                    @Override
                    public void onReceiveResult(Integer result) throws RemoteException {
                        callback.onDownloadMmsComplete(result);
                    }
                });
        }
    }
}
