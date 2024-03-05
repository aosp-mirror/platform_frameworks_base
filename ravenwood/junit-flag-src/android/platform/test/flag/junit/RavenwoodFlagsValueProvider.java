/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.platform.test.flag.junit;

import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.IFlagsValueProvider;

/**
 * Offer to create {@link CheckFlagsRule} instances that are useful on the Ravenwood deviceless
 * testing environment.
 *
 * At the moment, default flag values are not available on Ravenwood, so the only options offered
 * here are "all-on" and "all-off" options. Tests that want to exercise specific flag states should
 * use {@link android.platform.test.flag.junit.SetFlagsRule}.
 */
public class RavenwoodFlagsValueProvider {
    /**
     * Create a {@link CheckFlagsRule} instance where flags are in an "all-on" state.
     */
    public static CheckFlagsRule createAllOnCheckFlagsRule() {
        return new CheckFlagsRule(new IFlagsValueProvider() {
            @Override
            public boolean getBoolean(String flag) {
                return true;
            }
        });
    }

    /**
     * Create a {@link CheckFlagsRule} instance where flags are in an "all-off" state.
     */
    public static CheckFlagsRule createAllOffCheckFlagsRule() {
        return new CheckFlagsRule(new IFlagsValueProvider() {
            @Override
            public boolean getBoolean(String flag) {
                return false;
            }
        });
    }
}
