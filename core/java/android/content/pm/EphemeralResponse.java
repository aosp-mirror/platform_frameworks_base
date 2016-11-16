/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.content.pm;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.IntentFilter;

/**
 * Ephemeral application resolution response.
 * @hide
 */
public final class EphemeralResponse extends IntentFilter {
    /** Resolved information returned from the external ephemeral resolver */
    public final EphemeralResolveInfo resolveInfo;
    /** The resolved package. Copied from {@link #resolveInfo}. */
    public final String packageName;
    /** The resolve split. Copied from the matched filter in {@link #resolveInfo}. */
    public final String splitName;
    /** Whether or not ephemeral resolution needs the second phase */
    public final boolean needsPhase2;
    /** Opaque token to track the ephemeral application resolution */
    public final String token;

    public EphemeralResponse(@NonNull EphemeralResolveInfo resolveInfo,
            @NonNull IntentFilter orig,
            @Nullable String splitName,
            @NonNull String token,
            boolean needsPhase2) {
        super(orig);
        this.resolveInfo = resolveInfo;
        this.packageName = resolveInfo.getPackageName();
        this.splitName = splitName;
        this.token = token;
        this.needsPhase2 = needsPhase2;
    }
}