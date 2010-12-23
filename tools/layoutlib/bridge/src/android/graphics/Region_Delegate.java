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

import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.impl.DelegateManager;

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
            new DelegateManager<Region_Delegate>();

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
     * @param shape1 the firt shape to combine
     * @param shape2 the 2nd shape to combine
     * @param regionOp the operande for the combine
     * @return a new area or null.
     */
    public static Area combineShapes(Shape shape1, Shape shape2, int regionOp) {
        if (regionOp == Region.Op.DIFFERENCE.nativeInt) {
            // result is always a new area.
            Area result = new Area(shape1);
            result.subtract(shape2 instanceof Area ? (Area) shape2 : new Area(shape2));
            return result;

        } else if (regionOp == Region.Op.INTERSECT.nativeInt) {
            // result is always a new area.
            Area result = new Area(shape1);
            result.intersect(shape2 instanceof Area ? (Area) shape2 : new Area(shape2));
            return result;

        } else if (regionOp == Region.Op.UNION.nativeInt) {
            // result is always a new area.
            Area result = new Area(shape1);
            result.add(shape2 instanceof Area ? (Area) shape2 : new Area(shape2));
            return result;

        } else if (regionOp == Region.Op.XOR.nativeInt) {
            // result is always a new area.
            Area result = new Area(shape1);
            result.exclusiveOr(shape2 instanceof Area ? (Area) shape2 : new Area(shape2));

        } else if (regionOp == Region.Op.REVERSE_DIFFERENCE.nativeInt) {
            // result is always a new area.
            Area result = new Area(shape2);
            result.subtract(shape1 instanceof Area ? (Area) shape1 : new Area(shape1));
            return result;
        }

        return null;
    }

    // ---- native methods ----

    /*package*/ static int nativeConstructor() {
        Region_Delegate newDelegate = new Region_Delegate();
        return sManager.addDelegate(newDelegate);
    }

    /*package*/ static void nativeDestructor(int native_region) {
        sManager.removeDelegate(native_region);
    }

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

    /*package*/ static boolean nativeSetRect(int native_dst,
            int left, int top, int right, int bottom) {
        Region_Delegate dstRegion = sManager.getDelegate(native_dst);
        if (dstRegion == null) {
            return true;
        }

        dstRegion.mArea = new Area(new Rectangle2D.Float(left, top, right - left, bottom - top));
        return dstRegion.mArea.getBounds().isEmpty() == false;
    }

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

    /*package*/ static int nativeCreateFromParcel(Parcel p) {
        // This is only called by Region.CREATOR (Parcelable.Creator<Region>), which is only
        // used during aidl call so really this should not be called.
        Bridge.getLog().error(null,
                "AIDL is not suppored, and therefore Regions cannot be created from parcels.");
        return 0;
    }

    /*package*/ static boolean nativeWriteToParcel(int native_region,
                                                      Parcel p) {
        // This is only called when sending a region through aidl, so really this should not
        // be called.
        Bridge.getLog().error(null,
                "AIDL is not suppored, and therefore Regions cannot be written to parcels.");
        return false;
    }

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

    // ---- Private delegate/helper methods ----

}
