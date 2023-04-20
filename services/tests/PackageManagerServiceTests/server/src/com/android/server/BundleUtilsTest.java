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

package com.android.server.pm;

import static com.android.server.devicepolicy.DpmTestUtils.assertRestrictions;
import static com.android.server.devicepolicy.DpmTestUtils.newRestrictions;

import static com.google.common.truth.Truth.assertThat;

import android.os.Bundle;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.BundleUtils;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Build/Install/Run:
 * atest com.android.server.pm.BundleUtilsTest
 */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class BundleUtilsTest {

    @Test
    public void testIsEmpty() {
        assertThat(BundleUtils.isEmpty(null)).isTrue();
        assertThat(BundleUtils.isEmpty(new Bundle())).isTrue();
        assertThat(BundleUtils.isEmpty(newRestrictions("a"))).isFalse();
    }

    @Test
    public void testClone() {
        Bundle in = new Bundle();
        Bundle out = BundleUtils.clone(in);
        assertThat(in).isNotSameInstanceAs(out);
        assertRestrictions(out, new Bundle());

        out = BundleUtils.clone(null);
        assertThat(out).isNotNull();
        out.putBoolean("a", true); // Should not be Bundle.EMPTY.
    }
}
