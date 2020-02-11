/*
 * Copyright (C) 2019 The Android Open Source Project
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
package android.net.util;

import android.net.INetdUnsolicitedEventListener;

import androidx.annotation.NonNull;

/**
 * Base {@link INetdUnsolicitedEventListener} that provides no-op implementations which can be
 * overridden.
 */
public class BaseNetdUnsolicitedEventListener extends INetdUnsolicitedEventListener.Stub {

    @Override
    public void onInterfaceClassActivityChanged(boolean isActive, int timerLabel, long timestampNs,
            int uid) { }

    @Override
    public void onQuotaLimitReached(@NonNull String alertName, @NonNull String ifName) { }

    @Override
    public void onInterfaceDnsServerInfo(@NonNull String ifName, long lifetimeS,
            @NonNull String[] servers) { }

    @Override
    public void onInterfaceAddressUpdated(@NonNull String addr, String ifName, int flags,
            int scope) { }

    @Override
    public void onInterfaceAddressRemoved(@NonNull String addr, @NonNull String ifName, int flags,
            int scope) { }

    @Override
    public void onInterfaceAdded(@NonNull String ifName) { }

    @Override
    public void onInterfaceRemoved(@NonNull String ifName) { }

    @Override
    public void onInterfaceChanged(@NonNull String ifName, boolean up) { }

    @Override
    public void onInterfaceLinkStateChanged(@NonNull String ifName, boolean up) { }

    @Override
    public void onRouteChanged(boolean updated, @NonNull String route, @NonNull String gateway,
            @NonNull String ifName) { }

    @Override
    public void onStrictCleartextDetected(int uid, @NonNull String hex) { }

    @Override
    public int getInterfaceVersion() {
        return INetdUnsolicitedEventListener.VERSION;
    }

    @Override
    public String getInterfaceHash() {
        return INetdUnsolicitedEventListener.HASH;
    }
}
