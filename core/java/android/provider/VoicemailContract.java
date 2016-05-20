/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.provider;

import android.Manifest;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CallLog.Calls;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.Voicemail;

import java.util.List;

/**
 * The contract between the voicemail provider and applications. Contains
 * definitions for the supported URIs and columns.
 *
 * <P>The content providers exposes two tables through this interface:
 * <ul>
 *   <li> Voicemails table: This stores the actual voicemail records. The
 *   columns and URIs for accessing this table are defined by the
 *   {@link Voicemails} class.
 *   </li>
 *   <li> Status table: This provides a way for the voicemail source application
 *   to convey its current state to the system. The columns and URIS for
 *   accessing this table are defined by the {@link Status} class.
 *   </li>
 * </ul>
 *
 * <P> The minimum permission needed to access this content provider is
 * {@link Manifest.permission#ADD_VOICEMAIL}
 *
 * <P>Voicemails are inserted by what is called as a "voicemail source"
 * application, which is responsible for syncing voicemail data between a remote
 * server and the local voicemail content provider. "voicemail source"
 * application should always set the {@link #PARAM_KEY_SOURCE_PACKAGE} in the
 * URI to identify its package.
 *
 * <P>In addition to the {@link ContentObserver} notifications the voicemail
 * provider also generates broadcast intents to notify change for applications
 * that are not active and therefore cannot listen to ContentObserver
 * notifications. Broadcast intents with following actions are generated:
 * <ul>
 *   <li> {@link #ACTION_NEW_VOICEMAIL} is generated for each new voicemail
 *   inserted.
 *   </li>
 *   <li> {@link Intent#ACTION_PROVIDER_CHANGED} is generated for any change
 *    made into the database, including new voicemail.
 *   </li>
 * </ul>
 */
public class VoicemailContract {
    /** Not instantiable. */
    private VoicemailContract() {
    }

    /** The authority used by the voicemail provider. */
    public static final String AUTHORITY = "com.android.voicemail";
    /**
     * Parameter key used in the URI to specify the voicemail source package name.
     * <p> This field must be set in all requests that originate from a voicemail source.
     */
    public static final String PARAM_KEY_SOURCE_PACKAGE = "source_package";

    /** Broadcast intent when a new voicemail record is inserted. */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_NEW_VOICEMAIL = "android.intent.action.NEW_VOICEMAIL";

    /**
     * Broadcast intent to request a voicemail source to fetch voicemail content of a specific
     * voicemail from the remote server. The voicemail to fetch is specified by the data uri
     * of the intent.
     * <p>
     * All voicemail sources are expected to handle this event. After storing the content
     * the application should also set {@link Voicemails#HAS_CONTENT} to 1;
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_FETCH_VOICEMAIL = "android.intent.action.FETCH_VOICEMAIL";

    /**
     * Broadcast intent to request all voicemail sources to perform a sync with the remote server.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_SYNC_VOICEMAIL = "android.provider.action.SYNC_VOICEMAIL";

    /**
     * Extra included in {@link Intent#ACTION_PROVIDER_CHANGED} broadcast intents to indicate if the
     * receiving package made this change.
     */
    public static final String EXTRA_SELF_CHANGE = "com.android.voicemail.extra.SELF_CHANGE";

    /**
     * Name of the source package field, which must be same across all voicemail related tables.
     * This is an internal field.
     * @hide
     */
    public static final String SOURCE_PACKAGE_FIELD = "source_package";

    /** Defines fields exposed through the /voicemail path of this content provider. */
    public static final class Voicemails implements BaseColumns, OpenableColumns {
        /** Not instantiable. */
        private Voicemails() {
        }

        /** URI to insert/retrieve voicemails. */
        public static final Uri CONTENT_URI =
            Uri.parse("content://" + AUTHORITY + "/voicemail");

        /** The MIME type for a collection of voicemails. */
        public static final String DIR_TYPE = "vnd.android.cursor.dir/voicemails";

        /** The MIME type for a single voicemail. */
        public static final String ITEM_TYPE = "vnd.android.cursor.item/voicemail";

        /**
         * Phone number of the voicemail sender.
         * <P>Type: TEXT</P>
         */
        public static final String NUMBER = Calls.NUMBER;
        /**
         * The date the voicemail was sent, in milliseconds since the epoch
         * <P>Type: INTEGER (long)</P>
         */
        public static final String DATE = Calls.DATE;
        /**
         * The duration of the voicemail in seconds.
         * <P>Type: INTEGER (long)</P>
         */
        public static final String DURATION = Calls.DURATION;
        /**
         * Whether this item has been read or otherwise consumed by the user.
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String IS_READ = Calls.IS_READ;
        /**
         * The mail box state of the voicemail. This field is currently not used by the system.
         * <P> Possible values: {@link #STATE_INBOX}, {@link #STATE_DELETED},
         * {@link #STATE_UNDELETED}.
         * <P>Type: INTEGER</P>
         * @hide
         */
        public static final String STATE = "state";
        /**
         * Value of {@link #STATE} when the voicemail is in inbox.
         * @hide
         */
        public static int STATE_INBOX = 0;
        /**
         * Value of {@link #STATE} when the voicemail has been marked as deleted.
         * @hide
         */
        public static int STATE_DELETED = 1;
        /**
         * Value of {@link #STATE} when the voicemail has marked as undeleted.
         * @hide
         */
        public static int STATE_UNDELETED = 2;
        /**
         * Package name of the source application that inserted the voicemail.
         * <P>Type: TEXT</P>
         */
        public static final String SOURCE_PACKAGE = SOURCE_PACKAGE_FIELD;
        /**
         * Application-specific data available to the source application that
         * inserted the voicemail. This is typically used to store the source
         * specific message id to identify this voicemail on the remote
         * voicemail server.
         * <P>Type: TEXT</P>
         * <P> Note that this is NOT the voicemail media content data.
         */
        public static final String SOURCE_DATA = "source_data";
        /**
         * Whether the media content for this voicemail is available for
         * consumption.
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String HAS_CONTENT = "has_content";
        /**
         * MIME type of the media content for the voicemail.
         * <P>Type: TEXT</P>
         */
        public static final String MIME_TYPE = "mime_type";
        /**
         * The transcription of the voicemail entry. This will only be populated if the voicemail
         * entry has a valid transcription.
         * <P>Type: TEXT</P>
         */
        public static final String TRANSCRIPTION = "transcription";
        /**
         * Path to the media content file. Internal only field.
         * @hide
         */
        public static final String _DATA = "_data";

        // Note: PHONE_ACCOUNT_* constant values are "subscription_*" due to a historic naming
        // that was encoded into call log databases.

        /**
         * The {@link ComponentName} of the {@link PhoneAccount} in string form. The
         * {@link PhoneAccount} of the voicemail is used to differentiate voicemails from different
         * sources.
         * <P>Type: TEXT</P>
         */
        public static final String PHONE_ACCOUNT_COMPONENT_NAME = "subscription_component_name";

        /**
         * The identifier of a {@link PhoneAccount} that is unique to a specified
         * {@link ComponentName}. The {@link PhoneAccount} of the voicemail is used to differentiate
         * voicemails from different sources.
         * <P>Type: TEXT</P>
         */
        public static final String PHONE_ACCOUNT_ID = "subscription_id";

        /**
         * Flag used to indicate that local, unsynced changes are present.
         * Currently, this is used to indicate that the voicemail was read or deleted.
         * The value will be 1 if dirty is true, 0 if false.
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String DIRTY = "dirty";

        /**
         * Flag used to indicate that the voicemail was deleted but not synced to the server.
         * A deleted row should be ignored.
         * The value will be 1 if deleted is true, 0 if false.
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String DELETED = "deleted";

        /**
         * The date the row is last inserted, updated, or marked as deleted, in milliseconds
         * since the epoch. Read only.
         * <P>Type: INTEGER (long)</P>
         */
        public static final String LAST_MODIFIED = "last_modified";

        /**
         * A convenience method to build voicemail URI specific to a source package by appending
         * {@link VoicemailContract#PARAM_KEY_SOURCE_PACKAGE} param to the base URI.
         */
        public static Uri buildSourceUri(String packageName) {
            return Voicemails.CONTENT_URI.buildUpon()
                    .appendQueryParameter(PARAM_KEY_SOURCE_PACKAGE, packageName)
                    .build();
        }

        /**
         * Inserts a new voicemail into the voicemail content provider.
         *
         * @param context The context of the app doing the inserting
         * @param voicemail Data to be inserted
         * @return {@link Uri} of the newly inserted {@link Voicemail}
         *
         * @hide
         */
        public static Uri insert(Context context, Voicemail voicemail) {
            ContentResolver contentResolver = context.getContentResolver();
            ContentValues contentValues = getContentValues(voicemail);
            return contentResolver.insert(buildSourceUri(context.getPackageName()), contentValues);
        }

        /**
         * Inserts a list of voicemails into the voicemail content provider.
         *
         * @param context The context of the app doing the inserting
         * @param voicemails Data to be inserted
         * @return the number of voicemails inserted
         *
         * @hide
         */
        public static int insert(Context context, List<Voicemail> voicemails) {
            ContentResolver contentResolver = context.getContentResolver();
            int count = voicemails.size();
            for (int i = 0; i < count; i++) {
                ContentValues contentValues = getContentValues(voicemails.get(i));
                contentResolver.insert(buildSourceUri(context.getPackageName()), contentValues);
            }
            return count;
        }

        /**
         * Clears all voicemails accessible to this voicemail content provider for the calling
         * package. By default, a package only has permission to delete voicemails it inserted.
         *
         * @return the number of voicemails deleted
         *
         * @hide
         */
        public static int deleteAll(Context context) {
            return context.getContentResolver().delete(
                    buildSourceUri(context.getPackageName()), "", new String[0]);
        }

        /**
         * Maps structured {@link Voicemail} to {@link ContentValues} in content provider.
         */
        private static ContentValues getContentValues(Voicemail voicemail) {
            ContentValues contentValues = new ContentValues();
            contentValues.put(Voicemails.DATE, String.valueOf(voicemail.getTimestampMillis()));
            contentValues.put(Voicemails.NUMBER, voicemail.getNumber());
            contentValues.put(Voicemails.DURATION, String.valueOf(voicemail.getDuration()));
            contentValues.put(Voicemails.SOURCE_PACKAGE, voicemail.getSourcePackage());
            contentValues.put(Voicemails.SOURCE_DATA, voicemail.getSourceData());
            contentValues.put(Voicemails.IS_READ, voicemail.isRead() ? 1 : 0);

            PhoneAccountHandle phoneAccount = voicemail.getPhoneAccount();
            if (phoneAccount != null) {
                contentValues.put(Voicemails.PHONE_ACCOUNT_COMPONENT_NAME,
                        phoneAccount.getComponentName().flattenToString());
                contentValues.put(Voicemails.PHONE_ACCOUNT_ID, phoneAccount.getId());
            }

            if (voicemail.getTranscription() != null) {
                contentValues.put(Voicemails.TRANSCRIPTION, voicemail.getTranscription());
            }

            return contentValues;
        }
    }

    /** Defines fields exposed through the /status path of this content provider. */
    public static final class Status implements BaseColumns {
        /** URI to insert/retrieve status of voicemail source. */
        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/status");
        /** The MIME type for a collection of voicemail source statuses. */
        public static final String DIR_TYPE = "vnd.android.cursor.dir/voicemail.source.status";
        /** The MIME type for a single voicemail source status entry. */
        public static final String ITEM_TYPE = "vnd.android.cursor.item/voicemail.source.status";

        /** Not instantiable. */
        private Status() {
        }
        /**
         * The package name of the voicemail source. There can only be a one entry per account
         * per source.
         * <P>Type: TEXT</P>
         */
        public static final String SOURCE_PACKAGE = SOURCE_PACKAGE_FIELD;

        // Note: Multiple entries may exist for a single source if they are differentiated by the
        // PHONE_ACCOUNT_* fields.

        /**
         * The {@link ComponentName} of the {@link PhoneAccount} in string form. The
         * {@link PhoneAccount} differentiates voicemail sources from the same package.
         * <P>Type: TEXT</P>
         */
        public static final String PHONE_ACCOUNT_COMPONENT_NAME = "phone_account_component_name";

        /**
         * The identifier of a {@link PhoneAccount} that is unique to a specified component. The
         * {@link PhoneAccount} differentiates voicemail sources from the same package.
         * <P>Type: TEXT</P>
         */
        public static final String PHONE_ACCOUNT_ID = "phone_account_id";

        /**
         * The URI to call to invoke source specific voicemail settings screen. On a user request
         * to setup voicemail an intent with action VIEW with this URI will be fired by the system.
         * <P>Type: TEXT</P>
         */
        public static final String SETTINGS_URI = "settings_uri";
        /**
         * The URI to call when the user requests to directly access the voicemail from the remote
         * server. In case of an IVR voicemail system this is typically set to the the voicemail
         * number specified using a tel:/ URI.
         * <P>Type: TEXT</P>
         */
        public static final String VOICEMAIL_ACCESS_URI = "voicemail_access_uri";
        /**
         * The configuration state of the voicemail source.
         * <P> Possible values:
         * {@link #CONFIGURATION_STATE_OK},
         * {@link #CONFIGURATION_STATE_NOT_CONFIGURED},
         * {@link #CONFIGURATION_STATE_CAN_BE_CONFIGURED}
         * <P>Type: INTEGER</P>
         */
        public static final String CONFIGURATION_STATE = "configuration_state";
        /**
         * Value of {@link #CONFIGURATION_STATE} passed into
         * {@link #setStatus(Context, PhoneAccountHandle, int, int, int)} to indicate that the
         * {@link #CONFIGURATION_STATE} field is not to be changed
         *
         * @hide
         */
        public static final int CONFIGURATION_STATE_IGNORE = -1;
        /** Value of {@link #CONFIGURATION_STATE} to indicate an all OK configuration status. */
        public static final int CONFIGURATION_STATE_OK = 0;
        /**
         * Value of {@link #CONFIGURATION_STATE} to indicate the visual voicemail is not
         * yet configured on this device.
         */
        public static final int CONFIGURATION_STATE_NOT_CONFIGURED = 1;
        /**
         * Value of {@link #CONFIGURATION_STATE} to indicate the visual voicemail is not
         * yet configured on this device but can be configured by the user.
         * <p> This state must be used when the source has verified that the current user can be
         * upgraded to visual voicemail and would like to show a set up invitation message.
         */
        public static final int CONFIGURATION_STATE_CAN_BE_CONFIGURED = 2;
        /**
         * The data channel state of the voicemail source. This the channel through which the source
         * pulls voicemail data from a remote server.
         * <P> Possible values:
         * {@link #DATA_CHANNEL_STATE_OK},
         * {@link #DATA_CHANNEL_STATE_NO_CONNECTION}
         * </P>
         * <P>Type: INTEGER</P>
         */
        public static final String DATA_CHANNEL_STATE = "data_channel_state";
        /**
         * Value of {@link #DATA_CHANNEL_STATE} passed into
         * {@link #setStatus(Context, PhoneAccountHandle, int, int, int)} to indicate that the
         * {@link #DATA_CHANNEL_STATE} field is not to be changed
         *
         * @hide
         */
        public static final int DATA_CHANNEL_STATE_IGNORE = -1;
        /**
         *  Value of {@link #DATA_CHANNEL_STATE} to indicate that data channel is working fine.
         */
        public static final int DATA_CHANNEL_STATE_OK = 0;
        /**
         *  Value of {@link #DATA_CHANNEL_STATE} to indicate that data channel failed to find a
         *  suitable network to connect to the server.
         */
        public static final int DATA_CHANNEL_STATE_NO_CONNECTION = 1;
        /**
         *  Value of {@link #DATA_CHANNEL_STATE} to indicate that data channel failed to find a
         *  suitable network to connect to the server, and the carrier requires using cellular
         *  data network to connect to the server.
         */
        public static final int DATA_CHANNEL_STATE_NO_CONNECTION_CELLULAR_REQUIRED = 2;
        /**
         *  Value of {@link #DATA_CHANNEL_STATE} to indicate that data channel received incorrect
         *  settings or credentials to connect to the server
         */
        public static final int DATA_CHANNEL_STATE_BAD_CONFIGURATION = 3;
        /**
         *  Value of {@link #DATA_CHANNEL_STATE} to indicate that a error has occurred in the data
         *  channel while communicating with the server
         */
        public static final int DATA_CHANNEL_STATE_COMMUNICATION_ERROR = 4;
        /**
         *  Value of {@link #DATA_CHANNEL_STATE} to indicate that the server reported an internal
         *  error to the data channel.
         */
        public static final int DATA_CHANNEL_STATE_SERVER_ERROR = 5;
        /**
         *  Value of {@link #DATA_CHANNEL_STATE} to indicate that while there is a suitable network,
         *  the data channel is unable to establish a connection with the server.
         */
        public static final int DATA_CHANNEL_STATE_SERVER_CONNECTION_ERROR = 6;

        /**
         * The notification channel state of the voicemail source. This is the channel through which
         * the source gets notified of new voicemails on the remote server.
         * <P> Possible values:
         * {@link #NOTIFICATION_CHANNEL_STATE_OK},
         * {@link #NOTIFICATION_CHANNEL_STATE_NO_CONNECTION},
         * {@link #NOTIFICATION_CHANNEL_STATE_MESSAGE_WAITING}
         * </P>
         * <P>Type: INTEGER</P>
         */
        public static final String NOTIFICATION_CHANNEL_STATE = "notification_channel_state";
        /**
         * Value of {@link #NOTIFICATION_CHANNEL_STATE} passed into
         * {@link #setStatus(Context, PhoneAccountHandle, int, int, int)} to indicate that the
         * {@link #NOTIFICATION_CHANNEL_STATE} field is not to be changed
         *
         * @hide
         */
        public static final int NOTIFICATION_CHANNEL_STATE_IGNORE = -1;
        /**
         * Value of {@link #NOTIFICATION_CHANNEL_STATE} to indicate that the notification channel is
         * working fine.
         */
        public static final int NOTIFICATION_CHANNEL_STATE_OK = 0;
        /**
         * Value of {@link #NOTIFICATION_CHANNEL_STATE} to indicate that the notification channel
         * connection is not working.
         */
        public static final int NOTIFICATION_CHANNEL_STATE_NO_CONNECTION = 1;
        /**
         * Value of {@link #NOTIFICATION_CHANNEL_STATE} to indicate that there are messages waiting
         * on the server but the details are not known.
         * <p> Use this state when the notification can only tell that there are pending messages on
         * the server but no details of the sender/time etc are known.
         */
        public static final int NOTIFICATION_CHANNEL_STATE_MESSAGE_WAITING = 2;

        /**
         * Amount of resource that is used by existing voicemail in the visual voicemail inbox,
         * or {@link #QUOTA_UNAVAILABLE} if the quota has never been updated before. This value is
         * used to inform the client the situation on the remote server. Unit is not specified.
         * <P>Type: INTEGER</P>
         */
        public static final String QUOTA_OCCUPIED = "quota_occupied";

        /**
         * Total resource in the visual voicemail inbox that can be used, or
         * {@link #QUOTA_UNAVAILABLE} if server either has unlimited quota or does not provide quota
         * information. This value is used to inform the client the situation on the remote server.
         * Unit is not specified.
         * <P>Type: INTEGER</P>
         */
        public static final String QUOTA_TOTAL = "quota_total";

        /**
         * Value for {@link #QUOTA_OCCUPIED} and {@link #QUOTA_TOTAL} to indicate that no
         * information is available.
         */
        public static final int QUOTA_UNAVAILABLE = -1;

        /**
         * A convenience method to build status URI specific to a source package by appending
         * {@link VoicemailContract#PARAM_KEY_SOURCE_PACKAGE} param to the base URI.
         */
        public static Uri buildSourceUri(String packageName) {
            return Status.CONTENT_URI.buildUpon()
                    .appendQueryParameter(PARAM_KEY_SOURCE_PACKAGE, packageName).build();
        }

        /**
         * A helper method to set the status of a voicemail source.
         *
         * @param context The context from the package calling the method. This will be the source.
         * @param accountHandle The handle for the account the source is associated with.
         * @param configurationState See {@link Status#CONFIGURATION_STATE}
         * @param dataChannelState See {@link Status#DATA_CHANNEL_STATE}
         * @param notificationChannelState See {@link Status#NOTIFICATION_CHANNEL_STATE}
         *
         * @hide
         */
        public static void setStatus(Context context, PhoneAccountHandle accountHandle,
                int configurationState, int dataChannelState, int notificationChannelState) {
            ContentValues values = new ContentValues();
            values.put(Status.PHONE_ACCOUNT_COMPONENT_NAME,
                    accountHandle.getComponentName().flattenToString());
            values.put(Status.PHONE_ACCOUNT_ID, accountHandle.getId());
            if(configurationState != CONFIGURATION_STATE_IGNORE) {
                values.put(Status.CONFIGURATION_STATE, configurationState);
            }
            if(dataChannelState != DATA_CHANNEL_STATE_IGNORE) {
                values.put(Status.DATA_CHANNEL_STATE, dataChannelState);
            }
            if(notificationChannelState != NOTIFICATION_CHANNEL_STATE_IGNORE) {
                values.put(Status.NOTIFICATION_CHANNEL_STATE, notificationChannelState);
            }
            ContentResolver contentResolver = context.getContentResolver();
            Uri statusUri = buildSourceUri(context.getPackageName());
            contentResolver.insert(statusUri, values);
        }

        /**
         * A helper method to set the quota of a voicemail source. Unit is unspecified.
         *
         * @param context The context from the package calling the method. This will be the source.
         * @param accountHandle The handle for the account the source is associated with.
         * @param occupied See {@link Status#QUOTA_OCCUPIED}
         * @param total See {@link Status#QUOTA_TOTAL}
         *
         * @hide
         */
        public static void setQuota(Context context, PhoneAccountHandle accountHandle, int occupied,
                int total) {
            if (occupied == QUOTA_UNAVAILABLE && total == QUOTA_UNAVAILABLE) {
                return;
            }
            ContentValues values = new ContentValues();
            values.put(Status.PHONE_ACCOUNT_COMPONENT_NAME,
                    accountHandle.getComponentName().flattenToString());
            values.put(Status.PHONE_ACCOUNT_ID, accountHandle.getId());
            if (occupied != QUOTA_UNAVAILABLE) {
                values.put(Status.QUOTA_OCCUPIED,occupied);
            }
            if (total != QUOTA_UNAVAILABLE) {
                values.put(Status.QUOTA_TOTAL,total);
            }

            ContentResolver contentResolver = context.getContentResolver();
            Uri statusUri = buildSourceUri(context.getPackageName());
            contentResolver.insert(statusUri, values);
        }
    }
}
