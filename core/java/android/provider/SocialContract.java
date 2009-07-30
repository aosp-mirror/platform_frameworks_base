/*
 * Copyright (C) 2009 The Android Open Source Project
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

import android.content.res.Resources;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Data;

/**
 * The contract between the social provider and applications. Contains
 * definitions for the supported URIs and columns.
 *
 * @hide
 */
public class SocialContract {
    /** The authority for the social provider */
    public static final String AUTHORITY = "com.android.social";

    /** A content:// style uri to the authority for the contacts provider */
    public static final Uri AUTHORITY_URI = Uri.parse("content://" + AUTHORITY);

    private interface ActivitiesColumns {
        /**
         * The package name to use when creating {@link Resources} objects for
         * this data row. This value is only designed for use when building user
         * interfaces, and should not be used to infer the owner.
         * <p>
         * Type: TEXT
         */
        public static final String RES_PACKAGE = "res_package";

        /**
         * The mime-type of this social activity.
         * <p>
         * Type: TEXT
         */
        public static final String MIMETYPE = "mimetype";

        /**
         * Internal raw identifier for this social activity. This field is
         * analogous to the <code>atom:id</code> element defined in RFC 4287.
         * <p>
         * Type: TEXT
         */
        public static final String RAW_ID = "raw_id";

        /**
         * Reference to another {@link Activities#RAW_ID} that this social activity
         * is replying to. This field is analogous to the
         * <code>thr:in-reply-to</code> element defined in RFC 4685.
         * <p>
         * Type: TEXT
         */
        public static final String IN_REPLY_TO = "in_reply_to";

        /**
         * Reference to the {@link android.provider.ContactsContract.Contacts#_ID} that authored
         * this social activity. This field is analogous to the <code>atom:author</code>
         * element defined in RFC 4287.
         * <p>
         * Type: INTEGER
         */
        public static final String AUTHOR_CONTACT_ID = "author_contact_id";

        /**
         * Optional reference to the {@link android.provider.ContactsContract.Contacts#_ID} this
         * social activity is targeted towards. If more than one direct target, this field may
         * be left undefined. This field is analogous to the
         * <code>activity:target</code> element defined in the Atom Activity
         * Extensions Internet-Draft.
         * <p>
         * Type: INTEGER
         */
        public static final String TARGET_CONTACT_ID = "target_contact_id";

        /**
         * Timestamp when this social activity was published, in a
         * {@link System#currentTimeMillis()} time base. This field is analogous
         * to the <code>atom:published</code> element defined in RFC 4287.
         * <p>
         * Type: INTEGER
         */
        public static final String PUBLISHED = "published";

        /**
         * Timestamp when the original social activity in a thread was
         * published. For activities that have an in-reply-to field specified, the
         * content provider will automatically populate this field with the
         * timestamp of the original activity.
         * <p>
         * This field is useful for sorting order of activities that keeps together all
         * messages in each thread.
         * <p>
         * Type: INTEGER
         */
        public static final String THREAD_PUBLISHED = "thread_published";

        /**
         * Title of this social activity. This field is analogous to the
         * <code>atom:title</code> element defined in RFC 4287.
         * <p>
         * Type: TEXT
         */
        public static final String TITLE = "title";

        /**
         * Summary of this social activity. This field is analogous to the
         * <code>atom:summary</code> element defined in RFC 4287.
         * <p>
         * Type: TEXT
         */
        public static final String SUMMARY = "summary";

        /**
         * A URI associated this social activity. This field is analogous to the
         * <code>atom:link rel="alternate"</code> element defined in RFC 4287.
         * <p>
         * Type: TEXT
         */
        public static final String LINK = "link";

        /**
         * Optional thumbnail specific to this social activity. This is the raw
         * bytes of an image that could be inflated using {@link BitmapFactory}.
         * <p>
         * Type: BLOB
         */
        public static final String THUMBNAIL = "thumbnail";
    }

    public static final class Activities implements BaseColumns, ActivitiesColumns {
        /**
         * This utility class cannot be instantiated
         */
        private Activities() {
        }

        /**
         * The content:// style URI for this table
         */
        public static final Uri CONTENT_URI = Uri.withAppendedPath(AUTHORITY_URI, "activities");

        /**
         * The content:// URI for this table filtered to the set of social activities
         * authored by a specific {@link android.provider.ContactsContract.Contacts#_ID}.
         */
        public static final Uri CONTENT_AUTHORED_BY_URI =
            Uri.withAppendedPath(CONTENT_URI, "authored_by");

        /**
         * The {@link Uri} for the latest social activity performed by any
         * raw contact aggregated under the specified {@link Contacts#_ID}. Will
         * also join with most-present {@link Presence} for this aggregate.
         */
        public static final Uri CONTENT_CONTACT_STATUS_URI =
            Uri.withAppendedPath(AUTHORITY_URI, "contact_status");

        /**
         * The MIME type of {@link #CONTENT_URI} providing a directory of social
         * activities.
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/activity";

        /**
         * The MIME type of a {@link #CONTENT_URI} subdirectory of a single
         * social activity.
         */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/activity";
    }

}
