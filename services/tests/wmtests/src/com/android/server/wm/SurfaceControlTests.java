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

package com.android.server.wm;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import android.os.Parcel;
import android.platform.test.annotations.Presubmit;
import android.view.SurfaceControl;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Class for testing {@link SurfaceControl}.
 *
 * Build/Install/Run:
 *  atest WmTests:SurfaceControlTests
 */
@Presubmit
@RunWith(AndroidJUnit4.class)
public class SurfaceControlTests {

    @SmallTest
    @Test
    public void testUseValidSurface() {
        SurfaceControl sc = buildTestSurface();
        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        t.setVisibility(sc, false);
        sc.release();
    }

    @SmallTest
    @Test
    public void testUseInvalidSurface() {
        SurfaceControl sc = buildTestSurface();
        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        sc.release();
        try {
            t.setVisibility(sc, false);
            fail("Expected exception from updating invalid surface");
        } catch (Exception e) {
            // Expected exception
        }
    }

    @SmallTest
    @Test
    public void testUseInvalidSurface_debugEnabled() {
        SurfaceControl sc = buildTestSurface();
        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        try {
            SurfaceControl.setDebugUsageAfterRelease(true);
            sc.release();
            try {
                t.setVisibility(sc, false);
                fail("Expected exception from updating invalid surface");
            } catch (IllegalStateException ise) {
                assertNotNull(ise.getCause());
            } catch (Exception e) {
                fail("Expected IllegalStateException with cause");
            }
        } finally {
            SurfaceControl.setDebugUsageAfterRelease(false);
        }
    }

    @SmallTest
    @Test
    public void testWriteInvalidSurface_debugEnabled() {
        SurfaceControl sc = buildTestSurface();
        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        Parcel p = Parcel.obtain();
        try {
            SurfaceControl.setDebugUsageAfterRelease(true);
            sc.release();
            try {
                sc.writeToParcel(p, 0 /* flags */);
                fail("Expected exception from writing invalid surface to parcel");
            } catch (IllegalStateException ise) {
                assertNotNull(ise.getCause());
            } catch (Exception e) {
                fail("Expected IllegalStateException with cause");
            }
        } finally {
            SurfaceControl.setDebugUsageAfterRelease(false);
            p.recycle();
        }
    }

    private SurfaceControl buildTestSurface() {
        return new SurfaceControl.Builder()
                .setContainerLayer()
                .setName("SurfaceControlTests")
                .setCallsite("SurfaceControlTests")
                .build();
    }
}
