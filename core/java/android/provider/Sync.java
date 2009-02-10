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
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;

import java.util.Map;

/**
 * The Sync provider stores information used in managing the syncing of the device,
 * including the history and pending syncs.
 * 
 * @hide
 */
public final class Sync {
    // utility class
    private Sync() {}

    /**
     * The content url for this provider.
     */
    public static final Uri CONTENT_URI = Uri.parse("content://sync");

    /**
     * Columns from the stats table.
     */
    public interface StatsColumns {
        /**
         * The sync account.
         * <P>Type: TEXT</P>
         */
        public static final String ACCOUNT = "account";

        /**
         * The content authority (contacts, calendar, etc.).
         * <P>Type: TEXT</P>
         */
        public static final String AUTHORITY = "authority";
    }

    /**
     * Provides constants and utility methods to access and use the stats table.
     */
    public static final class Stats implements BaseColumns, StatsColumns {

        // utility class
        private Stats() {}

        /**
         * The content url for this table.
         */
        public static final Uri CONTENT_URI =
                Uri.parse("content://sync/stats");

        /** Projection for the _id column in the stats table. */
        public static final String[] SYNC_STATS_PROJECTION = {_ID};
    }

    /**
     * Columns from the history table.
     */
    public interface HistoryColumns {
        /**
         * The ID of the stats row corresponding to this event.
         * <P>Type: INTEGER</P>
         */
        public static final String STATS_ID = "stats_id";

        /**
         * The source of the sync event (LOCAL, POLL, USER, SERVER).
         * <P>Type: INTEGER</P>
         */
        public static final String SOURCE = "source";

        /**
         * The type of sync event (START, STOP).
         * <P>Type: INTEGER</P>
         */
        public static final String EVENT = "event";

        /**
         * The time of the event.
         * <P>Type: INTEGER</P>
         */
        public static final String EVENT_TIME = "eventTime";

        /**
         * How long this event took. This is only valid if the EVENT is EVENT_STOP.
         * <P>Type: INTEGER</P>
         */
        public static final String ELAPSED_TIME = "elapsedTime";

        /**
         * Any additional message associated with this event.
         * <P>Type: TEXT</P>
         */
        public static final String MESG = "mesg";

        /**
         * How much activity was performed sending data to the server. This is sync adapter
         * specific, but usually is something like how many record update/insert/delete attempts
         * were carried out. This is only valid if the EVENT is EVENT_STOP.
         * <P>Type: INTEGER</P>
         */
        public static final String UPSTREAM_ACTIVITY = "upstreamActivity";

        /**
         * How much activity was performed while receiving data from the server.
         * This is sync adapter specific, but usually is something like how many
         * records were received from the server. This is only valid if the
         * EVENT is EVENT_STOP.
         * <P>Type: INTEGER</P>
         */
        public static final String DOWNSTREAM_ACTIVITY = "downstreamActivity";
    }

    /**
     * Columns from the history table.
     */
    public interface StatusColumns {
        /**
         * How many syncs were completed for this account and authority.
         * <P>Type: INTEGER</P>
         */
        public static final String NUM_SYNCS = "numSyncs";

        /**
         * How long all the events for this account and authority took.
         * <P>Type: INTEGER</P>
         */
        public static final String TOTAL_ELAPSED_TIME = "totalElapsedTime";

        /**
         * The number of syncs with SOURCE_POLL.
         * <P>Type: INTEGER</P>
         */
        public static final String NUM_SOURCE_POLL = "numSourcePoll";

        /**
         * The number of syncs with SOURCE_SERVER.
         * <P>Type: INTEGER</P>
         */
        public static final String NUM_SOURCE_SERVER = "numSourceServer";

        /**
         * The number of syncs with SOURCE_LOCAL.
         * <P>Type: INTEGER</P>
         */
        public static final String NUM_SOURCE_LOCAL = "numSourceLocal";

        /**
         * The number of syncs with SOURCE_USER.
         * <P>Type: INTEGER</P>
         */
        public static final String NUM_SOURCE_USER = "numSourceUser";

        /**
         * The time in ms that the last successful sync ended. Will be null if
         * there are no successful syncs. A successful sync is defined as one having
         * MESG=MESG_SUCCESS.
         * <P>Type: INTEGER</P>
         */
        public static final String LAST_SUCCESS_TIME = "lastSuccessTime";

        /**
         * The SOURCE of the last successful sync. Will be null if
         * there are no successful syncs. A successful sync is defined
         * as one having MESG=MESG_SUCCESS.
         * <P>Type: INTEGER</P>
         */
        public static final String LAST_SUCCESS_SOURCE = "lastSuccessSource";

        /**
         * The end time in ms of the last sync that failed since the last successful sync.
         * Will be null if there are no syncs or if the last one succeeded. A failed
         * sync is defined as one where MESG isn't MESG_SUCCESS or MESG_CANCELED.
         * <P>Type: INTEGER</P>
         */
        public static final String LAST_FAILURE_TIME = "lastFailureTime";

        /**
         * The SOURCE of the last sync that failed since the last successful sync.
         * Will be null if there are no syncs or if the last one succeeded. A failed
         * sync is defined as one where MESG isn't MESG_SUCCESS or MESG_CANCELED.
         * <P>Type: INTEGER</P>
         */
        public static final String LAST_FAILURE_SOURCE = "lastFailureSource";

        /**
         * The MESG of the last sync that failed since the last successful sync.
         * Will be null if there are no syncs or if the last one succeeded. A failed
         * sync is defined as one where MESG isn't MESG_SUCCESS or MESG_CANCELED.
         * <P>Type: STRING</P>
         */
        public static final String LAST_FAILURE_MESG = "lastFailureMesg";

        /**
         * Is set to 1 if a sync is pending, 0 if not.
         * <P>Type: INTEGER</P>
         */
        public static final String PENDING = "pending";
    }

    /**
     * Provides constants and utility methods to access and use the history
     * table.
     */
    public static class History implements BaseColumns,
                                                 StatsColumns,
                                                 HistoryColumns {

        /**
         * The content url for this table.
         */
        public static final Uri CONTENT_URI =
                Uri.parse("content://sync/history");

        /** Enum value for a sync start event. */
        public static final int EVENT_START = 0;

        /** Enum value for a sync stop event. */
        public static final int EVENT_STOP = 1;

        // TODO: i18n -- grab these out of resources.
        /** String names for the sync event types. */
        public static final String[] EVENTS = { "START", "STOP" };

        /** Enum value for a server-initiated sync. */
        public static final int SOURCE_SERVER = 0;

        /** Enum value for a local-initiated sync. */
        public static final int SOURCE_LOCAL = 1;
        /**
         * Enum value for a poll-based sync (e.g., upon connection to
         * network)
         */
        public static final int SOURCE_POLL = 2;

        /** Enum value for a user-initiated sync. */
        public static final int SOURCE_USER = 3;

        // TODO: i18n -- grab these out of resources.
        /** String names for the sync source types. */
        public static final String[] SOURCES = { "SERVER",
                                                 "LOCAL",
                                                 "POLL",
                                                 "USER" };

        // Error types
        public static final int ERROR_SYNC_ALREADY_IN_PROGRESS = 1;
        public static final int ERROR_AUTHENTICATION = 2;
        public static final int ERROR_IO = 3;
        public static final int ERROR_PARSE = 4;
        public static final int ERROR_CONFLICT = 5;
        public static final int ERROR_TOO_MANY_DELETIONS = 6;
        public static final int ERROR_TOO_MANY_RETRIES = 7;
        public static final int ERROR_INTERNAL = 8;

        // The MESG column will contain one of these or one of the Error types.
        public static final String MESG_SUCCESS = "success";
        public static final String MESG_CANCELED = "canceled";

        private static final String FINISHED_SINCE_WHERE_CLAUSE = EVENT + "=" + EVENT_STOP
                + " AND " + EVENT_TIME + ">? AND " + ACCOUNT + "=? AND " + AUTHORITY + "=?";

        public static String mesgToString(String mesg) {
            if (MESG_SUCCESS.equals(mesg)) return mesg;
            if (MESG_CANCELED.equals(mesg)) return mesg;
            switch (Integer.parseInt(mesg)) {
                case ERROR_SYNC_ALREADY_IN_PROGRESS: return "already in progress";
                case ERROR_AUTHENTICATION: return "bad authentication";
                case ERROR_IO: return "network error";
                case ERROR_PARSE: return "parse error";
                case ERROR_CONFLICT: return "conflict detected";
                case ERROR_TOO_MANY_DELETIONS: return "too many deletions";
                case ERROR_TOO_MANY_RETRIES: return "too many retries";
                case ERROR_INTERNAL: return "internal error";
                default: return "unknown error";
            }
        }

        // utility class
        private History() {}

        /**
         * returns a cursor that queries the sync history in descending event time order
         * @param contentResolver the ContentResolver to use for the query
         * @return the cursor on the History table
         */
        public static Cursor query(ContentResolver contentResolver) {
            return contentResolver.query(CONTENT_URI, null, null, null, EVENT_TIME + " desc");
        }

        public static boolean hasNewerSyncFinished(ContentResolver contentResolver,
                String account, String authority, long when) {
            Cursor c = contentResolver.query(CONTENT_URI, new String[]{_ID},
                    FINISHED_SINCE_WHERE_CLAUSE,
                    new String[]{Long.toString(when), account, authority}, null);
            try {
              return c.getCount() > 0;
            } finally {
                c.close();
            }
        }
    }

    /**
     * Provides constants and utility methods to access and use the authority history
     * table, which contains information about syncs aggregated by account and authority.
     * All the HistoryColumns except for EVENT are present, plus the AuthorityHistoryColumns.
     */
    public static class Status extends History implements StatusColumns {

        /**
         * The content url for this table.
         */
        public static final Uri CONTENT_URI = Uri.parse("content://sync/status");

        // utility class
        private Status() {}

        /**
         * returns a cursor that queries the authority sync history in descending event order of
         * ACCOUNT, AUTHORITY
         * @param contentResolver the ContentResolver to use for the query
         * @return the cursor on the AuthorityHistory table
         */
        public static Cursor query(ContentResolver contentResolver) {
            return contentResolver.query(CONTENT_URI, null, null, null, ACCOUNT + ", " + AUTHORITY);
        }

        public static class QueryMap extends ContentQueryMap {
            public QueryMap(ContentResolver contentResolver,
                    boolean keepUpdated,
                    Handler handlerForUpdateNotifications) {
                super(contentResolver.query(CONTENT_URI, null, null, null, null),
                        _ID, keepUpdated, handlerForUpdateNotifications);
            }

            public ContentValues get(String account, String authority) {
                Map<String, ContentValues> rows = getRows();
                for (ContentValues values : rows.values()) {
                    if (values.getAsString(ACCOUNT).equals(account)
                            && values.getAsString(AUTHORITY).equals(authority)) {
                        return values;
                    }
                }
                return null;
            }
        }
    }

    /**
     * Provides constants and utility methods to access and use the pending syncs table
     */
    public static final class Pending implements BaseColumns,
                                                 StatsColumns {

        /**
         * The content url for this table.
         */
        public static final Uri CONTENT_URI = Uri.parse("content://sync/pending");

        // utility class
        private Pending() {}

        public static class QueryMap extends ContentQueryMap {
            public QueryMap(ContentResolver contentResolver, boolean keepUpdated,
                    Handler handlerForUpdateNotifications) {
                super(contentResolver.query(CONTENT_URI, null, null, null, null), _ID, keepUpdated,
                        handlerForUpdateNotifications);
            }

            public boolean isPending(String account, String authority) {
                Map<String, ContentValues> rows = getRows();
                for (ContentValues values : rows.values()) {
                    if (values.getAsString(ACCOUNT).equals(account)
                            && values.getAsString(AUTHORITY).equals(authority)) {
                        return true;
                    }
                }
                return false;
            }
        }
    }

    /**
     * Columns from the history table.
     */
    public interface ActiveColumns {
        /**
         * The wallclock time of when the active sync started.
         * <P>Type: INTEGER</P>
         */
        public static final String START_TIME = "startTime";
    }

    /**
     * Provides constants and utility methods to access and use the pending syncs table
     */
    public static final class Active implements BaseColumns,
                                                StatsColumns,
                                                ActiveColumns {

        /**
         * The content url for this table.
         */
        public static final Uri CONTENT_URI = Uri.parse("content://sync/active");

        // utility class
        private Active() {}

        public static class QueryMap extends ContentQueryMap {
            public QueryMap(ContentResolver contentResolver, boolean keepUpdated,
                    Handler handlerForUpdateNotifications) {
                super(contentResolver.query(CONTENT_URI, null, null, null, null), _ID, keepUpdated,
                        handlerForUpdateNotifications);
            }

            public ContentValues getActiveSyncInfo() {
                Map<String, ContentValues> rows = getRows();
                for (ContentValues values : rows.values()) {
                    return values;
                }
                return null;
            }

            public String getSyncingAccount() {
                ContentValues values = getActiveSyncInfo();
                return (values == null) ? null : values.getAsString(ACCOUNT);
            }

            public String getSyncingAuthority() {
                ContentValues values = getActiveSyncInfo();
                return (values == null) ? null : values.getAsString(AUTHORITY);
            }

            public long getSyncStartTime() {
                ContentValues values = getActiveSyncInfo();
                return (values == null) ? -1 : values.getAsLong(START_TIME);
            }
        }
    }

    /**
     * Columns in the settings table, which holds key/value pairs of settings.
     */
    public interface SettingsColumns {
        /**
         * The key of the setting
         * <P>Type: TEXT</P>
         */
        public static final String KEY = "name";

        /**
         * The value of the settings
         * <P>Type: TEXT</P>
         */
        public static final String VALUE = "value";
    }

    /**
     * Provides constants and utility methods to access and use the settings
     * table.
     */
    public static final class Settings implements BaseColumns, SettingsColumns {
        /**
         * The Uri of the settings table. This table behaves a little differently than
         * normal tables. Updates are not allowed, only inserts, and inserts cause a replace
         * to be performed, which first deletes the row if it is already present.
         */
        public static final Uri CONTENT_URI = Uri.parse("content://sync/settings");

        /** controls whether or not the device listens for sync tickles */
        public static final String SETTING_LISTEN_FOR_TICKLES = "listen_for_tickles";

        /** controls whether or not the individual provider is synced when tickles are received */
        public static final String SETTING_SYNC_PROVIDER_PREFIX = "sync_provider_";

        /** query column project */
        private static final String[] PROJECTION = { KEY, VALUE };

        /**
         * Convenience function for updating a single settings value as a
         * boolean. This will either create a new entry in the table if the
         * given name does not exist, or modify the value of the existing row
         * with that name.  Note that internally setting values are always
         * stored as strings, so this function converts the given value to a
         * string before storing it.
         *
         * @param contentResolver the ContentResolver to use to access the settings table
         * @param name The name of the setting to modify.
         * @param val The new value for the setting.
         */
        static private void putBoolean(ContentResolver contentResolver, String name, boolean val) {
            ContentValues values = new ContentValues();
            values.put(KEY, name);
            values.put(VALUE, Boolean.toString(val));
            // this insert is translated into an update by the underlying Sync provider
            contentResolver.insert(CONTENT_URI, values);
        }

        /**
         * Convenience function for getting a setting value as a boolean without using the
         * QueryMap for light-weight setting querying.
         * @param contentResolver The ContentResolver for querying the setting.
         * @param name The name of the setting to query
         * @param def The default value for the setting.
         * @return The value of the setting.
         */
        static public boolean getBoolean(ContentResolver contentResolver,
                String name, boolean def) {
            Cursor cursor = contentResolver.query(
                    CONTENT_URI,
                    PROJECTION,
                    KEY + "=?",
                    new String[] { name },
                    null);
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    return Boolean.parseBoolean(cursor.getString(1));
                }
            } finally {
                if (cursor != null) cursor.close();
            }
            return def;
        }

        /**
         * A convenience method to set whether or not the provider is synced when
         * it receives a network tickle.
         *
         * @param contentResolver the ContentResolver to use to access the settings table
         * @param providerName the provider whose behavior is being controlled
         * @param sync true if the provider should be synced when tickles are received for it
         */
        static public void setSyncProviderAutomatically(ContentResolver contentResolver,
                String providerName, boolean sync) {
            putBoolean(contentResolver, SETTING_SYNC_PROVIDER_PREFIX + providerName, sync);
        }

        /**
         * A convenience method to set whether or not the device should listen to tickles.
         *
         * @param contentResolver the ContentResolver to use to access the settings table
         * @param flag true if it should listen.
         */
        static public void setListenForNetworkTickles(ContentResolver contentResolver,
                boolean flag) {
            putBoolean(contentResolver, SETTING_LISTEN_FOR_TICKLES, flag);
        }

        public static class QueryMap extends ContentQueryMap {
            private ContentResolver mContentResolver;

            public QueryMap(ContentResolver contentResolver, boolean keepUpdated,
                    Handler handlerForUpdateNotifications) {
                super(contentResolver.query(CONTENT_URI, null, null, null, null), KEY, keepUpdated,
                        handlerForUpdateNotifications);
                mContentResolver = contentResolver;
            }

            /**
             * Check if the provider should be synced when a network tickle is received
             * @param providerName the provider whose setting we are querying
             * @return true of the provider should be synced when a network tickle is received
             */
            public boolean getSyncProviderAutomatically(String providerName) {
                return getBoolean(SETTING_SYNC_PROVIDER_PREFIX + providerName, true);
            }

            /**
             * Set whether or not the provider is synced when it receives a network tickle.
             *
             * @param providerName the provider whose behavior is being controlled
             * @param sync true if the provider should be synced when tickles are received for it
             */
            public void setSyncProviderAutomatically(String providerName, boolean sync) {
                Settings.setSyncProviderAutomatically(mContentResolver, providerName, sync);
            }

            /**
             * Set whether or not the device should listen for tickles.
             *
             * @param flag true if it should listen.
             */
            public void setListenForNetworkTickles(boolean flag) {
                Settings.setListenForNetworkTickles(mContentResolver, flag);
            }

            /**
             * Check if the device should listen to tickles.

             * @return true if it should
             */
            public boolean getListenForNetworkTickles() {
                return getBoolean(SETTING_LISTEN_FOR_TICKLES, true);
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
        }
    }
}
