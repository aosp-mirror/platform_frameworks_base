/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.testutils.shadow;

import static org.robolectric.Shadows.shadowOf;

import android.testing.TestableLooper;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;

@Implements(TestableLooper.class)
public class ShadowTestableLooper {
    @RealObject private TestableLooper mRealTestableLooper;
    /**
     * Process messages in the queue until no more are found.
     */
    @Implementation
    protected void processAllMessages() {
        shadowOf(mRealTestableLooper.getLooper()).idle();
    }
}
