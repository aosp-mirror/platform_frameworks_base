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
import com.android.layoutlib.bridge.util.CachedPathIteratorFactory;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import com.android.layoutlib.bridge.util.CachedPathIteratorFactory.CachedPathIterator;

import java.awt.geom.PathIterator;

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
    private CachedPathIteratorFactory mOriginalPathIterator;

    private long mNativePath;


    private PathMeasure_Delegate(long native_path, boolean forceClosed) {
        mNativePath = native_path;
        if (native_path != 0) {
            if (forceClosed) {
                // Copy the path and call close
                native_path = Path_Delegate.init2(native_path);
                Path_Delegate.native_close(native_path);
            }

            Path_Delegate pathDelegate = Path_Delegate.getDelegate(native_path);
            mOriginalPathIterator = new CachedPathIteratorFactory(pathDelegate.getJavaShape()
                    .getPathIterator(null));
        }
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

        if (native_path != 0) {
            if (forceClosed) {
                // Copy the path and call close
                native_path = Path_Delegate.init2(native_path);
                Path_Delegate.native_close(native_path);
            }

            Path_Delegate pathDelegate = Path_Delegate.getDelegate(native_path);
            pathMeasure.mOriginalPathIterator = new CachedPathIteratorFactory(pathDelegate.getJavaShape()
                    .getPathIterator(null));
        }

        pathMeasure.mNativePath = native_path;
    }

    @LayoutlibDelegate
    /*package*/ static float native_getLength(long native_instance) {
        PathMeasure_Delegate pathMeasure = sManager.getDelegate(native_instance);
        assert pathMeasure != null;

        if (pathMeasure.mOriginalPathIterator == null) {
            return 0;
        }

        return pathMeasure.mOriginalPathIterator.iterator().getTotalLength();
    }

    @LayoutlibDelegate
    /*package*/ static boolean native_isClosed(long native_instance) {
        PathMeasure_Delegate pathMeasure = sManager.getDelegate(native_instance);
        assert pathMeasure != null;

        Path_Delegate path = Path_Delegate.getDelegate(pathMeasure.mNativePath);
        if (path == null) {
            return false;
        }

        int type = 0;
        float segment[] = new float[6];
        for (PathIterator pi = path.getJavaShape().getPathIterator(null); !pi.isDone(); pi.next()) {
            type = pi.currentSegment(segment);
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

        CachedPathIterator iterator = pathMeasure.mOriginalPathIterator.iterator();
        float accLength = startD;
        boolean isZeroLength = true; // Whether the output has zero length or not
        float[] points = new float[6];

        iterator.jumpToSegment(accLength);
        while (!iterator.isDone() && (stopD - accLength > 0.1f)) {
            int type = iterator.currentSegment(points, stopD - accLength);

            if (accLength - iterator.getCurrentSegmentLength() <= stopD) {
                if (startWithMoveTo) {
                    startWithMoveTo = false;

                    // If this segment is a MOVETO, then we just use that one. If not, then we issue
                    // a first moveto
                    if (type != PathIterator.SEG_MOVETO) {
                        float[] lastPoint = new float[2];
                        iterator.getCurrentSegmentEnd(lastPoint);
                        Path_Delegate.native_moveTo(native_dst_path, lastPoint[0], lastPoint[1]);
                    }
                }

                isZeroLength = isZeroLength && iterator.getCurrentSegmentLength() > 0;
                switch (type) {
                    case PathIterator.SEG_MOVETO:
                        Path_Delegate.native_moveTo(native_dst_path, points[0], points[1]);
                        break;
                    case PathIterator.SEG_LINETO:
                        Path_Delegate.native_lineTo(native_dst_path, points[0], points[1]);
                        break;
                    case PathIterator.SEG_CLOSE:
                        Path_Delegate.native_close(native_dst_path);
                        break;
                    case PathIterator.SEG_CUBICTO:
                        Path_Delegate.native_cubicTo(native_dst_path, points[0], points[1],
                                points[2], points[3],
                                points[4], points[5]);
                        break;
                    case PathIterator.SEG_QUADTO:
                        Path_Delegate.native_quadTo(native_dst_path, points[0], points[1],
                                points[2],
                                points[3]);
                        break;
                    default:
                        assert false;
                }
            }

            accLength += iterator.getCurrentSegmentLength();
            iterator.next();
        }

        return !isZeroLength;
    }
}
