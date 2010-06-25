/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.fixvibratesetting;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.media.AudioManager;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.os.Bundle;

public class FixVibrateSetting extends Activity implements View.OnClickListener
{
    AudioManager mAudioManager;
    NotificationManager mNotificationManager;
    TextView mCurrentSetting;
    View mFix;
    View mUnfix;
    View mTest;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        setContentView(R.layout.fix_vibrate);

        mAudioManager = (AudioManager)getSystemService(AUDIO_SERVICE);
        mNotificationManager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);

        mCurrentSetting = (TextView)findViewById(R.id.current_setting);

        mFix = findViewById(R.id.fix);
        mFix.setOnClickListener(this);

        mUnfix = findViewById(R.id.unfix);
        mUnfix.setOnClickListener(this);

        mTest = findViewById(R.id.test);
        mTest.setOnClickListener(this);
    }

    @Override
    public void onResume() {
        super.onResume();

        update();
    }

    private String getSettingValue(int vibrateType) {
        int setting = mAudioManager.getVibrateSetting(vibrateType);
        switch (setting) {
            case AudioManager.VIBRATE_SETTING_OFF:
                return "off";
            case AudioManager.VIBRATE_SETTING_ON:
                return "on";
            case AudioManager.VIBRATE_SETTING_ONLY_SILENT:
                return "silent-only";
            default:
                return "unknown";
        }
    }

    public void onClick(View v) {
        if (v == mFix) {
            fix();
            update();
        } else if (v == mUnfix) {
            unfix();
            update();
        } else if (v == mTest) {
            test();
            update();
        }
    }

    private void update() {
        String ringer = getSettingValue(AudioManager.VIBRATE_TYPE_RINGER);
        String notification = getSettingValue(AudioManager.VIBRATE_TYPE_NOTIFICATION);
        String text = getString(R.string.current_setting, ringer, notification);
        mCurrentSetting.setText(text);
    }

    private void fix() {
        mAudioManager.setVibrateSetting(AudioManager.VIBRATE_TYPE_NOTIFICATION,
                AudioManager.VIBRATE_SETTING_ON);
    }

    private void unfix() {
        mAudioManager.setVibrateSetting(AudioManager.VIBRATE_TYPE_NOTIFICATION,
                AudioManager.VIBRATE_SETTING_OFF);
    }

    private void test() {
        Notification n = new Notification(R.drawable.stat_sys_warning, "Test notification",
                        System.currentTimeMillis());
        Intent intent = new Intent(this, FixVibrateSetting.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, intent, 0);
        n.setLatestEventInfo(this, "Test notification", "Test notification", pending);

        n.vibrate = new long[] { 0, 700, 500, 1000 };
        n.flags |= Notification.FLAG_AUTO_CANCEL;
        mNotificationManager.notify(1, n);
    }
}

