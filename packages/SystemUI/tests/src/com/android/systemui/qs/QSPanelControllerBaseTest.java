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

package com.android.systemui.qs;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.logging.testing.UiEventLoggerFake;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.media.MediaHost;
import com.android.systemui.plugins.qs.QSTileView;
import com.android.systemui.qs.customize.QSCustomizerController;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.util.animation.DisappearParameters;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class QSPanelControllerBaseTest extends SysuiTestCase {

    @Mock
    private QSPanel mQSPanel;
    @Mock
    private QSTileHost mQSTileHost;
    @Mock
    private QSCustomizerController mQSCustomizerController;
    @Mock
    private QSTileRevealController.Factory mQSTileRevealControllerFactory;
    @Mock
    private QSTileRevealController mQSTileRevealController;
    @Mock
    private MediaHost mMediaHost;
    @Mock
    private MetricsLogger mMetricsLogger;
    private UiEventLoggerFake mUiEventLogger = new UiEventLoggerFake();
    @Mock
    private QSLogger mQSLogger;
    private DumpManager mDumpManager = new DumpManager();
    @Mock
    QSTileImpl mQSTile;
    @Mock
    QSTileView mQSTileView;
    @Mock
    PagedTileLayout mPagedTileLayout;
    @Mock
    Resources mResources;
    @Mock
    Configuration mConfiguration;
    @Mock
    Runnable mHorizontalLayoutListener;

    private QSPanelControllerBase<QSPanel> mController;

    /** Implementation needed to ensure we have a reflectively-available class name. */
    private class TestableQSPanelControllerBase extends QSPanelControllerBase<QSPanel> {
        protected TestableQSPanelControllerBase(QSPanel view, QSTileHost host,
                QSCustomizerController qsCustomizerController, MediaHost mediaHost,
                MetricsLogger metricsLogger, UiEventLogger uiEventLogger, QSLogger qsLogger,
                DumpManager dumpManager) {
            super(view, host, qsCustomizerController, true, mediaHost, metricsLogger, uiEventLogger,
                    qsLogger, dumpManager);
        }

        @Override
        protected QSTileRevealController createTileRevealController() {
            return mQSTileRevealController;
        }
    }

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mQSPanel.isAttachedToWindow()).thenReturn(true);
        when(mQSPanel.getDumpableTag()).thenReturn("QSPanel");
        when(mQSPanel.openPanelEvent()).thenReturn(QSEvent.QS_PANEL_EXPANDED);
        when(mQSPanel.closePanelEvent()).thenReturn(QSEvent.QS_PANEL_COLLAPSED);
        when(mQSPanel.getOrCreateTileLayout()).thenReturn(mPagedTileLayout);
        when(mQSPanel.getTileLayout()).thenReturn(mPagedTileLayout);
        when(mQSTile.getTileSpec()).thenReturn("dnd");
        when(mQSTileHost.getTiles()).thenReturn(Collections.singleton(mQSTile));
        when(mQSTileHost.createTileView(any(), eq(mQSTile), anyBoolean())).thenReturn(mQSTileView);
        when(mQSTileRevealControllerFactory.create(any(), any()))
                .thenReturn(mQSTileRevealController);
        when(mMediaHost.getDisappearParameters()).thenReturn(new DisappearParameters());
        when(mQSPanel.getResources()).thenReturn(mResources);
        when(mResources.getConfiguration()).thenReturn(mConfiguration);

        mController = new TestableQSPanelControllerBase(mQSPanel, mQSTileHost,
                mQSCustomizerController, mMediaHost,
                mMetricsLogger, mUiEventLogger, mQSLogger, mDumpManager);

        mController.init();
        reset(mQSTileRevealController);
    }

    @Test
    public void testSetRevealExpansion_preAttach() {
        mController.onViewDetached();

        QSPanelControllerBase<QSPanel> controller = new TestableQSPanelControllerBase(mQSPanel,
                mQSTileHost, mQSCustomizerController, mMediaHost,
                mMetricsLogger, mUiEventLogger, mQSLogger, mDumpManager) {
            @Override
            protected QSTileRevealController createTileRevealController() {
                return mQSTileRevealController;
            }
        };

        // Nothing happens until attached
        controller.setRevealExpansion(0);
        verify(mQSTileRevealController, never()).setExpansion(anyFloat());
        controller.setRevealExpansion(0.5f);
        verify(mQSTileRevealController, never()).setExpansion(anyFloat());
        controller.setRevealExpansion(1);
        verify(mQSTileRevealController, never()).setExpansion(anyFloat());

        controller.init();
        verify(mQSTileRevealController).setExpansion(1);
    }

    @Test
    public void testSetRevealExpansion_postAttach() {
        mController.setRevealExpansion(0);
        verify(mQSTileRevealController).setExpansion(0);
        mController.setRevealExpansion(0.5f);
        verify(mQSTileRevealController).setExpansion(0.5f);
        mController.setRevealExpansion(1);
        verify(mQSTileRevealController).setExpansion(1);
    }


    @Test
    public void testSetExpanded_Metrics() {
        when(mQSPanel.isExpanded()).thenReturn(false);
        mController.setExpanded(true);
        verify(mMetricsLogger).visibility(eq(MetricsEvent.QS_PANEL), eq(true));
        verify(mQSLogger).logPanelExpanded(true, mQSPanel.getDumpableTag());
        assertEquals(1, mUiEventLogger.numLogs());
        assertEquals(QSEvent.QS_PANEL_EXPANDED.getId(), mUiEventLogger.eventId(0));
        mUiEventLogger.getLogs().clear();

        when(mQSPanel.isExpanded()).thenReturn(true);
        mController.setExpanded(false);
        verify(mMetricsLogger).visibility(eq(MetricsEvent.QS_PANEL), eq(false));
        verify(mQSLogger).logPanelExpanded(false, mQSPanel.getDumpableTag());
        assertEquals(1, mUiEventLogger.numLogs());
        assertEquals(QSEvent.QS_PANEL_COLLAPSED.getId(), mUiEventLogger.eventId(0));
        mUiEventLogger.getLogs().clear();

    }

    @Test
    public void testDump() {
        String mockTileViewString = "Mock Tile View";
        String mockTileString = "Mock Tile";
        doAnswer(invocation -> {
            PrintWriter pw = invocation.getArgument(1);
            pw.println(mockTileString);
            return null;
        }).when(mQSTile).dump(any(FileDescriptor.class), any(PrintWriter.class),
                any(String[].class));
        when(mQSTileView.toString()).thenReturn(mockTileViewString);

        StringWriter w = new StringWriter();
        PrintWriter pw = new PrintWriter(w);
        mController.dump(mock(FileDescriptor.class), pw, new String[]{});
        String expected = "TestableQSPanelControllerBase:\n"
                + "  Tile records:\n"
                + "    " + mockTileString + "\n"
                + "    " + mockTileViewString + "\n";
        assertEquals(expected, w.getBuffer().toString());
    }

    @Test
    public void setListening() {
        mController.setListening(true);
        verify(mQSLogger).logAllTilesChangeListening(true, "QSPanel", "dnd");
        verify(mPagedTileLayout).setListening(true, mUiEventLogger);

        mController.setListening(false);
        verify(mQSLogger).logAllTilesChangeListening(false, "QSPanel", "dnd");
        verify(mPagedTileLayout).setListening(false, mUiEventLogger);
    }


    @Test
    public void testShouldUzeHorizontalLayout_falseForSplitShade() {
        mConfiguration.orientation = Configuration.ORIENTATION_LANDSCAPE;
        when(mMediaHost.getVisible()).thenReturn(true);

        when(mResources.getBoolean(R.bool.config_use_split_notification_shade)).thenReturn(false);
        when(mQSPanel.getDumpableTag()).thenReturn("QSPanelLandscape");
        mController = new TestableQSPanelControllerBase(mQSPanel, mQSTileHost,
                mQSCustomizerController, mMediaHost,
                mMetricsLogger, mUiEventLogger, mQSLogger, mDumpManager);
        mController.init();

        assertThat(mController.shouldUseHorizontalLayout()).isTrue();

        when(mResources.getBoolean(R.bool.config_use_split_notification_shade)).thenReturn(true);
        when(mQSPanel.getDumpableTag()).thenReturn("QSPanelPortrait");
        mController = new TestableQSPanelControllerBase(mQSPanel, mQSTileHost,
                mQSCustomizerController, mMediaHost,
                mMetricsLogger, mUiEventLogger, mQSLogger, mDumpManager);
        mController.init();

        assertThat(mController.shouldUseHorizontalLayout()).isFalse();
    }

    @Test
    public void testChangeConfiguration_shouldUseHorizontalLayout() {
        when(mMediaHost.getVisible()).thenReturn(true);
        mController.setUsingHorizontalLayoutChangeListener(mHorizontalLayoutListener);

        // When device is rotated to landscape
        mConfiguration.orientation = Configuration.ORIENTATION_LANDSCAPE;
        mController.mOnConfigurationChangedListener.onConfigurationChange(mConfiguration);

        // Then the layout changes
        assertThat(mController.shouldUseHorizontalLayout()).isTrue();
        verify(mHorizontalLayoutListener).run(); // not invoked

        // When it is rotated back to portrait
        mConfiguration.orientation = Configuration.ORIENTATION_PORTRAIT;
        mController.mOnConfigurationChangedListener.onConfigurationChange(mConfiguration);

        // Then the layout changes back
        assertThat(mController.shouldUseHorizontalLayout()).isFalse();
        verify(mHorizontalLayoutListener, times(2)).run();
    }
}
