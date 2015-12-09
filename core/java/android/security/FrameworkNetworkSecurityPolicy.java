/**
 * Copyright (c) 2015, The Android Open Source Project
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

package android.security;

/**
 * Android framework's implementation of {@link libcore.net.NetworkSecurityPolicy}.
 *
 * @hide
 */
public class FrameworkNetworkSecurityPolicy extends libcore.net.NetworkSecurityPolicy {
    private final boolean mCleartextTrafficPermitted;

    public FrameworkNetworkSecurityPolicy(boolean cleartextTrafficPermitted) {
        mCleartextTrafficPermitted = cleartextTrafficPermitted;
    }

    @Override
    public boolean isCleartextTrafficPermitted() {
        return mCleartextTrafficPermitted;
    }

    @Override
    public boolean isCleartextTrafficPermitted(String hostname) {
        return isCleartextTrafficPermitted();
    }
}
