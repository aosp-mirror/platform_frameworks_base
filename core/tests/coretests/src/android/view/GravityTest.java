/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.view;

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

public class GravityTest extends AndroidTestCase {

    @SmallTest
    public void testGetAbsoluteGravity() throws Exception {
        assertOneGravity(Gravity.LEFT, Gravity.LEFT, false);
        assertOneGravity(Gravity.LEFT, Gravity.LEFT, true);

        assertOneGravity(Gravity.RIGHT, Gravity.RIGHT, false);
        assertOneGravity(Gravity.RIGHT, Gravity.RIGHT, true);

        assertOneGravity(Gravity.TOP, Gravity.TOP, false);
        assertOneGravity(Gravity.TOP, Gravity.TOP, true);

        assertOneGravity(Gravity.BOTTOM, Gravity.BOTTOM, false);
        assertOneGravity(Gravity.BOTTOM, Gravity.BOTTOM, true);

        assertOneGravity(Gravity.CENTER_VERTICAL, Gravity.CENTER_VERTICAL, false);
        assertOneGravity(Gravity.CENTER_VERTICAL, Gravity.CENTER_VERTICAL, true);

        assertOneGravity(Gravity.CENTER_HORIZONTAL, Gravity.CENTER_HORIZONTAL, false);
        assertOneGravity(Gravity.CENTER_HORIZONTAL, Gravity.CENTER_HORIZONTAL, true);

        assertOneGravity(Gravity.CENTER, Gravity.CENTER, false);
        assertOneGravity(Gravity.CENTER, Gravity.CENTER, true);

        assertOneGravity(Gravity.FILL_VERTICAL, Gravity.FILL_VERTICAL, false);
        assertOneGravity(Gravity.FILL_VERTICAL, Gravity.FILL_VERTICAL, true);

        assertOneGravity(Gravity.FILL_HORIZONTAL, Gravity.FILL_HORIZONTAL, false);
        assertOneGravity(Gravity.FILL_HORIZONTAL, Gravity.FILL_HORIZONTAL, true);

        assertOneGravity(Gravity.FILL, Gravity.FILL, false);
        assertOneGravity(Gravity.FILL, Gravity.FILL, true);

        assertOneGravity(Gravity.CLIP_HORIZONTAL, Gravity.CLIP_HORIZONTAL, false);
        assertOneGravity(Gravity.CLIP_HORIZONTAL, Gravity.CLIP_HORIZONTAL, true);

        assertOneGravity(Gravity.CLIP_VERTICAL, Gravity.CLIP_VERTICAL, false);
        assertOneGravity(Gravity.CLIP_VERTICAL, Gravity.CLIP_VERTICAL, true);

        assertOneGravity(Gravity.LEFT, Gravity.START, false);
        assertOneGravity(Gravity.RIGHT, Gravity.START, true);

        assertOneGravity(Gravity.RIGHT, Gravity.END, false);
        assertOneGravity(Gravity.LEFT, Gravity.END, true);
    }

    private void assertOneGravity(int expected, int initial, boolean isRtl) {
        final int layoutDirection = isRtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR;

        assertEquals(expected, Gravity.getAbsoluteGravity(initial, layoutDirection));
    }
}
