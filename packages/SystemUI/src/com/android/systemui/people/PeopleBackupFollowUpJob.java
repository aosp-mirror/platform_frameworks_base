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

package com.android.systemui.people;

import static com.android.systemui.people.PeopleSpaceUtils.DEBUG;
import static com.android.systemui.people.PeopleSpaceUtils.EMPTY_STRING;
import static com.android.systemui.people.PeopleSpaceUtils.removeSharedPreferencesStorageForTile;
import static com.android.systemui.people.widget.PeopleBackupHelper.isReadyForRestore;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.app.people.IPeopleManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.PersistableBundle;
import android.os.ServiceManager;
import android.preference.PreferenceManager;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import com.android.systemui.people.widget.PeopleBackupHelper;
import com.android.systemui.people.widget.PeopleTileKey;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Follow-up job that runs after a Conversations widgets restore operation. Check if shortcuts that
 * were not available before are now available. If any shortcut doesn't become available after
 * 1 day, we clean up its storage.
 */
public class PeopleBackupFollowUpJob extends JobService {
    private static final String TAG = "PeopleBackupFollowUpJob";
    private static final String START_DATE = "start_date";

    /** Follow-up job id. */
    public static final int JOB_ID = 74823873;

    private static final long JOB_PERIODIC_DURATION = Duration.ofHours(6).toMillis();
    private static final long CLEAN_UP_STORAGE_AFTER_DURATION = Duration.ofHours(24).toMillis();

    /** SharedPreferences file name for follow-up specific storage.*/
    public static final String SHARED_FOLLOW_UP = "shared_follow_up";

    private final Object mLock = new Object();
    private Context mContext;
    private PackageManager mPackageManager;
    private IPeopleManager mIPeopleManager;
    private JobScheduler mJobScheduler;

    /** Schedules a PeopleBackupFollowUpJob every 2 hours. */
    public static void scheduleJob(Context context) {
        JobScheduler jobScheduler = context.getSystemService(JobScheduler.class);
        PersistableBundle bundle = new PersistableBundle();
        bundle.putLong(START_DATE, System.currentTimeMillis());
        JobInfo jobInfo = new JobInfo
                .Builder(JOB_ID, new ComponentName(context, PeopleBackupFollowUpJob.class))
                .setPeriodic(JOB_PERIODIC_DURATION)
                .setExtras(bundle)
                .build();
        jobScheduler.schedule(jobInfo);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mContext = getApplicationContext();
        mPackageManager = getApplicationContext().getPackageManager();
        mIPeopleManager = IPeopleManager.Stub.asInterface(
                ServiceManager.getService(Context.PEOPLE_SERVICE));
        mJobScheduler = mContext.getSystemService(JobScheduler.class);

    }

    /** Sets necessary managers for testing. */
    @VisibleForTesting
    public void setManagers(Context context, PackageManager packageManager,
            IPeopleManager iPeopleManager, JobScheduler jobScheduler) {
        mContext = context;
        mPackageManager = packageManager;
        mIPeopleManager = iPeopleManager;
        mJobScheduler = jobScheduler;
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        if (DEBUG) Log.d(TAG, "Starting job.");
        synchronized (mLock) {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
            SharedPreferences.Editor editor = sp.edit();
            SharedPreferences followUp = this.getSharedPreferences(
                    SHARED_FOLLOW_UP, Context.MODE_PRIVATE);
            SharedPreferences.Editor followUpEditor = followUp.edit();

            // Remove from SHARED_FOLLOW_UP storage all widgets that are now ready to be updated.
            Map<String, Set<String>> remainingWidgets =
                    processFollowUpFile(followUp, followUpEditor);

            // Check if all widgets were restored or if enough time elapsed to cancel the job.
            long start = params.getExtras().getLong(START_DATE);
            long now = System.currentTimeMillis();
            if (shouldCancelJob(remainingWidgets, start, now)) {
                cancelJobAndClearRemainingWidgets(remainingWidgets, followUpEditor, sp);
            }

            editor.apply();
            followUpEditor.apply();
        }

        // Ensure all widgets modified from SHARED_FOLLOW_UP storage are now updated.
        PeopleBackupHelper.updateWidgets(mContext);
        return false;
    }

    /**
     * Iterates through follow-up file entries and checks which shortcuts are now available.
     * Returns a map of shortcuts that should be checked at a later time.
     */
    public Map<String, Set<String>> processFollowUpFile(SharedPreferences followUp,
            SharedPreferences.Editor followUpEditor) {
        Map<String, Set<String>> remainingWidgets = new HashMap<>();
        Map<String, ?> all = followUp.getAll();
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            String key = entry.getKey();

            PeopleTileKey peopleTileKey = PeopleTileKey.fromString(key);
            boolean restored = isReadyForRestore(mIPeopleManager, mPackageManager, peopleTileKey);
            if (restored) {
                if (DEBUG) Log.d(TAG, "Removing key from follow-up: " + key);
                followUpEditor.remove(key);
                continue;
            }

            if (DEBUG) Log.d(TAG, "Key should not be restored yet, try later: " + key);
            try {
                remainingWidgets.put(entry.getKey(), (Set<String>) entry.getValue());
            } catch (Exception e) {
                Log.e(TAG, "Malformed entry value: " + entry.getValue());
            }
        }
        return remainingWidgets;
    }

    /** Returns whether all shortcuts were restored or if enough time elapsed to cancel the job. */
    public boolean shouldCancelJob(Map<String, Set<String>> remainingWidgets,
            long start, long now) {
        if (remainingWidgets.isEmpty()) {
            if (DEBUG) Log.d(TAG, "All widget storage was successfully restored.");
            return true;
        }

        boolean oneDayHasPassed = (now - start) > CLEAN_UP_STORAGE_AFTER_DURATION;
        if (oneDayHasPassed) {
            if (DEBUG) {
                Log.w(TAG, "One or more widgets were not properly restored, "
                        + "but cancelling job because it has been a day.");
            }
            return true;
        }
        if (DEBUG) Log.d(TAG, "There are still non-restored widgets, run job again.");
        return false;
    }

    /** Cancels job and removes storage of any shortcut that was not restored. */
    public void cancelJobAndClearRemainingWidgets(Map<String, Set<String>> remainingWidgets,
            SharedPreferences.Editor followUpEditor, SharedPreferences sp) {
        if (DEBUG) Log.d(TAG, "Cancelling follow up job.");
        removeUnavailableShortcutsFromSharedStorage(remainingWidgets, sp);
        followUpEditor.clear();
        mJobScheduler.cancel(JOB_ID);
    }

    private void removeUnavailableShortcutsFromSharedStorage(Map<String,
            Set<String>> remainingWidgets, SharedPreferences sp) {
        for (Map.Entry<String, Set<String>> entry : remainingWidgets.entrySet()) {
            PeopleTileKey peopleTileKey = PeopleTileKey.fromString(entry.getKey());
            if (!PeopleTileKey.isValid(peopleTileKey)) {
                Log.e(TAG, "Malformed peopleTileKey in follow-up file: " + entry.getKey());
                continue;
            }
            Set<String> widgetIds;
            try {
                widgetIds = (Set<String>) entry.getValue();
            } catch (Exception e) {
                Log.e(TAG, "Malformed widget ids in follow-up file: " + e);
                continue;
            }
            for (String id : widgetIds) {
                int widgetId;
                try {
                    widgetId = Integer.parseInt(id);
                } catch (NumberFormatException ex) {
                    Log.e(TAG, "Malformed widget id in follow-up file: " + ex);
                    continue;
                }

                String contactUriString = sp.getString(String.valueOf(widgetId), EMPTY_STRING);
                removeSharedPreferencesStorageForTile(
                        mContext, peopleTileKey, widgetId, contactUriString);
            }
        }
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
