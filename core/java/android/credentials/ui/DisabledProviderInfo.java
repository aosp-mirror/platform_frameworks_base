/*
 * Copyright 2022 The Android Open Source Project
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

package android.credentials.ui;

import android.annotation.NonNull;

import com.android.internal.util.Preconditions;

/**
 * Information pertaining to a specific provider that is disabled from the user settings.
 *
 * Currently, disabled provider data is only propagated in the create-credential flow.
 *
 * @hide
 */
public final class DisabledProviderInfo {

    @NonNull
    private final String mProviderName;

    /**
     * Constructs a {@link DisabledProviderInfo}.
     *
     * @throws IllegalArgumentException if {@code providerName} is empty
     */
    public DisabledProviderInfo(
            @NonNull String providerName) {
        mProviderName = Preconditions.checkStringNotEmpty(providerName);
    }

    /** Returns the fully-qualified provider (component or package) name. */
    @NonNull
    public String getProviderName() {
        return mProviderName;
    }
}
