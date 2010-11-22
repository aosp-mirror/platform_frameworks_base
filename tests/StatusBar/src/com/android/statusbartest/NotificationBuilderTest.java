/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.statusbartest;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Environment;
import android.os.Vibrator;
import android.os.Handler;
import android.util.Log;
import android.net.Uri;
import android.os.SystemClock;
import android.widget.RemoteViews;
import android.os.PowerManager;

public class NotificationBuilderTest extends TestActivity
{
    private final static String TAG = "NotificationTestList";

    NotificationManager mNM;

    @Override
    protected String tag() {
        return TAG;
    }

    @Override
    protected Test[] tests() {
        mNM = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        
        return mTests;
    }

    private Test[] mTests = new Test[] {
        new Test("Cancel (1)") {
            public void run() {
                mNM.cancel(1);
            }
        },

        new Test("Basic Content (1)") {
            public void run() {
                int id = 1;
                final Notification.Builder b = makeBasicBuilder(this, id);

                mNM.notify(id, b.getNotification());
            }
        },

        new Test("Content w/ Info (1)") {
            public void run() {
                int id = 1;
                final Notification.Builder b = makeBasicBuilder(this, id);

                b.setContentInfo("Snoozed");

                mNM.notify(id, b.getNotification());
            }
        },

        new Test("w/ Number (1)") {
            public void run() {
                int id = 1;
                final Notification.Builder b = makeBasicBuilder(this, id);

                b.setNumber(12345);

                mNM.notify(id, b.getNotification());
            }
        },

        new Test("w/ Number and Large Icon (1)") {
            public void run() {
                int id = 1;
                final Notification.Builder b = makeBasicBuilder(this, id);

                b.setNumber(42);

                final BitmapDrawable bd = (BitmapDrawable)getResources().getDrawable(
                        R.drawable.pineapple);
                b.setLargeIcon(Bitmap.createBitmap(bd.getBitmap()));

                mNM.notify(id, b.getNotification());
            }
        },
    };

    private Notification.Builder makeBasicBuilder(Test t, int id) {
        final Notification.Builder b = new Notification.Builder(this);

        b.setWhen(System.currentTimeMillis());
        b.setSmallIcon(R.drawable.ic_statusbar_chat);
        b.setContentTitle("Notification builder Test");
        b.setContentText(t.name + "\nhappy notifying");
        b.setContentIntent(makeContentIntent(id));
        b.setDeleteIntent(makeDeleteIntent(id));

        return b;
    }

    private PendingIntent makeContentIntent(int id) {
        Intent intent = new Intent(this, ConfirmationActivity.class);
        intent.setData(Uri.fromParts("content", "//status_bar_test/content/" + id, null));
        intent.putExtra(ConfirmationActivity.EXTRA_TITLE, "Content intent");
        intent.putExtra(ConfirmationActivity.EXTRA_TEXT, "id: " + id);
        return PendingIntent.getActivity(this, 0, intent, 0);
    }

    private PendingIntent makeDeleteIntent(int id) {
        Intent intent = new Intent(this, ConfirmationActivity.class);
        intent.setData(Uri.fromParts("content", "//status_bar_test/delete/" + id, null));
        intent.putExtra(ConfirmationActivity.EXTRA_TITLE, "Delete intent");
        intent.putExtra(ConfirmationActivity.EXTRA_TEXT, "id: " + id);
        return PendingIntent.getActivity(this, 0, intent, 0);
    }
}

