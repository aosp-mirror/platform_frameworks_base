/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.hardware.radio;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;

import android.os.Parcel;
import android.util.ArrayMap;

import org.junit.Test;

import java.util.Map;

public final class RadioAnnouncementTest {
    private static final ProgramSelector.Identifier FM_IDENTIFIER = new ProgramSelector.Identifier(
            ProgramSelector.IDENTIFIER_TYPE_AMFM_FREQUENCY, /* value= */ 90500);
    private static final ProgramSelector FM_PROGRAM_SELECTOR = new ProgramSelector(
            ProgramSelector.PROGRAM_TYPE_FM, FM_IDENTIFIER, /* secondaryIds= */ null,
            /* vendorIds= */ null);
    private static final int TRAFFIC_ANNOUNCEMENT_TYPE = Announcement.TYPE_TRAFFIC;
    private static final Map<String, String> VENDOR_INFO = createVendorInfo();
    private static final Announcement TEST_ANNOUNCEMENT =
            new Announcement(FM_PROGRAM_SELECTOR, TRAFFIC_ANNOUNCEMENT_TYPE, VENDOR_INFO);

    @Test
    public void constructor_withNullSelector_fails() {
        NullPointerException thrown = assertThrows(NullPointerException.class, () -> {
            new Announcement(/* selector= */ null, TRAFFIC_ANNOUNCEMENT_TYPE, VENDOR_INFO);
        });

        assertWithMessage("Exception for null program selector in announcement constructor")
                .that(thrown).hasMessageThat().contains("Program selector cannot be null");
    }

    @Test
    public void constructor_withNullVendorInfo_fails() {
        NullPointerException thrown = assertThrows(NullPointerException.class, () -> {
            new Announcement(FM_PROGRAM_SELECTOR, TRAFFIC_ANNOUNCEMENT_TYPE,
                    /* vendorInfo= */ null);
        });

        assertWithMessage("Exception for null vendor info in announcement constructor")
                .that(thrown).hasMessageThat().contains("Vendor info cannot be null");
    }

    @Test
    public void getSelector() {
        assertWithMessage("Radio announcement selector")
                .that(TEST_ANNOUNCEMENT.getSelector()).isEqualTo(FM_PROGRAM_SELECTOR);
    }

    @Test
    public void getType() {
        assertWithMessage("Radio announcement type")
                .that(TEST_ANNOUNCEMENT.getType()).isEqualTo(TRAFFIC_ANNOUNCEMENT_TYPE);
    }

    @Test
    public void getVendorInfo() {
        assertWithMessage("Radio announcement vendor info")
                .that(TEST_ANNOUNCEMENT.getVendorInfo()).isEqualTo(VENDOR_INFO);
    }

    private static Map<String, String> createVendorInfo() {
        Map<String, String> vendorInfo = new ArrayMap<>();
        vendorInfo.put("vendorKeyMock", "vendorValueMock");
        return vendorInfo;
    }

    @Test
    public void describeContents_forAnnouncement() {
        assertWithMessage("Radio announcement contents")
                .that(TEST_ANNOUNCEMENT.describeContents()).isEqualTo(0);
    }

    @Test
    public void newArray_forAnnouncementCreator() {
        int sizeExpected = 2;

        Announcement[] announcements = Announcement.CREATOR.newArray(sizeExpected);

        assertWithMessage("Announcements").that(announcements).hasLength(sizeExpected);
    }

    @Test
    public void writeToParcel_forAnnouncement() {
        Parcel parcel = Parcel.obtain();

        TEST_ANNOUNCEMENT.writeToParcel(parcel, /* flags= */ 0);
        parcel.setDataPosition(0);

        Announcement announcementFromParcel = Announcement.CREATOR.createFromParcel(parcel);
        assertWithMessage("Selector of announcement created from parcel")
                .that(announcementFromParcel.getSelector()).isEqualTo(FM_PROGRAM_SELECTOR);
        assertWithMessage("Type of announcement created from parcel")
                .that(announcementFromParcel.getType()).isEqualTo(TRAFFIC_ANNOUNCEMENT_TYPE);
        assertWithMessage("Vendor info of announcement created from parcel")
                .that(announcementFromParcel.getVendorInfo()).isEqualTo(VENDOR_INFO);
    }
}
