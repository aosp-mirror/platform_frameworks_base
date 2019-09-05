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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.filters.SmallTest;

import com.android.internal.util.CollectionUtils;
import com.android.systemui.DumpController;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.qs.QSFactory;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.qs.tileimpl.QSFactoryImpl;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.shared.plugins.PluginManager;
import com.android.systemui.statusbar.phone.AutoTileManager;
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

import javax.inject.Provider;

@RunWith(AndroidTestingRunner.class)
@SmallTest
@RunWithLooper
public class QSTileHostTest extends SysuiTestCase {

    private static String MOCK_STATE_STRING = "MockState";

    @Mock
    private StatusBarIconController mIconController;
    @Mock
    private QSFactoryImpl mDefaultFactory;
    @Mock
    private PluginManager mPluginManager;
    @Mock
    private TunerService mTunerService;
    @Mock
    private Provider<AutoTileManager> mAutoTiles;
    @Mock
    private DumpController mDumpController;
    @Mock
    private QSTile.State mMockState;
    private Handler mHandler;
    private TestableLooper mLooper;
    private QSTileHost mQSTileHost;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mLooper = TestableLooper.get(this);
        mHandler = new Handler(mLooper.getLooper());
        mQSTileHost = new TestQSTileHost(mContext, mIconController, mDefaultFactory, mHandler,
                mLooper.getLooper(),
                mPluginManager, mTunerService, mAutoTiles, mDumpController);
        setUpTileFactory();
        Settings.Secure.putStringForUser(mContext.getContentResolver(), QSTileHost.TILES_SETTING,
                "", ActivityManager.getCurrentUser());
    }

    private void setUpTileFactory() {
        when(mMockState.toString()).thenReturn(MOCK_STATE_STRING);
        when(mDefaultFactory.createTile(anyString())).thenAnswer(
                invocation -> {
                    String spec = invocation.getArgument(0);
                    switch (spec) {
                        case "spec1":
                            return new TestTile1(mQSTileHost);
                        case "spec2":
                            return new TestTile2(mQSTileHost);
                        default:
                            return null;
                    }
                });
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

    private static class TestQSTileHost extends QSTileHost {
        TestQSTileHost(Context context, StatusBarIconController iconController,
                QSFactoryImpl defaultFactory, Handler mainHandler, Looper bgLooper,
                PluginManager pluginManager, TunerService tunerService,
                Provider<AutoTileManager> autoTiles, DumpController dumpController) {
            super(context, iconController, defaultFactory, mainHandler, bgLooper, pluginManager,
                    tunerService, autoTiles, dumpController);
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
            // newSetting is not null, as it has just been set.
            if (!newSetting.equals(previousSetting)) {
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
        protected void handleSetListening(boolean listening) {}

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
}
