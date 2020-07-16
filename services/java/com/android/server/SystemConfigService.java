/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.server;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

import android.Manifest;
import android.content.Context;
import android.os.ISystemConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Service class that runs inside the system_server process to handle queries to
 * {@link com.android.server.SystemConfig}.
 * @hide
 */
public class SystemConfigService extends SystemService {
    private final Context mContext;

    private final ISystemConfig.Stub mInterface = new ISystemConfig.Stub() {
        @Override
        public List<String> getDisabledUntilUsedPreinstalledCarrierApps() {
            mContext.enforceCallingOrSelfPermission(Manifest.permission.READ_CARRIER_APP_INFO,
                    "getDisabledUntilUsedPreInstalledCarrierApps requires READ_CARRIER_APP_INFO");
            return new ArrayList<>(
                    SystemConfig.getInstance().getDisabledUntilUsedPreinstalledCarrierApps());
        }

        @Override
        public Map getDisabledUntilUsedPreinstalledCarrierAssociatedApps() {
            mContext.enforceCallingOrSelfPermission(Manifest.permission.READ_CARRIER_APP_INFO,
                    "getDisabledUntilUsedPreInstalledCarrierAssociatedApps requires"
                            + " READ_CARRIER_APP_INFO");
            return SystemConfig.getInstance()
                    .getDisabledUntilUsedPreinstalledCarrierAssociatedApps().entrySet().stream()
                    .collect(toMap(
                            Map.Entry::getKey,
                            e -> e.getValue().stream().map(app -> app.packageName)
                                    .collect(toList())));
        }

        @Override
        public Map getDisabledUntilUsedPreinstalledCarrierAssociatedAppEntries() {
            mContext.enforceCallingOrSelfPermission(Manifest.permission.READ_CARRIER_APP_INFO,
                    "getDisabledUntilUsedPreInstalledCarrierAssociatedAppEntries requires"
                            + " READ_CARRIER_APP_INFO");
            return SystemConfig.getInstance()
                    .getDisabledUntilUsedPreinstalledCarrierAssociatedApps();
        }
    };

    public SystemConfigService(Context context) {
        super(context);
        mContext = context;
    }

    @Override
    public void onStart() {
        publishBinderService(Context.SYSTEM_CONFIG_SERVICE, mInterface);
    }
}
