/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.app;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcel;
import android.os.SystemClock;
import android.provider.ContactsContract;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.Suppress;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.lang.InterruptedException;
import java.lang.NoSuchMethodError;
import java.lang.NoSuchMethodException;
import java.util.ArrayList;

import com.android.frameworks.tests.notification.R;

public class NotificationTests extends AndroidTestCase {
    private static final String TAG = "NOTEST";
    public static void L(String msg, Object... args) {
        Log.v(TAG, (args == null || args.length == 0) ? msg : String.format(msg, args));
    }

    public static final String ACTION_CREATE = "create";
    public static final int NOTIFICATION_ID = 31338;

    public static final boolean SHOW_PHONE_CALL = false;
    public static final boolean SHOW_INBOX = true;
    public static final boolean SHOW_BIG_TEXT = true;
    public static final boolean SHOW_BIG_PICTURE = true;
    public static final boolean SHOW_MEDIA = true;
    public static final boolean SHOW_STOPWATCH = false;
    public static final boolean SHOW_SOCIAL = false;
    public static final boolean SHOW_CALENDAR = false;
    public static final boolean SHOW_PROGRESS = false;

    private static Bitmap getBitmap(Context context, int resId) {
        int largeIconWidth = (int) context.getResources()
                .getDimension(R.dimen.notification_large_icon_width);
        int largeIconHeight = (int) context.getResources()
                .getDimension(R.dimen.notification_large_icon_height);
        Drawable d = context.getResources().getDrawable(resId);
        Bitmap b = Bitmap.createBitmap(largeIconWidth, largeIconHeight, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, largeIconWidth, largeIconHeight);
        d.draw(c);
        return b;
    }

    private static PendingIntent makeEmailIntent(Context context, String who) {
        final Intent intent = new Intent(android.content.Intent.ACTION_SENDTO,
                Uri.parse("mailto:" + who));
        return PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_CANCEL_CURRENT);
    }

    static final String[] LINES = new String[] {
            "Uh oh",
            "Getting kicked out of this room",
            "I'll be back in 5-10 minutes.",
            "And now \u2026 I have to find my shoes. \uD83D\uDC63",
            "\uD83D\uDC5F \uD83D\uDC5F",
            "\uD83D\uDC60 \uD83D\uDC60",
    };
    static final int MAX_LINES = 5;
    public static Notification makeBigTextNotification(Context context, int update, int id,
            long when) {
        String personUri = null;
        /*
        Cursor c = null;
        try {
            String[] projection = new String[] { ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY };
            String selections = ContactsContract.Contacts.DISPLAY_NAME + " = 'Mike Cleron'";
            final ContentResolver contentResolver = context.getContentResolver();
            c = contentResolver.query(ContactsContract.Contacts.CONTENT_URI,
                    projection, selections, null, null);
            if (c != null && c.getCount() > 0) {
                c.moveToFirst();
                int lookupIdx = c.getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY);
                int idIdx = c.getColumnIndex(ContactsContract.Contacts._ID);
                String lookupKey = c.getString(lookupIdx);
                long contactId = c.getLong(idIdx);
                Uri lookupUri = ContactsContract.Contacts.getLookupUri(contactId, lookupKey);
                personUri = lookupUri.toString();
            }
        } finally {
            if (c != null) {
                c.close();
            }
        }
        if (TextUtils.isEmpty(personUri)) {
            Log.w(TAG, "failed to find contact for Mike Cleron");
        } else {
            Log.w(TAG, "Mike Cleron is " + personUri);
        }
        */

        StringBuilder longSmsText = new StringBuilder();
        int end = 2 + update;
        if (end > LINES.length) {
            end = LINES.length;
        }
        final int start = Math.max(0, end - MAX_LINES);
        for (int i=start; i<end; i++) {
            if (i >= LINES.length) break;
            if (i > start) longSmsText.append("\n");
            longSmsText.append(LINES[i]);
        }
        if (update > 2) {
            when = System.currentTimeMillis();
        }
        Notification.BigTextStyle bigTextStyle = new Notification.BigTextStyle()
                .bigText(longSmsText);
        Notification bigText = new Notification.Builder(context)
                .setContentTitle("Mike Cleron")
                .setContentIntent(ToastService.getPendingIntent(context, "Clicked on bigText"))
                .setContentText(longSmsText)
                        //.setTicker("Mike Cleron: " + longSmsText)
                .setWhen(when)
                .setLargeIcon(getBitmap(context, R.drawable.bucket))
                .setPriority(Notification.PRIORITY_HIGH)
                .setNumber(update)
                .setSmallIcon(R.drawable.stat_notify_talk_text)
                .setStyle(bigTextStyle)
                .setDefaults(Notification.DEFAULT_SOUND)
                .addPerson(personUri)
                .build();
        return bigText;
    }

    public static Notification makeUploadNotification(Context context, int progress, long when) {
        Notification.Builder uploadNotification = new Notification.Builder(context)
                .setContentTitle("File Upload")
                .setContentText("foo.txt")
                .setPriority(Notification.PRIORITY_MIN)
                .setContentIntent(ToastService.getPendingIntent(context, "Clicked on Upload"))
                .setWhen(when)
                .setSmallIcon(R.drawable.ic_menu_upload)
                .setProgress(100, Math.min(progress, 100), false);
        return uploadNotification.build();
    }

    static SpannableStringBuilder BOLD(CharSequence str) {
        final SpannableStringBuilder ssb = new SpannableStringBuilder(str);
        ssb.setSpan(new StyleSpan(Typeface.BOLD), 0, ssb.length(), 0);
        return ssb;
    }

    public static class ToastService extends IntentService {

        private static final String TAG = "ToastService";

        private static final String ACTION_TOAST = "toast";

        private Handler handler;

        public ToastService() {
            super(TAG);
        }
        public ToastService(String name) {
            super(name);
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            handler = new Handler();
            return super.onStartCommand(intent, flags, startId);
        }

        @Override
        protected void onHandleIntent(Intent intent) {
            Log.v(TAG, "clicked a thing! intent=" + intent.toString());
            if (intent.hasExtra("text")) {
                final String text = intent.getStringExtra("text");
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(ToastService.this, text, Toast.LENGTH_LONG).show();
                        Log.v(TAG, "toast " + text);
                    }
                });
            }
        }

        public static PendingIntent getPendingIntent(Context context, String text) {
            Intent toastIntent = new Intent(context, ToastService.class);
            toastIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            toastIntent.setAction(ACTION_TOAST + ":" + text); // one per toast message
            toastIntent.putExtra("text", text);
            PendingIntent pi = PendingIntent.getService(
                    context, 58, toastIntent, PendingIntent.FLAG_UPDATE_CURRENT);
            return pi;
        }
    }

    public static void sleepIfYouCan(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {}
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
    }

    public static String summarize(Notification n) {
        return String.format("<notif title=\"%s\" icon=0x%08x view=%s>",
                n.extras.get(Notification.EXTRA_TITLE),
                n.icon,
                String.valueOf(n.contentView));
    }
    
    public void testCreate() throws Exception {
        ArrayList<Notification> mNotifications = new ArrayList<Notification>();
        NotificationManager noMa =
                (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);

        L("Constructing notifications...");
        if (SHOW_BIG_TEXT) {
            int bigtextId = mNotifications.size();
            final long time = SystemClock.currentThreadTimeMillis();
            final Notification n = makeBigTextNotification(mContext, 0, bigtextId, System.currentTimeMillis());
            L("  %s: create=%dms", summarize(n), SystemClock.currentThreadTimeMillis() - time);
            mNotifications.add(n);
        }

        int uploadId = mNotifications.size();
        long uploadWhen = System.currentTimeMillis();

        if (SHOW_PROGRESS) {
            mNotifications.add(makeUploadNotification(mContext, 0, uploadWhen));
        }

        if (SHOW_PHONE_CALL) {
            int phoneId = mNotifications.size();
            final PendingIntent fullscreenIntent
                    = FullScreenActivity.getPendingIntent(mContext, phoneId);
            final long time = SystemClock.currentThreadTimeMillis();
            Notification phoneCall = new Notification.Builder(mContext)
                    .setContentTitle("Incoming call")
                    .setContentText("Matias Duarte")
                    .setLargeIcon(getBitmap(mContext, R.drawable.matias_hed))
                    .setSmallIcon(R.drawable.stat_sys_phone_call)
                    .setDefaults(Notification.DEFAULT_SOUND)
                    .setPriority(Notification.PRIORITY_MAX)
                    .setContentIntent(fullscreenIntent)
                    .setFullScreenIntent(fullscreenIntent, true)
                    .addAction(R.drawable.ic_dial_action_call, "Answer",
                            ToastService.getPendingIntent(mContext, "Clicked on Answer"))
                    .addAction(R.drawable.ic_end_call, "Ignore",
                            ToastService.getPendingIntent(mContext, "Clicked on Ignore"))
                    .setOngoing(true)
                    .addPerson(Uri.fromParts("tel", "1 (617) 555-1212", null).toString())
                    .build();
            L("  %s: create=%dms", phoneCall.toString(), SystemClock.currentThreadTimeMillis() - time);
            mNotifications.add(phoneCall);
        }

        if (SHOW_STOPWATCH) {
            final long time = SystemClock.currentThreadTimeMillis();
            final Notification n = new Notification.Builder(mContext)
                    .setContentTitle("Stopwatch PRO")
                    .setContentText("Counting up")
                    .setContentIntent(ToastService.getPendingIntent(mContext, "Clicked on Stopwatch"))
                    .setSmallIcon(R.drawable.stat_notify_alarm)
                    .setUsesChronometer(true)
                    .build();
            L("  %s: create=%dms", summarize(n), SystemClock.currentThreadTimeMillis() - time);
            mNotifications.add(n);
        }

        if (SHOW_CALENDAR) {
            final long time = SystemClock.currentThreadTimeMillis();
            final Notification n = new Notification.Builder(mContext)
                    .setContentTitle("J Planning")
                    .setContentText("The Botcave")
                    .setWhen(System.currentTimeMillis())
                    .setSmallIcon(R.drawable.stat_notify_calendar)
                    .setContentIntent(ToastService.getPendingIntent(mContext, "Clicked on calendar event"))
                    .setContentInfo("7PM")
                    .addAction(R.drawable.stat_notify_snooze, "+10 min",
                            ToastService.getPendingIntent(mContext, "snoozed 10 min"))
                    .addAction(R.drawable.stat_notify_snooze_longer, "+1 hour",
                            ToastService.getPendingIntent(mContext, "snoozed 1 hr"))
                    .addAction(R.drawable.stat_notify_email, "Email",
                            ToastService.getPendingIntent(mContext,
                                    "Congratulations, you just destroyed someone's inbox zero"))
                    .build();
            L("  %s: create=%dms", summarize(n), SystemClock.currentThreadTimeMillis() - time);
            mNotifications.add(n);
        }

        if (SHOW_BIG_PICTURE) {
            BitmapDrawable d =
                    (BitmapDrawable) mContext.getResources().getDrawable(R.drawable.romainguy_rockaway);
            final long time = SystemClock.currentThreadTimeMillis();
            final Notification n = new Notification.Builder(mContext)
                        .setContentTitle("Romain Guy")
                        .setContentText("I was lucky to find a Canon 5D Mk III at a local Bay Area "
                                + "store last week but I had not been able to try it in the field "
                                + "until tonight. After a few days of rain the sky finally cleared "
                                + "up. Rockaway Beach did not disappoint and I was finally able to "
                                + "see what my new camera feels like when shooting landscapes.")
                        .setSmallIcon(android.R.drawable.stat_notify_chat)
                        .setContentIntent(
                                ToastService.getPendingIntent(mContext, "Clicked picture"))
                        .setLargeIcon(getBitmap(mContext, R.drawable.romainguy_hed))
                        .addAction(R.drawable.add, "Add to Gallery",
                                ToastService.getPendingIntent(mContext, "Added"))
                        .setStyle(new Notification.BigPictureStyle()
                                .bigPicture(d.getBitmap()))
                        .build();
            L("  %s: create=%dms", summarize(n), SystemClock.currentThreadTimeMillis() - time);
            mNotifications.add(n);
        }

        if (SHOW_INBOX) {
            final long time = SystemClock.currentThreadTimeMillis();
            final Notification n = new Notification.Builder(mContext)
                    .setContentTitle("New mail")
                    .setContentText("3 new messages")
                    .setSubText("example@gmail.com")
                    .setContentIntent(ToastService.getPendingIntent(mContext, "Clicked on Mail"))
                    .setSmallIcon(R.drawable.stat_notify_email)
                    .setStyle(new Notification.InboxStyle()
                                    .setSummaryText("example@gmail.com")
                                    .addLine(BOLD("Alice:").append(" hey there!"))
                                    .addLine(BOLD("Bob:").append(" hi there!"))
                                    .addLine(BOLD("Charlie:").append(" Iz IN UR EMAILZ!!"))
                    ).build();
            L("  %s: create=%dms", summarize(n), SystemClock.currentThreadTimeMillis() - time);
            mNotifications.add(n);
        }

        if (SHOW_SOCIAL) {
            final long time = SystemClock.currentThreadTimeMillis();
            final Notification n = new Notification.Builder(mContext)
                    .setContentTitle("Social Network")
                    .setContentText("You were mentioned in a post")
                    .setContentInfo("example@gmail.com")
                    .setContentIntent(ToastService.getPendingIntent(mContext, "Clicked on Social"))
                    .setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setPriority(Notification.PRIORITY_LOW)
                    .build();
            L("  %s: create=%dms", summarize(n), SystemClock.currentThreadTimeMillis() - time);
            mNotifications.add(n);
        }

        L("Posting notifications...");
        for (int i=0; i<mNotifications.size(); i++) {
            final int count = 4;
            for (int j=0; j<count; j++) {
                long time = SystemClock.currentThreadTimeMillis();
                final Notification n = mNotifications.get(i);
                noMa.notify(NOTIFICATION_ID + i, n);
                time = SystemClock.currentThreadTimeMillis() - time;
                L("  %s: notify=%dms (%d/%d)", summarize(n), time,
                        j + 1, count);
                sleepIfYouCan(150);
            }
        }

        sleepIfYouCan(1000);

        L("Canceling notifications...");
        for (int i=0; i<mNotifications.size(); i++) {
            final Notification n = mNotifications.get(i);
            long time = SystemClock.currentThreadTimeMillis();
            noMa.cancel(NOTIFICATION_ID + i);
            time = SystemClock.currentThreadTimeMillis() - time;
            L("  %s: cancel=%dms", summarize(n), time);
        }

        sleepIfYouCan(500);

        L("Parceling notifications...");
        // we want to be able to use this test on older OSes that do not have getBlobAshmemSize
        Method getBlobAshmemSize = null;
        try {
            getBlobAshmemSize = Parcel.class.getMethod("getBlobAshmemSize");
        } catch (NoSuchMethodException ex) {
        }
        for (int i=0; i<mNotifications.size(); i++) {
            Parcel p = Parcel.obtain();
            {
                final Notification n = mNotifications.get(i);
                long time = SystemClock.currentThreadTimeMillis();
                n.writeToParcel(p, 0);
                time = SystemClock.currentThreadTimeMillis() - time;
                L("  %s: write parcel=%dms size=%d ashmem=%s",
                        summarize(n), time, p.dataPosition(),
                        (getBlobAshmemSize != null)
                            ? getBlobAshmemSize.invoke(p)
                            : "???");
                p.setDataPosition(0);
            }

            long time = SystemClock.currentThreadTimeMillis();
            final Notification n2 = Notification.CREATOR.createFromParcel(p);
            time = SystemClock.currentThreadTimeMillis() - time;
            L("  %s: parcel read=%dms", summarize(n2), time);

            time = SystemClock.currentThreadTimeMillis();
            noMa.notify(NOTIFICATION_ID + i, n2);
            time = SystemClock.currentThreadTimeMillis() - time;
            L("  %s: notify=%dms", summarize(n2), time);
        }

        sleepIfYouCan(500);

        L("Canceling notifications...");
        for (int i=0; i<mNotifications.size(); i++) {
            long time = SystemClock.currentThreadTimeMillis();
            final Notification n = mNotifications.get(i);
            noMa.cancel(NOTIFICATION_ID + i);
            time = SystemClock.currentThreadTimeMillis() - time;
            L("  %s: cancel=%dms", summarize(n), time);
        }


//            if (SHOW_PROGRESS) {
//                ProgressService.startProgressUpdater(this, uploadId, uploadWhen, 0);
//            }
    }

    public static class FullScreenActivity extends Activity {
        public static final String EXTRA_ID = "id";

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            setContentView(R.layout.full_screen);
            final Intent intent = getIntent();
            if (intent != null && intent.hasExtra(EXTRA_ID)) {
                final int id = intent.getIntExtra(EXTRA_ID, -1);
                if (id >= 0) {
                    NotificationManager noMa =
                            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    noMa.cancel(NOTIFICATION_ID + id);
                }
            }
        }

        public void dismiss(View v) {
            finish();
        }

        public static PendingIntent getPendingIntent(Context context, int id) {
            Intent fullScreenIntent = new Intent(context, FullScreenActivity.class);
            fullScreenIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            fullScreenIntent.putExtra(EXTRA_ID, id);
            PendingIntent pi = PendingIntent.getActivity(
                    context, 22, fullScreenIntent, PendingIntent.FLAG_UPDATE_CURRENT);
            return pi;
        }
    }
}

