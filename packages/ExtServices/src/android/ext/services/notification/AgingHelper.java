/**
 * Copyright (C) 2018 The Android Open Source Project
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
package android.ext.services.notification;

import static android.app.NotificationManager.IMPORTANCE_MIN;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.ext.services.notification.NotificationCategorizer.Category;
import android.net.Uri;
import android.util.ArraySet;
import android.util.Slog;

import java.util.Set;

public class AgingHelper {
    private final static String TAG = "AgingHelper";
    private final boolean DEBUG = false;

    private static final String AGING_ACTION = AgingHelper.class.getSimpleName() + ".EVALUATE";
    private static final int REQUEST_CODE_AGING = 1;
    private static final String AGING_SCHEME = "aging";
    private static final String EXTRA_KEY = "key";
    private static final String EXTRA_CATEGORY = "category";

    private static final int HOUR_MS = 1000 * 60 * 60;
    private static final int TWO_HOURS_MS = 2 * HOUR_MS;

    private Context mContext;
    private NotificationCategorizer mNotificationCategorizer;
    private AlarmManager mAm;
    private Callback mCallback;

    // The set of keys we've scheduled alarms for
    private Set<String> mAging = new ArraySet<>();

    public AgingHelper(Context context, NotificationCategorizer categorizer, Callback callback) {
        mNotificationCategorizer = categorizer;
        mContext = context;
        mAm = mContext.getSystemService(AlarmManager.class);
        mCallback = callback;

        IntentFilter filter = new IntentFilter(AGING_ACTION);
        filter.addDataScheme(AGING_SCHEME);
        mContext.registerReceiver(mBroadcastReceiver, filter);
    }

    // NAS lifecycle methods

    public void onNotificationSeen(NotificationEntry entry) {
        // user has strong opinions about this notification. we can't down rank it, so don't bother.
        if (entry.getChannel().hasUserSetImportance()) {
            return;
        }

        @Category int category = mNotificationCategorizer.getCategory(entry);

        // already very low
        if (category == NotificationCategorizer.CATEGORY_MIN) {
            return;
        }

        if (entry.hasSeen()) {
            if (category == NotificationCategorizer.CATEGORY_ONGOING
                    || category > NotificationCategorizer.CATEGORY_REMINDER) {
                scheduleAging(entry.getSbn().getKey(), category, TWO_HOURS_MS);
            } else {
                scheduleAging(entry.getSbn().getKey(), category, HOUR_MS);
            }

            mAging.add(entry.getSbn().getKey());
        }
    }

    public void onNotificationPosted(NotificationEntry entry) {
        cancelAging(entry.getSbn().getKey());
    }

    public void onNotificationRemoved(String key) {
        cancelAging(key);
    }

    public void onDestroy() {
        mContext.unregisterReceiver(mBroadcastReceiver);
    }

    // Aging

    private void scheduleAging(String key, @Category int category, long duration) {
        if (mAging.contains(key)) {
            // already scheduled. Don't reset aging just because the user saw the noti again.
            return;
        }
        final PendingIntent pi = createPendingIntent(key, category);
        long time = System.currentTimeMillis() + duration;
        if (DEBUG) Slog.d(TAG, "Scheduling evaluate for " + key + " in ms: " + duration);
        mAm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pi);
    }

    private void cancelAging(String key) {
        final PendingIntent pi = createPendingIntent(key);
        mAm.cancel(pi);
        mAging.remove(key);
    }

    private Intent createBaseIntent(String key) {
        return new Intent(AGING_ACTION)
                .setData(new Uri.Builder().scheme(AGING_SCHEME).appendPath(key).build());
    }

    private Intent createAgingIntent(String key, @Category int category) {
        Intent intent = createBaseIntent(key);
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra(EXTRA_CATEGORY, category)
                .putExtra(EXTRA_KEY, key);
        return intent;
    }

    private PendingIntent createPendingIntent(String key, @Category int category) {
        return PendingIntent.getBroadcast(mContext,
                REQUEST_CODE_AGING,
                createAgingIntent(key, category),
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent createPendingIntent(String key) {
        return PendingIntent.getBroadcast(mContext,
                REQUEST_CODE_AGING,
                createBaseIntent(key),
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void demote(String key, @Category int category) {
        int newImportance = IMPORTANCE_MIN;
        // TODO: Change "aged" importance based on category
        mCallback.sendAdjustment(key, newImportance);
    }

    protected interface Callback {
        void sendAdjustment(String key, int newImportance);
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) {
                Slog.d(TAG, "Reposting notification");
            }
            if (AGING_ACTION.equals(intent.getAction())) {
                demote(intent.getStringExtra(EXTRA_KEY), intent.getIntExtra(EXTRA_CATEGORY,
                        NotificationCategorizer.CATEGORY_EVERYTHING_ELSE));
            }
        }
    };
}
