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

import static com.android.systemui.Flags.FLAG_QUICK_SETTINGS_VISUAL_HAPTICS_LONGPRESS;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.view.ContextThemeWrapper;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.logging.testing.UiEventLoggerFake;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.haptics.qs.QSLongPressEffect;
import com.android.systemui.kosmos.KosmosJavaAdapter;
import com.android.systemui.media.controls.ui.view.MediaHost;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.qs.customize.QSCustomizerController;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.policy.ResourcesSplitShadeStateController;
import com.android.systemui.util.animation.DisappearParameters;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import java.util.List;

import javax.inject.Provider;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class QSPanelControllerBaseTest extends SysuiTestCase {

    private final KosmosJavaAdapter mKosmos = new KosmosJavaAdapter(this);
    @Mock
    private QSPanel mQSPanel;
    @Mock
    private QSHost mQSHost;
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
    QSTile mOtherTile;
    @Mock
    PagedTileLayout mPagedTileLayout;
    @Mock
    Resources mResources;
    @Mock
    Configuration mConfiguration;
    @Mock
    Runnable mHorizontalLayoutListener;
    private TestableLongPressEffectProvider mLongPressEffectProvider =
            new TestableLongPressEffectProvider();

    private QSPanelControllerBase<QSPanel> mController;

    /** Implementation needed to ensure we have a reflectively-available class name. */
    private class TestableQSPanelControllerBase extends QSPanelControllerBase<QSPanel> {
        protected TestableQSPanelControllerBase(QSPanel view, QSHost host,
                QSCustomizerController qsCustomizerController, MediaHost mediaHost,
                MetricsLogger metricsLogger, UiEventLogger uiEventLogger, QSLogger qsLogger,
                DumpManager dumpManager) {
            super(view, host, qsCustomizerController, true, mediaHost, metricsLogger, uiEventLogger,
                    qsLogger, dumpManager, new ResourcesSplitShadeStateController(),
                    mLongPressEffectProvider);
        }

        @Override
        protected QSTileRevealController createTileRevealController() {
            return mQSTileRevealController;
        }
    }

    private class TestableLongPressEffectProvider implements Provider<QSLongPressEffect> {

        private int mEffectsProvided = 0;

        @Override
        public QSLongPressEffect get() {
            mEffectsProvided++;
            return mKosmos.getQsLongPressEffect();
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
        when(mQSHost.getTiles()).thenReturn(Collections.singleton(mQSTile));
        when(mQSTileRevealControllerFactory.create(any(), any()))
                .thenReturn(mQSTileRevealController);
        when(mMediaHost.getDisappearParameters()).thenReturn(new DisappearParameters());
        when(mQSPanel.getResources()).thenReturn(mResources);
        when(mQSPanel.getContext()).thenReturn(
                new ContextThemeWrapper(getContext(), R.style.Theme_SystemUI_QuickSettings));
        when(mResources.getConfiguration()).thenReturn(mConfiguration);
        doAnswer(invocation -> {
            when(mQSPanel.isListening()).thenReturn(invocation.getArgument(0));
            return null;
        }).when(mQSPanel).setListening(anyBoolean());

        mController = new TestableQSPanelControllerBase(mQSPanel, mQSHost,
                mQSCustomizerController, mMediaHost,
                mMetricsLogger, mUiEventLogger, mQSLogger, mDumpManager);

        mController.init();
        reset(mQSTileRevealController);
    }

    @Test
    public void testSetRevealExpansion_preAttach() {
        mController.onViewDetached();

        QSPanelControllerBase<QSPanel> controller = new TestableQSPanelControllerBase(mQSPanel,
                mQSHost, mQSCustomizerController, mMediaHost,
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
        String mockTileViewString = "QSTileViewImpl[locInScreen=(0, 0), "
                + "iconView=QSIconViewImpl[state=-1, tint=0], "
                + "tileState=false]";
        String mockTileString = "Mock Tile";
        doAnswer(invocation -> {
            PrintWriter pw = invocation.getArgument(0);
            pw.println(mockTileString);
            return null;
        }).when(mQSTile).dump(any(PrintWriter.class), any(String[].class));

        StringWriter w = new StringWriter();
        PrintWriter pw = new PrintWriter(w);
        mController.dump(pw, new String[]{});
        String expected = "TestableQSPanelControllerBase:\n"
                + "  Tile records:\n"
                + "    " + mockTileString + "\n"
                + "    " + mockTileViewString + "\n"
                + "  media bounds: null\n"
                + "  horizontal layout: false\n"
                + "  last orientation: 0\n"
                + "  mShouldUseSplitNotificationShade: false\n";
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
    public void testShouldUseHorizontalLayout_falseForSplitShade() {
        mConfiguration.orientation = Configuration.ORIENTATION_LANDSCAPE;
        mConfiguration.screenLayout = Configuration.SCREENLAYOUT_LONG_YES;
        when(mMediaHost.getVisible()).thenReturn(true);

        when(mResources.getBoolean(R.bool.config_use_split_notification_shade)).thenReturn(false);
        when(mQSPanel.getDumpableTag()).thenReturn("QSPanelLandscape");
        mController = new TestableQSPanelControllerBase(mQSPanel, mQSHost,
                mQSCustomizerController, mMediaHost,
                mMetricsLogger, mUiEventLogger, mQSLogger, mDumpManager);
        mController.init();

        assertThat(mController.shouldUseHorizontalLayout()).isTrue();

        when(mResources.getBoolean(R.bool.config_use_split_notification_shade)).thenReturn(true);
        when(mQSPanel.getDumpableTag()).thenReturn("QSPanelPortrait");
        mController = new TestableQSPanelControllerBase(mQSPanel, mQSHost,
                mQSCustomizerController, mMediaHost,
                mMetricsLogger, mUiEventLogger, mQSLogger, mDumpManager);
        mController.init();

        assertThat(mController.shouldUseHorizontalLayout()).isFalse();
    }

    @Test
    public void testChangeConfiguration_shouldUseHorizontalLayoutInLandscape_true() {
        when(mMediaHost.getVisible()).thenReturn(true);
        mController.setUsingHorizontalLayoutChangeListener(mHorizontalLayoutListener);

        // When device is rotated to landscape and is long
        mConfiguration.orientation = Configuration.ORIENTATION_LANDSCAPE;
        mConfiguration.screenLayout = Configuration.SCREENLAYOUT_LONG_YES;
        mController.mOnConfigurationChangedListener.onConfigurationChange(mConfiguration);

        // Then the layout changes
        assertThat(mController.shouldUseHorizontalLayout()).isTrue();
        verify(mHorizontalLayoutListener).run();

        // When it is rotated back to portrait
        mConfiguration.orientation = Configuration.ORIENTATION_PORTRAIT;
        mController.mOnConfigurationChangedListener.onConfigurationChange(mConfiguration);

        // Then the layout changes back
        assertThat(mController.shouldUseHorizontalLayout()).isFalse();
        verify(mHorizontalLayoutListener, times(2)).run();
    }

    @Test
    public void testChangeConfiguration_shouldUseHorizontalLayoutInLongDevices_true() {
        when(mMediaHost.getVisible()).thenReturn(true);
        mController.setUsingHorizontalLayoutChangeListener(mHorizontalLayoutListener);

        // When device is rotated to landscape and is long
        mConfiguration.orientation = Configuration.ORIENTATION_LANDSCAPE;
        mConfiguration.screenLayout = Configuration.SCREENLAYOUT_LONG_YES;
        mController.mOnConfigurationChangedListener.onConfigurationChange(mConfiguration);

        // Then the layout changes
        assertThat(mController.shouldUseHorizontalLayout()).isTrue();
        verify(mHorizontalLayoutListener).run();

        // When device changes to not-long
        mConfiguration.screenLayout = Configuration.SCREENLAYOUT_LONG_NO;
        mController.mOnConfigurationChangedListener.onConfigurationChange(mConfiguration);

        // Then the layout changes back
        assertThat(mController.shouldUseHorizontalLayout()).isFalse();
        verify(mHorizontalLayoutListener, times(2)).run();
    }

    @Test
    public void testRefreshAllTilesDoesntRefreshListeningTiles() {
        when(mQSHost.getTiles()).thenReturn(List.of(mQSTile, mOtherTile));
        mController.setTiles();

        when(mQSTile.isListening()).thenReturn(false);
        when(mOtherTile.isListening()).thenReturn(true);

        mController.refreshAllTiles();
        verify(mQSTile).refreshState();
        verify(mOtherTile, never()).refreshState();
    }

    @Test
    public void configurationChange_onlySplitShadeConfigChanges_horizontalLayoutStatusUpdated() {
        // Preconditions for horizontal layout
        when(mMediaHost.getVisible()).thenReturn(true);
        when(mResources.getBoolean(R.bool.config_use_split_notification_shade)).thenReturn(false);
        mConfiguration.orientation = Configuration.ORIENTATION_LANDSCAPE;
        mConfiguration.screenLayout = Configuration.SCREENLAYOUT_LONG_YES;
        mController.setUsingHorizontalLayoutChangeListener(mHorizontalLayoutListener);
        mController.mOnConfigurationChangedListener.onConfigurationChange(mConfiguration);
        assertThat(mController.shouldUseHorizontalLayout()).isTrue();
        reset(mHorizontalLayoutListener);

        // Only split shade status changes
        when(mResources.getBoolean(R.bool.config_use_split_notification_shade)).thenReturn(true);
        mController.mOnConfigurationChangedListener.onConfigurationChange(mConfiguration);

        // Horizontal layout is updated accordingly.
        assertThat(mController.shouldUseHorizontalLayout()).isFalse();
        verify(mHorizontalLayoutListener).run();
    }

    @Test
    public void changeTiles_callbackRemovedOnOldOnes() {
        // Start with one tile
        assertThat(mController.mRecords.size()).isEqualTo(1);
        QSPanelControllerBase.TileRecord record = mController.mRecords.get(0);

        assertThat(record.tile).isEqualTo(mQSTile);

        // Change to a different tile
        when(mQSHost.getTiles()).thenReturn(List.of(mOtherTile));
        mController.setTiles();

        verify(mQSTile).removeCallback(record.callback);
        verify(mOtherTile, never()).removeCallback(any());
        verify(mOtherTile, never()).removeCallbacks();
    }

    @Test
    public void onDestroy_removesJustTheAssociatedCallback() {
        QSPanelControllerBase.TileRecord record = mController.mRecords.get(0);

        mController.destroy();
        verify(mQSTile).removeCallback(record.callback);
        verify(mQSTile, never()).removeCallbacks();

        assertThat(mController.mRecords).isEmpty();
    }

    @Test
    public void onViewDettached_callbackNotRemoved() {
        QSPanelControllerBase.TileRecord record = mController.mRecords.get(0);

        mController.onViewDetached();
        verify(mQSTile, never()).removeCallback(record.callback);
        verify(mQSTile, never()).removeCallbacks();
    }

    @Test
    public void onInit_qsHostCallbackAdded() {
        verify(mQSHost).addCallback(any());
    }

    @Test
    public void onViewDettached_qsHostCallbackNotRemoved() {
        mController.onViewDetached();
        verify(mQSHost, never()).removeCallback(any());
    }

    @Test
    public void onDestroy_qsHostCallbackRemoved() {
        mController.destroy();
        verify(mQSHost).removeCallback(any());
    }

    @Test
    public void setTiles_sameTiles_doesntRemoveAndReaddViews() {
        when(mQSHost.getTiles()).thenReturn(List.of(mQSTile, mOtherTile));
        mController.setTiles();

        clearInvocations(mQSPanel);

        mController.setTiles();
        verify(mQSPanel, never()).removeTile(any());
        verify(mQSPanel, never()).addTile(any());
    }

    @Test
    @EnableFlags(FLAG_QUICK_SETTINGS_VISUAL_HAPTICS_LONGPRESS)
    public void setTiles_longPressEffectEnabled_nonNullLongPressEffectsAreProvided() {
        mLongPressEffectProvider.mEffectsProvided = 0;
        when(mQSHost.getTiles()).thenReturn(List.of(mQSTile, mOtherTile));
        mController.setTiles();

        // There is one non-null effect provided for each tile in the host
        assertThat(mLongPressEffectProvider.mEffectsProvided).isEqualTo(2);
    }

    @Test
    @DisableFlags(FLAG_QUICK_SETTINGS_VISUAL_HAPTICS_LONGPRESS)
    public void setTiles_longPressEffectDisabled_noLongPressEffectsAreProvided() {
        mLongPressEffectProvider.mEffectsProvided = 0;
        when(mQSHost.getTiles()).thenReturn(List.of(mQSTile, mOtherTile));
        mController.setTiles();

        assertThat(mLongPressEffectProvider.mEffectsProvided).isEqualTo(0);
    }

    @Test
    public void setTiles_differentTiles_extraTileRemoved() {
        when(mQSHost.getTiles()).thenReturn(List.of(mQSTile, mOtherTile));
        mController.setTiles();
        assertEquals(2, mController.mRecords.size());

        clearInvocations(mQSPanel);

        when(mQSHost.getTiles()).thenReturn(List.of(mQSTile));
        mController.setTiles();

        verify(mQSPanel, times(1)).removeTile(any());
        verify(mQSPanel, never()).addTile(any());
        assertEquals(1, mController.mRecords.size());
    }

    @Test
    public void detachAndReattach_sameTiles_doesntRemoveAndReAddViews() {
        when(mQSHost.getTiles()).thenReturn(List.of(mQSTile, mOtherTile));
        mController.setTiles();

        clearInvocations(mQSPanel);

        mController.onViewDetached();
        mController.onViewAttached();
        verify(mQSPanel, never()).removeTile(any());
        verify(mQSPanel, never()).addTile(any());
    }

    @Test
    public void setTiles_sameTilesDifferentOrder_removesAndReads() {
        when(mQSHost.getTiles()).thenReturn(List.of(mQSTile, mOtherTile));
        mController.setTiles();

        clearInvocations(mQSPanel);

        when(mQSHost.getTiles()).thenReturn(List.of(mOtherTile, mQSTile));
        mController.setTiles();

        verify(mQSPanel, times(2)).removeTile(any());
        verify(mQSPanel, times(2)).addTile(any());
    }

    @Test
    public void dettach_destroy_attach_tilesAreNotReadded() {
        when(mQSHost.getTiles()).thenReturn(List.of(mQSTile, mOtherTile));
        mController.setTiles();

        mController.onViewDetached();
        mController.destroy();
        mController.onViewAttached();

        assertThat(mController.mRecords).isEmpty();
    }
}
