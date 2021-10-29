/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.companion;

import static android.Manifest.permission.BIND_COMPANION_DEVICE_SERVICE;
import static android.content.Context.BIND_IMPORTANT;

import static com.android.internal.util.CollectionUtils.filter;

import android.annotation.NonNull;
import android.companion.AssociationInfo;
import android.companion.CompanionDeviceService;
import android.companion.ICompanionDeviceService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.infra.PerUser;
import com.android.internal.infra.ServiceConnector;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * This class creates/removes {@link ServiceConnector}s between {@link CompanionDeviceService} and
 * the companion apps. The controller also will notify the companion apps with device status.
 */
public class CompanionDevicePresenceController {
    private static final String LOG_TAG = "CompanionDevicePresenceController";
    PerUser<ArrayMap<String, List<BoundService>>> mBoundServices;
    private static final String META_DATA_KEY_PRIMARY = "primary";

    public CompanionDevicePresenceController() {
        mBoundServices = new PerUser<ArrayMap<String, List<BoundService>>>() {
            @NonNull
            @Override
            protected ArrayMap<String, List<BoundService>> create(int userId) {
                return new ArrayMap<>();
            }
        };
    }

    void onDeviceNotifyAppeared(AssociationInfo association, Context context, Handler handler) {
        ServiceConnector<ICompanionDeviceService> primaryConnector =
                getPrimaryServiceConnector(association, context, handler);
        if (primaryConnector != null) {
            Slog.i(LOG_TAG,
                    "Sending onDeviceAppeared to " + association.getPackageName() + ")");
            primaryConnector.run(
                    service -> service.onDeviceAppeared(association.getDeviceMacAddress()));
        }
    }

    void onDeviceNotifyDisappeared(AssociationInfo association, Context context, Handler handler) {
        ServiceConnector<ICompanionDeviceService> primaryConnector =
                getPrimaryServiceConnector(association, context, handler);
        if (primaryConnector != null) {
            Slog.i(LOG_TAG,
                    "Sending onDeviceDisappeared to " + association.getPackageName() + ")");
            primaryConnector.run(
                    service -> service.onDeviceDisappeared(association.getDeviceMacAddress()));
        }
    }

    void unbindDevicePresenceListener(String packageName, int userId) {
        List<BoundService> boundServices = mBoundServices.forUser(userId)
                .remove(packageName);
        if (boundServices != null) {
            for (BoundService boundService: boundServices) {
                Slog.d(LOG_TAG, "Unbinding the serviceConnector: " + boundService.mComponentName);
                boundService.mServiceConnector.unbind();
            }
        }
    }

    private ServiceConnector<ICompanionDeviceService> getPrimaryServiceConnector(
            AssociationInfo association, Context context, Handler handler) {
        for (BoundService boundService: getDeviceListenerServiceConnector(association, context,
                handler)) {
            if (boundService.mIsPrimary) {
                return boundService.mServiceConnector;
            }
        }
        return null;
    }

    private List<BoundService> getDeviceListenerServiceConnector(AssociationInfo a, Context context,
            Handler handler) {
        return mBoundServices.forUser(a.getUserId()).computeIfAbsent(
                a.getPackageName(),
                pkg -> createDeviceListenerServiceConnector(a, context, handler));
    }

    private List<BoundService> createDeviceListenerServiceConnector(AssociationInfo a,
            Context context, Handler handler) {
        List<ResolveInfo> resolveInfos = context
                .getPackageManager()
                .queryIntentServicesAsUser(new Intent(CompanionDeviceService.SERVICE_INTERFACE),
                        PackageManager.GET_META_DATA, a.getUserId());
        List<ResolveInfo> packageResolveInfos = filter(resolveInfos,
                info -> Objects.equals(info.serviceInfo.packageName, a.getPackageName()));
        List<BoundService> serviceConnectors = new ArrayList<>();
        if (!validatePackageInfo(packageResolveInfos, a)) {
            return serviceConnectors;
        }
        for (ResolveInfo packageResolveInfo : packageResolveInfos) {
            boolean isPrimary = (packageResolveInfo.serviceInfo.metaData != null
                    && packageResolveInfo.serviceInfo.metaData.getBoolean(META_DATA_KEY_PRIMARY))
                    || packageResolveInfos.size() == 1;
            ComponentName componentName = packageResolveInfo.serviceInfo.getComponentName();

            Slog.i(LOG_TAG, "Initializing CompanionDeviceService binding for " + componentName);

            ServiceConnector<ICompanionDeviceService> serviceConnector =
                    new ServiceConnector.Impl<ICompanionDeviceService>(context,
                            new Intent(CompanionDeviceService.SERVICE_INTERFACE).setComponent(
                                    componentName), BIND_IMPORTANT, a.getUserId(),
                            ICompanionDeviceService.Stub::asInterface) {
                        @Override
                        protected long getAutoDisconnectTimeoutMs() {
                            // Service binding is managed manually based on corresponding device
                            // being nearby
                            return Long.MAX_VALUE;
                        }

                        @Override
                        public void binderDied() {
                            super.binderDied();

                            // Re-connect to the service if process gets killed
                            handler.postDelayed(
                                    this::connect,
                                    CompanionDeviceManagerService
                                            .DEVICE_LISTENER_DIED_REBIND_TIMEOUT_MS);
                        }
                    };

            serviceConnectors.add(new BoundService(componentName, isPrimary, serviceConnector));
        }
        return serviceConnectors;
    }

    private boolean validatePackageInfo(List<ResolveInfo> packageResolveInfos,
            AssociationInfo association) {
        if (packageResolveInfos.size() == 0 || packageResolveInfos.size() > 5) {
            Slog.e(LOG_TAG, "Device presence listener package must have at least one and not "
                    + "more than five CompanionDeviceService(s) declared. But "
                    + association.getPackageName()
                    + " has " + packageResolveInfos.size());
            return false;
        }

        int primaryCount = 0;
        for (ResolveInfo packageResolveInfo : packageResolveInfos) {
            String servicePermission = packageResolveInfo.serviceInfo.permission;
            if (!BIND_COMPANION_DEVICE_SERVICE.equals(servicePermission)) {
                Slog.e(LOG_TAG, "Binding CompanionDeviceService must have "
                        + BIND_COMPANION_DEVICE_SERVICE + " permission.");
                return false;
            }

            if (packageResolveInfo.serviceInfo.metaData != null
                    && packageResolveInfo.serviceInfo.metaData.getBoolean(META_DATA_KEY_PRIMARY)) {
                primaryCount++;
                if (primaryCount > 1) {
                    Slog.e(LOG_TAG, "Must have exactly one primary CompanionDeviceService "
                            + "to be bound but "
                            + association.getPackageName() + "has " + primaryCount);
                    return false;
                }
            }
        }

        if (packageResolveInfos.size() == 1 && primaryCount != 0) {
            Slog.w(LOG_TAG, "Do not need the primary metadata if there's only one"
                    + " CompanionDeviceService " + "but " + association.getPackageName()
                    + " has " + primaryCount);
        }

        return true;
    }

    private static class BoundService {
        private final ComponentName mComponentName;
        private final boolean mIsPrimary;
        private final ServiceConnector<ICompanionDeviceService> mServiceConnector;

        BoundService(ComponentName componentName,
                boolean isPrimary,  ServiceConnector<ICompanionDeviceService> serviceConnector) {
            this.mComponentName = componentName;
            this.mIsPrimary = isPrimary;
            this.mServiceConnector = serviceConnector;
        }
    }
}
