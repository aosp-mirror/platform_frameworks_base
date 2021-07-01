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

import static com.android.systemui.people.PeopleBackupFollowUpJob.SHARED_FOLLOW_UP;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.job.JobScheduler;
import android.app.people.IPeopleManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.people.widget.PeopleTileKey;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@RunWith(AndroidTestingRunner.class)
@SmallTest
public class PeopleBackupFollowUpJobTest extends SysuiTestCase {
    private static final String SHORTCUT_ID_1 = "101";
    private static final String PACKAGE_NAME_1 = "package_name";
    private static final int USER_ID_1 = 0;

    private static final PeopleTileKey PEOPLE_TILE_KEY =
            new PeopleTileKey(SHORTCUT_ID_1, USER_ID_1, PACKAGE_NAME_1);

    private static final String WIDGET_ID_STRING = "3";
    private static final String SECOND_WIDGET_ID_STRING = "12";
    private static final Set<String> WIDGET_IDS = new HashSet<>(
            Arrays.asList(WIDGET_ID_STRING, SECOND_WIDGET_ID_STRING));

    private static final Uri URI = Uri.parse("fake_uri");

    @Mock
    private PackageManager mPackageManager;
    @Mock
    private PackageInfo mPackageInfo;
    @Mock
    private IPeopleManager mIPeopleManager;
    @Mock
    private JobScheduler mJobScheduler;

    private final SharedPreferences mSp = PreferenceManager.getDefaultSharedPreferences(mContext);
    private final SharedPreferences.Editor mEditor = mSp.edit();
    private final SharedPreferences mFollowUpSp = mContext.getSharedPreferences(
            SHARED_FOLLOW_UP, Context.MODE_PRIVATE);
    private final SharedPreferences.Editor mFollowUpEditor = mFollowUpSp.edit();
    private final SharedPreferences mWidgetIdSp = mContext.getSharedPreferences(
            WIDGET_ID_STRING, Context.MODE_PRIVATE);
    private final SharedPreferences mSecondWidgetIdSp = mContext.getSharedPreferences(
            SECOND_WIDGET_ID_STRING, Context.MODE_PRIVATE);

    private PeopleBackupFollowUpJob mPeopleBackupFollowUpJob;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mPackageManager.getPackageInfoAsUser(any(), anyInt(), anyInt()))
                .thenReturn(mPackageInfo);
        when(mIPeopleManager.isConversation(any(), anyInt(), any())).thenReturn(true);

        mPeopleBackupFollowUpJob = new PeopleBackupFollowUpJob();
        mPeopleBackupFollowUpJob.setManagers(
                mContext, mPackageManager, mIPeopleManager, mJobScheduler);
    }

    @After
    public void tearDown() {
        mEditor.clear().commit();
        mFollowUpEditor.clear().commit();
        mWidgetIdSp.edit().clear().commit();
        mSecondWidgetIdSp.edit().clear().commit();
    }

    @Test
    public void testProcessFollowUpFile_shouldFollowUp() throws RemoteException {
        when(mIPeopleManager.isConversation(any(), anyInt(), any())).thenReturn(false);
        mFollowUpEditor.putStringSet(PEOPLE_TILE_KEY.toString(), WIDGET_IDS);
        mFollowUpEditor.apply();

        Map<String, Set<String>> remainingWidgets =
                mPeopleBackupFollowUpJob.processFollowUpFile(mFollowUpSp, mFollowUpEditor);
        mEditor.apply();
        mFollowUpEditor.apply();

        assertThat(remainingWidgets.size()).isEqualTo(1);
        assertThat(remainingWidgets.get(PEOPLE_TILE_KEY.toString()))
                .containsExactly(WIDGET_ID_STRING, SECOND_WIDGET_ID_STRING);
        assertThat(mFollowUpSp.getStringSet(PEOPLE_TILE_KEY.toString(), new HashSet<>()))
                .containsExactly(WIDGET_ID_STRING, SECOND_WIDGET_ID_STRING);
    }

    @Test
    public void testProcessFollowUpFile_shouldRestore() {
        mFollowUpEditor.putStringSet(PEOPLE_TILE_KEY.toString(), WIDGET_IDS);
        mFollowUpEditor.apply();

        Map<String, Set<String>> remainingWidgets =
                mPeopleBackupFollowUpJob.processFollowUpFile(mFollowUpSp, mFollowUpEditor);
        mEditor.apply();
        mFollowUpEditor.apply();

        assertThat(remainingWidgets).isEmpty();
        assertThat(mFollowUpSp.getStringSet(PEOPLE_TILE_KEY.toString(), new HashSet<>())).isEmpty();
    }

    @Test
    public void testShouldCancelJob_noRemainingWidgets_shouldCancel() {
        assertThat(mPeopleBackupFollowUpJob.shouldCancelJob(
                new HashMap<>(), 10, Duration.ofMinutes(1).toMillis())).isTrue();
    }

    @Test
    public void testShouldCancelJob_noRemainingWidgets_longTimeElapsed_shouldCancel() {
        assertThat(mPeopleBackupFollowUpJob.shouldCancelJob(
                new HashMap<>(), 10, Duration.ofHours(25).toMillis())).isTrue();
    }

    @Test
    public void testShouldCancelJob_remainingWidgets_shortTimeElapsed_shouldNotCancel() {
        Map<String, Set<String>> remainingWidgets = new HashMap<>();
        remainingWidgets.put(PEOPLE_TILE_KEY.toString(), WIDGET_IDS);
        assertThat(mPeopleBackupFollowUpJob.shouldCancelJob(remainingWidgets, 10, 1000)).isFalse();
    }

    @Test
    public void testShouldCancelJob_remainingWidgets_longTimeElapsed_shouldCancel() {
        Map<String, Set<String>> remainingWidgets = new HashMap<>();
        remainingWidgets.put(PEOPLE_TILE_KEY.toString(), WIDGET_IDS);
        assertThat(mPeopleBackupFollowUpJob.shouldCancelJob(
                remainingWidgets, 10, 1000 * 60 * 60 * 25)).isTrue();
    }

    @Test
    public void testCancelJobAndClearRemainingWidgets() {
        SharedPreferencesHelper.setPeopleTileKey(mWidgetIdSp, PEOPLE_TILE_KEY);
        SharedPreferencesHelper.setPeopleTileKey(mSecondWidgetIdSp, PEOPLE_TILE_KEY);
        mEditor.putStringSet(URI.toString(), WIDGET_IDS);
        mEditor.putString(WIDGET_ID_STRING, URI.toString());
        mEditor.putString(SECOND_WIDGET_ID_STRING, URI.toString());
        mEditor.apply();
        Map<String, Set<String>> remainingWidgets = new HashMap<>();
        remainingWidgets.put(PEOPLE_TILE_KEY.toString(), WIDGET_IDS);

        mPeopleBackupFollowUpJob.cancelJobAndClearRemainingWidgets(
                remainingWidgets, mFollowUpEditor, mSp);
        mEditor.apply();
        mFollowUpEditor.apply();

        verify(mJobScheduler, times(1)).cancel(anyInt());
        assertThat(mFollowUpSp.getAll()).isEmpty();
        assertThat(mWidgetIdSp.getAll()).isEmpty();
        assertThat(mSecondWidgetIdSp.getAll()).isEmpty();
        assertThat(mSp.getStringSet(PEOPLE_TILE_KEY.toString(), new HashSet<>())).isEmpty();
        assertThat(mSp.getStringSet(URI.toString(), new HashSet<>())).isEmpty();
        assertThat(mSp.getString(WIDGET_ID_STRING, null)).isNull();
        assertThat(mSp.getString(SECOND_WIDGET_ID_STRING, null)).isNull();
    }
}
