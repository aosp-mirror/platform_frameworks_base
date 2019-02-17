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
package com.android.keyguard.clock;

import android.util.ArrayMap;
import android.view.LayoutInflater;

import com.android.systemui.plugins.ClockPlugin;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Supplier that only gets an instance when a settings value matches expected value.
 */
public class DefaultClockSupplier implements Supplier<ClockPlugin> {

    private final SettingsWrapper mSettingsWrapper;
    /**
     * Map from expected value stored in settings to supplier of custom clock face.
     */
    private final Map<String, Supplier<ClockPlugin>> mClocks = new ArrayMap<>();
    /**
     * When docked, the DOCKED_CLOCK_FACE setting will be checked for the custom clock face
     * to show.
     */
    private boolean mIsDocked;

    /**
     * Constructs a supplier that changes secure setting key against value.
     *
     * @param settingsWrapper Wrapper around settings used to look up the custom clock face.
     * @param layoutInflater Provided to clocks as dependency to inflate clock views.
     */
    public DefaultClockSupplier(SettingsWrapper settingsWrapper, LayoutInflater layoutInflater) {
        mSettingsWrapper = settingsWrapper;

        mClocks.put(BubbleClockController.class.getName(),
                () -> BubbleClockController.build(layoutInflater));
        mClocks.put(StretchAnalogClockController.class.getName(),
                () -> StretchAnalogClockController.build(layoutInflater));
        mClocks.put(TypeClockController.class.getName(),
                () -> TypeClockController.build(layoutInflater));
    }

    /**
     * Sets the dock state.
     *
     * @param isDocked True when docked, false otherwise.
     */
    public void setDocked(boolean isDocked) {
        mIsDocked = isDocked;
    }

    boolean isDocked() {
        return mIsDocked;
    }

    /**
     * Get the custom clock face based on values in settings.
     *
     * @return Custom clock face, null if the settings value doesn't match a custom clock.
     */
    @Override
    public ClockPlugin get() {
        ClockPlugin plugin = null;
        if (mIsDocked) {
            final String name = mSettingsWrapper.getDockedClockFace();
            if (name != null) {
                Supplier<ClockPlugin> supplier = mClocks.get(name);
                if (supplier != null) {
                    plugin = supplier.get();
                    if (plugin != null) {
                        return plugin;
                    }
                }
            }
        }
        final String name = mSettingsWrapper.getLockScreenCustomClockFace();
        if (name != null) {
            Supplier<ClockPlugin> supplier = mClocks.get(name);
            if (supplier != null) {
                plugin = supplier.get();
            }
        }
        return plugin;
    }
}
