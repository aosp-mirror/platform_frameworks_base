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

abstract class BooleanFlagBase implements Flag<Boolean> {

    private final String mNamespace;
    private final String mName;
    private String mLabel;
    private String mDescription;
    private String mCategoryName;

    /**
     * @param namespace A namespace for this flag. See {@link android.provider.DeviceConfig}.
     * @param name A name for this flag.
     */
    BooleanFlagBase(String namespace, String name) {
        mNamespace = namespace;
        mName = name;
        mLabel = name;
    }

    public abstract Boolean getDefault();

    @Override
    @NonNull
    public String getNamespace() {
        return mNamespace;
    }

    @Override
    @NonNull
    public String getName() {
        return mName;
    }

    @Override
    public BooleanFlagBase defineMetaData(String label, String description, String categoryName) {
        mLabel = label;
        mDescription = description;
        mCategoryName = categoryName;
        return this;
    }

    @Override
    @NonNull
    public String getLabel() {
        return mLabel;
    }

    @Override
    public String getDescription() {
        return mDescription;
    }

    @Override
    public String getCategoryName() {
        return mCategoryName;
    }

    @Override
    @NonNull
    public String toString() {
        return getNamespace() + "." + getName() + "[" + getDefault() + "]";
    }
}
