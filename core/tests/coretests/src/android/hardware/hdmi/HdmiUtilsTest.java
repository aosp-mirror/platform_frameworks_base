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
package android.hardware.hdmi;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@SmallTest
@RunWith(JUnit4.class)
/** Tests for {@link HdmiUtils} class. */
public class HdmiUtilsTest {

    @Test
    public void pathToPort_isMe() {
        int targetPhysicalAddress = 0x1000;
        int myPhysicalAddress = 0x1000;
        assertThat(HdmiUtils.getLocalPortFromPhysicalAddress(
                targetPhysicalAddress, myPhysicalAddress)).isEqualTo(
                HdmiUtils.TARGET_SAME_PHYSICAL_ADDRESS);
    }

    @Test
    public void pathToPort_isDirectlyBelow() {
        int targetPhysicalAddress = 0x1100;
        int myPhysicalAddress = 0x1000;
        assertThat(HdmiUtils.getLocalPortFromPhysicalAddress(
            targetPhysicalAddress, myPhysicalAddress)).isEqualTo(1);
    }

    @Test
    public void pathToPort_isBelow() {
        int targetPhysicalAddress = 0x1110;
        int myPhysicalAddress = 0x1000;
        assertThat(HdmiUtils.getLocalPortFromPhysicalAddress(
            targetPhysicalAddress, myPhysicalAddress)).isEqualTo(1);
    }

    @Test
    public void pathToPort_neitherMeNorBelow() {
        int targetPhysicalAddress = 0x3000;
        int myPhysicalAddress = 0x2000;
        assertThat(HdmiUtils.getLocalPortFromPhysicalAddress(
                targetPhysicalAddress, myPhysicalAddress)).isEqualTo(
                HdmiUtils.TARGET_NOT_UNDER_LOCAL_DEVICE);

        targetPhysicalAddress = 0x2200;
        myPhysicalAddress = 0x3300;
        assertThat(HdmiUtils.getLocalPortFromPhysicalAddress(
                targetPhysicalAddress, myPhysicalAddress)).isEqualTo(
                HdmiUtils.TARGET_NOT_UNDER_LOCAL_DEVICE);

        targetPhysicalAddress = 0x2213;
        myPhysicalAddress = 0x2212;
        assertThat(HdmiUtils.getLocalPortFromPhysicalAddress(
                targetPhysicalAddress, myPhysicalAddress)).isEqualTo(
                HdmiUtils.TARGET_NOT_UNDER_LOCAL_DEVICE);

        targetPhysicalAddress = 0x2340;
        myPhysicalAddress = 0x2310;
        assertThat(HdmiUtils.getLocalPortFromPhysicalAddress(
                targetPhysicalAddress, myPhysicalAddress)).isEqualTo(
                HdmiUtils.TARGET_NOT_UNDER_LOCAL_DEVICE);
    }
}
