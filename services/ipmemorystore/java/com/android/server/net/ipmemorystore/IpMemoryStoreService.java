/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.net.ipmemorystore;

import android.annotation.NonNull;
import android.content.Context;
import android.net.IIpMemoryStore;

/**
 * Implementation for the IP memory store.
 * This component offers specialized services for network components to store and retrieve
 * knowledge about networks, and provides intelligence that groups level 2 networks together
 * into level 3 networks.
 * @hide
 */
public class IpMemoryStoreService extends IIpMemoryStore.Stub {
    final Context mContext;

    public IpMemoryStoreService(@NonNull final Context context) {
        mContext = context;
    }

    /**
     * TODO : remove this useless method.
     */
    public int version() {
        return 1;
    }
}
