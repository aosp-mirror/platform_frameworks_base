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

package android.graphics;

import static android.graphics.Rect.copyOrNull;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class RectTest {

    @Test
    public void copyOrNull_passesThroughNull() {
        assertNull(copyOrNull(null));
    }

    @Test
    public void copyOrNull_copiesNonNull() {
        final Rect orig = new Rect(1, 2, 3, 4);
        final Rect copy = copyOrNull(orig);

        assertEquals(orig, copy);
        assertNotSame(orig, copy);
    }
}
