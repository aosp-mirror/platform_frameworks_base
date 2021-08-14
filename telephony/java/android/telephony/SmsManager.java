/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.telephony;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SuppressAutoDoc;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.PendingIntent;
import android.compat.Compatibility;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledAfter;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.database.CursorWindow;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.telephony.IIntegerConsumer;
import com.android.internal.telephony.ISms;
import com.android.internal.telephony.ITelephony;
import com.android.internal.telephony.SmsRawData;
import com.android.telephony.Rlog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/*
 * TODO(code review): Curious question... Why are a lot of these
 * methods not declared as static, since they do not seem to require
 * any local object state?  Presumably this cannot be changed without
 * interfering with the API...
 */

/**
 * Manages SMS operations such as sending data, text, and pdu SMS messages.
 * Get this object by calling the static method {@link #getDefault()}. To create an instance of
 * {@link SmsManager} associated with a specific subscription ID, call
 * {@link #getSmsManagerForSubscriptionId(int)}. This is typically used for devices that support
 * multiple active subscriptions at once.
 *
 * <p>For information about how to behave as the default SMS app on Android 4.4 (API level 19)
 * and higher, see {@link android.provider.Telephony}.
 *
 * @see SubscriptionManager#getActiveSubscriptionInfoList()
 */
public final class SmsManager {
    private static final String TAG = "SmsManager";

    private static final Object sLockObject = new Object();

    @GuardedBy("sLockObject")
    private static final Map<Pair<Context, Integer>, SmsManager> sSubInstances =
            new ArrayMap<>();

    /** Singleton object constructed during class initialization. */
    private static final SmsManager DEFAULT_INSTANCE = getSmsManagerForContextAndSubscriptionId(
            null, SubscriptionManager.DEFAULT_SUBSCRIPTION_ID);

    /** SMS record length from TS 51.011 10.5.3
     * @hide
     */
    public static final int SMS_RECORD_LENGTH = 176;

    /** SMS record length from C.S0023 3.4.27
     * @hide
     */
    public static final int CDMA_SMS_RECORD_LENGTH = 255;

    /** A concrete subscription id, or the pseudo DEFAULT_SUBSCRIPTION_ID */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private int mSubId;

    /**
     * Context this SmsManager is for. Can be {@code null} in the case the manager was created via
     * legacy APIs
     */
    private final @Nullable Context mContext;

    /*
     * Key for the various carrier-dependent configuration values.
     * Some of the values are used by the system in processing SMS or MMS messages. Others
     * are provided for the convenience of SMS applications.
     */

    /**
     * Whether to append transaction id to MMS WAP Push M-Notification.ind's content location URI
     * when constructing the download URL of a new MMS (boolean type)
     */
    public static final String MMS_CONFIG_APPEND_TRANSACTION_ID =
            CarrierConfigManager.KEY_MMS_APPEND_TRANSACTION_ID_BOOL;
    /**
     * Whether MMS is enabled for the current carrier (boolean type)
     */
    public static final String
            MMS_CONFIG_MMS_ENABLED = CarrierConfigManager.KEY_MMS_MMS_ENABLED_BOOL;
    /**
     * Whether group MMS is enabled for the current carrier (boolean type)
     */
    public static final String
            MMS_CONFIG_GROUP_MMS_ENABLED = CarrierConfigManager.KEY_MMS_GROUP_MMS_ENABLED_BOOL;
    /**
     * If this is enabled, M-NotifyResp.ind should be sent to the WAP Push content location instead
     * of the default MMSC (boolean type)
     */
    public static final String MMS_CONFIG_NOTIFY_WAP_MMSC_ENABLED =
            CarrierConfigManager.KEY_MMS_NOTIFY_WAP_MMSC_ENABLED_BOOL;
    /**
     * Whether alias is enabled (boolean type)
     */
    public static final String
            MMS_CONFIG_ALIAS_ENABLED = CarrierConfigManager.KEY_MMS_ALIAS_ENABLED_BOOL;
    /**
     * Whether audio is allowed to be attached for MMS messages (boolean type)
     */
    public static final String
            MMS_CONFIG_ALLOW_ATTACH_AUDIO = CarrierConfigManager.KEY_MMS_ALLOW_ATTACH_AUDIO_BOOL;
    /**
     * Whether multipart SMS is enabled (boolean type)
     */
    public static final String MMS_CONFIG_MULTIPART_SMS_ENABLED =
            CarrierConfigManager.KEY_MMS_MULTIPART_SMS_ENABLED_BOOL;
    /**
     * Whether SMS delivery report is enabled (boolean type)
     */
    public static final String MMS_CONFIG_SMS_DELIVERY_REPORT_ENABLED =
            CarrierConfigManager.KEY_MMS_SMS_DELIVERY_REPORT_ENABLED_BOOL;
    /**
     * Whether content-disposition field should be expected in an MMS PDU (boolean type)
     */
    public static final String MMS_CONFIG_SUPPORT_MMS_CONTENT_DISPOSITION =
            CarrierConfigManager.KEY_MMS_SUPPORT_MMS_CONTENT_DISPOSITION_BOOL;
    /**
     * Whether multipart SMS should be sent as separate messages
     */
    public static final String MMS_CONFIG_SEND_MULTIPART_SMS_AS_SEPARATE_MESSAGES =
            CarrierConfigManager.KEY_MMS_SEND_MULTIPART_SMS_AS_SEPARATE_MESSAGES_BOOL;
    /**
     * Whether MMS read report is enabled (boolean type)
     */
    public static final String MMS_CONFIG_MMS_READ_REPORT_ENABLED =
            CarrierConfigManager.KEY_MMS_MMS_READ_REPORT_ENABLED_BOOL;
    /**
     * Whether MMS delivery report is enabled (boolean type)
     */
    public static final String MMS_CONFIG_MMS_DELIVERY_REPORT_ENABLED =
            CarrierConfigManager.KEY_MMS_MMS_DELIVERY_REPORT_ENABLED_BOOL;
    /**
     * Max MMS message size in bytes (int type)
     */
    public static final String
            MMS_CONFIG_MAX_MESSAGE_SIZE = CarrierConfigManager.KEY_MMS_MAX_MESSAGE_SIZE_INT;
    /**
     * Max MMS image width (int type)
     */
    public static final String
            MMS_CONFIG_MAX_IMAGE_WIDTH = CarrierConfigManager.KEY_MMS_MAX_IMAGE_WIDTH_INT;
    /**
     * Max MMS image height (int type)
     */
    public static final String
            MMS_CONFIG_MAX_IMAGE_HEIGHT = CarrierConfigManager.KEY_MMS_MAX_IMAGE_HEIGHT_INT;
    /**
     * Limit of recipients of MMS messages (int type)
     */
    public static final String
            MMS_CONFIG_RECIPIENT_LIMIT = CarrierConfigManager.KEY_MMS_RECIPIENT_LIMIT_INT;
    /**
     * Min alias character count (int type)
     */
    public static final String
            MMS_CONFIG_ALIAS_MIN_CHARS = CarrierConfigManager.KEY_MMS_ALIAS_MIN_CHARS_INT;
    /**
     * Max alias character count (int type)
     */
    public static final String
            MMS_CONFIG_ALIAS_MAX_CHARS = CarrierConfigManager.KEY_MMS_ALIAS_MAX_CHARS_INT;
    /**
     * When the number of parts of a multipart SMS reaches this threshold, it should be converted
     * into an MMS (int type)
     */
    public static final String MMS_CONFIG_SMS_TO_MMS_TEXT_THRESHOLD =
            CarrierConfigManager.KEY_MMS_SMS_TO_MMS_TEXT_THRESHOLD_INT;
    /**
     * Some carriers require SMS to be converted into MMS when text length reaches this threshold
     * (int type)
     */
    public static final String MMS_CONFIG_SMS_TO_MMS_TEXT_LENGTH_THRESHOLD =
            CarrierConfigManager.KEY_MMS_SMS_TO_MMS_TEXT_LENGTH_THRESHOLD_INT;
    /**
     * Max message text size (int type)
     */
    public static final String MMS_CONFIG_MESSAGE_TEXT_MAX_SIZE =
            CarrierConfigManager.KEY_MMS_MESSAGE_TEXT_MAX_SIZE_INT;
    /**
     * Max message subject length (int type)
     */
    public static final String
            MMS_CONFIG_SUBJECT_MAX_LENGTH = CarrierConfigManager.KEY_MMS_SUBJECT_MAX_LENGTH_INT;
    /**
     * MMS HTTP socket timeout in milliseconds (int type)
     */
    public static final String
            MMS_CONFIG_HTTP_SOCKET_TIMEOUT = CarrierConfigManager.KEY_MMS_HTTP_SOCKET_TIMEOUT_INT;
    /**
     * The name of the UA Prof URL HTTP header for MMS HTTP request (String type)
     */
    public static final String
            MMS_CONFIG_UA_PROF_TAG_NAME = CarrierConfigManager.KEY_MMS_UA_PROF_TAG_NAME_STRING;
    /**
     * The User-Agent header value for MMS HTTP request (String type)
     */
    public static final String
            MMS_CONFIG_USER_AGENT = CarrierConfigManager.KEY_MMS_USER_AGENT_STRING;
    /**
     * The UA Profile URL header value for MMS HTTP request (String type)
     */
    public static final String
            MMS_CONFIG_UA_PROF_URL = CarrierConfigManager.KEY_MMS_UA_PROF_URL_STRING;
    /**
     * A list of HTTP headers to add to MMS HTTP request, separated by "|" (String type)
     */
    public static final String
            MMS_CONFIG_HTTP_PARAMS = CarrierConfigManager.KEY_MMS_HTTP_PARAMS_STRING;
    /**
     * Email gateway number (String type)
     */
    public static final String MMS_CONFIG_EMAIL_GATEWAY_NUMBER =
            CarrierConfigManager.KEY_MMS_EMAIL_GATEWAY_NUMBER_STRING;
    /**
     * The suffix to append to the NAI header value for MMS HTTP request (String type)
     */
    public static final String
            MMS_CONFIG_NAI_SUFFIX = CarrierConfigManager.KEY_MMS_NAI_SUFFIX_STRING;
    /**
     * If true, show the cell broadcast (amber alert) in the SMS settings. Some carriers don't want
     * this shown. (Boolean type)
     */
    public static final String MMS_CONFIG_SHOW_CELL_BROADCAST_APP_LINKS =
            CarrierConfigManager.KEY_MMS_SHOW_CELL_BROADCAST_APP_LINKS_BOOL;
    /**
     * Whether the carrier MMSC supports charset field in Content-Type header. If this is false,
     * then we don't add "charset" to "Content-Type"
     */
    public static final String MMS_CONFIG_SUPPORT_HTTP_CHARSET_HEADER =
            CarrierConfigManager.KEY_MMS_SUPPORT_HTTP_CHARSET_HEADER_BOOL;
    /**
     * If true, add "Connection: close" header to MMS HTTP requests so the connection
     * is immediately closed (disabling keep-alive). (Boolean type)
     * @hide
     */
    public static final String MMS_CONFIG_CLOSE_CONNECTION =
            CarrierConfigManager.KEY_MMS_CLOSE_CONNECTION_BOOL;

    /**
     * 3gpp2 SMS priority is not specified
     * @hide
     */
    public static final int SMS_MESSAGE_PRIORITY_NOT_SPECIFIED = -1;
    /**
     * 3gpp SMS period is not specified
     * @hide
     */
    public static final int SMS_MESSAGE_PERIOD_NOT_SPECIFIED = -1;

    /** @hide */
    @IntDef(prefix = { "PREMIUM_SMS_CONSENT" }, value = {
        SmsManager.PREMIUM_SMS_CONSENT_UNKNOWN,
        SmsManager.PREMIUM_SMS_CONSENT_ASK_USER,
        SmsManager.PREMIUM_SMS_CONSENT_NEVER_ALLOW,
        SmsManager.PREMIUM_SMS_CONSENT_ALWAYS_ALLOW
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PremiumSmsConsent {}

    /** Premium SMS Consent for the package is unknown. This indicates that the user
     *  has not set a permission for this package, because this package has never tried
     *  to send a premium SMS.
     * @hide
     */
    @SystemApi
    public static final int PREMIUM_SMS_CONSENT_UNKNOWN = 0;

    /** Default premium SMS Consent (ask user for each premium SMS sent).
     * @hide
     */
    @SystemApi
    public static final int PREMIUM_SMS_CONSENT_ASK_USER = 1;

    /** Premium SMS Consent when the owner has denied the app from sending premium SMS.
     * @hide
     */
    @SystemApi
    public static final int PREMIUM_SMS_CONSENT_NEVER_ALLOW = 2;

    /** Premium SMS Consent when the owner has allowed the app to send premium SMS.
     * @hide
     */
    @SystemApi
    public static final int PREMIUM_SMS_CONSENT_ALWAYS_ALLOW = 3;

    // result of asking the user for a subscription to perform an operation.
    private interface SubscriptionResolverResult {
        void onSuccess(int subId);
        void onFailure();
    }

    /**
     * Get {@link Context#getOpPackageName()} if this manager has a context, otherwise a dummy
     * value.
     *
     * @return The package name to be used for app-ops checks
     */
    private @Nullable String getOpPackageName() {
        if (mContext == null) {
            return null;
        } else {
            return mContext.getOpPackageName();
        }
    }

    /**
     * Get {@link Context#getAttributionTag()} ()} if this manager has a context, otherwise get the
     * default attribution tag.
     *
     * @return The attribution tag to be used for app-ops checks
     */
    private @Nullable String getAttributionTag() {
        if (mContext == null) {
            return null;
        } else {
            return mContext.getAttributionTag();
        }
    }

    /**
     * Send a text based SMS.
     *
     * <p class="note"><strong>Note:</strong> Using this method requires that your app has the
     * {@link android.Manifest.permission#SEND_SMS} permission.</p>
     *
     * <p class="note"><strong>Note:</strong> Beginning with Android 4.4 (API level 19), if
     * <em>and only if</em> an app is not selected as the default SMS app, the system automatically
     * writes messages sent using this method to the SMS Provider (the default SMS app is always
     * responsible for writing its sent messages to the SMS Provider). For information about
     * how to behave as the default SMS app, see {@link android.provider.Telephony}.</p>
     *
     * <p class="note"><strong>Note:</strong> If {@link #getDefault()} is used to instantiate this
     * manager on a multi-SIM device, this operation may fail sending the SMS message because no
     * suitable default subscription could be found. In this case, if {@code sentIntent} is
     * non-null, then the {@link PendingIntent} will be sent with an error code
     * {@code RESULT_ERROR_GENERIC_FAILURE} and an extra string {@code "noDefault"} containing the
     * boolean value {@code true}. See {@link #getDefault()} for more information on the conditions
     * where this operation may fail.
     * </p>
     *
     * @param destinationAddress the address to send the message to
     * @param scAddress is the service center address or null to use
     *  the current default SMSC
     * @param text the body of the message to send
     * @param sentIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is successfully sent, or failed.
     *  The result code will be <code>Activity.RESULT_OK</code> for success,
     *  or one of these errors:<br>
     *  <code>RESULT_ERROR_GENERIC_FAILURE</code><br>
     *  <code>RESULT_ERROR_RADIO_OFF</code><br>
     *  <code>RESULT_ERROR_NULL_PDU</code><br>
     *  <code>RESULT_ERROR_NO_SERVICE</code><br>
     *  <code>RESULT_ERROR_LIMIT_EXCEEDED</code><br>
     *  <code>RESULT_ERROR_FDN_CHECK_FAILURE</code><br>
     *  <code>RESULT_ERROR_SHORT_CODE_NOT_ALLOWED</code><br>
     *  <code>RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED</code><br>
     *  <code>RESULT_RADIO_NOT_AVAILABLE</code><br>
     *  <code>RESULT_NETWORK_REJECT</code><br>
     *  <code>RESULT_INVALID_ARGUMENTS</code><br>
     *  <code>RESULT_INVALID_STATE</code><br>
     *  <code>RESULT_NO_MEMORY</code><br>
     *  <code>RESULT_INVALID_SMS_FORMAT</code><br>
     *  <code>RESULT_SYSTEM_ERROR</code><br>
     *  <code>RESULT_MODEM_ERROR</code><br>
     *  <code>RESULT_NETWORK_ERROR</code><br>
     *  <code>RESULT_ENCODING_ERROR</code><br>
     *  <code>RESULT_INVALID_SMSC_ADDRESS</code><br>
     *  <code>RESULT_OPERATION_NOT_ALLOWED</code><br>
     *  <code>RESULT_INTERNAL_ERROR</code><br>
     *  <code>RESULT_NO_RESOURCES</code><br>
     *  <code>RESULT_CANCELLED</code><br>
     *  <code>RESULT_REQUEST_NOT_SUPPORTED</code><br>
     *  <code>RESULT_NO_BLUETOOTH_SERVICE</code><br>
     *  <code>RESULT_INVALID_BLUETOOTH_ADDRESS</code><br>
     *  <code>RESULT_BLUETOOTH_DISCONNECTED</code><br>
     *  <code>RESULT_UNEXPECTED_EVENT_STOP_SENDING</code><br>
     *  <code>RESULT_SMS_BLOCKED_DURING_EMERGENCY</code><br>
     *  <code>RESULT_SMS_SEND_RETRY_FAILED</code><br>
     *  <code>RESULT_REMOTE_EXCEPTION</code><br>
     *  <code>RESULT_NO_DEFAULT_SMS_APP</code><br>
     *  <code>RESULT_RIL_RADIO_NOT_AVAILABLE</code><br>
     *  <code>RESULT_RIL_SMS_SEND_FAIL_RETRY</code><br>
     *  <code>RESULT_RIL_NETWORK_REJECT</code><br>
     *  <code>RESULT_RIL_INVALID_STATE</code><br>
     *  <code>RESULT_RIL_INVALID_ARGUMENTS</code><br>
     *  <code>RESULT_RIL_NO_MEMORY</code><br>
     *  <code>RESULT_RIL_REQUEST_RATE_LIMITED</code><br>
     *  <code>RESULT_RIL_INVALID_SMS_FORMAT</code><br>
     *  <code>RESULT_RIL_SYSTEM_ERR</code><br>
     *  <code>RESULT_RIL_ENCODING_ERR</code><br>
     *  <code>RESULT_RIL_INVALID_SMSC_ADDRESS</code><br>
     *  <code>RESULT_RIL_MODEM_ERR</code><br>
     *  <code>RESULT_RIL_NETWORK_ERR</code><br>
     *  <code>RESULT_RIL_INTERNAL_ERR</code><br>
     *  <code>RESULT_RIL_REQUEST_NOT_SUPPORTED</code><br>
     *  <code>RESULT_RIL_INVALID_MODEM_STATE</code><br>
     *  <code>RESULT_RIL_NETWORK_NOT_READY</code><br>
     *  <code>RESULT_RIL_OPERATION_NOT_ALLOWED</code><br>
     *  <code>RESULT_RIL_NO_RESOURCES</code><br>
     *  <code>RESULT_RIL_CANCELLED</code><br>
     *  <code>RESULT_RIL_SIM_ABSENT</code><br>
     *  <code>RESULT_RIL_SIMULTANEOUS_SMS_AND_CALL_NOT_ALLOWED</code><br>
     *  <code>RESULT_RIL_ACCESS_BARRED</code><br>
     *  <code>RESULT_RIL_BLOCKED_DUE_TO_CALL</code><br>
     *  For <code>RESULT_ERROR_GENERIC_FAILURE</code> or any of the RESULT_RIL errors,
     *  the sentIntent may include the extra "errorCode" containing a radio technology specific
     *  value, generally only useful for troubleshooting.<br>
     * @param deliveryIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is delivered to the recipient.  The
     *  raw pdu of the status report is in the extended data ("pdu").
     *
     * @throws IllegalArgumentException if destinationAddress or text are empty
     */
    public void sendTextMessage(
            String destinationAddress, String scAddress, String text,
            PendingIntent sentIntent, PendingIntent deliveryIntent) {
        sendTextMessageInternal(destinationAddress, scAddress, text, sentIntent, deliveryIntent,
                true /* persistMessage*/, getOpPackageName(), getAttributionTag(),
                0L /* messageId */);
    }


    /**
     * Send a text based SMS. Same as {@link #sendTextMessage( String destinationAddress,
     * String scAddress, String text, PendingIntent sentIntent, PendingIntent deliveryIntent)}, but
     * adds an optional messageId.
     * @param messageId An id that uniquely identifies the message requested to be sent.
     * Used for logging and diagnostics purposes. The id may be 0.
     *
     * @throws IllegalArgumentException if destinationAddress or text are empty
     *
     */
    public void sendTextMessage(
            @NonNull String destinationAddress, @Nullable String scAddress, @NonNull String text,
            @Nullable PendingIntent sentIntent, @Nullable PendingIntent deliveryIntent,
            long messageId) {
        sendTextMessageInternal(destinationAddress, scAddress, text, sentIntent, deliveryIntent,
                true /* persistMessage*/, getOpPackageName(), getAttributionTag(),
                messageId);
    }

    /**
     * Send a text based SMS with messaging options.
     *
     * <p class="note"><strong>Note:</strong> If {@link #getDefault()} is used to instantiate this
     * manager on a multi-SIM device, this operation may fail sending the SMS message because no
     * suitable default subscription could be found. In this case, if {@code sentIntent} is
     * non-null, then the {@link PendingIntent} will be sent with an error code
     * {@code RESULT_ERROR_GENERIC_FAILURE} and an extra string {@code "noDefault"} containing the
     * boolean value {@code true}. See {@link #getDefault()} for more information on the conditions
     * where this operation may fail.
     * </p>
     *
     * @param destinationAddress the address to send the message to
     * @param scAddress is the service center address or null to use
     *  the current default SMSC
     * @param text the body of the message to send
     * @param sentIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is successfully sent, or failed.
     *  The result code will be <code>Activity.RESULT_OK</code> for success,
     *  or one of these errors:<br>
     *  <code>RESULT_ERROR_GENERIC_FAILURE</code><br>
     *  <code>RESULT_ERROR_RADIO_OFF</code><br>
     *  <code>RESULT_ERROR_NULL_PDU</code><br>
     *  <code>RESULT_ERROR_NO_SERVICE</code><br>
     *  <code>RESULT_ERROR_LIMIT_EXCEEDED</code><br>
     *  <code>RESULT_ERROR_FDN_CHECK_FAILURE</code><br>
     *  <code>RESULT_ERROR_SHORT_CODE_NOT_ALLOWED</code><br>
     *  <code>RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED</code><br>
     *  <code>RESULT_RADIO_NOT_AVAILABLE</code><br>
     *  <code>RESULT_NETWORK_REJECT</code><br>
     *  <code>RESULT_INVALID_ARGUMENTS</code><br>
     *  <code>RESULT_INVALID_STATE</code><br>
     *  <code>RESULT_NO_MEMORY</code><br>
     *  <code>RESULT_INVALID_SMS_FORMAT</code><br>
     *  <code>RESULT_SYSTEM_ERROR</code><br>
     *  <code>RESULT_MODEM_ERROR</code><br>
     *  <code>RESULT_NETWORK_ERROR</code><br>
     *  <code>RESULT_ENCODING_ERROR</code><br>
     *  <code>RESULT_INVALID_SMSC_ADDRESS</code><br>
     *  <code>RESULT_OPERATION_NOT_ALLOWED</code><br>
     *  <code>RESULT_INTERNAL_ERROR</code><br>
     *  <code>RESULT_NO_RESOURCES</code><br>
     *  <code>RESULT_CANCELLED</code><br>
     *  <code>RESULT_REQUEST_NOT_SUPPORTED</code><br>
     *  <code>RESULT_NO_BLUETOOTH_SERVICE</code><br>
     *  <code>RESULT_INVALID_BLUETOOTH_ADDRESS</code><br>
     *  <code>RESULT_BLUETOOTH_DISCONNECTED</code><br>
     *  <code>RESULT_UNEXPECTED_EVENT_STOP_SENDING</code><br>
     *  <code>RESULT_SMS_BLOCKED_DURING_EMERGENCY</code><br>
     *  <code>RESULT_SMS_SEND_RETRY_FAILED</code><br>
     *  <code>RESULT_REMOTE_EXCEPTION</code><br>
     *  <code>RESULT_NO_DEFAULT_SMS_APP</code><br>
     *  <code>RESULT_RIL_RADIO_NOT_AVAILABLE</code><br>
     *  <code>RESULT_RIL_SMS_SEND_FAIL_RETRY</code><br>
     *  <code>RESULT_RIL_NETWORK_REJECT</code><br>
     *  <code>RESULT_RIL_INVALID_STATE</code><br>
     *  <code>RESULT_RIL_INVALID_ARGUMENTS</code><br>
     *  <code>RESULT_RIL_NO_MEMORY</code><br>
     *  <code>RESULT_RIL_REQUEST_RATE_LIMITED</code><br>
     *  <code>RESULT_RIL_INVALID_SMS_FORMAT</code><br>
     *  <code>RESULT_RIL_SYSTEM_ERR</code><br>
     *  <code>RESULT_RIL_ENCODING_ERR</code><br>
     *  <code>RESULT_RIL_INVALID_SMSC_ADDRESS</code><br>
     *  <code>RESULT_RIL_MODEM_ERR</code><br>
     *  <code>RESULT_RIL_NETWORK_ERR</code><br>
     *  <code>RESULT_RIL_INTERNAL_ERR</code><br>
     *  <code>RESULT_RIL_REQUEST_NOT_SUPPORTED</code><br>
     *  <code>RESULT_RIL_INVALID_MODEM_STATE</code><br>
     *  <code>RESULT_RIL_NETWORK_NOT_READY</code><br>
     *  <code>RESULT_RIL_OPERATION_NOT_ALLOWED</code><br>
     *  <code>RESULT_RIL_NO_RESOURCES</code><br>
     *  <code>RESULT_RIL_CANCELLED</code><br>
     *  <code>RESULT_RIL_SIM_ABSENT</code><br>
     *  <code>RESULT_RIL_SIMULTANEOUS_SMS_AND_CALL_NOT_ALLOWED</code><br>
     *  <code>RESULT_RIL_ACCESS_BARRED</code><br>
     *  <code>RESULT_RIL_BLOCKED_DUE_TO_CALL</code><br>
     *  For <code>RESULT_ERROR_GENERIC_FAILURE</code> or any of the RESULT_RIL errors,
     *  the sentIntent may include the extra "errorCode" containing a radio technology specific
     *  value, generally only useful for troubleshooting.<br>
     * @param deliveryIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is delivered to the recipient.  The
     *  raw pdu of the status report is in the extended data ("pdu").
     * @param priority Priority level of the message
     *  Refer specification See 3GPP2 C.S0015-B, v2.0, table 4.5.9-1
     *  ---------------------------------
     *  PRIORITY      | Level of Priority
     *  ---------------------------------
     *      '00'      |     Normal
     *      '01'      |     Interactive
     *      '10'      |     Urgent
     *      '11'      |     Emergency
     *  ----------------------------------
     *  Any Other values included Negative considered as Invalid Priority Indicator of the message.
     * @param expectMore is a boolean to indicate the sending messages through same link or not.
     * @param validityPeriod Validity Period of the message in mins.
     *  Refer specification 3GPP TS 23.040 V6.8.1 section 9.2.3.12.1.
     *  Validity Period(Minimum) -> 5 mins
     *  Validity Period(Maximum) -> 635040 mins(i.e.63 weeks).
     *  Any Other values included Negative considered as Invalid Validity Period of the message.
     *
     * @throws IllegalArgumentException if destinationAddress or text are empty
     * {@hide}
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void sendTextMessage(
            String destinationAddress, String scAddress, String text,
            PendingIntent sentIntent, PendingIntent deliveryIntent,
            int priority, boolean expectMore, int validityPeriod) {
        sendTextMessageInternal(destinationAddress, scAddress, text, sentIntent, deliveryIntent,
                true /* persistMessage*/, priority, expectMore, validityPeriod);
    }

    private void sendTextMessageInternal(String destinationAddress, String scAddress,
            String text, PendingIntent sentIntent, PendingIntent deliveryIntent,
            boolean persistMessage, String packageName, String attributionTag, long messageId) {
        if (TextUtils.isEmpty(destinationAddress)) {
            throw new IllegalArgumentException("Invalid destinationAddress");
        }

        if (TextUtils.isEmpty(text)) {
            throw new IllegalArgumentException("Invalid message body");
        }

        // We will only show the SMS disambiguation dialog in the case that the message is being
        // persisted. This is for two reasons:
        // 1) Messages that are not persisted are sent by carrier/OEM apps for a specific
        //    subscription and require special permissions. These messages are usually not sent by
        //    the device user and should not have an SMS disambiguation dialog associated with them
        //    because the device user did not trigger them.
        // 2) The SMS disambiguation dialog ONLY checks to make sure that the user has the SEND_SMS
        //    permission. If we call resolveSubscriptionForOperation from a carrier/OEM app that has
        //    the correct MODIFY_PHONE_STATE or carrier permissions, but no SEND_SMS, it will throw
        //    an incorrect SecurityException.
        if (persistMessage) {
            resolveSubscriptionForOperation(new SubscriptionResolverResult() {
                @Override
                public void onSuccess(int subId) {
                    ISms iSms = getISmsServiceOrThrow();
                    try {
                        iSms.sendTextForSubscriber(subId, packageName, attributionTag,
                                destinationAddress, scAddress, text, sentIntent, deliveryIntent,
                                persistMessage, messageId);
                    } catch (RemoteException e) {
                        Log.e(TAG, "sendTextMessageInternal: Couldn't send SMS, exception - "
                                + e.getMessage() + " " + formatCrossStackMessageId(messageId));
                        notifySmsError(sentIntent, RESULT_REMOTE_EXCEPTION);
                    }
                }

                @Override
                public void onFailure() {
                    notifySmsError(sentIntent, RESULT_NO_DEFAULT_SMS_APP);
                }
            });
        } else {
            // Not persisting the message, used by sendTextMessageWithoutPersisting() and is not
            // visible to the user.
            ISms iSms = getISmsServiceOrThrow();
            try {
                iSms.sendTextForSubscriber(getSubscriptionId(), packageName, attributionTag,
                        destinationAddress, scAddress, text, sentIntent, deliveryIntent,
                        persistMessage, messageId);
            } catch (RemoteException e) {
                Log.e(TAG, "sendTextMessageInternal (no persist): Couldn't send SMS, exception - "
                        + e.getMessage() + " " + formatCrossStackMessageId(messageId));
                notifySmsError(sentIntent, RESULT_REMOTE_EXCEPTION);
            }
        }
    }

    /**
     * Send a text based SMS without writing it into the SMS Provider.
     *
     * <p>
     * The message will be sent directly over the network and will not be visible in SMS
     * applications. Intended for internal carrier use only.
     * </p>
     *
     * <p>Requires Permission: Both {@link android.Manifest.permission#SEND_SMS} and
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE}, or that the calling app has carrier
     * privileges (see {@link TelephonyManager#hasCarrierPrivileges}), or that the calling app is
     * the default IMS app (see
     * {@link CarrierConfigManager#KEY_CONFIG_IMS_PACKAGE_OVERRIDE_STRING}).
     * </p>
     *
     * <p class="note"><strong>Note:</strong> This method is intended for internal use by carrier
     * applications or the Telephony framework and will never trigger an SMS disambiguation
     * dialog. If this method is called on a device that has multiple active subscriptions, this
     * {@link SmsManager} instance has been created with {@link #getDefault()}, and no user-defined
     * default subscription is defined, the subscription ID associated with this message will be
     * INVALID, which will result in the SMS being sent on the subscription associated with logical
     * slot 0. Use {@link #getSmsManagerForSubscriptionId(int)} to ensure the SMS is sent on the
     * correct subscription.
     * </p>
     *
     * @see #sendTextMessage(String, String, String, PendingIntent, PendingIntent)
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(allOf = {
            android.Manifest.permission.MODIFY_PHONE_STATE,
            android.Manifest.permission.SEND_SMS
    })
    public void sendTextMessageWithoutPersisting(
            String destinationAddress, String scAddress, String text,
            PendingIntent sentIntent, PendingIntent deliveryIntent) {
        sendTextMessageInternal(destinationAddress, scAddress, text, sentIntent, deliveryIntent,
                false /* persistMessage */, getOpPackageName(),
                getAttributionTag(), 0L /* messageId */);
    }

    private void sendTextMessageInternal(
            String destinationAddress, String scAddress, String text,
            PendingIntent sentIntent, PendingIntent deliveryIntent, boolean persistMessage,
            int priority, boolean expectMore, int validityPeriod) {
        if (TextUtils.isEmpty(destinationAddress)) {
            throw new IllegalArgumentException("Invalid destinationAddress");
        }

        if (TextUtils.isEmpty(text)) {
            throw new IllegalArgumentException("Invalid message body");
        }

        if (priority < 0x00 || priority > 0x03) {
            Log.e(TAG, "Invalid Priority " + priority);
            priority = SMS_MESSAGE_PRIORITY_NOT_SPECIFIED;
        }

        if (validityPeriod < 0x05 || validityPeriod > 0x09b0a0) {
            Log.e(TAG, "Invalid Validity Period " + validityPeriod);
            validityPeriod = SMS_MESSAGE_PERIOD_NOT_SPECIFIED;
        }

        final int finalPriority = priority;
        final int finalValidity = validityPeriod;
        // We will only show the SMS disambiguation dialog in the case that the message is being
        // persisted. This is for two reasons:
        // 1) Messages that are not persisted are sent by carrier/OEM apps for a specific
        //    subscription and require special permissions. These messages are usually not sent by
        //    the device user and should not have an SMS disambiguation dialog associated with them
        //    because the device user did not trigger them.
        // 2) The SMS disambiguation dialog ONLY checks to make sure that the user has the SEND_SMS
        //    permission. If we call resolveSubscriptionForOperation from a carrier/OEM app that has
        //    the correct MODIFY_PHONE_STATE or carrier permissions, but no SEND_SMS, it will throw
        //    an incorrect SecurityException.
        if (persistMessage) {
            resolveSubscriptionForOperation(new SubscriptionResolverResult() {
                @Override
                public void onSuccess(int subId) {
                    try {
                        ISms iSms = getISmsServiceOrThrow();
                        if (iSms != null) {
                            iSms.sendTextForSubscriberWithOptions(subId,
                                    null, null, destinationAddress,
                                    scAddress,
                                    text, sentIntent, deliveryIntent, persistMessage, finalPriority,
                                    expectMore, finalValidity);
                        }
                    } catch (RemoteException e) {
                        Log.e(TAG, "sendTextMessageInternal: Couldn't send SMS, exception - "
                                + e.getMessage());
                        notifySmsError(sentIntent, RESULT_REMOTE_EXCEPTION);
                    }
                }

                @Override
                public void onFailure() {
                    notifySmsError(sentIntent, RESULT_NO_DEFAULT_SMS_APP);
                }
            });
        } else {
            try {
                ISms iSms = getISmsServiceOrThrow();
                if (iSms != null) {
                    iSms.sendTextForSubscriberWithOptions(getSubscriptionId(),
                            null, null, destinationAddress,
                            scAddress,
                            text, sentIntent, deliveryIntent, persistMessage, finalPriority,
                            expectMore, finalValidity);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "sendTextMessageInternal(no persist): Couldn't send SMS, exception - "
                        + e.getMessage());
                notifySmsError(sentIntent, RESULT_REMOTE_EXCEPTION);
            }
        }
    }

    /**
     * Send a text based SMS without writing it into the SMS Provider.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE} or the calling app has carrier
     * privileges.
     * </p>
     *
     * <p class="note"><strong>Note:</strong> This method is intended for internal use by carrier
     * applications or the Telephony framework and will never trigger an SMS disambiguation
     * dialog. If this method is called on a device that has multiple active subscriptions, this
     * {@link SmsManager} instance has been created with {@link #getDefault()}, and no user-defined
     * default subscription is defined, the subscription ID associated with this message will be
     * INVALID, which will result in the SMS being sent on the subscription associated with logical
     * slot 0. Use {@link #getSmsManagerForSubscriptionId(int)} to ensure the SMS is sent on the
     * correct subscription.
     * </p>
     *
     * @see #sendTextMessage(String, String, String, PendingIntent,
     * PendingIntent, int, boolean, int)
     * @hide
     */
    @UnsupportedAppUsage
    public void sendTextMessageWithoutPersisting(
            String destinationAddress, String scAddress, String text,
            PendingIntent sentIntent, PendingIntent deliveryIntent, int priority,
            boolean expectMore, int validityPeriod) {
        sendTextMessageInternal(destinationAddress, scAddress, text, sentIntent, deliveryIntent,
                false /* persistMessage */, priority, expectMore, validityPeriod);
    }

    /**
     *
     * Inject an SMS PDU into the android application framework.
     *
     * <p>Requires permission: {@link android.Manifest.permission#MODIFY_PHONE_STATE} or carrier
     * privileges per {@link android.telephony.TelephonyManager#hasCarrierPrivileges}.
     *
     * <p class="note"><strong>Note:</strong> This method is intended for internal use by carrier
     * applications or the Telephony framework and will never trigger an SMS disambiguation
     * dialog. If this method is called on a device that has multiple active subscriptions, this
     * {@link SmsManager} instance has been created with {@link #getDefault()}, and no user-defined
     * default subscription is defined, the subscription ID associated with this message will be
     * INVALID, which will result in the SMS being injected on the subscription associated with
     * logical slot 0. Use {@link #getSmsManagerForSubscriptionId(int)} to ensure the SMS is
     * delivered to the correct subscription.
     * </p>
     *
     * @param pdu is the byte array of pdu to be injected into android application framework
     * @param format is the format of SMS pdu ({@link SmsMessage#FORMAT_3GPP} or
     *  {@link SmsMessage#FORMAT_3GPP2})
     * @param receivedIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is successfully received by the
     *  android application framework, or failed. This intent is broadcasted at
     *  the same time an SMS received from radio is acknowledged back.
     *  The result code will be {@link android.provider.Telephony.Sms.Intents#RESULT_SMS_HANDLED}
     *  for success, or {@link android.provider.Telephony.Sms.Intents#RESULT_SMS_GENERIC_ERROR} or
     *  {@link #RESULT_REMOTE_EXCEPTION} for error.
     *
     * @throws IllegalArgumentException if the format is invalid.
     */
    public void injectSmsPdu(
            byte[] pdu, @SmsMessage.Format String format, PendingIntent receivedIntent) {
        if (!format.equals(SmsMessage.FORMAT_3GPP) && !format.equals(SmsMessage.FORMAT_3GPP2)) {
            // Format must be either 3gpp or 3gpp2.
            throw new IllegalArgumentException(
                    "Invalid pdu format. format must be either 3gpp or 3gpp2");
        }
        try {
            ISms iSms = TelephonyManager.getSmsService();
            if (iSms != null) {
                iSms.injectSmsPduForSubscriber(
                        getSubscriptionId(), pdu, format, receivedIntent);
            }
        } catch (RemoteException ex) {
            try {
                if (receivedIntent != null) {
                    receivedIntent.send(RESULT_REMOTE_EXCEPTION);
                }
            } catch (PendingIntent.CanceledException cx) {
                // Don't worry about it, we do not need to notify the caller if this is the case.
            }
        }
    }

    /**
     * Divide a message text into several fragments, none bigger than the maximum SMS message size.
     *
     * @param text the original message. Must not be null.
     * @return an <code>ArrayList</code> of strings that, in order, comprise the original message.
     * @throws IllegalArgumentException if text is null.
     */
    public ArrayList<String> divideMessage(String text) {
        if (null == text) {
            throw new IllegalArgumentException("text is null");
        }
        return SmsMessage.fragmentText(text, getSubscriptionId());
    }

    /**
     * Send a multi-part text based SMS.  The callee should have already
     * divided the message into correctly sized parts by calling
     * <code>divideMessage</code>.
     *
     * <p class="note"><strong>Note:</strong> Using this method requires that your app has the
     * {@link android.Manifest.permission#SEND_SMS} permission.</p>
     *
     * <p class="note"><strong>Note:</strong> Beginning with Android 4.4 (API level 19), if
     * <em>and only if</em> an app is not selected as the default SMS app, the system automatically
     * writes messages sent using this method to the SMS Provider (the default SMS app is always
     * responsible for writing its sent messages to the SMS Provider). For information about
     * how to behave as the default SMS app, see {@link android.provider.Telephony}.</p>
     *
     * <p class="note"><strong>Note:</strong> If {@link #getDefault()} is used to instantiate this
     * manager on a multi-SIM device, this operation may fail sending the SMS message because no
     * suitable default subscription could be found. In this case, if {@code sentIntent} is
     * non-null, then the {@link PendingIntent} will be sent with an error code
     * {@code RESULT_ERROR_GENERIC_FAILURE} and an extra string {@code "noDefault"} containing the
     * boolean value {@code true}. See {@link #getDefault()} for more information on the conditions
     * where this operation may fail.
     * </p>
     *
     * @param destinationAddress the address to send the message to
     * @param scAddress is the service center address or null to use
     *  the current default SMSC
     * @param parts an <code>ArrayList</code> of strings that, in order,
     *  comprise the original message
     * @param sentIntents if not null, an <code>ArrayList</code> of
     *  <code>PendingIntent</code>s (one for each message part) that is
     *  broadcast when the corresponding message part has been sent.
     *  The result code will be <code>Activity.RESULT_OK</code> for success,
     *  or one of these errors:<br>
     *  <code>RESULT_ERROR_GENERIC_FAILURE</code><br>
     *  <code>RESULT_ERROR_RADIO_OFF</code><br>
     *  <code>RESULT_ERROR_NULL_PDU</code><br>
     *  <code>RESULT_ERROR_NO_SERVICE</code><br>
     *  <code>RESULT_ERROR_LIMIT_EXCEEDED</code><br>
     *  <code>RESULT_ERROR_FDN_CHECK_FAILURE</code><br>
     *  <code>RESULT_ERROR_SHORT_CODE_NOT_ALLOWED</code><br>
     *  <code>RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED</code><br>
     *  <code>RESULT_RADIO_NOT_AVAILABLE</code><br>
     *  <code>RESULT_NETWORK_REJECT</code><br>
     *  <code>RESULT_INVALID_ARGUMENTS</code><br>
     *  <code>RESULT_INVALID_STATE</code><br>
     *  <code>RESULT_NO_MEMORY</code><br>
     *  <code>RESULT_INVALID_SMS_FORMAT</code><br>
     *  <code>RESULT_SYSTEM_ERROR</code><br>
     *  <code>RESULT_MODEM_ERROR</code><br>
     *  <code>RESULT_NETWORK_ERROR</code><br>
     *  <code>RESULT_ENCODING_ERROR</code><br>
     *  <code>RESULT_INVALID_SMSC_ADDRESS</code><br>
     *  <code>RESULT_OPERATION_NOT_ALLOWED</code><br>
     *  <code>RESULT_INTERNAL_ERROR</code><br>
     *  <code>RESULT_NO_RESOURCES</code><br>
     *  <code>RESULT_CANCELLED</code><br>
     *  <code>RESULT_REQUEST_NOT_SUPPORTED</code><br>
     *  <code>RESULT_NO_BLUETOOTH_SERVICE</code><br>
     *  <code>RESULT_INVALID_BLUETOOTH_ADDRESS</code><br>
     *  <code>RESULT_BLUETOOTH_DISCONNECTED</code><br>
     *  <code>RESULT_UNEXPECTED_EVENT_STOP_SENDING</code><br>
     *  <code>RESULT_SMS_BLOCKED_DURING_EMERGENCY</code><br>
     *  <code>RESULT_SMS_SEND_RETRY_FAILED</code><br>
     *  <code>RESULT_REMOTE_EXCEPTION</code><br>
     *  <code>RESULT_NO_DEFAULT_SMS_APP</code><br>
     *  <code>RESULT_RIL_RADIO_NOT_AVAILABLE</code><br>
     *  <code>RESULT_RIL_SMS_SEND_FAIL_RETRY</code><br>
     *  <code>RESULT_RIL_NETWORK_REJECT</code><br>
     *  <code>RESULT_RIL_INVALID_STATE</code><br>
     *  <code>RESULT_RIL_INVALID_ARGUMENTS</code><br>
     *  <code>RESULT_RIL_NO_MEMORY</code><br>
     *  <code>RESULT_RIL_REQUEST_RATE_LIMITED</code><br>
     *  <code>RESULT_RIL_INVALID_SMS_FORMAT</code><br>
     *  <code>RESULT_RIL_SYSTEM_ERR</code><br>
     *  <code>RESULT_RIL_ENCODING_ERR</code><br>
     *  <code>RESULT_RIL_INVALID_SMSC_ADDRESS</code><br>
     *  <code>RESULT_RIL_MODEM_ERR</code><br>
     *  <code>RESULT_RIL_NETWORK_ERR</code><br>
     *  <code>RESULT_RIL_INTERNAL_ERR</code><br>
     *  <code>RESULT_RIL_REQUEST_NOT_SUPPORTED</code><br>
     *  <code>RESULT_RIL_INVALID_MODEM_STATE</code><br>
     *  <code>RESULT_RIL_NETWORK_NOT_READY</code><br>
     *  <code>RESULT_RIL_OPERATION_NOT_ALLOWED</code><br>
     *  <code>RESULT_RIL_NO_RESOURCES</code><br>
     *  <code>RESULT_RIL_CANCELLED</code><br>
     *  <code>RESULT_RIL_SIM_ABSENT</code><br>
     *  <code>RESULT_RIL_SIMULTANEOUS_SMS_AND_CALL_NOT_ALLOWED</code><br>
     *  <code>RESULT_RIL_ACCESS_BARRED</code><br>
     *  <code>RESULT_RIL_BLOCKED_DUE_TO_CALL</code><br>
     *  For <code>RESULT_ERROR_GENERIC_FAILURE</code> or any of the RESULT_RIL errors,
     *  the sentIntent may include the extra "errorCode" containing a radio technology specific
     *  value, generally only useful for troubleshooting.<br>
     * @param deliveryIntents if not null, an <code>ArrayList</code> of
     *  <code>PendingIntent</code>s (one for each message part) that is
     *  broadcast when the corresponding message part has been delivered
     *  to the recipient.  The raw pdu of the status report is in the
     *  extended data ("pdu").
     *
     * @throws IllegalArgumentException if destinationAddress or data are empty
     */
    public void sendMultipartTextMessage(
            String destinationAddress, String scAddress, ArrayList<String> parts,
            ArrayList<PendingIntent> sentIntents, ArrayList<PendingIntent> deliveryIntents) {
        sendMultipartTextMessageInternal(destinationAddress, scAddress, parts, sentIntents,
                deliveryIntents, true /* persistMessage*/, getOpPackageName(),
                getAttributionTag(), 0L /* messageId */);
    }

    /**
     * Send a multi-part text based SMS. Same as #sendMultipartTextMessage(String, String,
     * ArrayList, ArrayList, ArrayList), but adds an optional messageId.
     * @param messageId An id that uniquely identifies the message requested to be sent.
     * Used for logging and diagnostics purposes. The id may be 0.
     *
     * @throws IllegalArgumentException if destinationAddress or data are empty
     *
     */
    public void sendMultipartTextMessage(
            @NonNull String destinationAddress, @Nullable String scAddress,
            @NonNull List<String> parts, @Nullable List<PendingIntent> sentIntents,
            @Nullable List<PendingIntent> deliveryIntents, long messageId) {
        sendMultipartTextMessageInternal(destinationAddress, scAddress, parts, sentIntents,
                deliveryIntents, true /* persistMessage*/, getOpPackageName(),
                getAttributionTag(), messageId);
    }

    /**
     * Similar method as #sendMultipartTextMessage(String, String, ArrayList, ArrayList, ArrayList)
     * With an additional argument.
     *
     * <p class="note"><strong>Note:</strong> This method is intended for internal use the Telephony
     * framework and will never trigger an SMS disambiguation dialog. If this method is called on a
     * device that has multiple active subscriptions, this {@link SmsManager} instance has been
     * created with {@link #getDefault()}, and no user-defined default subscription is defined, the
     * subscription ID associated with this message will be INVALID, which will result in the SMS
     * being sent on the subscription associated with logical slot 0. Use
     * {@link #getSmsManagerForSubscriptionId(int)} to ensure the SMS is sent on the correct
     * subscription.
     * </p>
     *
     * @param packageName serves as the default package name if the package name that is
     *        associated with the user id is null.
     */
    public void sendMultipartTextMessage(
            @NonNull String destinationAddress, @Nullable String scAddress,
            @NonNull List<String> parts, @Nullable List<PendingIntent> sentIntents,
            @Nullable List<PendingIntent> deliveryIntents, @NonNull String packageName,
            @Nullable String attributionTag) {
        sendMultipartTextMessageInternal(destinationAddress, scAddress, parts, sentIntents,
                deliveryIntents, true /* persistMessage*/, packageName, attributionTag,
                0L /* messageId */);
    }

    private void sendMultipartTextMessageInternal(
            String destinationAddress, String scAddress, List<String> parts,
            List<PendingIntent> sentIntents, List<PendingIntent> deliveryIntents,
            boolean persistMessage, String packageName, @Nullable String attributionTag,
            long messageId) {
        if (TextUtils.isEmpty(destinationAddress)) {
            throw new IllegalArgumentException("Invalid destinationAddress");
        }
        if (parts == null || parts.size() < 1) {
            throw new IllegalArgumentException("Invalid message body");
        }

        if (parts.size() > 1) {
            // We will only show the SMS disambiguation dialog in the case that the message is being
            // persisted. This is for two reasons:
            // 1) Messages that are not persisted are sent by carrier/OEM apps for a specific
            //    subscription and require special permissions. These messages are usually not sent
            //    by the device user and should not have an SMS disambiguation dialog associated
            //    with them because the device user did not trigger them.
            // 2) The SMS disambiguation dialog ONLY checks to make sure that the user has the
            //    SEND_SMS permission. If we call resolveSubscriptionForOperation from a carrier/OEM
            //    app that has the correct MODIFY_PHONE_STATE or carrier permissions, but no
            //    SEND_SMS, it will throw an incorrect SecurityException.
            if (persistMessage) {
                resolveSubscriptionForOperation(new SubscriptionResolverResult() {
                    @Override
                    public void onSuccess(int subId) {
                        try {
                            ISms iSms = getISmsServiceOrThrow();
                            iSms.sendMultipartTextForSubscriber(subId, packageName, attributionTag,
                                    destinationAddress, scAddress, parts, sentIntents,
                                    deliveryIntents, persistMessage, messageId);
                        } catch (RemoteException e) {
                            Log.e(TAG, "sendMultipartTextMessageInternal: Couldn't send SMS - "
                                    + e.getMessage() + " " + formatCrossStackMessageId(messageId));
                            notifySmsError(sentIntents, RESULT_REMOTE_EXCEPTION);
                        }
                    }

                    @Override
                    public void onFailure() {
                        notifySmsError(sentIntents, RESULT_NO_DEFAULT_SMS_APP);
                    }
                });
            } else {
                // Called by apps that are not user facing, don't show disambiguation dialog.
                try {
                    ISms iSms = getISmsServiceOrThrow();
                    if (iSms != null) {
                        iSms.sendMultipartTextForSubscriber(getSubscriptionId(), packageName,
                                attributionTag, destinationAddress, scAddress, parts, sentIntents,
                                deliveryIntents, persistMessage, messageId);
                    }
                } catch (RemoteException e) {
                    Log.e(TAG, "sendMultipartTextMessageInternal: Couldn't send SMS - "
                            + e.getMessage() + " " + formatCrossStackMessageId(messageId));
                    notifySmsError(sentIntents, RESULT_REMOTE_EXCEPTION);
                }
            }
        } else {
            PendingIntent sentIntent = null;
            PendingIntent deliveryIntent = null;
            if (sentIntents != null && sentIntents.size() > 0) {
                sentIntent = sentIntents.get(0);
            }
            if (deliveryIntents != null && deliveryIntents.size() > 0) {
                deliveryIntent = deliveryIntents.get(0);
            }
            sendTextMessageInternal(destinationAddress, scAddress, parts.get(0),
                    sentIntent, deliveryIntent, true, packageName, attributionTag, messageId);
        }
    }

    /**
     * Send a multi-part text based SMS without writing it into the SMS Provider.
     *
     * <p>
     * If this method is called on a device with multiple active subscriptions, this
     * {@link SmsManager} instance has been created with {@link #getDefault()}, and no user-defined
     * default subscription is defined, the subscription ID associated with this message will be
     * INVALID, which will result in the SMS sent on the subscription associated with slot
     * 0. Use {@link #getSmsManagerForSubscriptionId(int)} to ensure the SMS is sent using the
     * correct subscription.
     * </p>
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE} or the calling app has carrier
     * privileges.
     * </p>
     *
     * @see #sendMultipartTextMessage(String, String, ArrayList, ArrayList, ArrayList)
     * @hide
     **/
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void sendMultipartTextMessageWithoutPersisting(
            String destinationAddress, String scAddress, List<String> parts,
            List<PendingIntent> sentIntents, List<PendingIntent> deliveryIntents) {
        sendMultipartTextMessageInternal(destinationAddress, scAddress, parts, sentIntents,
                deliveryIntents, false /* persistMessage*/, getOpPackageName(),
                getAttributionTag(), 0L /* messageId */);
    }

    /**
     * Send a multi-part text based SMS with messaging options. The callee should have already
     * divided the message into correctly sized parts by calling
     * <code>divideMessage</code>.
     *
     * <p class="note"><strong>Note:</strong> Using this method requires that your app has the
     * {@link android.Manifest.permission#SEND_SMS} permission.</p>
     *
     * <p class="note"><strong>Note:</strong> Beginning with Android 4.4 (API level 19), if
     * <em>and only if</em> an app is not selected as the default SMS app, the system automatically
     * writes messages sent using this method to the SMS Provider (the default SMS app is always
     * responsible for writing its sent messages to the SMS Provider). For information about
     * how to behave as the default SMS app, see {@link android.provider.Telephony}.</p>
     *
     * <p class="note"><strong>Note:</strong> If {@link #getDefault()} is used to instantiate this
     * manager on a multi-SIM device, this operation may fail sending the SMS message because no
     * suitable default subscription could be found. In this case, if {@code sentIntent} is
     * non-null, then the {@link PendingIntent} will be sent with an error code
     * {@code RESULT_ERROR_GENERIC_FAILURE} and an extra string {@code "noDefault"} containing the
     * boolean value {@code true}. See {@link #getDefault()} for more information on the conditions
     * where this operation may fail.
     * </p>
     *
     * @param destinationAddress the address to send the message to
     * @param scAddress is the service center address or null to use
     *  the current default SMSC
     * @param parts an <code>ArrayList</code> of strings that, in order,
     *  comprise the original message
     * @param sentIntents if not null, an <code>ArrayList</code> of
     *  <code>PendingIntent</code>s (one for each message part) that is
     *  broadcast when the corresponding message part has been sent.
     *  The result code will be <code>Activity.RESULT_OK</code> for success,
     *  or one of these errors:<br>
     *  <code>RESULT_ERROR_GENERIC_FAILURE</code><br>
     *  <code>RESULT_ERROR_RADIO_OFF</code><br>
     *  <code>RESULT_ERROR_NULL_PDU</code><br>
     *  <code>RESULT_ERROR_NO_SERVICE</code><br>
     *  <code>RESULT_ERROR_LIMIT_EXCEEDED</code><br>
     *  <code>RESULT_ERROR_FDN_CHECK_FAILURE</code><br>
     *  <code>RESULT_ERROR_SHORT_CODE_NOT_ALLOWED</code><br>
     *  <code>RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED</code><br>
     *  <code>RESULT_RADIO_NOT_AVAILABLE</code><br>
     *  <code>RESULT_NETWORK_REJECT</code><br>
     *  <code>RESULT_INVALID_ARGUMENTS</code><br>
     *  <code>RESULT_INVALID_STATE</code><br>
     *  <code>RESULT_NO_MEMORY</code><br>
     *  <code>RESULT_INVALID_SMS_FORMAT</code><br>
     *  <code>RESULT_SYSTEM_ERROR</code><br>
     *  <code>RESULT_MODEM_ERROR</code><br>
     *  <code>RESULT_NETWORK_ERROR</code><br>
     *  <code>RESULT_ENCODING_ERROR</code><br>
     *  <code>RESULT_INVALID_SMSC_ADDRESS</code><br>
     *  <code>RESULT_OPERATION_NOT_ALLOWED</code><br>
     *  <code>RESULT_INTERNAL_ERROR</code><br>
     *  <code>RESULT_NO_RESOURCES</code><br>
     *  <code>RESULT_CANCELLED</code><br>
     *  <code>RESULT_REQUEST_NOT_SUPPORTED</code><br>
     *  <code>RESULT_NO_BLUETOOTH_SERVICE</code><br>
     *  <code>RESULT_INVALID_BLUETOOTH_ADDRESS</code><br>
     *  <code>RESULT_BLUETOOTH_DISCONNECTED</code><br>
     *  <code>RESULT_UNEXPECTED_EVENT_STOP_SENDING</code><br>
     *  <code>RESULT_SMS_BLOCKED_DURING_EMERGENCY</code><br>
     *  <code>RESULT_SMS_SEND_RETRY_FAILED</code><br>
     *  <code>RESULT_REMOTE_EXCEPTION</code><br>
     *  <code>RESULT_NO_DEFAULT_SMS_APP</code><br>
     *  <code>RESULT_RIL_RADIO_NOT_AVAILABLE</code><br>
     *  <code>RESULT_RIL_SMS_SEND_FAIL_RETRY</code><br>
     *  <code>RESULT_RIL_NETWORK_REJECT</code><br>
     *  <code>RESULT_RIL_INVALID_STATE</code><br>
     *  <code>RESULT_RIL_INVALID_ARGUMENTS</code><br>
     *  <code>RESULT_RIL_NO_MEMORY</code><br>
     *  <code>RESULT_RIL_REQUEST_RATE_LIMITED</code><br>
     *  <code>RESULT_RIL_INVALID_SMS_FORMAT</code><br>
     *  <code>RESULT_RIL_SYSTEM_ERR</code><br>
     *  <code>RESULT_RIL_ENCODING_ERR</code><br>
     *  <code>RESULT_RIL_INVALID_SMSC_ADDRESS</code><br>
     *  <code>RESULT_RIL_MODEM_ERR</code><br>
     *  <code>RESULT_RIL_NETWORK_ERR</code><br>
     *  <code>RESULT_RIL_INTERNAL_ERR</code><br>
     *  <code>RESULT_RIL_REQUEST_NOT_SUPPORTED</code><br>
     *  <code>RESULT_RIL_INVALID_MODEM_STATE</code><br>
     *  <code>RESULT_RIL_NETWORK_NOT_READY</code><br>
     *  <code>RESULT_RIL_OPERATION_NOT_ALLOWED</code><br>
     *  <code>RESULT_RIL_NO_RESOURCES</code><br>
     *  <code>RESULT_RIL_CANCELLED</code><br>
     *  <code>RESULT_RIL_SIM_ABSENT</code><br>
     *  <code>RESULT_RIL_SIMULTANEOUS_SMS_AND_CALL_NOT_ALLOWED</code><br>
     *  <code>RESULT_RIL_ACCESS_BARRED</code><br>
     *  <code>RESULT_RIL_BLOCKED_DUE_TO_CALL</code><br>
     *  For <code>RESULT_ERROR_GENERIC_FAILURE</code> or any of the RESULT_RIL errors,
     *  the sentIntent may include the extra "errorCode" containing a radio technology specific
     *  value, generally only useful for troubleshooting.<br>
     * @param deliveryIntents if not null, an <code>ArrayList</code> of
     *  <code>PendingIntent</code>s (one for each message part) that is
     *  broadcast when the corresponding message part has been delivered
     *  to the recipient.  The raw pdu of the status report is in the
     *  extended data ("pdu").
     * @param priority Priority level of the message
     *  Refer specification See 3GPP2 C.S0015-B, v2.0, table 4.5.9-1
     *  ---------------------------------
     *  PRIORITY      | Level of Priority
     *  ---------------------------------
     *      '00'      |     Normal
     *      '01'      |     Interactive
     *      '10'      |     Urgent
     *      '11'      |     Emergency
     *  ----------------------------------
     *  Any Other values included Negative considered as Invalid Priority Indicator of the message.
     * @param expectMore is a boolean to indicate the sending messages through same link or not.
     * @param validityPeriod Validity Period of the message in mins.
     *  Refer specification 3GPP TS 23.040 V6.8.1 section 9.2.3.12.1.
     *  Validity Period(Minimum) -> 5 mins
     *  Validity Period(Maximum) -> 635040 mins(i.e.63 weeks).
     *  Any Other values included Negative considered as Invalid Validity Period of the message.
     *
     * @throws IllegalArgumentException if destinationAddress or data are empty
     * {@hide}
     */
    @UnsupportedAppUsage
    public void sendMultipartTextMessage(
            String destinationAddress, String scAddress, ArrayList<String> parts,
            ArrayList<PendingIntent> sentIntents, ArrayList<PendingIntent> deliveryIntents,
            int priority, boolean expectMore, int validityPeriod) {
        sendMultipartTextMessageInternal(destinationAddress, scAddress, parts, sentIntents,
                deliveryIntents, true /* persistMessage*/, priority, expectMore,
                validityPeriod);
    }

    private void sendMultipartTextMessageInternal(
            String destinationAddress, String scAddress, List<String> parts,
            List<PendingIntent> sentIntents, List<PendingIntent> deliveryIntents,
            boolean persistMessage, int priority, boolean expectMore, int validityPeriod) {
        if (TextUtils.isEmpty(destinationAddress)) {
            throw new IllegalArgumentException("Invalid destinationAddress");
        }
        if (parts == null || parts.size() < 1) {
            throw new IllegalArgumentException("Invalid message body");
        }

        if (priority < 0x00 || priority > 0x03) {
            Log.e(TAG, "Invalid Priority " + priority);
            priority = SMS_MESSAGE_PRIORITY_NOT_SPECIFIED;
        }

        if (validityPeriod < 0x05 || validityPeriod > 0x09b0a0) {
            Log.e(TAG, "Invalid Validity Period " + validityPeriod);
            validityPeriod = SMS_MESSAGE_PERIOD_NOT_SPECIFIED;
        }

        if (parts.size() > 1) {
            final int finalPriority = priority;
            final int finalValidity = validityPeriod;
            if (persistMessage) {
                resolveSubscriptionForOperation(new SubscriptionResolverResult() {
                    @Override
                    public void onSuccess(int subId) {
                        try {
                            ISms iSms = getISmsServiceOrThrow();
                            if (iSms != null) {
                                iSms.sendMultipartTextForSubscriberWithOptions(subId,
                                        null, null, destinationAddress,
                                        scAddress, parts, sentIntents, deliveryIntents,
                                        persistMessage, finalPriority, expectMore, finalValidity);
                            }
                        } catch (RemoteException e) {
                            Log.e(TAG, "sendMultipartTextMessageInternal: Couldn't send SMS - "
                                    + e.getMessage());
                            notifySmsError(sentIntents, RESULT_REMOTE_EXCEPTION);
                        }
                    }

                    @Override
                    public void onFailure() {
                        notifySmsError(sentIntents, RESULT_NO_DEFAULT_SMS_APP);
                    }
                });
            } else {
                // Sent by apps that are not user visible, so don't show SIM disambiguation dialog.
                try {
                    ISms iSms = getISmsServiceOrThrow();
                    if (iSms != null) {
                        iSms.sendMultipartTextForSubscriberWithOptions(getSubscriptionId(),
                                null, null, destinationAddress,
                                scAddress, parts, sentIntents, deliveryIntents,
                                persistMessage, finalPriority, expectMore, finalValidity);
                    }
                } catch (RemoteException e) {
                    Log.e(TAG, "sendMultipartTextMessageInternal (no persist): Couldn't send SMS - "
                            + e.getMessage());
                    notifySmsError(sentIntents, RESULT_REMOTE_EXCEPTION);
                }
            }
        } else {
            PendingIntent sentIntent = null;
            PendingIntent deliveryIntent = null;
            if (sentIntents != null && sentIntents.size() > 0) {
                sentIntent = sentIntents.get(0);
            }
            if (deliveryIntents != null && deliveryIntents.size() > 0) {
                deliveryIntent = deliveryIntents.get(0);
            }
            sendTextMessageInternal(destinationAddress, scAddress, parts.get(0),
                    sentIntent, deliveryIntent, persistMessage, priority, expectMore,
                    validityPeriod);
        }
    }

    /**
     * Send a data based SMS to a specific application port.
     *
     * <p class="note"><strong>Note:</strong> Using this method requires that your app has the
     * {@link android.Manifest.permission#SEND_SMS} permission.</p>
     *
     * <p class="note"><strong>Note:</strong> If {@link #getDefault()} is used to instantiate this
     * manager on a multi-SIM device, this operation may fail sending the SMS message because no
     * suitable default subscription could be found. In this case, if {@code sentIntent} is
     * non-null, then the {@link PendingIntent} will be sent with an error code
     * {@code RESULT_ERROR_GENERIC_FAILURE} and an extra string {@code "noDefault"} containing the
     * boolean value {@code true}. See {@link #getDefault()} for more information on the conditions
     * where this operation may fail.
     * </p>
     *
     * @param destinationAddress the address to send the message to
     * @param scAddress is the service center address or null to use
     *  the current default SMSC
     * @param destinationPort the port to deliver the message to
     * @param data the body of the message to send
     * @param sentIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is successfully sent, or failed.
     *  The result code will be <code>Activity.RESULT_OK</code> for success,
     *  or one of these errors:<br>
     *  <code>RESULT_ERROR_GENERIC_FAILURE</code><br>
     *  <code>RESULT_ERROR_RADIO_OFF</code><br>
     *  <code>RESULT_ERROR_NULL_PDU</code><br>
     *  <code>RESULT_ERROR_NO_SERVICE</code><br>
     *  <code>RESULT_ERROR_LIMIT_EXCEEDED</code><br>
     *  <code>RESULT_ERROR_FDN_CHECK_FAILURE</code><br>
     *  <code>RESULT_ERROR_SHORT_CODE_NOT_ALLOWED</code><br>
     *  <code>RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED</code><br>
     *  <code>RESULT_RADIO_NOT_AVAILABLE</code><br>
     *  <code>RESULT_NETWORK_REJECT</code><br>
     *  <code>RESULT_INVALID_ARGUMENTS</code><br>
     *  <code>RESULT_INVALID_STATE</code><br>
     *  <code>RESULT_NO_MEMORY</code><br>
     *  <code>RESULT_INVALID_SMS_FORMAT</code><br>
     *  <code>RESULT_SYSTEM_ERROR</code><br>
     *  <code>RESULT_MODEM_ERROR</code><br>
     *  <code>RESULT_NETWORK_ERROR</code><br>
     *  <code>RESULT_ENCODING_ERROR</code><br>
     *  <code>RESULT_INVALID_SMSC_ADDRESS</code><br>
     *  <code>RESULT_OPERATION_NOT_ALLOWED</code><br>
     *  <code>RESULT_INTERNAL_ERROR</code><br>
     *  <code>RESULT_NO_RESOURCES</code><br>
     *  <code>RESULT_CANCELLED</code><br>
     *  <code>RESULT_REQUEST_NOT_SUPPORTED</code><br>
     *  <code>RESULT_NO_BLUETOOTH_SERVICE</code><br>
     *  <code>RESULT_INVALID_BLUETOOTH_ADDRESS</code><br>
     *  <code>RESULT_BLUETOOTH_DISCONNECTED</code><br>
     *  <code>RESULT_UNEXPECTED_EVENT_STOP_SENDING</code><br>
     *  <code>RESULT_SMS_BLOCKED_DURING_EMERGENCY</code><br>
     *  <code>RESULT_SMS_SEND_RETRY_FAILED</code><br>
     *  <code>RESULT_REMOTE_EXCEPTION</code><br>
     *  <code>RESULT_NO_DEFAULT_SMS_APP</code><br>
     *  <code>RESULT_RIL_RADIO_NOT_AVAILABLE</code><br>
     *  <code>RESULT_RIL_SMS_SEND_FAIL_RETRY</code><br>
     *  <code>RESULT_RIL_NETWORK_REJECT</code><br>
     *  <code>RESULT_RIL_INVALID_STATE</code><br>
     *  <code>RESULT_RIL_INVALID_ARGUMENTS</code><br>
     *  <code>RESULT_RIL_NO_MEMORY</code><br>
     *  <code>RESULT_RIL_REQUEST_RATE_LIMITED</code><br>
     *  <code>RESULT_RIL_INVALID_SMS_FORMAT</code><br>
     *  <code>RESULT_RIL_SYSTEM_ERR</code><br>
     *  <code>RESULT_RIL_ENCODING_ERR</code><br>
     *  <code>RESULT_RIL_INVALID_SMSC_ADDRESS</code><br>
     *  <code>RESULT_RIL_MODEM_ERR</code><br>
     *  <code>RESULT_RIL_NETWORK_ERR</code><br>
     *  <code>RESULT_RIL_INTERNAL_ERR</code><br>
     *  <code>RESULT_RIL_REQUEST_NOT_SUPPORTED</code><br>
     *  <code>RESULT_RIL_INVALID_MODEM_STATE</code><br>
     *  <code>RESULT_RIL_NETWORK_NOT_READY</code><br>
     *  <code>RESULT_RIL_OPERATION_NOT_ALLOWED</code><br>
     *  <code>RESULT_RIL_NO_RESOURCES</code><br>
     *  <code>RESULT_RIL_CANCELLED</code><br>
     *  <code>RESULT_RIL_SIM_ABSENT</code><br>
     *  <code>RESULT_RIL_SIMULTANEOUS_SMS_AND_CALL_NOT_ALLOWED</code><br>
     *  <code>RESULT_RIL_ACCESS_BARRED</code><br>
     *  <code>RESULT_RIL_BLOCKED_DUE_TO_CALL</code><br>
     *  For <code>RESULT_ERROR_GENERIC_FAILURE</code> or any of the RESULT_RIL errors,
     *  the sentIntent may include the extra "errorCode" containing a radio technology specific
     *  value, generally only useful for troubleshooting.<br>
     * @param deliveryIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is delivered to the recipient.  The
     *  raw pdu of the status report is in the extended data ("pdu").
     *
     * @throws IllegalArgumentException if destinationAddress or data are empty
     */
    public void sendDataMessage(
            String destinationAddress, String scAddress, short destinationPort,
            byte[] data, PendingIntent sentIntent, PendingIntent deliveryIntent) {
        if (TextUtils.isEmpty(destinationAddress)) {
            throw new IllegalArgumentException("Invalid destinationAddress");
        }

        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Invalid message data");
        }

        resolveSubscriptionForOperation(new SubscriptionResolverResult() {
            @Override
            public void onSuccess(int subId) {
                try {
                    ISms iSms = getISmsServiceOrThrow();
                    iSms.sendDataForSubscriber(subId, null, null, destinationAddress, scAddress,
                            destinationPort & 0xFFFF, data, sentIntent, deliveryIntent);
                } catch (RemoteException e) {
                    Log.e(TAG, "sendDataMessage: Couldn't send SMS - Exception: " + e.getMessage());
                    notifySmsError(sentIntent, RESULT_REMOTE_EXCEPTION);
                }
            }
            @Override
            public void onFailure() {
                notifySmsError(sentIntent, RESULT_NO_DEFAULT_SMS_APP);
            }
        });
    }

    /**
     * Get the SmsManager associated with the default subscription id. The instance will always be
     * associated with the default subscription id, even if the default subscription id changes.
     *
     * <p class="note"><strong>Note:</strong> For devices that support multiple active subscriptions
     * at a time, SmsManager will track the subscription set by the user as the default SMS
     * subscription. If the user has not set a default, {@link SmsManager} may
     * start an activity to kick off a subscription disambiguation dialog. Most operations will not
     * complete until the user has chosen the subscription that will be associated with the
     * operation. If the user cancels the dialog without choosing a subscription, one of the
     * following will happen, depending on the target SDK version of the application. For
     * compatibility purposes, if the target SDK level is <= 28, telephony will still send the SMS
     * over the first available subscription. If the target SDK level is > 28, the operation will
     * fail to complete.
     * </p>
     *
     * <p class="note"><strong>Note:</strong> If this method is used to perform an operation on a
     * device that has multiple active subscriptions, the user has not set a default SMS
     * subscription, and the operation is being performed while the application is not in the
     * foreground, the SMS disambiguation dialog will not be shown. The result of the operation will
     * conclude as if the user cancelled the disambiguation dialog and the operation will finish as
     * outlined above, depending on the target SDK version of the calling application. It is safer
     * to use {@link #getSmsManagerForSubscriptionId(int)} if the application will perform the
     * operation while in the background because this can cause unpredictable results, such as the
     * operation being sent over the wrong subscription or failing completely, depending on the
     * user's default SMS subscription setting.
     * </p>
     *
     * @return the {@link SmsManager} associated with the default subscription id.
     *
     * @see SubscriptionManager#getDefaultSmsSubscriptionId()
     *
     * @deprecated Use {@link Context#getSystemService Context.getSystemService(SmsManager.class)}
     * instead
     */
    @Deprecated
    public static SmsManager getDefault() {
        return DEFAULT_INSTANCE;
    }

    /**
     * Get the instance of the SmsManager associated with a particular context and subscription ID.
     *
     * @param context The context the manager belongs to
     * @param subId an SMS subscription ID, typically accessed using {@link SubscriptionManager}
     *
     * @return the instance of the SmsManager associated with subscription
     *
     * @hide
     */
    public static @NonNull SmsManager getSmsManagerForContextAndSubscriptionId(
            @Nullable Context context, int subId) {
        synchronized(sLockObject) {
            Pair<Context, Integer> key = new Pair<>(context, subId);

            SmsManager smsManager = sSubInstances.get(key);
            if (smsManager == null) {
                smsManager = new SmsManager(context, subId);
                sSubInstances.put(key, smsManager);
            }
            return smsManager;
        }
    }

    /**
     * Get the instance of the SmsManager associated with a particular subscription ID.
     *
     * <p class="note"><strong>Note:</strong> Constructing an {@link SmsManager} in this manner will
     * never cause an SMS disambiguation dialog to appear, unlike {@link #getDefault()}.
     * </p>
     *
     * @param subId an SMS subscription ID, typically accessed using {@link SubscriptionManager}
     * @return the instance of the SmsManager associated with subscription
     *
     * @see SubscriptionManager#getActiveSubscriptionInfoList()
     * @see SubscriptionManager#getDefaultSmsSubscriptionId()
     * @deprecated Use {@link Context#getSystemService Context.getSystemService(SmsManager.class)}
     * .{@link #createForSubscriptionId createForSubscriptionId(subId)} instead
     */
    @Deprecated
    public static SmsManager getSmsManagerForSubscriptionId(int subId) {
        return getSmsManagerForContextAndSubscriptionId(null, subId);
    }

    /**
     * Get the instance of the SmsManager associated with a particular subscription ID.
     *
     * <p class="note"><strong>Note:</strong> Constructing an {@link SmsManager} in this manner will
     * never cause an SMS disambiguation dialog to appear, unlike {@link #getDefault()}.
     * </p>
     *
     * @param subId an SMS subscription ID, typically accessed using {@link SubscriptionManager}
     * @return the instance of the SmsManager associated with subscription
     *
     * @see SubscriptionManager#getActiveSubscriptionInfoList()
     * @see SubscriptionManager#getDefaultSmsSubscriptionId()
     */
    public @NonNull SmsManager createForSubscriptionId(int subId) {
        return getSmsManagerForContextAndSubscriptionId(mContext, subId);
    }

    private SmsManager(@Nullable Context context, int subId) {
        mContext = context;
        mSubId = subId;
    }

    /**
     * Get the associated subscription id. If the instance was returned by {@link #getDefault()},
     * then this method may return different values at different points in time (if the user
     * changes the default subscription id).
     *
     * <p class="note"><strong>Note:</strong> This method used to display a disambiguation dialog to
     * the user asking them to choose a default subscription to send SMS messages over if they
     * haven't chosen yet. Starting in API level 29, we allow the user to not have a default set as
     * a valid option for the default SMS subscription on multi-SIM devices. We no longer show the
     * disambiguation dialog and return {@link SubscriptionManager#INVALID_SUBSCRIPTION_ID} if the
     * device has multiple active subscriptions and no default is set.
     * </p>
     *
     * @return associated subscription ID or {@link SubscriptionManager#INVALID_SUBSCRIPTION_ID} if
     * the default subscription id cannot be determined or the device has multiple active
     * subscriptions and and no default is set ("ask every time") by the user.
     */
    public int getSubscriptionId() {
        try {
            return (mSubId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID)
                    ? getISmsServiceOrThrow().getPreferredSmsSubscription() : mSubId;
        } catch (RemoteException e) {
            return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        }
    }

    /**
     * Resolves the subscription id to use for the associated operation if
     * {@link #getSubscriptionId()} returns {@link SubscriptionManager#INVALID_SUBSCRIPTION_ID}.
     *
     * If app targets API level 28 or below and they are either sending the SMS from the background
     * or the device has more than one active subscription available and no default is set, we will
     * use the first logical slot to send the SMS and possibly fail later in the SMS sending
     * process.
     *
     * Regardless of the API level, if the app is the foreground app, then we will show the SMS
     * disambiguation dialog. If the app is in the background and tries to perform an operation, we
     * will not show the disambiguation dialog.
     *
     * See {@link #getDefault()} for a detailed explanation of how this method operates.
     *
     * @param resolverResult The callback that will be called when the subscription is resolved or
     *                       fails to be resolved.
     */
    private void resolveSubscriptionForOperation(SubscriptionResolverResult resolverResult) {
        int subId = getSubscriptionId();
        boolean isSmsSimPickActivityNeeded = false;
        try {
            ISms iSms = getISmsService();
            if (iSms != null) {
                // Determines if the SMS SIM pick activity should be shown. This is only shown if:
                // 1) The device has multiple active subscriptions and an SMS default subscription
                //    hasn't been set, and
                // 2) SmsManager is being called from the foreground app.
                // Android does not allow background activity starts, so we need to block this.
                // if Q+, do not perform requested operation if these two operations are not set. If
                // <P, perform these operations on phone 0 (for compatibility purposes, since we
                // used to not wait for the result of this activity).
                isSmsSimPickActivityNeeded = iSms.isSmsSimPickActivityNeeded(subId);
            }
        } catch (RemoteException ex) {
            Log.e(TAG, "resolveSubscriptionForOperation", ex);
        }
        if (!isSmsSimPickActivityNeeded) {
            sendResolverResult(resolverResult, subId, false /*pickActivityShown*/);
            return;
        }
        // We need to ask the user pick an appropriate subid for the operation.
        Log.d(TAG, "resolveSubscriptionForOperation isSmsSimPickActivityNeeded is true for calling"
                + " package. ");
        try {
            // Create the SMS pick activity and call back once the activity is complete. Can't do
            // it here because we do not have access to the activity context that is performing this
            // operation.
            // Requires that the calling process has the SEND_SMS permission.
            getITelephony().enqueueSmsPickResult(null, null,
                    new IIntegerConsumer.Stub() {
                        @Override
                        public void accept(int subId) {
                            // Runs on binder thread attached to this app's process.
                            sendResolverResult(resolverResult, subId, true /*pickActivityShown*/);
                        }
                    });
        } catch (RemoteException ex) {
            Log.e(TAG, "Unable to launch activity", ex);
            // pickActivityShown is true here because we want to call sendResolverResult and always
            // have this operation fail. This is because we received a RemoteException here, which
            // means that telephony is not available and the next operation to Telephony will fail
            // as well anyways, so we might as well shortcut fail here first.
            sendResolverResult(resolverResult, subId, true /*pickActivityShown*/);
        }
    }

    /**
     * To check the SDK version for SmsManager.sendResolverResult method.
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.P)
    private static final long GET_TARGET_SDK_VERSION_CODE_CHANGE = 145147528L;

    private void sendResolverResult(SubscriptionResolverResult resolverResult, int subId,
            boolean pickActivityShown) {
        if (SubscriptionManager.isValidSubscriptionId(subId)) {
            resolverResult.onSuccess(subId);
            return;
        }

        if (!Compatibility.isChangeEnabled(GET_TARGET_SDK_VERSION_CODE_CHANGE)
                && !pickActivityShown) {
            // Do not fail, return a success with an INVALID subid for apps targeting P or below
            // that tried to perform an operation and the SMS disambiguation dialog was never shown,
            // as these applications may not have been written to handle the failure case properly.
            // This will resolve to performing the operation on phone 0 in telephony.
            resolverResult.onSuccess(subId);
        } else {
            // Fail if the app targets Q or above or it targets P and below and the disambiguation
            // dialog was shown and the user clicked out of it.
            resolverResult.onFailure();
        }
    }

    private static ITelephony getITelephony() {
        ITelephony binder = ITelephony.Stub.asInterface(
                TelephonyFrameworkInitializer
                        .getTelephonyServiceManager()
                        .getTelephonyServiceRegisterer()
                        .get());
        if (binder == null) {
            throw new RuntimeException("Could not find Telephony Service.");
        }
        return binder;
    }

    private static void notifySmsError(PendingIntent pendingIntent, int error) {
        if (pendingIntent != null) {
            try {
                pendingIntent.send(error);
            } catch (PendingIntent.CanceledException e) {
                // Don't worry about it, we do not need to notify the caller if this is the case.
            }
        }
    }

    private static void notifySmsError(List<PendingIntent> pendingIntents, int error) {
        if (pendingIntents != null) {
            for (PendingIntent pendingIntent : pendingIntents) {
                notifySmsError(pendingIntent, error);
            }
        }
    }

    /**
     * Returns the ISms service, or throws an UnsupportedOperationException if
     * the service does not exist.
     */
    private static ISms getISmsServiceOrThrow() {
        ISms iSms = TelephonyManager.getSmsService();
        if (iSms == null) {
            throw new UnsupportedOperationException("Sms is not supported");
        }
        return iSms;
    }

    private static ISms getISmsService() {
        return TelephonyManager.getSmsService();
    }

    /**
     * Copies a raw SMS PDU to the ICC.
     * ICC (Integrated Circuit Card) is the card of the device.
     * For example, this can be the SIM or USIM for GSM.
     *
     * <p class="note"><strong>Note:</strong> This method is intended for internal use by carrier
     * applications or the Telephony framework and will never trigger an SMS disambiguation
     * dialog. If this method is called on a device that has multiple active subscriptions, this
     * {@link SmsManager} instance has been created with {@link #getDefault()}, and no user-defined
     * default subscription is defined, the subscription ID associated with this message will be
     * INVALID, which will result in the operation being completed on the subscription associated
     * with logical slot 0. Use {@link #getSmsManagerForSubscriptionId(int)} to ensure the
     * operation is performed on the correct subscription.
     * </p>
     *
     * @param smsc the SMSC for this messag or null for the default SMSC.
     * @param pdu the raw PDU to store.
     * @param status message status. One of these status:
     *               <code>STATUS_ON_ICC_READ</code>
     *               <code>STATUS_ON_ICC_UNREAD</code>
     *               <code>STATUS_ON_ICC_SENT</code>
     *               <code>STATUS_ON_ICC_UNSENT</code>
     * @return true for success. Otherwise false.
     *
     * @throws IllegalArgumentException if pdu is null.
     * @hide
     */
    @RequiresPermission(Manifest.permission.ACCESS_MESSAGES_ON_ICC)
    public boolean copyMessageToIcc(
            @Nullable byte[] smsc, @NonNull byte[] pdu, @StatusOnIcc int status) {
        boolean success = false;

        if (pdu == null) {
            throw new IllegalArgumentException("pdu is null");
        }
        try {
            ISms iSms = getISmsService();
            if (iSms != null) {
                success = iSms.copyMessageToIccEfForSubscriber(getSubscriptionId(),
                        null,
                        status, pdu, smsc);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return success;
    }

    /**
     * Deletes the specified message from the ICC.
     * ICC (Integrated Circuit Card) is the card of the device.
     * For example, this can be the SIM or USIM for GSM.
     *
     * <p class="note"><strong>Note:</strong> This method is intended for internal use by carrier
     * applications or the Telephony framework and will never trigger an SMS disambiguation
     * dialog. If this method is called on a device that has multiple active subscriptions, this
     * {@link SmsManager} instance has been created with {@link #getDefault()}, and no user-defined
     * default subscription is defined, the subscription ID associated with this message will be
     * INVALID, which will result in the operation being completed on the subscription associated
     * with logical slot 0. Use {@link #getSmsManagerForSubscriptionId(int)} to ensure the
     * operation is performed on the correct subscription.
     * </p>
     *
     * @param messageIndex the message index of the message in the ICC (1-based index).
     * @return true for success, false if the operation fails. Failure can be due to IPC failure,
     * RIL/modem error which results in SMS failed to be deleted on SIM
     *
     * {@hide}
     */
    @UnsupportedAppUsage
    @RequiresPermission(Manifest.permission.ACCESS_MESSAGES_ON_ICC)
    public boolean deleteMessageFromIcc(int messageIndex) {
        boolean success = false;

        try {
            ISms iSms = getISmsService();
            if (iSms != null) {
                success = iSms.updateMessageOnIccEfForSubscriber(getSubscriptionId(),
                        null,
                        messageIndex, STATUS_ON_ICC_FREE, null /* pdu */);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return success;
    }

    /**
     * Update the specified message on the ICC.
     * ICC (Integrated Circuit Card) is the card of the device.
     * For example, this can be the SIM or USIM for GSM.
     *
     * <p class="note"><strong>Note:</strong> This method is intended for internal use by carrier
     * applications or the Telephony framework and will never trigger an SMS disambiguation
     * dialog. If this method is called on a device that has multiple active subscriptions, this
     * {@link SmsManager} instance has been created with {@link #getDefault()}, and no user-defined
     * default subscription is defined, the subscription ID associated with this message will be
     * INVALID, which will result in the operation being completed on the subscription associated
     * with logical slot 0. Use {@link #getSmsManagerForSubscriptionId(int)} to ensure the
     * operation is performed on the correct subscription.
     * </p>
     *
     * @param messageIndex record index of message to update
     * @param newStatus new message status (STATUS_ON_ICC_READ,
     *                  STATUS_ON_ICC_UNREAD, STATUS_ON_ICC_SENT,
     *                  STATUS_ON_ICC_UNSENT, STATUS_ON_ICC_FREE)
     * @param pdu the raw PDU to store
     * @return true for success
     *
     * {@hide}
     */
    @UnsupportedAppUsage
    @RequiresPermission(Manifest.permission.ACCESS_MESSAGES_ON_ICC)
    public boolean updateMessageOnIcc(int messageIndex, int newStatus, byte[] pdu) {
        boolean success = false;

        try {
            ISms iSms = getISmsService();
            if (iSms != null) {
                success = iSms.updateMessageOnIccEfForSubscriber(getSubscriptionId(),
                        null,
                        messageIndex, newStatus, pdu);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return success;
    }

    /**
     * Retrieves all messages currently stored on the ICC.
     * ICC (Integrated Circuit Card) is the card of the device.
     * For example, this can be the SIM or USIM for GSM.
     *
     * <p class="note"><strong>Note:</strong> This method is intended for internal use by carrier
     * applications or the Telephony framework and will never trigger an SMS disambiguation
     * dialog. If this method is called on a device that has multiple active subscriptions, this
     * {@link SmsManager} instance has been created with {@link #getDefault()}, and no user-defined
     * default subscription is defined, the subscription ID associated with this message will be
     * INVALID, which will result in the operation being completed on the subscription associated
     * with logical slot 0. Use {@link #getSmsManagerForSubscriptionId(int)} to ensure the
     * operation is performed on the correct subscription.
     * </p>
     *
     * @return <code>List</code> of <code>SmsMessage</code> objects for valid records only.
     *
     * {@hide}
     */
    @RequiresPermission(Manifest.permission.ACCESS_MESSAGES_ON_ICC)
    public @NonNull List<SmsMessage> getMessagesFromIcc() {
        return getAllMessagesFromIcc();
    }

    /**
     * @return <code>ArrayList</code> of <code>SmsMessage</code> objects
     *
     * This is similar to {@link #getMessagesFromIcc} except that it will return ArrayList.
     * Suggested to use {@link #getMessagesFromIcc} instead.
     *
     * {@hide}
     */
    @UnsupportedAppUsage
    public ArrayList<SmsMessage> getAllMessagesFromIcc() {
        List<SmsRawData> records = null;

        try {
            ISms iSms = getISmsService();
            if (iSms != null) {
                records = iSms.getAllMessagesFromIccEfForSubscriber(
                        getSubscriptionId(),
                        null);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return createMessageListFromRawRecords(records);
    }

    /**
     * Enable reception of cell broadcast (SMS-CB) messages with the given
     * message identifier range and RAN type. The RAN type specifies if this message ID
     * belongs to 3GPP (GSM) or 3GPP2(CDMA). Note that if two different clients enable
     * the same message identifier, they must both disable it for the device to stop
     * receiving those messages. All received messages will be broadcast in an
     * intent with the action "android.provider.Telephony.SMS_CB_RECEIVED".
     * Note: This call is blocking, callers may want to avoid calling it from
     * the main thread of an application.
     *
     * <p class="note"><strong>Note:</strong> This method is intended for internal use by carrier
     * applications or the Telephony framework and will never trigger an SMS disambiguation
     * dialog. If this method is called on a device that has multiple active subscriptions, this
     * {@link SmsManager} instance has been created with {@link #getDefault()}, and no user-defined
     * default subscription is defined, the subscription ID associated with this message will be
     * {@link SubscriptionManager#INVALID_SUBSCRIPTION_ID}, which will result in the operation
     * being completed on the subscription associated with logical slot 0. Use
     * {@link #getSmsManagerForSubscriptionId(int)} to ensure the operation is performed on the
     * correct subscription.
     * </p>
     *
     * <p>Requires {@link android.Manifest.permission#RECEIVE_EMERGENCY_BROADCAST}</p>
     *
     * @param startMessageId first message identifier as specified in TS 23.041 (3GPP)
     * or C.R1001-G (3GPP2)
     * @param endMessageId last message identifier as specified in TS 23.041 (3GPP)
     * or C.R1001-G (3GPP2)
     * @param ranType the message format as defined in {@link SmsCbMessage}
     * @return true if successful, false if the modem reports a failure (e.g. the given range or
     * RAN type is invalid).
     * @see #disableCellBroadcastRange(int, int, int)
     *
     * @throws IllegalArgumentException if endMessageId < startMessageId
     * {@hide}
     */
    @SystemApi
    public boolean enableCellBroadcastRange(int startMessageId, int endMessageId,
            @android.telephony.SmsCbMessage.MessageFormat int ranType) {
        boolean success = false;
        if (endMessageId < startMessageId) {
            throw new IllegalArgumentException("endMessageId < startMessageId");
        }
        try {
            ISms iSms = getISmsService();
            if (iSms != null) {
                // If getSubscriptionId() returns INVALID or an inactive subscription, we will use
                // the default phone internally.
                int subId = getSubscriptionId();
                success = iSms.enableCellBroadcastRangeForSubscriber(subId,
                        startMessageId, endMessageId, ranType);
                Rlog.d(TAG, "enableCellBroadcastRange: " + (success ? "succeeded" : "failed")
                        + " at calling enableCellBroadcastRangeForSubscriber. subId = " + subId);
            }
        } catch (RemoteException ex) {
            Rlog.d(TAG, "enableCellBroadcastRange: " + ex.getStackTrace());
            // ignore it
        }

        return success;
    }

    /**
     * Disable reception of cell broadcast (SMS-CB) messages with the given
     * message identifier range and RAN type. The RAN type specify this message
     * ID range belong to 3GPP (GSM) or 3GPP2(CDMA). Note that if two different
     * clients enable the same message identifier, they must both disable it for
     * the device to stop receiving those messages.
     * Note: This call is blocking, callers may want to avoid calling it from
     * the main thread of an application.
     *
     * <p class="note"><strong>Note:</strong> This method is intended for internal use by carrier
     * applications or the Telephony framework and will never trigger an SMS disambiguation
     * dialog. If this method is called on a device that has multiple active subscriptions, this
     * {@link SmsManager} instance has been created with {@link #getDefault()}, and no user-defined
     * default subscription is defined, the subscription ID associated with this message will be
     * INVALID, which will result in the operation being completed on the subscription associated
     * with logical slot 0. Use {@link #getSmsManagerForSubscriptionId(int)} to ensure the
     * operation is performed on the correct subscription.
     * </p>
     *
     * <p>Requires {@link android.Manifest.permission#RECEIVE_EMERGENCY_BROADCAST}</p>
     *
     * @param startMessageId first message identifier as specified in TS 23.041 (3GPP)
     * or C.R1001-G (3GPP2)
     * @param endMessageId last message identifier as specified in TS 23.041 (3GPP)
     * or C.R1001-G (3GPP2)
     * @param ranType the message format as defined in {@link SmsCbMessage}
     * @return true if successful, false if the modem reports a failure (e.g. the given range or
     * RAN type is invalid).
     *
     * @see #enableCellBroadcastRange(int, int, int)
     *
     * @throws IllegalArgumentException if endMessageId < startMessageId
     * {@hide}
     */
    @SystemApi
    public boolean disableCellBroadcastRange(int startMessageId, int endMessageId,
            @android.telephony.SmsCbMessage.MessageFormat int ranType) {
        boolean success = false;

        if (endMessageId < startMessageId) {
            throw new IllegalArgumentException("endMessageId < startMessageId");
        }
        try {
            ISms iSms = getISmsService();
            if (iSms != null) {
                // If getSubscriptionId() returns INVALID or an inactive subscription, we will use
                // the default phone internally.
                int subId = getSubscriptionId();
                success = iSms.disableCellBroadcastRangeForSubscriber(subId,
                        startMessageId, endMessageId, ranType);
                Rlog.d(TAG, "disableCellBroadcastRange: " + (success ? "succeeded" : "failed")
                        + " at calling disableCellBroadcastRangeForSubscriber. subId = " + subId);
            }
        } catch (RemoteException ex) {
            Rlog.d(TAG, "disableCellBroadcastRange: " + ex.getStackTrace());
            // ignore it
        }

        return success;
    }

    /**
     * Creates a list of <code>SmsMessage</code>s from a list of SmsRawData records.
     *
     * <p class="note"><strong>Note:</strong> This method is intended for internal use by carrier
     * applications or the Telephony framework and will never trigger an SMS disambiguation
     * dialog. If this method is called on a device that has multiple active subscriptions, this
     * {@link SmsManager} instance has been created with {@link #getDefault()}, and no user-defined
     * default subscription is defined, the subscription ID associated with this message will be
     * INVALID, which will result in the operation being completed on the subscription associated
     * with logical slot 0. Use {@link #getSmsManagerForSubscriptionId(int)} to ensure the
     * operation is performed on the correct subscription.
     * </p>
     *
     * @param records SMS EF records.
     * @return <code>ArrayList</code> of <code>SmsMessage</code> objects.
     */
    private ArrayList<SmsMessage> createMessageListFromRawRecords(List<SmsRawData> records) {
        ArrayList<SmsMessage> messages = new ArrayList<SmsMessage>();
        if (records != null) {
            int count = records.size();
            for (int i = 0; i < count; i++) {
                SmsRawData data = records.get(i);
                // List contains all records, including "free" records (null)
                if (data != null) {
                    SmsMessage sms = SmsMessage.createFromEfRecord(i + 1, data.getBytes(),
                            getSubscriptionId());
                    if (sms != null) {
                        messages.add(sms);
                    }
                }
            }
        }
        return messages;
    }

    /**
     * SMS over IMS is supported if IMS is registered and SMS is supported
     * on IMS.
     *
     * <p class="note"><strong>Note:</strong> This method is intended for internal use by carrier
     * applications or the Telephony framework and will never trigger an SMS disambiguation
     * dialog. If this method is called on a device that has multiple active subscriptions, this
     * {@link SmsManager} instance has been created with {@link #getDefault()}, and no user-defined
     * default subscription is defined, the subscription ID associated with this message will be
     * INVALID, which will result in the operation being completed on the subscription associated
     * with logical slot 0. Use {@link #getSmsManagerForSubscriptionId(int)} to ensure the
     * operation is performed on the correct subscription.
     * </p>
     *
     * @return true if SMS over IMS is supported, false otherwise
     *
     * @see #getImsSmsFormat()
     *
     * @hide
     */
    public boolean isImsSmsSupported() {
        boolean boSupported = false;
        try {
            ISms iSms = getISmsService();
            if (iSms != null) {
                boSupported = iSms.isImsSmsSupportedForSubscriber(getSubscriptionId());
            }
        } catch (RemoteException ex) {
            // ignore it
        }
        return boSupported;
    }

    /**
     * Gets SMS format supported on IMS.  SMS over IMS format is either 3GPP or 3GPP2.
     *
     * <p class="note"><strong>Note:</strong> This method is intended for internal use by carrier
     * applications or the Telephony framework and will never trigger an SMS disambiguation
     * dialog. If this method is called on a device that has multiple active subscriptions, this
     * {@link SmsManager} instance has been created with {@link #getDefault()}, and no user-defined
     * default subscription is defined, the subscription ID associated with this message will be
     * INVALID, which will result in the operation being completed on the subscription associated
     * with logical slot 0. Use {@link #getSmsManagerForSubscriptionId(int)} to ensure the
     * operation is performed on the correct subscription.
     * </p>
     *
     * @return SmsMessage.FORMAT_3GPP,
     *         SmsMessage.FORMAT_3GPP2
     *      or SmsMessage.FORMAT_UNKNOWN
     *
     * @see #isImsSmsSupported()
     *
     * @hide
     */
    public String getImsSmsFormat() {
        String format = com.android.internal.telephony.SmsConstants.FORMAT_UNKNOWN;
        try {
            ISms iSms = getISmsService();
            if (iSms != null) {
                format = iSms.getImsSmsFormatForSubscriber(getSubscriptionId());
            }
        } catch (RemoteException ex) {
            // ignore it
        }
        return format;
    }

    /**
     * Get default sms subscription id.
     *
     * <p class="note"><strong>Note:</strong>This returns a value different from
     * {@link SubscriptionManager#getDefaultSmsSubscriptionId} if the user has not chosen a default.
     * In this case it returns the active subscription id if there's only one active subscription
     * available.
     *
     * @return the user-defined default SMS subscription id, or the active subscription id if
     * there's only one active subscription available, otherwise
     * {@link SubscriptionManager#INVALID_SUBSCRIPTION_ID}.
     */
    public static int getDefaultSmsSubscriptionId() {
        try {
            return getISmsService().getPreferredSmsSubscription();
        } catch (RemoteException e) {
            return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        } catch (NullPointerException e) {
            return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
        }
    }

    /**
     * Get SMS prompt property,  enabled or not
     *
     * @return true if enabled, false otherwise
     * @hide
     */
    @UnsupportedAppUsage
    public boolean isSMSPromptEnabled() {
        ISms iSms = null;
        try {
            iSms = TelephonyManager.getSmsService();
            return iSms.isSMSPromptEnabled();
        } catch (RemoteException ex) {
            return false;
        } catch (NullPointerException ex) {
            return false;
        }
    }

    /**
     * Gets the total capacity of SMS storage on the SIM card.
     *
     * <p>
     * This is the number of 176 byte EF-SMS records which can be stored on the SIM card.
     * See 3GPP TS 31.102 - 4.2.25 - EF-SMS for more information.
     * </p>
     *
     * <p class="note"><strong>Note:</strong> This method will never trigger an SMS disambiguation
     * dialog. If this method is called on a device that has multiple active subscriptions, this
     * {@link SmsManager} instance has been created with {@link #getDefault()}, and no user-defined
     * default subscription is defined, the subscription ID associated with this method will be
     * INVALID, which will result in the operation being completed on the subscription associated
     * with logical slot 0. Use {@link #getSmsManagerForSubscriptionId(int)} to ensure the operation
     * is performed on the correct subscription.
     * </p>
     *
     * @return the total number of SMS records which can be stored on the SIM card.
     */
    @RequiresPermission(anyOf = {android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE})
    @IntRange(from = 0)
    public int getSmsCapacityOnIcc() {
        int ret = 0;
        try {
            ISms iccISms = getISmsService();
            if (iccISms != null) {
                ret = iccISms.getSmsCapacityOnIccForSubscriber(getSubscriptionId());
            }
        } catch (RemoteException ex) {
            Log.e(TAG, "getSmsCapacityOnIcc() RemoteException", ex);
        }
        return ret;
    }

    /** @hide */
    @IntDef(prefix = { "STATUS_ON_ICC_" }, value = {
            STATUS_ON_ICC_FREE,
            STATUS_ON_ICC_READ,
            STATUS_ON_ICC_UNREAD,
            STATUS_ON_ICC_SENT,
            STATUS_ON_ICC_UNSENT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface StatusOnIcc {}

    // see SmsMessage.getStatusOnIcc

    /** Free space (TS 51.011 10.5.3 / 3GPP2 C.S0023 3.4.27). */
    public static final int STATUS_ON_ICC_FREE      = 0;

    /** Received and read (TS 51.011 10.5.3 / 3GPP2 C.S0023 3.4.27). */
    public static final int STATUS_ON_ICC_READ      = 1;

    /** Received and unread (TS 51.011 10.5.3 / 3GPP2 C.S0023 3.4.27). */
    public static final int STATUS_ON_ICC_UNREAD    = 3;

    /** Stored and sent (TS 51.011 10.5.3 / 3GPP2 C.S0023 3.4.27). */
    public static final int STATUS_ON_ICC_SENT      = 5;

    /** Stored and unsent (TS 51.011 10.5.3 / 3GPP2 C.S0023 3.4.27). */
    public static final int STATUS_ON_ICC_UNSENT    = 7;

    // SMS send failure result codes

    /** @hide */
    @IntDef(prefix = { "RESULT" }, value = {
            RESULT_ERROR_NONE,
            RESULT_ERROR_GENERIC_FAILURE,
            RESULT_ERROR_RADIO_OFF,
            RESULT_ERROR_NULL_PDU,
            RESULT_ERROR_NO_SERVICE,
            RESULT_ERROR_LIMIT_EXCEEDED,
            RESULT_ERROR_FDN_CHECK_FAILURE,
            RESULT_ERROR_SHORT_CODE_NOT_ALLOWED,
            RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED,
            RESULT_RADIO_NOT_AVAILABLE,
            RESULT_NETWORK_REJECT,
            RESULT_INVALID_ARGUMENTS,
            RESULT_INVALID_STATE,
            RESULT_NO_MEMORY,
            RESULT_INVALID_SMS_FORMAT,
            RESULT_SYSTEM_ERROR,
            RESULT_MODEM_ERROR,
            RESULT_NETWORK_ERROR,
            RESULT_INVALID_SMSC_ADDRESS,
            RESULT_OPERATION_NOT_ALLOWED,
            RESULT_INTERNAL_ERROR,
            RESULT_NO_RESOURCES,
            RESULT_CANCELLED,
            RESULT_REQUEST_NOT_SUPPORTED,
            RESULT_NO_BLUETOOTH_SERVICE,
            RESULT_INVALID_BLUETOOTH_ADDRESS,
            RESULT_BLUETOOTH_DISCONNECTED,
            RESULT_UNEXPECTED_EVENT_STOP_SENDING,
            RESULT_SMS_BLOCKED_DURING_EMERGENCY,
            RESULT_SMS_SEND_RETRY_FAILED,
            RESULT_REMOTE_EXCEPTION,
            RESULT_NO_DEFAULT_SMS_APP,
            RESULT_RIL_RADIO_NOT_AVAILABLE,
            RESULT_RIL_SMS_SEND_FAIL_RETRY,
            RESULT_RIL_NETWORK_REJECT,
            RESULT_RIL_INVALID_STATE,
            RESULT_RIL_INVALID_ARGUMENTS,
            RESULT_RIL_NO_MEMORY,
            RESULT_RIL_REQUEST_RATE_LIMITED,
            RESULT_RIL_INVALID_SMS_FORMAT,
            RESULT_RIL_SYSTEM_ERR,
            RESULT_RIL_ENCODING_ERR,
            RESULT_RIL_INVALID_SMSC_ADDRESS,
            RESULT_RIL_MODEM_ERR,
            RESULT_RIL_NETWORK_ERR,
            RESULT_RIL_INTERNAL_ERR,
            RESULT_RIL_REQUEST_NOT_SUPPORTED,
            RESULT_RIL_INVALID_MODEM_STATE,
            RESULT_RIL_NETWORK_NOT_READY,
            RESULT_RIL_OPERATION_NOT_ALLOWED,
            RESULT_RIL_NO_RESOURCES,
            RESULT_RIL_CANCELLED,
            RESULT_RIL_SIM_ABSENT,
            RESULT_RIL_SIMULTANEOUS_SMS_AND_CALL_NOT_ALLOWED,
            RESULT_RIL_ACCESS_BARRED,
            RESULT_RIL_BLOCKED_DUE_TO_CALL
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Result {}

    /**
     * No error.
     */
    public static final int RESULT_ERROR_NONE    = 0;

    /** Generic failure cause */
    public static final int RESULT_ERROR_GENERIC_FAILURE    = 1;

    /** Failed because radio was explicitly turned off */
    public static final int RESULT_ERROR_RADIO_OFF          = 2;

    /** Failed because no pdu provided */
    public static final int RESULT_ERROR_NULL_PDU           = 3;

    /** Failed because service is currently unavailable */
    public static final int RESULT_ERROR_NO_SERVICE         = 4;

    /** Failed because we reached the sending queue limit. */
    public static final int RESULT_ERROR_LIMIT_EXCEEDED     = 5;

    /**
     * Failed because FDN is enabled.
     */
    public static final int RESULT_ERROR_FDN_CHECK_FAILURE  = 6;

    /** Failed because user denied the sending of this short code. */
    public static final int RESULT_ERROR_SHORT_CODE_NOT_ALLOWED = 7;

    /** Failed because the user has denied this app ever send premium short codes. */
    public static final int RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED = 8;

    /**
     * Failed because the radio was not available
     */
    public static final int RESULT_RADIO_NOT_AVAILABLE = 9;

    /**
     * Failed because of network rejection
     */
    public static final int RESULT_NETWORK_REJECT = 10;

    /**
     * Failed because of invalid arguments
     */
    public static final int RESULT_INVALID_ARGUMENTS = 11;

    /**
     * Failed because of an invalid state
     */
    public static final int RESULT_INVALID_STATE = 12;

    /**
     * Failed because there is no memory
     */
    public static final int RESULT_NO_MEMORY = 13;

    /**
     * Failed because the sms format is not valid
     */
    public static final int RESULT_INVALID_SMS_FORMAT = 14;

    /**
     * Failed because of a system error
     */
    public static final int RESULT_SYSTEM_ERROR = 15;

    /**
     * Failed because of a modem error
     */
    public static final int RESULT_MODEM_ERROR = 16;

    /**
     * Failed because of a network error
     */
    public static final int RESULT_NETWORK_ERROR = 17;

    /**
     * Failed because of an encoding error
     */
    public static final int RESULT_ENCODING_ERROR = 18;

    /**
     * Failed because of an invalid smsc address
     */
    public static final int RESULT_INVALID_SMSC_ADDRESS = 19;

    /**
     * Failed because the operation is not allowed
     */
    public static final int RESULT_OPERATION_NOT_ALLOWED = 20;

    /**
     * Failed because of an internal error
     */
    public static final int RESULT_INTERNAL_ERROR = 21;

    /**
     * Failed because there are no resources
     */
    public static final int RESULT_NO_RESOURCES = 22;

    /**
     * Failed because the operation was cancelled
     */
    public static final int RESULT_CANCELLED = 23;

    /**
     * Failed because the request is not supported
     */
    public static final int RESULT_REQUEST_NOT_SUPPORTED = 24;

    /**
     * Failed sending via bluetooth because the bluetooth service is not available
     */
    public static final int RESULT_NO_BLUETOOTH_SERVICE = 25;

    /**
     * Failed sending via bluetooth because the bluetooth device address is invalid
     */
    public static final int RESULT_INVALID_BLUETOOTH_ADDRESS = 26;

    /**
     * Failed sending via bluetooth because bluetooth disconnected
     */
    public static final int RESULT_BLUETOOTH_DISCONNECTED = 27;

    /**
     * Failed sending because the user denied or canceled the dialog displayed for a premium
     * shortcode sms or rate-limited sms.
     */
    public static final int RESULT_UNEXPECTED_EVENT_STOP_SENDING = 28;

    /**
     * Failed sending during an emergency call
     */
    public static final int RESULT_SMS_BLOCKED_DURING_EMERGENCY = 29;

    /**
     * Failed to send an sms retry
     */
    public static final int RESULT_SMS_SEND_RETRY_FAILED = 30;

    /**
     * Set by BroadcastReceiver to indicate a remote exception while handling a message.
     */
    public static final int RESULT_REMOTE_EXCEPTION = 31;

    /**
     * Set by BroadcastReceiver to indicate there's no default sms app.
     */
    public static final int RESULT_NO_DEFAULT_SMS_APP = 32;

    // Radio Error results

    /**
     * The radio did not start or is resetting.
     */
    public static final int RESULT_RIL_RADIO_NOT_AVAILABLE = 100;

    /**
     * The radio failed to send the sms and needs to retry.
     */
    public static final int RESULT_RIL_SMS_SEND_FAIL_RETRY = 101;

    /**
     * The sms request was rejected by the network.
     */
    public static final int RESULT_RIL_NETWORK_REJECT = 102;

    /**
     * The radio returned an unexpected request for the current state.
     */
    public static final int RESULT_RIL_INVALID_STATE = 103;

    /**
     * The radio received invalid arguments in the request.
     */
    public static final int RESULT_RIL_INVALID_ARGUMENTS = 104;

    /**
     * The radio didn't have sufficient memory to process the request.
     */
    public static final int RESULT_RIL_NO_MEMORY = 105;

    /**
     * The radio denied the operation due to overly-frequent requests.
     */
    public static final int RESULT_RIL_REQUEST_RATE_LIMITED = 106;

    /**
     * The radio returned an error indicating invalid sms format.
     */
    public static final int RESULT_RIL_INVALID_SMS_FORMAT = 107;

    /**
     * The radio encountered a platform or system error.
     */
    public static final int RESULT_RIL_SYSTEM_ERR = 108;

    /**
     * The SMS message was not encoded properly.
     */
    public static final int RESULT_RIL_ENCODING_ERR = 109;

    /**
     * The specified SMSC address was invalid.
     */
    public static final int RESULT_RIL_INVALID_SMSC_ADDRESS = 110;

    /**
     * The vendor RIL received an unexpected or incorrect response.
     */
    public static final int RESULT_RIL_MODEM_ERR = 111;

    /**
     * The radio received an error from the network.
     */
    public static final int RESULT_RIL_NETWORK_ERR = 112;

    /**
     * The modem encountered an unexpected error scenario while handling the request.
     */
    public static final int RESULT_RIL_INTERNAL_ERR = 113;

    /**
     * The request was not supported by the radio.
     */
    public static final int RESULT_RIL_REQUEST_NOT_SUPPORTED = 114;

    /**
     * The radio cannot process the request in the current modem state.
     */
    public static final int RESULT_RIL_INVALID_MODEM_STATE = 115;

    /**
     * The network is not ready to perform the request.
     */
    public static final int RESULT_RIL_NETWORK_NOT_READY = 116;

    /**
     * The radio reports the request is not allowed.
     */
    public static final int RESULT_RIL_OPERATION_NOT_ALLOWED = 117;

    /**
     * There are insufficient resources to process the request.
     */
    public static final int RESULT_RIL_NO_RESOURCES = 118;

    /**
     * The request has been cancelled.
     */
    public static final int RESULT_RIL_CANCELLED = 119;

    /**
     * The radio failed to set the location where the CDMA subscription
     * can be retrieved because the SIM or RUIM is absent.
     */
    public static final int RESULT_RIL_SIM_ABSENT = 120;

    /**
     * 1X voice and SMS are not allowed simulteneously.
     */
    public static final int RESULT_RIL_SIMULTANEOUS_SMS_AND_CALL_NOT_ALLOWED = 121;

    /**
     * Access is barred.
     */
    public static final int RESULT_RIL_ACCESS_BARRED = 122;

    /**
     * SMS is blocked due to call control, e.g., resource unavailable in the SMR entity.
     */
    public static final int RESULT_RIL_BLOCKED_DUE_TO_CALL = 123;

    // SMS receiving results sent as a "result" extra in {@link Intents.SMS_REJECTED_ACTION}

    /**
     * SMS receive dispatch failure.
     */
    public static final int RESULT_RECEIVE_DISPATCH_FAILURE = 500;

    /**
     * SMS receive injected null PDU.
     */
    public static final int RESULT_RECEIVE_INJECTED_NULL_PDU = 501;

    /**
     * SMS receive encountered runtime exception.
     */
    public static final int RESULT_RECEIVE_RUNTIME_EXCEPTION = 502;

    /**
     * SMS received null message from the radio interface layer.
     */
    public static final int RESULT_RECEIVE_NULL_MESSAGE_FROM_RIL = 503;

    /**
     * SMS short code received while the phone is in encrypted state.
     */
    public static final int RESULT_RECEIVE_WHILE_ENCRYPTED = 504;

    /**
     * SMS receive encountered an SQL exception.
     */
    public static final int RESULT_RECEIVE_SQL_EXCEPTION = 505;

    /**
     * SMS receive an exception parsing a uri.
     */
    public static final int RESULT_RECEIVE_URI_EXCEPTION = 506;



    /**
     * Send an MMS message
     *
     * <p class="note"><strong>Note:</strong> If {@link #getDefault()} is used to instantiate this
     * manager on a multi-SIM device, this operation may fail sending the MMS message because no
     * suitable default subscription could be found. In this case, if {@code sentIntent} is
     * non-null, then the {@link PendingIntent} will be sent with an error code
     * {@code RESULT_NO_DEFAULT_SMS_APP}. See {@link #getDefault()} for more information on the
     * conditions where this operation may fail.
     * </p>
     *
     * @param context application context
     * @param contentUri the content Uri from which the message pdu will be read
     * @param locationUrl the optional location url where message should be sent to
     * @param configOverrides the carrier-specific messaging configuration values to override for
     *  sending the message.
     * @param sentIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is successfully sent, or failed
     * @throws IllegalArgumentException if contentUri is empty
     */
    public void sendMultimediaMessage(Context context, Uri contentUri, String locationUrl,
            Bundle configOverrides, PendingIntent sentIntent) {
        sendMultimediaMessage(context, contentUri, locationUrl, configOverrides, sentIntent,
                0L /* messageId */);
    }

    /**
     * Send an MMS message
     *
     * Same as {@link #sendMultimediaMessage(Context context, Uri contentUri, String locationUrl,
     *           Bundle configOverrides, PendingIntent sentIntent)}, but adds an optional messageId.
     * <p class="note"><strong>Note:</strong> If {@link #getDefault()} is used to instantiate this
     * manager on a multi-SIM device, this operation may fail sending the MMS message because no
     * suitable default subscription could be found. In this case, if {@code sentIntent} is
     * non-null, then the {@link PendingIntent} will be sent with an error code
     * {@code RESULT_NO_DEFAULT_SMS_APP}. See {@link #getDefault()} for more information on the
     * conditions where this operation may fail.
     * </p>
     *
     * @param context application context
     * @param contentUri the content Uri from which the message pdu will be read
     * @param locationUrl the optional location url where message should be sent to
     * @param configOverrides the carrier-specific messaging configuration values to override for
     *  sending the message.
     * @param sentIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is successfully sent, or failed
     * @param messageId an id that uniquely identifies the message requested to be sent.
     * Used for logging and diagnostics purposes. The id may be 0.
     * @throws IllegalArgumentException if contentUri is empty
     */
    public void sendMultimediaMessage(@NonNull Context context, @NonNull Uri contentUri,
            @Nullable String locationUrl, @Nullable Bundle configOverrides,
            @Nullable PendingIntent sentIntent, long messageId) {
        if (contentUri == null) {
            throw new IllegalArgumentException("Uri contentUri null");
        }
        MmsManager m = (MmsManager) context.getSystemService(Context.MMS_SERVICE);
        if (m != null) {
            resolveSubscriptionForOperation(new SubscriptionResolverResult() {
                @Override
                public void onSuccess(int subId) {
                    m.sendMultimediaMessage(subId, contentUri, locationUrl, configOverrides,
                            sentIntent, messageId);
                }

                @Override
                public void onFailure() {
                    notifySmsError(sentIntent, RESULT_NO_DEFAULT_SMS_APP);
                }
            });
        }
    }

    /**
     * Download an MMS message from carrier by a given location URL
     *
     * <p class="note"><strong>Note:</strong> If {@link #getDefault()} is used to instantiate this
     * manager on a multi-SIM device, this operation may fail downloading the MMS message because no
     * suitable default subscription could be found. In this case, if {@code downloadedIntent} is
     * non-null, then the {@link PendingIntent} will be sent with an error code
     * {@code RESULT_NO_DEFAULT_SMS_APP}. See {@link #getDefault()} for more information on the
     * conditions where this operation may fail.
     * </p>
     *
     * @param context application context
     * @param locationUrl the location URL of the MMS message to be downloaded, usually obtained
     *  from the MMS WAP push notification
     * @param contentUri the content uri to which the downloaded pdu will be written
     * @param configOverrides the carrier-specific messaging configuration values to override for
     *  downloading the message.
     * @param downloadedIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is downloaded, or the download is failed
     * @throws IllegalArgumentException if locationUrl or contentUri is empty
     */
    public void downloadMultimediaMessage(Context context, String locationUrl, Uri contentUri,
            Bundle configOverrides, PendingIntent downloadedIntent) {
        downloadMultimediaMessage(context, locationUrl, contentUri, configOverrides,
                downloadedIntent, 0L /* messageId */);
    }

    /**
     * Download an MMS message from carrier by a given location URL
     *
     * Same as {@link #downloadMultimediaMessage(Context context, String locationUrl,
     *      Uri contentUri, Bundle configOverrides, PendingIntent downloadedIntent)},
     *      but adds an optional messageId.
     * <p class="note"><strong>Note:</strong> If {@link #getDefault()} is used to instantiate this
     * manager on a multi-SIM device, this operation may fail downloading the MMS message because no
     * suitable default subscription could be found. In this case, if {@code downloadedIntent} is
     * non-null, then the {@link PendingIntent} will be sent with an error code
     * {@code RESULT_NO_DEFAULT_SMS_APP}. See {@link #getDefault()} for more information on the
     * conditions where this operation may fail.
     * </p>
     *
     * @param context application context
     * @param locationUrl the location URL of the MMS message to be downloaded, usually obtained
     *  from the MMS WAP push notification
     * @param contentUri the content uri to which the downloaded pdu will be written
     * @param configOverrides the carrier-specific messaging configuration values to override for
     *  downloading the message.
     * @param downloadedIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is downloaded, or the download is failed
     * @param messageId an id that uniquely identifies the message requested to be downloaded.
     * Used for logging and diagnostics purposes. The id may be 0.
     * @throws IllegalArgumentException if locationUrl or contentUri is empty
     */
    public void downloadMultimediaMessage(@NonNull Context context, @NonNull String locationUrl,
            @NonNull Uri contentUri, @Nullable Bundle configOverrides,
            @Nullable PendingIntent downloadedIntent, long messageId) {
        if (TextUtils.isEmpty(locationUrl)) {
            throw new IllegalArgumentException("Empty MMS location URL");
        }
        if (contentUri == null) {
            throw new IllegalArgumentException("Uri contentUri null");
        }
        MmsManager m = (MmsManager) context.getSystemService(Context.MMS_SERVICE);
        if (m != null) {
            resolveSubscriptionForOperation(new SubscriptionResolverResult() {
                @Override
                public void onSuccess(int subId) {
                    m.downloadMultimediaMessage(subId, locationUrl, contentUri, configOverrides,
                            downloadedIntent, messageId);
                }

                @Override
                public void onFailure() {
                    notifySmsError(downloadedIntent, RESULT_NO_DEFAULT_SMS_APP);
                }
            });
        }
    }

    // MMS send/download failure result codes
    public static final int MMS_ERROR_UNSPECIFIED = 1;
    public static final int MMS_ERROR_INVALID_APN = 2;
    public static final int MMS_ERROR_UNABLE_CONNECT_MMS = 3;
    public static final int MMS_ERROR_HTTP_FAILURE = 4;
    public static final int MMS_ERROR_IO_ERROR = 5;
    public static final int MMS_ERROR_RETRY = 6;
    public static final int MMS_ERROR_CONFIGURATION_ERROR = 7;
    public static final int MMS_ERROR_NO_DATA_NETWORK = 8;

    /** Intent extra name for MMS sending result data in byte array type */
    public static final String EXTRA_MMS_DATA = "android.telephony.extra.MMS_DATA";
    /** Intent extra name for HTTP status code for MMS HTTP failure in integer type */
    public static final String EXTRA_MMS_HTTP_STATUS = "android.telephony.extra.MMS_HTTP_STATUS";

    /**
     * Get carrier-dependent MMS configuration values.
     *
     * <p class="note"><strong>Note:</strong> This method is intended for internal use by carrier
     * applications or the Telephony framework and will never trigger an SMS disambiguation dialog.
     * If this method is called on a device that has multiple active subscriptions, this {@link
     * SmsManager} instance has been created with {@link #getDefault()}, and no user-defined default
     * subscription is defined, the subscription ID associated with this message will be INVALID,
     * which will result in the operation being completed on the subscription associated with
     * logical slot 0. Use {@link #getSmsManagerForSubscriptionId(int)} to ensure the operation is
     * performed on the correct subscription.
     * </p>
     *
     * @return the bundle key/values pairs that contains MMS configuration values
     *  or an empty Bundle if they cannot be found.
     */
    @NonNull public Bundle getCarrierConfigValues() {
        try {
            ISms iSms = getISmsService();
            if (iSms != null) {
                return iSms.getCarrierConfigValuesForSubscriber(getSubscriptionId());
            }
        } catch (RemoteException ex) {
            // ignore it
        }
        return new Bundle();
    }

    /**
     * Create a single use app specific incoming SMS request for the calling package.
     *
     * This method returns a token that if included in a subsequent incoming SMS message will cause
     * {@code intent} to be sent with the SMS data.
     *
     * The token is only good for one use, after an SMS has been received containing the token all
     * subsequent SMS messages with the token will be routed as normal.
     *
     * An app can only have one request at a time, if the app already has a request pending it will
     * be replaced with a new request.
     *
     * <p class="note"><strong>Note:</strong> This method will never trigger an SMS disambiguation
     * dialog. If this method is called on a device that has multiple active subscriptions, this
     * {@link SmsManager} instance has been created with {@link #getDefault()}, and no user-defined
     * default subscription is defined, the subscription ID associated with this message will be
     * INVALID, which will result in the operation being completed on the subscription associated
     * with logical slot 0. Use {@link #getSmsManagerForSubscriptionId(int)} to ensure the
     * operation is performed on the correct subscription.
     * </p>
     *
     * @return Token to include in an SMS message. The token will be 11 characters long.
     * @see android.provider.Telephony.Sms.Intents#getMessagesFromIntent
     */
    public String createAppSpecificSmsToken(PendingIntent intent) {
        try {
            ISms iccSms = getISmsServiceOrThrow();
            return iccSms.createAppSpecificSmsToken(getSubscriptionId(),
                    null, intent);

        } catch (RemoteException ex) {
            ex.rethrowFromSystemServer();
            return null;
        }
    }

    /**
     * callback for providing asynchronous sms messages for financial app.
     */
    public abstract static class FinancialSmsCallback {
        /**
         * Callback to send sms messages back to financial app asynchronously.
         *
         * @param msgs SMS messages.
         */
        public abstract void onFinancialSmsMessages(CursorWindow msgs);
    };

    /**
     * Get SMS messages for the calling financial app.
     * The result will be delivered asynchronously in the passing in callback interface.
     *
     * <p class="note"><strong>Note:</strong> This method will never trigger an SMS disambiguation
     * dialog. If this method is called on a device that has multiple active subscriptions, this
     * {@link SmsManager} instance has been created with {@link #getDefault()}, and no user-defined
     * default subscription is defined, the subscription ID associated with this message will be
     * INVALID, which will result in the operation being completed on the subscription associated
     * with logical slot 0. Use {@link #getSmsManagerForSubscriptionId(int)} to ensure the
     * operation is performed on the correct subscription.
     * </p>
     *
     * @param params the parameters to filter SMS messages returned.
     * @param executor the executor on which callback will be invoked.
     * @param callback a callback to receive CursorWindow with SMS messages.
     *
     */
    @RequiresPermission(android.Manifest.permission.SMS_FINANCIAL_TRANSACTIONS)
    public void getSmsMessagesForFinancialApp(
            Bundle params,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull FinancialSmsCallback callback) {
        // This API is not functional and thus removed to avoid future confusion.
    }

    /**
     * @see #createAppSpecificSmsTokenWithPackageInfo(String, PendingIntent).
     * The prefixes is a list of prefix {@code String} separated by this delimiter.
     * @hide
     */
    public static final String REGEX_PREFIX_DELIMITER = ",";
    /**
     * @see #createAppSpecificSmsTokenWithPackageInfo(String, PendingIntent).
     * The success status to be added into the intent to be sent to the calling package.
     * @hide
     */
    public static final int RESULT_STATUS_SUCCESS = 0;
    /**
     * @see #createAppSpecificSmsTokenWithPackageInfo(String, PendingIntent).
     * The timeout status to be added into the intent to be sent to the calling package.
     * @hide
     */
    public static final int RESULT_STATUS_TIMEOUT = 1;
    /**
     * @see #createAppSpecificSmsTokenWithPackageInfo(String, PendingIntent).
     * Intent extra key of the retrieved SMS message as a {@code String}.
     * @hide
     */
    public static final String EXTRA_SMS_MESSAGE = "android.telephony.extra.SMS_MESSAGE";
    /**
     * @see #createAppSpecificSmsTokenWithPackageInfo(String, PendingIntent).
     * Intent extra key of SMS retriever status, which indicates whether the request for the
     * coming SMS message is SUCCESS or TIMEOUT
     * @hide
     */
    public static final String EXTRA_STATUS = "android.telephony.extra.STATUS";
    /**
     * @see #createAppSpecificSmsTokenWithPackageInfo(String, PendingIntent).
     * [Optional] Intent extra key of the retrieved Sim card subscription Id if any. {@code int}
     * @hide
     */
    public static final String EXTRA_SIM_SUBSCRIPTION_ID =
            "android.telephony.extra.SIM_SUBSCRIPTION_ID";

    /**
     * Create a single use app specific incoming SMS request for the calling package.
     *
     * This method returns a token that if included in a subsequent incoming SMS message, and the
     * SMS message has a prefix from the given prefixes list, the provided {@code intent} will be
     * sent with the SMS data to the calling package.
     *
     * The token is only good for one use within a reasonable amount of time. After an SMS has been
     * received containing the token all subsequent SMS messages with the token will be routed as
     * normal.
     *
     * An app can only have one request at a time, if the app already has a request pending it will
     * be replaced with a new request.
     *
     * <p class="note"><strong>Note:</strong> This method will never trigger an SMS disambiguation
     * dialog. If this method is called on a device that has multiple active subscriptions, this
     * {@link SmsManager} instance has been created with {@link #getDefault()}, and no user-defined
     * default subscription is defined, the subscription ID associated with this message will be
     * INVALID, which will result in the operation being completed on the subscription associated
     * with logical slot 0. Use {@link #getSmsManagerForSubscriptionId(int)} to ensure the
     * operation is performed on the correct subscription.
     * </p>
     *
     * @param prefixes this is a list of prefixes string separated by REGEX_PREFIX_DELIMITER. The
     *  matching SMS message should have at least one of the prefixes in the beginning of the
     *  message.
     * @param intent this intent is sent when the matching SMS message is received.
     * @return Token to include in an SMS message.
     */
    @Nullable
    public String createAppSpecificSmsTokenWithPackageInfo(
            @Nullable String prefixes, @NonNull PendingIntent intent) {
        try {
            ISms iccSms = getISmsServiceOrThrow();
            return iccSms.createAppSpecificSmsTokenWithPackageInfo(getSubscriptionId(),
                    null, prefixes, intent);

        } catch (RemoteException ex) {
            ex.rethrowFromSystemServer();
            return null;
        }
    }

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"SMS_CATEGORY_"},
            value = {
                    SmsManager.SMS_CATEGORY_NOT_SHORT_CODE,
                    SmsManager.SMS_CATEGORY_FREE_SHORT_CODE,
                    SmsManager.SMS_CATEGORY_STANDARD_SHORT_CODE,
                    SmsManager.SMS_CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE,
                    SmsManager.SMS_CATEGORY_PREMIUM_SHORT_CODE})
    public @interface SmsShortCodeCategory {}

    /**
     * Return value from {@link #checkSmsShortCodeDestination(String, String)} ()} for regular
     * phone numbers.
     * @hide
     */
    @TestApi
    public static final int SMS_CATEGORY_NOT_SHORT_CODE = 0;
    /**
     * Return value from {@link #checkSmsShortCodeDestination(String, String)} ()} for free
     * (no cost) short codes.
     * @hide
     */
    @TestApi
    public static final int SMS_CATEGORY_FREE_SHORT_CODE = 1;
    /**
     * Return value from {@link #checkSmsShortCodeDestination(String, String)} ()} for
     * standard rate (non-premium)
     * short codes.
     * @hide
     */
    @TestApi
    public static final int SMS_CATEGORY_STANDARD_SHORT_CODE = 2;
    /**
     * Return value from {@link #checkSmsShortCodeDestination(String, String)} ()} for possible
     * premium short codes.
     * @hide
     */
    @TestApi
    public static final int SMS_CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE = 3;
    /**
     * Return value from {@link #checkSmsShortCodeDestination(String, String)} ()} for
     * premium short codes.
     * @hide
     */
    @TestApi
    public static final int SMS_CATEGORY_PREMIUM_SHORT_CODE = 4;

    /**
     * Check if the destination address is a possible premium short code.
     * NOTE: the caller is expected to strip non-digits from the destination number with
     * {@link PhoneNumberUtils#extractNetworkPortion} before calling this method.
     *
     * <p class="note"><strong>Note:</strong> This method is intended for internal use by carrier
     * applications or the Telephony framework and will never trigger an SMS disambiguation
     * dialog. If this method is called on a device that has multiple active subscriptions, this
     * {@link SmsManager} instance has been created with {@link #getDefault()}, and no user-defined
     * default subscription is defined, the subscription ID associated with this message will be
     * INVALID, which will result in the operation being completed on the subscription associated
     * with logical slot 0. Use {@link #getSmsManagerForSubscriptionId(int)} to ensure the
     * operation is performed on the correct subscription.
     * </p>
     *
     * @param destAddress the destination address to test for possible short code
     * @param countryIso the ISO country code
     *
     * @return
     * {@link SmsManager#SMS_CATEGORY_NOT_SHORT_CODE},
     * {@link SmsManager#SMS_CATEGORY_FREE_SHORT_CODE},
     * {@link SmsManager#SMS_CATEGORY_POSSIBLE_PREMIUM_SHORT_CODE},
     * {@link SmsManager#SMS_CATEGORY_PREMIUM_SHORT_CODE}, or
     * {@link SmsManager#SMS_CATEGORY_STANDARD_SHORT_CODE}
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    @TestApi
    public @SmsShortCodeCategory int checkSmsShortCodeDestination(
            String destAddress, String countryIso) {
        try {
            ISms iccISms = getISmsServiceOrThrow();
            if (iccISms != null) {
                return iccISms.checkSmsShortCodeDestination(getSubscriptionId(),
                        null, null, destAddress, countryIso);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "checkSmsShortCodeDestination() RemoteException", e);
        }
        return SmsManager.SMS_CATEGORY_NOT_SHORT_CODE;
    }

    /**
     * Gets the SMSC address from (U)SIM.
     *
     * <p class="note"><strong>Note:</strong> Using this method requires that your app is the
     * default SMS application, or READ_PRIVILEGED_PHONE_STATE permission, or has the carrier
     * privileges.</p>
     *
     * <p class="note"><strong>Note:</strong> This method will never trigger an SMS disambiguation
     * dialog. If this method is called on a device that has multiple active subscriptions, this
     * {@link SmsManager} instance has been created with {@link #getDefault()}, and no user-defined
     * default subscription is defined, the subscription ID associated with this method will be
     * INVALID, which will result in the operation being completed on the subscription associated
     * with logical slot 0. Use {@link #getSmsManagerForSubscriptionId(int)} to ensure the operation
     * is performed on the correct subscription.
     * </p>
     *
     * @return the SMSC address string, null if failed.
     */
    @SuppressAutoDoc // for carrier privileges and default SMS application.
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    @Nullable
    public String getSmscAddress() {
        String smsc = null;

        try {
            ISms iSms = getISmsService();
            if (iSms != null) {
                smsc = iSms.getSmscAddressFromIccEfForSubscriber(
                        getSubscriptionId(), null);
            }
        } catch (RemoteException ex) {
            throw new RuntimeException(ex);
        }
        return smsc;
    }

    /**
     * Sets the SMSC address on (U)SIM.
     *
     * <p class="note"><strong>Note:</strong> Using this method requires that your app is the
     * default SMS application, or has {@link android.Manifest.permission#MODIFY_PHONE_STATE}
     * permission, or has the carrier privileges.</p>
     *
     * <p class="note"><strong>Note:</strong> This method will never trigger an SMS disambiguation
     * dialog. If this method is called on a device that has multiple active subscriptions, this
     * {@link SmsManager} instance has been created with {@link #getDefault()}, and no user-defined
     * default subscription is defined, the subscription ID associated with this method will be
     * INVALID, which will result in the operation being completed on the subscription associated
     * with logical slot 0. Use {@link #getSmsManagerForSubscriptionId(int)} to ensure the operation
     * is performed on the correct subscription.
     * </p>
     *
     * @param smsc the SMSC address string.
     * @return true for success, false otherwise. Failure can be due modem returning an error.
     */
    @SuppressAutoDoc // for carrier privileges and default SMS application.
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public boolean setSmscAddress(@NonNull String smsc) {
        try {
            ISms iSms = getISmsService();
            if (iSms != null) {
                return iSms.setSmscAddressOnIccEfForSubscriber(
                        smsc, getSubscriptionId(), null);
            }
        } catch (RemoteException ex) {
            throw new RuntimeException(ex);
        }
        return false;
    }

    /**
     * Gets the premium SMS permission for the specified package. If the package has never
     * been seen before, the default {@link SmsManager#PREMIUM_SMS_CONSENT_UNKNOWN}
     * will be returned.
     * @param packageName the name of the package to query permission
     * @return one of {@link SmsManager#PREMIUM_SMS_CONSENT_UNKNOWN},
     *  {@link SmsManager#PREMIUM_SMS_CONSENT_ASK_USER},
     *  {@link SmsManager#PREMIUM_SMS_CONSENT_NEVER_ALLOW}, or
     *  {@link SmsManager#PREMIUM_SMS_CONSENT_ALWAYS_ALLOW}
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public @PremiumSmsConsent int getPremiumSmsConsent(@NonNull String packageName) {
        int permission = 0;
        try {
            ISms iSms = getISmsService();
            if (iSms != null) {
                permission = iSms.getPremiumSmsPermission(packageName);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "getPremiumSmsPermission() RemoteException", e);
        }
        return permission;
    }

    /**
     * Sets the premium SMS permission for the specified package and save the value asynchronously
     * to persistent storage.
     * @param packageName the name of the package to set permission
     * @param permission one of {@link SmsManager#PREMIUM_SMS_CONSENT_ASK_USER},
     *  {@link SmsManager#PREMIUM_SMS_CONSENT_NEVER_ALLOW}, or
     *  {@link SmsManager#PREMIUM_SMS_CONSENT_ALWAYS_ALLOW}
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void setPremiumSmsConsent(
            @NonNull String packageName, @PremiumSmsConsent int permission) {
        try {
            ISms iSms = getISmsService();
            if (iSms != null) {
                iSms.setPremiumSmsPermission(packageName, permission);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "setPremiumSmsPermission() RemoteException", e);
        }
    }

    /**
     * Reset all cell broadcast ranges. Previously enabled ranges will become invalid after this.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_CELL_BROADCASTS)
    public void resetAllCellBroadcastRanges() {
        try {
            ISms iSms = getISmsService();
            if (iSms != null) {
                // If getSubscriptionId() returns INVALID or an inactive subscription, we will use
                // the default phone internally.
                iSms.resetAllCellBroadcastRanges(getSubscriptionId());
            }
        } catch (RemoteException ex) {
            ex.rethrowFromSystemServer();
        }
    }

    private static String formatCrossStackMessageId(long id) {
        return "{x-message-id:" + id + "}";
    }
}
