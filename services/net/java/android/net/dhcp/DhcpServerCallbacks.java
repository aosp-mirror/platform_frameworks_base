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

package android.net.dhcp;

/**
 * Convenience wrapper around IDhcpServerCallbacks.Stub that implements getInterfaceVersion().
 * @hide
 */
public abstract class DhcpServerCallbacks extends IDhcpServerCallbacks.Stub {
    // TODO: add @Override here once the API is versioned

    /**
     * Get the version of the aidl interface implemented by the callbacks.
     */
    public int getInterfaceVersion() {
        // TODO: return IDhcpServerCallbacks.VERSION;
        return 0;
    }
}
