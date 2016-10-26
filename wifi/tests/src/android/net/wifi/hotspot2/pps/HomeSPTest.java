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

import static org.junit.Assert.assertTrue;

import android.os.Parcel;
import android.test.suitebuilder.annotation.SmallTest;

import java.util.HashMap;

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

    @Test
    public void verifyParcelWithEmptyHomeSP() throws Exception {
        verifyParcel(new HomeSP());
    }

    @Test
    public void verifyParcelWithValidHomeSP() throws Exception {
        verifyParcel(createHomeSp());
    }
}
