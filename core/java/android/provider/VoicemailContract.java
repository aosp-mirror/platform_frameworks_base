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
import android.content.Intent;
import android.database.ContentObserver;
import android.net.Uri;
import android.provider.CallLog.Calls;

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
 * {@link Manifest.permission#READ_WRITE_OWN_VOICEMAIL}
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
    public static final class Voicemails implements BaseColumns {
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
         * Whether this is a new voicemail (i.e. has not been heard).
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String NEW = Calls.NEW;
        /**
         * Whether this item has been read or otherwise consumed by the user.
         * <P>Type: INTEGER (boolean)</P>
         * @hide
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
         * Path to the media content file. Internal only field.
         * @hide
         */
        public static final String _DATA = "_data";

        /**
         * A convenience method to build voicemail URI specific to a source package by appending
         * {@link VoicemailContract#PARAM_KEY_SOURCE_PACKAGE} param to the base URI.
         */
        public static Uri buildSourceUri(String packageName) {
            return Voicemails.CONTENT_URI.buildUpon()
                    .appendQueryParameter(PARAM_KEY_SOURCE_PACKAGE, packageName).build();
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
         * The package name of the voicemail source. There can only be a one entry per source.
         * <P>Type: TEXT</P>
         */
        public static final String SOURCE_PACKAGE = SOURCE_PACKAGE_FIELD;
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
         *  Value of {@link #DATA_CHANNEL_STATE} to indicate that data channel is working fine.
         */
        public static final int DATA_CHANNEL_STATE_OK = 0;
        /**
         * Value of {@link #DATA_CHANNEL_STATE} to indicate that data channel connection is not
         * working.
         */
        public static final int DATA_CHANNEL_STATE_NO_CONNECTION = 1;
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
         * A convenience method to build status URI specific to a source package by appending
         * {@link VoicemailContract#PARAM_KEY_SOURCE_PACKAGE} param to the base URI.
         */
        public static Uri buildSourceUri(String packageName) {
            return Status.CONTENT_URI.buildUpon()
                    .appendQueryParameter(PARAM_KEY_SOURCE_PACKAGE, packageName).build();
        }
    }
}
