/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.location;

import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import junit.framework.TestCase;

/**
 * Unit tests for {@link GnssStatus}.
 */
@SmallTest
public class GnssStatusTest extends TestCase {

  private static final String TAG = GnssStatusTest.class.getSimpleName();
  public void setUp() throws Exception {
    super.setUp();
  }

  /*
   * Create {@link GnssStatus} with default value, verify whether its fields are set correctly.
   *
   */
  public void testEmptyGnssStatus() throws Exception {
    Log.i(TAG, "testEmptyGnssStatus");
    List<SatelliteInfo> svInfos = new ArrayList<>();
    GnssStatus gnssStatus = createGnssStatus(svInfos);
    verifyGnssStatus(svInfos, gnssStatus);
  }

  /*
   * Create {@link GnssStatus} with only one satellite info, verify whether its fields are set
   * correctly.
   */
  public void testOneSatelliteGnssStatus() throws Exception {
    Log.i(TAG, "testOneSatelliteGnssStatus");
    List<SatelliteInfo> svInfos = new ArrayList<>();
    SatelliteInfo svInfo =
        new SatelliteInfo(100,1, true, true, true, true, 100f, 20.3f, 45.5f, 100.23f);
    svInfos.add(svInfo);
    GnssStatus gnssStatus = createGnssStatus(svInfos);
    verifyGnssStatus(svInfos, gnssStatus);
  }

  /*
   * Create {@link GnssStatus} with multiple satellite info, verify whether its fields are set
   * correctly.
   */
  public void testMultipleSatellitesGnssStatus() throws Exception {
    Log.i(TAG, "testMultipleSatellitesGnssStatus");
    List<SatelliteInfo> svInfos = new ArrayList<>();
    SatelliteInfo svInfo1 =
        new SatelliteInfo(20, 1,true, true, true, true, 10.1f, 20.3f, 45.5f, 111.23f);
    SatelliteInfo svInfo2 =
        new SatelliteInfo(50, 2, true, false, true, false, 20.2f, 21.3f, 46.5f, 222.23f);
    SatelliteInfo svInfo3 =
        new SatelliteInfo(192, 3, false, true, false, true, 30.3f, 22.3f, 47.5f, 333.23f);
    SatelliteInfo svInfo4 =
        new SatelliteInfo(250, 4, false, false, false, false, 40.4f, 23.3f, 48.5f, 444.23f);
    svInfos.add(svInfo1);
    svInfos.add(svInfo2);
    svInfos.add(svInfo3);
    svInfos.add(svInfo4);
    GnssStatus gnssStatus = createGnssStatus(svInfos);
    verifyGnssStatus(svInfos, gnssStatus);
  }

  private void verifyGnssStatus(List<SatelliteInfo> svInfos, GnssStatus gnssStatus) {
    Log.i(TAG, String.format("Verifing {0} satellites info.",svInfos.size()));
    assertEquals(TAG + "::SatelliteCount", svInfos.size(),
        gnssStatus.getSatelliteCount());
    for (int i = 0; i< svInfos.size(); i++) {
      SatelliteInfo svInfo = svInfos.get(i);
      assertEquals(TAG + "::Svid", svInfo.mSvid, gnssStatus.getSvid(i));
      assertEquals(TAG + "::ConstellationType", svInfo.mConstellationType,
          gnssStatus.getConstellationType(i));
      assertEquals(TAG + "::Cn0DbHz", svInfo.mCn0DbHz, gnssStatus.getCn0DbHz(i));
      assertEquals(TAG + "::Elevation", svInfo.mElevation,
          gnssStatus.getElevationDegrees(i));
      assertEquals(TAG + "::Azimuth", svInfo.mAzimuth, gnssStatus.getAzimuthDegrees(i));
      assertEquals(TAG + "::CarrierFrequencyHz", svInfo.mCarrierFrequency,
          gnssStatus.getCarrierFrequencyHz(i));
      assertEquals(TAG + "::hasEphemerisData", svInfo.mHasEphemris,
          gnssStatus.hasEphemerisData(i));
      assertEquals(TAG + "::HasAlmanacData", svInfo.mHasAlmanac,
          gnssStatus.hasAlmanacData(i));
      assertEquals(TAG + "::UsedInFix", svInfo.mUsedInFix, gnssStatus.usedInFix(i));
      assertEquals(TAG + "::HasCarrierFrequencyHz", svInfo.mHasCarriesFrequency,
          gnssStatus.hasCarrierFrequencyHz(i));
    }
  }

  private static GnssStatus createGnssStatus(List<SatelliteInfo> svInfos) throws Exception {
    Class<?> intClass = Integer.TYPE;
    Class<?> floatArrayClass = Class.forName("[F");
    Class<?> intArrayClass = Class.forName("[I");
    Class[] cArg = new Class[6];
    cArg[0] = intClass;
    cArg[1] = intArrayClass;
    cArg[2] = floatArrayClass;
    cArg[3] = floatArrayClass;
    cArg[4] = floatArrayClass;
    cArg[5] = floatArrayClass;
    Constructor<GnssStatus>  ctor = GnssStatus.class.getDeclaredConstructor(cArg);
    ctor.setAccessible(true);
    return ctor.newInstance(svInfos.size(),
        SatelliteInfo.getSvidWithFlagsArray(svInfos),
        SatelliteInfo.getCn0sArray(svInfos),
        SatelliteInfo.getElevationsArray(svInfos),
        SatelliteInfo.getAzimuthsArray(svInfos),
        SatelliteInfo.getCarrierFrequencyArray(svInfos));
  }
}
