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

package com.android.settingslib;

import static com.google.common.truth.Truth.assertThat;

import com.android.settingslib.mobile.TelephonyIcons;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class MobileNetworkTypeIconsTest {

    @Test
    public void getNetworkTypeIcon_hPlus_returnsHPlus() {
        MobileNetworkTypeIcon icon =
                MobileNetworkTypeIcons.getNetworkTypeIcon(TelephonyIcons.H_PLUS);

        assertThat(icon.getName()).isEqualTo(TelephonyIcons.H_PLUS.name);
        assertThat(icon.getIconResId()).isEqualTo(TelephonyIcons.ICON_H_PLUS);
    }

    @Test
    public void getNetworkTypeIcon_fourG_returnsFourG() {
        MobileNetworkTypeIcon icon =
                MobileNetworkTypeIcons.getNetworkTypeIcon(TelephonyIcons.FOUR_G);

        assertThat(icon.getName()).isEqualTo(TelephonyIcons.FOUR_G.name);
        assertThat(icon.getIconResId()).isEqualTo(TelephonyIcons.ICON_4G);
    }

    @Test
    public void getNetworkTypeIcon_unknown_returnsUnknown() {
        SignalIcon.MobileIconGroup unknownGroup = new SignalIcon.MobileIconGroup(
                "testUnknownNameHere", /* dataContentDesc= */ 45, /* dataType= */ 6);

        MobileNetworkTypeIcon icon = MobileNetworkTypeIcons.getNetworkTypeIcon(unknownGroup);

        assertThat(icon.getName()).isEqualTo("testUnknownNameHere");
        assertThat(icon.getIconResId()).isEqualTo(6);
        assertThat(icon.getContentDescriptionResId()).isEqualTo(45);
    }
}
