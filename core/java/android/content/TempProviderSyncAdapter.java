package android.content;

import android.database.SQLException;
import android.os.Bundle;
import android.os.Debug;
import android.os.NetStat;
import android.os.Parcelable;
import android.os.Process;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Config;
import android.util.EventLog;
import android.util.Log;
import android.util.TimingLogger;

/**
 * @hide
 */
public abstract class TempProviderSyncAdapter extends SyncAdapter {
    private static final String TAG = "Sync";

    private static final int MAX_GET_SERVER_DIFFS_LOOP_COUNT = 20;
    private static final int MAX_UPLOAD_CHANGES_LOOP_COUNT = 10;
    private static final int NUM_ALLOWED_SIMULTANEOUS_DELETIONS = 5;
    private static final long PERCENT_ALLOWED_SIMULTANEOUS_DELETIONS = 20;

    private volatile SyncableContentProvider mProvider;
    private volatile SyncThread mSyncThread = null;
    private volatile boolean mProviderSyncStarted;
    private volatile boolean mAdapterSyncStarted;
    
    public TempProviderSyncAdapter(SyncableContentProvider provider) {
        super();
        mProvider = provider;
    }

    /**
     * Used by getServerDiffs() to track the sync progress for a given
     * sync adapter. Implementations of SyncAdapter generally specialize
     * this class in order to track specific data about that SyncAdapter's
     * sync. If an implementation of SyncAdapter doesn't need to store
     * any data for a sync it may use TrivialSyncData.
     */
    public static abstract class SyncData implements Parcelable {

    }

    public final void setContext(Context context) {
        mContext = context;
    }

    /**
     * Retrieve the Context this adapter is running in.  Only available
     * once onSyncStarting() is called (not available from constructor).
     */
    final public Context getContext() {
        return mContext;
    }

    /**
     * Called right before a sync is started.
     *
     * @param context allows you to publish status and interact with the
     * @param account the account to sync
     * @param forced if true then the sync was forced
     * @param result information to track what happened during this sync attempt
     * @return true, if the sync was successfully started. One reason it can
     *   fail to start is if there is no user configured on the device.
     */
    public abstract void onSyncStarting(SyncContext context, String account, boolean forced,
            SyncResult result);

    /**
     * Called right after a sync is completed
     *
     * @param context allows you to publish status and interact with the
     *                user during interactive syncs.
     * @param success true if the sync suceeded, false if an error occured
     */
    public abstract void onSyncEnding(SyncContext context, boolean success);

    /**
     * Implement this to return true if the data in your content provider
     * is read only.
     */
    public abstract boolean isReadOnly();

    /**
     * Get diffs from the server since the last completed sync and put them
     * into a temporary provider.
     *
     * @param context allows you to publish status and interact with the
     *                user during interactive syncs.
     * @param syncData used to track the progress this client has made in syncing data
     *   from the server
     * @param tempProvider this is where the diffs should be stored
     * @param extras any extra data describing the sync that is desired
     * @param syncInfo sync adapter-specific data that is used during a single sync operation
     * @param syncResult information to track what happened during this sync attempt
     */
    public abstract void getServerDiffs(SyncContext context,
            SyncData syncData, SyncableContentProvider tempProvider,
            Bundle extras, Object syncInfo, SyncResult syncResult);

    /**
     * Send client diffs to the server, optionally receiving more diffs from the server
     *
     * @param context allows you to publish status and interact with the
     *                user during interactive syncs.
     * @param clientDiffs the diffs from the client
     * @param serverDiffs the SyncableContentProvider that should be populated with
*   the entries that were returned in response to an insert/update/delete request
*   to the server
     * @param syncResult information to track what happened during this sync attempt
     * @param dontActuallySendDeletes
     */
    public abstract void sendClientDiffs(SyncContext context,
            SyncableContentProvider clientDiffs,
            SyncableContentProvider serverDiffs, SyncResult syncResult,
            boolean dontActuallySendDeletes);

    /**
     * Reads the sync data from the ContentProvider
     * @param contentProvider the ContentProvider to read from
     * @return the SyncData for the provider. This may be null.
     */
    public SyncData readSyncData(SyncableContentProvider contentProvider) {
        return null;
    }

    /**
     * Create and return a new, empty SyncData object
     */
    public SyncData newSyncData() {
        return null;
    }

    /**
     * Stores the sync data in the Sync Stats database, keying it by
     * the account that was set in the last call to onSyncStarting()
     */
    public void writeSyncData(SyncData syncData, SyncableContentProvider contentProvider) {}

    /**
     * Indicate to the SyncAdapter that the last sync that was started has
     * been cancelled.
     */
    public abstract void onSyncCanceled();

    /**
     * Initializes the temporary content providers used during
     * {@link TempProviderSyncAdapter#sendClientDiffs}.
     * May copy relevant data from the underlying db into this provider so
     * joins, etc., can work.
     *
     * @param cp The ContentProvider to initialize.
     */
    protected void initTempProvider(SyncableContentProvider cp) {}

    protected Object createSyncInfo() {
        return null;
    }

    /**
     * Called when the accounts list possibly changed, to give the
     * SyncAdapter a chance to do any necessary bookkeeping, e.g.
     * to make sure that any required SubscribedFeeds subscriptions
     * exist.
     * @param accounts the list of accounts
     */
    public abstract void onAccountsChanged(String[] accounts);

    private Context mContext;

    private class SyncThread extends Thread {
        private final String mAccount;
        private final Bundle mExtras;
        private final SyncContext mSyncContext;
        private volatile boolean mIsCanceled = false;
        private long mInitialTxBytes;
        private long mInitialRxBytes;
        private final SyncResult mResult;

        SyncThread(SyncContext syncContext, String account, Bundle extras) {
            super("SyncThread");
            mAccount = account;
            mExtras = extras;
            mSyncContext = syncContext;
            mResult = new SyncResult();
        }

        void cancelSync() {
            mIsCanceled = true;
            if (mAdapterSyncStarted) onSyncCanceled();
            if (mProviderSyncStarted) mProvider.onSyncCanceled();
            // We may lose the last few sync events when canceling.  Oh well.
            int uid = Process.myUid();
            logSyncDetails(NetStat.getUidTxBytes(uid) - mInitialTxBytes,
                    NetStat.getUidRxBytes(uid) - mInitialRxBytes, mResult);
        }
        
        @Override
        public void run() {
            Process.setThreadPriority(Process.myTid(),
                    Process.THREAD_PRIORITY_BACKGROUND);
            int uid = Process.myUid();
            mInitialTxBytes = NetStat.getUidTxBytes(uid);
            mInitialRxBytes = NetStat.getUidRxBytes(uid);
            try {
                sync(mSyncContext, mAccount, mExtras);
            } catch (SQLException e) {
                Log.e(TAG, "Sync failed", e);
                mResult.databaseError = true;
            } finally {
                mSyncThread = null;
                if (!mIsCanceled) {
                    logSyncDetails(NetStat.getUidTxBytes(uid) - mInitialTxBytes,
                    NetStat.getUidRxBytes(uid) - mInitialRxBytes, mResult);
                    mSyncContext.onFinished(mResult);
                }
            }
        }

        private void sync(SyncContext syncContext, String account, Bundle extras) {
            mIsCanceled = false;

            mProviderSyncStarted = false;
            mAdapterSyncStarted = false;
            String message = null;

            boolean syncForced = extras.getBoolean(ContentResolver.SYNC_EXTRAS_FORCE, false);

            try {
                mProvider.onSyncStart(syncContext, account);
                mProviderSyncStarted = true;
                onSyncStarting(syncContext, account, syncForced, mResult);
                if (mResult.hasError()) {
                    message = "SyncAdapter failed while trying to start sync";
                    return;
                }
                mAdapterSyncStarted = true;
                if (mIsCanceled) {
                    return;
                }
                final String syncTracingEnabledValue = SystemProperties.get(TAG + "Tracing");
                final boolean syncTracingEnabled = !TextUtils.isEmpty(syncTracingEnabledValue);
                try {
                    if (syncTracingEnabled) {
                        System.gc();
                        System.gc();
                        Debug.startMethodTracing("synctrace." + System.currentTimeMillis());
                    }
                    runSyncLoop(syncContext, account, extras);
                } finally {
                    if (syncTracingEnabled) Debug.stopMethodTracing();
                }
                onSyncEnding(syncContext, !mResult.hasError());
                mAdapterSyncStarted = false;
                mProvider.onSyncStop(syncContext, true);
                mProviderSyncStarted = false;
            } finally {
                if (mAdapterSyncStarted) {
                    mAdapterSyncStarted = false;
                    onSyncEnding(syncContext, false);
                }
                if (mProviderSyncStarted) {
                    mProviderSyncStarted = false;
                    mProvider.onSyncStop(syncContext, false);
                }
                if (!mIsCanceled) {
                    if (message != null) syncContext.setStatusText(message);
                }
            }
        }

        private void runSyncLoop(SyncContext syncContext, String account, Bundle extras) {
            TimingLogger syncTimer = new TimingLogger(TAG + "Profiling", "sync");
            syncTimer.addSplit("start");
            int loopCount = 0;
            boolean tooManyGetServerDiffsAttempts = false;

            final boolean overrideTooManyDeletions =
                    extras.getBoolean(ContentResolver.SYNC_EXTRAS_OVERRIDE_TOO_MANY_DELETIONS,
                            false);
            final boolean discardLocalDeletions =
                    extras.getBoolean(ContentResolver.SYNC_EXTRAS_DISCARD_LOCAL_DELETIONS, false);
            boolean uploadOnly = extras.getBoolean(ContentResolver.SYNC_EXTRAS_UPLOAD,
                    false /* default this flag to false */);
            SyncableContentProvider serverDiffs = null;
            TempProviderSyncResult result = new TempProviderSyncResult();
            try {
                if (!uploadOnly) {
                    /**
                     * This loop repeatedly calls SyncAdapter.getServerDiffs()
                     * (to get changes from the feed) followed by
                     * ContentProvider.merge() (to incorporate these changes
                     * into the provider), stopping when the SyncData returned
                     * from getServerDiffs() indicates that all the data was
                     * fetched.
                     */
                    while (!mIsCanceled) {
                        // Don't let a bad sync go forever
                        if (loopCount++ == MAX_GET_SERVER_DIFFS_LOOP_COUNT) {
                            Log.e(TAG, "runSyncLoop: Hit max loop count while getting server diffs "
                                    + getClass().getName());
                            // TODO: change the structure here to schedule a new sync
                            // with a backoff time, keeping track to be sure
                            // we don't keep doing this forever (due to some bug or
                            // mismatch between the client and the server)
                            tooManyGetServerDiffsAttempts = true;
                            break;
                        }

                        // Get an empty content provider to put the diffs into
                        if (serverDiffs != null) serverDiffs.close();
                        serverDiffs = mProvider.getTemporaryInstance();

                        // Get records from the server which will be put into the serverDiffs
                        initTempProvider(serverDiffs);
                        Object syncInfo = createSyncInfo();
                        SyncData syncData = readSyncData(serverDiffs);
                        // syncData will only be null if there was a demarshalling error
                        // while reading the sync data.
                        if (syncData == null) {
                            mProvider.wipeAccount(account);
                            syncData = newSyncData();
                        }
                        mResult.clear();
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.v(TAG, "runSyncLoop: running getServerDiffs using syncData "
                                    + syncData.toString());
                        }
                        getServerDiffs(syncContext, syncData, serverDiffs, extras, syncInfo,
                                mResult);

                        if (mIsCanceled) return;
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.v(TAG, "runSyncLoop: result: " + mResult);
                        }
                        if (mResult.hasError()) return;
                        if (mResult.partialSyncUnavailable) {
                            if (Config.LOGD) {
                                Log.d(TAG, "partialSyncUnavailable is set, setting "
                                        + "ignoreSyncData and retrying");
                            }
                            mProvider.wipeAccount(account);
                            continue;
                        }

                        // write the updated syncData back into the temp provider
                        writeSyncData(syncData, serverDiffs);

                        // apply the downloaded changes to the provider
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.v(TAG, "runSyncLoop: running merge");
                        }
                        mProvider.merge(syncContext, serverDiffs,
                                null /* don't return client diffs */, mResult);
                        if (mIsCanceled) return;
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.v(TAG, "runSyncLoop: result: " + mResult);
                        }

                        // if the server has no more changes then break out of the loop
                        if (!mResult.moreRecordsToGet) {
                            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                                Log.v(TAG, "runSyncLoop: fetched all data, moving on");
                            }
                            break;
                        }
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.v(TAG, "runSyncLoop: more data to fetch, looping");
                        }
                    }
                }

                /**
                 * This loop repeatedly calls ContentProvider.merge() followed
                 * by SyncAdapter.merge() until either indicate that there is
                 * no more work to do by returning null.
                 * <p>
                 * The initial ContentProvider.merge() returns a temporary
                 * ContentProvider that contains any local changes that need
                 * to be committed to the server.
                 * <p>
                 * The SyncAdapter.merge() calls upload the changes to the server
                 * and populates temporary provider (the serverDiffs) with the
                 * result.
                 * <p>
                 * Subsequent calls to ContentProvider.merge() incoporate the
                 * result of previous SyncAdapter.merge() calls into the
                 * real ContentProvider and again return a temporary
                 * ContentProvider that contains any local changes that need
                 * to be committed to the server.
                 */
                loopCount = 0;
                boolean readOnly = isReadOnly();
                long previousNumModifications = 0;
                if (serverDiffs != null) {
                    serverDiffs.close();
                    serverDiffs = null;
                }

                // If we are discarding local deletions then we need to redownload all the items
                // again (since some of them might have been deleted). We do this by deleting the
                // sync data for the current account by writing in a null one.
                if (discardLocalDeletions) {
                    serverDiffs = mProvider.getTemporaryInstance();
                    initTempProvider(serverDiffs);
                    writeSyncData(null, serverDiffs);
                }

                while (!mIsCanceled) {
                    if (Config.LOGV) {
                        Log.v(TAG, "runSyncLoop: Merging diffs from server to client");
                    }
                    if (result.tempContentProvider != null) {
                        result.tempContentProvider.close();
                        result.tempContentProvider = null;
                    }
                    mResult.clear();
                    mProvider.merge(syncContext, serverDiffs, readOnly ? null : result,
                            mResult);
                    if (mIsCanceled) return;
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "runSyncLoop: result: " + mResult);
                    }

                    SyncableContentProvider clientDiffs =
                            readOnly ? null : result.tempContentProvider;
                    if (clientDiffs == null) {
                        // Nothing to commit back to the server
                        if (Config.LOGV) Log.v(TAG, "runSyncLoop: No client diffs");
                        break;
                    }

                    long numModifications = mResult.stats.numUpdates
                            + mResult.stats.numDeletes
                            + mResult.stats.numInserts;

                    // as long as we are making progress keep resetting the loop count
                    if (numModifications < previousNumModifications) {
                        loopCount = 0;
                    }
                    previousNumModifications = numModifications;

                    // Don't let a bad sync go forever
                    if (loopCount++ >= MAX_UPLOAD_CHANGES_LOOP_COUNT) {
                        Log.e(TAG, "runSyncLoop: Hit max loop count while syncing "
                                + getClass().getName());
                        mResult.tooManyRetries = true;
                        break;
                    }

                    if (!overrideTooManyDeletions && !discardLocalDeletions
                            && hasTooManyDeletions(mResult.stats)) {
                        if (Config.LOGD) {
                            Log.d(TAG, "runSyncLoop: Too many deletions were found in provider "
                                    + getClass().getName() + ", not doing any more updates");
                        }
                        long numDeletes = mResult.stats.numDeletes;
                        mResult.stats.clear();
                        mResult.tooManyDeletions = true;
                        mResult.stats.numDeletes = numDeletes;
                        break;
                    }

                    if (Config.LOGV) Log.v(TAG, "runSyncLoop: Merging diffs from client to server");
                    if (serverDiffs != null) serverDiffs.close();
                    serverDiffs = clientDiffs.getTemporaryInstance();
                    initTempProvider(serverDiffs);
                    mResult.clear();
                    sendClientDiffs(syncContext, clientDiffs, serverDiffs, mResult,
                            discardLocalDeletions);
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "runSyncLoop: result: " + mResult);
                    }

                    if (!mResult.madeSomeProgress()) {
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.v(TAG, "runSyncLoop: No data from client diffs merge");
                        }
                        break;
                    }
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "runSyncLoop: made some progress, looping");
                    }
                }

                // add in any status codes that we saved from earlier
                mResult.tooManyRetries |= tooManyGetServerDiffsAttempts;
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "runSyncLoop: final result: " + mResult);
                }
            } finally {
                // do this in the finally block to guarantee that is is set and not overwritten
                if (discardLocalDeletions) {
                    mResult.fullSyncRequested = true;
                }
                if (serverDiffs != null) serverDiffs.close();
                if (result.tempContentProvider != null) result.tempContentProvider.close();
                syncTimer.addSplit("stop");
                syncTimer.dumpToLog();
            }
        }
    }

    /**
     * Logs details on the sync.
     * Normally this will be overridden by a subclass that will provide
     * provider-specific details.
     * 
     * @param bytesSent number of bytes the sync sent over the network
     * @param bytesReceived number of bytes the sync received over the network
     * @param result The SyncResult object holding info on the sync
     */
    protected void logSyncDetails(long bytesSent, long bytesReceived, SyncResult result) {
        EventLog.writeEvent(SyncAdapter.LOG_SYNC_DETAILS, TAG, bytesSent, bytesReceived, "");
    }

    public void startSync(SyncContext syncContext, String account, Bundle extras) {
        if (mSyncThread != null) {
            syncContext.onFinished(SyncResult.ALREADY_IN_PROGRESS);
            return;
        }

        mSyncThread = new SyncThread(syncContext, account, extras);
        mSyncThread.start();
    }

    public void cancelSync() {
        if (mSyncThread != null) {
            mSyncThread.cancelSync();
        }
    }

    protected boolean hasTooManyDeletions(SyncStats stats) {
        long numEntries = stats.numEntries;
        long numDeletedEntries = stats.numDeletes;

        long percentDeleted = (numDeletedEntries == 0)
                ? 0
                : (100 * numDeletedEntries /
                        (numEntries + numDeletedEntries));
        boolean tooManyDeletions =
                (numDeletedEntries > NUM_ALLOWED_SIMULTANEOUS_DELETIONS)
                && (percentDeleted > PERCENT_ALLOWED_SIMULTANEOUS_DELETIONS);
        return tooManyDeletions;
    }
}
