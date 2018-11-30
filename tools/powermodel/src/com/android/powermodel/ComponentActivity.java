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


/**
 * Encapsulates the work done by an app (including synthetic apps) that costs power.
 */
public class ComponentActivity {
    public AttributionKey attribution;

    protected ComponentActivity(AttributionKey attribution) {
        this.attribution = attribution;
    }

    // TODO: Can we refactor what goes into the activities so this function
    // doesn't need the global state?
    /**
     * Apply the power profile for this component.  Subclasses should implement this
     * to do the per-component calculatinos.  The default implementation returns null.
     * If this method returns null, then there will be no power associated for this
     * component, which, for example is true with some of the GLOBAL activities.
     */
    public ComponentPower applyProfile(ActivityReport activityReport, PowerProfile profile) {
        return null;
    }
}

