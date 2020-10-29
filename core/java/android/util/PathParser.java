/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package android.util;

import android.compat.annotation.UnsupportedAppUsage;
import android.graphics.Path;

import dalvik.annotation.optimization.FastNative;

/**
 * @hide
 */
public class PathParser {
    static final String LOGTAG = PathParser.class.getSimpleName();

    /**
     * @param pathString The string representing a path, the same as "d" string in svg file.
     * @return the generated Path object.
     */
    @UnsupportedAppUsage
    public static Path createPathFromPathData(String pathString) {
        if (pathString == null) {
            throw new IllegalArgumentException("Path string can not be null.");
        }
        Path path = new Path();
        nParseStringForPath(path.mNativePath, pathString, pathString.length());
        return path;
    }

    /**
     * Interpret PathData as path commands and insert the commands to the given path.
     *
     * @param data The source PathData to be converted.
     * @param outPath The Path object where path commands will be inserted.
     */
    public static void createPathFromPathData(Path outPath, PathData data) {
        nCreatePathFromPathData(outPath.mNativePath, data.mNativePathData);
    }

    /**
     * @param pathDataFrom The source path represented in PathData
     * @param pathDataTo The target path represented in PathData
     * @return whether the <code>nodesFrom</code> can morph into <code>nodesTo</code>
     */
    public static boolean canMorph(PathData pathDataFrom, PathData pathDataTo) {
        return nCanMorph(pathDataFrom.mNativePathData, pathDataTo.mNativePathData);
    }

    /**
     * PathData class is a wrapper around the native PathData object, which contains
     * the result of parsing a path string. Specifically, there are verbs and points
     * associated with each verb stored in PathData. This data can then be used to
     * generate commands to manipulate a Path.
     */
    public static class PathData {
        long mNativePathData = 0;
        public PathData() {
            mNativePathData = nCreateEmptyPathData();
        }

        public PathData(PathData data) {
            mNativePathData = nCreatePathData(data.mNativePathData);
        }

        public PathData(String pathString) {
            mNativePathData = nCreatePathDataFromString(pathString, pathString.length());
            if (mNativePathData == 0) {
                throw new IllegalArgumentException("Invalid pathData: " + pathString);
            }
        }

        public long getNativePtr() {
            return mNativePathData;
        }

        /**
         * Update the path data to match the source.
         * Before calling this, make sure canMorph(target, source) is true.
         *
         * @param source The source path represented in PathData
         */
        public void setPathData(PathData source) {
            nSetPathData(mNativePathData, source.mNativePathData);
        }

        @Override
        protected void finalize() throws Throwable {
            if (mNativePathData != 0) {
                nFinalize(mNativePathData);
                mNativePathData = 0;
            }
            super.finalize();
        }
    }

    /**
     * Interpolate between the <code>fromData</code> and <code>toData</code> according to the
     * <code>fraction</code>, and put the resulting path data into <code>outData</code>.
     *
     * @param outData The resulting PathData of the interpolation
     * @param fromData The start value as a PathData.
     * @param toData The end value as a PathData
     * @param fraction The fraction to interpolate.
     */
    public static boolean interpolatePathData(PathData outData, PathData fromData, PathData toData,
            float fraction) {
        return nInterpolatePathData(outData.mNativePathData, fromData.mNativePathData,
                toData.mNativePathData, fraction);
    }

    // Native functions are defined below.
    private static native void nParseStringForPath(long pathPtr, String pathString,
            int stringLength);
    private static native long nCreatePathDataFromString(String pathString, int stringLength);

    // ----------------- @FastNative -----------------------

    @FastNative
    private static native void nCreatePathFromPathData(long outPathPtr, long pathData);
    @FastNative
    private static native long nCreateEmptyPathData();
    @FastNative
    private static native long nCreatePathData(long nativePtr);
    @FastNative
    private static native boolean nInterpolatePathData(long outDataPtr, long fromDataPtr,
            long toDataPtr, float fraction);
    @FastNative
    private static native void nFinalize(long nativePtr);
    @FastNative
    private static native boolean nCanMorph(long fromDataPtr, long toDataPtr);
    @FastNative
    private static native void nSetPathData(long outDataPtr, long fromDataPtr);
}


