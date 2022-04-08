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

import java.util.GregorianCalendar;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Vibrator;
import android.os.Handler;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.util.Log;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.RemoteViews;
import android.os.PowerManager;

public class NotificationBuilderTest extends Activity
{
    private final static String TAG = "NotificationTestList";

    private final static String NOTIFY_TAG = "foo";

    NotificationManager mNM;
    Handler mHandler;
    int mStartDelay;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        mNM = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        mHandler = new Handler();
        setContentView(R.layout.notification_builder_test);
        if (icicle == null) {
            setDefaults();
        }
        for (int id: new int[] {
                    R.id.clear_1,
                    R.id.clear_2,
                    R.id.clear_3,
                    R.id.clear_4,
                    R.id.clear_5,
                    R.id.clear_6,
                    R.id.clear_7,
                    R.id.clear_8,
                    R.id.clear_9,
                    R.id.clear_10,
                    R.id.notify_1,
                    R.id.notify_2,
                    R.id.notify_3,
                    R.id.notify_4,
                    R.id.notify_5,
                    R.id.notify_6,
                    R.id.notify_7,
                    R.id.notify_8,
                    R.id.notify_9,
                    R.id.notify_10,
                    R.id.ten,
                    R.id.clear_all,
                }) {
            findViewById(id).setOnClickListener(mClickListener);
        }
    }

    private void setDefaults() {
        setChecked(R.id.when_now);
        setChecked(R.id.icon_surprise);
        setChecked(R.id.title_medium);
        setChecked(R.id.text_medium);
        setChecked(R.id.info_none);
        setChecked(R.id.number_0);
        setChecked(R.id.intent_alert);
        setChecked(R.id.delete_none);
        setChecked(R.id.full_screen_none);
        setChecked(R.id.ticker_none);
        setChecked(R.id.large_icon_none);
        setChecked(R.id.sound_none);
        setChecked(R.id.vibrate_none);
        setChecked(R.id.pri_default);
        setChecked(R.id.lights_red);
        setChecked(R.id.lights_off);
        setChecked(R.id.delay_none);
//        setChecked(R.id.default_vibrate);
//        setChecked(R.id.default_sound);
//        setChecked(R.id.default_lights);
    }

    private View.OnClickListener mClickListener = new View.OnClickListener() {
        public void onClick(View v) {
            switch (v.getId()) {
                case R.id.clear_1:
                    cancelNotification(1);
                    break;
                case R.id.clear_2:
                    cancelNotification(2);
                    break;
                case R.id.clear_3:
                    cancelNotification(3);
                    break;
                case R.id.clear_4:
                    cancelNotification(4);
                    break;
                case R.id.clear_5:
                    cancelNotification(5);
                    break;
                case R.id.clear_6:
                    cancelNotification(6);
                    break;
                case R.id.clear_7:
                    cancelNotification(7);
                    break;
                case R.id.clear_8:
                    cancelNotification(8);
                    break;
                case R.id.clear_9:
                    cancelNotification(9);
                    break;
                case R.id.clear_10:
                    cancelNotification(10);
                    break;
                case R.id.notify_1:
                    sendNotification(1);
                    break;
                case R.id.notify_2:
                    sendNotification(2);
                    break;
                case R.id.notify_3:
                    sendNotification(3);
                    break;
                case R.id.notify_4:
                    sendNotification(4);
                    break;
                case R.id.notify_5:
                    sendNotification(5);
                    break;
                case R.id.notify_6:
                    sendNotification(6);
                    break;
                case R.id.notify_7:
                    sendNotification(7);
                    break;
                case R.id.notify_8:
                    sendNotification(8);
                    break;
                case R.id.notify_9:
                    sendNotification(9);
                    break;
                case R.id.notify_10:
                    sendNotification(10);
                    break;
                case R.id.ten: {
                    for (int id=1; id<=10; id++) {
                        sendNotification(id);
                    }
                    break;
                }
                case R.id.clear_all: {
                    for (int id=1; id<=10; id++) {
                        mNM.cancel(id);
                    }
                    break;
                }
            }
        }
    };

    private void sendNotification(final int id) {
        final Notification n = buildNotification(id);
        mHandler.postDelayed(new Runnable() {
            public void run() {
                mNM.notify(NOTIFY_TAG, id, n);
            }
        }, mStartDelay);
    }

    private void cancelNotification(final int id) {
        mNM.cancel(NOTIFY_TAG, id);
    }

    private static CharSequence subst(CharSequence in, char ch, CharSequence sub) {
        int i=0;
        SpannableStringBuilder edit = new SpannableStringBuilder(in);
        while (i<edit.length()) {
            if (edit.charAt(i) == ch) {
                edit.replace(i, i+1, sub);
                i += sub.length();
            } else {
                i ++;
            }
        }
        return edit;
    }

    private Notification buildNotification(int id) {
        Notification.Builder b = new Notification.Builder(this);

        // when
        switch (getRadioChecked(R.id.group_when)) {
            case R.id.when_midnight: {
                GregorianCalendar c = new GregorianCalendar();
                c.set(GregorianCalendar.HOUR_OF_DAY, 0);
                c.set(GregorianCalendar.MINUTE, 0);
                c.set(GregorianCalendar.SECOND, 0);
                b.setWhen(c.getTimeInMillis());
                break;
            }
            case R.id.when_now:
                b.setWhen(System.currentTimeMillis());
                break;
            case R.id.when_now_plus_1h:
                break;
            case R.id.when_tomorrow:
                break;
        }

        // icon
        switch (getRadioChecked(R.id.group_icon)) {
            case R.id.icon_im:
                b.setSmallIcon(R.drawable.icon1);
                break;
            case R.id.icon_alert:
                b.setSmallIcon(R.drawable.icon2);
                break;
            case R.id.icon_surprise:
                b.setSmallIcon(R.drawable.emo_im_kissing);
                break;
        }

        // title
        final CharSequence title = getRadioTag(R.id.group_title);
        if (!TextUtils.isEmpty(title)) {
            b.setContentTitle(title);
        }

        // text
        final CharSequence text = getRadioTag(R.id.group_text);
        if (!TextUtils.isEmpty(text)) {
            if (getRadioChecked(R.id.group_text) == R.id.text_emoji) {
                // UTF-16 for +1F335
                b.setContentText(subst(text,
                        '_', "\ud83c\udf35"));
            } else {
                b.setContentText(text);
            }
        }

        // info
        final CharSequence info = getRadioTag(R.id.group_info);
        if (!TextUtils.isEmpty(info)) {
            b.setContentInfo(info);
        }

        // number
        b.setNumber(getRadioInt(R.id.group_number, 0));

        // contentIntent
        switch (getRadioChecked(R.id.group_intent)) {
            case R.id.intent_none:
                break;
            case R.id.intent_alert:
                b.setContentIntent(makeContentIntent(id));
                break;
        }

        // deleteIntent
        switch (getRadioChecked(R.id.group_delete)) {
            case R.id.delete_none:
                break;
            case R.id.delete_alert:
                b.setDeleteIntent(makeDeleteIntent(id));
                break;
        }

        // fullScreenIntent TODO

        // ticker
        switch (getRadioChecked(R.id.group_ticker)) {
            case R.id.ticker_none:
                break;
            case R.id.ticker_short:
            case R.id.ticker_wrap:
            case R.id.ticker_haiku:
                b.setTicker(getRadioTag(R.id.group_ticker));
                break;
            case R.id.ticker_emoji:
                // UTF-16 for +1F335
                b.setTicker(subst(getRadioTag(R.id.group_ticker),
                        '_', "\ud83c\udf35"));
                break;
            case R.id.ticker_custom:
                // TODO
                break;
        }

        // largeIcon
        switch (getRadioChecked(R.id.group_large_icon)) {
            case R.id.large_icon_none:
                break;
            case R.id.large_icon_pineapple:
                b.setLargeIcon(loadBitmap(R.drawable.pineapple));
                break;
            case R.id.large_icon_pineapple2:
                b.setLargeIcon(loadBitmap(R.drawable.pineapple2));
                break;
            case R.id.large_icon_small:
                b.setLargeIcon(loadBitmap(R.drawable.icon2));
                break;
        }

        // sound TODO

        // vibrate
        switch (getRadioChecked(R.id.group_vibrate)) {
            case R.id.vibrate_none:
                b.setVibrate(null);
                break;
            case R.id.vibrate_zero:
                b.setVibrate(new long[] { 0 });
                break;
            case R.id.vibrate_short:
                b.setVibrate(new long[] { 0, 100 });
                break;
            case R.id.vibrate_long:
                b.setVibrate(new long[] { 0, 1000 });
                break;
            case R.id.vibrate_pattern:
                b.setVibrate(new long[] { 0, 50,  200, 50,  200, 50,  500,
                                             500, 200, 500, 200, 500, 500,
                                             50,  200, 50,  200, 50        });
                break;
        }

        // lights
        final int color = getRadioHex(R.id.group_lights_color, 0xff0000);
        int onMs;
        int offMs;
        switch (getRadioChecked(R.id.group_lights_blink)) {
            case R.id.lights_slow:
                onMs = 1300;
                offMs = 1300;
                break;
            case R.id.lights_fast:
                onMs = 300;
                offMs = 300;
                break;
            case R.id.lights_on:
                onMs = 1;
                offMs = 0;
                break;
            case R.id.lights_off:
            default:
                onMs = 0;
                offMs = 0;
                break;
        }
        if (onMs != 0 && offMs != 0) {
            b.setLights(color, onMs, offMs);
        }

        // priority
        switch (getRadioChecked(R.id.group_priority)) {
            case R.id.pri_min:
                b.setPriority(Notification.PRIORITY_MIN);
                break;
            case R.id.pri_low:
                b.setPriority(Notification.PRIORITY_LOW);
                break;
            case R.id.pri_default:
                b.setPriority(Notification.PRIORITY_DEFAULT);
                break;
            case R.id.pri_high:
                b.setPriority(Notification.PRIORITY_HIGH);
                break;
            case R.id.pri_max:
                b.setPriority(Notification.PRIORITY_MAX);
                break;
        }

        // start delay
        switch (getRadioChecked(R.id.group_delay)) {
            case R.id.delay_none:
                mStartDelay = 0;
                break;
            case R.id.delay_5:
                mStartDelay = 5000;
                break;
        }

        // flags
        b.setOngoing(getChecked(R.id.flag_ongoing));
        b.setOnlyAlertOnce(getChecked(R.id.flag_once));
        b.setAutoCancel(getChecked(R.id.flag_auto_cancel));

        // defaults
        int defaults = 0;
        if (getChecked(R.id.default_sound)) {
            defaults |= Notification.DEFAULT_SOUND;
        }
        if (getChecked(R.id.default_vibrate)) {
            defaults |= Notification.DEFAULT_VIBRATE;
        }
        if (getChecked(R.id.default_lights)) {
            defaults |= Notification.DEFAULT_LIGHTS;
        }
        b.setDefaults(defaults);

        return b.build();
    }

    private void setChecked(int id) {
        final CompoundButton b = (CompoundButton)findViewById(id);
        b.setChecked(true);
    }

    private int getRadioChecked(int id) {
        final RadioGroup g = (RadioGroup)findViewById(id);
        return g.getCheckedRadioButtonId();
    }

    private String getRadioTag(int id) {
        final RadioGroup g = (RadioGroup)findViewById(id);
        final View v = findViewById(g.getCheckedRadioButtonId());
        return (String) v.getTag();
    }

    private int getRadioInt(int id, int def) {
        String str = getRadioTag(id);
        if (TextUtils.isEmpty(str)) {
            return def;
        } else {
            try {
                return Integer.parseInt(str.toString());
            } catch (NumberFormatException ex) {
                return def;
            }
        }
    }

    private int getRadioHex(int id, int def) {
        String str = getRadioTag(id);
        if (TextUtils.isEmpty(str)) {
            return def;
        } else {
            if (str.startsWith("0x")) {
                str = str.substring(2);
            }
            try {
                return Integer.parseInt(str.toString(), 16);
            } catch (NumberFormatException ex) {
                return def;
            }
        }
    }

    private boolean getChecked(int id) {
        final CompoundButton b = (CompoundButton)findViewById(id);
        return b.isChecked();
    }
    
    private Bitmap loadBitmap(int id) {
        final BitmapDrawable bd = (BitmapDrawable)getResources().getDrawable(id);
        return Bitmap.createBitmap(bd.getBitmap());
    }

    private PendingIntent makeDeleteIntent(int id) {
        Intent intent = new Intent(this, ConfirmationActivity.class);
        intent.setData(Uri.fromParts("content", "//status_bar_test/delete/" + id, null));
        intent.putExtra(ConfirmationActivity.EXTRA_TITLE, "Delete intent");
        intent.putExtra(ConfirmationActivity.EXTRA_TEXT, "id: " + id);
        return PendingIntent.getActivity(this, 0, intent, 0);
    }

    private PendingIntent makeContentIntent(int id) {
        Intent intent = new Intent(this, ConfirmationActivity.class);
        intent.setData(Uri.fromParts("content", "//status_bar_test/content/" + id, null));
        intent.putExtra(ConfirmationActivity.EXTRA_TITLE, "Content intent");
        intent.putExtra(ConfirmationActivity.EXTRA_TEXT, "id: " + id);
        return PendingIntent.getActivity(this, 0, intent, 0);
    }
}

