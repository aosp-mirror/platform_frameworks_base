/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.layoutlib.api;

/**
 * Represents an Android Resources that has a density info attached to it.
 */
public interface IDensityBasedResourceValue extends IResourceValue {
    public static enum Density {
        HIGH(240),
        MEDIUM(160),
        LOW(120),
        NODPI(0);

        private final int mValue;

        Density(int value) {
            mValue = value;
        }

        public int getValue() {
            return mValue;
        }
    }

    /**
     * Returns the density associated to the resource.
     */
    Density getDensity();
}
