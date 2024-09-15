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

package com.android.systemui.statusbar.notification.row;

import static com.android.systemui.log.LogBufferHelperKt.logcatLogBuffer;
import static com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_ALL;
import static com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_CONTRACTED;
import static com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_EXPANDED;
import static com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_HEADS_UP;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNotSame;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.testing.TestableLooper;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.BindParams;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper
public class RowContentBindStageTest extends SysuiTestCase {

    private RowContentBindStage mRowContentBindStage;

    @Mock private NotificationRowContentBinder mBinder;
    @Mock private NotificationEntry mEntry;
    @Mock private ExpandableNotificationRow mRow;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mRowContentBindStage = new RowContentBindStage(
                mBinder,
                mock(NotifInflationErrorManager.class),
                new RowContentBindStageLogger(logcatLogBuffer()));
        mRowContentBindStage.createStageParams(mEntry);
    }

    @Test
    public void testRequireContentViews() {
        // WHEN inflation flags are set and pipeline is invalidated.
        final int flags = FLAG_CONTENT_VIEW_CONTRACTED | FLAG_CONTENT_VIEW_EXPANDED;
        RowContentBindParams params = mRowContentBindStage.getStageParams(mEntry);
        params.requireContentViews(flags);
        mRowContentBindStage.executeStage(mEntry, mRow, (en) -> { });

        // THEN binder binds inflation flags.
        verify(mBinder).bindContent(
                eq(mEntry),
                any(),
                eq(flags),
                any(),
                anyBoolean(),
                any());
    }

    @Test
    public void testFreeContentViews() {
        // GIVEN a view with all content bound.
        RowContentBindParams params = mRowContentBindStage.getStageParams(mEntry);
        params.requireContentViews(FLAG_CONTENT_VIEW_ALL);

        // WHEN inflation flags are cleared and stage executed.
        final int flags = FLAG_CONTENT_VIEW_CONTRACTED | FLAG_CONTENT_VIEW_EXPANDED;
        params.markContentViewsFreeable(flags);
        mRowContentBindStage.executeStage(mEntry, mRow, (en) -> { });

        // THEN binder unbinds flags.
        verify(mBinder).unbindContent(eq(mEntry), any(), eq(flags));
    }

    class CountingWtfHandler implements Log.TerribleFailureHandler {
        private Log.TerribleFailureHandler mOldHandler = null;
        private int mWtfCount = 0;

        public void register() {
            mOldHandler = Log.setWtfHandler(this);
        }

        public void unregister() {
            Log.setWtfHandler(mOldHandler);
            mOldHandler = null;
        }

        @Override
        public void onTerribleFailure(String tag, Log.TerribleFailure what, boolean system) {
            mWtfCount++;
        }

        public int getWtfCount() {
            return mWtfCount;
        }
    }

    @Test
    public void testGetStageParamsAfterCleanUp() {
        // GIVEN an entry whose params have already been deleted.
        RowContentBindParams originalParams = mRowContentBindStage.getStageParams(mEntry);
        mRowContentBindStage.deleteStageParams(mEntry);

        // WHEN a caller calls getStageParams.
        CountingWtfHandler countingWtfHandler = new CountingWtfHandler();
        countingWtfHandler.register();

        RowContentBindParams blankParams = mRowContentBindStage.getStageParams(mEntry);

        countingWtfHandler.unregister();

        // THEN getStageParams logs a WTF and returns blank params created to avoid a crash.
        assertEquals(1, countingWtfHandler.getWtfCount());
        assertNotNull(blankParams);
        assertNotSame(originalParams, blankParams);
    }

    @Test
    public void testTryGetStageParamsAfterCleanUp() {
        // GIVEN an entry whose params have already been deleted.
        mRowContentBindStage.deleteStageParams(mEntry);

        // WHEN a caller calls getStageParams.
        CountingWtfHandler countingWtfHandler = new CountingWtfHandler();
        countingWtfHandler.register();

        RowContentBindParams nullParams = mRowContentBindStage.tryGetStageParams(mEntry);

        countingWtfHandler.unregister();

        // THEN getStageParams does NOT log a WTF and returns null to indicate missing params.
        assertEquals(0, countingWtfHandler.getWtfCount());
        assertNull(nullParams);
    }

    @Test
    public void testRebindAllContentViews() {
        // GIVEN a view with content bound.
        RowContentBindParams params = mRowContentBindStage.getStageParams(mEntry);
        final int flags = FLAG_CONTENT_VIEW_CONTRACTED | FLAG_CONTENT_VIEW_EXPANDED;
        params.requireContentViews(flags);
        params.clearDirtyContentViews();

        // WHEN we request rebind and stage executed.
        params.rebindAllContentViews();
        mRowContentBindStage.executeStage(mEntry, mRow, (en) -> { });

        // THEN binder binds inflation flags.
        verify(mBinder).bindContent(
                eq(mEntry),
                any(),
                eq(flags),
                any(),
                anyBoolean(),
                any());
    }

    @Test
    public void testSetUseLowPriority() {
        // GIVEN a view with all content bound.
        RowContentBindParams params = mRowContentBindStage.getStageParams(mEntry);
        params.requireContentViews(FLAG_CONTENT_VIEW_ALL);
        params.clearDirtyContentViews();

        // WHEN low priority is set and stage executed.
        params.setUseMinimized(true);
        mRowContentBindStage.executeStage(mEntry, mRow, (en) -> { });

        // THEN binder is called with use low priority and contracted/expanded are called to bind.
        ArgumentCaptor<BindParams> bindParamsCaptor = ArgumentCaptor.forClass(BindParams.class);
        verify(mBinder).bindContent(
                eq(mEntry),
                any(),
                eq(FLAG_CONTENT_VIEW_CONTRACTED | FLAG_CONTENT_VIEW_EXPANDED),
                bindParamsCaptor.capture(),
                anyBoolean(),
                any());
        BindParams usedParams = bindParamsCaptor.getValue();
        assertTrue(usedParams.isMinimized);
    }

    @Test
    public void testSetUseIncreasedHeight() {
        // GIVEN a view with all content bound.
        RowContentBindParams params = mRowContentBindStage.getStageParams(mEntry);
        params.requireContentViews(FLAG_CONTENT_VIEW_ALL);
        params.clearDirtyContentViews();

        // WHEN use increased height is set and stage executed.
        params.setUseIncreasedCollapsedHeight(true);
        mRowContentBindStage.executeStage(mEntry, mRow, (en) -> { });

        // THEN binder is called with group view and contracted is bound.
        ArgumentCaptor<BindParams> bindParamsCaptor = ArgumentCaptor.forClass(BindParams.class);
        verify(mBinder).bindContent(
                eq(mEntry),
                any(),
                eq(FLAG_CONTENT_VIEW_CONTRACTED),
                bindParamsCaptor.capture(),
                anyBoolean(),
                any());
        BindParams usedParams = bindParamsCaptor.getValue();
        assertTrue(usedParams.usesIncreasedHeight);
    }

    @Test
    public void testSetUseIncreasedHeadsUpHeight() {
        // GIVEN a view with all content bound.
        RowContentBindParams params = mRowContentBindStage.getStageParams(mEntry);
        params.requireContentViews(FLAG_CONTENT_VIEW_ALL);
        params.clearDirtyContentViews();

        // WHEN use increased heads up height is set and stage executed.
        params.setUseIncreasedHeadsUpHeight(true);
        mRowContentBindStage.executeStage(mEntry, mRow, (en) -> { });

        // THEN binder is called with use group view and heads up is bound.
        ArgumentCaptor<BindParams> bindParamsCaptor = ArgumentCaptor.forClass(BindParams.class);
        verify(mBinder).bindContent(
                eq(mEntry),
                any(),
                eq(FLAG_CONTENT_VIEW_HEADS_UP),
                bindParamsCaptor.capture(),
                anyBoolean(),
                any());
        BindParams usedParams = bindParamsCaptor.getValue();
        assertTrue(usedParams.usesIncreasedHeadsUpHeight);
    }

    @Test
    public void testSetNeedsReinflation() {
        // GIVEN a view with all content bound.
        RowContentBindParams params = mRowContentBindStage.getStageParams(mEntry);
        params.requireContentViews(FLAG_CONTENT_VIEW_ALL);
        params.clearDirtyContentViews();

        // WHEN needs reinflation is set.
        params.setNeedsReinflation(true);
        mRowContentBindStage.executeStage(mEntry, mRow, (en) -> { });

        // THEN binder is called with forceInflate and all views are requested to bind.
        verify(mBinder).bindContent(
                eq(mEntry),
                any(),
                eq(FLAG_CONTENT_VIEW_ALL),
                any(),
                eq(true),
                any());
    }

    @Test
    public void testSupersedesPreviousContentViews() {
        // GIVEN a view with content view bind already in progress.
        RowContentBindParams params = mRowContentBindStage.getStageParams(mEntry);
        int defaultFlags = FLAG_CONTENT_VIEW_CONTRACTED | FLAG_CONTENT_VIEW_EXPANDED;
        params.requireContentViews(defaultFlags);
        mRowContentBindStage.executeStage(mEntry, mRow, (en) -> { });

        // WHEN we bind with another content view before the first finishes.
        params.requireContentViews(FLAG_CONTENT_VIEW_HEADS_UP);
        mRowContentBindStage.executeStage(mEntry, mRow, (en) -> { });

        // THEN binder is called with BOTH content views.
        verify(mBinder).bindContent(
                eq(mEntry),
                any(),
                eq(defaultFlags),
                any(),
                anyBoolean(),
                any());
        verify(mBinder).bindContent(
                eq(mEntry),
                any(),
                eq(defaultFlags | FLAG_CONTENT_VIEW_HEADS_UP),
                any(),
                anyBoolean(),
                any());
    }
}
