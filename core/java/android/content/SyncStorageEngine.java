package android.content;

import android.Manifest;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.provider.Sync;
import android.text.TextUtils;
import android.util.Config;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/**
 * ContentProvider that tracks the sync data and overall sync
 * history on the device.
 * 
 * @hide
 */
public class SyncStorageEngine {
    private static final String TAG = "SyncManager";

    private static final String DATABASE_NAME = "syncmanager.db";
    private static final int DATABASE_VERSION = 10;

    private static final int STATS = 1;
    private static final int STATS_ID = 2;
    private static final int HISTORY = 3;
    private static final int HISTORY_ID = 4;
    private static final int SETTINGS = 5;
    private static final int PENDING = 7;
    private static final int ACTIVE = 8;
    private static final int STATUS = 9;

    private static final UriMatcher sURLMatcher =
            new UriMatcher(UriMatcher.NO_MATCH);

    private static final HashMap<String,String> HISTORY_PROJECTION_MAP;
    private static final HashMap<String,String> PENDING_PROJECTION_MAP;
    private static final HashMap<String,String> ACTIVE_PROJECTION_MAP;
    private static final HashMap<String,String> STATUS_PROJECTION_MAP;

    private final Context mContext;
    private final SQLiteOpenHelper mOpenHelper;
    private static SyncStorageEngine sSyncStorageEngine = null;

    static {
        sURLMatcher.addURI("sync", "stats", STATS);
        sURLMatcher.addURI("sync", "stats/#", STATS_ID);
        sURLMatcher.addURI("sync", "history", HISTORY);
        sURLMatcher.addURI("sync", "history/#", HISTORY_ID);
        sURLMatcher.addURI("sync", "settings", SETTINGS);
        sURLMatcher.addURI("sync", "status", STATUS);
        sURLMatcher.addURI("sync", "active", ACTIVE);
        sURLMatcher.addURI("sync", "pending", PENDING);

        HashMap<String,String> map;
        PENDING_PROJECTION_MAP = map = new HashMap<String,String>();
        map.put(Sync.History._ID, Sync.History._ID);
        map.put(Sync.History.ACCOUNT, Sync.History.ACCOUNT);
        map.put(Sync.History.AUTHORITY, Sync.History.AUTHORITY);

        ACTIVE_PROJECTION_MAP = map = new HashMap<String,String>();
        map.put(Sync.History._ID, Sync.History._ID);
        map.put(Sync.History.ACCOUNT, Sync.History.ACCOUNT);
        map.put(Sync.History.AUTHORITY, Sync.History.AUTHORITY);
        map.put("startTime", "startTime");

        HISTORY_PROJECTION_MAP = map = new HashMap<String,String>();
        map.put(Sync.History._ID, "history._id as _id");
        map.put(Sync.History.ACCOUNT, "stats.account as account");
        map.put(Sync.History.AUTHORITY, "stats.authority as authority");
        map.put(Sync.History.EVENT, Sync.History.EVENT);
        map.put(Sync.History.EVENT_TIME, Sync.History.EVENT_TIME);
        map.put(Sync.History.ELAPSED_TIME, Sync.History.ELAPSED_TIME);
        map.put(Sync.History.SOURCE, Sync.History.SOURCE);
        map.put(Sync.History.UPSTREAM_ACTIVITY, Sync.History.UPSTREAM_ACTIVITY);
        map.put(Sync.History.DOWNSTREAM_ACTIVITY, Sync.History.DOWNSTREAM_ACTIVITY);
        map.put(Sync.History.MESG, Sync.History.MESG);

        STATUS_PROJECTION_MAP = map = new HashMap<String,String>();
        map.put(Sync.Status._ID, "status._id as _id");
        map.put(Sync.Status.ACCOUNT, "stats.account as account");
        map.put(Sync.Status.AUTHORITY, "stats.authority as authority");
        map.put(Sync.Status.TOTAL_ELAPSED_TIME, Sync.Status.TOTAL_ELAPSED_TIME);
        map.put(Sync.Status.NUM_SYNCS, Sync.Status.NUM_SYNCS);
        map.put(Sync.Status.NUM_SOURCE_LOCAL, Sync.Status.NUM_SOURCE_LOCAL);
        map.put(Sync.Status.NUM_SOURCE_POLL, Sync.Status.NUM_SOURCE_POLL);
        map.put(Sync.Status.NUM_SOURCE_SERVER, Sync.Status.NUM_SOURCE_SERVER);
        map.put(Sync.Status.NUM_SOURCE_USER, Sync.Status.NUM_SOURCE_USER);
        map.put(Sync.Status.LAST_SUCCESS_SOURCE, Sync.Status.LAST_SUCCESS_SOURCE);
        map.put(Sync.Status.LAST_SUCCESS_TIME, Sync.Status.LAST_SUCCESS_TIME);
        map.put(Sync.Status.LAST_FAILURE_SOURCE, Sync.Status.LAST_FAILURE_SOURCE);
        map.put(Sync.Status.LAST_FAILURE_TIME, Sync.Status.LAST_FAILURE_TIME);
        map.put(Sync.Status.LAST_FAILURE_MESG, Sync.Status.LAST_FAILURE_MESG);
        map.put(Sync.Status.PENDING, Sync.Status.PENDING);
    }

    private static final String[] STATS_ACCOUNT_PROJECTION =
            new String[] { Sync.Stats.ACCOUNT };

    private static final int MAX_HISTORY_EVENTS_TO_KEEP = 5000;

    private static final String SELECT_INITIAL_FAILURE_TIME_QUERY_STRING = ""
            + "SELECT min(a) "
            + "FROM ("
            + "  SELECT initialFailureTime AS a "
            + "  FROM status "
            + "  WHERE stats_id=? AND a IS NOT NULL "
            + "    UNION "
            + "  SELECT ? AS a"
            + " )";

    private SyncStorageEngine(Context context) {
        mContext = context;
        mOpenHelper = new SyncStorageEngine.DatabaseHelper(context);
        sSyncStorageEngine = this;
    }

    public static SyncStorageEngine newTestInstance(Context context) {
        return new SyncStorageEngine(context);
    }

    public static void init(Context context) {
        if (sSyncStorageEngine != null) {
            throw new IllegalStateException("already initialized");
        }
        sSyncStorageEngine = new SyncStorageEngine(context);
    }

    public static SyncStorageEngine getSingleton() {
        if (sSyncStorageEngine == null) {
            throw new IllegalStateException("not initialized");
        }
        return sSyncStorageEngine;
    }

    private class DatabaseHelper extends SQLiteOpenHelper {
        DatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE pending ("
                    + "_id INTEGER PRIMARY KEY,"
                    + "authority TEXT NOT NULL,"
                    + "account TEXT NOT NULL,"
                    + "extras BLOB NOT NULL,"
                    + "source INTEGER NOT NULL"
                    + ");");

            db.execSQL("CREATE TABLE stats (" +
                       "_id INTEGER PRIMARY KEY," +
                       "account TEXT, " +
                       "authority TEXT, " +
                       "syncdata TEXT, " +
                       "UNIQUE (account, authority)" +
                       ");");

            db.execSQL("CREATE TABLE history (" +
                       "_id INTEGER PRIMARY KEY," +
                       "stats_id INTEGER," +
                       "eventTime INTEGER," +
                       "elapsedTime INTEGER," +
                       "source INTEGER," +
                       "event INTEGER," +
                       "upstreamActivity INTEGER," +
                       "downstreamActivity INTEGER," +
                       "mesg TEXT);");

            db.execSQL("CREATE TABLE status ("
                    + "_id INTEGER PRIMARY KEY,"
                    + "stats_id INTEGER NOT NULL,"
                    + "totalElapsedTime INTEGER NOT NULL DEFAULT 0,"
                    + "numSyncs INTEGER NOT NULL DEFAULT 0,"
                    + "numSourcePoll INTEGER NOT NULL DEFAULT 0,"
                    + "numSourceServer INTEGER NOT NULL DEFAULT 0,"
                    + "numSourceLocal INTEGER NOT NULL DEFAULT 0,"
                    + "numSourceUser INTEGER NOT NULL DEFAULT 0,"
                    + "lastSuccessTime INTEGER,"
                    + "lastSuccessSource INTEGER,"
                    + "lastFailureTime INTEGER,"
                    + "lastFailureSource INTEGER,"
                    + "lastFailureMesg STRING,"
                    + "initialFailureTime INTEGER,"
                    + "pending INTEGER NOT NULL DEFAULT 0);");

            db.execSQL("CREATE TABLE active ("
                    + "_id INTEGER PRIMARY KEY,"
                    + "authority TEXT,"
                    + "account TEXT,"
                    + "startTime INTEGER);");

            db.execSQL("CREATE INDEX historyEventTime ON history (eventTime)");

            db.execSQL("CREATE TABLE settings (" +
                       "name TEXT PRIMARY KEY," +
                       "value TEXT);");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion == 9 && newVersion == 10) {
                Log.w(TAG, "Upgrading database from version " + oldVersion + " to "
                        + newVersion + ", which will preserve old data");
                db.execSQL("ALTER TABLE status ADD COLUMN initialFailureTime INTEGER");
                return;
            }

            Log.w(TAG, "Upgrading database from version " + oldVersion + " to "
                    + newVersion + ", which will destroy all old data");
            db.execSQL("DROP TABLE IF EXISTS pending");
            db.execSQL("DROP TABLE IF EXISTS stats");
            db.execSQL("DROP TABLE IF EXISTS history");
            db.execSQL("DROP TABLE IF EXISTS settings");
            db.execSQL("DROP TABLE IF EXISTS active");
            db.execSQL("DROP TABLE IF EXISTS status");
            onCreate(db);
        }

        @Override
        public void onOpen(SQLiteDatabase db) {
            if (!db.isReadOnly()) {
                db.delete("active", null, null);
                db.insert("active", "account", null);
            }
        }
    }

    protected void doDatabaseCleanup(String[] accounts) {
        HashSet<String> currentAccounts = new HashSet<String>();
        for (String account : accounts) currentAccounts.add(account);
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        Cursor cursor = db.query("stats", STATS_ACCOUNT_PROJECTION,
                null /* where */, null /* where args */, Sync.Stats.ACCOUNT,
                null /* having */, null /* order by */);
        try {
            while (cursor.moveToNext()) {
                String account = cursor.getString(0);
                if (TextUtils.isEmpty(account)) {
                    continue;
                }
                if (!currentAccounts.contains(account)) {
                    String where = Sync.Stats.ACCOUNT + "=?";
                    int numDeleted;
                    numDeleted = db.delete("stats", where, new String[]{account});
                    if (Config.LOGD) {
                        Log.d(TAG, "deleted " + numDeleted
                                + " records from stats table"
                                + " for account " + account);
                    }
                }
            }
        } finally {
            cursor.close();
        }
    }

    protected void setActiveSync(SyncManager.ActiveSyncContext activeSyncContext) {
        if (activeSyncContext != null) {
            updateActiveSync(activeSyncContext.mSyncOperation.account,
                    activeSyncContext.mSyncOperation.authority, activeSyncContext.mStartTime);
        } else {
            // we indicate that the sync is not active by passing null for all the parameters
            updateActiveSync(null, null, null);
        }
    }

    private int updateActiveSync(String account, String authority, Long startTime) {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("account", account);
        values.put("authority", authority);
        values.put("startTime", startTime);
        int numChanges = db.update("active", values, null, null);
        if (numChanges > 0) {
            mContext.getContentResolver().notifyChange(Sync.Active.CONTENT_URI,
                    null /* this change wasn't made through an observer */);
        }
        return numChanges;
    }

    /**
     * Implements the {@link ContentProvider#query} method
     */
    public Cursor query(Uri url, String[] projectionIn,
            String selection, String[] selectionArgs, String sort) {
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();

        // Generate the body of the query
        int match = sURLMatcher.match(url);
        String groupBy = null;
        switch (match) {
            case STATS:
                qb.setTables("stats");
                break;
            case STATS_ID:
                qb.setTables("stats");
                qb.appendWhere("_id=");
                qb.appendWhere(url.getPathSegments().get(1));
                break;
            case HISTORY:
                // join the stats and history tables, so the caller can get
                // the account and authority information as part of this query.
                qb.setTables("stats, history");
                qb.setProjectionMap(HISTORY_PROJECTION_MAP);
                qb.appendWhere("stats._id = history.stats_id");
                break;
            case ACTIVE:
                qb.setTables("active");
                qb.setProjectionMap(ACTIVE_PROJECTION_MAP);
                qb.appendWhere("account is not null");
                break;
            case PENDING:
                qb.setTables("pending");
                qb.setProjectionMap(PENDING_PROJECTION_MAP);
                groupBy = "account, authority";
                break;
            case STATUS:
                // join the stats and status tables, so the caller can get
                // the account and authority information as part of this query.
                qb.setTables("stats, status");
                qb.setProjectionMap(STATUS_PROJECTION_MAP);
                qb.appendWhere("stats._id = status.stats_id");
                break;
            case HISTORY_ID:
                // join the stats and history tables, so the caller can get
                // the account and authority information as part of this query.
                qb.setTables("stats, history");
                qb.setProjectionMap(HISTORY_PROJECTION_MAP);
                qb.appendWhere("stats._id = history.stats_id");
                qb.appendWhere("AND history._id=");
                qb.appendWhere(url.getPathSegments().get(1));
                break;
            case SETTINGS:
                qb.setTables("settings");
                break;
            default:
                throw new IllegalArgumentException("Unknown URL " + url);
        }

        if (match == SETTINGS) {
            mContext.enforceCallingOrSelfPermission(Manifest.permission.READ_SYNC_SETTINGS,
                    "no permission to read the sync settings");
        } else {
            mContext.enforceCallingOrSelfPermission(Manifest.permission.READ_SYNC_STATS,
                    "no permission to read the sync stats");
        }
        SQLiteDatabase db = mOpenHelper.getReadableDatabase();
        Cursor c = qb.query(db, projectionIn, selection, selectionArgs, groupBy, null, sort);
        c.setNotificationUri(mContext.getContentResolver(), url);
        return c;
    }

    /**
     * Implements the {@link ContentProvider#insert} method
     * @param callerIsTheProvider true if this is being called via the
     *  {@link ContentProvider#insert} in method rather than directly.
     * @throws UnsupportedOperationException if callerIsTheProvider is true and the url isn't
     *   for the Settings table.
     */
    public Uri insert(boolean callerIsTheProvider, Uri url, ContentValues values) {
        String table;
        long rowID;
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        final int match = sURLMatcher.match(url);
        checkCaller(callerIsTheProvider, match);
        switch (match) {
            case SETTINGS:
                mContext.enforceCallingOrSelfPermission(Manifest.permission.WRITE_SYNC_SETTINGS,
                        "no permission to write the sync settings");
                table = "settings";
                rowID = db.replace(table, null, values);
                break;
            default:
                throw new IllegalArgumentException("Unknown URL " + url);
        }


        if (rowID > 0) {
            mContext.getContentResolver().notifyChange(url, null /* observer */);
            return Uri.parse("content://sync/" + table + "/" + rowID);
        }

        return null;
    }

    private static void checkCaller(boolean callerIsTheProvider, int match) {
        if (callerIsTheProvider && match != SETTINGS) {
            throw new UnsupportedOperationException(
                    "only the settings are modifiable via the ContentProvider interface");
        }
    }

    /**
     * Implements the {@link ContentProvider#delete} method
     * @param callerIsTheProvider true if this is being called via the
     *  {@link ContentProvider#delete} in method rather than directly.
     * @throws UnsupportedOperationException if callerIsTheProvider is true and the url isn't
     *   for the Settings table.
     */
    public int delete(boolean callerIsTheProvider, Uri url, String where, String[] whereArgs) {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        int match = sURLMatcher.match(url);

        int numRows;
        switch (match) {
            case SETTINGS:
                mContext.enforceCallingOrSelfPermission(Manifest.permission.WRITE_SYNC_SETTINGS,
                        "no permission to write the sync settings");
                numRows = db.delete("settings", where, whereArgs);
                break;
            default:
                throw new UnsupportedOperationException("Cannot delete URL: " + url);
        }

        if (numRows > 0) {
            mContext.getContentResolver().notifyChange(url, null /* observer */);
        }
        return numRows;
    }

    /**
     * Implements the {@link ContentProvider#update} method
     * @param callerIsTheProvider true if this is being called via the
     *  {@link ContentProvider#update} in method rather than directly.
     * @throws UnsupportedOperationException if callerIsTheProvider is true and the url isn't
     *   for the Settings table.
     */
    public int update(boolean callerIsTheProvider, Uri url, ContentValues initialValues,
            String where, String[] whereArgs) {
        switch (sURLMatcher.match(url)) {
            case SETTINGS:
                throw new UnsupportedOperationException("updating url " + url
                        + " is not allowed, use insert instead");
            default:
                throw new UnsupportedOperationException("Cannot update URL: " + url);
        }
    }

    /**
     * Implements the {@link ContentProvider#getType} method
     */
    public String getType(Uri url) {
        int match = sURLMatcher.match(url);
        switch (match) {
            case SETTINGS:
                return "vnd.android.cursor.dir/sync-settings";
            default:
                throw new IllegalArgumentException("Unknown URL");
        }
    }

    protected Uri insertIntoPending(ContentValues values) {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        try {
            db.beginTransaction();
            long rowId = db.insert("pending", Sync.Pending.ACCOUNT, values);
            if (rowId < 0) return null;
            String account = values.getAsString(Sync.Pending.ACCOUNT);
            String authority = values.getAsString(Sync.Pending.AUTHORITY);

            long statsId = createStatsRowIfNecessary(account, authority);
            createStatusRowIfNecessary(statsId);

            values.clear();
            values.put(Sync.Status.PENDING, 1);
            int numUpdatesStatus = db.update("status", values, "stats_id=" + statsId, null);

            db.setTransactionSuccessful();

            mContext.getContentResolver().notifyChange(Sync.Pending.CONTENT_URI,
                    null /* no observer initiated this change */);
            if (numUpdatesStatus > 0) {
                mContext.getContentResolver().notifyChange(Sync.Status.CONTENT_URI,
                        null /* no observer initiated this change */);
            }
            return ContentUris.withAppendedId(Sync.Pending.CONTENT_URI, rowId);
        } finally {
            db.endTransaction();
        }
    }

    int deleteFromPending(long rowId) {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            String account;
            String authority;
            Cursor c = db.query("pending",
                    new String[]{Sync.Pending.ACCOUNT, Sync.Pending.AUTHORITY},
                    "_id=" + rowId, null, null, null, null);
            try {
                if (c.getCount() != 1) {
                    return 0;
                }
                c.moveToNext();
                account = c.getString(0);
                authority = c.getString(1);
            } finally {
                c.close();
            }
            db.delete("pending", "_id=" + rowId, null /* no where args */);
            final String[] accountAuthorityWhereArgs = new String[]{account, authority};
            boolean isPending = 0 < DatabaseUtils.longForQuery(db,
                    "SELECT COUNT(*) FROM PENDING WHERE account=? AND authority=?",
                    accountAuthorityWhereArgs);
            if (!isPending) {
                long statsId = createStatsRowIfNecessary(account, authority);
                db.execSQL("UPDATE status SET pending=0 WHERE stats_id=" + statsId);
            }
            db.setTransactionSuccessful();

            mContext.getContentResolver().notifyChange(Sync.Pending.CONTENT_URI,
                    null /* no observer initiated this change */);
            if (!isPending) {
                mContext.getContentResolver().notifyChange(Sync.Status.CONTENT_URI,
                        null /* no observer initiated this change */);
            }
            return 1;
        } finally {
            db.endTransaction();
        }
    }

    int clearPending() {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            int numChanges = db.delete("pending", null, null /* no where args */);
            if (numChanges > 0) {
                db.execSQL("UPDATE status SET pending=0");
                mContext.getContentResolver().notifyChange(Sync.Pending.CONTENT_URI,
                        null /* no observer initiated this change */);
                mContext.getContentResolver().notifyChange(Sync.Status.CONTENT_URI,
                        null /* no observer initiated this change */);
            }
            db.setTransactionSuccessful();
            return numChanges;
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Returns a cursor over all the pending syncs in no particular order. This cursor is not
     * "live", in that if changes are made to the pending table any observers on this cursor
     * will not be notified.
     * @param projection Return only these columns. If null then all columns are returned.
     * @return the cursor of pending syncs
     */
    public Cursor getPendingSyncsCursor(String[] projection) {
        SQLiteDatabase db = mOpenHelper.getReadableDatabase();
        return db.query("pending", projection, null, null, null, null, null);
    }

    // @VisibleForTesting
    static final long MILLIS_IN_4WEEKS = 1000L * 60 * 60 * 24 * 7 * 4;

    private boolean purgeOldHistoryEvents(long now) {
        // remove events that are older than MILLIS_IN_4WEEKS
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        int numDeletes = db.delete("history", "eventTime<" + (now - MILLIS_IN_4WEEKS), null);
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            if (numDeletes > 0) {
                Log.v(TAG, "deleted " + numDeletes + " old event(s) from the sync history");
            }
        }

        // keep only the last MAX_HISTORY_EVENTS_TO_KEEP history events
        numDeletes += db.delete("history", "eventTime < (select min(eventTime) from "
                + "(select eventTime from history order by eventTime desc limit ?))",
                new String[]{String.valueOf(MAX_HISTORY_EVENTS_TO_KEEP)});
        
        return numDeletes > 0;
    }

    public long insertStartSyncEvent(String account, String authority, long now, int source) {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        long statsId = createStatsRowIfNecessary(account, authority);

        purgeOldHistoryEvents(now);
        ContentValues values = new ContentValues();
        values.put(Sync.History.STATS_ID, statsId);
        values.put(Sync.History.EVENT_TIME, now);
        values.put(Sync.History.SOURCE, source);
        values.put(Sync.History.EVENT, Sync.History.EVENT_START);
        long rowId = db.insert("history", null, values);
        mContext.getContentResolver().notifyChange(Sync.History.CONTENT_URI, null /* observer */);
        mContext.getContentResolver().notifyChange(Sync.Status.CONTENT_URI, null /* observer */);
        return rowId;
    }

    public void stopSyncEvent(long historyId, long elapsedTime, String resultMessage,
            long downstreamActivity, long upstreamActivity) {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put(Sync.History.ELAPSED_TIME, elapsedTime);
            values.put(Sync.History.EVENT, Sync.History.EVENT_STOP);
            values.put(Sync.History.MESG, resultMessage);
            values.put(Sync.History.DOWNSTREAM_ACTIVITY, downstreamActivity);
            values.put(Sync.History.UPSTREAM_ACTIVITY, upstreamActivity);

            int count = db.update("history", values, "_id=?",
                    new String[]{Long.toString(historyId)});
            // We think that count should always be 1 but don't want to change this until after
            // launch.
            if (count > 0) {
                int source = (int) DatabaseUtils.longForQuery(db,
                        "SELECT source FROM history WHERE _id=" + historyId, null);
                long eventTime = DatabaseUtils.longForQuery(db,
                        "SELECT eventTime FROM history WHERE _id=" + historyId, null);
                long statsId = DatabaseUtils.longForQuery(db,
                        "SELECT stats_id FROM history WHERE _id=" + historyId, null);

                createStatusRowIfNecessary(statsId);

                // update the status table to reflect this sync
                StringBuilder sb = new StringBuilder();
                ArrayList<String> bindArgs = new ArrayList<String>();
                sb.append("UPDATE status SET");
                sb.append(" numSyncs=numSyncs+1");
                sb.append(", totalElapsedTime=totalElapsedTime+" + elapsedTime);
                switch (source) {
                    case Sync.History.SOURCE_LOCAL:
                        sb.append(", numSourceLocal=numSourceLocal+1");
                        break;
                    case Sync.History.SOURCE_POLL:
                        sb.append(", numSourcePoll=numSourcePoll+1");
                        break;
                    case Sync.History.SOURCE_USER:
                        sb.append(", numSourceUser=numSourceUser+1");
                        break;
                    case Sync.History.SOURCE_SERVER:
                        sb.append(", numSourceServer=numSourceServer+1");
                        break;
                }

                final String statsIdString = String.valueOf(statsId);
                final long lastSyncTime = (eventTime + elapsedTime);
                if (Sync.History.MESG_SUCCESS.equals(resultMessage)) {
                    // - if successful, update the successful columns
                    sb.append(", lastSuccessTime=" + lastSyncTime);
                    sb.append(", lastSuccessSource=" + source);
                    sb.append(", lastFailureTime=null");
                    sb.append(", lastFailureSource=null");
                    sb.append(", lastFailureMesg=null");
                    sb.append(", initialFailureTime=null");
                } else if (!Sync.History.MESG_CANCELED.equals(resultMessage)) {
                    sb.append(", lastFailureTime=" + lastSyncTime);
                    sb.append(", lastFailureSource=" + source);
                    sb.append(", lastFailureMesg=?");
                    bindArgs.add(resultMessage);
                    long initialFailureTime = DatabaseUtils.longForQuery(db,
                            SELECT_INITIAL_FAILURE_TIME_QUERY_STRING, 
                            new String[]{statsIdString, String.valueOf(lastSyncTime)});
                    sb.append(", initialFailureTime=" + initialFailureTime);
                }
                sb.append(" WHERE stats_id=?");
                bindArgs.add(statsIdString);
                db.execSQL(sb.toString(), bindArgs.toArray());
                db.setTransactionSuccessful();
                mContext.getContentResolver().notifyChange(Sync.History.CONTENT_URI,
                        null /* observer */);
                mContext.getContentResolver().notifyChange(Sync.Status.CONTENT_URI,
                        null /* observer */);
            }
        } finally {
            db.endTransaction();
        }
    }

    /**
     * If sync is failing for any of the provider/accounts then determine the time at which it
     * started failing and return the earliest time over all the provider/accounts. If none are
     * failing then return 0.
     */
    public long getInitialSyncFailureTime() {
        SQLiteDatabase db = mOpenHelper.getReadableDatabase();
        // Join the settings for a provider with the status so that we can easily
        // check if each provider is enabled for syncing. We also join in the overall
        // enabled flag ("listen_for_tickles") to each row so that we don't need to
        // make a separate DB lookup to access it.
        Cursor c = db.rawQuery(""
                + "SELECT initialFailureTime, s1.value, s2.value "
                + "FROM status "
                + "LEFT JOIN stats ON status.stats_id=stats._id "
                + "LEFT JOIN settings as s1 ON 'sync_provider_' || authority=s1.name "
                + "LEFT JOIN settings as s2 ON s2.name='listen_for_tickles' "
                + "where initialFailureTime is not null "
                + "  AND lastFailureMesg!=" + Sync.History.ERROR_TOO_MANY_DELETIONS
                + "  AND lastFailureMesg!=" + Sync.History.ERROR_AUTHENTICATION
                + "  AND lastFailureMesg!=" + Sync.History.ERROR_SYNC_ALREADY_IN_PROGRESS
                + "  AND authority!='subscribedfeeds' "
                + " ORDER BY initialFailureTime", null);
        try {
            while (c.moveToNext()) {
                // these settings default to true, so if they are null treat them as enabled
                final String providerEnabledString = c.getString(1);
                if (providerEnabledString != null && !Boolean.parseBoolean(providerEnabledString)) {
                    continue;
                }
                final String allEnabledString = c.getString(2);
                if (allEnabledString != null && !Boolean.parseBoolean(allEnabledString)) {
                    continue;
                }
                return c.getLong(0);
            }
        } finally {
            c.close();
        }
        return 0;
    }

    private void createStatusRowIfNecessary(long statsId) {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        boolean statusExists = 0 != DatabaseUtils.longForQuery(db,
                "SELECT count(*) FROM status WHERE stats_id=" + statsId, null);
        if (!statusExists) {
            ContentValues values = new ContentValues();
            values.put("stats_id", statsId);
            db.insert("status", null, values);
        }
    }

    private long createStatsRowIfNecessary(String account, String authority) {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        StringBuilder where = new StringBuilder();
        where.append(Sync.Stats.ACCOUNT + "= ?");
        where.append(" and " + Sync.Stats.AUTHORITY + "= ?");
        Cursor cursor = query(Sync.Stats.CONTENT_URI,
                Sync.Stats.SYNC_STATS_PROJECTION,
                where.toString(), new String[] { account, authority },
                null /* order */);
        try {
            long id;
            if (cursor.moveToFirst()) {
                id = cursor.getLong(cursor.getColumnIndexOrThrow(Sync.Stats._ID));
            } else {
                ContentValues values = new ContentValues();
                values.put(Sync.Stats.ACCOUNT, account);
                values.put(Sync.Stats.AUTHORITY, authority);
                id = db.insert("stats", null, values);
            }
            return id;
        } finally {
            cursor.close();
        }
    }
}
