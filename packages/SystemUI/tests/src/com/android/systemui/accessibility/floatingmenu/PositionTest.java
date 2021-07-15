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

package com.android.systemui.accessibility.floatingmenu;

import static com.google.common.truth.Truth.assertThat;

import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link Position}. */
@SmallTest
@RunWith(AndroidTestingRunner.class)
public class PositionTest extends SysuiTestCase {

    @Test
    public void fromString_correctFormat_expectedValues() {
        final float expectedX = 0.0f;
        final float expectedY = 0.7f;
        final String correctStringFormat = expectedX + ", " + expectedY;

        final Position position = Position.fromString(correctStringFormat);

        assertThat(position.getPercentageX()).isEqualTo(expectedX);
        assertThat(position.getPercentageY()).isEqualTo(expectedY);
    }

    @Test(expected = IllegalArgumentException.class)
    public void fromString_incorrectFormat_throwsException() {
        final String incorrectStringFormat = "0.0: 1.0";

        // expect to throw IllegalArgumentException for the incorrect separator ":"
        Position.fromString(incorrectStringFormat);
    }

    @Test
    public void constructor() {
        final float expectedX = 0.5f;
        final float expectedY = 0.9f;

        final Position position = new Position(expectedX, expectedY);

        assertThat(position.getPercentageX()).isEqualTo(expectedX);
        assertThat(position.getPercentageY()).isEqualTo(expectedY);
    }
}
