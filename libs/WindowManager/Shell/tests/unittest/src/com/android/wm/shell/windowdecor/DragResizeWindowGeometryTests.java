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

package com.android.wm.shell.windowdecor;

import android.testing.AndroidTestingRunner;
import android.util.Size;

import androidx.test.filters.SmallTest;

import com.google.common.testing.EqualsTester;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link WindowDecoration}.
 *
 * Build/Install/Run:
 * atest WMShellUnitTests:DragResizeWindowGeometryTests
 */
@SmallTest
@RunWith(AndroidTestingRunner.class)
public class DragResizeWindowGeometryTests {
    private static final DragResizeWindowGeometry GEOMETRY_1 = new DragResizeWindowGeometry(50,
            new Size(500, 1000), 15, 40);
    private static final DragResizeWindowGeometry GEOMETRY_2 = new DragResizeWindowGeometry(50,
            new Size(500, 1000), 20, 40);

    /**
     * Check that both groups of objects satisfy equals/hashcode within each group, and that each
     * group is distinct from the next.
     */
    @Test
    public void testEqualsAndHash() {
        new EqualsTester()
                .addEqualityGroup(GEOMETRY_1,
                    new DragResizeWindowGeometry(50, new Size(500, 1000), 15, 40))
                .addEqualityGroup(
                    GEOMETRY_2,
                    new DragResizeWindowGeometry(50, new Size(500, 1000), 20, 40))
                .testEquals();
    }
}
