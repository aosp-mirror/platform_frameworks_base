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

package com.android.systemui.communal;

import static com.google.common.truth.Truth.assertThat;

import android.test.suitebuilder.annotation.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.communal.CommunalHostViewPositionAlgorithm.Result;

import org.junit.Test;


@SmallTest
public class CommunalHostViewPositionAlgorithmTest extends SysuiTestCase {
    @Test
    public void testOutput() {
        final float expansion = 0.25f;
        final int height = 120;

        final CommunalHostViewPositionAlgorithm algorithm = new CommunalHostViewPositionAlgorithm();
        algorithm.setup(expansion, height);
        final Result result = new Result();
        algorithm.run(result);

        // Verify the communal view is shifted offscreen vertically by the correct amount.
        assertThat((1 - expansion) * -height).isEqualTo(result.communalY);
    }
}
