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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.io.File;
import java.util.List;
import java.util.Set;

@Presubmit
@SmallTest
public class AuthenticationStatsPersisterTest {

    @Rule public MockitoRule mockitoRule = MockitoJUnit.rule();

    private static final int USER_ID_1 = 1;
    private static final int USER_ID_2 = 2;
    private static final String USER_ID = "user_id";
    private static final String FACE_ATTEMPTS = "face_attempts";
    private static final String FACE_REJECTIONS = "face_rejections";
    private static final String FINGERPRINT_ATTEMPTS = "fingerprint_attempts";
    private static final String FINGERPRINT_REJECTIONS = "fingerprint_rejections";
    private static final String ENROLLMENT_NOTIFICATIONS = "enrollment_notifications";
    private static final String KEY = "frr_stats";
    private static final String THRESHOLD_KEY = "frr_threshold";
    private static final float FRR_THRESHOLD = 0.25f;

    @Mock
    private Context mContext;
    @Mock
    private SharedPreferences mSharedPreferences;
    @Mock
    private SharedPreferences.Editor mEditor;
    private AuthenticationStatsPersister mAuthenticationStatsPersister;

    @Captor
    private ArgumentCaptor<Set<String>> mStringSetArgumentCaptor;
    @Captor
    private ArgumentCaptor<Float> mFrrThresholdArgumentCaptor;

    @Before
    public void setUp() {
        when(mContext.getSharedPreferences(any(File.class), anyInt()))
                .thenReturn(mSharedPreferences);
        when(mSharedPreferences.edit()).thenReturn(mEditor);
        when(mEditor.putStringSet(anyString(), anySet())).thenReturn(mEditor);
        when(mEditor.putFloat(anyString(), anyFloat())).thenReturn(mEditor);

        mAuthenticationStatsPersister = new AuthenticationStatsPersister(mContext);
    }

    @Test
    public void getAllFrrStats_face_shouldListAllFrrStats() throws JSONException {
        AuthenticationStats stats1 = new AuthenticationStats(USER_ID_1,
                300 /* totalAttempts */, 10 /* rejectedAttempts */,
                0 /* enrollmentNotifications */, BiometricsProtoEnums.MODALITY_FACE);
        AuthenticationStats stats2 = new AuthenticationStats(USER_ID_2,
                200 /* totalAttempts */, 20 /* rejectedAttempts */,
                0 /* enrollmentNotifications */, BiometricsProtoEnums.MODALITY_FINGERPRINT);
        when(mSharedPreferences.getStringSet(eq(KEY), anySet())).thenReturn(
                Set.of(buildFrrStats(stats1), buildFrrStats(stats2)));

        List<AuthenticationStats> authenticationStatsList =
                mAuthenticationStatsPersister.getAllFrrStats(BiometricsProtoEnums.MODALITY_FACE);

        assertThat(authenticationStatsList.size()).isEqualTo(2);
        AuthenticationStats expectedStats2 = new AuthenticationStats(USER_ID_2,
                0 /* totalAttempts */, 0 /* rejectedAttempts */,
                0 /* enrollmentNotifications */, BiometricsProtoEnums.MODALITY_FACE);
        assertThat(authenticationStatsList).contains(stats1);
        assertThat(authenticationStatsList).contains(expectedStats2);
    }

    @Test
    public void getAllFrrStats_fingerprint_shouldListAllFrrStats() throws JSONException {
        // User 1 with fingerprint authentication stats.
        AuthenticationStats stats1 = new AuthenticationStats(USER_ID_1,
                200 /* totalAttempts */, 20 /* rejectedAttempts */,
                0 /* enrollmentNotifications */, BiometricsProtoEnums.MODALITY_FINGERPRINT);
        // User 2 without fingerprint authentication stats.
        AuthenticationStats stats2 = new AuthenticationStats(USER_ID_2,
                300 /* totalAttempts */, 10 /* rejectedAttempts */,
                0 /* enrollmentNotifications */, BiometricsProtoEnums.MODALITY_FACE);
        when(mSharedPreferences.getStringSet(eq(KEY), anySet())).thenReturn(
                Set.of(buildFrrStats(stats1), buildFrrStats(stats2)));

        List<AuthenticationStats> authenticationStatsList =
                mAuthenticationStatsPersister
                        .getAllFrrStats(BiometricsProtoEnums.MODALITY_FINGERPRINT);

        assertThat(authenticationStatsList.size()).isEqualTo(2);
        AuthenticationStats expectedStats2 = new AuthenticationStats(USER_ID_2,
                0 /* totalAttempts */, 0 /* rejectedAttempts */,
                0 /* enrollmentNotifications */, BiometricsProtoEnums.MODALITY_FINGERPRINT);
        assertThat(authenticationStatsList).contains(stats1);
        assertThat(authenticationStatsList).contains(expectedStats2);
    }

    @Test
    public void persistFrrStats_newUser_face_shouldSuccess() throws JSONException {
        AuthenticationStats authenticationStats = new AuthenticationStats(USER_ID_1,
                300 /* totalAttempts */, 10 /* rejectedAttempts */,
                0 /* enrollmentNotifications */, BiometricsProtoEnums.MODALITY_FACE);

        mAuthenticationStatsPersister.persistFrrStats(authenticationStats.getUserId(),
                authenticationStats.getTotalAttempts(),
                authenticationStats.getRejectedAttempts(),
                authenticationStats.getEnrollmentNotifications(),
                authenticationStats.getModality());

        verify(mEditor).putStringSet(eq(KEY), mStringSetArgumentCaptor.capture());
        assertThat(mStringSetArgumentCaptor.getValue())
                .contains(buildFrrStats(authenticationStats));
    }

    @Test
    public void persistFrrStats_newUser_fingerprint_shouldSuccess() throws JSONException {
        AuthenticationStats authenticationStats = new AuthenticationStats(USER_ID_1,
                300 /* totalAttempts */, 10 /* rejectedAttempts */,
                0 /* enrollmentNotifications */, BiometricsProtoEnums.MODALITY_FINGERPRINT);

        mAuthenticationStatsPersister.persistFrrStats(authenticationStats.getUserId(),
                authenticationStats.getTotalAttempts(),
                authenticationStats.getRejectedAttempts(),
                authenticationStats.getEnrollmentNotifications(),
                authenticationStats.getModality());

        verify(mEditor).putStringSet(eq(KEY), mStringSetArgumentCaptor.capture());
        assertThat(mStringSetArgumentCaptor.getValue())
                .contains(buildFrrStats(authenticationStats));
    }

    @Test
    public void persistFrrStats_existingUser_shouldUpdateRecord() throws JSONException {
        AuthenticationStats authenticationStats = new AuthenticationStats(USER_ID_1,
                300 /* totalAttempts */, 10 /* rejectedAttempts */,
                0 /* enrollmentNotifications */, BiometricsProtoEnums.MODALITY_FACE);
        AuthenticationStats newAuthenticationStats = new AuthenticationStats(USER_ID_1,
                500 /* totalAttempts */, 30 /* rejectedAttempts */,
                1 /* enrollmentNotifications */, BiometricsProtoEnums.MODALITY_FACE);
        when(mSharedPreferences.getStringSet(eq(KEY), anySet())).thenReturn(
                Set.of(buildFrrStats(authenticationStats)));

        mAuthenticationStatsPersister.persistFrrStats(newAuthenticationStats.getUserId(),
                newAuthenticationStats.getTotalAttempts(),
                newAuthenticationStats.getRejectedAttempts(),
                newAuthenticationStats.getEnrollmentNotifications(),
                newAuthenticationStats.getModality());

        verify(mEditor).putStringSet(eq(KEY), mStringSetArgumentCaptor.capture());
        assertThat(mStringSetArgumentCaptor.getValue())
                .contains(buildFrrStats(newAuthenticationStats));
    }

    @Test
    public void persistFrrStats_existingUserWithFingerprint_faceAuthenticate_shouldUpdateRecord()
            throws JSONException {
        // User with fingerprint authentication stats.
        AuthenticationStats authenticationStats = new AuthenticationStats(USER_ID_1,
                200 /* totalAttempts */, 20 /* rejectedAttempts */,
                0 /* enrollmentNotifications */, BiometricsProtoEnums.MODALITY_FINGERPRINT);
        // The same user with face authentication stats.
        AuthenticationStats newAuthenticationStats = new AuthenticationStats(USER_ID_1,
                500 /* totalAttempts */, 30 /* rejectedAttempts */,
                1 /* enrollmentNotifications */, BiometricsProtoEnums.MODALITY_FACE);
        when(mSharedPreferences.getStringSet(eq(KEY), anySet())).thenReturn(
                Set.of(buildFrrStats(authenticationStats)));

        mAuthenticationStatsPersister.persistFrrStats(newAuthenticationStats.getUserId(),
                newAuthenticationStats.getTotalAttempts(),
                newAuthenticationStats.getRejectedAttempts(),
                newAuthenticationStats.getEnrollmentNotifications(),
                newAuthenticationStats.getModality());

        String expectedFrrStats = new JSONObject(buildFrrStats(authenticationStats))
                .put(ENROLLMENT_NOTIFICATIONS, newAuthenticationStats.getEnrollmentNotifications())
                .put(FACE_ATTEMPTS, newAuthenticationStats.getTotalAttempts())
                .put(FACE_REJECTIONS, newAuthenticationStats.getRejectedAttempts()).toString();
        verify(mEditor).putStringSet(eq(KEY), mStringSetArgumentCaptor.capture());
        assertThat(mStringSetArgumentCaptor.getValue()).contains(expectedFrrStats);
    }

    @Test
    public void persistFrrStats_multiUser_newUser_shouldUpdateRecord() throws JSONException {
        AuthenticationStats authenticationStats1 = new AuthenticationStats(USER_ID_1,
                300 /* totalAttempts */, 10 /* rejectedAttempts */,
                0 /* enrollmentNotifications */, BiometricsProtoEnums.MODALITY_FACE);
        AuthenticationStats authenticationStats2 = new AuthenticationStats(USER_ID_2,
                100 /* totalAttempts */, 5 /* rejectedAttempts */,
                1 /* enrollmentNotifications */, BiometricsProtoEnums.MODALITY_FINGERPRINT);

        // Sets up the shared preference with user 1 only.
        when(mSharedPreferences.getStringSet(eq(KEY), anySet())).thenReturn(
                Set.of(buildFrrStats(authenticationStats1)));

        // Add data for user 2.
        mAuthenticationStatsPersister.persistFrrStats(authenticationStats2.getUserId(),
                authenticationStats2.getTotalAttempts(),
                authenticationStats2.getRejectedAttempts(),
                authenticationStats2.getEnrollmentNotifications(),
                authenticationStats2.getModality());

        verify(mEditor).putStringSet(eq(KEY), mStringSetArgumentCaptor.capture());
        assertThat(mStringSetArgumentCaptor.getValue())
                .contains(buildFrrStats(authenticationStats2));
    }

    @Test
    public void removeFrrStats_existingUser_shouldUpdateRecord() throws JSONException {
        AuthenticationStats authenticationStats = new AuthenticationStats(USER_ID_1,
                300 /* totalAttempts */, 10 /* rejectedAttempts */,
                0 /* enrollmentNotifications */, BiometricsProtoEnums.MODALITY_FACE);
        when(mSharedPreferences.getStringSet(eq(KEY), anySet())).thenReturn(
                Set.of(buildFrrStats(authenticationStats)));

        mAuthenticationStatsPersister.removeFrrStats(USER_ID_1);

        verify(mEditor).putStringSet(eq(KEY), mStringSetArgumentCaptor.capture());
        assertThat(mStringSetArgumentCaptor.getValue()).doesNotContain(authenticationStats);
    }

    @Test
    public void persistFrrThreshold_shouldUpdateRecord() {
        mAuthenticationStatsPersister.persistFrrThreshold(FRR_THRESHOLD);

        verify(mEditor).putFloat(eq(THRESHOLD_KEY), mFrrThresholdArgumentCaptor.capture());
        assertThat(mFrrThresholdArgumentCaptor.getValue()).isWithin(0f).of(FRR_THRESHOLD);
    }

    private String buildFrrStats(AuthenticationStats authenticationStats)
            throws JSONException {
        if (authenticationStats.getModality() == BiometricsProtoEnums.MODALITY_FACE) {
            return new JSONObject()
                    .put(USER_ID, authenticationStats.getUserId())
                    .put(FACE_ATTEMPTS, authenticationStats.getTotalAttempts())
                    .put(FACE_REJECTIONS, authenticationStats.getRejectedAttempts())
                    .put(ENROLLMENT_NOTIFICATIONS, authenticationStats.getEnrollmentNotifications())
                    .toString();
        } else if (authenticationStats.getModality() == BiometricsProtoEnums.MODALITY_FINGERPRINT) {
            return new JSONObject()
                    .put(USER_ID, authenticationStats.getUserId())
                    .put(FINGERPRINT_ATTEMPTS, authenticationStats.getTotalAttempts())
                    .put(FINGERPRINT_REJECTIONS, authenticationStats.getRejectedAttempts())
                    .put(ENROLLMENT_NOTIFICATIONS, authenticationStats.getEnrollmentNotifications())
                    .toString();
        }
        return "";
    }
}
