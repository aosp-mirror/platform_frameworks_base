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

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SuppressAutoDoc;
import android.annotation.SystemApi;
import android.annotation.UnsupportedAppUsage;
import android.app.ActivityThread;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothMapClient;
import android.bluetooth.BluetoothProfile;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.CursorWindow;
import android.net.Uri;
import android.os.BaseBundle;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.telecom.PhoneAccount;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.telephony.IMms;
import com.android.internal.telephony.ISms;
import com.android.internal.telephony.SmsRawData;

import java.util.ArrayList;
import java.util.Arrays;
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
 * Get this object by calling the static method {@link #getDefault()}.
 *
 * <p>For information about how to behave as the default SMS app on Android 4.4 (API level 19)
 * and higher, see {@link android.provider.Telephony}.
 */
public final class SmsManager {
    private static final String TAG = "SmsManager";
    private static final boolean DBG = false;

    /**
     * A psuedo-subId that represents the default subId at any given time. The actual subId it
     * represents changes as the default subId is changed.
     */
    private static final int DEFAULT_SUBSCRIPTION_ID = -1002;

    /** Singleton object constructed during class initialization. */
    private static final SmsManager sInstance = new SmsManager(DEFAULT_SUBSCRIPTION_ID);
    private static final Object sLockObject = new Object();

    /** @hide */
    public static final int CELL_BROADCAST_RAN_TYPE_GSM = 0;
    /** @hide */
    public static final int CELL_BROADCAST_RAN_TYPE_CDMA = 1;

    /** SMS record length from TS 51.011 10.5.3
     * @hide
     */
    public static final int SMS_RECORD_LENGTH = 176;

    /** SMS record length from C.S0023 3.4.27
     * @hide
     */
    public static final int CDMA_SMS_RECORD_LENGTH = 255;

    private static final Map<Integer, SmsManager> sSubInstances =
            new ArrayMap<Integer, SmsManager>();

    /** A concrete subscription id, or the pseudo DEFAULT_SUBSCRIPTION_ID */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private int mSubId;

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

    /*
     * Forwarded constants from SimDialogActivity.
     */
    private static String DIALOG_TYPE_KEY = "dialog_type";
    private static final int SMS_PICK = 2;

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
     *  For <code>RESULT_ERROR_GENERIC_FAILURE</code> the sentIntent may include
     *  the extra "errorCode" containing a radio technology specific value,
     *  generally only useful for troubleshooting.<br>
     *  The per-application based SMS control checks sentIntent. If sentIntent
     *  is NULL the caller will be checked against all unknown applications,
     *  which cause smaller number of SMS to be sent in checking period.
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
                true /* persistMessage*/);
    }

    private void sendTextMessageInternal(String destinationAddress, String scAddress,
            String text, PendingIntent sentIntent, PendingIntent deliveryIntent,
            boolean persistMessage) {
        if (TextUtils.isEmpty(destinationAddress)) {
            throw new IllegalArgumentException("Invalid destinationAddress");
        }

        if (TextUtils.isEmpty(text)) {
            throw new IllegalArgumentException("Invalid message body");
        }

        // A Manager code accessing another manager is *not* acceptable, in Android.
        // In this particular case, it is unavoidable because of the following:
        // If the subscription for this SmsManager instance belongs to a remote SIM
        // then a listener to get BluetoothMapClient proxy needs to be started up.
        // Doing that is possible only in a foreground thread or as a system user.
        // i.e., Can't be done in ISms service.
        // For that reason, SubscriptionManager needs to be accessed here to determine
        // if the subscription belongs to a remote SIM.
        // Ideally, there should be another API in ISms to service messages going thru
        // remote SIM subscriptions (and ISms should be tweaked to be able to access
        // BluetoothMapClient proxy)
        Context context = ActivityThread.currentApplication().getApplicationContext();
        SubscriptionManager manager = (SubscriptionManager) context
                .getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        int subId = getSubscriptionId();
        SubscriptionInfo info = manager.getActiveSubscriptionInfo(subId);
        if (DBG) {
            Log.d(TAG, "for subId: " + subId + ", subscription-info: " + info);
        }

        /* If the Subscription associated with this SmsManager instance belongs to a remote-sim,
         * then send the message thru the remote-sim subscription.
         */
        if (info != null
                && info.getSubscriptionType() == SubscriptionManager.SUBSCRIPTION_TYPE_REMOTE_SIM) {
            if (DBG) Log.d(TAG, "sending message thru bluetooth");
            sendTextMessageBluetooth(destinationAddress, scAddress, text, sentIntent,
                    deliveryIntent, info);
            return;
        }

        try {
            // If the subscription is invalid or default, we will use the default phone to send the
            // SMS and possibly fail later in the SMS sending process.
            ISms iccISms = getISmsServiceOrThrow();
            iccISms.sendTextForSubscriber(subId, ActivityThread.currentPackageName(),
                    destinationAddress,
                    scAddress, text, sentIntent, deliveryIntent,
                    persistMessage);
        } catch (RemoteException ex) {
            // ignore it
        }
    }

    private void sendTextMessageBluetooth(String destAddr, String scAddress,
            String text, PendingIntent sentIntent, PendingIntent deliveryIntent,
            SubscriptionInfo info) {
        BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
        if (btAdapter == null) {
            // No bluetooth service on this platform?
            sendErrorInPendingIntent(sentIntent, SmsManager.RESULT_ERROR_NO_SERVICE);
            return;
        }
        BluetoothDevice device = btAdapter.getRemoteDevice(info.getIccId());
        if (device == null) {
            if (DBG) Log.d(TAG, "Bluetooth device addr invalid: " + info.getIccId());
            sendErrorInPendingIntent(sentIntent, SmsManager.RESULT_ERROR_NO_SERVICE);
            return;
        }
        btAdapter.getProfileProxy(ActivityThread.currentApplication().getApplicationContext(),
                new MapMessageSender(destAddr, text, device, sentIntent, deliveryIntent),
                BluetoothProfile.MAP_CLIENT);
    }

    private class MapMessageSender implements BluetoothProfile.ServiceListener {
        final Uri[] mDestAddr;
        private String mMessage;
        final BluetoothDevice mDevice;
        final PendingIntent mSentIntent;
        final PendingIntent mDeliveryIntent;
        MapMessageSender(final String destAddr, final String message, final BluetoothDevice device,
                final PendingIntent sentIntent, final PendingIntent deliveryIntent) {
            super();
            mDestAddr = new Uri[] {new Uri.Builder()
                    .appendPath(destAddr)
                    .scheme(PhoneAccount.SCHEME_TEL)
                    .build()};
            mMessage = message;
            mDevice = device;
            mSentIntent = sentIntent;
            mDeliveryIntent = deliveryIntent;
        }

        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (DBG) Log.d(TAG, "Service connected");
            if (profile != BluetoothProfile.MAP_CLIENT) return;
            BluetoothMapClient mapProfile = (BluetoothMapClient) proxy;
            if (mMessage != null) {
                if (DBG) Log.d(TAG, "Sending message thru bluetooth");
                mapProfile.sendMessage(mDevice, mDestAddr, mMessage, mSentIntent, mDeliveryIntent);
                mMessage = null;
            }
            BluetoothAdapter.getDefaultAdapter()
                    .closeProfileProxy(BluetoothProfile.MAP_CLIENT, mapProfile);
        }

        @Override
        public void onServiceDisconnected(int profile) {
            if (mMessage != null) {
                if (DBG) Log.d(TAG, "Bluetooth disconnected before sending the message");
                sendErrorInPendingIntent(mSentIntent, SmsManager.RESULT_ERROR_NO_SERVICE);
                mMessage = null;
            }
        }
    }

    private void sendErrorInPendingIntent(PendingIntent intent, int errorCode) {
        if (intent == null) {
            return;
        }
        try {
            intent.send(errorCode);
        } catch (PendingIntent.CanceledException e) {
            // PendingIntent is cancelled. ignore sending this error code back to
            // caller.
            if (DBG) Log.d(TAG, "PendingIntent.CanceledException: " + e.getMessage());
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
                false /* persistMessage */);
    }

    /**
     * A variant of {@link SmsManager#sendTextMessage} that allows self to be the caller. This is
     * for internal use only.
     *
     * @param persistMessage whether to persist the sent message in the SMS app. the caller must be
     * the Phone process if set to false.
     *
     * @hide
     */
    public void sendTextMessageWithSelfPermissions(
            String destinationAddress, String scAddress, String text,
            PendingIntent sentIntent, PendingIntent deliveryIntent, boolean persistMessage) {
        if (TextUtils.isEmpty(destinationAddress)) {
            throw new IllegalArgumentException("Invalid destinationAddress");
        }

        if (TextUtils.isEmpty(text)) {
            throw new IllegalArgumentException("Invalid message body");
        }

        try {
            ISms iccISms = getISmsServiceOrThrow();
            iccISms.sendTextForSubscriberWithSelfPermissions(getSubscriptionId(),
                    ActivityThread.currentPackageName(),
                    destinationAddress,
                    scAddress, text, sentIntent, deliveryIntent, persistMessage);
        } catch (RemoteException ex) {
            // ignore it
        }
    }

    /**
     * Send a text based SMS with messaging options.
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
     *  For <code>RESULT_ERROR_GENERIC_FAILURE</code> the sentIntent may include
     *  the extra "errorCode" containing a radio technology specific value,
     *  generally only useful for troubleshooting.<br>
     *  The per-application based SMS control checks sentIntent. If sentIntent
     *  is NULL the caller will be checked against all unknown applications,
     *  which cause smaller number of SMS to be sent in checking period.
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
    @UnsupportedAppUsage
    public void sendTextMessage(
            String destinationAddress, String scAddress, String text,
            PendingIntent sentIntent, PendingIntent deliveryIntent,
            int priority, boolean expectMore, int validityPeriod) {
        sendTextMessageInternal(destinationAddress, scAddress, text, sentIntent, deliveryIntent,
                true /* persistMessage*/, priority, expectMore, validityPeriod);
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
            priority = SMS_MESSAGE_PRIORITY_NOT_SPECIFIED;
        }

        if (validityPeriod < 0x05 || validityPeriod > 0x09b0a0) {
            validityPeriod = SMS_MESSAGE_PERIOD_NOT_SPECIFIED;
        }

        try {
             ISms iccISms = getISmsServiceOrThrow();
            if (iccISms != null) {
                iccISms.sendTextForSubscriberWithOptions(getSubscriptionId(),
                        ActivityThread.currentPackageName(), destinationAddress, scAddress, text,
                        sentIntent, deliveryIntent, persistMessage,  priority, expectMore,
                        validityPeriod);
            }
        } catch (RemoteException ex) {
            // ignore it
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
     * @param pdu is the byte array of pdu to be injected into android application framework
     * @param format is the format of SMS pdu ({@link SmsMessage#FORMAT_3GPP} or
     *  {@link SmsMessage#FORMAT_3GPP2})
     * @param receivedIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is successfully received by the
     *  android application framework, or failed. This intent is broadcasted at
     *  the same time an SMS received from radio is acknowledged back.
     *  The result code will be {@link android.provider.Telephony.Sms.Intents#RESULT_SMS_HANDLED}
     *  for success, or {@link android.provider.Telephony.Sms.Intents#RESULT_SMS_GENERIC_ERROR} for
     *  error.
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
            ISms iccISms = ISms.Stub.asInterface(ServiceManager.getService("isms"));
            if (iccISms != null) {
                iccISms.injectSmsPduForSubscriber(
                        getSubscriptionId(), pdu, format, receivedIntent);
            }
        } catch (RemoteException ex) {
          // ignore it
        }
    }

    /**
     * Divide a message text into several fragments, none bigger than
     * the maximum SMS message size.
     *
     * @param text the original message.  Must not be null.
     * @return an <code>ArrayList</code> of strings that, in order,
     *   comprise the original message
     *
     * @throws IllegalArgumentException if text is null
     */
    public ArrayList<String> divideMessage(String text) {
        if (null == text) {
            throw new IllegalArgumentException("text is null");
        }
        return SmsMessage.fragmentText(text);
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
     * @param destinationAddress the address to send the message to
     * @param scAddress is the service center address or null to use
     *   the current default SMSC
     * @param parts an <code>ArrayList</code> of strings that, in order,
     *   comprise the original message
     * @param sentIntents if not null, an <code>ArrayList</code> of
     *   <code>PendingIntent</code>s (one for each message part) that is
     *   broadcast when the corresponding message part has been sent.
     *   The result code will be <code>Activity.RESULT_OK</code> for success,
     *   or one of these errors:<br>
     *   <code>RESULT_ERROR_GENERIC_FAILURE</code><br>
     *   <code>RESULT_ERROR_RADIO_OFF</code><br>
     *   <code>RESULT_ERROR_NULL_PDU</code><br>
     *   For <code>RESULT_ERROR_GENERIC_FAILURE</code> each sentIntent may include
     *   the extra "errorCode" containing a radio technology specific value,
     *   generally only useful for troubleshooting.<br>
     *   The per-application based SMS control checks sentIntent. If sentIntent
     *   is NULL the caller will be checked against all unknown applications,
     *   which cause smaller number of SMS to be sent in checking period.
     * @param deliveryIntents if not null, an <code>ArrayList</code> of
     *   <code>PendingIntent</code>s (one for each message part) that is
     *   broadcast when the corresponding message part has been delivered
     *   to the recipient.  The raw pdu of the status report is in the
     *   extended data ("pdu").
     *
     * @throws IllegalArgumentException if destinationAddress or data are empty
     */
    public void sendMultipartTextMessage(
            String destinationAddress, String scAddress, ArrayList<String> parts,
            ArrayList<PendingIntent> sentIntents, ArrayList<PendingIntent> deliveryIntents) {
        sendMultipartTextMessageInternal(destinationAddress, scAddress, parts, sentIntents,
                deliveryIntents, true /* persistMessage*/);
    }

    private void sendMultipartTextMessageInternal(
            String destinationAddress, String scAddress, List<String> parts,
            List<PendingIntent> sentIntents, List<PendingIntent> deliveryIntents,
            boolean persistMessage) {
        if (TextUtils.isEmpty(destinationAddress)) {
            throw new IllegalArgumentException("Invalid destinationAddress");
        }
        if (parts == null || parts.size() < 1) {
            throw new IllegalArgumentException("Invalid message body");
        }

        if (parts.size() > 1) {
            try {
                ISms iccISms = getISmsServiceOrThrow();
                iccISms.sendMultipartTextForSubscriber(getSubscriptionId(),
                        ActivityThread.currentPackageName(),
                        destinationAddress, scAddress, parts,
                        sentIntents, deliveryIntents, persistMessage);
            } catch (RemoteException ex) {
                // ignore it
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
            sendTextMessage(destinationAddress, scAddress, parts.get(0),
                    sentIntent, deliveryIntent);
        }
    }

    /**
     * Send a multi-part text based SMS without writing it into the SMS Provider.
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
                deliveryIntents, false /* persistMessage*/);
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
     * @param destinationAddress the address to send the message to
     * @param scAddress is the service center address or null to use
     *   the current default SMSC
     * @param parts an <code>ArrayList</code> of strings that, in order,
     *   comprise the original message
     * @param sentIntents if not null, an <code>ArrayList</code> of
     *   <code>PendingIntent</code>s (one for each message part) that is
     *   broadcast when the corresponding message part has been sent.
     *   The result code will be <code>Activity.RESULT_OK</code> for success,
     *   or one of these errors:<br>
     *   <code>RESULT_ERROR_GENERIC_FAILURE</code><br>
     *   <code>RESULT_ERROR_RADIO_OFF</code><br>
     *   <code>RESULT_ERROR_NULL_PDU</code><br>
     *   For <code>RESULT_ERROR_GENERIC_FAILURE</code> each sentIntent may include
     *   the extra "errorCode" containing a radio technology specific value,
     *   generally only useful for troubleshooting.<br>
     *   The per-application based SMS control checks sentIntent. If sentIntent
     *   is NULL the caller will be checked against all unknown applications,
     *   which cause smaller number of SMS to be sent in checking period.
     * @param deliveryIntents if not null, an <code>ArrayList</code> of
     *   <code>PendingIntent</code>s (one for each message part) that is
     *   broadcast when the corresponding message part has been delivered
     *   to the recipient.  The raw pdu of the status report is in the
     *   extended data ("pdu").
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
           priority = SMS_MESSAGE_PRIORITY_NOT_SPECIFIED;
        }

        if (validityPeriod < 0x05 || validityPeriod > 0x09b0a0) {
           validityPeriod = SMS_MESSAGE_PERIOD_NOT_SPECIFIED;
        }

        if (parts.size() > 1) {
            try {
                 ISms iccISms = getISmsServiceOrThrow();
                if (iccISms != null) {
                    iccISms.sendMultipartTextForSubscriberWithOptions(getSubscriptionId(),
                            ActivityThread.currentPackageName(), destinationAddress, scAddress,
                            parts, sentIntents, deliveryIntents, persistMessage, priority,
                            expectMore, validityPeriod);
                }
            } catch (RemoteException ex) {
                // ignore it
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
     * Send a multi-part text based SMS without writing it into the SMS Provider.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#MODIFY_PHONE_STATE} or the calling app has carrier
     * privileges.
     * </p>
     *
     * @see #sendMultipartTextMessage(String, String, ArrayList, ArrayList,
     * ArrayList, int, boolean, int)
     * @hide
     **/
    public void sendMultipartTextMessageWithoutPersisting(
            String destinationAddress, String scAddress, List<String> parts,
            List<PendingIntent> sentIntents, List<PendingIntent> deliveryIntents,
            int priority, boolean expectMore, int validityPeriod) {
        sendMultipartTextMessageInternal(destinationAddress, scAddress, parts, sentIntents,
                deliveryIntents, false /* persistMessage*/, priority, expectMore,
                validityPeriod);
    }

   /**
     * Send a data based SMS to a specific application port.
     *
     * <p class="note"><strong>Note:</strong> Using this method requires that your app has the
     * {@link android.Manifest.permission#SEND_SMS} permission.</p>
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
     *  For <code>RESULT_ERROR_GENERIC_FAILURE</code> the sentIntent may include
     *  the extra "errorCode" containing a radio technology specific value,
     *  generally only useful for troubleshooting.<br>
     *  The per-application based SMS control checks sentIntent. If sentIntent
     *  is NULL the caller will be checked against all unknown applications,
     *  which cause smaller number of SMS to be sent in checking period.
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

        try {
            ISms iccISms = getISmsServiceOrThrow();
            iccISms.sendDataForSubscriber(getSubscriptionId(), ActivityThread.currentPackageName(),
                    destinationAddress, scAddress, destinationPort & 0xFFFF,
                    data, sentIntent, deliveryIntent);
        } catch (RemoteException ex) {
            // ignore it
        }
    }

    /**
     * A variant of {@link SmsManager#sendDataMessage} that allows self to be the caller. This is
     * for internal use only.
     *
     * @hide
     */
    public void sendDataMessageWithSelfPermissions(
            String destinationAddress, String scAddress, short destinationPort,
            byte[] data, PendingIntent sentIntent, PendingIntent deliveryIntent) {
        if (TextUtils.isEmpty(destinationAddress)) {
            throw new IllegalArgumentException("Invalid destinationAddress");
        }

        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Invalid message data");
        }

        try {
            ISms iccISms = getISmsServiceOrThrow();
            iccISms.sendDataForSubscriberWithSelfPermissions(getSubscriptionId(),
                    ActivityThread.currentPackageName(), destinationAddress, scAddress,
                    destinationPort & 0xFFFF, data, sentIntent, deliveryIntent);
        } catch (RemoteException ex) {
            // ignore it
        }
    }

    /**
     * Get the SmsManager associated with the default subscription id. The instance will always be
     * associated with the default subscription id, even if the default subscription id is changed.
     *
     * @return the SmsManager associated with the default subscription id
     */
    public static SmsManager getDefault() {
        return sInstance;
    }

    /**
     * Get the the instance of the SmsManager associated with a particular subscription id
     *
     * @param subId an SMS subscription id, typically accessed using
     *   {@link android.telephony.SubscriptionManager}
     * @return the instance of the SmsManager associated with subId
     */
    public static SmsManager getSmsManagerForSubscriptionId(int subId) {
        // TODO(shri): Add javadoc link once SubscriptionManager is made public api
        synchronized(sLockObject) {
            SmsManager smsManager = sSubInstances.get(subId);
            if (smsManager == null) {
                smsManager = new SmsManager(subId);
                sSubInstances.put(subId, smsManager);
            }
            return smsManager;
        }
    }

    private SmsManager(int subId) {
        mSubId = subId;
    }

    /**
     * Get the associated subscription id. If the instance was returned by {@link #getDefault()},
     * then this method may return different values at different points in time (if the user
     * changes the default subscription id). It will return < 0 if the default subscription id
     * cannot be determined.
     *
     * Additionally, to support legacy applications that are not multi-SIM aware,
     * if the following are true:
     *     - We are using a multi-SIM device
     *     - A default SMS SIM has not been selected
     *     - At least one SIM subscription is available
     * then ask the user to set the default SMS SIM.
     *
     * @return associated subscription id
     */
    public int getSubscriptionId() {
        final int subId = (mSubId == DEFAULT_SUBSCRIPTION_ID)
                ? getDefaultSmsSubscriptionId() : mSubId;
        boolean isSmsSimPickActivityNeeded = false;
        final Context context = ActivityThread.currentApplication().getApplicationContext();
        try {
            ISms iccISms = getISmsService();
            if (iccISms != null) {
                isSmsSimPickActivityNeeded = iccISms.isSmsSimPickActivityNeeded(subId);
            }
        } catch (RemoteException ex) {
            Log.e(TAG, "Exception in getSubscriptionId");
        }

        if (isSmsSimPickActivityNeeded) {
            Log.d(TAG, "getSubscriptionId isSmsSimPickActivityNeeded is true");
            // ask the user for a default SMS SIM.
            Intent intent = new Intent();
            intent.setClassName("com.android.settings",
                    "com.android.settings.sim.SimDialogActivity");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra(DIALOG_TYPE_KEY, SMS_PICK);
            try {
                context.startActivity(intent);
            } catch (ActivityNotFoundException anfe) {
                // If Settings is not installed, only log the error as we do not want to break
                // legacy applications.
                Log.e(TAG, "Unable to launch Settings application.");
            }
        }

        return subId;
    }

    /**
     * Returns the ISms service, or throws an UnsupportedOperationException if
     * the service does not exist.
     */
    private static ISms getISmsServiceOrThrow() {
        ISms iccISms = getISmsService();
        if (iccISms == null) {
            throw new UnsupportedOperationException("Sms is not supported");
        }
        return iccISms;
    }

    private static ISms getISmsService() {
        return ISms.Stub.asInterface(ServiceManager.getService("isms"));
    }

    /**
     * Copy a raw SMS PDU to the ICC.
     * ICC (Integrated Circuit Card) is the card of the device.
     * For example, this can be the SIM or USIM for GSM.
     *
     * @param smsc the SMSC for this message, or NULL for the default SMSC
     * @param pdu the raw PDU to store
     * @param status message status (STATUS_ON_ICC_READ, STATUS_ON_ICC_UNREAD,
     *               STATUS_ON_ICC_SENT, STATUS_ON_ICC_UNSENT)
     * @return true for success
     *
     * @throws IllegalArgumentException if pdu is NULL
     * {@hide}
     */
    @UnsupportedAppUsage
    public boolean copyMessageToIcc(byte[] smsc, byte[] pdu,int status) {
        boolean success = false;

        if (null == pdu) {
            throw new IllegalArgumentException("pdu is NULL");
        }
        try {
            ISms iccISms = getISmsService();
            if (iccISms != null) {
                success = iccISms.copyMessageToIccEfForSubscriber(getSubscriptionId(),
                        ActivityThread.currentPackageName(),
                        status, pdu, smsc);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return success;
    }

    /**
     * Delete the specified message from the ICC.
     * ICC (Integrated Circuit Card) is the card of the device.
     * For example, this can be the SIM or USIM for GSM.
     *
     * @param messageIndex is the record index of the message on ICC
     * @return true for success
     *
     * {@hide}
     */
    @UnsupportedAppUsage
    public boolean
    deleteMessageFromIcc(int messageIndex) {
        boolean success = false;
        byte[] pdu = new byte[SMS_RECORD_LENGTH-1];
        Arrays.fill(pdu, (byte)0xff);

        try {
            ISms iccISms = getISmsService();
            if (iccISms != null) {
                success = iccISms.updateMessageOnIccEfForSubscriber(getSubscriptionId(),
                        ActivityThread.currentPackageName(),
                        messageIndex, STATUS_ON_ICC_FREE, pdu);
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
    public boolean updateMessageOnIcc(int messageIndex, int newStatus, byte[] pdu) {
        boolean success = false;

        try {
            ISms iccISms = getISmsService();
            if (iccISms != null) {
                success = iccISms.updateMessageOnIccEfForSubscriber(getSubscriptionId(),
                        ActivityThread.currentPackageName(),
                        messageIndex, newStatus, pdu);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return success;
    }

    /**
     * Retrieves all messages currently stored on ICC.
     * ICC (Integrated Circuit Card) is the card of the device.
     * For example, this can be the SIM or USIM for GSM.
     *
     * @return <code>ArrayList</code> of <code>SmsMessage</code> objects
     *
     * {@hide}
     */
    @UnsupportedAppUsage
    public ArrayList<SmsMessage> getAllMessagesFromIcc() {
        List<SmsRawData> records = null;

        try {
            ISms iccISms = getISmsService();
            if (iccISms != null) {
                records = iccISms.getAllMessagesFromIccEfForSubscriber(
                        getSubscriptionId(),
                        ActivityThread.currentPackageName());
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return createMessageListFromRawRecords(records);
    }

    /**
     * Enable reception of cell broadcast (SMS-CB) messages with the given
     * message identifier and RAN type. The RAN type specify this message ID
     * belong to 3GPP (GSM) or 3GPP2(CDMA).Note that if two different clients
     * enable the same message identifier, they must both disable it for the device to stop
     * receiving those messages. All received messages will be broadcast in an
     * intent with the action "android.provider.Telephony.SMS_CB_RECEIVED".
     * Note: This call is blocking, callers may want to avoid calling it from
     * the main thread of an application.
     *
     * @param messageIdentifier Message identifier as specified in TS 23.041 (3GPP)
     * or C.R1001-G (3GPP2)
     * @param ranType as defined in class SmsManager, the value can be one of these:
     *    android.telephony.SmsMessage.CELL_BROADCAST_RAN_TYPE_GSM
     *    android.telephony.SmsMessage.CELL_BROADCAST_RAN_TYPE_CDMA
     * @return true if successful, false otherwise
     * @see #disableCellBroadcast(int, int)
     *
     * {@hide}
     */
    public boolean enableCellBroadcast(int messageIdentifier, int ranType) {
        boolean success = false;

        try {
            ISms iccISms = getISmsService();
            if (iccISms != null) {
                success = iccISms.enableCellBroadcastForSubscriber(
                        getSubscriptionId(), messageIdentifier, ranType);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return success;
    }

    /**
     * Disable reception of cell broadcast (SMS-CB) messages with the given
     * message identifier and RAN type. The RAN type specify this message ID
     * belong to 3GPP (GSM) or 3GPP2(CDMA). Note that if two different clients
     * enable the same message identifier, they must both disable it for the
     * device to stop receiving those messages.
     * Note: This call is blocking, callers may want to avoid calling it from
     * the main thread of an application.
     *
     * @param messageIdentifier Message identifier as specified in TS 23.041 (3GPP)
     * or C.R1001-G (3GPP2)
     * @param ranType as defined in class SmsManager, the value can be one of these:
     *    android.telephony.SmsMessage.CELL_BROADCAST_RAN_TYPE_GSM
     *    android.telephony.SmsMessage.CELL_BROADCAST_RAN_TYPE_CDMA
     * @return true if successful, false otherwise
     *
     * @see #enableCellBroadcast(int, int)
     *
     * {@hide}
     */
    public boolean disableCellBroadcast(int messageIdentifier, int ranType) {
        boolean success = false;

        try {
            ISms iccISms = getISmsService();
            if (iccISms != null) {
                success = iccISms.disableCellBroadcastForSubscriber(
                        getSubscriptionId(), messageIdentifier, ranType);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return success;
    }

    /**
     * Enable reception of cell broadcast (SMS-CB) messages with the given
     * message identifier range and RAN type. The RAN type specify this message ID
     * belong to 3GPP (GSM) or 3GPP2(CDMA). Note that if two different clients enable
     * the same message identifier, they must both disable it for the device to stop
     * receiving those messages. All received messages will be broadcast in an
     * intent with the action "android.provider.Telephony.SMS_CB_RECEIVED".
     * Note: This call is blocking, callers may want to avoid calling it from
     * the main thread of an application.
     *
     * @param startMessageId first message identifier as specified in TS 23.041 (3GPP)
     * or C.R1001-G (3GPP2)
     * @param endMessageId last message identifier as specified in TS 23.041 (3GPP)
     * or C.R1001-G (3GPP2)
     * @param ranType as defined in class SmsManager, the value can be one of these:
     *    android.telephony.SmsMessage.CELL_BROADCAST_RAN_TYPE_GSM
     *    android.telephony.SmsMessage.CELL_BROADCAST_RAN_TYPE_CDMA
     * @return true if successful, false otherwise
     * @see #disableCellBroadcastRange(int, int, int)
     *
     * @throws IllegalArgumentException if endMessageId < startMessageId
     * {@hide}
     */
    @UnsupportedAppUsage
    public boolean enableCellBroadcastRange(int startMessageId, int endMessageId, int ranType) {
        boolean success = false;

        if (endMessageId < startMessageId) {
            throw new IllegalArgumentException("endMessageId < startMessageId");
        }
        try {
            ISms iccISms = getISmsService();
            if (iccISms != null) {
                success = iccISms.enableCellBroadcastRangeForSubscriber(getSubscriptionId(),
                        startMessageId, endMessageId, ranType);
            }
        } catch (RemoteException ex) {
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
     * @param startMessageId first message identifier as specified in TS 23.041 (3GPP)
     * or C.R1001-G (3GPP2)
     * @param endMessageId last message identifier as specified in TS 23.041 (3GPP)
     * or C.R1001-G (3GPP2)
     * @param ranType as defined in class SmsManager, the value can be one of these:
     *    android.telephony.SmsMessage.CELL_BROADCAST_RAN_TYPE_GSM
     *    android.telephony.SmsMessage.CELL_BROADCAST_RAN_TYPE_CDMA
     * @return true if successful, false otherwise
     *
     * @see #enableCellBroadcastRange(int, int, int)
     *
     * @throws IllegalArgumentException if endMessageId < startMessageId
     * {@hide}
     */
    @UnsupportedAppUsage
    public boolean disableCellBroadcastRange(int startMessageId, int endMessageId, int ranType) {
        boolean success = false;

        if (endMessageId < startMessageId) {
            throw new IllegalArgumentException("endMessageId < startMessageId");
        }
        try {
            ISms iccISms = getISmsService();
            if (iccISms != null) {
                success = iccISms.disableCellBroadcastRangeForSubscriber(getSubscriptionId(),
                        startMessageId, endMessageId, ranType);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return success;
    }

    /**
     * Create a list of <code>SmsMessage</code>s from a list of RawSmsData
     * records returned by <code>getAllMessagesFromIcc()</code>
     *
     * @param records SMS EF records, returned by
     *   <code>getAllMessagesFromIcc</code>
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
                    SmsMessage sms = SmsMessage.createFromEfRecord(i+1, data.getBytes(),
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
     * @return true if SMS over IMS is supported, false otherwise
     *
     * @see #getImsSmsFormat()
     *
     * @hide
     */
    public boolean isImsSmsSupported() {
        boolean boSupported = false;
        try {
            ISms iccISms = getISmsService();
            if (iccISms != null) {
                boSupported = iccISms.isImsSmsSupportedForSubscriber(getSubscriptionId());
            }
        } catch (RemoteException ex) {
            // ignore it
        }
        return boSupported;
    }

    /**
     * Gets SMS format supported on IMS.  SMS over IMS format is
     * either 3GPP or 3GPP2.
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
            ISms iccISms = getISmsService();
            if (iccISms != null) {
                format = iccISms.getImsSmsFormatForSubscriber(getSubscriptionId());
            }
        } catch (RemoteException ex) {
            // ignore it
        }
        return format;
    }

    /**
     * Get default sms subscription id
     *
     * @return the default SMS subscription id
     */
    public static int getDefaultSmsSubscriptionId() {
        ISms iccISms = null;
        try {
            iccISms = ISms.Stub.asInterface(ServiceManager.getService("isms"));
            return iccISms.getPreferredSmsSubscription();
        } catch (RemoteException ex) {
            return -1;
        } catch (NullPointerException ex) {
            return -1;
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
        ISms iccISms = null;
        try {
            iccISms = ISms.Stub.asInterface(ServiceManager.getService("isms"));
            return iccISms.isSMSPromptEnabled();
        } catch (RemoteException ex) {
            return false;
        } catch (NullPointerException ex) {
            return false;
        }
    }

    // see SmsMessage.getStatusOnIcc

    /** Free space (TS 51.011 10.5.3 / 3GPP2 C.S0023 3.4.27). */
    static public final int STATUS_ON_ICC_FREE      = 0;

    /** Received and read (TS 51.011 10.5.3 / 3GPP2 C.S0023 3.4.27). */
    static public final int STATUS_ON_ICC_READ      = 1;

    /** Received and unread (TS 51.011 10.5.3 / 3GPP2 C.S0023 3.4.27). */
    static public final int STATUS_ON_ICC_UNREAD    = 3;

    /** Stored and sent (TS 51.011 10.5.3 / 3GPP2 C.S0023 3.4.27). */
    static public final int STATUS_ON_ICC_SENT      = 5;

    /** Stored and unsent (TS 51.011 10.5.3 / 3GPP2 C.S0023 3.4.27). */
    static public final int STATUS_ON_ICC_UNSENT    = 7;

    // SMS send failure result codes

    /**
     * No error.
     * @hide
     */
    @SystemApi
    static public final int RESULT_ERROR_NONE    = 0;
    /** Generic failure cause */
    static public final int RESULT_ERROR_GENERIC_FAILURE    = 1;
    /** Failed because radio was explicitly turned off */
    static public final int RESULT_ERROR_RADIO_OFF          = 2;
    /** Failed because no pdu provided */
    static public final int RESULT_ERROR_NULL_PDU           = 3;
    /** Failed because service is currently unavailable */
    static public final int RESULT_ERROR_NO_SERVICE         = 4;
    /** Failed because we reached the sending queue limit. */
    static public final int RESULT_ERROR_LIMIT_EXCEEDED     = 5;
    /**
     * Failed because FDN is enabled.
     * @hide
     */
    @SystemApi
    static public final int RESULT_ERROR_FDN_CHECK_FAILURE  = 6;
    /** Failed because user denied the sending of this short code. */
    static public final int RESULT_ERROR_SHORT_CODE_NOT_ALLOWED = 7;
    /** Failed because the user has denied this app ever send premium short codes. */
    static public final int RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED = 8;
    /**
     * Failed because the radio was not available
     * @hide
     */
    @SystemApi
    static public final int RESULT_RADIO_NOT_AVAILABLE = 9;
    /**
     * Failed because of network rejection
     * @hide
     */
    @SystemApi
    static public final int RESULT_NETWORK_REJECT = 10;
    /**
     * Failed because of invalid arguments
     * @hide
     */
    @SystemApi
    static public final int RESULT_INVALID_ARGUMENTS = 11;
    /**
     * Failed because of an invalid state
     * @hide
     */
    @SystemApi
    static public final int RESULT_INVALID_STATE = 12;
    /**
     * Failed because there is no memory
     * @hide
     */
    @SystemApi
    static public final int RESULT_NO_MEMORY = 13;
    /**
     * Failed because the sms format is not valid
     * @hide
     */
    @SystemApi
    static public final int RESULT_INVALID_SMS_FORMAT = 14;
    /**
     * Failed because of a system error
     * @hide
     */
    @SystemApi
    static public final int RESULT_SYSTEM_ERROR = 15;
    /**
     * Failed because of a modem error
     * @hide
     */
    @SystemApi
    static public final int RESULT_MODEM_ERROR = 16;
    /**
     * Failed because of a network error
     * @hide
     */
    @SystemApi
    static public final int RESULT_NETWORK_ERROR = 17;
    /**
     * Failed because of an encoding error
     * @hide
     */
    @SystemApi
    static public final int RESULT_ENCODING_ERROR = 18;
    /**
     * Failed because of an invalid smsc address
     * @hide
     */
    @SystemApi
    static public final int RESULT_INVALID_SMSC_ADDRESS = 19;
    /**
     * Failed because the operation is not allowed
     * @hide
     */
    @SystemApi
    static public final int RESULT_OPERATION_NOT_ALLOWED = 20;
    /**
     * Failed because of an internal error
     * @hide
     */
    @SystemApi
    static public final int RESULT_INTERNAL_ERROR = 21;
    /**
     * Failed because there are no resources
     * @hide
     */
    @SystemApi
    static public final int RESULT_NO_RESOURCES = 22;
    /**
     * Failed because the operation was cancelled
     * @hide
     */
    @SystemApi
    static public final int RESULT_CANCELLED = 23;
    /**
     * Failed because the request is not supported
     * @hide
     */
    @SystemApi
    static public final int RESULT_REQUEST_NOT_SUPPORTED = 24;


    static private final String PHONE_PACKAGE_NAME = "com.android.phone";

    /**
     * Send an MMS message
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
        if (contentUri == null) {
            throw new IllegalArgumentException("Uri contentUri null");
        }
        try {
            final IMms iMms = IMms.Stub.asInterface(ServiceManager.getService("imms"));
            if (iMms == null) {
                return;
            }

            iMms.sendMessage(getSubscriptionId(), ActivityThread.currentPackageName(), contentUri,
                    locationUrl, configOverrides, sentIntent);
        } catch (RemoteException e) {
            // Ignore it
        }
    }

    /**
     * Download an MMS message from carrier by a given location URL
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
        if (TextUtils.isEmpty(locationUrl)) {
            throw new IllegalArgumentException("Empty MMS location URL");
        }
        if (contentUri == null) {
            throw new IllegalArgumentException("Uri contentUri null");
        }
        try {
            final IMms iMms = IMms.Stub.asInterface(ServiceManager.getService("imms"));
            if (iMms == null) {
                return;
            }
            iMms.downloadMessage(
                    getSubscriptionId(), ActivityThread.currentPackageName(), locationUrl,
                    contentUri, configOverrides, downloadedIntent);
        } catch (RemoteException e) {
            // Ignore it
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
     * Import a text message into system's SMS store
     *
     * Only default SMS apps can import SMS
     *
     * @param address the destination(source) address of the sent(received) message
     * @param type the type of the message
     * @param text the message text
     * @param timestampMillis the message timestamp in milliseconds
     * @param seen if the message is seen
     * @param read if the message is read
     * @return the message URI, null if failed
     * @hide
     */
    public Uri importTextMessage(String address, int type, String text, long timestampMillis,
            boolean seen, boolean read) {
        try {
            IMms iMms = IMms.Stub.asInterface(ServiceManager.getService("imms"));
            if (iMms != null) {
                return iMms.importTextMessage(ActivityThread.currentPackageName(),
                        address, type, text, timestampMillis, seen, read);
            }
        } catch (RemoteException ex) {
            // ignore it
        }
        return null;
    }

    /** Represents the received SMS message for importing {@hide} */
    public static final int SMS_TYPE_INCOMING = 0;
    /** Represents the sent SMS message for importing {@hide} */
    public static final int SMS_TYPE_OUTGOING = 1;

    /**
     * Import a multimedia message into system's MMS store. Only the following PDU type is
     * supported: Retrieve.conf, Send.req, Notification.ind, Delivery.ind, Read-Orig.ind
     *
     * Only default SMS apps can import MMS
     *
     * @param contentUri the content uri from which to read the PDU of the message to import
     * @param messageId the optional message id. Use null if not specifying
     * @param timestampSecs the optional message timestamp. Use -1 if not specifying
     * @param seen if the message is seen
     * @param read if the message is read
     * @return the message URI, null if failed
     * @throws IllegalArgumentException if pdu is empty
     * {@hide}
     */
    public Uri importMultimediaMessage(Uri contentUri, String messageId, long timestampSecs,
            boolean seen, boolean read) {
        if (contentUri == null) {
            throw new IllegalArgumentException("Uri contentUri null");
        }
        try {
            IMms iMms = IMms.Stub.asInterface(ServiceManager.getService("imms"));
            if (iMms != null) {
                return iMms.importMultimediaMessage(ActivityThread.currentPackageName(),
                        contentUri, messageId, timestampSecs, seen, read);
            }
        } catch (RemoteException ex) {
            // ignore it
        }
        return null;
    }

    /**
     * Delete a system stored SMS or MMS message
     *
     * Only default SMS apps can delete system stored SMS and MMS messages
     *
     * @param messageUri the URI of the stored message
     * @return true if deletion is successful, false otherwise
     * @throws IllegalArgumentException if messageUri is empty
     * {@hide}
     */
    public boolean deleteStoredMessage(Uri messageUri) {
        if (messageUri == null) {
            throw new IllegalArgumentException("Empty message URI");
        }
        try {
            IMms iMms = IMms.Stub.asInterface(ServiceManager.getService("imms"));
            if (iMms != null) {
                return iMms.deleteStoredMessage(ActivityThread.currentPackageName(), messageUri);
            }
        } catch (RemoteException ex) {
            // ignore it
        }
        return false;
    }

    /**
     * Delete a system stored SMS or MMS thread
     *
     * Only default SMS apps can delete system stored SMS and MMS conversations
     *
     * @param conversationId the ID of the message conversation
     * @return true if deletion is successful, false otherwise
     * {@hide}
     */
    public boolean deleteStoredConversation(long conversationId) {
        try {
            IMms iMms = IMms.Stub.asInterface(ServiceManager.getService("imms"));
            if (iMms != null) {
                return iMms.deleteStoredConversation(
                        ActivityThread.currentPackageName(), conversationId);
            }
        } catch (RemoteException ex) {
            // ignore it
        }
        return false;
    }

    /**
     * Update the status properties of a system stored SMS or MMS message, e.g.
     * the read status of a message, etc.
     *
     * @param messageUri the URI of the stored message
     * @param statusValues a list of status properties in key-value pairs to update
     * @return true if update is successful, false otherwise
     * @throws IllegalArgumentException if messageUri is empty
     * {@hide}
     */
    public boolean updateStoredMessageStatus(Uri messageUri, ContentValues statusValues) {
        if (messageUri == null) {
            throw new IllegalArgumentException("Empty message URI");
        }
        try {
            IMms iMms = IMms.Stub.asInterface(ServiceManager.getService("imms"));
            if (iMms != null) {
                return iMms.updateStoredMessageStatus(ActivityThread.currentPackageName(),
                        messageUri, statusValues);
            }
        } catch (RemoteException ex) {
            // ignore it
        }
        return false;
    }

    /** Message status property: whether the message has been seen. 1 means seen, 0 not {@hide} */
    public static final String MESSAGE_STATUS_SEEN = "seen";
    /** Message status property: whether the message has been read. 1 means read, 0 not {@hide} */
    public static final String MESSAGE_STATUS_READ = "read";

    /**
     * Archive or unarchive a stored conversation
     *
     * @param conversationId the ID of the message conversation
     * @param archived true to archive the conversation, false to unarchive
     * @return true if update is successful, false otherwise
     * {@hide}
     */
    public boolean archiveStoredConversation(long conversationId, boolean archived) {
        try {
            IMms iMms = IMms.Stub.asInterface(ServiceManager.getService("imms"));
            if (iMms != null) {
                return iMms.archiveStoredConversation(ActivityThread.currentPackageName(),
                        conversationId, archived);
            }
        } catch (RemoteException ex) {
            // ignore it
        }
        return false;
    }

    /**
     * Add a text message draft to system SMS store
     *
     * Only default SMS apps can add SMS draft
     *
     * @param address the destination address of message
     * @param text the body of the message to send
     * @return the URI of the stored draft message
     * {@hide}
     */
    public Uri addTextMessageDraft(String address, String text) {
        try {
            IMms iMms = IMms.Stub.asInterface(ServiceManager.getService("imms"));
            if (iMms != null) {
                return iMms.addTextMessageDraft(ActivityThread.currentPackageName(), address, text);
            }
        } catch (RemoteException ex) {
            // ignore it
        }
        return null;
    }

    /**
     * Add a multimedia message draft to system MMS store
     *
     * Only default SMS apps can add MMS draft
     *
     * @param contentUri the content uri from which to read the PDU data of the draft MMS
     * @return the URI of the stored draft message
     * @throws IllegalArgumentException if pdu is empty
     * {@hide}
     */
    public Uri addMultimediaMessageDraft(Uri contentUri) {
        if (contentUri == null) {
            throw new IllegalArgumentException("Uri contentUri null");
        }
        try {
            IMms iMms = IMms.Stub.asInterface(ServiceManager.getService("imms"));
            if (iMms != null) {
                return iMms.addMultimediaMessageDraft(ActivityThread.currentPackageName(),
                        contentUri);
            }
        } catch (RemoteException ex) {
            // ignore it
        }
        return null;
    }

    /**
     * Send a system stored text message.
     *
     * You can only send a failed text message or a draft text message.
     *
     * @param messageUri the URI of the stored message
     * @param scAddress is the service center address or null to use the current default SMSC
     * @param sentIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is successfully sent, or failed.
     *  The result code will be <code>Activity.RESULT_OK</code> for success,
     *  or one of these errors:<br>
     *  <code>RESULT_ERROR_GENERIC_FAILURE</code><br>
     *  <code>RESULT_ERROR_RADIO_OFF</code><br>
     *  <code>RESULT_ERROR_NULL_PDU</code><br>
     *  For <code>RESULT_ERROR_GENERIC_FAILURE</code> the sentIntent may include
     *  the extra "errorCode" containing a radio technology specific value,
     *  generally only useful for troubleshooting.<br>
     *  The per-application based SMS control checks sentIntent. If sentIntent
     *  is NULL the caller will be checked against all unknown applications,
     *  which cause smaller number of SMS to be sent in checking period.
     * @param deliveryIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is delivered to the recipient.  The
     *  raw pdu of the status report is in the extended data ("pdu").
     *
     * @throws IllegalArgumentException if messageUri is empty
     * {@hide}
     */
    public void sendStoredTextMessage(Uri messageUri, String scAddress, PendingIntent sentIntent,
            PendingIntent deliveryIntent) {
        if (messageUri == null) {
            throw new IllegalArgumentException("Empty message URI");
        }
        try {
            ISms iccISms = getISmsServiceOrThrow();
            iccISms.sendStoredText(
                    getSubscriptionId(), ActivityThread.currentPackageName(), messageUri,
                    scAddress, sentIntent, deliveryIntent);
        } catch (RemoteException ex) {
            // ignore it
        }
    }

    /**
     * Send a system stored multi-part text message.
     *
     * You can only send a failed text message or a draft text message.
     * The provided <code>PendingIntent</code> lists should match the part number of the
     * divided text of the stored message by using <code>divideMessage</code>
     *
     * @param messageUri the URI of the stored message
     * @param scAddress is the service center address or null to use
     *   the current default SMSC
     * @param sentIntents if not null, an <code>ArrayList</code> of
     *   <code>PendingIntent</code>s (one for each message part) that is
     *   broadcast when the corresponding message part has been sent.
     *   The result code will be <code>Activity.RESULT_OK</code> for success,
     *   or one of these errors:<br>
     *   <code>RESULT_ERROR_GENERIC_FAILURE</code><br>
     *   <code>RESULT_ERROR_RADIO_OFF</code><br>
     *   <code>RESULT_ERROR_NULL_PDU</code><br>
     *   For <code>RESULT_ERROR_GENERIC_FAILURE</code> each sentIntent may include
     *   the extra "errorCode" containing a radio technology specific value,
     *   generally only useful for troubleshooting.<br>
     *   The per-application based SMS control checks sentIntent. If sentIntent
     *   is NULL the caller will be checked against all unknown applications,
     *   which cause smaller number of SMS to be sent in checking period.
     * @param deliveryIntents if not null, an <code>ArrayList</code> of
     *   <code>PendingIntent</code>s (one for each message part) that is
     *   broadcast when the corresponding message part has been delivered
     *   to the recipient.  The raw pdu of the status report is in the
     *   extended data ("pdu").
     *
     * @throws IllegalArgumentException if messageUri is empty
     * {@hide}
     */
    public void sendStoredMultipartTextMessage(Uri messageUri, String scAddress,
            ArrayList<PendingIntent> sentIntents, ArrayList<PendingIntent> deliveryIntents) {
        if (messageUri == null) {
            throw new IllegalArgumentException("Empty message URI");
        }
        try {
            ISms iccISms = getISmsServiceOrThrow();
            iccISms.sendStoredMultipartText(
                    getSubscriptionId(), ActivityThread.currentPackageName(), messageUri,
                    scAddress, sentIntents, deliveryIntents);
        } catch (RemoteException ex) {
            // ignore it
        }
    }

    /**
     * Send a system stored MMS message
     *
     * This is used for sending a previously sent, but failed-to-send, message or
     * for sending a text message that has been stored as a draft.
     *
     * @param messageUri the URI of the stored message
     * @param configOverrides the carrier-specific messaging configuration values to override for
     *  sending the message.
     * @param sentIntent if not NULL this <code>PendingIntent</code> is
     *  broadcast when the message is successfully sent, or failed
     * @throws IllegalArgumentException if messageUri is empty
     * {@hide}
     */
    public void sendStoredMultimediaMessage(Uri messageUri, Bundle configOverrides,
            PendingIntent sentIntent) {
        if (messageUri == null) {
            throw new IllegalArgumentException("Empty message URI");
        }
        try {
            IMms iMms = IMms.Stub.asInterface(ServiceManager.getService("imms"));
            if (iMms != null) {
                iMms.sendStoredMessage(
                        getSubscriptionId(), ActivityThread.currentPackageName(), messageUri,
                        configOverrides, sentIntent);
            }
        } catch (RemoteException ex) {
            // ignore it
        }
    }

    /**
     * Turns on/off the flag to automatically write sent/received SMS/MMS messages into system
     *
     * When this flag is on, all SMS/MMS sent/received are stored by system automatically
     * When this flag is off, only SMS/MMS sent by non-default SMS apps are stored by system
     * automatically
     *
     * This flag can only be changed by default SMS apps
     *
     * @param enabled Whether to enable message auto persisting
     * {@hide}
     */
    public void setAutoPersisting(boolean enabled) {
        try {
            IMms iMms = IMms.Stub.asInterface(ServiceManager.getService("imms"));
            if (iMms != null) {
                iMms.setAutoPersisting(ActivityThread.currentPackageName(), enabled);
            }
        } catch (RemoteException ex) {
            // ignore it
        }
    }

    /**
     * Get the value of the flag to automatically write sent/received SMS/MMS messages into system
     *
     * When this flag is on, all SMS/MMS sent/received are stored by system automatically
     * When this flag is off, only SMS/MMS sent by non-default SMS apps are stored by system
     * automatically
     *
     * @return the current value of the auto persist flag
     * {@hide}
     */
    public boolean getAutoPersisting() {
        try {
            IMms iMms = IMms.Stub.asInterface(ServiceManager.getService("imms"));
            if (iMms != null) {
                return iMms.getAutoPersisting();
            }
        } catch (RemoteException ex) {
            // ignore it
        }
        return false;
    }

    /**
     * Get carrier-dependent configuration values.
     *
     * @return bundle key/values pairs of configuration values
     */
    public Bundle getCarrierConfigValues() {
        try {
            IMms iMms = IMms.Stub.asInterface(ServiceManager.getService("imms"));
            if (iMms != null) {
                return iMms.getCarrierConfigValues(getSubscriptionId());
            }
        } catch (RemoteException ex) {
            // ignore it
        }
        return null;
    }

    /**
     * Create a single use app specific incoming SMS request for the the calling package.
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
     * @return Token to include in an SMS message. The token will be 11 characters long.
     * @see android.provider.Telephony.Sms.Intents#getMessagesFromIntent
     */
    public String createAppSpecificSmsToken(PendingIntent intent) {
        try {
            ISms iccSms = getISmsServiceOrThrow();
            return iccSms.createAppSpecificSmsToken(getSubscriptionId(),
                    ActivityThread.currentPackageName(), intent);

        } catch (RemoteException ex) {
            ex.rethrowFromSystemServer();
            return null;
        }
    }

    /** callback for providing asynchronous sms messages for financial app. */
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
     * @param params the parameters to filter SMS messages returned.
     * @param executor the executor on which callback will be invoked.
     * @param callback a callback to receive CursorWindow with SMS messages.
     */
    @RequiresPermission(android.Manifest.permission.SMS_FINANCIAL_TRANSACTIONS)
    public void getSmsMessagesForFinancialApp(
            Bundle params,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull FinancialSmsCallback callback) {
        try {
            ISms iccSms = getISmsServiceOrThrow();
            iccSms.getSmsMessagesForFinancialApp(
                    getSubscriptionId(), ActivityThread.currentPackageName(), params,
                    new IFinancialSmsCallback.Stub() {
                        public void onGetSmsMessagesForFinancialApp(CursorWindow msgs) {
                            Binder.withCleanCallingIdentity(() -> executor.execute(
                                    () -> callback.onFinancialSmsMessages(msgs)));
                        }});
        } catch (RemoteException ex) {
            ex.rethrowFromSystemServer();
        }
    }

    /**
     * @see #createAppSpecificSmsTokenWithPackageInfo().
     * The prefixes is a list of prefix {@code String} separated by this delimiter.
     * @hide
     */
    public static final String REGEX_PREFIX_DELIMITER = ",";
    /**
     * @see #createAppSpecificSmsTokenWithPackageInfo().
     * The success status to be added into the intent to be sent to the calling package.
     * @hide
     */
    public static final int RESULT_STATUS_SUCCESS = 0;
    /**
     * @see #createAppSpecificSmsTokenWithPackageInfo().
     * The timeout status to be added into the intent to be sent to the calling package.
     * @hide
     */
    public static final int RESULT_STATUS_TIMEOUT = 1;
    /**
     * @see #createAppSpecificSmsTokenWithPackageInfo().
     * Intent extra key of the retrieved SMS message as a {@code String}.
     * @hide
     */
    public static final String EXTRA_SMS_MESSAGE = "android.telephony.extra.SMS_MESSAGE";
    /**
     * @see #createAppSpecificSmsTokenWithPackageInfo().
     * Intent extra key of SMS retriever status, which indicates whether the request for the
     * coming SMS message is SUCCESS or TIMEOUT
     * @hide
     */
    public static final String EXTRA_STATUS = "android.telephony.extra.STATUS";
    /**
     * @see #createAppSpecificSmsTokenWithPackageInfo().
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
                ActivityThread.currentPackageName(), prefixes, intent);

        } catch (RemoteException ex) {
            ex.rethrowFromSystemServer();
            return null;
        }
    }

    /**
     * Filters a bundle to only contain MMS config variables.
     *
     * This is for use with bundles returned by {@link CarrierConfigManager} which contain MMS
     * config and unrelated config. It is assumed that all MMS_CONFIG_* keys are present in the
     * supplied bundle.
     *
     * @param config a Bundle that contains MMS config variables and possibly more.
     * @return a new Bundle that only contains the MMS_CONFIG_* keys defined above.
     * @hide
     */
    public static Bundle getMmsConfig(BaseBundle config) {
        Bundle filtered = new Bundle();
        filtered.putBoolean(MMS_CONFIG_APPEND_TRANSACTION_ID,
                config.getBoolean(MMS_CONFIG_APPEND_TRANSACTION_ID));
        filtered.putBoolean(MMS_CONFIG_MMS_ENABLED, config.getBoolean(MMS_CONFIG_MMS_ENABLED));
        filtered.putBoolean(MMS_CONFIG_GROUP_MMS_ENABLED,
                config.getBoolean(MMS_CONFIG_GROUP_MMS_ENABLED));
        filtered.putBoolean(MMS_CONFIG_NOTIFY_WAP_MMSC_ENABLED,
                config.getBoolean(MMS_CONFIG_NOTIFY_WAP_MMSC_ENABLED));
        filtered.putBoolean(MMS_CONFIG_ALIAS_ENABLED, config.getBoolean(MMS_CONFIG_ALIAS_ENABLED));
        filtered.putBoolean(MMS_CONFIG_ALLOW_ATTACH_AUDIO,
                config.getBoolean(MMS_CONFIG_ALLOW_ATTACH_AUDIO));
        filtered.putBoolean(MMS_CONFIG_MULTIPART_SMS_ENABLED,
                config.getBoolean(MMS_CONFIG_MULTIPART_SMS_ENABLED));
        filtered.putBoolean(MMS_CONFIG_SMS_DELIVERY_REPORT_ENABLED,
                config.getBoolean(MMS_CONFIG_SMS_DELIVERY_REPORT_ENABLED));
        filtered.putBoolean(MMS_CONFIG_SUPPORT_MMS_CONTENT_DISPOSITION,
                config.getBoolean(MMS_CONFIG_SUPPORT_MMS_CONTENT_DISPOSITION));
        filtered.putBoolean(MMS_CONFIG_SEND_MULTIPART_SMS_AS_SEPARATE_MESSAGES,
                config.getBoolean(MMS_CONFIG_SEND_MULTIPART_SMS_AS_SEPARATE_MESSAGES));
        filtered.putBoolean(MMS_CONFIG_MMS_READ_REPORT_ENABLED,
                config.getBoolean(MMS_CONFIG_MMS_READ_REPORT_ENABLED));
        filtered.putBoolean(MMS_CONFIG_MMS_DELIVERY_REPORT_ENABLED,
                config.getBoolean(MMS_CONFIG_MMS_DELIVERY_REPORT_ENABLED));
        filtered.putBoolean(MMS_CONFIG_CLOSE_CONNECTION,
                config.getBoolean(MMS_CONFIG_CLOSE_CONNECTION));
        filtered.putInt(MMS_CONFIG_MAX_MESSAGE_SIZE, config.getInt(MMS_CONFIG_MAX_MESSAGE_SIZE));
        filtered.putInt(MMS_CONFIG_MAX_IMAGE_WIDTH, config.getInt(MMS_CONFIG_MAX_IMAGE_WIDTH));
        filtered.putInt(MMS_CONFIG_MAX_IMAGE_HEIGHT, config.getInt(MMS_CONFIG_MAX_IMAGE_HEIGHT));
        filtered.putInt(MMS_CONFIG_RECIPIENT_LIMIT, config.getInt(MMS_CONFIG_RECIPIENT_LIMIT));
        filtered.putInt(MMS_CONFIG_ALIAS_MIN_CHARS, config.getInt(MMS_CONFIG_ALIAS_MIN_CHARS));
        filtered.putInt(MMS_CONFIG_ALIAS_MAX_CHARS, config.getInt(MMS_CONFIG_ALIAS_MAX_CHARS));
        filtered.putInt(MMS_CONFIG_SMS_TO_MMS_TEXT_THRESHOLD,
                config.getInt(MMS_CONFIG_SMS_TO_MMS_TEXT_THRESHOLD));
        filtered.putInt(MMS_CONFIG_SMS_TO_MMS_TEXT_LENGTH_THRESHOLD,
                config.getInt(MMS_CONFIG_SMS_TO_MMS_TEXT_LENGTH_THRESHOLD));
        filtered.putInt(MMS_CONFIG_MESSAGE_TEXT_MAX_SIZE,
                config.getInt(MMS_CONFIG_MESSAGE_TEXT_MAX_SIZE));
        filtered.putInt(MMS_CONFIG_SUBJECT_MAX_LENGTH,
                config.getInt(MMS_CONFIG_SUBJECT_MAX_LENGTH));
        filtered.putInt(MMS_CONFIG_HTTP_SOCKET_TIMEOUT,
                config.getInt(MMS_CONFIG_HTTP_SOCKET_TIMEOUT));
        filtered.putString(MMS_CONFIG_UA_PROF_TAG_NAME,
                config.getString(MMS_CONFIG_UA_PROF_TAG_NAME));
        filtered.putString(MMS_CONFIG_USER_AGENT, config.getString(MMS_CONFIG_USER_AGENT));
        filtered.putString(MMS_CONFIG_UA_PROF_URL, config.getString(MMS_CONFIG_UA_PROF_URL));
        filtered.putString(MMS_CONFIG_HTTP_PARAMS, config.getString(MMS_CONFIG_HTTP_PARAMS));
        filtered.putString(MMS_CONFIG_EMAIL_GATEWAY_NUMBER,
                config.getString(MMS_CONFIG_EMAIL_GATEWAY_NUMBER));
        filtered.putString(MMS_CONFIG_NAI_SUFFIX, config.getString(MMS_CONFIG_NAI_SUFFIX));
        filtered.putBoolean(MMS_CONFIG_SHOW_CELL_BROADCAST_APP_LINKS,
                config.getBoolean(MMS_CONFIG_SHOW_CELL_BROADCAST_APP_LINKS));
        filtered.putBoolean(MMS_CONFIG_SUPPORT_HTTP_CHARSET_HEADER,
                config.getBoolean(MMS_CONFIG_SUPPORT_HTTP_CHARSET_HEADER));
        return filtered;
    }

}
