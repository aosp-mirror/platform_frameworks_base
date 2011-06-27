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

import android.content.Intent;
import android.database.ContentObserver;
import android.net.Uri;
import android.provider.CallLog.Calls;

/**
 * The contract between the voicemail provider and applications. Contains
 * definitions for the supported URIs and columns.
 *
 * <P>Voicemails are inserted by what is called as a "voicemail source"
 * application, which is responsible for syncing voicemail data between a remote
 * server and the local voicemail content provider. "voicemail source"
 * application should use the source specific {@link #CONTENT_URI_SOURCE} URI
 * to insert and retrieve voicemails.
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
 * @hide
 */
// TODO: unhide when the API is approved by android-api-council
public class VoicemailContract {
    /** Not instantiable. */
    private VoicemailContract() {
    }

    /** The authority used by the voicemail provider. */
    public static final String AUTHORITY = "com.android.voicemail";

    /** URI to insert/retrieve all voicemails. */
    public static final Uri CONTENT_URI =
            Uri.parse("content://" + AUTHORITY + "/voicemail");
    /** URI to insert/retrieve voicemails by a given voicemail source. */
    public static final Uri CONTENT_URI_SOURCE =
            Uri.parse("content://" + AUTHORITY + "/voicemail/source/");

    // TODO: Move ACTION_NEW_VOICEMAIL to the Intent class.
    /** Broadcast intent when a new voicemail record is inserted. */
    public static final String ACTION_NEW_VOICEMAIL = "android.intent.action.NEW_VOICEMAIL";
    /**
     * Extra included in {@value Intent#ACTION_PROVIDER_CHANGED} and
     * {@value #ACTION_NEW_VOICEMAIL} broadcast intents to indicate the package
     * that caused the change in content provider.
     * <p>Receivers of the broadcast can use this field to determine if this is
     * a self change.
     * @deprecated {@link #EXTRA_SELF_CHANGE} is now populated instead.
     */
    public static final String EXTRA_CHANGED_BY = "com.android.voicemail.extra.CHANGED_BY";
    /**
     * Extra included in {@value Intent#ACTION_PROVIDER_CHANGED} and
     * {@value #ACTION_NEW_VOICEMAIL} broadcast intents to indicate if the receiving
     * package made this change.
     */
    public static final String EXTRA_SELF_CHANGE = "com.android.voicemail.extra.SELF_CHANGE";

    /** The mime type for a collection of voicemails. */
    public static final String DIR_TYPE =
            "vnd.android.cursor.dir/voicemails";

    public static final class Voicemails implements BaseColumns {
        /** Not instantiable. */
        private Voicemails() {
        }

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
         * The mail box state of the voicemail.
         * <P> Possible values: {@link #STATE_INBOX}, {@link #STATE_DELETED},
         * {@link #STATE_UNDELETED}.
         * <P>Type: INTEGER</P>
         */
        public static final String STATE = "state";
        /** Value of {@link #STATE} when the voicemail is in inbox. */
        public static int STATE_INBOX = 0;
        /** Value of {@link #STATE} when the voicemail has been marked as deleted. */
        public static int STATE_DELETED = 1;
        /** Value of {@link #STATE} when the voicemail has marked as undeleted. */
        public static int STATE_UNDELETED = 2;
        /**
         * Package name of the source application that inserted the voicemail.
         * <P>Type: TEXT</P>
         */
        public static final String SOURCE_PACKAGE = "source_package";
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
    }
}
