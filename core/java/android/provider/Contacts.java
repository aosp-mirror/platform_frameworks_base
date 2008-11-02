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

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
import android.widget.ImageView;

import com.android.internal.R;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * The Contacts provider stores all information about contacts.
 */
public class Contacts {
    private static final String TAG = "Contacts";
    
    public static final String AUTHORITY = "contacts";

    /**
     * The content:// style URL for this provider
     */
    public static final Uri CONTENT_URI =
        Uri.parse("content://" + AUTHORITY);

    /** Signifies an email address row that is stored in the ContactMethods table */
    public static final int KIND_EMAIL = 1;
    /** Signifies a postal address row that is stored in the ContactMethods table */
    public static final int KIND_POSTAL = 2;
    /** Signifies an IM address row that is stored in the ContactMethods table */
    public static final int KIND_IM = 3;
    /** Signifies an Organization row that is stored in the Organizations table */
    public static final int KIND_ORGANIZATION = 4;
    /** Signifies an Phone row that is stored in the Phones table */
    public static final int KIND_PHONE = 5;

    /**
     * no public constructor since this is a utility class
     */
    private Contacts() {}

    /**
     * Columns from the Settings table that other columns join into themselves.
     */
    public interface SettingsColumns {
        /**
         * The _SYNC_ACCOUNT to which this setting corresponds. This may be null.
         * <P>Type: TEXT</P>
         */
        public static final String _SYNC_ACCOUNT = "_sync_account";

        /**
         * The key of this setting.
         * <P>Type: TEXT</P>
         */
        public static final String KEY = "key";

        /**
         * The value of this setting.
         * <P>Type: TEXT</P>
         */
        public static final String VALUE = "value";
    }

    /**
     * The settings over all of the people
     */
    public static final class Settings implements BaseColumns, SettingsColumns {
        /**
         * no public constructor since this is a utility class
         */
        private Settings() {}

        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI =
            Uri.parse("content://contacts/settings");

        /**
         * The directory twig for this sub-table
         */
        public static final String CONTENT_DIRECTORY = "settings";

        /**
         * The default sort order for this table
         */
        public static final String DEFAULT_SORT_ORDER = "key ASC";

        /**
         * A setting that is used to indicate if we should sync down all groups for the
         * specified account. For this setting the _SYNC_ACCOUNT column must be set.
         * If this isn't set then we will only sync the groups whose SHOULD_SYNC column
         * is set to true.
         * <p>
         * This is a boolean setting. It is true if it is set and it is anything other than the
         * emptry string or "0".
         */
        public static final String SYNC_EVERYTHING = "syncEverything";

        public static String getSetting(ContentResolver cr, String account, String key) {
            // For now we only support a single account and the UI doesn't know what
            // the account name is, so we're using a global setting for SYNC_EVERYTHING.
            // Some day when we add multiple accounts to the UI this should honor the account
            // that was asked for.
            String selectString;
            String[] selectArgs;
            if (false) {
                selectString = (account == null)
                        ? "_sync_account is null AND key=?"
                        : "_sync_account=? AND key=?";
                selectArgs = (account == null)
                ? new String[]{key}
                : new String[]{account, key};
            } else {
                selectString = "key=?";
                selectArgs = new String[] {key};
            }
            Cursor cursor = cr.query(Settings.CONTENT_URI, new String[]{VALUE},
                    selectString, selectArgs, null);
            try {
                if (!cursor.moveToNext()) return null;
                return cursor.getString(0);
            } finally {
                cursor.close();
            }
        }

        public static void setSetting(ContentResolver cr, String account, String key,
                String value) {
            ContentValues values = new ContentValues();
            // For now we only support a single account and the UI doesn't know what
            // the account name is, so we're using a global setting for SYNC_EVERYTHING.
            // Some day when we add multiple accounts to the UI this should honor the account
            // that was asked for.
            //values.put(_SYNC_ACCOUNT, account);
            values.put(KEY, key);
            values.put(VALUE, value);
            cr.update(Settings.CONTENT_URI, values, null, null);
        }
    }

    /**
     * Columns from the People table that other tables join into themselves.
     */
    public interface PeopleColumns {
        /**
         * The persons name.
         * <P>Type: TEXT</P>
         */
        public static final String NAME = "name";

        /**
         * The display name. If name is not null name, else if number is not null number,
         * else if email is not null email.
         * <P>Type: TEXT</P>
         */
        public static final String DISPLAY_NAME = "display_name";

        /**
         * Notes about the person.
         * <P>Type: TEXT</P>
         */
        public static final String NOTES = "notes";

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
         * A custom ringtone associated with a person. Not always present.
         * <P>Type: TEXT (URI to the ringtone)</P>
         */
        public static final String CUSTOM_RINGTONE = "custom_ringtone";

        /**
         * Whether the person should always be sent to voicemail. Not always
         * present.
         * <P>Type: INTEGER (0 for false, 1 for true)</P>
         */
        public static final String SEND_TO_VOICEMAIL = "send_to_voicemail";

        /**
         * Is the contact starred?
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String STARRED = "starred";

        /**
         * The server version of the photo
         * <P>Type: TEXT (the version number portion of the photo URI)</P>
         */
        public static final String PHOTO_VERSION = "photo_version";
    }

    /**
     * This table contains people.
     */
    public static final class People implements BaseColumns, SyncConstValue, PeopleColumns,
            PhonesColumns, PresenceColumns {
        /**
         * no public constructor since this is a utility class
         */
        private People() {}

        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI =
            Uri.parse("content://contacts/people");

        /**
         * The content:// style URL for filtering people by name. The filter
         * argument should be passed as an additional path segment after this URI.
         */
        public static final Uri CONTENT_FILTER_URI =
            Uri.parse("content://contacts/people/filter");

        /**
         * The content:// style URL for the table that holds the deleted
         * contacts.
         */
        public static final Uri DELETED_CONTENT_URI =
            Uri.parse("content://contacts/deleted_people");

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
         * The default sort order for this table
         */
        public static final String DEFAULT_SORT_ORDER = People.NAME + " ASC";

        /**
         * The ID of the persons preferred phone number.
         * <P>Type: INTEGER (foreign key to phones table on the _ID field)</P>
         */
        public static final String PRIMARY_PHONE_ID = "primary_phone";

        /**
         * The ID of the persons preferred email.
         * <P>Type: INTEGER (foreign key to contact_methods table on the
         * _ID field)</P>
         */
        public static final String PRIMARY_EMAIL_ID = "primary_email";

        /**
         * The ID of the persons preferred organization.
         * <P>Type: INTEGER (foreign key to organizations table on the
         * _ID field)</P>
         */
        public static final String PRIMARY_ORGANIZATION_ID = "primary_organization";

        /**
         * Mark a person as having been contacted.
         *
         * @param resolver the ContentResolver to use
         * @param personId the person who was contacted
         */
        public static void markAsContacted(ContentResolver resolver, long personId) {
            Uri uri = ContentUris.withAppendedId(CONTENT_URI, personId);
            uri = Uri.withAppendedPath(uri, "update_contact_time");
            ContentValues values = new ContentValues();
            // There is a trigger in place that will update TIMES_CONTACTED when
            // LAST_TIME_CONTACTED is modified.
            values.put(LAST_TIME_CONTACTED, System.currentTimeMillis());
            resolver.update(uri, values, null, null);
        }

        /**
         * Adds a person to the My Contacts group.
         * 
         * @param resolver the resolver to use
         * @param personId the person to add to the group
         * @return the URI of the group membership row
         * @throws IllegalStateException if the My Contacts group can't be found
         */
        public static Uri addToMyContactsGroup(ContentResolver resolver, long personId) {
            long groupId = 0;
            Cursor groupsCursor = resolver.query(Groups.CONTENT_URI, GROUPS_PROJECTION,
                    Groups.SYSTEM_ID + "='" + Groups.GROUP_MY_CONTACTS + "'", null, null);
            if (groupsCursor != null) {
                try {
                    if (groupsCursor.moveToFirst()) {
                        groupId = groupsCursor.getLong(0);
                    }
                } finally {
                    groupsCursor.close();
                }
            }

            if (groupId == 0) {
                throw new IllegalStateException("Failed to find the My Contacts group");
            }
            
            return addToGroup(resolver, personId, groupId);
        }

        /**
         * Adds a person to a group referred to by name.
         * 
         * @param resolver the resolver to use
         * @param personId the person to add to the group
         * @param groupName the name of the group to add the contact to
         * @return the URI of the group membership row
         * @throws IllegalStateException if the group can't be found
         */
        public static Uri addToGroup(ContentResolver resolver, long personId, String groupName) {
            long groupId = 0;
            Cursor groupsCursor = resolver.query(Groups.CONTENT_URI, GROUPS_PROJECTION,
                    Groups.NAME + "=?", new String[] { groupName }, null);
            if (groupsCursor != null) {
                try {
                    if (groupsCursor.moveToFirst()) {
                        groupId = groupsCursor.getLong(0);
                    }
                } finally {
                    groupsCursor.close();
                }
            }

            if (groupId == 0) {
                throw new IllegalStateException("Failed to find the My Contacts group");
            }
            
            return addToGroup(resolver, personId, groupId);
        }

        /**
         * Adds a person to a group.
         * 
         * @param resolver the resolver to use
         * @param personId the person to add to the group
         * @param groupId the group to add the person to
         * @return the URI of the group membership row
         */
        public static Uri addToGroup(ContentResolver resolver, long personId, long groupId) {
            ContentValues values = new ContentValues();
            values.put(GroupMembership.PERSON_ID, personId);
            values.put(GroupMembership.GROUP_ID, groupId);
            return resolver.insert(GroupMembership.CONTENT_URI, values);
        }
        
        private static final String[] GROUPS_PROJECTION = new String[] {
            Groups._ID,
        };

        /**
         * Creates a new contacts and adds it to the "My Contacts" group.
         * 
         * @param resolver the ContentResolver to use
         * @param values the values to use when creating the contact
         * @return the URI of the contact, or null if the operation fails
         */
        public static Uri createPersonInMyContactsGroup(ContentResolver resolver,
                ContentValues values) {

            Uri contactUri = resolver.insert(People.CONTENT_URI, values);
            if (contactUri == null) {
                Log.e(TAG, "Failed to create the contact");
                return null;
            }

            if (addToMyContactsGroup(resolver, ContentUris.parseId(contactUri)) == null) {
                resolver.delete(contactUri, null, null);
                return null;
            }
            return contactUri;
        }

        public static Cursor queryGroups(ContentResolver resolver, long person) {
            return resolver.query(GroupMembership.CONTENT_URI, null, "person=?",
                    new String[]{String.valueOf(person)}, Groups.DEFAULT_SORT_ORDER);
        }

        /**
         * Set the photo for this person. data may be null
         * @param cr the ContentResolver to use
         * @param person the Uri of the person whose photo is to be updated
         * @param data the byte[] that represents the photo
         */
        public static void setPhotoData(ContentResolver cr, Uri person, byte[] data) {
            Uri photoUri = Uri.withAppendedPath(person, Contacts.Photos.CONTENT_DIRECTORY);
            ContentValues values = new ContentValues();
            values.put(Photos.DATA, data);
            cr.update(photoUri, values, null, null);
        }
        
        /**
         * Opens an InputStream for the person's photo and returns the photo as a Bitmap.
         * If the person's photo isn't present returns the placeholderImageResource instead.
         * @param person the person whose photo should be used
         */
        public static InputStream openContactPhotoInputStream(ContentResolver cr, Uri person) {
            Uri photoUri = Uri.withAppendedPath(person, Contacts.Photos.CONTENT_DIRECTORY);
            Cursor cursor = cr.query(photoUri, new String[]{Photos.DATA}, null, null, null);
            try {
                if (!cursor.moveToNext()) {
                    return null;
                }
                byte[] data = cursor.getBlob(0);
                if (data == null) {
                    return null;
                }
                return new ByteArrayInputStream(data);
            } finally {
                cursor.close();
            }
        }

        /**
         * Opens an InputStream for the person's photo and returns the photo as a Bitmap.
         * If the person's photo isn't present returns the placeholderImageResource instead.
         * @param context the Context
         * @param person the person whose photo should be used
         * @param placeholderImageResource the image resource to use if the person doesn't
         *   have a photo
         * @param options the decoding options, can be set to null
         */
        public static Bitmap loadContactPhoto(Context context, Uri person,
                int placeholderImageResource, BitmapFactory.Options options) {
            if (person == null) {
                return loadPlaceholderPhoto(placeholderImageResource, context, options);
            }

            InputStream stream = openContactPhotoInputStream(context.getContentResolver(), person);
            Bitmap bm = stream != null ? BitmapFactory.decodeStream(stream, null, options) : null;
            if (bm == null) {
                bm = loadPlaceholderPhoto(placeholderImageResource, context, options);
            }
            return bm;
        }

        private static Bitmap loadPlaceholderPhoto(int placeholderImageResource, Context context,
                BitmapFactory.Options options) {
            if (placeholderImageResource == 0) {
                return null;
            }
            return BitmapFactory.decodeResource(context.getResources(),
                    placeholderImageResource, options);
        }

        /**
         * A sub directory of a single person that contains all of their Phones.
         */
        public static final class Phones implements BaseColumns, PhonesColumns,
                PeopleColumns {
            /**
             * no public constructor since this is a utility class
             */
            private Phones() {}

            /**
             * The directory twig for this sub-table
             */
            public static final String CONTENT_DIRECTORY = "phones";

            /**
             * The default sort order for this table
             */
            public static final String DEFAULT_SORT_ORDER = "number ASC";
        }

        /**
         * A subdirectory of a single person that contains all of their
         * ContactMethods.
         */
        public static final class ContactMethods
                implements BaseColumns, ContactMethodsColumns, PeopleColumns {
            /**
             * no public constructor since this is a utility class
             */
            private ContactMethods() {}

            /**
             * The directory twig for this sub-table
             */
            public static final String CONTENT_DIRECTORY = "contact_methods";

            /**
             * The default sort order for this table
             */
            public static final String DEFAULT_SORT_ORDER = "data ASC";
        }

        /**
         * The extensions for a person
         */
        public static class Extensions implements BaseColumns, ExtensionsColumns {
            /**
             * no public constructor since this is a utility class
             */
            private Extensions() {}

            /**
             * The directory twig for this sub-table
             */
            public static final String CONTENT_DIRECTORY = "extensions";

            /**
             * The default sort order for this table
             */
            public static final String DEFAULT_SORT_ORDER = "name ASC";

            /**
             * The ID of the person this phone number is assigned to.
             * <P>Type: INTEGER (long)</P>
             */
            public static final String PERSON_ID = "person";
        }
    }

    /**
     * Columns from the groups table.
     */
    public interface GroupsColumns {
        /**
         * The group name.
         * <P>Type: TEXT</P>
         */
        public static final String NAME = "name";

        /**
         * Notes about the group.
         * <P>Type: TEXT</P>
         */
        public static final String NOTES = "notes";

        /**
         * Whether this group should be synced if the SYNC_EVERYTHING settings is false
         * for this group's account.
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String SHOULD_SYNC = "should_sync";

        /**
         * The ID of this group if it is a System Group, null otherwise.
         * <P>Type: TEXT</P>
         */
        public static final String SYSTEM_ID = "system_id";
    }

    /**
     * This table contains the groups for an account.
     */
    public static final class Groups
            implements BaseColumns, SyncConstValue, GroupsColumns {
        /**
         * no public constructor since this is a utility class
         */
        private Groups() {}

        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI =
            Uri.parse("content://contacts/groups");

        /**
         * The content:// style URL for the table that holds the deleted
         * groups.
         */
        public static final Uri DELETED_CONTENT_URI =
            Uri.parse("content://contacts/deleted_groups");

        /**
         * The MIME type of {@link #CONTENT_URI} providing a directory of
         * groups.
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/contactsgroup";

        /**
         * The MIME type of a {@link #CONTENT_URI} subdirectory of a single
         * group.
         */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/contactsgroup";

        /**
         * The default sort order for this table
         */
        public static final String DEFAULT_SORT_ORDER = NAME + " ASC";

        /**
         *
         */
        public static final String GROUP_ANDROID_STARRED = "Starred in Android";

        /**
         * The "My Contacts" system group.
         */
        public static final String GROUP_MY_CONTACTS = "Contacts";
    }

    /**
     * Columns from the Phones table that other columns join into themselves.
     */
    public interface PhonesColumns {
        /**
         * The type of the the phone number.
         * <P>Type: INTEGER (one of the constants below)</P>
         */
        public static final String TYPE = "type";

        public static final int TYPE_CUSTOM = 0;
        public static final int TYPE_HOME = 1;
        public static final int TYPE_MOBILE = 2;
        public static final int TYPE_WORK = 3;
        public static final int TYPE_FAX_WORK = 4;
        public static final int TYPE_FAX_HOME = 5;
        public static final int TYPE_PAGER = 6;
        public static final int TYPE_OTHER = 7;

        /**
         * The user provided label for the phone number, only used if TYPE is TYPE_CUSTOM.
         * <P>Type: TEXT</P>
         */
        public static final String LABEL = "label";

        /**
         * The phone number as the user entered it.
         * <P>Type: TEXT</P>
         */
        public static final String NUMBER = "number";

        /**
         * The normalized phone number
         * <P>Type: TEXT</P>
         */
        public static final String NUMBER_KEY = "number_key";

        /**
         * Whether this is the primary phone number
         * <P>Type: INTEGER (if set, non-0 means true)</P>
         */
        public static final String ISPRIMARY = "isprimary";
    }

    /**
     * This table stores phone numbers and a reference to the person that the
     * contact method belongs to. Phone numbers are stored separately from
     * other contact methods to make caller ID lookup more efficient.
     */
    public static final class Phones
            implements BaseColumns, PhonesColumns, PeopleColumns {
        /**
         * no public constructor since this is a utility class
         */
        private Phones() {}

        public static final CharSequence getDisplayLabel(Context context, int type,
                CharSequence label, CharSequence[] labelArray) {
            CharSequence display = "";

            if (type != People.Phones.TYPE_CUSTOM) {
                CharSequence[] labels = labelArray != null? labelArray 
                        : context.getResources().getTextArray(
                                com.android.internal.R.array.phoneTypes);
                try {
                    display = labels[type - 1];
                } catch (ArrayIndexOutOfBoundsException e) {
                    display = labels[People.Phones.TYPE_HOME - 1];
                }
            } else {
                if (!TextUtils.isEmpty(label)) {
                    display = label;
                }
            }
            return display;
        }

        public static final CharSequence getDisplayLabel(Context context, int type,
                CharSequence label) {
            return getDisplayLabel(context, type, label, null);
        }
        
        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI =
            Uri.parse("content://contacts/phones");

        /**
         * The content:// style URL for filtering phone numbers
         */
        public static final Uri CONTENT_FILTER_URL =
            Uri.parse("content://contacts/phones/filter");

        /**
         * The MIME type of {@link #CONTENT_URI} providing a directory of
         * phones.
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/phone";

        /**
         * The MIME type of a {@link #CONTENT_URI} subdirectory of a single
         * phone.
         */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/phone";

        /**
         * The default sort order for this table
         */
        public static final String DEFAULT_SORT_ORDER = "name ASC";

        /**
         * The ID of the person this phone number is assigned to.
         * <P>Type: INTEGER (long)</P>
         */
        public static final String PERSON_ID = "person";
    }

    public static final class GroupMembership implements BaseColumns, GroupsColumns {
        /**
         * no public constructor since this is a utility class
         */
        private GroupMembership() {}

        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI =
            Uri.parse("content://contacts/groupmembership");

        /**
         * The content:// style URL for this table
         */
        public static final Uri RAW_CONTENT_URI =
            Uri.parse("content://contacts/groupmembershipraw");

        /**
         * The directory twig for this sub-table
         */
        public static final String CONTENT_DIRECTORY = "groupmembership";
        /**
         * The MIME type of {@link #CONTENT_URI} providing a directory of all
         * person groups.
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/contactsgroupmembership";

        /**
         * The MIME type of a {@link #CONTENT_URI} subdirectory of a single
         * person group.
         */
        public static final String CONTENT_ITEM_TYPE =
                "vnd.android.cursor.item/contactsgroupmembership";

        /**
         * The default sort order for this table
         */
        public static final String DEFAULT_SORT_ORDER = "group_id ASC";

        /**
         * The row id of the accounts group.
         * <P>Type: TEXT</P>
         */
        public static final String GROUP_ID = "group_id";

        /**
         * The sync id of the group.
         * <P>Type: TEXT</P>
         */
        public static final String GROUP_SYNC_ID = "group_sync_id";

        /**
         * The account of the group.
         * <P>Type: TEXT</P>
         */
        public static final String GROUP_SYNC_ACCOUNT = "group_sync_account";

        /**
         * The row id of the person.
         * <P>Type: TEXT</P>
         */
        public static final String PERSON_ID = "person";
    }

    /**
     * Columns from the ContactMethods table that other tables join into
     * themseleves.
     */
    public interface ContactMethodsColumns {
        /**
         * The kind of the the contact method. For example, email address,
         * postal address, etc.
         * <P>Type: INTEGER (one of the values below)</P>
         */
        public static final String KIND = "kind";

        /**
         * The type of the contact method, must be one of the types below.
         * <P>Type: INTEGER (one of the values below)</P>
         */
        public static final String TYPE = "type";
        public static final int TYPE_CUSTOM = 0;
        public static final int TYPE_HOME = 1;
        public static final int TYPE_WORK = 2;
        public static final int TYPE_OTHER = 3;

        /**
         * The user defined label for the the contact method.
         * <P>Type: TEXT</P>
         */
        public static final String LABEL = "label";

        /**
         * The data for the contact method.
         * <P>Type: TEXT</P>
         */
        public static final String DATA = "data";

        /**
         * Auxiliary data for the contact method.
         * <P>Type: TEXT</P>
         */
        public static final String AUX_DATA = "aux_data";

        /**
         * Whether this is the primary organization
         * <P>Type: INTEGER (if set, non-0 means true)</P>
         */
        public static final String ISPRIMARY = "isprimary";
    }

    /**
     * This table stores all non-phone contact methods and a reference to the
     * person that the contact method belongs to.
     */
    public static final class ContactMethods
            implements BaseColumns, ContactMethodsColumns, PeopleColumns {
        /**
         * The column with latitude data for postal locations
         * <P>Type: REAL</P>
         */
        public static final String POSTAL_LOCATION_LATITUDE = DATA;

        /**
         * The column with longitude data for postal locations
         * <P>Type: REAL</P>
         */
        public static final String POSTAL_LOCATION_LONGITUDE = AUX_DATA;

        /**
         * The predefined IM protocol types. The protocol can either be non-present, one
         * of these types, or a free-form string. These cases are encoded in the AUX_DATA
         * column as:
         *  - null
         *  - pre:<an integer, one of the protocols below>
         *  - custom:<a string>
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
        
        /**
         * This looks up the provider category defined in
         * {@link android.provider.Im.ProviderCategories} from the predefined IM protocol id.
         * This is used for interacting with the IM application.
         * 
         * @param protocol the protocol ID
         * @return the provider category the IM app uses for the given protocol, or null if no
         * provider is defined for the given protocol
         * @hide
         */
        public static String lookupProviderCategoryFromId(int protocol) {
            switch (protocol) {
                case PROTOCOL_GOOGLE_TALK:
                    return Im.ProviderCategories.GTALK;
                case PROTOCOL_AIM:
                    return Im.ProviderCategories.AIM;
                case PROTOCOL_MSN:
                    return Im.ProviderCategories.MSN;
                case PROTOCOL_YAHOO:
                    return Im.ProviderCategories.YAHOO;
                case PROTOCOL_ICQ:
                    return Im.ProviderCategories.ICQ;
            }
            return null;
        }

        /**
         * no public constructor since this is a utility class
         */
        private ContactMethods() {}

        public static final CharSequence getDisplayLabel(Context context, int kind,
                int type, CharSequence label) {
            CharSequence display = "";
            switch (kind) {
                case KIND_EMAIL: {
                    if (type != People.ContactMethods.TYPE_CUSTOM) {
                        CharSequence[] labels = context.getResources().getTextArray(
                                com.android.internal.R.array.emailAddressTypes);
                        try {
                            display = labels[type - 1];
                        } catch (ArrayIndexOutOfBoundsException e) {
                            display = labels[ContactMethods.TYPE_HOME - 1];
                        }
                    } else {
                        if (!TextUtils.isEmpty(label)) {
                            display = label;
                        }
                    }
                    break;
                }

                case KIND_POSTAL: {
                    if (type != People.ContactMethods.TYPE_CUSTOM) {
                        CharSequence[] labels = context.getResources().getTextArray(
                                com.android.internal.R.array.postalAddressTypes);
                        try {
                            display = labels[type - 1];
                        } catch (ArrayIndexOutOfBoundsException e) {
                            display = labels[ContactMethods.TYPE_HOME - 1];
                        }
                    } else {
                        if (!TextUtils.isEmpty(label)) {
                            display = label;
                        }
                    }
                    break;
                }

                default:
                    display = context.getString(R.string.untitled);
            }
            return display;
        }

        /**
         * Add a longitude and latitude location to a postal address.
         *
         * @param context the context to use when updating the database
         * @param postalId the address to update
         * @param latitude the latitude for the address
         * @param longitude the longitude for the address
         */
        public void addPostalLocation(Context context, long postalId,
                double latitude, double longitude) {
            final ContentResolver resolver = context.getContentResolver();
            // Insert the location
            ContentValues values = new ContentValues(2);
            values.put(POSTAL_LOCATION_LATITUDE, latitude);
            values.put(POSTAL_LOCATION_LONGITUDE, longitude);
            Uri loc = resolver.insert(CONTENT_URI, values);
            long locId = ContentUris.parseId(loc);

            // Update the postal address
            values.clear();
            values.put(AUX_DATA, locId);
            resolver.update(ContentUris.withAppendedId(CONTENT_URI, postalId), values, null, null);
        }

        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI =
            Uri.parse("content://contacts/contact_methods");

        /**
         * The content:// style URL for sub-directory of e-mail addresses.
         */
        public static final Uri CONTENT_EMAIL_URI =
            Uri.parse("content://contacts/contact_methods/email");

        /**
         * The MIME type of {@link #CONTENT_URI} providing a directory of
         * phones.
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/contact-methods";

        /**
         * The MIME type of a {@link #CONTENT_EMAIL_URI} sub-directory of\
         * multiple {@link Contacts#KIND_EMAIL} entries.
         */
        public static final String CONTENT_EMAIL_TYPE = "vnd.android.cursor.dir/email";

        /**
         * The MIME type of a {@link #CONTENT_EMAIL_URI} sub-directory of\
         * multiple {@link Contacts#KIND_POSTAL} entries.
         */
        public static final String CONTENT_POSTAL_TYPE = "vnd.android.cursor.dir/postal-address";

        /**
         * The MIME type of a {@link #CONTENT_URI} sub-directory of a single
         * {@link Contacts#KIND_EMAIL} entry.
         */
        public static final String CONTENT_EMAIL_ITEM_TYPE = "vnd.android.cursor.item/email";

        /**
         * The MIME type of a {@link #CONTENT_URI} sub-directory of a single
         * {@link Contacts#KIND_POSTAL} entry.
         */
        public static final String CONTENT_POSTAL_ITEM_TYPE
                = "vnd.android.cursor.item/postal-address";

        /**
         * The MIME type of a {@link #CONTENT_URI} sub-directory of a single
         * {@link Contacts#KIND_IM} entry.
         */
        public static final String CONTENT_IM_ITEM_TYPE = "vnd.android.cursor.item/jabber-im";

        /**
         * The default sort order for this table
         */
        public static final String DEFAULT_SORT_ORDER = "name ASC";

        /**
         * The ID of the person this contact method is assigned to.
         * <P>Type: INTEGER (long)</P>
         */
        public static final String PERSON_ID = "person";
    }

    /**
     * The IM presence columns with some contacts specific columns mixed in.
     */
    public interface PresenceColumns extends Im.CommonPresenceColumns {
        /**
         * The IM service the presence is coming from. Formatted using either
         * {@link Contacts.ContactMethods#encodePredefinedImProtocol} or
         * {@link Contacts.ContactMethods#encodeCustomImProtocol}.
         * <P>Type: STRING</P>
         */
        public static final String IM_PROTOCOL = "im_protocol";

        /**
         * The IM handle the presence item is for. The handle is scoped to
         * the {@link #IM_PROTOCOL}.
         * <P>Type: STRING</P>
         */
        public static final String IM_HANDLE = "im_handle";

        /**
         * The IM account for the local user that the presence data came from.
         * <P>Type: STRING</P>
         */
        public static final String IM_ACCOUNT = "im_account";
    }

    /**
     * Contains presence information about contacts.
     * @hide
     */
    public static final class Presence
            implements BaseColumns, PresenceColumns, PeopleColumns {
        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI =
            Uri.parse("content://contacts/presence");

        /**
         * The ID of the person this presence item is assigned to.
         * <P>Type: INTEGER (long)</P>
         */
        public static final String PERSON_ID = "person";

        /**
         * Gets the resource ID for the proper presence icon.
         * 
         * @param status the status to get the icon for
         * @return the resource ID for the proper presence icon
         */
        public static final int getPresenceIconResourceId(int status) {
            switch (status) {
                case Contacts.People.AVAILABLE:
                    return com.android.internal.R.drawable.presence_online;
    
                case Contacts.People.IDLE:
                case Contacts.People.AWAY:
                    return com.android.internal.R.drawable.presence_away;
    
                case Contacts.People.DO_NOT_DISTURB:
                    return com.android.internal.R.drawable.presence_busy;
    
                case Contacts.People.INVISIBLE:
                    return com.android.internal.R.drawable.presence_invisible;
                    
                case Contacts.People.OFFLINE:
                default:
                    return com.android.internal.R.drawable.presence_offline;
            }
        }

        /**
         * Sets a presence icon to the proper graphic
         *
         * @param icon the icon to to set
         * @param serverStatus that status
         */
        public static final void setPresenceIcon(ImageView icon, int serverStatus) {
            icon.setImageResource(getPresenceIconResourceId(serverStatus));
        }
    }

    /**
     * Columns from the Organizations table that other columns join into themselves.
     */
    public interface OrganizationColumns {
        /**
         * The type of the organizations.
         * <P>Type: INTEGER (one of the constants below)</P>
         */
        public static final String TYPE = "type";

        public static final int TYPE_CUSTOM = 0;
        public static final int TYPE_WORK = 1;
        public static final int TYPE_OTHER = 2;

        /**
         * The user provided label, only used if TYPE is TYPE_CUSTOM.
         * <P>Type: TEXT</P>
         */
        public static final String LABEL = "label";

        /**
         * The name of the company for this organization.
         * <P>Type: TEXT</P>
         */
        public static final String COMPANY = "company";

        /**
         * The title within this organization.
         * <P>Type: TEXT</P>
         */
        public static final String TITLE = "title";

        /**
         * The person this organization is tied to.
         * <P>Type: TEXT</P>
         */
        public static final String PERSON_ID = "person";

        /**
         * Whether this is the primary organization
         * <P>Type: INTEGER (if set, non-0 means true)</P>
         */
        public static final String ISPRIMARY = "isprimary";
    }

    /**
     * A sub directory of a single person that contains all of their Phones.
     */
    public static final class Organizations implements BaseColumns, OrganizationColumns {
        /**
         * no public constructor since this is a utility class
         */
        private Organizations() {}

        public static final CharSequence getDisplayLabel(Context context, int type,
                CharSequence label) {
            CharSequence display = "";

            if (type != TYPE_CUSTOM) {
                CharSequence[] labels = context.getResources().getTextArray(
                        com.android.internal.R.array.organizationTypes);
                try {
                    display = labels[type - 1];
                } catch (ArrayIndexOutOfBoundsException e) {
                    display = labels[Organizations.TYPE_WORK - 1];
                }
            } else {
                if (!TextUtils.isEmpty(label)) {
                    display = label;
                }
            }
            return display;
        }

        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI =
            Uri.parse("content://contacts/organizations");

        /**
         * The directory twig for this sub-table
         */
        public static final String CONTENT_DIRECTORY = "organizations";

        /**
         * The default sort order for this table
         */
        public static final String DEFAULT_SORT_ORDER = "company, title, isprimary ASC";
    }

    /**
     * Columns from the Photos table that other columns join into themselves.
     */
    public interface PhotosColumns {
        /**
         * The _SYNC_VERSION of the photo that was last downloaded
         * <P>Type: TEXT</P>
         */
        public static final String LOCAL_VERSION = "local_version";

        /**
         * The person this photo is associated with.
         * <P>Type: TEXT</P>
         */
        public static final String PERSON_ID = "person";

        /**
         * non-zero if a download is required and the photo isn't marked as a bad resource.
         * You must specify this in the columns in order to use it in the where clause.
         * <P>Type: INTEGER(boolean)</P>
         */
        public static final String DOWNLOAD_REQUIRED = "download_required";

        /**
         * non-zero if this photo is known to exist on the server
         * <P>Type: INTEGER(boolean)</P>
         */
        public static final String EXISTS_ON_SERVER = "exists_on_server";

        /**
         * Contains the description of the upload or download error from
         * the previous attempt. If null then the previous attempt succeeded.
         * <P>Type: TEXT</P>
         */
        public static final String SYNC_ERROR = "sync_error";

        /**
         * The image data, or null if there is no image.
         * <P>Type: BLOB</P>
         */
        public static final String DATA = "data";

    }

    /**
     * The photos over all of the people
     */
    public static final class Photos implements BaseColumns, PhotosColumns, SyncConstValue {
        /**
         * no public constructor since this is a utility class
         */
        private Photos() {}

        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI =
            Uri.parse("content://contacts/photos");

        /**
         * The directory twig for this sub-table
         */
        public static final String CONTENT_DIRECTORY = "photo";

        /**
         * The default sort order for this table
         */
        public static final String DEFAULT_SORT_ORDER = "person ASC";
    }

    public interface ExtensionsColumns {
        /**
         * The name of this extension. May not be null. There may be at most one row for each name.
         * <P>Type: TEXT</P>
         */
        public static final String NAME = "name";

        /**
         * The value of this extension. May not be null.
         * <P>Type: TEXT</P>
         */
        public static final String VALUE = "value";
    }

    /**
     * The extensions for a person
     */
    public static final class Extensions implements BaseColumns, ExtensionsColumns {
        /**
         * no public constructor since this is a utility class
         */
        private Extensions() {}

        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI =
            Uri.parse("content://contacts/extensions");

        /**
         * The MIME type of {@link #CONTENT_URI} providing a directory of
         * phones.
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/contact_extensions";

        /**
         * The MIME type of a {@link #CONTENT_URI} subdirectory of a single
         * phone.
         */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/contact_extensions";
        /**
         * The default sort order for this table
         */
        public static final String DEFAULT_SORT_ORDER = "person, name ASC";

        /**
         * The ID of the person this phone number is assigned to.
         * <P>Type: INTEGER (long)</P>
         */
        public static final String PERSON_ID = "person";
    }

    /**
     * Contains helper classes used to create or manage {@link android.content.Intent Intents}
     * that involve contacts.
     */
    public static final class Intents {
        /**
         * This is the intent that is fired when a search suggestion is clicked on.
         */
        public static final String SEARCH_SUGGESTION_CLICKED =
                "android.provider.Contacts.SEARCH_SUGGESTION_CLICKED";

        /**
         * This is the intent that is fired when a search suggestion for dialing a number 
         * is clicked on.
         */
        public static final String SEARCH_SUGGESTION_DIAL_NUMBER_CLICKED =
                "android.provider.Contacts.SEARCH_SUGGESTION_DIAL_NUMBER_CLICKED";

        /**
         * This is the intent that is fired when a search suggestion for creating a contact
         * is clicked on.
         */
        public static final String SEARCH_SUGGESTION_CREATE_CONTACT_CLICKED =
                "android.provider.Contacts.SEARCH_SUGGESTION_CREATE_CONTACT_CLICKED";

        /**
         * Starts an Activity that lets the user pick a contact to attach an image to.
         * After picking the contact it launches the image cropper in face detection mode.
         */
        public static final String ATTACH_IMAGE =
                "com.android.contacts.action.ATTACH_IMAGE";
        
        /**
         * Intents related to the Contacts app UI.
         */
        public static final class UI {
            /**
             * The action for the default contacts list tab.
             */
            public static final String LIST_DEFAULT =
                    "com.android.contacts.action.LIST_DEFAULT";

            /**
             * The action for the contacts list tab.
             */
            public static final String LIST_GROUP_ACTION =
                    "com.android.contacts.action.LIST_GROUP";

            /**
             * When in LIST_GROUP_ACTION mode, this is the group to display.
             */
            public static final String GROUP_NAME_EXTRA_KEY = "com.android.contacts.extra.GROUP";
            
            /**
             * The action for the all contacts list tab.
             */
            public static final String LIST_ALL_CONTACTS_ACTION =
                    "com.android.contacts.action.LIST_ALL_CONTACTS";

            /**
             * The action for the contacts with phone numbers list tab.
             */
            public static final String LIST_CONTACTS_WITH_PHONES_ACTION =
                    "com.android.contacts.action.LIST_CONTACTS_WITH_PHONES";

            /**
             * The action for the starred contacts list tab.
             */
            public static final String LIST_STARRED_ACTION =
                    "com.android.contacts.action.LIST_STARRED";

            /**
             * The action for the frequent contacts list tab.
             */
            public static final String LIST_FREQUENT_ACTION =
                    "com.android.contacts.action.LIST_FREQUENT";

            /**
             * The action for the "strequent" contacts list tab. It first lists the starred
             * contacts in alphabetical order and then the frequent contacts in descending
             * order of the number of times they have been contacted.
             */
            public static final String LIST_STREQUENT_ACTION =
                    "com.android.contacts.action.LIST_STREQUENT";

            /**
             * A key for to be used as an intent extra to set the activity
             * title to a custom String value.
             */
            public static final String TITLE_EXTRA_KEY =
                "com.android.contacts.extra.TITLE_EXTRA";
            
            /**
             * Activity Action: Display a filtered list of contacts
             * <p>
             * Input: Extra field {@link #FILTER_TEXT_EXTRA_KEY} is the text to use for
             * filtering
             * <p>
             * Output: Nothing.
             */
            public static final String FILTER_CONTACTS_ACTION = 
                "com.android.contacts.action.FILTER_CONTACTS";
            
            /**
             * Used as an int extra field in {@link #FILTER_CONTACTS_ACTION}
             * intents to supply the text on which to filter.
             */
            public static final String FILTER_TEXT_EXTRA_KEY = 
                "com.android.contacts.extra.FILTER_TEXT";
        }

        /**
         * Convenience class that contains string constants used
         * to create contact {@link android.content.Intent Intents}.
         */
        public static final class Insert {
            /** The action code to use when adding a contact */
            public static final String ACTION = Intent.ACTION_INSERT;

            /**
             * If present, forces a bypass of quick insert mode.
             */
            public static final String FULL_MODE = "full_mode";

            /**
             * The extra field for the contact name.
             * <P>Type: String</P>
             */
            public static final String NAME = "name";

            /**
             * The extra field for the contact company.
             * <P>Type: String</P>
             */
            public static final String COMPANY = "company";

            /**
             * The extra field for the contact job title.
             * <P>Type: String</P>
             */
            public static final String JOB_TITLE = "job_title";

            /**
             * The extra field for the contact notes.
             * <P>Type: String</P>
             */
            public static final String NOTES = "notes";

            /**
             * The extra field for the contact phone number.
             * <P>Type: String</P>
             */
            public static final String PHONE = "phone";

            /**
             * The extra field for the contact phone number type.
             * <P>Type: Either an integer value from {@link android.provider.Contacts.PhonesColumns PhonesColumns},
             *  or a string specifying a type and label.</P>
             */
            public static final String PHONE_TYPE = "phone_type";

            /**
             * The extra field for the phone isprimary flag.
             * <P>Type: boolean</P>
             */
            public static final String PHONE_ISPRIMARY = "phone_isprimary";

            /**
             * The extra field for the contact email address.
             * <P>Type: String</P>
             */
            public static final String EMAIL = "email";

            /**
             * The extra field for the contact email type.
             * <P>Type: Either an integer value from {@link android.provider.Contacts.ContactMethodsColumns ContactMethodsColumns}
             *  or a string specifying a type and label.</P>
             */
            public static final String EMAIL_TYPE = "email_type";

            /**
             * The extra field for the email isprimary flag.
             * <P>Type: boolean</P>
             */
            public static final String EMAIL_ISPRIMARY = "email_isprimary";

            /**
             * The extra field for the contact postal address.
             * <P>Type: String</P>
             */
            public static final String POSTAL = "postal";

            /**
             * The extra field for the contact postal address type.
             * <P>Type: Either an integer value from {@link android.provider.Contacts.ContactMethodsColumns ContactMethodsColumns}
             *  or a string specifying a type and label.</P>
             */
            public static final String POSTAL_TYPE = "postal_type";

            /**
             * The extra field for the postal isprimary flag.
             * <P>Type: boolean</P>
             */
            public static final String POSTAL_ISPRIMARY = "postal_isprimary";

            /**
             * The extra field for an IM handle.
             * <P>Type: String</P>
             */
            public static final String IM_HANDLE = "im_handle";

            /**
             * The extra field for the IM protocol
             * <P>Type: the result of {@link Contacts.ContactMethods#encodePredefinedImProtocol}
             * or {@link Contacts.ContactMethods#encodeCustomImProtocol}.</P>
             */
            public static final String IM_PROTOCOL = "im_protocol";

            /**
             * The extra field for the IM isprimary flag.
             * <P>Type: boolean</P>
             */
            public static final String IM_ISPRIMARY = "im_isprimary";
        }
    }
}
