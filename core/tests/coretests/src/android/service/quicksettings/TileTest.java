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

package android.service.quicksettings;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class TileTest {

    private static final String DEFAULT_LABEL = "DEFAULT_LABEL";
    private static final String CUSTOM_APP_LABEL = "CUSTOM_LABEL";

    @Test
    public void testGetLabel_labelSet_usesCustomLabel() {
        Tile tile = new Tile();
        tile.setDefaultLabel(DEFAULT_LABEL);
        tile.setLabel(CUSTOM_APP_LABEL);

        assertThat(tile.getLabel()).isEqualTo(CUSTOM_APP_LABEL);
    }

    @Test
    public void testGetLabel_labelNotSet_usesDefaultLabel() {
        Tile tile = new Tile();
        tile.setDefaultLabel(DEFAULT_LABEL);

        assertThat(tile.getLabel()).isEqualTo(DEFAULT_LABEL);
    }

    @Test
    public void testGetCustomLabel_labelSet() {
        Tile tile = new Tile();
        tile.setDefaultLabel(DEFAULT_LABEL);
        tile.setLabel(CUSTOM_APP_LABEL);

        assertThat(tile.getCustomLabel()).isEqualTo(CUSTOM_APP_LABEL);
    }

    @Test
    public void testGetCustomLabel_labelNotSet() {
        Tile tile = new Tile();
        tile.setDefaultLabel(DEFAULT_LABEL);

        assertThat(tile.getCustomLabel()).isNull();
    }
}
