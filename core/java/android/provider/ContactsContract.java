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

import android.accounts.Account;
import android.content.ContentProviderClient;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.CursorEntityIterator;
import android.content.Entity;
import android.content.EntityIterator;
import android.content.Intent;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteException;
import android.graphics.Rect;
import android.net.Uri;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Pair;
import android.view.View;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * <p>
 * The contract between the contacts provider and applications. Contains
 * definitions for the supported URIs and columns. These APIs supersede
 * {@link Contacts}.
 * </p>
 * <h3>Overview</h3>
 * <p>
 * ContactsContract defines an extensible database of contact-related
 * information. Contact information is stored in a three-tier data model:
 * </p>
 * <ul>
 * <li>
 * A row in the {@link Data} table can store any kind of personal data, such
 * as a phone number or email addresses.  The set of data kinds that can be
 * stored in this table is open-ended. There is a predefined set of common
 * kinds, but any application can add its own data kinds.
 * </li>
 * <li>
 * A row in the {@link RawContacts} table represents a set of data describing a
 * person and associated with a single account (for example, one of the user's
 * Gmail accounts).
 * </li>
 * <li>
 * A row in the {@link Contacts} table represents an aggregate of one or more
 * RawContacts presumably describing the same person.  When data in or associated with
 * the RawContacts table is changed, the affected aggregate contacts are updated as
 * necessary.
 * </li>
 * </ul>
 * <p>
 * Other tables include:
 * </p>
 * <ul>
 * <li>
 * {@link Groups}, which contains information about raw contact groups
 * such as Gmail contact groups.  The
 * current API does not support the notion of groups spanning multiple accounts.
 * </li>
 * <li>
 * {@link StatusUpdates}, which contains social status updates including IM
 * availability.
 * </li>
 * <li>
 * {@link AggregationExceptions}, which is used for manual aggregation and
 * disaggregation of raw contacts
 * </li>
 * <li>
 * {@link Settings}, which contains visibility and sync settings for accounts
 * and groups.
 * </li>
 * <li>
 * {@link SyncState}, which contains free-form data maintained on behalf of sync
 * adapters
 * </li>
 * <li>
 * {@link PhoneLookup}, which is used for quick caller-ID lookup</li>
 * </ul>
 */
@SuppressWarnings("unused")
public final class ContactsContract {
    /** The authority for the contacts provider */
    public static final String AUTHORITY = "com.android.contacts";
    /** A content:// style uri to the authority for the contacts provider */
    public static final Uri AUTHORITY_URI = Uri.parse("content://" + AUTHORITY);

    /**
     * An optional URI parameter for insert, update, or delete queries
     * that allows the caller
     * to specify that it is a sync adapter. The default value is false. If true
     * {@link RawContacts#DIRTY} is not automatically set and the
     * "syncToNetwork" parameter is set to false when calling
     * {@link
     * ContentResolver#notifyChange(android.net.Uri, android.database.ContentObserver, boolean)}.
     * This prevents an unnecessary extra synchronization, see the discussion of
     * the delete operation in {@link RawContacts}.
     */
    public static final String CALLER_IS_SYNCADAPTER = "caller_is_syncadapter";

    /**
     * A query parameter key used to specify the package that is requesting a query.
     * This is used for restricting data based on package name.
     *
     * @hide
     */
    public static final String REQUESTING_PACKAGE_PARAM_KEY = "requesting_package";

    /**
     * @hide
     */
    public static final class Preferences {

        /**
         * A key in the {@link android.provider.Settings android.provider.Settings} provider
         * that stores the preferred sorting order for contacts (by given name vs. by family name).
         *
         * @hide
         */
        public static final String SORT_ORDER = "android.contacts.SORT_ORDER";

        /**
         * The value for the SORT_ORDER key corresponding to sorting by given name first.
         *
         * @hide
         */
        public static final int SORT_ORDER_PRIMARY = 1;

        /**
         * The value for the SORT_ORDER key corresponding to sorting by family name first.
         *
         * @hide
         */
        public static final int SORT_ORDER_ALTERNATIVE = 2;

        /**
         * A key in the {@link android.provider.Settings android.provider.Settings} provider
         * that stores the preferred display order for contacts (given name first vs. family
         * name first).
         *
         * @hide
         */
        public static final String DISPLAY_ORDER = "android.contacts.DISPLAY_ORDER";

        /**
         * The value for the DISPLAY_ORDER key corresponding to showing the given name first.
         *
         * @hide
         */
        public static final int DISPLAY_ORDER_PRIMARY = 1;

        /**
         * The value for the DISPLAY_ORDER key corresponding to showing the family name first.
         *
         * @hide
         */
        public static final int DISPLAY_ORDER_ALTERNATIVE = 2;
    }

    /**
     * @hide should be removed when users are updated to refer to SyncState
     * @deprecated use SyncState instead
     */
    @Deprecated
    public interface SyncStateColumns extends SyncStateContract.Columns {
    }

    /**
     * A table provided for sync adapters to use for storing private sync state data.
     *
     * @see SyncStateContract
     */
    public static final class SyncState implements SyncStateContract.Columns {
        /**
         * This utility class cannot be instantiated
         */
        private SyncState() {}

        public static final String CONTENT_DIRECTORY =
                SyncStateContract.Constants.CONTENT_DIRECTORY;

        /**
         * The content:// style URI for this table
         */
        public static final Uri CONTENT_URI =
                Uri.withAppendedPath(AUTHORITY_URI, CONTENT_DIRECTORY);

        /**
         * @see android.provider.SyncStateContract.Helpers#get
         */
        public static byte[] get(ContentProviderClient provider, Account account)
                throws RemoteException {
            return SyncStateContract.Helpers.get(provider, CONTENT_URI, account);
        }

        /**
         * @see android.provider.SyncStateContract.Helpers#get
         */
        public static Pair<Uri, byte[]> getWithUri(ContentProviderClient provider, Account account)
                throws RemoteException {
            return SyncStateContract.Helpers.getWithUri(provider, CONTENT_URI, account);
        }

        /**
         * @see android.provider.SyncStateContract.Helpers#set
         */
        public static void set(ContentProviderClient provider, Account account, byte[] data)
                throws RemoteException {
            SyncStateContract.Helpers.set(provider, CONTENT_URI, account, data);
        }

        /**
         * @see android.provider.SyncStateContract.Helpers#newSetOperation
         */
        public static ContentProviderOperation newSetOperation(Account account, byte[] data) {
            return SyncStateContract.Helpers.newSetOperation(CONTENT_URI, account, data);
        }
    }

    /**
     * Generic columns for use by sync adapters. The specific functions of
     * these columns are private to the sync adapter. Other clients of the API
     * should not attempt to either read or write this column.
     *
     * @see RawContacts
     * @see Groups
     */
    protected interface BaseSyncColumns {

        /** Generic column for use by sync adapters. */
        public static final String SYNC1 = "sync1";
        /** Generic column for use by sync adapters. */
        public static final String SYNC2 = "sync2";
        /** Generic column for use by sync adapters. */
        public static final String SYNC3 = "sync3";
        /** Generic column for use by sync adapters. */
        public static final String SYNC4 = "sync4";
    }

    /**
     * Columns that appear when each row of a table belongs to a specific
     * account, including sync information that an account may need.
     *
     * @see RawContacts
     * @see Groups
     */
    protected interface SyncColumns extends BaseSyncColumns {
        /**
         * The name of the account instance to which this row belongs, which when paired with
         * {@link #ACCOUNT_TYPE} identifies a specific account.
         * <P>Type: TEXT</P>
         */
        public static final String ACCOUNT_NAME = "account_name";

        /**
         * The type of account to which this row belongs, which when paired with
         * {@link #ACCOUNT_NAME} identifies a specific account.
         * <P>Type: TEXT</P>
         */
        public static final String ACCOUNT_TYPE = "account_type";

        /**
         * String that uniquely identifies this row to its source account.
         * <P>Type: TEXT</P>
         */
        public static final String SOURCE_ID = "sourceid";

        /**
         * Version number that is updated whenever this row or its related data
         * changes.
         * <P>Type: INTEGER</P>
         */
        public static final String VERSION = "version";

        /**
         * Flag indicating that {@link #VERSION} has changed, and this row needs
         * to be synchronized by its owning account.
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String DIRTY = "dirty";
    }

    /**
     * Columns of {@link ContactsContract.Contacts} that track the user's
     * preferences for, or interactions with, the contact.
     *
     * @see Contacts
     * @see RawContacts
     * @see ContactsContract.Data
     * @see PhoneLookup
     * @see ContactsContract.Contacts.AggregationSuggestions
     */
    protected interface ContactOptionsColumns {
        /**
         * The number of times a contact has been contacted
         * <P>Type: INTEGER</P>
         */
        public static final String TIMES_CONTACTED = "times_contacted";

        /**
         * The last time a contact was contacted.
         * <P>Type: INTEGER</P>
         */
        public static final String LAST_TIME_CONTACTED = "last_time_contacted";

        /**
         * Is the contact starred?
         * <P>Type: INTEGER (boolean)</P>
         */
        public static final String STARRED = "starred";

        /**
         * URI for a custom ringtone associated with the contact. If null or missing,
         * the default ringtone is used.
         * <P>Type: TEXT (URI to the ringtone)</P>
         */
        public static final String CUSTOM_RINGTONE = "custom_ringtone";

        /**
         * Whether the contact should always be sent to voicemail. If missing,
         * defaults to false.
         * <P>Type: INTEGER (0 for false, 1 for true)</P>
         */
        public static final String SEND_TO_VOICEMAIL = "send_to_voicemail";
    }

    /**
     * Columns of {@link ContactsContract.Contacts} that refer to intrinsic
     * properties of the contact, as opposed to the user-specified options
     * found in {@link ContactOptionsColumns}.
     *
     * @see Contacts
     * @see ContactsContract.Data
     * @see PhoneLookup
     * @see ContactsContract.Contacts.AggregationSuggestions
     */
    protected interface ContactsColumns {
        /**
         * The display name for the contact.
         * <P>Type: TEXT</P>
         */
        public static final String DISPLAY_NAME = ContactNameColumns.DISPLAY_NAME_PRIMARY;

        /**
         * Reference to the row in the RawContacts table holding the contact name.
         * <P>Type: INTEGER REFERENCES raw_contacts(_id)</P>
         * @hide
         */
        public static final String NAME_RAW_CONTACT_ID = "name_raw_contact_id";

        /**
         * Reference to the row in the data table holding the photo.
         * <P>Type: INTEGER REFERENCES data(_id)</P>
         */
        public static final String PHOTO_ID = "photo_id";

        /**
         * Lookup value that reflects the {@link Groups#GROUP_VISIBLE} state of
         * any {@link CommonDataKinds.GroupMembership} for this contact.
         */
        public static final String IN_VISIBLE_GROUP = "in_visible_group";

        /**
         * An indicator of whether this contact has at least one phone number. "1" if there is
         * at least one phone number, "0" otherwise.
         * <P>Type: INTEGER</P>
         */
        public static final String HAS_PHONE_NUMBER = "has_phone_number";

        /**
         * An opaque value that contains hints on how to find the contact if
         * its row id changed as a result of a sync or aggregation.
         */
        public static final String LOOKUP_KEY = "lookup";
    }

    /**
     * @see Contacts
     */
    protected interface ContactStatusColumns {
        /**
         * Contact presence status. See {@link StatusUpdates} for individual status
         * definitions.
         * <p>Type: NUMBER</p>
         */
        public static final String CONTACT_PRESENCE = "contact_presence";

        /**
         * Contact's latest status update.
         * <p>Type: TEXT</p>
         */
        public static final String CONTACT_STATUS = "contact_status";

        /**
         * The absolute time in milliseconds when the latest status was
         * inserted/updated.
         * <p>Type: NUMBER</p>
         */
        public static final String CONTACT_STATUS_TIMESTAMP = "contact_status_ts";

        /**
         * The package containing resources for this status: label and icon.
         * <p>Type: TEXT</p>
         */
        public static final String CONTACT_STATUS_RES_PACKAGE = "contact_status_res_package";

        /**
         * The resource ID of the label describing the source of contact
         * status, e.g. "Google Talk". This resource is scoped by the
         * {@link #CONTACT_STATUS_RES_PACKAGE}.
         * <p>Type: NUMBER</p>
         */
        public static final String CONTACT_STATUS_LABEL = "contact_status_label";

        /**
         * The resource ID of the icon for the source of contact status. This
         * resource is scoped by the {@link #CONTACT_STATUS_RES_PACKAGE}.
         * <p>Type: NUMBER</p>
         */
        public static final String CONTACT_STATUS_ICON = "contact_status_icon";
    }

    /**
     * Constants for various styles of combining given name, family name etc into
     * a full name.  For example, the western tradition follows the pattern
     * 'given name' 'middle name' 'family name' with the alternative pattern being
     * 'family name', 'given name' 'middle name'.  The CJK tradition is
     * 'family name' 'middle name' 'given name', with Japanese favoring a space between
     * the names and Chinese omitting the space.
     * @hide
     */
    public interface FullNameStyle {
        public static final int UNDEFINED = 0;
        public static final int WESTERN = 1;

        /**
         * Used if the name is written in Hanzi/Kanji/Hanja and we could not determine
         * which specific language it belongs to: Chinese, Japanese or Korean.
         */
        public static final int CJK = 2;

        public static final int CHINESE = 3;
        public static final int JAPANESE = 4;
        public static final int KOREAN = 5;
    }

    /**
     * Constants for various styles of capturing the pronunciation of a person's name.
     * @hide
     */
    public interface PhoneticNameStyle {
        public static final int UNDEFINED = 0;

        /**
         * Pinyin is a phonetic method of entering Chinese characters. Typically not explicitly
         * shown in UIs, but used for searches and sorting.
         */
        public static final int PINYIN = 3;

        /**
         * Hiragana and Katakana are two common styles of writing out the pronunciation
         * of a Japanese names.
         */
        public static final int JAPANESE = 4;

        /**
         * Hangul is the Korean phonetic alphabet.
         */
        public static final int KOREAN = 5;
    }

    /**
     * Types of data used to produce the display name for a contact. Listed in the order
     * of increasing priority.
     *
     * @hide
     */
    public interface DisplayNameSources {
        public static final int UNDEFINED = 0;
        public static final int EMAIL = 10;
        public static final int PHONE = 20;
        public static final int ORGANIZATION = 30;
        public static final int NICKNAME = 35;
        public static final int STRUCTURED_NAME = 40;
    }

    /**
     * Contact name and contact name metadata columns in the RawContacts table.
     *
     * @see Contacts
     * @see RawContacts
     * @hide
     */
    protected interface ContactNameColumns {

        /**
         * The kind of data that is used as the display name for the contact, such as
         * structured name or email address.  See DisplayNameSources.
         *
         * TODO: convert DisplayNameSources to a link after it is un-hidden
         */
        public static final String DISPLAY_NAME_SOURCE = "display_name_source";

        /**
         * <p>
         * The standard text shown as the contact's display name, based on the best
         * available information for the contact (for example, it might be the email address
         * if the name is not available).
         * The information actually used to compute the name is stored in
         * {@link #DISPLAY_NAME_SOURCE}.
         * </p>
         * <p>
         * A contacts provider is free to choose whatever representation makes most
         * sense for its target market.
         * For example in the default Android Open Source Project implementation,
         * if the display name is
         * based on the structured name and the structured name follows
         * the Western full-name style, then this field contains the "given name first"
         * version of the full name.
         * <p>
         *
         * @see ContactsContract.ContactNameColumns#DISPLAY_NAME_ALTERNATIVE
         */
        public static final String DISPLAY_NAME_PRIMARY = "display_name";

        /**
         * <p>
         * An alternative representation of the display name, such as "family name first"
         * instead of "given name first" for Western names.  If an alternative is not
         * available, the values should be the same as {@link #DISPLAY_NAME_PRIMARY}.
         * </p>
         * <p>
         * A contacts provider is free to provide alternatives as necessary for
         * its target market.
         * For example the default Android Open Source Project contacts provider
         * currently provides an
         * alternative in a single case:  if the display name is
         * based on the structured name and the structured name follows
         * the Western full name style, then the field contains the "family name first"
         * version of the full name.
         * Other cases may be added later.
         * </p>
         */
        public static final String DISPLAY_NAME_ALTERNATIVE = "display_name_alt";

        /**
         * The phonetic alphabet used to represent the {@link #PHONETIC_NAME}.  See
         * PhoneticNameStyle.
         *
         * TODO: convert PhoneticNameStyle to a link after it is un-hidden
         */
        public static final String PHONETIC_NAME_STYLE = "phonetic_name_style";

        /**
         * <p>
         * Pronunciation of the full name in the phonetic alphabet specified by
         * {@link #PHONETIC_NAME_STYLE}.
         * </p>
         * <p>
         * The value may be set manually by the user.
         * This capability is is of interest only in countries
         * with commonly used phonetic
         * alphabets, such as Japan and Korea.  See PhoneticNameStyle.
         * </p>
         *
         * TODO: convert PhoneticNameStyle to a link after it is un-hidden
         */
        public static final String PHONETIC_NAME = "phonetic_name";

        /**
         * Sort key that takes into account locale-based traditions for sorting
         * names in address books.  The default
         * sort key is {@link #DISPLAY_NAME_PRIMARY}.  For Chinese names
         * the sort key is the name's Pinyin spelling, and for Japanese names
         * it is the Hiragana version of the phonetic name.
         */
        public static final String SORT_KEY_PRIMARY = "sort_key";

        /**
         * Sort key based on the alternative representation of the full name,
         * {@link #DISPLAY_NAME_ALTERNATIVE}.  Thus for Western names,
         * it is the one using the "family name first" format.
         */
        public static final String SORT_KEY_ALTERNATIVE = "sort_key_alt";
    }

    /**
     * URI parameter and cursor extras that return counts of rows grouped by the
     * address book index, which is usually the first letter of the sort key.
     * When this parameter is supplied, the row counts are returned in the
     * cursor extras bundle.
     *
     * @hide
     */
    public final static class ContactCounts {

        /**
         * Add this query parameter to a URI to get back row counts grouped by
         * the address book index as cursor extras. For most languages it is the
         * first letter of the sort key. This parameter does not affect the main
         * content of the cursor.
         *
         * @hide
         */
        public static final String ADDRESS_BOOK_INDEX_EXTRAS = "address_book_index_extras";

        /**
         * The array of address book index titles, which are returned in the
         * same order as the data in the cursor.
         * <p>TYPE: String[]</p>
         *
         * @hide
         */
        public static final String EXTRA_ADDRESS_BOOK_INDEX_TITLES = "address_book_index_titles";

        /**
         * The array of group counts for the corresponding group.  Contains the same number
         * of elements as the EXTRA_ADDRESS_BOOK_INDEX_TITLES array.
         * <p>TYPE: int[]</p>
         *
         * @hide
         */
        public static final String EXTRA_ADDRESS_BOOK_INDEX_COUNTS = "address_book_index_counts";
    }

    /**
     * Constants for the contacts table, which contains a record per aggregate
     * of raw contacts representing the same person.
     * <h3>Operations</h3>
     * <dl>
     * <dt><b>Insert</b></dt>
     * <dd>A Contact cannot be created explicitly. When a raw contact is
     * inserted, the provider will first try to find a Contact representing the
     * same person. If one is found, the raw contact's
     * {@link RawContacts#CONTACT_ID} column gets the _ID of the aggregate
     * Contact. If no match is found, the provider automatically inserts a new
     * Contact and puts its _ID into the {@link RawContacts#CONTACT_ID} column
     * of the newly inserted raw contact.</dd>
     * <dt><b>Update</b></dt>
     * <dd>Only certain columns of Contact are modifiable:
     * {@link #TIMES_CONTACTED}, {@link #LAST_TIME_CONTACTED}, {@link #STARRED},
     * {@link #CUSTOM_RINGTONE}, {@link #SEND_TO_VOICEMAIL}. Changing any of
     * these columns on the Contact also changes them on all constituent raw
     * contacts.</dd>
     * <dt><b>Delete</b></dt>
     * <dd>Be careful with deleting Contacts! Deleting an aggregate contact
     * deletes all constituent raw contacts. The corresponding sync adapters
     * will notice the deletions of their respective raw contacts and remove
     * them from their back end storage.</dd>
     * <dt><b>Query</b></dt>
     * <dd>
     * <ul>
     * <li>If you need to read an individual contact, consider using
     * {@link #CONTENT_LOOKUP_URI} instead of {@link #CONTENT_URI}.</li>
     * <li>If you need to look up a contact by the phone number, use
     * {@link PhoneLookup#CONTENT_FILTER_URI PhoneLookup.CONTENT_FILTER_URI},
     * which is optimized for this purpose.</li>
     * <li>If you need to look up a contact by partial name, e.g. to produce
     * filter-as-you-type suggestions, use the {@link #CONTENT_FILTER_URI} URI.
     * <li>If you need to look up a contact by some data element like email
     * address, nickname, etc, use a query against the {@link ContactsContract.Data} table.
     * The result will contain contact ID, name etc.
     * </ul>
     * </dd>
     * </dl>
     * <h2>Columns</h2>
     * <table class="jd-sumtable">
     * <tr>
     * <th colspan='4'>Contacts</th>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #_ID}</td>
     * <td>read-only</td>
     * <td>Row ID. Consider using {@link #LOOKUP_KEY} instead.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #LOOKUP_KEY}</td>
     * <td>read-only</td>
     * <td>An opaque value that contains hints on how to find the contact if its
     * row id changed as a result of a sync or aggregation.</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>NAME_RAW_CONTACT_ID</td>
     * <td>read-only</td>
     * <td>The ID of the raw contact that contributes the display name
     * to the aggregate contact. During aggregation one of the constituent
     * raw contacts is chosen using a heuristic: a longer name or a name
     * with more diacritic marks or more upper case characters is chosen.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>DISPLAY_NAME_PRIMARY</td>
     * <td>read-only</td>
     * <td>The display name for the contact. It is the display name
     * contributed by the raw contact referred to by the NAME_RAW_CONTACT_ID
     * column.</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #PHOTO_ID}</td>
     * <td>read-only</td>
     * <td>Reference to the row in the {@link ContactsContract.Data} table holding the photo.
     * That row has the mime type
     * {@link CommonDataKinds.Photo#CONTENT_ITEM_TYPE}. The value of this field
     * is computed automatically based on the
     * {@link CommonDataKinds.Photo#IS_SUPER_PRIMARY} field of the data rows of
     * that mime type.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #IN_VISIBLE_GROUP}</td>
     * <td>read-only</td>
     * <td>An indicator of whether this contact is supposed to be visible in the
     * UI. "1" if the contact has at least one raw contact that belongs to a
     * visible group; "0" otherwise.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #HAS_PHONE_NUMBER}</td>
     * <td>read-only</td>
     * <td>An indicator of whether this contact has at least one phone number.
     * "1" if there is at least one phone number, "0" otherwise.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #TIMES_CONTACTED}</td>
     * <td>read/write</td>
     * <td>The number of times the contact has been contacted. See
     * {@link #markAsContacted}. When raw contacts are aggregated, this field is
     * computed automatically as the maximum number of times contacted among all
     * constituent raw contacts. Setting this field automatically changes the
     * corresponding field on all constituent raw contacts.</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #LAST_TIME_CONTACTED}</td>
     * <td>read/write</td>
     * <td>The timestamp of the last time the contact was contacted. See
     * {@link #markAsContacted}. Setting this field also automatically
     * increments {@link #TIMES_CONTACTED}. When raw contacts are aggregated,
     * this field is computed automatically as the latest time contacted of all
     * constituent raw contacts. Setting this field automatically changes the
     * corresponding field on all constituent raw contacts.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #STARRED}</td>
     * <td>read/write</td>
     * <td>An indicator for favorite contacts: '1' if favorite, '0' otherwise.
     * When raw contacts are aggregated, this field is automatically computed:
     * if any constituent raw contacts are starred, then this field is set to
     * '1'. Setting this field automatically changes the corresponding field on
     * all constituent raw contacts.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #CUSTOM_RINGTONE}</td>
     * <td>read/write</td>
     * <td>A custom ringtone associated with a contact. Typically this is the
     * URI returned by an activity launched with the
     * {@link android.media.RingtoneManager#ACTION_RINGTONE_PICKER} intent.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #SEND_TO_VOICEMAIL}</td>
     * <td>read/write</td>
     * <td>An indicator of whether calls from this contact should be forwarded
     * directly to voice mail ('1') or not ('0'). When raw contacts are
     * aggregated, this field is automatically computed: if <i>all</i>
     * constituent raw contacts have SEND_TO_VOICEMAIL=1, then this field is set
     * to '1'. Setting this field automatically changes the corresponding field
     * on all constituent raw contacts.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #CONTACT_PRESENCE}</td>
     * <td>read-only</td>
     * <td>Contact IM presence status. See {@link StatusUpdates} for individual
     * status definitions. Automatically computed as the highest presence of all
     * constituent raw contacts. The provider may choose not to store this value
     * in persistent storage. The expectation is that presence status will be
     * updated on a regular basic.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #CONTACT_STATUS}</td>
     * <td>read-only</td>
     * <td>Contact's latest status update. Automatically computed as the latest
     * of all constituent raw contacts' status updates.</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #CONTACT_STATUS_TIMESTAMP}</td>
     * <td>read-only</td>
     * <td>The absolute time in milliseconds when the latest status was
     * inserted/updated.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #CONTACT_STATUS_RES_PACKAGE}</td>
     * <td>read-only</td>
     * <td> The package containing resources for this status: label and icon.</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #CONTACT_STATUS_LABEL}</td>
     * <td>read-only</td>
     * <td>The resource ID of the label describing the source of contact status,
     * e.g. "Google Talk". This resource is scoped by the
     * {@link #CONTACT_STATUS_RES_PACKAGE}.</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #CONTACT_STATUS_ICON}</td>
     * <td>read-only</td>
     * <td>The resource ID of the icon for the source of contact status. This
     * resource is scoped by the {@link #CONTACT_STATUS_RES_PACKAGE}.</td>
     * </tr>
     * </table>
     */
    public static class Contacts implements BaseColumns, ContactsColumns,
            ContactOptionsColumns, ContactNameColumns, ContactStatusColumns {
        /**
         * This utility class cannot be instantiated
         */
        private Contacts()  {}

        /**
         * The content:// style URI for this table
         */
        public static final Uri CONTENT_URI = Uri.withAppendedPath(AUTHORITY_URI, "contacts");

        /**
         * A content:// style URI for this table that should be used to create
         * shortcuts or otherwise create long-term links to contacts. This URI
         * should always be followed by a "/" and the contact's {@link #LOOKUP_KEY}.
         * It can optionally also have a "/" and last known contact ID appended after
         * that. This "complete" format is an important optimization and is highly recommended.
         * <p>
         * As long as the contact's row ID remains the same, this URI is
         * equivalent to {@link #CONTENT_URI}. If the contact's row ID changes
         * as a result of a sync or aggregation, this URI will look up the
         * contact using indirect information (sync IDs or constituent raw
         * contacts).
         * <p>
         * Lookup key should be appended unencoded - it is stored in the encoded
         * form, ready for use in a URI.
         */
        public static final Uri CONTENT_LOOKUP_URI = Uri.withAppendedPath(CONTENT_URI,
                "lookup");

        /**
         * Base {@link Uri} for referencing a single {@link Contacts} entry,
         * created by appending {@link #LOOKUP_KEY} using
         * {@link Uri#withAppendedPath(Uri, String)}. Provides
         * {@link OpenableColumns} columns when queried, or returns the
         * referenced contact formatted as a vCard when opened through
         * {@link ContentResolver#openAssetFileDescriptor(Uri, String)}.
         */
        public static final Uri CONTENT_VCARD_URI = Uri.withAppendedPath(CONTENT_URI,
                "as_vcard");

        /**
         * Base {@link Uri} for referencing multiple {@link Contacts} entry,
         * created by appending {@link #LOOKUP_KEY} using
         * {@link Uri#withAppendedPath(Uri, String)}. The lookup keys have to be
         * encoded and joined with the colon (":") seperator. The resulting string
         * has to be encoded again. Provides
         * {@link OpenableColumns} columns when queried, or returns the
         * referenced contact formatted as a vCard when opened through
         * {@link ContentResolver#openAssetFileDescriptor(Uri, String)}.
         *
         * This is private API because we do not have a well-defined way to
         * specify several entities yet. The format of this Uri might change in the future
         * or the Uri might be completely removed.
         *
         * @hide
         */
        public static final Uri CONTENT_MULTI_VCARD_URI = Uri.withAppendedPath(CONTENT_URI,
                "as_multi_vcard");

        /**
         * Builds a {@link #CONTENT_LOOKUP_URI} style {@link Uri} describing the
         * requested {@link Contacts} entry.
         *
         * @param contactUri A {@link #CONTENT_URI} row, or an existing
         *            {@link #CONTENT_LOOKUP_URI} to attempt refreshing.
         */
        public static Uri getLookupUri(ContentResolver resolver, Uri contactUri) {
            final Cursor c = resolver.query(contactUri, new String[] {
                    Contacts.LOOKUP_KEY, Contacts._ID
            }, null, null, null);
            if (c == null) {
                return null;
            }

            try {
                if (c.moveToFirst()) {
                    final String lookupKey = c.getString(0);
                    final long contactId = c.getLong(1);
                    return getLookupUri(contactId, lookupKey);
                }
            } finally {
                c.close();
            }
            return null;
        }

        /**
         * Build a {@link #CONTENT_LOOKUP_URI} lookup {@link Uri} using the
         * given {@link ContactsContract.Contacts#_ID} and {@link #LOOKUP_KEY}.
         */
        public static Uri getLookupUri(long contactId, String lookupKey) {
            return ContentUris.withAppendedId(Uri.withAppendedPath(Contacts.CONTENT_LOOKUP_URI,
                    lookupKey), contactId);
        }

        /**
         * Computes a content URI (see {@link #CONTENT_URI}) given a lookup URI.
         * <p>
         * Returns null if the contact cannot be found.
         */
        public static Uri lookupContact(ContentResolver resolver, Uri lookupUri) {
            if (lookupUri == null) {
                return null;
            }

            Cursor c = resolver.query(lookupUri, new String[]{Contacts._ID}, null, null, null);
            if (c == null) {
                return null;
            }

            try {
                if (c.moveToFirst()) {
                    long contactId = c.getLong(0);
                    return ContentUris.withAppendedId(Contacts.CONTENT_URI, contactId);
                }
            } finally {
                c.close();
            }
            return null;
        }

        /**
         * Mark a contact as having been contacted.  This updates the
         * {@link #TIMES_CONTACTED} and {@link #LAST_TIME_CONTACTED} for the
         * contact, plus the corresponding values of any associated raw
         * contacts.
         *
         * @param resolver the ContentResolver to use
         * @param contactId the person who was contacted
         */
        public static void markAsContacted(ContentResolver resolver, long contactId) {
            Uri uri = ContentUris.withAppendedId(CONTENT_URI, contactId);
            ContentValues values = new ContentValues();
            // TIMES_CONTACTED will be incremented when LAST_TIME_CONTACTED is modified.
            values.put(LAST_TIME_CONTACTED, System.currentTimeMillis());
            resolver.update(uri, values, null, null);
        }

        /**
         * The content:// style URI used for "type-to-filter" functionality on the
         * {@link #CONTENT_URI} URI. The filter string will be used to match
         * various parts of the contact name. The filter argument should be passed
         * as an additional path segment after this URI.
         */
        public static final Uri CONTENT_FILTER_URI = Uri.withAppendedPath(
                CONTENT_URI, "filter");

        /**
         * The content:// style URI for this table joined with useful data from
         * {@link ContactsContract.Data}, filtered to include only starred contacts
         * and the most frequently contacted contacts.
         */
        public static final Uri CONTENT_STREQUENT_URI = Uri.withAppendedPath(
                CONTENT_URI, "strequent");

        /**
         * The content:// style URI used for "type-to-filter" functionality on the
         * {@link #CONTENT_STREQUENT_URI} URI. The filter string will be used to match
         * various parts of the contact name. The filter argument should be passed
         * as an additional path segment after this URI.
         */
        public static final Uri CONTENT_STREQUENT_FILTER_URI = Uri.withAppendedPath(
                CONTENT_STREQUENT_URI, "filter");

        public static final Uri CONTENT_GROUP_URI = Uri.withAppendedPath(
                CONTENT_URI, "group");

        /**
         * The MIME type of {@link #CONTENT_URI} providing a directory of
         * people.
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/contact";

        /**
         * The MIME type of a {@link #CONTENT_URI} subdirectory of a single
         * person.
         */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/contact";

        /**
         * The MIME type of a {@link #CONTENT_URI} subdirectory of a single
         * person.
         */
        public static final String CONTENT_VCARD_TYPE = "text/x-vcard";

        /**
         * A sub-directory of a single contact that contains all of the constituent raw contact
         * {@link ContactsContract.Data} rows.
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

        /**
         * <p>
         * A <i>read-only</i> sub-directory of a single contact aggregate that
         * contains all aggregation suggestions (other contacts). The
         * aggregation suggestions are computed based on approximate data
         * matches with this contact.
         * </p>
         * <p>
         * <i>Note: this query may be expensive! If you need to use it in bulk,
         * make sure the user experience is acceptable when the query runs for a
         * long time.</i>
         * <p>
         * Usage example:
         *
         * <pre>
         * Uri uri = Contacts.CONTENT_URI.buildUpon()
         *          .appendEncodedPath(String.valueOf(contactId))
         *          .appendPath(Contacts.AggregationSuggestions.CONTENT_DIRECTORY)
         *          .appendQueryParameter(&quot;limit&quot;, &quot;3&quot;)
         *          .build()
         * Cursor cursor = getContentResolver().query(suggestionsUri,
         *          new String[] {Contacts.DISPLAY_NAME, Contacts._ID, Contacts.LOOKUP_KEY},
         *          null, null, null);
         * </pre>
         *
         * </p>
         */
        // TODO: add ContactOptionsColumns, ContactStatusColumns
        public static final class AggregationSuggestions implements BaseColumns, ContactsColumns {
            /**
             * No public constructor since this is a utility class
             */
            private AggregationSuggestions() {}

            /**
             * The directory twig for this sub-table. The URI can be followed by an optional
             * type-to-filter, similar to
             * {@link android.provider.ContactsContract.Contacts#CONTENT_FILTER_URI}.
             */
            public static final String CONTENT_DIRECTORY = "suggestions";
        }

        /**
         * A <i>read-only</i> sub-directory of a single contact that contains
         * the contact's primary photo.
         * <p>
         * Usage example:
         *
         * <pre>
         * public InputStream openPhoto(long contactId) {
         *     Uri contactUri = ContentUris.withAppendedId(Contacts.CONTENT_URI, contactId);
         *     Uri photoUri = Uri.withAppendedPath(contactUri, Contacts.Photo.CONTENT_DIRECTORY);
         *     Cursor cursor = getContentResolver().query(photoUri,
         *          new String[] {Contacts.Photo.PHOTO}, null, null, null);
         *     if (cursor == null) {
         *         return null;
         *     }
         *     try {
         *         if (cursor.moveToFirst()) {
         *             byte[] data = cursor.getBlob(0);
         *             if (data != null) {
         *                 return new ByteArrayInputStream(data);
         *             }
         *         }
         *     } finally {
         *         cursor.close();
         *     }
         *     return null;
         * }
         * </pre>
         *
         * </p>
         * <p>You should also consider using the convenience method
         * {@link ContactsContract.Contacts#openContactPhotoInputStream(ContentResolver, Uri)}
         * </p>
         */
        // TODO: change DataColumns to DataColumnsWithJoins
        public static final class Photo implements BaseColumns, DataColumns {
            /**
             * no public constructor since this is a utility class
             */
            private Photo() {}

            /**
             * The directory twig for this sub-table
             */
            public static final String CONTENT_DIRECTORY = "photo";

            /**
             * Thumbnail photo of the raw contact. This is the raw bytes of an image
             * that could be inflated using {@link android.graphics.BitmapFactory}.
             * <p>
             * Type: BLOB
             * @hide TODO: Unhide in a separate CL
             */
            public static final String PHOTO = DATA15;
        }

        /**
         * Opens an InputStream for the contacts's default photo and returns the
         * photo as a byte stream. If there is not photo null will be returned.
         *
         * @param contactUri the contact whose photo should be used
         * @return an InputStream of the photo, or null if no photo is present
         */
        public static InputStream openContactPhotoInputStream(ContentResolver cr, Uri contactUri) {
            Uri photoUri = Uri.withAppendedPath(contactUri, Photo.CONTENT_DIRECTORY);
            if (photoUri == null) {
                return null;
            }
            Cursor cursor = cr.query(photoUri,
                    new String[]{ContactsContract.CommonDataKinds.Photo.PHOTO}, null, null, null);
            try {
                if (cursor == null || !cursor.moveToNext()) {
                    return null;
                }
                byte[] data = cursor.getBlob(0);
                if (data == null) {
                    return null;
                }
                return new ByteArrayInputStream(data);
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
    }

    protected interface RawContactsColumns {
        /**
         * A reference to the {@link ContactsContract.Contacts#_ID} that this
         * data belongs to.
         * <P>Type: INTEGER</P>
         */
        public static final String CONTACT_ID = "contact_id";

        /**
         * Flag indicating that this {@link RawContacts} entry and its children have
         * been restricted to specific platform apps.
         * <P>Type: INTEGER (boolean)</P>
         *
         * @hide until finalized in future platform release
         */
        public static final String IS_RESTRICTED = "is_restricted";

        /**
         * The aggregation mode for this contact.
         * <P>Type: INTEGER</P>
         */
        public static final String AGGREGATION_MODE = "aggregation_mode";

        /**
         * The "deleted" flag: "0" by default, "1" if the row has been marked
         * for deletion. When {@link android.content.ContentResolver#delete} is
         * called on a raw contact, it is marked for deletion and removed from its
         * aggregate contact. The sync adaptor deletes the raw contact on the server and
         * then calls ContactResolver.delete once more, this time passing the
         * {@link ContactsContract#CALLER_IS_SYNCADAPTER} query parameter to finalize
         * the data removal.
         * <P>Type: INTEGER</P>
         */
        public static final String DELETED = "deleted";

        /**
         * The "name_verified" flag: "1" means that the name fields on this raw
         * contact can be trusted and therefore should be used for the entire
         * aggregated contact.
         * <p>
         * If an aggregated contact contains more than one raw contact with a
         * verified name, one of those verified names is chosen at random.
         * If an aggregated contact contains no verified names, the
         * name is chosen randomly from the constituent raw contacts.
         * </p>
         * <p>
         * Updating this flag from "0" to "1" automatically resets it to "0" on
         * all other raw contacts in the same aggregated contact.
         * </p>
         * <p>
         * Sync adapters should only specify a value for this column when
         * inserting a raw contact and leave it out when doing an update.
         * </p>
         * <p>
         * The default value is "0"
         * </p>
         * <p>Type: INTEGER</p>
         *
         * @hide
         */
        public static final String NAME_VERIFIED = "name_verified";
    }

    /**
     * Constants for the raw contacts table, which contains one row of contact
     * information for each person in each synced account. Sync adapters and
     * contact management apps
     * are the primary consumers of this API.
     *
     * <h3>Aggregation</h3>
     * <p>
     * As soon as a raw contact is inserted or whenever its constituent data
     * changes, the provider will check if the raw contact matches other
     * existing raw contacts and if so will aggregate it with those. The
     * aggregation is reflected in the {@link RawContacts} table by the change of the
     * {@link #CONTACT_ID} field, which is the reference to the aggregate contact.
     * </p>
     * <p>
     * Changes to the structured name, organization, phone number, email address,
     * or nickname trigger a re-aggregation.
     * </p>
     * <p>
     * See also {@link AggregationExceptions} for a mechanism to control
     * aggregation programmatically.
     * </p>
     *
     * <h3>Operations</h3>
     * <dl>
     * <dt><b>Insert</b></dt>
     * <dd>
     * <p>
     * Raw contacts can be inserted incrementally or in a batch.
     * The incremental method is more traditional but less efficient.
     * It should be used
     * only if no {@link Data} values are available at the time the raw contact is created:
     * <pre>
     * ContentValues values = new ContentValues();
     * values.put(RawContacts.ACCOUNT_TYPE, accountType);
     * values.put(RawContacts.ACCOUNT_NAME, accountName);
     * Uri rawContactUri = getContentResolver().insert(RawContacts.CONTENT_URI, values);
     * long rawContactId = ContentUris.parseId(rawContactUri);
     * </pre>
     * </p>
     * <p>
     * Once {@link Data} values become available, insert those.
     * For example, here's how you would insert a name:
     *
     * <pre>
     * values.clear();
     * values.put(Data.RAW_CONTACT_ID, rawContactId);
     * values.put(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE);
     * values.put(StructuredName.DISPLAY_NAME, &quot;Mike Sullivan&quot;);
     * getContentResolver().insert(Data.CONTENT_URI, values);
     * </pre>
     * </p>
     * <p>
     * The batch method is by far preferred.  It inserts the raw contact and its
     * constituent data rows in a single database transaction
     * and causes at most one aggregation pass.
     * <pre>
     * ArrayList&lt;ContentProviderOperation&gt; ops = Lists.newArrayList();
     * ...
     * int rawContactInsertIndex = ops.size();
     * ops.add(ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
     *          .withValue(RawContacts.ACCOUNT_TYPE, accountType)
     *          .withValue(RawContacts.ACCOUNT_NAME, accountName)
     *          .build());
     *
     * ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI)
     *          .withValueBackReference(Data.RAW_CONTACT_ID, rawContactInsertIndex)
     *          .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
     *          .withValue(StructuredName.DISPLAY_NAME, &quot;Mike Sullivan&quot;)
     *          .build());
     *
     * getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
     * </pre>
     * </p>
     * <p>
     * Note the use of {@link ContentProviderOperation.Builder#withValueBackReference(String, int)}
     * to refer to the as-yet-unknown index value of the raw contact inserted in the
     * first operation.
     * </p>
     *
     * <dt><b>Update</b></dt>
     * <dd><p>
     * Raw contacts can be updated incrementally or in a batch.
     * Batch mode should be used whenever possible.
     * The procedures and considerations are analogous to those documented above for inserts.
     * </p></dd>
     * <dt><b>Delete</b></dt>
     * <dd><p>When a raw contact is deleted, all of its Data rows as well as StatusUpdates,
     * AggregationExceptions, PhoneLookup rows are deleted automatically. When all raw
     * contacts associated with a {@link Contacts} row are deleted, the {@link Contacts} row
     * itself is also deleted automatically.
     * </p>
     * <p>
     * The invocation of {@code resolver.delete(...)}, does not immediately delete
     * a raw contacts row.
     * Instead, it sets the {@link #DELETED} flag on the raw contact and
     * removes the raw contact from its aggregate contact.
     * The sync adapter then deletes the raw contact from the server and
     * finalizes phone-side deletion by calling {@code resolver.delete(...)}
     * again and passing the {@link ContactsContract#CALLER_IS_SYNCADAPTER} query parameter.<p>
     * <p>Some sync adapters are read-only, meaning that they only sync server-side
     * changes to the phone, but not the reverse.  If one of those raw contacts
     * is marked for deletion, it will remain on the phone.  However it will be
     * effectively invisible, because it will not be part of any aggregate contact.
     * </dd>
     *
     * <dt><b>Query</b></dt>
     * <dd>
     * <p>
     * It is easy to find all raw contacts in a Contact:
     * <pre>
     * Cursor c = getContentResolver().query(RawContacts.CONTENT_URI,
     *          new String[]{RawContacts._ID},
     *          RawContacts.CONTACT_ID + "=?",
     *          new String[]{String.valueOf(contactId)}, null);
     * </pre>
     * </p>
     * <p>
     * To find raw contacts within a specific account,
     * you can either put the account name and type in the selection or pass them as query
     * parameters.  The latter approach is preferable, especially when you can reuse the
     * URI:
     * <pre>
     * Uri rawContactUri = RawContacts.URI.buildUpon()
     *          .appendQueryParameter(RawContacts.ACCOUNT_NAME, accountName)
     *          .appendQueryParameter(RawContacts.ACCOUNT_TYPE, accountType)
     *          .build();
     * Cursor c1 = getContentResolver().query(rawContactUri,
     *          RawContacts.STARRED + "&lt;&gt;0", null, null, null);
     * ...
     * Cursor c2 = getContentResolver().query(rawContactUri,
     *          RawContacts.DELETED + "&lt;&gt;0", null, null, null);
     * </pre>
     * </p>
     * <p>The best way to read a raw contact along with all the data associated with it is
     * by using the {@link Entity} directory. If the raw contact has data rows,
     * the Entity cursor will contain a row for each data row.  If the raw contact has no
     * data rows, the cursor will still contain one row with the raw contact-level information.
     * <pre>
     * Uri rawContactUri = ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawContactId);
     * Uri entityUri = Uri.withAppendedPath(rawContactUri, Entity.CONTENT_DIRECTORY);
     * Cursor c = getContentResolver().query(entityUri,
     *          new String[]{RawContacts.SOURCE_ID, Entity.DATA_ID, Entity.MIMETYPE, Entity.DATA1},
     *          null, null, null);
     * try {
     *     while (c.moveToNext()) {
     *         String sourceId = c.getString(0);
     *         if (!c.isNull(1)) {
     *             String mimeType = c.getString(2);
     *             String data = c.getString(3);
     *             ...
     *         }
     *     }
     * } finally {
     *     c.close();
     * }
     * </pre>
     * </p>
     * </dd>
     * </dl>
     * <h2>Columns</h2>
     *
     * <table class="jd-sumtable">
     * <tr>
     * <th colspan='4'>RawContacts</th>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #_ID}</td>
     * <td>read-only</td>
     * <td>Row ID. Sync adapters should try to preserve row IDs during updates. In other words,
     * it is much better for a sync adapter to update a raw contact rather than to delete and
     * re-insert it.</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #CONTACT_ID}</td>
     * <td>read-only</td>
     * <td>The ID of the row in the {@link ContactsContract.Contacts} table
     * that this raw contact belongs
     * to. Raw contacts are linked to contacts by the aggregation process, which can be controlled
     * by the {@link #AGGREGATION_MODE} field and {@link AggregationExceptions}.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #AGGREGATION_MODE}</td>
     * <td>read/write</td>
     * <td>A mechanism that allows programmatic control of the aggregation process. The allowed
     * values are {@link #AGGREGATION_MODE_DEFAULT}, {@link #AGGREGATION_MODE_DISABLED}
     * and {@link #AGGREGATION_MODE_SUSPENDED}. See also {@link AggregationExceptions}.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #DELETED}</td>
     * <td>read/write</td>
     * <td>The "deleted" flag: "0" by default, "1" if the row has been marked
     * for deletion. When {@link android.content.ContentResolver#delete} is
     * called on a raw contact, it is marked for deletion and removed from its
     * aggregate contact. The sync adaptor deletes the raw contact on the server and
     * then calls ContactResolver.delete once more, this time passing the
     * {@link ContactsContract#CALLER_IS_SYNCADAPTER} query parameter to finalize
     * the data removal.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #TIMES_CONTACTED}</td>
     * <td>read/write</td>
     * <td>The number of times the contact has been contacted. To have an effect
     * on the corresponding value of the aggregate contact, this field
     * should be set at the time the raw contact is inserted.
     * After that, this value is typically updated via
     * {@link ContactsContract.Contacts#markAsContacted}.</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #LAST_TIME_CONTACTED}</td>
     * <td>read/write</td>
     * <td>The timestamp of the last time the contact was contacted. To have an effect
     * on the corresponding value of the aggregate contact, this field
     * should be set at the time the raw contact is inserted.
     * After that, this value is typically updated via
     * {@link ContactsContract.Contacts#markAsContacted}.
     * </td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #STARRED}</td>
     * <td>read/write</td>
     * <td>An indicator for favorite contacts: '1' if favorite, '0' otherwise.
     * Changing this field immediately affects the corresponding aggregate contact:
     * if any raw contacts in that aggregate contact are starred, then the contact
     * itself is marked as starred.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #CUSTOM_RINGTONE}</td>
     * <td>read/write</td>
     * <td>A custom ringtone associated with a raw contact. Typically this is the
     * URI returned by an activity launched with the
     * {@link android.media.RingtoneManager#ACTION_RINGTONE_PICKER} intent.
     * To have an effect on the corresponding value of the aggregate contact, this field
     * should be set at the time the raw contact is inserted. To set a custom
     * ringtone on a contact, use the field {@link ContactsContract.Contacts#CUSTOM_RINGTONE
     * Contacts.CUSTOM_RINGTONE}
     * instead.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #SEND_TO_VOICEMAIL}</td>
     * <td>read/write</td>
     * <td>An indicator of whether calls from this raw contact should be forwarded
     * directly to voice mail ('1') or not ('0'). To have an effect
     * on the corresponding value of the aggregate contact, this field
     * should be set at the time the raw contact is inserted.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #ACCOUNT_NAME}</td>
     * <td>read/write-once</td>
     * <td>The name of the account instance to which this row belongs, which when paired with
     * {@link #ACCOUNT_TYPE} identifies a specific account.
     * For example, this will be the Gmail address if it is a Google account.
     * It should be set at the time
     * the raw contact is inserted and never changed afterwards.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #ACCOUNT_TYPE}</td>
     * <td>read/write-once</td>
     * <td>
     * <p>
     * The type of account to which this row belongs, which when paired with
     * {@link #ACCOUNT_NAME} identifies a specific account.
     * It should be set at the time
     * the raw contact is inserted and never changed afterwards.
     * </p>
     * <p>
     * To ensure uniqueness, new account types should be chosen according to the
     * Java package naming convention.  Thus a Google account is of type "com.google".
     * </p>
     * </td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #SOURCE_ID}</td>
     * <td>read/write</td>
     * <td>String that uniquely identifies this row to its source account.
     * Typically it is set at the time the raw contact is inserted and never
     * changed afterwards. The one notable exception is a new raw contact: it
     * will have an account name and type, but no source id. This
     * indicates to the sync adapter that a new contact needs to be created
     * server-side and its ID stored in the corresponding SOURCE_ID field on
     * the phone.
     * </td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #VERSION}</td>
     * <td>read-only</td>
     * <td>Version number that is updated whenever this row or its related data
     * changes. This field can be used for optimistic locking of a raw contact.
     * </td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #DIRTY}</td>
     * <td>read/write</td>
     * <td>Flag indicating that {@link #VERSION} has changed, and this row needs
     * to be synchronized by its owning account.  The value is set to "1" automatically
     * whenever the raw contact changes, unless the URI has the
     * {@link ContactsContract#CALLER_IS_SYNCADAPTER} query parameter specified.
     * The sync adapter should always supply this query parameter to prevent
     * unnecessary synchronization: user changes some data on the server,
     * the sync adapter updates the contact on the phone (without the
     * CALLER_IS_SYNCADAPTER flag) flag, which sets the DIRTY flag,
     * which triggers a sync to bring the changes to the server.
     * </td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #SYNC1}</td>
     * <td>read/write</td>
     * <td>Generic column provided for arbitrary use by sync adapters.
     * The content provider
     * stores this information on behalf of the sync adapter but does not
     * interpret it in any way.
     * </td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #SYNC2}</td>
     * <td>read/write</td>
     * <td>Generic column for use by sync adapters.
     * </td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #SYNC3}</td>
     * <td>read/write</td>
     * <td>Generic column for use by sync adapters.
     * </td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #SYNC4}</td>
     * <td>read/write</td>
     * <td>Generic column for use by sync adapters.
     * </td>
     * </tr>
     * </table>
     */
    public static final class RawContacts implements BaseColumns, RawContactsColumns,
            ContactOptionsColumns, ContactNameColumns, SyncColumns  {
        /**
         * This utility class cannot be instantiated
         */
        private RawContacts() {
        }

        /**
         * The content:// style URI for this table, which requests a directory of
         * raw contact rows matching the selection criteria.
         */
        public static final Uri CONTENT_URI = Uri.withAppendedPath(AUTHORITY_URI, "raw_contacts");

        /**
         * The MIME type of the results from {@link #CONTENT_URI} when a specific
         * ID value is not provided, and multiple raw contacts may be returned.
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/raw_contact";

        /**
         * The MIME type of the results when a raw contact ID is appended to {@link #CONTENT_URI},
         * yielding a subdirectory of a single person.
         */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/raw_contact";

        /**
         * Aggregation mode: aggregate immediately after insert or update operation(s) are complete.
         */
        public static final int AGGREGATION_MODE_DEFAULT = 0;

        /**
         * Do not use.
         *
         * TODO: deprecate in favor of {@link #AGGREGATION_MODE_DEFAULT}
         */
        public static final int AGGREGATION_MODE_IMMEDIATE = 1;

        /**
         * <p>
         * Aggregation mode: aggregation suspended temporarily, and is likely to be resumed later.
         * Changes to the raw contact will update the associated aggregate contact but will not
         * result in any change in how the contact is aggregated. Similar to
         * {@link #AGGREGATION_MODE_DISABLED}, but maintains a link to the corresponding
         * {@link Contacts} aggregate.
         * </p>
         * <p>
         * This can be used to postpone aggregation until after a series of updates, for better
         * performance and/or user experience.
         * </p>
         * <p>
         * Note that changing
         * {@link #AGGREGATION_MODE} from {@link #AGGREGATION_MODE_SUSPENDED} to
         * {@link #AGGREGATION_MODE_DEFAULT} does not trigger an aggregation pass, but any
         * subsequent
         * change to the raw contact's data will.
         * </p>
         */
        public static final int AGGREGATION_MODE_SUSPENDED = 2;

        /**
         * <p>
         * Aggregation mode: never aggregate this raw contact.  The raw contact will not
         * have a corresponding {@link Contacts} aggregate and therefore will not be included in
         * {@link Contacts} query results.
         * </p>
         * <p>
         * For example, this mode can be used for a raw contact that is marked for deletion while
         * waiting for the deletion to occur on the server side.
         * </p>
         *
         * @see #AGGREGATION_MODE_SUSPENDED
         */
        public static final int AGGREGATION_MODE_DISABLED = 3;

        /**
         * Build a {@link android.provider.ContactsContract.Contacts#CONTENT_LOOKUP_URI}
         * style {@link Uri} for the parent {@link android.provider.ContactsContract.Contacts}
         * entry of the given {@link RawContacts} entry.
         */
        public static Uri getContactLookupUri(ContentResolver resolver, Uri rawContactUri) {
            // TODO: use a lighter query by joining rawcontacts with contacts in provider
            final Uri dataUri = Uri.withAppendedPath(rawContactUri, Data.CONTENT_DIRECTORY);
            final Cursor cursor = resolver.query(dataUri, new String[] {
                    RawContacts.CONTACT_ID, Contacts.LOOKUP_KEY
            }, null, null, null);

            Uri lookupUri = null;
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    final long contactId = cursor.getLong(0);
                    final String lookupKey = cursor.getString(1);
                    return Contacts.getLookupUri(contactId, lookupKey);
                }
            } finally {
                if (cursor != null) cursor.close();
            }
            return lookupUri;
        }

        /**
         * A sub-directory of a single raw contact that contains all of its
         * {@link ContactsContract.Data} rows. To access this directory
         * append {@link Data#CONTENT_DIRECTORY} to the contact URI.
         *
         * TODO: deprecate in favor of {@link RawContacts.Entity}.
         */
        public static final class Data implements BaseColumns, DataColumns {
            /**
             * no public constructor since this is a utility class
             */
            private Data() {
            }

            /**
             * The directory twig for this sub-table
             */
            public static final String CONTENT_DIRECTORY = "data";
        }

        /**
         * <p>
         * A sub-directory of a single raw contact that contains all of its
         * {@link ContactsContract.Data} rows. To access this directory append
         * {@link #CONTENT_DIRECTORY} to the contact URI. See
         * {@link RawContactsEntity} for a stand-alone table containing the same
         * data.
         * </p>
         * <p>
         * Entity has two ID fields: {@link #_ID} for the raw contact
         * and {@link #DATA_ID} for the data rows.
         * Entity always contains at least one row, even if there are no
         * actual data rows. In this case the {@link #DATA_ID} field will be
         * null.
         * </p>
         * <p>
         * Entity reads all
         * data for a raw contact in one transaction, to guarantee
         * consistency.
         * </p>
         */
        public static final class Entity implements BaseColumns, DataColumns {
            /**
             * no public constructor since this is a utility class
             */
            private Entity() {
            }

            /**
             * The directory twig for this sub-table
             */
            public static final String CONTENT_DIRECTORY = "entity";

            /**
             * The ID of the data column. The value will be null if this raw contact has no
             * data rows.
             * <P>Type: INTEGER</P>
             */
            public static final String DATA_ID = "data_id";
        }

        /**
         * TODO: javadoc
         * @param cursor
         * @return
         */
        public static EntityIterator newEntityIterator(Cursor cursor) {
            return new EntityIteratorImpl(cursor);
        }

        private static class EntityIteratorImpl extends CursorEntityIterator {
            private static final String[] DATA_KEYS = new String[]{
                    Data.DATA1,
                    Data.DATA2,
                    Data.DATA3,
                    Data.DATA4,
                    Data.DATA5,
                    Data.DATA6,
                    Data.DATA7,
                    Data.DATA8,
                    Data.DATA9,
                    Data.DATA10,
                    Data.DATA11,
                    Data.DATA12,
                    Data.DATA13,
                    Data.DATA14,
                    Data.DATA15,
                    Data.SYNC1,
                    Data.SYNC2,
                    Data.SYNC3,
                    Data.SYNC4};

            public EntityIteratorImpl(Cursor cursor) {
                super(cursor);
            }

            @Override
            public android.content.Entity getEntityAndIncrementCursor(Cursor cursor)
                    throws RemoteException {
                final int columnRawContactId = cursor.getColumnIndexOrThrow(RawContacts._ID);
                final long rawContactId = cursor.getLong(columnRawContactId);

                // we expect the cursor is already at the row we need to read from
                ContentValues cv = new ContentValues();
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, ACCOUNT_NAME);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, ACCOUNT_TYPE);
                DatabaseUtils.cursorLongToContentValuesIfPresent(cursor, cv, _ID);
                DatabaseUtils.cursorLongToContentValuesIfPresent(cursor, cv, DIRTY);
                DatabaseUtils.cursorLongToContentValuesIfPresent(cursor, cv, VERSION);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, SOURCE_ID);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, SYNC1);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, SYNC2);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, SYNC3);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, SYNC4);
                DatabaseUtils.cursorLongToContentValuesIfPresent(cursor, cv, DELETED);
                DatabaseUtils.cursorLongToContentValuesIfPresent(cursor, cv, CONTACT_ID);
                DatabaseUtils.cursorLongToContentValuesIfPresent(cursor, cv, STARRED);
                DatabaseUtils.cursorIntToContentValuesIfPresent(cursor, cv, IS_RESTRICTED);
                DatabaseUtils.cursorIntToContentValuesIfPresent(cursor, cv, NAME_VERIFIED);
                android.content.Entity contact = new android.content.Entity(cv);

                // read data rows until the contact id changes
                do {
                    if (rawContactId != cursor.getLong(columnRawContactId)) {
                        break;
                    }
                    // add the data to to the contact
                    cv = new ContentValues();
                    cv.put(Data._ID, cursor.getLong(cursor.getColumnIndexOrThrow(Entity.DATA_ID)));
                    DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv,
                            Data.RES_PACKAGE);
                    DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv, Data.MIMETYPE);
                    DatabaseUtils.cursorLongToContentValuesIfPresent(cursor, cv, Data.IS_PRIMARY);
                    DatabaseUtils.cursorLongToContentValuesIfPresent(cursor, cv,
                            Data.IS_SUPER_PRIMARY);
                    DatabaseUtils.cursorLongToContentValuesIfPresent(cursor, cv, Data.DATA_VERSION);
                    DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv,
                            CommonDataKinds.GroupMembership.GROUP_SOURCE_ID);
                    DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, cv,
                            Data.DATA_VERSION);
                    for (String key : DATA_KEYS) {
                        final int columnIndex = cursor.getColumnIndexOrThrow(key);
                        if (cursor.isNull(columnIndex)) {
                            // don't put anything
                        } else {
                            try {
                                cv.put(key, cursor.getString(columnIndex));
                            } catch (SQLiteException e) {
                                cv.put(key, cursor.getBlob(columnIndex));
                            }
                        }
                        // TODO: go back to this version of the code when bug
                        // http://b/issue?id=2306370 is fixed.
//                        if (cursor.isNull(columnIndex)) {
//                            // don't put anything
//                        } else if (cursor.isLong(columnIndex)) {
//                            values.put(key, cursor.getLong(columnIndex));
//                        } else if (cursor.isFloat(columnIndex)) {
//                            values.put(key, cursor.getFloat(columnIndex));
//                        } else if (cursor.isString(columnIndex)) {
//                            values.put(key, cursor.getString(columnIndex));
//                        } else if (cursor.isBlob(columnIndex)) {
//                            values.put(key, cursor.getBlob(columnIndex));
//                        }
                    }
                    contact.addSubValue(ContactsContract.Data.CONTENT_URI, cv);
                } while (cursor.moveToNext());

                return contact;
            }

        }
    }

    /**
     * Social status update columns.
     *
     * @see StatusUpdates
     * @see ContactsContract.Data
     */
    protected interface StatusColumns {
        /**
         * Contact's latest presence level.
         * <P>Type: INTEGER (one of the values below)</P>
         */
        public static final String PRESENCE = "mode";

        /**
         * @deprecated use {@link #PRESENCE}
         */
        @Deprecated
        public static final String PRESENCE_STATUS = PRESENCE;

        /**
         * An allowed value of {@link #PRESENCE}.
         */
        int OFFLINE = 0;

        /**
         * An allowed value of {@link #PRESENCE}.
         */
        int INVISIBLE = 1;

        /**
         * An allowed value of {@link #PRESENCE}.
         */
        int AWAY = 2;

        /**
         * An allowed value of {@link #PRESENCE}.
         */
        int IDLE = 3;

        /**
         * An allowed value of {@link #PRESENCE}.
         */
        int DO_NOT_DISTURB = 4;

        /**
         * An allowed value of {@link #PRESENCE}.
         */
        int AVAILABLE = 5;

        /**
         * Contact latest status update.
         * <p>Type: TEXT</p>
         */
        public static final String STATUS = "status";

        /**
         * @deprecated use {@link #STATUS}
         */
        @Deprecated
        public static final String PRESENCE_CUSTOM_STATUS = STATUS;

        /**
         * The absolute time in milliseconds when the latest status was inserted/updated.
         * <p>Type: NUMBER</p>
         */
        public static final String STATUS_TIMESTAMP = "status_ts";

        /**
         * The package containing resources for this status: label and icon.
         * <p>Type: NUMBER</p>
         */
        public static final String STATUS_RES_PACKAGE = "status_res_package";

        /**
         * The resource ID of the label describing the source of the status update, e.g. "Google
         * Talk".  This resource should be scoped by the {@link #STATUS_RES_PACKAGE}.
         * <p>Type: NUMBER</p>
         */
        public static final String STATUS_LABEL = "status_label";

        /**
         * The resource ID of the icon for the source of the status update.
         * This resource should be scoped by the {@link #STATUS_RES_PACKAGE}.
         * <p>Type: NUMBER</p>
         */
        public static final String STATUS_ICON = "status_icon";
    }

    /**
     * Columns in the Data table.
     *
     * @see ContactsContract.Data
     */
    protected interface DataColumns {
        /**
         * The package name to use when creating {@link Resources} objects for
         * this data row. This value is only designed for use when building user
         * interfaces, and should not be used to infer the owner.
         *
         * @hide
         */
        public static final String RES_PACKAGE = "res_package";

        /**
         * The MIME type of the item represented by this row.
         */
        public static final String MIMETYPE = "mimetype";

        /**
         * A reference to the {@link RawContacts#_ID}
         * that this data belongs to.
         */
        public static final String RAW_CONTACT_ID = "raw_contact_id";

        /**
         * Whether this is the primary entry of its kind for the raw contact it belongs to.
         * <P>Type: INTEGER (if set, non-0 means true)</P>
         */
        public static final String IS_PRIMARY = "is_primary";

        /**
         * Whether this is the primary entry of its kind for the aggregate
         * contact it belongs to. Any data record that is "super primary" must
         * also be "primary".
         * <P>Type: INTEGER (if set, non-0 means true)</P>
         */
        public static final String IS_SUPER_PRIMARY = "is_super_primary";

        /**
         * The version of this data record. This is a read-only value. The data column is
         * guaranteed to not change without the version going up. This value is monotonically
         * increasing.
         * <P>Type: INTEGER</P>
         */
        public static final String DATA_VERSION = "data_version";

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
        /** Generic data column, the meaning is {@link #MIMETYPE} specific */
        public static final String DATA11 = "data11";
        /** Generic data column, the meaning is {@link #MIMETYPE} specific */
        public static final String DATA12 = "data12";
        /** Generic data column, the meaning is {@link #MIMETYPE} specific */
        public static final String DATA13 = "data13";
        /** Generic data column, the meaning is {@link #MIMETYPE} specific */
        public static final String DATA14 = "data14";
        /**
         * Generic data column, the meaning is {@link #MIMETYPE} specific. By convention,
         * this field is used to store BLOBs (binary data).
         */
        public static final String DATA15 = "data15";

        /** Generic column for use by sync adapters. */
        public static final String SYNC1 = "data_sync1";
        /** Generic column for use by sync adapters. */
        public static final String SYNC2 = "data_sync2";
        /** Generic column for use by sync adapters. */
        public static final String SYNC3 = "data_sync3";
        /** Generic column for use by sync adapters. */
        public static final String SYNC4 = "data_sync4";
    }

    /**
     * Combines all columns returned by {@link ContactsContract.Data} table queries.
     *
     * @see ContactsContract.Data
     */
    protected interface DataColumnsWithJoins extends BaseColumns, DataColumns, StatusColumns,
            RawContactsColumns, ContactsColumns, ContactNameColumns, ContactOptionsColumns,
            ContactStatusColumns {
    }

    /**
     * <p>
     * Constants for the data table, which contains data points tied to a raw
     * contact.  Each row of the data table is typically used to store a single
     * piece of contact
     * information (such as a phone number) and its
     * associated metadata (such as whether it is a work or home number).
     * </p>
     * <h3>Data kinds</h3>
     * <p>
     * Data is a generic table that can hold any kind of contact data.
     * The kind of data stored in a given row is specified by the row's
     * {@link #MIMETYPE} value, which determines the meaning of the
     * generic columns {@link #DATA1} through
     * {@link #DATA15}.
     * For example, if the data kind is
     * {@link CommonDataKinds.Phone Phone.CONTENT_ITEM_TYPE}, then the column
     * {@link #DATA1} stores the
     * phone number, but if the data kind is
     * {@link CommonDataKinds.Email Email.CONTENT_ITEM_TYPE}, then {@link #DATA1}
     * stores the email address.
     * Sync adapters and applications can introduce their own data kinds.
     * </p>
     * <p>
     * ContactsContract defines a small number of pre-defined data kinds, e.g.
     * {@link CommonDataKinds.Phone}, {@link CommonDataKinds.Email} etc. As a
     * convenience, these classes define data kind specific aliases for DATA1 etc.
     * For example, {@link CommonDataKinds.Phone Phone.NUMBER} is the same as
     * {@link ContactsContract.Data Data.DATA1}.
     * </p>
     * <p>
     * {@link #DATA1} is an indexed column and should be used for the data element that is
     * expected to be most frequently used in query selections. For example, in the
     * case of a row representing email addresses {@link #DATA1} should probably
     * be used for the email address itself, while {@link #DATA2} etc can be
     * used for auxiliary information like type of email address.
     * <p>
     * <p>
     * By convention, {@link #DATA15} is used for storing BLOBs (binary data).
     * </p>
     * <p>
     * The sync adapter for a given account type must correctly handle every data type
     * used in the corresponding raw contacts.  Otherwise it could result in lost or
     * corrupted data.
     * </p>
     * <p>
     * Similarly, you should refrain from introducing new kinds of data for an other
     * party's account types. For example, if you add a data row for
     * "favorite song" to a raw contact owned by a Google account, it will not
     * get synced to the server, because the Google sync adapter does not know
     * how to handle this data kind. Thus new data kinds are typically
     * introduced along with new account types, i.e. new sync adapters.
     * </p>
     * <h3>Batch operations</h3>
     * <p>
     * Data rows can be inserted/updated/deleted using the traditional
     * {@link ContentResolver#insert}, {@link ContentResolver#update} and
     * {@link ContentResolver#delete} methods, however the newer mechanism based
     * on a batch of {@link ContentProviderOperation} will prove to be a better
     * choice in almost all cases. All operations in a batch are executed in a
     * single transaction, which ensures that the phone-side and server-side
     * state of a raw contact are always consistent. Also, the batch-based
     * approach is far more efficient: not only are the database operations
     * faster when executed in a single transaction, but also sending a batch of
     * commands to the content provider saves a lot of time on context switching
     * between your process and the process in which the content provider runs.
     * </p>
     * <p>
     * The flip side of using batched operations is that a large batch may lock
     * up the database for a long time preventing other applications from
     * accessing data and potentially causing ANRs ("Application Not Responding"
     * dialogs.)
     * </p>
     * <p>
     * To avoid such lockups of the database, make sure to insert "yield points"
     * in the batch. A yield point indicates to the content provider that before
     * executing the next operation it can commit the changes that have already
     * been made, yield to other requests, open another transaction and continue
     * processing operations. A yield point will not automatically commit the
     * transaction, but only if there is another request waiting on the
     * database. Normally a sync adapter should insert a yield point at the
     * beginning of each raw contact operation sequence in the batch. See
     * {@link ContentProviderOperation.Builder#withYieldAllowed(boolean)}.
     * </p>
     * <h3>Operations</h3>
     * <dl>
     * <dt><b>Insert</b></dt>
     * <dd>
     * <p>
     * An individual data row can be inserted using the traditional
     * {@link ContentResolver#insert(Uri, ContentValues)} method. Multiple rows
     * should always be inserted as a batch.
     * </p>
     * <p>
     * An example of a traditional insert:
     * <pre>
     * ContentValues values = new ContentValues();
     * values.put(Data.RAW_CONTACT_ID, rawContactId);
     * values.put(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE);
     * values.put(Phone.NUMBER, "1-800-GOOG-411");
     * values.put(Phone.TYPE, Phone.TYPE_CUSTOM);
     * values.put(Phone.LABEL, "free directory assistance");
     * Uri dataUri = getContentResolver().insert(Data.CONTENT_URI, values);
     * </pre>
     * <p>
     * The same done using ContentProviderOperations:
     * <pre>
     * ArrayList&lt;ContentProviderOperation&gt; ops = Lists.newArrayList();
     * ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI)
     *          .withValue(Data.RAW_CONTACT_ID, rawContactId)
     *          .withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
     *          .withValue(Phone.NUMBER, "1-800-GOOG-411")
     *          .withValue(Phone.TYPE, Phone.TYPE_CUSTOM)
     *          .withValue(Phone.LABEL, "free directory assistance")
     *          .build());
     * getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
     * </pre>
     * </p>
     * <dt><b>Update</b></dt>
     * <dd>
     * <p>
     * Just as with insert, update can be done incrementally or as a batch,
     * the batch mode being the preferred method:
     * <pre>
     * ArrayList&lt;ContentProviderOperation&gt; ops = Lists.newArrayList();
     * ops.add(ContentProviderOperation.newUpdate(Data.CONTENT_URI)
     *          .withSelection(Data._ID + "=?", new String[]{String.valueOf(dataId)})
     *          .withValue(Email.DATA, "somebody@android.com")
     *          .build());
     * getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
     * </pre>
     * </p>
     * </dd>
     * <dt><b>Delete</b></dt>
     * <dd>
     * <p>
     * Just as with insert and update, deletion can be done either using the
     * {@link ContentResolver#delete} method or using a ContentProviderOperation:
     * <pre>
     * ArrayList&lt;ContentProviderOperation&gt; ops = Lists.newArrayList();
     * ops.add(ContentProviderOperation.newDelete(Data.CONTENT_URI)
     *          .withSelection(Data._ID + "=?", new String[]{String.valueOf(dataId)})
     *          .build());
     * getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
     * </pre>
     * </p>
     * </dd>
     * <dt><b>Query</b></dt>
     * <dd>
     * <p>
     * <dl>
     * <dt>Finding all Data of a given type for a given contact</dt>
     * <dd>
     * <pre>
     * Cursor c = getContentResolver().query(Data.CONTENT_URI,
     *          new String[] {Data._ID, Phone.NUMBER, Phone.TYPE, Phone.LABEL},
     *          Data.CONTACT_ID + &quot;=?&quot; + " AND "
     *                  + Data.MIMETYPE + "='" + Phone.CONTENT_ITEM_TYPE + "'",
     *          new String[] {String.valueOf(contactId)}, null);
     * </pre>
     * </p>
     * <p>
     * </dd>
     * <dt>Finding all Data of a given type for a given raw contact</dt>
     * <dd>
     * <pre>
     * Cursor c = getContentResolver().query(Data.CONTENT_URI,
     *          new String[] {Data._ID, Phone.NUMBER, Phone.TYPE, Phone.LABEL},
     *          Data.RAW_CONTACT_ID + &quot;=?&quot; + " AND "
     *                  + Data.MIMETYPE + "='" + Phone.CONTENT_ITEM_TYPE + "'",
     *          new String[] {String.valueOf(rawContactId)}, null);
     * </pre>
     * </dd>
     * <dt>Finding all Data for a given raw contact</dt>
     * <dd>
     * Most sync adapters will want to read all data rows for a raw contact
     * along with the raw contact itself.  For that you should use the
     * {@link RawContactsEntity}. See also {@link RawContacts}.
     * </dd>
     * </dl>
     * </p>
     * </dd>
     * </dl>
     * <h2>Columns</h2>
     * <p>
     * Many columns are available via a {@link Data#CONTENT_URI} query.  For best performance you
     * should explicitly specify a projection to only those columns that you need.
     * </p>
     * <table class="jd-sumtable">
     * <tr>
     * <th colspan='4'>Data</th>
     * </tr>
     * <tr>
     * <td style="width: 7em;">long</td>
     * <td style="width: 20em;">{@link #_ID}</td>
     * <td style="width: 5em;">read-only</td>
     * <td>Row ID. Sync adapter should try to preserve row IDs during updates. In other words,
     * it would be a bad idea to delete and reinsert a data row. A sync adapter should
     * always do an update instead.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #MIMETYPE}</td>
     * <td>read/write-once</td>
     * <td>
     * <p>The MIME type of the item represented by this row. Examples of common
     * MIME types are:
     * <ul>
     * <li>{@link CommonDataKinds.StructuredName StructuredName.CONTENT_ITEM_TYPE}</li>
     * <li>{@link CommonDataKinds.Phone Phone.CONTENT_ITEM_TYPE}</li>
     * <li>{@link CommonDataKinds.Email Email.CONTENT_ITEM_TYPE}</li>
     * <li>{@link CommonDataKinds.Photo Photo.CONTENT_ITEM_TYPE}</li>
     * <li>{@link CommonDataKinds.Organization Organization.CONTENT_ITEM_TYPE}</li>
     * <li>{@link CommonDataKinds.Im Im.CONTENT_ITEM_TYPE}</li>
     * <li>{@link CommonDataKinds.Nickname Nickname.CONTENT_ITEM_TYPE}</li>
     * <li>{@link CommonDataKinds.Note Note.CONTENT_ITEM_TYPE}</li>
     * <li>{@link CommonDataKinds.StructuredPostal StructuredPostal.CONTENT_ITEM_TYPE}</li>
     * <li>{@link CommonDataKinds.GroupMembership GroupMembership.CONTENT_ITEM_TYPE}</li>
     * <li>{@link CommonDataKinds.Website Website.CONTENT_ITEM_TYPE}</li>
     * <li>{@link CommonDataKinds.Event Event.CONTENT_ITEM_TYPE}</li>
     * <li>{@link CommonDataKinds.Relation Relation.CONTENT_ITEM_TYPE}</li>
     * </ul>
     * </p>
     * </td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #RAW_CONTACT_ID}</td>
     * <td>read/write-once</td>
     * <td>The id of the row in the {@link RawContacts} table that this data belongs to.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #IS_PRIMARY}</td>
     * <td>read/write</td>
     * <td>Whether this is the primary entry of its kind for the raw contact it belongs to.
     * "1" if true, "0" if false.
     * </td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #IS_SUPER_PRIMARY}</td>
     * <td>read/write</td>
     * <td>Whether this is the primary entry of its kind for the aggregate
     * contact it belongs to. Any data record that is "super primary" must
     * also be "primary".  For example, the super-primary entry may be
     * interpreted as the default contact value of its kind (for example,
     * the default phone number to use for the contact).</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #DATA_VERSION}</td>
     * <td>read-only</td>
     * <td>The version of this data record. Whenever the data row changes
     * the version goes up. This value is monotonically increasing.</td>
     * </tr>
     * <tr>
     * <td>Any type</td>
     * <td>
     * {@link #DATA1}<br>
     * {@link #DATA2}<br>
     * {@link #DATA3}<br>
     * {@link #DATA4}<br>
     * {@link #DATA5}<br>
     * {@link #DATA6}<br>
     * {@link #DATA7}<br>
     * {@link #DATA8}<br>
     * {@link #DATA9}<br>
     * {@link #DATA10}<br>
     * {@link #DATA11}<br>
     * {@link #DATA12}<br>
     * {@link #DATA13}<br>
     * {@link #DATA14}<br>
     * {@link #DATA15}
     * </td>
     * <td>read/write</td>
     * <td>
     * <p>
     * Generic data columns.  The meaning of each column is determined by the
     * {@link #MIMETYPE}.  By convention, {@link #DATA15} is used for storing
     * BLOBs (binary data).
     * </p>
     * <p>
     * Data columns whose meaning is not explicitly defined for a given MIMETYPE
     * should not be used.  There is no guarantee that any sync adapter will
     * preserve them.  Sync adapters themselves should not use such columns either,
     * but should instead use {@link #SYNC1}-{@link #SYNC4}.
     * </p>
     * </td>
     * </tr>
     * <tr>
     * <td>Any type</td>
     * <td>
     * {@link #SYNC1}<br>
     * {@link #SYNC2}<br>
     * {@link #SYNC3}<br>
     * {@link #SYNC4}
     * </td>
     * <td>read/write</td>
     * <td>Generic columns for use by sync adapters. For example, a Photo row
     * may store the image URL in SYNC1, a status (not loaded, loading, loaded, error)
     * in SYNC2, server-side version number in SYNC3 and error code in SYNC4.</td>
     * </tr>
     * </table>
     *
     * <p>
     * Some columns from the most recent associated status update are also available
     * through an implicit join.
     * </p>
     * <table class="jd-sumtable">
     * <tr>
     * <th colspan='4'>Join with {@link StatusUpdates}</th>
     * </tr>
     * <tr>
     * <td style="width: 7em;">int</td>
     * <td style="width: 20em;">{@link #PRESENCE}</td>
     * <td style="width: 5em;">read-only</td>
     * <td>IM presence status linked to this data row. Compare with
     * {@link #CONTACT_PRESENCE}, which contains the contact's presence across
     * all IM rows. See {@link StatusUpdates} for individual status definitions.
     * The provider may choose not to store this value
     * in persistent storage. The expectation is that presence status will be
     * updated on a regular basic.
     * </td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #STATUS}</td>
     * <td>read-only</td>
     * <td>Latest status update linked with this data row.</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #STATUS_TIMESTAMP}</td>
     * <td>read-only</td>
     * <td>The absolute time in milliseconds when the latest status was
     * inserted/updated for this data row.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #STATUS_RES_PACKAGE}</td>
     * <td>read-only</td>
     * <td>The package containing resources for this status: label and icon.</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #STATUS_LABEL}</td>
     * <td>read-only</td>
     * <td>The resource ID of the label describing the source of status update linked
     * to this data row. This resource is scoped by the {@link #STATUS_RES_PACKAGE}.</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #STATUS_ICON}</td>
     * <td>read-only</td>
     * <td>The resource ID of the icon for the source of the status update linked
     * to this data row. This resource is scoped by the {@link #STATUS_RES_PACKAGE}.</td>
     * </tr>
     * </table>
     *
     * <p>
     * Some columns from the associated raw contact are also available through an
     * implicit join.  The other columns are excluded as uninteresting in this
     * context.
     * </p>
     *
     * <table class="jd-sumtable">
     * <tr>
     * <th colspan='4'>Join with {@link ContactsContract.RawContacts}</th>
     * </tr>
     * <tr>
     * <td style="width: 7em;">long</td>
     * <td style="width: 20em;">{@link #CONTACT_ID}</td>
     * <td style="width: 5em;">read-only</td>
     * <td>The id of the row in the {@link Contacts} table that this data belongs
     * to.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #AGGREGATION_MODE}</td>
     * <td>read-only</td>
     * <td>See {@link RawContacts}.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #DELETED}</td>
     * <td>read-only</td>
     * <td>See {@link RawContacts}.</td>
     * </tr>
     * </table>
     *
     * <p>
     * The ID column for the associated aggregated contact table
     * {@link ContactsContract.Contacts} is available
     * via the implicit join to the {@link RawContacts} table, see above.
     * The remaining columns from this table are also
     * available, through an implicit join.  This
     * facilitates lookup by
     * the value of a single data element, such as the email address.
     * </p>
     *
     * <table class="jd-sumtable">
     * <tr>
     * <th colspan='4'>Join with {@link ContactsContract.Contacts}</th>
     * </tr>
     * <tr>
     * <td style="width: 7em;">String</td>
     * <td style="width: 20em;">{@link #LOOKUP_KEY}</td>
     * <td style="width: 5em;">read-only</td>
     * <td>See {@link ContactsContract.Contacts}</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #DISPLAY_NAME}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Contacts}</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #PHOTO_ID}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Contacts}.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #IN_VISIBLE_GROUP}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Contacts}.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #HAS_PHONE_NUMBER}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Contacts}.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #TIMES_CONTACTED}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Contacts}.</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #LAST_TIME_CONTACTED}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Contacts}.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #STARRED}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Contacts}.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #CUSTOM_RINGTONE}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Contacts}.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #SEND_TO_VOICEMAIL}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Contacts}.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #CONTACT_PRESENCE}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Contacts}.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #CONTACT_STATUS}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Contacts}.</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #CONTACT_STATUS_TIMESTAMP}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Contacts}.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #CONTACT_STATUS_RES_PACKAGE}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Contacts}.</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #CONTACT_STATUS_LABEL}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Contacts}.</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #CONTACT_STATUS_ICON}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Contacts}.</td>
     * </tr>
     * </table>
     */
    public final static class Data implements DataColumnsWithJoins {
        /**
         * This utility class cannot be instantiated
         */
        private Data() {}

        /**
         * The content:// style URI for this table, which requests a directory
         * of data rows matching the selection criteria.
         */
        public static final Uri CONTENT_URI = Uri.withAppendedPath(AUTHORITY_URI, "data");

        /**
         * The MIME type of the results from {@link #CONTENT_URI}.
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/data";

        /**
         * <p>
         * If {@link #FOR_EXPORT_ONLY} is explicitly set to "1", returned Cursor toward
         * Data.CONTENT_URI contains only exportable data.
         * </p>
         * <p>
         * This flag is useful (currently) only for vCard exporter in Contacts app, which
         * needs to exclude "un-exportable" data from available data to export, while
         * Contacts app itself has priviledge to access all data including "un-exportable"
         * ones and providers return all of them regardless of the callers' intention.
         * </p>
         * <p>
         * Type: INTEGER
         * </p>
         *
         * @hide Maybe available only in Eclair and not really ready for public use.
         * TODO: remove, or implement this feature completely. As of now (Eclair),
         * we only use this flag in queryEntities(), not query().
         */
        public static final String FOR_EXPORT_ONLY = "for_export_only";

        /**
         * <p>
         * Build a {@link android.provider.ContactsContract.Contacts#CONTENT_LOOKUP_URI}
         * style {@link Uri} for the parent {@link android.provider.ContactsContract.Contacts}
         * entry of the given {@link ContactsContract.Data} entry.
         * </p>
         * <p>
         * Returns the Uri for the contact in the first entry returned by
         * {@link ContentResolver#query(Uri, String[], String, String[], String)}
         * for the provided {@code dataUri}.  If the query returns null or empty
         * results, silently returns null.
         * </p>
         */
        public static Uri getContactLookupUri(ContentResolver resolver, Uri dataUri) {
            final Cursor cursor = resolver.query(dataUri, new String[] {
                    RawContacts.CONTACT_ID, Contacts.LOOKUP_KEY
            }, null, null, null);

            Uri lookupUri = null;
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    final long contactId = cursor.getLong(0);
                    final String lookupKey = cursor.getString(1);
                    return Contacts.getLookupUri(contactId, lookupKey);
                }
            } finally {
                if (cursor != null) cursor.close();
            }
            return lookupUri;
        }
    }

    /**
     * <p>
     * Constants for the raw contacts entities table, which can be thought of as
     * an outer join of the raw_contacts table with the data table.  It is a strictly
     * read-only table.
     * </p>
     * <p>
     * If a raw contact has data rows, the RawContactsEntity cursor will contain
     * a one row for each data row. If the raw contact has no data rows, the
     * cursor will still contain one row with the raw contact-level information
     * and nulls for data columns.
     *
     * <pre>
     * Uri entityUri = ContentUris.withAppendedId(RawContactsEntity.CONTENT_URI, rawContactId);
     * Cursor c = getContentResolver().query(entityUri,
     *          new String[]{
     *              RawContactsEntity.SOURCE_ID,
     *              RawContactsEntity.DATA_ID,
     *              RawContactsEntity.MIMETYPE,
     *              RawContactsEntity.DATA1
     *          }, null, null, null);
     * try {
     *     while (c.moveToNext()) {
     *         String sourceId = c.getString(0);
     *         if (!c.isNull(1)) {
     *             String mimeType = c.getString(2);
     *             String data = c.getString(3);
     *             ...
     *         }
     *     }
     * } finally {
     *     c.close();
     * }
     * </pre>
     *
     * <h3>Columns</h3>
     * RawContactsEntity has a combination of RawContact and Data columns.
     *
     * <table class="jd-sumtable">
     * <tr>
     * <th colspan='4'>RawContacts</th>
     * </tr>
     * <tr>
     * <td style="width: 7em;">long</td>
     * <td style="width: 20em;">{@link #_ID}</td>
     * <td style="width: 5em;">read-only</td>
     * <td>Raw contact row ID. See {@link RawContacts}.</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #CONTACT_ID}</td>
     * <td>read-only</td>
     * <td>See {@link RawContacts}.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #AGGREGATION_MODE}</td>
     * <td>read-only</td>
     * <td>See {@link RawContacts}.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #DELETED}</td>
     * <td>read-only</td>
     * <td>See {@link RawContacts}.</td>
     * </tr>
     * </table>
     *
     * <table class="jd-sumtable">
     * <tr>
     * <th colspan='4'>Data</th>
     * </tr>
     * <tr>
     * <td style="width: 7em;">long</td>
     * <td style="width: 20em;">{@link #DATA_ID}</td>
     * <td style="width: 5em;">read-only</td>
     * <td>Data row ID. It will be null if the raw contact has no data rows.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #MIMETYPE}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Data}.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #IS_PRIMARY}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Data}.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #IS_SUPER_PRIMARY}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Data}.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #DATA_VERSION}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Data}.</td>
     * </tr>
     * <tr>
     * <td>Any type</td>
     * <td>
     * {@link #DATA1}<br>
     * {@link #DATA2}<br>
     * {@link #DATA3}<br>
     * {@link #DATA4}<br>
     * {@link #DATA5}<br>
     * {@link #DATA6}<br>
     * {@link #DATA7}<br>
     * {@link #DATA8}<br>
     * {@link #DATA9}<br>
     * {@link #DATA10}<br>
     * {@link #DATA11}<br>
     * {@link #DATA12}<br>
     * {@link #DATA13}<br>
     * {@link #DATA14}<br>
     * {@link #DATA15}
     * </td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Data}.</td>
     * </tr>
     * <tr>
     * <td>Any type</td>
     * <td>
     * {@link #SYNC1}<br>
     * {@link #SYNC2}<br>
     * {@link #SYNC3}<br>
     * {@link #SYNC4}
     * </td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Data}.</td>
     * </tr>
     * </table>
     */
    public final static class RawContactsEntity
            implements BaseColumns, DataColumns, RawContactsColumns {
        /**
         * This utility class cannot be instantiated
         */
        private RawContactsEntity() {}

        /**
         * The content:// style URI for this table
         */
        public static final Uri CONTENT_URI =
                Uri.withAppendedPath(AUTHORITY_URI, "raw_contact_entities");

        /**
         * The MIME type of {@link #CONTENT_URI} providing a directory of raw contact entities.
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/raw_contact_entity";

        /**
         * If {@link #FOR_EXPORT_ONLY} is explicitly set to "1", returned Cursor toward
         * Data.CONTENT_URI contains only exportable data.
         *
         * This flag is useful (currently) only for vCard exporter in Contacts app, which
         * needs to exclude "un-exportable" data from available data to export, while
         * Contacts app itself has priviledge to access all data including "un-expotable"
         * ones and providers return all of them regardless of the callers' intention.
         * <P>Type: INTEGER</p>
         *
         * @hide Maybe available only in Eclair and not really ready for public use.
         * TODO: remove, or implement this feature completely. As of now (Eclair),
         * we only use this flag in queryEntities(), not query().
         */
        public static final String FOR_EXPORT_ONLY = "for_export_only";

        /**
         * The ID of the data column. The value will be null if this raw contact has no data rows.
         * <P>Type: INTEGER</P>
         */
        public static final String DATA_ID = "data_id";
    }

    /**
     * @see PhoneLookup
     */
    protected interface PhoneLookupColumns {
        /**
         * The phone number as the user entered it.
         * <P>Type: TEXT</P>
         */
        public static final String NUMBER = "number";

        /**
         * The type of phone number, for example Home or Work.
         * <P>Type: INTEGER</P>
         */
        public static final String TYPE = "type";

        /**
         * The user defined label for the phone number.
         * <P>Type: TEXT</P>
         */
        public static final String LABEL = "label";
    }

    /**
     * A table that represents the result of looking up a phone number, for
     * example for caller ID. To perform a lookup you must append the number you
     * want to find to {@link #CONTENT_FILTER_URI}.  This query is highly
     * optimized.
     * <pre>
     * Uri uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber));
     * resolver.query(uri, new String[]{PhoneLookup.DISPLAY_NAME,...
     * </pre>
     *
     * <h3>Columns</h3>
     *
     * <table class="jd-sumtable">
     * <tr>
     * <th colspan='4'>PhoneLookup</th>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #_ID}</td>
     * <td>read-only</td>
     * <td>Data row ID.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #NUMBER}</td>
     * <td>read-only</td>
     * <td>Phone number.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #TYPE}</td>
     * <td>read-only</td>
     * <td>Phone number type. See {@link CommonDataKinds.Phone}.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #LABEL}</td>
     * <td>read-only</td>
     * <td>Custom label for the phone number. See {@link CommonDataKinds.Phone}.</td>
     * </tr>
     * </table>
     * <p>
     * Columns from the Contacts table are also available through a join.
     * </p>
     * <table class="jd-sumtable">
     * <tr>
     * <th colspan='4'>Join with {@link Contacts}</th>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #LOOKUP_KEY}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Contacts}</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #DISPLAY_NAME}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Contacts}</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #PHOTO_ID}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Contacts}.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #IN_VISIBLE_GROUP}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Contacts}.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #HAS_PHONE_NUMBER}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Contacts}.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #TIMES_CONTACTED}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Contacts}.</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #LAST_TIME_CONTACTED}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Contacts}.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #STARRED}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Contacts}.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #CUSTOM_RINGTONE}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Contacts}.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #SEND_TO_VOICEMAIL}</td>
     * <td>read-only</td>
     * <td>See {@link ContactsContract.Contacts}.</td>
     * </tr>
     * </table>
     */
    public static final class PhoneLookup implements BaseColumns, PhoneLookupColumns,
            ContactsColumns, ContactOptionsColumns {
        /**
         * This utility class cannot be instantiated
         */
        private PhoneLookup() {}

        /**
         * The content:// style URI for this table. Append the phone number you want to lookup
         * to this URI and query it to perform a lookup. For example:
         * <pre>
         * Uri lookupUri = Uri.withAppendedPath(PhoneLookup.CONTENT_URI, Uri.encode(phoneNumber));
         * </pre>
         */
        public static final Uri CONTENT_FILTER_URI = Uri.withAppendedPath(AUTHORITY_URI,
                "phone_lookup");

        /**
         * The MIME type of {@link #CONTENT_FILTER_URI} providing a directory of phone lookup rows.
         *
         * @hide
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/phone_lookup";
    }

    /**
     * Additional data mixed in with {@link StatusColumns} to link
     * back to specific {@link ContactsContract.Data#_ID} entries.
     *
     * @see StatusUpdates
     */
    protected interface PresenceColumns {

        /**
         * Reference to the {@link Data#_ID} entry that owns this presence.
         * <P>Type: INTEGER</P>
         */
        public static final String DATA_ID = "presence_data_id";

        /**
         * See {@link CommonDataKinds.Im} for a list of defined protocol constants.
         * <p>Type: NUMBER</p>
         */
        public static final String PROTOCOL = "protocol";

        /**
         * Name of the custom protocol.  Should be supplied along with the {@link #PROTOCOL} value
         * {@link ContactsContract.CommonDataKinds.Im#PROTOCOL_CUSTOM}.  Should be null or
         * omitted if {@link #PROTOCOL} value is not
         * {@link ContactsContract.CommonDataKinds.Im#PROTOCOL_CUSTOM}.
         *
         * <p>Type: NUMBER</p>
         */
        public static final String CUSTOM_PROTOCOL = "custom_protocol";

        /**
         * The IM handle the presence item is for. The handle is scoped to
         * {@link #PROTOCOL}.
         * <P>Type: TEXT</P>
         */
        public static final String IM_HANDLE = "im_handle";

        /**
         * The IM account for the local user that the presence data came from.
         * <P>Type: TEXT</P>
         */
        public static final String IM_ACCOUNT = "im_account";
    }

    /**
     * <p>
     * A status update is linked to a {@link ContactsContract.Data} row and captures
     * the user's latest status update via the corresponding source, e.g.
     * "Having lunch" via "Google Talk".
     * </p>
     * <p>
     * There are two ways a status update can be inserted: by explicitly linking
     * it to a Data row using {@link #DATA_ID} or indirectly linking it to a data row
     * using a combination of {@link #PROTOCOL} (or {@link #CUSTOM_PROTOCOL}) and
     * {@link #IM_HANDLE}.  There is no difference between insert and update, you can use
     * either.
     * </p>
     * <p>
     * You cannot use {@link ContentResolver#update} to change a status, but
     * {@link ContentResolver#insert} will replace the latests status if it already
     * exists.
     * </p>
     * <p>
     * Use {@link ContentResolver#bulkInsert(Uri, ContentValues[])} to insert/update statuses
     * for multiple contacts at once.
     * </p>
     *
     * <h3>Columns</h3>
     * <table class="jd-sumtable">
     * <tr>
     * <th colspan='4'>StatusUpdates</th>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #DATA_ID}</td>
     * <td>read/write</td>
     * <td>Reference to the {@link Data#_ID} entry that owns this presence. If this
     * field is <i>not</i> specified, the provider will attempt to find a data row
     * that matches the {@link #PROTOCOL} (or {@link #CUSTOM_PROTOCOL}) and
     * {@link #IM_HANDLE} columns.
     * </td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #PROTOCOL}</td>
     * <td>read/write</td>
     * <td>See {@link CommonDataKinds.Im} for a list of defined protocol constants.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #CUSTOM_PROTOCOL}</td>
     * <td>read/write</td>
     * <td>Name of the custom protocol.  Should be supplied along with the {@link #PROTOCOL} value
     * {@link ContactsContract.CommonDataKinds.Im#PROTOCOL_CUSTOM}.  Should be null or
     * omitted if {@link #PROTOCOL} value is not
     * {@link ContactsContract.CommonDataKinds.Im#PROTOCOL_CUSTOM}.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #IM_HANDLE}</td>
     * <td>read/write</td>
     * <td> The IM handle the presence item is for. The handle is scoped to
     * {@link #PROTOCOL}.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #IM_ACCOUNT}</td>
     * <td>read/write</td>
     * <td>The IM account for the local user that the presence data came from.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #PRESENCE}</td>
     * <td>read/write</td>
     * <td>Contact IM presence status. The allowed values are:
     * <p>
     * <ul>
     * <li>{@link #OFFLINE}</li>
     * <li>{@link #INVISIBLE}</li>
     * <li>{@link #AWAY}</li>
     * <li>{@link #IDLE}</li>
     * <li>{@link #DO_NOT_DISTURB}</li>
     * <li>{@link #AVAILABLE}</li>
     * </ul>
     * </p>
     * <p>
     * Since presence status is inherently volatile, the content provider
     * may choose not to store this field in long-term storage.
     * </p>
     * </td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #STATUS}</td>
     * <td>read/write</td>
     * <td>Contact's latest status update, e.g. "having toast for breakfast"</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #STATUS_TIMESTAMP}</td>
     * <td>read/write</td>
     * <td>The absolute time in milliseconds when the status was
     * entered by the user. If this value is not provided, the provider will follow
     * this logic: if there was no prior status update, the value will be left as null.
     * If there was a prior status update, the provider will default this field
     * to the current time.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #STATUS_RES_PACKAGE}</td>
     * <td>read/write</td>
     * <td> The package containing resources for this status: label and icon.</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #STATUS_LABEL}</td>
     * <td>read/write</td>
     * <td>The resource ID of the label describing the source of contact status,
     * e.g. "Google Talk". This resource is scoped by the
     * {@link #STATUS_RES_PACKAGE}.</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #STATUS_ICON}</td>
     * <td>read/write</td>
     * <td>The resource ID of the icon for the source of contact status. This
     * resource is scoped by the {@link #STATUS_RES_PACKAGE}.</td>
     * </tr>
     * </table>
     */
    public static class StatusUpdates implements StatusColumns, PresenceColumns {

        /**
         * This utility class cannot be instantiated
         */
        private StatusUpdates() {}

        /**
         * The content:// style URI for this table
         */
        public static final Uri CONTENT_URI = Uri.withAppendedPath(AUTHORITY_URI, "status_updates");

        /**
         * Gets the resource ID for the proper presence icon.
         *
         * @param status the status to get the icon for
         * @return the resource ID for the proper presence icon
         */
        public static final int getPresenceIconResourceId(int status) {
            switch (status) {
                case AVAILABLE:
                    return android.R.drawable.presence_online;
                case IDLE:
                case AWAY:
                    return android.R.drawable.presence_away;
                case DO_NOT_DISTURB:
                    return android.R.drawable.presence_busy;
                case INVISIBLE:
                    return android.R.drawable.presence_invisible;
                case OFFLINE:
                default:
                    return android.R.drawable.presence_offline;
            }
        }

        /**
         * Returns the precedence of the status code the higher number being the higher precedence.
         *
         * @param status The status code.
         * @return An integer representing the precedence, 0 being the lowest.
         */
        public static final int getPresencePrecedence(int status) {
            // Keep this function here incase we want to enforce a different precedence than the
            // natural order of the status constants.
            return status;
        }

        /**
         * The MIME type of {@link #CONTENT_URI} providing a directory of
         * status update details.
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/status-update";

        /**
         * The MIME type of a {@link #CONTENT_URI} subdirectory of a single
         * status update detail.
         */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/status-update";
    }

    /**
     * @deprecated This old name was never meant to be made public. Do not use.
     */
    @Deprecated
    public static final class Presence extends StatusUpdates {

    }

    /**
     * Additional columns returned by the {@link Contacts#CONTENT_FILTER_URI} providing the
     * explanation of why the filter matched the contact.  Specifically, they contain the
     * data type and element that was used for matching.
     * <p>
     * This is temporary API, it will need to change when we move to FTS.
     *
     * @hide
     */
    public static class SearchSnippetColumns {

        /**
         * The ID of the data row that was matched by the filter.
         *
         * @hide
         */
        public static final String SNIPPET_DATA_ID = "snippet_data_id";

        /**
         * The type of data that was matched by the filter.
         *
         * @hide
         */
        public static final String SNIPPET_MIMETYPE = "snippet_mimetype";

        /**
         * The {@link Data#DATA1} field of the data row that was matched by the filter.
         *
         * @hide
         */
        public static final String SNIPPET_DATA1 = "snippet_data1";

        /**
         * The {@link Data#DATA2} field of the data row that was matched by the filter.
         *
         * @hide
         */
        public static final String SNIPPET_DATA2 = "snippet_data2";

        /**
         * The {@link Data#DATA3} field of the data row that was matched by the filter.
         *
         * @hide
         */
        public static final String SNIPPET_DATA3 = "snippet_data3";

        /**
         * The {@link Data#DATA4} field of the data row that was matched by the filter.
         *
         * @hide
         */
        public static final String SNIPPET_DATA4 = "snippet_data4";

    }

    /**
     * Container for definitions of common data types stored in the {@link ContactsContract.Data}
     * table.
     */
    public static final class CommonDataKinds {
        /**
         * This utility class cannot be instantiated
         */
        private CommonDataKinds() {}

        /**
         * The {@link Data#RES_PACKAGE} value for common data that should be
         * shown using a default style.
         *
         * @hide RES_PACKAGE is hidden
         */
        public static final String PACKAGE_COMMON = "common";

        /**
         * The base types that all "Typed" data kinds support.
         */
        public interface BaseTypes {
            /**
             * A custom type. The custom label should be supplied by user.
             */
            public static int TYPE_CUSTOM = 0;
        }

        /**
         * Columns common across the specific types.
         */
        protected interface CommonColumns extends BaseTypes {
            /**
             * The data for the contact method.
             * <P>Type: TEXT</P>
             */
            public static final String DATA = DataColumns.DATA1;

            /**
             * The type of data, for example Home or Work.
             * <P>Type: INTEGER</P>
             */
            public static final String TYPE = DataColumns.DATA2;

            /**
             * The user defined label for the the contact method.
             * <P>Type: TEXT</P>
             */
            public static final String LABEL = DataColumns.DATA3;
        }

        /**
         * A data kind representing the contact's proper name. You can use all
         * columns defined for {@link ContactsContract.Data} as well as the following aliases.
         *
         * <h2>Column aliases</h2>
         * <table class="jd-sumtable">
         * <tr>
         * <th>Type</th><th>Alias</th><th colspan='2'>Data column</th>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #DISPLAY_NAME}</td>
         * <td>{@link #DATA1}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #GIVEN_NAME}</td>
         * <td>{@link #DATA2}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #FAMILY_NAME}</td>
         * <td>{@link #DATA3}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #PREFIX}</td>
         * <td>{@link #DATA4}</td>
         * <td>Common prefixes in English names are "Mr", "Ms", "Dr" etc.</td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #MIDDLE_NAME}</td>
         * <td>{@link #DATA5}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #SUFFIX}</td>
         * <td>{@link #DATA6}</td>
         * <td>Common suffixes in English names are "Sr", "Jr", "III" etc.</td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #PHONETIC_GIVEN_NAME}</td>
         * <td>{@link #DATA7}</td>
         * <td>Used for phonetic spelling of the name, e.g. Pinyin, Katakana, Hiragana</td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #PHONETIC_MIDDLE_NAME}</td>
         * <td>{@link #DATA8}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #PHONETIC_FAMILY_NAME}</td>
         * <td>{@link #DATA9}</td>
         * <td></td>
         * </tr>
         * </table>
         */
        public static final class StructuredName implements DataColumnsWithJoins {
            /**
             * This utility class cannot be instantiated
             */
            private StructuredName() {}

            /** MIME type used when storing this in data table. */
            public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/name";

            /**
             * The name that should be used to display the contact.
             * <i>Unstructured component of the name should be consistent with
             * its structured representation.</i>
             * <p>
             * Type: TEXT
             */
            public static final String DISPLAY_NAME = DATA1;

            /**
             * The given name for the contact.
             * <P>Type: TEXT</P>
             */
            public static final String GIVEN_NAME = DATA2;

            /**
             * The family name for the contact.
             * <P>Type: TEXT</P>
             */
            public static final String FAMILY_NAME = DATA3;

            /**
             * The contact's honorific prefix, e.g. "Sir"
             * <P>Type: TEXT</P>
             */
            public static final String PREFIX = DATA4;

            /**
             * The contact's middle name
             * <P>Type: TEXT</P>
             */
            public static final String MIDDLE_NAME = DATA5;

            /**
             * The contact's honorific suffix, e.g. "Jr"
             */
            public static final String SUFFIX = DATA6;

            /**
             * The phonetic version of the given name for the contact.
             * <P>Type: TEXT</P>
             */
            public static final String PHONETIC_GIVEN_NAME = DATA7;

            /**
             * The phonetic version of the additional name for the contact.
             * <P>Type: TEXT</P>
             */
            public static final String PHONETIC_MIDDLE_NAME = DATA8;

            /**
             * The phonetic version of the family name for the contact.
             * <P>Type: TEXT</P>
             */
            public static final String PHONETIC_FAMILY_NAME = DATA9;

            /**
             * The style used for combining given/middle/family name into a full name.
             * See {@link ContactsContract.FullNameStyle}.
             *
             * @hide
             */
            public static final String FULL_NAME_STYLE = DATA10;

            /**
             * The alphabet used for capturing the phonetic name.
             * See ContactsContract.PhoneticNameStyle.
             * @hide
             */
            public static final String PHONETIC_NAME_STYLE = DATA11;
        }

        /**
         * <p>A data kind representing the contact's nickname. For example, for
         * Bob Parr ("Mr. Incredible"):
         * <pre>
         * ArrayList&lt;ContentProviderOperation&gt; ops = Lists.newArrayList();
         * ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI)
         *          .withValue(Data.RAW_CONTACT_ID, rawContactId)
         *          .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
         *          .withValue(StructuredName.DISPLAY_NAME, &quot;Bob Parr&quot;)
         *          .build());
         *
         * ops.add(ContentProviderOperation.newInsert(Data.CONTENT_URI)
         *          .withValue(Data.RAW_CONTACT_ID, rawContactId)
         *          .withValue(Data.MIMETYPE, Nickname.CONTENT_ITEM_TYPE)
         *          .withValue(Nickname.NAME, "Mr. Incredible")
         *          .withValue(Nickname.TYPE, Nickname.TYPE_CUSTOM)
         *          .withValue(Nickname.LABEL, "Superhero")
         *          .build());
         *
         * getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
         * </pre>
         * </p>
         * <p>
         * You can use all columns defined for {@link ContactsContract.Data} as well as the
         * following aliases.
         * </p>
         *
         * <h2>Column aliases</h2>
         * <table class="jd-sumtable">
         * <tr>
         * <th>Type</th><th>Alias</th><th colspan='2'>Data column</th>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #NAME}</td>
         * <td>{@link #DATA1}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>int</td>
         * <td>{@link #TYPE}</td>
         * <td>{@link #DATA2}</td>
         * <td>
         * Allowed values are:
         * <p>
         * <ul>
         * <li>{@link #TYPE_CUSTOM}. Put the actual type in {@link #LABEL}.</li>
         * <li>{@link #TYPE_DEFAULT}</li>
         * <li>{@link #TYPE_OTHER_NAME}</li>
         * <li>{@link #TYPE_MAINDEN_NAME}</li>
         * <li>{@link #TYPE_SHORT_NAME}</li>
         * <li>{@link #TYPE_INITIALS}</li>
         * </ul>
         * </p>
         * </td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #LABEL}</td>
         * <td>{@link #DATA3}</td>
         * <td></td>
         * </tr>
         * </table>
         */
        public static final class Nickname implements DataColumnsWithJoins, CommonColumns {
            /**
             * This utility class cannot be instantiated
             */
            private Nickname() {}

            /** MIME type used when storing this in data table. */
            public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/nickname";

            public static final int TYPE_DEFAULT = 1;
            public static final int TYPE_OTHER_NAME = 2;
            public static final int TYPE_MAINDEN_NAME = 3;
            public static final int TYPE_SHORT_NAME = 4;
            public static final int TYPE_INITIALS = 5;

            /**
             * The name itself
             */
            public static final String NAME = DATA;
        }

        /**
         * <p>
         * A data kind representing a telephone number.
         * </p>
         * <p>
         * You can use all columns defined for {@link ContactsContract.Data} as
         * well as the following aliases.
         * </p>
         * <h2>Column aliases</h2>
         * <table class="jd-sumtable">
         * <tr>
         * <th>Type</th>
         * <th>Alias</th><th colspan='2'>Data column</th>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #NUMBER}</td>
         * <td>{@link #DATA1}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>int</td>
         * <td>{@link #TYPE}</td>
         * <td>{@link #DATA2}</td>
         * <td>Allowed values are:
         * <p>
         * <ul>
         * <li>{@link #TYPE_CUSTOM}. Put the actual type in {@link #LABEL}.</li>
         * <li>{@link #TYPE_HOME}</li>
         * <li>{@link #TYPE_MOBILE}</li>
         * <li>{@link #TYPE_WORK}</li>
         * <li>{@link #TYPE_FAX_WORK}</li>
         * <li>{@link #TYPE_FAX_HOME}</li>
         * <li>{@link #TYPE_PAGER}</li>
         * <li>{@link #TYPE_OTHER}</li>
         * <li>{@link #TYPE_CALLBACK}</li>
         * <li>{@link #TYPE_CAR}</li>
         * <li>{@link #TYPE_COMPANY_MAIN}</li>
         * <li>{@link #TYPE_ISDN}</li>
         * <li>{@link #TYPE_MAIN}</li>
         * <li>{@link #TYPE_OTHER_FAX}</li>
         * <li>{@link #TYPE_RADIO}</li>
         * <li>{@link #TYPE_TELEX}</li>
         * <li>{@link #TYPE_TTY_TDD}</li>
         * <li>{@link #TYPE_WORK_MOBILE}</li>
         * <li>{@link #TYPE_WORK_PAGER}</li>
         * <li>{@link #TYPE_ASSISTANT}</li>
         * <li>{@link #TYPE_MMS}</li>
         * </ul>
         * </p>
         * </td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #LABEL}</td>
         * <td>{@link #DATA3}</td>
         * <td></td>
         * </tr>
         * </table>
         */
        public static final class Phone implements DataColumnsWithJoins, CommonColumns {
            /**
             * This utility class cannot be instantiated
             */
            private Phone() {}

            /** MIME type used when storing this in data table. */
            public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/phone_v2";

            /**
             * The MIME type of {@link #CONTENT_URI} providing a directory of
             * phones.
             */
            public static final String CONTENT_TYPE = "vnd.android.cursor.dir/phone_v2";

            /**
             * The content:// style URI for all data records of the
             * {@link #CONTENT_ITEM_TYPE} MIME type, combined with the
             * associated raw contact and aggregate contact data.
             */
            public static final Uri CONTENT_URI = Uri.withAppendedPath(Data.CONTENT_URI,
                    "phones");

            /**
             * The content:// style URL for phone lookup using a filter. The filter returns
             * records of MIME type {@link #CONTENT_ITEM_TYPE}. The filter is applied
             * to display names as well as phone numbers. The filter argument should be passed
             * as an additional path segment after this URI.
             */
            public static final Uri CONTENT_FILTER_URI = Uri.withAppendedPath(CONTENT_URI,
                    "filter");

            public static final int TYPE_HOME = 1;
            public static final int TYPE_MOBILE = 2;
            public static final int TYPE_WORK = 3;
            public static final int TYPE_FAX_WORK = 4;
            public static final int TYPE_FAX_HOME = 5;
            public static final int TYPE_PAGER = 6;
            public static final int TYPE_OTHER = 7;
            public static final int TYPE_CALLBACK = 8;
            public static final int TYPE_CAR = 9;
            public static final int TYPE_COMPANY_MAIN = 10;
            public static final int TYPE_ISDN = 11;
            public static final int TYPE_MAIN = 12;
            public static final int TYPE_OTHER_FAX = 13;
            public static final int TYPE_RADIO = 14;
            public static final int TYPE_TELEX = 15;
            public static final int TYPE_TTY_TDD = 16;
            public static final int TYPE_WORK_MOBILE = 17;
            public static final int TYPE_WORK_PAGER = 18;
            public static final int TYPE_ASSISTANT = 19;
            public static final int TYPE_MMS = 20;

            /**
             * The phone number as the user entered it.
             * <P>Type: TEXT</P>
             */
            public static final String NUMBER = DATA;

            /**
             * @deprecated use {@link #getTypeLabel(Resources, int, CharSequence)} instead.
             * @hide
             */
            @Deprecated
            public static final CharSequence getDisplayLabel(Context context, int type,
                    CharSequence label, CharSequence[] labelArray) {
                return getTypeLabel(context.getResources(), type, label);
            }

            /**
             * @deprecated use {@link #getTypeLabel(Resources, int, CharSequence)} instead.
             * @hide
             */
            @Deprecated
            public static final CharSequence getDisplayLabel(Context context, int type,
                    CharSequence label) {
                return getTypeLabel(context.getResources(), type, label);
            }

            /**
             * Return the string resource that best describes the given
             * {@link #TYPE}. Will always return a valid resource.
             */
            public static final int getTypeLabelResource(int type) {
                switch (type) {
                    case TYPE_HOME: return com.android.internal.R.string.phoneTypeHome;
                    case TYPE_MOBILE: return com.android.internal.R.string.phoneTypeMobile;
                    case TYPE_WORK: return com.android.internal.R.string.phoneTypeWork;
                    case TYPE_FAX_WORK: return com.android.internal.R.string.phoneTypeFaxWork;
                    case TYPE_FAX_HOME: return com.android.internal.R.string.phoneTypeFaxHome;
                    case TYPE_PAGER: return com.android.internal.R.string.phoneTypePager;
                    case TYPE_OTHER: return com.android.internal.R.string.phoneTypeOther;
                    case TYPE_CALLBACK: return com.android.internal.R.string.phoneTypeCallback;
                    case TYPE_CAR: return com.android.internal.R.string.phoneTypeCar;
                    case TYPE_COMPANY_MAIN: return com.android.internal.R.string.phoneTypeCompanyMain;
                    case TYPE_ISDN: return com.android.internal.R.string.phoneTypeIsdn;
                    case TYPE_MAIN: return com.android.internal.R.string.phoneTypeMain;
                    case TYPE_OTHER_FAX: return com.android.internal.R.string.phoneTypeOtherFax;
                    case TYPE_RADIO: return com.android.internal.R.string.phoneTypeRadio;
                    case TYPE_TELEX: return com.android.internal.R.string.phoneTypeTelex;
                    case TYPE_TTY_TDD: return com.android.internal.R.string.phoneTypeTtyTdd;
                    case TYPE_WORK_MOBILE: return com.android.internal.R.string.phoneTypeWorkMobile;
                    case TYPE_WORK_PAGER: return com.android.internal.R.string.phoneTypeWorkPager;
                    case TYPE_ASSISTANT: return com.android.internal.R.string.phoneTypeAssistant;
                    case TYPE_MMS: return com.android.internal.R.string.phoneTypeMms;
                    default: return com.android.internal.R.string.phoneTypeCustom;
                }
            }

            /**
             * Return a {@link CharSequence} that best describes the given type,
             * possibly substituting the given {@link #LABEL} value
             * for {@link #TYPE_CUSTOM}.
             */
            public static final CharSequence getTypeLabel(Resources res, int type,
                    CharSequence label) {
                if ((type == TYPE_CUSTOM || type == TYPE_ASSISTANT) && !TextUtils.isEmpty(label)) {
                    return label;
                } else {
                    final int labelRes = getTypeLabelResource(type);
                    return res.getText(labelRes);
                }
            }
        }

        /**
         * <p>
         * A data kind representing an email address.
         * </p>
         * <p>
         * You can use all columns defined for {@link ContactsContract.Data} as
         * well as the following aliases.
         * </p>
         * <h2>Column aliases</h2>
         * <table class="jd-sumtable">
         * <tr>
         * <th>Type</th>
         * <th>Alias</th><th colspan='2'>Data column</th>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #DATA}</td>
         * <td>{@link #DATA1}</td>
         * <td>Email address itself.</td>
         * </tr>
         * <tr>
         * <td>int</td>
         * <td>{@link #TYPE}</td>
         * <td>{@link #DATA2}</td>
         * <td>Allowed values are:
         * <p>
         * <ul>
         * <li>{@link #TYPE_CUSTOM}. Put the actual type in {@link #LABEL}.</li>
         * <li>{@link #TYPE_HOME}</li>
         * <li>{@link #TYPE_WORK}</li>
         * <li>{@link #TYPE_OTHER}</li>
         * <li>{@link #TYPE_MOBILE}</li>
         * </ul>
         * </p>
         * </td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #LABEL}</td>
         * <td>{@link #DATA3}</td>
         * <td></td>
         * </tr>
         * </table>
         */
        public static final class Email implements DataColumnsWithJoins, CommonColumns {
            /**
             * This utility class cannot be instantiated
             */
            private Email() {}

            /** MIME type used when storing this in data table. */
            public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/email_v2";

            /**
             * The MIME type of {@link #CONTENT_URI} providing a directory of email addresses.
             */
            public static final String CONTENT_TYPE = "vnd.android.cursor.dir/email_v2";

            /**
             * The content:// style URI for all data records of the
             * {@link #CONTENT_ITEM_TYPE} MIME type, combined with the
             * associated raw contact and aggregate contact data.
             */
            public static final Uri CONTENT_URI = Uri.withAppendedPath(Data.CONTENT_URI,
                    "emails");

            /**
             * <p>
             * The content:// style URL for looking up data rows by email address. The
             * lookup argument, an email address, should be passed as an additional path segment
             * after this URI.
             * </p>
             * <p>Example:
             * <pre>
             * Uri uri = Uri.withAppendedPath(Email.CONTENT_LOOKUP_URI, Uri.encode(email));
             * Cursor c = getContentResolver().query(uri,
             *          new String[]{Email.CONTACT_ID, Email.DISPLAY_NAME, Email.DATA},
             *          null, null, null);
             * </pre>
             * </p>
             */
            public static final Uri CONTENT_LOOKUP_URI = Uri.withAppendedPath(CONTENT_URI,
                    "lookup");

            /**
             * <p>
             * The content:// style URL for email lookup using a filter. The filter returns
             * records of MIME type {@link #CONTENT_ITEM_TYPE}. The filter is applied
             * to display names as well as email addresses. The filter argument should be passed
             * as an additional path segment after this URI.
             * </p>
             * <p>The query in the following example will return "Robert Parr (bob@incredibles.com)"
             * as well as "Bob Parr (incredible@android.com)".
             * <pre>
             * Uri uri = Uri.withAppendedPath(Email.CONTENT_LOOKUP_URI, Uri.encode("bob"));
             * Cursor c = getContentResolver().query(uri,
             *          new String[]{Email.DISPLAY_NAME, Email.DATA},
             *          null, null, null);
             * </pre>
             * </p>
             */
            public static final Uri CONTENT_FILTER_URI = Uri.withAppendedPath(CONTENT_URI,
                    "filter");

            /**
             * The email address.
             * <P>Type: TEXT</P>
             * @hide TODO: Unhide in a separate CL
             */
            public static final String ADDRESS = DATA1;

            public static final int TYPE_HOME = 1;
            public static final int TYPE_WORK = 2;
            public static final int TYPE_OTHER = 3;
            public static final int TYPE_MOBILE = 4;

            /**
             * The display name for the email address
             * <P>Type: TEXT</P>
             */
            public static final String DISPLAY_NAME = DATA4;

            /**
             * Return the string resource that best describes the given
             * {@link #TYPE}. Will always return a valid resource.
             */
            public static final int getTypeLabelResource(int type) {
                switch (type) {
                    case TYPE_HOME: return com.android.internal.R.string.emailTypeHome;
                    case TYPE_WORK: return com.android.internal.R.string.emailTypeWork;
                    case TYPE_OTHER: return com.android.internal.R.string.emailTypeOther;
                    case TYPE_MOBILE: return com.android.internal.R.string.emailTypeMobile;
                    default: return com.android.internal.R.string.emailTypeCustom;
                }
            }

            /**
             * Return a {@link CharSequence} that best describes the given type,
             * possibly substituting the given {@link #LABEL} value
             * for {@link #TYPE_CUSTOM}.
             */
            public static final CharSequence getTypeLabel(Resources res, int type,
                    CharSequence label) {
                if (type == TYPE_CUSTOM && !TextUtils.isEmpty(label)) {
                    return label;
                } else {
                    final int labelRes = getTypeLabelResource(type);
                    return res.getText(labelRes);
                }
            }
        }

        /**
         * <p>
         * A data kind representing a postal addresses.
         * </p>
         * <p>
         * You can use all columns defined for {@link ContactsContract.Data} as
         * well as the following aliases.
         * </p>
         * <h2>Column aliases</h2>
         * <table class="jd-sumtable">
         * <tr>
         * <th>Type</th>
         * <th>Alias</th><th colspan='2'>Data column</th>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #FORMATTED_ADDRESS}</td>
         * <td>{@link #DATA1}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>int</td>
         * <td>{@link #TYPE}</td>
         * <td>{@link #DATA2}</td>
         * <td>Allowed values are:
         * <p>
         * <ul>
         * <li>{@link #TYPE_CUSTOM}. Put the actual type in {@link #LABEL}.</li>
         * <li>{@link #TYPE_HOME}</li>
         * <li>{@link #TYPE_WORK}</li>
         * <li>{@link #TYPE_OTHER}</li>
         * </ul>
         * </p>
         * </td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #LABEL}</td>
         * <td>{@link #DATA3}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #STREET}</td>
         * <td>{@link #DATA4}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #POBOX}</td>
         * <td>{@link #DATA5}</td>
         * <td>Post Office Box number</td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #NEIGHBORHOOD}</td>
         * <td>{@link #DATA6}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #CITY}</td>
         * <td>{@link #DATA7}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #REGION}</td>
         * <td>{@link #DATA8}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #POSTCODE}</td>
         * <td>{@link #DATA9}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #COUNTRY}</td>
         * <td>{@link #DATA10}</td>
         * <td></td>
         * </tr>
         * </table>
         */
        public static final class StructuredPostal implements DataColumnsWithJoins, CommonColumns {
            /**
             * This utility class cannot be instantiated
             */
            private StructuredPostal() {
            }

            /** MIME type used when storing this in data table. */
            public static final String CONTENT_ITEM_TYPE =
                    "vnd.android.cursor.item/postal-address_v2";

            /**
             * The MIME type of {@link #CONTENT_URI} providing a directory of
             * postal addresses.
             */
            public static final String CONTENT_TYPE = "vnd.android.cursor.dir/postal-address_v2";

            /**
             * The content:// style URI for all data records of the
             * {@link StructuredPostal#CONTENT_ITEM_TYPE} MIME type.
             */
            public static final Uri CONTENT_URI = Uri.withAppendedPath(Data.CONTENT_URI,
                    "postals");

            public static final int TYPE_HOME = 1;
            public static final int TYPE_WORK = 2;
            public static final int TYPE_OTHER = 3;

            /**
             * The full, unstructured postal address. <i>This field must be
             * consistent with any structured data.</i>
             * <p>
             * Type: TEXT
             */
            public static final String FORMATTED_ADDRESS = DATA;

            /**
             * Can be street, avenue, road, etc. This element also includes the
             * house number and room/apartment/flat/floor number.
             * <p>
             * Type: TEXT
             */
            public static final String STREET = DATA4;

            /**
             * Covers actual P.O. boxes, drawers, locked bags, etc. This is
             * usually but not always mutually exclusive with street.
             * <p>
             * Type: TEXT
             */
            public static final String POBOX = DATA5;

            /**
             * This is used to disambiguate a street address when a city
             * contains more than one street with the same name, or to specify a
             * small place whose mail is routed through a larger postal town. In
             * China it could be a county or a minor city.
             * <p>
             * Type: TEXT
             */
            public static final String NEIGHBORHOOD = DATA6;

            /**
             * Can be city, village, town, borough, etc. This is the postal town
             * and not necessarily the place of residence or place of business.
             * <p>
             * Type: TEXT
             */
            public static final String CITY = DATA7;

            /**
             * A state, province, county (in Ireland), Land (in Germany),
             * departement (in France), etc.
             * <p>
             * Type: TEXT
             */
            public static final String REGION = DATA8;

            /**
             * Postal code. Usually country-wide, but sometimes specific to the
             * city (e.g. "2" in "Dublin 2, Ireland" addresses).
             * <p>
             * Type: TEXT
             */
            public static final String POSTCODE = DATA9;

            /**
             * The name or code of the country.
             * <p>
             * Type: TEXT
             */
            public static final String COUNTRY = DATA10;

            /**
             * Return the string resource that best describes the given
             * {@link #TYPE}. Will always return a valid resource.
             */
            public static final int getTypeLabelResource(int type) {
                switch (type) {
                    case TYPE_HOME: return com.android.internal.R.string.postalTypeHome;
                    case TYPE_WORK: return com.android.internal.R.string.postalTypeWork;
                    case TYPE_OTHER: return com.android.internal.R.string.postalTypeOther;
                    default: return com.android.internal.R.string.postalTypeCustom;
                }
            }

            /**
             * Return a {@link CharSequence} that best describes the given type,
             * possibly substituting the given {@link #LABEL} value
             * for {@link #TYPE_CUSTOM}.
             */
            public static final CharSequence getTypeLabel(Resources res, int type,
                    CharSequence label) {
                if (type == TYPE_CUSTOM && !TextUtils.isEmpty(label)) {
                    return label;
                } else {
                    final int labelRes = getTypeLabelResource(type);
                    return res.getText(labelRes);
                }
            }
        }

        /**
         * <p>
         * A data kind representing an IM address
         * </p>
         * <p>
         * You can use all columns defined for {@link ContactsContract.Data} as
         * well as the following aliases.
         * </p>
         * <h2>Column aliases</h2>
         * <table class="jd-sumtable">
         * <tr>
         * <th>Type</th>
         * <th>Alias</th><th colspan='2'>Data column</th>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #DATA}</td>
         * <td>{@link #DATA1}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>int</td>
         * <td>{@link #TYPE}</td>
         * <td>{@link #DATA2}</td>
         * <td>Allowed values are:
         * <p>
         * <ul>
         * <li>{@link #TYPE_CUSTOM}. Put the actual type in {@link #LABEL}.</li>
         * <li>{@link #TYPE_HOME}</li>
         * <li>{@link #TYPE_WORK}</li>
         * <li>{@link #TYPE_OTHER}</li>
         * </ul>
         * </p>
         * </td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #LABEL}</td>
         * <td>{@link #DATA3}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #PROTOCOL}</td>
         * <td>{@link #DATA5}</td>
         * <td>
         * <p>
         * Allowed values:
         * <ul>
         * <li>{@link #PROTOCOL_CUSTOM}. Also provide the actual protocol name
         * as {@link #CUSTOM_PROTOCOL}.</li>
         * <li>{@link #PROTOCOL_AIM}</li>
         * <li>{@link #PROTOCOL_MSN}</li>
         * <li>{@link #PROTOCOL_YAHOO}</li>
         * <li>{@link #PROTOCOL_SKYPE}</li>
         * <li>{@link #PROTOCOL_QQ}</li>
         * <li>{@link #PROTOCOL_GOOGLE_TALK}</li>
         * <li>{@link #PROTOCOL_ICQ}</li>
         * <li>{@link #PROTOCOL_JABBER}</li>
         * <li>{@link #PROTOCOL_NETMEETING}</li>
         * </ul>
         * </p>
         * </td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #CUSTOM_PROTOCOL}</td>
         * <td>{@link #DATA6}</td>
         * <td></td>
         * </tr>
         * </table>
         */
        public static final class Im implements DataColumnsWithJoins, CommonColumns {
            /**
             * This utility class cannot be instantiated
             */
            private Im() {}

            /** MIME type used when storing this in data table. */
            public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/im";

            public static final int TYPE_HOME = 1;
            public static final int TYPE_WORK = 2;
            public static final int TYPE_OTHER = 3;

            /**
             * This column should be populated with one of the defined
             * constants, e.g. {@link #PROTOCOL_YAHOO}. If the value of this
             * column is {@link #PROTOCOL_CUSTOM}, the {@link #CUSTOM_PROTOCOL}
             * should contain the name of the custom protocol.
             */
            public static final String PROTOCOL = DATA5;

            public static final String CUSTOM_PROTOCOL = DATA6;

            /*
             * The predefined IM protocol types.
             */
            public static final int PROTOCOL_CUSTOM = -1;
            public static final int PROTOCOL_AIM = 0;
            public static final int PROTOCOL_MSN = 1;
            public static final int PROTOCOL_YAHOO = 2;
            public static final int PROTOCOL_SKYPE = 3;
            public static final int PROTOCOL_QQ = 4;
            public static final int PROTOCOL_GOOGLE_TALK = 5;
            public static final int PROTOCOL_ICQ = 6;
            public static final int PROTOCOL_JABBER = 7;
            public static final int PROTOCOL_NETMEETING = 8;

            /**
             * Return the string resource that best describes the given
             * {@link #TYPE}. Will always return a valid resource.
             */
            public static final int getTypeLabelResource(int type) {
                switch (type) {
                    case TYPE_HOME: return com.android.internal.R.string.imTypeHome;
                    case TYPE_WORK: return com.android.internal.R.string.imTypeWork;
                    case TYPE_OTHER: return com.android.internal.R.string.imTypeOther;
                    default: return com.android.internal.R.string.imTypeCustom;
                }
            }

            /**
             * Return a {@link CharSequence} that best describes the given type,
             * possibly substituting the given {@link #LABEL} value
             * for {@link #TYPE_CUSTOM}.
             */
            public static final CharSequence getTypeLabel(Resources res, int type,
                    CharSequence label) {
                if (type == TYPE_CUSTOM && !TextUtils.isEmpty(label)) {
                    return label;
                } else {
                    final int labelRes = getTypeLabelResource(type);
                    return res.getText(labelRes);
                }
            }

            /**
             * Return the string resource that best describes the given
             * {@link #PROTOCOL}. Will always return a valid resource.
             */
            public static final int getProtocolLabelResource(int type) {
                switch (type) {
                    case PROTOCOL_AIM: return com.android.internal.R.string.imProtocolAim;
                    case PROTOCOL_MSN: return com.android.internal.R.string.imProtocolMsn;
                    case PROTOCOL_YAHOO: return com.android.internal.R.string.imProtocolYahoo;
                    case PROTOCOL_SKYPE: return com.android.internal.R.string.imProtocolSkype;
                    case PROTOCOL_QQ: return com.android.internal.R.string.imProtocolQq;
                    case PROTOCOL_GOOGLE_TALK: return com.android.internal.R.string.imProtocolGoogleTalk;
                    case PROTOCOL_ICQ: return com.android.internal.R.string.imProtocolIcq;
                    case PROTOCOL_JABBER: return com.android.internal.R.string.imProtocolJabber;
                    case PROTOCOL_NETMEETING: return com.android.internal.R.string.imProtocolNetMeeting;
                    default: return com.android.internal.R.string.imProtocolCustom;
                }
            }

            /**
             * Return a {@link CharSequence} that best describes the given
             * protocol, possibly substituting the given
             * {@link #CUSTOM_PROTOCOL} value for {@link #PROTOCOL_CUSTOM}.
             */
            public static final CharSequence getProtocolLabel(Resources res, int type,
                    CharSequence label) {
                if (type == PROTOCOL_CUSTOM && !TextUtils.isEmpty(label)) {
                    return label;
                } else {
                    final int labelRes = getProtocolLabelResource(type);
                    return res.getText(labelRes);
                }
            }
        }

        /**
         * <p>
         * A data kind representing an organization.
         * </p>
         * <p>
         * You can use all columns defined for {@link ContactsContract.Data} as
         * well as the following aliases.
         * </p>
         * <h2>Column aliases</h2>
         * <table class="jd-sumtable">
         * <tr>
         * <th>Type</th>
         * <th>Alias</th><th colspan='2'>Data column</th>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #COMPANY}</td>
         * <td>{@link #DATA1}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>int</td>
         * <td>{@link #TYPE}</td>
         * <td>{@link #DATA2}</td>
         * <td>Allowed values are:
         * <p>
         * <ul>
         * <li>{@link #TYPE_CUSTOM}. Put the actual type in {@link #LABEL}.</li>
         * <li>{@link #TYPE_WORK}</li>
         * <li>{@link #TYPE_OTHER}</li>
         * </ul>
         * </p>
         * </td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #LABEL}</td>
         * <td>{@link #DATA3}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #TITLE}</td>
         * <td>{@link #DATA4}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #DEPARTMENT}</td>
         * <td>{@link #DATA5}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #JOB_DESCRIPTION}</td>
         * <td>{@link #DATA6}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #SYMBOL}</td>
         * <td>{@link #DATA7}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #PHONETIC_NAME}</td>
         * <td>{@link #DATA8}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #OFFICE_LOCATION}</td>
         * <td>{@link #DATA9}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>PHONETIC_NAME_STYLE</td>
         * <td>{@link #DATA10}</td>
         * <td></td>
         * </tr>
         * </table>
         */
        public static final class Organization implements DataColumnsWithJoins, CommonColumns {
            /**
             * This utility class cannot be instantiated
             */
            private Organization() {}

            /** MIME type used when storing this in data table. */
            public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/organization";

            public static final int TYPE_WORK = 1;
            public static final int TYPE_OTHER = 2;

            /**
             * The company as the user entered it.
             * <P>Type: TEXT</P>
             */
            public static final String COMPANY = DATA;

            /**
             * The position title at this company as the user entered it.
             * <P>Type: TEXT</P>
             */
            public static final String TITLE = DATA4;

            /**
             * The department at this company as the user entered it.
             * <P>Type: TEXT</P>
             */
            public static final String DEPARTMENT = DATA5;

            /**
             * The job description at this company as the user entered it.
             * <P>Type: TEXT</P>
             */
            public static final String JOB_DESCRIPTION = DATA6;

            /**
             * The symbol of this company as the user entered it.
             * <P>Type: TEXT</P>
             */
            public static final String SYMBOL = DATA7;

            /**
             * The phonetic name of this company as the user entered it.
             * <P>Type: TEXT</P>
             */
            public static final String PHONETIC_NAME = DATA8;

            /**
             * The office location of this organization.
             * <P>Type: TEXT</P>
             */
            public static final String OFFICE_LOCATION = DATA9;

            /**
             * The alphabet used for capturing the phonetic name.
             * See {@link ContactsContract.PhoneticNameStyle}.
             * @hide
             */
            public static final String PHONETIC_NAME_STYLE = DATA10;

            /**
             * Return the string resource that best describes the given
             * {@link #TYPE}. Will always return a valid resource.
             */
            public static final int getTypeLabelResource(int type) {
                switch (type) {
                    case TYPE_WORK: return com.android.internal.R.string.orgTypeWork;
                    case TYPE_OTHER: return com.android.internal.R.string.orgTypeOther;
                    default: return com.android.internal.R.string.orgTypeCustom;
                }
            }

            /**
             * Return a {@link CharSequence} that best describes the given type,
             * possibly substituting the given {@link #LABEL} value
             * for {@link #TYPE_CUSTOM}.
             */
            public static final CharSequence getTypeLabel(Resources res, int type,
                    CharSequence label) {
                if (type == TYPE_CUSTOM && !TextUtils.isEmpty(label)) {
                    return label;
                } else {
                    final int labelRes = getTypeLabelResource(type);
                    return res.getText(labelRes);
                }
            }
        }

        /**
         * <p>
         * A data kind representing a relation.
         * </p>
         * <p>
         * You can use all columns defined for {@link ContactsContract.Data} as
         * well as the following aliases.
         * </p>
         * <h2>Column aliases</h2>
         * <table class="jd-sumtable">
         * <tr>
         * <th>Type</th>
         * <th>Alias</th><th colspan='2'>Data column</th>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #NAME}</td>
         * <td>{@link #DATA1}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>int</td>
         * <td>{@link #TYPE}</td>
         * <td>{@link #DATA2}</td>
         * <td>Allowed values are:
         * <p>
         * <ul>
         * <li>{@link #TYPE_CUSTOM}. Put the actual type in {@link #LABEL}.</li>
         * <li>{@link #TYPE_ASSISTANT}</li>
         * <li>{@link #TYPE_BROTHER}</li>
         * <li>{@link #TYPE_CHILD}</li>
         * <li>{@link #TYPE_DOMESTIC_PARTNER}</li>
         * <li>{@link #TYPE_FATHER}</li>
         * <li>{@link #TYPE_FRIEND}</li>
         * <li>{@link #TYPE_MANAGER}</li>
         * <li>{@link #TYPE_MOTHER}</li>
         * <li>{@link #TYPE_PARENT}</li>
         * <li>{@link #TYPE_PARTNER}</li>
         * <li>{@link #TYPE_REFERRED_BY}</li>
         * <li>{@link #TYPE_RELATIVE}</li>
         * <li>{@link #TYPE_SISTER}</li>
         * <li>{@link #TYPE_SPOUSE}</li>
         * </ul>
         * </p>
         * </td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #LABEL}</td>
         * <td>{@link #DATA3}</td>
         * <td></td>
         * </tr>
         * </table>
         */
        public static final class Relation implements DataColumnsWithJoins, CommonColumns {
            /**
             * This utility class cannot be instantiated
             */
            private Relation() {}

            /** MIME type used when storing this in data table. */
            public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/relation";

            public static final int TYPE_ASSISTANT = 1;
            public static final int TYPE_BROTHER = 2;
            public static final int TYPE_CHILD = 3;
            public static final int TYPE_DOMESTIC_PARTNER = 4;
            public static final int TYPE_FATHER = 5;
            public static final int TYPE_FRIEND = 6;
            public static final int TYPE_MANAGER = 7;
            public static final int TYPE_MOTHER = 8;
            public static final int TYPE_PARENT = 9;
            public static final int TYPE_PARTNER = 10;
            public static final int TYPE_REFERRED_BY = 11;
            public static final int TYPE_RELATIVE = 12;
            public static final int TYPE_SISTER = 13;
            public static final int TYPE_SPOUSE = 14;

            /**
             * The name of the relative as the user entered it.
             * <P>Type: TEXT</P>
             */
            public static final String NAME = DATA;
        }

        /**
         * <p>
         * A data kind representing an event.
         * </p>
         * <p>
         * You can use all columns defined for {@link ContactsContract.Data} as
         * well as the following aliases.
         * </p>
         * <h2>Column aliases</h2>
         * <table class="jd-sumtable">
         * <tr>
         * <th>Type</th>
         * <th>Alias</th><th colspan='2'>Data column</th>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #START_DATE}</td>
         * <td>{@link #DATA1}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>int</td>
         * <td>{@link #TYPE}</td>
         * <td>{@link #DATA2}</td>
         * <td>Allowed values are:
         * <p>
         * <ul>
         * <li>{@link #TYPE_CUSTOM}. Put the actual type in {@link #LABEL}.</li>
         * <li>{@link #TYPE_ANNIVERSARY}</li>
         * <li>{@link #TYPE_OTHER}</li>
         * <li>{@link #TYPE_BIRTHDAY}</li>
         * </ul>
         * </p>
         * </td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #LABEL}</td>
         * <td>{@link #DATA3}</td>
         * <td></td>
         * </tr>
         * </table>
         */
        public static final class Event implements DataColumnsWithJoins, CommonColumns {
            /**
             * This utility class cannot be instantiated
             */
            private Event() {}

            /** MIME type used when storing this in data table. */
            public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/contact_event";

            public static final int TYPE_ANNIVERSARY = 1;
            public static final int TYPE_OTHER = 2;
            public static final int TYPE_BIRTHDAY = 3;

            /**
             * The event start date as the user entered it.
             * <P>Type: TEXT</P>
             */
            public static final String START_DATE = DATA;

            /**
             * Return the string resource that best describes the given
             * {@link #TYPE}. Will always return a valid resource.
             */
            public static int getTypeResource(Integer type) {
                if (type == null) {
                    return com.android.internal.R.string.eventTypeOther;
                }
                switch (type) {
                    case TYPE_ANNIVERSARY:
                        return com.android.internal.R.string.eventTypeAnniversary;
                    case TYPE_BIRTHDAY: return com.android.internal.R.string.eventTypeBirthday;
                    case TYPE_OTHER: return com.android.internal.R.string.eventTypeOther;
                    default: return com.android.internal.R.string.eventTypeOther;
                }
            }
        }

        /**
         * <p>
         * A data kind representing an photo for the contact.
         * </p>
         * <p>
         * Some sync adapters will choose to download photos in a separate
         * pass. A common pattern is to use columns {@link ContactsContract.Data#SYNC1}
         * through {@link ContactsContract.Data#SYNC4} to store temporary
         * data, e.g. the image URL or ID, state of download, server-side version
         * of the image.  It is allowed for the {@link #PHOTO} to be null.
         * </p>
         * <p>
         * You can use all columns defined for {@link ContactsContract.Data} as
         * well as the following aliases.
         * </p>
         * <h2>Column aliases</h2>
         * <table class="jd-sumtable">
         * <tr>
         * <th>Type</th>
         * <th>Alias</th><th colspan='2'>Data column</th>
         * </tr>
         * <tr>
         * <td>BLOB</td>
         * <td>{@link #PHOTO}</td>
         * <td>{@link #DATA15}</td>
         * <td>By convention, binary data is stored in DATA15.</td>
         * </tr>
         * </table>
         */
        public static final class Photo implements DataColumnsWithJoins {
            /**
             * This utility class cannot be instantiated
             */
            private Photo() {}

            /** MIME type used when storing this in data table. */
            public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/photo";

            /**
             * Thumbnail photo of the raw contact. This is the raw bytes of an image
             * that could be inflated using {@link android.graphics.BitmapFactory}.
             * <p>
             * Type: BLOB
             */
            public static final String PHOTO = DATA15;
        }

        /**
         * <p>
         * Notes about the contact.
         * </p>
         * <p>
         * You can use all columns defined for {@link ContactsContract.Data} as
         * well as the following aliases.
         * </p>
         * <h2>Column aliases</h2>
         * <table class="jd-sumtable">
         * <tr>
         * <th>Type</th>
         * <th>Alias</th><th colspan='2'>Data column</th>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #NOTE}</td>
         * <td>{@link #DATA1}</td>
         * <td></td>
         * </tr>
         * </table>
         */
        public static final class Note implements DataColumnsWithJoins {
            /**
             * This utility class cannot be instantiated
             */
            private Note() {}

            /** MIME type used when storing this in data table. */
            public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/note";

            /**
             * The note text.
             * <P>Type: TEXT</P>
             */
            public static final String NOTE = DATA1;
        }

        /**
         * <p>
         * Group Membership.
         * </p>
         * <p>
         * You can use all columns defined for {@link ContactsContract.Data} as
         * well as the following aliases.
         * </p>
         * <h2>Column aliases</h2>
         * <table class="jd-sumtable">
         * <tr>
         * <th>Type</th>
         * <th>Alias</th><th colspan='2'>Data column</th>
         * </tr>
         * <tr>
         * <td>long</td>
         * <td>{@link #GROUP_ROW_ID}</td>
         * <td>{@link #DATA1}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #GROUP_SOURCE_ID}</td>
         * <td>none</td>
         * <td>
         * <p>
         * The sourceid of the group that this group membership refers to.
         * Exactly one of this or {@link #GROUP_ROW_ID} must be set when
         * inserting a row.
         * </p>
         * <p>
         * If this field is specified, the provider will first try to
         * look up a group with this {@link Groups Groups.SOURCE_ID}.  If such a group
         * is found, it will use the corresponding row id.  If the group is not
         * found, it will create one.
         * </td>
         * </tr>
         * </table>
         */
        public static final class GroupMembership implements DataColumnsWithJoins {
            /**
             * This utility class cannot be instantiated
             */
            private GroupMembership() {}

            /** MIME type used when storing this in data table. */
            public static final String CONTENT_ITEM_TYPE =
                    "vnd.android.cursor.item/group_membership";

            /**
             * The row id of the group that this group membership refers to. Exactly one of
             * this or {@link #GROUP_SOURCE_ID} must be set when inserting a row.
             * <P>Type: INTEGER</P>
             */
            public static final String GROUP_ROW_ID = DATA1;

            /**
             * The sourceid of the group that this group membership refers to.  Exactly one of
             * this or {@link #GROUP_ROW_ID} must be set when inserting a row.
             * <P>Type: TEXT</P>
             */
            public static final String GROUP_SOURCE_ID = "group_sourceid";
        }

        /**
         * <p>
         * A data kind representing a website related to the contact.
         * </p>
         * <p>
         * You can use all columns defined for {@link ContactsContract.Data} as
         * well as the following aliases.
         * </p>
         * <h2>Column aliases</h2>
         * <table class="jd-sumtable">
         * <tr>
         * <th>Type</th>
         * <th>Alias</th><th colspan='2'>Data column</th>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #URL}</td>
         * <td>{@link #DATA1}</td>
         * <td></td>
         * </tr>
         * <tr>
         * <td>int</td>
         * <td>{@link #TYPE}</td>
         * <td>{@link #DATA2}</td>
         * <td>Allowed values are:
         * <p>
         * <ul>
         * <li>{@link #TYPE_CUSTOM}. Put the actual type in {@link #LABEL}.</li>
         * <li>{@link #TYPE_HOMEPAGE}</li>
         * <li>{@link #TYPE_BLOG}</li>
         * <li>{@link #TYPE_PROFILE}</li>
         * <li>{@link #TYPE_HOME}</li>
         * <li>{@link #TYPE_WORK}</li>
         * <li>{@link #TYPE_FTP}</li>
         * <li>{@link #TYPE_OTHER}</li>
         * </ul>
         * </p>
         * </td>
         * </tr>
         * <tr>
         * <td>String</td>
         * <td>{@link #LABEL}</td>
         * <td>{@link #DATA3}</td>
         * <td></td>
         * </tr>
         * </table>
         */
        public static final class Website implements DataColumnsWithJoins, CommonColumns {
            /**
             * This utility class cannot be instantiated
             */
            private Website() {}

            /** MIME type used when storing this in data table. */
            public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/website";

            public static final int TYPE_HOMEPAGE = 1;
            public static final int TYPE_BLOG = 2;
            public static final int TYPE_PROFILE = 3;
            public static final int TYPE_HOME = 4;
            public static final int TYPE_WORK = 5;
            public static final int TYPE_FTP = 6;
            public static final int TYPE_OTHER = 7;

            /**
             * The website URL string.
             * <P>Type: TEXT</P>
             */
            public static final String URL = DATA;
        }
    }

    /**
     * @see Groups
     */
    protected interface GroupsColumns {
        /**
         * The display title of this group.
         * <p>
         * Type: TEXT
         */
        public static final String TITLE = "title";

        /**
         * The package name to use when creating {@link Resources} objects for
         * this group. This value is only designed for use when building user
         * interfaces, and should not be used to infer the owner.
         *
         * @hide
         */
        public static final String RES_PACKAGE = "res_package";

        /**
         * The display title of this group to load as a resource from
         * {@link #RES_PACKAGE}, which may be localized.
         * <P>Type: TEXT</P>
         *
         * @hide
         */
        public static final String TITLE_RES = "title_res";

        /**
         * Notes about the group.
         * <p>
         * Type: TEXT
         */
        public static final String NOTES = "notes";

        /**
         * The ID of this group if it is a System Group, i.e. a group that has a special meaning
         * to the sync adapter, null otherwise.
         * <P>Type: TEXT</P>
         */
        public static final String SYSTEM_ID = "system_id";

        /**
         * The total number of {@link Contacts} that have
         * {@link CommonDataKinds.GroupMembership} in this group. Read-only value that is only
         * present when querying {@link Groups#CONTENT_SUMMARY_URI}.
         * <p>
         * Type: INTEGER
         */
        public static final String SUMMARY_COUNT = "summ_count";

        /**
         * The total number of {@link Contacts} that have both
         * {@link CommonDataKinds.GroupMembership} in this group, and also have phone numbers.
         * Read-only value that is only present when querying
         * {@link Groups#CONTENT_SUMMARY_URI}.
         * <p>
         * Type: INTEGER
         */
        public static final String SUMMARY_WITH_PHONES = "summ_phones";

        /**
         * Flag indicating if the contacts belonging to this group should be
         * visible in any user interface.
         * <p>
         * Type: INTEGER (boolean)
         */
        public static final String GROUP_VISIBLE = "group_visible";

        /**
         * The "deleted" flag: "0" by default, "1" if the row has been marked
         * for deletion. When {@link android.content.ContentResolver#delete} is
         * called on a group, it is marked for deletion. The sync adaptor
         * deletes the group on the server and then calls ContactResolver.delete
         * once more, this time setting the the
         * {@link ContactsContract#CALLER_IS_SYNCADAPTER} query parameter to
         * finalize the data removal.
         * <P>Type: INTEGER</P>
         */
        public static final String DELETED = "deleted";

        /**
         * Whether this group should be synced if the SYNC_EVERYTHING settings
         * is false for this group's account.
         * <p>
         * Type: INTEGER (boolean)
         */
        public static final String SHOULD_SYNC = "should_sync";
    }

    /**
     * Constants for the groups table. Only per-account groups are supported.
     * <h2>Columns</h2>
     * <table class="jd-sumtable">
     * <tr>
     * <th colspan='4'>Groups</th>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #_ID}</td>
     * <td>read-only</td>
     * <td>Row ID. Sync adapter should try to preserve row IDs during updates.
     * In other words, it would be a really bad idea to delete and reinsert a
     * group. A sync adapter should always do an update instead.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #TITLE}</td>
     * <td>read/write</td>
     * <td>The display title of this group.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #NOTES}</td>
     * <td>read/write</td>
     * <td>Notes about the group.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #SYSTEM_ID}</td>
     * <td>read/write</td>
     * <td>The ID of this group if it is a System Group, i.e. a group that has a
     * special meaning to the sync adapter, null otherwise.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #SUMMARY_COUNT}</td>
     * <td>read-only</td>
     * <td>The total number of {@link Contacts} that have
     * {@link CommonDataKinds.GroupMembership} in this group. Read-only value
     * that is only present when querying {@link Groups#CONTENT_SUMMARY_URI}.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #SUMMARY_WITH_PHONES}</td>
     * <td>read-only</td>
     * <td>The total number of {@link Contacts} that have both
     * {@link CommonDataKinds.GroupMembership} in this group, and also have
     * phone numbers. Read-only value that is only present when querying
     * {@link Groups#CONTENT_SUMMARY_URI}.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #GROUP_VISIBLE}</td>
     * <td>read-only</td>
     * <td>Flag indicating if the contacts belonging to this group should be
     * visible in any user interface. Allowed values: 0 and 1.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #DELETED}</td>
     * <td>read/write</td>
     * <td>The "deleted" flag: "0" by default, "1" if the row has been marked
     * for deletion. When {@link android.content.ContentResolver#delete} is
     * called on a group, it is marked for deletion. The sync adaptor deletes
     * the group on the server and then calls ContactResolver.delete once more,
     * this time setting the the {@link ContactsContract#CALLER_IS_SYNCADAPTER}
     * query parameter to finalize the data removal.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #SHOULD_SYNC}</td>
     * <td>read/write</td>
     * <td>Whether this group should be synced if the SYNC_EVERYTHING settings
     * is false for this group's account.</td>
     * </tr>
     * </table>
     */
    public static final class Groups implements BaseColumns, GroupsColumns, SyncColumns {
        /**
         * This utility class cannot be instantiated
         */
        private Groups() {
        }

        /**
         * The content:// style URI for this table
         */
        public static final Uri CONTENT_URI = Uri.withAppendedPath(AUTHORITY_URI, "groups");

        /**
         * The content:// style URI for this table joined with details data from
         * {@link ContactsContract.Data}.
         */
        public static final Uri CONTENT_SUMMARY_URI = Uri.withAppendedPath(AUTHORITY_URI,
                "groups_summary");

        /**
         * The MIME type of a directory of groups.
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/group";

        /**
         * The MIME type of a single group.
         */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/group";

        public static EntityIterator newEntityIterator(Cursor cursor) {
            return new EntityIteratorImpl(cursor);
        }

        private static class EntityIteratorImpl extends CursorEntityIterator {
            public EntityIteratorImpl(Cursor cursor) {
                super(cursor);
            }

            @Override
            public Entity getEntityAndIncrementCursor(Cursor cursor) throws RemoteException {
                // we expect the cursor is already at the row we need to read from
                final ContentValues values = new ContentValues();
                DatabaseUtils.cursorLongToContentValuesIfPresent(cursor, values, _ID);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, values, ACCOUNT_NAME);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, values, ACCOUNT_TYPE);
                DatabaseUtils.cursorLongToContentValuesIfPresent(cursor, values, DIRTY);
                DatabaseUtils.cursorLongToContentValuesIfPresent(cursor, values, VERSION);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, values, SOURCE_ID);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, values, RES_PACKAGE);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, values, TITLE);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, values, TITLE_RES);
                DatabaseUtils.cursorLongToContentValuesIfPresent(cursor, values, GROUP_VISIBLE);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, values, SYNC1);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, values, SYNC2);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, values, SYNC3);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, values, SYNC4);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, values, SYSTEM_ID);
                DatabaseUtils.cursorLongToContentValuesIfPresent(cursor, values, DELETED);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, values, NOTES);
                DatabaseUtils.cursorStringToContentValuesIfPresent(cursor, values, SHOULD_SYNC);
                cursor.moveToNext();
                return new Entity(values);
            }
        }
    }

    /**
     * <p>
     * Constants for the contact aggregation exceptions table, which contains
     * aggregation rules overriding those used by automatic aggregation. This
     * type only supports query and update. Neither insert nor delete are
     * supported.
     * </p>
     * <h2>Columns</h2>
     * <table class="jd-sumtable">
     * <tr>
     * <th colspan='4'>AggregationExceptions</th>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #TYPE}</td>
     * <td>read/write</td>
     * <td>The type of exception: {@link #TYPE_KEEP_TOGETHER},
     * {@link #TYPE_KEEP_SEPARATE} or {@link #TYPE_AUTOMATIC}.</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #RAW_CONTACT_ID1}</td>
     * <td>read/write</td>
     * <td>A reference to the {@link RawContacts#_ID} of the raw contact that
     * the rule applies to.</td>
     * </tr>
     * <tr>
     * <td>long</td>
     * <td>{@link #RAW_CONTACT_ID2}</td>
     * <td>read/write</td>
     * <td>A reference to the other {@link RawContacts#_ID} of the raw contact
     * that the rule applies to.</td>
     * </tr>
     * </table>
     */
    public static final class AggregationExceptions implements BaseColumns {
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
         * The MIME type of a {@link #CONTENT_URI} subdirectory of an aggregation exception
         */
        public static final String CONTENT_ITEM_TYPE =
                "vnd.android.cursor.item/aggregation_exception";

        /**
         * The type of exception: {@link #TYPE_KEEP_TOGETHER}, {@link #TYPE_KEEP_SEPARATE} or
         * {@link #TYPE_AUTOMATIC}.
         *
         * <P>Type: INTEGER</P>
         */
        public static final String TYPE = "type";

        /**
         * Allows the provider to automatically decide whether the specified raw contacts should
         * be included in the same aggregate contact or not.
         */
        public static final int TYPE_AUTOMATIC = 0;

        /**
         * Makes sure that the specified raw contacts are included in the same
         * aggregate contact.
         */
        public static final int TYPE_KEEP_TOGETHER = 1;

        /**
         * Makes sure that the specified raw contacts are NOT included in the same
         * aggregate contact.
         */
        public static final int TYPE_KEEP_SEPARATE = 2;

        /**
         * A reference to the {@link RawContacts#_ID} of the raw contact that the rule applies to.
         */
        public static final String RAW_CONTACT_ID1 = "raw_contact_id1";

        /**
         * A reference to the other {@link RawContacts#_ID} of the raw contact that the rule
         * applies to.
         */
        public static final String RAW_CONTACT_ID2 = "raw_contact_id2";
    }

    /**
     * @see Settings
     */
    protected interface SettingsColumns {
        /**
         * The name of the account instance to which this row belongs.
         * <P>Type: TEXT</P>
         */
        public static final String ACCOUNT_NAME = "account_name";

        /**
         * The type of account to which this row belongs, which when paired with
         * {@link #ACCOUNT_NAME} identifies a specific account.
         * <P>Type: TEXT</P>
         */
        public static final String ACCOUNT_TYPE = "account_type";

        /**
         * Depending on the mode defined by the sync-adapter, this flag controls
         * the top-level sync behavior for this data source.
         * <p>
         * Type: INTEGER (boolean)
         */
        public static final String SHOULD_SYNC = "should_sync";

        /**
         * Flag indicating if contacts without any {@link CommonDataKinds.GroupMembership}
         * entries should be visible in any user interface.
         * <p>
         * Type: INTEGER (boolean)
         */
        public static final String UNGROUPED_VISIBLE = "ungrouped_visible";

        /**
         * Read-only flag indicating if this {@link #SHOULD_SYNC} or any
         * {@link Groups#SHOULD_SYNC} under this account have been marked as
         * unsynced.
         */
        public static final String ANY_UNSYNCED = "any_unsynced";

        /**
         * Read-only count of {@link Contacts} from a specific source that have
         * no {@link CommonDataKinds.GroupMembership} entries.
         * <p>
         * Type: INTEGER
         */
        public static final String UNGROUPED_COUNT = "summ_count";

        /**
         * Read-only count of {@link Contacts} from a specific source that have
         * no {@link CommonDataKinds.GroupMembership} entries, and also have phone numbers.
         * <p>
         * Type: INTEGER
         */
        public static final String UNGROUPED_WITH_PHONES = "summ_phones";
    }

    /**
     * <p>
     * Contacts-specific settings for various {@link Account}'s.
     * </p>
     * <h2>Columns</h2>
     * <table class="jd-sumtable">
     * <tr>
     * <th colspan='4'>Settings</th>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #ACCOUNT_NAME}</td>
     * <td>read/write-once</td>
     * <td>The name of the account instance to which this row belongs.</td>
     * </tr>
     * <tr>
     * <td>String</td>
     * <td>{@link #ACCOUNT_TYPE}</td>
     * <td>read/write-once</td>
     * <td>The type of account to which this row belongs, which when paired with
     * {@link #ACCOUNT_NAME} identifies a specific account.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #SHOULD_SYNC}</td>
     * <td>read/write</td>
     * <td>Depending on the mode defined by the sync-adapter, this flag controls
     * the top-level sync behavior for this data source.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #UNGROUPED_VISIBLE}</td>
     * <td>read/write</td>
     * <td>Flag indicating if contacts without any
     * {@link CommonDataKinds.GroupMembership} entries should be visible in any
     * user interface.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #ANY_UNSYNCED}</td>
     * <td>read-only</td>
     * <td>Read-only flag indicating if this {@link #SHOULD_SYNC} or any
     * {@link Groups#SHOULD_SYNC} under this account have been marked as
     * unsynced.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #UNGROUPED_COUNT}</td>
     * <td>read-only</td>
     * <td>Read-only count of {@link Contacts} from a specific source that have
     * no {@link CommonDataKinds.GroupMembership} entries.</td>
     * </tr>
     * <tr>
     * <td>int</td>
     * <td>{@link #UNGROUPED_WITH_PHONES}</td>
     * <td>read-only</td>
     * <td>Read-only count of {@link Contacts} from a specific source that have
     * no {@link CommonDataKinds.GroupMembership} entries, and also have phone
     * numbers.</td>
     * </tr>
     * </table>
     */
    public static final class Settings implements SettingsColumns {
        /**
         * This utility class cannot be instantiated
         */
        private Settings() {
        }

        /**
         * The content:// style URI for this table
         */
        public static final Uri CONTENT_URI =
                Uri.withAppendedPath(AUTHORITY_URI, "settings");

        /**
         * The MIME-type of {@link #CONTENT_URI} providing a directory of
         * settings.
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/setting";

        /**
         * The MIME-type of {@link #CONTENT_URI} providing a single setting.
         */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/setting";
    }

    /**
     * Private API for inquiring about the general status of the provider.
     *
     * @hide
     */
    public static final class ProviderStatus {

        /**
         * Not instantiable.
         */
        private ProviderStatus() {
        }

        /**
         * The content:// style URI for this table.  Requests to this URI can be
         * performed on the UI thread because they are always unblocking.
         *
         * @hide
         */
        public static final Uri CONTENT_URI =
                Uri.withAppendedPath(AUTHORITY_URI, "provider_status");

        /**
         * The MIME-type of {@link #CONTENT_URI} providing a directory of
         * settings.
         *
         * @hide
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/provider_status";

        /**
         * An integer representing the current status of the provider.
         *
         * @hide
         */
        public static final String STATUS = "status";

        /**
         * Default status of the provider.
         *
         * @hide
         */
        public static final int STATUS_NORMAL = 0;

        /**
         * The status used when the provider is in the process of upgrading.  Contacts
         * are temporarily unaccessible.
         *
         * @hide
         */
        public static final int STATUS_UPGRADING = 1;

        /**
         * The status used if the provider was in the process of upgrading but ran
         * out of storage. The DATA1 column will contain the estimated amount of
         * storage required (in bytes). Update status to STATUS_NORMAL to force
         * the provider to retry the upgrade.
         *
         * @hide
         */
        public static final int STATUS_UPGRADE_OUT_OF_MEMORY = 2;

        /**
         * The status used during a locale change.
         *
         * @hide
         */
        public static final int STATUS_CHANGING_LOCALE = 3;

        /**
         * Additional data associated with the status.
         *
         * @hide
         */
        public static final String DATA1 = "data1";
    }

    /**
     * Helper methods to display QuickContact dialogs that allow users to pivot on
     * a specific {@link Contacts} entry.
     */
    public static final class QuickContact {
        /**
         * Action used to trigger person pivot dialog.
         * @hide
         */
        public static final String ACTION_QUICK_CONTACT =
                "com.android.contacts.action.QUICK_CONTACT";

        /**
         * Extra used to specify pivot dialog location in screen coordinates.
         * @deprecated Use {@link Intent#setSourceBounds(Rect)} instead.
         * @hide
         */
        @Deprecated
        public static final String EXTRA_TARGET_RECT = "target_rect";

        /**
         * Extra used to specify size of pivot dialog.
         * @hide
         */
        public static final String EXTRA_MODE = "mode";

        /**
         * Extra used to indicate a list of specific MIME-types to exclude and
         * not display. Stored as a {@link String} array.
         * @hide
         */
        public static final String EXTRA_EXCLUDE_MIMES = "exclude_mimes";

        /**
         * Small QuickContact mode, usually presented with minimal actions.
         */
        public static final int MODE_SMALL = 1;

        /**
         * Medium QuickContact mode, includes actions and light summary describing
         * the {@link Contacts} entry being shown. This may include social
         * status and presence details.
         */
        public static final int MODE_MEDIUM = 2;

        /**
         * Large QuickContact mode, includes actions and larger, card-like summary
         * of the {@link Contacts} entry being shown. This may include detailed
         * information, such as a photo.
         */
        public static final int MODE_LARGE = 3;

        /**
         * Trigger a dialog that lists the various methods of interacting with
         * the requested {@link Contacts} entry. This may be based on available
         * {@link ContactsContract.Data} rows under that contact, and may also
         * include social status and presence details.
         *
         * @param context The parent {@link Context} that may be used as the
         *            parent for this dialog.
         * @param target Specific {@link View} from your layout that this dialog
         *            should be centered around. In particular, if the dialog
         *            has a "callout" arrow, it will be pointed and centered
         *            around this {@link View}.
         * @param lookupUri A {@link ContactsContract.Contacts#CONTENT_LOOKUP_URI} style
         *            {@link Uri} that describes a specific contact to feature
         *            in this dialog.
         * @param mode Any of {@link #MODE_SMALL}, {@link #MODE_MEDIUM}, or
         *            {@link #MODE_LARGE}, indicating the desired dialog size,
         *            when supported.
         * @param excludeMimes Optional list of {@link Data#MIMETYPE} MIME-types
         *            to exclude when showing this dialog. For example, when
         *            already viewing the contact details card, this can be used
         *            to omit the details entry from the dialog.
         */
        public static void showQuickContact(Context context, View target, Uri lookupUri, int mode,
                String[] excludeMimes) {
            // Find location and bounds of target view, adjusting based on the
            // assumed local density.
            final float appScale = context.getResources().getCompatibilityInfo().applicationScale;
            final int[] pos = new int[2];
            target.getLocationOnScreen(pos);

            final Rect rect = new Rect();
            rect.left = (int) (pos[0] * appScale + 0.5f);
            rect.top = (int) (pos[1] * appScale + 0.5f);
            rect.right = (int) ((pos[0] + target.getWidth()) * appScale + 0.5f);
            rect.bottom = (int) ((pos[1] + target.getHeight()) * appScale + 0.5f);

            // Trigger with obtained rectangle
            showQuickContact(context, rect, lookupUri, mode, excludeMimes);
        }

        /**
         * Trigger a dialog that lists the various methods of interacting with
         * the requested {@link Contacts} entry. This may be based on available
         * {@link ContactsContract.Data} rows under that contact, and may also
         * include social status and presence details.
         *
         * @param context The parent {@link Context} that may be used as the
         *            parent for this dialog.
         * @param target Specific {@link Rect} that this dialog should be
         *            centered around, in screen coordinates. In particular, if
         *            the dialog has a "callout" arrow, it will be pointed and
         *            centered around this {@link Rect}. If you are running at a
         *            non-native density, you need to manually adjust using
         *            {@link DisplayMetrics#density} before calling.
         * @param lookupUri A
         *            {@link ContactsContract.Contacts#CONTENT_LOOKUP_URI} style
         *            {@link Uri} that describes a specific contact to feature
         *            in this dialog.
         * @param mode Any of {@link #MODE_SMALL}, {@link #MODE_MEDIUM}, or
         *            {@link #MODE_LARGE}, indicating the desired dialog size,
         *            when supported.
         * @param excludeMimes Optional list of {@link Data#MIMETYPE} MIME-types
         *            to exclude when showing this dialog. For example, when
         *            already viewing the contact details card, this can be used
         *            to omit the details entry from the dialog.
         */
        public static void showQuickContact(Context context, Rect target, Uri lookupUri, int mode,
                String[] excludeMimes) {
            // Launch pivot dialog through intent for now
            final Intent intent = new Intent(ACTION_QUICK_CONTACT);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

            intent.setData(lookupUri);
            intent.setSourceBounds(target);
            intent.putExtra(EXTRA_MODE, mode);
            intent.putExtra(EXTRA_EXCLUDE_MIMES, excludeMimes);
            context.startActivity(intent);
        }
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
         * Takes as input a data URI with a mailto: or tel: scheme. If a single
         * contact exists with the given data it will be shown. If no contact
         * exists, a dialog will ask the user if they want to create a new
         * contact with the provided details filled in. If multiple contacts
         * share the data the user will be prompted to pick which contact they
         * want to view.
         * <p>
         * For <code>mailto:</code> URIs, the scheme specific portion must be a
         * raw email address, such as one built using
         * {@link Uri#fromParts(String, String, String)}.
         * <p>
         * For <code>tel:</code> URIs, the scheme specific portion is compared
         * to existing numbers using the standard caller ID lookup algorithm.
         * The number must be properly encoded, for example using
         * {@link Uri#fromParts(String, String, String)}.
         * <p>
         * Any extras from the {@link Insert} class will be passed along to the
         * create activity if there are no contacts to show.
         * <p>
         * Passing true for the {@link #EXTRA_FORCE_CREATE} extra will skip
         * prompting the user when the contact doesn't exist.
         */
        public static final String SHOW_OR_CREATE_CONTACT =
                "com.android.contacts.action.SHOW_OR_CREATE_CONTACT";

        /**
         * Used with {@link #SHOW_OR_CREATE_CONTACT} to force creating a new
         * contact if no matching contact found. Otherwise, default behavior is
         * to prompt user with dialog before creating.
         * <p>
         * Type: BOOLEAN
         */
        public static final String EXTRA_FORCE_CREATE =
                "com.android.contacts.action.FORCE_CREATE";

        /**
         * Used with {@link #SHOW_OR_CREATE_CONTACT} to specify an exact
         * description to be shown when prompting user about creating a new
         * contact.
         * <p>
         * Type: STRING
         */
        public static final String EXTRA_CREATE_DESCRIPTION =
            "com.android.contacts.action.CREATE_DESCRIPTION";

        /**
         * Optional extra used with {@link #SHOW_OR_CREATE_CONTACT} to specify a
         * dialog location using screen coordinates. When not specified, the
         * dialog will be centered.
         *
         * @hide
         */
        @Deprecated
        public static final String EXTRA_TARGET_RECT = "target_rect";

        /**
         * Optional extra used with {@link #SHOW_OR_CREATE_CONTACT} to specify a
         * desired dialog style, usually a variation on size. One of
         * {@link #MODE_SMALL}, {@link #MODE_MEDIUM}, or {@link #MODE_LARGE}.
         *
         * @hide
         */
        @Deprecated
        public static final String EXTRA_MODE = "mode";

        /**
         * Value for {@link #EXTRA_MODE} to show a small-sized dialog.
         *
         * @hide
         */
        @Deprecated
        public static final int MODE_SMALL = 1;

        /**
         * Value for {@link #EXTRA_MODE} to show a medium-sized dialog.
         *
         * @hide
         */
        @Deprecated
        public static final int MODE_MEDIUM = 2;

        /**
         * Value for {@link #EXTRA_MODE} to show a large-sized dialog.
         *
         * @hide
         */
        @Deprecated
        public static final int MODE_LARGE = 3;

        /**
         * Optional extra used with {@link #SHOW_OR_CREATE_CONTACT} to indicate
         * a list of specific MIME-types to exclude and not display. Stored as a
         * {@link String} array.
         *
         * @hide
         */
        @Deprecated
        public static final String EXTRA_EXCLUDE_MIMES = "exclude_mimes";

        /**
         * Intents related to the Contacts app UI.
         *
         * @hide
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

            // TODO add structured name values here.

            /**
             * The extra field for the contact phonetic name.
             * <P>Type: String</P>
             */
            public static final String PHONETIC_NAME = "phonetic_name";

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
             * <P>Type: Either an integer value from
             * {@link CommonDataKinds.Phone},
             *  or a string specifying a custom label.</P>
             */
            public static final String PHONE_TYPE = "phone_type";

            /**
             * The extra field for the phone isprimary flag.
             * <P>Type: boolean</P>
             */
            public static final String PHONE_ISPRIMARY = "phone_isprimary";

            /**
             * The extra field for an optional second contact phone number.
             * <P>Type: String</P>
             */
            public static final String SECONDARY_PHONE = "secondary_phone";

            /**
             * The extra field for an optional second contact phone number type.
             * <P>Type: Either an integer value from
             * {@link CommonDataKinds.Phone},
             *  or a string specifying a custom label.</P>
             */
            public static final String SECONDARY_PHONE_TYPE = "secondary_phone_type";

            /**
             * The extra field for an optional third contact phone number.
             * <P>Type: String</P>
             */
            public static final String TERTIARY_PHONE = "tertiary_phone";

            /**
             * The extra field for an optional third contact phone number type.
             * <P>Type: Either an integer value from
             * {@link CommonDataKinds.Phone},
             *  or a string specifying a custom label.</P>
             */
            public static final String TERTIARY_PHONE_TYPE = "tertiary_phone_type";

            /**
             * The extra field for the contact email address.
             * <P>Type: String</P>
             */
            public static final String EMAIL = "email";

            /**
             * The extra field for the contact email type.
             * <P>Type: Either an integer value from
             * {@link CommonDataKinds.Email}
             *  or a string specifying a custom label.</P>
             */
            public static final String EMAIL_TYPE = "email_type";

            /**
             * The extra field for the email isprimary flag.
             * <P>Type: boolean</P>
             */
            public static final String EMAIL_ISPRIMARY = "email_isprimary";

            /**
             * The extra field for an optional second contact email address.
             * <P>Type: String</P>
             */
            public static final String SECONDARY_EMAIL = "secondary_email";

            /**
             * The extra field for an optional second contact email type.
             * <P>Type: Either an integer value from
             * {@link CommonDataKinds.Email}
             *  or a string specifying a custom label.</P>
             */
            public static final String SECONDARY_EMAIL_TYPE = "secondary_email_type";

            /**
             * The extra field for an optional third contact email address.
             * <P>Type: String</P>
             */
            public static final String TERTIARY_EMAIL = "tertiary_email";

            /**
             * The extra field for an optional third contact email type.
             * <P>Type: Either an integer value from
             * {@link CommonDataKinds.Email}
             *  or a string specifying a custom label.</P>
             */
            public static final String TERTIARY_EMAIL_TYPE = "tertiary_email_type";

            /**
             * The extra field for the contact postal address.
             * <P>Type: String</P>
             */
            public static final String POSTAL = "postal";

            /**
             * The extra field for the contact postal address type.
             * <P>Type: Either an integer value from
             * {@link CommonDataKinds.StructuredPostal}
             *  or a string specifying a custom label.</P>
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
