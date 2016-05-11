/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.graphics.drawable;

import com.android.layoutlib.bridge.impl.DelegateManager;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import android.annotation.NonNull;
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;
import android.graphics.Canvas_Delegate;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Paint.Cap;
import android.graphics.Paint.Join;
import android.graphics.Paint_Delegate;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.Path_Delegate;
import android.graphics.Rect;
import android.graphics.Region.Op;
import android.util.ArrayMap;
import android.util.AttributeSet;
import android.util.Log;
import android.util.MathUtils;
import android.util.PathParser_Delegate;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.function.Consumer;

import static android.graphics.Canvas.CLIP_SAVE_FLAG;
import static android.graphics.Canvas.MATRIX_SAVE_FLAG;
import static android.graphics.Paint.Cap.BUTT;
import static android.graphics.Paint.Cap.ROUND;
import static android.graphics.Paint.Cap.SQUARE;
import static android.graphics.Paint.Join.BEVEL;
import static android.graphics.Paint.Join.MITER;
import static android.graphics.Paint.Style;

/**
 * Delegate used to provide new implementation of a select few methods of {@link VectorDrawable}
 * <p>
 * Through the layoutlib_create tool, the original  methods of VectorDrawable have been replaced by
 * calls to methods of the same name in this delegate class.
 */
@SuppressWarnings("unused")
public class VectorDrawable_Delegate {
    private static final String LOGTAG = VectorDrawable_Delegate.class.getSimpleName();
    private static final boolean DBG_VECTOR_DRAWABLE = false;

    private static final DelegateManager<VNativeObject> sPathManager =
            new DelegateManager<>(VNativeObject.class);

    /**
     * Obtains styled attributes from the theme, if available, or unstyled resources if the theme is
     * null.
     */
    private static TypedArray obtainAttributes(
            Resources res, Theme theme, AttributeSet set, int[] attrs) {
        if (theme == null) {
            return res.obtainAttributes(set, attrs);
        }
        return theme.obtainStyledAttributes(set, attrs, 0, 0);
    }

    private static int applyAlpha(int color, float alpha) {
        int alphaBytes = Color.alpha(color);
        color &= 0x00FFFFFF;
        color |= ((int) (alphaBytes * alpha)) << 24;
        return color;
    }

    @LayoutlibDelegate
    static long nCreateTree(long rootGroupPtr) {
        VGroup_Delegate rootGroup = VNativeObject.getDelegate(rootGroupPtr);
        return sPathManager.addNewDelegate(new VPathRenderer_Delegate(rootGroup));
    }

    @LayoutlibDelegate
    static void nSetRendererViewportSize(long rendererPtr, float viewportWidth,
            float viewportHeight) {
        VPathRenderer_Delegate nativePathRenderer = VNativeObject.getDelegate(rendererPtr);
        nativePathRenderer.mViewportWidth = viewportWidth;
        nativePathRenderer.mViewportHeight = viewportHeight;
    }

    @LayoutlibDelegate
    static boolean nSetRootAlpha(long rendererPtr, float alpha) {
        VPathRenderer_Delegate nativePathRenderer = VNativeObject.getDelegate(rendererPtr);
        nativePathRenderer.setRootAlpha(alpha);

        return true;
    }

    @LayoutlibDelegate
    static float nGetRootAlpha(long rendererPtr) {
        VPathRenderer_Delegate nativePathRenderer = VNativeObject.getDelegate(rendererPtr);

        return nativePathRenderer.getRootAlpha();
    }

    @LayoutlibDelegate
    static void nSetAllowCaching(long rendererPtr, boolean allowCaching) {
        // ignored
    }

    @LayoutlibDelegate
    static int nDraw(long rendererPtr, long canvasWrapperPtr,
            long colorFilterPtr, Rect bounds, boolean needsMirroring, boolean canReuseCache) {
        VPathRenderer_Delegate nativePathRenderer = VNativeObject.getDelegate(rendererPtr);

        Canvas_Delegate.native_save(canvasWrapperPtr, MATRIX_SAVE_FLAG | CLIP_SAVE_FLAG);
        Canvas_Delegate.native_translate(canvasWrapperPtr, bounds.left, bounds.top);

        if (needsMirroring) {
            Canvas_Delegate.native_translate(canvasWrapperPtr, bounds.width(), 0);
            Canvas_Delegate.native_scale(canvasWrapperPtr, -1.0f, 1.0f);
        }

        // At this point, canvas has been translated to the right position.
        // And we use this bound for the destination rect for the drawBitmap, so
        // we offset to (0, 0);
        bounds.offsetTo(0, 0);
        nativePathRenderer.draw(canvasWrapperPtr, colorFilterPtr, bounds.width(), bounds.height());

        Canvas_Delegate.native_restore(canvasWrapperPtr, true);

        return bounds.width() * bounds.height();
    }

    @LayoutlibDelegate
    static long nCreateFullPath() {
        return sPathManager.addNewDelegate(new VFullPath_Delegate());
    }

    @LayoutlibDelegate
    static long nCreateFullPath(long nativeFullPathPtr) {
        VFullPath_Delegate original = VNativeObject.getDelegate(nativeFullPathPtr);

        return sPathManager.addNewDelegate(new VFullPath_Delegate(original));
    }

    @LayoutlibDelegate
    static boolean nGetFullPathProperties(long pathPtr, byte[] propertiesData,
            int length) {
        VFullPath_Delegate path = VNativeObject.getDelegate(pathPtr);

        ByteBuffer properties = ByteBuffer.wrap(propertiesData);
        properties.order(ByteOrder.nativeOrder());

        properties.putFloat(VFullPath_Delegate.STROKE_WIDTH_INDEX * 4, path.getStrokeWidth());
        properties.putInt(VFullPath_Delegate.STROKE_COLOR_INDEX * 4, path.getStrokeColor());
        properties.putFloat(VFullPath_Delegate.STROKE_ALPHA_INDEX * 4, path.getStrokeAlpha());
        properties.putInt(VFullPath_Delegate.FILL_COLOR_INDEX * 4, path.getFillColor());
        properties.putFloat(VFullPath_Delegate.FILL_ALPHA_INDEX * 4, path.getStrokeAlpha());
        properties.putFloat(VFullPath_Delegate.TRIM_PATH_START_INDEX * 4, path.getTrimPathStart());
        properties.putFloat(VFullPath_Delegate.TRIM_PATH_END_INDEX * 4, path.getTrimPathEnd());
        properties.putFloat(VFullPath_Delegate.TRIM_PATH_OFFSET_INDEX * 4,
                path.getTrimPathOffset());
        properties.putInt(VFullPath_Delegate.STROKE_LINE_CAP_INDEX * 4, path.getStrokeLineCap());
        properties.putInt(VFullPath_Delegate.STROKE_LINE_JOIN_INDEX * 4, path.getStrokeLineJoin());
        properties.putFloat(VFullPath_Delegate.STROKE_MITER_LIMIT_INDEX * 4,
                path.getStrokeMiterlimit());
        properties.putInt(VFullPath_Delegate.FILL_TYPE_INDEX * 4, path.getFillType());

        return true;
    }

    @LayoutlibDelegate
    static void nUpdateFullPathProperties(long pathPtr, float strokeWidth,
            int strokeColor, float strokeAlpha, int fillColor, float fillAlpha, float trimPathStart,
            float trimPathEnd, float trimPathOffset, float strokeMiterLimit, int strokeLineCap,
            int strokeLineJoin, int fillType) {
        VFullPath_Delegate path = VNativeObject.getDelegate(pathPtr);

        path.setStrokeWidth(strokeWidth);
        path.setStrokeColor(strokeColor);
        path.setStrokeAlpha(strokeAlpha);
        path.setFillColor(fillColor);
        path.setFillAlpha(fillAlpha);
        path.setTrimPathStart(trimPathStart);
        path.setTrimPathEnd(trimPathEnd);
        path.setTrimPathOffset(trimPathOffset);
        path.setStrokeMiterlimit(strokeMiterLimit);
        path.setStrokeLineCap(strokeLineCap);
        path.setStrokeLineJoin(strokeLineJoin);
        path.setFillType(fillType);
    }

    @LayoutlibDelegate
    static void nUpdateFullPathFillGradient(long pathPtr, long fillGradientPtr) {
        VFullPath_Delegate path = VNativeObject.getDelegate(pathPtr);

        path.setFillGradient(fillGradientPtr);
    }

    @LayoutlibDelegate
    static void nUpdateFullPathStrokeGradient(long pathPtr, long strokeGradientPtr) {
        VFullPath_Delegate path = VNativeObject.getDelegate(pathPtr);

        path.setStrokeGradient(strokeGradientPtr);
    }

    @LayoutlibDelegate
    static long nCreateClipPath() {
        return sPathManager.addNewDelegate(new VClipPath_Delegate());
    }

    @LayoutlibDelegate
    static long nCreateClipPath(long clipPathPtr) {
        VClipPath_Delegate original = VNativeObject.getDelegate(clipPathPtr);
        return sPathManager.addNewDelegate(new VClipPath_Delegate(original));
    }

    @LayoutlibDelegate
    static long nCreateGroup() {
        return sPathManager.addNewDelegate(new VGroup_Delegate());
    }

    @LayoutlibDelegate
    static long nCreateGroup(long groupPtr) {
        VGroup_Delegate original = VNativeObject.getDelegate(groupPtr);
        return sPathManager.addNewDelegate(
                new VGroup_Delegate(original, new ArrayMap<String, Object>()));
    }

    @LayoutlibDelegate
    static void nSetName(long nodePtr, String name) {
        VNativeObject group = VNativeObject.getDelegate(nodePtr);
        group.setName(name);
    }

    @LayoutlibDelegate
    static boolean nGetGroupProperties(long groupPtr, float[] propertiesData,
            int length) {
        VGroup_Delegate group = VNativeObject.getDelegate(groupPtr);

        FloatBuffer properties = FloatBuffer.wrap(propertiesData);

        properties.put(VGroup_Delegate.ROTATE_INDEX, group.getRotation());
        properties.put(VGroup_Delegate.PIVOT_X_INDEX, group.getPivotX());
        properties.put(VGroup_Delegate.PIVOT_Y_INDEX, group.getPivotY());
        properties.put(VGroup_Delegate.SCALE_X_INDEX, group.getScaleX());
        properties.put(VGroup_Delegate.SCALE_Y_INDEX, group.getScaleY());
        properties.put(VGroup_Delegate.TRANSLATE_X_INDEX, group.getTranslateX());
        properties.put(VGroup_Delegate.TRANSLATE_Y_INDEX, group.getTranslateY());

        return true;
    }
    @LayoutlibDelegate
    static void nUpdateGroupProperties(long groupPtr, float rotate, float pivotX,
            float pivotY, float scaleX, float scaleY, float translateX, float translateY) {
        VGroup_Delegate group = VNativeObject.getDelegate(groupPtr);

        group.setRotation(rotate);
        group.setPivotX(pivotX);
        group.setPivotY(pivotY);
        group.setScaleX(scaleX);
        group.setScaleY(scaleY);
        group.setTranslateX(translateX);
        group.setTranslateY(translateY);
    }

    @LayoutlibDelegate
    static void nAddChild(long groupPtr, long nodePtr) {
        VGroup_Delegate group = VNativeObject.getDelegate(groupPtr);
        group.mChildren.add(VNativeObject.getDelegate(nodePtr));
    }

    @LayoutlibDelegate
    static void nSetPathString(long pathPtr, String pathString, int length) {
        VPath_Delegate path = VNativeObject.getDelegate(pathPtr);
        path.setPathData(PathParser_Delegate.createNodesFromPathData(pathString));
    }

    /**
     * The setters and getters below for paths and groups are here temporarily, and will be removed
     * once the animation in AVD is replaced with RenderNodeAnimator, in which case the animation
     * will modify these properties in native. By then no JNI hopping would be necessary for VD
     * during animation, and these setters and getters will be obsolete.
     */
    // Setters and getters during animation.
    @LayoutlibDelegate
    static float nGetRotation(long groupPtr) {
        VGroup_Delegate group = VNativeObject.getDelegate(groupPtr);
        return group.getRotation();
    }

    @LayoutlibDelegate
    static void nSetRotation(long groupPtr, float rotation) {
        VGroup_Delegate group = VNativeObject.getDelegate(groupPtr);
        group.setRotation(rotation);
    }

    @LayoutlibDelegate
    static float nGetPivotX(long groupPtr) {
        VGroup_Delegate group = VNativeObject.getDelegate(groupPtr);
        return group.getPivotX();
    }

    @LayoutlibDelegate
    static void nSetPivotX(long groupPtr, float pivotX) {
        VGroup_Delegate group = VNativeObject.getDelegate(groupPtr);
        group.setPivotX(pivotX);
    }

    @LayoutlibDelegate
    static float nGetPivotY(long groupPtr) {
        VGroup_Delegate group = VNativeObject.getDelegate(groupPtr);
        return group.getPivotY();
    }

    @LayoutlibDelegate
    static void nSetPivotY(long groupPtr, float pivotY) {
        VGroup_Delegate group = VNativeObject.getDelegate(groupPtr);
        group.setPivotY(pivotY);
    }

    @LayoutlibDelegate
    static float nGetScaleX(long groupPtr) {
        VGroup_Delegate group = VNativeObject.getDelegate(groupPtr);
        return group.getScaleX();
    }

    @LayoutlibDelegate
    static void nSetScaleX(long groupPtr, float scaleX) {
        VGroup_Delegate group = VNativeObject.getDelegate(groupPtr);
        group.setScaleX(scaleX);
    }

    @LayoutlibDelegate
    static float nGetScaleY(long groupPtr) {
        VGroup_Delegate group = VNativeObject.getDelegate(groupPtr);
        return group.getScaleY();
    }

    @LayoutlibDelegate
    static void nSetScaleY(long groupPtr, float scaleY) {
        VGroup_Delegate group = VNativeObject.getDelegate(groupPtr);
        group.setScaleY(scaleY);
    }

    @LayoutlibDelegate
    static float nGetTranslateX(long groupPtr) {
        VGroup_Delegate group = VNativeObject.getDelegate(groupPtr);
        return group.getTranslateX();
    }

    @LayoutlibDelegate
    static void nSetTranslateX(long groupPtr, float translateX) {
        VGroup_Delegate group = VNativeObject.getDelegate(groupPtr);
        group.setTranslateX(translateX);
    }

    @LayoutlibDelegate
    static float nGetTranslateY(long groupPtr) {
        VGroup_Delegate group = VNativeObject.getDelegate(groupPtr);
        return group.getTranslateY();
    }

    @LayoutlibDelegate
    static void nSetTranslateY(long groupPtr, float translateY) {
        VGroup_Delegate group = VNativeObject.getDelegate(groupPtr);
        group.setTranslateY(translateY);
    }

    @LayoutlibDelegate
    static void nSetPathData(long pathPtr, long pathDataPtr) {
        VPath_Delegate path = VNativeObject.getDelegate(pathPtr);
        path.setPathData(PathParser_Delegate.getDelegate(pathDataPtr).getPathDataNodes());
    }

    @LayoutlibDelegate
    static float nGetStrokeWidth(long pathPtr) {
        VFullPath_Delegate path = VNativeObject.getDelegate(pathPtr);
        return path.getStrokeWidth();
    }

    @LayoutlibDelegate
    static void nSetStrokeWidth(long pathPtr, float width) {
        VFullPath_Delegate path = VNativeObject.getDelegate(pathPtr);
        path.setStrokeWidth(width);
    }

    @LayoutlibDelegate
    static int nGetStrokeColor(long pathPtr) {
        VFullPath_Delegate path = VNativeObject.getDelegate(pathPtr);
        return path.getStrokeColor();
    }

    @LayoutlibDelegate
    static void nSetStrokeColor(long pathPtr, int strokeColor) {
        VFullPath_Delegate path = VNativeObject.getDelegate(pathPtr);
        path.setStrokeColor(strokeColor);
    }

    @LayoutlibDelegate
    static float nGetStrokeAlpha(long pathPtr) {
        VFullPath_Delegate path = VNativeObject.getDelegate(pathPtr);
        return path.getStrokeAlpha();
    }

    @LayoutlibDelegate
    static void nSetStrokeAlpha(long pathPtr, float alpha) {
        VFullPath_Delegate path = VNativeObject.getDelegate(pathPtr);
        path.setStrokeAlpha(alpha);
    }

    @LayoutlibDelegate
    static int nGetFillColor(long pathPtr) {
        VFullPath_Delegate path = VNativeObject.getDelegate(pathPtr);
        return path.getFillColor();
    }

    @LayoutlibDelegate
    static void nSetFillColor(long pathPtr, int fillColor) {
        VFullPath_Delegate path = VNativeObject.getDelegate(pathPtr);
        path.setFillColor(fillColor);
    }

    @LayoutlibDelegate
    static float nGetFillAlpha(long pathPtr) {
        VFullPath_Delegate path = VNativeObject.getDelegate(pathPtr);
        return path.getFillAlpha();
    }

    @LayoutlibDelegate
    static void nSetFillAlpha(long pathPtr, float fillAlpha) {
        VFullPath_Delegate path = VNativeObject.getDelegate(pathPtr);
        path.setFillAlpha(fillAlpha);
    }

    @LayoutlibDelegate
    static float nGetTrimPathStart(long pathPtr) {
        VFullPath_Delegate path = VNativeObject.getDelegate(pathPtr);
        return path.getTrimPathStart();
    }

    @LayoutlibDelegate
    static void nSetTrimPathStart(long pathPtr, float trimPathStart) {
        VFullPath_Delegate path = VNativeObject.getDelegate(pathPtr);
        path.setTrimPathStart(trimPathStart);
    }

    @LayoutlibDelegate
    static float nGetTrimPathEnd(long pathPtr) {
        VFullPath_Delegate path = VNativeObject.getDelegate(pathPtr);
        return path.getTrimPathEnd();
    }

    @LayoutlibDelegate
    static void nSetTrimPathEnd(long pathPtr, float trimPathEnd) {
        VFullPath_Delegate path = VNativeObject.getDelegate(pathPtr);
        path.setTrimPathEnd(trimPathEnd);
    }

    @LayoutlibDelegate
    static float nGetTrimPathOffset(long pathPtr) {
        VFullPath_Delegate path = VNativeObject.getDelegate(pathPtr);
        return path.getTrimPathOffset();
    }

    @LayoutlibDelegate
    static void nSetTrimPathOffset(long pathPtr, float trimPathOffset) {
        VFullPath_Delegate path = VNativeObject.getDelegate(pathPtr);
        path.setTrimPathOffset(trimPathOffset);
    }

    /**
     * Base class for all the internal Delegates that does two functions:
     * <ol>
     *     <li>Serves as base class to store all the delegates in one {@link DelegateManager}
     *     <li>Provides setName for all the classes. {@link VPathRenderer_Delegate} does actually
     *     not need it
     * </ol>
     */
    interface VNativeObject {
        @NonNull
        static <T> T getDelegate(long nativePtr) {
            //noinspection unchecked
            T vNativeObject = (T) sPathManager.getDelegate(nativePtr);

            assert vNativeObject != null;
            return vNativeObject;
        }

        void setName(String name);
    }

    private static class VClipPath_Delegate extends VPath_Delegate {
        private VClipPath_Delegate() {
            // Empty constructor.
        }

        private VClipPath_Delegate(VClipPath_Delegate copy) {
            super(copy);
        }

        @Override
        public boolean isClipPath() {
            return true;
        }
    }

    static class VFullPath_Delegate extends VPath_Delegate {
        // These constants need to be kept in sync with their values in VectorDrawable.VFullPath
        private static final int STROKE_WIDTH_INDEX = 0;
        private static final int STROKE_COLOR_INDEX = 1;
        private static final int STROKE_ALPHA_INDEX = 2;
        private static final int FILL_COLOR_INDEX = 3;
        private static final int FILL_ALPHA_INDEX = 4;
        private static final int TRIM_PATH_START_INDEX = 5;
        private static final int TRIM_PATH_END_INDEX = 6;
        private static final int TRIM_PATH_OFFSET_INDEX = 7;
        private static final int STROKE_LINE_CAP_INDEX = 8;
        private static final int STROKE_LINE_JOIN_INDEX = 9;
        private static final int STROKE_MITER_LIMIT_INDEX = 10;
        private static final int FILL_TYPE_INDEX = 11;

        private static final int LINECAP_BUTT = 0;
        private static final int LINECAP_ROUND = 1;
        private static final int LINECAP_SQUARE = 2;

        private static final int LINEJOIN_MITER = 0;
        private static final int LINEJOIN_ROUND = 1;
        private static final int LINEJOIN_BEVEL = 2;

        @NonNull
        public Consumer<Float> getFloatPropertySetter(int propertyIdx) {
            switch (propertyIdx) {
                case STROKE_ALPHA_INDEX:
                    return this::setStrokeAlpha;
                case FILL_ALPHA_INDEX:
                    return this::setFillAlpha;
                case TRIM_PATH_START_INDEX:
                    return this::setTrimPathStart;
                case TRIM_PATH_END_INDEX:
                    return this::setTrimPathEnd;
                case TRIM_PATH_OFFSET_INDEX:
                    return this::setTrimPathOffset;
            }

            throw new IllegalArgumentException("Invalid VFullPath_Delegate property index "
                    + propertyIdx);
        }

        @NonNull
        public Consumer<Integer> getIntPropertySetter(int propertyIdx) {
            switch (propertyIdx) {
                case STROKE_COLOR_INDEX:
                    return this::setStrokeColor;
                case FILL_COLOR_INDEX:
                    return this::setFillColor;
            }

            throw new IllegalArgumentException("Invalid VFullPath_Delegate property index "
                    + propertyIdx);
        }

        /////////////////////////////////////////////////////
        // Variables below need to be copied (deep copy if applicable) for mutation.

        int mStrokeColor = Color.TRANSPARENT;
        float mStrokeWidth = 0;

        int mFillColor = Color.TRANSPARENT;
        long mStrokeGradient = 0;
        long mFillGradient = 0;
        float mStrokeAlpha = 1.0f;
        float mFillAlpha = 1.0f;
        float mTrimPathStart = 0;
        float mTrimPathEnd = 1;
        float mTrimPathOffset = 0;

        Cap mStrokeLineCap = BUTT;
        Join mStrokeLineJoin = MITER;
        float mStrokeMiterlimit = 4;

        int mFillType = 0; // WINDING(0) is the default value. See Path.FillType

        private VFullPath_Delegate() {
            // Empty constructor.
        }

        private VFullPath_Delegate(VFullPath_Delegate copy) {
            super(copy);

            mStrokeColor = copy.mStrokeColor;
            mStrokeWidth = copy.mStrokeWidth;
            mStrokeAlpha = copy.mStrokeAlpha;
            mFillColor = copy.mFillColor;
            mFillAlpha = copy.mFillAlpha;
            mTrimPathStart = copy.mTrimPathStart;
            mTrimPathEnd = copy.mTrimPathEnd;
            mTrimPathOffset = copy.mTrimPathOffset;

            mStrokeLineCap = copy.mStrokeLineCap;
            mStrokeLineJoin = copy.mStrokeLineJoin;
            mStrokeMiterlimit = copy.mStrokeMiterlimit;

            mStrokeGradient = copy.mStrokeGradient;
            mFillGradient = copy.mFillGradient;
            mFillType = copy.mFillType;
        }

        private int getStrokeLineCap() {
            switch (mStrokeLineCap) {
                case BUTT:
                    return LINECAP_BUTT;
                case ROUND:
                    return LINECAP_ROUND;
                case SQUARE:
                    return LINECAP_SQUARE;
                default:
                    assert false;
            }

            return -1;
        }

        private void setStrokeLineCap(int cap) {
            switch (cap) {
                case LINECAP_BUTT:
                    mStrokeLineCap = BUTT;
                    break;
                case LINECAP_ROUND:
                    mStrokeLineCap = ROUND;
                    break;
                case LINECAP_SQUARE:
                    mStrokeLineCap = SQUARE;
                    break;
                default:
                    assert false;
            }
        }

        private int getStrokeLineJoin() {
            switch (mStrokeLineJoin) {
                case MITER:
                    return LINEJOIN_MITER;
                case ROUND:
                    return LINEJOIN_ROUND;
                case BEVEL:
                    return LINEJOIN_BEVEL;
                default:
                    assert false;
            }

            return -1;
        }

        private void setStrokeLineJoin(int join) {
            switch (join) {
                case LINEJOIN_BEVEL:
                    mStrokeLineJoin = BEVEL;
                    break;
                case LINEJOIN_MITER:
                    mStrokeLineJoin = MITER;
                    break;
                case LINEJOIN_ROUND:
                    mStrokeLineJoin = Join.ROUND;
                    break;
                default:
                    assert false;
            }
        }

        private int getStrokeColor() {
            return mStrokeColor;
        }

        private void setStrokeColor(int strokeColor) {
            mStrokeColor = strokeColor;
        }

        private float getStrokeWidth() {
            return mStrokeWidth;
        }

        private void setStrokeWidth(float strokeWidth) {
            mStrokeWidth = strokeWidth;
        }

        private float getStrokeAlpha() {
            return mStrokeAlpha;
        }

        private void setStrokeAlpha(float strokeAlpha) {
            mStrokeAlpha = strokeAlpha;
        }

        private int getFillColor() {
            return mFillColor;
        }

        private void setFillColor(int fillColor) {
            mFillColor = fillColor;
        }

        private float getFillAlpha() {
            return mFillAlpha;
        }

        private void setFillAlpha(float fillAlpha) {
            mFillAlpha = fillAlpha;
        }

        private float getTrimPathStart() {
            return mTrimPathStart;
        }

        private void setTrimPathStart(float trimPathStart) {
            mTrimPathStart = trimPathStart;
        }

        private float getTrimPathEnd() {
            return mTrimPathEnd;
        }

        private void setTrimPathEnd(float trimPathEnd) {
            mTrimPathEnd = trimPathEnd;
        }

        private float getTrimPathOffset() {
            return mTrimPathOffset;
        }

        private void setTrimPathOffset(float trimPathOffset) {
            mTrimPathOffset = trimPathOffset;
        }

        private void setStrokeMiterlimit(float limit) {
            mStrokeMiterlimit = limit;
        }

        private float getStrokeMiterlimit() {
            return mStrokeMiterlimit;
        }

        private void setStrokeGradient(long gradientPtr) {
            mStrokeGradient = gradientPtr;
        }

        private void setFillGradient(long gradientPtr) {
            mFillGradient = gradientPtr;
        }

        private void setFillType(int fillType) {
            mFillType = fillType;
        }

        private int getFillType() {
            return mFillType;
        }
    }

    static class VGroup_Delegate implements VNativeObject {
        // This constants need to be kept in sync with their definitions in VectorDrawable.Group
        private static final int ROTATE_INDEX = 0;
        private static final int PIVOT_X_INDEX = 1;
        private static final int PIVOT_Y_INDEX = 2;
        private static final int SCALE_X_INDEX = 3;
        private static final int SCALE_Y_INDEX = 4;
        private static final int TRANSLATE_X_INDEX = 5;
        private static final int TRANSLATE_Y_INDEX = 6;

        public Consumer<Float> getPropertySetter(int propertyIdx) {
            switch (propertyIdx) {
                case ROTATE_INDEX:
                    return this::setRotation;
                case PIVOT_X_INDEX:
                    return this::setPivotX;
                case PIVOT_Y_INDEX:
                    return this::setPivotY;
                case SCALE_X_INDEX:
                    return this::setScaleX;
                case SCALE_Y_INDEX:
                    return this::setScaleY;
                case TRANSLATE_X_INDEX:
                    return this::setTranslateX;
                case TRANSLATE_Y_INDEX:
                    return this::setTranslateY;
            }

            throw new IllegalArgumentException("Invalid VGroup_Delegate property index "
                    + propertyIdx);
        }

        /////////////////////////////////////////////////////
        // Variables below need to be copied (deep copy if applicable) for mutation.
        final ArrayList<Object> mChildren = new ArrayList<>();
        // mStackedMatrix is only used temporarily when drawing, it combines all
        // the parents' local matrices with the current one.
        private final Matrix mStackedMatrix = new Matrix();
        // mLocalMatrix is updated based on the update of transformation information,
        // either parsed from the XML or by animation.
        private final Matrix mLocalMatrix = new Matrix();
        private float mRotate = 0;
        private float mPivotX = 0;
        private float mPivotY = 0;
        private float mScaleX = 1;
        private float mScaleY = 1;
        private float mTranslateX = 0;
        private float mTranslateY = 0;
        private int mChangingConfigurations;
        private String mGroupName = null;

        private VGroup_Delegate(VGroup_Delegate copy, ArrayMap<String, Object> targetsMap) {
            mRotate = copy.mRotate;
            mPivotX = copy.mPivotX;
            mPivotY = copy.mPivotY;
            mScaleX = copy.mScaleX;
            mScaleY = copy.mScaleY;
            mTranslateX = copy.mTranslateX;
            mTranslateY = copy.mTranslateY;
            mGroupName = copy.mGroupName;
            mChangingConfigurations = copy.mChangingConfigurations;
            if (mGroupName != null) {
                targetsMap.put(mGroupName, this);
            }

            mLocalMatrix.set(copy.mLocalMatrix);

            final ArrayList<Object> children = copy.mChildren;
            //noinspection ForLoopReplaceableByForEach
            for (int i = 0; i < children.size(); i++) {
                Object copyChild = children.get(i);
                if (copyChild instanceof VGroup_Delegate) {
                    VGroup_Delegate copyGroup = (VGroup_Delegate) copyChild;
                    mChildren.add(new VGroup_Delegate(copyGroup, targetsMap));
                } else {
                    VPath_Delegate newPath;
                    if (copyChild instanceof VFullPath_Delegate) {
                        newPath = new VFullPath_Delegate((VFullPath_Delegate) copyChild);
                    } else if (copyChild instanceof VClipPath_Delegate) {
                        newPath = new VClipPath_Delegate((VClipPath_Delegate) copyChild);
                    } else {
                        throw new IllegalStateException("Unknown object in the tree!");
                    }
                    mChildren.add(newPath);
                    if (newPath.mPathName != null) {
                        targetsMap.put(newPath.mPathName, newPath);
                    }
                }
            }
        }

        private VGroup_Delegate() {
        }

        private void updateLocalMatrix() {
            // The order we apply is the same as the
            // RenderNode.cpp::applyViewPropertyTransforms().
            mLocalMatrix.reset();
            mLocalMatrix.postTranslate(-mPivotX, -mPivotY);
            mLocalMatrix.postScale(mScaleX, mScaleY);
            mLocalMatrix.postRotate(mRotate, 0, 0);
            mLocalMatrix.postTranslate(mTranslateX + mPivotX, mTranslateY + mPivotY);
        }

        /* Setters and Getters, used by animator from AnimatedVectorDrawable. */
        private float getRotation() {
            return mRotate;
        }

        private void setRotation(float rotation) {
            if (rotation != mRotate) {
                mRotate = rotation;
                updateLocalMatrix();
            }
        }

        private float getPivotX() {
            return mPivotX;
        }

        private void setPivotX(float pivotX) {
            if (pivotX != mPivotX) {
                mPivotX = pivotX;
                updateLocalMatrix();
            }
        }

        private float getPivotY() {
            return mPivotY;
        }

        private void setPivotY(float pivotY) {
            if (pivotY != mPivotY) {
                mPivotY = pivotY;
                updateLocalMatrix();
            }
        }

        private float getScaleX() {
            return mScaleX;
        }

        private void setScaleX(float scaleX) {
            if (scaleX != mScaleX) {
                mScaleX = scaleX;
                updateLocalMatrix();
            }
        }

        private float getScaleY() {
            return mScaleY;
        }

        private void setScaleY(float scaleY) {
            if (scaleY != mScaleY) {
                mScaleY = scaleY;
                updateLocalMatrix();
            }
        }

        private float getTranslateX() {
            return mTranslateX;
        }

        private void setTranslateX(float translateX) {
            if (translateX != mTranslateX) {
                mTranslateX = translateX;
                updateLocalMatrix();
            }
        }

        private float getTranslateY() {
            return mTranslateY;
        }

        private void setTranslateY(float translateY) {
            if (translateY != mTranslateY) {
                mTranslateY = translateY;
                updateLocalMatrix();
            }
        }

        @Override
        public void setName(String name) {
            mGroupName = name;
        }
    }

    public static class VPath_Delegate implements VNativeObject {
        protected PathParser_Delegate.PathDataNode[] mNodes = null;
        String mPathName;
        int mChangingConfigurations;

        public VPath_Delegate() {
            // Empty constructor.
        }

        public VPath_Delegate(VPath_Delegate copy) {
            mPathName = copy.mPathName;
            mChangingConfigurations = copy.mChangingConfigurations;
            mNodes = PathParser_Delegate.deepCopyNodes(copy.mNodes);
        }

        public void toPath(Path path) {
            path.reset();
            if (mNodes != null) {
                PathParser_Delegate.PathDataNode.nodesToPath(mNodes,
                        Path_Delegate.getDelegate(path.mNativePath));
            }
        }

        @Override
        public void setName(String name) {
            mPathName = name;
        }

        public boolean isClipPath() {
            return false;
        }

        private void setPathData(PathParser_Delegate.PathDataNode[] nodes) {
            if (!PathParser_Delegate.canMorph(mNodes, nodes)) {
                // This should not happen in the middle of animation.
                mNodes = PathParser_Delegate.deepCopyNodes(nodes);
            } else {
                PathParser_Delegate.updateNodes(mNodes, nodes);
            }
        }
    }

    static class VPathRenderer_Delegate implements VNativeObject {
        /* Right now the internal data structure is organized as a tree.
         * Each node can be a group node, or a path.
         * A group node can have groups or paths as children, but a path node has
         * no children.
         * One example can be:
         *                 Root Group
         *                /    |     \
         *           Group    Path    Group
         *          /     \             |
         *         Path   Path         Path
         *
         */
        // Variables that only used temporarily inside the draw() call, so there
        // is no need for deep copying.
        private final Path mPath;
        private final Path mRenderPath;
        private final Matrix mFinalPathMatrix = new Matrix();
        private final VGroup_Delegate mRootGroup;
        private float mViewportWidth = 0;
        private float mViewportHeight = 0;
        private float mRootAlpha = 1.0f;
        private Paint mStrokePaint;
        private Paint mFillPaint;
        private PathMeasure mPathMeasure;

        private VPathRenderer_Delegate(VGroup_Delegate rootGroup) {
            mRootGroup = rootGroup;
            mPath = new Path();
            mRenderPath = new Path();
        }

        private float getRootAlpha() {
            return mRootAlpha;
        }

        void setRootAlpha(float alpha) {
            mRootAlpha = alpha;
        }

        private void drawGroupTree(VGroup_Delegate currentGroup, Matrix currentMatrix,
                long canvasPtr, int w, int h, long filterPtr) {
            // Calculate current group's matrix by preConcat the parent's and
            // and the current one on the top of the stack.
            // Basically the Mfinal = Mviewport * M0 * M1 * M2;
            // Mi the local matrix at level i of the group tree.
            currentGroup.mStackedMatrix.set(currentMatrix);
            currentGroup.mStackedMatrix.preConcat(currentGroup.mLocalMatrix);

            // Save the current clip information, which is local to this group.
            Canvas_Delegate.native_save(canvasPtr, MATRIX_SAVE_FLAG | CLIP_SAVE_FLAG);
            // Draw the group tree in the same order as the XML file.
            for (int i = 0; i < currentGroup.mChildren.size(); i++) {
                Object child = currentGroup.mChildren.get(i);
                if (child instanceof VGroup_Delegate) {
                    VGroup_Delegate childGroup = (VGroup_Delegate) child;
                    drawGroupTree(childGroup, currentGroup.mStackedMatrix,
                            canvasPtr, w, h, filterPtr);
                } else if (child instanceof VPath_Delegate) {
                    VPath_Delegate childPath = (VPath_Delegate) child;
                    drawPath(currentGroup, childPath, canvasPtr, w, h, filterPtr);
                }
            }
            Canvas_Delegate.native_restore(canvasPtr, true);
        }

        public void draw(long canvasPtr, long filterPtr, int w, int h) {
            // Traverse the tree in pre-order to draw.
            drawGroupTree(mRootGroup, Matrix.IDENTITY_MATRIX, canvasPtr, w, h, filterPtr);
        }

        private void drawPath(VGroup_Delegate VGroup, VPath_Delegate VPath, long canvasPtr,
                int w,
                int h,
                long filterPtr) {
            final float scaleX = w / mViewportWidth;
            final float scaleY = h / mViewportHeight;
            final float minScale = Math.min(scaleX, scaleY);
            final Matrix groupStackedMatrix = VGroup.mStackedMatrix;

            mFinalPathMatrix.set(groupStackedMatrix);
            mFinalPathMatrix.postScale(scaleX, scaleY);

            final float matrixScale = getMatrixScale(groupStackedMatrix);
            if (matrixScale == 0) {
                // When either x or y is scaled to 0, we don't need to draw anything.
                return;
            }
            VPath.toPath(mPath);
            final Path path = mPath;

            mRenderPath.reset();

            if (VPath.isClipPath()) {
                mRenderPath.addPath(path, mFinalPathMatrix);
                Canvas_Delegate.native_clipPath(canvasPtr, mRenderPath.mNativePath, Op
                        .INTERSECT.nativeInt);
            } else {
                VFullPath_Delegate fullPath = (VFullPath_Delegate) VPath;
                if (fullPath.mTrimPathStart != 0.0f || fullPath.mTrimPathEnd != 1.0f) {
                    float start = (fullPath.mTrimPathStart + fullPath.mTrimPathOffset) % 1.0f;
                    float end = (fullPath.mTrimPathEnd + fullPath.mTrimPathOffset) % 1.0f;

                    if (mPathMeasure == null) {
                        mPathMeasure = new PathMeasure();
                    }
                    mPathMeasure.setPath(mPath, false);

                    float len = mPathMeasure.getLength();
                    start = start * len;
                    end = end * len;
                    path.reset();
                    if (start > end) {
                        mPathMeasure.getSegment(start, len, path, true);
                        mPathMeasure.getSegment(0f, end, path, true);
                    } else {
                        mPathMeasure.getSegment(start, end, path, true);
                    }
                    path.rLineTo(0, 0); // fix bug in measure
                }
                mRenderPath.addPath(path, mFinalPathMatrix);

                if (fullPath.mFillColor != Color.TRANSPARENT) {
                    if (mFillPaint == null) {
                        mFillPaint = new Paint();
                        mFillPaint.setStyle(Style.FILL);
                        mFillPaint.setAntiAlias(true);
                    }

                    final Paint fillPaint = mFillPaint;
                    fillPaint.setColor(applyAlpha(fullPath.mFillColor, fullPath.mFillAlpha));
                    Paint_Delegate fillPaintDelegate = Paint_Delegate.getDelegate(fillPaint
                            .getNativeInstance());
                    // mFillPaint can not be null at this point so we will have a delegate
                    assert fillPaintDelegate != null;
                    fillPaintDelegate.setColorFilter(filterPtr);
                    fillPaintDelegate.setShader(fullPath.mFillGradient);
                    Path_Delegate.native_setFillType(mRenderPath.mNativePath, fullPath.mFillType);
                    Canvas_Delegate.native_drawPath(canvasPtr, mRenderPath.mNativePath, fillPaint
                            .getNativeInstance());
                }

                if (fullPath.mStrokeColor != Color.TRANSPARENT) {
                    if (mStrokePaint == null) {
                        mStrokePaint = new Paint();
                        mStrokePaint.setStyle(Style.STROKE);
                        mStrokePaint.setAntiAlias(true);
                    }

                    final Paint strokePaint = mStrokePaint;
                    if (fullPath.mStrokeLineJoin != null) {
                        strokePaint.setStrokeJoin(fullPath.mStrokeLineJoin);
                    }

                    if (fullPath.mStrokeLineCap != null) {
                        strokePaint.setStrokeCap(fullPath.mStrokeLineCap);
                    }

                    strokePaint.setStrokeMiter(fullPath.mStrokeMiterlimit);
                    strokePaint.setColor(applyAlpha(fullPath.mStrokeColor, fullPath.mStrokeAlpha));
                    Paint_Delegate strokePaintDelegate = Paint_Delegate.getDelegate(strokePaint
                            .getNativeInstance());
                    // mStrokePaint can not be null at this point so we will have a delegate
                    assert strokePaintDelegate != null;
                    strokePaintDelegate.setColorFilter(filterPtr);
                    final float finalStrokeScale = minScale * matrixScale;
                    strokePaint.setStrokeWidth(fullPath.mStrokeWidth * finalStrokeScale);
                    strokePaintDelegate.setShader(fullPath.mStrokeGradient);
                    Canvas_Delegate.native_drawPath(canvasPtr, mRenderPath.mNativePath, strokePaint
                            .getNativeInstance());
                }
            }
        }

        private float getMatrixScale(Matrix groupStackedMatrix) {
            // Given unit vectors A = (0, 1) and B = (1, 0).
            // After matrix mapping, we got A' and B'. Let theta = the angel b/t A' and B'.
            // Therefore, the final scale we want is min(|A'| * sin(theta), |B'| * sin(theta)),
            // which is (|A'| * |B'| * sin(theta)) / max (|A'|, |B'|);
            // If  max (|A'|, |B'|) = 0, that means either x or y has a scale of 0.
            //
            // For non-skew case, which is most of the cases, matrix scale is computing exactly the
            // scale on x and y axis, and take the minimal of these two.
            // For skew case, an unit square will mapped to a parallelogram. And this function will
            // return the minimal height of the 2 bases.
            float[] unitVectors = new float[]{0, 1, 1, 0};
            groupStackedMatrix.mapVectors(unitVectors);
            float scaleX = MathUtils.mag(unitVectors[0], unitVectors[1]);
            float scaleY = MathUtils.mag(unitVectors[2], unitVectors[3]);
            float crossProduct = MathUtils.cross(unitVectors[0], unitVectors[1],
                    unitVectors[2], unitVectors[3]);
            float maxScale = MathUtils.max(scaleX, scaleY);

            float matrixScale = 0;
            if (maxScale > 0) {
                matrixScale = MathUtils.abs(crossProduct) / maxScale;
            }
            if (DBG_VECTOR_DRAWABLE) {
                Log.d(LOGTAG, "Scale x " + scaleX + " y " + scaleY + " final " + matrixScale);
            }
            return matrixScale;
        }

        @Override
        public void setName(String name) {
        }
    }
}
