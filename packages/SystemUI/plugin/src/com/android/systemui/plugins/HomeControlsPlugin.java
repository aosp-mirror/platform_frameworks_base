/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.systemui.plugins;

import android.view.ViewGroup;

import com.android.systemui.plugins.annotations.ProvidesInterface;

/**
 * Test plugin for home controls
 */
@ProvidesInterface(action = HomeControlsPlugin.ACTION, version = HomeControlsPlugin.VERSION)
public interface HomeControlsPlugin extends Plugin {

    String ACTION = "com.android.systemui.action.PLUGIN_HOME_CONTROLS";
    int VERSION = 1;

    /**
      * Pass the container for the plugin to use however it wants. Ideally the plugin impl
      * will add home controls to this space.
      */
    void sendParentGroup(ViewGroup group);

    /**
     * When visible, will poll for updates.
     */
    void setVisible(boolean visible);
}
