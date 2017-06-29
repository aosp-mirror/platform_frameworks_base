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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.qs.customize.QSCustomizer;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class QSPanelTest extends SysuiTestCase {

    private MetricsLogger mMetricsLogger;
    private QSPanel mQsPanel;
    private QSTileHost mHost;
    private QSCustomizer mCustomizer;

    @Before
    public void setup() throws Exception {
        TestableLooper.get(this).runWithLooper(() -> {
            mMetricsLogger = mDependency.injectMockDependency(MetricsLogger.class);
            mQsPanel = new QSPanel(mContext, null);
            mHost = mock(QSTileHost.class);
            when(mHost.getTiles()).thenReturn(Collections.emptyList());
            mCustomizer = mock(QSCustomizer.class);
            mQsPanel.setHost(mHost, mCustomizer);
        });
    }

    @Test
    public void testSetExpanded_Metrics() {
        mQsPanel.setExpanded(true);
        verify(mMetricsLogger).visibility(eq(MetricsEvent.QS_PANEL), eq(true));
        mQsPanel.setExpanded(false);
        verify(mMetricsLogger).visibility(eq(MetricsEvent.QS_PANEL), eq(false));
    }
}
