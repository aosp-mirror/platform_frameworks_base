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

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.MediaRoute2Info;
import android.media.MediaRouter2;
import android.media.MediaRouter2.RoutingController;
import android.media.MediaRouter2Manager;
import android.media.RouteDiscoveryPreference;
import android.media.RouteListingPreference;
import android.media.RoutingSessionInfo;
import android.media.session.MediaController;
import android.os.UserHandle;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.media.flags.Flags;
import com.android.settingslib.bluetooth.LocalBluetoothManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Implements {@link InfoMediaManager} using {@link MediaRouter2}. */
@SuppressLint("MissingPermission")
public final class RouterInfoMediaManager extends InfoMediaManager {

    private static final String TAG = "RouterInfoMediaManager";

    private final MediaRouter2 mRouter;
    private final MediaRouter2Manager mRouterManager;

    private final Executor mExecutor = Executors.newSingleThreadExecutor();

    private final RouteCallback mRouteCallback = new RouteCallback();
    private final TransferCallback mTransferCallback = new TransferCallback();
    private final ControllerCallback mControllerCallback = new ControllerCallback();
    private final Consumer<RouteListingPreference> mRouteListingPreferenceCallback =
            (preference) -> {
                notifyRouteListingPreferenceUpdated(preference);
                refreshDevices();
            };

    private final AtomicReference<MediaRouter2.ScanToken> mScanToken = new AtomicReference<>();

    // TODO (b/321969740): Plumb target UserHandle between UMO and RouterInfoMediaManager.
    /* package */ RouterInfoMediaManager(
            Context context,
            @NonNull String packageName,
            @NonNull UserHandle userHandle,
            LocalBluetoothManager localBluetoothManager,
            @Nullable MediaController mediaController)
            throws PackageNotAvailableException {
        super(context, packageName, userHandle, localBluetoothManager, mediaController);

        MediaRouter2 router = null;

        if (Flags.enableCrossUserRoutingInMediaRouter2()) {
            try {
                router = MediaRouter2.getInstance(context, packageName, userHandle);
            } catch (IllegalArgumentException ex) {
                // Do nothing
            }
        } else {
            router = MediaRouter2.getInstance(context, packageName);
        }
        if (router == null) {
            throw new PackageNotAvailableException(
                    "Package name " + packageName + " does not exist.");
        }
        // We have to defer initialization because mRouter is final.
        mRouter = router;

        mRouterManager = MediaRouter2Manager.getInstance(context);
    }

    @Override
    protected void startScanOnRouter() {
        if (Flags.enableScreenOffScanning()) {
            MediaRouter2.ScanRequest request = new MediaRouter2.ScanRequest.Builder().build();
            mScanToken.compareAndSet(null, mRouter.requestScan(request));
        } else {
            mRouter.startScan();
        }
    }

    @Override
    protected void registerRouter() {
        mRouter.registerRouteCallback(mExecutor, mRouteCallback, RouteDiscoveryPreference.EMPTY);
        mRouter.registerRouteListingPreferenceUpdatedCallback(
                mExecutor, mRouteListingPreferenceCallback);
        mRouter.registerTransferCallback(mExecutor, mTransferCallback);
        mRouter.registerControllerCallback(mExecutor, mControllerCallback);
    }

    @Override
    protected void stopScanOnRouter() {
        if (Flags.enableScreenOffScanning()) {
            MediaRouter2.ScanToken token = mScanToken.getAndSet(null);
            if (token != null) {
                mRouter.cancelScanRequest(token);
            }
        } else {
            mRouter.stopScan();
        }
    }

    @Override
    protected void unregisterRouter() {
        mRouter.unregisterControllerCallback(mControllerCallback);
        mRouter.unregisterTransferCallback(mTransferCallback);
        mRouter.unregisterRouteListingPreferenceUpdatedCallback(mRouteListingPreferenceCallback);
        mRouter.unregisterRouteCallback(mRouteCallback);
    }

    @Override
    protected void transferToRoute(@NonNull MediaRoute2Info route) {
        mRouter.transferTo(route);
    }

    @Override
    protected void selectRoute(@NonNull MediaRoute2Info route, @NonNull RoutingSessionInfo info) {
        RoutingController controller = getControllerForSession(info);
        if (controller != null) {
            controller.selectRoute(route);
        }
    }

    @Override
    protected void deselectRoute(@NonNull MediaRoute2Info route, @NonNull RoutingSessionInfo info) {
        RoutingController controller = getControllerForSession(info);
        if (controller != null) {
            controller.deselectRoute(route);
        }
    }

    @Override
    protected void releaseSession(@NonNull RoutingSessionInfo sessionInfo) {
        RoutingController controller = getControllerForSession(sessionInfo);
        if (controller != null) {
            controller.release();
        }
    }

    @NonNull
    @Override
    protected List<MediaRoute2Info> getSelectableRoutes(@NonNull RoutingSessionInfo info) {
        RoutingController controller = getControllerForSession(info);
        if (controller == null) {
            return Collections.emptyList();
        }

        // Filter out selected routes.
        List<String> selectedRouteIds = controller.getRoutingSessionInfo().getSelectedRoutes();
        return controller.getSelectableRoutes().stream()
                .filter(route -> !selectedRouteIds.contains(route.getId()))
                .collect(Collectors.toList());
    }

    @NonNull
    @Override
    protected List<MediaRoute2Info> getDeselectableRoutes(@NonNull RoutingSessionInfo info) {
        RoutingController controller = getControllerForSession(info);
        if (controller == null) {
            return Collections.emptyList();
        }

        return controller.getDeselectableRoutes();
    }

    @NonNull
    @Override
    protected List<MediaRoute2Info> getSelectedRoutes(@NonNull RoutingSessionInfo info) {
        RoutingController controller = getControllerForSession(info);
        if (controller == null) {
            return Collections.emptyList();
        }
        return controller.getSelectedRoutes();
    }

    @Override
    protected void setSessionVolume(@NonNull RoutingSessionInfo info, int volume) {
        // TODO: b/291277292 - Implement MediaRouter2-based solution. Keeping MR2Manager call as
        //      MR2 filters information by package name.
        mRouterManager.setSessionVolume(info, volume);
    }

    @Override
    protected void setRouteVolume(@NonNull MediaRoute2Info route, int volume) {
        mRouter.setRouteVolume(route, volume);
    }

    @Nullable
    @Override
    protected RouteListingPreference getRouteListingPreference() {
        return mRouter.getRouteListingPreference();
    }

    @NonNull
    @Override
    protected List<RoutingSessionInfo> getRemoteSessions() {
        // TODO: b/291277292 - Implement MediaRouter2-based solution. Keeping MR2Manager call as
        //      MR2 filters information by package name.
        return mRouterManager.getRemoteSessions();
    }

    @NonNull
    @Override
    protected List<RoutingSessionInfo> getRoutingSessionsForPackage() {
        return mRouter.getControllers().stream()
                .map(RoutingController::getRoutingSessionInfo)
                .collect(Collectors.toList());
    }

    @Nullable
    @Override
    protected RoutingSessionInfo getRoutingSessionById(@NonNull String sessionId) {
        // TODO: b/291277292 - Implement MediaRouter2-based solution. Keeping MR2Manager calls as
        //      MR2 filters information by package name.

        for (RoutingSessionInfo sessionInfo : getRemoteSessions()) {
            if (TextUtils.equals(sessionInfo.getId(), sessionId)) {
                return sessionInfo;
            }
        }

        RoutingSessionInfo systemSession = mRouterManager.getSystemRoutingSession(null);
        return TextUtils.equals(systemSession.getId(), sessionId) ? systemSession : null;
    }

    @NonNull
    @Override
    protected List<MediaRoute2Info> getAvailableRoutesFromRouter() {
        return mRouter.getRoutes();
    }

    @NonNull
    @Override
    protected List<MediaRoute2Info> getTransferableRoutes(@NonNull String packageName) {
        List<RoutingController> controllers = mRouter.getControllers();
        RoutingController activeController = controllers.get(controllers.size() - 1);
        HashMap<String, MediaRoute2Info> transferableRoutes = new HashMap<>();

        activeController
                .getTransferableRoutes()
                .forEach(route -> transferableRoutes.put(route.getId(), route));

        if (activeController.getRoutingSessionInfo().isSystemSession()) {
            mRouter.getRoutes().stream()
                    .filter(route -> !route.isSystemRoute())
                    .forEach(route -> transferableRoutes.put(route.getId(), route));
        } else {
            mRouter.getRoutes().stream()
                    .filter(route -> route.isSystemRoute())
                    .forEach(route -> transferableRoutes.put(route.getId(), route));
        }

        return new ArrayList<>(transferableRoutes.values());
    }

    @Nullable
    private RoutingController getControllerForSession(@NonNull RoutingSessionInfo sessionInfo) {
        return mRouter.getController(sessionInfo.getId());
    }

    private final class RouteCallback extends MediaRouter2.RouteCallback {
        @Override
        public void onRoutesUpdated(@NonNull List<MediaRoute2Info> routes) {
            refreshDevices();
        }

        @Override
        public void onPreferredFeaturesChanged(@NonNull List<String> preferredFeatures) {
            refreshDevices();
        }
    }

    private final class TransferCallback extends MediaRouter2.TransferCallback {
        @Override
        public void onTransfer(
                @NonNull RoutingController oldController,
                @NonNull RoutingController newController) {
            rebuildDeviceList();
            notifyCurrentConnectedDeviceChanged();
        }

        @Override
        public void onTransferFailure(@NonNull MediaRoute2Info requestedRoute) {
            // Do nothing.
        }

        @Override
        public void onStop(@NonNull RoutingController controller) {
            refreshDevices();
        }

        @Override
        public void onRequestFailed(int reason) {
            dispatchOnRequestFailed(reason);
        }
    }

    private final class ControllerCallback extends MediaRouter2.ControllerCallback {
        @Override
        public void onControllerUpdated(@NonNull RoutingController controller) {
            refreshDevices();
        }
    }
}
