/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.content.ContentQueryMap;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;

import java.util.HashMap;

/**
 * The GTalk provider stores all information about roster contacts, chat messages, presence, etc.
 *
 * @hide
 */
public class Im {
    /**
     * no public constructor since this is a utility class
     */
    private Im() {}

    /**
     * The Columns for IM providers
     */
    public interface ProviderColumns {
        /**
         * The name of the IM provider
         * <P>Type: TEXT</P>
         */
        String NAME = "name";

        /**
         * The full name of the provider
         * <P>Type: TEXT</P>
         */
        String FULLNAME = "fullname";

        /**
         * The category for the provider, used to form intent.
         * <P>Type: TEXT</P>
         */
        String CATEGORY = "category";

        /**
         * The url users should visit to create a new account for this provider
         * <P>Type: TEXT</P>
         */
        String SIGNUP_URL = "signup_url";
    }

    /**
     * Known names corresponding to the {@link ProviderColumns#NAME} column
     */
    public interface ProviderNames {
        //
        //NOTE: update Contacts.java with new providers when they're added.
        //
        String YAHOO = "Yahoo";
        String GTALK = "GTalk";
        String MSN = "MSN";
        String ICQ = "ICQ";
        String AIM = "AIM";
        String XMPP = "XMPP";
        String JABBER = "JABBER";
        String SKYPE = "SKYPE";
        String QQ = "QQ";
    }

    /**
     * This table contains the IM providers
     */
    public static final class Provider implements BaseColumns, ProviderColumns {
        private Provider() {}

        public static final long getProviderIdForName(ContentResolver cr, String providerName) {
            String[] selectionArgs = new String[1];
            selectionArgs[0] = providerName;

            Cursor cursor = cr.query(CONTENT_URI,
                    PROVIDER_PROJECTION,
                    NAME+"=?",
                    selectionArgs, null);

            long retVal = 0;
            try {
                if (cursor.moveToFirst()) {
                    retVal = cursor.getLong(cursor.getColumnIndexOrThrow(_ID));
                }
            } finally {
                cursor.close();
            }

            return retVal;
        }

        public static final String getProviderNameForId(ContentResolver cr, long providerId) {
            Cursor cursor = cr.query(CONTENT_URI,
                    PROVIDER_PROJECTION,
                    _ID + "=" + providerId,
                    null, null);

            String retVal = null;
            try {
                if (cursor.moveToFirst()) {
                    retVal = cursor.getString(cursor.getColumnIndexOrThrow(NAME));
                }
            } finally {
                cursor.close();
            }

            return retVal;
        }

        private static final String[] PROVIDER_PROJECTION = new String[] {
                _ID,
                NAME
        };

        public static final String ACTIVE_ACCOUNT_ID = "account_id";
        public static final String ACTIVE_ACCOUNT_USERNAME = "account_username";
        public static final String ACTIVE_ACCOUNT_PW = "account_pw";
        public static final String ACTIVE_ACCOUNT_LOCKED = "account_locked";
        public static final String ACTIVE_ACCOUNT_KEEP_SIGNED_IN = "account_keepSignedIn";
        public static final String ACCOUNT_PRESENCE_STATUS = "account_presenceStatus";
        public static final String ACCOUNT_CONNECTION_STATUS = "account_connStatus";

        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI =
            Uri.parse("content://com.google.android.providers.talk/providers");

        public static final Uri CONTENT_URI_WITH_ACCOUNT =
            Uri.parse("content://com.google.android.providers.talk/providers/account");

        /**
         * The MIME type of {@link #CONTENT_URI} providing a directory of
         * people.
         */
        public static final String CONTENT_TYPE =
                "vnd.android.cursor.dir/gtalk-providers";

        public static final String CONTENT_ITEM_TYPE =
                "vnd.android.cursor.item/gtalk-providers";

        /**
         * The default sort order for this table
         */
        public static final String DEFAULT_SORT_ORDER = "name ASC";
    }

    /**
     * The columns for IM accounts. There can be more than one account for each IM provider.
     */
    public interface AccountColumns {
        /**
         * The name of the account
         * <P>Type: TEXT</P>
         */
        String NAME = "name";

        /**
         * The IM provider for this account
         * <P>Type: INTEGER</P>
         */
        String PROVIDER = "provider";

        /**
         * The username for this account
         * <P>Type: TEXT</P>
         */
        String USERNAME = "username";

        /**
         * The password for this account
         * <P>Type: TEXT</P>
         */
        String PASSWORD = "pw";

        /**
         * A boolean value indicates if the account is active.
         * <P>Type: INTEGER</P>
         */
        String ACTIVE = "active";

        /**
         * A boolean value indicates if the account is locked (not editable)
         * <P>Type: INTEGER</P>
         */
        String LOCKED = "locked";

        /**
         * A boolean value to indicate whether this account is kept signed in.
         * <P>Type: INTEGER</P>
         */
        String KEEP_SIGNED_IN = "keep_signed_in";

        /**
         * A boolean value indiciating the last login state for this account
         * <P>Type: INTEGER</P>
         */
        String LAST_LOGIN_STATE = "last_login_state";
    }

    /**
     * This table contains the IM accounts.
     */
    public static final class Account implements BaseColumns, AccountColumns {
        private Account() {}

        public static final long getProviderIdForAccount(ContentResolver cr, long accountId) {
            Cursor cursor = cr.query(CONTENT_URI,
                    PROVIDER_PROJECTION,
                    _ID + "=" + accountId,
                    null /* selection args */,
                    null /* sort order */);

            long providerId = 0;

            try {
                if (cursor.moveToFirst()) {
                    providerId = cursor.getLong(PROVIDER_COLUMN);
                }
            } finally {
                cursor.close();
            }

            return providerId;
        }

        private static final String[] PROVIDER_PROJECTION = new String[] { PROVIDER };
        private static final int PROVIDER_COLUMN = 0;

        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI =
            Uri.parse("content://com.google.android.providers.talk/accounts");

        /**
         * The MIME type of {@link #CONTENT_URI} providing a directory of
         * account.
         */
        public static final String CONTENT_TYPE =
                "vnd.android.cursor.dir/gtalk-accounts";

        /**
         * The MIME type of a {@link #CONTENT_URI} subdirectory of a single
         * account.
         */
        public static final String CONTENT_ITEM_TYPE =
                "vnd.android.cursor.item/gtalk-accounts";

        /**
         * The default sort order for this table
         */
        public static final String DEFAULT_SORT_ORDER = "name ASC";

    }

    /**
     * Connection status
     */
    public interface ConnectionStatus {
        /**
         * The connection is offline, not logged in.
         */
        int OFFLINE = 0;

        /**
         * The connection is attempting to connect.
         */
        int CONNECTING = 1;

        /**
         * The connection is suspended due to network not available.
         */
        int SUSPENDED = 2;

        /**
         * The connection is logged in and online.
         */
        int ONLINE = 3;
    }

    public interface AccountStatusColumns {
        /**
         * account id
         * <P>Type: INTEGER</P>
         */
        String ACCOUNT = "account";

        /**
         * User's presence status, see definitions in {#link CommonPresenceColumn}
         * <P>Type: INTEGER</P>
         */
        String PRESENCE_STATUS = "presenceStatus";

        /**
         * The connection status of this account, see {#link ConnectionStatus}
         * <P>Type: INTEGER</P>
         */
        String CONNECTION_STATUS = "connStatus";
    }

    public static final class AccountStatus implements BaseColumns, AccountStatusColumns {
        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI =
            Uri.parse("content://com.google.android.providers.talk/accountStatus");

        /**
         * The MIME type of {@link #CONTENT_URI} providing a directory of account status.
         */
        public static final String CONTENT_TYPE =
                "vnd.android.cursor.dir/gtalk-account-status";

        /**
         * The MIME type of a {@link #CONTENT_URI} subdirectory of a single account status.
         */
        public static final String CONTENT_ITEM_TYPE =
                "vnd.android.cursor.item/gtalk-account-status";

        /**
         * The default sort order for this table
         */
        public static final String DEFAULT_SORT_ORDER = "name ASC";
    }

    /**
     * Columns from the Contacts table.
     */
    public interface ContactsColumns {
        /**
         * The username
         * <P>Type: TEXT</P>
         */
        String USERNAME = "username";

        /**
         * The nickname or display name
         * <P>Type: TEXT</P>
         */
        String NICKNAME = "nickname";

        /**
         * The IM provider for this contact
         * <P>Type: INTEGER</P>
         */
        String PROVIDER = "provider";

        /**
         * The account (within a IM provider) for this contact
         * <P>Type: INTEGER</P>
         */
        String ACCOUNT = "account";

        /**
         * The contactList this contact belongs to
         * <P>Type: INTEGER</P>
         */
        String CONTACTLIST = "contactList";

        /**
         * Contact type
         * <P>Type: INTEGER</P>
         */
        String TYPE = "type";

        /**
         * normal IM contact
         */
        int TYPE_NORMAL = 0;
        /**
         * temporary contact, someone not in the list of contacts that we
         * subscribe presence for. Usually created because of the user is
         * having a chat session with this contact.
         */
        int TYPE_TEMPORARY = 1;
        /**
         * temporary contact created for group chat.
         */
        int TYPE_GROUP = 2;
        /**
         * blocked contact.
         */
        int TYPE_BLOCKED = 3;
        /**
         * the contact is hidden. The client should always display this contact to the user.
         */
        int TYPE_HIDDEN = 4;
        /**
         * the contact is pinned. The client should always display this contact to the user.
         */
        int TYPE_PINNED = 5;

        /**
         * Contact subscription status
         * <P>Type: INTEGER</P>
         */
        String SUBSCRIPTION_STATUS = "subscriptionStatus";

        /**
         * no pending subscription
         */
        int SUBSCRIPTION_STATUS_NONE = 0;
        /**
         * requested to subscribe
         */
        int SUBSCRIPTION_STATUS_SUBSCRIBE_PENDING = 1;
        /**
         * requested to unsubscribe
         */
        int SUBSCRIPTION_STATUS_UNSUBSCRIBE_PENDING = 2;

        /**
         * Contact subscription type
         * <P>Type: INTEGER </P>
         */
        String SUBSCRIPTION_TYPE = "subscriptionType";

        /**
         * The user and contact have no interest in each other's presence.
         */
        int SUBSCRIPTION_TYPE_NONE = 0;
        /**
         * The user wishes to stop receiving presence updates from the contact.
         */
        int SUBSCRIPTION_TYPE_REMOVE = 1;
        /**
         * The user is interested in receiving presence updates from the contact.
         */
        int SUBSCRIPTION_TYPE_TO = 2;
        /**
         * The contact is interested in receiving presence updates from the user.
         */
        int SUBSCRIPTION_TYPE_FROM = 3;
        /**
         * The user and contact have a mutual interest in each other's presence.
         */
        int SUBSCRIPTION_TYPE_BOTH = 4;
        /**
         * This is a special type reserved for pending subscription requests
         */
        int SUBSCRIPTION_TYPE_INVITATIONS = 5;

        /**
         * Quick Contact: derived from Google Contact Extension's "message_count" attribute.
         * <P>Type: INTEGER</P>
         */
        String QUICK_CONTACT = "qc";

        /**
         * Google Contact Extension attribute
         *
         * Rejected: a boolean value indicating whether a subscription request from
         * this client was ever rejected by the user. "true" indicates that it has.
         * This is provided so that a client can block repeated subscription requests.
         * <P>Type: INTEGER</P>
         */
        String REJECTED = "rejected";

        /**
         * Off The Record status: 0 for disabled, 1 for enabled
         * <P>Type: INTEGER </P>
         */
        String OTR = "otr";
    }

    /**
     * This defines the different type of values of {@link ContactsColumns#OTR}
     */
    public interface OffTheRecordType {
        /*
         * Off the record not turned on
         */
        int DISABLED = 0;
        /**
         * Off the record turned on, but we don't know who turned it on
         */
        int ENABLED = 1;
        /**
         * Off the record turned on by the user
         */
        int ENABLED_BY_USER = 2;
        /**
         * Off the record turned on by the buddy
         */
        int ENABLED_BY_BUDDY = 3;
    };

    /**
     * This table contains contacts.
     */
    public static final class Contacts implements BaseColumns,
            ContactsColumns, PresenceColumns, ChatsColumns {
        /**
         * no public constructor since this is a utility class
         */
        private Contacts() {}

        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI =
            Uri.parse("content://com.google.android.providers.talk/contacts");

        /**
         * The content:// style URL for contacts joined with presence
         */
        public static final Uri CONTENT_URI_WITH_PRESENCE =
            Uri.parse("content://com.google.android.providers.talk/contactsWithPresence");

        /**
         * The content:// style URL for barebone contacts, not joined with any other table
         */
        public static final Uri CONTENT_URI_CONTACTS_BAREBONE =
            Uri.parse("content://com.google.android.providers.talk/contactsBarebone");

        /**
         * The content:// style URL for contacts who have an open chat session
         */
        public static final Uri CONTENT_URI_CHAT_CONTACTS =
            Uri.parse("content://com.google.android.providers.talk/contacts_chatting");

        /**
         * The content:// style URL for contacts who have been blocked
         */
        public static final Uri CONTENT_URI_BLOCKED_CONTACTS =
            Uri.parse("content://com.google.android.providers.talk/contacts/blocked");

        /**
         * The content:// style URL for contacts by provider and account
         */
        public static final Uri CONTENT_URI_CONTACTS_BY =
            Uri.parse("content://com.google.android.providers.talk/contacts");

        /**
         * The content:// style URL for contacts by provider and account,
         * and who have an open chat session
         */
        public static final Uri CONTENT_URI_CHAT_CONTACTS_BY =
            Uri.parse("content://com.google.android.providers.talk/contacts/chatting");

        /**
         * The content:// style URL for contacts by provider and account,
         * and who are online
         */
        public static final Uri CONTENT_URI_ONLINE_CONTACTS_BY =
            Uri.parse("content://com.google.android.providers.talk/contacts/online");

        /**
         * The content:// style URL for contacts by provider and account,
         * and who are offline
         */
        public static final Uri CONTENT_URI_OFFLINE_CONTACTS_BY =
            Uri.parse("content://com.google.android.providers.talk/contacts/offline");

        /**
         * The content:// style URL for operations on bulk contacts
         */
        public static final Uri BULK_CONTENT_URI =
                Uri.parse("content://com.google.android.providers.talk/bulk_contacts");

        /**
         * The content:// style URL for the count of online contacts in each
         * contact list by provider and account.
         */
        public static final Uri CONTENT_URI_ONLINE_COUNT =
            Uri.parse("content://com.google.android.providers.talk/contacts/onlineCount");

        /**
         * The MIME type of {@link #CONTENT_URI} providing a directory of
         * people.
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/gtalk-contacts";

        /**
         * The MIME type of a {@link #CONTENT_URI} subdirectory of a single
         * person.
         */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/gtalk-contacts";

        /**
         * The default sort order for this table
         */
        public static final String DEFAULT_SORT_ORDER =
                "subscriptionType DESC, last_message_date DESC," +
                        " mode DESC, nickname COLLATE UNICODE ASC";

        public static final String CHATS_CONTACT = "chats_contact";

        public static final String AVATAR_HASH = "avatars_hash";

        public static final String AVATAR_DATA = "avatars_data";
    }

    /**
     * Columns from the ContactList table.
     */
    public interface ContactListColumns {
        String NAME = "name";
        String PROVIDER = "provider";
        String ACCOUNT = "account";
    }

    /**
     * This table contains the contact lists.
     */
    public static final class ContactList implements BaseColumns,
            ContactListColumns {
        private ContactList() {}

        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI =
            Uri.parse("content://com.google.android.providers.talk/contactLists");

        /**
         * The MIME type of {@link #CONTENT_URI} providing a directory of
         * people.
         */
        public static final String CONTENT_TYPE =
                "vnd.android.cursor.dir/gtalk-contactLists";

        /**
         * The MIME type of a {@link #CONTENT_URI} subdirectory of a single
         * person.
         */
        public static final String CONTENT_ITEM_TYPE =
                "vnd.android.cursor.item/gtalk-contactLists";

        /**
         * The default sort order for this table
         */
        public static final String DEFAULT_SORT_ORDER = "name COLLATE UNICODE ASC";

        public static final String PROVIDER_NAME = "provider_name";

        public static final String ACCOUNT_NAME = "account_name";
    }

    /**
     * Columns from the BlockedList table.
     */
    public interface BlockedListColumns {
        /**
         * The username of the blocked contact.
         * <P>Type: TEXT</P>
         */
        String USERNAME = "username";

        /**
         * The nickname of the blocked contact.
         * <P>Type: TEXT</P>
         */
        String NICKNAME = "nickname";

        /**
         * The provider id of the blocked contact.
         * <P>Type: INT</P>
         */
        String PROVIDER = "provider";

        /**
         * The account id of the blocked contact.
         * <P>Type: INT</P>
         */
        String ACCOUNT = "account";
    }

    /**
     * This table contains blocked lists
     */
    public static final class BlockedList implements BaseColumns, BlockedListColumns {
        private BlockedList() {}

        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI =
            Uri.parse("content://com.google.android.providers.talk/blockedList");

        /**
         * The MIME type of {@link #CONTENT_URI} providing a directory of
         * people.
         */
        public static final String CONTENT_TYPE =
                "vnd.android.cursor.dir/gtalk-blockedList";

        /**
         * The MIME type of a {@link #CONTENT_URI} subdirectory of a single
         * person.
         */
        public static final String CONTENT_ITEM_TYPE =
                "vnd.android.cursor.item/gtalk-blockedList";

        /**
         * The default sort order for this table
         */
        public static final String DEFAULT_SORT_ORDER = "nickname ASC";

        public static final String PROVIDER_NAME = "provider_name";

        public static final String ACCOUNT_NAME = "account_name";

        public static final String AVATAR_DATA = "avatars_data";
    }

    /**
     * Columns from the contactsEtag table
     */
    public interface ContactsEtagColumns {
        /**
         * The roster etag, computed by the server, stored on the client. There is one etag
         * per account roster.
         * <P>Type: TEXT</P>
         */
        String ETAG = "etag";

        /**
         * The OTR etag, computed by the server, stored on the client. There is one OTR etag
         * per account roster.
         * <P>Type: TEXT</P>
         */
        String OTR_ETAG = "otr_etag";

        /**
         * The account id for the etag.
         * <P> Type: INTEGER </P>
         */
        String ACCOUNT = "account";
    }

    public static final class ContactsEtag implements BaseColumns, ContactsEtagColumns {
        private ContactsEtag() {}

        public static final Cursor query(ContentResolver cr,
                String[] projection) {
            return cr.query(CONTENT_URI, projection, null, null, null);
        }

        public static final Cursor query(ContentResolver cr,
                String[] projection, String where, String orderBy) {
            return cr.query(CONTENT_URI, projection, where,
                    null, orderBy == null ? null : orderBy);
        }

        public static final String getRosterEtag(ContentResolver resolver, long accountId) {
            String retVal = null;

            Cursor c = resolver.query(CONTENT_URI,
                    CONTACT_ETAG_PROJECTION,
                    ACCOUNT + "=" + accountId,
                    null /* selection args */,
                    null /* sort order */);

            try {
                if (c.moveToFirst()) {
                    retVal = c.getString(COLUMN_ETAG);
                }
            } finally {
                c.close();
            }

            return retVal;
        }

        public static final String getOtrEtag(ContentResolver resolver, long accountId) {
            String retVal = null;

            Cursor c = resolver.query(CONTENT_URI,
                    CONTACT_OTR_ETAG_PROJECTION,
                    ACCOUNT + "=" + accountId,
                    null /* selection args */,
                    null /* sort order */);

            try {
                if (c.moveToFirst()) {
                    retVal = c.getString(COLUMN_OTR_ETAG);
                }
            } finally {
                c.close();
            }

            return retVal;
        }

        private static final String[] CONTACT_ETAG_PROJECTION = new String[] {
                Im.ContactsEtag.ETAG    // 0
        };

        private static int COLUMN_ETAG = 0;

        private static final String[] CONTACT_OTR_ETAG_PROJECTION = new String[] {
                Im.ContactsEtag.OTR_ETAG    // 0
        };

        private static int COLUMN_OTR_ETAG = 0;

        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI =
            Uri.parse("content://com.google.android.providers.talk/contactsEtag");

        /**
         * The MIME type of {@link #CONTENT_URI} providing a directory of
         * people.
         */
        public static final String CONTENT_TYPE =
                "vnd.android.cursor.dir/gtalk-contactsEtag";

        /**
         * The MIME type of a {@link #CONTENT_URI} subdirectory of a single
         * person.
         */
        public static final String CONTENT_ITEM_TYPE =
                "vnd.android.cursor.item/gtalk-contactsEtag";
    }

    /**
     * Message type definition
     */
    public interface MessageType {
        /* sent message */
        int OUTGOING = 0;
        /* received message */
        int INCOMING = 1;
        /* presence became available */
        int PRESENCE_AVAILABLE = 2;
        /* presence became away */
        int PRESENCE_AWAY = 3;
        /* presence became DND (busy) */
        int PRESENCE_DND = 4;
        /* presence became unavailable */
        int PRESENCE_UNAVAILABLE = 5;
        /* the message is converted to a group chat */
        int CONVERT_TO_GROUPCHAT = 6;
        /* generic status */
        int STATUS = 7;
        /* the message cannot be sent now, but will be sent later */
        int POSTPONED = 8;
        /* off The Record status is turned off */
        int OTR_IS_TURNED_OFF = 9;
        /* off the record status is turned on */
        int OTR_IS_TURNED_ON = 10;
        /* off the record status turned on by user */
        int OTR_TURNED_ON_BY_USER = 11;
        /* off the record status turned on by buddy */
        int OTR_TURNED_ON_BY_BUDDY = 12;
    }

    /**
     * The common columns for messages table
     */
    public interface MessageColumns {
        /**
         * The thread_id column stores the contact id of the contact the message belongs to.
         * For groupchat messages, the thread_id stores the group id, which is the contact id
         * of the temporary group contact created for the groupchat. So there should be no
         * collision between groupchat message thread id and regular message thread id. 
         */
        String THREAD_ID = "thread_id";

        /**
         * The nickname. This is used for groupchat messages to indicate the participant's
         * nickname. For non groupchat messages, this field should be left empty.
         */
        String NICKNAME = "nickname";

        /**
         * The body
         * <P>Type: TEXT</P>
         */
        String BODY = "body";

        /**
         * The date this message is sent or received. This represents the display date for
         * the message.
         * <P>Type: INTEGER</P>
         */
        String DATE = "date";

        /**
         * The real date for this message. While 'date' can be modified by the client
         * to account for server time skew, the real_date is the original timestamp set
         * by the server for incoming messages.
         * <P>Type: INTEGER</P>
         */
        String REAL_DATE = "real_date";

        /**
         * Message Type, see {@link MessageType}
         * <P>Type: INTEGER</P>
         */
        String TYPE = "type";

        /**
         * Error Code: 0 means no error.
         * <P>Type: INTEGER </P>
         */
        String ERROR_CODE = "err_code";

        /**
         * Error Message
         * <P>Type: TEXT</P>
         */
        String ERROR_MESSAGE = "err_msg";

        /**
         * Packet ID, auto assigned by the GTalkService for outgoing messages or the
         * GTalk server for incoming messages. The packet id field is optional for messages,
         * so it could be null.
         * <P>Type: STRING</P>
         */
        String PACKET_ID = "packet_id";

        /**
         * Is groupchat message or not
         * <P>Type: INTEGER</P>
         */
        String IS_GROUP_CHAT = "is_muc";

        /**
         * A hint that the UI should show the sent time of this message
         * <P>Type: INTEGER</P>
         */
        String DISPLAY_SENT_TIME = "show_ts";
    }

    /**
     * This table contains messages.
     */
    public static final class Messages implements BaseColumns, MessageColumns {
        /**
         * no public constructor since this is a utility class
         */
        private Messages() {}

        /**
         * Gets the Uri to query messages by thread id.
         *
         * @param threadId the thread id of the message.
         * @return the Uri
         */
        public static final Uri getContentUriByThreadId(long threadId) {
            Uri.Builder builder = CONTENT_URI_MESSAGES_BY_THREAD_ID.buildUpon();
            ContentUris.appendId(builder, threadId);
            return builder.build();
        }

        /**
         * @deprecated
         *
         * Gets the Uri to query messages by account and contact.
         *
         * @param accountId the account id of the contact.
         * @param username the user name of the contact.
         * @return the Uri
         */
        public static final Uri getContentUriByContact(long accountId, String username) {
            Uri.Builder builder = CONTENT_URI_MESSAGES_BY_ACCOUNT_AND_CONTACT.buildUpon();
            ContentUris.appendId(builder, accountId);
            builder.appendPath(username);
            return builder.build();
        }

        /**
         * Gets the Uri to query messages by provider.
         *
         * @param providerId the service provider id.
         * @return the Uri
         */
        public static final Uri getContentUriByProvider(long providerId) {
            Uri.Builder builder = CONTENT_URI_MESSAGES_BY_PROVIDER.buildUpon();
            ContentUris.appendId(builder, providerId);
            return builder.build();
        }

        /**
         * Gets the Uri to query off the record messages by account.
         *
         * @param accountId the account id.
         * @return the Uri
         */
        public static final Uri getContentUriByAccount(long accountId) {
            Uri.Builder builder = CONTENT_URI_BY_ACCOUNT.buildUpon();
            ContentUris.appendId(builder, accountId);
            return builder.build();
        }

        /**
         * Gets the Uri to query off the record messages by thread id.
         *
         * @param threadId the thread id of the message.
         * @return the Uri
         */
        public static final Uri getOtrMessagesContentUriByThreadId(long threadId) {
            Uri.Builder builder = OTR_MESSAGES_CONTENT_URI_BY_THREAD_ID.buildUpon();
            ContentUris.appendId(builder, threadId);
            return builder.build();
        }

        /**
         * @deprecated
         *
         * Gets the Uri to query off the record messages by account and contact.
         *
         * @param accountId the account id of the contact.
         * @param username the user name of the contact.
         * @return the Uri
         */
        public static final Uri getOtrMessagesContentUriByContact(long accountId, String username) {
            Uri.Builder builder = OTR_MESSAGES_CONTENT_URI_BY_ACCOUNT_AND_CONTACT.buildUpon();
            ContentUris.appendId(builder, accountId);
            builder.appendPath(username);
            return builder.build();
        }

        /**
         * Gets the Uri to query off the record messages by provider.
         *
         * @param providerId the service provider id.
         * @return the Uri
         */
        public static final Uri getOtrMessagesContentUriByProvider(long providerId) {
            Uri.Builder builder = OTR_MESSAGES_CONTENT_URI_BY_PROVIDER.buildUpon();
            ContentUris.appendId(builder, providerId);
            return builder.build();
        }

        /**
         * Gets the Uri to query off the record messages by account.
         *
         * @param accountId the account id.
         * @return the Uri
         */
        public static final Uri getOtrMessagesContentUriByAccount(long accountId) {
            Uri.Builder builder = OTR_MESSAGES_CONTENT_URI_BY_ACCOUNT.buildUpon();
            ContentUris.appendId(builder, accountId);
            return builder.build();
        }

        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI =
                Uri.parse("content://com.google.android.providers.talk/messages");

        /**
         * The content:// style URL for messages by thread id
         */
        public static final Uri CONTENT_URI_MESSAGES_BY_THREAD_ID =
                Uri.parse("content://com.google.android.providers.talk/messagesByThreadId");

        /**
         * The content:// style URL for messages by account and contact
         */
        public static final Uri CONTENT_URI_MESSAGES_BY_ACCOUNT_AND_CONTACT =
                Uri.parse("content://com.google.android.providers.talk/messagesByAcctAndContact");

        /**
         * The content:// style URL for messages by provider
         */
        public static final Uri CONTENT_URI_MESSAGES_BY_PROVIDER =
                Uri.parse("content://com.google.android.providers.talk/messagesByProvider");

        /**
         * The content:// style URL for messages by account
         */
        public static final Uri CONTENT_URI_BY_ACCOUNT =
                Uri.parse("content://com.google.android.providers.talk/messagesByAccount");

        /**
         * The content:// style url for off the record messages
         */
        public static final Uri OTR_MESSAGES_CONTENT_URI =
                Uri.parse("content://com.google.android.providers.talk/otrMessages");

        /**
         * The content:// style url for off the record messages by thread id
         */
        public static final Uri OTR_MESSAGES_CONTENT_URI_BY_THREAD_ID =
                Uri.parse("content://com.google.android.providers.talk/otrMessagesByThreadId");

        /**
         * The content:// style url for off the record messages by account and contact
         */
        public static final Uri OTR_MESSAGES_CONTENT_URI_BY_ACCOUNT_AND_CONTACT =
                Uri.parse("content://com.google.android.providers.talk/otrMessagesByAcctAndContact");

        /**
         * The content:// style URL for off the record messages by provider
         */
        public static final Uri OTR_MESSAGES_CONTENT_URI_BY_PROVIDER =
                Uri.parse("content://com.google.android.providers.talk/otrMessagesByProvider");

        /**
         * The content:// style URL for off the record messages by account
         */
        public static final Uri OTR_MESSAGES_CONTENT_URI_BY_ACCOUNT =
                Uri.parse("content://com.google.android.providers.talk/otrMessagesByAccount");

        /**
         * The MIME type of {@link #CONTENT_URI} providing a directory of
         * people.
         */
        public static final String CONTENT_TYPE =
                "vnd.android.cursor.dir/gtalk-messages";

        /**
         * The MIME type of a {@link #CONTENT_URI} subdirectory of a single
         * person.
         */
        public static final String CONTENT_ITEM_TYPE =
                "vnd.android.cursor.item/gtalk-messages";

        /**
         * The default sort order for this table
         */
        public static final String DEFAULT_SORT_ORDER = "date ASC";

        /**
         * The "contact" column. This is not a real column in the messages table, but a
         * temoprary column created when querying for messages (joined with the contacts table)
         */
        public static final String CONTACT = "contact";
    }

    /**
     * Columns for the GroupMember table.
     */
    public interface GroupMemberColumns {
        /**
         * The id of the group this member belongs to.
         * <p>Type: INTEGER</p>
         */
        String GROUP = "groupId";

        /**
         * The full name of this member.
         * <p>Type: TEXT</p>
         */
        String USERNAME = "username";

        /**
         * The nick name of this member.
         * <p>Type: TEXT</p>
         */
        String NICKNAME = "nickname";
    }

    public final static class GroupMembers implements GroupMemberColumns {
        private GroupMembers(){}

        public static final Uri CONTENT_URI =
            Uri.parse("content://com.google.android.providers.talk/groupMembers");

        /**
         * The MIME type of {@link #CONTENT_URI} providing a directory of
         * group members.
         */
        public static final String CONTENT_TYPE =
            "vnd.android.cursor.dir/gtalk-groupMembers";

        /**
         * The MIME type of a {@link #CONTENT_URI} subdirectory of a single
         * group member.
         */
        public static final String CONTENT_ITEM_TYPE =
                "vnd.android.cursor.item/gtalk-groupMembers";
    }

    /**
     * Columns from the Invitation table.
     */
    public interface InvitationColumns {
        /**
         * The provider id.
         * <p>Type: INTEGER</p>
         */
        String PROVIDER = "providerId";

        /**
         * The account id.
         * <p>Type: INTEGER</p>
         */
        String ACCOUNT = "accountId";

        /**
         * The invitation id.
         * <p>Type: TEXT</p>
         */
        String INVITE_ID = "inviteId";

        /**
         * The name of the sender of the invitation.
         * <p>Type: TEXT</p>
         */
        String SENDER = "sender";

        /**
         * The name of the group which the sender invite you to join.
         * <p>Type: TEXT</p>
         */
        String GROUP_NAME = "groupName";

        /**
         * A note
         * <p>Type: TEXT</p>
         */
        String NOTE = "note";

        /**
         * The current status of the invitation.
         * <p>Type: TEXT</p>
         */
        String STATUS = "status";

        int STATUS_PENDING = 0;
        int STATUS_ACCEPTED = 1;
        int STATUS_REJECTED = 2;
    }

    /**
     * This table contains the invitations received from others.
     */
    public final static class Invitation implements InvitationColumns,
            BaseColumns {
        private Invitation() {
        }

        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI =
            Uri.parse("content://com.google.android.providers.talk/invitations");

        /**
         * The MIME type of {@link #CONTENT_URI} providing a directory of
         * invitations.
         */
        public static final String CONTENT_TYPE =
            "vnd.android.cursor.dir/gtalk-invitations";

        /**
         * The MIME type of a {@link #CONTENT_URI} subdirectory of a single
         * invitation.
         */
        public static final String CONTENT_ITEM_TYPE =
                "vnd.android.cursor.item/gtalk-invitations";
    }

    /**
     * Columns from the Avatars table
     */
    public interface AvatarsColumns {
        /**
         * The contact this avatar belongs to
         * <P>Type: TEXT</P>
         */
        String CONTACT = "contact";

        String PROVIDER = "provider_id";

        String ACCOUNT = "account_id";

        /**
         * The hash of the image data
         * <P>Type: TEXT</P>
         */
        String HASH = "hash";

        /**
         * raw image data
         * <P>Type: BLOB</P>
         */
        String DATA = "data";
    }

    /**
     * This table contains avatars.
     */
    public static final class Avatars implements BaseColumns, AvatarsColumns {
        /**
         * no public constructor since this is a utility class
         */
        private Avatars() {}

        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI =
            Uri.parse("content://com.google.android.providers.talk/avatars");

        /**
         * The content:// style URL for avatars by provider, account and contact
         */
        public static final Uri CONTENT_URI_AVATARS_BY =
                Uri.parse("content://com.google.android.providers.talk/avatarsBy");

        /**
         * The MIME type of {@link #CONTENT_URI} providing the avatars
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/gtalk-avatars";

        /**
         * The MIME type of a {@link #CONTENT_URI}
         */
        public static final String CONTENT_ITEM_TYPE =
                "vnd.android.cursor.item/gtalk-avatars";

        /**
         * The default sort order for this table
         */
        public static final String DEFAULT_SORT_ORDER = "contact ASC";

    }

    /**
     * Common presence columns shared between the IM and contacts presence tables
     */
    public interface CommonPresenceColumns {
        /**
         * The priority, an integer, used by XMPP presence
         * <P>Type: INTEGER</P>
         */
        String PRIORITY = "priority";

        /**
         * The server defined status.
         * <P>Type: INTEGER (one of the values below)</P>
         */
        String PRESENCE_STATUS = "mode";

        /**
         * Presence Status definition
         */
        int OFFLINE = 0;
        int INVISIBLE = 1;
        int AWAY = 2;
        int IDLE = 3;
        int DO_NOT_DISTURB = 4;
        int AVAILABLE = 5;

        /**
         * The user defined status line.
         * <P>Type: TEXT</P>
         */
        String PRESENCE_CUSTOM_STATUS = "status";
    }

    /**
     * Columns from the Presence table.
     */
    public interface PresenceColumns extends CommonPresenceColumns {
        /**
         * The contact id
         * <P>Type: INTEGER</P>
         */
        String CONTACT_ID = "contact_id";

        /**
         * The contact's JID resource, only relevant for XMPP contact
         * <P>Type: TEXT</P>
         */
        String JID_RESOURCE = "jid_resource";

        /**
         * The contact's client type
         */
        String CLIENT_TYPE = "client_type";

        /**
         * client type definitions
         */
        int CLIENT_TYPE_DEFAULT = 0;
        int CLIENT_TYPE_MOBILE = 1;
        int CLIENT_TYPE_ANDROID = 2;
    }

    /**
     * Contains presence infomation for contacts.
     */
    public static final class Presence implements BaseColumns, PresenceColumns {
        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI =
            Uri.parse("content://com.google.android.providers.talk/presence");

        /**
         * The content URL for Talk presences for an account
         */
        public static final Uri CONTENT_URI_BY_ACCOUNT =
            Uri.parse("content://com.google.android.providers.talk/presence/account");

        /**
         * The content:// style URL for operations on bulk contacts
         */
        public static final Uri BULK_CONTENT_URI =
            Uri.parse("content://com.google.android.providers.talk/bulk_presence");

        /**
         * The content:// style URL for seeding presences for a given account id.
         */
        public static final Uri SEED_PRESENCE_BY_ACCOUNT_CONTENT_URI =
                Uri.parse("content://com.google.android.providers.talk/seed_presence/account");

        /**
         * The MIME type of a {@link #CONTENT_URI} providing a directory of presence
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/gtalk-presence";

        /**
         * The default sort order for this table
         */
        public static final String DEFAULT_SORT_ORDER = "mode DESC";
    }

    /**
     * Columns from the Chats table.
     */
    public interface ChatsColumns {
        /**
         * The contact ID this chat belongs to. The value is a long.
         * <P>Type: INT</P>
         */
        String CONTACT_ID = "contact_id";

        /**
         * The GTalk JID resource. The value is a string.
         * <P>Type: TEXT</P>
         */
        String JID_RESOURCE = "jid_resource";

        /**
         * Whether this is a groupchat or not.
         * <P>Type: INT</P>
         */
        String GROUP_CHAT = "groupchat";

        /**
         * The last unread message. This both indicates that there is an
         * unread message, and what the message is.
         * <P>Type: TEXT</P>
         */
        String LAST_UNREAD_MESSAGE = "last_unread_message";

        /**
         * The last message timestamp
         * <P>Type: INT</P>
         */
        String LAST_MESSAGE_DATE = "last_message_date";

        /**
         * A message that is being composed.  This indicates that there was a
         * message being composed when the chat screen was shutdown, and what the
         * message is.
         * <P>Type: TEXT</P>
         */
        String UNSENT_COMPOSED_MESSAGE = "unsent_composed_message";

        /**
         * A value from 0-9 indicating which quick-switch chat screen slot this
         * chat is occupying.  If none (for instance, this is the 12th active chat)
         * then the value is -1.
         * <P>Type: INT</P>
         */
        String SHORTCUT = "shortcut";
    }

    /**
     * Contains ongoing chat sessions.
     */
    public static final class Chats implements BaseColumns, ChatsColumns {
        /**
         * no public constructor since this is a utility class
         */
        private Chats() {}

        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI =
            Uri.parse("content://com.google.android.providers.talk/chats");

        /**
         * The content URL for all chats that belong to the account
         */
        public static final Uri CONTENT_URI_BY_ACCOUNT =
            Uri.parse("content://com.google.android.providers.talk/chats/account");

        /**
         * The MIME type of {@link #CONTENT_URI} providing a directory of chats.
         */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/gtalk-chats";

        /**
         * The MIME type of a {@link #CONTENT_URI} subdirectory of a single chat.
         */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/gtalk-chats";

        /**
         * The default sort order for this table
         */
        public static final String DEFAULT_SORT_ORDER = "last_message_date ASC";
    }

    /**
     * Columns from session cookies table. Used for IMPS.
     */
    public static interface SessionCookiesColumns {
        String NAME = "name";
        String VALUE = "value";
        String PROVIDER = "provider";
        String ACCOUNT = "account";
    }

    /**
     * Contains IMPS session cookies.
     */
    public static class SessionCookies implements SessionCookiesColumns, BaseColumns {
        private SessionCookies() {
        }

        /**
         * The content:// style URI for this table
         */
        public static final Uri CONTENT_URI =
            Uri.parse("content://com.google.android.providers.talk/sessionCookies");

        /**
         * The content:// style URL for session cookies by provider and account
         */
        public static final Uri CONTENT_URI_SESSION_COOKIES_BY =
            Uri.parse("content://com.google.android.providers.talk/sessionCookiesBy");

        /**
         * The MIME type of {@link #CONTENT_URI} providing a directory of
         * people.
         */
        public static final String CONTENT_TYPE = "vnd.android-dir/gtalk-sessionCookies";
    }

    /**
     * Columns from ProviderSettings table
     */
    public static interface ProviderSettingsColumns {
         /**
          * The id in database of the related provider
          *
          * <P>Type: INT</P>
          */
        String PROVIDER = "provider";

        /**
         * The name of the setting
         * <P>Type: TEXT</P>
         */
        String NAME = "name";

        /**
         * The value of the setting
         * <P>Type: TEXT</P>
         */
        String VALUE = "value";
    }

    public static class ProviderSettings implements ProviderSettingsColumns {
        private ProviderSettings() {
        }

        /**
         * The content:// style URI for this table
         */
        public static final Uri CONTENT_URI =
                Uri.parse("content://com.google.android.providers.talk/providerSettings");

        /**
         * The MIME type of {@link #CONTENT_URI} providing provider settings
         */
        public static final String CONTENT_TYPE = "vnd.android-dir/gtalk-providerSettings";

        /**
         * A boolean value to indicate whether this provider should show the offline contacts
         */
        public static final String SHOW_OFFLINE_CONTACTS = "show_offline_contacts";

        /** controls whether or not the GTalk service automatically connect to server. */
        public static final String SETTING_AUTOMATICALLY_CONNECT_GTALK = "gtalk_auto_connect";

        /** controls whether or not the GTalk service will be automatically started after boot */
        public static final String SETTING_AUTOMATICALLY_START_SERVICE = "auto_start_service";

        /** controls whether or not the offline contacts will be hided */
        public static final String SETTING_HIDE_OFFLINE_CONTACTS = "hide_offline_contacts";

        /** controls whether or not enable the GTalk notification */
        public static final String SETTING_ENABLE_NOTIFICATION = "enable_notification";

        /** specifies whether or not to vibrate */
        public static final String SETTING_VIBRATE = "vibrate";

        /** specifies the Uri string of the ringtone */
        public static final String SETTING_RINGTONE = "ringtone";

        /** specifies the Uri of the default ringtone */
        public static final String SETTING_RINGTONE_DEFAULT =
                "content://settings/system/notification_sound";

        /** specifies whether or not to show mobile indicator to friends */
        public static final String SETTING_SHOW_MOBILE_INDICATOR = "mobile_indicator";

        /** specifies whether or not to show as away when device is idle */
        public static final String SETTING_SHOW_AWAY_ON_IDLE = "show_away_on_idle";

        /** specifies whether or not to upload heartbeat stat upon login */
        public static final String SETTING_UPLOAD_HEARTBEAT_STAT = "upload_heartbeat_stat";

        /** specifies the last heartbeat interval received from the server */
        public static final String SETTING_HEARTBEAT_INTERVAL = "heartbeat_interval";

        /** specifiy the JID resource used for Google Talk connection */
        public static final String SETTING_JID_RESOURCE = "jid_resource";

        /**
         * Used for reliable message queue (RMQ). This is for storing the last rmq id received
         * from the GTalk server
         */
        public static final String LAST_RMQ_RECEIVED = "last_rmq_rec";

        /**
         * Query the settings of the provider specified by id
         *
         * @param cr
         *            the relative content resolver
         * @param providerId
         *            the specified id of provider
         * @return a HashMap which contains all the settings for the specified
         *         provider
         */
        public static HashMap<String, String> queryProviderSettings(ContentResolver cr,
                long providerId) {
            HashMap<String, String> settings = new HashMap<String, String>();

            String[] projection = { NAME, VALUE };
            Cursor c = cr.query(ContentUris.withAppendedId(CONTENT_URI, providerId), projection, null, null, null);
            if (c == null) {
                return null;
            }

            while(c.moveToNext()) {
                settings.put(c.getString(0), c.getString(1));
            }

            c.close();

            return settings;
        }

        /**
         * Get the string value of setting which is specified by provider id and the setting name.
         *
         * @param cr The ContentResolver to use to access the settings table.
         * @param providerId The id of the provider.
         * @param settingName The name of the setting.
         * @return The value of the setting if the setting exist, otherwise return null.
         */
        public static String getStringValue(ContentResolver cr, long providerId, String settingName) {
            String ret = null;
            Cursor c = getSettingValue(cr, providerId, settingName);
            if (c != null) {
                ret = c.getString(0);
                c.close();
            }

            return ret;
        }

        /**
         * Get the boolean value of setting which is specified by provider id and the setting name.
         *
         * @param cr The ContentResolver to use to access the settings table.
         * @param providerId The id of the provider.
         * @param settingName The name of the setting.
         * @return The value of the setting if the setting exist, otherwise return false.
         */
        public static boolean getBooleanValue(ContentResolver cr, long providerId, String settingName) {
            boolean ret = false;
            Cursor c = getSettingValue(cr, providerId, settingName);
            if (c != null) {
                ret = c.getInt(0) != 0;
                c.close();
            }
            return ret;
        }

        private static Cursor getSettingValue(ContentResolver cr, long providerId, String settingName) {
            Cursor c = cr.query(ContentUris.withAppendedId(CONTENT_URI, providerId), new String[]{VALUE}, NAME + "=?",
                    new String[]{settingName}, null);
            if (c != null) {
                if (!c.moveToFirst()) {
                    c.close();
                    return null;
                }
            }
            return c;
        }

        /**
         * Save a long value of setting in the table providerSetting.
         *
         * @param cr The ContentProvider used to access the providerSetting table.
         * @param providerId The id of the provider.
         * @param name The name of the setting.
         * @param value The value of the setting.
         */
        public static void putLongValue(ContentResolver cr, long providerId, String name,
                long value) {
            ContentValues v = new ContentValues(3);
            v.put(PROVIDER, providerId);
            v.put(NAME, name);
            v.put(VALUE, value);

            cr.insert(CONTENT_URI, v);
        }

        /**
         * Save a boolean value of setting in the table providerSetting.
         *
         * @param cr The ContentProvider used to access the providerSetting table.
         * @param providerId The id of the provider.
         * @param name The name of the setting.
         * @param value The value of the setting.
         */
        public static void putBooleanValue(ContentResolver cr, long providerId, String name,
                boolean value) {
            ContentValues v = new ContentValues(3);
            v.put(PROVIDER, providerId);
            v.put(NAME, name);
            v.put(VALUE, Boolean.toString(value));

            cr.insert(CONTENT_URI, v);
        }

        /**
         * Save a string value of setting in the table providerSetting.
         *
         * @param cr The ContentProvider used to access the providerSetting table.
         * @param providerId The id of the provider.
         * @param name The name of the setting.
         * @param value The value of the setting.
         */
        public static void putStringValue(ContentResolver cr, long providerId, String name,
                String value) {
            ContentValues v = new ContentValues(3);
            v.put(PROVIDER, providerId);
            v.put(NAME, name);
            v.put(VALUE, value);

            cr.insert(CONTENT_URI, v);
        }

        /**
         * A convenience method to set whether or not the GTalk service should be started
         * automatically.
         *
         * @param contentResolver The ContentResolver to use to access the settings table
         * @param autoConnect Whether the GTalk service should be started automatically.
         */
        public static void setAutomaticallyConnectGTalk(ContentResolver contentResolver,
                long providerId, boolean autoConnect) {
            putBooleanValue(contentResolver, providerId, SETTING_AUTOMATICALLY_CONNECT_GTALK,
                    autoConnect);
        }

        /**
         * A convenience method to set whether or not the offline contacts should be hided
         *
         * @param contentResolver The ContentResolver to use to access the setting table
         * @param hideOfflineContacts Whether the offline contacts should be hided
         */
        public static void setHideOfflineContacts(ContentResolver contentResolver,
                long providerId, boolean hideOfflineContacts) {
            putBooleanValue(contentResolver, providerId, SETTING_HIDE_OFFLINE_CONTACTS,
                    hideOfflineContacts);
        }

        /**
         * A convenience method to set whether or not enable the GTalk notification.
         *
         * @param contentResolver The ContentResolver to use to access the setting table.
         * @param enable Whether enable the GTalk notification
         */
        public static void setEnableNotification(ContentResolver contentResolver, long providerId,
                boolean enable) {
            putBooleanValue(contentResolver, providerId, SETTING_ENABLE_NOTIFICATION, enable);
        }

        /**
         * A convenience method to set whether or not to vibrate.
         *
         * @param contentResolver The ContentResolver to use to access the setting table.
         * @param vibrate Whether or not to vibrate
         */
        public static void setVibrate(ContentResolver contentResolver, long providerId,
                boolean vibrate) {
            putBooleanValue(contentResolver, providerId, SETTING_VIBRATE, vibrate);
        }

        /**
         * A convenience method to set the Uri String of the ringtone.
         *
         * @param contentResolver The ContentResolver to use to access the setting table.
         * @param ringtoneUri The Uri String of the ringtone to be set.
         */
        public static void setRingtoneURI(ContentResolver contentResolver, long providerId,
                String ringtoneUri) {
            putStringValue(contentResolver, providerId, SETTING_RINGTONE, ringtoneUri);
        }

        /**
         * A convenience method to set whether or not to show mobile indicator.
         *
         * @param contentResolver The ContentResolver to use to access the setting table.
         * @param showMobileIndicator Whether or not to show mobile indicator.
         */
        public static void setShowMobileIndicator(ContentResolver contentResolver, long providerId,
                boolean showMobileIndicator) {
            putBooleanValue(contentResolver, providerId, SETTING_SHOW_MOBILE_INDICATOR,
                    showMobileIndicator);
        }

        /**
         * A convenience method to set whether or not to show as away when device is idle.
         *
         * @param contentResolver The ContentResolver to use to access the setting table.
         * @param showAway Whether or not to show as away when device is idle.
         */
        public static void setShowAwayOnIdle(ContentResolver contentResolver,
                long providerId, boolean showAway) {
            putBooleanValue(contentResolver, providerId, SETTING_SHOW_AWAY_ON_IDLE, showAway);
        }

        /**
         * A convenience method to set whether or not to upload heartbeat stat.
         *
         * @param contentResolver The ContentResolver to use to access the setting table.
         * @param uploadStat Whether or not to upload heartbeat stat.
         */
        public static void setUploadHeartbeatStat(ContentResolver contentResolver,
                long providerId, boolean uploadStat) {
            putBooleanValue(contentResolver, providerId, SETTING_UPLOAD_HEARTBEAT_STAT, uploadStat);
        }

        /**
         * A convenience method to set the heartbeat interval last received from the server.
         *
         * @param contentResolver The ContentResolver to use to access the setting table.
         * @param interval The heartbeat interval last received from the server.
         */
        public static void setHeartbeatInterval(ContentResolver contentResolver,
                long providerId, long interval) {
            putLongValue(contentResolver, providerId, SETTING_HEARTBEAT_INTERVAL, interval);
        }

        /**
         * A convenience method to set the jid resource.
         */
        public static void setJidResource(ContentResolver contentResolver,
                                          long providerId, String jidResource) {
            putStringValue(contentResolver, providerId, SETTING_JID_RESOURCE, jidResource);
        }

        public static class QueryMap extends ContentQueryMap {
            private ContentResolver mContentResolver;
            private long mProviderId;

            public QueryMap(ContentResolver contentResolver, long providerId, boolean keepUpdated,
                    Handler handlerForUpdateNotifications) {
                super(contentResolver.query(CONTENT_URI,
                            new String[] {NAME,VALUE},
                            PROVIDER + "=" + providerId,
                            null, // no selection args
                            null), // no sort order
                        NAME, keepUpdated, handlerForUpdateNotifications);
                mContentResolver = contentResolver;
                mProviderId = providerId;
            }

            /**
             * Set if the GTalk service should automatically connect to server.
             *
             * @param autoConnect if the GTalk service should auto connect to server.
             */
            public void setAutomaticallyConnectToGTalkServer(boolean autoConnect) {
                ProviderSettings.setAutomaticallyConnectGTalk(mContentResolver, mProviderId,
                        autoConnect);
            }

            /**
             * Check if the GTalk service should automatically connect to server.
             * @return if the GTalk service should automatically connect to server.
             */
            public boolean getAutomaticallyConnectToGTalkServer() {
                return getBoolean(SETTING_AUTOMATICALLY_CONNECT_GTALK,
                        true /* default to automatically sign in */);
            }

            /**
             * Set whether or not the offline contacts should be hided.
             *
             * @param hideOfflineContacts Whether or not the offline contacts should be hided.
             */
            public void setHideOfflineContacts(boolean hideOfflineContacts) {
                ProviderSettings.setHideOfflineContacts(mContentResolver, mProviderId,
                        hideOfflineContacts);
            }

            /**
             * Check if the offline contacts should be hided.
             *
             * @return Whether or not the offline contacts should be hided.
             */
            public boolean getHideOfflineContacts() {
                return getBoolean(SETTING_HIDE_OFFLINE_CONTACTS,
                        false/* by default not hide the offline contacts*/);
            }

            /**
             * Set whether or not enable the GTalk notification.
             *
             * @param enable Whether or not enable the GTalk notification.
             */
            public void setEnableNotification(boolean enable) {
                ProviderSettings.setEnableNotification(mContentResolver, mProviderId, enable);
            }

            /**
             * Check if the GTalk notification is enabled.
             *
             * @return Whether or not enable the GTalk notification.
             */
            public boolean getEnableNotification() {
                return getBoolean(SETTING_ENABLE_NOTIFICATION,
                        true/* by default enable the notification */);
            }

            /**
             * Set whether or not to vibrate on GTalk notification.
             *
             * @param vibrate Whether or not to vibrate.
             */
            public void setVibrate(boolean vibrate) {
                ProviderSettings.setVibrate(mContentResolver, mProviderId, vibrate);
            }

            /**
             * Gets whether or not to vibrate on GTalk notification.
             *
             * @return Whether or not to vibrate.
             */
            public boolean getVibrate() {
                return getBoolean(SETTING_VIBRATE, false /* by default disable vibrate */);
            }

            /**
             * Set the Uri for the ringtone.
             *
             * @param ringtoneUri The Uri of the ringtone to be set.
             */
            public void setRingtoneURI(String ringtoneUri) {
                ProviderSettings.setRingtoneURI(mContentResolver, mProviderId, ringtoneUri);
            }

            /**
             * Get the Uri String of the current ringtone.
             *
             * @return The Uri String of the current ringtone.
             */
            public String getRingtoneURI() {
                return getString(SETTING_RINGTONE, SETTING_RINGTONE_DEFAULT);
            }

            /**
             * Set whether or not to show mobile indicator to friends.
             *
             * @param showMobile whether or not to show mobile indicator.
             */
            public void setShowMobileIndicator(boolean showMobile) {
                ProviderSettings.setShowMobileIndicator(mContentResolver, mProviderId, showMobile);
            }

            /**
             * Gets whether or not to show mobile indicator.
             *
             * @return Whether or not to show mobile indicator.
             */
            public boolean getShowMobileIndicator() {
                return getBoolean(SETTING_SHOW_MOBILE_INDICATOR,
                        true /* by default show mobile indicator */);
            }

            /**
             * Set whether or not to show as away when device is idle.
             *
             * @param showAway whether or not to show as away when device is idle.
             */
            public void setShowAwayOnIdle(boolean showAway) {
                ProviderSettings.setShowAwayOnIdle(mContentResolver, mProviderId, showAway);
            }

            /**
             * Get whether or not to show as away when device is idle.
             *
             * @return Whether or not to show as away when device is idle.
             */
            public boolean getShowAwayOnIdle() {
                return getBoolean(SETTING_SHOW_AWAY_ON_IDLE,
                        true /* by default show as away on idle*/);
            }

            /**
             * Set whether or not to upload heartbeat stat.
             *
             * @param uploadStat whether or not to upload heartbeat stat.
             */
            public void setUploadHeartbeatStat(boolean uploadStat) {
                ProviderSettings.setUploadHeartbeatStat(mContentResolver, mProviderId, uploadStat);
            }

            /**
             * Get whether or not to upload heartbeat stat.
             *
             * @return Whether or not to upload heartbeat stat.
             */
            public boolean getUploadHeartbeatStat() {
                return getBoolean(SETTING_UPLOAD_HEARTBEAT_STAT,
                        false /* by default do not upload */);
            }

            /**
             * Set the last received heartbeat interval from the server.
             *
             * @param interval the last received heartbeat interval from the server.
             */
            public void setHeartbeatInterval(long interval) {
                ProviderSettings.setHeartbeatInterval(mContentResolver, mProviderId, interval);
            }

            /**
             * Get the last received heartbeat interval from the server.
             *
             * @return the last received heartbeat interval from the server.
             */
            public long getHeartbeatInterval() {
                return getLong(SETTING_HEARTBEAT_INTERVAL, 0L /* an invalid default interval */);
            }

            /**
             * Set the JID resource.
             *
             * @param jidResource the jid resource to be stored.
             */
            public void setJidResource(String jidResource) {
                ProviderSettings.setJidResource(mContentResolver, mProviderId, jidResource);
            }
            /**
             * Get the JID resource used for the Google Talk connection
             *
             * @return the JID resource stored.
             */
            public String getJidResource() {
                return getString(SETTING_JID_RESOURCE, null);
            }

            /**
             * Convenience function for retrieving a single settings value
             * as a boolean.
             *
             * @param name The name of the setting to retrieve.
             * @param def Value to return if the setting is not defined.
             * @return The setting's current value, or 'def' if it is not defined.
             */
            private boolean getBoolean(String name, boolean def) {
                ContentValues values = getValues(name);
                return values != null ? values.getAsBoolean(VALUE) : def;
            }

            /**
             * Convenience function for retrieving a single settings value
             * as a String.
             *
             * @param name The name of the setting to retrieve.
             * @param def The value to return if the setting is not defined.
             * @return The setting's current value or 'def' if it is not defined.
             */
            private String getString(String name, String def) {
                ContentValues values = getValues(name);
                return values != null ? values.getAsString(VALUE) : def;
            }

            /**
             * Convenience function for retrieving a single settings value
             * as an Integer.
             *
             * @param name The name of the setting to retrieve.
             * @param def The value to return if the setting is not defined.
             * @return The setting's current value or 'def' if it is not defined.
             */
            private int getInteger(String name, int def) {
                ContentValues values = getValues(name);
                return values != null ? values.getAsInteger(VALUE) : def;
            }

            /**
             * Convenience function for retrieving a single settings value
             * as a Long.
             *
             * @param name The name of the setting to retrieve.
             * @param def The value to return if the setting is not defined.
             * @return The setting's current value or 'def' if it is not defined.
             */
            private long getLong(String name, long def) {
                ContentValues values = getValues(name);
                return values != null ? values.getAsLong(VALUE) : def;
            }
        }

    }


    /**
     * Columns for GTalk branding resource map cache table. This table caches the result of
     * loading the branding resources to speed up GTalk landing page start.
     */
    public interface BrandingResourceMapCacheColumns {
        /**
         * The provider ID
         * <P>Type: INTEGER</P>
         */
        String PROVIDER_ID = "provider_id";
        /**
         * The application resource ID
         * <P>Type: INTEGER</P>
         */
        String APP_RES_ID = "app_res_id";
        /**
         * The plugin resource ID
         * <P>Type: INTEGER</P>
         */
        String PLUGIN_RES_ID = "plugin_res_id";
    }

    /**
     * The table for caching the result of loading GTalk branding resources.
     */
    public static final class BrandingResourceMapCache
        implements BaseColumns, BrandingResourceMapCacheColumns {
        /**
         * The content:// style URL for this table.
         */
        public static final Uri CONTENT_URI =
            Uri.parse("content://com.google.android.providers.talk/brandingResMapCache");
    }



    /**
     * //TODO: move these to MCS specific provider.
     * The following are MCS stuff, and should really live in a separate provider specific to
     * MCS code.
     */

    /**
     * Columns from OutgoingRmq table
     */
    public interface OutgoingRmqColumns {
        String RMQ_ID = "rmq_id";
        String TIMESTAMP = "ts";
        String DATA = "data";
        String PROTOBUF_TAG = "type";
    }

    /**
     * //TODO: we should really move these to their own provider and database.
     * The table for storing outgoing rmq packets.
     */
    public static final class OutgoingRmq implements BaseColumns, OutgoingRmqColumns {
        private static String[] RMQ_ID_PROJECTION = new String[] {
                RMQ_ID,
        };

        /**
         * queryHighestRmqId
         *
         * @param resolver the content resolver
         * @return the highest rmq id assigned to the rmq packet, or 0 if there are no rmq packets
         *         in the OutgoingRmq table.
         */
        public static final long queryHighestRmqId(ContentResolver resolver) {
            Cursor cursor = resolver.query(Im.OutgoingRmq.CONTENT_URI_FOR_HIGHEST_RMQ_ID,
                    RMQ_ID_PROJECTION,
                    null, // selection
                    null, // selection args
                    null  // sort
                    );

            long retVal = 0;
            try {
                //if (DBG) log("initializeRmqid: cursor.count= " + cursor.count());

                if (cursor.moveToFirst()) {
                    retVal = cursor.getLong(cursor.getColumnIndexOrThrow(RMQ_ID));
                }
            } finally {
                cursor.close();
            }

            return retVal;
        }

        /**
         * The content:// style URL for this table.
         */
        public static final Uri CONTENT_URI =
            Uri.parse("content://com.google.android.providers.talk/outgoingRmqMessages");

        /**
         * The content:// style URL for the highest rmq id for the outgoing rmq messages
         */
        public static final Uri CONTENT_URI_FOR_HIGHEST_RMQ_ID =
                Uri.parse("content://com.google.android.providers.talk/outgoingHighestRmqId");

        /**
         * The default sort order for this table.
         */
        public static final String DEFAULT_SORT_ORDER = "rmq_id ASC";
    }

    /**
     * Columns for the LastRmqId table, which stores a single row for the last client rmq id
     * sent to the server.
     */
    public interface LastRmqIdColumns {
        String RMQ_ID = "rmq_id";
    }

    /**
     * //TODO: move these out into their own provider and database
     * The table for storing the last client rmq id sent to the server.
     */
    public static final class LastRmqId implements BaseColumns, LastRmqIdColumns {
        private static String[] PROJECTION = new String[] {
                RMQ_ID,
        };

        /**
         * queryLastRmqId
         *
         * queries the last rmq id saved in the LastRmqId table.
         *
         * @param resolver the content resolver.
         * @return the last rmq id stored in the LastRmqId table, or 0 if not found.
         */
        public static final long queryLastRmqId(ContentResolver resolver) {
            Cursor cursor = resolver.query(Im.LastRmqId.CONTENT_URI,
                    PROJECTION,
                    null, // selection
                    null, // selection args
                    null  // sort
                    );

            long retVal = 0;
            try {
                if (cursor.moveToFirst()) {
                    retVal = cursor.getLong(cursor.getColumnIndexOrThrow(RMQ_ID));
                }
            } finally {
                cursor.close();
            }

            return retVal;
        }

        /**
         * saveLastRmqId
         *
         * saves the rmqId to the lastRmqId table. This will override the existing row if any,
         * as we only keep one row of data in this table.
         *
         * @param resolver the content resolver.
         * @param rmqId the rmq id to be saved.
         */
        public static final void saveLastRmqId(ContentResolver resolver, long rmqId) {
            ContentValues values = new ContentValues();

            // always replace the first row.
            values.put(_ID, 1);
            values.put(RMQ_ID, rmqId);
            resolver.insert(CONTENT_URI, values);
        }

        /**
         * The content:// style URL for this table.
         */
        public static final Uri CONTENT_URI =
            Uri.parse("content://com.google.android.providers.talk/lastRmqId");
    }

    /**
     * Columns for the s2dRmqIds table, which stores the server-to-device message
     * persistent ids. These are used in the RMQ2 protocol, where in the login request, the
     * client selective acks these s2d ids to the server.
     */
    public interface ServerToDeviceRmqIdsColumn {
        String RMQ_ID = "rmq_id";
    }

    public static final class ServerToDeviceRmqIds implements BaseColumns,
            ServerToDeviceRmqIdsColumn {

        /**
         * The content:// style URL for this table.
         */
        public static final Uri CONTENT_URI =
            Uri.parse("content://com.google.android.providers.talk/s2dids");
    }

}
