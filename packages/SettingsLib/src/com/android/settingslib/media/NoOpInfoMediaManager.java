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

import android.app.Notification;
import android.content.Context;
import android.media.MediaRoute2Info;
import android.media.RouteListingPreference;
import android.media.RoutingSessionInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.settingslib.bluetooth.LocalBluetoothManager;

import java.util.Collections;
import java.util.List;

/**
 * No-op implementation of {@link InfoMediaManager}.
 *
 * <p>This implementation is used when {@link RouterInfoMediaManager} throws a {@link
 * InfoMediaManager.PackageNotAvailableException}.
 */
// TODO - b/293578081: Remove once PackageNotAvailableException is propagated to library clients.
/* package */ final class NoOpInfoMediaManager extends InfoMediaManager {

    NoOpInfoMediaManager(
            Context context,
            String packageName,
            Notification notification,
            LocalBluetoothManager localBluetoothManager) {
        super(context, packageName, notification, localBluetoothManager);
    }

    @Override
    public void stopScan() {
        // Do nothing.
    }

    @Override
    protected void startScanOnRouter() {
        // Do nothing.
    }

    @Override
    protected boolean connectDeviceWithoutPackageName(@NonNull MediaDevice device) {
        return false;
    }

    @Override
    protected void transferToRoute(@NonNull MediaRoute2Info route) {
        // Do nothing.
    }

    @Override
    protected void selectRoute(@NonNull MediaRoute2Info route, @NonNull RoutingSessionInfo info) {
        // Do nothing.
    }

    @Override
    protected void deselectRoute(@NonNull MediaRoute2Info route, @NonNull RoutingSessionInfo info) {
        // Do nothing.
    }

    @Override
    protected void releaseSession(@NonNull RoutingSessionInfo sessionInfo) {
        // Do nothing.
    }

    @NonNull
    @Override
    protected List<MediaRoute2Info> getSelectableRoutes(@NonNull RoutingSessionInfo info) {
        return Collections.emptyList();
    }

    @NonNull
    @Override
    protected List<MediaRoute2Info> getDeselectableRoutes(@NonNull RoutingSessionInfo info) {
        return Collections.emptyList();
    }

    @NonNull
    @Override
    protected List<MediaRoute2Info> getSelectedRoutes(@NonNull RoutingSessionInfo info) {
        return Collections.emptyList();
    }

    @Override
    protected void setSessionVolume(@NonNull RoutingSessionInfo info, int volume) {
        // Do nothing.
    }

    @Override
    protected void setRouteVolume(@NonNull MediaRoute2Info route, int volume) {
        // Do nothing.
    }

    @Nullable
    @Override
    protected RouteListingPreference getRouteListingPreference() {
        return null;
    }

    @NonNull
    @Override
    protected List<RoutingSessionInfo> getRemoteSessions() {
        return Collections.emptyList();
    }

    @NonNull
    @Override
    protected List<RoutingSessionInfo> getRoutingSessionsForPackage() {
        return Collections.emptyList();
    }

    @Nullable
    @Override
    protected RoutingSessionInfo getRoutingSessionById(@NonNull String sessionId) {
        return null;
    }

    @NonNull
    @Override
    protected List<MediaRoute2Info> getAllRoutes() {
        return Collections.emptyList();
    }

    @NonNull
    @Override
    protected List<MediaRoute2Info> getAvailableRoutesFromRouter() {
        return Collections.emptyList();
    }

    @NonNull
    @Override
    protected List<MediaRoute2Info> getTransferableRoutes(@NonNull String packageName) {
        return Collections.emptyList();
    }
}
