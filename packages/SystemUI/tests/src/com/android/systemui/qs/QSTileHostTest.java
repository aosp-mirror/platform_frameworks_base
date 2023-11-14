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


import static com.android.systemui.Flags.FLAG_QS_NEW_PIPELINE;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.testing.AndroidTestingRunner;
import android.util.SparseArray;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.util.CollectionUtils;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.classifier.FalsingManagerFake;
import com.android.systemui.dump.nano.SystemUIProtoDump;
import com.android.systemui.flags.FakeFeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.PluginManager;
import com.android.systemui.plugins.qs.QSFactory;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.external.CustomTile;
import com.android.systemui.qs.external.CustomTileStatePersister;
import com.android.systemui.qs.external.TileLifecycleManager;
import com.android.systemui.qs.external.TileServiceKey;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.pipeline.shared.QSPipelineFlagsRepository;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.qs.tiles.di.NewQSTileFactory;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserFileManager;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.statusbar.phone.AutoTileManager;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.util.FakeSharedPreferences;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.settings.FakeSettings;
import com.android.systemui.util.settings.SecureSettings;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Provider;

import dagger.Lazy;

@RunWith(AndroidTestingRunner.class)
@SmallTest
public class QSTileHostTest extends SysuiTestCase {

    private static String MOCK_STATE_STRING = "MockState";
    private static ComponentName CUSTOM_TILE =
            ComponentName.unflattenFromString("TEST_PKG/.TEST_CLS");
    private static final String CUSTOM_TILE_SPEC = CustomTile.toSpec(CUSTOM_TILE);
    private static final String SETTING = QSHost.TILES_SETTING;
    @Mock
    private PluginManager mPluginManager;
    @Mock
    private TunerService mTunerService;
    @Mock
    private AutoTileManager mAutoTiles;
    @Mock
    private ShadeController mShadeController;
    @Mock
    private QSLogger mQSLogger;
    @Mock
    private CustomTile mCustomTile;
    @Mock
    private UserTracker mUserTracker;
    @Mock
    private CustomTileStatePersister mCustomTileStatePersister;
    @Mock
    private TileLifecycleManager.Factory mTileLifecycleManagerFactory;
    @Mock
    private TileLifecycleManager mTileLifecycleManager;
    @Mock
    private UserFileManager mUserFileManager;

    private SecureSettings mSecureSettings;

    private QSFactory mDefaultFactory;

    private SparseArray<SharedPreferences> mSharedPreferencesByUser;

    private FakeFeatureFlags mFeatureFlags;

    private QSPipelineFlagsRepository mQSPipelineFlagsRepository;

    private FakeExecutor mMainExecutor;

    private QSTileHost mQSTileHost;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mFeatureFlags = new FakeFeatureFlags();

        mSetFlagsRule.disableFlags(FLAG_QS_NEW_PIPELINE);
        // TODO(b/299909337): Add test checking the new factory is used when the flag is on
        mFeatureFlags.set(Flags.QS_PIPELINE_NEW_TILES, false);
        mQSPipelineFlagsRepository = new QSPipelineFlagsRepository(mFeatureFlags);

        mMainExecutor = new FakeExecutor(new FakeSystemClock());

        mSharedPreferencesByUser = new SparseArray<>();
        when(mTileLifecycleManagerFactory
                .create(any(Intent.class), any(UserHandle.class)))
                .thenReturn(mTileLifecycleManager);
        when(mUserFileManager.getSharedPreferences(anyString(), anyInt(), anyInt()))
                .thenAnswer((Answer<SharedPreferences>) invocation -> {
                    assertEquals(QSTileHost.TILES, invocation.getArgument(0));
                    int userId = invocation.getArgument(2);
                    if (!mSharedPreferencesByUser.contains(userId)) {
                        mSharedPreferencesByUser.put(userId, new FakeSharedPreferences());
                    }
                    return mSharedPreferencesByUser.get(userId);
                });

        mSecureSettings = new FakeSettings();
        saveSetting("");
        setUpTileFactory();
        mQSTileHost = new TestQSTileHost(mContext, () -> null, mDefaultFactory, mMainExecutor,
                mPluginManager, mTunerService, () -> mAutoTiles, mShadeController,
                mQSLogger, mUserTracker, mSecureSettings, mCustomTileStatePersister,
                mTileLifecycleManagerFactory, mUserFileManager, mQSPipelineFlagsRepository);
        mMainExecutor.runAllReady();

        mSecureSettings.registerContentObserverForUser(SETTING, new ContentObserver(null) {
            @Override
            public void onChange(boolean selfChange) {
                super.onChange(selfChange);
                mMainExecutor.execute(() -> mQSTileHost.onTuningChanged(SETTING, getSetting()));
                mMainExecutor.runAllReady();
            }
        }, mUserTracker.getUserId());
    }

    private void saveSetting(String value) {
        mSecureSettings.putStringForUser(
                SETTING, value, "", false, mUserTracker.getUserId(), false);
    }

    private String getSetting() {
        return mSecureSettings.getStringForUser(SETTING, mUserTracker.getUserId());
    }

    private void setUpTileFactory() {
        mDefaultFactory = new FakeQSFactory(spec -> {
            if ("spec1".equals(spec)) {
                return new TestTile1(mQSTileHost);
            } else if ("spec2".equals(spec)) {
                return new TestTile2(mQSTileHost);
            } else if ("spec3".equals(spec)) {
                return new TestTile3(mQSTileHost);
            } else if ("na".equals(spec)) {
                return new NotAvailableTile(mQSTileHost);
            } else if (CUSTOM_TILE_SPEC.equals(spec)) {
                QSTile tile = mCustomTile;
                QSTile.State s = mock(QSTile.State.class);
                s.spec = spec;
                when(mCustomTile.getState()).thenReturn(s);
                return tile;
            } else if ("internet".equals(spec)
                    || "wifi".equals(spec)
                    || "cell".equals(spec)) {
                return new TestTile1(mQSTileHost);
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
        saveSetting("not-valid");

        assertEquals(2, mQSTileHost.getTiles().size());
    }

    @Test
    public void testRemoveWifiAndCellularWithoutInternet() {
        saveSetting("wifi, spec1, cell, spec2");

        assertEquals("internet", mQSTileHost.getSpecs().get(0));
        assertEquals("spec1", mQSTileHost.getSpecs().get(1));
        assertEquals("spec2", mQSTileHost.getSpecs().get(2));
    }

    @Test
    public void testRemoveWifiAndCellularWithInternet() {
        saveSetting("wifi, spec1, cell, spec2, internet");

        assertEquals("spec1", mQSTileHost.getSpecs().get(0));
        assertEquals("spec2", mQSTileHost.getSpecs().get(1));
        assertEquals("internet", mQSTileHost.getSpecs().get(2));
    }

    @Test
    public void testRemoveWifiWithoutInternet() {
        saveSetting("spec1, wifi, spec2");

        assertEquals("spec1", mQSTileHost.getSpecs().get(0));
        assertEquals("internet", mQSTileHost.getSpecs().get(1));
        assertEquals("spec2", mQSTileHost.getSpecs().get(2));
    }

    @Test
    public void testRemoveCellWithInternet() {
        saveSetting("spec1, spec2, cell, internet");

        assertEquals("spec1", mQSTileHost.getSpecs().get(0));
        assertEquals("spec2", mQSTileHost.getSpecs().get(1));
        assertEquals("internet", mQSTileHost.getSpecs().get(2));
    }

    @Test
    public void testNoWifiNoCellularNoInternet() {
        saveSetting("spec1,spec2");

        assertEquals("spec1", mQSTileHost.getSpecs().get(0));
        assertEquals("spec2", mQSTileHost.getSpecs().get(1));
    }

    @Test
    public void testSpecWithInvalidDoesNotUseDefault() {
        mContext.getOrCreateTestableResources()
                .addOverride(R.string.quick_settings_tiles, "spec1,spec2");
        saveSetting("spec2,not-valid");

        assertEquals(1, mQSTileHost.getTiles().size());
        QSTile element = CollectionUtils.firstOrNull(mQSTileHost.getTiles());
        assertTrue(element instanceof TestTile2);
    }

    @Test
    public void testDump() {
        saveSetting("spec1,spec2");
        StringWriter w = new StringWriter();
        PrintWriter pw = new PrintWriter(w);
        mQSTileHost.dump(pw, new String[]{});

        String output = "QSTileHost:" + "\n"
                + "tile specs: [spec1, spec2]" + "\n"
                + "current user: 0" + "\n"
                + "is dirty: false" + "\n"
                + "tiles:" + "\n"
                + "TestTile1:" + "\n"
                + "    MockState" + "\n"
                + "TestTile2:" + "\n"
                + "    MockState" + "\n";

        System.out.println(output);
        System.out.println(w.getBuffer().toString());

        assertEquals(output, w.getBuffer().toString());
    }

    @Test
    public void testDefault() {
        mContext.getOrCreateTestableResources()
                .addOverride(R.string.quick_settings_tiles_default, "spec1");
        saveSetting("default");
        assertEquals(1, mQSTileHost.getTiles().size());
        QSTile element = CollectionUtils.firstOrNull(mQSTileHost.getTiles());
        assertTrue(element instanceof TestTile1);
        verify(mQSLogger).logTileAdded("spec1");
    }

    @Test
    public void testNoRepeatedSpecs_addTile() {
        mContext.getOrCreateTestableResources()
                .addOverride(R.string.quick_settings_tiles, "spec1,spec2");
        saveSetting("spec1,spec2");

        mQSTileHost.addTile("spec1");

        assertEquals(2, mQSTileHost.getSpecs().size());
        assertEquals("spec1", mQSTileHost.getSpecs().get(0));
        assertEquals("spec2", mQSTileHost.getSpecs().get(1));
    }

    @Test
    public void testAddTileAtValidPosition() {
        mContext.getOrCreateTestableResources()
                .addOverride(R.string.quick_settings_tiles, "spec1,spec3");
        saveSetting("spec1,spec3");

        mQSTileHost.addTile("spec2", 1);
        mMainExecutor.runAllReady();

        assertEquals(3, mQSTileHost.getSpecs().size());
        assertEquals("spec1", mQSTileHost.getSpecs().get(0));
        assertEquals("spec2", mQSTileHost.getSpecs().get(1));
        assertEquals("spec3", mQSTileHost.getSpecs().get(2));
    }

    @Test
    public void testAddTileAtInvalidPositionAddsToEnd() {
        mContext.getOrCreateTestableResources()
                .addOverride(R.string.quick_settings_tiles, "spec1,spec3");
        saveSetting("spec1,spec3");

        mQSTileHost.addTile("spec2", 100);
        mMainExecutor.runAllReady();

        assertEquals(3, mQSTileHost.getSpecs().size());
        assertEquals("spec1", mQSTileHost.getSpecs().get(0));
        assertEquals("spec3", mQSTileHost.getSpecs().get(1));
        assertEquals("spec2", mQSTileHost.getSpecs().get(2));
    }

    @Test
    public void testAddTileAtEnd() {
        mContext.getOrCreateTestableResources()
                .addOverride(R.string.quick_settings_tiles, "spec1,spec3");
        saveSetting("spec1,spec3");

        mQSTileHost.addTile("spec2", QSTileHost.POSITION_AT_END);
        mMainExecutor.runAllReady();

        assertEquals(3, mQSTileHost.getSpecs().size());
        assertEquals("spec1", mQSTileHost.getSpecs().get(0));
        assertEquals("spec3", mQSTileHost.getSpecs().get(1));
        assertEquals("spec2", mQSTileHost.getSpecs().get(2));
    }

    @Test
    public void testNoRepeatedSpecs_customTile() {
        saveSetting(CUSTOM_TILE_SPEC);

        mQSTileHost.addTile(CUSTOM_TILE, /* end */ false);
        mMainExecutor.runAllReady();

        assertEquals(1, mQSTileHost.getSpecs().size());
        assertEquals(CUSTOM_TILE_SPEC, mQSTileHost.getSpecs().get(0));
    }

    @Test
    public void testAddedAtBeginningOnDefault_customTile() {
        saveSetting("spec1"); // seed

        mQSTileHost.addTile(CUSTOM_TILE);
        mMainExecutor.runAllReady();

        assertEquals(2, mQSTileHost.getSpecs().size());
        assertEquals(CUSTOM_TILE_SPEC, mQSTileHost.getSpecs().get(0));
    }

    @Test
    public void testAddedAtBeginning_customTile() {
        saveSetting("spec1"); // seed

        mQSTileHost.addTile(CUSTOM_TILE, /* end */ false);
        mMainExecutor.runAllReady();

        assertEquals(2, mQSTileHost.getSpecs().size());
        assertEquals(CUSTOM_TILE_SPEC, mQSTileHost.getSpecs().get(0));
    }

    @Test
    public void testAddedAtEnd_customTile() {
        saveSetting("spec1"); // seed

        mQSTileHost.addTile(CUSTOM_TILE, /* end */ true);
        mMainExecutor.runAllReady();

        assertEquals(2, mQSTileHost.getSpecs().size());
        assertEquals(CUSTOM_TILE_SPEC, mQSTileHost.getSpecs().get(1));
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
        saveSetting("na");
        verify(mQSLogger, never()).logTileDestroyed(isNull(), anyString());
    }

    @Test
    public void testCustomTileRemoved_stateDeleted() {
        mQSTileHost.changeTilesByUser(List.of(CUSTOM_TILE_SPEC), List.of());

        verify(mCustomTileStatePersister)
                .removeState(new TileServiceKey(CUSTOM_TILE, mQSTileHost.getUserId()));
    }

    @Test
    public void testRemoveTiles() {
        saveSetting("spec1,spec2,spec3");

        mQSTileHost.removeTiles(List.of("spec1", "spec2"));

        mMainExecutor.runAllReady();
        assertEquals(List.of("spec3"), mQSTileHost.getSpecs());
    }

    @Test
    public void testTilesRemovedInQuickSuccession() {
        saveSetting("spec1,spec2,spec3");
        mQSTileHost.removeTile("spec1");
        mQSTileHost.removeTile("spec3");

        mMainExecutor.runAllReady();
        assertEquals(List.of("spec2"), mQSTileHost.getSpecs());
        assertEquals("spec2", getSetting());
    }

    @Test
    public void testAddTileInMainThread() {
        saveSetting("spec1,spec2");

        mQSTileHost.addTile("spec3");
        assertEquals(List.of("spec1", "spec2"), mQSTileHost.getSpecs());

        mMainExecutor.runAllReady();
        assertEquals(List.of("spec1", "spec2", "spec3"), mQSTileHost.getSpecs());
    }

    @Test
    public void testRemoveTileInMainThread() {
        saveSetting("spec1,spec2");

        mQSTileHost.removeTile("spec1");
        assertEquals(List.of("spec1", "spec2"), mQSTileHost.getSpecs());

        mMainExecutor.runAllReady();
        assertEquals(List.of("spec2"), mQSTileHost.getSpecs());
    }

    @Test
    public void testRemoveTilesInMainThread() {
        saveSetting("spec1,spec2,spec3");

        mQSTileHost.removeTiles(List.of("spec3", "spec1"));
        assertEquals(List.of("spec1", "spec2", "spec3"), mQSTileHost.getSpecs());

        mMainExecutor.runAllReady();
        assertEquals(List.of("spec2"), mQSTileHost.getSpecs());
    }

    @Test
    public void testRemoveTileByUserInMainThread() {
        saveSetting("spec1," + CUSTOM_TILE_SPEC);

        mQSTileHost.removeTileByUser(CUSTOM_TILE);
        assertEquals(List.of("spec1", CUSTOM_TILE_SPEC), mQSTileHost.getSpecs());

        mMainExecutor.runAllReady();
        assertEquals(List.of("spec1"), mQSTileHost.getSpecs());
    }

    @Test
    public void testNonValidTileNotStoredInSettings() {
        saveSetting("spec1,not-valid");

        assertEquals(List.of("spec1"), mQSTileHost.getSpecs());
        assertEquals("spec1", getSetting());
    }

    @Test
    public void testNotAvailableTileNotStoredInSettings() {
        saveSetting("spec1,na");

        assertEquals(List.of("spec1"), mQSTileHost.getSpecs());
        assertEquals("spec1", getSetting());
    }

    @Test
    public void testIsTileAdded_true() {
        int user = mUserTracker.getUserId();
        getSharedPreferencesForUser(user)
                .edit()
                .putBoolean(CUSTOM_TILE.flattenToString(), true)
                .apply();

        assertTrue(mQSTileHost.isTileAdded(CUSTOM_TILE, user));
    }

    @Test
    public void testIsTileAdded_false() {
        int user = mUserTracker.getUserId();
        getSharedPreferencesForUser(user)
                .edit()
                .putBoolean(CUSTOM_TILE.flattenToString(), false)
                .apply();

        assertFalse(mQSTileHost.isTileAdded(CUSTOM_TILE, user));
    }

    @Test
    public void testIsTileAdded_notSet() {
        int user = mUserTracker.getUserId();

        assertFalse(mQSTileHost.isTileAdded(CUSTOM_TILE, user));
    }

    @Test
    public void testIsTileAdded_differentUser() {
        int user = mUserTracker.getUserId();
        mUserFileManager.getSharedPreferences(QSTileHost.TILES, 0, user)
                .edit()
                .putBoolean(CUSTOM_TILE.flattenToString(), true)
                .apply();

        assertFalse(mQSTileHost.isTileAdded(CUSTOM_TILE, user + 1));
    }

    @Test
    public void testSetTileAdded_true() {
        int user = mUserTracker.getUserId();
        mQSTileHost.setTileAdded(CUSTOM_TILE, user, true);

        assertTrue(getSharedPreferencesForUser(user)
                .getBoolean(CUSTOM_TILE.flattenToString(), false));
    }

    @Test
    public void testSetTileAdded_false() {
        int user = mUserTracker.getUserId();
        mQSTileHost.setTileAdded(CUSTOM_TILE, user, false);

        assertFalse(getSharedPreferencesForUser(user)
                .getBoolean(CUSTOM_TILE.flattenToString(), false));
    }

    @Test
    public void testSetTileAdded_differentUser() {
        int user = mUserTracker.getUserId();
        mQSTileHost.setTileAdded(CUSTOM_TILE, user, true);

        assertFalse(getSharedPreferencesForUser(user + 1)
                .getBoolean(CUSTOM_TILE.flattenToString(), false));
    }

    @Test
    public void testSetTileRemoved_afterCustomTileChangedByUser() {
        int user = mUserTracker.getUserId();
        saveSetting(CUSTOM_TILE_SPEC);

        // This will be done by TileServiceManager
        mQSTileHost.setTileAdded(CUSTOM_TILE, user, true);

        mQSTileHost.changeTilesByUser(mQSTileHost.getSpecs(), List.of("spec1"));
        assertFalse(getSharedPreferencesForUser(user)
                .getBoolean(CUSTOM_TILE.flattenToString(), false));
    }

    @Test
    public void testSetTileRemoved_removedByUser() {
        int user = mUserTracker.getUserId();
        saveSetting(CUSTOM_TILE_SPEC);

        // This will be done by TileServiceManager
        mQSTileHost.setTileAdded(CUSTOM_TILE, user, true);

        mQSTileHost.removeTileByUser(CUSTOM_TILE);
        mMainExecutor.runAllReady();
        assertFalse(getSharedPreferencesForUser(user)
                .getBoolean(CUSTOM_TILE.flattenToString(), false));
    }

    @Test
    public void testSetTileRemoved_removedBySystem() {
        int user = mUserTracker.getUserId();
        saveSetting("spec1," + CUSTOM_TILE_SPEC);

        // This will be done by TileServiceManager
        mQSTileHost.setTileAdded(CUSTOM_TILE, user, true);

        mQSTileHost.removeTile(CUSTOM_TILE_SPEC);
        mMainExecutor.runAllReady();
        assertFalse(getSharedPreferencesForUser(user)
                .getBoolean(CUSTOM_TILE.flattenToString(), false));
    }

    @Test
    public void testProtoDump_noTiles() {
        SystemUIProtoDump proto = new SystemUIProtoDump();
        mQSTileHost.dumpProto(proto, new String[0]);

        assertEquals(0, proto.tiles.length);
    }

    @Test
    public void testTilesInOrder() {
        saveSetting("spec1," + CUSTOM_TILE_SPEC);

        SystemUIProtoDump proto = new SystemUIProtoDump();
        mQSTileHost.dumpProto(proto, new String[0]);

        assertEquals(2, proto.tiles.length);
        assertEquals("spec1", proto.tiles[0].getSpec());
        assertEquals(CUSTOM_TILE.getPackageName(), proto.tiles[1].getComponentName().packageName);
        assertEquals(CUSTOM_TILE.getClassName(), proto.tiles[1].getComponentName().className);
    }

    private SharedPreferences getSharedPreferencesForUser(int user) {
        return mUserFileManager.getSharedPreferences(QSTileHost.TILES, 0, user);
    }

    private class TestQSTileHost extends QSTileHost {
        TestQSTileHost(Context context, Lazy<NewQSTileFactory> newQSTileFactoryProvider,
                QSFactory defaultFactory, Executor mainExecutor,
                PluginManager pluginManager, TunerService tunerService,
                Provider<AutoTileManager> autoTiles,
                ShadeController shadeController, QSLogger qsLogger,
                UserTracker userTracker, SecureSettings secureSettings,
                CustomTileStatePersister customTileStatePersister,
                TileLifecycleManager.Factory tileLifecycleManagerFactory,
                UserFileManager userFileManager, QSPipelineFlagsRepository featureFlags) {
            super(context, newQSTileFactoryProvider, defaultFactory, mainExecutor, pluginManager,
                    tunerService, autoTiles,  shadeController, qsLogger,
                    userTracker, secureSettings, customTileStatePersister,
                    tileLifecycleManagerFactory, userFileManager, featureFlags);
        }

        @Override
        public void onPluginConnected(QSFactory plugin, Context pluginContext) {
        }

        @Override
        public void onPluginDisconnected(QSFactory plugin) {
        }
    }


    private class TestTile extends QSTileImpl<QSTile.State> {

        protected TestTile(QSHost host) {
            super(
                    host,
                    mock(QsEventLogger.class),
                    mock(Looper.class),
                    mock(Handler.class),
                    new FalsingManagerFake(),
                    mock(MetricsLogger.class),
                    mock(StatusBarStateController.class),
                    mock(ActivityStarter.class),
                    QSTileHostTest.this.mQSLogger
            );
        }

        @Override
        public State newTileState() {
            State s = mock(QSTile.State.class);
            when(s.toString()).thenReturn(MOCK_STATE_STRING);
            return s;
        }

        @Override
        protected void handleClick(@Nullable View view) {}

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

    private class TestTile3 extends TestTile {

        protected TestTile3(QSHost host) {
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
