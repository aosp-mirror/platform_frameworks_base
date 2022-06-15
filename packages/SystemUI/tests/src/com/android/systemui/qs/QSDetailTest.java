/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.qs;

import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.ACTION_QS_MORE_SETTINGS;

import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.testing.ViewUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.testing.UiEventLoggerFake;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.DetailAdapter;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class QSDetailTest extends SysuiTestCase {

    private MetricsLogger mMetricsLogger;
    private QSDetail mQsDetail;
    private QSPanelController mQsPanelController;
    private QuickStatusBarHeader mQuickHeader;
    private ActivityStarter mActivityStarter;
    private DetailAdapter mMockDetailAdapter;
    private TestableLooper mTestableLooper;
    private UiEventLoggerFake mUiEventLogger;
    private FrameLayout mParent;

    @Before
    public void setup() throws Exception {
        mTestableLooper = TestableLooper.get(this);
        mUiEventLogger = QSEvents.INSTANCE.setLoggerForTesting();

        mParent = new FrameLayout(mContext);
        mMetricsLogger = mDependency.injectMockDependency(MetricsLogger.class);
        mActivityStarter = mDependency.injectMockDependency(ActivityStarter.class);
        LayoutInflater.from(mContext).inflate(R.layout.qs_detail, mParent);
        mQsDetail = (QSDetail) mParent.getChildAt(0);

        mQsPanelController = mock(QSPanelController.class);
        mQuickHeader = mock(QuickStatusBarHeader.class);
        mQsDetail.setQsPanel(mQsPanelController, mQuickHeader, mock(QSFooter.class),
                mock(FalsingManager.class));
        mQsDetail.mClipper = mock(QSDetailClipper.class);

        mMockDetailAdapter = mock(DetailAdapter.class);
        when(mMockDetailAdapter.createDetailView(any(), any(), any()))
                .thenReturn(new View(mContext));

        // Only detail in use is the user detail
        when(mMockDetailAdapter.openDetailEvent())
                .thenReturn(QSUserSwitcherEvent.QS_USER_DETAIL_OPEN);
        when(mMockDetailAdapter.closeDetailEvent())
                .thenReturn(QSUserSwitcherEvent.QS_USER_DETAIL_CLOSE);
        when(mMockDetailAdapter.moreSettingsEvent())
                .thenReturn(QSUserSwitcherEvent.QS_USER_MORE_SETTINGS);
        ViewUtils.attachView(mParent);
    }

    @After
    public void tearDown() {
        QSEvents.INSTANCE.resetLogger();
        mTestableLooper.processAllMessages();
        ViewUtils.detachView(mParent);
    }

    @Test
    public void testShowDetail_Metrics() {
        mTestableLooper.processAllMessages();

        mQsDetail.handleShowingDetail(mMockDetailAdapter, 0, 0, false);
        verify(mMetricsLogger).visible(eq(mMockDetailAdapter.getMetricsCategory()));
        assertEquals(1, mUiEventLogger.numLogs());
        assertEquals(QSUserSwitcherEvent.QS_USER_DETAIL_OPEN.getId(), mUiEventLogger.eventId(0));
        mUiEventLogger.getLogs().clear();

        mQsDetail.handleShowingDetail(null, 0, 0, false);
        verify(mMetricsLogger).hidden(eq(mMockDetailAdapter.getMetricsCategory()));

        assertEquals(1, mUiEventLogger.numLogs());
        assertEquals(QSUserSwitcherEvent.QS_USER_DETAIL_CLOSE.getId(), mUiEventLogger.eventId(0));
    }

    @Test
    public void testShowDetail_ShouldAnimate() {
        mTestableLooper.processAllMessages();

        when(mMockDetailAdapter.shouldAnimate()).thenReturn(true);
        mQsDetail.setFullyExpanded(true);

        mQsDetail.handleShowingDetail(mMockDetailAdapter, 0, 0, false);
        verify(mQsDetail.mClipper).updateCircularClip(eq(true) /* animate */, anyInt(), anyInt(),
                eq(true) /* in */, any());
        clearInvocations(mQsDetail.mClipper);

        mQsDetail.handleShowingDetail(null, 0, 0, false);
        verify(mQsDetail.mClipper).updateCircularClip(eq(true) /* animate */, anyInt(), anyInt(),
                eq(false) /* in */, any());
    }

    @Test
    public void testShowDetail_ShouldNotAnimate() {
        mTestableLooper.processAllMessages();

        when(mMockDetailAdapter.shouldAnimate()).thenReturn(false);
        mQsDetail.setFullyExpanded(true);

        mQsDetail.handleShowingDetail(mMockDetailAdapter, 0, 0, false);
        verify(mQsDetail.mClipper).updateCircularClip(eq(false) /* animate */, anyInt(), anyInt(),
                eq(true) /* in */, any());
        clearInvocations(mQsDetail.mClipper);

        // Detail adapters should always animate on close. shouldAnimate() should only affect the
        // open transition
        mQsDetail.handleShowingDetail(null, 0, 0, false);
        verify(mQsDetail.mClipper).updateCircularClip(eq(true) /* animate */, anyInt(), anyInt(),
                eq(false) /* in */, any());
    }

    @Test
    public void testDoneButton_CloseDetailPanel() {
        mTestableLooper.processAllMessages();

        when(mMockDetailAdapter.onDoneButtonClicked()).thenReturn(false);

        mQsDetail.handleShowingDetail(mMockDetailAdapter, 0, 0, false);
        mQsDetail.requireViewById(android.R.id.button1).performClick();
        verify(mQsPanelController).closeDetail();
    }

    @Test
    public void testDoneButton_KeepDetailPanelOpen() {
        mTestableLooper.processAllMessages();

        when(mMockDetailAdapter.onDoneButtonClicked()).thenReturn(true);

        mQsDetail.handleShowingDetail(mMockDetailAdapter, 0, 0, false);
        mQsDetail.requireViewById(android.R.id.button1).performClick();
        verify(mQsPanelController, never()).closeDetail();
    }

    @Test
    public void testMoreSettingsButton() {
        mTestableLooper.processAllMessages();

        mQsDetail.handleShowingDetail(mMockDetailAdapter, 0, 0, false);
        mUiEventLogger.getLogs().clear();
        mQsDetail.requireViewById(android.R.id.button2).performClick();

        int metricsCategory = mMockDetailAdapter.getMetricsCategory();
        verify(mMetricsLogger).action(eq(ACTION_QS_MORE_SETTINGS), eq(metricsCategory));
        assertEquals(1, mUiEventLogger.numLogs());
        assertEquals(QSUserSwitcherEvent.QS_USER_MORE_SETTINGS.getId(), mUiEventLogger.eventId(0));

        verify(mActivityStarter).postStartActivityDismissingKeyguard(any(), anyInt());
    }

    @Test
    public void testNullAdapterClick() {
        DetailAdapter mock = mock(DetailAdapter.class);
        when(mock.moreSettingsEvent()).thenReturn(DetailAdapter.INVALID);
        mQsDetail.setupDetailFooter(mock);
        mQsDetail.requireViewById(android.R.id.button2).performClick();
    }
}
