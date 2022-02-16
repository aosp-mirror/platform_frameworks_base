/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.people.data;

import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.people.ConversationStatus;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Binder;
import android.os.CancellationSignal;

import com.android.server.LocalServices;
import com.android.server.people.PeopleServiceInternal;

/**
 * If a {@link ConversationStatus} is added to the system with an expiration time, remove that
 * status at that time
 */
public class ConversationStatusExpirationBroadcastReceiver extends BroadcastReceiver {

    static final String ACTION = "ConversationStatusExpiration";
    static final String EXTRA_USER_ID = "userId";
    static final int REQUEST_CODE = 10;
    static final String SCHEME = "expStatus";

    void scheduleExpiration(Context context, @UserIdInt int userId, String pkg,
            String conversationId, ConversationStatus status) {
        final long identity = Binder.clearCallingIdentity();
        try {
            final PendingIntent pi = PendingIntent.getBroadcast(context,
                    REQUEST_CODE,
                    new Intent(ACTION)
                            .setData(new Uri.Builder().scheme(SCHEME)
                                    .appendPath(getKey(userId, pkg, conversationId, status))
                                    .build())
                            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                            .putExtra(EXTRA_USER_ID, userId),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            context.getSystemService(AlarmManager.class).setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, status.getEndTimeMillis(), pi);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private static String getKey(@UserIdInt int userId, String pkg,
            String conversationId, ConversationStatus status) {
        return userId + pkg + conversationId + status.getId();
    }

    static IntentFilter getFilter() {
        IntentFilter conversationStatusFilter =
                new IntentFilter(ConversationStatusExpirationBroadcastReceiver.ACTION);
        conversationStatusFilter.addDataScheme(
                ConversationStatusExpirationBroadcastReceiver.SCHEME);
        return conversationStatusFilter;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) {
            return;
        }
        if (ACTION.equals(action)) {
            new Thread(() -> {
                PeopleServiceInternal peopleServiceInternal =
                        LocalServices.getService(PeopleServiceInternal.class);
                peopleServiceInternal.pruneDataForUser(intent.getIntExtra(EXTRA_USER_ID,
                        ActivityManager.getCurrentUser()), new CancellationSignal());
            }).start();
        }
    }
}
