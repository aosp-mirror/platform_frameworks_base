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

package com.android.internal.util;

import android.os.Parcel;
import android.platform.test.annotations.Presubmit;
import android.platform.test.ravenwood.RavenwoodRule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import androidx.test.filters.SmallTest;

import com.android.internal.util.Parcelling.BuiltIn.ForInstant;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.time.Instant;

/** Tests for {@link Parcelling}. */
@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class ParcellingTests {

    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    private Parcel mParcel = Parcel.obtain();

    @Test
    public void forInstant_normal() {
        testForInstant(Instant.ofEpochSecond(500L, 10));
    }

    @Test
    public void forInstant_minimum() {
        testForInstant(Instant.MIN);
    }

    @Test
    public void forInstant_maximum() {
        testForInstant(Instant.MAX);
    }

    @Test
    public void forInstant_null() {
        testForInstant(null);
    }

    private void testForInstant(Instant instant) {
        Parcelling<Instant> parcelling = new ForInstant();
        parcelling.parcel(instant, mParcel, 0);
        mParcel.setDataPosition(0);

        Instant created = parcelling.unparcel(mParcel);

        if (instant == null) {
            assertNull(created);
        } else {
            assertEquals(instant, created);
        }
    }

}
