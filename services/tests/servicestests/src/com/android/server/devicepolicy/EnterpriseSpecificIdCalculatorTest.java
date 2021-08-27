/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.devicepolicy;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.assertThrows;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class EnterpriseSpecificIdCalculatorTest {
    private static final String SOME_IMEI = "56134231542345";
    private static final String SOME_SERIAL_NUMBER = "XZ663CCAJA7";
    private static final String SOME_MAC_ADDRESS = "65:ca:f3:fe:9d:b1";
    private static final String NO_MEID = null;
    private static final String SOME_PACKAGE = "com.example.test.dpc";
    private static final String ANOTHER_PACKAGE = "org.example.test.another.dpc";
    private static final String SOME_ENTERPRISE_ID = "73456234";
    private static final String ANOTHER_ENTERPRISE_ID = "243441";

    private EnterpriseSpecificIdCalculator mEsidCalculator;

    @Before
    public void createDefaultEsidCalculator() {
        mEsidCalculator = new EnterpriseSpecificIdCalculator(SOME_IMEI, NO_MEID, SOME_SERIAL_NUMBER,
                SOME_MAC_ADDRESS);
    }

    @Test
    public void paddingOfIdentifiers() {
        assertThat(mEsidCalculator.getPaddedImei()).isEqualTo("  56134231542345");
        assertThat(mEsidCalculator.getPaddedMeid()).isEqualTo("                ");
        assertThat(mEsidCalculator.getPaddedSerialNumber()).isEqualTo("     XZ663CCAJA7");
    }

    @Test
    public void truncationOfLongIdentifier() {
        EnterpriseSpecificIdCalculator esidCalculator = new EnterpriseSpecificIdCalculator(
                SOME_IMEI, NO_MEID, "XZ663CCAJA7XZ663CCAJA7XZ663CCAJA7",
                SOME_MAC_ADDRESS);
        assertThat(esidCalculator.getPaddedSerialNumber()).isEqualTo("XZ663CCAJA7XZ663");
    }

    @Test
    public void paddingOfPackageName() {
        assertThat(mEsidCalculator.getPaddedProfileOwnerName(SOME_PACKAGE)).isEqualTo(
                "                                            " + SOME_PACKAGE);
    }

    @Test
    public void paddingOfEnterpriseId() {
        assertThat(mEsidCalculator.getPaddedEnterpriseId(SOME_ENTERPRISE_ID)).isEqualTo(
                "                                                        " + SOME_ENTERPRISE_ID);
    }

    @Test
    public void emptyEnterpriseIdYieldsEmptyEsid() {
        assertThrows(IllegalArgumentException.class, () ->
                mEsidCalculator.calculateEnterpriseId(SOME_PACKAGE, ""));
    }

    @Test
    public void emptyDpcPackageYieldsEmptyEsid() {
        assertThrows(IllegalArgumentException.class, () ->
                mEsidCalculator.calculateEnterpriseId("", SOME_ENTERPRISE_ID));
    }

    // On upgrade, an ESID will be calculated with an empty Enterprise ID. This is signalled
    // to the EnterpriseSpecificIdCalculator by passing in null.
    @Test
    public void nullEnterpriseIdYieldsValidEsid() {
        assertThat(mEsidCalculator.calculateEnterpriseId(SOME_PACKAGE, null)).isEqualTo(
                "C4W7-VUJT-PHSA-HMY53-CLHX-L4HW-L");
    }

    @Test
    public void knownValues() {
        assertThat(
                mEsidCalculator.calculateEnterpriseId(SOME_PACKAGE, SOME_ENTERPRISE_ID)).isEqualTo(
                "FP7B-RXQW-Q77F-7J6FC-5RXZ-UJI6-6");
        assertThat(mEsidCalculator.calculateEnterpriseId(SOME_PACKAGE,
                ANOTHER_ENTERPRISE_ID)).isEqualTo("ATAL-VPIX-GBNZ-NE3TF-TDEV-3OVO-C");
        assertThat(mEsidCalculator.calculateEnterpriseId(ANOTHER_PACKAGE,
                SOME_ENTERPRISE_ID)).isEqualTo("JHU3-6SHH-YLHC-ZGETD-PWNI-7NPQ-S");
        assertThat(mEsidCalculator.calculateEnterpriseId(ANOTHER_PACKAGE,
                ANOTHER_ENTERPRISE_ID)).isEqualTo("LEF3-QBEC-UQ6O-RIOCX-TQF6-GRLV-F");
    }
}
