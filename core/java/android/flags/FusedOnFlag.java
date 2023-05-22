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

package android.flags;

import android.annotation.NonNull;
import android.provider.DeviceConfig;

/**
 * A flag representing a true value.
 *
 * The flag can never be changed or overridden. It is true at compile time.
 *
 * @hide
 */
public final class FusedOnFlag extends BooleanFlagBase {
    /**
     * @param namespace A namespace for this flag. See {@link DeviceConfig}.
     * @param name      A name for this flag.
     */
    FusedOnFlag(String namespace, String name) {
        super(namespace, name);
    }

    @Override
    @NonNull
    public Boolean getDefault() {
        return true;
    }

    @Override
    public FusedOnFlag defineMetaData(String label, String description, String categoryName) {
        super.defineMetaData(label, description, categoryName);
        return this;
    }
}
