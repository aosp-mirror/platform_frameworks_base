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

package android.app;

import static android.Manifest.permission.QUERY_ALL_PACKAGES;
import static android.annotation.SystemApi.Client.PRIVILEGED_APPS;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.content.pm.IBackgroundInstallControlService;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.ServiceManager;

import java.util.List;

/**
 * BackgroundInstallControlManager client allows apps to query apps installed in background.
 *
 * <p>Any applications that was installed without an accompanying installer UI activity paired
 * with recorded user interaction event is considered background installed. This is determined by
 * analysis of user-activity logs.
 *
 * <p>Warning: BackgroundInstallControl should not be considered a reliable or accurate
 * determination of background install application. Consumers can use this as a supplementary
 * signal, but must perform additional due diligence to confirm the install nature of the package.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_BIC_CLIENT)
@SystemApi(client = PRIVILEGED_APPS)
@SystemService(Context.BACKGROUND_INSTALL_CONTROL_SERVICE)
public final class BackgroundInstallControlManager {

    private static final String TAG = "BackgroundInstallControlManager";
    private static IBackgroundInstallControlService sService;
    private final Context mContext;

    BackgroundInstallControlManager(Context context) {
        mContext = context;
    }

    private static IBackgroundInstallControlService getService() {
        if (sService == null) {
            sService =
                    IBackgroundInstallControlService.Stub.asInterface(
                            ServiceManager.getService(Context.BACKGROUND_INSTALL_CONTROL_SERVICE));
        }
        return sService;
    }

    /**
     * Returns a full list of {@link PackageInfo} of apps currently installed that are considered
     * installed in the background.
     *
     * <p>Refer to top level doc {@link BackgroundInstallControlManager} for more details on
     * background-installed applications.
     * <p>
     *
     * @param flags - Flags will be used to call
     * {@link PackageManager#getInstalledPackages(PackageInfoFlags)} to retrieve installed packages.
     * @return A list of packages retrieved from {@link PackageManager} with non-background
     * installed app filter applied.
     *
     * @hide
     */
    @FlaggedApi(Flags.FLAG_BIC_CLIENT)
    @SystemApi
    @RequiresPermission(QUERY_ALL_PACKAGES)
    public @NonNull List<PackageInfo> getBackgroundInstalledPackages(
            @PackageManager.PackageInfoFlagsBits long flags) {
        try {
            return getService()
                    .getBackgroundInstalledPackages(flags, mContext.getUserId())
                    .getList();
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
