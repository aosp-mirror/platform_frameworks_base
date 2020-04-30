/*
 * Copyright (C) 2018 The Android Open Source Project
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


import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;
import static junit.framework.TestCase.assertFalse;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.UiEventLogger;
import com.android.internal.util.CollectionUtils;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.plugins.qs.QSFactory;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.qs.external.CustomTile;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.shared.plugins.PluginManager;
import com.android.systemui.statusbar.phone.AutoTileManager;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.tuner.TunerService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javax.inject.Provider;

@RunWith(AndroidTestingRunner.class)
@SmallTest
@RunWithLooper
public class QSTileHostTest extends SysuiTestCase {

    private static String MOCK_STATE_STRING = "MockState";
    private static ComponentName CUSTOM_TILE =
            ComponentName.unflattenFromString("TEST_PKG/.TEST_CLS");
    private static final String CUSTOM_TILE_SPEC = CustomTile.toSpec(CUSTOM_TILE);

    @Mock
    private StatusBarIconController mIconController;
    @Mock
    private QSFactory mDefaultFactory;
    @Mock
    private PluginManager mPluginManager;
    @Mock
    private TunerService mTunerService;
    @Mock
    private Provider<AutoTileManager> mAutoTiles;
    @Mock
    private DumpManager mDumpManager;
    @Mock
    private BroadcastDispatcher mBroadcastDispatcher;
    @Mock
    private QSTile.State mMockState;
    @Mock
    private StatusBar mStatusBar;
    @Mock
    private QSLogger mQSLogger;
    @Mock
    private CustomTile mCustomTile;
    @Mock
    private UiEventLogger mUiEventLogger;

    private Handler mHandler;
    private TestableLooper mLooper;
    private QSTileHost mQSTileHost;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mLooper = TestableLooper.get(this);
        mHandler = new Handler(mLooper.getLooper());
        mQSTileHost = new TestQSTileHost(mContext, mIconController, mDefaultFactory, mHandler,
                mLooper.getLooper(), mPluginManager, mTunerService, mAutoTiles, mDumpManager,
                mBroadcastDispatcher, mStatusBar, mQSLogger, mUiEventLogger);
        setUpTileFactory();

        // Override this config so there are no unexpected tiles
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.string.config_defaultExtraQuickSettingsTiles,
                "");

        Settings.Secure.putStringForUser(mContext.getContentResolver(), QSTileHost.TILES_SETTING,
                "", ActivityManager.getCurrentUser());
    }

    private void setUpTileFactory() {
        when(mMockState.toString()).thenReturn(MOCK_STATE_STRING);
        // Only create this kind of tiles
        when(mDefaultFactory.createTile(anyString())).thenAnswer(
                invocation -> {
                    String spec = invocation.getArgument(0);
                    if ("spec1".equals(spec)) {
                        return new TestTile1(mQSTileHost);
                    } else if ("spec2".equals(spec)) {
                        return new TestTile2(mQSTileHost);
                    } else if ("na".equals(spec)) {
                        return new NotAvailableTile(mQSTileHost);
                    } else if (CUSTOM_TILE_SPEC.equals(spec)) {
                        return mCustomTile;
                    } else {
                        return null;
                    }
                });
        when(mCustomTile.isAvailable()).thenReturn(true);
    }

    @Test
    public void testLoadTileSpecs_emptySetting() {
        List<String> tiles = QSTileHost.loadTileSpecs(mContext, "");
        assertFalse(tiles.isEmpty());
    }

    @Test
    public void testLoadTileSpecs_nullSetting() {
        List<String> tiles = QSTileHost.loadTileSpecs(mContext, null);
        assertFalse(tiles.isEmpty());
    }

    @Test
    public void testInvalidSpecUsesDefault() {
        mContext.getOrCreateTestableResources()
                .addOverride(R.string.quick_settings_tiles, "spec1,spec2");
        mQSTileHost.onTuningChanged(QSTileHost.TILES_SETTING, "not-valid");

        assertEquals(2, mQSTileHost.getTiles().size());
    }

    @Test
    public void testSpecWithInvalidDoesNotUseDefault() {
        mContext.getOrCreateTestableResources()
                .addOverride(R.string.quick_settings_tiles, "spec1,spec2");
        mQSTileHost.onTuningChanged(QSTileHost.TILES_SETTING, "spec2,not-valid");

        assertEquals(1, mQSTileHost.getTiles().size());
        QSTile element = CollectionUtils.firstOrNull(mQSTileHost.getTiles());
        assertTrue(element instanceof TestTile2);
    }

    @Test
    public void testDump() {
        mQSTileHost.onTuningChanged(QSTileHost.TILES_SETTING, "spec1,spec2");
        StringWriter w = new StringWriter();
        PrintWriter pw = new PrintWriter(w);
        mQSTileHost.dump(mock(FileDescriptor.class), pw, new String[]{});
        String output = "QSTileHost:\n"
                + TestTile1.class.getSimpleName() + ":\n"
                + "    " + MOCK_STATE_STRING + "\n"
                + TestTile2.class.getSimpleName() + ":\n"
                + "    " + MOCK_STATE_STRING + "\n";
        assertEquals(output, w.getBuffer().toString());
    }

    @Test
    public void testDefault() {
        mContext.getOrCreateTestableResources()
                .addOverride(R.string.quick_settings_tiles_default, "spec1");
        mQSTileHost.onTuningChanged(QSTileHost.TILES_SETTING, "default");
        assertEquals(1, mQSTileHost.getTiles().size());
        QSTile element = CollectionUtils.firstOrNull(mQSTileHost.getTiles());
        assertTrue(element instanceof TestTile1);
        verify(mQSLogger).logTileAdded("spec1");
    }

    @Test
    public void testDefaultAndExtra() {
        mContext.getOrCreateTestableResources()
                .addOverride(R.string.quick_settings_tiles_default, "spec1");
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.string.config_defaultExtraQuickSettingsTiles, "spec2");
        mQSTileHost.onTuningChanged(QSTileHost.TILES_SETTING, "default");
        assertEquals(2, mQSTileHost.getTiles().size());
        QSTile[] elements = mQSTileHost.getTiles().toArray(new QSTile[0]);
        assertTrue(elements[0] instanceof TestTile1);
        assertTrue(elements[1] instanceof TestTile2);

        verify(mQSLogger).logTileAdded("spec1");
        verify(mQSLogger).logTileAdded("spec2");
    }

    @Test
    public void testExtraCustom() {
        mContext.getOrCreateTestableResources().addOverride(
                com.android.internal.R.string.config_defaultExtraQuickSettingsTiles,
                CUSTOM_TILE_SPEC);
        mQSTileHost.onTuningChanged(QSTileHost.TILES_SETTING, "default");
        assertEquals(1, mQSTileHost.getTiles().size());
        assertEquals(mCustomTile, CollectionUtils.firstOrNull(mQSTileHost.getTiles()));

        verify(mQSLogger).logTileAdded(CUSTOM_TILE_SPEC);
    }

    @Test
    public void testNoRepeatedSpecs_addTile() {
        mQSTileHost.onTuningChanged(QSTileHost.TILES_SETTING, "spec1,spec2");

        mQSTileHost.addTile("spec1");

        assertEquals(2, mQSTileHost.mTileSpecs.size());
        assertEquals("spec1", mQSTileHost.mTileSpecs.get(0));
        assertEquals("spec2", mQSTileHost.mTileSpecs.get(1));
    }

    @Test
    public void testNoRepeatedSpecs_customTile() {
        mQSTileHost.onTuningChanged(QSTileHost.TILES_SETTING, CUSTOM_TILE_SPEC);

        mQSTileHost.addTile(CUSTOM_TILE);

        assertEquals(1, mQSTileHost.mTileSpecs.size());
        assertEquals(CUSTOM_TILE_SPEC, mQSTileHost.mTileSpecs.get(0));
    }

    @Test
    public void testLoadTileSpec_repeated() {
        List<String> specs = QSTileHost.loadTileSpecs(mContext, "spec1,spec1,spec2");

        assertEquals(2, specs.size());
        assertEquals("spec1", specs.get(0));
        assertEquals("spec2", specs.get(1));
    }

    @Test
    public void testLoadTileSpec_repeatedInDefault() {
        mContext.getOrCreateTestableResources()
                .addOverride(R.string.quick_settings_tiles_default, "spec1,spec1");
        List<String> specs = QSTileHost.loadTileSpecs(mContext, "default");

        // Remove spurious tiles, like dbg:mem
        specs.removeIf(spec -> !"spec1".equals(spec));
        assertEquals(1, specs.size());
    }

    @Test
    public void testLoadTileSpec_repeatedDefaultAndSetting() {
        mContext.getOrCreateTestableResources()
                .addOverride(R.string.quick_settings_tiles_default, "spec1");
        List<String> specs = QSTileHost.loadTileSpecs(mContext, "default,spec1");

        // Remove spurious tiles, like dbg:mem
        specs.removeIf(spec -> !"spec1".equals(spec));
        assertEquals(1, specs.size());
    }

    @Test
    public void testNotAvailableTile_specNotNull() {
        mQSTileHost.onTuningChanged(QSTileHost.TILES_SETTING, "na");
        verify(mQSLogger, never()).logTileDestroyed(isNull(), anyString());
    }

    private static class TestQSTileHost extends QSTileHost {
        TestQSTileHost(Context context, StatusBarIconController iconController,
                QSFactory defaultFactory, Handler mainHandler, Looper bgLooper,
                PluginManager pluginManager, TunerService tunerService,
                Provider<AutoTileManager> autoTiles, DumpManager dumpManager,
                BroadcastDispatcher broadcastDispatcher, StatusBar statusBar, QSLogger qsLogger,
                UiEventLogger uiEventLogger) {
            super(context, iconController, defaultFactory, mainHandler, bgLooper, pluginManager,
                    tunerService, autoTiles, dumpManager, broadcastDispatcher,
                    Optional.of(statusBar), qsLogger, uiEventLogger);
        }

        @Override
        public void onPluginConnected(QSFactory plugin, Context pluginContext) {
        }

        @Override
        public void onPluginDisconnected(QSFactory plugin) {
        }

        @Override
        public void changeTiles(List<String> previousTiles, List<String> newTiles) {
            String previousSetting = Settings.Secure.getStringForUser(
                    getContext().getContentResolver(), TILES_SETTING,
                    ActivityManager.getCurrentUser());
            super.changeTiles(previousTiles, newTiles);
            // After tiles are changed, make sure to call onTuningChanged with the new setting if it
            // changed
            String newSetting = Settings.Secure.getStringForUser(getContext().getContentResolver(),
                    TILES_SETTING, ActivityManager.getCurrentUser());
            if (!Objects.equals(newSetting, previousSetting)) {
                onTuningChanged(TILES_SETTING, newSetting);
            }
        }
    }

    private class TestTile extends QSTileImpl<QSTile.State> {

        protected TestTile(QSHost host) {
            super(host);
        }

        @Override
        public State newTileState() {
            return mMockState;
        }

        @Override
        public State getState() {
            return mMockState;
        }

        @Override
        protected void handleClick() {}

        @Override
        protected void handleUpdateState(State state, Object arg) {}

        @Override
        public int getMetricsCategory() {
            return 0;
        }

        @Override
        public Intent getLongClickIntent() {
            return null;
        }

        @Override
        public CharSequence getTileLabel() {
            return null;
        }
    }

    private class TestTile1 extends TestTile {

        protected TestTile1(QSHost host) {
            super(host);
        }
    }

    private class TestTile2 extends TestTile {

        protected TestTile2(QSHost host) {
            super(host);
        }
    }

    private class NotAvailableTile extends TestTile {

        protected NotAvailableTile(QSHost host) {
            super(host);
        }

        @Override
        public boolean isAvailable() {
            return false;
        }
    }
}
