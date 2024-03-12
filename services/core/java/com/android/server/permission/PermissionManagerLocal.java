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

package com.android.server.permission;

import android.annotation.TestApi;
import com.android.internal.annotations.Keep;

/**
 * In-process API for server side permission related infrastructure.
 *
 * @hide
 */
@Keep
@TestApi
public interface PermissionManagerLocal {

    /**
     * Get whether signature permission allowlist is enforced even on debuggable builds.
     *
     * @return whether the signature permission allowlist is force enforced
     */
    @TestApi
    boolean isSignaturePermissionAllowlistForceEnforced();

    /**
     * Set whether signature permission allowlist is enforced even on debuggable builds.
     *
     * @param forceEnforced whether the signature permission allowlist is force enforced
     */
    @TestApi
    void setSignaturePermissionAllowlistForceEnforced(boolean forceEnforced);
}
