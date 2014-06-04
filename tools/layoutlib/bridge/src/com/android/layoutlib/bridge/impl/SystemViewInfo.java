/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.layoutlib.bridge.impl;

import com.android.ide.common.rendering.api.ViewInfo;
import com.android.ide.common.rendering.api.ViewType;

/**
 * ViewInfo for views added by the platform.
 */
public class SystemViewInfo extends ViewInfo {

    private ViewType mViewType;

    public SystemViewInfo(String name, Object cookie, int left, int top,
            int right, int bottom) {
        super(name, cookie, left, top, right, bottom);
    }

    public SystemViewInfo(String name, Object cookie, int left, int top,
            int right, int bottom, Object viewObject, Object layoutParamsObject) {
        super(name, cookie, left, top, right, bottom, viewObject,
                layoutParamsObject);
    }

    @Override
    public ViewType getViewType() {
        if (mViewType != null) {
            return mViewType;
        }
        return ViewType.SYSTEM_UNKNOWN;
    }

    public void setViewType(ViewType type) {
        mViewType = type;
    }
}
