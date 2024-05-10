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

package android.media.tv.tuner.frontend;

import android.annotation.SystemApi;

/**
 * IPTV Capabilities.
 *
 * @hide
 */
@SystemApi
public class IptvFrontendCapabilities extends FrontendCapabilities {
    private final int mProtocolCap;

    // Used by native code
    private IptvFrontendCapabilities(int protocolCap) {
        mProtocolCap = protocolCap;
    }

    /**
     * Gets the protocols of IPTV transmission (UDP/RTP) defined in
     * {@link IptvFrontendSettings}.
     */
    public int getProtocolCapability() {
        return mProtocolCap;
    }
}
