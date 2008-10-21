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
import com.android.layoutlib.api.IStyleResourceValue;

import java.util.HashMap;

class StyleResourceValue extends ResourceValue implements IStyleResourceValue {

    private String mParentStyle = null;
    private HashMap<String, IResourceValue> mItems = new HashMap<String, IResourceValue>();

    StyleResourceValue(String name) {
        super(name);
    }

    StyleResourceValue(String name, String parentStyle) {
        super(name);
        mParentStyle = parentStyle;
    }

    public String getParentStyle() {
        return mParentStyle;
    }
    
    public IResourceValue findItem(String name) {
        return mItems.get(name);
    }
    
    public void addItem(IResourceValue value) {
        mItems.put(value.getName(), value);
    }
    
    @Override
    public void replaceWith(ResourceValue value) {
        super.replaceWith(value);
        
        if (value instanceof StyleResourceValue) {
            mItems.clear();
            mItems.putAll(((StyleResourceValue)value).mItems);
        }
    }

}
