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

package android.content;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;

import java.util.Map;

/**
 * A specialization of the ContentProvider that centralizes functionality
 * used by ContentProviders that are syncable. It also wraps calls to the ContentProvider
 * inside of database transactions.
 *
 * @hide
 */
public abstract class SyncableContentProvider extends ContentProvider {
    protected abstract boolean isTemporary();

    /**
     * Close resources that must be closed. You must call this to properly release
     * the resources used by the SyncableContentProvider.
     */
    public abstract void close();

    /**
     * Override to create your schema and do anything else you need to do with a new database.
     * This is run inside a transaction (so you don't need to use one).
     * This method may not use getDatabase(), or call content provider methods, it must only
     * use the database handle passed to it.
     */
    protected abstract void bootstrapDatabase(SQLiteDatabase db);

    /**
     * Override to upgrade your database from an old version to the version you specified.
     * Don't set the DB version, this will automatically be done after the method returns.
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
    protected abstract void onDatabaseOpened(SQLiteDatabase db);

    /**
     * Get a non-persistent instance of this content provider.
     * You must call {@link #close} on the returned
     * SyncableContentProvider when you are done with it.
     *
     * @return a non-persistent content provider with the same layout as this
     * provider.
     */
    public abstract SyncableContentProvider getTemporaryInstance();

    public abstract SQLiteDatabase getDatabase();

    public abstract boolean getContainsDiffs();

    public abstract void setContainsDiffs(boolean containsDiffs);

    /**
     * Each subclass of this class should define a subclass of {@link
     * AbstractTableMerger} for each table they wish to merge.  It
     * should then override this method and return one instance of
     * each merger, in sequence.  Their {@link
     * AbstractTableMerger#merge merge} methods will be called, one at a
     * time, in the order supplied.
     *
     * <p>The default implementation returns an empty list, so that no
     * merging will occur.
     * @return A sequence of subclasses of {@link
     * AbstractTableMerger}, one for each table that should be merged.
     */
    protected abstract Iterable<? extends AbstractTableMerger> getMergers();

    /**
     * Check if changes to this URI can be syncable changes.
     * @param uri the URI of the resource that was changed
     * @return true if changes to this URI can be syncable changes, false otherwise
     */
    public abstract boolean changeRequiresLocalSync(Uri uri);

    /**
     * Called right before a sync is started.
     *
     * @param context the sync context for the operation
     * @param account
     */
    public abstract void onSyncStart(SyncContext context, String account);

    /**
     * Called right after a sync is completed
     *
     * @param context the sync context for the operation
     * @param success true if the sync succeeded, false if an error occurred
     */
    public abstract void onSyncStop(SyncContext context, boolean success);

    /**
     * The account of the most recent call to onSyncStart()
     * @return the account
     */
    public abstract String getSyncingAccount();

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
    public abstract void merge(SyncContext context, SyncableContentProvider diffs,
            TempProviderSyncResult result, SyncResult syncResult);


    /**
     * Invoked when the active sync has been canceled. The default
     * implementation doesn't do anything (except ensure that this
     * provider is syncable). Subclasses of ContentProvider
     * that support canceling of sync should override this.
     */
    public abstract void onSyncCanceled();


    public abstract boolean isMergeCancelled();

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
    protected abstract void onAccountsChanged(String[] accountsArray);

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
    protected abstract void deleteRowsForRemovedAccounts(Map<String, Boolean> accounts,
            String table, String accountColumnName);

    /**
     * Called when the sync system determines that this provider should no longer
     * contain records for the specified account.
     */
    public abstract void wipeAccount(String account);

    /**
     * Retrieves the SyncData bytes for the given account. The byte array returned may be null.
     */
    public abstract byte[] readSyncDataBytes(String account);

    /**
     * Sets the SyncData bytes for the given account. The bytes array may be null.
     */
    public abstract void writeSyncDataBytes(String account, byte[] data);
}

