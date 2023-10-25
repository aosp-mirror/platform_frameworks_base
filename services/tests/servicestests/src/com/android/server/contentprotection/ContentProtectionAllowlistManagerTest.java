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

package com.android.server.contentprotection;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Test for {@link ContentProtectionAllowlistManager}.
 *
 * <p>Run with: {@code atest FrameworksServicesTests:
 * com.android.server.contentprotection.ContentProtectionAllowlistManagerTest}
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class ContentProtectionAllowlistManagerTest {

    private static final String PACKAGE_NAME = "com.test.package.name";

    @Rule public final MockitoRule mMockitoRule = MockitoJUnit.rule();

    private ContentProtectionAllowlistManager mContentProtectionAllowlistManager;

    @Before
    public void setup() {
        mContentProtectionAllowlistManager = new ContentProtectionAllowlistManager();
    }

    @Test
    public void isAllowed() {
        boolean actual = mContentProtectionAllowlistManager.isAllowed(PACKAGE_NAME);

        assertThat(actual).isFalse();
    }
}
