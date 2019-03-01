/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.provider;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.annotation.UnsupportedAppUsage;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.sqlite.SqliteWrapper;
import android.net.Uri;
import android.os.Build;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.SmsMessage;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Patterns;

import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.SmsApplication;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Telephony provider contains data related to phone operation, specifically SMS and MMS
 * messages, access to the APN list, including the MMSC to use, and the service state.
 *
 * <p class="note"><strong>Note:</strong> These APIs are not available on all Android-powered
 * devices. If your app depends on telephony features such as for managing SMS messages, include
 * a <a href="{@docRoot}guide/topics/manifest/uses-feature-element.html">{@code <uses-feature>}
 * </a> element in your manifest that declares the {@code "android.hardware.telephony"} hardware
 * feature. Alternatively, you can check for telephony availability at runtime using either
 * {@link android.content.pm.PackageManager#hasSystemFeature
 * hasSystemFeature(PackageManager.FEATURE_TELEPHONY)} or {@link
 * android.telephony.TelephonyManager#getPhoneType}.</p>
 *
 * <h3>Creating an SMS app</h3>
 *
 * <p>Only the default SMS app (selected by the user in system settings) is able to write to the
 * SMS Provider (the tables defined within the {@code Telephony} class) and only the default SMS
 * app receives the {@link android.provider.Telephony.Sms.Intents#SMS_DELIVER_ACTION} broadcast
 * when the user receives an SMS or the {@link
 * android.provider.Telephony.Sms.Intents#WAP_PUSH_DELIVER_ACTION} broadcast when the user
 * receives an MMS.</p>
 *
 * <p>Any app that wants to behave as the user's default SMS app must handle the following intents:
 * <ul>
 * <li>In a broadcast receiver, include an intent filter for {@link Sms.Intents#SMS_DELIVER_ACTION}
 * (<code>"android.provider.Telephony.SMS_DELIVER"</code>). The broadcast receiver must also
 * require the {@link android.Manifest.permission#BROADCAST_SMS} permission.
 * <p>This allows your app to directly receive incoming SMS messages.</p></li>
 * <li>In a broadcast receiver, include an intent filter for {@link
 * Sms.Intents#WAP_PUSH_DELIVER_ACTION}} ({@code "android.provider.Telephony.WAP_PUSH_DELIVER"})
 * with the MIME type <code>"application/vnd.wap.mms-message"</code>.
 * The broadcast receiver must also require the {@link
 * android.Manifest.permission#BROADCAST_WAP_PUSH} permission.
 * <p>This allows your app to directly receive incoming MMS messages.</p></li>
 * <li>In your activity that delivers new messages, include an intent filter for
 * {@link android.content.Intent#ACTION_SENDTO} (<code>"android.intent.action.SENDTO"
 * </code>) with schemas, <code>sms:</code>, <code>smsto:</code>, <code>mms:</code>, and
 * <code>mmsto:</code>.
 * <p>This allows your app to receive intents from other apps that want to deliver a
 * message.</p></li>
 * <li>In a service, include an intent filter for {@link
 * android.telephony.TelephonyManager#ACTION_RESPOND_VIA_MESSAGE}
 * (<code>"android.intent.action.RESPOND_VIA_MESSAGE"</code>) with schemas,
 * <code>sms:</code>, <code>smsto:</code>, <code>mms:</code>, and <code>mmsto:</code>.
 * This service must also require the {@link
 * android.Manifest.permission#SEND_RESPOND_VIA_MESSAGE} permission.
 * <p>This allows users to respond to incoming phone calls with an immediate text message
 * using your app.</p></li>
 * </ul>
 *
 * <p>Other apps that are not selected as the default SMS app can only <em>read</em> the SMS
 * Provider, but may also be notified when a new SMS arrives by listening for the {@link
 * Sms.Intents#SMS_RECEIVED_ACTION}
 * broadcast, which is a non-abortable broadcast that may be delivered to multiple apps. This
 * broadcast is intended for apps that&mdash;while not selected as the default SMS app&mdash;need to
 * read special incoming messages such as to perform phone number verification.</p>
 *
 * <p>For more information about building SMS apps, read the blog post, <a
 * href="http://android-developers.blogspot.com/2013/10/getting-your-sms-apps-ready-for-kitkat.html"
 * >Getting Your SMS Apps Ready for KitKat</a>.</p>
 *
 */
public final class Telephony {
    private static final String TAG = "Telephony";

    /**
     * Not instantiable.
     * @hide
     */
    private Telephony() {
    }

    /**
     * Base columns for tables that contain text-based SMSs.
     */
    public interface TextBasedSmsColumns {

        /** Message type: all messages. */
        public static final int MESSAGE_TYPE_ALL    = 0;

        /** Message type: inbox. */
        public static final int MESSAGE_TYPE_INBOX  = 1;

        /** Message type: sent messages. */
        public static final int MESSAGE_TYPE_SENT   = 2;

        /** Message type: drafts. */
        public static final int MESSAGE_TYPE_DRAFT  = 3;

        /** Message type: outbox. */
        public static final int MESSAGE_TYPE_OUTBOX = 4;

        /** Message type: failed outgoing message. */
        public static final int MESSAGE_TYPE_FAILED = 5;

        /** Message type: queued to send later. */
        public static final int MESSAGE_TYPE_QUEUED = 6;

        /**
         * The type of message.
         * <P>Type: INTEGER</P>
         */
        public static final String TYPE = "type";

        /**
         * The thread ID of the message.
         * <P>Type: INTEGER</P>
         */
        public static final String THREAD_ID = "thread_id";

        /**
         * The address of the other party.
         * <P>Type: TEXT</P>
         */
        public static final String ADDRESS = "address";

        /**
         * The date the message was received.
         * <P>Type: INTEGER (long)</P>
         */
        public static final String DATE = "date";

        /**
         * The date the message was sent.
         * <P>Type: INTEGER (long)</P>
         */
        public static final String DATE_SENT = "date_sent";

        /**
         * Has the message been read?
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String READ = "read";

        /**
         * Has the message been seen by the user? The "seen" flag determines
         * whether we need to show a notification.
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String SEEN = "seen";

        /**
         * {@code TP-Status} value for the message, or -1 if no status has been received.
         * <P>Type: INTEGER</P>
         */
        public static final String STATUS = "status";

        /** TP-Status: no status received. */
        public static final int STATUS_NONE = -1;
        /** TP-Status: complete. */
        public static final int STATUS_COMPLETE = 0;
        /** TP-Status: pending. */
        public static final int STATUS_PENDING = 32;
        /** TP-Status: failed. */
        public static final int STATUS_FAILED = 64;

        /**
         * The subject of the message, if present.
         * <P>Type: TEXT</P>
         */
        public static final String SUBJECT = "subject";

        /**
         * The body of the message.
         * <P>Type: TEXT</P>
         */
        public static final String BODY = "body";

        /**
         * The ID of the sender of the conversation, if present.
         * <P>Type: INTEGER (reference to item in {@code content://contacts/people})</P>
         */
        public static final String PERSON = "person";

        /**
         * The protocol identifier code.
         * <P>Type: INTEGER</P>
         */
        public static final String PROTOCOL = "protocol";

        /**
         * Is the {@code TP-Reply-Path} flag set?
         * <P>Type: BOOLEAN</P>
         */
        public static final String REPLY_PATH_PRESENT = "reply_path_present";

        /**
         * The service center (SC) through which to send the message, if present.
         * <P>Type: TEXT</P>
         */
        public static final String SERVICE_CENTER = "service_center";

        /**
         * Is the message locked?
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String LOCKED = "locked";

        /**
         * The subscription to which the message belongs to. Its value will be
         * < 0 if the sub id cannot be determined.
         * <p>Type: INTEGER (long) </p>
         */
        public static final String SUBSCRIPTION_ID = "sub_id";

        /**
         * The MTU size of the mobile interface to which the APN connected
         * @hide
         */
        public static final String MTU = "mtu";

        /**
         * Error code associated with sending or receiving this message
         * <P>Type: INTEGER</P>
         */
        public static final String ERROR_CODE = "error_code";

        /**
         * The identity of the sender of a sent message. It is
         * usually the package name of the app which sends the message.
         * <p class="note"><strong>Note:</strong>
         * This column is read-only. It is set by the provider and can not be changed by apps.
         * <p>Type: TEXT</p>
         */
        public static final String CREATOR = "creator";
    }

    /**
     * Columns in sms_changes table.
     * @hide
     */
    public interface TextBasedSmsChangesColumns {
        /**
         * The {@code content://} style URL for this table.
         * @hide
         */
        public static final Uri CONTENT_URI = Uri.parse("content://sms-changes");

        /**
         * Primary key.
         * <P>Type: INTEGER (long)</P>
         * @hide
         */
        public static final String ID = "_id";

        /**
         * Triggers on sms table create a row in this table for each update/delete.
         * This column is the "_id" of the row from sms table that was updated/deleted.
         * <P>Type: INTEGER (long)</P>
         * @hide
         */
        public static final String ORIG_ROW_ID = "orig_rowid";

        /**
         * Triggers on sms table create a row in this table for each update/delete.
         * This column is the "sub_id" of the row from sms table that was updated/deleted.
         * @hide
         * <P>Type: INTEGER (long)</P>
         */
        public static final String SUB_ID = "sub_id";

        /**
         * The type of operation that created this row.
         *    {@link #TYPE_UPDATE} = update op
         *    {@link #TYPE_DELETE} = delete op
         * @hide
         * <P>Type: INTEGER (long)</P>
         */
        public static final String TYPE = "type";

        /**
         * One of the possible values for the above column "type". Indicates it is an update op.
         * @hide
         */
        public static final int TYPE_UPDATE = 0;

        /**
         * One of the possible values for the above column "type". Indicates it is a delete op.
         * @hide
         */
        public static final int TYPE_DELETE = 1;

        /**
         * This column contains a non-null value only if the operation on sms table is an update op
         * and the column "read" is changed by the update op.
         * <P>Type: INTEGER (boolean)</P>
         * @hide
         */
        public static final String NEW_READ_STATUS = "new_read_status";
    }

    /**
     * Contains all text-based SMS messages.
     */
    public static final class Sms implements BaseColumns, TextBasedSmsColumns {

        /**
         * Not instantiable.
         * @hide
         */
        private Sms() {
        }

        /**
         * Used to determine the currently configured default SMS package.
         * @param context context of the requesting application
         * @return package name for the default SMS package or null
         */
        public static String getDefaultSmsPackage(Context context) {
            ComponentName component = SmsApplication.getDefaultSmsApplication(context, false);
            if (component != null) {
                return component.getPackageName();
            }
            return null;
        }

        /**
         * Return cursor for table query.
         * @hide
         */
        public static Cursor query(ContentResolver cr, String[] projection) {
            return cr.query(CONTENT_URI, projection, null, null, DEFAULT_SORT_ORDER);
        }

        /**
         * Return cursor for table query.
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public static Cursor query(ContentResolver cr, String[] projection,
                String where, String orderBy) {
            return cr.query(CONTENT_URI, projection, where,
                    null, orderBy == null ? DEFAULT_SORT_ORDER : orderBy);
        }

        /**
         * The {@code content://} style URL for this table.
         */
        public static final Uri CONTENT_URI = Uri.parse("content://sms");

        /**
         * The default sort order for this table.
         */
        public static final String DEFAULT_SORT_ORDER = "date DESC";

        /**
         * Add an SMS to the given URI.
         *
         * @param resolver the content resolver to use
         * @param uri the URI to add the message to
         * @param address the address of the sender
         * @param body the body of the message
         * @param subject the pseudo-subject of the message
         * @param date the timestamp for the message
         * @param read true if the message has been read, false if not
         * @param deliveryReport true if a delivery report was requested, false if not
         * @return the URI for the new message
         * @hide
         */
        @UnsupportedAppUsage
        public static Uri addMessageToUri(ContentResolver resolver,
                Uri uri, String address, String body, String subject,
                Long date, boolean read, boolean deliveryReport) {
            return addMessageToUri(SubscriptionManager.getDefaultSmsSubscriptionId(),
                    resolver, uri, address, body, subject, date, read, deliveryReport, -1L);
        }

        /**
         * Add an SMS to the given URI.
         *
         * @param resolver the content resolver to use
         * @param uri the URI to add the message to
         * @param address the address of the sender
         * @param body the body of the message
         * @param subject the psuedo-subject of the message
         * @param date the timestamp for the message
         * @param read true if the message has been read, false if not
         * @param deliveryReport true if a delivery report was requested, false if not
         * @param subId the subscription which the message belongs to
         * @return the URI for the new message
         * @hide
         */
        @UnsupportedAppUsage
        public static Uri addMessageToUri(int subId, ContentResolver resolver,
                Uri uri, String address, String body, String subject,
                Long date, boolean read, boolean deliveryReport) {
            return addMessageToUri(subId, resolver, uri, address, body, subject,
                    date, read, deliveryReport, -1L);
        }

        /**
         * Add an SMS to the given URI with the specified thread ID.
         *
         * @param resolver the content resolver to use
         * @param uri the URI to add the message to
         * @param address the address of the sender
         * @param body the body of the message
         * @param subject the pseudo-subject of the message
         * @param date the timestamp for the message
         * @param read true if the message has been read, false if not
         * @param deliveryReport true if a delivery report was requested, false if not
         * @param threadId the thread_id of the message
         * @return the URI for the new message
         * @hide
         */
        @UnsupportedAppUsage
        public static Uri addMessageToUri(ContentResolver resolver,
                Uri uri, String address, String body, String subject,
                Long date, boolean read, boolean deliveryReport, long threadId) {
            return addMessageToUri(SubscriptionManager.getDefaultSmsSubscriptionId(),
                    resolver, uri, address, body, subject,
                    date, read, deliveryReport, threadId);
        }

        /**
         * Add an SMS to the given URI with thread_id specified.
         *
         * @param resolver the content resolver to use
         * @param uri the URI to add the message to
         * @param address the address of the sender
         * @param body the body of the message
         * @param subject the psuedo-subject of the message
         * @param date the timestamp for the message
         * @param read true if the message has been read, false if not
         * @param deliveryReport true if a delivery report was requested, false if not
         * @param threadId the thread_id of the message
         * @param subId the subscription which the message belongs to
         * @return the URI for the new message
         * @hide
         */
        @UnsupportedAppUsage
        public static Uri addMessageToUri(int subId, ContentResolver resolver,
                Uri uri, String address, String body, String subject,
                Long date, boolean read, boolean deliveryReport, long threadId) {
            ContentValues values = new ContentValues(8);
            Rlog.v(TAG,"Telephony addMessageToUri sub id: " + subId);

            values.put(SUBSCRIPTION_ID, subId);
            values.put(ADDRESS, address);
            if (date != null) {
                values.put(DATE, date);
            }
            values.put(READ, read ? Integer.valueOf(1) : Integer.valueOf(0));
            values.put(SUBJECT, subject);
            values.put(BODY, body);
            if (deliveryReport) {
                values.put(STATUS, STATUS_PENDING);
            }
            if (threadId != -1L) {
                values.put(THREAD_ID, threadId);
            }
            return resolver.insert(uri, values);
        }

        /**
         * Move a message to the given folder.
         *
         * @param context the context to use
         * @param uri the message to move
         * @param folder the folder to move to
         * @return true if the operation succeeded
         * @hide
         */
        @UnsupportedAppUsage
        public static boolean moveMessageToFolder(Context context,
                Uri uri, int folder, int error) {
            if (uri == null) {
                return false;
            }

            boolean markAsUnread = false;
            boolean markAsRead = false;
            switch(folder) {
            case MESSAGE_TYPE_INBOX:
            case MESSAGE_TYPE_DRAFT:
                break;
            case MESSAGE_TYPE_OUTBOX:
            case MESSAGE_TYPE_SENT:
                markAsRead = true;
                break;
            case MESSAGE_TYPE_FAILED:
            case MESSAGE_TYPE_QUEUED:
                markAsUnread = true;
                break;
            default:
                return false;
            }

            ContentValues values = new ContentValues(3);

            values.put(TYPE, folder);
            if (markAsUnread) {
                values.put(READ, 0);
            } else if (markAsRead) {
                values.put(READ, 1);
            }
            values.put(ERROR_CODE, error);

            return 1 == SqliteWrapper.update(context, context.getContentResolver(),
                            uri, values, null, null);
        }

        /**
         * Returns true iff the folder (message type) identifies an
         * outgoing message.
         * @hide
         */
        @UnsupportedAppUsage
        public static boolean isOutgoingFolder(int messageType) {
            return  (messageType == MESSAGE_TYPE_FAILED)
                    || (messageType == MESSAGE_TYPE_OUTBOX)
                    || (messageType == MESSAGE_TYPE_SENT)
                    || (messageType == MESSAGE_TYPE_QUEUED);
        }

        /**
         * Contains all text-based SMS messages in the SMS app inbox.
         */
        public static final class Inbox implements BaseColumns, TextBasedSmsColumns {

            /**
             * Not instantiable.
             * @hide
             */
            private Inbox() {
            }

            /**
             * The {@code content://} style URL for this table.
             */
            public static final Uri CONTENT_URI = Uri.parse("content://sms/inbox");

            /**
             * The default sort order for this table.
             */
            public static final String DEFAULT_SORT_ORDER = "date DESC";

            /**
             * Add an SMS to the Draft box.
             *
             * @param resolver the content resolver to use
             * @param address the address of the sender
             * @param body the body of the message
             * @param subject the pseudo-subject of the message
             * @param date the timestamp for the message
             * @param read true if the message has been read, false if not
             * @return the URI for the new message
             * @hide
             */
            @UnsupportedAppUsage
            public static Uri addMessage(ContentResolver resolver,
                    String address, String body, String subject, Long date,
                    boolean read) {
                return addMessageToUri(SubscriptionManager.getDefaultSmsSubscriptionId(),
                        resolver, CONTENT_URI, address, body, subject, date, read, false);
            }

            /**
             * Add an SMS to the Draft box.
             *
             * @param resolver the content resolver to use
             * @param address the address of the sender
             * @param body the body of the message
             * @param subject the psuedo-subject of the message
             * @param date the timestamp for the message
             * @param read true if the message has been read, false if not
             * @param subId the subscription which the message belongs to
             * @return the URI for the new message
             * @hide
             */
            @UnsupportedAppUsage
            public static Uri addMessage(int subId, ContentResolver resolver,
                    String address, String body, String subject, Long date, boolean read) {
                return addMessageToUri(subId, resolver, CONTENT_URI, address, body,
                        subject, date, read, false);
            }
        }

        /**
         * Contains all sent text-based SMS messages in the SMS app.
         */
        public static final class Sent implements BaseColumns, TextBasedSmsColumns {

            /**
             * Not instantiable.
             * @hide
             */
            private Sent() {
            }

            /**
             * The {@code content://} style URL for this table.
             */
            public static final Uri CONTENT_URI = Uri.parse("content://sms/sent");

            /**
             * The default sort order for this table.
             */
            public static final String DEFAULT_SORT_ORDER = "date DESC";

            /**
             * Add an SMS to the Draft box.
             *
             * @param resolver the content resolver to use
             * @param address the address of the sender
             * @param body the body of the message
             * @param subject the pseudo-subject of the message
             * @param date the timestamp for the message
             * @return the URI for the new message
             * @hide
             */
            @UnsupportedAppUsage
            public static Uri addMessage(ContentResolver resolver,
                    String address, String body, String subject, Long date) {
                return addMessageToUri(SubscriptionManager.getDefaultSmsSubscriptionId(),
                        resolver, CONTENT_URI, address, body, subject, date, true, false);
            }

            /**
             * Add an SMS to the Draft box.
             *
             * @param resolver the content resolver to use
             * @param address the address of the sender
             * @param body the body of the message
             * @param subject the psuedo-subject of the message
             * @param date the timestamp for the message
             * @param subId the subscription which the message belongs to
             * @return the URI for the new message
             * @hide
             */
            @UnsupportedAppUsage
            public static Uri addMessage(int subId, ContentResolver resolver,
                    String address, String body, String subject, Long date) {
                return addMessageToUri(subId, resolver, CONTENT_URI, address, body,
                        subject, date, true, false);
            }
        }

        /**
         * Contains all sent text-based SMS messages in the SMS app.
         */
        public static final class Draft implements BaseColumns, TextBasedSmsColumns {

            /**
             * Not instantiable.
             * @hide
             */
            private Draft() {
            }

            /**
             * The {@code content://} style URL for this table.
             */
            public static final Uri CONTENT_URI = Uri.parse("content://sms/draft");

           /**
            * @hide
            */
            @UnsupportedAppUsage
            public static Uri addMessage(ContentResolver resolver,
                    String address, String body, String subject, Long date) {
                return addMessageToUri(SubscriptionManager.getDefaultSmsSubscriptionId(),
                        resolver, CONTENT_URI, address, body, subject, date, true, false);
            }

            /**
             * Add an SMS to the Draft box.
             *
             * @param resolver the content resolver to use
             * @param address the address of the sender
             * @param body the body of the message
             * @param subject the psuedo-subject of the message
             * @param date the timestamp for the message
             * @param subId the subscription which the message belongs to
             * @return the URI for the new message
             * @hide
             */
            @UnsupportedAppUsage
            public static Uri addMessage(int subId, ContentResolver resolver,
                    String address, String body, String subject, Long date) {
                return addMessageToUri(subId, resolver, CONTENT_URI, address, body,
                        subject, date, true, false);
            }

            /**
             * The default sort order for this table.
             */
            public static final String DEFAULT_SORT_ORDER = "date DESC";
        }

        /**
         * Contains all pending outgoing text-based SMS messages.
         */
        public static final class Outbox implements BaseColumns, TextBasedSmsColumns {

            /**
             * Not instantiable.
             * @hide
             */
            private Outbox() {
            }

            /**
             * The {@code content://} style URL for this table.
             */
            public static final Uri CONTENT_URI = Uri.parse("content://sms/outbox");

            /**
             * The default sort order for this table.
             */
            public static final String DEFAULT_SORT_ORDER = "date DESC";

            /**
             * Add an SMS to the outbox.
             *
             * @param resolver the content resolver to use
             * @param address the address of the sender
             * @param body the body of the message
             * @param subject the pseudo-subject of the message
             * @param date the timestamp for the message
             * @param deliveryReport whether a delivery report was requested for the message
             * @return the URI for the new message
             * @hide
             */
            @UnsupportedAppUsage
            public static Uri addMessage(ContentResolver resolver,
                    String address, String body, String subject, Long date,
                    boolean deliveryReport, long threadId) {
                return addMessageToUri(SubscriptionManager.getDefaultSmsSubscriptionId(),
                        resolver, CONTENT_URI, address, body, subject, date,
                        true, deliveryReport, threadId);
            }

            /**
             * Add an SMS to the Out box.
             *
             * @param resolver the content resolver to use
             * @param address the address of the sender
             * @param body the body of the message
             * @param subject the psuedo-subject of the message
             * @param date the timestamp for the message
             * @param deliveryReport whether a delivery report was requested for the message
             * @param subId the subscription which the message belongs to
             * @return the URI for the new message
             * @hide
             */
            public static Uri addMessage(int subId, ContentResolver resolver,
                    String address, String body, String subject, Long date,
                    boolean deliveryReport, long threadId) {
                return addMessageToUri(subId, resolver, CONTENT_URI, address, body,
                        subject, date, true, deliveryReport, threadId);
            }
        }

        /**
         * Contains all sent text-based SMS messages in the SMS app.
         */
        public static final class Conversations
                implements BaseColumns, TextBasedSmsColumns {

            /**
             * Not instantiable.
             * @hide
             */
            private Conversations() {
            }

            /**
             * The {@code content://} style URL for this table.
             */
            public static final Uri CONTENT_URI = Uri.parse("content://sms/conversations");

            /**
             * The default sort order for this table.
             */
            public static final String DEFAULT_SORT_ORDER = "date DESC";

            /**
             * The first 45 characters of the body of the message.
             * <P>Type: TEXT</P>
             */
            public static final String SNIPPET = "snippet";

            /**
             * The number of messages in the conversation.
             * <P>Type: INTEGER</P>
             */
            public static final String MESSAGE_COUNT = "msg_count";
        }

        /**
         * Contains constants for SMS related Intents that are broadcast.
         */
        public static final class Intents {

            /**
             * Not instantiable.
             * @hide
             */
            private Intents() {
            }

            /**
             * Set by BroadcastReceiver to indicate that the message was handled
             * successfully.
             */
            public static final int RESULT_SMS_HANDLED = 1;

            /**
             * Set by BroadcastReceiver to indicate a generic error while
             * processing the message.
             */
            public static final int RESULT_SMS_GENERIC_ERROR = 2;

            /**
             * Set by BroadcastReceiver to indicate insufficient memory to store
             * the message.
             */
            public static final int RESULT_SMS_OUT_OF_MEMORY = 3;

            /**
             * Set by BroadcastReceiver to indicate that the message, while
             * possibly valid, is of a format or encoding that is not
             * supported.
             */
            public static final int RESULT_SMS_UNSUPPORTED = 4;

            /**
             * Set by BroadcastReceiver to indicate a duplicate incoming message.
             */
            public static final int RESULT_SMS_DUPLICATED = 5;

            /**
             * Activity action: Ask the user to change the default
             * SMS application. This will show a dialog that asks the
             * user whether they want to replace the current default
             * SMS application with the one specified in
             * {@link #EXTRA_PACKAGE_NAME}.
             */
            @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
            public static final String ACTION_CHANGE_DEFAULT =
                    "android.provider.Telephony.ACTION_CHANGE_DEFAULT";

            /**
             * The PackageName string passed in as an
             * extra for {@link #ACTION_CHANGE_DEFAULT}
             *
             * @see #ACTION_CHANGE_DEFAULT
             */
            public static final String EXTRA_PACKAGE_NAME = "package";

            /**
             * Broadcast Action: A new text-based SMS message has been received
             * by the device. This intent will only be delivered to the default
             * sms app. That app is responsible for writing the message and notifying
             * the user. The intent will have the following extra values:</p>
             *
             * <ul>
             *   <li><em>"pdus"</em> - An Object[] of byte[]s containing the PDUs
             *   that make up the message.</li>
             *   <li><em>"format"</em> - A String describing the format of the PDUs. It can
             *   be either "3gpp" or "3gpp2".</li>
             *   <li><em>"subscription"</em> - An optional long value of the subscription id which
             *   received the message.</li>
             *   <li><em>"slot"</em> - An optional int value of the SIM slot containing the
             *   subscription.</li>
             *   <li><em>"phone"</em> - An optional int value of the phone id associated with the
             *   subscription.</li>
             *   <li><em>"errorCode"</em> - An optional int error code associated with receiving
             *   the message.</li>
             * </ul>
             *
             * <p>The extra values can be extracted using
             * {@link #getMessagesFromIntent(Intent)}.</p>
             *
             * <p>If a BroadcastReceiver encounters an error while processing
             * this intent it should set the result code appropriately.</p>
             *
             * <p class="note"><strong>Note:</strong>
             * The broadcast receiver that filters for this intent must declare
             * {@link android.Manifest.permission#BROADCAST_SMS} as a required permission in
             * the <a href="{@docRoot}guide/topics/manifest/receiver-element.html">{@code
             * <receiver>}</a> tag.
             *
             * <p>Requires {@link android.Manifest.permission#RECEIVE_SMS} to receive.</p>
             */
            @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
            public static final String SMS_DELIVER_ACTION =
                    "android.provider.Telephony.SMS_DELIVER";

            /**
             * Broadcast Action: A new text-based SMS message has been received
             * by the device. This intent will be delivered to all registered
             * receivers as a notification. These apps are not expected to write the
             * message or notify the user. The intent will have the following extra
             * values:</p>
             *
             * <ul>
             *   <li><em>"pdus"</em> - An Object[] of byte[]s containing the PDUs
             *   that make up the message.</li>
             * </ul>
             *
             * <p>The extra values can be extracted using
             * {@link #getMessagesFromIntent(Intent)}.</p>
             *
             * <p>If a BroadcastReceiver encounters an error while processing
             * this intent it should set the result code appropriately.</p>
             *
             * <p>Requires {@link android.Manifest.permission#RECEIVE_SMS} to receive.</p>
             */
            @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
            public static final String SMS_RECEIVED_ACTION =
                    "android.provider.Telephony.SMS_RECEIVED";

            /**
             * Broadcast Action: A new data based SMS message has been received
             * by the device. This intent will be delivered to all registered
             * receivers as a notification. The intent will have the following extra
             * values:</p>
             *
             * <ul>
             *   <li><em>"pdus"</em> - An Object[] of byte[]s containing the PDUs
             *   that make up the message.</li>
             * </ul>
             *
             * <p>The extra values can be extracted using
             * {@link #getMessagesFromIntent(Intent)}.</p>
             *
             * <p>If a BroadcastReceiver encounters an error while processing
             * this intent it should set the result code appropriately.</p>
             *
             * <p>Requires {@link android.Manifest.permission#RECEIVE_SMS} to receive.</p>
             */
            @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
            public static final String DATA_SMS_RECEIVED_ACTION =
                    "android.intent.action.DATA_SMS_RECEIVED";

            /**
             * Broadcast Action: A new WAP PUSH message has been received by the
             * device. This intent will only be delivered to the default
             * sms app. That app is responsible for writing the message and notifying
             * the user. The intent will have the following extra values:</p>
             *
             * <ul>
             *   <li><em>"transactionId"</em> - (Integer) The WAP transaction ID</li>
             *   <li><em>"pduType"</em> - (Integer) The WAP PDU type</li>
             *   <li><em>"header"</em> - (byte[]) The header of the message</li>
             *   <li><em>"data"</em> - (byte[]) The data payload of the message</li>
             *   <li><em>"contentTypeParameters" </em>
             *   -(HashMap&lt;String,String&gt;) Any parameters associated with the content type
             *   (decoded from the WSP Content-Type header)</li>
             *   <li><em>"subscription"</em> - An optional long value of the subscription id which
             *   received the message.</li>
             *   <li><em>"slot"</em> - An optional int value of the SIM slot containing the
             *   subscription.</li>
             *   <li><em>"phone"</em> - An optional int value of the phone id associated with the
             *   subscription.</li>
             * </ul>
             *
             * <p>If a BroadcastReceiver encounters an error while processing
             * this intent it should set the result code appropriately.</p>
             *
             * <p>The contentTypeParameters extra value is map of content parameters keyed by
             * their names.</p>
             *
             * <p>If any unassigned well-known parameters are encountered, the key of the map will
             * be 'unassigned/0x...', where '...' is the hex value of the unassigned parameter.  If
             * a parameter has No-Value the value in the map will be null.</p>
             *
             * <p>Requires {@link android.Manifest.permission#RECEIVE_MMS} or
             * {@link android.Manifest.permission#RECEIVE_WAP_PUSH} (depending on WAP PUSH type) to
             * receive.</p>
             *
             * <p class="note"><strong>Note:</strong>
             * The broadcast receiver that filters for this intent must declare
             * {@link android.Manifest.permission#BROADCAST_WAP_PUSH} as a required permission in
             * the <a href="{@docRoot}guide/topics/manifest/receiver-element.html">{@code
             * <receiver>}</a> tag.
             */
            @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
            public static final String WAP_PUSH_DELIVER_ACTION =
                    "android.provider.Telephony.WAP_PUSH_DELIVER";

            /**
             * Broadcast Action: A new WAP PUSH message has been received by the
             * device. This intent will be delivered to all registered
             * receivers as a notification. These apps are not expected to write the
             * message or notify the user. The intent will have the following extra
             * values:</p>
             *
             * <ul>
             *   <li><em>"transactionId"</em> - (Integer) The WAP transaction ID</li>
             *   <li><em>"pduType"</em> - (Integer) The WAP PDU type</li>
             *   <li><em>"header"</em> - (byte[]) The header of the message</li>
             *   <li><em>"data"</em> - (byte[]) The data payload of the message</li>
             *   <li><em>"contentTypeParameters"</em>
             *   - (HashMap&lt;String,String&gt;) Any parameters associated with the content type
             *   (decoded from the WSP Content-Type header)</li>
             * </ul>
             *
             * <p>If a BroadcastReceiver encounters an error while processing
             * this intent it should set the result code appropriately.</p>
             *
             * <p>The contentTypeParameters extra value is map of content parameters keyed by
             * their names.</p>
             *
             * <p>If any unassigned well-known parameters are encountered, the key of the map will
             * be 'unassigned/0x...', where '...' is the hex value of the unassigned parameter.  If
             * a parameter has No-Value the value in the map will be null.</p>
             *
             * <p>Requires {@link android.Manifest.permission#RECEIVE_MMS} or
             * {@link android.Manifest.permission#RECEIVE_WAP_PUSH} (depending on WAP PUSH type) to
             * receive.</p>
             */
            @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
            public static final String WAP_PUSH_RECEIVED_ACTION =
                    "android.provider.Telephony.WAP_PUSH_RECEIVED";

            /**
             * Broadcast Action: A new Cell Broadcast message has been received
             * by the device. The intent will have the following extra
             * values:</p>
             *
             * <ul>
             *   <li><em>"message"</em> - An SmsCbMessage object containing the broadcast message
             *   data. This is not an emergency alert, so ETWS and CMAS data will be null.</li>
             * </ul>
             *
             * <p>The extra values can be extracted using
             * {@link #getMessagesFromIntent(Intent)}.</p>
             *
             * <p>If a BroadcastReceiver encounters an error while processing
             * this intent it should set the result code appropriately.</p>
             *
             * <p>Requires {@link android.Manifest.permission#RECEIVE_SMS} to receive.</p>
             */
            @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
            public static final String SMS_CB_RECEIVED_ACTION =
                    "android.provider.Telephony.SMS_CB_RECEIVED";

            /**
             * Action: A SMS based carrier provision intent. Used to identify default
             * carrier provisioning app on the device.
             * @hide
             */
            @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
            @TestApi
            public static final String SMS_CARRIER_PROVISION_ACTION =
                    "android.provider.Telephony.SMS_CARRIER_PROVISION";

            /**
             * Broadcast Action: A new Emergency Broadcast message has been received
             * by the device. The intent will have the following extra
             * values:</p>
             *
             * <ul>
             *   <li><em>"message"</em> - An SmsCbMessage object containing the broadcast message
             *   data, including ETWS or CMAS warning notification info if present.</li>
             * </ul>
             *
             * <p>The extra values can be extracted using
             * {@link #getMessagesFromIntent(Intent)}.</p>
             *
             * <p>If a BroadcastReceiver encounters an error while processing
             * this intent it should set the result code appropriately.</p>
             *
             * <p>Requires {@link android.Manifest.permission#RECEIVE_EMERGENCY_BROADCAST} to
             * receive.</p>
             * @removed
             */
            @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
            public static final String SMS_EMERGENCY_CB_RECEIVED_ACTION =
                    "android.provider.Telephony.SMS_EMERGENCY_CB_RECEIVED";

            /**
             * Broadcast Action: A new CDMA SMS has been received containing Service Category
             * Program Data (updates the list of enabled broadcast channels). The intent will
             * have the following extra values:</p>
             *
             * <ul>
             *   <li><em>"operations"</em> - An array of CdmaSmsCbProgramData objects containing
             *   the service category operations (add/delete/clear) to perform.</li>
             * </ul>
             *
             * <p>The extra values can be extracted using
             * {@link #getMessagesFromIntent(Intent)}.</p>
             *
             * <p>If a BroadcastReceiver encounters an error while processing
             * this intent it should set the result code appropriately.</p>
             *
             * <p>Requires {@link android.Manifest.permission#RECEIVE_SMS} to receive.</p>
             */
            @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
            public static final String SMS_SERVICE_CATEGORY_PROGRAM_DATA_RECEIVED_ACTION =
                    "android.provider.Telephony.SMS_SERVICE_CATEGORY_PROGRAM_DATA_RECEIVED";

            /**
             * Broadcast Action: The SIM storage for SMS messages is full.  If
             * space is not freed, messages targeted for the SIM (class 2) may
             * not be saved.
             *
             * <p>Requires {@link android.Manifest.permission#RECEIVE_SMS} to receive.</p>
             */
            @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
            public static final String SIM_FULL_ACTION =
                    "android.provider.Telephony.SIM_FULL";

            /**
             * Broadcast Action: An incoming SMS has been rejected by the
             * telephony framework.  This intent is sent in lieu of any
             * of the RECEIVED_ACTION intents.  The intent will have the
             * following extra value:</p>
             *
             * <ul>
             *   <li><em>"result"</em> - An int result code, e.g. {@link #RESULT_SMS_OUT_OF_MEMORY}
             *   indicating the error returned to the network.</li>
             * </ul>
             *
             * <p>Requires {@link android.Manifest.permission#RECEIVE_SMS} to receive.</p>
             */
            @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
            public static final String SMS_REJECTED_ACTION =
                "android.provider.Telephony.SMS_REJECTED";

            /**
             * Broadcast Action: An incoming MMS has been downloaded. The intent is sent to all
             * users, except for secondary users where SMS has been disabled and to managed
             * profiles.
             * @hide
             */
            @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
            public static final String MMS_DOWNLOADED_ACTION =
                "android.provider.Telephony.MMS_DOWNLOADED";

            /**
             * Broadcast Action: A debug code has been entered in the dialer. This intent is
             * broadcast by the system and OEM telephony apps may need to receive these broadcasts.
             * These "secret codes" are used to activate developer menus by dialing certain codes.
             * And they are of the form {@code *#*#&lt;code&gt;#*#*}. The intent will have the data
             * URI: {@code android_secret_code://&lt;code&gt;}. It is possible that a manifest
             * receiver would be woken up even if it is not currently running.
             *
             * <p>Requires {@code android.Manifest.permission#CONTROL_INCALL_EXPERIENCE} to
             * send and receive.</p>
             * @deprecated it is no longer supported, use {@link
             * TelephonyManager#ACTION_SECRET_CODE} instead
             */
            @Deprecated
            @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
            public static final String SECRET_CODE_ACTION =
                    "android.provider.Telephony.SECRET_CODE";

            /**
             * Broadcast action: When the default SMS package changes,
             * the previous default SMS package and the new default SMS
             * package are sent this broadcast to notify them of the change.
             * A boolean is specified in {@link #EXTRA_IS_DEFAULT_SMS_APP} to
             * indicate whether the package is the new default SMS package.
            */
            @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
            public static final String ACTION_DEFAULT_SMS_PACKAGE_CHANGED =
                            "android.provider.action.DEFAULT_SMS_PACKAGE_CHANGED";

            /**
             * The IsDefaultSmsApp boolean passed as an
             * extra for {@link #ACTION_DEFAULT_SMS_PACKAGE_CHANGED} to indicate whether the
             * SMS app is becoming the default SMS app or is no longer the default.
             *
             * @see #ACTION_DEFAULT_SMS_PACKAGE_CHANGED
             */
            public static final String EXTRA_IS_DEFAULT_SMS_APP =
                    "android.provider.extra.IS_DEFAULT_SMS_APP";

            /**
             * Broadcast action: When a change is made to the SmsProvider or
             * MmsProvider by a process other than the default SMS application,
             * this intent is broadcast to the default SMS application so it can
             * re-sync or update the change. The uri that was used to call the provider
             * can be retrieved from the intent with getData(). The actual affected uris
             * (which would depend on the selection specified) are not included.
            */
            @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
            public static final String ACTION_EXTERNAL_PROVIDER_CHANGE =
                          "android.provider.action.EXTERNAL_PROVIDER_CHANGE";

            /**
             * Same as {@link #ACTION_DEFAULT_SMS_PACKAGE_CHANGED} but it's implicit (e.g. sent to
             * all apps) and requires
             * {@link android.Manifest.permission#MONITOR_DEFAULT_SMS_PACKAGE} to receive.
             *
             * @hide
             */
            @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
            public static final String ACTION_DEFAULT_SMS_PACKAGE_CHANGED_INTERNAL =
                    "android.provider.action.DEFAULT_SMS_PACKAGE_CHANGED_INTERNAL";

            /**
             * Broadcast action: When SMS-MMS db is being created. If file-based encryption is
             * supported, this broadcast indicates creation of the db in credential-encrypted
             * storage. A boolean is specified in {@link #EXTRA_IS_INITIAL_CREATE} to indicate if
             * this is the initial create of the db. Requires
             * {@link android.Manifest.permission#READ_SMS} to receive.
             *
             * @see #EXTRA_IS_INITIAL_CREATE
             *
             * @hide
             */
            @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
            public static final String ACTION_SMS_MMS_DB_CREATED =
                    "android.provider.action.SMS_MMS_DB_CREATED";

            /**
             * Boolean flag passed as an extra with {@link #ACTION_SMS_MMS_DB_CREATED} to indicate
             * whether the DB creation is the initial creation on the device, that is it is after a
             * factory-data reset or a new device. Any subsequent creations of the DB (which
             * happens only in error scenarios) will have this flag set to false.
             *
             * @see #ACTION_SMS_MMS_DB_CREATED
             *
             * @hide
             */
            public static final String EXTRA_IS_INITIAL_CREATE =
                    "android.provider.extra.IS_INITIAL_CREATE";

            /**
             * Broadcast intent action indicating that the telephony provider SMS MMS database is
             * corrupted. A boolean is specified in {@link #EXTRA_IS_CORRUPTED} to indicate if the
             * database is corrupted. Requires the
             * {@link android.Manifest.permission#READ_PRIVILEGED_PHONE_STATE permission.
             *
             * @hide
             */
            @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
            @RequiresPermission(android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
            public static final String ACTION_SMS_MMS_DB_LOST =
                    "android.provider.action.SMS_MMS_DB_LOST";

            /**
             * Boolean flag passed as an extra with {@link #ACTION_SMS_MMS_DB_LOST} to indicate
             * whether the DB got corrupted or not.
             *
             * @see #ACTION_SMS_MMS_DB_LOST
             *
             * @hide
             */
            public static final String EXTRA_IS_CORRUPTED =
                    "android.provider.extra.IS_CORRUPTED";

            /**
             * Read the PDUs out of an {@link #SMS_RECEIVED_ACTION} or a
             * {@link #DATA_SMS_RECEIVED_ACTION} intent.
             *
             * @param intent the intent to read from
             * @return an array of SmsMessages for the PDUs
             */
            public static SmsMessage[] getMessagesFromIntent(Intent intent) {
                Object[] messages;
                try {
                    messages = (Object[]) intent.getSerializableExtra("pdus");
                }
                catch (ClassCastException e) {
                    Rlog.e(TAG, "getMessagesFromIntent: " + e);
                    return null;
                }

                if (messages == null) {
                    Rlog.e(TAG, "pdus does not exist in the intent");
                    return null;
                }

                String format = intent.getStringExtra("format");
                int subId = intent.getIntExtra(PhoneConstants.SUBSCRIPTION_KEY,
                        SubscriptionManager.getDefaultSmsSubscriptionId());

                Rlog.v(TAG, " getMessagesFromIntent sub_id : " + subId);

                int pduCount = messages.length;
                SmsMessage[] msgs = new SmsMessage[pduCount];

                for (int i = 0; i < pduCount; i++) {
                    byte[] pdu = (byte[]) messages[i];
                    msgs[i] = SmsMessage.createFromPdu(pdu, format);
                    if (msgs[i] != null) msgs[i].setSubId(subId);
                }
                return msgs;
            }
        }
    }

    /**
     * Base column for the table that contain Carrier Public key.
     * @hide
     */
    public interface CarrierColumns extends BaseColumns {

        public static final String MCC = "mcc";
        public static final String MNC = "mnc";
        public static final String KEY_TYPE = "key_type";
        public static final String MVNO_TYPE = "mvno_type";
        public static final String MVNO_MATCH_DATA = "mvno_match_data";
        public static final String PUBLIC_KEY = "public_key";
        public static final String KEY_IDENTIFIER = "key_identifier";
        public static final String EXPIRATION_TIME = "expiration_time";
        public static final String LAST_MODIFIED = "last_modified";

        /**
         * The {@code content://} style URL for this table.
         * @hide
         */
        public static final Uri CONTENT_URI = Uri.parse("content://carrier_information/carrier");
    }

    /**
     * Base columns for tables that contain MMSs.
     */
    public interface BaseMmsColumns extends BaseColumns {

        /** Message box: all messages. */
        public static final int MESSAGE_BOX_ALL    = 0;
        /** Message box: inbox. */
        public static final int MESSAGE_BOX_INBOX  = 1;
        /** Message box: sent messages. */
        public static final int MESSAGE_BOX_SENT   = 2;
        /** Message box: drafts. */
        public static final int MESSAGE_BOX_DRAFTS = 3;
        /** Message box: outbox. */
        public static final int MESSAGE_BOX_OUTBOX = 4;
        /** Message box: failed. */
        public static final int MESSAGE_BOX_FAILED = 5;

        /**
         * The thread ID of the message.
         * <P>Type: INTEGER (long)</P>
         */
        public static final String THREAD_ID = "thread_id";

        /**
         * The date the message was received.
         * <P>Type: INTEGER (long)</P>
         */
        public static final String DATE = "date";

        /**
         * The date the message was sent.
         * <P>Type: INTEGER (long)</P>
         */
        public static final String DATE_SENT = "date_sent";

        /**
         * The box which the message belongs to, e.g. {@link #MESSAGE_BOX_INBOX}.
         * <P>Type: INTEGER</P>
         */
        public static final String MESSAGE_BOX = "msg_box";

        /**
         * Has the message been read?
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String READ = "read";

        /**
         * Has the message been seen by the user? The "seen" flag determines
         * whether we need to show a new message notification.
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String SEEN = "seen";

        /**
         * Does the message have only a text part (can also have a subject) with
         * no picture, slideshow, sound, etc. parts?
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String TEXT_ONLY = "text_only";

        /**
         * The {@code Message-ID} of the message.
         * <P>Type: TEXT</P>
         */
        public static final String MESSAGE_ID = "m_id";

        /**
         * The subject of the message, if present.
         * <P>Type: TEXT</P>
         */
        public static final String SUBJECT = "sub";

        /**
         * The character set of the subject, if present.
         * <P>Type: INTEGER</P>
         */
        public static final String SUBJECT_CHARSET = "sub_cs";

        /**
         * The {@code Content-Type} of the message.
         * <P>Type: TEXT</P>
         */
        public static final String CONTENT_TYPE = "ct_t";

        /**
         * The {@code Content-Location} of the message.
         * <P>Type: TEXT</P>
         */
        public static final String CONTENT_LOCATION = "ct_l";

        /**
         * The expiry time of the message.
         * <P>Type: INTEGER (long)</P>
         */
        public static final String EXPIRY = "exp";

        /**
         * The class of the message.
         * <P>Type: TEXT</P>
         */
        public static final String MESSAGE_CLASS = "m_cls";

        /**
         * The type of the message defined by MMS spec.
         * <P>Type: INTEGER</P>
         */
        public static final String MESSAGE_TYPE = "m_type";

        /**
         * The version of the specification that this message conforms to.
         * <P>Type: INTEGER</P>
         */
        public static final String MMS_VERSION = "v";

        /**
         * The size of the message.
         * <P>Type: INTEGER</P>
         */
        public static final String MESSAGE_SIZE = "m_size";

        /**
         * The priority of the message.
         * <P>Type: INTEGER</P>
         */
        public static final String PRIORITY = "pri";

        /**
         * The {@code read-report} of the message.
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String READ_REPORT = "rr";

        /**
         * Is read report allowed?
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String REPORT_ALLOWED = "rpt_a";

        /**
         * The {@code response-status} of the message.
         * <P>Type: INTEGER</P>
         */
        public static final String RESPONSE_STATUS = "resp_st";

        /**
         * The {@code status} of the message.
         * <P>Type: INTEGER</P>
         */
        public static final String STATUS = "st";

        /**
         * The {@code transaction-id} of the message.
         * <P>Type: TEXT</P>
         */
        public static final String TRANSACTION_ID = "tr_id";

        /**
         * The {@code retrieve-status} of the message.
         * <P>Type: INTEGER</P>
         */
        public static final String RETRIEVE_STATUS = "retr_st";

        /**
         * The {@code retrieve-text} of the message.
         * <P>Type: TEXT</P>
         */
        public static final String RETRIEVE_TEXT = "retr_txt";

        /**
         * The character set of the retrieve-text.
         * <P>Type: INTEGER</P>
         */
        public static final String RETRIEVE_TEXT_CHARSET = "retr_txt_cs";

        /**
         * The {@code read-status} of the message.
         * <P>Type: INTEGER</P>
         */
        public static final String READ_STATUS = "read_status";

        /**
         * The {@code content-class} of the message.
         * <P>Type: INTEGER</P>
         */
        public static final String CONTENT_CLASS = "ct_cls";

        /**
         * The {@code delivery-report} of the message.
         * <P>Type: INTEGER</P>
         */
        public static final String DELIVERY_REPORT = "d_rpt";

        /**
         * The {@code delivery-time-token} of the message.
         * <P>Type: INTEGER</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String DELIVERY_TIME_TOKEN = "d_tm_tok";

        /**
         * The {@code delivery-time} of the message.
         * <P>Type: INTEGER</P>
         */
        public static final String DELIVERY_TIME = "d_tm";

        /**
         * The {@code response-text} of the message.
         * <P>Type: TEXT</P>
         */
        public static final String RESPONSE_TEXT = "resp_txt";

        /**
         * The {@code sender-visibility} of the message.
         * <P>Type: TEXT</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String SENDER_VISIBILITY = "s_vis";

        /**
         * The {@code reply-charging} of the message.
         * <P>Type: INTEGER</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String REPLY_CHARGING = "r_chg";

        /**
         * The {@code reply-charging-deadline-token} of the message.
         * <P>Type: INTEGER</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String REPLY_CHARGING_DEADLINE_TOKEN = "r_chg_dl_tok";

        /**
         * The {@code reply-charging-deadline} of the message.
         * <P>Type: INTEGER</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String REPLY_CHARGING_DEADLINE = "r_chg_dl";

        /**
         * The {@code reply-charging-id} of the message.
         * <P>Type: TEXT</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String REPLY_CHARGING_ID = "r_chg_id";

        /**
         * The {@code reply-charging-size} of the message.
         * <P>Type: INTEGER</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String REPLY_CHARGING_SIZE = "r_chg_sz";

        /**
         * The {@code previously-sent-by} of the message.
         * <P>Type: TEXT</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String PREVIOUSLY_SENT_BY = "p_s_by";

        /**
         * The {@code previously-sent-date} of the message.
         * <P>Type: INTEGER</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String PREVIOUSLY_SENT_DATE = "p_s_d";

        /**
         * The {@code store} of the message.
         * <P>Type: TEXT</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String STORE = "store";

        /**
         * The {@code mm-state} of the message.
         * <P>Type: INTEGER</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String MM_STATE = "mm_st";

        /**
         * The {@code mm-flags-token} of the message.
         * <P>Type: INTEGER</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String MM_FLAGS_TOKEN = "mm_flg_tok";

        /**
         * The {@code mm-flags} of the message.
         * <P>Type: TEXT</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String MM_FLAGS = "mm_flg";

        /**
         * The {@code store-status} of the message.
         * <P>Type: TEXT</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String STORE_STATUS = "store_st";

        /**
         * The {@code store-status-text} of the message.
         * <P>Type: TEXT</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String STORE_STATUS_TEXT = "store_st_txt";

        /**
         * The {@code stored} of the message.
         * <P>Type: TEXT</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String STORED = "stored";

        /**
         * The {@code totals} of the message.
         * <P>Type: TEXT</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String TOTALS = "totals";

        /**
         * The {@code mbox-totals} of the message.
         * <P>Type: TEXT</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String MBOX_TOTALS = "mb_t";

        /**
         * The {@code mbox-totals-token} of the message.
         * <P>Type: INTEGER</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String MBOX_TOTALS_TOKEN = "mb_t_tok";

        /**
         * The {@code quotas} of the message.
         * <P>Type: TEXT</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String QUOTAS = "qt";

        /**
         * The {@code mbox-quotas} of the message.
         * <P>Type: TEXT</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String MBOX_QUOTAS = "mb_qt";

        /**
         * The {@code mbox-quotas-token} of the message.
         * <P>Type: INTEGER</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String MBOX_QUOTAS_TOKEN = "mb_qt_tok";

        /**
         * The {@code message-count} of the message.
         * <P>Type: INTEGER</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String MESSAGE_COUNT = "m_cnt";

        /**
         * The {@code start} of the message.
         * <P>Type: INTEGER</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String START = "start";

        /**
         * The {@code distribution-indicator} of the message.
         * <P>Type: TEXT</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String DISTRIBUTION_INDICATOR = "d_ind";

        /**
         * The {@code element-descriptor} of the message.
         * <P>Type: TEXT</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String ELEMENT_DESCRIPTOR = "e_des";

        /**
         * The {@code limit} of the message.
         * <P>Type: INTEGER</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String LIMIT = "limit";

        /**
         * The {@code recommended-retrieval-mode} of the message.
         * <P>Type: INTEGER</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String RECOMMENDED_RETRIEVAL_MODE = "r_r_mod";

        /**
         * The {@code recommended-retrieval-mode-text} of the message.
         * <P>Type: TEXT</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String RECOMMENDED_RETRIEVAL_MODE_TEXT = "r_r_mod_txt";

        /**
         * The {@code status-text} of the message.
         * <P>Type: TEXT</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String STATUS_TEXT = "st_txt";

        /**
         * The {@code applic-id} of the message.
         * <P>Type: TEXT</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String APPLIC_ID = "apl_id";

        /**
         * The {@code reply-applic-id} of the message.
         * <P>Type: TEXT</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String REPLY_APPLIC_ID = "r_apl_id";

        /**
         * The {@code aux-applic-id} of the message.
         * <P>Type: TEXT</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String AUX_APPLIC_ID = "aux_apl_id";

        /**
         * The {@code drm-content} of the message.
         * <P>Type: TEXT</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String DRM_CONTENT = "drm_c";

        /**
         * The {@code adaptation-allowed} of the message.
         * <P>Type: TEXT</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String ADAPTATION_ALLOWED = "adp_a";

        /**
         * The {@code replace-id} of the message.
         * <P>Type: TEXT</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String REPLACE_ID = "repl_id";

        /**
         * The {@code cancel-id} of the message.
         * <P>Type: TEXT</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String CANCEL_ID = "cl_id";

        /**
         * The {@code cancel-status} of the message.
         * <P>Type: INTEGER</P>
         * @deprecated this column is no longer supported.
         * @hide
         */
        @Deprecated
        public static final String CANCEL_STATUS = "cl_st";

        /**
         * Is the message locked?
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String LOCKED = "locked";

        /**
         * The subscription to which the message belongs to. Its value will be
         * < 0 if the sub id cannot be determined.
         * <p>Type: INTEGER (long)</p>
         */
        public static final String SUBSCRIPTION_ID = "sub_id";

        /**
         * The identity of the sender of a sent message. It is
         * usually the package name of the app which sends the message.
         * <p class="note"><strong>Note:</strong>
         * This column is read-only. It is set by the provider and can not be changed by apps.
         * <p>Type: TEXT</p>
         */
        public static final String CREATOR = "creator";
    }

    /**
     * Columns for the "canonical_addresses" table used by MMS and SMS.
     */
    public interface CanonicalAddressesColumns extends BaseColumns {
        /**
         * An address used in MMS or SMS.  Email addresses are
         * converted to lower case and are compared by string
         * equality.  Other addresses are compared using
         * PHONE_NUMBERS_EQUAL.
         * <P>Type: TEXT</P>
         */
        public static final String ADDRESS = "address";
    }

    /**
     * Columns for the "threads" table used by MMS and SMS.
     */
    public interface ThreadsColumns extends BaseColumns {

        /**
         * The date at which the thread was created.
         * <P>Type: INTEGER (long)</P>
         */
        public static final String DATE = "date";

        /**
         * A string encoding of the recipient IDs of the recipients of
         * the message, in numerical order and separated by spaces.
         * <P>Type: TEXT</P>
         */
        public static final String RECIPIENT_IDS = "recipient_ids";

        /**
         * The message count of the thread.
         * <P>Type: INTEGER</P>
         */
        public static final String MESSAGE_COUNT = "message_count";

        /**
         * Indicates whether all messages of the thread have been read.
         * <P>Type: INTEGER</P>
         */
        public static final String READ = "read";

        /**
         * The snippet of the latest message in the thread.
         * <P>Type: TEXT</P>
         */
        public static final String SNIPPET = "snippet";

        /**
         * The charset of the snippet.
         * <P>Type: INTEGER</P>
         */
        public static final String SNIPPET_CHARSET = "snippet_cs";

        /**
         * Type of the thread, either {@link Threads#COMMON_THREAD} or
         * {@link Threads#BROADCAST_THREAD}.
         * <P>Type: INTEGER</P>
         */
        public static final String TYPE = "type";

        /**
         * Indicates whether there is a transmission error in the thread.
         * <P>Type: INTEGER</P>
         */
        public static final String ERROR = "error";

        /**
         * Indicates whether this thread contains any attachments.
         * <P>Type: INTEGER</P>
         */
        public static final String HAS_ATTACHMENT = "has_attachment";

        /**
         * If the thread is archived
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String ARCHIVED = "archived";
    }

    /**
     * Helper functions for the "threads" table used by MMS and SMS.
     *
     * Thread IDs are determined by the participants in a conversation and can be used to match
     * both SMS and MMS messages.
     *
     * To avoid issues where applications might cache a thread ID, the thread ID of a deleted thread
     * must not be reused to point at a new thread.
     */
    public static final class Threads implements ThreadsColumns {

        @UnsupportedAppUsage
        private static final String[] ID_PROJECTION = { BaseColumns._ID };

        /**
         * Private {@code content://} style URL for this table. Used by
         * {@link #getOrCreateThreadId(android.content.Context, java.util.Set)}.
         */
        @UnsupportedAppUsage
        private static final Uri THREAD_ID_CONTENT_URI = Uri.parse(
                "content://mms-sms/threadID");

        /**
         * The {@code content://} style URL for this table, by conversation.
         */
        public static final Uri CONTENT_URI = Uri.withAppendedPath(
                MmsSms.CONTENT_URI, "conversations");

        /**
         * The {@code content://} style URL for this table, for obsolete threads.
         */
        public static final Uri OBSOLETE_THREADS_URI = Uri.withAppendedPath(
                CONTENT_URI, "obsolete");

        /** Thread type: common thread. */
        public static final int COMMON_THREAD    = 0;

        /** Thread type: broadcast thread. */
        public static final int BROADCAST_THREAD = 1;

        /**
         * Not instantiable.
         * @hide
         */
        private Threads() {
        }

        /**
         * This is a single-recipient version of {@code getOrCreateThreadId}.
         * It's convenient for use with SMS messages.
         * @param context the context object to use.
         * @param recipient the recipient to send to.
         */
        public static long getOrCreateThreadId(Context context, String recipient) {
            Set<String> recipients = new HashSet<String>();

            recipients.add(recipient);
            return getOrCreateThreadId(context, recipients);
        }

        /**
         * Given a set of recipients return its thread ID.
         * <p>
         * If a thread exists containing the provided participants, return its thread ID. Otherwise,
         * this will create a new thread containing the provided participants and return its ID.
         */
        public static long getOrCreateThreadId(
                Context context, Set<String> recipients) {
            Uri.Builder uriBuilder = THREAD_ID_CONTENT_URI.buildUpon();

            for (String recipient : recipients) {
                if (Mms.isEmailAddress(recipient)) {
                    recipient = Mms.extractAddrSpec(recipient);
                }

                uriBuilder.appendQueryParameter("recipient", recipient);
            }

            Uri uri = uriBuilder.build();
            //if (DEBUG) Rlog.v(TAG, "getOrCreateThreadId uri: " + uri);

            Cursor cursor = SqliteWrapper.query(context, context.getContentResolver(),
                    uri, ID_PROJECTION, null, null, null);
            if (cursor != null) {
                try {
                    if (cursor.moveToFirst()) {
                        return cursor.getLong(0);
                    } else {
                        Rlog.e(TAG, "getOrCreateThreadId returned no rows!");
                    }
                } finally {
                    cursor.close();
                }
            }

            Rlog.e(TAG, "getOrCreateThreadId failed with " + recipients.size() + " recipients");
            throw new IllegalArgumentException("Unable to find or allocate a thread ID.");
        }
    }

    /**
     * Columns for the "rcs_*" tables used by {@link android.telephony.ims.RcsMessageStore} classes.
     *
     * @hide - not meant for public use
     */
    public interface RcsColumns {
        // TODO(sahinc): Turn this to true once the schema finalizes, so that people can update
        //  their messaging databases. NOTE: move the switch/case update in MmsSmsDatabaseHelper to
        //  the latest version of the database before turning this flag to true.
        boolean IS_RCS_TABLE_SCHEMA_CODE_COMPLETE = false;

        /**
         * The authority for the content provider
         */
        String AUTHORITY = "rcs";

        /**
         * The URI to start building upon to use {@link com.android.providers.telephony.RcsProvider}
         */
        Uri CONTENT_AND_AUTHORITY = Uri.parse("content://" + AUTHORITY);

        /**
         * The value to be used whenever a transaction that expects an integer to be returned
         * failed.
         */
        int TRANSACTION_FAILED = Integer.MIN_VALUE;

        /**
         * The value that denotes a timestamp was not set before (e.g. a message that is not
         * delivered yet will not have a DELIVERED_TIMESTAMP)
         */
        long TIMESTAMP_NOT_SET = 0;

        /**
         * The table that {@link android.telephony.ims.RcsThread} gets persisted to
         */
        interface RcsThreadColumns {
            /**
             * The path that should be used for referring to
             * {@link android.telephony.ims.RcsThread}s in
             * {@link com.android.providers.telephony.RcsProvider} URIs.
             */
            String RCS_THREAD_URI_PART = "thread";

            /**
             * The URI to query or modify {@link android.telephony.ims.RcsThread} via the content
             * provider.
             */
            Uri RCS_THREAD_URI = Uri.withAppendedPath(CONTENT_AND_AUTHORITY, RCS_THREAD_URI_PART);

            /**
             * The unique identifier of an {@link android.telephony.ims.RcsThread}
             */
            String RCS_THREAD_ID_COLUMN = "rcs_thread_id";
        }

        /**
         * The table that {@link android.telephony.ims.Rcs1To1Thread} gets persisted to
         */
        interface Rcs1To1ThreadColumns extends RcsThreadColumns {
            /**
             * The path that should be used for referring to
             * {@link android.telephony.ims.Rcs1To1Thread}s in
             * {@link com.android.providers.telephony.RcsProvider} URIs.
             */
            String RCS_1_TO_1_THREAD_URI_PART = "p2p_thread";

            /**
             * The URI to query or modify {@link android.telephony.ims.Rcs1To1Thread}s via the
             * content provider. Can also insert to this URI to create a new 1-to-1 thread. When
             * performing an insert, ensure that the provided content values contain the other
             * participant's ID under the key
             * {@link RcsParticipantColumns.RCS_PARTICIPANT_ID_COLUMN}
             */
            Uri RCS_1_TO_1_THREAD_URI = Uri.withAppendedPath(CONTENT_AND_AUTHORITY,
                    RCS_1_TO_1_THREAD_URI_PART);

            /**
             * The SMS/MMS thread to fallback to in case of an RCS outage
             */
            String FALLBACK_THREAD_ID_COLUMN = "rcs_fallback_thread_id";
        }

        /**
         * The table that {@link android.telephony.ims.RcsGroupThread} gets persisted to
         */
        interface RcsGroupThreadColumns extends RcsThreadColumns {
            /**
             * The path that should be used for referring to
             * {@link android.telephony.ims.RcsGroupThread}s in
             * {@link com.android.providers.telephony.RcsProvider} URIs.
             */
            String RCS_GROUP_THREAD_URI_PART = "group_thread";

            /**
             * The URI to query or modify {@link android.telephony.ims.RcsGroupThread}s via the
             * content provider
             */
            Uri RCS_GROUP_THREAD_URI = Uri.withAppendedPath(CONTENT_AND_AUTHORITY,
                    RCS_GROUP_THREAD_URI_PART);

            /**
             * The owner/admin of the {@link android.telephony.ims.RcsGroupThread}
             */
            String OWNER_PARTICIPANT_COLUMN = "owner_participant";

            /**
             * The user visible name of the group
             */
            String GROUP_NAME_COLUMN = "group_name";

            /**
             * The user visible icon of the group
             */
            String GROUP_ICON_COLUMN = "group_icon";

            /**
             * The RCS conference URI for this group
             */
            String CONFERENCE_URI_COLUMN = "conference_uri";
        }

        /**
         * The view that enables polling from all types of RCS threads at once
         */
        interface RcsUnifiedThreadColumns extends RcsThreadColumns, Rcs1To1ThreadColumns,
                RcsGroupThreadColumns {
            /**
             * The type of this {@link android.telephony.ims.RcsThread}
             */
            String THREAD_TYPE_COLUMN = "thread_type";

            /**
             * Integer returned as a result from a database query that denotes the thread is 1 to 1
             */
            int THREAD_TYPE_1_TO_1 = 0;

            /**
             * Integer returned as a result from a database query that denotes the thread is 1 to 1
             */
            int THREAD_TYPE_GROUP = 1;
        }

        /**
         * The table that {@link android.telephony.ims.RcsParticipant} gets persisted to
         */
        interface RcsParticipantColumns {
            /**
             * The path that should be used for referring to
             * {@link android.telephony.ims.RcsParticipant}s in
             * {@link com.android.providers.telephony.RcsProvider} URIs.
             */
            String RCS_PARTICIPANT_URI_PART = "participant";

            /**
             * The URI to query or modify {@link android.telephony.ims.RcsParticipant}s via the
             * content provider
             */
            Uri RCS_PARTICIPANT_URI = Uri.withAppendedPath(CONTENT_AND_AUTHORITY,
                    RCS_PARTICIPANT_URI_PART);

            /**
             * The unique identifier of the entry in the database
             */
            String RCS_PARTICIPANT_ID_COLUMN = "rcs_participant_id";

            /**
             * A foreign key on canonical_address table, also used by SMS/MMS
             */
            String CANONICAL_ADDRESS_ID_COLUMN = "canonical_address_id";

            /**
             * The user visible RCS alias for this participant.
             */
            String RCS_ALIAS_COLUMN = "rcs_alias";
        }

        /**
         * Additional constants to enable access to {@link android.telephony.ims.RcsParticipant}
         * related data
         */
        interface RcsParticipantHelpers extends RcsParticipantColumns {
            /**
             * The view that unifies "rcs_participant" and "canonical_addresses" tables for easy
             * access to participant address.
             */
            String RCS_PARTICIPANT_WITH_ADDRESS_VIEW = "rcs_participant_with_address_view";

            /**
             * The view that unifies "rcs_participant", "canonical_addresses" and
             * "rcs_thread_participant" junction table to get full information on participants that
             * contribute to threads.
             */
            String RCS_PARTICIPANT_WITH_THREAD_VIEW = "rcs_participant_with_thread_view";
        }

        /**
         * The table that {@link android.telephony.ims.RcsMessage} gets persisted to
         */
        interface RcsMessageColumns {
            /**
             * Denotes the type of this message (i.e.
             * {@link android.telephony.ims.RcsIncomingMessage} or
             * {@link android.telephony.ims.RcsOutgoingMessage}
             */
            String MESSAGE_TYPE_COLUMN = "rcs_message_type";

            /**
             * The unique identifier for the message in the database - i.e. the primary key.
             */
            String MESSAGE_ID_COLUMN = "rcs_message_row_id";

            /**
             * The globally unique RCS identifier for the message. Please see 4.4.5.2 - GSMA
             * RCC.53 (RCS Device API 1.6 Specification)
             */
            String GLOBAL_ID_COLUMN = "rcs_message_global_id";

            /**
             * The subscription where this message was sent from/to.
             */
            String SUB_ID_COLUMN = "sub_id";

            /**
             * The sending status of the message.
             * @see android.telephony.ims.RcsMessage.RcsMessageStatus
             */
            String STATUS_COLUMN = "status";

            /**
             * The creation timestamp of the message.
             */
            String ORIGINATION_TIMESTAMP_COLUMN = "origination_timestamp";

            /**
             * The text content of the message.
             */
            String MESSAGE_TEXT_COLUMN = "rcs_text";

            /**
             * The latitude content of the message, if it contains a location.
             */
            String LATITUDE_COLUMN = "latitude";

            /**
             * The longitude content of the message, if it contains a location.
             */
            String LONGITUDE_COLUMN = "longitude";
        }

        /**
         * The table that additional information of {@link android.telephony.ims.RcsIncomingMessage}
         * gets persisted to.
         */
        interface RcsIncomingMessageColumns extends RcsMessageColumns {
            /**
             The path that should be used for referring to
             * {@link android.telephony.ims.RcsIncomingMessage}s in
             * {@link com.android.providers.telephony.RcsProvider} URIs.
             */
            String INCOMING_MESSAGE_URI_PART = "incoming_message";

            /**
             * The URI to query incoming messages through
             * {@link com.android.providers.telephony.RcsProvider}
             */
            Uri INCOMING_MESSAGE_URI = Uri.withAppendedPath(CONTENT_AND_AUTHORITY,
                    INCOMING_MESSAGE_URI_PART);

            /**
             * The ID of the {@link android.telephony.ims.RcsParticipant} that sent this message
             */
            String SENDER_PARTICIPANT_ID_COLUMN = "sender_participant";

            /**
             * The timestamp of arrival for this message.
             */
            String ARRIVAL_TIMESTAMP_COLUMN = "arrival_timestamp";

            /**
             * The time when the recipient has read this message.
             */
            String SEEN_TIMESTAMP_COLUMN = "seen_timestamp";
        }

        /**
         * The table that additional information of {@link android.telephony.ims.RcsOutgoingMessage}
         * gets persisted to.
         */
        interface RcsOutgoingMessageColumns extends RcsMessageColumns {
            /**
             * The path that should be used for referring to
             * {@link android.telephony.ims.RcsOutgoingMessage}s in
             * {@link com.android.providers.telephony.RcsProvider} URIs.
             */
            String OUTGOING_MESSAGE_URI_PART = "outgoing_message";

            /**
             * The URI to query or modify {@link android.telephony.ims.RcsOutgoingMessage}s via the
             * content provider
             */
            Uri OUTGOING_MESSAGE_URI = Uri.withAppendedPath(CONTENT_AND_AUTHORITY,
                    OUTGOING_MESSAGE_URI_PART);
        }

        /**
         * The delivery information of an {@link android.telephony.ims.RcsOutgoingMessage}
         */
        interface RcsMessageDeliveryColumns extends RcsOutgoingMessageColumns {
            /**
             * The path that should be used for referring to
             * {@link android.telephony.ims.RcsOutgoingMessageDelivery}s in
             * {@link com.android.providers.telephony.RcsProvider} URIs.
             */
            String DELIVERY_URI_PART = "delivery";

            /**
             * The timestamp of delivery of this message.
             */
            String DELIVERED_TIMESTAMP_COLUMN = "delivered_timestamp";

            /**
             * The time when the recipient has read this message.
             */
            String SEEN_TIMESTAMP_COLUMN = "seen_timestamp";
        }

        /**
         * The views that allow querying {@link android.telephony.ims.RcsIncomingMessage} and
         * {@link android.telephony.ims.RcsOutgoingMessage} at the same time.
         */
        interface RcsUnifiedMessageColumns extends RcsIncomingMessageColumns,
                RcsOutgoingMessageColumns {
            /**
             * The path that is used to query all {@link android.telephony.ims.RcsMessage} in
             * {@link com.android.providers.telephony.RcsProvider} URIs.
             */
            String UNIFIED_MESSAGE_URI_PART = "message";

            /**
             * The URI to query all types of {@link android.telephony.ims.RcsMessage}s
             */
            Uri UNIFIED_MESSAGE_URI = Uri.withAppendedPath(CONTENT_AND_AUTHORITY,
                    UNIFIED_MESSAGE_URI_PART);

            /**
             * The name of the view that unites rcs_message and rcs_incoming_message tables.
             */
            String UNIFIED_INCOMING_MESSAGE_VIEW = "unified_incoming_message_view";

            /**
             * The name of the view that unites rcs_message and rcs_outgoing_message tables.
             */
            String UNIFIED_OUTGOING_MESSAGE_VIEW = "unified_outgoing_message_view";

            /**
             * The column that shows from which table the message entry came from.
             */
            String MESSAGE_TYPE_COLUMN = "message_type";

            /**
             * Integer returned as a result from a database query that denotes that the message is
             * an incoming message
             */
            int MESSAGE_TYPE_INCOMING = 1;

            /**
             * Integer returned as a result from a database query that denotes that the message is
             * an outgoing message
             */
            int MESSAGE_TYPE_OUTGOING = 0;
        }

        /**
         * The table that {@link android.telephony.ims.RcsFileTransferPart} gets persisted to.
         */
        interface RcsFileTransferColumns {
            /**
             * The path that should be used for referring to
             * {@link android.telephony.ims.RcsFileTransferPart}s in
             * {@link com.android.providers.telephony.RcsProvider} URIs.
             */
            String FILE_TRANSFER_URI_PART = "file_transfer";

            /**
             * The URI to query or modify {@link android.telephony.ims.RcsFileTransferPart}s via the
             * content provider
             */
            Uri FILE_TRANSFER_URI = Uri.withAppendedPath(CONTENT_AND_AUTHORITY,
                    FILE_TRANSFER_URI_PART);

            /**
             * The globally unique file transfer ID for this RCS file transfer.
             */
            String FILE_TRANSFER_ID_COLUMN = "rcs_file_transfer_id";

            /**
             * The RCS session ID for this file transfer. The ID is implementation dependent but
             * should be unique.
             */
            String SESSION_ID_COLUMN = "session_id";

            /**
             * The URI that points to the content of this file transfer
             */
            String CONTENT_URI_COLUMN = "content_uri";

            /**
             * The file type of this file transfer in bytes. The validity of types is not enforced
             * in {@link android.telephony.ims.RcsMessageStore} APIs.
             */
            String CONTENT_TYPE_COLUMN = "content_type";

            /**
             * The size of the file transfer in bytes.
             */
            String FILE_SIZE_COLUMN = "file_size";

            /**
             * Number of bytes that was successfully transmitted for this file transfer
             */
            String SUCCESSFULLY_TRANSFERRED_BYTES = "transfer_offset";

            /**
             * The status of this file transfer
             * @see android.telephony.ims.RcsFileTransferPart.RcsFileTransferStatus
             */
            String TRANSFER_STATUS_COLUMN = "transfer_status";

            /**
             * The on-screen width of the file transfer, if it contains multi-media
             */
            String WIDTH_COLUMN = "width";

            /**
             * The on-screen height of the file transfer, if it contains multi-media
             */
            String HEIGHT_COLUMN = "height";

            /**
             * The duration of the content in milliseconds if this file transfer contains
             * multi-media
             */
            String DURATION_MILLIS_COLUMN = "duration";

            /**
             * The URI to the preview of the content of this file transfer
             */
            String PREVIEW_URI_COLUMN = "preview_uri";

            /**
             * The type of the preview of the content of this file transfer. The validity of types
             * is not enforced in {@link android.telephony.ims.RcsMessageStore} APIs.
             */
            String PREVIEW_TYPE_COLUMN = "preview_type";
        }

        /**
         * The table that holds the information for
         * {@link android.telephony.ims.RcsGroupThreadEvent} and its subclasses.
         */
        interface RcsThreadEventColumns {
            /**
             * The string used in the {@link com.android.providers.telephony.RcsProvider} URI to
             * refer to participant joined events (example URI:
             * {@code content://rcs/group_thread/3/participant_joined_event})
             */
            String PARTICIPANT_JOINED_URI_PART = "participant_joined_event";

            /**
             * The string used in the {@link com.android.providers.telephony.RcsProvider} URI to
             * refer to participant left events. (example URI:
             * {@code content://rcs/group_thread/3/participant_left_event/4})
             */
            String PARTICIPANT_LEFT_URI_PART = "participant_left_event";

            /**
             * The string used in the {@link com.android.providers.telephony.RcsProvider} URI to
             * refer to name changed events. (example URI:
             * {@code content://rcs/group_thread/3/name_changed_event})
             */
            String NAME_CHANGED_URI_PART = "name_changed_event";

            /**
             * The string used in the {@link com.android.providers.telephony.RcsProvider} URI to
             * refer to icon changed events. (example URI:
             * {@code content://rcs/group_thread/3/icon_changed_event})
             */
            String ICON_CHANGED_URI_PART = "icon_changed_event";

            /**
             * The unique ID of this event in the database, i.e. the primary key
             */
            String EVENT_ID_COLUMN = "event_id";

            /**
             * The type of this event
             *
             * @see RcsEventTypes
             */
            String EVENT_TYPE_COLUMN = "event_type";

            /**
             * The timestamp in milliseconds of when this event happened
             */
            String TIMESTAMP_COLUMN = "origination_timestamp";

            /**
             * The participant that generated this event
             */
            String SOURCE_PARTICIPANT_ID_COLUMN = "source_participant";

            /**
             * The receiving participant of this event if this was an
             * {@link android.telephony.ims.RcsGroupThreadParticipantJoinedEvent} or
             * {@link android.telephony.ims.RcsGroupThreadParticipantLeftEvent}
             */
            String DESTINATION_PARTICIPANT_ID_COLUMN = "destination_participant";

            /**
             * The URI for the new icon of the group thread if this was an
             * {@link android.telephony.ims.RcsGroupThreadIconChangedEvent}
             */
            String NEW_ICON_URI_COLUMN = "new_icon_uri";

            /**
             * The URI for the new name of the group thread if this was an
             * {@link android.telephony.ims.RcsGroupThreadNameChangedEvent}
             */
            String NEW_NAME_COLUMN = "new_name";
        }

        /**
         * The table that {@link android.telephony.ims.RcsParticipantAliasChangedEvent} gets
         * persisted to
         */
        interface RcsParticipantEventColumns {
            /**
             * The path that should be used for referring to
             * {@link android.telephony.ims.RcsParticipantAliasChangedEvent}s in
             * {@link com.android.providers.telephony.RcsProvider} URIs.
             */
            String ALIAS_CHANGE_EVENT_URI_PART = "alias_change_event";

            /**
             * The new alias of the participant
             */
            String NEW_ALIAS_COLUMN = "new_alias";
        }

        /**
         * These values are used in {@link com.android.providers.telephony.RcsProvider} to determine
         * what kind of event is present in the storage.
         */
        interface RcsEventTypes {
            /**
             * Integer constant that is stored in the
             * {@link com.android.providers.telephony.RcsProvider} database that denotes the event
             * is of type {@link android.telephony.ims.RcsParticipantAliasChangedEvent}
             */
            int PARTICIPANT_ALIAS_CHANGED_EVENT_TYPE = 1;

            /**
             * Integer constant that is stored in the
             * {@link com.android.providers.telephony.RcsProvider} database that denotes the event
             * is of type {@link android.telephony.ims.RcsGroupThreadParticipantJoinedEvent}
             */
            int PARTICIPANT_JOINED_EVENT_TYPE = 2;

            /**
             * Integer constant that is stored in the
             * {@link com.android.providers.telephony.RcsProvider} database that denotes the event
             * is of type {@link android.telephony.ims.RcsGroupThreadParticipantLeftEvent}
             */
            int PARTICIPANT_LEFT_EVENT_TYPE = 4;

            /**
             * Integer constant that is stored in the
             * {@link com.android.providers.telephony.RcsProvider} database that denotes the event
             * is of type {@link android.telephony.ims.RcsGroupThreadIconChangedEvent}
             */
            int ICON_CHANGED_EVENT_TYPE = 8;

            /**
             * Integer constant that is stored in the
             * {@link com.android.providers.telephony.RcsProvider} database that denotes the event
             * is of type {@link android.telephony.ims.RcsGroupThreadNameChangedEvent}
             */
            int NAME_CHANGED_EVENT_TYPE = 16;
        }

        /**
         * The view that allows unified querying across all events
         */
        interface RcsUnifiedEventHelper extends RcsParticipantEventColumns, RcsThreadEventColumns {
            /**
             * The path that should be used for referring to
             * {@link android.telephony.ims.RcsEvent}s in
             * {@link com.android.providers.telephony.RcsProvider} URIs.
             */
            String RCS_EVENT_QUERY_URI_PATH = "event";

            /**
             * The URI to query {@link android.telephony.ims.RcsEvent}s via the content provider.
             */
            Uri RCS_EVENT_QUERY_URI = Uri.withAppendedPath(CONTENT_AND_AUTHORITY,
                    RCS_EVENT_QUERY_URI_PATH);
        }

        /**
         * Allows RCS specific canonical address handling.
         */
        interface RcsCanonicalAddressHelper {
            /**
             * Returns the canonical address ID for a canonical address, if now row exists, this
             * will add a row and return its ID. This helper works against the same table used by
             * the SMS and MMS threads, but is accessible only by the phone process for use by RCS
             * message storage.
             *
             * @throws IllegalArgumentException if unable to retrieve or create the canonical
             *                                  address entry.
             */
            static long getOrCreateCanonicalAddressId(
                    ContentResolver contentResolver, String canonicalAddress) {

                Uri.Builder uriBuilder = CONTENT_AND_AUTHORITY.buildUpon();
                uriBuilder.appendPath("canonical-address");
                uriBuilder.appendQueryParameter("address", canonicalAddress);
                Uri uri = uriBuilder.build();

                try (Cursor cursor = contentResolver.query(uri, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        return cursor.getLong(cursor.getColumnIndex(CanonicalAddressesColumns._ID));
                    } else {
                        Rlog.e(TAG, "getOrCreateCanonicalAddressId returned no rows");
                    }
                }

                Rlog.e(TAG, "getOrCreateCanonicalAddressId failed");
                throw new IllegalArgumentException(
                        "Unable to find or allocate a canonical address ID");
            }
        }
    }

    /**
     * Contains all MMS messages.
     */
    public static final class Mms implements BaseMmsColumns {

        /**
         * Not instantiable.
         * @hide
         */
        private Mms() {
        }

        /**
         * The {@code content://} URI for this table.
         */
        public static final Uri CONTENT_URI = Uri.parse("content://mms");

        /**
         * Content URI for getting MMS report requests.
         */
        public static final Uri REPORT_REQUEST_URI = Uri.withAppendedPath(
                                            CONTENT_URI, "report-request");

        /**
         * Content URI for getting MMS report status.
         */
        public static final Uri REPORT_STATUS_URI = Uri.withAppendedPath(
                                            CONTENT_URI, "report-status");

        /**
         * The default sort order for this table.
         */
        public static final String DEFAULT_SORT_ORDER = "date DESC";

        /**
         * Regex pattern for names and email addresses.
         * <ul>
         *     <li><em>mailbox</em> = {@code name-addr}</li>
         *     <li><em>name-addr</em> = {@code [display-name] angle-addr}</li>
         *     <li><em>angle-addr</em> = {@code [CFWS] "<" addr-spec ">" [CFWS]}</li>
         * </ul>
         * @hide
         */
        @UnsupportedAppUsage
        public static final Pattern NAME_ADDR_EMAIL_PATTERN =
                Pattern.compile("\\s*(\"[^\"]*\"|[^<>\"]+)\\s*<([^<>]+)>\\s*");

        /**
         * Helper method to query this table.
         * @hide
         */
        public static Cursor query(
                ContentResolver cr, String[] projection) {
            return cr.query(CONTENT_URI, projection, null, null, DEFAULT_SORT_ORDER);
        }

        /**
         * Helper method to query this table.
         * @hide
         */
        public static Cursor query(
                ContentResolver cr, String[] projection,
                String where, String orderBy) {
            return cr.query(CONTENT_URI, projection,
                    where, null, orderBy == null ? DEFAULT_SORT_ORDER : orderBy);
        }

        /**
         * Helper method to extract email address from address string.
         * @hide
         */
        @UnsupportedAppUsage
        public static String extractAddrSpec(String address) {
            Matcher match = NAME_ADDR_EMAIL_PATTERN.matcher(address);

            if (match.matches()) {
                return match.group(2);
            }
            return address;
        }

        /**
         * Is the specified address an email address?
         *
         * @param address the input address to test
         * @return true if address is an email address; false otherwise.
         * @hide
         */
        @UnsupportedAppUsage
        public static boolean isEmailAddress(String address) {
            if (TextUtils.isEmpty(address)) {
                return false;
            }

            String s = extractAddrSpec(address);
            Matcher match = Patterns.EMAIL_ADDRESS.matcher(s);
            return match.matches();
        }

        /**
         * Is the specified number a phone number?
         *
         * @param number the input number to test
         * @return true if number is a phone number; false otherwise.
         * @hide
         */
        @UnsupportedAppUsage
        public static boolean isPhoneNumber(String number) {
            if (TextUtils.isEmpty(number)) {
                return false;
            }

            Matcher match = Patterns.PHONE.matcher(number);
            return match.matches();
        }

        /**
         * Contains all MMS messages in the MMS app inbox.
         */
        public static final class Inbox implements BaseMmsColumns {

            /**
             * Not instantiable.
             * @hide
             */
            private Inbox() {
            }

            /**
             * The {@code content://} style URL for this table.
             */
            public static final Uri
                    CONTENT_URI = Uri.parse("content://mms/inbox");

            /**
             * The default sort order for this table.
             */
            public static final String DEFAULT_SORT_ORDER = "date DESC";
        }

        /**
         * Contains all MMS messages in the MMS app sent folder.
         */
        public static final class Sent implements BaseMmsColumns {

            /**
             * Not instantiable.
             * @hide
             */
            private Sent() {
            }

            /**
             * The {@code content://} style URL for this table.
             */
            public static final Uri
                    CONTENT_URI = Uri.parse("content://mms/sent");

            /**
             * The default sort order for this table.
             */
            public static final String DEFAULT_SORT_ORDER = "date DESC";
        }

        /**
         * Contains all MMS messages in the MMS app drafts folder.
         */
        public static final class Draft implements BaseMmsColumns {

            /**
             * Not instantiable.
             * @hide
             */
            private Draft() {
            }

            /**
             * The {@code content://} style URL for this table.
             */
            public static final Uri
                    CONTENT_URI = Uri.parse("content://mms/drafts");

            /**
             * The default sort order for this table.
             */
            public static final String DEFAULT_SORT_ORDER = "date DESC";
        }

        /**
         * Contains all MMS messages in the MMS app outbox.
         */
        public static final class Outbox implements BaseMmsColumns {

            /**
             * Not instantiable.
             * @hide
             */
            private Outbox() {
            }

            /**
             * The {@code content://} style URL for this table.
             */
            public static final Uri
                    CONTENT_URI = Uri.parse("content://mms/outbox");

            /**
             * The default sort order for this table.
             */
            public static final String DEFAULT_SORT_ORDER = "date DESC";
        }

        /**
         * Contains address information for an MMS message.
         */
        public static final class Addr implements BaseColumns {

            /**
             * Not instantiable.
             * @hide
             */
            private Addr() {
            }

            /**
             * The ID of MM which this address entry belongs to.
             * <P>Type: INTEGER (long)</P>
             */
            public static final String MSG_ID = "msg_id";

            /**
             * The ID of contact entry in Phone Book.
             * <P>Type: INTEGER (long)</P>
             */
            public static final String CONTACT_ID = "contact_id";

            /**
             * The address text.
             * <P>Type: TEXT</P>
             */
            public static final String ADDRESS = "address";

            /**
             * Type of address: must be one of {@code PduHeaders.BCC},
             * {@code PduHeaders.CC}, {@code PduHeaders.FROM}, {@code PduHeaders.TO}.
             * <P>Type: INTEGER</P>
             */
            public static final String TYPE = "type";

            /**
             * Character set of this entry (MMS charset value).
             * <P>Type: INTEGER</P>
             */
            public static final String CHARSET = "charset";
        }

        /**
         * Contains message parts.
         *
         * To avoid issues where applications might cache a part ID, the ID of a deleted part must
         * not be reused to point at a new part.
         */
        public static final class Part implements BaseColumns {

            /**
             * Not instantiable.
             * @hide
             */
            private Part() {
            }

            /**
             * The {@code content://} style URL for this table. Can be appended with a part ID to
             * address individual parts.
             */
            public static final Uri CONTENT_URI = Uri.withAppendedPath(Mms.CONTENT_URI, "part");

            /**
             * The identifier of the message which this part belongs to.
             * <P>Type: INTEGER</P>
             */
            public static final String MSG_ID = "mid";

            /**
             * The order of the part.
             * <P>Type: INTEGER</P>
             */
            public static final String SEQ = "seq";

            /**
             * The content type of the part.
             * <P>Type: TEXT</P>
             */
            public static final String CONTENT_TYPE = "ct";

            /**
             * The name of the part.
             * <P>Type: TEXT</P>
             */
            public static final String NAME = "name";

            /**
             * The charset of the part.
             * <P>Type: TEXT</P>
             */
            public static final String CHARSET = "chset";

            /**
             * The file name of the part.
             * <P>Type: TEXT</P>
             */
            public static final String FILENAME = "fn";

            /**
             * The content disposition of the part.
             * <P>Type: TEXT</P>
             */
            public static final String CONTENT_DISPOSITION = "cd";

            /**
             * The content ID of the part.
             * <P>Type: INTEGER</P>
             */
            public static final String CONTENT_ID = "cid";

            /**
             * The content location of the part.
             * <P>Type: INTEGER</P>
             */
            public static final String CONTENT_LOCATION = "cl";

            /**
             * The start of content-type of the message.
             * <P>Type: INTEGER</P>
             */
            public static final String CT_START = "ctt_s";

            /**
             * The type of content-type of the message.
             * <P>Type: TEXT</P>
             */
            public static final String CT_TYPE = "ctt_t";

            /**
             * The location (on filesystem) of the binary data of the part.
             * <P>Type: INTEGER</P>
             */
            public static final String _DATA = "_data";

            /**
             * The message text.
             * <P>Type: TEXT</P>
             */
            public static final String TEXT = "text";
        }

        /**
         * Message send rate table.
         */
        public static final class Rate {

            /**
             * Not instantiable.
             * @hide
             */
            private Rate() {
            }

            /**
             * The {@code content://} style URL for this table.
             */
            public static final Uri CONTENT_URI = Uri.withAppendedPath(
                    Mms.CONTENT_URI, "rate");

            /**
             * When a message was successfully sent.
             * <P>Type: INTEGER (long)</P>
             */
            public static final String SENT_TIME = "sent_time";
        }

        /**
         * Intents class.
         */
        public static final class Intents {

            /**
             * Not instantiable.
             * @hide
             */
            private Intents() {
            }

            /**
             * Indicates that the contents of specified URIs were changed.
             * The application which is showing or caching these contents
             * should be updated.
             */
            @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
            public static final String CONTENT_CHANGED_ACTION
                    = "android.intent.action.CONTENT_CHANGED";

            /**
             * An extra field which stores the URI of deleted contents.
             */
            public static final String DELETED_CONTENTS = "deleted_contents";
        }
    }

    /**
     * Contains all MMS and SMS messages.
     */
    public static final class MmsSms implements BaseColumns {

        /**
         * Not instantiable.
         * @hide
         */
        private MmsSms() {
        }

        /**
         * The column to distinguish SMS and MMS messages in query results.
         */
        public static final String TYPE_DISCRIMINATOR_COLUMN =
                "transport_type";

        /**
         * The {@code content://} style URL for this table.
         */
        public static final Uri CONTENT_URI = Uri.parse("content://mms-sms/");

        /**
         * The {@code content://} style URL for this table, by conversation.
         */
        public static final Uri CONTENT_CONVERSATIONS_URI = Uri.parse(
                "content://mms-sms/conversations");

        /**
         * The {@code content://} style URL for this table, by phone number.
         */
        public static final Uri CONTENT_FILTER_BYPHONE_URI = Uri.parse(
                "content://mms-sms/messages/byphone");

        /**
         * The {@code content://} style URL for undelivered messages in this table.
         */
        public static final Uri CONTENT_UNDELIVERED_URI = Uri.parse(
                "content://mms-sms/undelivered");

        /**
         * The {@code content://} style URL for draft messages in this table.
         */
        public static final Uri CONTENT_DRAFT_URI = Uri.parse(
                "content://mms-sms/draft");

        /**
         * The {@code content://} style URL for locked messages in this table.
         */
        public static final Uri CONTENT_LOCKED_URI = Uri.parse(
                "content://mms-sms/locked");

        /**
         * Pass in a query parameter called "pattern" which is the text to search for.
         * The sort order is fixed to be: {@code thread_id ASC, date DESC}.
         */
        public static final Uri SEARCH_URI = Uri.parse(
                "content://mms-sms/search");

        // Constants for message protocol types.

        /** SMS protocol type. */
        public static final int SMS_PROTO = 0;

        /** MMS protocol type. */
        public static final int MMS_PROTO = 1;

        // Constants for error types of pending messages.

        /** Error type: no error. */
        public static final int NO_ERROR                      = 0;

        /** Error type: generic transient error. */
        public static final int ERR_TYPE_GENERIC              = 1;

        /** Error type: SMS protocol transient error. */
        public static final int ERR_TYPE_SMS_PROTO_TRANSIENT  = 2;

        /** Error type: MMS protocol transient error. */
        public static final int ERR_TYPE_MMS_PROTO_TRANSIENT  = 3;

        /** Error type: transport failure. */
        public static final int ERR_TYPE_TRANSPORT_FAILURE    = 4;

        /** Error type: permanent error (along with all higher error values). */
        public static final int ERR_TYPE_GENERIC_PERMANENT    = 10;

        /** Error type: SMS protocol permanent error. */
        public static final int ERR_TYPE_SMS_PROTO_PERMANENT  = 11;

        /** Error type: MMS protocol permanent error. */
        public static final int ERR_TYPE_MMS_PROTO_PERMANENT  = 12;

        /**
         * Contains pending messages info.
         */
        public static final class PendingMessages implements BaseColumns {

            /**
             * Not instantiable.
             * @hide
             */
            private PendingMessages() {
            }

            public static final Uri CONTENT_URI = Uri.withAppendedPath(
                    MmsSms.CONTENT_URI, "pending");

            /**
             * The type of transport protocol (MMS or SMS).
             * <P>Type: INTEGER</P>
             */
            public static final String PROTO_TYPE = "proto_type";

            /**
             * The ID of the message to be sent or downloaded.
             * <P>Type: INTEGER (long)</P>
             */
            public static final String MSG_ID = "msg_id";

            /**
             * The type of the message to be sent or downloaded.
             * This field is only valid for MM. For SM, its value is always set to 0.
             * <P>Type: INTEGER</P>
             */
            public static final String MSG_TYPE = "msg_type";

            /**
             * The type of the error code.
             * <P>Type: INTEGER</P>
             */
            public static final String ERROR_TYPE = "err_type";

            /**
             * The error code of sending/retrieving process.
             * <P>Type: INTEGER</P>
             */
            public static final String ERROR_CODE = "err_code";

            /**
             * How many times we tried to send or download the message.
             * <P>Type: INTEGER</P>
             */
            public static final String RETRY_INDEX = "retry_index";

            /**
             * The time to do next retry.
             * <P>Type: INTEGER (long)</P>
             */
            public static final String DUE_TIME = "due_time";

            /**
             * The time we last tried to send or download the message.
             * <P>Type: INTEGER (long)</P>
             */
            public static final String LAST_TRY = "last_try";

            /**
             * The subscription to which the message belongs to. Its value will be
             * < 0 if the sub id cannot be determined.
             * <p>Type: INTEGER (long) </p>
             */
            public static final String SUBSCRIPTION_ID = "pending_sub_id";
        }

        /**
         * Words table used by provider for full-text searches.
         * @hide
         */
        public static final class WordsTable {

            /**
             * Not instantiable.
             * @hide
             */
            private WordsTable() {}

            /**
             * Primary key.
             * <P>Type: INTEGER (long)</P>
             */
            public static final String ID = "_id";

            /**
             * Source row ID.
             * <P>Type: INTEGER (long)</P>
             */
            public static final String SOURCE_ROW_ID = "source_id";

            /**
             * Table ID (either 1 or 2).
             * <P>Type: INTEGER</P>
             */
            public static final String TABLE_ID = "table_to_use";

            /**
             * The words to index.
             * <P>Type: TEXT</P>
             */
            public static final String INDEXED_TEXT = "index_text";
        }
    }

    /**
     * Carriers class contains information about APNs, including MMSC information.
     */
    public static final class Carriers implements BaseColumns {

        /**
         * Not instantiable.
         * @hide
         */
        private Carriers() {}

        /**
         * The {@code content://} style URL for this table.
         * For MSIM, this will return APNs for the default subscription
         * {@link SubscriptionManager#getDefaultSubscriptionId()}. To specify subId for MSIM,
         * use {@link Uri#withAppendedPath(Uri, String)} to append with subscription id.
         */
        public static final Uri CONTENT_URI = Uri.parse("content://telephony/carriers");

        /**
         * The {@code content://} style URL for this table. Used for APN query based on current
         * subscription. Instead of specifying carrier matching information in the selection,
         * this API will return all matching APNs from current subscription carrier and queries
         * will be applied on top of that. If there is no match for MVNO (Mobile Virtual Network
         * Operator) APNs, return APNs from its MNO (based on mccmnc) instead. For MSIM, this will
         * return APNs for the default subscription
         * {@link SubscriptionManager#getDefaultSubscriptionId()}. To specify subId for MSIM,
         * use {@link Uri#withAppendedPath(Uri, String)} to append with subscription id.
         */
        public static final Uri SIM_APN_URI = Uri.parse(
                "content://telephony/carriers/sim_apn_list");

        /**
         * The {@code content://} style URL to be called from DevicePolicyManagerService,
         * can manage DPC-owned APNs.
         * @hide
         */
        public static final Uri DPC_URI = Uri.parse("content://telephony/carriers/dpc");

        /**
         * The {@code content://} style URL to be called from Telephony to query APNs.
         * When DPC-owned APNs are enforced, only DPC-owned APNs are returned, otherwise only
         * non-DPC-owned APNs are returned. For MSIM, this will return APNs for the default
         * subscription {@link SubscriptionManager#getDefaultSubscriptionId()}. To specify subId
         * for MSIM, use {@link Uri#withAppendedPath(Uri, String)} to append with subscription id.
         * @hide
         */
        public static final Uri FILTERED_URI = Uri.parse("content://telephony/carriers/filtered");

        /**
         * The {@code content://} style URL to be called from DevicePolicyManagerService
         * or Telephony to manage whether DPC-owned APNs are enforced.
         * @hide
         */
        public static final Uri ENFORCE_MANAGED_URI = Uri.parse(
                "content://telephony/carriers/enforce_managed");

        /**
         * The column name for ENFORCE_MANAGED_URI, indicates whether DPC-owned APNs are enforced.
         * @hide
         */
        public static final String ENFORCE_KEY = "enforced";

        /**
         * The default sort order for this table.
         */
        public static final String DEFAULT_SORT_ORDER = "name ASC";

        /**
         * Entry name.
         * <P>Type: TEXT</P>
         */
        public static final String NAME = "name";

        /**
         * APN name.
         * <P>Type: TEXT</P>
         */
        public static final String APN = "apn";

        /**
         * Proxy address.
         * <P>Type: TEXT</P>
         */
        public static final String PROXY = "proxy";

        /**
         * Proxy port.
         * <P>Type: TEXT</P>
         */
        public static final String PORT = "port";

        /**
         * MMS proxy address.
         * <P>Type: TEXT</P>
         */
        public static final String MMSPROXY = "mmsproxy";

        /**
         * MMS proxy port.
         * <P>Type: TEXT</P>
         */
        public static final String MMSPORT = "mmsport";

        /**
         * Server address.
         * <P>Type: TEXT</P>
         */
        public static final String SERVER = "server";

        /**
         * APN username.
         * <P>Type: TEXT</P>
         */
        public static final String USER = "user";

        /**
         * APN password.
         * <P>Type: TEXT</P>
         */
        public static final String PASSWORD = "password";

        /**
         * MMSC URL.
         * <P>Type: TEXT</P>
         */
        public static final String MMSC = "mmsc";

        /**
         * Mobile Country Code (MCC).
         * <P>Type: TEXT</P>
         * @deprecated Use {@link #SIM_APN_URI} to query APN instead, this API will return
         * matching APNs based on current subscription carrier, thus no need to specify MCC and
         * other carrier matching information. In the future, Android will not support MCC for
         * APN query.
         */
        public static final String MCC = "mcc";

        /**
         * Mobile Network Code (MNC).
         * <P>Type: TEXT</P>
         * @deprecated Use {@link #SIM_APN_URI} to query APN instead, this API will return
         * matching APNs based on current subscription carrier, thus no need to specify MNC and
         * other carrier matching information. In the future, Android will not support MNC for
         * APN query.
         */
        public static final String MNC = "mnc";

        /**
         * Numeric operator ID (as String). Usually {@code MCC + MNC}.
         * <P>Type: TEXT</P>
         * @deprecated Use {@link #SIM_APN_URI} to query APN instead, this API will return
         * matching APNs based on current subscription carrier, thus no need to specify Numeric
         * and other carrier matching information. In the future, Android will not support Numeric
         * for APN query.
         */
        public static final String NUMERIC = "numeric";

        /**
         * Authentication type.
         * <P>Type:  INTEGER</P>
         */
        public static final String AUTH_TYPE = "authtype";

        /**
         * Comma-delimited list of APN types.
         * <P>Type: TEXT</P>
         */
        public static final String TYPE = "type";

        /**
         * The protocol to use to connect to this APN.
         *
         * One of the {@code PDP_type} values in TS 27.007 section 10.1.1.
         * For example: {@code IP}, {@code IPV6}, {@code IPV4V6}, or {@code PPP}.
         * <P>Type: TEXT</P>
         */
        public static final String PROTOCOL = "protocol";

        /**
         * The protocol to use to connect to this APN when roaming.
         * The syntax is the same as protocol.
         * <P>Type: TEXT</P>
         */
        public static final String ROAMING_PROTOCOL = "roaming_protocol";

        /**
         * Is this the current APN?
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String CURRENT = "current";

        /**
         * Is this APN enabled?
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String CARRIER_ENABLED = "carrier_enabled";

        /**
         * Radio Access Technology info.
         * To check what values are allowed, refer to {@link android.telephony.ServiceState}.
         * This should be spread to other technologies,
         * but is currently only used for LTE (14) and eHRPD (13).
         * <P>Type: INTEGER</P>
         * @deprecated this column is no longer supported, use {@link #NETWORK_TYPE_BITMASK} instead
         */
        @Deprecated
        public static final String BEARER = "bearer";

        /**
         * Radio Access Technology bitmask.
         * To check what values can be contained, refer to {@link android.telephony.ServiceState}.
         * 0 indicates all techs otherwise first bit refers to RAT/bearer 1, second bit refers to
         * RAT/bearer 2 and so on.
         * Bitmask for a radio tech R is (1 << (R - 1))
         * <P>Type: INTEGER</P>
         * @hide
         * @deprecated this column is no longer supported, use {@link #NETWORK_TYPE_BITMASK} instead
         */
        @Deprecated
        public static final String BEARER_BITMASK = "bearer_bitmask";

        /**
         * Radio technology (network type) bitmask.
         * To check what values can be contained, refer to the NETWORK_TYPE_ constants in
         * {@link android.telephony.TelephonyManager}.
         * Bitmask for a radio tech R is (1 << (R - 1))
         * <P>Type: INTEGER</P>
         */
        public static final String NETWORK_TYPE_BITMASK = "network_type_bitmask";

        /**
         * MVNO type:
         * {@code SPN (Service Provider Name), IMSI, GID (Group Identifier Level 1)}.
         * <P>Type: TEXT</P>
         * @deprecated Use {@link #SIM_APN_URI} to query APN instead, this API will return
         * matching APNs based on current subscription carrier, thus no need to specify MVNO_TYPE
         * and other carrier matching information. In the future, Android will not support MVNO_TYPE
         * for APN query.
         */
        public static final String MVNO_TYPE = "mvno_type";

        /**
         * MVNO data.
         * Use the following examples.
         * <ul>
         *     <li>SPN: A MOBILE, BEN NL, ...</li>
         *     <li>IMSI: 302720x94, 2060188, ...</li>
         *     <li>GID: 4E, 33, ...</li>
         * </ul>
         * <P>Type: TEXT</P>
         * @deprecated Use {@link #SIM_APN_URI} to query APN instead, this API will return
         * matching APNs based on current subscription carrier, thus no need to specify
         * MVNO_MATCH_DATA and other carrier matching information. In the future, Android will not
         * support MVNO_MATCH_DATA for APN query.
         */
        public static final String MVNO_MATCH_DATA = "mvno_match_data";

        /**
         * The subscription to which the APN belongs to
         * <p>Type: INTEGER (long) </p>
         */
        public static final String SUBSCRIPTION_ID = "sub_id";

        /**
         * The profile_id to which the APN saved in modem.
         * <p>Type: INTEGER</p>
         *@hide
         */
        public static final String PROFILE_ID = "profile_id";

        /**
         * If set to {@code true}, then the APN setting will persist to the modem.
         * <p>Type: INTEGER (boolean)</p>
         *@hide
         */
        @SystemApi
        public static final String MODEM_PERSIST = "modem_cognitive";

        /**
         * The max number of connections of this APN.
         * <p>Type: INTEGER</p>
         *@hide
         */
        @SystemApi
        public static final String MAX_CONNECTIONS = "max_conns";

        /**
         * The wait time for retrying the APN, in milliseconds.
         * <p>Type: INTEGER</p>
         *@hide
         */
        @SystemApi
        public static final String WAIT_TIME_RETRY = "wait_time";

        /**
         * The max number of seconds this APN will support its maximum number of connections
         * as defined in {@link #MAX_CONNECTIONS}.
         * <p>Type: INTEGER</p>
         *@hide
         */
        @SystemApi
        public static final String TIME_LIMIT_FOR_MAX_CONNECTIONS = "max_conns_time";

        /**
         * The MTU (maximum transmit unit) size of the mobile interface to which the APN is
         * connected, in bytes.
         * <p>Type: INTEGER </p>
         * @hide
         */
        @SystemApi
        public static final String MTU = "mtu";

        /**
         * APN edit status. APN could be added/edited/deleted by a user or carrier.
         * see all possible returned APN edit status.
         * <ul>
         *     <li>{@link #UNEDITED}</li>
         *     <li>{@link #USER_EDITED}</li>
         *     <li>{@link #USER_DELETED}</li>
         *     <li>{@link #CARRIER_EDITED}</li>
         *     <li>{@link #CARRIER_DELETED}</li>
         * </ul>
         * <p>Type: INTEGER </p>
         * @hide
         */
        @SystemApi
        public static final String EDITED_STATUS = "edited";

        /**
         * {@code true} if this APN visible to the user, {@code false} otherwise.
         * <p>Type: INTEGER (boolean)</p>
         * @hide
         */
        @SystemApi
        public static final String USER_VISIBLE = "user_visible";

        /**
         * {@code true} if the user allowed to edit this APN, {@code false} otherwise.
         * <p>Type: INTEGER (boolean)</p>
         * @hide
         */
        @SystemApi
        public static final String USER_EDITABLE = "user_editable";

        /**
         * {@link #EDITED_STATUS APN edit status} indicates that this APN has not been edited or
         * fails to edit.
         * <p>Type: INTEGER </p>
         * @hide
         */
        @SystemApi
        public static final @EditStatus int UNEDITED = 0;

        /**
         * {@link #EDITED_STATUS APN edit status} indicates that this APN has been edited by users.
         * <p>Type: INTEGER </p>
         * @hide
         */
        @SystemApi
        public static final @EditStatus int USER_EDITED = 1;

        /**
         * {@link #EDITED_STATUS APN edit status} indicates that this APN has been deleted by users.
         * <p>Type: INTEGER </p>
         * @hide
         */
        @SystemApi
        public static final @EditStatus int USER_DELETED = 2;

        /**
         * {@link #EDITED_STATUS APN edit status} is an intermediate value used to indicate that an
         * entry deleted by the user is still present in the new APN database and therefore must
         * remain tagged as user deleted rather than completely removed from the database.
         * @hide
         */
        public static final int USER_DELETED_BUT_PRESENT_IN_XML = 3;

        /**
         * {@link #EDITED_STATUS APN edit status} indicates that this APN has been edited by
         * carriers.
         * <p>Type: INTEGER </p>
         * @hide
         */
        @SystemApi
        public static final @EditStatus int CARRIER_EDITED = 4;

        /**
         * {@link #EDITED_STATUS APN edit status} indicates that this APN has been deleted by
         * carriers. CARRIER_DELETED values are currently not used as there is no use case.
         * If they are used, delete() will have to change accordingly. Currently it is hardcoded to
         * USER_DELETED.
         * <p>Type: INTEGER </p>
         * @hide
         */
        public static final @EditStatus int CARRIER_DELETED = 5;

        /**
         * {@link #EDITED_STATUS APN edit status} is an intermediate value used to indicate that an
         * entry deleted by the carrier is still present in the new APN database and therefore must
         * remain tagged as user deleted rather than completely removed from the database.
         * @hide
         */
        public static final int CARRIER_DELETED_BUT_PRESENT_IN_XML = 6;

        /**
         * The owner of the APN.
         * <p>Type: INTEGER</p>
         * @hide
         */
        public static final String OWNED_BY = "owned_by";

        /**
         * Possible value for the OWNED_BY field.
         * APN is owned by DPC.
         * @hide
         */
        public static final int OWNED_BY_DPC = 0;

        /**
         * Possible value for the OWNED_BY field.
         * APN is owned by other sources.
         * @hide
         */
        public static final int OWNED_BY_OTHERS = 1;

        /**
         * The APN set id. When the user manually selects an APN or the framework sets an APN as
         * preferred, all APNs with the same set id as the selected APN should be prioritized over
         * APNs in other sets.
         * <p>Type: INTEGER</p>
         * @hide
         */
        @SystemApi
        public static final String APN_SET_ID = "apn_set_id";

        /**
         * Possible value for the {@link #APN_SET_ID} field. By default APNs will not belong to a
         * set. If the user manually selects an APN without apn set id, there is no need to
         * prioritize any specific APN set ids.
         * <p>Type: INTEGER</p>
         * @hide
         */
        @SystemApi
        public static final int NO_APN_SET_ID = 0;

        /**
         * A unique carrier id associated with this APN
         * {@see TelephonyManager#getSimCarrierId()}
         * <p>Type: STRING</p>
         */
        public static final String CARRIER_ID = "carrier_id";

        /** @hide */
        @IntDef({
                UNEDITED,
                USER_EDITED,
                USER_DELETED,
                CARRIER_DELETED,
                CARRIER_EDITED,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface EditStatus {}
    }

    /**
     * Contains received SMS cell broadcast messages.
     * @hide
     */
    public static final class CellBroadcasts implements BaseColumns {

        /**
         * Not instantiable.
         * @hide
         */
        private CellBroadcasts() {}

        /**
         * The {@code content://} URI for this table.
         */
        public static final Uri CONTENT_URI = Uri.parse("content://cellbroadcasts");

        /**
         * Message geographical scope.
         * <P>Type: INTEGER</P>
         */
        public static final String GEOGRAPHICAL_SCOPE = "geo_scope";

        /**
         * Message serial number.
         * <P>Type: INTEGER</P>
         */
        public static final String SERIAL_NUMBER = "serial_number";

        /**
         * PLMN of broadcast sender. {@code SERIAL_NUMBER + PLMN + LAC + CID} uniquely identifies
         * a broadcast for duplicate detection purposes.
         * <P>Type: TEXT</P>
         */
        public static final String PLMN = "plmn";

        /**
         * Location Area (GSM) or Service Area (UMTS) of broadcast sender. Unused for CDMA.
         * Only included if Geographical Scope of message is not PLMN wide (01).
         * <P>Type: INTEGER</P>
         */
        public static final String LAC = "lac";

        /**
         * Cell ID of message sender (GSM/UMTS). Unused for CDMA. Only included when the
         * Geographical Scope of message is cell wide (00 or 11).
         * <P>Type: INTEGER</P>
         */
        public static final String CID = "cid";

        /**
         * Message code. <em>OBSOLETE: merged into SERIAL_NUMBER.</em>
         * <P>Type: INTEGER</P>
         */
        public static final String V1_MESSAGE_CODE = "message_code";

        /**
         * Message identifier. <em>OBSOLETE: renamed to SERVICE_CATEGORY.</em>
         * <P>Type: INTEGER</P>
         */
        public static final String V1_MESSAGE_IDENTIFIER = "message_id";

        /**
         * Service category (GSM/UMTS: message identifier; CDMA: service category).
         * <P>Type: INTEGER</P>
         */
        public static final String SERVICE_CATEGORY = "service_category";

        /**
         * Message language code.
         * <P>Type: TEXT</P>
         */
        public static final String LANGUAGE_CODE = "language";

        /**
         * Message body.
         * <P>Type: TEXT</P>
         */
        public static final String MESSAGE_BODY = "body";

        /**
         * Message delivery time.
         * <P>Type: INTEGER (long)</P>
         */
        public static final String DELIVERY_TIME = "date";

        /**
         * Has the message been viewed?
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String MESSAGE_READ = "read";

        /**
         * Message format (3GPP or 3GPP2).
         * <P>Type: INTEGER</P>
         */
        public static final String MESSAGE_FORMAT = "format";

        /**
         * Message priority (including emergency).
         * <P>Type: INTEGER</P>
         */
        public static final String MESSAGE_PRIORITY = "priority";

        /**
         * ETWS warning type (ETWS alerts only).
         * <P>Type: INTEGER</P>
         */
        public static final String ETWS_WARNING_TYPE = "etws_warning_type";

        /**
         * CMAS message class (CMAS alerts only).
         * <P>Type: INTEGER</P>
         */
        public static final String CMAS_MESSAGE_CLASS = "cmas_message_class";

        /**
         * CMAS category (CMAS alerts only).
         * <P>Type: INTEGER</P>
         */
        public static final String CMAS_CATEGORY = "cmas_category";

        /**
         * CMAS response type (CMAS alerts only).
         * <P>Type: INTEGER</P>
         */
        public static final String CMAS_RESPONSE_TYPE = "cmas_response_type";

        /**
         * CMAS severity (CMAS alerts only).
         * <P>Type: INTEGER</P>
         */
        public static final String CMAS_SEVERITY = "cmas_severity";

        /**
         * CMAS urgency (CMAS alerts only).
         * <P>Type: INTEGER</P>
         */
        public static final String CMAS_URGENCY = "cmas_urgency";

        /**
         * CMAS certainty (CMAS alerts only).
         * <P>Type: INTEGER</P>
         */
        public static final String CMAS_CERTAINTY = "cmas_certainty";

        /** The default sort order for this table. */
        public static final String DEFAULT_SORT_ORDER = DELIVERY_TIME + " DESC";

        /**
         * Query columns for instantiating {@link android.telephony.CellBroadcastMessage} objects.
         */
        public static final String[] QUERY_COLUMNS = {
                _ID,
                GEOGRAPHICAL_SCOPE,
                PLMN,
                LAC,
                CID,
                SERIAL_NUMBER,
                SERVICE_CATEGORY,
                LANGUAGE_CODE,
                MESSAGE_BODY,
                DELIVERY_TIME,
                MESSAGE_READ,
                MESSAGE_FORMAT,
                MESSAGE_PRIORITY,
                ETWS_WARNING_TYPE,
                CMAS_MESSAGE_CLASS,
                CMAS_CATEGORY,
                CMAS_RESPONSE_TYPE,
                CMAS_SEVERITY,
                CMAS_URGENCY,
                CMAS_CERTAINTY
        };
    }

    /**
     * Constants for interfacing with the ServiceStateProvider and the different fields of the
     * {@link ServiceState} class accessible through the provider.
     */
    public static final class ServiceStateTable {

        /**
         * Not instantiable.
         * @hide
         */
        private ServiceStateTable() {}

        /**
         * The authority string for the ServiceStateProvider
         */
        public static final String AUTHORITY = "service-state";

        /**
         * The {@code content://} style URL for the ServiceStateProvider
         */
        public static final Uri CONTENT_URI = Uri.parse("content://service-state/");

        /**
         * Generates a content {@link Uri} used to receive updates on a specific field in the
         * ServiceState provider.
         * <p>
         * Use this {@link Uri} with a {@link ContentObserver} to be notified of changes to the
         * {@link ServiceState} while your app is running.  You can also use a {@link JobService} to
         * ensure your app is notified of changes to the {@link Uri} even when it is not running.
         * Note, however, that using a {@link JobService} does not guarantee timely delivery of
         * updates to the {@link Uri}.
         *
         * @param subscriptionId the subscriptionId to receive updates on
         * @param field the ServiceState field to receive updates on
         * @return the Uri used to observe {@link ServiceState} changes
         */
        public static Uri getUriForSubscriptionIdAndField(int subscriptionId, String field) {
            return CONTENT_URI.buildUpon().appendEncodedPath(String.valueOf(subscriptionId))
                    .appendEncodedPath(field).build();
        }

        /**
         * Generates a content {@link Uri} used to receive updates on every field in the
         * ServiceState provider.
         * <p>
         * Use this {@link Uri} with a {@link ContentObserver} to be notified of changes to the
         * {@link ServiceState} while your app is running.  You can also use a {@link JobService} to
         * ensure your app is notified of changes to the {@link Uri} even when it is not running.
         * Note, however, that using a {@link JobService} does not guarantee timely delivery of
         * updates to the {@link Uri}.
         *
         * @param subscriptionId the subscriptionId to receive updates on
         * @return the Uri used to observe {@link ServiceState} changes
         */
        public static Uri getUriForSubscriptionId(int subscriptionId) {
            return CONTENT_URI.buildUpon().appendEncodedPath(String.valueOf(subscriptionId)).build();
        }

        /**
         * Used to insert a ServiceState into the ServiceStateProvider as a ContentValues instance.
         *
         * @param state the ServiceState to convert into ContentValues
         * @return the convertedContentValues instance
         * @hide
         */
        public static ContentValues getContentValuesForServiceState(ServiceState state) {
            ContentValues values = new ContentValues();
            values.put(VOICE_REG_STATE, state.getVoiceRegState());
            values.put(DATA_REG_STATE, state.getDataRegState());
            values.put(VOICE_ROAMING_TYPE, state.getVoiceRoamingType());
            values.put(DATA_ROAMING_TYPE, state.getDataRoamingType());
            values.put(VOICE_OPERATOR_ALPHA_LONG, state.getVoiceOperatorAlphaLong());
            values.put(VOICE_OPERATOR_ALPHA_SHORT, state.getVoiceOperatorAlphaShort());
            values.put(VOICE_OPERATOR_NUMERIC, state.getVoiceOperatorNumeric());
            values.put(DATA_OPERATOR_ALPHA_LONG, state.getDataOperatorAlphaLong());
            values.put(DATA_OPERATOR_ALPHA_SHORT, state.getDataOperatorAlphaShort());
            values.put(DATA_OPERATOR_NUMERIC, state.getDataOperatorNumeric());
            values.put(IS_MANUAL_NETWORK_SELECTION, state.getIsManualSelection());
            values.put(RIL_VOICE_RADIO_TECHNOLOGY, state.getRilVoiceRadioTechnology());
            values.put(RIL_DATA_RADIO_TECHNOLOGY, state.getRilDataRadioTechnology());
            values.put(CSS_INDICATOR, state.getCssIndicator());
            values.put(NETWORK_ID, state.getCdmaNetworkId());
            values.put(SYSTEM_ID, state.getCdmaSystemId());
            values.put(CDMA_ROAMING_INDICATOR, state.getCdmaRoamingIndicator());
            values.put(CDMA_DEFAULT_ROAMING_INDICATOR, state.getCdmaDefaultRoamingIndicator());
            values.put(CDMA_ERI_ICON_INDEX, state.getCdmaEriIconIndex());
            values.put(CDMA_ERI_ICON_MODE, state.getCdmaEriIconMode());
            values.put(IS_EMERGENCY_ONLY, state.isEmergencyOnly());
            values.put(IS_USING_CARRIER_AGGREGATION, state.isUsingCarrierAggregation());
            return values;
        }

        /**
         * An integer value indicating the current voice service state.
         * <p>
         * Valid values: {@link ServiceState#STATE_IN_SERVICE},
         * {@link ServiceState#STATE_OUT_OF_SERVICE}, {@link ServiceState#STATE_EMERGENCY_ONLY},
         * {@link ServiceState#STATE_POWER_OFF}.
         * <p>
         * This is the same as {@link ServiceState#getState()}.
         */
        public static final String VOICE_REG_STATE = "voice_reg_state";

        /**
         * An integer value indicating the current data service state.
         * <p>
         * Valid values: {@link ServiceState#STATE_IN_SERVICE},
         * {@link ServiceState#STATE_OUT_OF_SERVICE}, {@link ServiceState#STATE_EMERGENCY_ONLY},
         * {@link ServiceState#STATE_POWER_OFF}.
         * <p>
         * This is the same as {@link ServiceState#getDataRegState()}.
         * @hide
         */
        public static final String DATA_REG_STATE = "data_reg_state";

        /**
         * An integer value indicating the current voice roaming type.
         * <p>
         * This is the same as {@link ServiceState#getVoiceRoamingType()}.
         * @hide
         */
        public static final String VOICE_ROAMING_TYPE = "voice_roaming_type";

        /**
         * An integer value indicating the current data roaming type.
         * <p>
         * This is the same as {@link ServiceState#getDataRoamingType()}.
         * @hide
         */
        public static final String DATA_ROAMING_TYPE = "data_roaming_type";

        /**
         * The current registered voice network operator name in long alphanumeric format.
         * <p>
         * This is the same as {@link ServiceState#getVoiceOperatorAlphaLong()}.
         * @hide
         */
        public static final String VOICE_OPERATOR_ALPHA_LONG = "voice_operator_alpha_long";

        /**
         * The current registered operator name in short alphanumeric format.
         * <p>
         * In GSM/UMTS, short format can be up to 8 characters long. The current registered voice
         * network operator name in long alphanumeric format.
         * <p>
         * This is the same as {@link ServiceState#getVoiceOperatorAlphaShort()}.
         * @hide
         */
        public static final String VOICE_OPERATOR_ALPHA_SHORT = "voice_operator_alpha_short";


        /**
         * The current registered operator numeric id.
         * <p>
         * In GSM/UMTS, numeric format is 3 digit country code plus 2 or 3 digit
         * network code.
         * <p>
         * This is the same as {@link ServiceState#getOperatorNumeric()}.
         */
        public static final String VOICE_OPERATOR_NUMERIC = "voice_operator_numeric";

        /**
         * The current registered data network operator name in long alphanumeric format.
         * <p>
         * This is the same as {@link ServiceState#getDataOperatorAlphaLong()}.
         * @hide
         */
        public static final String DATA_OPERATOR_ALPHA_LONG = "data_operator_alpha_long";

        /**
         * The current registered data network operator name in short alphanumeric format.
         * <p>
         * This is the same as {@link ServiceState#getDataOperatorAlphaShort()}.
         * @hide
         */
        public static final String DATA_OPERATOR_ALPHA_SHORT = "data_operator_alpha_short";

        /**
         * The current registered data network operator numeric id.
         * <p>
         * This is the same as {@link ServiceState#getDataOperatorNumeric()}.
         * @hide
         */
        public static final String DATA_OPERATOR_NUMERIC = "data_operator_numeric";

        /**
         * The current network selection mode.
         * <p>
         * This is the same as {@link ServiceState#getIsManualSelection()}.
         */
        public static final String IS_MANUAL_NETWORK_SELECTION = "is_manual_network_selection";

        /**
         * This is the same as {@link ServiceState#getRilVoiceRadioTechnology()}.
         * @hide
         */
        public static final String RIL_VOICE_RADIO_TECHNOLOGY = "ril_voice_radio_technology";

        /**
         * This is the same as {@link ServiceState#getRilDataRadioTechnology()}.
         * @hide
         */
        public static final String RIL_DATA_RADIO_TECHNOLOGY = "ril_data_radio_technology";

        /**
         * This is the same as {@link ServiceState#getCssIndicator()}.
         * @hide
         */
        public static final String CSS_INDICATOR = "css_indicator";

        /**
         * This is the same as {@link ServiceState#getCdmaNetworkId()}.
         * @hide
         */
        public static final String NETWORK_ID = "network_id";

        /**
         * This is the same as {@link ServiceState#getCdmaSystemId()}.
         * @hide
         */
        public static final String SYSTEM_ID = "system_id";

        /**
         * This is the same as {@link ServiceState#getCdmaRoamingIndicator()}.
         * @hide
         */
        public static final String CDMA_ROAMING_INDICATOR = "cdma_roaming_indicator";

        /**
         * This is the same as {@link ServiceState#getCdmaDefaultRoamingIndicator()}.
         * @hide
         */
        public static final String CDMA_DEFAULT_ROAMING_INDICATOR =
                "cdma_default_roaming_indicator";

        /**
         * This is the same as {@link ServiceState#getCdmaEriIconIndex()}.
         * @hide
         */
        public static final String CDMA_ERI_ICON_INDEX = "cdma_eri_icon_index";

        /**
         * This is the same as {@link ServiceState#getCdmaEriIconMode()}.
         * @hide
         */
        public static final String CDMA_ERI_ICON_MODE = "cdma_eri_icon_mode";

        /**
         * This is the same as {@link ServiceState#isEmergencyOnly()}.
         * @hide
         */
        public static final String IS_EMERGENCY_ONLY = "is_emergency_only";

        /**
         * This is the same as {@link ServiceState#getDataRoamingFromRegistration()}.
         * @hide
         */
        public static final String IS_DATA_ROAMING_FROM_REGISTRATION =
                "is_data_roaming_from_registration";

        /**
         * This is the same as {@link ServiceState#isUsingCarrierAggregation()}.
         * @hide
         */
        public static final String IS_USING_CARRIER_AGGREGATION = "is_using_carrier_aggregation";
    }

    /**
     * Contains carrier identification information for the current subscriptions.
     */
    public static final class CarrierId implements BaseColumns {
        /**
         * Not instantiable.
         * @hide
         */
        private CarrierId() {}

        /**
         * The {@code content://} style URI for this provider.
         */
        public static final Uri CONTENT_URI = Uri.parse("content://carrier_id");

        /**
         * The authority string for the CarrierId Provider
         * @hide
         */
        public static final String AUTHORITY = "carrier_id";


        /**
         * Generates a content {@link Uri} used to receive updates on carrier identity change
         * on the given subscriptionId
         * <p>
         * Use this {@link Uri} with a {@link ContentObserver} to be notified of changes to the
         * carrier identity {@link TelephonyManager#getSimCarrierId()}
         * while your app is running. You can also use a {@link JobService} to ensure your app
         * is notified of changes to the {@link Uri} even when it is not running.
         * Note, however, that using a {@link JobService} does not guarantee timely delivery of
         * updates to the {@link Uri}.
         *
         * @param subscriptionId the subscriptionId to receive updates on
         * @return the Uri used to observe carrier identity changes
         */
        public static Uri getUriForSubscriptionId(int subscriptionId) {
            return CONTENT_URI.buildUpon().appendEncodedPath(
                    String.valueOf(subscriptionId)).build();
        }

        /**
         * Generates a content {@link Uri} used to receive updates on precise carrier identity
         * change on the given subscriptionId returned by
         * {@link TelephonyManager#getSimPreciseCarrierId()}.
         * @see TelephonyManager#ACTION_SUBSCRIPTION_PRECISE_CARRIER_IDENTITY_CHANGED
         * <p>
         * Use this {@link Uri} with a {@link ContentObserver} to be notified of changes to the
         * precise carrier identity {@link TelephonyManager#getSimPreciseCarrierId()}
         * while your app is running. You can also use a {@link JobService} to ensure your app
         * is notified of changes to the {@link Uri} even when it is not running.
         * Note, however, that using a {@link JobService} does not guarantee timely delivery of
         * updates to the {@link Uri}.
         *
         * @param subscriptionId the subscriptionId to receive updates on
         * @return the Uri used to observe precise carrier identity changes
         */
        @NonNull
        public static Uri getPreciseCarrierIdUriForSubscriptionId(int subscriptionId) {
            return Uri.withAppendedPath(Uri.withAppendedPath(CONTENT_URI, "precise"),
                    String.valueOf(subscriptionId));
        }

        /**
         * A user facing carrier name.
         * @see TelephonyManager#getSimCarrierIdName()
         * <P>Type: TEXT </P>
         */
        public static final String CARRIER_NAME = "carrier_name";

        /**
         * A unique carrier id
         * @see TelephonyManager#getSimCarrierId()
         * <P>Type: INTEGER </P>
         */
        public static final String CARRIER_ID = "carrier_id";

        /**
         * A fine-grained carrier id.
         * @see TelephonyManager#getSimPreciseCarrierId()
         * This is not a database column, only used to notify content observers for
         * {@link #getPreciseCarrierIdUriForSubscriptionId(int)}
         */
        public static final String PRECISE_CARRIER_ID = "precise_carrier_id";

        /**
         * A user facing carrier name for precise carrier id {@link #PRECISE_CARRIER_ID}.
         * @see TelephonyManager#getSimPreciseCarrierIdName()
         * This is not a database column, only used to notify content observers for
         * {@link #getPreciseCarrierIdUriForSubscriptionId(int)}
         */
        public static final String PRECISE_CARRIER_ID_NAME = "precise_carrier_id_name";

        /**
         * A unique parent carrier id. The parent-child
         * relationship can be used to further differentiate a single carrier by different networks,
         * by prepaid v.s. postpaid or even by 4G v.s. 3G plan. It's an optional field.
         * A carrier id with a valid parent_carrier_id is considered fine-grained carrier id, will
         * not be returned as {@link #CARRIER_ID} but {@link #PRECISE_CARRIER_ID}.
         * <P>Type: INTEGER </P>
         * @hide
         */
        public static final String PARENT_CARRIER_ID = "parent_carrier_id";

        /**
         * Contains mappings between matching rules with carrier id for all carriers.
         * @hide
         */
        public static final class All implements BaseColumns {
            /**
             * Numeric operator ID (as String). {@code MCC + MNC}
             * <P>Type: TEXT </P>
             */
            public static final String MCCMNC = "mccmnc";

            /**
             * Group id level 1 (as String).
             * <P>Type: TEXT </P>
             */
            public static final String GID1 = "gid1";

            /**
             * Group id level 2 (as String).
             * <P>Type: TEXT </P>
             */
            public static final String GID2 = "gid2";

            /**
             * Public Land Mobile Network name.
             * <P>Type: TEXT </P>
             */
            public static final String PLMN = "plmn";

            /**
             * Prefix xpattern of IMSI (International Mobile Subscriber Identity).
             * <P>Type: TEXT </P>
             */
            public static final String IMSI_PREFIX_XPATTERN = "imsi_prefix_xpattern";

            /**
             * Service Provider Name.
             * <P>Type: TEXT </P>
             */
            public static final String SPN = "spn";

            /**
             * Prefer APN name.
             * <P>Type: TEXT </P>
             */
            public static final String APN = "apn";

            /**
             * Prefix of Integrated Circuit Card Identifier.
             * <P>Type: TEXT </P>
             */
            public static final String ICCID_PREFIX = "iccid_prefix";

            /**
             * Certificate for carrier privilege access rules.
             * <P>Type: TEXT in hex string </P>
             */
            public static final String PRIVILEGE_ACCESS_RULE = "privilege_access_rule";

            /**
             * The {@code content://} URI for this table.
             */
            public static final Uri CONTENT_URI = Uri.parse("content://carrier_id/all");
        }
    }
}
