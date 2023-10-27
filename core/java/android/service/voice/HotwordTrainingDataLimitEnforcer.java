/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.service.voice;

import android.annotation.NonNull;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.io.File;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Enforces daily limits on the egress of {@link HotwordTrainingData} from the hotword detection
 * service.
 *
 * <p> Egress is tracked across UTC day (24-hour window) and count is reset at
 * midnight (UTC 00:00:00).
 *
 * @hide
 */
public class HotwordTrainingDataLimitEnforcer {
    private static final String TAG = "HotwordTrainingDataLimitEnforcer";

    /**
     * Number of hotword training data events that are allowed to be egressed per day.
     */
    private static final int TRAINING_DATA_EGRESS_LIMIT = 20;

    /**
     * Name of hotword training data limit shared preference.
     */
    private static final String TRAINING_DATA_LIMIT_SHARED_PREF = "TrainingDataSharedPref";

    /**
     * Key for date associated with
     * {@link HotwordTrainingDataLimitEnforcer#TRAINING_DATA_EGRESS_COUNT}.
     */
    private static final String TRAINING_DATA_EGRESS_DATE = "TRAINING_DATA_EGRESS_DATE";

    /**
     * Key for number of hotword training data events egressed on
     * {@link HotwordTrainingDataLimitEnforcer#TRAINING_DATA_EGRESS_DATE}.
     */
    private static final String TRAINING_DATA_EGRESS_COUNT = "TRAINING_DATA_EGRESS_COUNT";

    private SharedPreferences mSharedPreferences;

    private static final Object INSTANCE_LOCK = new Object();
    private final Object mTrainingDataIncrementLock = new Object();

    private static HotwordTrainingDataLimitEnforcer sInstance;

    /** Get singleton HotwordTrainingDataLimitEnforcer instance. */
    public static @NonNull HotwordTrainingDataLimitEnforcer getInstance(@NonNull Context context) {
        synchronized (INSTANCE_LOCK) {
            if (sInstance == null) {
                sInstance = new HotwordTrainingDataLimitEnforcer(context.getApplicationContext());
            }
            return sInstance;
        }
    }

    private HotwordTrainingDataLimitEnforcer(Context context) {
        mSharedPreferences = context.getSharedPreferences(
                new File(Environment.getDataSystemCeDirectory(UserHandle.USER_SYSTEM),
                        TRAINING_DATA_LIMIT_SHARED_PREF),
                Context.MODE_PRIVATE);
    }

    /** @hide */
    @VisibleForTesting
    public void resetTrainingDataEgressCount() {
        Log.i(TAG, "Resetting training data egress count!");
        synchronized (mTrainingDataIncrementLock) {
            // Clear all training data shared preferences.
            mSharedPreferences.edit().clear().commit();
        }
    }

    /**
     * Increments training data egress count.
     * <p> If count exceeds daily training data egress limit, returns false. Else, will return true.
     */
    public boolean incrementEgressCount() {
        synchronized (mTrainingDataIncrementLock) {
            return incrementTrainingDataEgressCountLocked();
        }
    }

    private boolean incrementTrainingDataEgressCountLocked() {
        LocalDate utcDate = LocalDate.now(ZoneOffset.UTC);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        String currentDate = utcDate.format(formatter);

        String storedDate = mSharedPreferences.getString(TRAINING_DATA_EGRESS_DATE, "");
        int storedCount = mSharedPreferences.getInt(TRAINING_DATA_EGRESS_COUNT, 0);
        Log.i(TAG,
                TextUtils.formatSimple("There are %s hotword training data events egressed for %s",
                        storedCount, storedDate));

        SharedPreferences.Editor editor = mSharedPreferences.edit();

        // If date has not changed from last training data event, increment counter if within
        // limit.
        if (storedDate.equals(currentDate)) {
            if (storedCount < TRAINING_DATA_EGRESS_LIMIT) {
                Log.i(TAG, "Within hotword training data egress limit, incrementing...");
                editor.putInt(TRAINING_DATA_EGRESS_COUNT, storedCount + 1);
                editor.commit();
                return true;
            }
            Log.i(TAG, "Exceeded hotword training data egress limit.");
            return false;
        }

        // If date has changed, reset.
        Log.i(TAG, TextUtils.formatSimple(
                "Stored date %s is different from current data %s. Resetting counters...",
                storedDate, currentDate));

        editor.putString(TRAINING_DATA_EGRESS_DATE, currentDate);
        editor.putInt(TRAINING_DATA_EGRESS_COUNT, 1);
        editor.commit();
        return true;
    }
}
