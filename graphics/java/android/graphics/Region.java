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

import android.annotation.NonNull;
import android.compat.annotation.UnsupportedAppUsage;
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
    @UnsupportedAppUsage
    public long mNativeRegion;

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
        @UnsupportedAppUsage
        public final int nativeInt;
    }

    /** Create an empty region
    */
    public Region() {
        this(nativeConstructor());
    }

    /** Return a copy of the specified region
    */
    public Region(@NonNull Region region) {
        this(nativeConstructor());
        nativeSetRegion(mNativeRegion, region.mNativeRegion);
    }

    /** Return a region set to the specified rectangle
    */
    public Region(@NonNull Rect r) {
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
    public boolean set(@NonNull Region region) {
        nativeSetRegion(mNativeRegion, region.mNativeRegion);
        return true;
    }

    /** Set the region to the specified rectangle
    */
    public boolean set(@NonNull Rect r) {
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
    public boolean setPath(@NonNull Path path, @NonNull Region clip) {
        return nativeSetPath(mNativeRegion, path.readOnlyNI(), clip.mNativeRegion);
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
    @NonNull
    public Rect getBounds() {
        Rect r = new Rect();
        nativeGetBounds(mNativeRegion, r);
        return r;
    }
    
    /**
     * Set the Rect to the bounds of the region. If the region is empty, the
     * Rect will be set to [0, 0, 0, 0]
     */
    public boolean getBounds(@NonNull Rect r) {
        if (r == null) {
            throw new NullPointerException();
        }
        return nativeGetBounds(mNativeRegion, r);
    }

    /**
     * Return the boundary of the region as a new Path. If the region is empty,
     * the path will also be empty.
     */
    @NonNull
    public Path getBoundaryPath() {
        Path path = new Path();
        nativeGetBoundaryPath(mNativeRegion, path.mutateNI());
        return path;
    }

    /**
     * Set the path to the boundary of the region. If the region is empty, the
     * path will also be empty.
     */
    public boolean getBoundaryPath(@NonNull Path path) {
        return nativeGetBoundaryPath(mNativeRegion, path.mutateNI());
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
    public boolean quickContains(@NonNull Rect r) {
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
    public boolean quickReject(@NonNull Rect r) {
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
    @UnsupportedAppUsage
    public void scale(float scale) {
        scale(scale, null);
    }

    /**
     * Set the dst region to the result of scaling this region by the given scale amount.
     * If this region is empty, then dst will be set to empty.
     * @hide
     */
    public native void scale(float scale, Region dst);

    public final boolean union(@NonNull Rect r) {
        return op(r, Op.UNION);
    }

    /**
     * Perform the specified Op on this region and the specified rect. Return
     * true if the result of the op is not empty.
     */
    public boolean op(@NonNull Rect r, @NonNull Op op) {
        return nativeOp(mNativeRegion, r.left, r.top, r.right, r.bottom,
                        op.nativeInt);
    }

    /**
     * Perform the specified Op on this region and the specified rect. Return
     * true if the result of the op is not empty.
     */
    public boolean op(int left, int top, int right, int bottom, @NonNull Op op) {
        return nativeOp(mNativeRegion, left, top, right, bottom,
                        op.nativeInt);
    }

    /**
     * Perform the specified Op on this region and the specified region. Return
     * true if the result of the op is not empty.
     */
    public boolean op(@NonNull Region region, @NonNull Op op) {
        return op(this, region, op);
    }

    /**
     * Set this region to the result of performing the Op on the specified rect
     * and region. Return true if the result is not empty.
     */
    public boolean op(@NonNull Rect rect, @NonNull Region region, @NonNull Op op) {
        return nativeOp(mNativeRegion, rect, region.mNativeRegion,
                        op.nativeInt);
    }

    /**
     * Set this region to the result of performing the Op on the specified
     * regions. Return true if the result is not empty.
     */
    public boolean op(@NonNull Region region1, @NonNull Region region2, @NonNull Op op) {
        return nativeOp(mNativeRegion, region1.mNativeRegion,
                        region2.mNativeRegion, op.nativeInt);
    }

    @Override
    public String toString() {
        return nativeToString(mNativeRegion);
    }

    /**
     * @return An instance from a pool if such or a new one.
     *
     * @hide
     */
    @NonNull
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
    @NonNull
    public static Region obtain(@NonNull Region other) {
        Region region = obtain();
        region.set(other);
        return region;
    }

    /**
     * Recycles an instance.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public void recycle() {
        setEmpty();
        sPool.release(this);
    }

    //////////////////////////////////////////////////////////////////////////
    
    public static final @android.annotation.NonNull Parcelable.Creator<Region> CREATOR
        = new Parcelable.Creator<Region>() {
            /**
            * Rebuild a Region previously stored with writeToParcel().
             * @param p    Parcel object to read the region from
             * @return a new region created from the data in the parcel
             */
            @Override
            public Region createFromParcel(Parcel p) {
                long ni = nativeCreateFromParcel(p);
                if (ni == 0) {
                    throw new RuntimeException();
                }
                return new Region(ni);
            }
            @Override
            public Region[] newArray(int size) {
                return new Region[size];
            }
    };
    
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Write the region and its pixels to the parcel. The region can be
     * rebuilt from the parcel by calling CREATOR.createFromParcel().
     * @param p    Parcel object to write the region data into
     */
    @Override
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

    @Override
    protected void finalize() throws Throwable {
        try {
            nativeDestructor(mNativeRegion);
            mNativeRegion = 0;
        } finally {
            super.finalize();
        }
    }
    
    Region(long ni) {
        if (ni == 0) {
            throw new RuntimeException();
        }
        mNativeRegion = ni;
    }

    /* add dummy parameter so constructor can be called from jni without
       triggering 'not cloneable' exception */
    @UnsupportedAppUsage
    private Region(long ni, int dummy) {
        this(ni);
    }

    final long ni() {
        return mNativeRegion;
    }

    private static native boolean nativeEquals(long native_r1, long native_r2);

    private static native long nativeConstructor();
    private static native void nativeDestructor(long native_region);

    private static native void nativeSetRegion(long native_dst, long native_src);
    private static native boolean nativeSetRect(long native_dst, int left,
                                                int top, int right, int bottom);
    private static native boolean nativeSetPath(long native_dst, long native_path,
                                                long native_clip);
    private static native boolean nativeGetBounds(long native_region, Rect rect);
    private static native boolean nativeGetBoundaryPath(long native_region,
                                                        long native_path);

    private static native boolean nativeOp(long native_dst, int left, int top,
                                           int right, int bottom, int op);
    private static native boolean nativeOp(long native_dst, Rect rect,
                                           long native_region, int op);
    private static native boolean nativeOp(long native_dst, long native_region1,
                                           long native_region2, int op);

    private static native long nativeCreateFromParcel(Parcel p);
    private static native boolean nativeWriteToParcel(long native_region,
                                                      Parcel p);

    private static native String nativeToString(long native_region);
}
