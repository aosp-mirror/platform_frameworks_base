/*
 * Copyright (C) 2007 The Android Open Source Project
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

public class PathMeasure {
    private Path mPath;

    /**
     * Create an empty PathMeasure object. To uses this to measure the length
     * of a path, and/or to find the position and tangent along it, call
     * setPath.
     *
     * Note that once a path is associated with the measure object, it is
     * undefined if the path is subsequently modified and the the measure object
     * is used. If the path is modified, you must call setPath with the path.
     */
    public PathMeasure() {
        mPath = null;
        native_instance = native_create(0, false);
    }
    
    /**
     * Create a PathMeasure object associated with the specified path object
     * (already created and specified). The measure object can now return the
     * path's length, and the position and tangent of any position along the
     * path.
     *
     * Note that once a path is associated with the measure object, it is
     * undefined if the path is subsequently modified and the the measure object
     * is used. If the path is modified, you must call setPath with the path.
     *
     * @param path The path that will be measured by this object
     * @param forceClosed If true, then the path will be considered as "closed"
     *        even if its contour was not explicitly closed.
     */
    public PathMeasure(Path path, boolean forceClosed) {
        // The native implementation does not copy the path, prevent it from being GC'd
        mPath = path;
        native_instance = native_create(path != null ? path.readOnlyNI() : 0,
                                        forceClosed);
    }

    /**
     * Assign a new path, or null to have none.
     */
    public void setPath(Path path, boolean forceClosed) {
        mPath = path;
        native_setPath(native_instance,
                       path != null ? path.readOnlyNI() : 0,
                       forceClosed);
    }

    /**
     * Return the total length of the current contour, or 0 if no path is
     * associated with this measure object.
     */
    public float getLength() {
        return native_getLength(native_instance);
    }

    /**
     * Pins distance to 0 <= distance <= getLength(), and then computes the
     * corresponding position and tangent. Returns false if there is no path,
     * or a zero-length path was specified, in which case position and tangent
     * are unchanged.
     *
     * @param distance The distance along the current contour to sample
     * @param pos If not null, eturns the sampled position (x==[0], y==[1])
     * @param tan If not null, returns the sampled tangent (x==[0], y==[1])
     * @return false if there was no path associated with this measure object
    */
    public boolean getPosTan(float distance, float pos[], float tan[]) {
        if (pos != null && pos.length < 2 ||
            tan != null && tan.length < 2) {
            throw new ArrayIndexOutOfBoundsException();
        }
        return native_getPosTan(native_instance, distance, pos, tan);
    }

    public static final int POSITION_MATRIX_FLAG = 0x01;    // must match flags in SkPathMeasure.h
    public static final int TANGENT_MATRIX_FLAG  = 0x02;    // must match flags in SkPathMeasure.h

    /**
     * Pins distance to 0 <= distance <= getLength(), and then computes the
     * corresponding matrix. Returns false if there is no path, or a zero-length
     * path was specified, in which case matrix is unchanged.
     *
     * @param distance The distance along the associated path
     * @param matrix Allocated by the caller, this is set to the transformation
     *        associated with the position and tangent at the specified distance
     * @param flags Specified what aspects should be returned in the matrix.
     */
    public boolean getMatrix(float distance, Matrix matrix, int flags) {
        return native_getMatrix(native_instance, distance, matrix.native_instance, flags);
    }

    /**
     * Given a start and stop distance, return in dst the intervening
     * segment(s). If the segment is zero-length, return false, else return
     * true. startD and stopD are pinned to legal values (0..getLength()).
     * If startD >= stopD then return false (and leave dst untouched).
     * Begin the segment with a moveTo if startWithMoveTo is true.
     *
     * <p>On {@link android.os.Build.VERSION_CODES#KITKAT} and earlier
     * releases, the resulting path may not display on a hardware-accelerated
     * Canvas. A simple workaround is to add a single operation to this path,
     * such as <code>dst.rLineTo(0, 0)</code>.</p>
     */
    public boolean getSegment(float startD, float stopD, Path dst, boolean startWithMoveTo) {
        // Skia used to enforce this as part of it's API, but has since relaxed that restriction
        // so to maintain consistency in our API we enforce the preconditions here.
        float length = getLength();
        if (startD < 0) {
            startD = 0;
        }
        if (stopD > length) {
            stopD = length;
        }
        if (startD >= stopD) {
            return false;
        }

        return native_getSegment(native_instance, startD, stopD, dst.mutateNI(), startWithMoveTo);
    }

    /**
     * Return true if the current contour is closed()
     */
    public boolean isClosed() {
        return native_isClosed(native_instance);
    }

    /**
     * Move to the next contour in the path. Return true if one exists, or
     * false if we're done with the path.
     */
    public boolean nextContour() {
        return native_nextContour(native_instance);
    }

    protected void finalize() throws Throwable {
        native_destroy(native_instance);
        native_instance = 0;  // Other finalizers can still call us.
    }

    private static native long native_create(long native_path, boolean forceClosed);
    private static native void native_setPath(long native_instance, long native_path, boolean forceClosed);
    private static native float native_getLength(long native_instance);
    private static native boolean native_getPosTan(long native_instance, float distance, float pos[], float tan[]);
    private static native boolean native_getMatrix(long native_instance, float distance, long native_matrix, int flags);
    private static native boolean native_getSegment(long native_instance, float startD, float stopD, long native_path, boolean startWithMoveTo);
    private static native boolean native_isClosed(long native_instance);
    private static native boolean native_nextContour(long native_instance);
    private static native void native_destroy(long native_instance);

    /* package */private long native_instance;
}

