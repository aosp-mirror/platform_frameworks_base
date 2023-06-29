/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.settingslib.media;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Notification;
import android.content.Context;
import android.media.MediaRoute2Info;
import android.media.MediaRouter2Manager;
import android.media.RouteListingPreference;
import android.media.RoutingSessionInfo;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.settingslib.bluetooth.LocalBluetoothManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Template implementation of {@link InfoMediaManager} using {@link MediaRouter2Manager}.
 */
public class ManagerInfoMediaManager extends InfoMediaManager {

    private static final String TAG = "ManagerInfoMediaManager";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    @VisibleForTesting
    /* package */ final RouterManagerCallback mMediaRouterCallback = new RouterManagerCallback();
    @VisibleForTesting
    /* package */ MediaRouter2Manager mRouterManager;
    boolean mIsScanning = false;

    private final Executor mExecutor = Executors.newSingleThreadExecutor();

    public ManagerInfoMediaManager(
            Context context,
            String packageName,
            Notification notification,
            LocalBluetoothManager localBluetoothManager) {
        super(context, packageName, notification, localBluetoothManager);

        mRouterManager = MediaRouter2Manager.getInstance(context);
    }

    @Override
    protected void startScanOnRouter() {
        if (!mIsScanning) {
            mRouterManager.registerCallback(mExecutor, mMediaRouterCallback);
            mRouterManager.registerScanRequest();
            mIsScanning = true;
        }
    }

    @Override
    public void stopScan() {
        if (mIsScanning) {
            mRouterManager.unregisterCallback(mMediaRouterCallback);
            mRouterManager.unregisterScanRequest();
            mIsScanning = false;
        }
    }

    @Override
    protected boolean connectDeviceWithoutPackageName(@NonNull MediaDevice device) {
        final RoutingSessionInfo info = mRouterManager.getSystemRoutingSession(null);
        if (info != null) {
            mRouterManager.transfer(info, device.mRouteInfo);
            return true;
        }
        return false;
    }

    @Override
    protected void selectRoute(@NonNull MediaRoute2Info route, @NonNull RoutingSessionInfo info) {
        mRouterManager.selectRoute(info, route);
    }

    @Override
    protected void deselectRoute(@NonNull MediaRoute2Info route, @NonNull RoutingSessionInfo info) {
        mRouterManager.deselectRoute(info, route);
    }

    @Override
    protected void releaseSession(@NonNull RoutingSessionInfo sessionInfo) {
        mRouterManager.releaseSession(sessionInfo);
    }

    @Override
    @NonNull
    protected List<MediaRoute2Info> getSelectableRoutes(@NonNull RoutingSessionInfo info) {
        return mRouterManager.getSelectableRoutes(info);
    }

    @Override
    @NonNull
    protected List<MediaRoute2Info> getDeselectableRoutes(@NonNull RoutingSessionInfo info) {
        return mRouterManager.getDeselectableRoutes(info);
    }

    @Override
    @NonNull
    protected List<MediaRoute2Info> getSelectedRoutes(@NonNull RoutingSessionInfo info) {
        return mRouterManager.getSelectedRoutes(info);
    }

    @Override
    protected void setSessionVolume(@NonNull RoutingSessionInfo info, int volume) {
        mRouterManager.setSessionVolume(info, volume);
    }

    @Override
    @Nullable
    protected RouteListingPreference getRouteListingPreference() {
        return mRouterManager.getRouteListingPreference(mPackageName);
    }

    @Override
    @NonNull
    protected List<RoutingSessionInfo> getRoutingSessionsForPackage() {
        return mRouterManager.getRoutingSessions(mPackageName);
    }

    @Override
    @NonNull
    protected List<RoutingSessionInfo> getActiveRoutingSessions() {
        List<RoutingSessionInfo> infos = new ArrayList<>();
        infos.add(mRouterManager.getSystemRoutingSession(null));
        infos.addAll(mRouterManager.getRemoteSessions());
        return infos;
    }

    @Override
    @NonNull
    protected List<MediaRoute2Info> getAllRoutes() {
        return mRouterManager.getAllRoutes();
    }

    @Override
    @NonNull
    protected List<MediaRoute2Info> getAvailableRoutesFromRouter() {
        return mRouterManager.getAvailableRoutes(mPackageName);
    }

    @Override
    @NonNull
    protected List<MediaRoute2Info> getTransferableRoutes(@NonNull String packageName) {
        return mRouterManager.getTransferableRoutes(packageName);
    }

    @Override
    @NonNull
    protected ComplexMediaDevice createComplexMediaDevice(
            MediaRoute2Info route, RouteListingPreference.Item routeListingPreferenceItem) {
        return new ComplexMediaDevice(
                mContext, mRouterManager, route, mPackageName, routeListingPreferenceItem);
    }

    @Override
    @NonNull
    protected InfoMediaDevice createInfoMediaDevice(
            MediaRoute2Info route, RouteListingPreference.Item routeListingPreferenceItem) {
        return new InfoMediaDevice(
                mContext, mRouterManager, route, mPackageName, routeListingPreferenceItem);
    }

    @Override
    @NonNull
    protected PhoneMediaDevice createPhoneMediaDevice(MediaRoute2Info route) {
        return new PhoneMediaDevice(mContext, mRouterManager, route, mPackageName);
    }

    @Override
    @NonNull
    protected BluetoothMediaDevice createBluetoothMediaDevice(
            MediaRoute2Info route, CachedBluetoothDevice cachedDevice) {
        return new BluetoothMediaDevice(
                mContext, cachedDevice, mRouterManager, route, mPackageName);
    }

    @VisibleForTesting
    /* package */ final class RouterManagerCallback implements MediaRouter2Manager.Callback {

        @Override
        public void onRoutesUpdated() {
            refreshDevices();
        }

        @Override
        public void onPreferredFeaturesChanged(String packageName, List<String> preferredFeatures) {
            if (TextUtils.equals(mPackageName, packageName)) {
                refreshDevices();
            }
        }

        @Override
        public void onTransferred(RoutingSessionInfo oldSession, RoutingSessionInfo newSession) {
            if (DEBUG) {
                Log.d(
                        TAG,
                        "onTransferred() oldSession : "
                                + oldSession.getName()
                                + ", newSession : "
                                + newSession.getName());
            }
            rebuildDeviceList();
            notifyCurrentConnectedDeviceChanged();
        }

        /**
         * Ignore callback here since we'll also receive{@link
         * MediaRouter2Manager.Callback#onRequestFailed onRequestFailed} with reason code.
         */
        @Override
        public void onTransferFailed(RoutingSessionInfo session, MediaRoute2Info route) {}

        @Override
        public void onRequestFailed(int reason) {
            dispatchOnRequestFailed(reason);
        }

        @Override
        public void onSessionUpdated(RoutingSessionInfo sessionInfo) {
            refreshDevices();
        }

        @Override
        public void onSessionReleased(@NonNull RoutingSessionInfo session) {
            refreshDevices();
        }

        @Override
        public void onRouteListingPreferenceUpdated(
                String packageName, RouteListingPreference routeListingPreference) {
            if (!TextUtils.equals(mPackageName, packageName)) {
                return;
            }
            notifyRouteListingPreferenceUpdated(routeListingPreference);
            refreshDevices();
        }
    }
}
