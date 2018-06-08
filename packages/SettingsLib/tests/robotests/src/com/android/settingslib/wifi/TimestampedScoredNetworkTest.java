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
package com.android.settingslib.wifi;

import static com.google.common.truth.Truth.assertThat;

import android.net.NetworkKey;
import android.net.ScoredNetwork;
import android.net.WifiKey;
import android.os.Parcel;

import com.android.settingslib.SettingsLibRobolectricTestRunner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Date;

@RunWith(SettingsLibRobolectricTestRunner.class)
public class TimestampedScoredNetworkTest {
  private TimestampedScoredNetwork impl;

  private ScoredNetwork createTestScoredNetwork(String ssid) {
    return new ScoredNetwork(
        new NetworkKey(new WifiKey("\"" + ssid + "\"", "00:00:00:00:00:00")), null);
  }

  @Before
  public void setUp() {
    impl = new TimestampedScoredNetwork(createTestScoredNetwork("test"),
        0 /* updatedTimestampMillis */);
  }

  @Test
  public void testUpdate() {
    long time = new Date().getTime();
    ScoredNetwork updated = createTestScoredNetwork("updated");
    impl.update(updated, time);

    assertThat(impl.getScore()).isEqualTo(updated);
    assertThat(impl.getUpdatedTimestampMillis()).isEqualTo(time);
  }

  @Test
  public void testParcel() {
    Parcel parcel = Parcel.obtain();
    impl.writeToParcel(parcel, 0);
    parcel.setDataPosition(0);

    TimestampedScoredNetwork fromParcel = TimestampedScoredNetwork.CREATOR.createFromParcel(parcel);

    assertThat(fromParcel.getScore()).isEqualTo(impl.getScore());
    assertThat(fromParcel.getUpdatedTimestampMillis()).isEqualTo(impl.getUpdatedTimestampMillis());
  }
}
