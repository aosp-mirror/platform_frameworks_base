/*
 * Copyright (C) 2024 The Android Open Source Project
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
package android.nfc;

import android.annotation.FlaggedApi;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.nfc.cardemulation.CardEmulation;

/**
 * A class indicating default route, ISO-DEP route and off-host route.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
public class RoutingStatus {
    private final @CardEmulation.ProtocolAndTechnologyRoute int mDefaultRoute;
    private final @CardEmulation.ProtocolAndTechnologyRoute int mDefaultIsoDepRoute;
    private final @CardEmulation.ProtocolAndTechnologyRoute int mDefaultOffHostRoute;

    RoutingStatus(@CardEmulation.ProtocolAndTechnologyRoute int mDefaultRoute,
                  @CardEmulation.ProtocolAndTechnologyRoute int mDefaultIsoDepRoute,
                  @CardEmulation.ProtocolAndTechnologyRoute int mDefaultOffHostRoute) {
        this.mDefaultRoute = mDefaultRoute;
        this.mDefaultIsoDepRoute = mDefaultIsoDepRoute;
        this.mDefaultOffHostRoute = mDefaultOffHostRoute;
    }

    /**
     * Getter of the default route.
     * @return an integer defined in
     * {@link android.nfc.cardemulation.CardEmulation.ProtocolAndTechnologyRoute}
     */
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    @CardEmulation.ProtocolAndTechnologyRoute
    public int getDefaultRoute() {
        return mDefaultRoute;
    }

    /**
     * Getter of the default ISO-DEP route.
     * @return an integer defined in
     * {@link android.nfc.cardemulation.CardEmulation.ProtocolAndTechnologyRoute}
     */
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    @CardEmulation.ProtocolAndTechnologyRoute
    public int getDefaultIsoDepRoute() {
        return mDefaultIsoDepRoute;
    }

    /**
     * Getter of the default off-host route.
     * @return an integer defined in
     * {@link android.nfc.cardemulation.CardEmulation.ProtocolAndTechnologyRoute}
     */
    @FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
    @RequiresPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
    @CardEmulation.ProtocolAndTechnologyRoute
    public int getDefaultOffHostRoute() {
        return mDefaultOffHostRoute;
    }

}
