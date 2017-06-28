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
import android.content.res.Resources;
import android.os.Bundle;

import com.android.systemui.plugins.annotations.ProvidesInterface;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A PluginActivity is an activity that replaces another full activity (e.g. RecentsActivity)
 * at runtime within the sysui process.
 */
@ProvidesInterface(version = PluginActivity.VERSION)
public abstract class PluginActivity extends Activity implements Plugin {

    public static final int VERSION = 1;

    public static final String ACTION_RECENTS = "com.android.systemui.action.PLUGIN_RECENTS";

    private Context mSysuiContext;
    private boolean mSettingActionBar;

    @Override
    public final void onCreate(Context sysuiContext, Context pluginContext) {
        mSysuiContext = sysuiContext;
        super.attachBaseContext(pluginContext);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Theme theme = getClass().getDeclaredAnnotation(Theme.class);
        if (theme != null && theme.value() != 0) {
            setTheme(theme.value());
        }
        mSettingActionBar = true;
        getActionBar();
        mSettingActionBar = false;
    }

    @Override
    public Resources getResources() {
        return mSettingActionBar ? mSysuiContext.getResources() : super.getResources();
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        mSysuiContext = newBase;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    public Context getSysuiContext() {
        return mSysuiContext;
    }

    public Context getPluginContext() {
        return getBaseContext();
    }

    /**
     * Since PluginActivities are declared as services instead of activities (since they
     * are plugins), they can't have a theme attached to them. Instead a PluginActivity
     * can annotate itself with @Theme to specify the resource of the style it wants
     * to be themed with.
     */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Theme {
        int value();
    }
}
