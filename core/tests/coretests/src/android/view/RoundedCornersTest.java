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

import static android.view.RoundedCorner.POSITION_BOTTOM_LEFT;
import static android.view.RoundedCorner.POSITION_BOTTOM_RIGHT;
import static android.view.RoundedCorner.POSITION_TOP_LEFT;
import static android.view.RoundedCorner.POSITION_TOP_RIGHT;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.assertThat;

import android.platform.test.annotations.Presubmit;
import android.util.Pair;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class RoundedCornersTest {

    final RoundedCorners mRoundedCorners = new RoundedCorners(
            new RoundedCorner(POSITION_TOP_LEFT, 10, 10, 10),
            new RoundedCorner(POSITION_TOP_RIGHT, 10, 190, 10),
            new RoundedCorner(POSITION_BOTTOM_RIGHT, 20, 180, 380),
            new RoundedCorner(POSITION_BOTTOM_LEFT, 20, 20, 380));

    @Test
    public void testGetRoundedCorner() {
        final Pair<Integer, Integer> radius = new Pair<>(10, 20);
        RoundedCorners roundedCorners = RoundedCorners.fromRadii(radius, 200, 400);

        assertThat(roundedCorners.getRoundedCorner(POSITION_TOP_LEFT),
                equalTo(new RoundedCorner(POSITION_TOP_LEFT, 10, 10, 10)));
        assertThat(roundedCorners.getRoundedCorner(POSITION_TOP_RIGHT),
                equalTo(new RoundedCorner(POSITION_TOP_RIGHT, 10, 190, 10)));
        assertThat(roundedCorners.getRoundedCorner(POSITION_BOTTOM_RIGHT),
                equalTo(new RoundedCorner(POSITION_BOTTOM_RIGHT, 20, 180, 380)));
        assertThat(roundedCorners.getRoundedCorner(POSITION_BOTTOM_LEFT),
                equalTo(new RoundedCorner(POSITION_BOTTOM_LEFT, 20, 20, 380)));
    }

    @Test
    public void testGetRoundedCorner_noRoundedCorners() {
        RoundedCorners roundedCorners = RoundedCorners.NO_ROUNDED_CORNERS;

        assertThat(roundedCorners.getRoundedCorner(POSITION_TOP_LEFT), nullValue());
        assertThat(roundedCorners.getRoundedCorner(POSITION_TOP_RIGHT), nullValue());
        assertThat(roundedCorners.getRoundedCorner(POSITION_BOTTOM_RIGHT), nullValue());
        assertThat(roundedCorners.getRoundedCorner(POSITION_BOTTOM_LEFT), nullValue());
    }

    @Test
    public void testHashCode() {
        assertThat(mRoundedCorners.hashCode(),
                equalTo(RoundedCorners.fromRadii(new Pair<>(10, 20), 200, 400).hashCode()));
        assertThat(mRoundedCorners.hashCode(),
                not(equalTo(RoundedCorners.fromRadii(new Pair<>(5, 10), 200, 400).hashCode())));
    }

    @Test
    public void testEquals() {
        assertThat(mRoundedCorners,
                equalTo(RoundedCorners.fromRadii(new Pair<>(10, 20), 200, 400)));

        assertThat(mRoundedCorners,
                not(equalTo(RoundedCorners.fromRadii(new Pair<>(5, 10), 200, 400))));
    }

    @Test
    public void testSetRoundedCorner() {
        RoundedCorner roundedCorner = new RoundedCorner(POSITION_BOTTOM_LEFT, 5, 6, 7);
        mRoundedCorners.setRoundedCorner(POSITION_BOTTOM_LEFT, roundedCorner);

        assertThat(mRoundedCorners.getRoundedCorner(POSITION_BOTTOM_LEFT), equalTo(roundedCorner));
    }

    @Test
    public void testSetRoundedCorner_null() {
        mRoundedCorners.setRoundedCorner(POSITION_BOTTOM_LEFT, null);

        assertThat(mRoundedCorners.mRoundedCorners[POSITION_BOTTOM_LEFT],
                equalTo(new RoundedCorner(POSITION_BOTTOM_LEFT)));
        assertThat(mRoundedCorners.getRoundedCorner(POSITION_BOTTOM_LEFT), nullValue());
    }

    @Test
    public void testInsetRoundedCorners_partialOverlap() {
        RoundedCorners roundedCorners = mRoundedCorners.inset(1, 2, 3, 4);

        assertThat(roundedCorners.mRoundedCorners[POSITION_TOP_LEFT],
                equalTo(new RoundedCorner(POSITION_TOP_LEFT, 10, 9, 8)));
        assertThat(roundedCorners.mRoundedCorners[POSITION_TOP_RIGHT],
                equalTo(new RoundedCorner(POSITION_TOP_RIGHT, 10, 189, 8)));
        assertThat(roundedCorners.mRoundedCorners[POSITION_BOTTOM_RIGHT],
                equalTo(new RoundedCorner(POSITION_BOTTOM_RIGHT, 20, 179, 378)));
        assertThat(roundedCorners.mRoundedCorners[POSITION_BOTTOM_LEFT],
                equalTo(new RoundedCorner(POSITION_BOTTOM_LEFT, 20, 19, 378)));
    }

    @Test
    public void testInsetRoundedCorners_noOverlap() {
        RoundedCorners roundedCorners = mRoundedCorners.inset(20, 20, 20, 20);

        assertThat(roundedCorners.mRoundedCorners[POSITION_TOP_LEFT].isEmpty(), is(true));
        assertThat(roundedCorners.mRoundedCorners[POSITION_TOP_RIGHT].isEmpty(), is(true));
        assertThat(roundedCorners.mRoundedCorners[POSITION_BOTTOM_RIGHT].isEmpty(), is(true));
        assertThat(roundedCorners.mRoundedCorners[POSITION_BOTTOM_LEFT].isEmpty(), is(true));
    }

    @Test
    public void testRotateRoundedCorners_90() {
        final Pair<Integer, Integer> radius = new Pair<>(10, 20);
        RoundedCorners roundedCorners = RoundedCorners.fromRadii(radius, 200, 400)
                .rotate(ROTATION_90, 200, 400);

        assertThat(roundedCorners.getRoundedCorner(POSITION_TOP_LEFT),
                equalTo(new RoundedCorner(POSITION_TOP_LEFT, 10, 10, 10)));
        assertThat(roundedCorners.getRoundedCorner(POSITION_TOP_RIGHT),
                equalTo(new RoundedCorner(POSITION_TOP_RIGHT, 20, 380, 20)));
        assertThat(roundedCorners.getRoundedCorner(POSITION_BOTTOM_RIGHT),
                equalTo(new RoundedCorner(POSITION_BOTTOM_RIGHT, 20, 380, 180)));
        assertThat(roundedCorners.getRoundedCorner(POSITION_BOTTOM_LEFT),
                equalTo(new RoundedCorner(POSITION_BOTTOM_LEFT, 10, 10, 190)));
    }

    @Test
    public void testRotateRoundedCorners_270() {
        final Pair<Integer, Integer> radius = new Pair<>(10, 20);
        RoundedCorners roundedCorners = RoundedCorners.fromRadii(radius, 200, 400)
                .rotate(ROTATION_270, 200, 400);

        assertThat(roundedCorners.getRoundedCorner(POSITION_TOP_LEFT),
                equalTo(new RoundedCorner(POSITION_TOP_LEFT, 20, 20, 20)));
        assertThat(roundedCorners.getRoundedCorner(POSITION_TOP_RIGHT),
                equalTo(new RoundedCorner(POSITION_TOP_RIGHT, 10, 390, 10)));
        assertThat(roundedCorners.getRoundedCorner(POSITION_BOTTOM_RIGHT),
                equalTo(new RoundedCorner(POSITION_BOTTOM_RIGHT, 10, 390, 190)));
        assertThat(roundedCorners.getRoundedCorner(POSITION_BOTTOM_LEFT),
                equalTo(new RoundedCorner(POSITION_BOTTOM_LEFT, 20, 20, 180)));
    }

    @Test
    public void testFromRadius_cache() {
        final Pair<Integer, Integer> radius = new Pair<>(10, 20);
        RoundedCorners cached = RoundedCorners.fromRadii(radius, 200, 400);

        assertThat(RoundedCorners.fromRadii(radius, 200, 400), sameInstance(cached));
    }

    @Test
    public void testFromRadius_wontCacheIfRadiusChanged() {
        final Pair<Integer, Integer> radius = new Pair<>(10, 20);
        RoundedCorners cached = RoundedCorners.fromRadii(radius, 200, 400);

        assertThat(RoundedCorners.fromRadii(new Pair<>(5, 10), 200, 400),
                not(sameInstance(cached)));
    }

    @Test
    public void testFromRadius_wontCacheIfDisplayWidthChanged() {
        final Pair<Integer, Integer> radius = new Pair<>(10, 20);
        RoundedCorners cached = RoundedCorners.fromRadii(radius, 200, 400);

        assertThat(RoundedCorners.fromRadii(radius, 100, 400),
                not(sameInstance(cached)));
    }

    @Test
    public void testFromRadius_wontCacheIfDisplayHeightChanged() {
        final Pair<Integer, Integer> radius = new Pair<>(10, 20);
        RoundedCorners cached = RoundedCorners.fromRadii(radius, 200, 400);

        assertThat(RoundedCorners.fromRadii(radius, 200, 300),
                not(sameInstance(cached)));
    }
}
