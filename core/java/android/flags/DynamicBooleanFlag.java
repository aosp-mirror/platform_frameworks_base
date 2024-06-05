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

/**
 * A flag representing a true or false value.
 *
 * The value may be different from one read to the next.
 *
 * @hide
 */
public class DynamicBooleanFlag extends BooleanFlagBase implements DynamicFlag<Boolean> {

    private final boolean mDefault;

    /**
     * @param namespace A namespace for this flag. See {@link android.provider.DeviceConfig}.
     * @param name A name for this flag.
     * @param defaultValue The value of this flag if no other override is present.
     */
    DynamicBooleanFlag(String namespace, String name, boolean defaultValue) {
        super(namespace, name);
        mDefault = defaultValue;
    }

    @Override
    public Boolean getDefault() {
        return mDefault;
    }

    @Override
    public DynamicBooleanFlag defineMetaData(String label, String description, String categoryName) {
        super.defineMetaData(label, description, categoryName);
        return this;
    }
}
