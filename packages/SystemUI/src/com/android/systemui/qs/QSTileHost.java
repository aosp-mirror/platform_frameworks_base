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
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings.Secure;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.Dumpable;
import com.android.systemui.ProtoDumpable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.nano.SystemUIProtoDump;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.plugins.PluginManager;
import com.android.systemui.plugins.qs.QSFactory;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.qs.external.CustomTile;
import com.android.systemui.qs.external.CustomTileStatePersister;
import com.android.systemui.qs.external.TileLifecycleManager;
import com.android.systemui.qs.external.TileServiceKey;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.nano.QsTileState;
import com.android.systemui.qs.pipeline.data.repository.CustomTileAddedRepository;
import com.android.systemui.qs.pipeline.domain.interactor.PanelInteractor;
import com.android.systemui.qs.pipeline.shared.QSPipelineFlagsRepository;
import com.android.systemui.qs.tiles.di.NewQSTileFactory;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserFileManager;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.statusbar.phone.AutoTileManager;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;
import com.android.systemui.util.settings.SecureSettings;

import dagger.Lazy;

import org.jetbrains.annotations.NotNull;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Provider;

/** Platform implementation of the quick settings tile host
 *
 * This class keeps track of the set of current tiles and is the in memory source of truth
 * (ground truth is kept in {@link Secure#QS_TILES}). When the ground truth changes,
 * {@link #onTuningChanged} will be called and the tiles will be re-created as needed.
 *
 * This class also provides the interface for adding/removing/changing tiles.
 */
@SysUISingleton
public class QSTileHost implements QSHost, Tunable, PluginListener<QSFactory>, ProtoDumpable,
        PanelInteractor, CustomTileAddedRepository {
    private static final String TAG = "QSTileHost";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    // Shared prefs that hold tile lifecycle info.
    @VisibleForTesting
    static final String TILES = "tiles_prefs";

    private final Context mContext;
    private final LinkedHashMap<String, QSTile> mTiles = new LinkedHashMap<>();
    private final ArrayList<String> mTileSpecs = new ArrayList<>();
    private final TunerService mTunerService;
    private final PluginManager mPluginManager;
    private final QSLogger mQSLogger;
    private final CustomTileStatePersister mCustomTileStatePersister;
    private final Executor mMainExecutor;
    private final UserFileManager mUserFileManager;

    private final List<Callback> mCallbacks = new ArrayList<>();
    @Nullable
    private AutoTileManager mAutoTiles;
    private final ArrayList<QSFactory> mQsFactories = new ArrayList<>();
    private int mCurrentUser;
    private final ShadeController mShadeController;
    private Context mUserContext;
    private UserTracker mUserTracker;
    private SecureSettings mSecureSettings;
    // Keep track of whether mTilesList contains the same information as the Settings value.
    // This is a performance optimization to reduce the number of blocking calls to Settings from
    // main thread.
    // This is enforced by only cleaning the flag at the end of a successful run of #onTuningChanged
    private boolean mTilesListDirty = true;

    private TileLifecycleManager.Factory mTileLifeCycleManagerFactory;

    private final QSPipelineFlagsRepository mFeatureFlags;

    @Inject
    public QSTileHost(Context context,
            Lazy<NewQSTileFactory> newQsTileFactoryProvider,
            QSFactory defaultFactory,
            @Main Executor mainExecutor,
            PluginManager pluginManager,
            TunerService tunerService,
            Provider<AutoTileManager> autoTiles,
            ShadeController shadeController,
            QSLogger qsLogger,
            UserTracker userTracker,
            SecureSettings secureSettings,
            CustomTileStatePersister customTileStatePersister,
            TileLifecycleManager.Factory tileLifecycleManagerFactory,
            UserFileManager userFileManager,
            QSPipelineFlagsRepository featureFlags
    ) {
        mContext = context;
        mUserContext = context;
        mTunerService = tunerService;
        mPluginManager = pluginManager;
        mQSLogger = qsLogger;
        mMainExecutor = mainExecutor;
        mTileLifeCycleManagerFactory = tileLifecycleManagerFactory;
        mUserFileManager = userFileManager;
        mFeatureFlags = featureFlags;

        mShadeController = shadeController;

        if (featureFlags.getTilesEnabled()) {
            mQsFactories.add(newQsTileFactoryProvider.get());
        }
        mQsFactories.add(defaultFactory);
        pluginManager.addPluginListener(this, QSFactory.class, true);
        mUserTracker = userTracker;
        mCurrentUser = userTracker.getUserId();
        mSecureSettings = secureSettings;
        mCustomTileStatePersister = customTileStatePersister;

        mainExecutor.execute(() -> {
            // This is technically a hack to avoid circular dependency of
            // QSTileHost -> XXXTile -> QSTileHost. Posting ensures creation
            // finishes before creating any tiles.
            tunerService.addTunable(this, TILES_SETTING);
            // AutoTileManager can modify mTiles so make sure mTiles has already been initialized.
            if (!mFeatureFlags.getPipelineEnabled()) {
                mAutoTiles = autoTiles.get();
            }
        });
    }

    public void destroy() {
        mTiles.values().forEach(tile -> tile.destroy());
        mAutoTiles.destroy();
        mTunerService.removeTunable(this);
        mPluginManager.removePluginListener(this);
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
    public void collapsePanels() {
        mShadeController.postAnimateCollapseShade();
    }

    @Override
    public void forceCollapsePanels() {
        mShadeController.postAnimateForceCollapseShade();
    }

    @Override
    public void openPanels() {
        mShadeController.postAnimateExpandQs();
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

    /**
     * Whenever the Secure Setting keeping track of the current tiles changes (or upon start) this
     * will be called with the new value of the setting.
     *
     * This method will do the following:
     * <ol>
     *     <li>Destroy any existing tile that's not one of the current tiles (in the setting)</li>
     *     <li>Create new tiles for those that don't already exist. If this tiles end up being
     *         not available, they'll also be destroyed.</li>
     *     <li>Save the resolved list of tiles (current tiles that are available) into the setting.
     *         This means that after this call ends, the tiles in the Setting, {@link #mTileSpecs},
     *         and visible tiles ({@link #mTiles}) must match.
     *         </li>
     * </ol>
     *
     * Additionally, if the user has changed, it'll do the following:
     * <ul>
     *     <li>Change the user for SystemUI tiles: {@link QSTile#userSwitch}.</li>
     *     <li>Destroy any {@link CustomTile} and recreate it for the new user.</li>
     * </ul>
     *
     * This happens in main thread as {@link com.android.systemui.tuner.TunerServiceImpl} dispatches
     * in main thread.
     *
     * @see QSTile#isAvailable
     */
    @MainThread
    @Override
    public void onTuningChanged(String key, String newValue) {
        if (!TILES_SETTING.equals(key)) {
            return;
        }
        int currentUser = mUserTracker.getUserId();
        if (currentUser != mCurrentUser) {
            mUserContext = mUserTracker.getUserContext();
            if (mAutoTiles != null) {
                mAutoTiles.changeUser(UserHandle.of(currentUser));
            }
        }
        // Do not process tiles if the flag is enabled.
        if (mFeatureFlags.getPipelineEnabled()) {
            return;
        }
        QSPipelineFlagsRepository.Utils.assertInLegacyMode();
        if (newValue == null && UserManager.isDeviceInDemoMode(mContext)) {
            newValue = mContext.getResources().getString(R.string.quick_settings_tiles_retail_mode);
        }
        final List<String> tileSpecs = loadTileSpecs(mContext, newValue);
        if (tileSpecs.equals(mTileSpecs) && currentUser == mCurrentUser) return;
        Log.d(TAG, "Recreating tiles: " + tileSpecs);
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
                    Log.d(TAG, "Adding " + tile);
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
                        if (tile.isAvailable()) {
                            newTiles.put(tileSpec, tile);
                            mQSLogger.logTileAdded(tileSpec);
                        } else {
                            tile.destroy();
                            Log.d(TAG, "Destroying not available tile: " + tileSpec);
                            mQSLogger.logTileDestroyed(tileSpec, "Tile not available");
                        }
                    } else {
                        Log.d(TAG, "No factory for a spec: " + tileSpec);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Error creating tile for spec: " + tileSpec, t);
                }
            }
        }
        mCurrentUser = currentUser;
        List<String> currentSpecs = new ArrayList<>(mTileSpecs);
        mTileSpecs.clear();
        mTileSpecs.addAll(newTiles.keySet()); // Only add the valid (available) tiles.
        mTiles.clear();
        mTiles.putAll(newTiles);
        if (newTiles.isEmpty() && !tileSpecs.isEmpty()) {
            // If we didn't manage to create any tiles, set it to empty (default)
            Log.d(TAG, "No valid tiles on tuning changed. Setting to default.");
            changeTilesByUser(currentSpecs, loadTileSpecs(mContext, ""));
        } else {
            String resolvedTiles = TextUtils.join(",", mTileSpecs);
            if (!resolvedTiles.equals(newValue)) {
                // If the resolved tiles (those we actually ended up with) are different than
                // the ones that are in the setting, update the Setting.
                saveTilesToSettings(mTileSpecs);
            }
            mTilesListDirty = false;
            for (int i = 0; i < mCallbacks.size(); i++) {
                mCallbacks.get(i).onTilesChanged();
            }
        }
    }

    /**
     * Only use with [CustomTile] if the tile doesn't exist anymore (and therefore doesn't need
     * its lifecycle terminated).
     */
    @Override
    public void removeTile(String spec) {
        if (spec.startsWith(CustomTile.PREFIX)) {
            // If the tile is removed (due to it not actually existing), mark it as removed. That
            // way it will be marked as newly added if it appears in the future.
            setTileAdded(CustomTile.getComponentFromSpec(spec), mCurrentUser, false);
        }
        mMainExecutor.execute(() -> changeTileSpecs(tileSpecs-> tileSpecs.remove(spec)));
    }

    /**
     * Remove many tiles at once.
     *
     * It will only save to settings once (as opposed to {@link QSTileHost#removeTileByUser} called
     * multiple times).
     */
    @Override
    public void removeTiles(Collection<String> specs) {
        mMainExecutor.execute(() -> changeTileSpecs(tileSpecs -> tileSpecs.removeAll(specs)));
    }

    /**
     * Add a tile to the end
     *
     * @param spec string matching a pre-defined tilespec
     */
    public void addTile(String spec) {
        addTile(spec, POSITION_AT_END);
    }

    @Override
    public void addTile(String spec, int requestPosition) {
        mMainExecutor.execute(() ->
                changeTileSpecs(tileSpecs -> {
                    if (tileSpecs.contains(spec)) return false;

                    int size = tileSpecs.size();
                    if (requestPosition == POSITION_AT_END || requestPosition >= size) {
                        tileSpecs.add(spec);
                    } else {
                        tileSpecs.add(requestPosition, spec);
                    }
                    return true;
                })
        );
    }

    // When calling this, you may want to modify mTilesListDirty accordingly.
    @MainThread
    private void saveTilesToSettings(List<String> tileSpecs) {
        Log.d(TAG, "Saving tiles: " + tileSpecs + " for user: " + mCurrentUser);
        mSecureSettings.putStringForUser(TILES_SETTING, TextUtils.join(",", tileSpecs),
                null /* tag */, false /* default */, mCurrentUser,
                true /* overrideable by restore */);
    }

    @MainThread
    private void changeTileSpecs(Predicate<List<String>> changeFunction) {
        final List<String> tileSpecs;
        if (!mTilesListDirty) {
            tileSpecs = new ArrayList<>(mTileSpecs);
        } else {
            tileSpecs = loadTileSpecs(mContext,
                    mSecureSettings.getStringForUser(TILES_SETTING, mCurrentUser));
        }
        if (changeFunction.test(tileSpecs)) {
            mTilesListDirty = true;
            saveTilesToSettings(tileSpecs);
        }
    }

    @Override
    public void addTile(ComponentName tile) {
        addTile(tile, /* end */ false);
    }

    @Override
    public void addTile(ComponentName tile, boolean end) {
        String spec = CustomTile.toSpec(tile);
        addTile(spec, end ? POSITION_AT_END : 0);
    }

    /**
     * This will call through {@link #changeTilesByUser}. It should only be used when a tile is
     * removed by a <b>user action</b> like {@code adb}.
     */
    @Override
    public void removeTileByUser(ComponentName tile) {
        mMainExecutor.execute(() -> {
            List<String> newSpecs = new ArrayList<>(mTileSpecs);
            if (newSpecs.remove(CustomTile.toSpec(tile))) {
                changeTilesByUser(mTileSpecs, newSpecs);
            }
        });
    }

    /**
     * Change the tiles triggered by the user editing.
     * <p>
     * This is not called on device start, or on user change.
     *
     * {@link android.service.quicksettings.TileService#onTileRemoved} will be called for tiles
     * that are removed.
     */
    @MainThread
    @Override
    public void changeTilesByUser(List<String> previousTiles, List<String> newTiles) {
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
                setTileAdded(component, mCurrentUser, false);
                lifecycleManager.flushMessagesAndUnbind();
            }
        }
        Log.d(TAG, "saveCurrentTiles " + newTiles);
        mTilesListDirty = true;
        saveTilesToSettings(newTiles);
    }

    @Nullable
    @Override
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
     * Check if a particular {@link CustomTile} has been added for a user and has not been removed
     * since.
     * @param componentName the {@link ComponentName} of the
     *                      {@link android.service.quicksettings.TileService} associated with the
     *                      tile.
     * @param userId the user to check
     */
    @Override
    public boolean isTileAdded(ComponentName componentName, int userId) {
        return mUserFileManager
                .getSharedPreferences(TILES, 0, userId)
                .getBoolean(componentName.flattenToString(), false);
    }

    /**
     * Persists whether a particular {@link CustomTile} has been added and it's currently in the
     * set of selected tiles ({@link #mTiles}.
     * @param componentName the {@link ComponentName} of the
     *                      {@link android.service.quicksettings.TileService} associated
     *                      with the tile.
     * @param userId the user for this tile
     * @param added {@code true} if the tile is being added, {@code false} otherwise
     */
    @Override
    public void setTileAdded(ComponentName componentName, int userId, boolean added) {
        mUserFileManager.getSharedPreferences(TILES, 0, userId)
                .edit()
                .putBoolean(componentName.flattenToString(), added)
                .apply();
    }

    @Override
    public List<String> getSpecs() {
        return mTileSpecs;
    }

    protected static List<String> loadTileSpecs(Context context, String tileList) {
        final Resources res = context.getResources();

        if (TextUtils.isEmpty(tileList)) {
            tileList = res.getString(R.string.quick_settings_tiles);
            Log.d(TAG, "Loaded tile specs from default config: " + tileList);
        } else {
            Log.d(TAG, "Loaded tile specs from setting: " + tileList);
        }
        final ArrayList<String> tiles = new ArrayList<String>();
        boolean addedDefault = false;
        Set<String> addedSpecs = new ArraySet<>();
        for (String tile : tileList.split(",")) {
            tile = tile.trim();
            if (tile.isEmpty()) continue;
            if (tile.equals("default")) {
                if (!addedDefault) {
                    List<String> defaultSpecs = QSHost.getDefaultSpecs(context.getResources());
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

    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.println("QSTileHost:");
        pw.println("tile specs: " + mTileSpecs);
        pw.println("current user: " + mCurrentUser);
        pw.println("is dirty: " + mTilesListDirty);
        pw.println("tiles:");
        mTiles.values().stream().filter(obj -> obj instanceof Dumpable)
                .forEach(o -> ((Dumpable) o).dump(pw, args));
    }

    @Override
    public void dumpProto(@NotNull SystemUIProtoDump systemUIProtoDump, @NotNull String[] args) {
        List<QsTileState> data = mTiles.values().stream()
                .map(QSTile::getState)
                .map(TileStateToProtoKt::toProto)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        systemUIProtoDump.tiles = data.toArray(new QsTileState[0]);
    }
}
