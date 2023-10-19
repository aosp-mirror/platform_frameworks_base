/*
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

package com.android.soundpicker;

import android.app.Service;
import android.content.Intent;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Environment;
import android.os.FileUtils;
import android.os.IBinder;
import android.provider.MediaStore;
import android.provider.Settings.System;
import android.util.Log;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Service to copy and set customization of default sounds
 */
public class RingtoneOverlayService extends Service {
    private static final String TAG = "RingtoneOverlayService";
    private static final boolean DEBUG = false;

    @Override
    public int onStartCommand(@Nullable final Intent intent, final int flags, final int startId) {
        AsyncTask.execute(() -> {
            updateRingtones();
            stopSelf();
        });

        // Try again later if we are killed before we finish.
        return Service.START_REDELIVER_INTENT;
    }

    @Override
    public IBinder onBind(@Nullable final Intent intent) {
        return null;
    }

    private void updateRingtones() {
        copyResourceAndSetAsSound(R.raw.default_ringtone,
                System.RINGTONE, Environment.DIRECTORY_RINGTONES);
        copyResourceAndSetAsSound(R.raw.default_notification_sound,
                System.NOTIFICATION_SOUND, Environment.DIRECTORY_NOTIFICATIONS);
        copyResourceAndSetAsSound(R.raw.default_alarm_alert,
                System.ALARM_ALERT, Environment.DIRECTORY_ALARMS);
    }

    /* If the resource contains any data, copy a resource to the file system, scan it, and set the
     * file URI as the default for a sound. */
    private void copyResourceAndSetAsSound(@IdRes final int id, @NonNull final String name,
            @NonNull final String subPath) {
        final File destDir = Environment.getExternalStoragePublicDirectory(subPath);
        if (!destDir.exists() && !destDir.mkdirs()) {
            Log.e(TAG, "can't create " + destDir.getAbsolutePath());
            return;
        }

        final File dest = new File(destDir, "default_" + name + ".ogg");
        try (
                InputStream is = getResources().openRawResource(id);
                FileOutputStream os = new FileOutputStream(dest);
        ) {
            if (is.available() > 0) {
                FileUtils.copy(is, os);
                final Uri uri = scanFile(dest);
                if (uri != null) {
                    set(name, uri);
                }
            } else {
                // TODO Shall we remove any former copied resource in this case and unset
                // the defaults if we use this event a second time to clear the data?
                if (DEBUG) Log.d(TAG, "Resource for " + name + " has no overlay");
            }
        } catch (IOException e) {
            Log.e(TAG, "Unable to open resource for " + name + ": " + e);
        }
    }

    private Uri scanFile(@NonNull final File file) {
        return MediaStore.scanFile(getContentResolver(), file);
    }

    private void set(@NonNull final String name, @NonNull final Uri uri) {
        final Uri settingUri = System.getUriFor(name);
        RingtoneManager.setActualDefaultRingtoneUri(this,
                RingtoneManager.getDefaultType(settingUri), uri);
        System.putInt(getContentResolver(), name + "_set", 1);
    }
}
