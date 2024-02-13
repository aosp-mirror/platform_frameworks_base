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
package com.android.ravenwood.mockito;

import static com.google.common.truth.Truth.assertThat;

import android.app.ActivityManager;
import android.platform.test.ravenwood.RavenwoodRule;

import org.junit.Rule;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

public class RavenwoodMockitoRavenwoodOnlyTest {
    @Rule public final RavenwoodRule mRavenwood = new RavenwoodRule();

    @Test
    public void testStaticMockOnRavenwood() {
        try (MockedStatic<ActivityManager> am = Mockito.mockStatic(ActivityManager.class)) {
            am.when(ActivityManager::isUserAMonkey).thenReturn(true);
            assertThat(ActivityManager.isUserAMonkey()).isEqualTo(true);
        }
    }
}
