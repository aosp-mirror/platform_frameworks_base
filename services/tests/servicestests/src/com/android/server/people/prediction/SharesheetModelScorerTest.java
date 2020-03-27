/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.people.prediction;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.prediction.AppTarget;
import android.app.prediction.AppTargetId;
import android.app.usage.UsageEvents;
import android.os.UserHandle;
import android.util.Range;

import com.android.server.people.data.DataManager;
import com.android.server.people.data.Event;
import com.android.server.people.data.EventHistory;
import com.android.server.people.data.EventIndex;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@RunWith(JUnit4.class)
public final class SharesheetModelScorerTest {

    private static final int USER_ID = 0;
    private static final String PACKAGE_1 = "pkg1";
    private static final String PACKAGE_2 = "pkg2";
    private static final String PACKAGE_3 = "pkg3";
    private static final String CLASS_1 = "cls1";
    private static final String CLASS_2 = "cls2";
    private static final double DELTA = 1e-6;
    private static final long NOW = System.currentTimeMillis();
    private static final Range<Long> WITHIN_ONE_DAY = new Range(
            NOW - Duration.ofHours(23).toMillis(),
            NOW - Duration.ofHours(22).toMillis());
    private static final Range<Long> TWO_DAYS_AGO = new Range(
            NOW - Duration.ofHours(50).toMillis(),
            NOW - Duration.ofHours(49).toMillis());
    private static final Range<Long> FIVE_DAYS_AGO = new Range(
            NOW - Duration.ofDays(6).toMillis(),
            NOW - Duration.ofDays(5).toMillis());
    private static final Range<Long> EIGHT_DAYS_AGO = new Range(
            NOW - Duration.ofDays(9).toMillis(),
            NOW - Duration.ofDays(8).toMillis());
    private static final Range<Long> TWELVE_DAYS_AGO = new Range(
            NOW - Duration.ofDays(13).toMillis(),
            NOW - Duration.ofDays(12).toMillis());
    private static final Range<Long> TWENTY_DAYS_AGO = new Range(
            NOW - Duration.ofDays(21).toMillis(),
            NOW - Duration.ofDays(20).toMillis());
    private static final Range<Long> FOUR_WEEKS_AGO = new Range(
            NOW - Duration.ofDays(29).toMillis(),
            NOW - Duration.ofDays(28).toMillis());

    @Mock
    private DataManager mDataManager;
    @Mock
    private EventHistory mEventHistory1;
    @Mock
    private EventHistory mEventHistory2;
    @Mock
    private EventHistory mEventHistory3;
    @Mock
    private EventHistory mEventHistory4;
    @Mock
    private EventHistory mEventHistory5;
    @Mock
    private EventIndex mEventIndex1;
    @Mock
    private EventIndex mEventIndex2;
    @Mock
    private EventIndex mEventIndex3;
    @Mock
    private EventIndex mEventIndex4;
    @Mock
    private EventIndex mEventIndex5;
    @Mock
    private EventIndex mEventIndex6;
    @Mock
    private EventIndex mEventIndex7;
    @Mock
    private EventIndex mEventIndex8;
    @Mock
    private EventIndex mEventIndex9;
    @Mock
    private EventIndex mEventIndex10;

    private ShareTargetPredictor.ShareTarget mShareTarget1;
    private ShareTargetPredictor.ShareTarget mShareTarget2;
    private ShareTargetPredictor.ShareTarget mShareTarget3;
    private ShareTargetPredictor.ShareTarget mShareTarget4;
    private ShareTargetPredictor.ShareTarget mShareTarget5;
    private ShareTargetPredictor.ShareTarget mShareTarget6;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mShareTarget1 = new ShareTargetPredictor.ShareTarget(
                new AppTarget.Builder(
                        new AppTargetId("cls1#pkg1"), PACKAGE_1, UserHandle.of(USER_ID))
                        .setClassName(CLASS_1).build(),
                mEventHistory1, null);
        mShareTarget2 = new ShareTargetPredictor.ShareTarget(
                new AppTarget.Builder(new AppTargetId("cls2#pkg1"), PACKAGE_1,
                        UserHandle.of(USER_ID)).setClassName(CLASS_2).build(),
                mEventHistory2, null);
        mShareTarget3 = new ShareTargetPredictor.ShareTarget(
                new AppTarget.Builder(
                        new AppTargetId("cls1#pkg2"), PACKAGE_2, UserHandle.of(USER_ID))
                        .setClassName(CLASS_1).build(),
                mEventHistory3, null);
        mShareTarget4 = new ShareTargetPredictor.ShareTarget(
                new AppTarget.Builder(
                        new AppTargetId("cls2#pkg2"), PACKAGE_2, UserHandle.of(USER_ID))
                        .setClassName(CLASS_2).build(),
                mEventHistory4, null);
        mShareTarget5 = new ShareTargetPredictor.ShareTarget(
                new AppTarget.Builder(
                        new AppTargetId("cls1#pkg3"), PACKAGE_3, UserHandle.of(USER_ID))
                        .setClassName(CLASS_1).build(),
                mEventHistory5, null);
        mShareTarget6 = new ShareTargetPredictor.ShareTarget(
                new AppTarget.Builder(
                        new AppTargetId("cls2#pkg3"), PACKAGE_3, UserHandle.of(USER_ID))
                        .setClassName(CLASS_2).build(),
                null, null);
    }

    @Test
    public void testComputeScore() {
        // Frequency and recency
        when(mEventHistory1.getEventIndex(anySet())).thenReturn(mEventIndex1);
        when(mEventHistory2.getEventIndex(anySet())).thenReturn(mEventIndex2);
        when(mEventHistory3.getEventIndex(anySet())).thenReturn(mEventIndex3);
        when(mEventHistory4.getEventIndex(anySet())).thenReturn(mEventIndex4);
        when(mEventHistory5.getEventIndex(anySet())).thenReturn(mEventIndex5);

        when(mEventIndex1.getActiveTimeSlots()).thenReturn(
                List.of(WITHIN_ONE_DAY, TWO_DAYS_AGO, FIVE_DAYS_AGO));
        when(mEventIndex2.getActiveTimeSlots()).thenReturn(List.of(TWO_DAYS_AGO, TWELVE_DAYS_AGO));
        when(mEventIndex3.getActiveTimeSlots()).thenReturn(List.of(FIVE_DAYS_AGO, TWENTY_DAYS_AGO));
        when(mEventIndex4.getActiveTimeSlots()).thenReturn(
                List.of(EIGHT_DAYS_AGO, TWELVE_DAYS_AGO, FOUR_WEEKS_AGO));
        when(mEventIndex5.getActiveTimeSlots()).thenReturn(List.of());

        when(mEventIndex1.getMostRecentActiveTimeSlot()).thenReturn(WITHIN_ONE_DAY);
        when(mEventIndex2.getMostRecentActiveTimeSlot()).thenReturn(TWO_DAYS_AGO);
        when(mEventIndex3.getMostRecentActiveTimeSlot()).thenReturn(FIVE_DAYS_AGO);
        when(mEventIndex4.getMostRecentActiveTimeSlot()).thenReturn(EIGHT_DAYS_AGO);
        when(mEventIndex5.getMostRecentActiveTimeSlot()).thenReturn(null);

        // Frequency of the same mime type
        when(mEventHistory1.getEventIndex(Event.TYPE_SHARE_TEXT)).thenReturn(mEventIndex6);
        when(mEventHistory2.getEventIndex(Event.TYPE_SHARE_TEXT)).thenReturn(mEventIndex7);
        when(mEventHistory3.getEventIndex(Event.TYPE_SHARE_TEXT)).thenReturn(mEventIndex8);
        when(mEventHistory4.getEventIndex(Event.TYPE_SHARE_TEXT)).thenReturn(mEventIndex9);
        when(mEventHistory5.getEventIndex(Event.TYPE_SHARE_TEXT)).thenReturn(mEventIndex10);

        when(mEventIndex6.getActiveTimeSlots()).thenReturn(List.of(TWO_DAYS_AGO));
        when(mEventIndex7.getActiveTimeSlots()).thenReturn(List.of(TWO_DAYS_AGO, TWELVE_DAYS_AGO));
        when(mEventIndex8.getActiveTimeSlots()).thenReturn(List.of());
        when(mEventIndex9.getActiveTimeSlots()).thenReturn(List.of(EIGHT_DAYS_AGO));
        when(mEventIndex10.getActiveTimeSlots()).thenReturn(List.of());

        SharesheetModelScorer.computeScore(
                List.of(mShareTarget1, mShareTarget2, mShareTarget3, mShareTarget4, mShareTarget5,
                        mShareTarget6),
                Event.TYPE_SHARE_TEXT,
                NOW);

        // Verification
        assertEquals(0.514f, mShareTarget1.getScore(), DELTA);
        assertEquals(0.475125f, mShareTarget2.getScore(), DELTA);
        assertEquals(0.33f, mShareTarget3.getScore(), DELTA);
        assertEquals(0.4411f, mShareTarget4.getScore(), DELTA);
        assertEquals(0f, mShareTarget5.getScore(), DELTA);
        assertEquals(0f, mShareTarget6.getScore(), DELTA);
    }

    @Test
    public void testComputeScoreForAppShare() {
        // Frequency and recency
        when(mEventHistory1.getEventIndex(anySet())).thenReturn(mEventIndex1);
        when(mEventHistory2.getEventIndex(anySet())).thenReturn(mEventIndex2);
        when(mEventHistory3.getEventIndex(anySet())).thenReturn(mEventIndex3);
        when(mEventHistory4.getEventIndex(anySet())).thenReturn(mEventIndex4);
        when(mEventHistory5.getEventIndex(anySet())).thenReturn(mEventIndex5);

        when(mEventIndex1.getActiveTimeSlots()).thenReturn(
                List.of(WITHIN_ONE_DAY, TWO_DAYS_AGO, FIVE_DAYS_AGO));
        when(mEventIndex2.getActiveTimeSlots()).thenReturn(List.of(TWO_DAYS_AGO, TWELVE_DAYS_AGO));
        when(mEventIndex3.getActiveTimeSlots()).thenReturn(List.of(FIVE_DAYS_AGO, TWENTY_DAYS_AGO));
        when(mEventIndex4.getActiveTimeSlots()).thenReturn(
                List.of(EIGHT_DAYS_AGO, TWELVE_DAYS_AGO, FOUR_WEEKS_AGO));
        when(mEventIndex5.getActiveTimeSlots()).thenReturn(List.of());

        when(mEventIndex1.getMostRecentActiveTimeSlot()).thenReturn(WITHIN_ONE_DAY);
        when(mEventIndex2.getMostRecentActiveTimeSlot()).thenReturn(TWO_DAYS_AGO);
        when(mEventIndex3.getMostRecentActiveTimeSlot()).thenReturn(FIVE_DAYS_AGO);
        when(mEventIndex4.getMostRecentActiveTimeSlot()).thenReturn(EIGHT_DAYS_AGO);
        when(mEventIndex5.getMostRecentActiveTimeSlot()).thenReturn(null);

        // Frequency of the same mime type
        when(mEventHistory1.getEventIndex(Event.TYPE_SHARE_TEXT)).thenReturn(mEventIndex6);
        when(mEventHistory2.getEventIndex(Event.TYPE_SHARE_TEXT)).thenReturn(mEventIndex7);
        when(mEventHistory3.getEventIndex(Event.TYPE_SHARE_TEXT)).thenReturn(mEventIndex8);
        when(mEventHistory4.getEventIndex(Event.TYPE_SHARE_TEXT)).thenReturn(mEventIndex9);
        when(mEventHistory5.getEventIndex(Event.TYPE_SHARE_TEXT)).thenReturn(mEventIndex10);

        when(mEventIndex6.getActiveTimeSlots()).thenReturn(List.of(TWO_DAYS_AGO));
        when(mEventIndex7.getActiveTimeSlots()).thenReturn(List.of(TWO_DAYS_AGO, TWELVE_DAYS_AGO));
        when(mEventIndex8.getActiveTimeSlots()).thenReturn(List.of());
        when(mEventIndex9.getActiveTimeSlots()).thenReturn(List.of(EIGHT_DAYS_AGO));
        when(mEventIndex10.getActiveTimeSlots()).thenReturn(List.of());

        SharesheetModelScorer.computeScoreForAppShare(
                List.of(mShareTarget1, mShareTarget2, mShareTarget3, mShareTarget4, mShareTarget5,
                        mShareTarget6),
                Event.TYPE_SHARE_TEXT, 20, NOW, mDataManager, USER_ID);

        // Verification
        assertEquals(0.514f, mShareTarget1.getScore(), DELTA);
        assertEquals(0.475125f, mShareTarget2.getScore(), DELTA);
        assertEquals(0.33f, mShareTarget3.getScore(), DELTA);
        assertEquals(0.4411f, mShareTarget4.getScore(), DELTA);
        assertEquals(0f, mShareTarget5.getScore(), DELTA);
        assertEquals(0f, mShareTarget6.getScore(), DELTA);
    }

    @Test
    public void testComputeScoreForAppShare_promoteFrequentlyUsedApps() {
        when(mEventHistory1.getEventIndex(anySet())).thenReturn(mEventIndex1);
        when(mEventHistory2.getEventIndex(anySet())).thenReturn(mEventIndex2);
        when(mEventHistory3.getEventIndex(anySet())).thenReturn(mEventIndex3);
        when(mEventHistory4.getEventIndex(anySet())).thenReturn(mEventIndex4);
        when(mEventHistory5.getEventIndex(anySet())).thenReturn(mEventIndex5);
        when(mEventHistory1.getEventIndex(Event.TYPE_SHARE_TEXT)).thenReturn(mEventIndex6);
        when(mEventHistory2.getEventIndex(Event.TYPE_SHARE_TEXT)).thenReturn(mEventIndex7);
        when(mEventHistory3.getEventIndex(Event.TYPE_SHARE_TEXT)).thenReturn(mEventIndex8);
        when(mEventHistory4.getEventIndex(Event.TYPE_SHARE_TEXT)).thenReturn(mEventIndex9);
        when(mEventHistory5.getEventIndex(Event.TYPE_SHARE_TEXT)).thenReturn(mEventIndex10);
        when(mDataManager.queryAppLaunchCount(anyInt(), anyLong(), anyLong(), anySet()))
                .thenReturn(
                        Map.of(PACKAGE_1, 1,
                                PACKAGE_2, 2,
                                PACKAGE_3, 3));

        SharesheetModelScorer.computeScoreForAppShare(
                List.of(mShareTarget1, mShareTarget2, mShareTarget3, mShareTarget4, mShareTarget5,
                        mShareTarget6),
                Event.TYPE_SHARE_TEXT, 20, NOW, mDataManager, USER_ID);

        verify(mDataManager, times(1)).queryAppLaunchCount(anyInt(), anyLong(), anyLong(),
                anySet());
        assertEquals(0.9f, mShareTarget5.getScore(), DELTA);
        assertEquals(0.81f, mShareTarget3.getScore(), DELTA);
        assertEquals(0.729f, mShareTarget1.getScore(), DELTA);
        assertEquals(0f, mShareTarget2.getScore(), DELTA);
        assertEquals(0f, mShareTarget4.getScore(), DELTA);
        assertEquals(0f, mShareTarget6.getScore(), DELTA);
    }

    @Test
    public void testComputeScoreForAppShare_skipPromoteFrequentlyUsedAppsWhenReachesLimit() {
        when(mEventHistory1.getEventIndex(anySet())).thenReturn(mEventIndex1);
        when(mEventHistory2.getEventIndex(anySet())).thenReturn(mEventIndex2);
        when(mEventHistory3.getEventIndex(anySet())).thenReturn(mEventIndex3);
        when(mEventHistory4.getEventIndex(anySet())).thenReturn(mEventIndex4);
        when(mEventHistory5.getEventIndex(anySet())).thenReturn(mEventIndex5);
        when(mEventHistory1.getEventIndex(Event.TYPE_SHARE_TEXT)).thenReturn(mEventIndex6);
        when(mEventHistory2.getEventIndex(Event.TYPE_SHARE_TEXT)).thenReturn(mEventIndex7);
        when(mEventHistory3.getEventIndex(Event.TYPE_SHARE_TEXT)).thenReturn(mEventIndex8);
        when(mEventHistory4.getEventIndex(Event.TYPE_SHARE_TEXT)).thenReturn(mEventIndex9);
        when(mEventHistory5.getEventIndex(Event.TYPE_SHARE_TEXT)).thenReturn(mEventIndex10);
        when(mEventIndex1.getMostRecentActiveTimeSlot()).thenReturn(WITHIN_ONE_DAY);
        when(mEventIndex2.getMostRecentActiveTimeSlot()).thenReturn(TWO_DAYS_AGO);
        when(mEventIndex3.getMostRecentActiveTimeSlot()).thenReturn(FIVE_DAYS_AGO);
        when(mEventIndex4.getMostRecentActiveTimeSlot()).thenReturn(EIGHT_DAYS_AGO);
        when(mEventIndex5.getMostRecentActiveTimeSlot()).thenReturn(null);
        when(mDataManager.queryAppLaunchCount(anyInt(), anyLong(), anyLong(), anySet()))
                .thenReturn(
                        Map.of(PACKAGE_1, 1,
                                PACKAGE_2, 2,
                                PACKAGE_3, 3));

        SharesheetModelScorer.computeScoreForAppShare(
                List.of(mShareTarget1, mShareTarget2, mShareTarget3, mShareTarget4, mShareTarget5,
                        mShareTarget6),
                Event.TYPE_SHARE_TEXT, 4, NOW, mDataManager, USER_ID);

        verify(mDataManager, never()).queryAppLaunchCount(anyInt(), anyLong(), anyLong(), anySet());
        assertEquals(0.4f, mShareTarget1.getScore(), DELTA);
        assertEquals(0.35f, mShareTarget2.getScore(), DELTA);
        assertEquals(0.33f, mShareTarget3.getScore(), DELTA);
        assertEquals(0.31f, mShareTarget4.getScore(), DELTA);
        assertEquals(0f, mShareTarget5.getScore(), DELTA);
        assertEquals(0f, mShareTarget6.getScore(), DELTA);
    }

    @Test
    public void testComputeScoreForAppShare_promoteForegroundApp() {
        when(mEventHistory1.getEventIndex(anySet())).thenReturn(mEventIndex1);
        when(mEventHistory2.getEventIndex(anySet())).thenReturn(mEventIndex2);
        when(mEventHistory3.getEventIndex(anySet())).thenReturn(mEventIndex3);
        when(mEventHistory4.getEventIndex(anySet())).thenReturn(mEventIndex4);
        when(mEventHistory5.getEventIndex(anySet())).thenReturn(mEventIndex5);
        when(mEventHistory1.getEventIndex(Event.TYPE_SHARE_TEXT)).thenReturn(mEventIndex6);
        when(mEventHistory2.getEventIndex(Event.TYPE_SHARE_TEXT)).thenReturn(mEventIndex7);
        when(mEventHistory3.getEventIndex(Event.TYPE_SHARE_TEXT)).thenReturn(mEventIndex8);
        when(mEventHistory4.getEventIndex(Event.TYPE_SHARE_TEXT)).thenReturn(mEventIndex9);
        when(mEventHistory5.getEventIndex(Event.TYPE_SHARE_TEXT)).thenReturn(mEventIndex10);
        when(mDataManager.queryAppMovingToForegroundEvents(anyInt(), anyLong(),
                anyLong())).thenReturn(
                List.of(createUsageEvent(PACKAGE_2),
                        createUsageEvent(PACKAGE_3),
                        createUsageEvent(SharesheetModelScorer.CHOOSER_ACTIVITY),
                        createUsageEvent(PACKAGE_3),
                        createUsageEvent(PACKAGE_3))
        );

        SharesheetModelScorer.computeScoreForAppShare(
                List.of(mShareTarget1, mShareTarget2, mShareTarget3, mShareTarget4, mShareTarget5,
                        mShareTarget6),
                Event.TYPE_SHARE_TEXT, 20, NOW, mDataManager, USER_ID);

        verify(mDataManager, times(1)).queryAppMovingToForegroundEvents(anyInt(), anyLong(),
                anyLong());
        assertEquals(0f, mShareTarget1.getScore(), DELTA);
        assertEquals(0f, mShareTarget2.getScore(), DELTA);
        assertEquals(SharesheetModelScorer.FOREGROUND_APP_WEIGHT, mShareTarget3.getScore(), DELTA);
        assertEquals(0f, mShareTarget4.getScore(), DELTA);
        assertEquals(0f, mShareTarget5.getScore(), DELTA);
        assertEquals(0f, mShareTarget6.getScore(), DELTA);
    }

    @Test
    public void testComputeScoreForAppShare_skipPromoteForegroundAppWhenNoValidForegroundApp() {
        when(mEventHistory1.getEventIndex(anySet())).thenReturn(mEventIndex1);
        when(mEventHistory2.getEventIndex(anySet())).thenReturn(mEventIndex2);
        when(mEventHistory3.getEventIndex(anySet())).thenReturn(mEventIndex3);
        when(mEventHistory4.getEventIndex(anySet())).thenReturn(mEventIndex4);
        when(mEventHistory5.getEventIndex(anySet())).thenReturn(mEventIndex5);
        when(mEventHistory1.getEventIndex(Event.TYPE_SHARE_TEXT)).thenReturn(mEventIndex6);
        when(mEventHistory2.getEventIndex(Event.TYPE_SHARE_TEXT)).thenReturn(mEventIndex7);
        when(mEventHistory3.getEventIndex(Event.TYPE_SHARE_TEXT)).thenReturn(mEventIndex8);
        when(mEventHistory4.getEventIndex(Event.TYPE_SHARE_TEXT)).thenReturn(mEventIndex9);
        when(mEventHistory5.getEventIndex(Event.TYPE_SHARE_TEXT)).thenReturn(mEventIndex10);
        when(mDataManager.queryAppMovingToForegroundEvents(anyInt(), anyLong(),
                anyLong())).thenReturn(
                List.of(createUsageEvent(PACKAGE_3),
                        createUsageEvent(PACKAGE_3),
                        createUsageEvent(SharesheetModelScorer.CHOOSER_ACTIVITY),
                        createUsageEvent(PACKAGE_3),
                        createUsageEvent(PACKAGE_3))
        );

        SharesheetModelScorer.computeScoreForAppShare(
                List.of(mShareTarget1, mShareTarget2, mShareTarget3, mShareTarget4, mShareTarget5,
                        mShareTarget6),
                Event.TYPE_SHARE_TEXT, 20, NOW, mDataManager, USER_ID);

        verify(mDataManager, times(1)).queryAppMovingToForegroundEvents(anyInt(), anyLong(),
                anyLong());
        assertEquals(0f, mShareTarget1.getScore(), DELTA);
        assertEquals(0f, mShareTarget2.getScore(), DELTA);
        assertEquals(0f, mShareTarget3.getScore(), DELTA);
        assertEquals(0f, mShareTarget4.getScore(), DELTA);
        assertEquals(0f, mShareTarget5.getScore(), DELTA);
        assertEquals(0f, mShareTarget6.getScore(), DELTA);
    }

    private static UsageEvents.Event createUsageEvent(String packageName) {
        UsageEvents.Event e = new UsageEvents.Event();
        e.mPackage = packageName;
        return e;
    }
}
