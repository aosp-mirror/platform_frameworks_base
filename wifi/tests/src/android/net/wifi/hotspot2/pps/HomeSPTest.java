/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package android.net.wifi.hotspot2.pps;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Parcel;
import android.test.suitebuilder.annotation.SmallTest;

import org.junit.Test;

/**
 * Unit tests for {@link android.net.wifi.hotspot2.pps.HomeSP}.
 */
@SmallTest
public class HomeSPTest {
    private static HomeSP createHomeSp() {
        HomeSP homeSp = new HomeSP();
        homeSp.fqdn = "fqdn";
        homeSp.friendlyName = "friendly name";
        homeSp.roamingConsortiumOIs = new long[] {0x55, 0x66};
        return homeSp;
    }

    private static void verifyParcel(HomeSP writeHomeSp) throws Exception {
        Parcel parcel = Parcel.obtain();
        writeHomeSp.writeToParcel(parcel, 0);

        parcel.setDataPosition(0);    // Rewind data position back to the beginning for read.
        HomeSP readHomeSp = HomeSP.CREATOR.createFromParcel(parcel);
        assertTrue(readHomeSp.equals(writeHomeSp));
    }

    /**
     * Verify parcel read/write for an empty HomeSP.
     *
     * @throws Exception
     */
    @Test
    public void verifyParcelWithEmptyHomeSP() throws Exception {
        verifyParcel(new HomeSP());
    }

    /**
     * Verify parcel read/write for a valid HomeSP.
     *
     * @throws Exception
     */
    @Test
    public void verifyParcelWithValidHomeSP() throws Exception {
        verifyParcel(createHomeSp());
    }

    /**
     * Verify that a HomeSP is valid when both FQDN and Friendly Name
     * are provided.
     *
     * @throws Exception
     */
    @Test
    public void validateValidHomeSP() throws Exception {
        HomeSP homeSp = new HomeSP();
        homeSp.fqdn = "fqdn";
        homeSp.friendlyName = "friendly name";
        assertTrue(homeSp.validate());
    }

    /**
     * Verify that a HomeSP is not valid when FQDN is not provided
     *
     * @throws Exception
     */
    @Test
    public void validateHomeSpWithoutFqdn() throws Exception {
        HomeSP homeSp = new HomeSP();
        homeSp.friendlyName = "friendly name";
        assertFalse(homeSp.validate());
    }

    /**
     * Verify that a HomeSP is not valid when Friendly Name is not provided
     *
     * @throws Exception
     */
    @Test
    public void validateHomeSpWithoutFriendlyName() throws Exception {
        HomeSP homeSp = new HomeSP();
        homeSp.fqdn = "fqdn";
        assertFalse(homeSp.validate());
    }

    /**
     * Verify that a HomeSP is valid when the optional Roaming Consortium OIs are
     * provided.
     *
     * @throws Exception
     */
    @Test
    public void validateHomeSpWithRoamingConsoritums() throws Exception {
        HomeSP homeSp = new HomeSP();
        homeSp.fqdn = "fqdn";
        homeSp.friendlyName = "friendly name";
        homeSp.roamingConsortiumOIs = new long[] {0x55, 0x66};
        assertTrue(homeSp.validate());
    }

    /**
     * Verify that copy constructor works when pass in a null source.
     *
     * @throws Exception
     */
    @Test
    public void validateCopyConstructorFromNullSource() throws Exception {
        HomeSP copySp = new HomeSP(null);
        HomeSP defaultSp = new HomeSP();
        assertTrue(copySp.equals(defaultSp));
    }

    /**
     * Verify that copy constructor works when pass in a valid source.
     *
     * @throws Exception
     */
    @Test
    public void validateCopyConstructorFromValidSource() throws Exception {
        HomeSP sourceSp = new HomeSP();
        sourceSp.fqdn = "fqdn";
        sourceSp.friendlyName = "friendlyName";
        sourceSp.roamingConsortiumOIs = new long[] {0x55, 0x66};
        HomeSP copySp = new HomeSP(sourceSp);
        assertTrue(copySp.equals(sourceSp));
    }
}
