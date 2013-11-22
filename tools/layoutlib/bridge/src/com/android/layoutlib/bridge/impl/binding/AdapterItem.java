/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.layoutlib.bridge.impl.binding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.android.ide.common.rendering.api.DataBindingItem;

/**
 * This is the items provided by the adapter. They are dynamically generated.
 */
final class AdapterItem {
    private final DataBindingItem mItem;
    private final int mType;
    private final int mFullPosition;
    private final int mPositionPerType;
    private List<AdapterItem> mChildren;

    protected AdapterItem(DataBindingItem item, int type, int fullPosition,
            int positionPerType) {
        mItem = item;
        mType = type;
        mFullPosition = fullPosition;
        mPositionPerType = positionPerType;
    }

    void addChild(AdapterItem child) {
        if (mChildren == null) {
            mChildren = new ArrayList<AdapterItem>();
        }

        mChildren.add(child);
    }

    List<AdapterItem> getChildren() {
        if (mChildren != null) {
            return mChildren;
        }

        return Collections.emptyList();
    }

    int getType() {
        return mType;
    }

    int getFullPosition() {
        return mFullPosition;
    }

    int getPositionPerType() {
        return mPositionPerType;
    }

    DataBindingItem getDataBindingItem() {
        return mItem;
    }
}
