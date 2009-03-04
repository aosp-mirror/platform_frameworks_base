package android.content;

import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteDatabase;
import android.database.Cursor;
import android.net.Uri;
import android.accounts.AccountMonitor;
import android.accounts.AccountMonitorListener;
import android.provider.SyncConstValue;
import android.util.Config;
import android.util.Log;
import android.os.Bundle;
import android.text.TextUtils;

import java.util.Collections;
import java.util.Map;
import java.util.HashMap;
import java.util.Vector;
import java.util.ArrayList;

/**
 * A specialization of the ContentProvider that centralizes functionality
 * used by ContentProviders that are syncable. It also wraps calls to the ContentProvider
 * inside of database transactions.
 *
 * @hide
 */
public abstract class AbstractSyncableContentProvider extends SyncableContentProvider {
    private static final String TAG = "SyncableContentProvider";
    protected SQLiteOpenHelper mOpenHelper;
    protected SQLiteDatabase mDb;
    private final String mDatabaseName;
    private final int mDatabaseVersion;
    private final Uri mContentUri;
    private AccountMonitor mAccountMonitor;

    /** the account set in the last call to onSyncStart() */
    private String mSyncingAccount;

    private SyncStateContentProviderHelper mSyncState = null;

    private static final String[] sAccountProjection = new String[] {SyncConstValue._SYNC_ACCOUNT};

    private boolean mIsTemporary;

    private AbstractTableMerger mCurrentMerger = null;
    private boolean mIsMergeCancelled = false;

    private static final String SYNC_ACCOUNT_WHERE_CLAUSE = SyncConstValue._SYNC_ACCOUNT + "=?";

    protected boolean isTemporary() {
        return mIsTemporary;
    }

    /**
     * Indicates whether or not this ContentProvider contains a full
     * set of data or just diffs. This knowledge comes in handy when
     * determining how to incorporate the contents of a temporary
     * provider into a real provider.
     */
    private boolean mContainsDiffs;

    /**
     * Initializes the AbstractSyncableContentProvider
     * @param dbName the filename of the database
     * @param dbVersion the current version of the database schema
     * @param contentUri The base Uri of the syncable content in this provider
     */
    public AbstractSyncableContentProvider(String dbName, int dbVersion, Uri contentUri) {
        super();

        mDatabaseName = dbName;
        mDatabaseVersion = dbVersion;
        mContentUri = contentUri;
        mIsTemporary = false;
        setContainsDiffs(false);
        if (Config.LOGV) {
            Log.v(TAG, "created SyncableContentProvider " + this);
        }
    }

    /**
     * Close resources that must be closed. You must call this to properly release
     * the resources used by the AbstractSyncableContentProvider.
     */
    public void close() {
        if (mOpenHelper != null) {
            mOpenHelper.close();  // OK to call .close() repeatedly.
        }
    }

    /**
     * Override to create your schema and do anything else you need to do with a new database.
     * This is run inside a transaction (so you don't need to use one).
     * This method may not use getDatabase(), or call content provider methods, it must only
     * use the database handle passed to it.
     */
    protected void bootstrapDatabase(SQLiteDatabase db) {}

    /**
     * Override to upgrade your database from an old version to the version you specified.
     * Don't set the DB version; this will automatically be done after the method returns.
     * This method may not use getDatabase(), or call content provider methods, it must only
     * use the database handle passed to it.
     *
     * @param oldVersion version of the existing database
     * @param newVersion current version to upgrade to
     * @return true if the upgrade was lossless, false if it was lossy
     */
    protected abstract boolean upgradeDatabase(SQLiteDatabase db, int oldVersion, int newVersion);

    /**
     * Override to do anything (like cleanups or checks) you need to do after opening a database.
     * Does nothing by default.  This is run inside a transaction (so you don't need to use one).
     * This method may not use getDatabase(), or call content provider methods, it must only
     * use the database handle passed to it.
     */
    protected void onDatabaseOpened(SQLiteDatabase db) {}

    private class DatabaseHelper extends SQLiteOpenHelper {
        DatabaseHelper(Context context, String name) {
            // Note: context and name may be null for temp providers
            super(context, name, null, mDatabaseVersion);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            bootstrapDatabase(db);
            mSyncState.createDatabase(db);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (!upgradeDatabase(db, oldVersion, newVersion)) {
                mSyncState.discardSyncData(db, null /* all accounts */);
                getContext().getContentResolver().startSync(mContentUri, new Bundle());
            }
        }

        @Override
        public void onOpen(SQLiteDatabase db) {
            onDatabaseOpened(db);
            mSyncState.onDatabaseOpened(db);
        }
    }

    @Override
    public boolean onCreate() {
        if (isTemporary()) throw new IllegalStateException("onCreate() called for temp provider");
        mOpenHelper = new AbstractSyncableContentProvider.DatabaseHelper(getContext(), mDatabaseName);
        mSyncState = new SyncStateContentProviderHelper(mOpenHelper);

        AccountMonitorListener listener = new AccountMonitorListener() {
            public void onAccountsUpdated(String[] accounts) {
                // Some providers override onAccountsChanged(); give them a database to work with.
                mDb = mOpenHelper.getWritableDatabase();
                onAccountsChanged(accounts);
                TempProviderSyncAdapter syncAdapter = (TempProviderSyncAdapter)getSyncAdapter();
                if (syncAdapter != null) {
                    syncAdapter.onAccountsChanged(accounts);
                }
            }
        };
        mAccountMonitor = new AccountMonitor(getContext(), listener);

        return true;
    }

    /**
     * Get a non-persistent instance of this content provider.
     * You must call {@link #close} on the returned
     * SyncableContentProvider when you are done with it.
     *
     * @return a non-persistent content provider with the same layout as this
     * provider.
     */
    public AbstractSyncableContentProvider getTemporaryInstance() {
        AbstractSyncableContentProvider temp;
        try {
            temp = getClass().newInstance();
        } catch (InstantiationException e) {
            throw new RuntimeException("unable to instantiate class, "
                    + "this should never happen", e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(
                    "IllegalAccess while instantiating class, "
                            + "this should never happen", e);
        }

        // Note: onCreate() isn't run for the temp provider, and it has no Context.
        temp.mIsTemporary = true;
        temp.setContainsDiffs(true);
        temp.mOpenHelper = temp.new DatabaseHelper(null, null);
        temp.mSyncState = new SyncStateContentProviderHelper(temp.mOpenHelper);
        if (!isTemporary()) {
            mSyncState.copySyncState(
                    mOpenHelper.getReadableDatabase(),
                    temp.mOpenHelper.getWritableDatabase(),
                    getSyncingAccount());
        }
        return temp;
    }

    public SQLiteDatabase getDatabase() {
       if (mDb == null) mDb = mOpenHelper.getWritableDatabase();
       return mDb;
    }

    public boolean getContainsDiffs() {
        return mContainsDiffs;
    }

    public void setContainsDiffs(boolean containsDiffs) {
        if (containsDiffs && !isTemporary()) {
            throw new IllegalStateException(
                    "only a temporary provider can contain diffs");
        }
        mContainsDiffs = containsDiffs;
    }

    /**
     * Each subclass of this class should define a subclass of {@link
     * android.content.AbstractTableMerger} for each table they wish to merge.  It
     * should then override this method and return one instance of
     * each merger, in sequence.  Their {@link
     * android.content.AbstractTableMerger#merge merge} methods will be called, one at a
     * time, in the order supplied.
     *
     * <p>The default implementation returns an empty list, so that no
     * merging will occur.
     * @return A sequence of subclasses of {@link
     * android.content.AbstractTableMerger}, one for each table that should be merged.
     */
    protected Iterable<? extends AbstractTableMerger> getMergers() {
        return Collections.emptyList();
    }

    @Override
    public final int update(final Uri url, final ContentValues values,
            final String selection, final String[] selectionArgs) {
        mDb = mOpenHelper.getWritableDatabase();
        mDb.beginTransaction();
        try {
            if (isTemporary() && mSyncState.matches(url)) {
                int numRows = mSyncState.asContentProvider().update(
                        url, values, selection, selectionArgs);
                mDb.setTransactionSuccessful();
                return numRows;
            }

            int result = updateInternal(url, values, selection, selectionArgs);
            mDb.setTransactionSuccessful();

            if (!isTemporary() && result > 0) {
                getContext().getContentResolver().notifyChange(url, null /* observer */,
                        changeRequiresLocalSync(url));
            }

            return result;
        } finally {
            mDb.endTransaction();
        }
    }

    @Override
    public final int delete(final Uri url, final String selection,
            final String[] selectionArgs) {
        mDb = mOpenHelper.getWritableDatabase();
        mDb.beginTransaction();
        try {
            if (isTemporary() && mSyncState.matches(url)) {
                int numRows = mSyncState.asContentProvider().delete(url, selection, selectionArgs);
                mDb.setTransactionSuccessful();
                return numRows;
            }
            int result = deleteInternal(url, selection, selectionArgs);
            mDb.setTransactionSuccessful();
            if (!isTemporary() && result > 0) {
                getContext().getContentResolver().notifyChange(url, null /* observer */,
                        changeRequiresLocalSync(url));
            }
            return result;
        } finally {
            mDb.endTransaction();
        }
    }

    @Override
    public final Uri insert(final Uri url, final ContentValues values) {
        mDb = mOpenHelper.getWritableDatabase();
        mDb.beginTransaction();
        try {
            if (isTemporary() && mSyncState.matches(url)) {
                Uri result = mSyncState.asContentProvider().insert(url, values);
                mDb.setTransactionSuccessful();
                return result;
            }
            Uri result = insertInternal(url, values);
            mDb.setTransactionSuccessful();
            if (!isTemporary() && result != null) {
                getContext().getContentResolver().notifyChange(url, null /* observer */,
                        changeRequiresLocalSync(url));
            }
            return result;
        } finally {
            mDb.endTransaction();
        }
    }

    @Override
    public final int bulkInsert(final Uri uri, final ContentValues[] values) {
        int size = values.length;
        int completed = 0;
        final boolean isSyncStateUri = mSyncState.matches(uri);
        mDb = mOpenHelper.getWritableDatabase();
        mDb.beginTransaction();
        try {
            for (int i = 0; i < size; i++) {
                Uri result;
                if (isTemporary() && isSyncStateUri) {
                    result = mSyncState.asContentProvider().insert(uri, values[i]);
                } else {
                    result = insertInternal(uri, values[i]);
                    mDb.yieldIfContended();
                }
                if (result != null) {
                    completed++;
                }
            }
            mDb.setTransactionSuccessful();
        } finally {
            mDb.endTransaction();
        }
        if (!isTemporary() && completed == size) {
            getContext().getContentResolver().notifyChange(uri, null /* observer */,
                    changeRequiresLocalSync(uri));
        }
        return completed;
    }

    /**
     * Check if changes to this URI can be syncable changes.
     * @param uri the URI of the resource that was changed
     * @return true if changes to this URI can be syncable changes, false otherwise
     */
    public boolean changeRequiresLocalSync(Uri uri) {
        return true;
    }

    @Override
    public final Cursor query(final Uri url, final String[] projection,
            final String selection, final String[] selectionArgs,
            final String sortOrder) {
        mDb = mOpenHelper.getReadableDatabase();
        if (isTemporary() && mSyncState.matches(url)) {
            return mSyncState.asContentProvider().query(
                    url, projection, selection,  selectionArgs, sortOrder);
        }
        return queryInternal(url, projection, selection, selectionArgs, sortOrder);
    }

    /**
     * Called right before a sync is started.
     *
     * @param context the sync context for the operation
     * @param account
     */
    public void onSyncStart(SyncContext context, String account) {
        if (TextUtils.isEmpty(account)) {
            throw new IllegalArgumentException("you passed in an empty account");
        }
        mSyncingAccount = account;
    }

    /**
     * Called right after a sync is completed
     *
     * @param context the sync context for the operation
     * @param success true if the sync succeeded, false if an error occurred
     */
    public void onSyncStop(SyncContext context, boolean success) {
    }

    /**
     * The account of the most recent call to onSyncStart()
     * @return the account
     */
    public String getSyncingAccount() {
        return mSyncingAccount;
    }

    /**
     * Merge diffs from a sync source with this content provider.
     *
     * @param context the SyncContext within which this merge is taking place
     * @param diffs A temporary content provider containing diffs from a sync
     *   source.
     * @param result a MergeResult that contains information about the merge, including
     *   a temporary content provider with the same layout as this provider containing
     * @param syncResult
     */
    public void merge(SyncContext context, SyncableContentProvider diffs,
            TempProviderSyncResult result, SyncResult syncResult) {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            synchronized(this) {
                mIsMergeCancelled = false;
            }
            Iterable<? extends AbstractTableMerger> mergers = getMergers();
            try {
                for (AbstractTableMerger merger : mergers) {
                    synchronized(this) {
                        if (mIsMergeCancelled) break;
                        mCurrentMerger = merger;
                    }
                    merger.merge(context, getSyncingAccount(), diffs, result, syncResult, this);
                }
                if (mIsMergeCancelled) return;
                if (diffs != null) {
                    mSyncState.copySyncState(
                        ((AbstractSyncableContentProvider)diffs).mOpenHelper.getReadableDatabase(),
                        mOpenHelper.getWritableDatabase(),
                        getSyncingAccount());
                }
            } finally {
                synchronized (this) {
                    mCurrentMerger = null;
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }


    /**
     * Invoked when the active sync has been canceled. Sets the sync state of this provider and
     * its merger to canceled.
     */
    public void onSyncCanceled() {
        synchronized (this) {
            mIsMergeCancelled = true;
            if (mCurrentMerger != null) {
                mCurrentMerger.onMergeCancelled();
            }
        }
    }


    public boolean isMergeCancelled() {
        return mIsMergeCancelled;
    }

    /**
     * Subclasses should override this instead of update(). See update()
     * for details.
     *
     * <p> This method is called within a acquireDbLock()/releaseDbLock() block,
     * which means a database transaction will be active during the call;
     */
    protected abstract int updateInternal(Uri url, ContentValues values,
            String selection, String[] selectionArgs);

    /**
     * Subclasses should override this instead of delete(). See delete()
     * for details.
     *
     * <p> This method is called within a acquireDbLock()/releaseDbLock() block,
     * which means a database transaction will be active during the call;
     */
    protected abstract int deleteInternal(Uri url, String selection, String[] selectionArgs);

    /**
     * Subclasses should override this instead of insert(). See insert()
     * for details.
     *
     * <p> This method is called within a acquireDbLock()/releaseDbLock() block,
     * which means a database transaction will be active during the call;
     */
    protected abstract Uri insertInternal(Uri url, ContentValues values);

    /**
     * Subclasses should override this instead of query(). See query()
     * for details.
     *
     * <p> This method is *not* called within a acquireDbLock()/releaseDbLock()
     * block for performance reasons. If an implementation needs atomic access
     * to the database the lock can be acquired then.
     */
    protected abstract Cursor queryInternal(Uri url, String[] projection,
            String selection, String[] selectionArgs, String sortOrder);

    /**
     * Make sure that there are no entries for accounts that no longer exist
     * @param accountsArray the array of currently-existing accounts
     */
    protected void onAccountsChanged(String[] accountsArray) {
        Map<String, Boolean> accounts = new HashMap<String, Boolean>();
        for (String account : accountsArray) {
            accounts.put(account, false);
        }
        accounts.put(SyncConstValue.NON_SYNCABLE_ACCOUNT, false);

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        Map<String, String> tableMap = db.getSyncedTables();
        Vector<String> tables = new Vector<String>();
        tables.addAll(tableMap.keySet());
        tables.addAll(tableMap.values());

        db.beginTransaction();
        try {
            mSyncState.onAccountsChanged(accountsArray);
            for (String table : tables) {
                deleteRowsForRemovedAccounts(accounts, table,
                        SyncConstValue._SYNC_ACCOUNT);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * A helper method to delete all rows whose account is not in the accounts
     * map. The accountColumnName is the name of the column that is expected
     * to hold the account. If a row has an empty account it is never deleted.
     *
     * @param accounts a map of existing accounts
     * @param table the table to delete from
     * @param accountColumnName the name of the column that is expected
     * to hold the account.
     */
    protected void deleteRowsForRemovedAccounts(Map<String, Boolean> accounts,
            String table, String accountColumnName) {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        Cursor c = db.query(table, sAccountProjection, null, null,
                accountColumnName, null, null);
        try {
            while (c.moveToNext()) {
                String account = c.getString(0);
                if (TextUtils.isEmpty(account)) {
                    continue;
                }
                if (!accounts.containsKey(account)) {
                    int numDeleted;
                    numDeleted = db.delete(table, accountColumnName + "=?", new String[]{account});
                    if (Config.LOGV) {
                        Log.v(TAG, "deleted " + numDeleted
                                + " records from table " + table
                                + " for account " + account);
                    }
                }
            }
        } finally {
            c.close();
        }
    }

    /**
     * Called when the sync system determines that this provider should no longer
     * contain records for the specified account.
     */
    public void wipeAccount(String account) {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        Map<String, String> tableMap = db.getSyncedTables();
        ArrayList<String> tables = new ArrayList<String>();
        tables.addAll(tableMap.keySet());
        tables.addAll(tableMap.values());

        db.beginTransaction();

        try {
            // remove the SyncState data
            mSyncState.discardSyncData(db, account);

            // remove the data in the synced tables
            for (String table : tables) {
                db.delete(table, SYNC_ACCOUNT_WHERE_CLAUSE, new String[]{account});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Retrieves the SyncData bytes for the given account. The byte array returned may be null.
     */
    public byte[] readSyncDataBytes(String account) {
        return mSyncState.readSyncDataBytes(mOpenHelper.getReadableDatabase(), account);
    }

    /**
     * Sets the SyncData bytes for the given account. The byte array may be null.
     */
    public void writeSyncDataBytes(String account, byte[] data) {
        mSyncState.writeSyncDataBytes(mOpenHelper.getWritableDatabase(), account, data);
    }
}
