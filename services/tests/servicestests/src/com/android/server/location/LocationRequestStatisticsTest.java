package com.android.server.location;

import com.android.server.location.LocationRequestStatistics.PackageProviderKey;
import com.android.server.location.LocationRequestStatistics.PackageStatistics;

import android.os.SystemClock;
import android.test.AndroidTestCase;

/**
 * Unit tests for {@link LocationRequestStatistics}.
 */
public class LocationRequestStatisticsTest extends AndroidTestCase {
    private static final String PACKAGE1 = "package1";
    private static final String PACKAGE2 = "package2";
    private static final String PROVIDER1 = "provider1";
    private static final String PROVIDER2 = "provider2";
    private static final long INTERVAL1 = 5000;
    private static final long INTERVAL2 = 100000;

    private LocationRequestStatistics mStatistics;
    private long mStartElapsedRealtimeMs;

    @Override
    public void setUp() {
        mStatistics = new LocationRequestStatistics();
        mStartElapsedRealtimeMs = SystemClock.elapsedRealtime();
    }

    /**
     * Tests that adding a single package works correctly.
     */
    public void testSinglePackage() {
        mStatistics.startRequesting(PACKAGE1, PROVIDER1, INTERVAL1, true);

        assertEquals(1, mStatistics.statistics.size());
        PackageProviderKey key = mStatistics.statistics.keySet().iterator().next();
        assertEquals(PACKAGE1, key.packageName);
        assertEquals(PROVIDER1, key.providerName);
        PackageStatistics stats = mStatistics.statistics.values().iterator().next();
        verifyStatisticsTimes(stats);
        assertEquals(INTERVAL1, stats.getFastestIntervalMs());
        assertEquals(INTERVAL1, stats.getSlowestIntervalMs());
        assertTrue(stats.isActive());
    }

    /**
     * Tests that adding a single package works correctly when it is stopped and restarted.
     */
    public void testSinglePackage_stopAndRestart() {
        mStatistics.startRequesting(PACKAGE1, PROVIDER1, INTERVAL1, true);
        mStatistics.stopRequesting(PACKAGE1, PROVIDER1);
        mStatistics.startRequesting(PACKAGE1, PROVIDER1, INTERVAL1, true);

        assertEquals(1, mStatistics.statistics.size());
        PackageProviderKey key = mStatistics.statistics.keySet().iterator().next();
        assertEquals(PACKAGE1, key.packageName);
        assertEquals(PROVIDER1, key.providerName);
        PackageStatistics stats = mStatistics.statistics.values().iterator().next();
        verifyStatisticsTimes(stats);
        assertEquals(INTERVAL1, stats.getFastestIntervalMs());
        assertEquals(INTERVAL1, stats.getSlowestIntervalMs());
        assertTrue(stats.isActive());

        mStatistics.stopRequesting(PACKAGE1, PROVIDER1);
        assertFalse(stats.isActive());
    }

    /**
     * Tests that adding a single package works correctly when multiple intervals are used.
     */
    public void testSinglePackage_multipleIntervals() {
        mStatistics.startRequesting(PACKAGE1, PROVIDER1, INTERVAL1, true);
        mStatistics.startRequesting(PACKAGE1, PROVIDER1, INTERVAL2, true);

        assertEquals(1, mStatistics.statistics.size());
        PackageProviderKey key = mStatistics.statistics.keySet().iterator().next();
        assertEquals(PACKAGE1, key.packageName);
        assertEquals(PROVIDER1, key.providerName);
        PackageStatistics stats = mStatistics.statistics.values().iterator().next();
        verifyStatisticsTimes(stats);
        assertEquals(INTERVAL1, stats.getFastestIntervalMs());
        assertTrue(stats.isActive());

        mStatistics.stopRequesting(PACKAGE1, PROVIDER1);
        assertTrue(stats.isActive());
        mStatistics.stopRequesting(PACKAGE1, PROVIDER1);
        assertFalse(stats.isActive());
    }

    /**
     * Tests that adding a single package works correctly when multiple providers are used.
     */
    public void testSinglePackage_multipleProviders() {
        mStatistics.startRequesting(PACKAGE1, PROVIDER1, INTERVAL1, true);
        mStatistics.startRequesting(PACKAGE1, PROVIDER2, INTERVAL2, true);

        assertEquals(2, mStatistics.statistics.size());
        PackageProviderKey key1 = new PackageProviderKey(PACKAGE1, PROVIDER1);
        PackageStatistics stats1 = mStatistics.statistics.get(key1);
        verifyStatisticsTimes(stats1);
        assertEquals(INTERVAL1, stats1.getSlowestIntervalMs());
        assertEquals(INTERVAL1, stats1.getFastestIntervalMs());
        assertTrue(stats1.isActive());
        PackageProviderKey key2 = new PackageProviderKey(PACKAGE1, PROVIDER2);
        PackageStatistics stats2 = mStatistics.statistics.get(key2);
        verifyStatisticsTimes(stats2);
        assertEquals(INTERVAL2, stats2.getSlowestIntervalMs());
        assertEquals(INTERVAL2, stats2.getFastestIntervalMs());
        assertTrue(stats2.isActive());

        mStatistics.stopRequesting(PACKAGE1, PROVIDER1);
        assertFalse(stats1.isActive());
        assertTrue(stats2.isActive());
        mStatistics.stopRequesting(PACKAGE1, PROVIDER2);
        assertFalse(stats1.isActive());
        assertFalse(stats2.isActive());
    }

    /**
     * Tests that adding multiple packages works correctly.
     */
    public void testMultiplePackages() {
        mStatistics.startRequesting(PACKAGE1, PROVIDER1, INTERVAL1, true);
        mStatistics.startRequesting(PACKAGE1, PROVIDER2, INTERVAL1, true);
        mStatistics.startRequesting(PACKAGE1, PROVIDER2, INTERVAL2, true);
        mStatistics.startRequesting(PACKAGE2, PROVIDER1, INTERVAL1, true);

        assertEquals(3, mStatistics.statistics.size());
        PackageProviderKey key1 = new PackageProviderKey(PACKAGE1, PROVIDER1);
        PackageStatistics stats1 = mStatistics.statistics.get(key1);
        verifyStatisticsTimes(stats1);
        assertEquals(INTERVAL1, stats1.getSlowestIntervalMs());
        assertEquals(INTERVAL1, stats1.getFastestIntervalMs());
        assertTrue(stats1.isActive());

        PackageProviderKey key2 = new PackageProviderKey(PACKAGE1, PROVIDER2);
        PackageStatistics stats2 = mStatistics.statistics.get(key2);
        verifyStatisticsTimes(stats2);
        assertEquals(INTERVAL2, stats2.getSlowestIntervalMs());
        assertEquals(INTERVAL1, stats2.getFastestIntervalMs());
        assertTrue(stats2.isActive());

        PackageProviderKey key3 = new PackageProviderKey(PACKAGE2, PROVIDER1);
        PackageStatistics stats3 = mStatistics.statistics.get(key3);
        verifyStatisticsTimes(stats3);
        assertEquals(INTERVAL1, stats3.getSlowestIntervalMs());
        assertEquals(INTERVAL1, stats3.getFastestIntervalMs());
        assertTrue(stats3.isActive());

        mStatistics.stopRequesting(PACKAGE1, PROVIDER1);
        assertFalse(stats1.isActive());
        assertTrue(stats2.isActive());
        assertTrue(stats3.isActive());

        mStatistics.stopRequesting(PACKAGE1, PROVIDER2);
        assertFalse(stats1.isActive());
        assertTrue(stats2.isActive());
        assertTrue(stats3.isActive());
        mStatistics.stopRequesting(PACKAGE1, PROVIDER2);
        assertFalse(stats2.isActive());

        mStatistics.stopRequesting(PACKAGE2, PROVIDER1);
        assertFalse(stats1.isActive());
        assertFalse(stats2.isActive());
        assertFalse(stats3.isActive());
    }

    /**
     * Tests that switching foreground & background states accmulates time reasonably.
     */
    public void testForegroundBackground() {
        mStatistics.startRequesting(PACKAGE1, PROVIDER1, INTERVAL1, true);
        mStatistics.startRequesting(PACKAGE1, PROVIDER2, INTERVAL1, true);
        mStatistics.startRequesting(PACKAGE2, PROVIDER1, INTERVAL1, false);

        mStatistics.updateForeground(PACKAGE1, PROVIDER2, false);
        mStatistics.updateForeground(PACKAGE2, PROVIDER1, true);

        mStatistics.stopRequesting(PACKAGE1, PROVIDER1);

        for (PackageStatistics stats : mStatistics.statistics.values()) {
            verifyStatisticsTimes(stats);
        }
    }

    private void verifyStatisticsTimes(PackageStatistics stats) {
        long durationMs = stats.getDurationMs();
        long foregroundDurationMs = stats.getForegroundDurationMs();
        long timeSinceFirstRequestMs = stats.getTimeSinceFirstRequestMs();
        long maxDeltaMs = SystemClock.elapsedRealtime() - mStartElapsedRealtimeMs;
        assertTrue("Duration is too small", durationMs >= 0);
        assertTrue("Duration is too large", durationMs <= maxDeltaMs);
        assertTrue("Foreground Duration is too small", foregroundDurationMs >= 0);
        assertTrue("Foreground Duration is too large", foregroundDurationMs <= maxDeltaMs);
        assertTrue("Time since first request is too large", timeSinceFirstRequestMs <= maxDeltaMs);
    }
}
