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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings.Secure;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;

import androidx.annotation.Nullable;

import com.android.internal.logging.InstanceId;
import com.android.internal.logging.InstanceIdSequence;
import com.android.internal.logging.UiEventLogger;
import com.android.systemui.Dumpable;
import com.android.systemui.R;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.plugins.qs.QSFactory;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.qs.QSTileView;
import com.android.systemui.qs.external.CustomTile;
import com.android.systemui.qs.external.CustomTileStatePersister;
import com.android.systemui.qs.external.TileLifecycleManager;
import com.android.systemui.qs.external.TileServiceKey;
import com.android.systemui.qs.external.TileServiceRequestController;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shared.plugins.PluginManager;
import com.android.systemui.statusbar.phone.AutoTileManager;
import com.android.systemui.statusbar.phone.CentralSurfaces;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;
import com.android.systemui.util.leak.GarbageMonitor;
import com.android.systemui.util.settings.SecureSettings;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import javax.inject.Inject;
import javax.inject.Provider;

/** Platform implementation of the quick settings tile host **/
@SysUISingleton
public class QSTileHost implements QSHost, Tunable, PluginListener<QSFactory>, Dumpable {
    private static final String TAG = "QSTileHost";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final int MAX_QS_INSTANCE_ID = 1 << 20;

    public static final int POSITION_AT_END = -1;
    public static final String TILES_SETTING = Secure.QS_TILES;

    private final Context mContext;
    private final LinkedHashMap<String, QSTile> mTiles = new LinkedHashMap<>();
    protected final ArrayList<String> mTileSpecs = new ArrayList<>();
    private final TunerService mTunerService;
    private final PluginManager mPluginManager;
    private final DumpManager mDumpManager;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final QSLogger mQSLogger;
    private final UiEventLogger mUiEventLogger;
    private final InstanceIdSequence mInstanceIdSequence;
    private final CustomTileStatePersister mCustomTileStatePersister;

    private final List<Callback> mCallbacks = new ArrayList<>();
    @Nullable
    private AutoTileManager mAutoTiles;
    private final StatusBarIconController mIconController;
    private final ArrayList<QSFactory> mQsFactories = new ArrayList<>();
    private int mCurrentUser;
    private final Optional<CentralSurfaces> mCentralSurfacesOptional;
    private Context mUserContext;
    private UserTracker mUserTracker;
    private SecureSettings mSecureSettings;

    private final TileServiceRequestController mTileServiceRequestController;
    private TileLifecycleManager.Factory mTileLifeCycleManagerFactory;

    @Inject
    public QSTileHost(Context context,
            StatusBarIconController iconController,
            QSFactory defaultFactory,
            @Main Handler mainHandler,
            @Background Looper bgLooper,
            PluginManager pluginManager,
            TunerService tunerService,
            Provider<AutoTileManager> autoTiles,
            DumpManager dumpManager,
            BroadcastDispatcher broadcastDispatcher,
            Optional<CentralSurfaces> centralSurfacesOptional,
            QSLogger qsLogger,
            UiEventLogger uiEventLogger,
            UserTracker userTracker,
            SecureSettings secureSettings,
            CustomTileStatePersister customTileStatePersister,
            TileServiceRequestController.Builder tileServiceRequestControllerBuilder,
            TileLifecycleManager.Factory tileLifecycleManagerFactory
    ) {
        mIconController = iconController;
        mContext = context;
        mUserContext = context;
        mTunerService = tunerService;
        mPluginManager = pluginManager;
        mDumpManager = dumpManager;
        mQSLogger = qsLogger;
        mUiEventLogger = uiEventLogger;
        mBroadcastDispatcher = broadcastDispatcher;
        mTileServiceRequestController = tileServiceRequestControllerBuilder.create(this);
        mTileLifeCycleManagerFactory = tileLifecycleManagerFactory;

        mInstanceIdSequence = new InstanceIdSequence(MAX_QS_INSTANCE_ID);
        mCentralSurfacesOptional = centralSurfacesOptional;

        mQsFactories.add(defaultFactory);
        pluginManager.addPluginListener(this, QSFactory.class, true);
        mDumpManager.registerDumpable(TAG, this);
        mUserTracker = userTracker;
        mSecureSettings = secureSettings;
        mCustomTileStatePersister = customTileStatePersister;

        mainHandler.post(() -> {
            // This is technically a hack to avoid circular dependency of
            // QSTileHost -> XXXTile -> QSTileHost. Posting ensures creation
            // finishes before creating any tiles.
            tunerService.addTunable(this, TILES_SETTING);
            // AutoTileManager can modify mTiles so make sure mTiles has already been initialized.
            mAutoTiles = autoTiles.get();
            mTileServiceRequestController.init();
        });
    }

    public StatusBarIconController getIconController() {
        return mIconController;
    }

    @Override
    public InstanceId getNewInstanceId() {
        return mInstanceIdSequence.newInstanceId();
    }

    public void destroy() {
        mTiles.values().forEach(tile -> tile.destroy());
        mAutoTiles.destroy();
        mTunerService.removeTunable(this);
        mPluginManager.removePluginListener(this);
        mDumpManager.unregisterDumpable(TAG);
        mTileServiceRequestController.destroy();
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
    public UiEventLogger getUiEventLogger() {
        return mUiEventLogger;
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
        mCentralSurfacesOptional.ifPresent(CentralSurfaces::postAnimateCollapsePanels);
    }

    @Override
    public void forceCollapsePanels() {
        mCentralSurfacesOptional.ifPresent(CentralSurfaces::postAnimateForceCollapsePanels);
    }

    @Override
    public void openPanels() {
        mCentralSurfacesOptional.ifPresent(CentralSurfaces::postAnimateOpenPanels);
    }

    @Override
    public Context getContext() {
        return mContext;
    }

    @Override
    public Context getUserContext() {
        return mUserContext;
    }

    @Override
    public int getUserId() {
        return mCurrentUser;
    }

    public int indexOf(String spec) {
        return mTileSpecs.indexOf(spec);
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (!TILES_SETTING.equals(key)) {
            return;
        }
        Log.d(TAG, "Recreating tiles");
        if (newValue == null && UserManager.isDeviceInDemoMode(mContext)) {
            newValue = mContext.getResources().getString(R.string.quick_settings_tiles_retail_mode);
        }
        final List<String> tileSpecs = loadTileSpecs(mContext, newValue);
        int currentUser = mUserTracker.getUserId();
        if (currentUser != mCurrentUser) {
            mUserContext = mUserTracker.getUserContext();
            if (mAutoTiles != null) {
                mAutoTiles.changeUser(UserHandle.of(currentUser));
            }
        }
        if (tileSpecs.equals(mTileSpecs) && currentUser == mCurrentUser) return;
        mTiles.entrySet().stream().filter(tile -> !tileSpecs.contains(tile.getKey())).forEach(
                tile -> {
                    Log.d(TAG, "Destroying tile: " + tile.getKey());
                    mQSLogger.logTileDestroyed(tile.getKey(), "Tile removed");
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
                    mQSLogger.logTileAdded(tileSpec);
                } else {
                    tile.destroy();
                    Log.d(TAG, "Destroying not available tile: " + tileSpec);
                    mQSLogger.logTileDestroyed(tileSpec, "Tile not available");
                }
            } else {
                // This means that the tile is a CustomTile AND the user is different, so let's
                // destroy it
                if (tile != null) {
                    tile.destroy();
                    Log.d(TAG, "Destroying tile for wrong user: " + tileSpec);
                    mQSLogger.logTileDestroyed(tileSpec, "Tile for wrong user");
                }
                Log.d(TAG, "Creating tile: " + tileSpec);
                try {
                    tile = createTile(tileSpec);
                    if (tile != null) {
                        tile.setTileSpec(tileSpec);
                        if (tile.isAvailable()) {
                            newTiles.put(tileSpec, tile);
                            mQSLogger.logTileAdded(tileSpec);
                        } else {
                            tile.destroy();
                            Log.d(TAG, "Destroying not available tile: " + tileSpec);
                            mQSLogger.logTileDestroyed(tileSpec, "Tile not available");
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Error creating tile for spec: " + tileSpec, t);
                }
            }
        }
        mCurrentUser = currentUser;
        List<String> currentSpecs = new ArrayList<>(mTileSpecs);
        mTileSpecs.clear();
        mTileSpecs.addAll(tileSpecs);
        mTiles.clear();
        mTiles.putAll(newTiles);
        if (newTiles.isEmpty() && !tileSpecs.isEmpty()) {
            // If we didn't manage to create any tiles, set it to empty (default)
            Log.d(TAG, "No valid tiles on tuning changed. Setting to default.");
            changeTiles(currentSpecs, loadTileSpecs(mContext, ""));
        } else {
            for (int i = 0; i < mCallbacks.size(); i++) {
                mCallbacks.get(i).onTilesChanged();
            }
        }
    }

    @Override
    public void removeTile(String spec) {
        changeTileSpecs(tileSpecs-> tileSpecs.remove(spec));
    }

    /**
     * Remove many tiles at once.
     *
     * It will only save to settings once (as opposed to {@link QSTileHost#removeTile} called
     * multiple times).
     */
    @Override
    public void removeTiles(Collection<String> specs) {
        changeTileSpecs(tileSpecs -> tileSpecs.removeAll(specs));
    }

    @Override
    public void unmarkTileAsAutoAdded(String spec) {
        if (mAutoTiles != null) mAutoTiles.unmarkTileAsAutoAdded(spec);
    }

    /**
     * Add a tile to the end
     *
     * @param spec string matching a pre-defined tilespec
     */
    public void addTile(String spec) {
        addTile(spec, POSITION_AT_END);
    }

    /**
     * Add a tile into the requested spot, or at the end if the position is greater than the number
     * of tiles.
     * @param spec string matching a pre-defined tilespec
     * @param requestPosition -1 for end, 0 for beginning, or X for insertion at position X
     */
    public void addTile(String spec, int requestPosition) {
        if (spec.equals("work")) Log.wtfStack(TAG, "Adding work tile");
        changeTileSpecs(tileSpecs -> {
            if (tileSpecs.contains(spec)) return false;

            int size = tileSpecs.size();
            if (requestPosition == POSITION_AT_END || requestPosition >= size) {
                tileSpecs.add(spec);
            } else {
                tileSpecs.add(requestPosition, spec);
            }
            return true;
        });
    }

    void saveTilesToSettings(List<String> tileSpecs) {
        if (tileSpecs.contains("work")) Log.wtfStack(TAG, "Saving work tile");
        mSecureSettings.putStringForUser(TILES_SETTING, TextUtils.join(",", tileSpecs),
                null /* tag */, false /* default */, mCurrentUser,
                true /* overrideable by restore */);
    }

    private void changeTileSpecs(Predicate<List<String>> changeFunction) {
        final String setting = mSecureSettings.getStringForUser(TILES_SETTING, mCurrentUser);
        final List<String> tileSpecs = loadTileSpecs(mContext, setting);
        if (changeFunction.test(tileSpecs)) {
            saveTilesToSettings(tileSpecs);
        }
    }

    public void addTile(ComponentName tile) {
        addTile(tile, /* end */ false);
    }

    /**
     * Adds a custom tile to the set of current tiles.
     * @param tile the component name of the {@link android.service.quicksettings.TileService}
     * @param end if true, the tile will be added at the end. If false, at the beginning.
     */
    public void addTile(ComponentName tile, boolean end) {
        String spec = CustomTile.toSpec(tile);
        if (!mTileSpecs.contains(spec)) {
            List<String> newSpecs = new ArrayList<>(mTileSpecs);
            if (end) {
                newSpecs.add(spec);
            } else {
                newSpecs.add(0, spec);
            }
            changeTiles(mTileSpecs, newSpecs);
        }
    }

    public void removeTile(ComponentName tile) {
        List<String> newSpecs = new ArrayList<>(mTileSpecs);
        newSpecs.remove(CustomTile.toSpec(tile));
        changeTiles(mTileSpecs, newSpecs);
    }

    /**
     * Change the tiles triggered by the user editing.
     * <p>
     * This is not called on device start, or on user change.
     */
    public void changeTiles(List<String> previousTiles, List<String> newTiles) {
        final List<String> copy = new ArrayList<>(previousTiles);
        final int NP = copy.size();
        for (int i = 0; i < NP; i++) {
            String tileSpec = copy.get(i);
            if (!tileSpec.startsWith(CustomTile.PREFIX)) continue;
            if (!newTiles.contains(tileSpec)) {
                ComponentName component = CustomTile.getComponentFromSpec(tileSpec);
                Intent intent = new Intent().setComponent(component);
                TileLifecycleManager lifecycleManager = mTileLifeCycleManagerFactory.create(
                        intent, new UserHandle(mCurrentUser));
                lifecycleManager.onStopListening();
                lifecycleManager.onTileRemoved();
                mCustomTileStatePersister.removeState(new TileServiceKey(component, mCurrentUser));
                TileLifecycleManager.setTileAdded(mContext, component, false);
                lifecycleManager.flushMessagesAndUnbind();
            }
        }
        if (DEBUG) Log.d(TAG, "saveCurrentTiles " + newTiles);
        saveTilesToSettings(newTiles);
    }

    /** Create a {@link QSTile} of a {@code tileSpec} type. */
    @Nullable
    public QSTile createTile(String tileSpec) {
        for (int i = 0; i < mQsFactories.size(); i++) {
            QSTile t = mQsFactories.get(i).createTile(tileSpec);
            if (t != null) {
                return t;
            }
        }
        return null;
    }

    /**
     * Create a view for a tile, iterating over all possible {@link QSFactory}.
     *
     * @see QSFactory#createTileView
     */
    public QSTileView createTileView(Context themedContext, QSTile tile, boolean collapsedView) {
        for (int i = 0; i < mQsFactories.size(); i++) {
            QSTileView view = mQsFactories.get(i)
                    .createTileView(themedContext, tile, collapsedView);
            if (view != null) {
                return view;
            }
        }
        throw new RuntimeException("Default factory didn't create view for " + tile.getTileSpec());
    }

    protected static List<String> loadTileSpecs(Context context, String tileList) {
        final Resources res = context.getResources();

        if (TextUtils.isEmpty(tileList)) {
            tileList = res.getString(R.string.quick_settings_tiles);
            if (DEBUG) Log.d(TAG, "Loaded tile specs from config: " + tileList);
        } else {
            if (DEBUG) Log.d(TAG, "Loaded tile specs from setting: " + tileList);
        }
        final ArrayList<String> tiles = new ArrayList<String>();
        boolean addedDefault = false;
        Set<String> addedSpecs = new ArraySet<>();
        for (String tile : tileList.split(",")) {
            tile = tile.trim();
            if (tile.isEmpty()) continue;
            if (tile.equals("default")) {
                if (!addedDefault) {
                    List<String> defaultSpecs = getDefaultSpecs(context);
                    for (String spec : defaultSpecs) {
                        if (!addedSpecs.contains(spec)) {
                            tiles.add(spec);
                            addedSpecs.add(spec);
                        }
                    }
                    addedDefault = true;
                }
            } else {
                if (!addedSpecs.contains(tile)) {
                    tiles.add(tile);
                    addedSpecs.add(tile);
                }
            }
        }

        if (!tiles.contains("internet")) {
            if (tiles.contains("wifi")) {
                // Replace the WiFi with Internet, and remove the Cell
                tiles.set(tiles.indexOf("wifi"), "internet");
                tiles.remove("cell");
            } else if (tiles.contains("cell")) {
                // Replace the Cell with Internet
                tiles.set(tiles.indexOf("cell"), "internet");
            }
        } else {
            tiles.remove("wifi");
            tiles.remove("cell");
        }
        return tiles;
    }

    /**
     * Returns the default QS tiles for the context.
     * @param context the context to obtain the resources from
     * @return a list of specs of the default tiles
     */
    public static List<String> getDefaultSpecs(Context context) {
        final ArrayList<String> tiles = new ArrayList<String>();

        final Resources res = context.getResources();
        final String defaultTileList = res.getString(R.string.quick_settings_tiles_default);

        tiles.addAll(Arrays.asList(defaultTileList.split(",")));
        if (Build.IS_DEBUGGABLE
                && GarbageMonitor.ADD_MEMORY_TILE_TO_DEFAULT_ON_DEBUGGABLE_BUILDS) {
            tiles.add(GarbageMonitor.MemoryTile.TILE_SPEC);
        }
        return tiles;
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.println("QSTileHost:");
        mTiles.values().stream().filter(obj -> obj instanceof Dumpable)
                .forEach(o -> ((Dumpable) o).dump(pw, args));
    }
}
