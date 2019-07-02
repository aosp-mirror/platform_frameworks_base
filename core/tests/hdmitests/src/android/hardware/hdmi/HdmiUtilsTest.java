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
/**
 * Tests for {@link HdmiUtils}.
 */
@RunWith(JUnit4.class)
@SmallTest
public class HdmiUtilsTest {
    @Test
    public void testInvalidAddress() {
        assertThat(HdmiUtils.getHdmiAddressRelativePosition(0, -1))
                .isEqualTo(HdmiUtils.HDMI_RELATIVE_POSITION_UNKNOWN);
        assertThat(HdmiUtils.getHdmiAddressRelativePosition(0xFFFF, 0xFFFF))
                .isEqualTo(HdmiUtils.HDMI_RELATIVE_POSITION_UNKNOWN);
        assertThat(HdmiUtils.getHdmiAddressRelativePosition(0xFFFFF, 0))
                .isEqualTo(HdmiUtils.HDMI_RELATIVE_POSITION_UNKNOWN);
    }

    @Test
    public void testSameAddress() {
        assertThat(HdmiUtils.getHdmiAddressRelativePosition(0x1000, 0x1000))
                .isEqualTo(HdmiUtils.HDMI_RELATIVE_POSITION_SAME);
    }

    @Test
    public void testDirectlyAbove() {
        assertThat(HdmiUtils.getHdmiAddressRelativePosition(0x1000, 0x1200))
                .isEqualTo(HdmiUtils.HDMI_RELATIVE_POSITION_DIRECTLY_ABOVE);
    }

    @Test
    public void testDirectlyAbove_rootDevice() {
        assertThat(HdmiUtils.getHdmiAddressRelativePosition(0x0000, 0x2000))
                .isEqualTo(HdmiUtils.HDMI_RELATIVE_POSITION_DIRECTLY_ABOVE);
    }

    @Test
    public void testDirectlyAbove_leafDevice() {
        assertThat(HdmiUtils.getHdmiAddressRelativePosition(0x1240, 0x1245))
                .isEqualTo(HdmiUtils.HDMI_RELATIVE_POSITION_DIRECTLY_ABOVE);
    }

    @Test
    public void testAbove() {
        assertThat(HdmiUtils.getHdmiAddressRelativePosition(0x1000, 0x1210))
                .isEqualTo(HdmiUtils.HDMI_RELATIVE_POSITION_ABOVE);
    }

    @Test
    public void testAbove_rootDevice() {
        assertThat(HdmiUtils.getHdmiAddressRelativePosition(0x0000, 0x1200))
                .isEqualTo(HdmiUtils.HDMI_RELATIVE_POSITION_ABOVE);
    }

    @Test
    public void testDirectlyBelow() {
        assertThat(HdmiUtils.getHdmiAddressRelativePosition(0x2250, 0x2200))
                .isEqualTo(HdmiUtils.HDMI_RELATIVE_POSITION_DIRECTLY_BELOW);
    }

    @Test
    public void testDirectlyBelow_rootDevice() {
        assertThat(HdmiUtils.getHdmiAddressRelativePosition(0x5000, 0x0000))
                .isEqualTo(HdmiUtils.HDMI_RELATIVE_POSITION_DIRECTLY_BELOW);
    }

    @Test
    public void testDirectlyBelow_leafDevice() {
        assertThat(HdmiUtils.getHdmiAddressRelativePosition(0x3249, 0x3240))
                .isEqualTo(HdmiUtils.HDMI_RELATIVE_POSITION_DIRECTLY_BELOW);
    }

    @Test
    public void testBelow() {
        assertThat(HdmiUtils.getHdmiAddressRelativePosition(0x5143, 0x5100))
                .isEqualTo(HdmiUtils.HDMI_RELATIVE_POSITION_BELOW);
    }

    @Test
    public void testBelow_rootDevice() {
        assertThat(HdmiUtils.getHdmiAddressRelativePosition(0x3420, 0x0000))
                .isEqualTo(HdmiUtils.HDMI_RELATIVE_POSITION_BELOW);
    }

    @Test
    public void testSibling() {
        assertThat(HdmiUtils.getHdmiAddressRelativePosition(0x4000, 0x5000))
                .isEqualTo(HdmiUtils.HDMI_RELATIVE_POSITION_SIBLING);
    }

    @Test
    public void testSibling_leafDevice() {
        assertThat(HdmiUtils.getHdmiAddressRelativePosition(0x798A, 0x798F))
                .isEqualTo(HdmiUtils.HDMI_RELATIVE_POSITION_SIBLING);
    }

    @Test
    public void testDifferentBranch() {
        assertThat(HdmiUtils.getHdmiAddressRelativePosition(0x798A, 0x7970))
                .isEqualTo(HdmiUtils.HDMI_RELATIVE_POSITION_DIFFERENT_BRANCH);
    }

    @Test
    public void isValidPysicalAddress_true() {
        assertThat(HdmiUtils.isValidPhysicalAddress(0)).isTrue();
        assertThat(HdmiUtils.isValidPhysicalAddress(0xFFFE)).isTrue();
        assertThat(HdmiUtils.isValidPhysicalAddress(0x1200)).isTrue();
    }

    @Test
    public void isValidPysicalAddress_outOfRange() {
        assertThat(HdmiUtils.isValidPhysicalAddress(-1)).isFalse();
        assertThat(HdmiUtils.isValidPhysicalAddress(0xFFFF)).isFalse();
        assertThat(HdmiUtils.isValidPhysicalAddress(0x10000)).isFalse();
    }

    @Test
    public void isValidPysicalAddress_nonTrailingZeros() {
        assertThat(HdmiUtils.isValidPhysicalAddress(0x0001)).isFalse();
        assertThat(HdmiUtils.isValidPhysicalAddress(0x0213)).isFalse();
    }
}
