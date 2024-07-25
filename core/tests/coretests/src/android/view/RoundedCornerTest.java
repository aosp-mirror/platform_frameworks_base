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

package android.view;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import android.graphics.Point;
import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class RoundedCornerTest {

    @Test
    public void testGetPosition() {
        RoundedCorner roundedCorner = new RoundedCorner(
                RoundedCorner.POSITION_BOTTOM_LEFT, 2, 3, 4);
        assertThat(roundedCorner.getPosition(), is(RoundedCorner.POSITION_BOTTOM_LEFT));
    }

    @Test
    public void testGetRadius() {
        RoundedCorner roundedCorner = new RoundedCorner(
                RoundedCorner.POSITION_BOTTOM_LEFT, 2, 3, 4);
        assertThat(roundedCorner.getRadius(), is(2));
    }

    @Test
    public void testGetCenter() {
        RoundedCorner roundedCorner = new RoundedCorner(
                RoundedCorner.POSITION_BOTTOM_LEFT, 2, 3, 4);
        assertThat(roundedCorner.getCenter(), equalTo(new Point(3, 4)));
    }

    @Test
    public void testIsEmpty() {
        RoundedCorner roundedCorner = new RoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT);
        assertThat(roundedCorner.isEmpty(), is(true));
    }

    @Test
    public void testIsEmpty_negativeCenter() {
        RoundedCorner roundedCorner =
                new RoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT, 1, -2, -3);
        assertThat(roundedCorner.isEmpty(), is(true));
    }

    @Test
    public void testEquals() {
        RoundedCorner roundedCorner = new RoundedCorner(
                RoundedCorner.POSITION_BOTTOM_LEFT, 2, 3, 4);
        RoundedCorner roundedCorner2 = new RoundedCorner(
                RoundedCorner.POSITION_BOTTOM_LEFT, 2, 3, 4);
        assertThat(roundedCorner, equalTo(roundedCorner2));
    }
}
