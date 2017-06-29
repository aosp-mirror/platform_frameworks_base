/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.plugins;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import android.util.ArrayMap;

public class PluginActivityManager {

    private final Context mContext;
    private final PluginManager mPluginManager;
    private final ArrayMap<String, String> mActionLookup = new ArrayMap<>();

    public PluginActivityManager(Context context, PluginManager pluginManager) {
        mContext = context;
        mPluginManager = pluginManager;
    }

    public void addActivityPlugin(String className, String action) {
        mActionLookup.put(className, action);
    }

    public Activity instantiate(ClassLoader cl, String className, Intent intent) {
        String action = mActionLookup.get(className);
        if (TextUtils.isEmpty(action)) return null;
        return mPluginManager.getOneShotPlugin(action, PluginActivity.class);
    }
}
