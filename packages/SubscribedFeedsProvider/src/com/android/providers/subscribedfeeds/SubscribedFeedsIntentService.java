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
import android.provider.Sync;
import android.provider.SubscribedFeeds;
import android.provider.SyncConstValue;
import android.database.Cursor;
import android.database.sqlite.SQLiteFullException;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.os.Bundle;
import android.os.Debug;
import android.text.TextUtils;
import android.net.Uri;

import java.util.ArrayList;
import java.util.Calendar;

/**
 * A service to handle various intents asynchronously.
 */
public class SubscribedFeedsIntentService extends IntentService {
    private static final String TAG = "Sync";

    private static final String[] sAccountProjection =
            new String[] {SubscribedFeeds.Accounts._SYNC_ACCOUNT};

    /** How often to refresh the subscriptions, in milliseconds */
    private static final long SUBSCRIPTION_REFRESH_INTERVAL = 1000L * 60 * 60 * 24; // one day

    private static final String sRefreshTime = "refreshTime";

    private static final String sSubscribedFeedsPrefs = "subscribedFeeds";

    private static final String GTALK_DATA_MESSAGE_RECEIVED =
            "android.intent.action.GTALK_DATA_MESSAGE_RECEIVED";

    private static final String SUBSCRIBED_FEEDS_REFRESH_ACTION =
            "com.android.subscribedfeeds.action.REFRESH";

    private static final int LOG_TICKLE = 2742;

    public SubscribedFeedsIntentService() {
        super("SubscribedFeedsIntentService");
    }

    protected void onHandleIntent(Intent intent) {
        if (GTALK_DATA_MESSAGE_RECEIVED.equals(intent.getAction())) {
            boolean fromTrustedServer = intent.getBooleanExtra("from_trusted_server", false);
            if (fromTrustedServer) {
                String account = intent.getStringExtra("account");
                String token = intent.getStringExtra("message_token");

                if (TextUtils.isEmpty(account) || TextUtils.isEmpty(token)) {
                    if (Config.LOGD) {
                        Log.d(TAG, "Ignoring malformed tickle -- missing account or token.");
                    }
                    return;
                }

                if (Config.LOGD) {
                    Log.d(TAG, "Received network tickle for "
                            + account + " - " + token);
                }

                handleTickle(this, account, token);
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

    private void handleTickle(Context context, String account, String feed) {
        Cursor c = null;
        Sync.Settings.QueryMap syncSettings =
                new Sync.Settings.QueryMap(context.getContentResolver(),
                        false /* don't keep updated */,
                        null /* not needed since keep updated is false */);
        final String where = SubscribedFeeds.Feeds._SYNC_ACCOUNT + "= ? "
                + "and " + SubscribedFeeds.Feeds.FEED + "= ?";
        try {
            c = context.getContentResolver().query(SubscribedFeeds.Feeds.CONTENT_URI,
                    null, where, new String[]{account, feed}, null);
            if (c.getCount() == 0) {
                Log.w(TAG, "received tickle for non-existent feed: "
                        + "account " + account + ", feed " + feed);
                EventLog.writeEvent(LOG_TICKLE, "unknown");
            }
            while (c.moveToNext()) {
                // initiate a sync
                String authority = c.getString(c.getColumnIndexOrThrow(
                        SubscribedFeeds.Feeds.AUTHORITY));
                EventLog.writeEvent(LOG_TICKLE, authority);
                if (!syncSettings.getSyncProviderAutomatically(authority)) {
                    Log.d(TAG, "supressing tickle since provider " + authority
                            + " is configured to not sync automatically");
                    continue;
                }
                Uri uri = Uri.parse("content://" + authority);
                Bundle extras = new Bundle();
                extras.putString(ContentResolver.SYNC_EXTRAS_ACCOUNT, account);
                extras.putString("feed", feed);
                context.getContentResolver().startSync(uri, extras);
            }
        } finally {
            if (c != null) c.deactivate();
            syncSettings.close();
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
        ArrayList<String> accounts = new ArrayList<String>();
        ContentResolver contentResolver = context.getContentResolver();
        Cursor c = contentResolver.query(SubscribedFeeds.Accounts.CONTENT_URI,
                sAccountProjection, null, null, null);
        while (c.moveToNext()) {
            String account = c.getString(0);
            if (TextUtils.isEmpty(account)) {
                continue;
            }
            accounts.add(account);
        }
        c.deactivate();

        // Clear the auth tokens for all these accounts so that we are sure
        // they will still be valid until the next time we refresh them.
        // TODO: add this when the google login service is done

        // mark the feeds dirty, by setting the accounts to the same value,
        //  which will trigger a sync.
        try {
            ContentValues values = new ContentValues();
            for (String account : accounts) {
                values.put(SyncConstValue._SYNC_ACCOUNT, account);
                contentResolver.update(SubscribedFeeds.Feeds.CONTENT_URI, values,
                        SubscribedFeeds.Feeds._SYNC_ACCOUNT + "=?", new String[] {account});
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
