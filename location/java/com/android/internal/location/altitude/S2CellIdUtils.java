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

import java.util.Arrays;
import java.util.Locale;

/**
 * Provides lightweight S2 cell ID utilities without traditional geometry dependencies.
 *
 * <p>See <a href="https://s2geometry.io/">the S2 Geometry Library website</a> for more details.
 */
public final class S2CellIdUtils {

    /** The level of all leaf S2 cells. */
    public static final int MAX_LEVEL = 30;

    private static final int MAX_SIZE = 1 << MAX_LEVEL;
    private static final double ONE_OVER_MAX_SIZE = 1.0 / MAX_SIZE;
    private static final int NUM_FACES = 6;
    private static final int POS_BITS = 2 * MAX_LEVEL + 1;
    private static final int SWAP_MASK = 0x1;
    private static final int LOOKUP_BITS = 4;
    private static final int LOOKUP_MASK = (1 << LOOKUP_BITS) - 1;
    private static final int INVERT_MASK = 0x2;
    private static final int LEAF_MASK = 0x1;
    private static final int[] LOOKUP_POS = new int[1 << (2 * LOOKUP_BITS + 2)];
    private static final int[] LOOKUP_IJ = new int[1 << (2 * LOOKUP_BITS + 2)];
    private static final int[] POS_TO_ORIENTATION = {SWAP_MASK, 0, 0, INVERT_MASK + SWAP_MASK};
    private static final int[][] POS_TO_IJ =
            {{0, 1, 3, 2}, {0, 2, 3, 1}, {3, 2, 0, 1}, {3, 1, 0, 2}};
    private static final double UV_LIMIT = calculateUvLimit();
    private static final UvTransform[] UV_TRANSFORMS = createUvTransforms();
    private static final XyzTransform[] XYZ_TRANSFORMS = createXyzTransforms();

    // Used to encode (i, j, o) coordinates into primitive longs.
    private static final int I_SHIFT = 33;
    private static final int J_SHIFT = 2;
    private static final long J_MASK = (1L << 31) - 1;

    static {
        initLookupCells();
    }

    /** Prevents instantiation. */
    private S2CellIdUtils() {
    }

    /**
     * Returns the leaf S2 cell ID for the specified latitude and longitude, both measured in
     * degrees.
     */
    public static long fromLatLngDegrees(double latDegrees, double lngDegrees) {
        return fromLatLngRadians(Math.toRadians(latDegrees), Math.toRadians(lngDegrees));
    }

    /** Returns the leaf S2 cell ID of the specified (face, i, j) coordinate. */
    public static long fromFij(int face, int i, int j) {
        int bits = (face & SWAP_MASK);
        // Update most significant bits.
        long msb = ((long) face) << (POS_BITS - 33);
        for (int k = 7; k >= 4; --k) {
            bits = lookupBits(i, j, k, bits);
            msb = updateBits(msb, k, bits);
            bits = maskBits(bits);
        }
        // Update least significant bits.
        long lsb = 0;
        for (int k = 3; k >= 0; --k) {
            bits = lookupBits(i, j, k, bits);
            lsb = updateBits(lsb, k, bits);
            bits = maskBits(bits);
        }
        return (((msb << 32) + lsb) << 1) + 1;
    }

    /**
     * Returns the face of the specified S2 cell. The returned face is in [0, 5] for valid S2 cell
     * IDs. Behavior is undefined for invalid S2 cell IDs.
     */
    public static int getFace(long s2CellId) {
        return (int) (s2CellId >>> POS_BITS);
    }

    /**
     * Returns the ID of the parent of the specified S2 cell at the specified parent level.
     * Behavior is undefined for invalid S2 cell IDs or parent levels not in
     * [0, {@code getLevel(s2CellId)}[.
     */
    public static long getParent(long s2CellId, int level) {
        long newLsb = getLowestOnBitForLevel(level);
        return (s2CellId & -newLsb) | newLsb;
    }

    /**
     * Inserts into {@code neighbors} the four S2 cell IDs corresponding to the neighboring
     * cells adjacent across the specified cell's four edges. This array must be of minimum
     * length four, and elements at the tail end of the array not corresponding to a neighbor
     * are set to zero. A reference to this array is returned.
     *
     * <p>Inserts in the order of down, right, up, and left directions, in that order. All
     * neighbors are guaranteed to be distinct.
     */
    public static void getEdgeNeighbors(long s2CellId, @NonNull long[] neighbors) {
        int level = getLevel(s2CellId);
        int size = levelToSizeIj(level);
        int face = getFace(s2CellId);
        long ijo = toIjo(s2CellId);
        int i = ijoToI(ijo);
        int j = ijoToJ(ijo);

        int iPlusSize = i + size;
        int iMinusSize = i - size;
        int jPlusSize = j + size;
        int jMinusSize = j - size;
        boolean iPlusSizeLtMax = iPlusSize < MAX_SIZE;
        boolean iMinusSizeGteZero = iMinusSize >= 0;
        boolean jPlusSizeLtMax = jPlusSize < MAX_SIZE;
        boolean jMinusSizeGteZero = jMinusSize >= 0;

        int index = 0;
        // Down direction.
        neighbors[index++] = getParent(fromFijSame(face, i, jMinusSize, jMinusSizeGteZero),
                level);
        // Right direction.
        neighbors[index++] = getParent(fromFijSame(face, iPlusSize, j, iPlusSizeLtMax), level);
        // Up direction.
        neighbors[index++] = getParent(fromFijSame(face, i, jPlusSize, jPlusSizeLtMax), level);
        // Left direction.
        neighbors[index++] = getParent(fromFijSame(face, iMinusSize, j, iMinusSizeGteZero),
                level);

        // Pad end of neighbor array with zeros.
        Arrays.fill(neighbors, index, neighbors.length, 0);
    }

    /** Returns the "i" coordinate for the specified S2 cell. */
    public static int getI(long s2CellId) {
        return ijoToI(toIjo(s2CellId));
    }

    /** Returns the "j" coordinate for the specified S2 cell. */
    public static int getJ(long s2CellId) {
        return ijoToJ(toIjo(s2CellId));
    }

    /**
     * Returns the leaf S2 cell ID for the specified latitude and longitude, both measured in
     * radians.
     */
    private static long fromLatLngRadians(double latRadians, double lngRadians) {
        double cosLat = Math.cos(latRadians);
        double x = Math.cos(lngRadians) * cosLat;
        double y = Math.sin(lngRadians) * cosLat;
        double z = Math.sin(latRadians);
        return fromXyz(x, y, z);
    }

    /**
     * Returns the level of the specified S2 cell. The returned level is in [0, 30] for valid
     * S2 cell IDs. Behavior is undefined for invalid S2 cell IDs.
     */
    static int getLevel(long s2CellId) {
        if (isLeaf(s2CellId)) {
            return MAX_LEVEL;
        }
        return MAX_LEVEL - (Long.numberOfTrailingZeros(s2CellId) >> 1);
    }

    /** Returns the lowest-numbered bit that is on for the specified S2 cell. */
    static long getLowestOnBit(long s2CellId) {
        return s2CellId & -s2CellId;
    }

    /** Returns the lowest-numbered bit that is on for any S2 cell on the specified level. */
    static long getLowestOnBitForLevel(int level) {
        return 1L << (2 * (MAX_LEVEL - level));
    }

    /**
     * Returns the ID of the first S2 cell in a traversal of the children S2 cells at the specified
     * level, in Hilbert curve order.
     */
    static long getTraversalStart(long s2CellId, int level) {
        return s2CellId - getLowestOnBit(s2CellId) + getLowestOnBitForLevel(level);
    }

    /** Returns the ID of the next S2 cell at the same level along the Hilbert curve. */
    static long getTraversalNext(long s2CellId) {
        return s2CellId + (getLowestOnBit(s2CellId) << 1);
    }

    /**
     * Encodes the S2 cell id to compact text strings suitable for display or indexing. Cells at
     * lower levels (i.e., larger cells) are encoded into fewer characters.
     */
    @NonNull
    static String getToken(long s2CellId) {
        if (s2CellId == 0) {
            return "X";
        }

        // Convert to a hex string with as many digits as necessary.
        String hex = Long.toHexString(s2CellId).toLowerCase(Locale.US);
        // Prefix 0s to get a length 16 string.
        String padded = padStart(hex);
        // Trim zeroes off the end.
        return padded.replaceAll("0*$", "");
    }

    private static String padStart(String string) {
        if (string.length() >= 16) {
            return string;
        }
        return "0".repeat(16 - string.length()) + string;
    }

    /** Returns the leaf S2 cell ID of the specified (x, y, z) coordinate. */
    private static long fromXyz(double x, double y, double z) {
        int face = xyzToFace(x, y, z);
        UvTransform uvTransform = UV_TRANSFORMS[face];
        double u = uvTransform.xyzToU(x, y, z);
        double v = uvTransform.xyzToV(x, y, z);
        return fromFuv(face, u, v);
    }

    /** Returns the leaf S2 cell ID of the specified (face, u, v) coordinate. */
    private static long fromFuv(int face, double u, double v) {
        int i = uToI(u);
        int j = vToJ(v);
        return fromFij(face, i, j);
    }

    private static long fromFijWrap(int face, int i, int j) {
        double u = iToU(i);
        double v = jToV(j);

        XyzTransform xyzTransform = XYZ_TRANSFORMS[face];
        double x = xyzTransform.uvToX(u, v);
        double y = xyzTransform.uvToY(u, v);
        double z = xyzTransform.uvToZ(u, v);

        int newFace = xyzToFace(x, y, z);
        UvTransform uvTransform = UV_TRANSFORMS[newFace];
        double newU = uvTransform.xyzToU(x, y, z);
        double newV = uvTransform.xyzToV(x, y, z);

        int newI = uShiftIntoI(newU);
        int newJ = vShiftIntoJ(newV);
        return fromFij(newFace, newI, newJ);
    }

    private static long fromFijSame(int face, int i, int j, boolean isSameFace) {
        if (isSameFace) {
            return fromFij(face, i, j);
        }
        return fromFijWrap(face, i, j);
    }

    /**
     * Returns the face associated with the specified (x, y, z) coordinate. For a coordinate
     * on a face boundary, the returned face is arbitrary but repeatable.
     */
    private static int xyzToFace(double x, double y, double z) {
        double absX = Math.abs(x);
        double absY = Math.abs(y);
        double absZ = Math.abs(z);
        if (absX > absY) {
            if (absX > absZ) {
                return (x < 0) ? 3 : 0;
            }
            return (z < 0) ? 5 : 2;
        }
        if (absY > absZ) {
            return (y < 0) ? 4 : 1;
        }
        return (z < 0) ? 5 : 2;
    }

    private static int uToI(double u) {
        double s;
        if (u >= 0) {
            s = 0.5 * Math.sqrt(1 + 3 * u);
        } else {
            s = 1 - 0.5 * Math.sqrt(1 - 3 * u);
        }
        return Math.max(0, Math.min(MAX_SIZE - 1, (int) Math.round(MAX_SIZE * s - 0.5)));
    }

    private static int vToJ(double v) {
        // Same calculation as uToI.
        return uToI(v);
    }

    private static int lookupBits(int i, int j, int k, int bits) {
        bits += ((i >> (k * LOOKUP_BITS)) & LOOKUP_MASK) << (LOOKUP_BITS + 2);
        bits += ((j >> (k * LOOKUP_BITS)) & LOOKUP_MASK) << 2;
        return LOOKUP_POS[bits];
    }

    private static long updateBits(long sb, int k, int bits) {
        return sb | ((((long) bits) >> 2) << ((k & 0x3) * 2 * LOOKUP_BITS));
    }

    private static int maskBits(int bits) {
        return bits & (SWAP_MASK | INVERT_MASK);
    }

    private static boolean isLeaf(long s2CellId) {
        return ((int) s2CellId & LEAF_MASK) != 0;
    }

    private static double iToU(int i) {
        int satI = Math.max(-1, Math.min(MAX_SIZE, i));
        return Math.max(
                -UV_LIMIT,
                Math.min(UV_LIMIT, ONE_OVER_MAX_SIZE * ((satI << 1) + 1 - MAX_SIZE)));
    }

    private static double jToV(int j) {
        // Same calculation as iToU.
        return iToU(j);
    }

    private static long toIjo(long s2CellId) {
        int face = getFace(s2CellId);
        int bits = face & SWAP_MASK;
        int i = 0;
        int j = 0;
        for (int k = 7; k >= 0; --k) {
            int nbits = (k == 7) ? (MAX_LEVEL - 7 * LOOKUP_BITS) : LOOKUP_BITS;
            bits += ((int) (s2CellId >>> (k * 2 * LOOKUP_BITS + 1)) & ((1 << (2 * nbits))
                    - 1)) << 2;
            bits = LOOKUP_IJ[bits];
            i += (bits >> (LOOKUP_BITS + 2)) << (k * LOOKUP_BITS);
            j += ((bits >> 2) & ((1 << LOOKUP_BITS) - 1)) << (k * LOOKUP_BITS);
            bits &= (SWAP_MASK | INVERT_MASK);
        }
        int orientation =
                ((getLowestOnBit(s2CellId) & 0x1111111111111110L) != 0) ? (bits ^ SWAP_MASK)
                        : bits;
        return (((long) i) << I_SHIFT) | (((long) j) << J_SHIFT) | orientation;
    }

    private static int ijoToI(long ijo) {
        return (int) (ijo >>> I_SHIFT);
    }

    private static int ijoToJ(long ijo) {
        return (int) ((ijo >>> J_SHIFT) & J_MASK);
    }

    private static int uShiftIntoI(double u) {
        double s = 0.5 * (u + 1);
        return Math.max(0, Math.min(MAX_SIZE - 1, (int) Math.round(MAX_SIZE * s - 0.5)));
    }

    private static int vShiftIntoJ(double v) {
        // Same calculation as uShiftIntoI.
        return uShiftIntoI(v);
    }

    private static int levelToSizeIj(int level) {
        return 1 << (MAX_LEVEL - level);
    }

    private static void initLookupCells() {
        initLookupCell(0, 0, 0, 0, 0, 0);
        initLookupCell(0, 0, 0, SWAP_MASK, 0, SWAP_MASK);
        initLookupCell(0, 0, 0, INVERT_MASK, 0, INVERT_MASK);
        initLookupCell(0, 0, 0, SWAP_MASK | INVERT_MASK, 0, SWAP_MASK | INVERT_MASK);
    }

    private static void initLookupCell(
            int level, int i, int j, int origOrientation, int pos, int orientation) {
        if (level == LOOKUP_BITS) {
            int ij = (i << LOOKUP_BITS) + j;
            LOOKUP_POS[(ij << 2) + origOrientation] = (pos << 2) + orientation;
            LOOKUP_IJ[(pos << 2) + origOrientation] = (ij << 2) + orientation;
        } else {
            level++;
            i <<= 1;
            j <<= 1;
            pos <<= 2;
            for (int subPos = 0; subPos < 4; subPos++) {
                int ij = POS_TO_IJ[orientation][subPos];
                int orientationMask = POS_TO_ORIENTATION[subPos];
                initLookupCell(
                        level,
                        i + (ij >>> 1),
                        j + (ij & 0x1),
                        origOrientation,
                        pos + subPos,
                        orientation ^ orientationMask);
            }
        }
    }

    private static double calculateUvLimit() {
        double machEps = 1.0;
        do {
            machEps /= 2.0f;
        } while ((1.0 + (machEps / 2.0)) != 1.0);
        return 1.0 + machEps;
    }

    @NonNull
    private static UvTransform[] createUvTransforms() {
        UvTransform[] uvTransforms = new UvTransform[NUM_FACES];
        uvTransforms[0] =
                new UvTransform() {

                    @Override
                    public double xyzToU(double x, double y, double z) {
                        return y / x;
                    }

                    @Override
                    public double xyzToV(double x, double y, double z) {
                        return z / x;
                    }
                };
        uvTransforms[1] =
                new UvTransform() {

                    @Override
                    public double xyzToU(double x, double y, double z) {
                        return -x / y;
                    }

                    @Override
                    public double xyzToV(double x, double y, double z) {
                        return z / y;
                    }
                };
        uvTransforms[2] =
                new UvTransform() {

                    @Override
                    public double xyzToU(double x, double y, double z) {
                        return -x / z;
                    }

                    @Override
                    public double xyzToV(double x, double y, double z) {
                        return -y / z;
                    }
                };
        uvTransforms[3] =
                new UvTransform() {

                    @Override
                    public double xyzToU(double x, double y, double z) {
                        return z / x;
                    }

                    @Override
                    public double xyzToV(double x, double y, double z) {
                        return y / x;
                    }
                };
        uvTransforms[4] =
                new UvTransform() {

                    @Override
                    public double xyzToU(double x, double y, double z) {
                        return z / y;
                    }

                    @Override
                    public double xyzToV(double x, double y, double z) {
                        return -x / y;
                    }
                };
        uvTransforms[5] =
                new UvTransform() {

                    @Override
                    public double xyzToU(double x, double y, double z) {
                        return -y / z;
                    }

                    @Override
                    public double xyzToV(double x, double y, double z) {
                        return -x / z;
                    }
                };
        return uvTransforms;
    }

    @NonNull
    private static XyzTransform[] createXyzTransforms() {
        XyzTransform[] xyzTransforms = new XyzTransform[NUM_FACES];
        xyzTransforms[0] =
                new XyzTransform() {

                    @Override
                    public double uvToX(double u, double v) {
                        return 1;
                    }

                    @Override
                    public double uvToY(double u, double v) {
                        return u;
                    }

                    @Override
                    public double uvToZ(double u, double v) {
                        return v;
                    }
                };
        xyzTransforms[1] =
                new XyzTransform() {

                    @Override
                    public double uvToX(double u, double v) {
                        return -u;
                    }

                    @Override
                    public double uvToY(double u, double v) {
                        return 1;
                    }

                    @Override
                    public double uvToZ(double u, double v) {
                        return v;
                    }
                };
        xyzTransforms[2] =
                new XyzTransform() {

                    @Override
                    public double uvToX(double u, double v) {
                        return -u;
                    }

                    @Override
                    public double uvToY(double u, double v) {
                        return -v;
                    }

                    @Override
                    public double uvToZ(double u, double v) {
                        return 1;
                    }
                };
        xyzTransforms[3] =
                new XyzTransform() {

                    @Override
                    public double uvToX(double u, double v) {
                        return -1;
                    }

                    @Override
                    public double uvToY(double u, double v) {
                        return -v;
                    }

                    @Override
                    public double uvToZ(double u, double v) {
                        return -u;
                    }
                };
        xyzTransforms[4] =
                new XyzTransform() {

                    @Override
                    public double uvToX(double u, double v) {
                        return v;
                    }

                    @Override
                    public double uvToY(double u, double v) {
                        return -1;
                    }

                    @Override
                    public double uvToZ(double u, double v) {
                        return -u;
                    }
                };
        xyzTransforms[5] =
                new XyzTransform() {

                    @Override
                    public double uvToX(double u, double v) {
                        return v;
                    }

                    @Override
                    public double uvToY(double u, double v) {
                        return u;
                    }

                    @Override
                    public double uvToZ(double u, double v) {
                        return -1;
                    }
                };
        return xyzTransforms;
    }

    /**
     * Transform from (x, y, z) coordinates to (u, v) coordinates, indexed by face. For a
     * (x, y, z) coordinate within a face, each element of the resulting (u, v) coordinate
     * should lie in the inclusive range [-1, 1], with the face center having a (u, v)
     * coordinate equal to (0, 0).
     */
    private interface UvTransform {

        /**
         * Returns for the specified (x, y, z) coordinate the corresponding u-coordinate
         * (which may lie outside the range [-1, 1]).
         */
        double xyzToU(double x, double y, double z);

        /**
         * Returns for the specified (x, y, z) coordinate the corresponding v-coordinate
         * (which may lie outside the range [-1, 1]).
         */
        double xyzToV(double x, double y, double z);
    }

    /**
     * Transform from (u, v) coordinates to (x, y, z) coordinates, indexed by face. The
     * resulting vectors are not necessarily of unit length.
     */
    private interface XyzTransform {

        /** Returns for the specified (u, v) coordinate the corresponding x-coordinate. */
        double uvToX(double u, double v);

        /** Returns for the specified (u, v) coordinate the corresponding y-coordinate. */
        double uvToY(double u, double v);

        /** Returns for the specified (u, v) coordinate the corresponding z-coordinate. */
        double uvToZ(double u, double v);
    }
}
