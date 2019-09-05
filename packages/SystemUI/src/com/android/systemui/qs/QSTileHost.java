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

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.provider.Settings.Secure;
import android.service.quicksettings.Tile;
import android.text.TextUtils;
import android.util.Log;

import com.android.systemui.Dependency;
import com.android.systemui.DumpController;
import com.android.systemui.Dumpable;
import com.android.systemui.R;
import com.android.systemui.SysUiServiceProvider;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.plugins.qs.QSFactory;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.qs.QSTileView;
import com.android.systemui.qs.external.CustomTile;
import com.android.systemui.qs.external.TileLifecycleManager;
import com.android.systemui.qs.external.TileServices;
import com.android.systemui.qs.tileimpl.QSFactoryImpl;
import com.android.systemui.shared.plugins.PluginManager;
import com.android.systemui.statusbar.phone.AutoTileManager;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;
import com.android.systemui.util.leak.GarbageMonitor;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Predicate;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

/** Platform implementation of the quick settings tile host **/
@Singleton
public class QSTileHost implements QSHost, Tunable, PluginListener<QSFactory>, Dumpable {
    private static final String TAG = "QSTileHost";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    public static final String TILES_SETTING = Secure.QS_TILES;

    private final Context mContext;
    private final LinkedHashMap<String, QSTile> mTiles = new LinkedHashMap<>();
    protected final ArrayList<String> mTileSpecs = new ArrayList<>();
    private final TileServices mServices;
    private final TunerService mTunerService;
    private final PluginManager mPluginManager;
    private final DumpController mDumpController;

    private final List<Callback> mCallbacks = new ArrayList<>();
    private AutoTileManager mAutoTiles;
    private final StatusBarIconController mIconController;
    private final ArrayList<QSFactory> mQsFactories = new ArrayList<>();
    private int mCurrentUser;
    private StatusBar mStatusBar;

    private QSColorController mQSColorController = QSColorController.Companion.getInstance();

    @Inject
    public QSTileHost(Context context,
            StatusBarIconController iconController,
            QSFactoryImpl defaultFactory,
            @Named(Dependency.MAIN_HANDLER_NAME) Handler mainHandler,
            @Named(Dependency.BG_LOOPER_NAME) Looper bgLooper,
            PluginManager pluginManager,
            TunerService tunerService,
            Provider<AutoTileManager> autoTiles,
            DumpController dumpController) {
        mIconController = iconController;
        mContext = context;
        mTunerService = tunerService;
        mPluginManager = pluginManager;
        mDumpController = dumpController;

        mServices = new TileServices(this, bgLooper);

        defaultFactory.setHost(this);
        mQsFactories.add(defaultFactory);
        pluginManager.addPluginListener(this, QSFactory.class, true);
        mDumpController.registerDumpable(TAG, this);

        mainHandler.post(() -> {
            // This is technically a hack to avoid circular dependency of
            // QSTileHost -> XXXTile -> QSTileHost. Posting ensures creation
            // finishes before creating any tiles.
            tunerService.addTunable(this, TILES_SETTING);
            // AutoTileManager can modify mTiles so make sure mTiles has already been initialized.
            mAutoTiles = autoTiles.get();
        });

        mQSColorController.initQSTileHost(this);
    }

    public StatusBarIconController getIconController() {
        return mIconController;
    }

    public void destroy() {
        mTiles.values().forEach(tile -> tile.destroy());
        mAutoTiles.destroy();
        mTunerService.removeTunable(this);
        mServices.destroy();
        mPluginManager.removePluginListener(this);
        mDumpController.unregisterDumpable(this);

        mQSColorController.destroy();
    }

    @Override
    public void onPluginConnected(QSFactory plugin, Context pluginContext) {
        // Give plugins priority over creation so they can override if they wish.
        mQsFactories.add(0, plugin);
        String value = mTunerService.getValue(TILES_SETTING);
        // Force remove and recreate of all tiles.
        onTuningChanged(TILES_SETTING, "");
        onTuningChanged(TILES_SETTING, value);
    }

    @Override
    public void onPluginDisconnected(QSFactory plugin) {
        mQsFactories.remove(plugin);
        // Force remove and recreate of all tiles.
        String value = mTunerService.getValue(TILES_SETTING);
        onTuningChanged(TILES_SETTING, "");
        onTuningChanged(TILES_SETTING, value);
    }

    @Override
    public void addCallback(Callback callback) {
        mCallbacks.add(callback);
    }

    @Override
    public void removeCallback(Callback callback) {
        mCallbacks.remove(callback);
    }

    @Override
    public Collection<QSTile> getTiles() {
        return mTiles.values();
    }

    @Override
    public void warn(String message, Throwable t) {
        // already logged
    }

    @Override
    public void collapsePanels() {
        if (mStatusBar == null) {
            mStatusBar = SysUiServiceProvider.getComponent(mContext, StatusBar.class);
        }
        mStatusBar.postAnimateCollapsePanels();
    }

    @Override
    public void forceCollapsePanels() {
        if (mStatusBar == null) {
            mStatusBar = SysUiServiceProvider.getComponent(mContext, StatusBar.class);
        }
        mStatusBar.postAnimateForceCollapsePanels();
    }

    @Override
    public void openPanels() {
        if (mStatusBar == null) {
            mStatusBar = SysUiServiceProvider.getComponent(mContext, StatusBar.class);
        }
        mStatusBar.postAnimateOpenPanels();
    }

    @Override
    public Context getContext() {
        return mContext;
    }


    public TileServices getTileServices() {
        return mServices;
    }

    public int indexOf(String spec) {
        return mTileSpecs.indexOf(spec);
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (!TILES_SETTING.equals(key)) {
            return;
        }
        if (DEBUG) Log.d(TAG, "Recreating tiles");
        if (newValue == null && UserManager.isDeviceInDemoMode(mContext)) {
            newValue = mContext.getResources().getString(R.string.quick_settings_tiles_retail_mode);
        }
        final List<String> tileSpecs = loadTileSpecs(mContext, newValue);
        int currentUser = ActivityManager.getCurrentUser();
        if (tileSpecs.equals(mTileSpecs) && currentUser == mCurrentUser) return;
        mTiles.entrySet().stream().filter(tile -> !tileSpecs.contains(tile.getKey())).forEach(
                tile -> {
                    if (DEBUG) Log.d(TAG, "Destroying tile: " + tile.getKey());
                    tile.getValue().destroy();
                });
        final LinkedHashMap<String, QSTile> newTiles = new LinkedHashMap<>();
        for (String tileSpec : tileSpecs) {
            QSTile tile = mTiles.get(tileSpec);
            if (tile != null && (!(tile instanceof CustomTile)
                    || ((CustomTile) tile).getUser() == currentUser)) {
                if (tile.isAvailable()) {
                    if (DEBUG) Log.d(TAG, "Adding " + tile);
                    tile.removeCallbacks();
                    if (!(tile instanceof CustomTile) && mCurrentUser != currentUser) {
                        tile.userSwitch(currentUser);
                    }
                    newTiles.put(tileSpec, tile);
                } else {
                    tile.destroy();
                }
            } else {
                if (DEBUG) Log.d(TAG, "Creating tile: " + tileSpec);
                try {
                    tile = createTile(tileSpec);
                    if (tile != null) {
                        if (tile.isAvailable()) {
                            tile.setTileSpec(tileSpec);
                            newTiles.put(tileSpec, tile);
                        } else {
                            tile.destroy();
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Error creating tile for spec: " + tileSpec, t);
                }
            }
        }
        mCurrentUser = currentUser;
        List<String> currentSpecs = new ArrayList(mTileSpecs);
        mTileSpecs.clear();
        mTileSpecs.addAll(tileSpecs);
        mTiles.clear();
        mTiles.putAll(newTiles);
        if (newTiles.isEmpty() && !tileSpecs.isEmpty()) {
            // If we didn't manage to create any tiles, set it to empty (default)
            if (DEBUG) Log.d(TAG, "No valid tiles on tuning changed. Setting to default.");
            changeTiles(currentSpecs, loadTileSpecs(mContext, ""));
        } else {
            for (int i = 0; i < mCallbacks.size(); i++) {
                mCallbacks.get(i).onTilesChanged();
            }
        }

        mQSColorController.applyColorToAllTiles();
    }

    @Override
    public void removeTile(String spec) {
        changeTileSpecs(tileSpecs-> tileSpecs.remove(spec));
    }

    @Override
    public void unmarkTileAsAutoAdded(String spec) {
        if (mAutoTiles != null) mAutoTiles.unmarkTileAsAutoAdded(spec);
    }

    public void addTile(String spec) {
        changeTileSpecs(tileSpecs-> tileSpecs.add(spec));
    }

    private void changeTileSpecs(Predicate<List<String>> changeFunction) {
        final String setting = Settings.Secure.getStringForUser(mContext.getContentResolver(),
            TILES_SETTING, ActivityManager.getCurrentUser());
        final List<String> tileSpecs = loadTileSpecs(mContext, setting);
        if (changeFunction.test(tileSpecs)) {
            Settings.Secure.putStringForUser(mContext.getContentResolver(), TILES_SETTING,
                TextUtils.join(",", tileSpecs), ActivityManager.getCurrentUser());
        }
    }

    public void addTile(ComponentName tile) {
        List<String> newSpecs = new ArrayList<>(mTileSpecs);
        newSpecs.add(0, CustomTile.toSpec(tile));
        changeTiles(mTileSpecs, newSpecs);
    }

    public void removeTile(ComponentName tile) {
        List<String> newSpecs = new ArrayList<>(mTileSpecs);
        newSpecs.remove(CustomTile.toSpec(tile));
        changeTiles(mTileSpecs, newSpecs);
    }

    public void changeTiles(List<String> previousTiles, List<String> newTiles) {
        final int NP = previousTiles.size();
        final int NA = newTiles.size();
        for (int i = 0; i < NP; i++) {
            String tileSpec = previousTiles.get(i);
            if (!tileSpec.startsWith(CustomTile.PREFIX)) continue;
            if (!newTiles.contains(tileSpec)) {
                ComponentName component = CustomTile.getComponentFromSpec(tileSpec);
                Intent intent = new Intent().setComponent(component);
                TileLifecycleManager lifecycleManager = new TileLifecycleManager(new Handler(),
                        mContext, mServices, new Tile(), intent,
                        new UserHandle(ActivityManager.getCurrentUser()));
                lifecycleManager.onStopListening();
                lifecycleManager.onTileRemoved();
                TileLifecycleManager.setTileAdded(mContext, component, false);
                lifecycleManager.flushMessagesAndUnbind();
            }
        }
        if (DEBUG) Log.d(TAG, "saveCurrentTiles " + newTiles);
        Secure.putStringForUser(getContext().getContentResolver(), QSTileHost.TILES_SETTING,
                TextUtils.join(",", newTiles), ActivityManager.getCurrentUser());
    }

    public QSTile createTile(String tileSpec) {
        for (int i = 0; i < mQsFactories.size(); i++) {
            QSTile t = mQsFactories.get(i).createTile(tileSpec);
            if (t != null) {
                return t;
            }
        }
        return null;
    }

    public QSTileView createTileView(QSTile tile, boolean collapsedView) {
        for (int i = 0; i < mQsFactories.size(); i++) {
            QSTileView view = mQsFactories.get(i).createTileView(tile, collapsedView);
            if (view != null) {
                return view;
            }
        }
        throw new RuntimeException("Default factory didn't create view for " + tile.getTileSpec());
    }

    protected static List<String> loadTileSpecs(Context context, String tileList) {
        final Resources res = context.getResources();
        final String defaultTileList = res.getString(R.string.quick_settings_tiles_default);
        if (TextUtils.isEmpty(tileList)) {
            tileList = res.getString(R.string.quick_settings_tiles);
            if (DEBUG) Log.d(TAG, "Loaded tile specs from config: " + tileList);
        } else {
            if (DEBUG) Log.d(TAG, "Loaded tile specs from setting: " + tileList);
        }
        final ArrayList<String> tiles = new ArrayList<String>();
        boolean addedDefault = false;
        for (String tile : tileList.split(",")) {
            tile = tile.trim();
            if (tile.isEmpty()) continue;
            if (tile.equals("default")) {
                if (!addedDefault) {
                    tiles.addAll(Arrays.asList(defaultTileList.split(",")));
                    if (Build.IS_DEBUGGABLE
                            && GarbageMonitor.MemoryTile.ADD_TO_DEFAULT_ON_DEBUGGABLE_BUILDS) {
                        tiles.add(GarbageMonitor.MemoryTile.TILE_SPEC);
                    }
                    addedDefault = true;
                }
            } else {
                tiles.add(tile);
            }
        }
        return tiles;
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("QSTileHost:");
        mTiles.values().stream().filter(obj -> obj instanceof Dumpable)
                .forEach(o -> ((Dumpable) o).dump(fd, pw, args));
    }
}
