/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import static android.app.AppOpsManager.OP_MONITOR_HIGH_POWER_LOCATION;

import static com.android.settingslib.Utils.updateLocationEnabled;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.systemui.BootCompleteCache;
import com.android.systemui.appops.AppOpItem;
import com.android.systemui.appops.AppOpsController;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.util.Utils;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

/**
 * A controller to manage changes of location related states and update the views accordingly.
 */
@SysUISingleton
public class LocationControllerImpl extends BroadcastReceiver implements LocationController,
        AppOpsController.Callback {

    private final Context mContext;
    private final AppOpsController mAppOpsController;
    private final BootCompleteCache mBootCompleteCache;
    private final UserTracker mUserTracker;
    private final H mHandler;


    private boolean mAreActiveLocationRequests;

    @Inject
    public LocationControllerImpl(Context context, AppOpsController appOpsController,
            @Main Looper mainLooper, @Background Handler backgroundHandler,
            BroadcastDispatcher broadcastDispatcher, BootCompleteCache bootCompleteCache,
            UserTracker userTracker) {
        mContext = context;
        mAppOpsController = appOpsController;
        mBootCompleteCache = bootCompleteCache;
        mHandler = new H(mainLooper);
        mUserTracker = userTracker;

        // Register to listen for changes in location settings.
        IntentFilter filter = new IntentFilter();
        filter.addAction(LocationManager.MODE_CHANGED_ACTION);
        broadcastDispatcher.registerReceiverWithHandler(this, filter, mHandler, UserHandle.ALL);

        mAppOpsController.addCallback(new int[]{OP_MONITOR_HIGH_POWER_LOCATION}, this);

        // Examine the current location state and initialize the status view.
        backgroundHandler.post(this::updateActiveLocationRequests);
    }

    /**
     * Add a callback to listen for changes in location settings.
     */
    @Override
    public void addCallback(@NonNull LocationChangeCallback cb) {
        mHandler.obtainMessage(H.MSG_ADD_CALLBACK, cb).sendToTarget();
        mHandler.sendEmptyMessage(H.MSG_LOCATION_SETTINGS_CHANGED);
    }

    @Override
    public void removeCallback(@NonNull LocationChangeCallback cb) {
        mHandler.obtainMessage(H.MSG_REMOVE_CALLBACK, cb).sendToTarget();
    }

    /**
     * Enable or disable location in settings.
     *
     * <p>This will attempt to enable/disable every type of location setting
     * (e.g. high and balanced power).
     *
     * <p>If enabling, a user consent dialog will pop up prompting the user to accept.
     * If the user doesn't accept, network location won't be enabled.
     *
     * @return true if attempt to change setting was successful.
     */
    public boolean setLocationEnabled(boolean enabled) {
        // QuickSettings always runs as the owner, so specifically set the settings
        // for the current foreground user.
        int currentUserId = mUserTracker.getUserId();
        if (isUserLocationRestricted(currentUserId)) {
            return false;
        }
        // When enabling location, a user consent dialog will pop up, and the
        // setting won't be fully enabled until the user accepts the agreement.
        updateLocationEnabled(mContext, enabled, currentUserId,
                Settings.Secure.LOCATION_CHANGER_QUICK_SETTINGS);
        return true;
    }

    /**
     * Returns true if location is enabled in settings. Will return false if
     * {@link LocationManager} service has not been completely initialized
     */
    public boolean isLocationEnabled() {
        // QuickSettings always runs as the owner, so specifically retrieve the settings
        // for the current foreground user.
        LocationManager locationManager =
                (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
        return mBootCompleteCache.isBootComplete() && locationManager.isLocationEnabledForUser(
                mUserTracker.getUserHandle());
    }

    @Override
    public boolean isLocationActive() {
        return mAreActiveLocationRequests;
    }

    /**
     * Returns true if the current user is restricted from using location.
     */
    private boolean isUserLocationRestricted(int userId) {
        final UserManager um = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        return um.hasUserRestriction(UserManager.DISALLOW_SHARE_LOCATION,
                UserHandle.of(userId));
    }

    /**
     * Returns true if there currently exist active high power location requests.
     */
    @VisibleForTesting
    protected boolean areActiveHighPowerLocationRequests() {
        List<AppOpItem> appOpsItems = mAppOpsController.getActiveAppOps();

        final int numItems = appOpsItems.size();
        for (int i = 0; i < numItems; i++) {
            if (appOpsItems.get(i).getCode() == OP_MONITOR_HIGH_POWER_LOCATION) {
                return true;
            }
        }

        return false;
    }

    // Reads the active location requests and updates the status view if necessary.
    private void updateActiveLocationRequests() {
        boolean hadActiveLocationRequests = mAreActiveLocationRequests;
        mAreActiveLocationRequests = areActiveHighPowerLocationRequests();
        if (mAreActiveLocationRequests != hadActiveLocationRequests) {
            mHandler.sendEmptyMessage(H.MSG_LOCATION_ACTIVE_CHANGED);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (LocationManager.MODE_CHANGED_ACTION.equals(intent.getAction())) {
            mHandler.locationSettingsChanged();
        }
    }

    @Override
    public void onActiveStateChanged(int code, int uid, String packageName, boolean active) {
        updateActiveLocationRequests();
    }

    private final class H extends Handler {
        private static final int MSG_LOCATION_SETTINGS_CHANGED = 1;
        private static final int MSG_LOCATION_ACTIVE_CHANGED = 2;
        private static final int MSG_ADD_CALLBACK = 3;
        private static final int MSG_REMOVE_CALLBACK = 4;

        private ArrayList<LocationChangeCallback> mSettingsChangeCallbacks = new ArrayList<>();

        H(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_LOCATION_SETTINGS_CHANGED:
                    locationSettingsChanged();
                    break;
                case MSG_LOCATION_ACTIVE_CHANGED:
                    locationActiveChanged();
                    break;
                case MSG_ADD_CALLBACK:
                    mSettingsChangeCallbacks.add((LocationChangeCallback) msg.obj);
                    break;
                case MSG_REMOVE_CALLBACK:
                    mSettingsChangeCallbacks.remove((LocationChangeCallback) msg.obj);
                    break;

            }
        }

        private void locationActiveChanged() {
            Utils.safeForeach(mSettingsChangeCallbacks,
                    cb -> cb.onLocationActiveChanged(mAreActiveLocationRequests));
        }

        private void locationSettingsChanged() {
            boolean isEnabled = isLocationEnabled();
            Utils.safeForeach(mSettingsChangeCallbacks,
                    cb -> cb.onLocationSettingsChanged(isEnabled));
        }
    }
}
