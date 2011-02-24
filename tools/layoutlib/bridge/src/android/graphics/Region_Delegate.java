/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.os.Parcel;

import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;

/**
 * Delegate implementing the native methods of android.graphics.Region
 *
 * Through the layoutlib_create tool, the original native methods of Region have been replaced
 * by calls to methods of the same name in this delegate class.
 *
 * This class behaves like the original native implementation, but in Java, keeping previously
 * native data into its own objects and mapping them to int that are sent back and forth between
 * it and the original Region class.
 *
 * This also serve as a base class for all Region delegate classes.
 *
 * @see DelegateManager
 *
 */
public class Region_Delegate {

    // ---- delegate manager ----
    protected static final DelegateManager<Region_Delegate> sManager =
            new DelegateManager<Region_Delegate>(Region_Delegate.class);

    // ---- delegate helper data ----

    // ---- delegate data ----
    private Area mArea = new Area();

    // ---- Public Helper methods ----

    public static Region_Delegate getDelegate(int nativeShader) {
        return sManager.getDelegate(nativeShader);
    }

    public Area getJavaArea() {
        return mArea;
    }

    /**
     * Combines two {@link Shape} into another one (actually an {@link Area}), according
     * to the given {@link Region.Op}.
     *
     * If the Op is not one that combines two shapes, then this return null
     *
     * @param shape1 the firt shape to combine which can be null if there's no original clip.
     * @param shape2 the 2nd shape to combine
     * @param regionOp the operande for the combine
     * @return a new area or null.
     */
    public static Area combineShapes(Shape shape1, Shape shape2, int regionOp) {
        if (regionOp == Region.Op.DIFFERENCE.nativeInt) {
            // if shape1 is null (empty), then the result is null.
            if (shape1 == null) {
                return null;
            }

            // result is always a new area.
            Area result = new Area(shape1);
            result.subtract(shape2 instanceof Area ? (Area) shape2 : new Area(shape2));
            return result;

        } else if (regionOp == Region.Op.INTERSECT.nativeInt) {
            // if shape1 is null, then the result is simply shape2.
            if (shape1 == null) {
                return new Area(shape2);
            }

            // result is always a new area.
            Area result = new Area(shape1);
            result.intersect(shape2 instanceof Area ? (Area) shape2 : new Area(shape2));
            return result;

        } else if (regionOp == Region.Op.UNION.nativeInt) {
            // if shape1 is null, then the result is simply shape2.
            if (shape1 == null) {
                return new Area(shape2);
            }

            // result is always a new area.
            Area result = new Area(shape1);
            result.add(shape2 instanceof Area ? (Area) shape2 : new Area(shape2));
            return result;

        } else if (regionOp == Region.Op.XOR.nativeInt) {
            // if shape1 is null, then the result is simply shape2
            if (shape1 == null) {
                return new Area(shape2);
            }

            // result is always a new area.
            Area result = new Area(shape1);
            result.exclusiveOr(shape2 instanceof Area ? (Area) shape2 : new Area(shape2));
            return result;

        } else if (regionOp == Region.Op.REVERSE_DIFFERENCE.nativeInt) {
            // result is always a new area.
            Area result = new Area(shape2);

            if (shape1 != null) {
                result.subtract(shape1 instanceof Area ? (Area) shape1 : new Area(shape1));
            }

            return result;
        }

        return null;
    }

    // ---- native methods ----

    @LayoutlibDelegate
    /*package*/ static boolean isEmpty(Region thisRegion) {
        Region_Delegate regionDelegate = sManager.getDelegate(thisRegion.mNativeRegion);
        if (regionDelegate == null) {
            return true;
        }

        return regionDelegate.mArea.isEmpty();
    }

    @LayoutlibDelegate
    /*package*/ static boolean isRect(Region thisRegion) {
        Region_Delegate regionDelegate = sManager.getDelegate(thisRegion.mNativeRegion);
        if (regionDelegate == null) {
            return true;
        }

        return regionDelegate.mArea.isRectangular();
    }

    @LayoutlibDelegate
    /*package*/ static boolean isComplex(Region thisRegion) {
        Region_Delegate regionDelegate = sManager.getDelegate(thisRegion.mNativeRegion);
        if (regionDelegate == null) {
            return true;
        }

        return regionDelegate.mArea.isSingular() == false;
    }

    @LayoutlibDelegate
    /*package*/ static boolean contains(Region thisRegion, int x, int y) {
        Region_Delegate regionDelegate = sManager.getDelegate(thisRegion.mNativeRegion);
        if (regionDelegate == null) {
            return false;
        }

        return regionDelegate.mArea.contains(x, y);
    }

    @LayoutlibDelegate
    /*package*/ static boolean quickContains(Region thisRegion,
            int left, int top, int right, int bottom) {
        Region_Delegate regionDelegate = sManager.getDelegate(thisRegion.mNativeRegion);
        if (regionDelegate == null) {
            return false;
        }

        return regionDelegate.mArea.isRectangular() &&
                regionDelegate.mArea.contains(left, top, right - left, bottom - top);
    }

    @LayoutlibDelegate
    /*package*/ static boolean quickReject(Region thisRegion,
            int left, int top, int right, int bottom) {
        Region_Delegate regionDelegate = sManager.getDelegate(thisRegion.mNativeRegion);
        if (regionDelegate == null) {
            return false;
        }

        return regionDelegate.mArea.isEmpty() ||
                regionDelegate.mArea.intersects(left, top, right - left, bottom - top) == false;
    }

    @LayoutlibDelegate
    /*package*/ static boolean quickReject(Region thisRegion, Region rgn) {
        Region_Delegate regionDelegate = sManager.getDelegate(thisRegion.mNativeRegion);
        if (regionDelegate == null) {
            return false;
        }

        Region_Delegate targetRegionDelegate = sManager.getDelegate(rgn.mNativeRegion);
        if (targetRegionDelegate == null) {
            return false;
        }

        return regionDelegate.mArea.isEmpty() ||
                regionDelegate.mArea.getBounds().intersects(
                        targetRegionDelegate.mArea.getBounds()) == false;

    }

    @LayoutlibDelegate
    /*package*/ static void translate(Region thisRegion, int dx, int dy, Region dst) {
        Region_Delegate regionDelegate = sManager.getDelegate(thisRegion.mNativeRegion);
        if (regionDelegate == null) {
            return;
        }

        Region_Delegate targetRegionDelegate = sManager.getDelegate(dst.mNativeRegion);
        if (targetRegionDelegate == null) {
            return;
        }

        if (regionDelegate.mArea.isEmpty()) {
            targetRegionDelegate.mArea = new Area();
        } else {
            targetRegionDelegate.mArea = new Area(regionDelegate.mArea);
            AffineTransform mtx = new AffineTransform();
            mtx.translate(dx, dy);
            targetRegionDelegate.mArea.transform(mtx);
        }
    }

    @LayoutlibDelegate
    /*package*/ static void scale(Region thisRegion, float scale, Region dst) {
        Region_Delegate regionDelegate = sManager.getDelegate(thisRegion.mNativeRegion);
        if (regionDelegate == null) {
            return;
        }

        Region_Delegate targetRegionDelegate = sManager.getDelegate(dst.mNativeRegion);
        if (targetRegionDelegate == null) {
            return;
        }

        if (regionDelegate.mArea.isEmpty()) {
            targetRegionDelegate.mArea = new Area();
        } else {
            targetRegionDelegate.mArea = new Area(regionDelegate.mArea);
            AffineTransform mtx = new AffineTransform();
            mtx.scale(scale, scale);
            targetRegionDelegate.mArea.transform(mtx);
        }
    }

    @LayoutlibDelegate
    /*package*/ static int nativeConstructor() {
        Region_Delegate newDelegate = new Region_Delegate();
        return sManager.addNewDelegate(newDelegate);
    }

    @LayoutlibDelegate
    /*package*/ static void nativeDestructor(int native_region) {
        sManager.removeJavaReferenceFor(native_region);
    }

    @LayoutlibDelegate
    /*package*/ static boolean nativeSetRegion(int native_dst, int native_src) {
        Region_Delegate dstRegion = sManager.getDelegate(native_dst);
        if (dstRegion == null) {
            return true;
        }

        Region_Delegate srcRegion = sManager.getDelegate(native_src);
        if (srcRegion == null) {
            return true;
        }

        dstRegion.mArea.reset();
        dstRegion.mArea.add(srcRegion.mArea);

        return true;
    }

    @LayoutlibDelegate
    /*package*/ static boolean nativeSetRect(int native_dst,
            int left, int top, int right, int bottom) {
        Region_Delegate dstRegion = sManager.getDelegate(native_dst);
        if (dstRegion == null) {
            return true;
        }

        dstRegion.mArea = new Area(new Rectangle2D.Float(left, top, right - left, bottom - top));
        return dstRegion.mArea.getBounds().isEmpty() == false;
    }

    @LayoutlibDelegate
    /*package*/ static boolean nativeSetPath(int native_dst, int native_path, int native_clip) {
        Region_Delegate dstRegion = sManager.getDelegate(native_dst);
        if (dstRegion == null) {
            return true;
        }

        Path_Delegate path = Path_Delegate.getDelegate(native_path);
        if (path == null) {
            return true;
        }

        dstRegion.mArea = new Area(path.getJavaShape());

        Region_Delegate clip = sManager.getDelegate(native_clip);
        if (clip != null) {
            dstRegion.mArea.subtract(clip.getJavaArea());
        }

        return dstRegion.mArea.getBounds().isEmpty() == false;
    }

    @LayoutlibDelegate
    /*package*/ static boolean nativeGetBounds(int native_region, Rect rect) {
        Region_Delegate region = sManager.getDelegate(native_region);
        if (region == null) {
            return true;
        }

        Rectangle bounds = region.mArea.getBounds();
        if (bounds.isEmpty()) {
            rect.left = rect.top = rect.right = rect.bottom = 0;
            return false;
        }

        rect.left = bounds.x;
        rect.top = bounds.y;
        rect.right = bounds.x + bounds.width;
        rect.bottom = bounds.y + bounds.height;
        return true;
    }

    @LayoutlibDelegate
    /*package*/ static boolean nativeGetBoundaryPath(int native_region, int native_path) {
        Region_Delegate region = sManager.getDelegate(native_region);
        if (region == null) {
            return false;
        }

        Path_Delegate path = Path_Delegate.getDelegate(native_path);
        if (path == null) {
            return false;
        }

        if (region.mArea.isEmpty()) {
            path.reset();
            return false;
        }

        path.setPathIterator(region.mArea.getPathIterator(new AffineTransform()));
        return true;
    }

    @LayoutlibDelegate
    /*package*/ static boolean nativeOp(int native_dst,
            int left, int top, int right, int bottom, int op) {
        Region_Delegate region = sManager.getDelegate(native_dst);
        if (region == null) {
            return false;
        }

        region.mArea = combineShapes(region.mArea,
                new Rectangle2D.Float(left, top, right - left, bottom - top), op);

        assert region.mArea != null;
        if (region.mArea != null) {
            region.mArea = new Area();
        }

        return region.mArea.getBounds().isEmpty() == false;
    }

    @LayoutlibDelegate
    /*package*/ static boolean nativeOp(int native_dst, Rect rect, int native_region, int op) {
        Region_Delegate region = sManager.getDelegate(native_dst);
        if (region == null) {
            return false;
        }

        region.mArea = combineShapes(region.mArea,
                new Rectangle2D.Float(rect.left, rect.top, rect.width(), rect.height()), op);

        assert region.mArea != null;
        if (region.mArea != null) {
            region.mArea = new Area();
        }

        return region.mArea.getBounds().isEmpty() == false;
    }

    @LayoutlibDelegate
    /*package*/ static boolean nativeOp(int native_dst,
            int native_region1, int native_region2, int op) {
        Region_Delegate dstRegion = sManager.getDelegate(native_dst);
        if (dstRegion == null) {
            return true;
        }

        Region_Delegate region1 = sManager.getDelegate(native_region1);
        if (region1 == null) {
            return false;
        }

        Region_Delegate region2 = sManager.getDelegate(native_region2);
        if (region2 == null) {
            return false;
        }

        dstRegion.mArea = combineShapes(region1.mArea, region2.mArea, op);

        assert dstRegion.mArea != null;
        if (dstRegion.mArea != null) {
            dstRegion.mArea = new Area();
        }

        return dstRegion.mArea.getBounds().isEmpty() == false;

    }

    @LayoutlibDelegate
    /*package*/ static int nativeCreateFromParcel(Parcel p) {
        // This is only called by Region.CREATOR (Parcelable.Creator<Region>), which is only
        // used during aidl call so really this should not be called.
        Bridge.getLog().error(LayoutLog.TAG_UNSUPPORTED,
                "AIDL is not suppored, and therefore Regions cannot be created from parcels.",
                null /*data*/);
        return 0;
    }

    @LayoutlibDelegate
    /*package*/ static boolean nativeWriteToParcel(int native_region,
                                                      Parcel p) {
        // This is only called when sending a region through aidl, so really this should not
        // be called.
        Bridge.getLog().error(LayoutLog.TAG_UNSUPPORTED,
                "AIDL is not suppored, and therefore Regions cannot be written to parcels.",
                null /*data*/);
        return false;
    }

    @LayoutlibDelegate
    /*package*/ static boolean nativeEquals(int native_r1, int native_r2) {
        Region_Delegate region1 = sManager.getDelegate(native_r1);
        if (region1 == null) {
            return false;
        }

        Region_Delegate region2 = sManager.getDelegate(native_r2);
        if (region2 == null) {
            return false;
        }

        return region1.mArea.equals(region2.mArea);
    }

    @LayoutlibDelegate
    /*package*/ static String nativeToString(int native_region) {
        Region_Delegate region = sManager.getDelegate(native_region);
        if (region == null) {
            return "not found";
        }

        return region.mArea.toString();
    }

    // ---- Private delegate/helper methods ----

}
