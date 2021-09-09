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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.testing.UiEventLoggerFake;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.classifier.FalsingManagerFake;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.media.MediaHost;
import com.android.systemui.plugins.qs.QSTileView;
import com.android.systemui.qs.customize.QSCustomizerController;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.settings.brightness.BrightnessController;
import com.android.systemui.settings.brightness.BrightnessSlider;
import com.android.systemui.settings.brightness.ToggleSlider;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.util.animation.DisappearParameters;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class QSPanelControllerTest extends SysuiTestCase {

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
    private UiEventLogger mUiEventLogger = new UiEventLoggerFake();
    private DumpManager mDumpManager = new DumpManager();
    @Mock
    private TunerService mTunerService;
    @Mock
    private QSSecurityFooter mQSSecurityFooter;
    @Mock
    private QSLogger mQSLogger;
    @Mock
    private BrightnessController.Factory mBrightnessControllerFactory;
    @Mock
    private BrightnessController mBrightnessController;
    @Mock
    private BrightnessSlider.Factory mToggleSliderViewControllerFactory;
    @Mock
    private BrightnessSlider mBrightnessSlider;
    @Mock
    QSTileImpl mQSTile;
    @Mock
    QSTileView mQSTileView;
    @Mock
    PagedTileLayout mPagedTileLayout;
    @Mock
    CommandQueue mCommandQueue;
    FalsingManagerFake mFalsingManager = new FalsingManagerFake();

    private QSPanelController mController;

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mQSPanel.isAttachedToWindow()).thenReturn(true);
        when(mQSPanel.getDumpableTag()).thenReturn("QSPanel");
        when(mQSPanel.getOrCreateTileLayout()).thenReturn(mPagedTileLayout);
        when(mQSPanel.getTileLayout()).thenReturn(mPagedTileLayout);
        when(mQSPanel.getResources()).thenReturn(mContext.getResources());
        when(mQSTileHost.getTiles()).thenReturn(Collections.singleton(mQSTile));
        when(mQSTileHost.createTileView(any(), eq(mQSTile), anyBoolean())).thenReturn(mQSTileView);
        when(mToggleSliderViewControllerFactory.create(any(), any()))
                .thenReturn(mBrightnessSlider);
        when(mBrightnessControllerFactory.create(any(ToggleSlider.class)))
                .thenReturn(mBrightnessController);
        when(mQSTileRevealControllerFactory.create(any(), any()))
                .thenReturn(mQSTileRevealController);
        when(mMediaHost.getDisappearParameters()).thenReturn(new DisappearParameters());

        mController = new QSPanelController(mQSPanel, mQSSecurityFooter, mTunerService,
                mQSTileHost, mQSCustomizerController, true, mMediaHost,
                mQSTileRevealControllerFactory, mDumpManager, mMetricsLogger, mUiEventLogger,
                mQSLogger, mBrightnessControllerFactory, mToggleSliderViewControllerFactory,
                mFalsingManager, mCommandQueue
        );

        mController.init();
    }

    @Test
    public void testOpenDetailsWithNonExistingTile_NoException() {
        mController.openDetails("none");

        verify(mQSPanel, never()).openDetails(any());
    }
}
