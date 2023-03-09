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

package com.android.internal.location.altitude;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.location.altitude.nano.MapParamsProto;
import com.android.internal.location.altitude.nano.S2TileProto;
import com.android.internal.util.Preconditions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Manages a mapping of geoid heights associated with S2 cells, referred to as MAP CELLS.
 *
 * <p>Tiles are used extensively to reduce the number of entries needed to be stored in memory and
 * on disk. A tile associates geoid heights with all map cells of a common parent at a specified S2
 * level.
 *
 * <p>Since bilinear interpolation considers at most four map cells at a time, at most four tiles
 * are simultaneously stored in memory. These tiles, referred to as CACHE TILES, are each keyed by
 * its common parent's S2 cell ID, referred to as a CACHE KEY.
 *
 * <p>Absent cache tiles needed for interpolation are constructed from larger tiles stored on disk.
 * The latter tiles, referred to as DISK TILES, are each keyed by its common parent's S2 cell token,
 * referred to as a DISK TOKEN.
 */
public final class GeoidHeightMap {

    private static final Object sLock = new Object();

    @GuardedBy("sLock")
    @Nullable
    private static MapParamsProto sParams;

    /** Defines a cache large enough to hold all cache tiles needed for interpolation. */
    private final LruCache<Long, S2TileProto> mCacheTiles = new LruCache<>(4);

    /**
     * Returns the singleton parameter instance for a spherically projected geoid height map and its
     * corresponding tile management.
     */
    @NonNull
    public static MapParamsProto getParams(@NonNull Context context) throws IOException {
        synchronized (sLock) {
            if (sParams == null) {
                try (InputStream is = context.getApplicationContext().getAssets().open(
                        "geoid_height_map/map-params.pb")) {
                    sParams = MapParamsProto.parseFrom(is.readAllBytes());
                }
            }
            return sParams;
        }
    }

    /**
     * Same as {@link #getParams(Context)} except that null is returned if the singleton parameter
     * instance is not yet initialized.
     */
    @Nullable
    public static MapParamsProto getParams() {
        synchronized (sLock) {
            return sParams;
        }
    }

    private static long getCacheKey(@NonNull MapParamsProto params, long s2CellId) {
        return S2CellIdUtils.getParent(s2CellId, params.cacheTileS2Level);
    }

    @NonNull
    private static String getDiskToken(@NonNull MapParamsProto params, long s2CellId) {
        return S2CellIdUtils.getToken(
                S2CellIdUtils.getParent(s2CellId, params.diskTileS2Level));
    }

    /**
     * Adds to {@code values} values in the unit interval [0, 1] for the map cells identified by
     * {@code s2CellIds}. Returns true if values are present for all IDs; otherwise, returns false
     * and adds NaNs for absent values.
     */
    private static boolean getUnitIntervalValues(@NonNull MapParamsProto params,
            @NonNull TileFunction tileFunction,
            @NonNull long[] s2CellIds, @NonNull double[] values) {
        int len = s2CellIds.length;

        S2TileProto[] tiles = new S2TileProto[len];
        for (int i = 0; i < len; i++) {
            long cacheKey = getCacheKey(params, s2CellIds[i]);
            tiles[i] = tileFunction.getTile(cacheKey);
            values[i] = Double.NaN;
        }

        for (int i = 0; i < len; i++) {
            if (tiles[i] == null || !Double.isNaN(values[i])) {
                continue;
            }

            mergeByteBufferValues(params, s2CellIds, tiles, i, values);
            mergeByteJpegValues(params, s2CellIds, tiles, i, values);
            mergeBytePngValues(params, s2CellIds, tiles, i, values);
        }

        boolean allFound = true;
        for (int i = 0; i < len; i++) {
            if (Double.isNaN(values[i])) {
                allFound = false;
            } else {
                values[i] = (((int) values[i]) & 0xFF) / 255.0;
            }
        }
        return allFound;
    }

    @SuppressWarnings("ReferenceEquality")
    private static void mergeByteBufferValues(@NonNull MapParamsProto params,
            @NonNull long[] s2CellIds,
            @NonNull S2TileProto[] tiles,
            int tileIndex, @NonNull double[] values) {
        byte[] bytes = tiles[tileIndex].byteBuffer;
        if (bytes == null || bytes.length == 0) {
            return;
        }

        ByteBuffer byteBuffer = ByteBuffer.wrap(bytes).asReadOnlyBuffer();
        int tileS2Level = params.mapS2Level - Integer.numberOfTrailingZeros(byteBuffer.limit()) / 2;
        int numBitsLeftOfTile = 2 * tileS2Level + 3;

        for (int i = tileIndex; i < tiles.length; i++) {
            if (tiles[i] != tiles[tileIndex]) {
                continue;
            }

            long maskedS2CellId = s2CellIds[i] & (-1L >>> numBitsLeftOfTile);
            int numBitsRightOfMap = 2 * (S2CellIdUtils.MAX_LEVEL - params.mapS2Level) + 1;
            int bufferIndex = (int) (maskedS2CellId >>> numBitsRightOfMap);
            values[i] = Double.isNaN(values[i]) ? 0 : values[i];
            values[i] += ((int) byteBuffer.get(bufferIndex)) & 0xFF;
        }
    }

    private static void mergeByteJpegValues(@NonNull MapParamsProto params,
            @NonNull long[] s2CellIds,
            @NonNull S2TileProto[] tiles,
            int tileIndex, @NonNull double[] values) {
        mergeByteImageValues(params, tiles[tileIndex].byteJpeg, s2CellIds, tiles, tileIndex,
                values);
    }

    private static void mergeBytePngValues(@NonNull MapParamsProto params,
            @NonNull long[] s2CellIds,
            @NonNull S2TileProto[] tiles,
            int tileIndex, @NonNull double[] values) {
        mergeByteImageValues(params, tiles[tileIndex].bytePng, s2CellIds, tiles, tileIndex, values);
    }

    @SuppressWarnings("ReferenceEquality")
    private static void mergeByteImageValues(@NonNull MapParamsProto params, @NonNull byte[] bytes,
            @NonNull long[] s2CellIds,
            @NonNull S2TileProto[] tiles, int tileIndex, @NonNull double[] values) {
        if (bytes == null || bytes.length == 0) {
            return;
        }
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (bitmap == null) {
            return;
        }

        for (int i = tileIndex; i < tiles.length; i++) {
            if (tiles[i] != tiles[tileIndex]) {
                continue;
            }

            values[i] = Double.isNaN(values[i]) ? 0 : values[i];
            values[i] += bitmap.getPixel(getIndexX(params, s2CellIds[i], bitmap.getWidth()),
                    getIndexY(params, s2CellIds[i], bitmap.getHeight())) & 0xFF;
        }
    }

    /** Returns the X index for an S2 cell within an S2 tile image of specified width. */
    private static int getIndexX(@NonNull MapParamsProto params, long s2CellId, int width) {
        return getIndexXOrY(params, S2CellIdUtils.getI(s2CellId), width);
    }

    /** Returns the Y index for an S2 cell within an S2 tile image of specified height. */
    private static int getIndexY(@NonNull MapParamsProto params, long s2CellId, int height) {
        return getIndexXOrY(params, S2CellIdUtils.getJ(s2CellId), height);
    }

    private static int getIndexXOrY(@NonNull MapParamsProto params, int iOrJ, int widthOrHeight) {
        return (iOrJ >> (S2CellIdUtils.MAX_LEVEL - params.mapS2Level)) % widthOrHeight;
    }

    /**
     * Throws an {@link IllegalArgumentException} if the {@code s2CellIds} has an invalid length or
     * ID.
     */
    private static void validate(@NonNull MapParamsProto params, @NonNull long[] s2CellIds) {
        Preconditions.checkArgument(s2CellIds.length == 4);
        for (long s2CellId : s2CellIds) {
            Preconditions.checkArgument(S2CellIdUtils.getLevel(s2CellId) == params.mapS2Level);
        }
    }

    /**
     * Returns the geoid heights in meters associated with the map cells identified by
     * {@code s2CellIds}. Throws an {@link IOException} if a geoid height cannot be calculated for
     * an ID.
     */
    @NonNull
    public double[] readGeoidHeights(@NonNull MapParamsProto params, @NonNull Context context,
            @NonNull long[] s2CellIds) throws IOException {
        validate(params, s2CellIds);
        double[] heightsMeters = new double[s2CellIds.length];
        if (getGeoidHeights(params, mCacheTiles::get, s2CellIds, heightsMeters)) {
            return heightsMeters;
        }

        TileFunction loadedTiles = loadFromCacheAndDisk(params, context, s2CellIds);
        if (getGeoidHeights(params, loadedTiles, s2CellIds, heightsMeters)) {
            return heightsMeters;
        }
        throw new IOException("Unable to calculate geoid heights from raw assets.");
    }

    /**
     * Same as {@link #readGeoidHeights(MapParamsProto, Context, long[])} except that data will not
     * be loaded from raw assets. Returns the heights if present for all IDs; otherwise, returns
     * null.
     */
    @Nullable
    public double[] readGeoidHeights(@NonNull MapParamsProto params, @NonNull long[] s2CellIds) {
        validate(params, s2CellIds);
        double[] heightsMeters = new double[s2CellIds.length];
        if (getGeoidHeights(params, mCacheTiles::get, s2CellIds, heightsMeters)) {
            return heightsMeters;
        }
        return null;
    }

    /**
     * Adds to {@code heightsMeters} the geoid heights in meters associated with the map cells
     * identified by {@code s2CellIds}. Returns true if heights are present for all IDs; otherwise,
     * returns false and adds NaNs for absent heights.
     */
    private boolean getGeoidHeights(@NonNull MapParamsProto params,
            @NonNull TileFunction tileFunction, @NonNull long[] s2CellIds,
            @NonNull double[] heightsMeters) {
        boolean allFound = getUnitIntervalValues(params, tileFunction, s2CellIds, heightsMeters);
        for (int i = 0; i < heightsMeters.length; i++) {
            // NaNs are properly preserved.
            heightsMeters[i] *= params.modelAMeters;
            heightsMeters[i] += params.modelBMeters;
        }
        return allFound;
    }

    @NonNull
    private TileFunction loadFromCacheAndDisk(@NonNull MapParamsProto params,
            @NonNull Context context, @NonNull long[] s2CellIds) throws IOException {
        int len = s2CellIds.length;

        // Enable batch loading by finding all cache keys upfront.
        long[] cacheKeys = new long[len];
        for (int i = 0; i < len; i++) {
            cacheKeys[i] = getCacheKey(params, s2CellIds[i]);
        }

        // Attempt to load tiles from cache.
        S2TileProto[] loadedTiles = new S2TileProto[len];
        String[] diskTokens = new String[len];
        for (int i = 0; i < len; i++) {
            if (diskTokens[i] != null) {
                continue;
            }
            loadedTiles[i] = mCacheTiles.get(cacheKeys[i]);
            diskTokens[i] = getDiskToken(params, cacheKeys[i]);

            // Batch across common cache key.
            for (int j = i + 1; j < len; j++) {
                if (cacheKeys[j] == cacheKeys[i]) {
                    loadedTiles[j] = loadedTiles[i];
                    diskTokens[j] = diskTokens[i];
                }
            }
        }

        // Attempt to load tiles from disk.
        for (int i = 0; i < len; i++) {
            if (loadedTiles[i] != null) {
                continue;
            }

            S2TileProto tile;
            try (InputStream is = context.getApplicationContext().getAssets().open(
                    "geoid_height_map/tile-" + diskTokens[i] + ".pb")) {
                tile = S2TileProto.parseFrom(is.readAllBytes());
            }
            mergeFromDiskTile(params, tile, cacheKeys, diskTokens, i, loadedTiles);
        }

        return cacheKey -> {
            for (int i = 0; i < cacheKeys.length; i++) {
                if (cacheKeys[i] == cacheKey) {
                    return loadedTiles[i];
                }
            }
            return null;
        };
    }

    private void mergeFromDiskTile(@NonNull MapParamsProto params, @NonNull S2TileProto diskTile,
            @NonNull long[] cacheKeys, @NonNull String[] diskTokens, int diskTokenIndex,
            @NonNull S2TileProto[] loadedTiles) throws IOException {
        int len = cacheKeys.length;
        int numMapCellsPerCacheTile = 1 << (2 * (params.mapS2Level - params.cacheTileS2Level));

        // Reusable arrays.
        long[] s2CellIds = new long[numMapCellsPerCacheTile];
        double[] values = new double[numMapCellsPerCacheTile];

        // Each cache key identifies a different sub-tile of the same disk tile.
        TileFunction diskTileFunction = cacheKey -> diskTile;
        for (int i = diskTokenIndex; i < len; i++) {
            if (!Objects.equals(diskTokens[i], diskTokens[diskTokenIndex])
                    || loadedTiles[i] != null) {
                continue;
            }

            // Find all map cells within the current cache tile.
            long s2CellId = S2CellIdUtils.getTraversalStart(cacheKeys[i], params.mapS2Level);
            for (int j = 0; j < numMapCellsPerCacheTile; j++) {
                s2CellIds[j] = s2CellId;
                s2CellId = S2CellIdUtils.getTraversalNext(s2CellId);
            }

            if (!getUnitIntervalValues(params, diskTileFunction, s2CellIds, values)) {
                throw new IOException("Corrupted disk tile of disk token: " + diskTokens[i]);
            }

            loadedTiles[i] = new S2TileProto();
            loadedTiles[i].byteBuffer = new byte[numMapCellsPerCacheTile];
            for (int j = 0; j < numMapCellsPerCacheTile; j++) {
                loadedTiles[i].byteBuffer[j] = (byte) Math.round(values[j] * 0xFF);
            }

            // Batch across common cache key.
            for (int j = i + 1; j < len; j++) {
                if (cacheKeys[j] == cacheKeys[i]) {
                    loadedTiles[j] = loadedTiles[i];
                }
            }

            // Side load into tile cache.
            mCacheTiles.put(cacheKeys[i], loadedTiles[i]);
        }
    }

    /** Defines a function-like object to retrieve tiles for cache keys. */
    private interface TileFunction {

        @Nullable
        S2TileProto getTile(long cacheKey);
    }
}
