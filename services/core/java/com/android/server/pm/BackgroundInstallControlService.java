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

package com.android.server.pm;

import android.annotation.NonNull;
import android.content.Context;
import android.content.pm.IBackgroundInstallControlService;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.SparseArrayMap;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.SystemService;

/**
 * @hide
 */
public class BackgroundInstallControlService extends SystemService {
    private static final String TAG = "BackgroundInstallControlService";

    private final Context mContext;
    private final BinderService mBinderService;
    private final IPackageManager mIPackageManager;

    // User ID -> package name -> time diff
    // The time diff between the last foreground activity installer and
    // the "onPackageAdded" function call.
    private final SparseArrayMap<String, Long> mBackgroundInstalledPackages =
            new SparseArrayMap<>();

    public BackgroundInstallControlService(@NonNull Context context) {
        this(new InjectorImpl(context));
    }

    @VisibleForTesting
    BackgroundInstallControlService(@NonNull Injector injector) {
        super(injector.getContext());
        mContext = injector.getContext();
        mIPackageManager = injector.getIPackageManager();
        mBinderService = new BinderService(this);
    }

    private static final class BinderService extends IBackgroundInstallControlService.Stub {
        final BackgroundInstallControlService mService;

        BinderService(BackgroundInstallControlService service)  {
            mService = service;
        }

        @Override
        public ParceledListSlice<PackageInfo> getBackgroundInstalledPackages(
                @PackageManager.PackageInfoFlagsBits long flags, int userId) {
            ParceledListSlice<PackageInfo> packages;
            try {
                packages = mService.mIPackageManager.getInstalledPackages(flags, userId);
            } catch (RemoteException e) {
                throw new IllegalStateException("Package manager not available", e);
            }

            // TODO(b/244216300): to enable the test the usage by BinaryTransparencyService,
            // we currently comment out the actual implementation.
            // The fake implementation is just to filter out the first app of the list.
            // for (int i = 0, size = packages.getList().size(); i < size; i++) {
            //     String packageName = packages.getList().get(i).packageName;
            //     if (!mBackgroundInstalledPackages.contains(userId, packageName) {
            //         packages.getList().remove(i);
            //     }
            // }
            if (packages.getList().size() > 0) {
                packages.getList().remove(0);
            }
            return packages;
        }
    }

    /**
     * Called when the system service should publish a binder service using
     * {@link #publishBinderService(String, IBinder).}
     */
    @Override
    public void onStart() {
        publishBinderService(Context.BACKGROUND_INSTALL_CONTROL_SERVICE, mBinderService);
    }

    /**
     * Dependency injector for {@link #BackgroundInstallControlService)}.
     */
    interface Injector {
        Context getContext();

        IPackageManager getIPackageManager();
    }

    private static final class InjectorImpl implements Injector {
        private final Context mContext;

        InjectorImpl(Context context) {
            mContext = context;
        }

        @Override
        public Context getContext() {
            return mContext;
        }

        @Override
        public IPackageManager getIPackageManager() {
            return IPackageManager.Stub.asInterface(ServiceManager.getService("package"));
        }
    }
}
