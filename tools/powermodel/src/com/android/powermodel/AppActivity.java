/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.powermodel;

import java.util.HashMap;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

public class AppActivity extends AppInfo {

    private ImmutableMap<Component, ComponentActivity> mComponents;
    // TODO: power rails
    // private ImmutableMap<Component, PowerRailActivity> mRails;

    private AppActivity() {
    }

    /**
     * Returns the {@link ComponentActivity} for the {@link Component} provided,
     * or null if this AppActivity does not have that component.
     * @more
     * If there is no ComponentActivity for a particular Component, then
     * there was no usage associated with that app for the app in question.
     */
    public ComponentActivity getComponentActivity(Component component) {
        return mComponents.get(component);
    }

    public ImmutableSet<Component> getComponents() {
        return mComponents.keySet();
    }

    public ImmutableMap<Component,ComponentActivity> getComponentActivities() {
        return mComponents;
    }

    // TODO: power rails
    // public ComponentActivity getPowerRail(Component component) {
    //     return mComponents.get(component);
    // }
    //
    // public Set<Component> getPowerRails() {
    //     return mComponents.keySet();
    // }

    public static class Builder extends AppInfo.Builder<AppActivity> {
        private HashMap<Component, ComponentActivity> mComponents = new HashMap();
        // TODO power rails.
        
        public Builder() {
        }

        public AppActivity build() {
            final AppActivity result = new AppActivity();
            init(result);
            result.mComponents = ImmutableMap.copyOf(mComponents);
            return result;
        }

        public void addComponentActivity(Component component, ComponentActivity activity) {
            mComponents.put(component, activity);
        }
    }
}
