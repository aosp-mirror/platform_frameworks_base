/*
 * Copyright (C) 2022 The Android Open Source Project
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

package androidx.window.extensions;

import static com.google.common.truth.Truth.assertThat;

import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test class for {@link WindowExtensionsTest}.
 *
 * Build/Install/Run:
 *  atest WMJetpackUnitTests:WindowExtensionsTest
 */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class WindowExtensionsTest {
    private WindowExtensions mExtensions;

    @Before
    public void setUp() {
        mExtensions = WindowExtensionsProvider.getWindowExtensions();
    }

    @Test
    public void testGetWindowLayoutComponent() {
        assertThat(mExtensions.getWindowLayoutComponent()).isNotNull();
    }

    @Test
    public void testGetActivityEmbeddingComponent() {
        assertThat(mExtensions.getActivityEmbeddingComponent()).isNotNull();
    }
}
