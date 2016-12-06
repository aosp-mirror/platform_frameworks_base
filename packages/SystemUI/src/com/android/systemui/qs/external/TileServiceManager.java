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
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.UserHandle;
import android.service.quicksettings.IQSTileService;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.support.annotation.VisibleForTesting;
import android.util.Log;

import com.android.systemui.qs.customize.TileQueryHelper.TileStateListener;
import com.android.systemui.qs.external.TileLifecycleManager.TileChangeListener;

import java.util.List;

import libcore.util.Objects;

/**
 * Manages the priority which lets {@link TileServices} make decisions about which tiles
 * to bind.  Also holds on to and manages the {@link TileLifecycleManager}, informing it
 * of when it is allowed to bind based on decisions frome the {@link TileServices}.
 */
public class TileServiceManager {

    private static final long MIN_BIND_TIME = 5000;
    private static final long UNBIND_DELAY = 30000;

    public static final boolean DEBUG = true;

    private static final String TAG = "TileServiceManager";

    @VisibleForTesting
    static final String PREFS_FILE = "CustomTileModes";

    private final TileServices mServices;
    private final TileLifecycleManager mStateManager;
    private final Handler mHandler;
    private boolean mBindRequested;
    private boolean mBindAllowed;
    private boolean mBound;
    private int mPriority;
    private boolean mJustBound;
    private long mLastUpdate;
    private boolean mShowingDialog;
    // Whether we have a pending bind going out to the service without a response yet.
    // This defaults to true to ensure tiles start out unavailable.
    private boolean mPendingBind = true;

    TileServiceManager(TileServices tileServices, Handler handler, ComponentName component,
            Tile tile) {
        this(tileServices, handler, new TileLifecycleManager(handler,
                tileServices.getContext(), tileServices, tile, new Intent().setComponent(component),
                new UserHandle(ActivityManager.getCurrentUser())));
    }

    @VisibleForTesting
    TileServiceManager(TileServices tileServices, Handler handler,
            TileLifecycleManager tileLifecycleManager) {
        mServices = tileServices;
        mHandler = handler;
        mStateManager = tileLifecycleManager;

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addDataScheme("package");
        Context context = mServices.getContext();
        context.registerReceiverAsUser(mUninstallReceiver,
                new UserHandle(ActivityManager.getCurrentUser()), filter, null, mHandler);
        ComponentName component = tileLifecycleManager.getComponent();
        if (!TileLifecycleManager.isTileAdded(context, component)) {
            TileLifecycleManager.setTileAdded(context, component, true);
            mStateManager.onTileAdded();
            mStateManager.flushMessagesAndUnbind();
        }
    }

    public void setTileChangeListener(TileChangeListener changeListener) {
        mStateManager.setTileChangeListener(changeListener);
    }

    public boolean isActiveTile() {
        return mStateManager.isActiveTile();
    }

    public void setShowingDialog(boolean dialog) {
        mShowingDialog = dialog;
    }

    public IQSTileService getTileService() {
        return mStateManager;
    }

    public IBinder getToken() {
        return mStateManager.getToken();
    }

    public void setBindRequested(boolean bindRequested) {
        if (mBindRequested == bindRequested) return;
        mBindRequested = bindRequested;
        if (mBindAllowed && mBindRequested && !mBound) {
            mHandler.removeCallbacks(mUnbind);
            bindService();
        } else {
            mServices.recalculateBindAllowance();
        }
        if (mBound && !mBindRequested) {
            mHandler.postDelayed(mUnbind, UNBIND_DELAY);
        }
    }

    public void setLastUpdate(long lastUpdate) {
        mLastUpdate = lastUpdate;
        if (mBound && isActiveTile()) {
            mStateManager.onStopListening();
            setBindRequested(false);
        }
        mServices.recalculateBindAllowance();
    }

    public void handleDestroy() {
        mServices.getContext().unregisterReceiver(mUninstallReceiver);
        mStateManager.handleDestroy();
    }

    public void setBindAllowed(boolean allowed) {
        if (mBindAllowed == allowed) return;
        mBindAllowed = allowed;
        if (!mBindAllowed && mBound) {
            unbindService();
        } else if (mBindAllowed && mBindRequested && !mBound) {
            bindService();
        }
    }

    public boolean hasPendingBind() {
        return mPendingBind;
    }

    public void clearPendingBind() {
        mPendingBind = false;
    }

    private void bindService() {
        if (mBound) {
            Log.e(TAG, "Service already bound");
            return;
        }
        mPendingBind = true;
        mBound = true;
        mJustBound = true;
        mHandler.postDelayed(mJustBoundOver, MIN_BIND_TIME);
        mStateManager.setBindService(true);
    }

    private void unbindService() {
        if (!mBound) {
            Log.e(TAG, "Service not bound");
            return;
        }
        mBound = false;
        mJustBound = false;
        mStateManager.setBindService(false);
    }

    public void calculateBindPriority(long currentTime) {
        if (mStateManager.hasPendingClick()) {
            // Pending click is the most important thing, need to put this service at the top of
            // the list to be bound.
            mPriority = Integer.MAX_VALUE;
        } else if (mShowingDialog) {
            // Hang on to services that are showing dialogs so they don't die.
            mPriority = Integer.MAX_VALUE - 1;
        } else if (mJustBound) {
            // If we just bound, lets not thrash on binding/unbinding too much, this is second most
            // important.
            mPriority = Integer.MAX_VALUE - 2;
        } else if (!mBindRequested) {
            // Don't care about binding right now, put us last.
            mPriority = Integer.MIN_VALUE;
        } else {
            // Order based on whether this was just updated.
            long timeSinceUpdate = currentTime - mLastUpdate;
            // Fit compare into integer space for simplicity. Make sure to leave MAX_VALUE and
            // MAX_VALUE - 1 for the more important states above.
            if (timeSinceUpdate > Integer.MAX_VALUE - 3) {
                mPriority = Integer.MAX_VALUE - 3;
            } else {
                mPriority = (int) timeSinceUpdate;
            }
        }
    }

    public int getBindPriority() {
        return mPriority;
    }

    private final Runnable mUnbind = new Runnable() {
        @Override
        public void run() {
            if (mBound && !mBindRequested) {
                unbindService();
            }
        }
    };

    @VisibleForTesting
    final Runnable mJustBoundOver = new Runnable() {
        @Override
        public void run() {
            mJustBound = false;
            mServices.recalculateBindAllowance();
        }
    };

    private final BroadcastReceiver mUninstallReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())) {
                return;
            }

            Uri data = intent.getData();
            String pkgName = data.getEncodedSchemeSpecificPart();
            final ComponentName component = mStateManager.getComponent();
            if (!Objects.equal(pkgName, component.getPackageName())) {
                return;
            }

            // If the package is being updated, verify the component still exists.
            if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                Intent queryIntent = new Intent(TileService.ACTION_QS_TILE);
                queryIntent.setPackage(pkgName);
                PackageManager pm = context.getPackageManager();
                List<ResolveInfo> services = pm.queryIntentServicesAsUser(
                        queryIntent, 0, ActivityManager.getCurrentUser());
                for (ResolveInfo info : services) {
                    if (Objects.equal(info.serviceInfo.packageName, component.getPackageName())
                            && Objects.equal(info.serviceInfo.name, component.getClassName())) {
                        return;
                    }
                }
            }

            mServices.getHost().removeTile(component);
        }
    };
}
