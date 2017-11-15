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

import java.util.List;

/*
 * Helper class to store single Satellite info, only used it in the unit test.
 */
public class SatelliteInfo {
  private static final int SVID_MAX_BIT_INDEX = 32;
  private static final int SVID_SHIFT_WIDTH = 8;
  private static final int CONSTELLATION_TYPE_SHIFT_WIDTH = 4;

  // Index for the bits in mSvidWithFlag
  private static final int GNSS_SV_FLAGS_HAS_EPHEMERIS_DATA_BIT_INDEX = 0;
  private static final int GNSS_SV_FLAGS_HAS_ALMANAC_DATA_BIT_INDEX = 1;
  private static final int GNSS_SV_FLAGS_USED_IN_FIX_BIT_INDEX = 2;
  private static final int GNSS_SV_FLAGS_HAS_CARRIER_FREQUENCY_BIT_INDEX = 3;
  public int mSvid;
  public int mSvidWithFlag;
  public float mCn0DbHz;
  public float mElevation;
  public float mAzimuth;
  public float mCarrierFrequency;

  /*
   * Flag fields, it stores the same information as svidWithFlag, but in different format, easy for
   * the unit test.
   */
  public int mConstellationType;
  public boolean mHasEphemris;
  public boolean mHasAlmanac;
  public boolean mUsedInFix;
  public boolean mHasCarriesFrequency;

  public SatelliteInfo(int svid, int constellationType, boolean hasEphemris, boolean hasAlmanac,
      boolean usedInFix, boolean hasCarriesFrequency, float cn0, float elevation, float azimuth,
      float carrierFrequency) {
    mSvidWithFlag =
        setRange(mSvidWithFlag, constellationType, CONSTELLATION_TYPE_SHIFT_WIDTH, SVID_SHIFT_WIDTH);
    mSvidWithFlag = setRange(mSvidWithFlag, svid, SVID_SHIFT_WIDTH, SVID_MAX_BIT_INDEX);
    mSvidWithFlag = setBit(mSvidWithFlag, hasEphemris, GNSS_SV_FLAGS_HAS_EPHEMERIS_DATA_BIT_INDEX);
    mSvidWithFlag = setBit(mSvidWithFlag, hasAlmanac, GNSS_SV_FLAGS_HAS_ALMANAC_DATA_BIT_INDEX);
    mSvidWithFlag = setBit(mSvidWithFlag, usedInFix, GNSS_SV_FLAGS_USED_IN_FIX_BIT_INDEX);
    mSvidWithFlag =
        setBit(mSvidWithFlag, hasCarriesFrequency, GNSS_SV_FLAGS_HAS_CARRIER_FREQUENCY_BIT_INDEX);
    this.mSvid = svid;
    this.mConstellationType = constellationType;
    this.mCn0DbHz = cn0;
    this.mElevation = elevation;
    this.mAzimuth = azimuth;
    this.mCarrierFrequency = carrierFrequency;
    this.mHasEphemris = hasEphemris;
    this.mHasAlmanac = hasAlmanac;
    this.mUsedInFix = usedInFix;
    this.mHasCarriesFrequency = hasCarriesFrequency;
  }

  /*
   * Gernerate svidWithFlags array from svInfos
   */
  public static int[] getSvidWithFlagsArray(List<SatelliteInfo> svInfos) {
    int[] svidWithFlags = new int[svInfos.size()];
    for (int i = 0; i< svInfos.size(); i++) {
      svidWithFlags[i] = svInfos.get(i).mSvidWithFlag;
    }
    return svidWithFlags;
  }

  /*
   * Gernerate cn0s array from svInfos
   */
  public static float[] getCn0sArray(List<SatelliteInfo> svInfos) {
    float[] cn0s = new float[svInfos.size()];
    for (int i = 0; i< svInfos.size(); i++) {
      cn0s[i] = svInfos.get(i).mCn0DbHz;
    }
    return cn0s;
  }

  /*
   * Gernerate elevations array from svInfos
   */
  public static float[] getElevationsArray(List<SatelliteInfo> svInfos) {
    float[] elevations = new float[svInfos.size()];
    for (int i = 0; i< svInfos.size(); i++) {
      elevations[i] = svInfos.get(i).mElevation;
    }
    return elevations;
  }

  /*
   * Gernerate azimuths array from svInfos
   */
  public static float[] getAzimuthsArray(List<SatelliteInfo> svInfos) {
    float[] azimuths = new float[svInfos.size()];
    for (int i = 0; i< svInfos.size(); i++) {
      azimuths[i] = svInfos.get(i).mAzimuth;
    }
    return azimuths;
  }

  /*
   * Gernerate carrierFrequency array from svInfos
   */
  public static float[] getCarrierFrequencyArray(List<SatelliteInfo> svInfos) {
    float[] carrierFrequencies = new float[svInfos.size()];
    for (int i = 0; i< svInfos.size(); i++) {
      carrierFrequencies[i] = svInfos.get(i).mCarrierFrequency;
    }
    return carrierFrequencies;
  }

  private int setBit(int targetValue, boolean value, int index) {
    if (value) {
      targetValue = targetValue | (1 << index);
    } else {
      targetValue = targetValue & ~(1 << index);
    }
    return targetValue;
  }

  /*
   * Set the bit in the range [fromIndex, toIndex), index start from the lowest bit.
   * value -> 1 1 0 1 1 0 1 0
   * index -> 7 6 5 4 3 2 1 0
   * This function will set the bit in the range to the lowest X bits of the value.
   */
  private int setRange(int targetValue, int value, int fromIndex, int toIndex) {
    int rangeLen = toIndex - fromIndex;
    int valueMask = (1 << rangeLen) -1;
    value &= valueMask;
    value = value << fromIndex;
    valueMask = valueMask << fromIndex;
    targetValue &= (~valueMask);
    targetValue |= value;
    return targetValue;
  }

}