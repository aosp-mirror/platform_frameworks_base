/**
 * Copyright (c) 2018, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.connectivity;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.ProxyInfo;

import com.android.internal.annotations.GuardedBy;

/**
 * A class to handle proxy for ConnectivityService.
 *
 * @hide
 */
public class ProxyTracker {
    // TODO : make this private and import as much managing logic from ConnectivityService as
    // possible
    @NonNull
    public final Object mProxyLock = new Object();
    @Nullable
    @GuardedBy("mProxyLock")
    public ProxyInfo mGlobalProxy = null;
    @Nullable
    @GuardedBy("mProxyLock")
    public volatile ProxyInfo mDefaultProxy = null;
    @GuardedBy("mProxyLock")
    public boolean mDefaultProxyDisabled = false;
}
