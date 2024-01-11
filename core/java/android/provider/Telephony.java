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

import android.Manifest;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledAfter;
import android.compat.annotation.UnsupportedAppUsage;
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
import android.os.Bundle;
import android.telephony.CarrierConfigManager;
import android.telephony.Rlog;
import android.telephony.ServiceState;
import android.telephony.SmsMessage;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.UiccAccessRule;
import android.text.TextUtils;
import android.util.Patterns;

import com.android.internal.telephony.SmsApplication;
import com.android.internal.telephony.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HashSet;
import java.util.List;
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
         * <p>
         * As of Android 11 apps will need specific permission to query other packages. To use
         * this method an app must include in their AndroidManifest:
         * <pre>{@code
         * <queries>
         *   <intent>
         *     <action android:name="android.provider.Telephony.SMS_DELIVER"/>
         *   </intent>
         * </queries>
         * }</pre>
         * Which will allow them to query packages which declare intent filters that include
         * the {@link android.provider.Telephony.Sms.Intents#SMS_DELIVER_ACTION} intent.
         * </p>
         *
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
            @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
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
            @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
            public static Uri addMessage(int subId, ContentResolver resolver,
                    String address, String body, String subject, Long date) {
                return addMessageToUri(subId, resolver, CONTENT_URI, address, body,
                        subject, date, true, false);
            }
        }

        /**
         * Contains all draft text-based SMS messages in the SMS app.
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
            @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
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
            @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
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
         * Contains a view of SMS conversations (also referred to as threads). This is similar to
         * {@link Threads}, but only includes SMS messages and columns relevant to SMS
         * conversations.
         * <p>
         * Note that this view ignores any information about MMS messages, it is a
         * view of conversations as if MMS messages did not exist at all. This means that all
         * relevant information, such as snippets and message count, will ignore any MMS messages
         * that might be in the same thread through other views and present only data based on the
         * SMS messages in that thread.
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
             * The first 45 characters of the body of the most recent message.
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
             * Set as a "result" extra in the {@link #SMS_REJECTED_ACTION} intent to indicate
             * insufficient memory to store the message.
             */
            public static final int RESULT_SMS_OUT_OF_MEMORY = 3;

            /**
             * Set as a "result" extra in the {@link #SMS_REJECTED_ACTION} intent to indicate that
             * the message, while possibly valid, is of a format or encoding that is not supported.
             */
            public static final int RESULT_SMS_UNSUPPORTED = 4;

            /**
             * Set as a "result" extra in the {@link #SMS_REJECTED_ACTION} intent to indicate a
             * duplicate incoming message.
             */
            public static final int RESULT_SMS_DUPLICATED = 5;

            /**
             * Set as a "result" extra in the {@link #SMS_REJECTED_ACTION} intent to indicate a
             * dispatch failure.
             */
            public static final int RESULT_SMS_DISPATCH_FAILURE = 6;

            /**
             * Set as a "result" extra in the {@link #SMS_REJECTED_ACTION} intent to indicate a null
             * PDU was received.
             */
            public static final int RESULT_SMS_NULL_PDU = 7;

            /**
             * Set as a "result" extra in the {@link #SMS_REJECTED_ACTION} intent to indicate a null
             * message was encountered.
             */
            public static final int RESULT_SMS_NULL_MESSAGE = 8;

            /**
             * Set as a "result" extra in the {@link #SMS_REJECTED_ACTION} intent to indicate an sms
             * was received while the phone was in encrypted state.
             * <p>
             * This result code is only used on devices that use Full Disk Encryption.  Support for
             * Full Disk Encryption was entirely removed in API level 33, having been replaced by
             * File Based Encryption.  Devices that use File Based Encryption never reject incoming
             * SMS messages due to the encryption state.
             */
            public static final int RESULT_SMS_RECEIVED_WHILE_ENCRYPTED = 9;

            /**
             * Set as a "result" extra in the {@link #SMS_REJECTED_ACTION} intent to indicate a
             * telephony database error.
             */
            public static final int RESULT_SMS_DATABASE_ERROR = 10;

            /**
             * Set as a "result" extra in the {@link #SMS_REJECTED_ACTION} intent to indicate an
             * invalid uri.
             */
            public static final int RESULT_SMS_INVALID_URI = 11;

            /**
             * Activity action: Ask the user to change the default
             * SMS application. This will show a dialog that asks the
             * user whether they want to replace the current default
             * SMS application with the one specified in
             * {@link #EXTRA_PACKAGE_NAME}.
             * <p>
             * This is no longer supported since Q, please use
             * {@link android.app.role.RoleManager#createRequestRoleIntent(String)} with
             * {@link android.app.role.RoleManager#ROLE_SMS} instead.
             */
            @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
            public static final String ACTION_CHANGE_DEFAULT =
                    "android.provider.Telephony.ACTION_CHANGE_DEFAULT";

            /**
             * The PackageName string passed in as an
             * extra for {@link #ACTION_CHANGE_DEFAULT}
             *
             * @see #ACTION_CHANGE_DEFAULT
             * <p>
             * This is no longer supported since Q, please use
             * {@link android.app.role.RoleManager#createRequestRoleIntent(String)} with
             * {@link android.app.role.RoleManager#ROLE_SMS} instead.
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
             *   <li><em>"message"</em> - An {@link android.telephony.SmsCbMessage} object
             *   containing the broadcast message data, including ETWS or CMAS warning notification
             *   info if present.</li>
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
             * @hide
             */
            @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
            @SystemApi
            public static final String ACTION_SMS_EMERGENCY_CB_RECEIVED =
                    "android.provider.action.SMS_EMERGENCY_CB_RECEIVED";

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
             * And they are of the form {@code *#*#<code>#*#*}. The intent will have the data
             * URI: {@code android_secret_code://<code>}. It is possible that a manifest
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
             * Broadcast action: When SMS-MMS db is being created. If file-based encryption is
             * supported, this broadcast indicates creation of the db in credential-encrypted
             * storage. A boolean is specified in {@link #EXTRA_IS_INITIAL_CREATE} to indicate if
             * this is the initial create of the db.
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
                } catch (ClassCastException e) {
                    Rlog.e(TAG, "getMessagesFromIntent: " + e);
                    return null;
                }

                if (messages == null) {
                    Rlog.e(TAG, "pdus does not exist in the intent");
                    return null;
                }

                String format = intent.getStringExtra("format");
                int subId = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
                        SubscriptionManager.INVALID_SUBSCRIPTION_ID);
                if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    Rlog.v(TAG, "getMessagesFromIntent with valid subId : " + subId);
                } else {
                    Rlog.v(TAG, "getMessagesFromIntent");
                }

                int pduCount = messages.length;
                SmsMessage[] msgs = new SmsMessage[pduCount];

                for (int i = 0; i < pduCount; i++) {
                    byte[] pdu = (byte[]) messages[i];
                    msgs[i] = SmsMessage.createFromPdu(pdu, format);
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

        /**
         * Mobile Country Code (MCC).
         * <P> Type: TEXT </P>
         */
        public static final String MCC = "mcc";

        /**
         * Mobile Network Code (MNC).
         * <P> Type: TEXT </P>
         */
        public static final String MNC = "mnc";

        /**
         * KeyType whether the key is being used for WLAN or ePDG.
         * <P> Type: INTEGER </P>
         */
        public static final String KEY_TYPE = "key_type";

        /**
         * The carrier public key that is used for the IMSI encryption.
         * <P> Type: TEXT </P>
         */
        public static final String PUBLIC_KEY = "public_key";

        /**
         * The key identifier Attribute value pair that helps a server locate
         * the private key to decrypt the permanent identity.
         * <P> Type: TEXT </P>
         */
        public static final String KEY_IDENTIFIER = "key_identifier";

        /**
         * Date-Time in UTC when the key will expire.
         * <P> Type: INTEGER (long) </P>
         */
        public static final String EXPIRATION_TIME = "expiration_time";

        /**
         * Timestamp when this table was last modified, in milliseconds since
         * January 1, 1970 00:00:00.0 UTC.
         * <P> Type: INTEGER (long) </P>
         */
        public static final String LAST_MODIFIED = "last_modified";

        /**
         * Carrier ID of the operetor.
         * <P> Type: TEXT </P>
         */
        public static final String CARRIER_ID = "carrier_id";
        /**
         * The {@code content://} style URL for this table.
         */
        @NonNull
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

        /**
         * The subscription to which the message belongs to. Its value will be less than 0
         * if the sub id cannot be determined.
         * <p>Type: INTEGER (long) </p>
         * @hide
         */
        public static final String SUBSCRIPTION_ID = "sub_id";
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

        /**
         * The subscription to which the message belongs to. Its value will be less than 0
         * if the sub id cannot be determined.
         * <p>Type: INTEGER (long) </p>
         * @hide
         */
        public static final String SUBSCRIPTION_ID = "sub_id";
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

            /**
             * The subscription to which the message belongs to. Its value will be less than 0
             * if the sub id cannot be determined.
             * <p>Type: INTEGER (long) </p>
             * @hide
             */
            public static final String SUBSCRIPTION_ID = "sub_id";

            /**
             * Generates a Addr {@link Uri} for message, used to perform Addr table operation
             * for mms.
             *
             * @param messageId the messageId used to generate Addr {@link Uri} dynamically
             * @return the addrUri used to perform Addr table operation for mms
             */
            @NonNull
            public static Uri getAddrUriForMessage(@NonNull String messageId) {
                Uri addrUri = Mms.CONTENT_URI.buildUpon()
                        .appendPath(String.valueOf(messageId)).appendPath("addr").build();
                return addrUri;
            }
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
             * The name of part table.
             */
            private static final String TABLE_PART = "part";

            /**
             * The {@code content://} style URL for this table. Can be appended with a part ID to
             * address individual parts.
             */
            @NonNull
            public static final Uri CONTENT_URI = Uri.withAppendedPath(Mms.CONTENT_URI, TABLE_PART);

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

            /**
             * The subscription to which the message belongs to. Its value will be less than 0
             * if the sub id cannot be determined.
             * <p>Type: INTEGER (long) </p>
             * @hide
             */
            public static final String SUBSCRIPTION_ID = "sub_id";

            /**
             * Generates a Part {@link Uri} for message, used to perform Part table operation
             * for mms.
             *
             * @param messageId the messageId used to generate Part {@link Uri} dynamically
             * @return the partUri used to perform Part table operation for mms
             */
            @NonNull
            public static Uri getPartUriForMessage(@NonNull String messageId) {
                Uri partUri = Mms.CONTENT_URI.buildUpon()
                        .appendPath(String.valueOf(messageId)).appendPath(
                                TABLE_PART).build();
                return partUri;
            }
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

            /**
             * The subscription to which the message belongs to. Its value will be less than 0
             * if the sub id cannot be determined.
             * <p>Type: INTEGER (long) </p>
             * @hide
             */
            public static final String SUBSCRIPTION_ID = "sub_id";
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
         * <P>This {@link Uri} is used to check at most one locked message found in the union of MMS
         * and SMS messages. Also this will return only _id column in response.</P>
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

            /**
             * The subscription to which the message belongs to. Its value will be less than 0
             * if the sub id cannot be determined.
             * <p>Type: INTEGER (long) </p>
             * @hide
             */
            public static final String SUBSCRIPTION_ID = "sub_id";
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
        @NonNull
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
        @NonNull
        public static final Uri SIM_APN_URI = Uri.parse(
                "content://telephony/carriers/sim_apn_list");

        /**
         * The {@code content://} style URL to be called from DevicePolicyManagerService,
         * can manage DPC-owned APNs.
         * @hide
         */
        public static final @NonNull Uri DPC_URI = Uri.parse("content://telephony/carriers/dpc");

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
         * The {@code content://} style URL for the perferred APN used for internet.
         *
         * @hide
         */
        public static final Uri PREFERRED_APN_URI = Uri.parse(
                "content://telephony/carriers/preferapn/subId");

        /**
         * The {@code content://} style URL for the perferred APN set id.
         *
         * @hide
         */
        public static final Uri PREFERRED_APN_SET_URI = Uri.parse(
                "content://telephony/carriers/preferapnset/subId");

        /**
         * The id of preferred APN.
         *
         * @see #PREFERRED_APN_URI
         * @hide
         */
        public static final String APN_ID = "apn_id";

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
         * Lingering radio technology (network type) bitmask.
         * To check what values can be contained, refer to the NETWORK_TYPE_ constants in
         * {@link android.telephony.TelephonyManager}.
         * Bitmask for a radio tech R is (1 << (R - 1))
         * <P>Type: INTEGER (long)</P>
         * @hide
         */
        public static final String LINGERING_NETWORK_TYPE_BITMASK =
                "lingering_network_type_bitmask";

        /**
         * Sets whether the PDU session brought up by this APN should always be on.
         * See 3GPP TS 23.501 section 5.6.13
         * <P>Type: INTEGER</P>
         */
        @FlaggedApi(Flags.FLAG_APN_SETTING_FIELD_SUPPORT_FLAG)
        public static final String ALWAYS_ON = "always_on";

        /**
         * The infrastructure bitmask which the APN can be used on. For example, some APNs can only
         * be used when the device is on cellular, on satellite, or both. The default value is
         * 3 (INFRASTRUCTURE_CELLULAR | INFRASTRUCTURE_SATELLITE).
         *
         * <P>Type: INTEGER</P>
         * @hide
         */
        public static final String INFRASTRUCTURE_BITMASK = "infrastructure_bitmask";

        /**
         * Indicating if the APN is used for eSIM bootsrap provisioning. The default value is 0 (Not
         * used for eSIM bootstrap provisioning).
         *
         * <P>Type: INTEGER</P>
         * @hide
         */
        public static final String ESIM_BOOTSTRAP_PROVISIONING = "esim_bootstrap_provisioning";

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
         * @deprecated use {@link #MTU_V4} or {@link #MTU_V6} instead
         */
        @SystemApi
        @Deprecated
        public static final String MTU = "mtu";

        /**
         * The MTU (maximum transmit unit) size of the mobile interface for IPv4 to which the APN is
         * connected, in bytes.
         * <p>Type: INTEGER </p>
         */
        @FlaggedApi(Flags.FLAG_APN_SETTING_FIELD_SUPPORT_FLAG)
        public static final String MTU_V4 = "mtu_v4";

        /**
         * The MTU (maximum transmit unit) size of the mobile interface for IPv6 to which the APN is
         * connected, in bytes.
         * <p>Type: INTEGER </p>
         */
        @FlaggedApi(Flags.FLAG_APN_SETTING_FIELD_SUPPORT_FLAG)
        public static final String MTU_V6 = "mtu_v6";

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
         */
        @FlaggedApi(Flags.FLAG_APN_SETTING_FIELD_SUPPORT_FLAG)
        public static final String USER_VISIBLE = "user_visible";

        /**
         * {@code true} if the user allowed to edit this APN, {@code false} otherwise.
         * <p>Type: INTEGER (boolean)</p>
         */
        @FlaggedApi(Flags.FLAG_APN_SETTING_FIELD_SUPPORT_FLAG)
        public static final String USER_EDITABLE = "user_editable";

        /**
         * Integer value denoting an invalid APN id
         * @hide
         */
        public static final int INVALID_APN_ID = -1;

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
         * preferred, the device can only use APNs with the same set id as the selected APN.
         * <p>Type: INTEGER</p>
         * @hide
         */
        @SystemApi
        public static final String APN_SET_ID = "apn_set_id";

        /**
         * Possible value for the {@link #APN_SET_ID} field. By default APNs are added to set 0.
         * <p>Type: INTEGER</p>
         * @hide
         */
        @SystemApi
        public static final int NO_APN_SET_ID = 0;

        /**
         * Possible value for the {@link #APN_SET_ID} field.
         * APNs with MATCH_ALL_APN_SET_ID will be used regardless of any set ids of
         * the selected APN.
         * <p>Type: INTEGER</p>
         * @hide
         */
        @SystemApi
        public static final int MATCH_ALL_APN_SET_ID = -1;

        /**
         * A unique carrier id associated with this APN {@link TelephonyManager#getSimCarrierId()}
         * In case of matching carrier information, this should be used by default instead of
         * those fields of {@link #MCC}, {@link #MNC}, {@link #NUMERIC}, {@link #MVNO_TYPE},
         * {@link #MVNO_MATCH_DATA}, etc.
         * <p>Type: STRING</p>
         */
        public static final String CARRIER_ID = "carrier_id";

        /**
         * The skip 464xlat flag. Flag works as follows.
         * {@link #SKIP_464XLAT_DEFAULT}: the APN will skip only APN is IMS and no internet.
         * {@link #SKIP_464XLAT_DISABLE}: the APN will NOT skip 464xlat
         * {@link #SKIP_464XLAT_ENABLE}: the APN will skip 464xlat
         * <p>Type: INTEGER</p>
         *
         * @hide
         */
        public static final String SKIP_464XLAT = "skip_464xlat";

        /**
         * Possible value for the {@link #SKIP_464XLAT} field.
         * <p>Type: INTEGER</p>
         *
         * @hide
         */
        public static final int SKIP_464XLAT_DEFAULT = -1;

        /**
         * Possible value for the {@link #SKIP_464XLAT} field.
         * <p>Type: INTEGER</p>
         *
         * @hide
         */
        public static final int SKIP_464XLAT_DISABLE = 0;

        /**
         * Possible value for the {@link #SKIP_464XLAT} field.
         * <p>Type: INTEGER</p>
         *
         * @hide
         */
        public static final int SKIP_464XLAT_ENABLE = 1;


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

        /**
         * Compat framework change ID for the APN db read permission change.
         *
         * In API level 30 and beyond, accessing the APN database will require the
         * {@link android.Manifest.permission#WRITE_APN_SETTINGS} permission. This change ID tracks
         * apps that are affected because they don't hold this permission.
         * @hide
         */
        @ChangeId
        @EnabledAfter(targetSdkVersion = android.os.Build.VERSION_CODES.Q)
        public static final long APN_READING_PERMISSION_CHANGE_ID = 124107808L;
    }

    /**
     * Contains received cell broadcast messages. More details are available in 3GPP TS 23.041.
     * @hide
     */
    @SystemApi
    public static final class CellBroadcasts implements BaseColumns {

        /**
         * Not instantiable.
         * @hide
         */
        private CellBroadcasts() {}

        /**
         * The {@code content://} URI for this table.
         * Only privileged framework components running on phone or network stack uid can
         * query or modify this table.
         */
        @NonNull
        public static final Uri CONTENT_URI = Uri.parse("content://cellbroadcasts");

        /**
         * The {@code content://} URI for query cellbroadcast message history.
         * query results include following entries
         * <ul>
         *     <li>{@link #_ID}</li>
         *     <li>{@link #SLOT_INDEX}</li>
         *     <li>{@link #GEOGRAPHICAL_SCOPE}</li>
         *     <li>{@link #PLMN}</li>
         *     <li>{@link #LAC}</li>
         *     <li>{@link #CID}</li>
         *     <li>{@link #SERIAL_NUMBER}</li>
         *     <li>{@link #SERVICE_CATEGORY}</li>
         *     <li>{@link #LANGUAGE_CODE}</li>
         *     <li>{@link #MESSAGE_BODY}</li>
         *     <li>{@link #DELIVERY_TIME}</li>
         *     <li>{@link #MESSAGE_READ}</li>
         *     <li>{@link #MESSAGE_FORMAT}</li>
         *     <li>{@link #MESSAGE_PRIORITY}</li>
         *     <li>{@link #ETWS_WARNING_TYPE}</li>
         *     <li>{@link #CMAS_MESSAGE_CLASS}</li>
         *     <li>{@link #CMAS_CATEGORY}</li>
         *     <li>{@link #CMAS_RESPONSE_TYPE}</li>
         *     <li>{@link #CMAS_SEVERITY}</li>
         *     <li>{@link #CMAS_URGENCY}</li>
         *     <li>{@link #CMAS_CERTAINTY}</li>
         * </ul>
         */
        @RequiresPermission(Manifest.permission.READ_CELL_BROADCASTS)
        @NonNull
        public static final Uri MESSAGE_HISTORY_URI = Uri.parse("content://cellbroadcasts/history");

        /**
         * The authority for the legacy cellbroadcast provider.
         * This is used for OEM data migration. OEMs want to migrate message history or
         * sharepreference data to mainlined cellbroadcastreceiver app, should have a
         * contentprovider with authority: cellbroadcast-legacy. Mainlined cellbroadcastreceiver
         * will interact with this URI to retrieve data and persists to mainlined cellbroadcast app.
         *
         * @hide
         */
        @SystemApi
        public static final @NonNull String AUTHORITY_LEGACY = "cellbroadcast-legacy";

        /**
         * A content:// style uri to the authority for the legacy cellbroadcast provider.
         * @hide
         */
        @SystemApi
        public static final @NonNull Uri AUTHORITY_LEGACY_URI =
                Uri.parse("content://cellbroadcast-legacy");

        /**
         * Method name to {@link android.content.ContentProvider#call(String, String, Bundle)
         * for {@link #AUTHORITY_LEGACY}. Used to query cellbroadcast {@link Preference},
         * containing following supported entries
         * <ul>
         *     <li>{@link #ENABLE_AREA_UPDATE_INFO_PREF}</li>
         *     <li>{@link #ENABLE_TEST_ALERT_PREF}</li>
         *     <li>{@link #ENABLE_STATE_LOCAL_TEST_PREF}</li>
         *     <li>{@link #ENABLE_PUBLIC_SAFETY_PREF}</li>
         *     <li>{@link #ENABLE_CMAS_AMBER_PREF}</li>
         *     <li>{@link #ENABLE_CMAS_SEVERE_THREAT_PREF}</li>
         *     <li>{@link #ENABLE_CMAS_EXTREME_THREAT_PREF}</li>
         *     <li>{@link #ENABLE_CMAS_PRESIDENTIAL_PREF}</li>
         *     <li>{@link #ENABLE_ALERT_VIBRATION_PREF}</li>
         *     <li>{@link #ENABLE_EMERGENCY_PERF}</li>
         *     <li>{@link #ENABLE_CMAS_IN_SECOND_LANGUAGE_PREF}</li>
         * </ul>
         * @hide
         */
        @SystemApi
        public static final @NonNull String CALL_METHOD_GET_PREFERENCE = "get_preference";

        /**
         * Arg name to {@link android.content.ContentProvider#call(String, String, Bundle)}
         * for {@link #AUTHORITY_LEGACY}.
         * Contains all supported shared preferences for cellbroadcast.
         *
         * @hide
         */
        @SystemApi
        public static final class Preference {
            /**
             * Not Instantiatable.
             * @hide
             */
            private Preference() {}

            /** Preference to enable area update info alert */
            public static final @NonNull String ENABLE_AREA_UPDATE_INFO_PREF =
                    "enable_area_update_info_alerts";

            /** Preference to enable test alert */
            public static final @NonNull String ENABLE_TEST_ALERT_PREF =
                    "enable_test_alerts";

            /** Preference to enable state local test alert */
            public static final @NonNull String ENABLE_STATE_LOCAL_TEST_PREF
                    = "enable_state_local_test_alerts";

            /** Preference to enable public safety alert */
            public static final @NonNull String ENABLE_PUBLIC_SAFETY_PREF
                    = "enable_public_safety_messages";

            /** Preference to enable amber alert */
            public static final @NonNull String ENABLE_CMAS_AMBER_PREF
                    = "enable_cmas_amber_alerts";

            /** Preference to enable severe threat alert */
            public static final @NonNull String ENABLE_CMAS_SEVERE_THREAT_PREF
                    = "enable_cmas_severe_threat_alerts";

            /** Preference to enable extreme threat alert */
            public static final @NonNull String ENABLE_CMAS_EXTREME_THREAT_PREF =
                    "enable_cmas_extreme_threat_alerts";

            /** Preference to enable presidential alert */
            public static final @NonNull String ENABLE_CMAS_PRESIDENTIAL_PREF =
                    "enable_cmas_presidential_alerts";

            /** Preference to enable alert vibration */
            public static final @NonNull String ENABLE_ALERT_VIBRATION_PREF =
                    "enable_alert_vibrate";

            /** Preference to enable emergency alert */
            public static final @NonNull String ENABLE_EMERGENCY_PERF =
                    "enable_emergency_alerts";

            /** Preference to enable receive alerts in second language */
            public static final @NonNull String ENABLE_CMAS_IN_SECOND_LANGUAGE_PREF =
                    "receive_cmas_in_second_language";
        }

        /**
         * The subscription which received this cell broadcast message.
         * <P>Type: INTEGER</P>
         */
        public static final String SUBSCRIPTION_ID = "sub_id";

        /**
         * The slot which received this cell broadcast message.
         * <P>Type: INTEGER</P>
         */
        public static final String SLOT_INDEX = "slot_index";

        /**
         * Message geographical scope. Valid values are:
         * <ul>
         * <li>{@link android.telephony.SmsCbMessage#GEOGRAPHICAL_SCOPE_CELL_WIDE}. meaning the
         * message is for the radio service cell</li>
         * <li>{@link android.telephony.SmsCbMessage#GEOGRAPHICAL_SCOPE_CELL_WIDE_IMMEDIATE},
         * meaning the message is for the radio service cell and immediately displayed</li>
         * <li>{@link android.telephony.SmsCbMessage#GEOGRAPHICAL_SCOPE_PLMN_WIDE}, meaning the
         * message is for the PLMN (i.e. MCC/MNC)</li>
         * <li>{@link android.telephony.SmsCbMessage#GEOGRAPHICAL_SCOPE_LOCATION_AREA_WIDE},
         * meaning the message is for the location area (in GSM) or service area (in UMTS)</li>
         * </ul>
         *
         * <p>A message meant for a particular scope is automatically dismissed when the device
         * exits that scope.</p>
         * <P>Type: INTEGER</P>
         */
        public static final String GEOGRAPHICAL_SCOPE = "geo_scope";

        /**
         * Message serial number.
         * <p>
         * A 16-bit integer which identifies a particular CBS (cell
         * broadcast short message service) message. The core network is responsible for
         * allocating this value, and the value may be managed cyclically (3GPP TS 23.041 section
         * 9.2.1) once the serial message has been incremented a sufficient number of times.
         * </p>
         * <P>Type: INTEGER</P>
         */
        public static final String SERIAL_NUMBER = "serial_number";

        /**
         * PLMN (i.e. MCC/MNC) of broadcast sender. {@code SERIAL_NUMBER + PLMN + LAC + CID}
         * uniquely identifies a broadcast for duplicate detection purposes.
         * <P>Type: TEXT</P>
         */
        public static final String PLMN = "plmn";

        /**
         * Location area code (LAC).
         * <p>Code representing location area (GSM) or service area (UMTS) of broadcast sender.
         * Unused for CDMA. Only included if Geographical Scope of message is not PLMN wide (01).
         * This value is sent by the network based on the cell tower.
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
         * Service category which represents the general topic of the message.
         * <p>
         * For GSM/UMTS: message identifier (see 3GPP TS 23.041 section 9.4.1.2.2)
         * For CDMA: a 16-bit CDMA service category (see 3GPP2 C.R1001-D section 9.3)
         * </p>
         * <P>Type: INTEGER</P>
         */
        public static final String SERVICE_CATEGORY = "service_category";

        /**
         * Message language code. (See 3GPP TS 23.041 section 9.4.1.2.3 for details).
         * <P>Type: TEXT</P>
         */
        public static final String LANGUAGE_CODE = "language";

        /**
         * Dats coding scheme of the message.
         * <p>
         * The data coding scheme (dcs) value defined in 3GPP TS 23.038 section 4
         * </p>
         * <P>Type: INTEGER</P>
         */
        public static final String DATA_CODING_SCHEME = "dcs";

        /**
         * Message body.
         * <P>Type: TEXT</P>
         */
        public static final String MESSAGE_BODY = "body";

        /**
         * Message delivery time.
         * <p>This value is a system timestamp using {@link System#currentTimeMillis}</p>
         * <P>Type: INTEGER (long)</P>
         */
        public static final String DELIVERY_TIME = "date";

        /**
         * Has the message been viewed?
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String MESSAGE_READ = "read";

        /**
         * Message format ({@link android.telephony.SmsCbMessage#MESSAGE_FORMAT_3GPP} or
         * {@link android.telephony.SmsCbMessage#MESSAGE_FORMAT_3GPP2}).
         * <P>Type: INTEGER</P>
         */
        public static final String MESSAGE_FORMAT = "format";

        /**
         * Message priority.
         * <p>This includes
         * <ul>
         * <li>{@link android.telephony.SmsCbMessage#MESSAGE_PRIORITY_NORMAL}</li>
         * <li>{@link android.telephony.SmsCbMessage#MESSAGE_PRIORITY_INTERACTIVE}</li>
         * <li>{@link android.telephony.SmsCbMessage#MESSAGE_PRIORITY_URGENT}</li>
         * <li>{@link android.telephony.SmsCbMessage#MESSAGE_PRIORITY_EMERGENCY}</li>
         * </p>
         * </ul>
         * <P>Type: INTEGER</P>
         */
        public static final String MESSAGE_PRIORITY = "priority";

        /**
         * ETWS (Earthquake and Tsunami Warning System) warning type (ETWS alerts only).
         * <p>See {@link android.telephony.SmsCbEtwsInfo}</p>
         * <P>Type: INTEGER</P>
         */
        public static final String ETWS_WARNING_TYPE = "etws_warning_type";

        /**
         * ETWS (Earthquake and Tsunami Warning System, Japan only) primary message or not. The
         * primary message is sent as soon as the emergency occurs. It does not provide any
         * information except the emergency type (e.g. earthquake, tsunami). The ETWS secondary
         * message is sent afterwards and provides the details of the emergency.
         *
         * <p>See {@link android.telephony.SmsCbEtwsInfo}</p>
         * <P>Type: BOOLEAN</P>
         */
        public static final String ETWS_IS_PRIMARY = "etws_is_primary";

        /**
         * CMAS (Commercial Mobile Alert System) message class (CMAS alerts only).
         * <p>See {@link android.telephony.SmsCbCmasInfo}</p>
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
         * The timestamp in millisecond, reported by {@link System#currentTimeMillis()}, when the
         * device received the message.
         * <P>Type: BIGINT</P>
         */
        public static final String RECEIVED_TIME = "received_time";

        /**
         * The timestamp in millisecond, reported by {@link System#currentTimeMillis()}, when
         * location was checked last time. Note this is only applicable to geo-targeting message.
         * For non geo-targeting message. the field will be set to -1.
         * <P>Type: BIGINT</P>
         */
        public static final String LOCATION_CHECK_TIME = "location_check_time";
        /**
         * Indicates that whether the message has been broadcasted to the application.
         * <P>Type: BOOLEAN</P>
         */
        // TODO: deprecate this in S.
        public static final String MESSAGE_BROADCASTED = "message_broadcasted";

        /**
         * Indicates that whether the message has been displayed to the user.
         * <P>Type: BOOLEAN</P>
         */
        public static final String MESSAGE_DISPLAYED = "message_displayed";

        /**
         * The Warning Area Coordinates Elements. This element is used for geo-fencing purpose.
         *
         * The geometry and its coordinates are separated vertical bar, the first item is the
         * geometry type and the remaining items are the parameter of this geometry.
         *
         * Only circle and polygon are supported. The coordinates are represented in Horizontal
         * coordinates format.
         *
         * Coordinate encoding:
         * "latitude, longitude"
         * where -90.00000 <= latitude <= 90.00000 and -180.00000 <= longitude <= 180.00000
         *
         * Polygon encoding:
         * "polygon|lat1,lng1|lat2,lng2|...|latn,lngn"
         * lat1,lng1 ... latn,lngn are the vertices coordinate of the polygon.
         *
         * Circle encoding:
         * "circle|lat,lng|radius".
         * lat,lng is the center of the circle. The unit of circle's radius is meter.
         *
         * Example:
         * "circle|0,0|100" mean a circle which center located at (0,0) and its radius is 100 meter.
         * "polygon|0,1.5|0,1|1,1|1,0" mean a polygon has vertices (0,1.5),(0,1),(1,1),(1,0).
         *
         * There could be more than one geometry store in this field, they are separated by a
         * semicolon.
         *
         * Example:
         * "circle|0,0|100;polygon|0,0|0,1.5|1,1|1,0;circle|100.123,100|200.123"
         *
         * <P>Type: TEXT</P>
         */
        public static final String GEOMETRIES = "geometries";

        /**
         * Geo-Fencing Maximum Wait Time in second. The range of the time is [0, 255]. A device
         * shall allow to determine its position meeting operator policy. If the device is unable to
         * determine its position meeting operator policy within the GeoFencing Maximum Wait Time,
         * it shall present the alert to the user and discontinue further positioning determination
         * for the alert.
         *
         * <P>Type: INTEGER</P>
         */
        public static final String MAXIMUM_WAIT_TIME = "maximum_wait_time";

        /**
         * Query columns for instantiating com.android.cellbroadcastreceiver.CellBroadcastMessage.
         * @hide
         */
        @NonNull
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
         * {@link ServiceState} while your app is running.
         * You can also use a {@link android.app.job.JobService} to
         * ensure your app is notified of changes to the {@link Uri} even when it is not running.
         * Note, however, that using a {@link android.app.job.JobService}
         * does not guarantee timely delivery of
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
         * {@link ServiceState} while your app is running.  You can also use a
         * {@link android.app.job.JobService} to
         * ensure your app is notified of changes to the {@link Uri} even when it is not running.
         * Note, however, that using a {@link android.app.job.JobService}
         * does not guarantee timely delivery of
         * updates to the {@link Uri}.
         *
         * @param subscriptionId the subscriptionId to receive updates on
         * @return the Uri used to observe {@link ServiceState} changes
         */
        public static Uri getUriForSubscriptionId(int subscriptionId) {
            return CONTENT_URI.buildUpon().appendEncodedPath(String.valueOf(subscriptionId)).build();
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
         */
        public static final String DATA_REG_STATE = "data_reg_state";

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
         * The current network selection mode.
         * <p>
         * This is the same as {@link ServiceState#getIsManualSelection()}.
         */
        public static final String IS_MANUAL_NETWORK_SELECTION = "is_manual_network_selection";

        /**
         * The current data network type.
         * <p>
         * This is the same as {@link TelephonyManager#getDataNetworkType()}.
         */
        public static final String DATA_NETWORK_TYPE = "data_network_type";

        /**
         * An integer value indicating the current duplex mode if the radio technology is LTE,
         * LTE-CA or NR.
         * <p>
         * Valid values: {@link ServiceState#DUPLEX_MODE_UNKNOWN},
         * {@link ServiceState#DUPLEX_MODE_FDD}, {@link ServiceState#DUPLEX_MODE_TDD}.
         * <p>
         * This is the same as {@link ServiceState#getDuplexMode()}.
         */
        public static final String DUPLEX_MODE = "duplex_mode";
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
         * while your app is running. You can also use a {@link android.app.job.JobService}
         * to ensure your app
         * is notified of changes to the {@link Uri} even when it is not running.
         * Note, however, that using a {@link android.app.job.JobService} does not guarantee
         * timely delivery of updates to the {@link Uri}.
         *
         * @param subscriptionId the subscriptionId to receive updates on
         * @return the Uri used to observe carrier identity changes
         */
        public static Uri getUriForSubscriptionId(int subscriptionId) {
            return CONTENT_URI.buildUpon().appendEncodedPath(
                    String.valueOf(subscriptionId)).build();
        }

        /**
         * Generates a content {@link Uri} used to receive updates on specific carrier identity
         * change on the given subscriptionId returned by
         * {@link TelephonyManager#getSimSpecificCarrierId()}.
         * @see TelephonyManager#ACTION_SUBSCRIPTION_SPECIFIC_CARRIER_IDENTITY_CHANGED
         * <p>
         * Use this {@link Uri} with a {@link ContentObserver} to be notified of changes to the
         * specific carrier identity {@link TelephonyManager#getSimSpecificCarrierId()}
         * while your app is running. You can also use a {@link android.app.job.JobService}
         * to ensure your app
         * is notified of changes to the {@link Uri} even when it is not running.
         * Note, however, that using a {@link android.app.job.JobService} does not guarantee timely
         * delivery of updates to the {@link Uri}.
         *
         * @param subscriptionId the subscriptionId to receive updates on
         * @return the Uri used to observe specific carrier identity changes
         */
        @NonNull
        public static Uri getSpecificCarrierIdUriForSubscriptionId(int subscriptionId) {
            return Uri.withAppendedPath(Uri.withAppendedPath(CONTENT_URI, "specific"),
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
         * The specific carrier ID would be used for configuration purposes, but apps wishing to
         * know about the carrier itself should use the regular carrier ID returned by
         * {@link TelephonyManager#getSimCarrierId()}.
         *
         * @see TelephonyManager#getSimSpecificCarrierId()
         * This is not a database column, only used to notify content observers for
         * {@link #getSpecificCarrierIdUriForSubscriptionId(int)}
         */
        public static final String SPECIFIC_CARRIER_ID = "specific_carrier_id";

        /**
         * A user facing carrier name for specific carrier id {@link #SPECIFIC_CARRIER_ID}.
         * @see TelephonyManager#getSimSpecificCarrierIdName()
         * This is not a database column, only used to notify content observers for
         * {@link #getSpecificCarrierIdUriForSubscriptionId(int)}
         */
        public static final String SPECIFIC_CARRIER_ID_NAME = "specific_carrier_id_name";

        /**
         * A unique parent carrier id. The parent-child
         * relationship can be used to further differentiate a single carrier by different networks,
         * by prepaid v.s. postpaid. It's an optional field.
         * A carrier id with a valid parent_carrier_id is considered fine-grained specific carrier
         * ID, will not be returned as {@link #CARRIER_ID} but {@link #SPECIFIC_CARRIER_ID}.
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
             * Not instantiable.
             * @hide
             */
            private All() {
            }

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
            @NonNull
            public static final Uri CONTENT_URI = Uri.parse("content://carrier_id/all");
        }
    }

    /**
     * Contains SIM Information
     * @hide
     */
    public static final class SimInfo {
        /**
         * Not instantiable.
         * @hide
         */
        private SimInfo() {}

        /**
         * The {@code content://} style URI for this provider.
         * @hide
         */
        @NonNull
        public static final Uri CONTENT_URI = Uri.parse("content://telephony/siminfo");

        /**
         * TelephonyProvider unique key column name is the subscription id.
         * <P>Type: TEXT (String)</P>
         *
         * @hide
         */
        public static final String COLUMN_UNIQUE_KEY_SUBSCRIPTION_ID = "_id";

        /**
         * TelephonyProvider column name for a unique identifier for the subscription within the
         * specific subscription type. For example, it contains SIM ICC Identifier subscriptions
         * on Local SIMs. and Mac-address for Remote-SIM Subscriptions for Bluetooth devices.
         * <P>Type: TEXT (String)</P>
         *
         * @hide
         */
        public static final String COLUMN_ICC_ID = "icc_id";

        /**
         * TelephonyProvider column name for user SIM_SlOT_INDEX
         * <P>Type: INTEGER (int)</P>
         *
         * @hide
         */
        public static final String COLUMN_SIM_SLOT_INDEX = "sim_id";

        /**
         * SIM is not inserted
         * @hide
         */
        public static final int SIM_NOT_INSERTED = -1;

        /**
         * TelephonyProvider column name Subscription-type.
         * <P>Type: INTEGER (int)</P> {@link #SUBSCRIPTION_TYPE_LOCAL_SIM} for Local-SIM
         * Subscriptions, {@link #SUBSCRIPTION_TYPE_REMOTE_SIM} for Remote-SIM Subscriptions.
         * Default value is 0.
         *
         * @hide
         */
        public static final String COLUMN_SUBSCRIPTION_TYPE = "subscription_type";

        /**
         * This constant is to designate a subscription as a Local-SIM Subscription.
         * <p> A Local-SIM can be a physical SIM inserted into a sim-slot in the device, or eSIM on
         * the device.
         * </p>
         *
         * @hide
         */
        public static final int SUBSCRIPTION_TYPE_LOCAL_SIM = 0;

        /**
         * This constant is to designate a subscription as a Remote-SIM Subscription.
         * <p>
         * A Remote-SIM subscription is for a SIM on a phone connected to this device via some
         * connectivity mechanism, for example bluetooth. Similar to Local SIM, this subscription
         * can be used for SMS, Voice and data by proxying data through the connected device.
         * Certain data of the SIM, such as IMEI, are not accessible for Remote SIMs.
         * </p>
         *
         * <p>
         * A Remote-SIM is available only as long the phone stays connected to this device.
         * When the phone disconnects, Remote-SIM subscription is removed from this device and is
         * no longer known. All data associated with the subscription, such as stored SMS, call
         * logs, contacts etc, are removed from this device.
         * </p>
         *
         * <p>
         * If the phone re-connects to this device, a new Remote-SIM subscription is created for
         * the phone. The Subscription Id associated with the new subscription is different from
         * the Subscription Id of the previous Remote-SIM subscription created (and removed) for the
         * phone; i.e., new Remote-SIM subscription treats the reconnected phone as a Remote-SIM
         * that was never seen before.
         * </p>
         *
         * @hide
         */
        public static final int SUBSCRIPTION_TYPE_REMOTE_SIM = 1;

        /**
         * TelephonyProvider column name data_enabled_override_rules.
         * It's a list of rules for overriding data enabled settings. The syntax is
         * For example, "mms=nonDefault" indicates enabling data for mms in non-default
         * subscription.
         * "default=nonDefault&inVoiceCall" indicates enabling data for internet in non-default
         * subscription and while is in voice call.
         *
         * Default value is empty string.
         * @deprecated This column is no longer supported. Use
         * {@link #COLUMN_ENABLED_MOBILE_DATA_POLICIES} instead.
         * @hide
         */
        @Deprecated
        public static final String COLUMN_DATA_ENABLED_OVERRIDE_RULES =
                "data_enabled_override_rules";

        /**
         * TelephonyProvider column name enabled_mobile_data_policies.
         * A list of mobile data policies, each of which represented by an integer and joint by ",".
         *
         * Default value is empty string.
         * @hide
         */
        public static final String COLUMN_ENABLED_MOBILE_DATA_POLICIES =
                "enabled_mobile_data_policies";

        /**
         * TelephonyProvider column name for user displayed name.
         * <P>Type: TEXT (String)</P>
         *
         * @hide
         */
        public static final String COLUMN_DISPLAY_NAME = "display_name";

        /**
         * TelephonyProvider column name for the service provider name for the SIM.
         * <P>Type: TEXT (String)</P>
         *
         * @hide
         */
        public static final String COLUMN_CARRIER_NAME = "carrier_name";

        /**
         * TelephonyProvider column name for source of the user displayed name.
         * <P>Type: INT (int)</P> with one of the NAME_SOURCE_XXXX values below
         *
         * @hide
         */
        public static final String COLUMN_NAME_SOURCE = "name_source";

        /**
         * The name source is unknown.
         * @hide
         */
        public static final int NAME_SOURCE_UNKNOWN = -1;

        /** The name_source is from the carrier id. {@hide} */
        public static final int NAME_SOURCE_CARRIER_ID = 0;

        /**
         * The name_source is from SIM EF_SPN.
         * @hide
         */
        public static final int NAME_SOURCE_SIM_SPN = 1;

        /**
         * The name_source is from user input
         * @hide
         */
        public static final int NAME_SOURCE_USER_INPUT = 2;

        /**
         * The name_source is carrier (carrier app, carrier config, etc.)
         * @hide
         */
        public static final int NAME_SOURCE_CARRIER = 3;

        /**
         * The name_source is from SIM EF_PNN.
         * @hide
         */
        public static final int NAME_SOURCE_SIM_PNN = 4;

        /**
         * TelephonyProvider column name for the color of a SIM.
         * <P>Type: INTEGER (int)</P>
         *
         * @hide
         */
        public static final String COLUMN_COLOR = "color";

        /** The default color of a SIM {@hide} */
        public static final int COLOR_DEFAULT = 0;

        /**
         * TelephonyProvider column name for the phone number of a SIM.
         * <P>Type: TEXT (String)</P>
         *
         * @hide
         */
        public static final String COLUMN_NUMBER = "number";

        /**
         * TelephonyProvider column name for the number display format of a SIM.
         * <P>Type: INTEGER (int)</P>
         *
         * @hide
         */
        public static final String COLUMN_DISPLAY_NUMBER_FORMAT = "display_number_format";

        /**
         * TelephonyProvider column name for the default display format of a SIM
         * @hide
         */
        public static final int DISPLAY_NUMBER_DEFAULT = 1;

        /**
         * TelephonyProvider column name for whether data roaming is enabled.
         * <P>Type: INTEGER (int)</P>
         *
         * @hide
         */
        public static final String COLUMN_DATA_ROAMING = "data_roaming";

        /** Indicates that data roaming is enabled for a subscription {@hide} */
        public static final int DATA_ROAMING_ENABLE = 1;

        /** Indicates that data roaming is disabled for a subscription {@hide} */
        public static final int DATA_ROAMING_DISABLE = 0;

        /**
         * TelephonyProvider column name for subscription carrier id.
         * @see TelephonyManager#getSimCarrierId()
         * <p>Type: INTEGER (int) </p>
         *
         * @hide
         */
        public static final String COLUMN_CARRIER_ID = "carrier_id";

        /**
         * A comma-separated list of EHPLMNs associated with the subscription
         * <P>Type: TEXT (String)</P>
         *
         * @hide
         */
        public static final String COLUMN_EHPLMNS = "ehplmns";

        /**
         * A comma-separated list of HPLMNs associated with the subscription
         * <P>Type: TEXT (String)</P>
         *
         * @hide
         */
        public static final String COLUMN_HPLMNS = "hplmns";

        /**
         * TelephonyProvider column name for the MCC associated with a SIM, stored as a string.
         * <P>Type: TEXT (String)</P>
         *
         * @hide
         */
        public static final String COLUMN_MCC_STRING = "mcc_string";

        /**
         * TelephonyProvider column name for the MNC associated with a SIM, stored as a string.
         * <P>Type: TEXT (String)</P>
         *
         * @hide
         */
        public static final String COLUMN_MNC_STRING = "mnc_string";

        /**
         * TelephonyProvider column name for the MCC associated with a SIM.
         * <P>Type: INTEGER (int)</P>
         *
         * @hide
         */
        public static final String COLUMN_MCC = "mcc";

        /**
         * TelephonyProvider column name for the MNC associated with a SIM.
         * <P>Type: INTEGER (int)</P>
         *
         * @hide
         */
        public static final String COLUMN_MNC = "mnc";

        /**
         * TelephonyProvider column name for the iso country code associated with a SIM.
         * <P>Type: TEXT (String)</P>
         *
         * @hide
         */
        public static final String COLUMN_ISO_COUNTRY_CODE = "iso_country_code";

        /**
         * TelephonyProvider column name for the sim provisioning status associated with a SIM.
         * <P>Type: INTEGER (int)</P>
         *
         * @hide
         */
        public static final String COLUMN_SIM_PROVISIONING_STATUS = "sim_provisioning_status";

        /** The sim is provisioned {@hide} */
        public static final int SIM_PROVISIONED = 0;

        /**
         * TelephonyProvider column name for whether a subscription is embedded (that is, present on
         * an eSIM).
         * <p>Type: INTEGER (int), 1 for embedded or 0 for non-embedded.
         *
         * @hide
         */
        public static final String COLUMN_IS_EMBEDDED = "is_embedded";

        /**
         * TelephonyProvider column name for SIM card identifier. For UICC card it is the ICCID of
         * the current enabled profile on the card, while for eUICC card it is the EID of the card.
         * <P>Type: TEXT (String)</P>
         *
         * @hide
         */
        public static final String COLUMN_CARD_ID = "card_id";

        /**
         * TelephonyProvider column name for the encoded {@link UiccAccessRule}s from
         * {@link UiccAccessRule#encodeRules}. Only present if {@link #COLUMN_IS_EMBEDDED} is 1.
         * <p>TYPE: BLOB
         *
         * @hide
         */
        public static final String COLUMN_ACCESS_RULES = "access_rules";

        /**
         * TelephonyProvider column name for the encoded {@link UiccAccessRule}s from
         * {@link UiccAccessRule#encodeRules} but for the rules that come from CarrierConfigs.
         * Only present if there are access rules in CarrierConfigs
         * <p>TYPE: BLOB
         *
         * @hide
         */
        public static final String COLUMN_ACCESS_RULES_FROM_CARRIER_CONFIGS =
                "access_rules_from_carrier_configs";

        /**
         * TelephonyProvider column name identifying whether an embedded subscription is on a
         * removable card. Such subscriptions are marked inaccessible as soon as the current card
         * is removed. Otherwise, they will remain accessible unless explicitly deleted. Only
         * present if {@link #COLUMN_IS_EMBEDDED} is 1.
         * <p>TYPE: INTEGER (int), 1 for removable or 0 for non-removable.
         *
         * @hide
         */
        public static final String COLUMN_IS_REMOVABLE = "is_removable";

        /** TelephonyProvider column name for extreme threat in CB settings {@hide} */
        public static final String COLUMN_CB_EXTREME_THREAT_ALERT =
                "enable_cmas_extreme_threat_alerts";

        /** TelephonyProvider column name for severe threat in CB settings {@hide} */
        public static final String COLUMN_CB_SEVERE_THREAT_ALERT =
                "enable_cmas_severe_threat_alerts";

        /** TelephonyProvider column name for amber alert in CB settings {@hide} */
        public static final String COLUMN_CB_AMBER_ALERT = "enable_cmas_amber_alerts";

        /** TelephonyProvider column name for emergency alert in CB settings {@hide} */
        public static final String COLUMN_CB_EMERGENCY_ALERT = "enable_emergency_alerts";

        /** TelephonyProvider column name for alert sound duration in CB settings {@hide} */
        public static final String COLUMN_CB_ALERT_SOUND_DURATION = "alert_sound_duration";

        /** TelephonyProvider column name for alert reminder interval in CB settings {@hide} */
        public static final String COLUMN_CB_ALERT_REMINDER_INTERVAL = "alert_reminder_interval";

        /** TelephonyProvider column name for enabling vibrate in CB settings {@hide} */
        public static final String COLUMN_CB_ALERT_VIBRATE = "enable_alert_vibrate";

        /** TelephonyProvider column name for enabling alert speech in CB settings {@hide} */
        public static final String COLUMN_CB_ALERT_SPEECH = "enable_alert_speech";

        /** TelephonyProvider column name for ETWS test alert in CB settings {@hide} */
        public static final String COLUMN_CB_ETWS_TEST_ALERT = "enable_etws_test_alerts";

        /** TelephonyProvider column name for enable channel50 alert in CB settings {@hide} */
        public static final String COLUMN_CB_CHANNEL_50_ALERT = "enable_channel_50_alerts";

        /** TelephonyProvider column name for CMAS test alert in CB settings {@hide} */
        public static final String COLUMN_CB_CMAS_TEST_ALERT = "enable_cmas_test_alerts";

        /** TelephonyProvider column name for Opt out dialog in CB settings {@hide} */
        public static final String COLUMN_CB_OPT_OUT_DIALOG = "show_cmas_opt_out_dialog";

        /**
         * TelephonyProvider column name for enable Volte.
         *
         * If this setting is not initialized (set to -1)  then we use the Carrier Config value
         * {@link CarrierConfigManager#KEY_ENHANCED_4G_LTE_ON_BY_DEFAULT_BOOL}.
         *
         * @hide
         */
        public static final String COLUMN_ENHANCED_4G_MODE_ENABLED = "volte_vt_enabled";

        /** TelephonyProvider column name for enable VT (Video Telephony over IMS) {@hide} */
        public static final String COLUMN_VT_IMS_ENABLED = "vt_ims_enabled";

        /** TelephonyProvider column name for enable Wifi calling {@hide} */
        public static final String COLUMN_WFC_IMS_ENABLED = "wfc_ims_enabled";

        /** TelephonyProvider column name for Wifi calling mode {@hide} */
        public static final String COLUMN_WFC_IMS_MODE = "wfc_ims_mode";

        /** TelephonyProvider column name for Wifi calling mode in roaming {@hide} */
        public static final String COLUMN_WFC_IMS_ROAMING_MODE = "wfc_ims_roaming_mode";

        /** TelephonyProvider column name for enable Wifi calling in roaming {@hide} */
        public static final String COLUMN_WFC_IMS_ROAMING_ENABLED = "wfc_ims_roaming_enabled";

        /**
         * TelephonyProvider column name for determining if the user has enabled IMS RCS User
         * Capability Exchange (UCE) for this subscription.
         *
         * @hide
         */
        public static final String COLUMN_IMS_RCS_UCE_ENABLED = "ims_rcs_uce_enabled";

        /**
         * TelephonyProvider column name for determining if the user has enabled cross SIM calling
         * for this subscription.
         *
         * @hide
         */
        public static final String COLUMN_CROSS_SIM_CALLING_ENABLED = "cross_sim_calling_enabled";

        /**
         * TelephonyProvider column name for whether a subscription is opportunistic, that is,
         * whether the network it connects to is limited in functionality or coverage.
         * For example, CBRS.
         * <p>Type: INTEGER (int), 1 for opportunistic or 0 for non-opportunistic.
         *
         * @hide
         */
        public static final String COLUMN_IS_OPPORTUNISTIC = "is_opportunistic";

        /**
         * TelephonyProvider column name for group ID. Subscriptions with same group ID
         * are considered bundled together, and should behave as a single subscription at
         * certain scenarios.
         *
         * @hide
         */
        public static final String COLUMN_GROUP_UUID = "group_uuid";

        /**
         * TelephonyProvider column name for group owner. It's the package name who created
         * the subscription group.
         *
         * @hide
         */
        public static final String COLUMN_GROUP_OWNER = "group_owner";

        /**
         * TelephonyProvider column name for whether a subscription is metered or not, that is,
         * whether the network it connects to charges for subscription or not. For example, paid
         * CBRS or unpaid.
         *
         * @hide
         */
        public static final String COLUMN_IS_METERED = "is_metered";

        /**
         * TelephonyProvider column name for the profile class of a subscription
         * Only present if {@link #COLUMN_IS_EMBEDDED} is 1.
         * <P>Type: INTEGER (int)</P>
         *
         * @hide
         */
        public static final String COLUMN_PROFILE_CLASS = "profile_class";

        /**
         * TelephonyProvider column name for the port index of the active UICC port.
         * <P>Type: INTEGER (int)</P>
         * @hide
         */
        public static final String COLUMN_PORT_INDEX = "port_index";

        /**
         * A testing profile can be pre-loaded or downloaded onto
         * the eUICC and provides connectivity to test equipment
         * for the purpose of testing the device and the eUICC. It
         * is not intended to store any operator credentials.
         *
         * @hide
         */
        public static final int PROFILE_CLASS_TESTING = 0;

        /**
         * A provisioning profile is pre-loaded onto the eUICC and
         * provides connectivity to a mobile network solely for the
         * purpose of provisioning profiles.
         *
         * @hide
         */
        public static final int PROFILE_CLASS_PROVISIONING = 1;

        /**
         * An operational profile can be pre-loaded or downloaded
         * onto the eUICC and provides services provided by the
         * operator.
         *
         * @hide
         */
        public static final int PROFILE_CLASS_OPERATIONAL = 2;

        /**
         * The profile class is unset. This occurs when profile class
         * info is not available. The subscription either has no profile
         * metadata or the profile metadata did not encode profile class.
         *
         * @hide
         */
        public static final int PROFILE_CLASS_UNSET = -1;

        /**
         * IMSI (International Mobile Subscriber Identity).
         * <P>Type: TEXT </P>
         *
         * @hide
         */
        public static final String COLUMN_IMSI = "imsi";

        /**
         * Whether uicc applications is set to be enabled or disabled. By default it's enabled.
         * @hide
         */
        public static final String COLUMN_UICC_APPLICATIONS_ENABLED = "uicc_applications_enabled";

        /**
         * TelephonyProvider column name for allowed network types. Indicate which network types
         * are allowed. Default is -1.
         * <P>Type: BIGINT (long) </P>
         *
         * @hide
         */
        public static final String COLUMN_ALLOWED_NETWORK_TYPES = "allowed_network_types";

        /**
         * TelephonyProvider column name for allowed network types with all of reasons. Indicate
         * which network types are allowed for
         * {@link TelephonyManager#ALLOWED_NETWORK_TYPES_REASON_USER},
         * {@link TelephonyManager#ALLOWED_NETWORK_TYPES_REASON_POWER},
         * {@link TelephonyManager#ALLOWED_NETWORK_TYPES_REASON_CARRIER},
         * {@link TelephonyManager#ALLOWED_NETWORK_TYPES_REASON_ENABLE_2G}.
         * <P>Type: TEXT </P>
         *
         * @hide
         */
        public static final String COLUMN_ALLOWED_NETWORK_TYPES_FOR_REASONS =
                "allowed_network_types_for_reasons";

        /**
         * TelephonyProvider column name for RCS configuration.
         * <p>TYPE: BLOB
         *
         * @hide
         */
        public static final String COLUMN_RCS_CONFIG = "rcs_config";

        /**
         * TelephonyProvider column name for device to device sharing status.
         *
         * @hide
         */
        public static final String COLUMN_D2D_STATUS_SHARING = "d2d_sharing_status";

        /**
         * TelephonyProvider column name for VoIMS provisioning. Default is 0.
         * <P>Type: INTEGER </P>
         *
         * @hide
         */
        public static final String COLUMN_VOIMS_OPT_IN_STATUS = "voims_opt_in_status";

        /**
         * TelephonyProvider column name for information selected contacts that allow device to
         * device sharing.
         *
         * @hide
         */
        public static final String COLUMN_D2D_STATUS_SHARING_SELECTED_CONTACTS =
                "d2d_sharing_contacts";

        /**
        * TelephonyProvider column name for NR Advanced calling
        * Determines if the user has enabled VoNR settings for this subscription.
        *
        * @hide
        */
        public static final String COLUMN_NR_ADVANCED_CALLING_ENABLED =
                "nr_advanced_calling_enabled";

        /**
         * TelephonyProvider column name for the phone number from source CARRIER
         *
         * @hide
         */
        public static final String COLUMN_PHONE_NUMBER_SOURCE_CARRIER =
                "phone_number_source_carrier";

        /**
         * TelephonyProvider column name for the phone number from source IMS
         *
         * @hide
         */
        public static final String COLUMN_PHONE_NUMBER_SOURCE_IMS =
                "phone_number_source_ims";

        /**
         * TelephonyProvider column name for last used TP - message Reference
         *
         * @hide
         */
        public static final String COLUMN_TP_MESSAGE_REF = "tp_message_ref";

        /**
         * TelephonyProvider column name for the device's preferred usage setting.
         *
         * @hide
         */
        public static final String COLUMN_USAGE_SETTING = "usage_setting";

        /**
         * TelephonyProvider column name for user handle associated with this sim.
         *
         * @hide
         */
        public static final String COLUMN_USER_HANDLE = "user_handle";

        /**
         * TelephonyProvider column name for satellite enabled.
         * By default, it's disabled.
         *
         * @hide
         */
        public static final String COLUMN_SATELLITE_ENABLED = "satellite_enabled";

        /**
         * TelephonyProvider column name for satellite attach enabled for carrier. The value of this
         * column is set based on user settings.
         * By default, it's enabled.
         *
         * @hide
         */
        public static final String COLUMN_SATELLITE_ATTACH_ENABLED_FOR_CARRIER =
                "satellite_attach_enabled_for_carrier";

        /**
         * TelephonyProvider column name to identify eSIM profile of a non-terrestrial network.
         * By default, it's disabled.
         *
         * @hide
         */
        public static final String COLUMN_IS_NTN = "is_ntn";

        /**
         * TelephonyProvider column name for transferred status
         *
         * @hide
         */
        public static final String COLUMN_TRANSFER_STATUS = "transfer_status";

        /**
         * TelephonyProvider column name to indicate the service capability bitmasks.
         *
         * @hide
         */
        public static final String COLUMN_SERVICE_CAPABILITIES = "service_capabilities";

        /** All columns in {@link SimInfo} table. */
        private static final List<String> ALL_COLUMNS = List.of(
                COLUMN_UNIQUE_KEY_SUBSCRIPTION_ID,
                COLUMN_ICC_ID,
                COLUMN_SIM_SLOT_INDEX,
                COLUMN_DISPLAY_NAME,
                COLUMN_CARRIER_NAME,
                COLUMN_NAME_SOURCE,
                COLUMN_COLOR,
                COLUMN_NUMBER,
                COLUMN_DISPLAY_NUMBER_FORMAT,
                COLUMN_DATA_ROAMING,
                COLUMN_MCC,
                COLUMN_MNC,
                COLUMN_MCC_STRING,
                COLUMN_MNC_STRING,
                COLUMN_EHPLMNS,
                COLUMN_HPLMNS,
                COLUMN_SIM_PROVISIONING_STATUS,
                COLUMN_IS_EMBEDDED,
                COLUMN_CARD_ID,
                COLUMN_ACCESS_RULES,
                COLUMN_ACCESS_RULES_FROM_CARRIER_CONFIGS,
                COLUMN_IS_REMOVABLE,
                COLUMN_CB_EXTREME_THREAT_ALERT,
                COLUMN_CB_SEVERE_THREAT_ALERT,
                COLUMN_CB_AMBER_ALERT,
                COLUMN_CB_EMERGENCY_ALERT,
                COLUMN_CB_ALERT_SOUND_DURATION,
                COLUMN_CB_ALERT_REMINDER_INTERVAL,
                COLUMN_CB_ALERT_VIBRATE,
                COLUMN_CB_ALERT_SPEECH,
                COLUMN_CB_ETWS_TEST_ALERT,
                COLUMN_CB_CHANNEL_50_ALERT,
                COLUMN_CB_CMAS_TEST_ALERT,
                COLUMN_CB_OPT_OUT_DIALOG,
                COLUMN_ENHANCED_4G_MODE_ENABLED,
                COLUMN_VT_IMS_ENABLED,
                COLUMN_WFC_IMS_ENABLED,
                COLUMN_WFC_IMS_MODE,
                COLUMN_WFC_IMS_ROAMING_MODE,
                COLUMN_WFC_IMS_ROAMING_ENABLED,
                COLUMN_IS_OPPORTUNISTIC,
                COLUMN_GROUP_UUID,
                COLUMN_IS_METERED,
                COLUMN_ISO_COUNTRY_CODE,
                COLUMN_CARRIER_ID,
                COLUMN_PROFILE_CLASS,
                COLUMN_SUBSCRIPTION_TYPE,
                COLUMN_GROUP_OWNER,
                COLUMN_DATA_ENABLED_OVERRIDE_RULES,
                COLUMN_ENABLED_MOBILE_DATA_POLICIES,
                COLUMN_IMSI,
                COLUMN_UICC_APPLICATIONS_ENABLED,
                COLUMN_ALLOWED_NETWORK_TYPES,
                COLUMN_IMS_RCS_UCE_ENABLED,
                COLUMN_CROSS_SIM_CALLING_ENABLED,
                COLUMN_RCS_CONFIG,
                COLUMN_ALLOWED_NETWORK_TYPES_FOR_REASONS,
                COLUMN_D2D_STATUS_SHARING,
                COLUMN_VOIMS_OPT_IN_STATUS,
                COLUMN_D2D_STATUS_SHARING_SELECTED_CONTACTS,
                COLUMN_NR_ADVANCED_CALLING_ENABLED,
                COLUMN_PHONE_NUMBER_SOURCE_CARRIER,
                COLUMN_PHONE_NUMBER_SOURCE_IMS,
                COLUMN_PORT_INDEX,
                COLUMN_USAGE_SETTING,
                COLUMN_TP_MESSAGE_REF,
                COLUMN_USER_HANDLE,
                COLUMN_SATELLITE_ENABLED,
                COLUMN_SATELLITE_ATTACH_ENABLED_FOR_CARRIER,
                COLUMN_IS_NTN,
                COLUMN_SERVICE_CAPABILITIES,
                COLUMN_TRANSFER_STATUS
        );

        /**
         * @return All columns in {@link SimInfo} table.
         *
         * @hide
         */
        @NonNull
        public static List<String> getAllColumns() {
            return ALL_COLUMNS;
        }
    }

    /**
     * Stores incoming satellite datagrams.
     * @hide
     */
    public static final class SatelliteDatagrams {
        /**
         * Not instantiable.
         * @hide
         */
        private SatelliteDatagrams() {}

        /**
         * Provider name for Satellite Datagrams table.
         */
        public static final String PROVIDER_NAME = "satellite";

        /**
         * Table name for Satellite Datagrams table.
         */
        public static final String TABLE_NAME = "incoming_datagrams";

        /**
         * URL for satellite incoming datagrams table.
         */
        private static final String URL = "content://" + PROVIDER_NAME + "/" + TABLE_NAME;

        /**
         * The {@code content://} style URI for this provider.
         * @hide
         */
        public static final Uri CONTENT_URI = Uri.parse(URL);

        /**
         * SatelliteProvider unique key column name is the datagram id.
         * <P>Type: INTEGER (int)</P>
         * @hide
         */
        public static final String COLUMN_UNIQUE_KEY_DATAGRAM_ID = "datagram_id";

        /**
         * SatelliteProvider column name for storing datagram.
         * <p>TYPE: BLOB
         * @hide
         */
        public static final String COLUMN_DATAGRAM = "datagram";

        /** All columns in {@link SatelliteDatagrams} table. */
        private static final List<String> ALL_COLUMNS = List.of(
                COLUMN_UNIQUE_KEY_DATAGRAM_ID,
                COLUMN_DATAGRAM
        );

        /**
         * @return All columns in {@link SatelliteDatagrams} table.
         * @hide
         */
        @NonNull
        public static List<String> getAllColumns() {
            return ALL_COLUMNS;
        }
    }
}
