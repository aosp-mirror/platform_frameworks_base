/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.egg.neko;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import java.util.List;
import android.util.Log;

import com.android.egg.R;

import java.util.Random;

import static com.android.egg.neko.Cat.PURR;
import static com.android.egg.neko.NekoLand.CHAN_ID;

public class NekoService extends JobService {

    private static final String TAG = "NekoService";

    public static int JOB_ID = 42;

    public static int CAT_NOTIFICATION = 1;
    public static int DEBUG_NOTIFICATION = 1234;

    public static float CAT_CAPTURE_PROB = 1.0f; // generous

    public static long SECONDS = 1000;
    public static long MINUTES = 60 * SECONDS;

    public static long INTERVAL_FLEX = 5 * MINUTES;

    public static float INTERVAL_JITTER_FRAC = 0.25f;

    private static void setupNotificationChannels(Context context) {
        NotificationManager noman = context.getSystemService(NotificationManager.class);
        NotificationChannel eggChan = new NotificationChannel(CHAN_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT);
        eggChan.setSound(Uri.EMPTY, Notification.AUDIO_ATTRIBUTES_DEFAULT); // cats are quiet
        eggChan.setVibrationPattern(PURR); // not totally quiet though
        eggChan.setBlockableSystem(true); // unlike a real cat, you can push this one off your lap
        eggChan.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC); // cats sit in the window
        noman.createNotificationChannel(eggChan);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.v(TAG, "Starting job: " + String.valueOf(params));

        NotificationManager noman = getSystemService(NotificationManager.class);
        if (NekoLand.DEBUG_NOTIFICATIONS) {
            final Bundle extras = new Bundle();
            extras.putString("android.substName", getString(R.string.notification_name));
            final int size = getResources()
                    .getDimensionPixelSize(android.R.dimen.notification_large_icon_width);
            final Cat cat = Cat.create(this);
            final Notification.Builder builder
                    = cat.buildNotification(this)
                        .setContentTitle("DEBUG")
                        .setChannel(NekoLand.CHAN_ID)
                        .setContentText("Ran job: " + params);
            noman.notify(DEBUG_NOTIFICATION, builder.build());
        }

        final PrefState prefs = new PrefState(this);
        int food = prefs.getFoodState();
        if (food != 0) {
            prefs.setFoodState(0); // nom
            final Random rng = new Random();
            if (rng.nextFloat() <= CAT_CAPTURE_PROB) {
                Cat cat;
                List<Cat> cats = prefs.getCats();
                final int[] probs = getResources().getIntArray(R.array.food_new_cat_prob);
                final float new_cat_prob = (float)((food < probs.length) ? probs[food] : 50) / 100f;

                if (cats.size() == 0 || rng.nextFloat() <= new_cat_prob) {
                    cat = Cat.create(this);
                    prefs.addCat(cat);
                    cat.logAdd(this);
                    Log.v(TAG, "A new cat is here: " + cat.getName());
                } else {
                    cat = cats.get(rng.nextInt(cats.size()));
                    Log.v(TAG, "A cat has returned: " + cat.getName());
                }

                final Notification.Builder builder = cat.buildNotification(this);
                noman.notify(CAT_NOTIFICATION, builder.build());
            }
        }
        cancelJob(this);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters jobParameters) {
        return false;
    }

    public static void registerJobIfNeeded(Context context, long intervalMinutes) {
        JobScheduler jss = context.getSystemService(JobScheduler.class);
        JobInfo info = jss.getPendingJob(JOB_ID);
        if (info == null) {
            registerJob(context, intervalMinutes);
        }
    }

    public static void registerJob(Context context, long intervalMinutes) {
        setupNotificationChannels(context);

        JobScheduler jss = context.getSystemService(JobScheduler.class);
        jss.cancel(JOB_ID);
        long interval = intervalMinutes * MINUTES;
        long jitter = (long)(INTERVAL_JITTER_FRAC * interval);
        interval += (long)(Math.random() * (2 * jitter)) - jitter;
        final JobInfo jobInfo = new JobInfo.Builder(JOB_ID,
                new ComponentName(context, NekoService.class))
                .setPeriodic(interval, INTERVAL_FLEX)
                .build();

        Log.v(TAG, "A cat will visit in " + interval + "ms: " + String.valueOf(jobInfo));
        jss.schedule(jobInfo);

        if (NekoLand.DEBUG_NOTIFICATIONS) {
            NotificationManager noman = context.getSystemService(NotificationManager.class);
            noman.notify(DEBUG_NOTIFICATION, new Notification.Builder(context)
                    .setSmallIcon(R.drawable.stat_icon)
                    .setContentTitle(String.format("Job scheduled in %d min", (interval / MINUTES)))
                    .setContentText(String.valueOf(jobInfo))
                    .setPriority(Notification.PRIORITY_MIN)
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .setChannel(NekoLand.CHAN_ID)
                    .setShowWhen(true)
                    .build());
        }
    }

    public static void cancelJob(Context context) {
        JobScheduler jss = context.getSystemService(JobScheduler.class);
        Log.v(TAG, "Canceling job");
        jss.cancel(JOB_ID);
    }
}
