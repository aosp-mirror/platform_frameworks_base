/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.qs.tiles;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.provider.Settings;
import android.service.dreams.IDreamManager;
import android.service.quicksettings.Tile;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dreams.dagger.DreamModule;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.UserSettingObserver;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.util.settings.SecureSettings;

import javax.inject.Inject;
import javax.inject.Named;

/** Quick settings tile: Screensaver (dream) **/
public class DreamTile extends QSTileImpl<QSTile.BooleanState> {

    public static final String TILE_SPEC = "dream";

    private static final String LOG_TAG = "QSDream";
    // TODO: consider 1 animated icon instead
    private final Icon mIconDocked = ResourceIcon.get(R.drawable.ic_qs_screen_saver);
    private final Icon mIconUndocked = ResourceIcon.get(R.drawable.ic_qs_screen_saver_undocked);
    private final IDreamManager mDreamManager;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final UserSettingObserver mEnabledSettingObserver;
    private final UserSettingObserver mDreamSettingObserver;
    private final UserTracker mUserTracker;
    private final boolean mDreamSupported;
    private final boolean mDreamOnlyEnabledForDockUser;

    private boolean mIsDocked = false;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_DOCK_EVENT.equals(intent.getAction())) {
                mIsDocked = intent.getIntExtra(Intent.EXTRA_DOCK_STATE, -1)
                        != Intent.EXTRA_DOCK_STATE_UNDOCKED;
            }
            refreshState();
        }
    };

    @Inject
    public DreamTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            IDreamManager dreamManager,
            SecureSettings secureSettings,
            BroadcastDispatcher broadcastDispatcher,
            UserTracker userTracker,
            @Named(DreamModule.DREAM_SUPPORTED) boolean dreamSupported,
            @Named(DreamModule.DREAM_ONLY_ENABLED_FOR_DOCK_USER)
                    boolean dreamOnlyEnabledForDockUser
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mDreamManager = dreamManager;
        mBroadcastDispatcher = broadcastDispatcher;
        mEnabledSettingObserver = new UserSettingObserver(secureSettings, mHandler,
                Settings.Secure.SCREENSAVER_ENABLED, userTracker.getUserId()) {
            @Override
            protected void handleValueChanged(int value, boolean observedChange) {
                refreshState();
            }
        };
        mDreamSettingObserver = new UserSettingObserver(secureSettings, mHandler,
                Settings.Secure.SCREENSAVER_COMPONENTS, userTracker.getUserId()) {
            @Override
            protected void handleValueChanged(int value, boolean observedChange) {
                refreshState();
            }
        };
        mUserTracker = userTracker;
        mDreamSupported = dreamSupported;
        mDreamOnlyEnabledForDockUser = dreamOnlyEnabledForDockUser;
    }

    @Override
    public void handleSetListening(boolean listening) {
        super.handleSetListening(listening);

        if (listening) {
            final IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_DREAMING_STARTED);
            filter.addAction(Intent.ACTION_DREAMING_STOPPED);
            filter.addAction(Intent.ACTION_DOCK_EVENT);
            mBroadcastDispatcher.registerReceiver(mReceiver, filter);
        } else {
            mBroadcastDispatcher.unregisterReceiver(mReceiver);
        }
        mEnabledSettingObserver.setListening(listening);
        mDreamSettingObserver.setListening(listening);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleClick(@Nullable View view) {
        try {
            if (mDreamManager.isDreaming()) {
                mDreamManager.awaken();
            } else {
                mDreamManager.dream();
            }
        } catch (RemoteException e) {
            Log.e(LOG_TAG, "Can't dream", e);
        }
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        state.label = getTileLabel();
        state.secondaryLabel = getActiveDreamName();
        state.contentDescription = getContentDescription(state.secondaryLabel);
        state.icon = mIsDocked ? mIconDocked : mIconUndocked;

        if (getActiveDream() == null || !isScreensaverEnabled()) {
            state.state = Tile.STATE_UNAVAILABLE;
        } else {
            state.state = isDreaming() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
        }
    }

    @Nullable
    @Override
    public Intent getLongClickIntent() {
        return new Intent(Settings.ACTION_DREAM_SETTINGS);
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_screensaver_label);
    }

    @Override
    public boolean isAvailable() {
        // Only enable for devices that have dreams for the user(s) that can dream.
        // For now, restrict to debug users.
        return Build.isDebuggable()
                && mDreamSupported
                && (!mDreamOnlyEnabledForDockUser || mUserTracker.getUserInfo().isMain());
    }

    @VisibleForTesting
    protected CharSequence getContentDescription(CharSequence dreamName) {
        return !TextUtils.isEmpty(dreamName)
                ? getTileLabel() + ", " + dreamName : getTileLabel();
    }

    private boolean isDreaming() {
        try {
            return mDreamManager.isDreaming();
        } catch (RemoteException e) {
            Log.e(LOG_TAG, "Can't check if dreaming", e);
            return false;
        }
    }

    private ComponentName getActiveDream() {
        try {
            final ComponentName[] dreams = mDreamManager.getDreamComponentsForUser(
                                                mUserTracker.getUserId());
            return dreams != null && dreams.length > 0 ? dreams[0] : null;
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to get active dream", e);
            return null;
        }
    }

    private CharSequence getActiveDreamName() {
        final ComponentName componentName = getActiveDream();
        if (componentName != null) {
            PackageManager pm = mContext.getPackageManager();
            try {
                ServiceInfo ri = pm.getServiceInfo(componentName, 0);
                if (ri != null) {
                    return ri.loadLabel(pm);
                }
            } catch (PackageManager.NameNotFoundException exc) {
                return null; // uninstalled?
            }
        }
        return null;
    }

    private boolean isScreensaverEnabled() {
        return mEnabledSettingObserver.getValue() == 1;
    }
}
