/*
 * Copyright (C) 2006 The Android Open Source Project
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

import android.os.Parcel;
import android.os.Parcelable;
import android.util.Pools.SynchronizedPool;

public class Region implements Parcelable {

    private static final int MAX_POOL_SIZE = 10;

    private static final SynchronizedPool<Region> sPool =
            new SynchronizedPool<Region>(MAX_POOL_SIZE);

    /**
     * @hide
     */
    public final int mNativeRegion;

    // the native values for these must match up with the enum in SkRegion.h
    public enum Op {
        DIFFERENCE(0),
        INTERSECT(1),
        UNION(2),
        XOR(3),
        REVERSE_DIFFERENCE(4),
        REPLACE(5);

        Op(int nativeInt) {
            this.nativeInt = nativeInt;
        }

        /**
         * @hide
         */
        public final int nativeInt;
    }

    /** Create an empty region
    */
    public Region() {
        this(nativeConstructor());
    }

    /** Return a copy of the specified region
    */
    public Region(Region region) {
        this(nativeConstructor());
        nativeSetRegion(mNativeRegion, region.mNativeRegion);
    }

    /** Return a region set to the specified rectangle
    */
    public Region(Rect r) {
        mNativeRegion = nativeConstructor();
        nativeSetRect(mNativeRegion, r.left, r.top, r.right, r.bottom);
    }

    /** Return a region set to the specified rectangle
    */
    public Region(int left, int top, int right, int bottom) {
        mNativeRegion = nativeConstructor();
        nativeSetRect(mNativeRegion, left, top, right, bottom);
    }

    /** Set the region to the empty region
    */
    public void setEmpty() {
        nativeSetRect(mNativeRegion, 0, 0, 0, 0);
    }

    /** Set the region to the specified region.
    */
    public boolean set(Region region) {
        nativeSetRegion(mNativeRegion, region.mNativeRegion);
        return true;
    }

    /** Set the region to the specified rectangle
    */
    public boolean set(Rect r) {
        return nativeSetRect(mNativeRegion, r.left, r.top, r.right, r.bottom);
    }
    
    /** Set the region to the specified rectangle
    */
    public boolean set(int left, int top, int right, int bottom) {
        return nativeSetRect(mNativeRegion, left, top, right, bottom);
    }

    /**
     * Set the region to the area described by the path and clip.
     * Return true if the resulting region is non-empty. This produces a region
     * that is identical to the pixels that would be drawn by the path
     * (with no antialiasing).
     */
    public boolean setPath(Path path, Region clip) {
        return nativeSetPath(mNativeRegion, path.ni(), clip.mNativeRegion);
    }

    /**
     * Return true if this region is empty
     */
    public native boolean isEmpty();
    
    /**
     * Return true if the region contains a single rectangle
     */
    public native boolean isRect();
    
    /**
     * Return true if the region contains more than one rectangle
     */
    public native boolean isComplex();

    /**
     * Return a new Rect set to the bounds of the region. If the region is
     * empty, the Rect will be set to [0, 0, 0, 0]
     */
    public Rect getBounds() {
        Rect r = new Rect();
        nativeGetBounds(mNativeRegion, r);
        return r;
    }
    
    /**
     * Set the Rect to the bounds of the region. If the region is empty, the
     * Rect will be set to [0, 0, 0, 0]
     */
    public boolean getBounds(Rect r) {
        if (r == null) {
            throw new NullPointerException();
        }
        return nativeGetBounds(mNativeRegion, r);
    }

    /**
     * Return the boundary of the region as a new Path. If the region is empty,
     * the path will also be empty.
     */
    public Path getBoundaryPath() {
        Path path = new Path();
        nativeGetBoundaryPath(mNativeRegion, path.ni());
        return path;
    }

    /**
     * Set the path to the boundary of the region. If the region is empty, the
     * path will also be empty.
     */
    public boolean getBoundaryPath(Path path) {
        return nativeGetBoundaryPath(mNativeRegion, path.ni());
    }
        
    /**
     * Return true if the region contains the specified point
     */
    public native boolean contains(int x, int y);

    /**
     * Return true if the region is a single rectangle (not complex) and it
     * contains the specified rectangle. Returning false is not a guarantee
     * that the rectangle is not contained by this region, but return true is a
     * guarantee that the rectangle is contained by this region.
     */
    public boolean quickContains(Rect r) {
        return quickContains(r.left, r.top, r.right, r.bottom);
    }

    /**
     * Return true if the region is a single rectangle (not complex) and it
     * contains the specified rectangle. Returning false is not a guarantee
     * that the rectangle is not contained by this region, but return true is a
     * guarantee that the rectangle is contained by this region.
     */
    public native boolean quickContains(int left, int top, int right,
                                        int bottom);

    /**
     * Return true if the region is empty, or if the specified rectangle does
     * not intersect the region. Returning false is not a guarantee that they
     * intersect, but returning true is a guarantee that they do not.
     */
    public boolean quickReject(Rect r) {
        return quickReject(r.left, r.top, r.right, r.bottom);
    }

    /**
     * Return true if the region is empty, or if the specified rectangle does
     * not intersect the region. Returning false is not a guarantee that they
     * intersect, but returning true is a guarantee that they do not.
     */
    public native boolean quickReject(int left, int top, int right, int bottom);

    /**
     * Return true if the region is empty, or if the specified region does not
     * intersect the region. Returning false is not a guarantee that they
     * intersect, but returning true is a guarantee that they do not.
     */
    public native boolean quickReject(Region rgn);

    /**
     * Translate the region by [dx, dy]. If the region is empty, do nothing.
     */
    public void translate(int dx, int dy) {
        translate(dx, dy, null);
    }

    /**
     * Set the dst region to the result of translating this region by [dx, dy].
     * If this region is empty, then dst will be set to empty.
     */
    public native void translate(int dx, int dy, Region dst);

    /**
     * Scale the region by the given scale amount. This re-constructs new region by
     * scaling the rects that this region consists of. New rectis are computed by scaling 
     * coordinates by float, then rounded by roundf() function to integers. This may results
     * in less internal rects if 0 < scale < 1. Zero and Negative scale result in
     * an empty region. If this region is empty, do nothing.
     *
     * @hide
     */
    public void scale(float scale) {
        scale(scale, null);
    }

    /**
     * Set the dst region to the result of scaling this region by the given scale amount.
     * If this region is empty, then dst will be set to empty.
     * @hide
     */
    public native void scale(float scale, Region dst);

    public final boolean union(Rect r) {
        return op(r, Op.UNION);
    }

    /**
     * Perform the specified Op on this region and the specified rect. Return
     * true if the result of the op is not empty.
     */
    public boolean op(Rect r, Op op) {
        return nativeOp(mNativeRegion, r.left, r.top, r.right, r.bottom,
                        op.nativeInt);
    }

    /**
     * Perform the specified Op on this region and the specified rect. Return
     * true if the result of the op is not empty.
     */
    public boolean op(int left, int top, int right, int bottom, Op op) {
        return nativeOp(mNativeRegion, left, top, right, bottom,
                        op.nativeInt);
    }

    /**
     * Perform the specified Op on this region and the specified region. Return
     * true if the result of the op is not empty.
     */
    public boolean op(Region region, Op op) {
        return op(this, region, op);
    }

    /**
     * Set this region to the result of performing the Op on the specified rect
     * and region. Return true if the result is not empty.
     */
    public boolean op(Rect rect, Region region, Op op) {
        return nativeOp(mNativeRegion, rect, region.mNativeRegion,
                        op.nativeInt);
    }

    /**
     * Set this region to the result of performing the Op on the specified
     * regions. Return true if the result is not empty.
     */
    public boolean op(Region region1, Region region2, Op op) {
        return nativeOp(mNativeRegion, region1.mNativeRegion,
                        region2.mNativeRegion, op.nativeInt);
    }

    public String toString() {
        return nativeToString(mNativeRegion);
    }

    /**
     * @return An instance from a pool if such or a new one.
     *
     * @hide
     */
    public static Region obtain() {
        Region region = sPool.acquire();
        return (region != null) ? region : new Region();
    }

    /**
     * @return An instance from a pool if such or a new one.
     *
     * @param other Region to copy values from for initialization.
     *
     * @hide
     */
    public static Region obtain(Region other) {
        Region region = obtain();
        region.set(other);
        return region;
    }

    /**
     * Recycles an instance.
     *
     * @hide
     */
    public void recycle() {
        setEmpty();
        sPool.release(this);
    }

    //////////////////////////////////////////////////////////////////////////
    
    public static final Parcelable.Creator<Region> CREATOR
        = new Parcelable.Creator<Region>() {
            /**
            * Rebuild a Region previously stored with writeToParcel().
             * @param p    Parcel object to read the region from
             * @return a new region created from the data in the parcel
             */
            public Region createFromParcel(Parcel p) {
                int ni = nativeCreateFromParcel(p);
                if (ni == 0) {
                    throw new RuntimeException();
                }
                return new Region(ni);
            }
            public Region[] newArray(int size) {
                return new Region[size];
            }
    };
    
    public int describeContents() {
        return 0;
    }

    /**
     * Write the region and its pixels to the parcel. The region can be
     * rebuilt from the parcel by calling CREATOR.createFromParcel().
     * @param p    Parcel object to write the region data into
     */
    public void writeToParcel(Parcel p, int flags) {
        if (!nativeWriteToParcel(mNativeRegion, p)) {
            throw new RuntimeException();
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof Region)) {
            return false;
        }
        Region peer = (Region) obj;
        return nativeEquals(mNativeRegion, peer.mNativeRegion);
    }

    protected void finalize() throws Throwable {
        try {
            nativeDestructor(mNativeRegion);
        } finally {
            super.finalize();
        }
    }
    
    Region(int ni) {
        if (ni == 0) {
            throw new RuntimeException();
        }
        mNativeRegion = ni;
    }

    /* add dummy parameter so constructor can be called from jni without
       triggering 'not cloneable' exception */
    private Region(int ni, int dummy) {
        this(ni);
    }

    final int ni() {
        return mNativeRegion;
    }

    private static native boolean nativeEquals(int native_r1, int native_r2);

    private static native int nativeConstructor();
    private static native void nativeDestructor(int native_region);

    private static native void nativeSetRegion(int native_dst, int native_src);
    private static native boolean nativeSetRect(int native_dst, int left,
                                                int top, int right, int bottom);
    private static native boolean nativeSetPath(int native_dst, int native_path,
                                                int native_clip);
    private static native boolean nativeGetBounds(int native_region, Rect rect);
    private static native boolean nativeGetBoundaryPath(int native_region,
                                                        int native_path);

    private static native boolean nativeOp(int native_dst, int left, int top,
                                           int right, int bottom, int op);
    private static native boolean nativeOp(int native_dst, Rect rect,
                                           int native_region, int op);
    private static native boolean nativeOp(int native_dst, int native_region1,
                                           int native_region2, int op);

    private static native int nativeCreateFromParcel(Parcel p);
    private static native boolean nativeWriteToParcel(int native_region,
                                                      Parcel p);

    private static native String nativeToString(int native_region);
}
