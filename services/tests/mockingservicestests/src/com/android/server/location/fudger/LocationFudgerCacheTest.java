/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.location.fudger;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.location.flags.Flags;
import android.location.provider.IS2CellIdsCallback;
import android.location.provider.IS2LevelCallback;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.location.geometry.S2CellIdUtils;
import com.android.server.location.provider.proxy.ProxyPopulationDensityProvider;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
@RequiresFlagsEnabled(Flags.FLAG_DENSITY_BASED_COARSE_LOCATIONS)
public class LocationFudgerCacheTest {

    private static final String TAG = "LocationFudgerCacheTest";

    private static final long TIMES_SQUARE_S2_ID =
            S2CellIdUtils.fromLatLngDegrees(40.758896, -73.985130);

    private static final double[] POINT_IN_TIMES_SQUARE = {40.75889599346095, -73.9851300385147};

    private static final double[] POINT_OUTSIDE_TIMES_SQUARE = {48.858093, 2.294694};

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Rule
    public final CheckFlagsRule mCheckFlagsRule =
            DeviceFlagsValueProvider.createCheckFlagsRule();

    @Test
    public void hasDefaultValue_isInitiallyFalse()
            throws RemoteException {
        ProxyPopulationDensityProvider provider = mock(ProxyPopulationDensityProvider.class);
        LocationFudgerCache cache = new LocationFudgerCache(provider);

        assertThat(cache.hasDefaultValue()).isFalse();
    }

    @Test
    public void hasDefaultValue_uponQueryError_isStillFalse()
            throws RemoteException {
        ProxyPopulationDensityProvider provider = mock(ProxyPopulationDensityProvider.class);
        LocationFudgerCache cache = new LocationFudgerCache(provider);

        ArgumentCaptor<IS2LevelCallback> argumentCaptor = ArgumentCaptor.forClass(
                IS2LevelCallback.class);
        verify(provider).getDefaultCoarseningLevel(argumentCaptor.capture());

        IS2LevelCallback cb = argumentCaptor.getValue();
        cb.onError();

        assertThat(cache.hasDefaultValue()).isFalse();
    }

    @Test
    public void hasDefaultValue_afterSuccessfulQuery_isTrue()
            throws RemoteException {
        ProxyPopulationDensityProvider provider = mock(ProxyPopulationDensityProvider.class);
        LocationFudgerCache cache = new LocationFudgerCache(provider);

        ArgumentCaptor<IS2LevelCallback> argumentCaptor = ArgumentCaptor.forClass(
                IS2LevelCallback.class);
        verify(provider).getDefaultCoarseningLevel(argumentCaptor.capture());

        IS2LevelCallback cb = argumentCaptor.getValue();
        cb.onResult(10);

        assertThat(cache.hasDefaultValue()).isTrue();
    }

    @Test
    public void locationFudgerCache_whenQueriedOutsideOfCache_returnsDefault()
            throws RemoteException {
        ProxyPopulationDensityProvider provider = mock(ProxyPopulationDensityProvider.class);
        LocationFudgerCache cache = new LocationFudgerCache(provider);
        int level = 10;
        int defaultLevel = 2;
        Long s2Cell = S2CellIdUtils.getParent(TIMES_SQUARE_S2_ID, level);

        ArgumentCaptor<IS2LevelCallback> argumentCaptor = ArgumentCaptor.forClass(
                IS2LevelCallback.class);
        verify(provider).getDefaultCoarseningLevel(argumentCaptor.capture());

        IS2LevelCallback cb = argumentCaptor.getValue();
        cb.onResult(defaultLevel);

        cache.addToCache(s2Cell);

        assertThat(cache.getCoarseningLevel(POINT_OUTSIDE_TIMES_SQUARE[0],
                POINT_OUTSIDE_TIMES_SQUARE[1])).isEqualTo(defaultLevel);
    }

    @Test
    public void locationFudgerCache_whenQueriedValueIsCached_returnsCachedValue() {
        ProxyPopulationDensityProvider provider = mock(ProxyPopulationDensityProvider.class);
        LocationFudgerCache cache = new LocationFudgerCache(provider);
        int level = 10;
        Long s2Cell = S2CellIdUtils.getParent(TIMES_SQUARE_S2_ID, level);

        cache.addToCache(s2Cell);

        assertThat(cache.getCoarseningLevel(POINT_IN_TIMES_SQUARE[0], POINT_IN_TIMES_SQUARE[1]))
                .isEqualTo(level);
    }

    @Test
    public void locationFudgerCache_whenStarting_queriesDefaultValue() {
        ProxyPopulationDensityProvider provider = mock(ProxyPopulationDensityProvider.class);
        LocationFudgerCache cache = new LocationFudgerCache(provider);

        verify(provider).getDefaultCoarseningLevel(any());
    }

    @Test
    public void locationFudgerCache_ifDidntGetDefaultValue_queriesItAgain() {
        ProxyPopulationDensityProvider provider = mock(ProxyPopulationDensityProvider.class);
        LocationFudgerCache cache = new LocationFudgerCache(provider);

        verify(provider, times(1)).getDefaultCoarseningLevel(any());

        cache.getCoarseningLevel(90.0, 0.0);

        verify(provider, times(2)).getDefaultCoarseningLevel(any());
    }

    @Test
    public void locationFudgerCache_ifReceivedDefaultValue_doesNotQueriesIt()
            throws RemoteException {
        ProxyPopulationDensityProvider provider = mock(ProxyPopulationDensityProvider.class);
        LocationFudgerCache cache = new LocationFudgerCache(provider);

        ArgumentCaptor<IS2LevelCallback> argumentCaptor = ArgumentCaptor.forClass(
                IS2LevelCallback.class);
        verify(provider, times(1)).getDefaultCoarseningLevel(argumentCaptor.capture());

        IS2LevelCallback cb = argumentCaptor.getValue();
        cb.onResult(10);

        cache.getCoarseningLevel(90.0, 0.0);

        // Verify getDefaultCoarseningLevel did not get called again
        verify(provider, times(1)).getDefaultCoarseningLevel(any());
    }

    @Test
    public void locationFudgerCache_whenSuccessfullyQueriesDefaultValue_storesResult()
            throws RemoteException {
        ProxyPopulationDensityProvider provider = mock(ProxyPopulationDensityProvider.class);
        LocationFudgerCache cache = new LocationFudgerCache(provider);
        int level = 10;

        ArgumentCaptor<IS2LevelCallback> argumentCaptor = ArgumentCaptor.forClass(
                IS2LevelCallback.class);
        verify(provider).getDefaultCoarseningLevel(argumentCaptor.capture());

        IS2LevelCallback cb = argumentCaptor.getValue();
        cb.onResult(level);

        // Query any uncached location
        assertThat(cache.getCoarseningLevel(0.0, 0.0)).isEqualTo(level);
    }

    @Test
    public void locationFudgerCache_whenQueryingDefaultValueFails_returnsDefault()
            throws RemoteException {
        ProxyPopulationDensityProvider provider = mock(ProxyPopulationDensityProvider.class);
        LocationFudgerCache cache = new LocationFudgerCache(provider);

        ArgumentCaptor<IS2LevelCallback> argumentCaptor = ArgumentCaptor.forClass(
                IS2LevelCallback.class);
        verify(provider).getDefaultCoarseningLevel(argumentCaptor.capture());

        IS2LevelCallback cb = argumentCaptor.getValue();
        cb.onError();

        // Query any uncached location. The default value is 0
        assertThat(cache.getCoarseningLevel(0.0, 0.0)).isEqualTo(0);
    }

    @Test
    public void locationFudgerCache_whenQueryIsNotCached_queriesProvider() {
        ProxyPopulationDensityProvider provider = mock(ProxyPopulationDensityProvider.class);
        LocationFudgerCache cache = new LocationFudgerCache(provider);

        cache.getCoarseningLevel(POINT_IN_TIMES_SQUARE[0], POINT_IN_TIMES_SQUARE[1]);

        verify(provider).getCoarsenedS2Cells(eq(POINT_IN_TIMES_SQUARE[0]),
                eq(POINT_IN_TIMES_SQUARE[1]), anyInt(), any());
    }

    @Test
    public void locationFudgerCache_whenProviderIsQueried_resultIsCached() throws RemoteException {
        double lat = POINT_IN_TIMES_SQUARE[0];
        double lng = POINT_IN_TIMES_SQUARE[1];
        ProxyPopulationDensityProvider provider = mock(ProxyPopulationDensityProvider.class);
        LocationFudgerCache cache = new LocationFudgerCache(provider);

        int level = cache.getCoarseningLevel(lat, lng);
        assertThat(level).isEqualTo(0);  // default value

        ArgumentCaptor<IS2CellIdsCallback> argumentCaptor = ArgumentCaptor.forClass(
                IS2CellIdsCallback.class);
        verify(provider).getCoarsenedS2Cells(eq(POINT_IN_TIMES_SQUARE[0]),
                eq(POINT_IN_TIMES_SQUARE[1]), anyInt(), argumentCaptor.capture());

        // Results from the proxy should set the cache
        int expectedLevel = 4;
        long leafCell = S2CellIdUtils.fromLatLngDegrees(lat, lng);
        Long s2CellId = S2CellIdUtils.getParent(leafCell, expectedLevel);
        IS2CellIdsCallback cb = argumentCaptor.getValue();
        long[] answer = new long[] {s2CellId};
        cb.onResult(answer);

        int level2 = cache.getCoarseningLevel(lat, lng);
        assertThat(level2).isEqualTo(expectedLevel);
    }

    @Test
    public void locationFudgerCache_whenQueryIsCached_doesNotRefreshIt() {
        ProxyPopulationDensityProvider provider = mock(ProxyPopulationDensityProvider.class);
        LocationFudgerCache cache = new LocationFudgerCache(provider);

        cache.addToCache(TIMES_SQUARE_S2_ID);

        verify(provider, never()).getCoarsenedS2Cells(anyDouble(), anyDouble(), anyInt(), any());
    }

    @Test
    public void locationFudgerCache_whenQueryIsCached_askForMaxCacheSizeElems() {
        ProxyPopulationDensityProvider provider = mock(ProxyPopulationDensityProvider.class);
        LocationFudgerCache cache = new LocationFudgerCache(provider);
        int numAdditionalCells = cache.MAX_CACHE_SIZE - 1;

        cache.getCoarseningLevel(POINT_IN_TIMES_SQUARE[0], POINT_IN_TIMES_SQUARE[1]);

        verify(provider).getCoarsenedS2Cells(eq(POINT_IN_TIMES_SQUARE[0]),
                eq(POINT_IN_TIMES_SQUARE[1]), eq(numAdditionalCells), any());
    }


    @Test
    public void locationFudgerCache_canContainUpToMaxSizeItems() {
        // This test has two sequences of arrange-act-assert.
        // The first checks that the cache correctly store up to MAX_CACHE_SIZE items.
        // The second checks that any new element replaces the oldest in the cache.

        // Arrange.
        ProxyPopulationDensityProvider provider = mock(ProxyPopulationDensityProvider.class);
        LocationFudgerCache cache = new LocationFudgerCache(provider);
        int size = cache.MAX_CACHE_SIZE;

        double[][] latlngs = new double[size][2];
        long[] cells = new long[size];
        int[] expectedLevels = new int[size];

        for (int i = 0; i < size; i++) {
            // Create arbitrary lat/lngs.
            latlngs[i][0] = 10.0 * i;
            latlngs[i][1] = 10.0 * i;

            expectedLevels[i] = 10;  // we set some arbitrary S2 level for each latlng.

            long leafCell = S2CellIdUtils.fromLatLngDegrees(latlngs[i][0], latlngs[i][1]);
            long s2CellId = S2CellIdUtils.getParent(leafCell, expectedLevels[i]);
            cells[i] = s2CellId;
        }

        // Act.
        cache.addToCache(cells);

        // Assert: check that the cache contains these latlngs and returns the correct level.
        for (int i = 0; i < size; i++) {
            assertThat(cache.getCoarseningLevel(latlngs[i][0], latlngs[i][1]))
                    .isEqualTo(expectedLevels[i]);
        }

        // Second assertion: A new value evicts the oldest one.

        // Arrange.
        int expectedLevel = 25;
        long leafCell = S2CellIdUtils.fromLatLngDegrees(-10.0, -180.0);
        long s2CellId = S2CellIdUtils.getParent(leafCell, expectedLevel);

        // Act.
        cache.addToCache(s2CellId);

        // Assert: the new point is in the cache.
        assertThat(cache.getCoarseningLevel(-10.0, -180.0)).isEqualTo(expectedLevel);
        // Assert: all but the oldest point are still in cache.
        for (int i = 0; i < size - 1; i++) {
            assertThat(cache.getCoarseningLevel(latlngs[i][0], latlngs[i][1]))
                    .isEqualTo(expectedLevels[i]);
        }
        // Assert: the oldest point has been evicted.
        assertThat(cache.getCoarseningLevel(latlngs[size - 1][0], latlngs[size - 1][1]))
                .isEqualTo(0);
    }
}
