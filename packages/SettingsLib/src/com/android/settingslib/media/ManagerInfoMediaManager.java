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

import android.content.Context;
import android.media.MediaRoute2Info;
import android.media.MediaRouter2Manager;
import android.media.RouteListingPreference;
import android.media.RoutingSessionInfo;
import android.media.session.MediaController;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.settingslib.bluetooth.LocalBluetoothManager;

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

    /* package */ ManagerInfoMediaManager(
            Context context,
            @NonNull String packageName,
            @NonNull UserHandle userHandle,
            LocalBluetoothManager localBluetoothManager,
            @Nullable MediaController mediaController) {
        super(context, packageName, userHandle, localBluetoothManager, mediaController);

        mRouterManager = MediaRouter2Manager.getInstance(context);
    }

    @Override
    protected void startScanOnRouter() {
        if (!mIsScanning) {
            mRouterManager.registerScanRequest();
            mIsScanning = true;
        }
    }

    @Override
    protected void registerRouter() {
        mRouterManager.registerCallback(mExecutor, mMediaRouterCallback);
    }

    @Override
    protected void stopScanOnRouter() {
        if (mIsScanning) {
            mRouterManager.unregisterScanRequest();
            mIsScanning = false;
        }
    }

    @Override
    protected void unregisterRouter() {
        mRouterManager.unregisterCallback(mMediaRouterCallback);
    }

    @Override
    protected void transferToRoute(@NonNull MediaRoute2Info route) {
        mRouterManager.transfer(mPackageName, route, mUserHandle);
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
    protected void setRouteVolume(@NonNull MediaRoute2Info route, int volume) {
        mRouterManager.setRouteVolume(route, volume);
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
    protected List<RoutingSessionInfo> getRemoteSessions() {
        return mRouterManager.getRemoteSessions();
    }

    @Nullable
    @Override
    protected RoutingSessionInfo getRoutingSessionById(@NonNull String sessionId) {
        for (RoutingSessionInfo sessionInfo : getRemoteSessions()) {
            if (TextUtils.equals(sessionInfo.getId(), sessionId)) {
                return sessionInfo;
            }
        }

        RoutingSessionInfo systemSession = mRouterManager.getSystemRoutingSession(null);

        return TextUtils.equals(systemSession.getId(), sessionId) ? systemSession : null;
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
