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
package com.android.server.accessibility;
import android.accessibilityservice.IAccessibilityServiceClient;

import java.util.HashSet;

/**
 * Manages proxy connections.
 *
 * Currently this acts similarly to UiAutomationManager as a global manager, though ideally each
 * proxy connection will belong to a separate user state.
 *
 * TODO(241117292): Remove or cut down during simultaneous user refactoring.
 */
public class ProxyManager {
    private final Object mLock;
    private final HashSet<Integer> mDisplayIds = new HashSet<>();

    ProxyManager(Object lock) {
        mLock = lock;
    }

    /**
     * TODO: Create the proxy service connection.
     */
    public void registerProxy(IAccessibilityServiceClient client, int displayId) {
        mDisplayIds.add(displayId);
    }

    /**
     * TODO: Unregister the proxy service connection based on display id.
     */
    public boolean unregisterProxy(int displayId) {
        mDisplayIds.remove(displayId);
        return true;
    }

    /**
     * Checks if a display id is being proxy-ed.
     */
    public boolean isProxyed(int displayId) {
        return mDisplayIds.contains(displayId);
    }
}
