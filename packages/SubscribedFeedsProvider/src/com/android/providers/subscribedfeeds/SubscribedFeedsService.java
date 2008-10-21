/*
** Copyright 2006, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package com.android.providers.subscribedfeeds;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.SubscribedFeeds;
import android.provider.Sync;
import android.provider.SyncConstValue;
import android.text.TextUtils;
import android.util.Config;
import android.util.EventLog;
import android.util.Log;

import java.util.ArrayList;
import java.util.Calendar;

/**
 * Handles the XMPP_CONNECTED_ACTION intent by updating all the
 * subscribed feeds with the new jabber id and initiating a sync
 * for all subscriptions.
 *
 * Handles the TICKLE_ACTION intent by finding the matching
 * subscribed feed and intiating a sync for it.
 */
public class SubscribedFeedsService extends BroadcastReceiver {

    private static final String TAG = "Sync";

    private static final String SUBSCRIBED_FEEDS_REFRESH_ACTION =
            "com.android.subscribedfeeds.action.REFRESH";

    private static final Intent sSubscribedFeedsRefreshIntent =
            new Intent(SUBSCRIBED_FEEDS_REFRESH_ACTION);

    private static final String[] sAccountProjection =
            new String[] {SubscribedFeeds.Accounts._SYNC_ACCOUNT};

    /** How often to refresh the subscriptions, in milliseconds */
    private static final long SUBSCRIPTION_REFRESH_INTERVAL = 1000L * 60 * 60 * 24; // one day

    private static final String sRefreshTime = "refreshTime";

    private static final String sSubscribedFeedsPrefs = "subscribedFeeds";

    static final int LOG_TICKLE = 2742;

    public void onReceive(Context context, Intent intent) {
        if ("android.intent.action.GTALK_DATA_MESSAGE_RECEIVED".equals(
                intent.getAction())) {
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

                handleTickle(context, account, token);
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
            long refreshTime = context.getSharedPreferences(
                    sSubscribedFeedsPrefs,
                    Context.MODE_WORLD_READABLE).getLong(sRefreshTime, 0);
            scheduleRefresh(context, refreshTime);
        } else if (sSubscribedFeedsRefreshIntent.getAction().equals(
                intent.getAction())) {
            if (Config.LOGD) {
                Log.d(TAG, "Received sSubscribedFeedsRefreshIntent");
            }
            handleRefreshAlarm(context);
        }
    }

    private void scheduleRefresh(Context context, long when) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(
                Context.ALARM_SERVICE);
        PendingIntent sender = PendingIntent.getBroadcast(context,
                0, sSubscribedFeedsRefreshIntent, 0);
        alarmManager.set(AlarmManager.RTC, when, sender);
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
        ContentValues values = new ContentValues();
        for (String account : accounts) {
            values.put(SyncConstValue._SYNC_ACCOUNT, account);
            contentResolver.update(SubscribedFeeds.Feeds.CONTENT_URI, values,
                    SubscribedFeeds.Feeds._SYNC_ACCOUNT + "=?", new String[] {account});
        }

        // Schedule a refresh.
        long refreshTime = Calendar.getInstance().getTimeInMillis() + SUBSCRIPTION_REFRESH_INTERVAL;
        scheduleRefresh(context, refreshTime);
        SharedPreferences preferences = context
                .getSharedPreferences(sSubscribedFeedsPrefs,
                        Context.MODE_WORLD_READABLE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putLong(sRefreshTime, refreshTime);
        editor.commit();
    }
}
