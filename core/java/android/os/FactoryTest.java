/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.os;

/**
 * Provides support for in-place factory test functions.
 *
 * This class provides a few properties that alter the normal operation of the system
 * during factory testing.
 *
 * {@hide}
 */
public final class FactoryTest {
    public static final int FACTORY_TEST_OFF = 0;
    public static final int FACTORY_TEST_LOW_LEVEL = 1;
    public static final int FACTORY_TEST_HIGH_LEVEL = 2;

    /**
     * Gets the current factory test mode.
     *
     * @return One of: {@link #FACTORY_TEST_OFF}, {@link #FACTORY_TEST_LOW_LEVEL},
     * or {@link #FACTORY_TEST_HIGH_LEVEL}.
     */
    public static int getMode() {
        return SystemProperties.getInt("ro.factorytest", FACTORY_TEST_OFF);
    }

    /**
     * When true, long-press on power should immediately cause the device to
     * shut down, without prompting the user.
     */
    public static boolean isLongPressOnPowerOffEnabled() {
        return SystemProperties.getInt("factory.long_press_power_off", 0) != 0;
    }
}
