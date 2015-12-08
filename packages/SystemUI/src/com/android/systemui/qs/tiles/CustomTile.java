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
 * limitations under the License.
 */

package com.android.systemui.qs.tiles;

import android.app.ActivityManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Drawable;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.quicksettings.IQSTileService;
import android.service.quicksettings.Tile;
import android.util.Log;
import android.view.IWindowManager;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import com.android.internal.logging.MetricsLogger;
import com.android.systemui.qs.QSTile;
import com.android.systemui.qs.QSTileServiceWrapper;
import com.android.systemui.statusbar.phone.QSTileHost;

public class CustomTile extends QSTile<QSTile.State> {
    public static final String PREFIX = "custom(";

    private static final boolean DEBUG = false;

    // We don't want to thrash binding and unbinding if the user opens and closes the panel a lot.
    // So instead we have a period of waiting.
    private static final long UNBIND_DELAY = 30000;

    private final ComponentName mComponent;
    private final Tile mTile;
    private final IWindowManager mWindowManager;
    private final IBinder mToken = new Binder();

    private QSTileServiceWrapper mService;
    private boolean mListening;
    private boolean mBound;
    private boolean mIsTokenGranted;
    private boolean mIsShowingDialog;

    private CustomTile(QSTileHost host, String action) {
        super(host);
        mWindowManager = WindowManagerGlobal.getWindowManagerService();
        mComponent = ComponentName.unflattenFromString(action);
        mTile = new Tile(mComponent, host);
        try {
            PackageManager pm = mContext.getPackageManager();
            ServiceInfo info = pm.getServiceInfo(mComponent, 0);
            mTile.setIcon(android.graphics.drawable.Icon
                    .createWithResource(mComponent.getPackageName(), info.icon));
            mTile.setLabel(info.loadLabel(pm));
        } catch (Exception e) {
        }
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
    }

    public void onDialogShown() {
        mIsShowingDialog = true;
    }

    @Override
    public void setListening(boolean listening) {
        if (mListening == listening) return;
        mListening = listening;
        if (listening) {
            mHandler.removeCallbacks(mUnbind);
            if (!mBound) {
                // TODO: Guarantee re-bind on user-switch.
                mContext.bindServiceAsUser(new Intent().setComponent(mComponent),
                        mServiceConnection, Service.BIND_AUTO_CREATE,
                        new UserHandle(ActivityManager.getCurrentUser()));
                mBound = true;
            } else {
                if (mService != null) {
                    mService.onStartListening();
                } else {
                    Log.d(TAG, "Can't start service listening");
                }
            }
        } else {
            if (mService != null) {
                mService.onStopListening();
            }
            if (mIsTokenGranted && !mIsShowingDialog) {
                try {
                    if (DEBUG) Log.d(TAG, "Removing token");
                    mWindowManager.removeWindowToken(mToken);
                } catch (RemoteException e) {
                }
                mIsTokenGranted = false;
            }
            mIsShowingDialog = false;
            mHandler.postDelayed(mUnbind, UNBIND_DELAY);
        }
    }

    @Override
    protected void handleDestroy() {
        super.handleDestroy();
        mHandler.removeCallbacks(mUnbind);
        if (mIsTokenGranted) {
            try {
                if (DEBUG) Log.d(TAG, "Removing token");
                mWindowManager.removeWindowToken(mToken);
            } catch (RemoteException e) {
            }
        }
        mUnbind.run();
    }

    @Override
    protected State newTileState() {
        return new State();
    }

    @Override
    protected void handleUserSwitch(int newUserId) {
        super.handleUserSwitch(newUserId);
    }

    @Override
    protected void handleClick() {
        if (mService != null) {
            try {
                if (DEBUG) Log.d(TAG, "Adding token");
                mWindowManager.addWindowToken(mToken, WindowManager.LayoutParams.TYPE_QS_DIALOG);
                mIsTokenGranted = true;
            } catch (RemoteException e) {
            }
            mService.onClick(mToken);
        } else {
            Log.e(TAG, "Click with no service " + getTileSpec());
        }
        MetricsLogger.action(mContext, getMetricsCategory(), mComponent.getPackageName());
    }

    @Override
    protected void handleLongClick() {
    }

    @Override
    protected void handleUpdateState(State state, Object arg) {
        state.visible = true;
        Drawable drawable = mTile.getIcon().loadDrawable(mContext);
        drawable.setTint(mContext.getColor(android.R.color.white));
        state.icon = new DrawableIcon(drawable);
        state.label = mTile.getLabel();
        if (mTile.getContentDescription() != null) {
            state.contentDescription = mTile.getContentDescription();
        } else {
            state.contentDescription = state.label;
        }
    }

    @Override
    public int getMetricsCategory() {
        return MetricsLogger.QS_INTENT;
    }

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mService = new QSTileServiceWrapper(IQSTileService.Stub.asInterface(service));
            if (mListening) {
                mService.setQSTile(mTile);
                mService.onStartListening();
            } else {
                mService.onStopListening();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
        }
    };

    private final Runnable mUnbind = new Runnable() {
        @Override
        public void run() {
            mContext.unbindService(mServiceConnection);
            mBound = false;
        }
    };

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
