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
 * The hardware component that uses power on a device.
 * <p>
 * This base class contains the total power used by each Component in an app.
 * Subclasses may add more detail, which is a drill-down, but is not to be
 * <i>added</i> to {@link #powerMah}.
 */
public abstract class ComponentPower<ACTIVITY extends ComponentActivity> {
    /**
     * The app associated with this ComponentPower.
     */
    public AttributionKey attribution;

    /**
     * The app activity that resulted in the power usage for this component.
     */
    public ACTIVITY activity;

    /**
     * The total power used by this component in this app.
     */
    public double powerMah;
}
