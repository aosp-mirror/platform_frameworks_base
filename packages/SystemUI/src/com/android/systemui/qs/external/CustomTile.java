/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */
package com.android.systemui.qs.external;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.Settings;
import android.service.quicksettings.IQSTileService;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.IWindowManager;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.systemui.R;
import com.android.systemui.qs.QSTile;
import com.android.systemui.qs.external.TileLifecycleManager.TileChangeListener;
import com.android.systemui.statusbar.phone.QSTileHost;
import libcore.util.Objects;

public class CustomTile extends QSTile<QSTile.State> implements TileChangeListener {
    public static final String PREFIX = "custom(";

    private static final boolean DEBUG = false;

    // We don't want to thrash binding and unbinding if the user opens and closes the panel a lot.
    // So instead we have a period of waiting.
    private static final long UNBIND_DELAY = 30000;

    private final ComponentName mComponent;
    private final Tile mTile;
    private final IWindowManager mWindowManager;
    private final IBinder mToken = new Binder();
    private final IQSTileService mService;
    private final TileServiceManager mServiceManager;
    private final int mUser;
    private Context mAppContext;
    private android.graphics.drawable.Icon mDefaultIcon;

    private boolean mListening;
    private boolean mBound;
    private boolean mIsTokenGranted;
    private boolean mIsShowingDialog;

    private CustomTile(QSTileHost host, String action) {
        super(host);
        mWindowManager = WindowManagerGlobal.getWindowManagerService();
        mComponent = ComponentName.unflattenFromString(action);
        mTile = new Tile();
        setTileIcon();
        mServiceManager = host.getTileServices().getTileWrapper(this);
        mService = mServiceManager.getTileService();
        mServiceManager.setTileChangeListener(this);
        mUser = ActivityManager.getCurrentUser();
        try {
            mAppContext = mContext.createPackageContext(mComponent.getPackageName(), 0);
        } catch (NameNotFoundException e) {
        }
    }

    private void setTileIcon() {
        try {
            PackageManager pm = mContext.getPackageManager();
            int flags = PackageManager.MATCH_ENCRYPTION_AWARE_AND_UNAWARE;
            if (isSystemApp(pm)) {
                flags |= PackageManager.MATCH_DISABLED_COMPONENTS;
            }
            ServiceInfo info = pm.getServiceInfo(mComponent, flags);
            int icon = info.icon != 0 ? info.icon
                    : info.applicationInfo.icon;
            // Update the icon if its not set or is the default icon.
            boolean updateIcon = mTile.getIcon() == null
                    || iconEquals(mTile.getIcon(), mDefaultIcon);
            mDefaultIcon = icon != 0 ? android.graphics.drawable.Icon
                    .createWithResource(mComponent.getPackageName(), icon) : null;
            if (updateIcon) {
                mTile.setIcon(mDefaultIcon);
            }
            // Update the label if there is no label.
            if (mTile.getLabel() == null) {
                mTile.setLabel(info.loadLabel(pm));
            }
        } catch (Exception e) {
            mDefaultIcon = null;
        }
    }

    private boolean isSystemApp(PackageManager pm) throws PackageManager.NameNotFoundException {
        return pm.getApplicationInfo(mComponent.getPackageName(), 0).isSystemApp();
    }

    /**
     * Compare two icons, only works for resources.
     */
    private boolean iconEquals(android.graphics.drawable.Icon icon1,
            android.graphics.drawable.Icon icon2) {
        if (icon1 == icon2) {
            return true;
        }
        if (icon1 == null || icon2 == null) {
            return false;
        }
        if (icon1.getType() != android.graphics.drawable.Icon.TYPE_RESOURCE
                || icon2.getType() != android.graphics.drawable.Icon.TYPE_RESOURCE) {
            return false;
        }
        if (icon1.getResId() != icon2.getResId()) {
            return false;
        }
        if (!Objects.equal(icon1.getResPackage(), icon2.getResPackage())) {
            return false;
        }
        return true;
    }

    @Override
    public void onTileChanged(ComponentName tile) {
        setTileIcon();
    }

    @Override
    public boolean isAvailable() {
        return mDefaultIcon != null;
    }

    public int getUser() {
        return mUser;
    }

    public ComponentName getComponent() {
        return mComponent;
    }

    public Tile getQsTile() {
        return mTile;
    }

    public void updateState(Tile tile) {
        mTile.setIcon(tile.getIcon());
        mTile.setLabel(tile.getLabel());
        mTile.setContentDescription(tile.getContentDescription());
        mTile.setState(tile.getState());
    }

    public void onDialogShown() {
        mIsShowingDialog = true;
    }

    public void onDialogHidden() {
        mIsShowingDialog = false;
        try {
            if (DEBUG) Log.d(TAG, "Removing token");
            mWindowManager.removeWindowToken(mToken);
        } catch (RemoteException e) {
        }
    }

    @Override
    public void setListening(boolean listening) {
        if (mListening == listening) return;
        mListening = listening;
        try {
            if (listening) {
                setTileIcon();
                refreshState();
                if (!mServiceManager.isActiveTile()) {
                    mServiceManager.setBindRequested(true);
                    mService.onStartListening();
                }
            } else {
                mService.onStopListening();
                if (mIsTokenGranted && !mIsShowingDialog) {
                    try {
                        if (DEBUG) Log.d(TAG, "Removing token");
                        mWindowManager.removeWindowToken(mToken);
                    } catch (RemoteException e) {
                    }
                    mIsTokenGranted = false;
                }
                mIsShowingDialog = false;
                mServiceManager.setBindRequested(false);
            }
        } catch (RemoteException e) {
            // Called through wrapper, won't happen here.
        }
    }

    @Override
    protected void handleDestroy() {
        super.handleDestroy();
        if (mIsTokenGranted) {
            try {
                if (DEBUG) Log.d(TAG, "Removing token");
                mWindowManager.removeWindowToken(mToken);
            } catch (RemoteException e) {
            }
        }
        mHost.getTileServices().freeService(this, mServiceManager);
    }

    @Override
    public State newTileState() {
        return new State();
    }

    @Override
    public Intent getLongClickIntent() {
        Intent i = new Intent(TileService.ACTION_QS_TILE_PREFERENCES);
        i.setPackage(mComponent.getPackageName());
        i = resolveIntent(i);
        if (i != null) {
            return i;
        }
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(
                Uri.fromParts("package", mComponent.getPackageName(), null));
    }

    private Intent resolveIntent(Intent i) {
        ResolveInfo result = mContext.getPackageManager().resolveActivityAsUser(i, 0,
                ActivityManager.getCurrentUser());
        return result != null ? new Intent(TileService.ACTION_QS_TILE_PREFERENCES)
                .setClassName(result.activityInfo.packageName, result.activityInfo.name) : null;
    }

    @Override
    protected void handleClick() {
        if (mTile.getState() == Tile.STATE_UNAVAILABLE) {
            return;
        }
        try {
            if (DEBUG) Log.d(TAG, "Adding token");
            mWindowManager.addWindowToken(mToken, WindowManager.LayoutParams.TYPE_QS_DIALOG);
            mIsTokenGranted = true;
        } catch (RemoteException e) {
        }
        try {
            if (mServiceManager.isActiveTile()) {
                mServiceManager.setBindRequested(true);
                mService.onStartListening();
            }
            mService.onClick(mToken);
        } catch (RemoteException e) {
            // Called through wrapper, won't happen here.
        }
        MetricsLogger.action(mContext, getMetricsCategory(), mComponent.getPackageName());
    }

    @Override
    public CharSequence getTileLabel() {
        return getState().label;
    }

    @Override
    protected void handleUpdateState(State state, Object arg) {
        int tileState = mTile.getState();
        if (mServiceManager.hasPendingBind()) {
            tileState = Tile.STATE_UNAVAILABLE;
        }
        Drawable drawable;
        boolean mHasRes = false;
        android.graphics.drawable.Icon icon = mTile.getIcon();
        try {
            drawable = icon.loadDrawable(mAppContext);
            mHasRes = icon.getType() == android.graphics.drawable.Icon.TYPE_RESOURCE;
        } catch (Exception e) {
            Log.w(TAG, "Invalid icon, forcing into unavailable state");
            tileState = Tile.STATE_UNAVAILABLE;
            drawable = mDefaultIcon.loadDrawable(mAppContext);
        }
        int color = mContext.getColor(getColor(tileState));
        drawable.setTint(color);
        state.icon = mHasRes ? new DrawableIconWithRes(drawable, icon.getResId())
                : new DrawableIcon(drawable);
        state.label = mTile.getLabel();
        if (tileState == Tile.STATE_UNAVAILABLE) {
            state.label = new SpannableStringBuilder().append(state.label,
                    new ForegroundColorSpan(color),
                    SpannableStringBuilder.SPAN_INCLUSIVE_INCLUSIVE);
        }
        if (mTile.getContentDescription() != null) {
            state.contentDescription = mTile.getContentDescription();
        } else {
            state.contentDescription = state.label;
        }
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_CUSTOM;
    }

    public void startUnlockAndRun() {
        mHost.startRunnableDismissingKeyguard(new Runnable() {
            @Override
            public void run() {
                try {
                    mService.onUnlockComplete();
                } catch (RemoteException e) {
                }
            }
        });
    }

    private static int getColor(int state) {
        switch (state) {
            case Tile.STATE_UNAVAILABLE:
                return R.color.qs_tile_tint_unavailable;
            case Tile.STATE_INACTIVE:
                return R.color.qs_tile_tint_inactive;
            case Tile.STATE_ACTIVE:
                return R.color.qs_tile_tint_active;
        }
        return 0;
    }

    public static String toSpec(ComponentName name) {
        return PREFIX + name.flattenToShortString() + ")";
    }

    public static ComponentName getComponentFromSpec(String spec) {
        final String action = spec.substring(PREFIX.length(), spec.length() - 1);
        if (action.isEmpty()) {
            throw new IllegalArgumentException("Empty custom tile spec action");
        }
        return ComponentName.unflattenFromString(action);
    }

    public static QSTile<?> create(QSTileHost host, String spec) {
        if (spec == null || !spec.startsWith(PREFIX) || !spec.endsWith(")")) {
            throw new IllegalArgumentException("Bad custom tile spec: " + spec);
        }
        final String action = spec.substring(PREFIX.length(), spec.length() - 1);
        if (action.isEmpty()) {
            throw new IllegalArgumentException("Empty custom tile spec action");
        }
        return new CustomTile(host, action);
    }
}
