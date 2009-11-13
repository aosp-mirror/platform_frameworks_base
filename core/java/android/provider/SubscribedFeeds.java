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
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.accounts.Account;

/**
 * The SubscribedFeeds provider stores all information about subscribed feeds.
 * 
 * @hide
 */
public class SubscribedFeeds {
    private SubscribedFeeds() {}
    
    /**
     * Columns from the Feed table that other tables join into themselves.
     */
    public interface FeedColumns {
        /**
         * The feed url.
         * <P>Type: TEXT</P>
         */
        public static final String FEED = "feed";

        /**
         * The authority that cares about the feed.
         * <P>Type: TEXT</P>
         */
        public static final String AUTHORITY = "authority";

        /**
         * The gaia service this feed is for (used for authentication).
         * <P>Type: TEXT</P>
         */
        public static final String SERVICE = "service";
    }

    /**
     * Provides constants to access the Feeds table and some utility methods
     * to ease using the Feeds content provider.
     */
    public static final class Feeds implements BaseColumns, SyncConstValue,
            FeedColumns {
        private Feeds() {}
        
        public static Cursor query(ContentResolver cr, String[] projection) {
            return cr.query(CONTENT_URI, projection, null, null, DEFAULT_SORT_ORDER);
        }

        public static Cursor query(ContentResolver cr, String[] projection,
                String where, String[] whereArgs, String orderBy) {
            return cr.query(CONTENT_URI, projection, where,
                    whereArgs, (orderBy == null) ? DEFAULT_SORT_ORDER : orderBy);
        }

        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI =
            Uri.parse("content://subscribedfeeds/feeds");

        /**
         * The content:// style URL for this table
         */
        public static final Uri DELETED_CONTENT_URI =
            Uri.parse("content://subscribedfeeds/deleted_feeds");

        /**
         * The MIME type of {@link #CONTENT_URI} providing a directory of
         * subscribed feeds.
         */
        public static final String CONTENT_TYPE =
                "vnd.android.cursor.dir/subscribedfeeds";

        /**
         * The MIME type of a {@link #CONTENT_URI} subdirectory of a single
         * subscribed feed.
         */
        public static final String CONTENT_ITEM_TYPE =
                "vnd.android.cursor.item/subscribedfeed";

        /**
         * The default sort order for this table
         */
        public static final String DEFAULT_SORT_ORDER = "_SYNC_ACCOUNT_TYPE, _SYNC_ACCOUNT ASC";
    }

    /**
     * A convenience method to add a feed to the SubscribedFeeds
     * content provider. The user specifies the values of the FEED,
     * _SYNC_ACCOUNT, AUTHORITY. SERVICE, and ROUTING_INFO.
     * @param resolver          used to access the underlying content provider
     * @param feed              corresponds to the FEED column
     * @param account           corresponds to the _SYNC_ACCOUNT column
     * @param authority         corresponds to the AUTHORITY column
     * @param service           corresponds to the SERVICE column
     * @return  the Uri of the feed that was added
     */
    public static Uri addFeed(ContentResolver resolver,
            String feed, Account account,
            String authority, String service) {
        ContentValues values = new ContentValues();
        values.put(SubscribedFeeds.Feeds.FEED, feed);
        values.put(SubscribedFeeds.Feeds._SYNC_ACCOUNT, account.name);
        values.put(SubscribedFeeds.Feeds._SYNC_ACCOUNT_TYPE, account.type);
        values.put(SubscribedFeeds.Feeds.AUTHORITY, authority);
        values.put(SubscribedFeeds.Feeds.SERVICE, service);
        return resolver.insert(SubscribedFeeds.Feeds.CONTENT_URI, values);
    }

    public static int deleteFeed(ContentResolver resolver,
            String feed, Account account, String authority) {
        StringBuilder where = new StringBuilder();
        where.append(SubscribedFeeds.Feeds._SYNC_ACCOUNT + "=?");
        where.append(" AND " + SubscribedFeeds.Feeds._SYNC_ACCOUNT_TYPE + "=?");
        where.append(" AND " + SubscribedFeeds.Feeds.FEED + "=?");
        where.append(" AND " + SubscribedFeeds.Feeds.AUTHORITY + "=?");
        return resolver.delete(SubscribedFeeds.Feeds.CONTENT_URI,
                where.toString(), new String[] {account.name, account.type, feed, authority});
    }

    public static int deleteFeeds(ContentResolver resolver,
            Account account, String authority) {
        StringBuilder where = new StringBuilder();
        where.append(SubscribedFeeds.Feeds._SYNC_ACCOUNT + "=?");
        where.append(" AND " + SubscribedFeeds.Feeds._SYNC_ACCOUNT_TYPE + "=?");
        where.append(" AND " + SubscribedFeeds.Feeds.AUTHORITY + "=?");
        return resolver.delete(SubscribedFeeds.Feeds.CONTENT_URI,
                where.toString(), new String[] {account.name, account.type, authority});
    }

    /**
     * Columns from the Accounts table.
     */
    public interface AccountColumns {
        /**
         * The account.
         * <P>Type: TEXT</P>
         */
        public static final String _SYNC_ACCOUNT = SyncConstValue._SYNC_ACCOUNT;

        /**
         * The account type.
         * <P>Type: TEXT</P>
         */
        public static final String _SYNC_ACCOUNT_TYPE = SyncConstValue._SYNC_ACCOUNT_TYPE;
    }

    /**
     * Provides constants to access the Accounts table and some utility methods
     * to ease using it.
     */
    public static final class Accounts implements BaseColumns, AccountColumns {
        private Accounts() {}

        public static Cursor query(ContentResolver cr, String[] projection) {
            return cr.query(CONTENT_URI, projection, null, null, DEFAULT_SORT_ORDER);
        }

        public static Cursor query(ContentResolver cr, String[] projection,
                String where, String orderBy) {
            return cr.query(CONTENT_URI, projection, where,
                    null, (orderBy == null) ? DEFAULT_SORT_ORDER : orderBy);
        }

        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI =
            Uri.parse("content://subscribedfeeds/accounts");

        /**
         * The MIME type of {@link #CONTENT_URI} providing a directory of
         * accounts that have subscribed feeds.
         */
        public static final String CONTENT_TYPE =
                "vnd.android.cursor.dir/subscribedfeedaccounts";

        /**
         * The MIME type of a {@link #CONTENT_URI} subdirectory of a single
         * account in the subscribed feeds.
         */
        public static final String CONTENT_ITEM_TYPE =
                "vnd.android.cursor.item/subscribedfeedaccount";

        /**
         * The default sort order for this table
         */
        public static final String DEFAULT_SORT_ORDER = "_SYNC_ACCOUNT_TYPE, _SYNC_ACCOUNT ASC";
    }
}
