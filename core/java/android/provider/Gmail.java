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

import com.google.android.collect.Lists;
import com.google.android.collect.Maps;
import com.google.android.collect.Sets;

import android.content.AsyncQueryHandler;
import android.content.ContentQueryMap;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.DataSetObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextUtils.SimpleStringSplitter;
import android.text.style.CharacterStyle;
import android.text.util.Regex;
import android.util.Config;
import android.util.Log;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Observable;
import java.util.Observer;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A thin wrapper over the content resolver for accessing the gmail provider.
 *
 * @hide
 */
public final class Gmail {
    public static final String GMAIL_AUTH_SERVICE = "mail";
    // These constants come from google3/java/com/google/caribou/backend/MailLabel.java.
    public static final String LABEL_SENT = "^f";
    public static final String LABEL_INBOX = "^i";
    public static final String LABEL_DRAFT = "^r";
    public static final String LABEL_UNREAD = "^u";
    public static final String LABEL_TRASH = "^k";
    public static final String LABEL_SPAM = "^s";
    public static final String LABEL_STARRED = "^t";
    public static final String LABEL_CHAT = "^b"; // 'b' for 'buzz'
    public static final String LABEL_VOICEMAIL = "^vm";
    public static final String LABEL_IGNORED = "^g";
    public static final String LABEL_ALL = "^all";
    // These constants (starting with "^^") are only used locally and are not understood by the
    // server.
    public static final String LABEL_VOICEMAIL_INBOX = "^^vmi";
    public static final String LABEL_CACHED = "^^cached";
    public static final String LABEL_OUTBOX = "^^out";

    public static final String AUTHORITY = "gmail-ls";
    private static final String TAG = "gmail-ls";
    private static final String AUTHORITY_PLUS_CONVERSATIONS =
            "content://" + AUTHORITY + "/conversations/";
    private static final String AUTHORITY_PLUS_LABELS =
            "content://" + AUTHORITY + "/labels/";
    private static final String AUTHORITY_PLUS_MESSAGES =
            "content://" + AUTHORITY + "/messages/";
    private static final String AUTHORITY_PLUS_SETTINGS =
            "content://" + AUTHORITY + "/settings/";

    public static final Uri BASE_URI = Uri.parse(
            "content://" + AUTHORITY);
    private static final Uri LABELS_URI =
            Uri.parse(AUTHORITY_PLUS_LABELS);
    private static final Uri CONVERSATIONS_URI =
            Uri.parse(AUTHORITY_PLUS_CONVERSATIONS);
    private static final Uri SETTINGS_URI =
            Uri.parse(AUTHORITY_PLUS_SETTINGS);

    /** Separates email addresses in strings in the database. */
    public static final String EMAIL_SEPARATOR = "\n";
    public static final Pattern EMAIL_SEPARATOR_PATTERN = Pattern.compile(EMAIL_SEPARATOR);

    /**
     * Space-separated lists have separators only between items.
     */
    private static final char SPACE_SEPARATOR = ' ';
    public static final Pattern SPACE_SEPARATOR_PATTERN = Pattern.compile(" ");

    /**
     * Comma-separated lists have separators between each item, before the first and after the last
     * item. The empty list is <tt>,</tt>.
     *
     * <p>This makes them easier to modify with SQL since it is not a special case to add or
     * remove the last item. Having a separator on each side of each value also makes it safe to use
     * SQL's REPLACE to remove an item from a string by using REPLACE(',value,', ',').
     *
     * <p>We could use the same separator for both lists but this makes it easier to remember which
     * kind of list one is dealing with.
     */
    private static final char COMMA_SEPARATOR = ',';
    public static final Pattern COMMA_SEPARATOR_PATTERN = Pattern.compile(",");

    /** Separates attachment info parts in strings in the database. */
    public static final String ATTACHMENT_INFO_SEPARATOR = "\n";
    public static final Pattern ATTACHMENT_INFO_SEPARATOR_PATTERN =
            Pattern.compile(ATTACHMENT_INFO_SEPARATOR);

    public static final Character SENDER_LIST_SEPARATOR = '\n';
    public static final String SENDER_LIST_TOKEN_ELIDED = "e";
    public static final String SENDER_LIST_TOKEN_NUM_MESSAGES = "n";
    public static final String SENDER_LIST_TOKEN_NUM_DRAFTS = "d";
    public static final String SENDER_LIST_TOKEN_LITERAL = "l";
    public static final String SENDER_LIST_TOKEN_SENDING = "s";
    public static final String SENDER_LIST_TOKEN_SEND_FAILED = "f";

    /** Used for finding status in a cursor's extras. */
    public static final String EXTRA_STATUS = "status";

    public static final String RESPOND_INPUT_COMMAND = "command";
    public static final String COMMAND_RETRY = "retry";
    public static final String COMMAND_ACTIVATE = "activate";
    public static final String COMMAND_SET_VISIBLE = "setVisible";
    public static final String SET_VISIBLE_PARAM_VISIBLE = "visible";
    public static final String RESPOND_OUTPUT_COMMAND_RESPONSE = "commandResponse";
    public static final String COMMAND_RESPONSE_OK =  "ok";
    public static final String COMMAND_RESPONSE_UNKNOWN =  "unknownCommand";

    public static final String INSERT_PARAM_ATTACHMENT_ORIGIN = "origin";
    public static final String INSERT_PARAM_ATTACHMENT_ORIGIN_EXTRAS = "originExtras";

    private static final Pattern NAME_ADDRESS_PATTERN = Pattern.compile("\"(.*)\"");
    private static final Pattern UNNAMED_ADDRESS_PATTERN = Pattern.compile("([^<]+)@");

    private static final Map<Integer, Integer> sPriorityToLength = Maps.newHashMap();
    public static final SimpleStringSplitter sSenderListSplitter = 
            new SimpleStringSplitter(SENDER_LIST_SEPARATOR);
    public static String[] sSenderFragments = new String[8];

    /**
     * Returns the name in an address string
     * @param addressString such as &quot;bobby&quot; &lt;bob@example.com&gt;
     * @return returns the quoted name in the addressString, otherwise the username from the email
     *   address
     */
    public static String getNameFromAddressString(String addressString) {
        Matcher namedAddressMatch = NAME_ADDRESS_PATTERN.matcher(addressString);
        if (namedAddressMatch.find()) {
            String name = namedAddressMatch.group(1);
            if (name.length() > 0) return name;
            addressString =
                    addressString.substring(namedAddressMatch.end(), addressString.length());
        }

        Matcher unnamedAddressMatch = UNNAMED_ADDRESS_PATTERN.matcher(addressString);
        if (unnamedAddressMatch.find()) {
            return unnamedAddressMatch.group(1);
        }

        return addressString;
    }

    /**
     * Returns the email address in an address string
     * @param addressString such as &quot;bobby&quot; &lt;bob@example.com&gt;
     * @return returns the email address, such as bob@example.com from the example above
     */
    public static String getEmailFromAddressString(String addressString) {
        String result = addressString;
        Matcher match = Regex.EMAIL_ADDRESS_PATTERN.matcher(addressString);
        if (match.find()) {
            result = addressString.substring(match.start(), match.end());
        }

        return result;
    }

    /**
     * Returns whether the label is user-defined (versus system-defined labels such as inbox, whose
     * names start with "^").
     */
    public static boolean isLabelUserDefined(String label) {
        // TODO: label should never be empty so we should be able to say [label.charAt(0) != '^'].
        // However, it's a release week and I'm too scared to make that change.
        return !label.startsWith("^");
    }

    private static final Set<String> USER_SETTABLE_BUILTIN_LABELS = Sets.newHashSet(
            Gmail.LABEL_INBOX,
            Gmail.LABEL_UNREAD,
            Gmail.LABEL_TRASH,
            Gmail.LABEL_SPAM,
            Gmail.LABEL_STARRED,
            Gmail.LABEL_IGNORED);

    /**
     * Returns whether the label is user-settable. For example, labels such as LABEL_DRAFT should
     * only be set internally.
     */
    public static boolean isLabelUserSettable(String label) {
        return USER_SETTABLE_BUILTIN_LABELS.contains(label) || isLabelUserDefined(label);
    }

    /**
     * Returns the set of labels using the raw labels from a previous getRawLabels()
     * as input.
     * @return a copy of the set of labels. To add or remove labels call
     * MessageCursor.addOrRemoveLabel on each message in the conversation.
     */
    public static Set<Long> getLabelIdsFromLabelIdsString(
            TextUtils.StringSplitter splitter) {
        Set<Long> labelIds = Sets.newHashSet();
        for (String labelIdString : splitter) {
            labelIds.add(Long.valueOf(labelIdString));
        }
        return labelIds;
    }

    /**
     * @deprecated remove when the activities stop using canonical names to identify labels
     */
    public static Set<String> getCanonicalNamesFromLabelIdsString(
            LabelMap labelMap, TextUtils.StringSplitter splitter) {
        Set<String> canonicalNames = Sets.newHashSet();
        for (long labelId : getLabelIdsFromLabelIdsString(splitter)) {
            final String canonicalName = labelMap.getCanonicalName(labelId);
            // We will sometimes see labels that the label map does not yet know about or that
            // do not have names yet.
            if (!TextUtils.isEmpty(canonicalName)) {
                canonicalNames.add(canonicalName);
            } else {
                Log.w(TAG, "getCanonicalNamesFromLabelIdsString skipping label id: " + labelId);
            }
        }
        return canonicalNames;
    }

    /**
     * @return a StringSplitter that is configured to split message label id strings
     */
    public static TextUtils.StringSplitter newMessageLabelIdsSplitter() {
        return new TextUtils.SimpleStringSplitter(SPACE_SEPARATOR);
    }

    /**
     * @return a StringSplitter that is configured to split conversation label id strings
     */
    public static TextUtils.StringSplitter newConversationLabelIdsSplitter() {
        return new CommaStringSplitter();
    }

    /**
     * A splitter for strings of the form described in the docs for COMMA_SEPARATOR.
     */
    private static class CommaStringSplitter extends TextUtils.SimpleStringSplitter {

        public CommaStringSplitter() {
            super(COMMA_SEPARATOR);
        }

        @Override
        public void setString(String string) {
            // The string should always be at least a single comma.
            super.setString(string.substring(1));
        }
    }

    /**
     * Creates a single string of the form that getLabelIdsFromLabelIdsString can split.
     */
    public static String getLabelIdsStringFromLabelIds(Set<Long> labelIds) {
        StringBuilder sb = new StringBuilder();
        sb.append(COMMA_SEPARATOR);
        for (Long labelId : labelIds) {
            sb.append(labelId);
            sb.append(COMMA_SEPARATOR);
        }
        return sb.toString();
    }

    public static final class ConversationColumns {
        public static final String ID = "_id";
        public static final String SUBJECT = "subject";
        public static final String SNIPPET = "snippet";
        public static final String FROM = "fromAddress";
        public static final String DATE = "date";
        public static final String PERSONAL_LEVEL = "personalLevel";
        /** A list of label names with a space after each one (including the last one). This makes
         * it easier remove individual labels from this list using SQL. */
        public static final String LABEL_IDS = "labelIds";
        public static final String NUM_MESSAGES = "numMessages";
        public static final String MAX_MESSAGE_ID = "maxMessageId";
        public static final String HAS_ATTACHMENTS = "hasAttachments";
        public static final String HAS_MESSAGES_WITH_ERRORS = "hasMessagesWithErrors";
        public static final String FORCE_ALL_UNREAD = "forceAllUnread";

        private ConversationColumns() {}
    }

    public static final class MessageColumns {

        public static final String ID = "_id";
        public static final String MESSAGE_ID = "messageId";
        public static final String CONVERSATION_ID = "conversation";
        public static final String SUBJECT = "subject";
        public static final String SNIPPET = "snippet";
        public static final String FROM = "fromAddress";
        public static final String TO = "toAddresses";
        public static final String CC = "ccAddresses";
        public static final String BCC = "bccAddresses";
        public static final String REPLY_TO = "replyToAddresses";
        public static final String DATE_SENT_MS = "dateSentMs";
        public static final String DATE_RECEIVED_MS = "dateReceivedMs";
        public static final String LIST_INFO = "listInfo";
        public static final String PERSONAL_LEVEL = "personalLevel";
        public static final String BODY = "body";
        public static final String EMBEDS_EXTERNAL_RESOURCES = "bodyEmbedsExternalResources";
        public static final String LABEL_IDS = "labelIds";
        public static final String JOINED_ATTACHMENT_INFOS = "joinedAttachmentInfos";
        public static final String ERROR = "error";
        // TODO: add a method for accessing this
        public static final String REF_MESSAGE_ID = "refMessageId";

        // Fake columns used only for saving or sending messages.
        public static final String FAKE_SAVE = "save";
        public static final String FAKE_REF_MESSAGE_ID = "refMessageId";

        private MessageColumns() {}
    }

    public static final class LabelColumns {
        public static final String CANONICAL_NAME = "canonicalName";
        public static final String NAME = "name";
        public static final String NUM_CONVERSATIONS = "numConversations";
        public static final String NUM_UNREAD_CONVERSATIONS =
                "numUnreadConversations";

        private LabelColumns() {}
    }

    public static final class SettingsColumns {
        public static final String LABELS_INCLUDED = "labelsIncluded";
        public static final String LABELS_PARTIAL = "labelsPartial";
        public static final String CONVERSATION_AGE_DAYS =
                "conversationAgeDays";
        public static final String MAX_ATTACHMENET_SIZE_MB =
                "maxAttachmentSize";
    }

    /**
     * These flags can be included as Selection Arguments when
     * querying the provider.
     */
    public static class SelectionArguments {
        private SelectionArguments() {
            // forbid instantiation
        }

        /**
         * Specifies that you do NOT wish the returned cursor to
         * become the Active Network Cursor.  If you do not include
         * this flag as a selectionArg, the new cursor will become the
         * Active Network Cursor by default.
         */
        public static final String DO_NOT_BECOME_ACTIVE_NETWORK_CURSOR =
                "SELECTION_ARGUMENT_DO_NOT_BECOME_ACTIVE_NETWORK_CURSOR";
    }

    // These are the projections that we need when getting cursors from the
    // content provider.
    private static String[] CONVERSATION_PROJECTION = {
            ConversationColumns.ID,
            ConversationColumns.SUBJECT,
            ConversationColumns.SNIPPET,
            ConversationColumns.FROM,
            ConversationColumns.DATE,
            ConversationColumns.PERSONAL_LEVEL,
            ConversationColumns.LABEL_IDS,
            ConversationColumns.NUM_MESSAGES,
            ConversationColumns.MAX_MESSAGE_ID,
            ConversationColumns.HAS_ATTACHMENTS,
            ConversationColumns.HAS_MESSAGES_WITH_ERRORS,
            ConversationColumns.FORCE_ALL_UNREAD};
    private static String[] MESSAGE_PROJECTION = {
            MessageColumns.ID,
            MessageColumns.MESSAGE_ID,
            MessageColumns.CONVERSATION_ID,
            MessageColumns.SUBJECT,
            MessageColumns.SNIPPET,
            MessageColumns.FROM,
            MessageColumns.TO,
            MessageColumns.CC,
            MessageColumns.BCC,
            MessageColumns.REPLY_TO,
            MessageColumns.DATE_SENT_MS,
            MessageColumns.DATE_RECEIVED_MS,
            MessageColumns.LIST_INFO,
            MessageColumns.PERSONAL_LEVEL,
            MessageColumns.BODY,
            MessageColumns.EMBEDS_EXTERNAL_RESOURCES,
            MessageColumns.LABEL_IDS,
            MessageColumns.JOINED_ATTACHMENT_INFOS,
            MessageColumns.ERROR};
    private static String[] LABEL_PROJECTION = {
            BaseColumns._ID,
            LabelColumns.CANONICAL_NAME,
            LabelColumns.NAME,
            LabelColumns.NUM_CONVERSATIONS,
            LabelColumns.NUM_UNREAD_CONVERSATIONS};
    private static String[] SETTINGS_PROJECTION = {
            SettingsColumns.LABELS_INCLUDED,
            SettingsColumns.LABELS_PARTIAL,
            SettingsColumns.CONVERSATION_AGE_DAYS,
            SettingsColumns.MAX_ATTACHMENET_SIZE_MB,
    };

    private ContentResolver mContentResolver;

    public Gmail(ContentResolver contentResolver) {
        mContentResolver = contentResolver;
    }

    /**
     * Returns source if source is non-null. Returns the empty string otherwise.
     */
    private static String toNonnullString(String source) {
        if (source == null) {
            return "";
        } else {
            return source;
        }
    }

    /**
     * Behavior for a new cursor: should it become the Active Network
     * Cursor?  This could potentially lead to bad behavior if someone
     * else is using the Active Network Cursor, since theirs will stop
     * being the Active Network Cursor.
     */
    public static enum BecomeActiveNetworkCursor {
        /**
         * The new cursor should become the one and only Active
         * Network Cursor.  Any other cursor that might already be the
         * Active Network Cursor will cease to be so.
         */
        YES,

        /**
         * The new cursor should not become the Active Network
         * Cursor. Any other cursor that might already be the Active
         * Network Cursor will continue to be so.
         */
        NO
    }

    /**
     * Wraps a Cursor in a ConversationCursor
     *
     * @param account the account the cursor is associated with
     * @param cursor The Cursor to wrap
     * @return a new ConversationCursor
     */
    public ConversationCursor getConversationCursorForCursor(String account, Cursor cursor) {
        if (TextUtils.isEmpty(account)) {
            throw new IllegalArgumentException("account is empty");
        }
        return new ConversationCursor(this, account, cursor);
    }

    /**
     * Creates an array of SelectionArguments suitable for passing to the provider's query.
     * Currently this only handles one flag, but it could be expanded in the future.
     */
    private static String[] getSelectionArguments(
            BecomeActiveNetworkCursor becomeActiveNetworkCursor) {
        if (BecomeActiveNetworkCursor.NO == becomeActiveNetworkCursor) {
            return new String[] {SelectionArguments.DO_NOT_BECOME_ACTIVE_NETWORK_CURSOR};
        } else {
            // Default behavior; no args required.
            return null;
        }
    }

    /**
     * Asynchronously gets a cursor over all conversations matching a query. The
     * query is in Gmail's query syntax. When the operation is complete the handler's
     * onQueryComplete() method is called with the resulting Cursor.
     *
     * @param account run the query on this account
     * @param handler An AsyncQueryHanlder that will be used to run the query
     * @param token The token to pass to startQuery, which will be passed back to onQueryComplete
     * @param query a query in Gmail's query syntax
     * @param becomeActiveNetworkCursor whether or not the returned
     * cursor should become the Active Network Cursor
     */
    public void runQueryForConversations(String account, AsyncQueryHandler handler, int token,
            String query, BecomeActiveNetworkCursor becomeActiveNetworkCursor) {
        if (TextUtils.isEmpty(account)) {
            throw new IllegalArgumentException("account is empty");
        }
        String[] selectionArgs = getSelectionArguments(becomeActiveNetworkCursor);
        handler.startQuery(token, null, Uri.withAppendedPath(CONVERSATIONS_URI, account),
                CONVERSATION_PROJECTION, query, selectionArgs, null);
    }

    /**
     * Synchronously gets a cursor over all conversations matching a query. The
     * query is in Gmail's query syntax.
     *
     * @param account run the query on this account
     * @param query a query in Gmail's query syntax
     * @param becomeActiveNetworkCursor whether or not the returned
     * cursor should become the Active Network Cursor
     */
    public ConversationCursor getConversationCursorForQuery(
            String account, String query, BecomeActiveNetworkCursor becomeActiveNetworkCursor) {
        String[] selectionArgs = getSelectionArguments(becomeActiveNetworkCursor);
        Cursor cursor = mContentResolver.query(
                Uri.withAppendedPath(CONVERSATIONS_URI, account), CONVERSATION_PROJECTION,
                query, selectionArgs, null);
        return new ConversationCursor(this, account, cursor);
    }

    /**
     * Gets a message cursor over the single message with the given id.
     *
     * @param account get the cursor for messages in this account
     * @param messageId the id of the message
     * @return a cursor over the message
     */
    public MessageCursor getMessageCursorForMessageId(String account, long messageId) {
        if (TextUtils.isEmpty(account)) {
            throw new IllegalArgumentException("account is empty");
        }
        Uri uri = Uri.parse(AUTHORITY_PLUS_MESSAGES + account + "/" + messageId);
        Cursor cursor = mContentResolver.query(uri, MESSAGE_PROJECTION, null, null, null);
        return new MessageCursor(this, mContentResolver, account, cursor);
    }

    /**
     * Gets a message cursor over the messages that match the query. Note that
     * this simply finds all of the messages that match and returns them. It
     * does not return all messages in conversations where any message matches.
     *
     * @param account get the cursor for messages in this account
     * @param query a query in GMail's query syntax. Currently only queries of
     *     the form [label:<label>] are supported
     * @return a cursor over the messages
     */
    public MessageCursor getLocalMessageCursorForQuery(String account, String query) {
        if (TextUtils.isEmpty(account)) {
            throw new IllegalArgumentException("account is empty");
        }
        Uri uri = Uri.parse(AUTHORITY_PLUS_MESSAGES + account + "/");
        Cursor cursor = mContentResolver.query(uri, MESSAGE_PROJECTION, query, null, null);
        return new MessageCursor(this, mContentResolver, account, cursor);
    }

    /**
     * Gets a cursor over all of the messages in a conversation.
     *
     * @param account get the cursor for messages in this account
     * @param conversationId the id of the converstion to fetch messages for
     * @return a cursor over messages in the conversation
     */
    public MessageCursor getMessageCursorForConversationId(String account, long conversationId) {
        if (TextUtils.isEmpty(account)) {
            throw new IllegalArgumentException("account is empty");
        }
        Uri uri = Uri.parse(
                AUTHORITY_PLUS_CONVERSATIONS + account + "/" + conversationId + "/messages");
        Cursor cursor = mContentResolver.query(
                uri, MESSAGE_PROJECTION, null, null, null);
        return new MessageCursor(this, mContentResolver, account, cursor);
    }

    /**
     * Expunge the indicated message. One use of this is to discard drafts.
     *
     * @param account the account of the message id
     * @param messageId the id of the message to expunge
     */
    public void expungeMessage(String account, long messageId) {
        if (TextUtils.isEmpty(account)) {
            throw new IllegalArgumentException("account is empty");
        }
        Uri uri = Uri.parse(AUTHORITY_PLUS_MESSAGES + account + "/" + messageId);
        mContentResolver.delete(uri, null, null);
    }

    /**
     * Adds or removes the label on the conversation.
     *
     * @param account the account of the conversation
     * @param conversationId the conversation
     * @param maxServerMessageId the highest message id to whose labels should be changed. Note that
     *   everywhere else in this file messageId means local message id but here you need to use a
     *   server message id.
     * @param label the label to add or remove
     * @param add true to add the label, false to remove it
     */
    public void addOrRemoveLabelOnConversation(
            String account, long conversationId, long maxServerMessageId, String label,
            boolean add) {
        if (TextUtils.isEmpty(account)) {
            throw new IllegalArgumentException("account is empty");
        }
        if (add) {
            Uri uri = Uri.parse(
                    AUTHORITY_PLUS_CONVERSATIONS + account + "/" + conversationId + "/labels");
            ContentValues values = new ContentValues();
            values.put(LabelColumns.CANONICAL_NAME, label);
            values.put(ConversationColumns.MAX_MESSAGE_ID, maxServerMessageId);
            mContentResolver.insert(uri, values);
        } else {
            String encodedLabel;
            try {
                encodedLabel = URLEncoder.encode(label, "utf-8");
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
            Uri uri = Uri.parse(
                    AUTHORITY_PLUS_CONVERSATIONS + account + "/"
                            + conversationId + "/labels/" + encodedLabel);
            mContentResolver.delete(
                    uri, ConversationColumns.MAX_MESSAGE_ID, new String[]{"" + maxServerMessageId});
        }
    }

    /**
     * Adds or removes the label on the message.
     *
     * @param contentResolver the content resolver.
     * @param account the account of the message
     * @param conversationId the conversation containing the message
     * @param messageId the id of the message to whose labels should be changed
     * @param label the label to add or remove
     * @param add true to add the label, false to remove it
     */
    public static void addOrRemoveLabelOnMessage(ContentResolver contentResolver, String account,
            long conversationId, long messageId, String label, boolean add) {

        // conversationId is unused but we want to start passing it whereever we pass a message id.
        if (add) {
            Uri uri = Uri.parse(
                    AUTHORITY_PLUS_MESSAGES + account + "/" + messageId + "/labels");
            ContentValues values = new ContentValues();
            values.put(LabelColumns.CANONICAL_NAME, label);
            contentResolver.insert(uri, values);
        } else {
            String encodedLabel;
            try {
                encodedLabel = URLEncoder.encode(label, "utf-8");
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
            Uri uri = Uri.parse(
                    AUTHORITY_PLUS_MESSAGES + account + "/" + messageId
                    + "/labels/" + encodedLabel);
            contentResolver.delete(uri, null, null);
        }
    }

    /**
     * The mail provider will send an intent when certain changes happen in certain labels.
     * Currently those labels are inbox and voicemail.
     *
     * <p>The intent will have the action ACTION_PROVIDER_CHANGED and the extras mentioned below.
     * The data for the intent will be content://gmail-ls/unread/<name of label>.
     *
     * <p>The goal is to support the following user experience:<ul>
     *   <li>When present the new mail indicator reports the number of unread conversations in the
     *   inbox (or some other label).</li>
     *   <li>When the user views the inbox the indicator is removed immediately. They do not have to
     *   read all of the conversations.</li>
     *   <li>If more mail arrives the indicator reappears and shows the total number of unread
     *   conversations in the inbox.</li>
     *   <li>If the user reads the new conversations on the web the indicator disappears on the
     *   phone since there is no unread mail in the inbox that the user hasn't seen.</li>
     *   <li>The phone should vibrate/etc when it transitions from having no unseen unread inbox
     *   mail to having some.</li>
     */

    /** The account in which the change occurred. */
    static public final String PROVIDER_CHANGED_EXTRA_ACCOUNT = "account";

    /** The number of unread conversations matching the label. */
    static public final String PROVIDER_CHANGED_EXTRA_COUNT = "count";

    /** Whether to get the user's attention, perhaps by vibrating. */
    static public final String PROVIDER_CHANGED_EXTRA_GET_ATTENTION = "getAttention";

    /**
     * A label that is attached to all of the conversations being notified about. This enables the
     * receiver of a notification to get a list of matching conversations.
     */
    static public final String PROVIDER_CHANGED_EXTRA_TAG_LABEL = "tagLabel";

    /**
     * Settings for which conversations should be synced to the phone.
     * Conversations are synced if any message matches any of the following
     * criteria:
     *
     * <ul>
     *   <li>the message has a label in the include set</li>
     *   <li>the message is no older than conversationAgeDays and has a label in the partial set.
     *   </li>
     *   <li>also, pending changes on the server: the message has no user-controllable labels.</li>
     * </ul>
     *
     * <p>A user-controllable label is a user-defined label or star, inbox,
     * trash, spam, etc. LABEL_UNREAD is not considered user-controllable.
     */
    public static class Settings {
        public long conversationAgeDays;
        public long maxAttachmentSizeMb;
        public String[] labelsIncluded;
        public String[] labelsPartial;
    }

    /**
     * Returns the settings.
     * @param account the account whose setting should be retrieved
     */
    public Settings getSettings(String account) {
        if (TextUtils.isEmpty(account)) {
            throw new IllegalArgumentException("account is empty");
        }
        Settings settings = new Settings();
        Cursor cursor = mContentResolver.query(
                Uri.withAppendedPath(SETTINGS_URI, account), SETTINGS_PROJECTION, null, null, null);
        cursor.moveToNext();
        settings.labelsIncluded = TextUtils.split(cursor.getString(0), SPACE_SEPARATOR_PATTERN);
        settings.labelsPartial = TextUtils.split(cursor.getString(1), SPACE_SEPARATOR_PATTERN);
        settings.conversationAgeDays = Long.parseLong(cursor.getString(2));
        settings.maxAttachmentSizeMb = Long.parseLong(cursor.getString(3));
        cursor.close();
        return settings;
    }

    /**
     * Sets the settings. A sync will be scheduled automatically.
     */
    public void setSettings(String account, Settings settings) {
        if (TextUtils.isEmpty(account)) {
            throw new IllegalArgumentException("account is empty");
        }
        ContentValues values = new ContentValues();
        values.put(
                SettingsColumns.LABELS_INCLUDED,
                TextUtils.join(" ", settings.labelsIncluded));
        values.put(
                SettingsColumns.LABELS_PARTIAL,
                TextUtils.join(" ", settings.labelsPartial));
        values.put(
                SettingsColumns.CONVERSATION_AGE_DAYS,
                settings.conversationAgeDays);
        values.put(
                SettingsColumns.MAX_ATTACHMENET_SIZE_MB,
                settings.maxAttachmentSizeMb);
        mContentResolver.update(Uri.withAppendedPath(SETTINGS_URI, account), values, null, null);
    }

    /**
     * Uses sender instructions to build a formatted string.
     *
     * <p>Sender list instructions contain compact information about the sender list. Most work that
     * can be done without knowing how much room will be availble for the sender list is done when
     * creating the instructions.
     *
     * <p>The instructions string consists of tokens separated by SENDER_LIST_SEPARATOR. Here are
     * the tokens, one per line:<ul>
     * <li><tt>n</tt></li>
     * <li><em>int</em>, the number of non-draft messages in the conversation</li>
     * <li><tt>d</tt</li>
     * <li><em>int</em>, the number of drafts in the conversation</li>
     * <li><tt>l</tt></li>
     * <li><em>literal html to be included in the output</em></li>
     * <li><tt>s</tt> indicates that the message is sending (in the outbox without errors)</li>
     * <li><tt>f</tt> indicates that the message failed to send (in the outbox with errors)</li>
     * <li><em>for each message</em><ul>
     *   <li><em>int</em>, 0 for read, 1 for unread</li>
     *   <li><em>int</em>, the priority of the message. Zero is the most important</li>
     *   <li><em>text</em>, the sender text or blank for messages from 'me'</li>
     * </ul></li>
     * <li><tt>e</tt> to indicate that one or more messages have been elided</li>
     *
     * <p>The instructions indicate how many messages and drafts are in the conversation and then
     * describe the most important messages in order, indicating the priority of each message and
     * whether the message is unread.
     *
     * @param instructions instructions as described above
     * @param sb the SpannableStringBuilder to append to
     * @param maxChars the number of characters available to display the text
     * @param unreadStyle the CharacterStyle for unread messages, or null
     * @param draftsStyle the CharacterStyle for draft messages, or null
     * @param sendingString the string to use when there are messages scheduled to be sent
     * @param sendFailedString the string to use when there are messages that mailed to send
     * @param meString the string to use for messages sent by this user
     * @param draftString the string to use for "Draft"
     * @param draftPluralString the string to use for "Drafts" 
     */
    public static void getSenderSnippet(
            String instructions, SpannableStringBuilder sb, int maxChars,
            CharacterStyle unreadStyle,
            CharacterStyle draftsStyle,
            CharSequence meString, CharSequence draftString, CharSequence draftPluralString,
            CharSequence sendingString, CharSequence sendFailedString,
            boolean forceAllUnread, boolean forceAllRead) {
        assert !(forceAllUnread && forceAllRead);
        boolean unreadStatusIsForced = forceAllUnread || forceAllRead;
        boolean forcedUnreadStatus = forceAllUnread;

        // Measure each fragment. It's ok to iterate over the entire set of fragments because it is
        // never a long list, even if there are many senders.
        final Map<Integer, Integer> priorityToLength = sPriorityToLength;
        priorityToLength.clear();

        int maxFoundPriority = Integer.MIN_VALUE;
        int numMessages = 0;
        int numDrafts = 0;
        CharSequence draftsFragment = "";
        CharSequence sendingFragment = "";
        CharSequence sendFailedFragment = "";
        
        sSenderListSplitter.setString(instructions);
        int numFragments = 0;
        String[] fragments = sSenderFragments;
        int currentSize = fragments.length;
        while (sSenderListSplitter.hasNext()) {
            fragments[numFragments++] = sSenderListSplitter.next();
            if (numFragments == currentSize) {
                sSenderFragments = new String[2 * currentSize];
                System.arraycopy(fragments, 0, sSenderFragments, 0, currentSize);
                currentSize *= 2;
                fragments = sSenderFragments;
            }
        }
        
        for (int i = 0; i < numFragments;) {
            String fragment0 = fragments[i++];
            if ("".equals(fragment0)) {
                // This should be the final fragment.
            } else if (Gmail.SENDER_LIST_TOKEN_ELIDED.equals(fragment0)) {
                // ignore
            } else if (Gmail.SENDER_LIST_TOKEN_NUM_MESSAGES.equals(fragment0)) {
                numMessages = Integer.valueOf(fragments[i++]);
            } else if (Gmail.SENDER_LIST_TOKEN_NUM_DRAFTS.equals(fragment0)) {
                String numDraftsString = fragments[i++];
                numDrafts = Integer.parseInt(numDraftsString);
                draftsFragment = numDrafts == 1 ? draftString :
                        draftPluralString + " (" + numDraftsString + ")";
            } else if (Gmail.SENDER_LIST_TOKEN_LITERAL.equals(fragment0)) {
                sb.append(Html.fromHtml(fragments[i++]));
                return;
            } else if (Gmail.SENDER_LIST_TOKEN_SENDING.equals(fragment0)) {
                sendingFragment = sendingString;
            } else if (Gmail.SENDER_LIST_TOKEN_SEND_FAILED.equals(fragment0)) {
                sendFailedFragment = sendFailedString;
            } else {
                String priorityString = fragments[i++];
                CharSequence nameString = fragments[i++];
                if (nameString.length() == 0) nameString = meString;
                int priority = Integer.parseInt(priorityString);
                priorityToLength.put(priority, nameString.length());
                maxFoundPriority = Math.max(maxFoundPriority, priority);
            }
        }
        String numMessagesFragment =
                (numMessages != 0) ? " (" + Integer.toString(numMessages + numDrafts) + ")" : "";

        // Don't allocate fixedFragment unless we need it
        SpannableStringBuilder fixedFragment = null;
        int fixedFragmentLength = 0;
        if (draftsFragment.length() != 0) {
            if (fixedFragment == null) {
                fixedFragment = new SpannableStringBuilder();
            }
            fixedFragment.append(draftsFragment);
            if (draftsStyle != null) {
                fixedFragment.setSpan(
                        CharacterStyle.wrap(draftsStyle),
                        0, fixedFragment.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        if (sendingFragment.length() != 0) {
            if (fixedFragment == null) {
                fixedFragment = new SpannableStringBuilder();
            }
            if (fixedFragment.length() != 0) fixedFragment.append(", ");
            fixedFragment.append(sendingFragment);
        }
        if (sendFailedFragment.length() != 0) {
            if (fixedFragment == null) {
                fixedFragment = new SpannableStringBuilder();
            }
            if (fixedFragment.length() != 0) fixedFragment.append(", ");
            fixedFragment.append(sendFailedFragment);
        }

        if (fixedFragment != null) {
            fixedFragmentLength = fixedFragment.length();
        }

        final boolean normalMessagesExist =
                numMessagesFragment.length() != 0 || maxFoundPriority != Integer.MIN_VALUE;
        String preFixedFragement = "";
        if (normalMessagesExist && fixedFragmentLength != 0) {
            preFixedFragement = ", ";
        }
        int maxPriorityToInclude = -1; // inclusive
        int numCharsUsed =
                numMessagesFragment.length() + preFixedFragement.length() + fixedFragmentLength;
        int numSendersUsed = 0;
        while (maxPriorityToInclude < maxFoundPriority) {
            if (priorityToLength.containsKey(maxPriorityToInclude + 1)) {
                int length = numCharsUsed + priorityToLength.get(maxPriorityToInclude + 1);
                if (numCharsUsed > 0) length += 2;
                // We must show at least two senders if they exist. If we don't have space for both
                // then we will truncate names.
                if (length > maxChars && numSendersUsed >= 2) {
                    break;
                }
                numCharsUsed = length;
                numSendersUsed++;
            }
            maxPriorityToInclude++;
        }

        int numCharsToRemovePerWord = 0;
        if (numCharsUsed > maxChars) {
            numCharsToRemovePerWord = (numCharsUsed - maxChars) / numSendersUsed;
        }

        boolean elided = false;
        for (int i = 0; i < numFragments;) {
            String fragment0 = fragments[i++];
            if ("".equals(fragment0)) {
                // This should be the final fragment.
            } else if (SENDER_LIST_TOKEN_ELIDED.equals(fragment0)) {
                elided = true;
            } else if (SENDER_LIST_TOKEN_NUM_MESSAGES.equals(fragment0)) {
                i++;
            } else if (SENDER_LIST_TOKEN_NUM_DRAFTS.equals(fragment0)) {
                i++;
            } else if (SENDER_LIST_TOKEN_SENDING.equals(fragment0)) {
            } else if (SENDER_LIST_TOKEN_SEND_FAILED.equals(fragment0)) {
            } else {
                final String unreadString = fragment0;
                final String priorityString = fragments[i++];
                String nameString = fragments[i++];
                if (nameString.length() == 0) nameString = meString.toString();
                if (numCharsToRemovePerWord != 0) {
                    nameString = nameString.substring(
                            0, Math.max(nameString.length() - numCharsToRemovePerWord, 0));
                }
                final boolean unread = unreadStatusIsForced
                        ? forcedUnreadStatus : Integer.parseInt(unreadString) != 0;
                final int priority = Integer.parseInt(priorityString);
                if (priority <= maxPriorityToInclude) {
                    if (sb.length() != 0) {
                        sb.append(elided ? " .. " : ", ");
                    }
                    elided = false;
                    int pos = sb.length();
                    sb.append(nameString);
                    if (unread && unreadStyle != null) {
                        sb.setSpan(CharacterStyle.wrap(unreadStyle),
                                pos, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                } else {
                    elided = true;
                }
            }
        }
        sb.append(numMessagesFragment);
        if (fixedFragmentLength != 0) {
            sb.append(preFixedFragement);
            sb.append(fixedFragment);
        }
    }

    /**
     * This is a cursor that only defines methods to move throught the results
     * and register to hear about changes. All access to the data is left to
     * subinterfaces.
     */
    public static class MailCursor extends ContentObserver {

        // A list of observers of this cursor.
        private Set<MailCursorObserver> mObservers;

        // Updated values are accumulated here before being written out if the
        // cursor is asked to persist the changes.
        private ContentValues mUpdateValues;

        protected Cursor mCursor;
        protected String mAccount;

        public Cursor getCursor() {
            return mCursor;
        }

        /**
         * Constructs the MailCursor given a regular cursor, registering as a
         * change observer of the cursor.
         * @param account the account the cursor is associated with
         * @param cursor the underlying cursor
         */
        protected MailCursor(String account, Cursor cursor) {
            super(new Handler());
            mObservers = new HashSet<MailCursorObserver>();
            mCursor = cursor;
            mAccount = account;
            if (mCursor != null) mCursor.registerContentObserver(this);
        }

        /**
         * Gets the account associated with this cursor.
         * @return the account.
         */
        public String getAccount() {
            return mAccount;
        }

        protected void checkThread() {
            // Turn this on when activity code no longer runs in the sync thread
            // after notifications of changes.
//            Thread currentThread = Thread.currentThread();
//            if (currentThread != mThread) {
//                throw new RuntimeException("Accessed from the wrong thread");
//            }
        }

        /**
         * Lazily constructs a map of update values to apply to the database
         * if requested. This map is cleared out when we move to a different
         * item in the result set.
         *
         * @return a map of values to be applied by an update.
         */
        protected ContentValues getUpdateValues() {
            if (mUpdateValues == null) {
                mUpdateValues = new ContentValues();
            }
            return mUpdateValues;
        }

        /**
         * Called whenever mCursor is changed to point to a different row.
         * Subclasses should override this if they need to clear out state
         * when this happens.
         *
         * Subclasses must call the inherited version if they override this.
         */
        protected void onCursorPositionChanged() {
            mUpdateValues = null;
        }

        // ********* MailCursor

        /**
         * Returns the numbers of rows in the cursor.
         *
         * @return the number of rows in the cursor.
         */
        final public int count() {
            if (mCursor != null) {
                return mCursor.getCount();
            } else {
                return 0;
            }
        }

        /**
         * @return the current position of this cursor, or -1 if this cursor
         * has not been initialized.
         */
        final public int position() {
            if (mCursor != null) {
                return mCursor.getPosition();
            } else {
                return -1;
            }
        }

        /**
         * Move the cursor to an absolute position. The valid
         * range of vaues is -1 &lt;= position &lt;= count.
         *
         * <p>This method will return true if the request destination was
         * reachable, otherwise it returns false.
         *
         * @param position the zero-based position to move to.
         * @return whether the requested move fully succeeded.
         */
        final public boolean moveTo(int position) {
            checkCursor();
            checkThread();
            boolean moved = mCursor.moveToPosition(position);
            if (moved) onCursorPositionChanged();
            return moved;
        }

        /**
         * Move the cursor to the next row.
         *
         * <p>This method will return false if the cursor is already past the
         * last entry in the result set.
         *
         * @return whether the move succeeded.
         */
        final public boolean next() {
            checkCursor();
            checkThread();
            boolean moved = mCursor.moveToNext();
            if (moved) onCursorPositionChanged();
            return moved;
        }

        /**
         * Release all resources and locks associated with the cursor. The
         * cursor will not be valid after this function is called.
         */
        final public void release() {
            if (mCursor != null) {
                mCursor.unregisterContentObserver(this);
                mCursor.deactivate();
            }
        }

        final public void registerContentObserver(ContentObserver observer) {
            mCursor.registerContentObserver(observer);
        }

        final public void unregisterContentObserver(ContentObserver observer) {
            mCursor.unregisterContentObserver(observer);
        }

        final public void registerDataSetObserver(DataSetObserver observer) {
            mCursor.registerDataSetObserver(observer);
        }

        final public void unregisterDataSetObserver(DataSetObserver observer) {
            mCursor.unregisterDataSetObserver(observer);
        }

        /**
         * Register an observer to hear about changes to the cursor.
         *
         * @param observer the observer to register
         */
        final public void registerObserver(MailCursorObserver observer) {
            mObservers.add(observer);
        }

        /**
         * Unregister an observer.
         *
         * @param observer the observer to unregister
         */
        final public void unregisterObserver(MailCursorObserver observer) {
            mObservers.remove(observer);
        }

        // ********* ContentObserver

        @Override
        final public boolean deliverSelfNotifications() {
            return false;
        }

        @Override
        public void onChange(boolean selfChange) {
            if (Config.DEBUG) {
                Log.d(TAG, "MailCursor is notifying " + mObservers.size() + " observers");
            }
            for (MailCursorObserver o: mObservers) {
                o.onCursorChanged(this);
            }
        }

        protected void checkCursor() {
            if (mCursor == null) {
                throw new IllegalStateException(
                        "cannot read from an insertion cursor");
            }
        }

        /**
         * Returns the string value of the column, or "" if the value is null.
         */
        protected String getStringInColumn(int columnIndex) {
            checkCursor();
            return toNonnullString(mCursor.getString(columnIndex));
        }
    }

    /**
     * A MailCursor observer is notified of changes to the result set of a
     * cursor.
     */
    public interface MailCursorObserver {

        /**
         * Called when the result set of a cursor has changed.
         *
         * @param cursor the cursor whose result set has changed.
         */
        void onCursorChanged(MailCursor cursor);
    }

    /**
     * A cursor over labels.
     */
    public final class LabelCursor extends MailCursor {

        private int mNameIndex;
        private int mNumConversationsIndex;
        private int mNumUnreadConversationsIndex;

        private LabelCursor(String account, Cursor cursor) {
            super(account, cursor);

            mNameIndex = mCursor.getColumnIndexOrThrow(LabelColumns.CANONICAL_NAME);
            mNumConversationsIndex =
                    mCursor.getColumnIndexOrThrow(LabelColumns.NUM_CONVERSATIONS);
            mNumUnreadConversationsIndex = mCursor.getColumnIndexOrThrow(
                    LabelColumns.NUM_UNREAD_CONVERSATIONS);
        }

        /**
         * Gets the canonical name of the current label.
         *
         * @return the current label's name.
         */
        public String getName() {
            return getStringInColumn(mNameIndex);
        }

        /**
         * Gets the number of conversations with this label.
         *
         * @return the number of conversations with this label.
         */
        public int getNumConversations() {
            return mCursor.getInt(mNumConversationsIndex);
        }

        /**
         * Gets the number of unread conversations with this label.
         *
         * @return the number of unread conversations with this label.
         */
        public int getNumUnreadConversations() {
            return mCursor.getInt(mNumUnreadConversationsIndex);
        }
    }

    /**
     * This is a map of labels. TODO: make it observable.
     */
    public static final class LabelMap extends Observable {
        private final static ContentValues EMPTY_CONTENT_VALUES = new ContentValues();

        private ContentQueryMap mQueryMap;
        private SortedSet<String> mSortedUserLabels;
        private Map<String, Long> mCanonicalNameToId;

        private long mLabelIdSent;
        private long mLabelIdInbox;
        private long mLabelIdDraft;
        private long mLabelIdUnread;
        private long mLabelIdTrash;
        private long mLabelIdSpam;
        private long mLabelIdStarred;
        private long mLabelIdChat;
        private long mLabelIdVoicemail;
        private long mLabelIdIgnored;
        private long mLabelIdVoicemailInbox;
        private long mLabelIdCached;
        private long mLabelIdOutbox;

        private boolean mLabelsSynced = false;

        public LabelMap(ContentResolver contentResolver, String account, boolean keepUpdated) {
            if (TextUtils.isEmpty(account)) {
                throw new IllegalArgumentException("account is empty");
            }
            Cursor cursor = contentResolver.query(
                    Uri.withAppendedPath(LABELS_URI, account), LABEL_PROJECTION, null, null, null);
            init(cursor, keepUpdated);
        }

        public LabelMap(Cursor cursor, boolean keepUpdated) {
            init(cursor, keepUpdated);
        }

        private void init(Cursor cursor, boolean keepUpdated) {
            mQueryMap = new ContentQueryMap(cursor, BaseColumns._ID, keepUpdated, null);
            mSortedUserLabels = new TreeSet<String>(java.text.Collator.getInstance());
            mCanonicalNameToId = Maps.newHashMap();
            updateDataStructures();
            mQueryMap.addObserver(new Observer() {
                public void update(Observable observable, Object data) {
                    updateDataStructures();
                    setChanged();
                    notifyObservers();
                }
            });
        }

        /**
         * @return whether at least some labels have been synced.
         */
        public boolean labelsSynced() {
            return mLabelsSynced;
        }

        /**
         * Updates the data structures that are maintained separately from mQueryMap after the query
         * map has changed.
         */
        private void updateDataStructures() {
            mSortedUserLabels.clear();
            mCanonicalNameToId.clear();
            for (Map.Entry<String, ContentValues> row : mQueryMap.getRows().entrySet()) {
                long labelId = Long.valueOf(row.getKey());
                String canonicalName = row.getValue().getAsString(LabelColumns.CANONICAL_NAME);
                if (isLabelUserDefined(canonicalName)) {
                    mSortedUserLabels.add(canonicalName);
                }
                mCanonicalNameToId.put(canonicalName, labelId);

                if (LABEL_SENT.equals(canonicalName)) {
                    mLabelIdSent = labelId;
                } else if (LABEL_INBOX.equals(canonicalName)) {
                    mLabelIdInbox = labelId;
                } else if (LABEL_DRAFT.equals(canonicalName)) {
                    mLabelIdDraft = labelId;
                } else if (LABEL_UNREAD.equals(canonicalName)) {
                    mLabelIdUnread = labelId;
                } else if (LABEL_TRASH.equals(canonicalName)) {
                    mLabelIdTrash = labelId;
                } else if (LABEL_SPAM.equals(canonicalName)) {
                    mLabelIdSpam = labelId;
                } else if (LABEL_STARRED.equals(canonicalName)) {
                    mLabelIdStarred = labelId;
                } else if (LABEL_CHAT.equals(canonicalName)) {
                    mLabelIdChat = labelId;
                } else if (LABEL_IGNORED.equals(canonicalName)) {
                    mLabelIdIgnored = labelId;
                } else if (LABEL_VOICEMAIL.equals(canonicalName)) {
                    mLabelIdVoicemail = labelId;
                } else if (LABEL_VOICEMAIL_INBOX.equals(canonicalName)) {
                    mLabelIdVoicemailInbox = labelId;
                } else if (LABEL_CACHED.equals(canonicalName)) {
                    mLabelIdCached = labelId;
                } else if (LABEL_OUTBOX.equals(canonicalName)) {
                    mLabelIdOutbox = labelId;
                }
                mLabelsSynced = mLabelIdSent != 0
                    && mLabelIdInbox != 0
                    && mLabelIdDraft != 0
                    && mLabelIdUnread != 0
                    && mLabelIdTrash != 0
                    && mLabelIdSpam != 0
                    && mLabelIdStarred != 0
                    && mLabelIdChat != 0
                    && mLabelIdIgnored != 0
                    && mLabelIdVoicemail != 0;
            }
        }

        public long getLabelIdSent() {
            checkLabelsSynced();
            return mLabelIdSent;
        }

        public long getLabelIdInbox() {
            checkLabelsSynced();
            return mLabelIdInbox;
        }

        public long getLabelIdDraft() {
            checkLabelsSynced();
            return mLabelIdDraft;
        }

        public long getLabelIdUnread() {
            checkLabelsSynced();
            return mLabelIdUnread;
        }

        public long getLabelIdTrash() {
            checkLabelsSynced();
            return mLabelIdTrash;
        }

        public long getLabelIdSpam() {
            checkLabelsSynced();
            return mLabelIdSpam;
        }

        public long getLabelIdStarred() {
            checkLabelsSynced();
            return mLabelIdStarred;
        }

        public long getLabelIdChat() {
            checkLabelsSynced();
            return mLabelIdChat;
        }

        public long getLabelIdIgnored() {
            checkLabelsSynced();
            return mLabelIdIgnored;
        }

        public long getLabelIdVoicemail() {
            checkLabelsSynced();
            return mLabelIdVoicemail;
        }

        public long getLabelIdVoicemailInbox() {
            checkLabelsSynced();
            return mLabelIdVoicemailInbox;
        }

        public long getLabelIdCached() {
            checkLabelsSynced();
            return mLabelIdCached;
        }

        public long getLabelIdOutbox() {
            checkLabelsSynced();
            return mLabelIdOutbox;
        }

        private void checkLabelsSynced() {
            if (!labelsSynced()) {
                throw new IllegalStateException("LabelMap not initalized");
            }
        }

        /** Returns the list of user-defined labels in alphabetical order. */
        public SortedSet<String> getSortedUserLabels() {
            return mSortedUserLabels;
        }

        private static final List<String> SORTED_USER_MEANINGFUL_SYSTEM_LABELS =
                Lists.newArrayList(
                        LABEL_INBOX, LABEL_STARRED, LABEL_CHAT, LABEL_SENT,
                        LABEL_OUTBOX, LABEL_DRAFT, LABEL_ALL,
                        LABEL_SPAM, LABEL_TRASH);


        private static final Set<String> USER_MEANINGFUL_SYSTEM_LABELS_SET =
                Sets.newHashSet(
                        SORTED_USER_MEANINGFUL_SYSTEM_LABELS.toArray(
                                new String[]{}));

        public static List<String> getSortedUserMeaningfulSystemLabels() {
            return SORTED_USER_MEANINGFUL_SYSTEM_LABELS;
        }

        public static Set<String> getUserMeaningfulSystemLabelsSet() {
            return USER_MEANINGFUL_SYSTEM_LABELS_SET;
        }

        /**
         * If you are ever tempted to remove outbox or draft from this set make sure you have a
         * way to stop draft and outbox messages from getting purged before they are sent to the
         * server.
         */
        private static final Set<String> FORCED_INCLUDED_LABELS =
                Sets.newHashSet(LABEL_OUTBOX, LABEL_DRAFT);

        public static Set<String> getForcedIncludedLabels() {
            return FORCED_INCLUDED_LABELS;
        }

        private static final Set<String> FORCED_INCLUDED_OR_PARTIAL_LABELS =
                Sets.newHashSet(LABEL_INBOX);

        public static Set<String> getForcedIncludedOrPartialLabels() {
            return FORCED_INCLUDED_OR_PARTIAL_LABELS;
        }

        private static final Set<String> FORCED_UNSYNCED_LABELS =
                Sets.newHashSet(LABEL_ALL, LABEL_CHAT, LABEL_SPAM, LABEL_TRASH);

        public static Set<String> getForcedUnsyncedLabels() {
            return FORCED_UNSYNCED_LABELS;
        }

        /**
         * Returns the number of conversation with a given label.
         * @deprecated
         */
        public int getNumConversations(String label) {
            return getNumConversations(getLabelId(label));
        }

        /** Returns the number of conversation with a given label. */
        public int getNumConversations(long labelId) {
            return getLabelIdValues(labelId).getAsInteger(LabelColumns.NUM_CONVERSATIONS);
        }

        /**
         * Returns the number of unread conversation with a given label.
         * @deprecated
         */
        public int getNumUnreadConversations(String label) {
            return getNumUnreadConversations(getLabelId(label));
        }

        /** Returns the number of unread conversation with a given label. */
        public int getNumUnreadConversations(long labelId) {
            Integer unreadConversations =
                    getLabelIdValues(labelId).getAsInteger(LabelColumns.NUM_UNREAD_CONVERSATIONS);
            // There seems to be a race condition here that can get the label maps into a bad
            // state and lose state on a particular label.
            if (unreadConversations == null) {
                return 0;
            } else {
                return unreadConversations;
            }
        }

        /**
         * @return the canonical name for a label
         */
        public String getCanonicalName(long labelId) {
            return getLabelIdValues(labelId).getAsString(LabelColumns.CANONICAL_NAME);
        }

        /**
         * @return the human name for a label
         */
        public String getName(long labelId) {
            return getLabelIdValues(labelId).getAsString(LabelColumns.NAME);
        }

        /**
         * @return whether a given label is known
         */
        public boolean hasLabel(long labelId) {
            return mQueryMap.getRows().containsKey(Long.toString(labelId));
        }

        /**
         * @return returns the id of a label given the canonical name
         * @deprecated this is only needed because most of the UI uses label names instead of ids
         */
        public long getLabelId(String canonicalName) {
            if (mCanonicalNameToId.containsKey(canonicalName)) {
                return mCanonicalNameToId.get(canonicalName);
            } else {
                throw new IllegalArgumentException("Unknown canonical name: " + canonicalName);
            }
        }

        private ContentValues getLabelIdValues(long labelId) {
            final ContentValues values = mQueryMap.getValues(Long.toString(labelId));
            if (values != null) {
                return values;
            } else {
                return EMPTY_CONTENT_VALUES;
            }
        }

        /** Force the map to requery. This should not be necessary outside tests. */
        public void requery() {
            mQueryMap.requery();
        }

        public void close() {
            mQueryMap.close();
        }
    }

    private Map<String, Gmail.LabelMap> mLabelMaps = Maps.newHashMap();

    public LabelMap getLabelMap(String account) {
        Gmail.LabelMap labelMap = mLabelMaps.get(account);
        if (labelMap == null) {
            labelMap = new Gmail.LabelMap(mContentResolver, account, true /* keepUpdated */);
            mLabelMaps.put(account, labelMap);
        }
        return labelMap;
    }

    public enum PersonalLevel {
        NOT_TO_ME(0),
        TO_ME_AND_OTHERS(1),
        ONLY_TO_ME(2);

        private int mLevel;

        PersonalLevel(int level) {
            mLevel = level;
        }

        public int toInt() {
            return mLevel;
        }

        public static PersonalLevel fromInt(int level) {
            switch (level) {
                case 0: return NOT_TO_ME;
                case 1: return TO_ME_AND_OTHERS;
                case 2: return ONLY_TO_ME;
                default:
                    throw new IllegalArgumentException(
                            level + " is not a personal level");
            }
        }
    }

    /**
     * Indicates a version of an attachment.
     */
    public enum AttachmentRendition {
        /**
         * The full version of an attachment if it can be handled on the device, otherwise the
         * preview.
         */
        BEST,

        /** A smaller or simpler version of the attachment, such as a scaled-down image or an HTML
         * version of a document. Not always available.
         */
        SIMPLE,
    }

    /**
     * The columns that can be requested when querying an attachment's download URI. See
     * getAttachmentDownloadUri.
     */
    public static final class AttachmentColumns implements BaseColumns {

        /** Contains a STATUS value from {@link android.provider.Downloads} */
        public static final String STATUS = "status";

        /**
         * The name of the file to open (with ContentProvider.open). If this is empty then continue
         * to use the attachment's URI.
         *
         * TODO: I'm not sure that we need this. See the note in CL 66853-p9.
         */
        public static final String FILENAME = "filename";
    }

    /**
     * We track where an attachment came from so that we know how to download it and include it
     * in new messages.
     */
    public enum AttachmentOrigin {
        /** Extras are "<conversationId>-<messageId>-<partId>". */
        SERVER_ATTACHMENT,
        /** Extras are "<path>". */
        LOCAL_FILE;

        private static final String SERVER_EXTRAS_SEPARATOR = "_";

        public static String serverExtras(
                long conversationId, long messageId, String partId) {
            return conversationId + SERVER_EXTRAS_SEPARATOR
                    + messageId + SERVER_EXTRAS_SEPARATOR + partId;
        }

        /**
         * @param extras extras as returned by serverExtras
         * @return an array of conversationId, messageId, partId (all as strings)
         */
        public static String[] splitServerExtras(String extras) {
            return TextUtils.split(extras, SERVER_EXTRAS_SEPARATOR);
        }

        public static String localFileExtras(Uri path) {
            return path.toString();
        }
    }

    public static final class Attachment {
        /** Identifies the attachment uniquely when combined wih a message id.*/
        public String partId;

        /** The intended filename of the attachment.*/
        public String name;

        /** The native content type.*/
        public String contentType;

        /** The size of the attachment in its native form.*/
        public int size;

        /**
         * The content type of the simple version of the attachment. Blank if no simple version is
         * available.
         */
        public String simpleContentType;

        public AttachmentOrigin origin;

        public String originExtras;

        public String toJoinedString() {
            return TextUtils.join(
                "|", Lists.newArrayList(partId == null ? "" : partId,
                                        name.replace("|", ""), contentType,
                                        size, simpleContentType,
                                        origin.toString(), originExtras));
        }

        public static Attachment parseJoinedString(String joinedString) {
            String[] fragments = TextUtils.split(joinedString, "\\|");
            int i = 0;
            Attachment attachment = new Attachment();
            attachment.partId = fragments[i++];
            if (TextUtils.isEmpty(attachment.partId)) {
                attachment.partId = null;
            }
            attachment.name = fragments[i++];
            attachment.contentType = fragments[i++];
            attachment.size = Integer.parseInt(fragments[i++]);
            attachment.simpleContentType = fragments[i++];
            attachment.origin = AttachmentOrigin.valueOf(fragments[i++]);
            attachment.originExtras = fragments[i++];
            return attachment;
        }
    }

    /**
     * Any given attachment can come in two different renditions (see
     * {@link android.provider.Gmail.AttachmentRendition}) and can be saved to the sd card or to a
     * cache. The gmail provider automatically syncs some attachments to the cache. Other
     * attachments can be downloaded on demand. Attachments in the cache will be purged as needed to
     * save space. Attachments on the SD card must be managed by the user or other software.
     *
     * @param account which account to use
     * @param messageId the id of the mesage with the attachment
     * @param attachment the attachment
     * @param rendition the desired rendition
     * @param saveToSd whether the attachment should be saved to (or loaded from) the sd card or
     * @return the URI to ask the content provider to open in order to open an attachment.
     */
    public static Uri getAttachmentUri(
            String account, long messageId, Attachment attachment,
            AttachmentRendition rendition, boolean saveToSd) {
        if (TextUtils.isEmpty(account)) {
            throw new IllegalArgumentException("account is empty");
        }
        if (attachment.origin == AttachmentOrigin.LOCAL_FILE) {
            return Uri.parse(attachment.originExtras);
        } else {
            return Uri.parse(
                    AUTHORITY_PLUS_MESSAGES).buildUpon()
                    .appendPath(account).appendPath(Long.toString(messageId))
                    .appendPath("attachments").appendPath(attachment.partId)
                    .appendPath(rendition.toString())
                    .appendPath(Boolean.toString(saveToSd))
                    .build();
        }
    }

    /**
     * Return the URI to query in order to find out whether an attachment is downloaded.
     *
     * <p>Querying this will also start a download if necessary. The cursor returned by querying
     * this URI can contain the columns in {@link android.provider.Gmail.AttachmentColumns}.
     *
     * <p>Deleting this URI will cancel the download if it was not started automatically by the
     * provider. It will also remove bookkeeping for saveToSd downloads.
     *
     * @param attachmentUri the attachment URI as returned by getAttachmentUri. The URI's authority
     *   Gmail.AUTHORITY. If it is not then you should open the file directly.
     */
    public static Uri getAttachmentDownloadUri(Uri attachmentUri) {
        if (!"content".equals(attachmentUri.getScheme())) {
            throw new IllegalArgumentException("Uri's scheme must be 'content': " + attachmentUri);
        }
        return attachmentUri.buildUpon().appendPath("download").build();
    }

    public enum CursorStatus {
        LOADED,
        LOADING,
        ERROR, // A network error occurred.
    }

    /**
     * A cursor over messages.
     */
    public static final class MessageCursor extends MailCursor {

        private LabelMap mLabelMap;

        private ContentResolver mContentResolver;

        /**
         * Only valid if mCursor == null, in which case we are inserting a new
         * message.
         */
        long mInReplyToLocalMessageId;
        boolean mPreserveAttachments;

        private int mIdIndex;
        private int mConversationIdIndex;
        private int mSubjectIndex;
        private int mSnippetIndex;
        private int mFromIndex;
        private int mToIndex;
        private int mCcIndex;
        private int mBccIndex;
        private int mReplyToIndex;
        private int mDateSentMsIndex;
        private int mDateReceivedMsIndex;
        private int mListInfoIndex;
        private int mPersonalLevelIndex;
        private int mBodyIndex;
        private int mBodyEmbedsExternalResourcesIndex;
        private int mLabelIdsIndex;
        private int mJoinedAttachmentInfosIndex;
        private int mErrorIndex;

        private TextUtils.StringSplitter mLabelIdsSplitter = newMessageLabelIdsSplitter();

        public MessageCursor(Gmail gmail, ContentResolver cr, String account, Cursor cursor) {
            super(account, cursor);
            mLabelMap = gmail.getLabelMap(account);
            if (cursor == null) {
                throw new IllegalArgumentException(
                        "null cursor passed to MessageCursor()");
            }

            mContentResolver = cr;

            mIdIndex = mCursor.getColumnIndexOrThrow(MessageColumns.ID);
            mConversationIdIndex =
                    mCursor.getColumnIndexOrThrow(MessageColumns.CONVERSATION_ID);
            mSubjectIndex = mCursor.getColumnIndexOrThrow(MessageColumns.SUBJECT);
            mSnippetIndex = mCursor.getColumnIndexOrThrow(MessageColumns.SNIPPET);
            mFromIndex = mCursor.getColumnIndexOrThrow(MessageColumns.FROM);
            mToIndex = mCursor.getColumnIndexOrThrow(MessageColumns.TO);
            mCcIndex = mCursor.getColumnIndexOrThrow(MessageColumns.CC);
            mBccIndex = mCursor.getColumnIndexOrThrow(MessageColumns.BCC);
            mReplyToIndex = mCursor.getColumnIndexOrThrow(MessageColumns.REPLY_TO);
            mDateSentMsIndex =
                    mCursor.getColumnIndexOrThrow(MessageColumns.DATE_SENT_MS);
            mDateReceivedMsIndex =
                    mCursor.getColumnIndexOrThrow(MessageColumns.DATE_RECEIVED_MS);
            mListInfoIndex = mCursor.getColumnIndexOrThrow(MessageColumns.LIST_INFO);
            mPersonalLevelIndex =
                    mCursor.getColumnIndexOrThrow(MessageColumns.PERSONAL_LEVEL);
            mBodyIndex = mCursor.getColumnIndexOrThrow(MessageColumns.BODY);
            mBodyEmbedsExternalResourcesIndex =
                    mCursor.getColumnIndexOrThrow(MessageColumns.EMBEDS_EXTERNAL_RESOURCES);
            mLabelIdsIndex = mCursor.getColumnIndexOrThrow(MessageColumns.LABEL_IDS);
            mJoinedAttachmentInfosIndex =
                    mCursor.getColumnIndexOrThrow(MessageColumns.JOINED_ATTACHMENT_INFOS);
            mErrorIndex = mCursor.getColumnIndexOrThrow(MessageColumns.ERROR);

            mInReplyToLocalMessageId = 0;
            mPreserveAttachments = false;
        }

        protected MessageCursor(ContentResolver cr, String account, long inReplyToMessageId,
                boolean preserveAttachments) {
            super(account, null);
            mContentResolver = cr;
            mInReplyToLocalMessageId = inReplyToMessageId;
            mPreserveAttachments = preserveAttachments;
        }

        @Override
        protected void onCursorPositionChanged() {
            super.onCursorPositionChanged();
        }

        public CursorStatus getStatus() {
            Bundle extras = mCursor.getExtras();
            String stringStatus = extras.getString(EXTRA_STATUS);
            return CursorStatus.valueOf(stringStatus);
        }

        /** Retry a network request after errors. */
        public void retry() {
            Bundle input = new Bundle();
            input.putString(RESPOND_INPUT_COMMAND, COMMAND_RETRY);
            Bundle output = mCursor.respond(input);
            String response = output.getString(RESPOND_OUTPUT_COMMAND_RESPONSE);
            assert COMMAND_RESPONSE_OK.equals(response);
        }

        /**
         * Gets the message id of the current message. Note that this is an
         * immutable local message (not, for example, GMail's message id, which
         * is immutable).
         *
         * @return the message's id
         */
        public long getMessageId() {
            checkCursor();
            return mCursor.getLong(mIdIndex);
        }

        /**
         * Gets the message's conversation id. This must be immutable. (For
         * example, with GMail this should be the original conversation id
         * rather than the default notion of converation id.)
         *
         * @return the message's conversation id
         */
        public long getConversationId() {
            checkCursor();
            return mCursor.getLong(mConversationIdIndex);
        }

        /**
         * Gets the message's subject.
         *
         * @return the message's subject
         */
        public String getSubject() {
            return getStringInColumn(mSubjectIndex);
        }

        /**
         * Gets the message's snippet (the short piece of the body). The snippet
         * is generated from the body and cannot be set directly.
         *
         * @return the message's snippet
         */
        public String getSnippet() {
            return getStringInColumn(mSnippetIndex);
        }

        /**
         * Gets the message's from address.
         *
         * @return the message's from address
         */
        public String getFromAddress() {
            return getStringInColumn(mFromIndex);
        }

        /**
         * Returns the addresses for the key, if it has been updated, or index otherwise.
         */
        private String[] getAddresses(String key, int index) {
            ContentValues updated = getUpdateValues();
            String addresses;
            if (updated.containsKey(key)) {
                addresses = (String)getUpdateValues().get(key);
            } else {
                addresses = getStringInColumn(index);
            }

            return TextUtils.split(addresses, EMAIL_SEPARATOR_PATTERN);
        }

        /**
         * Gets the message's to addresses.
         * @return the message's to addresses
         */
        public String[] getToAddresses() {
           return getAddresses(MessageColumns.TO, mToIndex);
        }

        /**
         * Gets the message's cc addresses.
         * @return the message's cc addresses
         */
        public String[] getCcAddresses() {
            return getAddresses(MessageColumns.CC, mCcIndex);
        }

        /**
         * Gets the message's bcc addresses.
         * @return the message's bcc addresses
         */
        public String[] getBccAddresses() {
            return getAddresses(MessageColumns.BCC, mBccIndex);
        }

        /**
         * Gets the message's replyTo address.
         *
         * @return the message's replyTo address
         */
        public String[] getReplyToAddress() {
            return TextUtils.split(getStringInColumn(mReplyToIndex), EMAIL_SEPARATOR_PATTERN);
        }

        public long getDateSentMs() {
            checkCursor();
            return mCursor.getLong(mDateSentMsIndex);
        }

        public long getDateReceivedMs() {
            checkCursor();
            return mCursor.getLong(mDateReceivedMsIndex);
        }

        public String getListInfo() {
            return getStringInColumn(mListInfoIndex);
        }

        public PersonalLevel getPersonalLevel() {
            checkCursor();
            int personalLevelInt = mCursor.getInt(mPersonalLevelIndex);
            return PersonalLevel.fromInt(personalLevelInt);
        }

        /**
         * @deprecated
         */
        public boolean getExpanded() {
            return true;
        }

        /**
         * Gets the message's body.
         *
         * @return the message's body
         */
        public String getBody() {
            return getStringInColumn(mBodyIndex);
        }

        /**
         * @return whether the message's body contains embedded references to external resources. In
         * that case the resources should only be displayed if the user explicitly asks for them to
         * be
         */
        public boolean getBodyEmbedsExternalResources() {
            checkCursor();
            return mCursor.getInt(mBodyEmbedsExternalResourcesIndex) != 0;
        }

        /**
         * @return a copy of the set of label ids
         */
        public Set<Long> getLabelIds() {
            String labelNames = mCursor.getString(mLabelIdsIndex);
            mLabelIdsSplitter.setString(labelNames);
            return getLabelIdsFromLabelIdsString(mLabelIdsSplitter);
        }

        /**
         * @return a joined string of labels separated by spaces.
         */
        public String getRawLabelIds() {
            return mCursor.getString(mLabelIdsIndex);
        }

        /**
         * Adds a label to a message (if add is true) or removes it (if add is
         * false).
         *
         * @param label the label to add or remove
         * @param add whether to add or remove the label
         */
        public void addOrRemoveLabel(String label, boolean add) {
            addOrRemoveLabelOnMessage(mContentResolver, mAccount, getConversationId(),
                    getMessageId(), label, add);
        }

        public ArrayList<Attachment> getAttachmentInfos() {
            ArrayList<Attachment> attachments = Lists.newArrayList();

            String joinedAttachmentInfos = mCursor.getString(mJoinedAttachmentInfosIndex);
            if (joinedAttachmentInfos != null) {
                for (String joinedAttachmentInfo :
                        TextUtils.split(joinedAttachmentInfos, ATTACHMENT_INFO_SEPARATOR_PATTERN)) {

                    Attachment attachment = Attachment.parseJoinedString(joinedAttachmentInfo);
                    attachments.add(attachment);
                }
            }
            return attachments;
        }

        /**
         * @return the error text for the message. Error text gets set if the server rejects a
         * message that we try to save or send. If there is error text then the message is no longer
         * scheduled to be saved or sent. Calling save() or send() will clear any error as well as
         * scheduling another atempt to save or send the message.
         */
        public String getErrorText() {
            return mCursor.getString(mErrorIndex);
        }
    }

    /**
     * A helper class for creating or updating messags. Use the putXxx methods to provide initial or
     * new values for the message. Then save or send the message. To save or send an existing
     * message without making other changes to it simply provide an emty ContentValues.
     */
    public static class MessageModification {

        /**
         * Sets the message's subject. Only valid for drafts.
         *
         * @param values the ContentValues that will be used to create or update the message
         * @param subject the new subject
         */
        public static void putSubject(ContentValues values, String subject) {
            values.put(MessageColumns.SUBJECT, subject);
        }

        /**
         * Sets the message's to address. Only valid for drafts.
         *
         * @param values the ContentValues that will be used to create or update the message
         * @param toAddresses the new to addresses
         */
        public static void putToAddresses(ContentValues values, String[] toAddresses) {
            values.put(MessageColumns.TO, TextUtils.join(EMAIL_SEPARATOR, toAddresses));
        }

        /**
         * Sets the message's cc address. Only valid for drafts.
         *
         * @param values the ContentValues that will be used to create or update the message
         * @param ccAddresses the new cc addresses
         */
        public static void putCcAddresses(ContentValues values, String[] ccAddresses) {
            values.put(MessageColumns.CC, TextUtils.join(EMAIL_SEPARATOR, ccAddresses));
        }

        /**
         * Sets the message's bcc address. Only valid for drafts.
         *
         * @param values the ContentValues that will be used to create or update the message
         * @param bccAddresses the new bcc addresses
         */
        public static void putBccAddresses(ContentValues values, String[] bccAddresses) {
            values.put(MessageColumns.BCC, TextUtils.join(EMAIL_SEPARATOR, bccAddresses));
        }

        /**
         * Saves a new body for the message. Only valid for drafts.
         *
         * @param values the ContentValues that will be used to create or update the message
         * @param body the new body of the message
         */
        public static void putBody(ContentValues values, String body) {
            values.put(MessageColumns.BODY, body);
        }

        /**
         * Sets the attachments on a message. Only valid for drafts.
         *
         * @param values the ContentValues that will be used to create or update the message
         * @param attachments
         */
        public static void putAttachments(ContentValues values, List<Attachment> attachments) {
            values.put(
                    MessageColumns.JOINED_ATTACHMENT_INFOS, joinedAttachmentsString(attachments));
        }

        /**
         * Create a new message and save it as a draft or send it.
         *
         * @param contentResolver the content resolver to use
         * @param account the account to use
         * @param values the values for the new message
         * @param refMessageId the message that is being replied to or forwarded
         * @param save whether to save or send the message
         * @return the id of the new message
         */
        public static long sendOrSaveNewMessage(
                ContentResolver contentResolver, String account,
                ContentValues values, long refMessageId, boolean save) {
            values.put(MessageColumns.FAKE_SAVE, save);
            values.put(MessageColumns.FAKE_REF_MESSAGE_ID, refMessageId);
            Uri uri = Uri.parse(AUTHORITY_PLUS_MESSAGES + account + "/");
            Uri result = contentResolver.insert(uri, values);
            return ContentUris.parseId(result);
        }

        /**
         * Update an existing draft and save it as a new draft or send it.
         *
         * @param contentResolver the content resolver to use
         * @param account the account to use
         * @param messageId the id of the message to update
         * @param updateValues the values to change. Unspecified fields will not be altered
         * @param save whether to resave the message as a draft or send it
         */
        public static void sendOrSaveExistingMessage(
                ContentResolver contentResolver, String account, long messageId,
                ContentValues updateValues, boolean save) {
            updateValues.put(MessageColumns.FAKE_SAVE, save);
            updateValues.put(MessageColumns.FAKE_REF_MESSAGE_ID, 0);
            Uri uri = Uri.parse(
                    AUTHORITY_PLUS_MESSAGES + account + "/" + messageId);
            contentResolver.update(uri, updateValues, null, null);
        }

        /**
         * The string produced here is parsed by Gmail.MessageCursor#getAttachmentInfos.
         */
        public static String joinedAttachmentsString(List<Gmail.Attachment> attachments) {
            StringBuilder attachmentsSb = new StringBuilder();
            for (Gmail.Attachment attachment : attachments) {
                if (attachmentsSb.length() != 0) {
                    attachmentsSb.append(Gmail.ATTACHMENT_INFO_SEPARATOR);
                }
                attachmentsSb.append(attachment.toJoinedString());
            }
            return attachmentsSb.toString();
        }

    }

    /**
     * A cursor over conversations.
     *
     * "Conversation" refers to the information needed to populate a list of
     * conversations, not all of the messages in a conversation.
     */
    public static final class ConversationCursor extends MailCursor {

        private LabelMap mLabelMap;

        private int mConversationIdIndex;
        private int mSubjectIndex;
        private int mSnippetIndex;
        private int mFromIndex;
        private int mDateIndex;
        private int mPersonalLevelIndex;
        private int mLabelIdsIndex;
        private int mNumMessagesIndex;
        private int mMaxMessageIdIndex;
        private int mHasAttachmentsIndex;
        private int mHasMessagesWithErrorsIndex;
        private int mForceAllUnreadIndex;

        private TextUtils.StringSplitter mLabelIdsSplitter = newConversationLabelIdsSplitter();

        private ConversationCursor(Gmail gmail, String account, Cursor cursor) {
            super(account, cursor);
            mLabelMap = gmail.getLabelMap(account);

            mConversationIdIndex =
                    mCursor.getColumnIndexOrThrow(ConversationColumns.ID);
            mSubjectIndex = mCursor.getColumnIndexOrThrow(ConversationColumns.SUBJECT);
            mSnippetIndex = mCursor.getColumnIndexOrThrow(ConversationColumns.SNIPPET);
            mFromIndex = mCursor.getColumnIndexOrThrow(ConversationColumns.FROM);
            mDateIndex = mCursor.getColumnIndexOrThrow(ConversationColumns.DATE);
            mPersonalLevelIndex =
                    mCursor.getColumnIndexOrThrow(ConversationColumns.PERSONAL_LEVEL);
            mLabelIdsIndex =
                    mCursor.getColumnIndexOrThrow(ConversationColumns.LABEL_IDS);
            mNumMessagesIndex = mCursor.getColumnIndexOrThrow(ConversationColumns.NUM_MESSAGES);
            mMaxMessageIdIndex = mCursor.getColumnIndexOrThrow(ConversationColumns.MAX_MESSAGE_ID);
            mHasAttachmentsIndex =
                    mCursor.getColumnIndexOrThrow(ConversationColumns.HAS_ATTACHMENTS);
            mHasMessagesWithErrorsIndex =
                    mCursor.getColumnIndexOrThrow(ConversationColumns.HAS_MESSAGES_WITH_ERRORS);
            mForceAllUnreadIndex =
                    mCursor.getColumnIndexOrThrow(ConversationColumns.FORCE_ALL_UNREAD);
        }

        @Override
        protected void onCursorPositionChanged() {
            super.onCursorPositionChanged();
        }

        public CursorStatus getStatus() {
            Bundle extras = mCursor.getExtras();
            String stringStatus = extras.getString(EXTRA_STATUS);
            return CursorStatus.valueOf(stringStatus);
        }

        /** Retry a network request after errors. */
        public void retry() {
            Bundle input = new Bundle();
            input.putString(RESPOND_INPUT_COMMAND, COMMAND_RETRY);
            Bundle output = mCursor.respond(input);
            String response = output.getString(RESPOND_OUTPUT_COMMAND_RESPONSE);
            assert COMMAND_RESPONSE_OK.equals(response);
        }

        /**
         * When a conversation cursor is created it becomes the active network cursor, which means
         * that it will fetch results from the network if it needs to in order to show all mail that
         * matches its query. If you later want to requery an older cursor and would like that
         * cursor to be the active cursor you need to call this method before requerying.
         */
        public void becomeActiveNetworkCursor() {
            Bundle input = new Bundle();
            input.putString(RESPOND_INPUT_COMMAND, COMMAND_ACTIVATE);
            Bundle output = mCursor.respond(input);
            String response = output.getString(RESPOND_OUTPUT_COMMAND_RESPONSE);
            assert COMMAND_RESPONSE_OK.equals(response);
        }

        /**
         * Tells the cursor whether its contents are visible to the user. The cursor will
         * automatically broadcast intents to remove any matching new-mail notifications when this
         * cursor's results become visible and, if they are visible, when the cursor is requeried.
         *
         * Note that contents shown in an activity that is resumed but not focused
         * (onWindowFocusChanged/hasWindowFocus) then results shown in that activity do not count
         * as visible. (This happens when the activity is behind the lock screen or a dialog.)
         *
         * @param visible whether the contents of this cursor are visible to the user.
         */
        public void setContentsVisibleToUser(boolean visible) {
            Bundle input = new Bundle();
            input.putString(RESPOND_INPUT_COMMAND, COMMAND_SET_VISIBLE);
            input.putBoolean(SET_VISIBLE_PARAM_VISIBLE, visible);
            Bundle output = mCursor.respond(input);
            String response = output.getString(RESPOND_OUTPUT_COMMAND_RESPONSE);
            assert COMMAND_RESPONSE_OK.equals(response);
        }

        /**
         * Gets the conversation id. This is immutable. (The server calls it the original
         * conversation id.)
         *
         * @return the conversation id
         */
        public long getConversationId() {
            return mCursor.getLong(mConversationIdIndex);
        }

        /**
         * Returns the instructions for building from snippets. Pass this to getFromSnippetHtml
         * in order to actually build the snippets.
         * @return snippet instructions for use by getFromSnippetHtml()
         */
        public String getFromSnippetInstructions() {
            return getStringInColumn(mFromIndex);
        }

        /**
         * Gets the conversation's subject.
         *
         * @return the subject
         */
        public String getSubject() {
            return getStringInColumn(mSubjectIndex);
        }

        /**
         * Gets the conversation's snippet.
         *
         * @return the snippet
         */
        public String getSnippet() {
            return getStringInColumn(mSnippetIndex);
        }

        /**
         * Get's the conversation's personal level.
         *
         * @return the personal level.
         */
        public PersonalLevel getPersonalLevel() {
            int personalLevelInt = mCursor.getInt(mPersonalLevelIndex);
            return PersonalLevel.fromInt(personalLevelInt);
        }

        /**
         * @return a copy of the set of labels. To add or remove labels call
         *         MessageCursor.addOrRemoveLabel on each message in the conversation.
         * @deprecated use getLabelIds
         */
        public Set<String> getLabels() {
            return getLabels(getRawLabelIds(), mLabelMap);
        }

        /**
         * @return a copy of the set of labels. To add or remove labels call
         *         MessageCursor.addOrRemoveLabel on each message in the conversation.
         */
        public Set<Long> getLabelIds() {
            mLabelIdsSplitter.setString(getRawLabelIds());
            return getLabelIdsFromLabelIdsString(mLabelIdsSplitter);
        }

        /**
         * Returns the set of labels using the raw labels from a previous getRawLabels()
         * as input.
         * @return a copy of the set of labels. To add or remove labels call
         * MessageCursor.addOrRemoveLabel on each message in the conversation.
         */
        public Set<String> getLabels(String rawLabelIds, LabelMap labelMap) {
            mLabelIdsSplitter.setString(rawLabelIds);
            return getCanonicalNamesFromLabelIdsString(labelMap, mLabelIdsSplitter);
        }

        /**
         * @return a joined string of labels separated by spaces. Use
         * getLabels(rawLabels) to convert this to a Set of labels.
         */
        public String getRawLabelIds() {
            return mCursor.getString(mLabelIdsIndex);
        }

        /**
         * @return the number of messages in the conversation
         */
        public int getNumMessages() {
            return mCursor.getInt(mNumMessagesIndex);
        }

        /**
         * @return the max message id in the conversation
         */
        public long getMaxServerMessageId() {
            return mCursor.getLong(mMaxMessageIdIndex);
        }

        public long getDateMs() {
            return mCursor.getLong(mDateIndex);
        }

        public boolean hasAttachments() {
            return mCursor.getInt(mHasAttachmentsIndex) != 0;
        }

        public boolean hasMessagesWithErrors() {
            return mCursor.getInt(mHasMessagesWithErrorsIndex) != 0;
        }

        public boolean getForceAllUnread() {
            return !mCursor.isNull(mForceAllUnreadIndex)
                    && mCursor.getInt(mForceAllUnreadIndex) != 0;
        }
    }
}
