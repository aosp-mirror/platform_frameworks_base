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
import java.util.Set;

import com.google.common.collect.ImmutableMap;

public class AppPower extends AppInfo {
    private ImmutableMap<Component, ComponentPower> mComponents;

    private double mAppPowerMah;


    private AppPower() {
    }

    /**
     * Returns the {@link ComponentPower} for the {@link Component} provided,
     * or null if this AppPower does not have that component.
     * @more
     * If the component was in the power profile for this device, there
     * will be a component for it, even if there was no power used
     * by that component. In that case, the
     * {@link ComponentPower.getUsage() ComponentPower.getUsage()}
     * method will return 0.
     */
    public ComponentPower getComponentPower(Component component) {
        return mComponents.get(component);
    }

    public Set<Component> getComponents() {
        return mComponents.keySet();
    }

    /**
     * Return the total power used by this app.
     */
    public double getAppPowerMah() {
        return mAppPowerMah;
    }

    /**
     * Builder class for {@link AppPower}
     */
    public static class Builder extends AppInfo.Builder<AppPower> {
        private HashMap<Component, ComponentPower> mComponents = new HashMap();

        public Builder() {
        }

        public AppPower build() {
            final AppPower result = new AppPower();
            init(result);
            result.mComponents = ImmutableMap.copyOf(mComponents);

            // Add up the components
            double appPowerMah = 0;
            for (final ComponentPower componentPower: mComponents.values()) {
                appPowerMah += componentPower.powerMah;
            }
            result.mAppPowerMah = appPowerMah;

            return result;
        }

        public void addComponentPower(Component component, ComponentPower componentPower) {
            mComponents.put(component, componentPower);
        }
    }
}
