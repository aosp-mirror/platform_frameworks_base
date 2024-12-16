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

package android.nfc;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class NfcAntennaInfoTest {
    private NfcAntennaInfo mNfcAntennaInfo;


    @Before
    public void setUp() {
        AvailableNfcAntenna availableNfcAntenna = mock(AvailableNfcAntenna.class);
        List<AvailableNfcAntenna> antennas = new ArrayList<>();
        antennas.add(availableNfcAntenna);
        mNfcAntennaInfo = new NfcAntennaInfo(1, 1, false, antennas);
    }

    @After
    public void tearDown() {
    }

    @Test
    public void testGetDeviceHeight() {
        int height = mNfcAntennaInfo.getDeviceHeight();
        assertThat(height).isEqualTo(1);
    }

    @Test
    public void testGetDeviceWidth() {
        int width = mNfcAntennaInfo.getDeviceWidth();
        assertThat(width).isEqualTo(1);
    }

    @Test
    public void testIsDeviceFoldable() {
        boolean foldable = mNfcAntennaInfo.isDeviceFoldable();
        assertThat(foldable).isFalse();
    }

    @Test
    public void testGetAvailableNfcAntennas() {
        List<AvailableNfcAntenna> antennas = mNfcAntennaInfo.getAvailableNfcAntennas();
        assertThat(antennas).isNotNull();
        assertThat(antennas.size()).isEqualTo(1);
    }

}
