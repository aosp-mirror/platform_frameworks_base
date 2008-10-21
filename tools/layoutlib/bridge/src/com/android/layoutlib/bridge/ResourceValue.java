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

package com.android.layoutlib.bridge;

import com.android.layoutlib.api.IResourceValue;

/**
 * Basic implementation of IResourceValue.
 */
class ResourceValue implements IResourceValue {
    private final String mType;
    private final String mName;
    private String mValue = null;
    
    ResourceValue(String name) {
        mType = null;
        mName = name;
    }

    public ResourceValue(String type, String name, String value) {
        mType = type;
        mName = name;
        mValue = value;
    }

    public String getType() {
        return mType;
    }

    public final String getName() {
        return mName;
    }
    
    public final String getValue() {
        return mValue;
    }
    
    public final void setValue(String value) {
        mValue = value;
    }
    
    public void replaceWith(ResourceValue value) {
        mValue = value.mValue;
    }

    public boolean isFramework() {
        // ResourceValue object created directly in the framework are used to describe
        // non resolvable coming from the XML. Since they will never be cached (as they can't
        // be a value pointing to a bitmap, or they'd be resolvable.), the return value deoes
        // not matter.
        return false;
    }
}
