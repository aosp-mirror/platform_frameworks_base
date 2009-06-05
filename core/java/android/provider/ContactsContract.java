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

import android.graphics.BitmapFactory;
import android.net.Uri;

/**
 * The contract between the contacts provider and applications. Contains definitions
 * for the supported URIs and columns.
 *
 * @hide
 */
public final class ContactsContract {
    /** The authority for the contacts provider */
    public static final String AUTHORITY = "com.android.contacts";
    /** A content:// style uri to the authority for the contacts provider */
    public static final Uri AUTHORITY_URI = Uri.parse("content://" + AUTHORITY);

    public interface AccountsColumns {
        /**
         * The name of this account data
         * <P>Type: TEXT</P>
         */
        public static final String NAME = "name";
        /**
         * The name of this account data
         * <P>Type: TEXT</P>
         */
        public static final String TYPE = "type";
        /**
         * The name of this account data
         * <P>Type: TEXT</P>
         */
        public static final String DATA1 = "data1";

        /**
         * The value for this account data
         * <P>Type: INTEGER</P>
         */
        public static final String DATA2 = "data2";

        /**
         * The value for this account data
         * <P>Type: INTEGER</P>
         */
        public static final String DATA3 = "data3";

        /**
         * The value for this account data
         * <P>Type: INTEGER</P>
         */
        public static final String DATA4 = "data4";

        /**
         * The value for this account data
         * <P>Type: INTEGER</P>
         */
        public static final String DATA5 = "data5";
    }

    /**
     * Constants for the aggregates table, which contains a record per group
     * of contact representing the same person.
     */
    public static final class Accounts implements BaseColumns, AccountsColumns {
        /**
         * This utility class cannot be instantiated
         */
        private Accounts()  {}

        /**
         * The content:// style URI for this table
         */
        public static final Uri CONTENT_URI = Uri.withAppendedPath(AUTHORITY_URI, "accounts");

        /**
         * The MIME type of {@link #CONTENT_URI} providing a directory of
         * account data.
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/contacts_account";

        /**
         * The MIME type of a {@link #CONTENT_URI} subdirectory of a account
         */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/contacts_account";
    }

    public interface AggregatesColumns {
        /**
         * The display name for the contact.
         * <P>Type: TEXT</P>
         */
        public static final String DISPLAY_NAME = "display_name";

        /**
         * The number of times a person has been contacted
         * <P>Type: INTEGER</P>
         */
        public static final String TIMES_CONTACTED = "times_contacted";

        /**
         * The last time a person was contacted.
         * <P>Type: INTEGER</P>
         */
        public static final String LAST_TIME_CONTACTED = "last_time_contacted";

        /**
         * Is the contact starred?
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String STARRED = "starred";

        /**
         * Reference to the row in the data table holding the primary phone number.
         * <P>Type: INTEGER REFERENCES data(_id)</P>
         */
        public static final String PRIMARY_PHONE_ID = "primary_phone_id";

        /**
         * Reference to the row in the data table holding the primary email address.
         * <P>Type: INTEGER REFERENCES data(_id)</P>
         */
        public static final String PRIMARY_EMAIL_ID = "primary_email_id";

        /**
         * Reference to the row in the data table holding the photo.
         * <P>Type: INTEGER REFERENCES data(_id)</P>
         */
        public static final String PHOTO_ID = "photo_id";

        /**
         * Reference to a row containing custom ringtone and send to voicemail information.
         * <P>Type: INTEGER REFERENCES data(_id)</P>
         */
        public static final String CUSTOM_RINGTONE_ID = "custom_ringtone_id";
    }

    /**
     * Constants for the aggregates table, which contains a record per group
     * of contact representing the same person.
     */
    public static final class Aggregates implements BaseColumns, AggregatesColumns {
        /**
         * This utility class cannot be instantiated
         */
        private Aggregates()  {}

        /**
         * The content:// style URI for this table
         */
        public static final Uri CONTENT_URI = Uri.withAppendedPath(AUTHORITY_URI, "aggregates");

        /**
         * The MIME type of {@link #CONTENT_URI} providing a directory of
         * people.
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/person_aggregate";

        /**
         * The MIME type of a {@link #CONTENT_URI} subdirectory of a single
         * person.
         */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/person_aggregate";

        /**
         * A sub-directory of a single contact aggregate that contains all of their
         * {@link Data} rows.
         */
        public static final class Data implements BaseColumns, DataColumns {
            /**
             * no public constructor since this is a utility class
             */
            private Data() {}

            /**
             * The directory twig for this sub-table
             */
            public static final String CONTENT_DIRECTORY = "data";
        }
    }


    /**
     * Constants for the contacts table, which contains the base contact information.
     */
    public static final class Contacts implements BaseColumns {
        /**
         * This utility class cannot be instantiated
         */
        private Contacts()  {}

        /**
         * A reference to the {@link Accounts#_ID} that this data belongs to.
         */
        public static final String ACCOUNTS_ID = "accounts_id";

        /**
         * A reference to the {@link Aggregates#_ID} that this data belongs to.
         */
        public static final String AGGREGATE_ID = "aggregate_id";

        /**
         * The content:// style URI for this table
         */
        public static final Uri CONTENT_URI = Uri.withAppendedPath(AUTHORITY_URI, "contacts");

        /**
         * The content:// style URL for filtering people by email address. The
         * filter argument should be passed as an additional path segment after
         * this URI.
         *
         * @hide
         */
        public static final Uri CONTENT_FILTER_EMAIL_URI = Uri.withAppendedPath(CONTENT_URI, "filter_email");

        /**
         * The MIME type of {@link #CONTENT_URI} providing a directory of
         * people.
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/person";

        /**
         * The MIME type of a {@link #CONTENT_URI} subdirectory of a single
         * person.
         */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/person";

        /**
         * A string that uniquely identifies this contact to its source, which is referred to
         * by the {@link #ACCOUNTS_ID}
         */
        public static final String SOURCE_ID = "sourceid";

        /**
         * An integer that is updated whenever this contact or its data changes.
         */
        public static final String VERSION = "version";

        /**
         * Set to 1 whenever the version changes
         */
        public static final String DIRTY = "dirty";

        /**
         * A sub-directory of a single contact that contains all of their {@link Data} rows.
         * To access this directory append
         */
        public static final class Data implements BaseColumns, DataColumns {
            /**
             * no public constructor since this is a utility class
             */
            private Data() {}

            /**
             * The directory twig for this sub-table
             */
            public static final String CONTENT_DIRECTORY = "data";
        }
    }

    private interface DataColumns {
        /**
         * The package name that defines this type of data.
         */
        public static final String PACKAGE = "package";

        /**
         * The mime-type of the item represented by this row.
         */
        public static final String MIMETYPE = "mimetype";

        /**
         * A reference to the {@link android.provider.ContactsContract.Contacts#_ID}
         * that this data belongs to.
         */
        public static final String CONTACT_ID = "contact_id";

        /**
         * Whether this is the primary entry of its kind for the contact it belongs to
         * <P>Type: INTEGER (if set, non-0 means true)</P>
         */
        public static final String IS_PRIMARY = "is_primary";

        /**
         * Whether this is the primary entry of its kind for the aggregate it belongs to. Any data
         * record that is "super primary" must also be "primary".
         * <P>Type: INTEGER (if set, non-0 means true)</P>
         */
        public static final String IS_SUPER_PRIMARY = "is_super_primary";

        /** Generic data column, the meaning is {@link #MIMETYPE} specific */
        public static final String DATA1 = "data1";
        /** Generic data column, the meaning is {@link #MIMETYPE} specific */
        public static final String DATA2 = "data2";
        /** Generic data column, the meaning is {@link #MIMETYPE} specific */
        public static final String DATA3 = "data3";
        /** Generic data column, the meaning is {@link #MIMETYPE} specific */
        public static final String DATA4 = "data4";
        /** Generic data column, the meaning is {@link #MIMETYPE} specific */
        public static final String DATA5 = "data5";
        /** Generic data column, the meaning is {@link #MIMETYPE} specific */
        public static final String DATA6 = "data6";
        /** Generic data column, the meaning is {@link #MIMETYPE} specific */
        public static final String DATA7 = "data7";
        /** Generic data column, the meaning is {@link #MIMETYPE} specific */
        public static final String DATA8 = "data8";
        /** Generic data column, the meaning is {@link #MIMETYPE} specific */
        public static final String DATA9 = "data9";
        /** Generic data column, the meaning is {@link #MIMETYPE} specific */
        public static final String DATA10 = "data10";
    }

    /**
     * Constants for the data table, which contains data points tied to a contact.
     * For example, a phone number or email address. Each row in this table contains a type
     * definition and some generic columns. Each data type can define the meaning for each of
     * the generic columns.
     */
    public static final class Data implements BaseColumns, DataColumns {
        /**
         * This utility class cannot be instantiated
         */
        private Data() {}

        /**
         * The content:// style URI for this table
         */
        public static final Uri CONTENT_URI = Uri.withAppendedPath(AUTHORITY_URI, "data");

        /**
         * The MIME type of {@link #CONTENT_URI} providing a directory of data.
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/data";
    }

    /**
     * A table that represents the result of looking up a phone number, for example for caller ID.
     * The table joins that data row for the phone number with the contact that owns the number.
     * To perform a lookup you must append the number you want to find to {@link #CONTENT_URI}.
     */
    public static final class PhoneLookup implements BaseColumns, DataColumns, AggregatesColumns {
        /**
         * This utility class cannot be instantiated
         */
        private PhoneLookup() {}

        /**
         * The content:// style URI for this table. Append the phone number you want to lookup
         * to this URI and query it to perform a lookup. For example:
         *
         * {@code
         * Uri lookupUri = Uri.withAppendedPath(PhoneLookup.CONTENT_URI, phoneNumber);
         * }
         */
        public static final Uri CONTENT_URI = Uri.withAppendedPath(AUTHORITY_URI, "phone_lookup");
    }

    /**
     * Container for definitions of common data types stored in the {@link Data} table.
     */
    public static final class CommonDataKinds {
        /**
         * The {@link Data#PACKAGE} value for common data that should be shown
         * using a default style.
         */
        public static final String PACKAGE_COMMON = "common";

        /**
         * Columns common across the specific types.
         */
        private interface BaseCommonColumns {
            /**
             * The package name that defines this type of data.
             */
            public static final String PACKAGE = "package";

            /**
             * The mime-type of the item represented by this row.
             */
            public static final String MIMETYPE = "mimetype";

            /**
             * A reference to the {@link android.provider.ContactsContract.Contacts#_ID} that this
             * data belongs to.
             */
            public static final String CONTACT_ID = "contact_id";
        }

        /**
         * Columns common across the specific types.
         */
        private interface CommonColumns {
            /**
             * The type of data, for example Home or Work.
             * <P>Type: INTEGER</P>
             */
            public static final String TYPE = "data1";

            /**
             * The data for the contact method.
             * <P>Type: TEXT</P>
             */
            public static final String DATA = "data2";

            /**
             * The user defined label for the the contact method.
             * <P>Type: TEXT</P>
             */
            public static final String LABEL = "data3";
        }

        /**
         * Parts of the name.
         */
        public static final class StructuredName {
            private StructuredName() {}

            /** Mime-type used when storing this in data table. */
            public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/name";

            /**
             * The given name for the contact.
             * <P>Type: TEXT</P>
             */
            public static final String GIVEN_NAME = "data1";

            /**
             * The family name for the contact.
             * <P>Type: TEXT</P>
             */
            public static final String FAMILY_NAME = "data2";

            /**
             * The contact's honorific prefix, e.g. "Sir"
             * <P>Type: TEXT</P>
             */
            public static final String PREFIX = "data3";

            /**
             * The contact's middle name
             * <P>Type: TEXT</P>
             */
            public static final String MIDDLE_NAME = "data4";

            /**
             * The contact's honorific suffix, e.g. "Jr"
             */
            public static final String SUFFIX = "data5";

            /**
             * The phonetic version of the given name for the contact.
             * <P>Type: TEXT</P>
             */
            public static final String PHONETIC_GIVEN_NAME = "data6";

            /**
             * The phonetic version of the additional name for the contact.
             * <P>Type: TEXT</P>
             */
            public static final String PHONETIC_MIDDLE_NAME = "data7";

            /**
             * The phonetic version of the family name for the contact.
             * <P>Type: TEXT</P>
             */
            public static final String PHONETIC_FAMILY_NAME = "data8";

            /**
             * The name that should be used to display the contact.
             * <P>Type: TEXT</P>
             */
            public static final String DISPLAY_NAME = "data9";
        }

        /**
         * A nickname.
         */
        public static final class Nickname {
            private Nickname() {}

            /** Mime-type used when storing this in data table. */
            public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/nickname";

            /**
             * The type of data, for example Home or Work.
             * <P>Type: INTEGER</P>
             */
            public static final String TYPE = "data1";

            public static final int TYPE_CUSTOM = 1;
            public static final int TYPE_DEFAULT = 2;
            public static final int TYPE_OTHER_NAME = 3;
            public static final int TYPE_MAINDEN_NAME = 4;
            public static final int TYPE_SHORT_NAME = 5;
            public static final int TYPE_INITIALS = 6;

            /**
             * The name itself
             */
            public static final String NAME = "data2";

            /**
             * The user provided label, only used if TYPE is {@link #TYPE_CUSTOM}.
             * <P>Type: TEXT</P>
             */
            public static final String LABEL = "data3";
        }

        /**
         * Common data definition for telephone numbers.
         */
        public static final class Phone implements BaseCommonColumns, CommonColumns {
            private Phone() {}

            /** Mime-type used when storing this in data table. */
            public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/phone";

            public static final int TYPE_CUSTOM = 0;
            public static final int TYPE_HOME = 1;
            public static final int TYPE_MOBILE = 2;
            public static final int TYPE_WORK = 3;
            public static final int TYPE_FAX_WORK = 4;
            public static final int TYPE_FAX_HOME = 5;
            public static final int TYPE_PAGER = 6;
            public static final int TYPE_OTHER = 7;

            /**
             * The phone number as the user entered it.
             * <P>Type: TEXT</P>
             */
            public static final String NUMBER = "data2";
        }

        /**
         * Common data definition for email addresses.
         */
        public static final class Email implements BaseCommonColumns, CommonColumns {
            private Email() {}

            /** Mime-type used when storing this in data table. */
            public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/email";

            public static final int TYPE_CUSTOM = 0;
            public static final int TYPE_HOME = 1;
            public static final int TYPE_WORK = 2;
            public static final int TYPE_OTHER = 3;
        }

        /**
         * Common data definition for postal addresses.
         */
        public static final class Postal implements BaseCommonColumns, CommonColumns {
            private Postal() {}

            /** Mime-type used when storing this in data table. */
            public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/postal-address";

            public static final int TYPE_CUSTOM = 0;
            public static final int TYPE_HOME = 1;
            public static final int TYPE_WORK = 2;
            public static final int TYPE_OTHER = 3;
        }

       /**
        * Common data definition for IM addresses.
        */
        public static final class Im implements BaseCommonColumns, CommonColumns {
            private Im() {}

            /** Mime-type used when storing this in data table. */
            public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/im";

            public static final int TYPE_CUSTOM = 0;
            public static final int TYPE_HOME = 1;
            public static final int TYPE_WORK = 2;
            public static final int TYPE_OTHER = 3;

            public static final String PROTOCOL = "data5";

            /**
             * The predefined IM protocol types. The protocol can either be non-present, one
             * of these types, or a free-form string. These cases are encoded in the PROTOCOL
             * column as:
             * <ul>
             * <li>null</li>
             * <li>pre:&lt;an integer, one of the protocols below&gt;</li>
             * <li>custom:&lt;a string&gt;</li>
             * </ul>
             */
            public static final int PROTOCOL_AIM = 0;
            public static final int PROTOCOL_MSN = 1;
            public static final int PROTOCOL_YAHOO = 2;
            public static final int PROTOCOL_SKYPE = 3;
            public static final int PROTOCOL_QQ = 4;
            public static final int PROTOCOL_GOOGLE_TALK = 5;
            public static final int PROTOCOL_ICQ = 6;
            public static final int PROTOCOL_JABBER = 7;

            public static String encodePredefinedImProtocol(int protocol) {
               return "pre:" + protocol;
            }

            public static String encodeCustomImProtocol(String protocolString) {
               return "custom:" + protocolString;
            }

            public static Object decodeImProtocol(String encodedString) {
               if (encodedString == null) {
                   return null;
               }

               if (encodedString.startsWith("pre:")) {
                   return Integer.parseInt(encodedString.substring(4));
               }

               if (encodedString.startsWith("custom:")) {
                   return encodedString.substring(7);
               }

               throw new IllegalArgumentException(
                       "the value is not a valid encoded protocol, " + encodedString);
            }
        }

        /**
         * Common data definition for organizations.
         */
        public static final class Organization implements BaseCommonColumns {
            private Organization() {}

            /** Mime-type used when storing this in data table. */
            public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/organization";

            /**
             * The type of data, for example Home or Work.
             * <P>Type: INTEGER</P>
             */
            public static final String TYPE = "data1";

            public static final int TYPE_CUSTOM = 0;
            public static final int TYPE_HOME = 1;
            public static final int TYPE_WORK = 2;
            public static final int TYPE_OTHER = 3;

            /**
             * The user provided label, only used if TYPE is {@link #TYPE_CUSTOM}.
             * <P>Type: TEXT</P>
             */
            public static final String LABEL = "data2";

            /**
             * The company as the user entered it.
             * <P>Type: TEXT</P>
             */
            public static final String COMPANY = "data3";

            /**
             * The position title at this company as the user entered it.
             * <P>Type: TEXT</P>
             */
            public static final String TITLE = "data4";
        }

        /**
         * Photo of the contact.
         */
        public static final class Photo implements BaseCommonColumns {
            private Photo() {}

            /** Mime-type used when storing this in data table. */
            public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/photo";

            /**
             * Thumbnail photo of the contact. This is the raw bytes of an image
             * that could be inflated using {@link BitmapFactory}.
             * <p>
             * Type: BLOB
             */
            public static final String PHOTO = "data1";
        }

        /**
         * Notes about the contact.
         */
        public static final class Note implements BaseCommonColumns {
            private Note() {}

            /** Mime-type used when storing this in data table. */
            public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/note";

            /**
             * The note text.
             * <P>Type: TEXT</P>
             */
            public static final String NOTE = "data1";
        }

        /**
         * Custom ringtone associated with the contact.
         */
        public static final class CustomRingtone implements BaseCommonColumns {
            private CustomRingtone() {}

            public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/custom_ringtone";

            /**
             * Whether to send the number to voicemail.
             * <P>Type: INTEGER (if set, non-0 means true)</P>
             */
            public static final String SEND_TO_VOICEMAIL = "data1";

            /**
             * The ringtone uri.
             * <P>Type: TEXT</P>
             */
            public static final String RINGTONE_URI = "data2";
        }

        /**
         * Group Membership.
         */
        public static final class GroupMembership implements BaseCommonColumns {
            private GroupMembership() {}

            /** Mime-type used when storing this in data table. */
            public static final String CONTENT_ITEM_TYPE =
                    "vnd.android.cursor.item/group_membership";

            /**
             * The row id of the group that this group membership refers to. Either this or the
             * GROUP_SOURCE_ID must be set. If they are both set then they must refer to the same
             * group.
             * <P>Type: INTEGER</P>
             */
            public static final String GROUP_ROW_ID = "data1";

            /**
             * The source id of the group that this membership refers to. Either this or the
             * GROUP_ROW_ID must be set. If they are both set then they must refer to the same
             * group.
             * <P>Type: STRING</P>
             */
            public static final String GROUP_SOURCE_ID = "data2";
        }
    }

    /**
     * Constants for the contact aggregation exceptions table, which contains
     * aggregation rules overriding those used by automatic aggregation.
     */
    public static final class AggregationExceptions {
        /**
         * This utility class cannot be instantiated
         */
        private AggregationExceptions() {}

        /**
         * The content:// style URI for this table
         */
        public static final Uri CONTENT_URI =
                Uri.withAppendedPath(AUTHORITY_URI, "aggregation_exceptions");

        /**
         * The MIME type of {@link #CONTENT_URI} providing a directory of data.
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/aggregation_exception";

        /**
         * The type of exception: {@link #TYPE_NEVER_MATCH} or {@link #TYPE_ALWAYS_MATCH}.
         *
         * <P>Type: INTEGER</P>
         */
        public static final String TYPE = "type";

        public static final int TYPE_NEVER_MATCH = 0;
        public static final int TYPE_ALWAYS_MATCH = 1;

        /**
         * A reference to the {@link android.provider.ContactsContract.Contacts#_ID} of one of
         * the contacts that the rule applies to.
         */
        public static final String CONTACT_ID1 = "contact_id1";

        /**
         * A reference to the {@link android.provider.ContactsContract.Contacts#_ID} of the other
         * contact that the rule applies to.
         */
        public static final String CONTACT_ID2 = "contact_id2";
    }
}
