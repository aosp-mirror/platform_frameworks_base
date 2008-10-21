/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.webkit;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;

/**
 * A simple list of initialized plugins. This list gets
 * populated when the plugins are initialized (at
 * browser startup, at the moment).
 */
public class PluginList {
    private ArrayList<Plugin> mPlugins;

   /**
    * Public constructor. Initializes the list of plugins.
    */
    public PluginList() {
        mPlugins = new ArrayList<Plugin>();
    }

   /**
    * Returns the list of plugins as a java.util.List.
    */
    public synchronized List getList() {
        return mPlugins;
    }

   /**
    * Adds a plugin to the list.
    */
    public synchronized void addPlugin(Plugin plugin) {
        if (!mPlugins.contains(plugin)) {
            mPlugins.add(plugin);
        }
    }

   /**
    * Removes a plugin from the list.
    */
    public synchronized void removePlugin(Plugin plugin) {
        int location = mPlugins.indexOf(plugin);
        if (location != -1) {
            mPlugins.remove(location);
        }
    }

   /**
    * Clears the plugin list.
    */
    public synchronized void clear() {
        mPlugins.clear();
    }

   /**
    * Dispatches the click event to the appropriate plugin.
    */
    public synchronized void pluginClicked(Context context, int position) {
        try {
            Plugin plugin = mPlugins.get(position);
            plugin.dispatchClickEvent(context);
        } catch (IndexOutOfBoundsException e) {
            // This can happen if the list of plugins
            // gets changed while the pref menu is up.
        }
    }
}
