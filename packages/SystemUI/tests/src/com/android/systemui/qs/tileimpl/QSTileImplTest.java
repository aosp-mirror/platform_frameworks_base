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

package com.android.systemui.qs.tileimpl;

import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.ACTION_QS_CLICK;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.ACTION_QS_LONG_PRESS;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.ACTION_QS_SECONDARY_CLICK;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_QS_POSITION;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_QS_VALUE;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_STATUS_BAR_STATE;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.TYPE_ACTION;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Matchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static java.lang.Thread.sleep;

import android.content.Intent;
import android.metrics.LogMaker;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.Dependency;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QSTileHost;
import com.android.systemui.statusbar.StatusBarState;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Captor;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class QSTileImplTest extends SysuiTestCase {

    public static final int POSITION = 14;
    private TestableLooper mTestableLooper;
    private TileImpl mTile;
    private QSTileHost mHost;
    private MetricsLogger mMetricsLogger;
    private StatusBarStateController mStatusBarStateController;

    @Captor
    private ArgumentCaptor<LogMaker> mLogCaptor;

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);
        String spec = "spec";
        mTestableLooper = TestableLooper.get(this);
        mDependency.injectTestDependency(Dependency.BG_LOOPER, mTestableLooper.getLooper());
        mMetricsLogger = mDependency.injectMockDependency(MetricsLogger.class);
        mStatusBarStateController =
            mDependency.injectMockDependency(StatusBarStateController.class);
        mHost = mock(QSTileHost.class);
        when(mHost.indexOf(spec)).thenReturn(POSITION);
        when(mHost.getContext()).thenReturn(mContext.getBaseContext());

        mTile = spy(new TileImpl(mHost));
        mTile.mHandler = mTile.new H(mTestableLooper.getLooper());
        mTile.setTileSpec(spec);
    }

    @Test
    public void testClick_Metrics() {
        mTile.click();
        verify(mMetricsLogger).write(argThat(new TileLogMatcher(ACTION_QS_CLICK)));
    }

    @Test
    public void testClick_Metrics_Status_Bar_Status() {
        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.SHADE);
        mTile.click();
        verify(mMetricsLogger).write(mLogCaptor.capture());
        assertEquals(StatusBarState.SHADE, mLogCaptor.getValue()
                .getTaggedData(FIELD_STATUS_BAR_STATE));
    }

    @Test
    public void testSecondaryClick_Metrics() {
        mTile.secondaryClick();
        verify(mMetricsLogger).write(argThat(new TileLogMatcher(ACTION_QS_SECONDARY_CLICK)));
    }

    @Test
    public void testSecondaryClick_Metrics_Status_Bar_Status() {
        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.KEYGUARD);
        mTile.secondaryClick();
        verify(mMetricsLogger).write(mLogCaptor.capture());
        assertEquals(StatusBarState.KEYGUARD, mLogCaptor.getValue()
                .getTaggedData(FIELD_STATUS_BAR_STATE));
    }

    @Test
    public void testLongClick_Metrics() {
        mTile.longClick();
        verify(mMetricsLogger).write(argThat(new TileLogMatcher(ACTION_QS_LONG_PRESS)));
    }

    @Test
    public void testLongClick_Metrics_Status_Bar_Status() {
        when(mStatusBarStateController.getState()).thenReturn(StatusBarState.SHADE_LOCKED);
        mTile.click();
        verify(mMetricsLogger).write(mLogCaptor.capture());
        assertEquals(StatusBarState.SHADE_LOCKED, mLogCaptor.getValue()
                .getTaggedData(FIELD_STATUS_BAR_STATE));
    }

    @Test
    public void testPopulateWithLockedScreen() {
        LogMaker maker = mock(LogMaker.class);
        when(maker.setSubtype(anyInt())).thenReturn(maker);
        when(maker.addTaggedData(anyInt(), any())).thenReturn(maker);
        mTile.getState().value = true;
        mTile.populate(maker);
        verify(maker).addTaggedData(eq(FIELD_QS_VALUE), eq(1));
        verify(maker).addTaggedData(eq(FIELD_QS_POSITION), eq(POSITION));
    }

    @Test
    public void testPopulateWithUnlockedScreen() {
        LogMaker maker = mock(LogMaker.class);
        when(maker.setSubtype(anyInt())).thenReturn(maker);
        when(maker.addTaggedData(anyInt(), any())).thenReturn(maker);
        mTile.getState().value = true;
        mTile.populate(maker);
        verify(maker).addTaggedData(eq(FIELD_QS_VALUE), eq(1));
        verify(maker).addTaggedData(eq(FIELD_QS_POSITION), eq(POSITION));
    }

    @Test
    @Ignore("flaky")
    public void testStaleTimeout() throws InterruptedException {
        when(mTile.getStaleTimeout()).thenReturn(5l);
        clearInvocations(mTile);

        mTile.handleRefreshState(null);
        mTestableLooper.processAllMessages();
        verify(mTile, never()).handleStale();

        sleep(10);
        mTestableLooper.processAllMessages();
        verify(mTile).handleStale();
    }

    @Test
    public void testStaleListening() {
        mTile.handleStale();
        mTestableLooper.processAllMessages();
        verify(mTile).handleSetListening(eq(true));

        mTile.handleRefreshState(null);
        mTestableLooper.processAllMessages();
        verify(mTile).handleSetListening(eq(false));
    }

    private class TileLogMatcher implements ArgumentMatcher<LogMaker> {

        private final int mCategory;
        public String mInvalid;

        public TileLogMatcher(int category) {
            mCategory = category;
        }

        @Override
        public boolean matches(LogMaker arg) {
            if (arg.getCategory() != mCategory) {
                mInvalid = "Expected category " + mCategory + " but was " + arg.getCategory();
                return false;
            }
            if (arg.getType() != TYPE_ACTION) {
                mInvalid = "Expected type " + TYPE_ACTION + " but was " + arg.getType();
                return false;
            }
            if (arg.getSubtype() != mTile.getMetricsCategory()) {
                mInvalid = "Expected subtype " + mTile.getMetricsCategory() + " but was "
                        + arg.getSubtype();
                return false;
            }
            return true;
        }

        @Override
        public String toString() {
            return mInvalid;
        }
    }

    private static class TileImpl extends QSTileImpl<QSTile.BooleanState> {
        protected TileImpl(QSHost host) {
            super(host);
        }

        @Override
        public BooleanState newTileState() {
            return new BooleanState();
        }

        @Override
        protected void handleClick() {

        }

        @Override
        protected void handleUpdateState(BooleanState state, Object arg) {

        }

        @Override
        public int getMetricsCategory() {
            return 42;
        }

        @Override
        public Intent getLongClickIntent() {
            return null;
        }

        @Override
        protected void handleSetListening(boolean listening) {

        }

        @Override
        public CharSequence getTileLabel() {
            return null;
        }
    }
}
