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

import android.view.View;
import android.widget.FrameLayout;

import com.android.systemui.plugins.annotations.ProvidesInterface;

/**
 * Plugin to attach custom views under QQS.
 *
 * A parent view is provided to the plugin to which they can add Views.
 * <br>
 * The parent is a {@link FrameLayout} with same background as QS and 96dp height.
 *
 * {@see NPVPluginManager}
 * {@see status_bar_expanded_plugin_frame}
 */
@ProvidesInterface(action = NPVPlugin.ACTION, version = NPVPlugin.VERSION)
public interface NPVPlugin extends Plugin {
    String ACTION = "com.android.systemui.action.PLUGIN_NPV";
    int VERSION = 1;

    /**
     * Attach views to the parent.
     *
     * @param parent a {@link FrameLayout} to which to attach views. Preferably a root view.
     * @return a view attached to parent.
     */
    View attachToRoot(FrameLayout parent);

    /**
     * Indicate to the plugin when it is listening (QS expanded)
     * @param listening
     */
    default void setListening(boolean listening) {};
}
