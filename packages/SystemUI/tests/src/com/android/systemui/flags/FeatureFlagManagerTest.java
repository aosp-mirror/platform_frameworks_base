/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.flags;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.mockito.MockitoAnnotations;

@SmallTest
public class FeatureFlagManagerTest extends SysuiTestCase {
    FeatureFlagManager mFeatureFlagManager;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        mFeatureFlagManager = new FeatureFlagManager();
    }

    @Test
    public void testIsEnabled() {
        mFeatureFlagManager.setEnabled(1, true);
        // Again, nothing changes.
        assertThat(mFeatureFlagManager.isEnabled(1, false)).isFalse();
    }
}
