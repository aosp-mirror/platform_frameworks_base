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

package android.net.netstats.provider;

import android.annotation.NonNull;

/**
 * A wrapper class of {@link INetworkStatsProvider} that hides the binder interface from exposing
 * to outer world.
 *
 * @hide
 */
public class NetworkStatsProviderWrapper extends INetworkStatsProvider.Stub {
    @NonNull final AbstractNetworkStatsProvider mProvider;

    public NetworkStatsProviderWrapper(AbstractNetworkStatsProvider provider) {
        mProvider = provider;
    }

    @Override
    public void requestStatsUpdate(int token) {
        mProvider.requestStatsUpdate(token);
    }

    @Override
    public void setLimit(@NonNull String iface, long quotaBytes) {
        mProvider.setLimit(iface, quotaBytes);
    }

    @Override
    public void setAlert(long quotaBytes) {
        mProvider.setAlert(quotaBytes);
    }
}
