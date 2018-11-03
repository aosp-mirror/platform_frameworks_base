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

package com.android.server.wm;

import static org.junit.Assert.assertEquals;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Test;

/**
 * Tests for the {@link RootWindowContainer} class.
 *
 * Build/Install/Run:
 *  atest FrameworksServicesTests:RootWindowContainerTests
 */
@SmallTest
@Presubmit
public class RootWindowContainerTests extends WindowTestsBase {
    @Test
    public void testSetDisplayOverrideConfigurationIfNeeded() {
        synchronized (mWm.mGlobalLock) {
            // Add first stack we expect to be updated with configuration change.
            final TaskStack stack = createTaskStackOnDisplay(mDisplayContent);
            stack.getOverrideConfiguration().windowConfiguration.setBounds(new Rect(0, 0, 5, 5));

            // Add second task that will be set for deferred removal that should not be returned
            // with the configuration change.
            final TaskStack deferredDeletedStack = createTaskStackOnDisplay(mDisplayContent);
            deferredDeletedStack.getOverrideConfiguration().windowConfiguration.setBounds(
                    new Rect(0, 0, 5, 5));
            deferredDeletedStack.mDeferRemoval = true;

            final Configuration override = new Configuration(
                    mDisplayContent.getOverrideConfiguration());
            override.windowConfiguration.setBounds(new Rect(0, 0, 10, 10));

            // Set display override.
            final int[] results = mWm.mRoot.setDisplayOverrideConfigurationIfNeeded(override,
                    mDisplayContent.getDisplayId());

            // Ensure only first stack is returned.
            assertEquals(1, results.length);
            assertEquals(stack.mStackId, results[0]);
        }
    }
}
