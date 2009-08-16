package com.android.providers.subscribedfeeds;

import android.content.Intent;
import android.content.Context;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.util.Log;
import android.util.Config;
import android.util.EventLog;
import android.app.IntentService;
import android.provider.SubscribedFeeds;
import android.provider.SyncConstValue;
import android.database.Cursor;
import android.database.sqlite.SQLiteFullException;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.os.Bundle;
import android.text.TextUtils;
import android.accounts.Account;

import java.util.ArrayList;
import java.util.Calendar;

import com.google.android.collect.Lists;

/**
 * A service to handle various intents asynchronously.
 */
public class SubscribedFeedsIntentService extends IntentService {
    private static final String TAG = "Sync";

    private static final String[] sAccountProjection =
            new String[] {SubscribedFeeds.Accounts._SYNC_ACCOUNT,
                    SubscribedFeeds.Accounts._SYNC_ACCOUNT_TYPE};

    /** How often to refresh the subscriptions, in milliseconds */
    private static final long SUBSCRIPTION_REFRESH_INTERVAL = 1000L * 60 * 60 * 24; // one day

    private static final String sRefreshTime = "refreshTime";

    private static final String sSubscribedFeedsPrefs = "subscribedFeeds";

    private static final String REMOTE_INTENT_ACTION = Intent.ACTION_REMOTE_INTENT;

    private static final String SUBSCRIBED_FEEDS_REFRESH_ACTION =
            "com.android.subscribedfeeds.action.REFRESH";

    private static final int LOG_TICKLE = 2742;

    public SubscribedFeedsIntentService() {
        super("SubscribedFeedsIntentService");
    }

    protected void onHandleIntent(Intent intent) {
        if (REMOTE_INTENT_ACTION.equals(intent.getAction())) {
            boolean fromTrustedServer = intent.getBooleanExtra(
                    "android.intent.extra.from_trusted_server", false);
            if (fromTrustedServer) {
                String accountName = intent.getStringExtra("account");
                String token = intent.getStringExtra(Intent.EXTRA_REMOTE_INTENT_TOKEN);

                if (TextUtils.isEmpty(accountName) || TextUtils.isEmpty(token)) {
                    if (Config.LOGD) {
                        Log.d(TAG, "Ignoring malformed tickle -- missing account or token.");
                    }
                    return;
                }

                if (Config.LOGD) {
                    Log.d(TAG, "Received network tickle for "
                            + accountName + " - " + token);
                }

                handleTickle(this, accountName, token);
            } else {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "Ignoring tickle -- not from trusted server.");
                }
            }

        } else if (Intent.ACTION_BOOT_COMPLETED.equals(
                intent.getAction())) {
            if (Config.LOGD) {
                Log.d(TAG, "Received boot completed action");
            }
            // load the time from the shared preferences and schedule an alarm
            long refreshTime = getSharedPreferences(
                    sSubscribedFeedsPrefs,
                    Context.MODE_WORLD_READABLE).getLong(sRefreshTime, 0);
            scheduleRefresh(this, refreshTime);
        } else if (SUBSCRIBED_FEEDS_REFRESH_ACTION.equals(intent.getAction())) {
            if (Config.LOGD) {
                Log.d(TAG, "Received sSubscribedFeedsRefreshIntent");
            }
            handleRefreshAlarm(this);
        }
    }
    private void scheduleRefresh(Context context, long when) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(
                Context.ALARM_SERVICE);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context,
                0, new Intent(SUBSCRIBED_FEEDS_REFRESH_ACTION), 0);
        alarmManager.set(AlarmManager.RTC, when, pendingIntent);
    }

    private void handleTickle(Context context, String accountName, String feed) {
        Cursor c = null;
        final String where = SubscribedFeeds.Feeds._SYNC_ACCOUNT + "= ? "
                + "and " + SubscribedFeeds.Feeds._SYNC_ACCOUNT_TYPE + "= ? "
                + "and " + SubscribedFeeds.Feeds.FEED + "= ?";
        try {
            // TODO(fredq) fix the hardcoded type
            final Account account = new Account(accountName, "com.google.GAIA");
            c = context.getContentResolver().query(SubscribedFeeds.Feeds.CONTENT_URI,
                    null, where, new String[]{account.name, account.type, feed}, null);
            if (c.getCount() == 0) {
                Log.w(TAG, "received tickle for non-existent feed: "
                        + "account " + accountName + ", feed " + feed);
                EventLog.writeEvent(LOG_TICKLE, "unknown");
            }
            while (c.moveToNext()) {
                // initiate a sync
                String authority = c.getString(c.getColumnIndexOrThrow(
                        SubscribedFeeds.Feeds.AUTHORITY));
                EventLog.writeEvent(LOG_TICKLE, authority);
                if (!ContentResolver.getSyncAutomatically(account, authority)) {
                    Log.d(TAG, "supressing tickle since provider " + authority
                            + " is configured to not sync automatically");
                    continue;
                }
                Bundle extras = new Bundle();
                extras.putString("feed", feed);
                ContentResolver.requestSync(account, authority, extras);
            }
        } finally {
            if (c != null) c.deactivate();
        }
    }

    /**
     * Cause all the subscribed feeds to be marked dirty and their
     * authtokens to be refreshed, which will result in new authtokens
     * being sent to the subscription server. Then reschedules this
     * event for one week in the future.
     *
     * @param context Context we are running within
     */
    private void handleRefreshAlarm(Context context) {
        // retrieve the list of accounts from the subscribed feeds
        ArrayList<Account> accounts = Lists.newArrayList();
        ContentResolver contentResolver = context.getContentResolver();
        Cursor c = contentResolver.query(SubscribedFeeds.Accounts.CONTENT_URI,
                sAccountProjection, null, null, null);
        try {
            while (c.moveToNext()) {
                String accountName = c.getString(0);
                String accountType = c.getString(1);
                accounts.add(new Account(accountName, accountType));
            }
        } finally {
            c.close();
        }

        // Clear the auth tokens for all these accounts so that we are sure
        // they will still be valid until the next time we refresh them.
        // TODO(fredq): add this when the google login service is done

        // mark the feeds dirty, by setting the accounts to the same value,
        //  which will trigger a sync.
        try {
            ContentValues values = new ContentValues();
            for (Account account : accounts) {
                values.put(SyncConstValue._SYNC_ACCOUNT, account.name);
                values.put(SyncConstValue._SYNC_ACCOUNT_TYPE, account.type);
                contentResolver.update(SubscribedFeeds.Feeds.CONTENT_URI, values,
                        SubscribedFeeds.Feeds._SYNC_ACCOUNT + "=? AND "
                                + SubscribedFeeds.Feeds._SYNC_ACCOUNT_TYPE + "=?",
                        new String[] {account.name, account.type});
            }
        } catch (SQLiteFullException e) {
            Log.w(TAG, "disk full while trying to mark the feeds as dirty, skipping");
        }

        // Schedule a refresh.
        long refreshTime = Calendar.getInstance().getTimeInMillis() + SUBSCRIPTION_REFRESH_INTERVAL;
        scheduleRefresh(context, refreshTime);
        SharedPreferences.Editor editor = context.getSharedPreferences(sSubscribedFeedsPrefs,
                Context.MODE_WORLD_READABLE).edit();
        editor.putLong(sRefreshTime, refreshTime);
        editor.commit();
    }
}
