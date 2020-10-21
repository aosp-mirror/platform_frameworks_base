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

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.logging.testing.UiEventLoggerFake;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.media.MediaHost;
import com.android.systemui.plugins.qs.QSTileView;
import com.android.systemui.qs.tileimpl.QSTileImpl;

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
    private MediaHost mMediaHost;
    @Mock
    private MetricsLogger mMetricsLogger;
    private UiEventLoggerFake mUiEventLogger = new UiEventLoggerFake();
    private DumpManager mDumpManager = new DumpManager();
    @Mock
    QSTileImpl mQSTile;
    @Mock
    QSTileView mQSTileView;

    private QSPanelControllerBase<QSPanel> mController;

    /** Implementation needed to ensure we have a reflectively-available class name. */
    private static class TestableQSPanelControllerBase extends QSPanelControllerBase<QSPanel> {
        protected TestableQSPanelControllerBase(QSPanel view, QSTileHost host,
                MetricsLogger metricsLogger,
                UiEventLogger uiEventLogger, DumpManager dumpManager) {
            super(view, host, metricsLogger, uiEventLogger, dumpManager);
        }
    }

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mQSPanel.getMediaHost()).thenReturn(mMediaHost);
        when(mQSPanel.isAttachedToWindow()).thenReturn(true);
        when(mQSPanel.getDumpableTag()).thenReturn("QSPanel");
        when(mQSPanel.openPanelEvent()).thenReturn(QSEvent.QS_PANEL_EXPANDED);
        when(mQSPanel.closePanelEvent()).thenReturn(QSEvent.QS_PANEL_COLLAPSED);
        when(mQSTileHost.getTiles()).thenReturn(Collections.singleton(mQSTile));
        when(mQSTileHost.createTileView(eq(mQSTile), anyBoolean())).thenReturn(mQSTileView);

        mController = new TestableQSPanelControllerBase(mQSPanel, mQSTileHost,
                mMetricsLogger, mUiEventLogger, mDumpManager);

        mController.init();
    }

    @Test
    public void testSetExpanded_Metrics() {
        mController.setExpanded(true);
        verify(mMetricsLogger).visibility(eq(MetricsEvent.QS_PANEL), eq(true));
        assertEquals(1, mUiEventLogger.numLogs());
        assertEquals(QSEvent.QS_PANEL_EXPANDED.getId(), mUiEventLogger.eventId(0));
        mUiEventLogger.getLogs().clear();

        mController.setExpanded(false);
        verify(mMetricsLogger).visibility(eq(MetricsEvent.QS_PANEL), eq(false));
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

}
