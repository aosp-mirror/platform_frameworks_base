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

package com.android.settingslib.testutils.shadow;

import static org.mockito.Mockito.mock;

import com.android.internal.jank.InteractionJankMonitor;

import org.mockito.Mockito;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Resetter;

@Implements(InteractionJankMonitor.class)
public class ShadowInteractionJankMonitor {
    public static final InteractionJankMonitor MOCK_INSTANCE = mock(InteractionJankMonitor.class);

    @Resetter
    public static void reset() {
        Mockito.reset(MOCK_INSTANCE);
    }

    @Implementation
    public static InteractionJankMonitor getInstance() {
        return MOCK_INSTANCE;
    }
}
