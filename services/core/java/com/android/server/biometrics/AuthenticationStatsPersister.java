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

package com.android.server.biometrics;

import android.annotation.NonNull;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.os.Environment;
import android.os.UserHandle;
import android.util.Slog;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Persists and retrieves stats for Biometric Authentication.
 * Authentication stats include userId, total attempts, rejected attempts,
 * and the number of sent enrollment notifications.
 * Data are stored in SharedPreferences in a form of a set of JSON objects,
 * where it's one element per user.
 */
public class AuthenticationStatsPersister {

    private static final String TAG = "AuthenticationStatsPersister";
    private static final String FILE_NAME = "authentication_stats";
    private static final String USER_ID = "user_id";
    private static final String FACE_ATTEMPTS = "face_attempts";
    private static final String FACE_REJECTIONS = "face_rejections";
    private static final String FINGERPRINT_ATTEMPTS = "fingerprint_attempts";
    private static final String FINGERPRINT_REJECTIONS = "fingerprint_rejections";
    private static final String ENROLLMENT_NOTIFICATIONS = "enrollment_notifications";
    private static final String KEY = "frr_stats";
    private static final String THRESHOLD_KEY = "frr_threshold";

    @NonNull private final SharedPreferences mSharedPreferences;

    AuthenticationStatsPersister(@NonNull Context context) {
        // The package info in the context isn't initialized in the way it is for normal apps,
        // so the standard, name-based context.getSharedPreferences doesn't work. Instead, we
        // build the path manually below using the same policy that appears in ContextImpl.
        final File prefsFile = new File(Environment.getDataSystemDirectory(), FILE_NAME);
        mSharedPreferences = context.getSharedPreferences(prefsFile, Context.MODE_PRIVATE);
    }

    /**
     * Get all frr data from SharedPreference.
     */
    public List<AuthenticationStats> getAllFrrStats(int modality) {
        List<AuthenticationStats> authenticationStatsList = new ArrayList<>();
        for (String frrStats : readFrrStats()) {
            try {
                JSONObject frrStatsJson = new JSONObject(frrStats);
                if (modality == BiometricsProtoEnums.MODALITY_FACE) {
                    authenticationStatsList.add(new AuthenticationStats(
                            getIntValue(frrStatsJson, USER_ID,
                                    UserHandle.USER_NULL /* defaultValue */),
                            getIntValue(frrStatsJson, FACE_ATTEMPTS),
                            getIntValue(frrStatsJson, FACE_REJECTIONS),
                            getIntValue(frrStatsJson, ENROLLMENT_NOTIFICATIONS),
                            modality));
                } else if (modality == BiometricsProtoEnums.MODALITY_FINGERPRINT) {
                    authenticationStatsList.add(new AuthenticationStats(
                            getIntValue(frrStatsJson, USER_ID,
                                    UserHandle.USER_NULL /* defaultValue */),
                            getIntValue(frrStatsJson, FINGERPRINT_ATTEMPTS),
                            getIntValue(frrStatsJson, FINGERPRINT_REJECTIONS),
                            getIntValue(frrStatsJson, ENROLLMENT_NOTIFICATIONS),
                            modality));
                }
            } catch (JSONException e) {
                Slog.w(TAG, String.format("Unable to resolve authentication stats JSON: %s",
                        frrStats));
            }
        }
        return authenticationStatsList;
    }

    /**
     * Remove frr data for a specific user.
     */
    public void removeFrrStats(int userId) {
        try {
            // Copy into a new HashSet to allow modification.
            Set<String> frrStatsSet = new HashSet<>(readFrrStats());

            // Remove the old authentication stat for the user if it exists.
            for (Iterator<String> iterator = frrStatsSet.iterator(); iterator.hasNext();) {
                String frrStats = iterator.next();
                JSONObject frrStatJson = new JSONObject(frrStats);
                if (getValue(frrStatJson, USER_ID).equals(String.valueOf(userId))) {
                    iterator.remove();
                    break;
                }
            }

            mSharedPreferences.edit().putStringSet(KEY, frrStatsSet).apply();
        } catch (JSONException ignored) {
        }
    }

    /**
     * Persist frr data for a specific user.
     */
    public void persistFrrStats(int userId, int totalAttempts, int rejectedAttempts,
            int enrollmentNotifications, int modality) {
        try {
            // Copy into a new HashSet to allow modification.
            Set<String> frrStatsSet = new HashSet<>(readFrrStats());

            // Remove the old authentication stat for the user if it exists.
            JSONObject frrStatJson = null;
            for (Iterator<String> iterator = frrStatsSet.iterator(); iterator.hasNext();) {
                String frrStats = iterator.next();
                frrStatJson = new JSONObject(frrStats);
                if (getValue(frrStatJson, USER_ID).equals(String.valueOf(userId))) {
                    iterator.remove();
                    break;
                }
                // Reset frrStatJson when user doesn't exist.
                frrStatJson = null;
            }

            // Checks if this is a new user and there's no JSON for this user in the storage.
            if (frrStatJson == null) {
                frrStatJson = new JSONObject().put(USER_ID, userId);
            }
            frrStatsSet.add(buildFrrStats(frrStatJson, totalAttempts, rejectedAttempts,
                    enrollmentNotifications, modality));

            Slog.d(TAG, "frrStatsSet to persist: " + frrStatsSet);

            mSharedPreferences.edit().putStringSet(KEY, frrStatsSet).apply();

        } catch (JSONException e) {
            Slog.e(TAG, "Unable to persist authentication stats");
        }
    }

    /**
     * Persist frr threshold.
     */
    public void persistFrrThreshold(float frrThreshold) {
        mSharedPreferences.edit().putFloat(THRESHOLD_KEY, frrThreshold).apply();
    }

    private Set<String> readFrrStats() {
        return mSharedPreferences.getStringSet(KEY, Set.of());
    }

    // Update frr stats for existing frrStats JSONObject and build the new string.
    private String buildFrrStats(JSONObject frrStats, int totalAttempts, int rejectedAttempts,
            int enrollmentNotifications, int modality) throws JSONException {
        if (modality == BiometricsProtoEnums.MODALITY_FACE) {
            return frrStats
                    .put(FACE_ATTEMPTS, totalAttempts)
                    .put(FACE_REJECTIONS, rejectedAttempts)
                    .put(ENROLLMENT_NOTIFICATIONS, enrollmentNotifications)
                    .toString();
        } else if (modality == BiometricsProtoEnums.MODALITY_FINGERPRINT) {
            return frrStats
                    .put(FINGERPRINT_ATTEMPTS, totalAttempts)
                    .put(FINGERPRINT_REJECTIONS, rejectedAttempts)
                    .put(ENROLLMENT_NOTIFICATIONS, enrollmentNotifications)
                    .toString();
        } else {
            return frrStats.toString();
        }
    }

    private String getValue(JSONObject jsonObject, String key) throws JSONException {
        return jsonObject.has(key) ? jsonObject.getString(key) : "";
    }

    private int getIntValue(JSONObject jsonObject, String key) throws JSONException {
        return getIntValue(jsonObject, key, 0 /* defaultValue */);
    }

    private int getIntValue(JSONObject jsonObject, String key, int defaultValue)
            throws JSONException {
        return jsonObject.has(key) ? jsonObject.getInt(key) : defaultValue;
    }
}
