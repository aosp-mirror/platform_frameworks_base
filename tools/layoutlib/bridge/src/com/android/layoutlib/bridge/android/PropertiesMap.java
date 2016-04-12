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

package com.android.layoutlib.bridge.android;

import com.android.layoutlib.bridge.android.PropertiesMap.Property;

import java.util.HashMap;

/**
 * An alias used for the value in {@link BridgeContext#mDefaultPropMaps}
 */
public class PropertiesMap extends HashMap<String, Property> {

    public static class Property {
        public final String resource;
        public final String value;

        public Property(String resource, String value) {
            this.resource = resource;
            this.value = value;
        }
    }
}
