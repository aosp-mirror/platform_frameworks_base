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
 * limitations under the License.
 */

package android.graphics;

import com.android.ide.common.rendering.api.LayoutLog;
import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.impl.DelegateManager;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;

/**
 * Delegate implementing the native methods of {@link android.graphics.PathMeasure}
 * <p/>
 * Through the layoutlib_create tool, the original native methods of PathMeasure have been
 * replaced by
 * calls to methods of the same name in this delegate class.
 * <p/>
 * This class behaves like the original native implementation, but in Java, keeping previously
 * native data into its own objects and mapping them to int that are sent back and forth between it
 * and the original PathMeasure class.
 *
 * @see DelegateManager
 */
public final class PathMeasure_Delegate {
    // ---- delegate manager ----
    private static final DelegateManager<PathMeasure_Delegate> sManager =
            new DelegateManager<PathMeasure_Delegate>(PathMeasure_Delegate.class);

    // ---- delegate data ----
    // This governs how accurate the approximation of the Path is.
    private static final float PRECISION = 0.002f;

    /**
     * Array containing the path points components. There are three components for each point:
     * <ul>
     *     <li>Fraction along the length of the path that the point resides</li>
     *     <li>The x coordinate of the point</li>
     *     <li>The y coordinate of the point</li>
     * </ul>
     */
    private float mPathPoints[];
    private long mNativePath;

    private PathMeasure_Delegate(long native_path, boolean forceClosed) {
        mNativePath = native_path;
        if (forceClosed && mNativePath != 0) {
            // Copy the path and call close
            mNativePath = Path_Delegate.init2(native_path);
            Path_Delegate.native_close(mNativePath);
        }

        mPathPoints =
                mNativePath != 0 ? Path_Delegate.native_approximate(mNativePath, PRECISION) : null;
    }

    @LayoutlibDelegate
    /*package*/ static long native_create(long native_path, boolean forceClosed) {
        return sManager.addNewDelegate(new PathMeasure_Delegate(native_path, forceClosed));
    }

    @LayoutlibDelegate
    /*package*/ static void native_destroy(long native_instance) {
        sManager.removeJavaReferenceFor(native_instance);
    }

    @LayoutlibDelegate
    /*package*/ static boolean native_getPosTan(long native_instance, float distance, float pos[],
            float tan[]) {
        Bridge.getLog().fidelityWarning(LayoutLog.TAG_UNSUPPORTED,
                "PathMeasure.getPostTan is not supported.", null, null);
        return false;
    }

    @LayoutlibDelegate
    /*package*/ static boolean native_getMatrix(long native_instance, float distance, long
            native_matrix, int flags) {
        Bridge.getLog().fidelityWarning(LayoutLog.TAG_UNSUPPORTED,
                "PathMeasure.getMatrix is not supported.", null, null);
        return false;
    }

    @LayoutlibDelegate
    /*package*/ static boolean native_nextContour(long native_instance) {
        Bridge.getLog().fidelityWarning(LayoutLog.TAG_UNSUPPORTED,
                "PathMeasure.nextContour is not supported.", null, null);
        return false;
    }

    @LayoutlibDelegate
    /*package*/ static void native_setPath(long native_instance, long native_path, boolean
            forceClosed) {
        PathMeasure_Delegate pathMeasure = sManager.getDelegate(native_instance);
        assert pathMeasure != null;

        if (forceClosed && native_path != 0) {
            // Copy the path and call close
            native_path = Path_Delegate.init2(native_path);
            Path_Delegate.native_close(native_path);
        }
        pathMeasure.mNativePath = native_path;
        pathMeasure.mPathPoints = Path_Delegate.native_approximate(native_path, PRECISION);
    }

    @LayoutlibDelegate
    /*package*/ static float native_getLength(long native_instance) {
        PathMeasure_Delegate pathMeasure = sManager.getDelegate(native_instance);
        assert pathMeasure != null;

        if (pathMeasure.mPathPoints == null) {
            return 0;
        }

        float length = 0;
        int nPoints = pathMeasure.mPathPoints.length / 3;
        for (int i = 1; i < nPoints; i++) {
            length += Point2D.distance(
                    pathMeasure.mPathPoints[(i - 1) * 3 + 1],
                    pathMeasure.mPathPoints[(i - 1) * 3 + 2],
                    pathMeasure.mPathPoints[i*3 + 1],
                    pathMeasure.mPathPoints[i*3 + 2]);
        }

        return length;
    }

    @LayoutlibDelegate
    /*package*/ static boolean native_isClosed(long native_instance) {
        PathMeasure_Delegate pathMeasure = sManager.getDelegate(native_instance);
        assert pathMeasure != null;

        Path_Delegate path = Path_Delegate.getDelegate(pathMeasure.mNativePath);
        if (path == null) {
            return false;
        }

        PathIterator pathIterator = path.getJavaShape().getPathIterator(null);

        int type = 0;
        float segment[] = new float[6];
        while (!pathIterator.isDone()) {
            type = pathIterator.currentSegment(segment);
            pathIterator.next();
        }

        // A path is a closed path if the last element is SEG_CLOSE
        return type == PathIterator.SEG_CLOSE;
    }

    @LayoutlibDelegate
    /*package*/ static boolean native_getSegment(long native_instance, float startD, float stopD,
            long native_dst_path, boolean startWithMoveTo) {
        if (startD < 0) {
            startD = 0;
        }

        if (startD >= stopD) {
            return false;
        }

        PathMeasure_Delegate pathMeasure = sManager.getDelegate(native_instance);
        assert pathMeasure != null;

        if (pathMeasure.mPathPoints == null) {
            return false;
        }

        float accLength = 0;
        boolean isZeroLength = true; // Whether the output has zero length or not
        int nPoints = pathMeasure.mPathPoints.length / 3;
        for (int i = 0; i < nPoints; i++) {
            float x = pathMeasure.mPathPoints[i * 3 + 1];
            float y = pathMeasure.mPathPoints[i * 3 + 2];
            if (accLength >= startD && accLength <= stopD) {
                if (startWithMoveTo) {
                    startWithMoveTo = false;
                    Path_Delegate.native_moveTo(native_dst_path, x, y);
                } else {
                    isZeroLength = false;
                    Path_Delegate.native_lineTo(native_dst_path, x, y);
                }
            }

            if (i > 0) {
                accLength += Point2D.distance(
                        pathMeasure.mPathPoints[(i - 1) * 3 + 1],
                        pathMeasure.mPathPoints[(i - 1) * 3 + 2],
                        pathMeasure.mPathPoints[i * 3 + 1],
                        pathMeasure.mPathPoints[i * 3 + 2]);
            }
        }

        return !isZeroLength;
    }
}
