/*
 * Copyright (C) 2015 The Android Open Source Project
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

import junit.framework.TestCase;

import android.test.suitebuilder.annotation.SmallTest;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Random;
import java.util.Set;

/**
 * Unit tests for {@link GpsStatus}.
 */
@SmallTest
public class GpsStatusTest extends TestCase {

    private static final int MAX_VALUE = 250;

    private final Random mRandom = new Random();

    private GpsStatus mStatus;
    private int mCount;
    private int[] mPrns;
    private float[] mCn0s;
    private float[] mElevations;
    private float[] mAzimuth;
    private int mEphemerisMask;
    private int mAlmanacMask;
    private int mUsedInFixMask;

    public void setUp() throws Exception {
        super.setUp();
        mStatus = createGpsStatus();
        generateSatellitesData(generateInt());
    }

    public void testEmptyGpsStatus() throws Exception {
        verifyIsEmpty(mStatus);
    }

    public void testGpsStatusIterator() throws Exception {
        generateSatellitesData(2);
        setSatellites(mStatus);
        Iterator<GpsSatellite> iterator = mStatus.getSatellites().iterator();
        assertTrue("hasNext(1)", iterator.hasNext());
        assertTrue("hasNext(1) does not overflow", iterator.hasNext());
        GpsSatellite satellite1 = iterator.next();
        assertNotNull("satellite", satellite1);
        assertTrue("hasNext(2)", iterator.hasNext());
        assertTrue("hasNext(2) does not overflow", iterator.hasNext());
        GpsSatellite satellite2 = iterator.next();
        assertNotNull("satellite", satellite2);
        assertFalse("hasNext() no elements", iterator.hasNext());
    }

    public void testTtff() throws Exception {
        int testTtff = generateInt();
        set(mStatus, testTtff);
        verifyTtff(mStatus, testTtff);
    }

    public void testCopyTtff() throws Exception {
        int testTtff = generateInt();
        verifyTtff(mStatus, 0);

        GpsStatus otherStatus = createGpsStatus();
        set(otherStatus, testTtff);
        verifyTtff(otherStatus, testTtff);

        set(mStatus, otherStatus);
        verifyTtff(mStatus, testTtff);
    }

    public void testSetSatellites() throws Exception {
        setSatellites(mStatus);
        verifySatellites(mStatus);
    }

    public void testCopySatellites() throws Exception {
        verifyIsEmpty(mStatus);

        GpsStatus otherStatus = createGpsStatus();
        setSatellites(otherStatus);
        verifySatellites(otherStatus);

        set(mStatus, otherStatus);
        verifySatellites(mStatus);
    }

    public void testOverrideSatellites() throws Exception {
        setSatellites(mStatus);
        verifySatellites(mStatus);

        GpsStatus otherStatus = createGpsStatus();
        generateSatellitesData(mCount, true /* reusePrns */);
        setSatellites(otherStatus);
        verifySatellites(otherStatus);

        set(mStatus, otherStatus);
        verifySatellites(mStatus);
    }

    public void testAddSatellites() throws Exception {
        int count = 10;
        generateSatellitesData(count);
        setSatellites(mStatus);
        verifySatellites(mStatus);

        GpsStatus otherStatus = createGpsStatus();
        generateSatellitesData(count);
        setSatellites(otherStatus);
        verifySatellites(otherStatus);

        set(mStatus, otherStatus);
        verifySatellites(mStatus);
    }

    public void testAddMoreSatellites() throws Exception {
        int count = 25;
        generateSatellitesData(count);
        setSatellites(mStatus);
        verifySatellites(mStatus);

        GpsStatus otherStatus = createGpsStatus();
        generateSatellitesData(count * 2);
        setSatellites(otherStatus);
        verifySatellites(otherStatus);

        set(mStatus, otherStatus);
        verifySatellites(mStatus);
    }

    public void testAddLessSatellites() throws Exception {
        int count = 25;
        generateSatellitesData(count * 2);
        setSatellites(mStatus);
        verifySatellites(mStatus);

        GpsStatus otherStatus = createGpsStatus();
        generateSatellitesData(count);
        setSatellites(otherStatus);
        verifySatellites(otherStatus);

        set(mStatus, otherStatus);
        verifySatellites(mStatus);
    }

    private static void verifyIsEmpty(GpsStatus status) {
        verifySatelliteCount(status, 0);
        verifyTtff(status, 0);
    }

    private static void verifySatelliteCount(GpsStatus status, int expectedCount) {
        int satellites = 0;
        for (GpsSatellite s : status.getSatellites()) {
            ++satellites;
        }
        assertEquals("GpsStatus::SatelliteCount", expectedCount, satellites);
    }

    private void verifySatellites(GpsStatus status) {
        verifySatelliteCount(status, mCount);
        verifySatellites(status, mCount, mPrns, mCn0s, mElevations, mAzimuth, mEphemerisMask,
                mAlmanacMask, mUsedInFixMask);
    }

    private static void verifySatellites(
            GpsStatus status,
            int count,
            int[] prns,
            float[] cn0s,
            float[] elevations,
            float[] azimuth,
            int ephemerisMask,
            int almanacMask,
            int usedInFixMask) {
        for (int i = 0; i < count; ++i) {
            int prn = prns[i];
            GpsSatellite satellite = getSatellite(status, prn);
            assertNotNull(getSatelliteAssertInfo(i, prn, "non-null"), satellite);
            assertEquals(getSatelliteAssertInfo(i, prn, "Snr"), cn0s[i], satellite.getSnr());
            assertEquals(
                    getSatelliteAssertInfo(i, prn, "Elevation"),
                    elevations[i],
                    satellite.getElevation());
            assertEquals(
                    getSatelliteAssertInfo(i, prn, "Azimuth"),
                    azimuth[i],
                    satellite.getAzimuth());
            int prnShift = 1 << (prn - 1);
            assertEquals(
                    getSatelliteAssertInfo(i, prn, "ephemeris"),
                    (ephemerisMask & prnShift) != 0,
                    satellite.hasEphemeris());
            assertEquals(
                    getSatelliteAssertInfo(i, prn, "almanac"),
                    (almanacMask & prnShift) != 0,
                    satellite.hasAlmanac());
            assertEquals(
                    getSatelliteAssertInfo(i, prn, "usedInFix"),
                    (usedInFixMask & prnShift) != 0,
                    satellite.usedInFix());
        }
    }

    private static void verifyTtff(GpsStatus status, int expectedTtff) {
        assertEquals("GpsStatus::TTFF", expectedTtff, status.getTimeToFirstFix());
    }

    private static GpsStatus createGpsStatus() throws Exception {
        Constructor<GpsStatus>  ctor = GpsStatus.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    private static void set(GpsStatus status, int ttff) throws Exception {
        Class<?> statusClass = status.getClass();
        Method setTtff = statusClass.getDeclaredMethod("setTimeToFirstFix", Integer.TYPE);
        setTtff.setAccessible(true);
        setTtff.invoke(status, ttff);
    }

    private static void set(GpsStatus status, GpsStatus statusToSet) throws Exception {
        Class<?> statusClass = status.getClass();
        Method setStatus = statusClass.getDeclaredMethod("setStatus", statusClass);
        setStatus.setAccessible(true);
        setStatus.invoke(status, statusToSet);
    }

    private void setSatellites(GpsStatus status) throws Exception {
        set(status, mCount, mPrns, mCn0s, mElevations, mAzimuth, mEphemerisMask, mAlmanacMask,
                mUsedInFixMask);
    }

    private static void set(
            GpsStatus status,
            int count,
            int[] prns,
            float[] cn0s,
            float[] elevations,
            float[] azimuth,
            int ephemerisMask,
            int almanacMask,
            int usedInFixMask) throws Exception {
        Class<?> statusClass = status.getClass();
        Class<?> intClass = Integer.TYPE;
        Class<?> floatArrayClass = Class.forName("[F");
        Method setStatus = statusClass.getDeclaredMethod(
                "setStatus",
                intClass,
                Class.forName("[I"),
                floatArrayClass,
                floatArrayClass,
                floatArrayClass,
                intClass,
                intClass,
                intClass);
        setStatus.setAccessible(true);
        setStatus.invoke(
                status,
                count,
                prns,
                cn0s,
                elevations,
                azimuth,
                ephemerisMask,
                almanacMask,
                usedInFixMask);
    }

    private int generateInt() {
        return mRandom.nextInt(MAX_VALUE) + 1;
    }

    private int[] generateIntArray(int count) {
        Set<Integer> generatedPrns = new HashSet<>();
        int[] array = new int[count];
        for(int i = 0; i < count; ++i) {
            int generated;
            do {
                generated = generateInt();
            } while (generatedPrns.contains(generated));
            array[i] = generated;
            generatedPrns.add(generated);
        }
        return array;
    }

    private float[] generateFloatArray(int count) {
        float[] array = new float[count];
        for(int i = 0; i < count; ++i) {
            array[i] = generateInt();
        }
        return array;
    }

    private int generateMask(int[] prns) {
        int mask = 0;
        int prnsLength = prns.length;
        for (int i = 0; i < prnsLength; ++i) {
            if (mRandom.nextBoolean()) {
                mask |= 1 << (prns[i] - 1);
            }
        }
        return mask;
    }

    private void generateSatellitesData(int count) {
        generateSatellitesData(count, false /* reusePrns */);
    }

    private void generateSatellitesData(int count, boolean reusePrns) {
        mCount = count;
        if (!reusePrns) {
            mPrns = generateIntArray(count);
        }
        mCn0s = generateFloatArray(count);
        mElevations = generateFloatArray(count);
        mAzimuth = generateFloatArray(count);
        mEphemerisMask = generateMask(mPrns);
        mAlmanacMask = generateMask(mPrns);
        mUsedInFixMask = generateMask(mPrns);
    }

    private static GpsSatellite getSatellite(GpsStatus status, int prn) {
        for (GpsSatellite satellite : status.getSatellites()) {
            if (satellite.getPrn() == prn) {
                return satellite;
            }
        }
        return null;
    }

    private static String getSatelliteAssertInfo(int index, int prn, String param) {
        return String.format("Satellite::%s [i=%d, prn=%d]", param, index, prn);
    }
}
