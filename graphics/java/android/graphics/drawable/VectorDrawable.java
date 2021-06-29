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

package android.graphics.drawable;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.ActivityInfo.Config;
import android.content.res.ColorStateList;
import android.content.res.ComplexColor;
import android.content.res.GradientColor;
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff.Mode;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.Trace;
import android.util.ArrayMap;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.FloatProperty;
import android.util.IntProperty;
import android.util.LayoutDirection;
import android.util.Log;
import android.util.PathParser;
import android.util.Property;
import android.util.Xml;

import com.android.internal.R;
import com.android.internal.util.VirtualRefBasePtr;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Stack;

import dalvik.annotation.optimization.FastNative;
import dalvik.system.VMRuntime;

/**
 * This lets you create a drawable based on an XML vector graphic.
 * <p/>
 * <strong>Note:</strong> To optimize for the re-drawing performance, one bitmap cache is created
 * for each VectorDrawable. Therefore, referring to the same VectorDrawable means sharing the same
 * bitmap cache. If these references don't agree upon on the same size, the bitmap will be recreated
 * and redrawn every time size is changed. In other words, if a VectorDrawable is used for
 * different sizes, it is more efficient to create multiple VectorDrawables, one for each size.
 * <p/>
 * VectorDrawable can be defined in an XML file with the <code>&lt;vector></code> element.
 * <p/>
 * The vector drawable has the following elements:
 * <p/>
 * <dt><code>&lt;vector></code></dt>
 * <dl>
 * <dd>Used to define a vector drawable
 * <dl>
 * <dt><code>android:name</code></dt>
 * <dd>Defines the name of this vector drawable.</dd>
 * <dt><code>android:width</code></dt>
 * <dd>Used to define the intrinsic width of the drawable.
 * This support all the dimension units, normally specified with dp.</dd>
 * <dt><code>android:height</code></dt>
 * <dd>Used to define the intrinsic height the drawable.
 * This support all the dimension units, normally specified with dp.</dd>
 * <dt><code>android:viewportWidth</code></dt>
 * <dd>Used to define the width of the viewport space. Viewport is basically
 * the virtual canvas where the paths are drawn on.</dd>
 * <dt><code>android:viewportHeight</code></dt>
 * <dd>Used to define the height of the viewport space. Viewport is basically
 * the virtual canvas where the paths are drawn on.</dd>
 * <dt><code>android:tint</code></dt>
 * <dd>The color to apply to the drawable as a tint. By default, no tint is applied.</dd>
 * <dt><code>android:tintMode</code></dt>
 * <dd>The Porter-Duff blending mode for the tint color. Default is src_in.</dd>
 * <dt><code>android:autoMirrored</code></dt>
 * <dd>Indicates if the drawable needs to be mirrored when its layout direction is
 * RTL (right-to-left). Default is false.</dd>
 * <dt><code>android:alpha</code></dt>
 * <dd>The opacity of this drawable. Default is 1.0.</dd>
 * </dl></dd>
 * </dl>
 *
 * <dl>
 * <dt><code>&lt;group></code></dt>
 * <dd>Defines a group of paths or subgroups, plus transformation information.
 * The transformations are defined in the same coordinates as the viewport.
 * And the transformations are applied in the order of scale, rotate then translate.
 * <dl>
 * <dt><code>android:name</code></dt>
 * <dd>Defines the name of the group.</dd>
 * <dt><code>android:rotation</code></dt>
 * <dd>The degrees of rotation of the group. Default is 0.</dd>
 * <dt><code>android:pivotX</code></dt>
 * <dd>The X coordinate of the pivot for the scale and rotation of the group.
 * This is defined in the viewport space. Default is 0.</dd>
 * <dt><code>android:pivotY</code></dt>
 * <dd>The Y coordinate of the pivot for the scale and rotation of the group.
 * This is defined in the viewport space. Default is 0.</dd>
 * <dt><code>android:scaleX</code></dt>
 * <dd>The amount of scale on the X Coordinate. Default is 1.</dd>
 * <dt><code>android:scaleY</code></dt>
 * <dd>The amount of scale on the Y coordinate. Default is 1.</dd>
 * <dt><code>android:translateX</code></dt>
 * <dd>The amount of translation on the X coordinate.
 * This is defined in the viewport space. Default is 0.</dd>
 * <dt><code>android:translateY</code></dt>
 * <dd>The amount of translation on the Y coordinate.
 * This is defined in the viewport space. Default is 0.</dd>
 * </dl></dd>
 * </dl>
 *
 * <dl>
 * <dt><code>&lt;path></code></dt>
 * <dd>Defines paths to be drawn.
 * <dl>
 * <dt><code>android:name</code></dt>
 * <dd>Defines the name of the path.</dd>
 * <dt><code>android:pathData</code></dt>
 * <dd>Defines path data using exactly same format as "d" attribute
 * in the SVG's path data. This is defined in the viewport space.</dd>
 * <dt><code>android:fillColor</code></dt>
 * <dd>Specifies the color used to fill the path. May be a color or, for SDK 24+, a color state list
 * or a gradient color (See {@link android.R.styleable#GradientColor}
 * and {@link android.R.styleable#GradientColorItem}).
 * If this property is animated, any value set by the animation will override the original value.
 * No path fill is drawn if this property is not specified.</dd>
 * <dt><code>android:strokeColor</code></dt>
 * <dd>Specifies the color used to draw the path outline. May be a color or, for SDK 24+, a color
 * state list or a gradient color (See {@link android.R.styleable#GradientColor}
 * and {@link android.R.styleable#GradientColorItem}).
 * If this property is animated, any value set by the animation will override the original value.
 * No path outline is drawn if this property is not specified.</dd>
 * <dt><code>android:strokeWidth</code></dt>
 * <dd>The width a path stroke. Default is 0.</dd>
 * <dt><code>android:strokeAlpha</code></dt>
 * <dd>The opacity of a path stroke. Default is 1.</dd>
 * <dt><code>android:fillAlpha</code></dt>
 * <dd>The opacity to fill the path with. Default is 1.</dd>
 * <dt><code>android:trimPathStart</code></dt>
 * <dd>The fraction of the path to trim from the start, in the range from 0 to 1. Default is 0.</dd>
 * <dt><code>android:trimPathEnd</code></dt>
 * <dd>The fraction of the path to trim from the end, in the range from 0 to 1. Default is 1.</dd>
 * <dt><code>android:trimPathOffset</code></dt>
 * <dd>Shift trim region (allows showed region to include the start and end), in the range
 * from 0 to 1. Default is 0.</dd>
 * <dt><code>android:strokeLineCap</code></dt>
 * <dd>Sets the linecap for a stroked path: butt, round, square. Default is butt.</dd>
 * <dt><code>android:strokeLineJoin</code></dt>
 * <dd>Sets the lineJoin for a stroked path: miter,round,bevel. Default is miter.</dd>
 * <dt><code>android:strokeMiterLimit</code></dt>
 * <dd>Sets the Miter limit for a stroked path. Default is 4.</dd>
 * <dt><code>android:fillType</code></dt>
 * <dd>For SDK 24+, sets the fillType for a path. The types can be either "evenOdd" or "nonZero". They behave the
 * same as SVG's "fill-rule" properties. Default is nonZero. For more details, see
 * <a href="https://www.w3.org/TR/SVG/painting.html#FillRuleProperty">FillRuleProperty</a></dd>
 * </dl></dd>
 *
 * </dl>
 *
 * <dl>
 * <dt><code>&lt;clip-path></code></dt>
 * <dd>Defines path to be the current clip. Note that the clip path only apply to
 * the current group and its children.
 * <dl>
 * <dt><code>android:name</code></dt>
 * <dd>Defines the name of the clip path.</dd>
 * <dd>Animatable : No.</dd>
 * <dt><code>android:pathData</code></dt>
 * <dd>Defines clip path using the same format as "d" attribute
 * in the SVG's path data.</dd>
 * <dd>Animatable : Yes.</dd>
 * </dl></dd>
 * </dl>
 * <li>Here is a simple VectorDrawable in this vectordrawable.xml file.
 * <pre>
 * &lt;vector xmlns:android=&quot;http://schemas.android.com/apk/res/android&quot;
 *     android:height=&quot;64dp&quot;
 *     android:width=&quot;64dp&quot;
 *     android:viewportHeight=&quot;600&quot;
 *     android:viewportWidth=&quot;600&quot; &gt;
 *     &lt;group
 *         android:name=&quot;rotationGroup&quot;
 *         android:pivotX=&quot;300.0&quot;
 *         android:pivotY=&quot;300.0&quot;
 *         android:rotation=&quot;45.0&quot; &gt;
 *         &lt;path
 *             android:name=&quot;v&quot;
 *             android:fillColor=&quot;#000000&quot;
 *             android:pathData=&quot;M300,70 l 0,-70 70,70 0,0 -70,70z&quot; /&gt;
 *     &lt;/group&gt;
 * &lt;/vector&gt;
 * </pre>
 * </li>
 * <h4>Gradient support</h4>
 * We support 3 types of gradients: {@link android.graphics.LinearGradient},
 * {@link android.graphics.RadialGradient}, or {@link android.graphics.SweepGradient}.
 * <p/>
 * And we support all of 3 types of tile modes {@link android.graphics.Shader.TileMode}:
 * CLAMP, REPEAT, MIRROR.
 * <p/>
 * All of the attributes are listed in {@link android.R.styleable#GradientColor}.
 * Note that different attributes are relevant for different types of gradient.
 * <table border="2" align="center" cellpadding="5">
 *     <thead>
 *         <tr>
 *             <th>LinearGradient</th>
 *             <th>RadialGradient</th>
 *             <th>SweepGradient</th>
 *         </tr>
 *     </thead>
 *     <tr>
 *         <td>startColor </td>
 *         <td>startColor</td>
 *         <td>startColor</td>
 *     </tr>
 *     <tr>
 *         <td>centerColor</td>
 *         <td>centerColor</td>
 *         <td>centerColor</td>
 *     </tr>
 *     <tr>
 *         <td>endColor</td>
 *         <td>endColor</td>
 *         <td>endColor</td>
 *     </tr>
 *     <tr>
 *         <td>type</td>
 *         <td>type</td>
 *         <td>type</td>
 *     </tr>
 *     <tr>
 *         <td>tileMode</td>
 *         <td>tileMode</td>
 *         <td>tileMode</td>
 *     </tr>
 *     <tr>
 *         <td>startX</td>
 *         <td>centerX</td>
 *         <td>centerX</td>
 *     </tr>
 *     <tr>
 *         <td>startY</td>
 *         <td>centerY</td>
 *         <td>centerY</td>
 *     </tr>
 *     <tr>
 *         <td>endX</td>
 *         <td>gradientRadius</td>
 *         <td></td>
 *     </tr>
 *     <tr>
 *         <td>endY</td>
 *         <td></td>
 *         <td></td>
 *     </tr>
 * </table>
 * <p/>
 * Also note that if any color item {@link android.R.styleable#GradientColorItem} is defined, then
 * startColor, centerColor and endColor will be ignored.
 * <p/>
 * See more details in {@link android.R.styleable#GradientColor} and
 * {@link android.R.styleable#GradientColorItem}.
 * <p/>
 * Here is a simple example that defines a linear gradient.
 * <pre>
 * &lt;gradient xmlns:android="http://schemas.android.com/apk/res/android"
 *     android:startColor="?android:attr/colorPrimary"
 *     android:endColor="?android:attr/colorControlActivated"
 *     android:centerColor="#f00"
 *     android:startX="0"
 *     android:startY="0"
 *     android:endX="100"
 *     android:endY="100"
 *     android:type="linear"&gt;
 * &lt;/gradient&gt;
 * </pre>
 * And here is a simple example that defines a radial gradient using color items.
 * <pre>
 * &lt;gradient xmlns:android="http://schemas.android.com/apk/res/android"
 *     android:centerX="300"
 *     android:centerY="300"
 *     android:gradientRadius="100"
 *     android:type="radial"&gt;
 *     &lt;item android:offset="0.1" android:color="#0ff"/&gt;
 *     &lt;item android:offset="0.4" android:color="#fff"/&gt;
 *     &lt;item android:offset="0.9" android:color="#ff0"/&gt;
 * &lt;/gradient&gt;
 * </pre>
 *
 */

public class VectorDrawable extends Drawable {
    private static final String LOGTAG = VectorDrawable.class.getSimpleName();

    private static final String SHAPE_CLIP_PATH = "clip-path";
    private static final String SHAPE_GROUP = "group";
    private static final String SHAPE_PATH = "path";
    private static final String SHAPE_VECTOR = "vector";

    private VectorDrawableState mVectorState;

    private PorterDuffColorFilter mTintFilter;
    private ColorFilter mColorFilter;

    private boolean mMutated;

    /** The density of the display on which this drawable will be rendered. */
    private int mTargetDensity;

    // Given the virtual display setup, the dpi can be different than the inflation's dpi.
    // Therefore, we need to scale the values we got from the getDimension*().
    private int mDpiScaledWidth = 0;
    private int mDpiScaledHeight = 0;
    private Insets mDpiScaledInsets = Insets.NONE;

    /** Whether DPI-scaled width, height, and insets need to be updated. */
    private boolean mDpiScaledDirty = true;

    // Temp variable, only for saving "new" operation at the draw() time.
    private final Rect mTmpBounds = new Rect();

    public VectorDrawable() {
        this(null, null);
    }

    /**
     * The one constructor to rule them all. This is called by all public
     * constructors to set the state and initialize local properties.
     */
    private VectorDrawable(@Nullable VectorDrawableState state, @Nullable Resources res) {
        // As the mutable, not-thread-safe native instance is stored in VectorDrawableState, we
        // need to always do a defensive copy even if mutate() isn't called. Otherwise
        // draw() being called on 2 different VectorDrawable instances could still hit the same
        // underlying native object.
        mVectorState = new VectorDrawableState(state);
        updateLocalState(res);
    }

    /**
     * Initializes local dynamic properties from state. This should be called
     * after significant state changes, e.g. from the One True Constructor and
     * after inflating or applying a theme.
     *
     * @param res resources of the context in which the drawable will be
     *            displayed, or {@code null} to use the constant state defaults
     */
    private void updateLocalState(Resources res) {
        final int density = Drawable.resolveDensity(res, mVectorState.mDensity);
        if (mTargetDensity != density) {
            mTargetDensity = density;
            mDpiScaledDirty = true;
        }

        mTintFilter = updateTintFilter(mTintFilter, mVectorState.mTint, mVectorState.mTintMode);
    }

    @Override
    public Drawable mutate() {
        if (!mMutated && super.mutate() == this) {
            mVectorState = new VectorDrawableState(mVectorState);
            mMutated = true;
        }
        return this;
    }

    /**
     * @hide
     */
    public void clearMutated() {
        super.clearMutated();
        mMutated = false;
    }

    Object getTargetByName(String name) {
        return mVectorState.mVGTargetsMap.get(name);
    }

    @Override
    public ConstantState getConstantState() {
        mVectorState.mChangingConfigurations = getChangingConfigurations();
        return mVectorState;
    }

    @Override
    public void draw(Canvas canvas) {
        // We will offset the bounds for drawBitmap, so copyBounds() here instead
        // of getBounds().
        copyBounds(mTmpBounds);
        if (mTmpBounds.width() <= 0 || mTmpBounds.height() <= 0) {
            // Nothing to draw
            return;
        }

        // Color filters always override tint filters.
        final ColorFilter colorFilter = (mColorFilter == null ? mTintFilter : mColorFilter);
        final long colorFilterNativeInstance = colorFilter == null ? 0 :
                colorFilter.getNativeInstance();
        boolean canReuseCache = mVectorState.canReuseCache();
        int pixelCount = nDraw(mVectorState.getNativeRenderer(), canvas.getNativeCanvasWrapper(),
                colorFilterNativeInstance, mTmpBounds, needMirroring(),
                canReuseCache);
        if (pixelCount == 0) {
            // Invalid canvas matrix or drawable bounds. This would not affect existing bitmap
            // cache, if any.
            return;
        }

        int deltaInBytes;
        // Track different bitmap cache based whether the canvas is hw accelerated. By doing so,
        // we don't over count bitmap cache allocation: if the input canvas is always of the same
        // type, only one bitmap cache is allocated.
        if (canvas.isHardwareAccelerated()) {
            // Each pixel takes 4 bytes.
            deltaInBytes = (pixelCount - mVectorState.mLastHWCachePixelCount) * 4;
            mVectorState.mLastHWCachePixelCount = pixelCount;
        } else {
            // Each pixel takes 4 bytes.
            deltaInBytes = (pixelCount - mVectorState.mLastSWCachePixelCount) * 4;
            mVectorState.mLastSWCachePixelCount = pixelCount;
        }
        if (deltaInBytes > 0) {
            VMRuntime.getRuntime().registerNativeAllocation(deltaInBytes);
        } else if (deltaInBytes < 0) {
            VMRuntime.getRuntime().registerNativeFree(-deltaInBytes);
        }
    }


    @Override
    public int getAlpha() {
        return (int) (mVectorState.getAlpha() * 255);
    }

    @Override
    public void setAlpha(int alpha) {
        if (mVectorState.setAlpha(alpha / 255f)) {
            invalidateSelf();
        }
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        mColorFilter = colorFilter;
        invalidateSelf();
    }

    @Override
    public ColorFilter getColorFilter() {
        return mColorFilter;
    }

    @Override
    public void setTintList(ColorStateList tint) {
        final VectorDrawableState state = mVectorState;
        if (state.mTint != tint) {
            state.mTint = tint;
            mTintFilter = updateTintFilter(mTintFilter, tint, state.mTintMode);
            invalidateSelf();
        }
    }

    @Override
    public void setTintMode(Mode tintMode) {
        final VectorDrawableState state = mVectorState;
        if (state.mTintMode != tintMode) {
            state.mTintMode = tintMode;
            mTintFilter = updateTintFilter(mTintFilter, state.mTint, tintMode);
            invalidateSelf();
        }
    }

    @Override
    public boolean isStateful() {
        return super.isStateful() || (mVectorState != null && mVectorState.isStateful());
    }

    /** @hide */
    @Override
    public boolean hasFocusStateSpecified() {
        return mVectorState != null && mVectorState.hasFocusStateSpecified();
    }

    @Override
    protected boolean onStateChange(int[] stateSet) {
        boolean changed = false;

        // When the VD is stateful, we need to mutate the drawable such that we don't share the
        // cache bitmap with others. Such that the state change only affect this new cached bitmap.
        if (isStateful()) {
            mutate();
        }
        final VectorDrawableState state = mVectorState;
        if (state.onStateChange(stateSet)) {
            changed = true;
            state.mCacheDirty = true;
        }
        if (state.mTint != null && state.mTintMode != null) {
            mTintFilter = updateTintFilter(mTintFilter, state.mTint, state.mTintMode);
            changed = true;
        }

        return changed;
    }

    @Override
    public int getOpacity() {
        // We can't tell whether the drawable is fully opaque unless we examine all the pixels,
        // but we could tell it is transparent if the root alpha is 0.
        return getAlpha() == 0 ? PixelFormat.TRANSPARENT : PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        if (mDpiScaledDirty) {
            computeVectorSize();
        }
        return mDpiScaledWidth;
    }

    @Override
    public int getIntrinsicHeight() {
        if (mDpiScaledDirty) {
            computeVectorSize();
        }
        return mDpiScaledHeight;
    }

    /** @hide */
    @Override
    public Insets getOpticalInsets() {
        if (mDpiScaledDirty) {
            computeVectorSize();
        }
        return mDpiScaledInsets;
    }

    /*
     * Update local dimensions to adjust for a target density that may differ
     * from the source density against which the constant state was loaded.
     */
    void computeVectorSize() {
        final Insets opticalInsets = mVectorState.mOpticalInsets;

        final int sourceDensity = mVectorState.mDensity;
        final int targetDensity = mTargetDensity;
        if (targetDensity != sourceDensity) {
            mDpiScaledWidth = Drawable.scaleFromDensity(mVectorState.mBaseWidth, sourceDensity,
                    targetDensity, true);
            mDpiScaledHeight = Drawable.scaleFromDensity(mVectorState.mBaseHeight,sourceDensity,
                    targetDensity, true);
            final int left = Drawable.scaleFromDensity(
                    opticalInsets.left, sourceDensity, targetDensity, false);
            final int right = Drawable.scaleFromDensity(
                    opticalInsets.right, sourceDensity, targetDensity, false);
            final int top = Drawable.scaleFromDensity(
                    opticalInsets.top, sourceDensity, targetDensity, false);
            final int bottom = Drawable.scaleFromDensity(
                    opticalInsets.bottom, sourceDensity, targetDensity, false);
            mDpiScaledInsets = Insets.of(left, top, right, bottom);
        } else {
            mDpiScaledWidth = mVectorState.mBaseWidth;
            mDpiScaledHeight = mVectorState.mBaseHeight;
            mDpiScaledInsets = opticalInsets;
        }

        mDpiScaledDirty = false;
    }

    @Override
    public boolean canApplyTheme() {
        return (mVectorState != null && mVectorState.canApplyTheme()) || super.canApplyTheme();
    }

    @Override
    public void applyTheme(Theme t) {
        super.applyTheme(t);

        final VectorDrawableState state = mVectorState;
        if (state == null) {
            return;
        }

        final boolean changedDensity = mVectorState.setDensity(
                Drawable.resolveDensity(t.getResources(), 0));
        mDpiScaledDirty |= changedDensity;

        if (state.mThemeAttrs != null) {
            final TypedArray a = t.resolveAttributes(
                    state.mThemeAttrs, R.styleable.VectorDrawable);
            try {
                state.mCacheDirty = true;
                updateStateFromTypedArray(a);
            } catch (XmlPullParserException e) {
                throw new RuntimeException(e);
            } finally {
                a.recycle();
            }

            // May have changed size.
            mDpiScaledDirty = true;
        }

        // Apply theme to contained color state list.
        if (state.mTint != null && state.mTint.canApplyTheme()) {
            state.mTint = state.mTint.obtainForTheme(t);
        }

        if (mVectorState != null && mVectorState.canApplyTheme()) {
            mVectorState.applyTheme(t);
        }

        // Update local properties.
        updateLocalState(t.getResources());
    }

    /**
     * The size of a pixel when scaled from the intrinsic dimension to the viewport dimension.
     * This is used to calculate the path animation accuracy.
     *
     * @hide
     */
    public float getPixelSize() {
        if (mVectorState == null ||
                mVectorState.mBaseWidth == 0 ||
                mVectorState.mBaseHeight == 0 ||
                mVectorState.mViewportHeight == 0 ||
                mVectorState.mViewportWidth == 0) {
            return 1; // fall back to 1:1 pixel mapping.
        }
        float intrinsicWidth = mVectorState.mBaseWidth;
        float intrinsicHeight = mVectorState.mBaseHeight;
        float viewportWidth = mVectorState.mViewportWidth;
        float viewportHeight = mVectorState.mViewportHeight;
        float scaleX = viewportWidth / intrinsicWidth;
        float scaleY = viewportHeight / intrinsicHeight;
        return Math.min(scaleX, scaleY);
    }

    /** @hide */
    public static VectorDrawable create(Resources resources, int rid) {
        try {
            final XmlPullParser parser = resources.getXml(rid);
            final AttributeSet attrs = Xml.asAttributeSet(parser);
            int type;
            while ((type=parser.next()) != XmlPullParser.START_TAG &&
                    type != XmlPullParser.END_DOCUMENT) {
                // Empty loop
            }
            if (type != XmlPullParser.START_TAG) {
                throw new XmlPullParserException("No start tag found");
            }

            final VectorDrawable drawable = new VectorDrawable();
            drawable.inflate(resources, parser, attrs);

            return drawable;
        } catch (XmlPullParserException e) {
            Log.e(LOGTAG, "parser error", e);
        } catch (IOException e) {
            Log.e(LOGTAG, "parser error", e);
        }
        return null;
    }

    @Override
    public void inflate(@NonNull Resources r, @NonNull XmlPullParser parser,
            @NonNull AttributeSet attrs, @Nullable Theme theme)
            throws XmlPullParserException, IOException {
        try {
            Trace.traceBegin(Trace.TRACE_TAG_RESOURCES, "VectorDrawable#inflate");
            if (mVectorState.mRootGroup != null || mVectorState.mNativeTree != null) {
                // This VD has been used to display other VD resource content, clean up.
                if (mVectorState.mRootGroup != null) {
                    // Subtract the native allocation for all the nodes.
                    VMRuntime.getRuntime().registerNativeFree(
                            mVectorState.mRootGroup.getNativeSize());
                    // Remove child nodes' reference to tree
                    mVectorState.mRootGroup.setTree(null);
                }
                mVectorState.mRootGroup = new VGroup();
                if (mVectorState.mNativeTree != null) {
                    // Subtract the native allocation for the tree wrapper, which contains root node
                    // as well as rendering related data.
                    VMRuntime.getRuntime().registerNativeFree(mVectorState.NATIVE_ALLOCATION_SIZE);
                    mVectorState.mNativeTree.release();
                }
                mVectorState.createNativeTree(mVectorState.mRootGroup);
            }
            final VectorDrawableState state = mVectorState;
            state.setDensity(Drawable.resolveDensity(r, 0));

            final TypedArray a = obtainAttributes(r, theme, attrs, R.styleable.VectorDrawable);
            updateStateFromTypedArray(a);
            a.recycle();

            mDpiScaledDirty = true;

            state.mCacheDirty = true;
            inflateChildElements(r, parser, attrs, theme);

            state.onTreeConstructionFinished();
            // Update local properties.
            updateLocalState(r);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_RESOURCES);
        }
    }

    private void updateStateFromTypedArray(TypedArray a) throws XmlPullParserException {
        final VectorDrawableState state = mVectorState;

        // Account for any configuration changes.
        state.mChangingConfigurations |= a.getChangingConfigurations();

        // Extract the theme attributes, if any.
        state.mThemeAttrs = a.extractThemeAttrs();

        final int tintMode = a.getInt(R.styleable.VectorDrawable_tintMode, -1);
        if (tintMode != -1) {
            state.mTintMode = Drawable.parseTintMode(tintMode, Mode.SRC_IN);
        }

        final ColorStateList tint = a.getColorStateList(R.styleable.VectorDrawable_tint);
        if (tint != null) {
            state.mTint = tint;
        }

        state.mAutoMirrored = a.getBoolean(
                R.styleable.VectorDrawable_autoMirrored, state.mAutoMirrored);

        float viewportWidth = a.getFloat(
                R.styleable.VectorDrawable_viewportWidth, state.mViewportWidth);
        float viewportHeight = a.getFloat(
                R.styleable.VectorDrawable_viewportHeight, state.mViewportHeight);
        state.setViewportSize(viewportWidth, viewportHeight);

        if (state.mViewportWidth <= 0) {
            throw new XmlPullParserException(a.getPositionDescription() +
                    "<vector> tag requires viewportWidth > 0");
        } else if (state.mViewportHeight <= 0) {
            throw new XmlPullParserException(a.getPositionDescription() +
                    "<vector> tag requires viewportHeight > 0");
        }

        state.mBaseWidth = a.getDimensionPixelSize(
                R.styleable.VectorDrawable_width, state.mBaseWidth);
        state.mBaseHeight = a.getDimensionPixelSize(
                R.styleable.VectorDrawable_height, state.mBaseHeight);

        if (state.mBaseWidth <= 0) {
            throw new XmlPullParserException(a.getPositionDescription() +
                    "<vector> tag requires width > 0");
        } else if (state.mBaseHeight <= 0) {
            throw new XmlPullParserException(a.getPositionDescription() +
                    "<vector> tag requires height > 0");
        }

        final int insetLeft = a.getDimensionPixelOffset(
                R.styleable.VectorDrawable_opticalInsetLeft, state.mOpticalInsets.left);
        final int insetTop = a.getDimensionPixelOffset(
                R.styleable.VectorDrawable_opticalInsetTop, state.mOpticalInsets.top);
        final int insetRight = a.getDimensionPixelOffset(
                R.styleable.VectorDrawable_opticalInsetRight, state.mOpticalInsets.right);
        final int insetBottom = a.getDimensionPixelOffset(
                R.styleable.VectorDrawable_opticalInsetBottom, state.mOpticalInsets.bottom);
        state.mOpticalInsets = Insets.of(insetLeft, insetTop, insetRight, insetBottom);

        final float alphaInFloat = a.getFloat(
                R.styleable.VectorDrawable_alpha, state.getAlpha());
        state.setAlpha(alphaInFloat);

        final String name = a.getString(R.styleable.VectorDrawable_name);
        if (name != null) {
            state.mRootName = name;
            state.mVGTargetsMap.put(name, state);
        }
    }

    private void inflateChildElements(Resources res, XmlPullParser parser, AttributeSet attrs,
            Theme theme) throws XmlPullParserException, IOException {
        final VectorDrawableState state = mVectorState;
        boolean noPathTag = true;

        // Use a stack to help to build the group tree.
        // The top of the stack is always the current group.
        final Stack<VGroup> groupStack = new Stack<VGroup>();
        groupStack.push(state.mRootGroup);

        int eventType = parser.getEventType();
        final int innerDepth = parser.getDepth() + 1;

        // Parse everything until the end of the vector element.
        while (eventType != XmlPullParser.END_DOCUMENT
                && (parser.getDepth() >= innerDepth || eventType != XmlPullParser.END_TAG)) {
            if (eventType == XmlPullParser.START_TAG) {
                final String tagName = parser.getName();
                final VGroup currentGroup = groupStack.peek();

                if (SHAPE_PATH.equals(tagName)) {
                    final VFullPath path = new VFullPath();
                    path.inflate(res, attrs, theme);
                    currentGroup.addChild(path);
                    if (path.getPathName() != null) {
                        state.mVGTargetsMap.put(path.getPathName(), path);
                    }
                    noPathTag = false;
                    state.mChangingConfigurations |= path.mChangingConfigurations;
                } else if (SHAPE_CLIP_PATH.equals(tagName)) {
                    final VClipPath path = new VClipPath();
                    path.inflate(res, attrs, theme);
                    currentGroup.addChild(path);
                    if (path.getPathName() != null) {
                        state.mVGTargetsMap.put(path.getPathName(), path);
                    }
                    state.mChangingConfigurations |= path.mChangingConfigurations;
                } else if (SHAPE_GROUP.equals(tagName)) {
                    VGroup newChildGroup = new VGroup();
                    newChildGroup.inflate(res, attrs, theme);
                    currentGroup.addChild(newChildGroup);
                    groupStack.push(newChildGroup);
                    if (newChildGroup.getGroupName() != null) {
                        state.mVGTargetsMap.put(newChildGroup.getGroupName(),
                                newChildGroup);
                    }
                    state.mChangingConfigurations |= newChildGroup.mChangingConfigurations;
                }
            } else if (eventType == XmlPullParser.END_TAG) {
                final String tagName = parser.getName();
                if (SHAPE_GROUP.equals(tagName)) {
                    groupStack.pop();
                }
            }
            eventType = parser.next();
        }

        if (noPathTag) {
            final StringBuffer tag = new StringBuffer();

            if (tag.length() > 0) {
                tag.append(" or ");
            }
            tag.append(SHAPE_PATH);

            throw new XmlPullParserException("no " + tag + " defined");
        }
    }

    @Override
    public @Config int getChangingConfigurations() {
        return super.getChangingConfigurations() | mVectorState.getChangingConfigurations();
    }

    void setAllowCaching(boolean allowCaching) {
        nSetAllowCaching(mVectorState.getNativeRenderer(), allowCaching);
    }

    private boolean needMirroring() {
        return isAutoMirrored() && getLayoutDirection() == LayoutDirection.RTL;
    }

    @Override
    public void setAutoMirrored(boolean mirrored) {
        if (mVectorState.mAutoMirrored != mirrored) {
            mVectorState.mAutoMirrored = mirrored;
            invalidateSelf();
        }
    }

    @Override
    public boolean isAutoMirrored() {
        return mVectorState.mAutoMirrored;
    }

    /**
     * @hide
     */
    public long getNativeTree() {
        return mVectorState.getNativeRenderer();
    }

    /**
     * @hide
     */
    public void setAntiAlias(boolean aa) {
        nSetAntiAlias(mVectorState.mNativeTree.get(), aa);
    }

    static class VectorDrawableState extends ConstantState {
        // Variables below need to be copied (deep copy if applicable) for mutation.
        int[] mThemeAttrs;
        @Config int mChangingConfigurations;
        ColorStateList mTint = null;
        Mode mTintMode = DEFAULT_TINT_MODE;
        boolean mAutoMirrored;

        int mBaseWidth = 0;
        int mBaseHeight = 0;
        float mViewportWidth = 0;
        float mViewportHeight = 0;
        Insets mOpticalInsets = Insets.NONE;
        String mRootName = null;
        VGroup mRootGroup;
        VirtualRefBasePtr mNativeTree = null;

        int mDensity = DisplayMetrics.DENSITY_DEFAULT;
        final ArrayMap<String, Object> mVGTargetsMap = new ArrayMap<>();

        // Fields for cache
        int[] mCachedThemeAttrs;
        ColorStateList mCachedTint;
        Mode mCachedTintMode;
        boolean mCachedAutoMirrored;
        boolean mCacheDirty;

        // Since sw canvas and hw canvas uses different bitmap caches, we track the allocation of
        // these bitmaps separately.
        int mLastSWCachePixelCount = 0;
        int mLastHWCachePixelCount = 0;

        final static Property<VectorDrawableState, Float> ALPHA =
                new FloatProperty<VectorDrawableState>("alpha") {
                    @Override
                    public void setValue(VectorDrawableState state, float value) {
                        state.setAlpha(value);
                    }

                    @Override
                    public Float get(VectorDrawableState state) {
                        return state.getAlpha();
                    }
                };

        Property getProperty(String propertyName) {
            if (ALPHA.getName().equals(propertyName)) {
                return ALPHA;
            }
            return null;
        }

        // This tracks the total native allocation for all the nodes.
        private int mAllocationOfAllNodes = 0;

        private static final int NATIVE_ALLOCATION_SIZE = 316;

        // If copy is not null, deep copy the given VectorDrawableState. Otherwise, create a
        // native vector drawable tree with an empty root group.
        public VectorDrawableState(VectorDrawableState copy) {
            if (copy != null) {
                mThemeAttrs = copy.mThemeAttrs;
                mChangingConfigurations = copy.mChangingConfigurations;
                mTint = copy.mTint;
                mTintMode = copy.mTintMode;
                mAutoMirrored = copy.mAutoMirrored;
                mRootGroup = new VGroup(copy.mRootGroup, mVGTargetsMap);
                createNativeTreeFromCopy(copy, mRootGroup);

                mBaseWidth = copy.mBaseWidth;
                mBaseHeight = copy.mBaseHeight;
                setViewportSize(copy.mViewportWidth, copy.mViewportHeight);
                mOpticalInsets = copy.mOpticalInsets;

                mRootName = copy.mRootName;
                mDensity = copy.mDensity;
                if (copy.mRootName != null) {
                    mVGTargetsMap.put(copy.mRootName, this);
                }
            } else {
                mRootGroup = new VGroup();
                createNativeTree(mRootGroup);
            }
            onTreeConstructionFinished();
        }

        private void createNativeTree(VGroup rootGroup) {
            mNativeTree = new VirtualRefBasePtr(nCreateTree(rootGroup.mNativePtr));
            // Register tree size
            VMRuntime.getRuntime().registerNativeAllocation(NATIVE_ALLOCATION_SIZE);
        }

        // Create a new native tree with the given root group, and copy the properties from the
        // given VectorDrawableState's native tree.
        private void createNativeTreeFromCopy(VectorDrawableState copy, VGroup rootGroup) {
            mNativeTree = new VirtualRefBasePtr(nCreateTreeFromCopy(
                    copy.mNativeTree.get(), rootGroup.mNativePtr));
            // Register tree size
            VMRuntime.getRuntime().registerNativeAllocation(NATIVE_ALLOCATION_SIZE);
        }

        // This should be called every time after a new RootGroup and all its subtrees are created
        // (i.e. in constructors of VectorDrawableState and in inflate).
        void onTreeConstructionFinished() {
            mRootGroup.setTree(mNativeTree);
            mAllocationOfAllNodes = mRootGroup.getNativeSize();
            VMRuntime.getRuntime().registerNativeAllocation(mAllocationOfAllNodes);
        }

        long getNativeRenderer() {
            if (mNativeTree == null) {
                return 0;
            }
            return mNativeTree.get();
        }

        public boolean canReuseCache() {
            if (!mCacheDirty
                    && mCachedThemeAttrs == mThemeAttrs
                    && mCachedTint == mTint
                    && mCachedTintMode == mTintMode
                    && mCachedAutoMirrored == mAutoMirrored) {
                return true;
            }
            updateCacheStates();
            return false;
        }

        public void updateCacheStates() {
            // Use shallow copy here and shallow comparison in canReuseCache(),
            // likely hit cache miss more, but practically not much difference.
            mCachedThemeAttrs = mThemeAttrs;
            mCachedTint = mTint;
            mCachedTintMode = mTintMode;
            mCachedAutoMirrored = mAutoMirrored;
            mCacheDirty = false;
        }

        public void applyTheme(Theme t) {
            mRootGroup.applyTheme(t);
        }

        @Override
        public boolean canApplyTheme() {
            return mThemeAttrs != null
                    || (mRootGroup != null && mRootGroup.canApplyTheme())
                    || (mTint != null && mTint.canApplyTheme())
                    || super.canApplyTheme();
        }

        @Override
        public Drawable newDrawable() {
            return new VectorDrawable(this, null);
        }

        @Override
        public Drawable newDrawable(Resources res) {
            return new VectorDrawable(this, res);
        }

        @Override
        public @Config int getChangingConfigurations() {
            return mChangingConfigurations
                    | (mTint != null ? mTint.getChangingConfigurations() : 0);
        }

        public boolean isStateful() {
            return (mTint != null && mTint.isStateful())
                    || (mRootGroup != null && mRootGroup.isStateful());
        }

        public boolean hasFocusStateSpecified() {
            return mTint != null && mTint.hasFocusStateSpecified()
                    || (mRootGroup != null && mRootGroup.hasFocusStateSpecified());
        }

        void setViewportSize(float viewportWidth, float viewportHeight) {
            mViewportWidth = viewportWidth;
            mViewportHeight = viewportHeight;
            nSetRendererViewportSize(getNativeRenderer(), viewportWidth, viewportHeight);
        }

        public final boolean setDensity(int targetDensity) {
            if (mDensity != targetDensity) {
                final int sourceDensity = mDensity;
                mDensity = targetDensity;
                applyDensityScaling(sourceDensity, targetDensity);
                return true;
            }
            return false;
        }

        private void applyDensityScaling(int sourceDensity, int targetDensity) {
            mBaseWidth = Drawable.scaleFromDensity(mBaseWidth, sourceDensity, targetDensity, true);
            mBaseHeight = Drawable.scaleFromDensity(mBaseHeight, sourceDensity, targetDensity,
                    true);

            final int insetLeft = Drawable.scaleFromDensity(
                    mOpticalInsets.left, sourceDensity, targetDensity, false);
            final int insetTop = Drawable.scaleFromDensity(
                    mOpticalInsets.top, sourceDensity, targetDensity, false);
            final int insetRight = Drawable.scaleFromDensity(
                    mOpticalInsets.right, sourceDensity, targetDensity, false);
            final int insetBottom = Drawable.scaleFromDensity(
                    mOpticalInsets.bottom, sourceDensity, targetDensity, false);
            mOpticalInsets = Insets.of(insetLeft, insetTop, insetRight, insetBottom);
        }

        public boolean onStateChange(int[] stateSet) {
            return mRootGroup.onStateChange(stateSet);
        }

        @Override
        public void finalize() throws Throwable {
            super.finalize();
            int bitmapCacheSize = mLastHWCachePixelCount * 4 + mLastSWCachePixelCount * 4;
            VMRuntime.getRuntime().registerNativeFree(NATIVE_ALLOCATION_SIZE
                    + mAllocationOfAllNodes + bitmapCacheSize);
        }

        /**
         * setAlpha() and getAlpha() are used mostly for animation purpose. Return true if alpha
         * has changed.
         */
        public boolean setAlpha(float alpha) {
            return nSetRootAlpha(mNativeTree.get(), alpha);
        }

        @SuppressWarnings("unused")
        public float getAlpha() {
            return nGetRootAlpha(mNativeTree.get());
        }
    }

    static class VGroup extends VObject {
        private static final int ROTATION_INDEX = 0;
        private static final int PIVOT_X_INDEX = 1;
        private static final int PIVOT_Y_INDEX = 2;
        private static final int SCALE_X_INDEX = 3;
        private static final int SCALE_Y_INDEX = 4;
        private static final int TRANSLATE_X_INDEX = 5;
        private static final int TRANSLATE_Y_INDEX = 6;
        private static final int TRANSFORM_PROPERTY_COUNT = 7;

        private static final int NATIVE_ALLOCATION_SIZE = 100;

        private static final HashMap<String, Integer> sPropertyIndexMap =
                new HashMap<String, Integer>() {
                    {
                        put("translateX", TRANSLATE_X_INDEX);
                        put("translateY", TRANSLATE_Y_INDEX);
                        put("scaleX", SCALE_X_INDEX);
                        put("scaleY", SCALE_Y_INDEX);
                        put("pivotX", PIVOT_X_INDEX);
                        put("pivotY", PIVOT_Y_INDEX);
                        put("rotation", ROTATION_INDEX);
                    }
                };

        static int getPropertyIndex(String propertyName) {
            if (sPropertyIndexMap.containsKey(propertyName)) {
                return sPropertyIndexMap.get(propertyName);
            } else {
                // property not found
                return -1;
            }
        }

        // Below are the Properties that wrap the setters to avoid reflection overhead in animations
        private static final Property<VGroup, Float> TRANSLATE_X =
                new FloatProperty<VGroup> ("translateX") {
                    @Override
                    public void setValue(VGroup object, float value) {
                        object.setTranslateX(value);
                    }

                    @Override
                    public Float get(VGroup object) {
                        return object.getTranslateX();
                    }
                };

        private static final Property<VGroup, Float> TRANSLATE_Y =
                new FloatProperty<VGroup> ("translateY") {
                    @Override
                    public void setValue(VGroup object, float value) {
                        object.setTranslateY(value);
                    }

                    @Override
                    public Float get(VGroup object) {
                        return object.getTranslateY();
                    }
        };

        private static final Property<VGroup, Float> SCALE_X =
                new FloatProperty<VGroup> ("scaleX") {
                    @Override
                    public void setValue(VGroup object, float value) {
                        object.setScaleX(value);
                    }

                    @Override
                    public Float get(VGroup object) {
                        return object.getScaleX();
                    }
                };

        private static final Property<VGroup, Float> SCALE_Y =
                new FloatProperty<VGroup> ("scaleY") {
                    @Override
                    public void setValue(VGroup object, float value) {
                        object.setScaleY(value);
                    }

                    @Override
                    public Float get(VGroup object) {
                        return object.getScaleY();
                    }
                };

        private static final Property<VGroup, Float> PIVOT_X =
                new FloatProperty<VGroup> ("pivotX") {
                    @Override
                    public void setValue(VGroup object, float value) {
                        object.setPivotX(value);
                    }

                    @Override
                    public Float get(VGroup object) {
                        return object.getPivotX();
                    }
                };

        private static final Property<VGroup, Float> PIVOT_Y =
                new FloatProperty<VGroup> ("pivotY") {
                    @Override
                    public void setValue(VGroup object, float value) {
                        object.setPivotY(value);
                    }

                    @Override
                    public Float get(VGroup object) {
                        return object.getPivotY();
                    }
                };

        private static final Property<VGroup, Float> ROTATION =
                new FloatProperty<VGroup> ("rotation") {
                    @Override
                    public void setValue(VGroup object, float value) {
                        object.setRotation(value);
                    }

                    @Override
                    public Float get(VGroup object) {
                        return object.getRotation();
                    }
                };

        private static final HashMap<String, Property> sPropertyMap =
                new HashMap<String, Property>() {
                    {
                        put("translateX", TRANSLATE_X);
                        put("translateY", TRANSLATE_Y);
                        put("scaleX", SCALE_X);
                        put("scaleY", SCALE_Y);
                        put("pivotX", PIVOT_X);
                        put("pivotY", PIVOT_Y);
                        put("rotation", ROTATION);
                    }
                };
        // Temp array to store transform values obtained from native.
        private float[] mTransform;
        /////////////////////////////////////////////////////
        // Variables below need to be copied (deep copy if applicable) for mutation.
        private final ArrayList<VObject> mChildren = new ArrayList<>();
        private boolean mIsStateful;

        // mLocalMatrix is updated based on the update of transformation information,
        // either parsed from the XML or by animation.
        private @Config int mChangingConfigurations;
        private int[] mThemeAttrs;
        private String mGroupName = null;

        // The native object will be created in the constructor and will be destroyed in native
        // when the neither java nor native has ref to the tree. This pointer should be valid
        // throughout this VGroup Java object's life.
        private final long mNativePtr;
        public VGroup(VGroup copy, ArrayMap<String, Object> targetsMap) {

            mIsStateful = copy.mIsStateful;
            mThemeAttrs = copy.mThemeAttrs;
            mGroupName = copy.mGroupName;
            mChangingConfigurations = copy.mChangingConfigurations;
            if (mGroupName != null) {
                targetsMap.put(mGroupName, this);
            }
            mNativePtr = nCreateGroup(copy.mNativePtr);

            final ArrayList<VObject> children = copy.mChildren;
            for (int i = 0; i < children.size(); i++) {
                final VObject copyChild = children.get(i);
                if (copyChild instanceof VGroup) {
                    final VGroup copyGroup = (VGroup) copyChild;
                    addChild(new VGroup(copyGroup, targetsMap));
                } else {
                    final VPath newPath;
                    if (copyChild instanceof VFullPath) {
                        newPath = new VFullPath((VFullPath) copyChild);
                    } else if (copyChild instanceof VClipPath) {
                        newPath = new VClipPath((VClipPath) copyChild);
                    } else {
                        throw new IllegalStateException("Unknown object in the tree!");
                    }
                    addChild(newPath);
                    if (newPath.mPathName != null) {
                        targetsMap.put(newPath.mPathName, newPath);
                    }
                }
            }
        }

        public VGroup() {
            mNativePtr = nCreateGroup();
        }

        Property getProperty(String propertyName) {
            if (sPropertyMap.containsKey(propertyName)) {
                return sPropertyMap.get(propertyName);
            } else {
                // property not found
                return null;
            }
        }

        public String getGroupName() {
            return mGroupName;
        }

        public void addChild(VObject child) {
            nAddChild(mNativePtr, child.getNativePtr());
            mChildren.add(child);
            mIsStateful |= child.isStateful();
        }

        @Override
        public void setTree(VirtualRefBasePtr treeRoot) {
            super.setTree(treeRoot);
            for (int i = 0; i < mChildren.size(); i++) {
                mChildren.get(i).setTree(treeRoot);
            }
        }

        @Override
        public long getNativePtr() {
            return mNativePtr;
        }

        @Override
        public void inflate(Resources res, AttributeSet attrs, Theme theme) {
            final TypedArray a = obtainAttributes(res, theme, attrs,
                    R.styleable.VectorDrawableGroup);
            updateStateFromTypedArray(a);
            a.recycle();
        }

        void updateStateFromTypedArray(TypedArray a) {
            // Account for any configuration changes.
            mChangingConfigurations |= a.getChangingConfigurations();

            // Extract the theme attributes, if any.
            mThemeAttrs = a.extractThemeAttrs();
            if (mTransform == null) {
                // Lazy initialization: If the group is created through copy constructor, this may
                // never get called.
                mTransform = new float[TRANSFORM_PROPERTY_COUNT];
            }
            boolean success = nGetGroupProperties(mNativePtr, mTransform, TRANSFORM_PROPERTY_COUNT);
            if (!success) {
                throw new RuntimeException("Error: inconsistent property count");
            }
            float rotate = a.getFloat(R.styleable.VectorDrawableGroup_rotation,
                    mTransform[ROTATION_INDEX]);
            float pivotX = a.getFloat(R.styleable.VectorDrawableGroup_pivotX,
                    mTransform[PIVOT_X_INDEX]);
            float pivotY = a.getFloat(R.styleable.VectorDrawableGroup_pivotY,
                    mTransform[PIVOT_Y_INDEX]);
            float scaleX = a.getFloat(R.styleable.VectorDrawableGroup_scaleX,
                    mTransform[SCALE_X_INDEX]);
            float scaleY = a.getFloat(R.styleable.VectorDrawableGroup_scaleY,
                    mTransform[SCALE_Y_INDEX]);
            float translateX = a.getFloat(R.styleable.VectorDrawableGroup_translateX,
                    mTransform[TRANSLATE_X_INDEX]);
            float translateY = a.getFloat(R.styleable.VectorDrawableGroup_translateY,
                    mTransform[TRANSLATE_Y_INDEX]);

            final String groupName = a.getString(R.styleable.VectorDrawableGroup_name);
            if (groupName != null) {
                mGroupName = groupName;
                nSetName(mNativePtr, mGroupName);
            }
             nUpdateGroupProperties(mNativePtr, rotate, pivotX, pivotY, scaleX, scaleY,
                     translateX, translateY);
        }

        @Override
        public boolean onStateChange(int[] stateSet) {
            boolean changed = false;

            final ArrayList<VObject> children = mChildren;
            for (int i = 0, count = children.size(); i < count; i++) {
                final VObject child = children.get(i);
                if (child.isStateful()) {
                    changed |= child.onStateChange(stateSet);
                }
            }

            return changed;
        }

        @Override
        public boolean isStateful() {
            return mIsStateful;
        }

        @Override
        public boolean hasFocusStateSpecified() {
            boolean result = false;

            final ArrayList<VObject> children = mChildren;
            for (int i = 0, count = children.size(); i < count; i++) {
                final VObject child = children.get(i);
                if (child.isStateful()) {
                    result |= child.hasFocusStateSpecified();
                }
            }

            return result;
        }

        @Override
        int getNativeSize() {
            // Return the native allocation needed for the subtree.
            int size = NATIVE_ALLOCATION_SIZE;
            for (int i = 0; i < mChildren.size(); i++) {
                size += mChildren.get(i).getNativeSize();
            }
            return size;
        }

        @Override
        public boolean canApplyTheme() {
            if (mThemeAttrs != null) {
                return true;
            }

            final ArrayList<VObject> children = mChildren;
            for (int i = 0, count = children.size(); i < count; i++) {
                final VObject child = children.get(i);
                if (child.canApplyTheme()) {
                    return true;
                }
            }

            return false;
        }

        @Override
        public void applyTheme(Theme t) {
            if (mThemeAttrs != null) {
                final TypedArray a = t.resolveAttributes(mThemeAttrs,
                        R.styleable.VectorDrawableGroup);
                updateStateFromTypedArray(a);
                a.recycle();
            }

            final ArrayList<VObject> children = mChildren;
            for (int i = 0, count = children.size(); i < count; i++) {
                final VObject child = children.get(i);
                if (child.canApplyTheme()) {
                    child.applyTheme(t);

                    // Applying a theme may have made the child stateful.
                    mIsStateful |= child.isStateful();
                }
            }
        }

        /* Setters and Getters, used by animator from AnimatedVectorDrawable. */
        @SuppressWarnings("unused")
        public float getRotation() {
            return isTreeValid() ? nGetRotation(mNativePtr) : 0;
        }

        @SuppressWarnings("unused")
        public void setRotation(float rotation) {
            if (isTreeValid()) {
                nSetRotation(mNativePtr, rotation);
            }
        }

        @SuppressWarnings("unused")
        public float getPivotX() {
            return isTreeValid() ? nGetPivotX(mNativePtr) : 0;
        }

        @SuppressWarnings("unused")
        public void setPivotX(float pivotX) {
            if (isTreeValid()) {
                nSetPivotX(mNativePtr, pivotX);
            }
        }

        @SuppressWarnings("unused")
        public float getPivotY() {
            return isTreeValid() ? nGetPivotY(mNativePtr) : 0;
        }

        @SuppressWarnings("unused")
        public void setPivotY(float pivotY) {
            if (isTreeValid()) {
                nSetPivotY(mNativePtr, pivotY);
            }
        }

        @SuppressWarnings("unused")
        public float getScaleX() {
            return isTreeValid() ? nGetScaleX(mNativePtr) : 0;
        }

        @SuppressWarnings("unused")
        public void setScaleX(float scaleX) {
            if (isTreeValid()) {
                nSetScaleX(mNativePtr, scaleX);
            }
        }

        @SuppressWarnings("unused")
        public float getScaleY() {
            return isTreeValid() ? nGetScaleY(mNativePtr) : 0;
        }

        @SuppressWarnings("unused")
        public void setScaleY(float scaleY) {
            if (isTreeValid()) {
                nSetScaleY(mNativePtr, scaleY);
            }
        }

        @SuppressWarnings("unused")
        public float getTranslateX() {
            return isTreeValid() ? nGetTranslateX(mNativePtr) : 0;
        }

        @SuppressWarnings("unused")
        public void setTranslateX(float translateX) {
            if (isTreeValid()) {
                nSetTranslateX(mNativePtr, translateX);
            }
        }

        @SuppressWarnings("unused")
        public float getTranslateY() {
            return isTreeValid() ? nGetTranslateY(mNativePtr) : 0;
        }

        @SuppressWarnings("unused")
        public void setTranslateY(float translateY) {
            if (isTreeValid()) {
                nSetTranslateY(mNativePtr, translateY);
            }
        }
    }

    /**
     * Common Path information for clip path and normal path.
     */
    static abstract class VPath extends VObject {
        protected PathParser.PathData mPathData = null;

        String mPathName;
        @Config int mChangingConfigurations;

        private static final Property<VPath, PathParser.PathData> PATH_DATA =
                new Property<VPath, PathParser.PathData>(PathParser.PathData.class, "pathData") {
                    @Override
                    public void set(VPath object, PathParser.PathData data) {
                        object.setPathData(data);
                    }

                    @Override
                    public PathParser.PathData get(VPath object) {
                        return object.getPathData();
                    }
                };

        Property getProperty(String propertyName) {
            if (PATH_DATA.getName().equals(propertyName)) {
                return PATH_DATA;
            }
            // property not found
            return null;
        }

        public VPath() {
            // Empty constructor.
        }

        public VPath(VPath copy) {
            mPathName = copy.mPathName;
            mChangingConfigurations = copy.mChangingConfigurations;
            mPathData = copy.mPathData == null ? null : new PathParser.PathData(copy.mPathData);
        }

        public String getPathName() {
            return mPathName;
        }

        /* Setters and Getters, used by animator from AnimatedVectorDrawable. */
        @SuppressWarnings("unused")
        public PathParser.PathData getPathData() {
            return mPathData;
        }

        // TODO: Move the PathEvaluator and this setter and the getter above into native.
        @SuppressWarnings("unused")
        public void setPathData(PathParser.PathData pathData) {
            mPathData.setPathData(pathData);
            if (isTreeValid()) {
                nSetPathData(getNativePtr(), mPathData.getNativePtr());
            }
        }
    }

    /**
     * Clip path, which only has name and pathData.
     */
    private static class VClipPath extends VPath {
        private final long mNativePtr;
        private static final int NATIVE_ALLOCATION_SIZE = 120;

        public VClipPath() {
            mNativePtr = nCreateClipPath();
        }

        public VClipPath(VClipPath copy) {
            super(copy);
            mNativePtr = nCreateClipPath(copy.mNativePtr);
        }

        @Override
        public long getNativePtr() {
            return mNativePtr;
        }

        @Override
        public void inflate(Resources r, AttributeSet attrs, Theme theme) {
            final TypedArray a = obtainAttributes(r, theme, attrs,
                    R.styleable.VectorDrawableClipPath);
            updateStateFromTypedArray(a);
            a.recycle();
        }

        @Override
        public boolean canApplyTheme() {
            return false;
        }

        @Override
        public void applyTheme(Theme theme) {
            // No-op.
        }

        @Override
        public boolean onStateChange(int[] stateSet) {
            return false;
        }

        @Override
        public boolean isStateful() {
            return false;
        }

        @Override
        public boolean hasFocusStateSpecified() {
            return false;
        }

        @Override
        int getNativeSize() {
            return NATIVE_ALLOCATION_SIZE;
        }

        private void updateStateFromTypedArray(TypedArray a) {
            // Account for any configuration changes.
            mChangingConfigurations |= a.getChangingConfigurations();

            final String pathName = a.getString(R.styleable.VectorDrawableClipPath_name);
            if (pathName != null) {
                mPathName = pathName;
                nSetName(mNativePtr, mPathName);
            }

            final String pathDataString = a.getString(R.styleable.VectorDrawableClipPath_pathData);
            if (pathDataString != null) {
                mPathData = new PathParser.PathData(pathDataString);
                nSetPathString(mNativePtr, pathDataString, pathDataString.length());
            }
        }
    }

    /**
     * Normal path, which contains all the fill / paint information.
     */
    static class VFullPath extends VPath {
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
        private static final int TOTAL_PROPERTY_COUNT = 12;

        private static final int NATIVE_ALLOCATION_SIZE = 264;
        // Property map for animatable attributes.
        private final static HashMap<String, Integer> sPropertyIndexMap
                = new HashMap<String, Integer> () {
            {
                put("strokeWidth", STROKE_WIDTH_INDEX);
                put("strokeColor", STROKE_COLOR_INDEX);
                put("strokeAlpha", STROKE_ALPHA_INDEX);
                put("fillColor", FILL_COLOR_INDEX);
                put("fillAlpha", FILL_ALPHA_INDEX);
                put("trimPathStart", TRIM_PATH_START_INDEX);
                put("trimPathEnd", TRIM_PATH_END_INDEX);
                put("trimPathOffset", TRIM_PATH_OFFSET_INDEX);
            }
        };

        // Below are the Properties that wrap the setters to avoid reflection overhead in animations
        private static final Property<VFullPath, Float> STROKE_WIDTH =
                new FloatProperty<VFullPath> ("strokeWidth") {
                    @Override
                    public void setValue(VFullPath object, float value) {
                        object.setStrokeWidth(value);
                    }

                    @Override
                    public Float get(VFullPath object) {
                        return object.getStrokeWidth();
                    }
                };

        private static final Property<VFullPath, Integer> STROKE_COLOR =
                new IntProperty<VFullPath> ("strokeColor") {
                    @Override
                    public void setValue(VFullPath object, int value) {
                        object.setStrokeColor(value);
                    }

                    @Override
                    public Integer get(VFullPath object) {
                        return object.getStrokeColor();
                    }
                };

        private static final Property<VFullPath, Float> STROKE_ALPHA =
                new FloatProperty<VFullPath> ("strokeAlpha") {
                    @Override
                    public void setValue(VFullPath object, float value) {
                        object.setStrokeAlpha(value);
                    }

                    @Override
                    public Float get(VFullPath object) {
                        return object.getStrokeAlpha();
                    }
                };

        private static final Property<VFullPath, Integer> FILL_COLOR =
                new IntProperty<VFullPath>("fillColor") {
                    @Override
                    public void setValue(VFullPath object, int value) {
                        object.setFillColor(value);
                    }

                    @Override
                    public Integer get(VFullPath object) {
                        return object.getFillColor();
                    }
                };

        private static final Property<VFullPath, Float> FILL_ALPHA =
                new FloatProperty<VFullPath> ("fillAlpha") {
                    @Override
                    public void setValue(VFullPath object, float value) {
                        object.setFillAlpha(value);
                    }

                    @Override
                    public Float get(VFullPath object) {
                        return object.getFillAlpha();
                    }
                };

        private static final Property<VFullPath, Float> TRIM_PATH_START =
                new FloatProperty<VFullPath> ("trimPathStart") {
                    @Override
                    public void setValue(VFullPath object, float value) {
                        object.setTrimPathStart(value);
                    }

                    @Override
                    public Float get(VFullPath object) {
                        return object.getTrimPathStart();
                    }
                };

        private static final Property<VFullPath, Float> TRIM_PATH_END =
                new FloatProperty<VFullPath> ("trimPathEnd") {
                    @Override
                    public void setValue(VFullPath object, float value) {
                        object.setTrimPathEnd(value);
                    }

                    @Override
                    public Float get(VFullPath object) {
                        return object.getTrimPathEnd();
                    }
                };

        private static final Property<VFullPath, Float> TRIM_PATH_OFFSET =
                new FloatProperty<VFullPath> ("trimPathOffset") {
                    @Override
                    public void setValue(VFullPath object, float value) {
                        object.setTrimPathOffset(value);
                    }

                    @Override
                    public Float get(VFullPath object) {
                        return object.getTrimPathOffset();
                    }
                };

        private final static HashMap<String, Property> sPropertyMap
                = new HashMap<String, Property> () {
            {
                put("strokeWidth", STROKE_WIDTH);
                put("strokeColor", STROKE_COLOR);
                put("strokeAlpha", STROKE_ALPHA);
                put("fillColor", FILL_COLOR);
                put("fillAlpha", FILL_ALPHA);
                put("trimPathStart", TRIM_PATH_START);
                put("trimPathEnd", TRIM_PATH_END);
                put("trimPathOffset", TRIM_PATH_OFFSET);
            }
        };

        // Temp array to store property data obtained from native getter.
        private byte[] mPropertyData;
        /////////////////////////////////////////////////////
        // Variables below need to be copied (deep copy if applicable) for mutation.
        private int[] mThemeAttrs;

        ComplexColor mStrokeColors = null;
        ComplexColor mFillColors = null;
        private final long mNativePtr;

        public VFullPath() {
            mNativePtr = nCreateFullPath();
        }

        public VFullPath(VFullPath copy) {
            super(copy);
            mNativePtr = nCreateFullPath(copy.mNativePtr);
            mThemeAttrs = copy.mThemeAttrs;
            mStrokeColors = copy.mStrokeColors;
            mFillColors = copy.mFillColors;
        }

        Property getProperty(String propertyName) {
            Property p = super.getProperty(propertyName);
            if (p != null) {
                return p;
            }
            if (sPropertyMap.containsKey(propertyName)) {
                return sPropertyMap.get(propertyName);
            } else {
                // property not found
                return null;
            }
        }

        int getPropertyIndex(String propertyName) {
            if (!sPropertyIndexMap.containsKey(propertyName)) {
                return -1;
            } else {
                return sPropertyIndexMap.get(propertyName);
            }
        }

        @Override
        public boolean onStateChange(int[] stateSet) {
            boolean changed = false;

            if (mStrokeColors != null && mStrokeColors instanceof ColorStateList) {
                final int oldStrokeColor = getStrokeColor();
                final int newStrokeColor =
                        ((ColorStateList) mStrokeColors).getColorForState(stateSet, oldStrokeColor);
                changed |= oldStrokeColor != newStrokeColor;
                if (oldStrokeColor != newStrokeColor) {
                    nSetStrokeColor(mNativePtr, newStrokeColor);
                }
            }

            if (mFillColors != null && mFillColors instanceof ColorStateList) {
                final int oldFillColor = getFillColor();
                final int newFillColor = ((ColorStateList) mFillColors).getColorForState(stateSet, oldFillColor);
                changed |= oldFillColor != newFillColor;
                if (oldFillColor != newFillColor) {
                    nSetFillColor(mNativePtr, newFillColor);
                }
            }

            return changed;
        }

        @Override
        public boolean isStateful() {
            return mStrokeColors != null || mFillColors != null;
        }

        @Override
        public boolean hasFocusStateSpecified() {
            return (mStrokeColors != null && mStrokeColors instanceof ColorStateList &&
                    ((ColorStateList) mStrokeColors).hasFocusStateSpecified()) &&
                    (mFillColors != null && mFillColors instanceof ColorStateList &&
                    ((ColorStateList) mFillColors).hasFocusStateSpecified());
        }

        @Override
        int getNativeSize() {
            return NATIVE_ALLOCATION_SIZE;
        }

        @Override
        public long getNativePtr() {
            return mNativePtr;
        }

        @Override
        public void inflate(Resources r, AttributeSet attrs, Theme theme) {
            final TypedArray a = obtainAttributes(r, theme, attrs,
                    R.styleable.VectorDrawablePath);
            updateStateFromTypedArray(a);
            a.recycle();
        }

        private void updateStateFromTypedArray(TypedArray a) {
            int byteCount = TOTAL_PROPERTY_COUNT * 4;
            if (mPropertyData == null) {
                // Lazy initialization: If the path is created through copy constructor, this may
                // never get called.
                mPropertyData = new byte[byteCount];
            }
            // The bulk getters/setters of property data (e.g. stroke width, color, etc) allows us
            // to pull current values from native and store modifications with only two methods,
            // minimizing JNI overhead.
            boolean success = nGetFullPathProperties(mNativePtr, mPropertyData, byteCount);
            if (!success) {
                throw new RuntimeException("Error: inconsistent property count");
            }

            ByteBuffer properties = ByteBuffer.wrap(mPropertyData);
            properties.order(ByteOrder.nativeOrder());
            float strokeWidth = properties.getFloat(STROKE_WIDTH_INDEX * 4);
            int strokeColor = properties.getInt(STROKE_COLOR_INDEX * 4);
            float strokeAlpha = properties.getFloat(STROKE_ALPHA_INDEX * 4);
            int fillColor =  properties.getInt(FILL_COLOR_INDEX * 4);
            float fillAlpha = properties.getFloat(FILL_ALPHA_INDEX * 4);
            float trimPathStart = properties.getFloat(TRIM_PATH_START_INDEX * 4);
            float trimPathEnd = properties.getFloat(TRIM_PATH_END_INDEX * 4);
            float trimPathOffset = properties.getFloat(TRIM_PATH_OFFSET_INDEX * 4);
            int strokeLineCap =  properties.getInt(STROKE_LINE_CAP_INDEX * 4);
            int strokeLineJoin = properties.getInt(STROKE_LINE_JOIN_INDEX * 4);
            float strokeMiterLimit = properties.getFloat(STROKE_MITER_LIMIT_INDEX * 4);
            int fillType = properties.getInt(FILL_TYPE_INDEX * 4);
            Shader fillGradient = null;
            Shader strokeGradient = null;
            // Account for any configuration changes.
            mChangingConfigurations |= a.getChangingConfigurations();

            // Extract the theme attributes, if any.
            mThemeAttrs = a.extractThemeAttrs();

            final String pathName = a.getString(R.styleable.VectorDrawablePath_name);
            if (pathName != null) {
                mPathName = pathName;
                nSetName(mNativePtr, mPathName);
            }

            final String pathString = a.getString(R.styleable.VectorDrawablePath_pathData);
            if (pathString != null) {
                mPathData = new PathParser.PathData(pathString);
                nSetPathString(mNativePtr, pathString, pathString.length());
            }

            final ComplexColor fillColors = a.getComplexColor(
                    R.styleable.VectorDrawablePath_fillColor);
            if (fillColors != null) {
                // If the colors is a gradient color, or the color state list is stateful, keep the
                // colors information. Otherwise, discard the colors and keep the default color.
                if (fillColors instanceof  GradientColor) {
                    mFillColors = fillColors;
                    fillGradient = ((GradientColor) fillColors).getShader();
                } else if (fillColors.isStateful()) {
                    mFillColors = fillColors;
                } else {
                    mFillColors = null;
                }
                fillColor = fillColors.getDefaultColor();
            }

            final ComplexColor strokeColors = a.getComplexColor(
                    R.styleable.VectorDrawablePath_strokeColor);
            if (strokeColors != null) {
                // If the colors is a gradient color, or the color state list is stateful, keep the
                // colors information. Otherwise, discard the colors and keep the default color.
                if (strokeColors instanceof GradientColor) {
                    mStrokeColors = strokeColors;
                    strokeGradient = ((GradientColor) strokeColors).getShader();
                } else if (strokeColors.isStateful()) {
                    mStrokeColors = strokeColors;
                } else {
                    mStrokeColors = null;
                }
                strokeColor = strokeColors.getDefaultColor();
            }
            // Update the gradient info, even if the gradiet is null.
            nUpdateFullPathFillGradient(mNativePtr,
                    fillGradient != null ? fillGradient.getNativeInstance() : 0);
            nUpdateFullPathStrokeGradient(mNativePtr,
                    strokeGradient != null ? strokeGradient.getNativeInstance() : 0);

            fillAlpha = a.getFloat(R.styleable.VectorDrawablePath_fillAlpha, fillAlpha);

            strokeLineCap = a.getInt(
                    R.styleable.VectorDrawablePath_strokeLineCap, strokeLineCap);
            strokeLineJoin = a.getInt(
                    R.styleable.VectorDrawablePath_strokeLineJoin, strokeLineJoin);
            strokeMiterLimit = a.getFloat(
                    R.styleable.VectorDrawablePath_strokeMiterLimit, strokeMiterLimit);
            strokeAlpha = a.getFloat(R.styleable.VectorDrawablePath_strokeAlpha,
                    strokeAlpha);
            strokeWidth = a.getFloat(R.styleable.VectorDrawablePath_strokeWidth,
                    strokeWidth);
            trimPathEnd = a.getFloat(R.styleable.VectorDrawablePath_trimPathEnd,
                    trimPathEnd);
            trimPathOffset = a.getFloat(
                    R.styleable.VectorDrawablePath_trimPathOffset, trimPathOffset);
            trimPathStart = a.getFloat(
                    R.styleable.VectorDrawablePath_trimPathStart, trimPathStart);
            fillType = a.getInt(R.styleable.VectorDrawablePath_fillType, fillType);

            nUpdateFullPathProperties(mNativePtr, strokeWidth, strokeColor, strokeAlpha,
                    fillColor, fillAlpha, trimPathStart, trimPathEnd, trimPathOffset,
                    strokeMiterLimit, strokeLineCap, strokeLineJoin, fillType);
        }

        @Override
        public boolean canApplyTheme() {
            if (mThemeAttrs != null) {
                return true;
            }

            boolean fillCanApplyTheme = canComplexColorApplyTheme(mFillColors);
            boolean strokeCanApplyTheme = canComplexColorApplyTheme(mStrokeColors);
            if (fillCanApplyTheme || strokeCanApplyTheme) {
                return true;
            }
            return false;

        }

        @Override
        public void applyTheme(Theme t) {
            // Resolve the theme attributes directly referred by the VectorDrawable.
            if (mThemeAttrs != null) {
                final TypedArray a = t.resolveAttributes(mThemeAttrs, R.styleable.VectorDrawablePath);
                updateStateFromTypedArray(a);
                a.recycle();
            }

            // Resolve the theme attributes in-directly referred by the VectorDrawable, for example,
            // fillColor can refer to a color state list which itself needs to apply theme.
            // And this is the reason we still want to keep partial update for the path's properties.
            boolean fillCanApplyTheme = canComplexColorApplyTheme(mFillColors);
            boolean strokeCanApplyTheme = canComplexColorApplyTheme(mStrokeColors);

            if (fillCanApplyTheme) {
                mFillColors = mFillColors.obtainForTheme(t);
                if (mFillColors instanceof GradientColor) {
                    nUpdateFullPathFillGradient(mNativePtr,
                            ((GradientColor) mFillColors).getShader().getNativeInstance());
                } else if (mFillColors instanceof ColorStateList) {
                    nSetFillColor(mNativePtr, mFillColors.getDefaultColor());
                }
            }

            if (strokeCanApplyTheme) {
                mStrokeColors = mStrokeColors.obtainForTheme(t);
                if (mStrokeColors instanceof GradientColor) {
                    nUpdateFullPathStrokeGradient(mNativePtr,
                            ((GradientColor) mStrokeColors).getShader().getNativeInstance());
                } else if (mStrokeColors instanceof ColorStateList) {
                    nSetStrokeColor(mNativePtr, mStrokeColors.getDefaultColor());
                }
            }
        }

        private boolean canComplexColorApplyTheme(ComplexColor complexColor) {
            return complexColor != null && complexColor.canApplyTheme();
        }

        /* Setters and Getters, used by animator from AnimatedVectorDrawable. */
        @SuppressWarnings("unused")
        int getStrokeColor() {
            return isTreeValid() ? nGetStrokeColor(mNativePtr) : 0;
        }

        @SuppressWarnings("unused")
        void setStrokeColor(int strokeColor) {
            mStrokeColors = null;
            if (isTreeValid()) {
                nSetStrokeColor(mNativePtr, strokeColor);
            }
        }

        @SuppressWarnings("unused")
        float getStrokeWidth() {
            return isTreeValid() ? nGetStrokeWidth(mNativePtr) : 0;
        }

        @SuppressWarnings("unused")
        void setStrokeWidth(float strokeWidth) {
            if (isTreeValid()) {
                nSetStrokeWidth(mNativePtr, strokeWidth);
            }
        }

        @SuppressWarnings("unused")
        float getStrokeAlpha() {
            return isTreeValid() ? nGetStrokeAlpha(mNativePtr) : 0;
        }

        @SuppressWarnings("unused")
        void setStrokeAlpha(float strokeAlpha) {
            if (isTreeValid()) {
                nSetStrokeAlpha(mNativePtr, strokeAlpha);
            }
        }

        @SuppressWarnings("unused")
        int getFillColor() {
            return isTreeValid() ? nGetFillColor(mNativePtr) : 0;
        }

        @SuppressWarnings("unused")
        void setFillColor(int fillColor) {
            mFillColors = null;
            if (isTreeValid()) {
                nSetFillColor(mNativePtr, fillColor);
            }
        }

        @SuppressWarnings("unused")
        float getFillAlpha() {
            return isTreeValid() ? nGetFillAlpha(mNativePtr) : 0;
        }

        @SuppressWarnings("unused")
        void setFillAlpha(float fillAlpha) {
            if (isTreeValid()) {
                nSetFillAlpha(mNativePtr, fillAlpha);
            }
        }

        @SuppressWarnings("unused")
        float getTrimPathStart() {
            return isTreeValid() ? nGetTrimPathStart(mNativePtr) : 0;
        }

        @SuppressWarnings("unused")
        void setTrimPathStart(float trimPathStart) {
            if (isTreeValid()) {
                nSetTrimPathStart(mNativePtr, trimPathStart);
            }
        }

        @SuppressWarnings("unused")
        float getTrimPathEnd() {
            return isTreeValid() ? nGetTrimPathEnd(mNativePtr) : 0;
        }

        @SuppressWarnings("unused")
        void setTrimPathEnd(float trimPathEnd) {
            if (isTreeValid()) {
                nSetTrimPathEnd(mNativePtr, trimPathEnd);
            }
        }

        @SuppressWarnings("unused")
        float getTrimPathOffset() {
            return isTreeValid() ? nGetTrimPathOffset(mNativePtr) : 0;
        }

        @SuppressWarnings("unused")
        void setTrimPathOffset(float trimPathOffset) {
            if (isTreeValid()) {
                nSetTrimPathOffset(mNativePtr, trimPathOffset);
            }
        }
    }

    abstract static class VObject {
        VirtualRefBasePtr mTreePtr = null;
        boolean isTreeValid() {
            return mTreePtr != null && mTreePtr.get() != 0;
        }
        void setTree(VirtualRefBasePtr ptr) {
            mTreePtr = ptr;
        }
        abstract long getNativePtr();
        abstract void inflate(Resources r, AttributeSet attrs, Theme theme);
        abstract boolean canApplyTheme();
        abstract void applyTheme(Theme t);
        abstract boolean onStateChange(int[] state);
        abstract boolean isStateful();
        abstract boolean hasFocusStateSpecified();
        abstract int getNativeSize();
        abstract Property getProperty(String propertyName);
    }

    private static native int nDraw(long rendererPtr, long canvasWrapperPtr,
            long colorFilterPtr, Rect bounds, boolean needsMirroring, boolean canReuseCache);
    private static native boolean nGetFullPathProperties(long pathPtr, byte[] properties,
            int length);
    private static native void nSetName(long nodePtr, String name);
    private static native boolean nGetGroupProperties(long groupPtr, float[] properties,
            int length);
    private static native void nSetPathString(long pathPtr, String pathString, int length);

    // ------------- @FastNative ------------------

    @FastNative
    private static native long nCreateTree(long rootGroupPtr);
    @FastNative
    private static native long nCreateTreeFromCopy(long treeToCopy, long rootGroupPtr);
    @FastNative
    private static native void nSetRendererViewportSize(long rendererPtr, float viewportWidth,
            float viewportHeight);
    @FastNative
    private static native boolean nSetRootAlpha(long rendererPtr, float alpha);
    @FastNative
    private static native float nGetRootAlpha(long rendererPtr);
    @FastNative
    private static native void nSetAntiAlias(long rendererPtr, boolean aa);
    @FastNative
    private static native void nSetAllowCaching(long rendererPtr, boolean allowCaching);

    @FastNative
    private static native long nCreateFullPath();
    @FastNative
    private static native long nCreateFullPath(long nativeFullPathPtr);

    @FastNative
    private static native void nUpdateFullPathProperties(long pathPtr, float strokeWidth,
            int strokeColor, float strokeAlpha, int fillColor, float fillAlpha, float trimPathStart,
            float trimPathEnd, float trimPathOffset, float strokeMiterLimit, int strokeLineCap,
            int strokeLineJoin, int fillType);
    @FastNative
    private static native void nUpdateFullPathFillGradient(long pathPtr, long fillGradientPtr);
    @FastNative
    private static native void nUpdateFullPathStrokeGradient(long pathPtr, long strokeGradientPtr);

    @FastNative
    private static native long nCreateClipPath();
    @FastNative
    private static native long nCreateClipPath(long clipPathPtr);

    @FastNative
    private static native long nCreateGroup();
    @FastNative
    private static native long nCreateGroup(long groupPtr);
    @FastNative
    private static native void nUpdateGroupProperties(long groupPtr, float rotate, float pivotX,
            float pivotY, float scaleX, float scaleY, float translateX, float translateY);

    @FastNative
    private static native void nAddChild(long groupPtr, long nodePtr);

    /**
     * The setters and getters below for paths and groups are here temporarily, and will be
     * removed once the animation in AVD is replaced with RenderNodeAnimator, in which case the
     * animation will modify these properties in native. By then no JNI hopping would be necessary
     * for VD during animation, and these setters and getters will be obsolete.
     */
    // Setters and getters during animation.
    @FastNative
    private static native float nGetRotation(long groupPtr);
    @FastNative
    private static native void nSetRotation(long groupPtr, float rotation);
    @FastNative
    private static native float nGetPivotX(long groupPtr);
    @FastNative
    private static native void nSetPivotX(long groupPtr, float pivotX);
    @FastNative
    private static native float nGetPivotY(long groupPtr);
    @FastNative
    private static native void nSetPivotY(long groupPtr, float pivotY);
    @FastNative
    private static native float nGetScaleX(long groupPtr);
    @FastNative
    private static native void nSetScaleX(long groupPtr, float scaleX);
    @FastNative
    private static native float nGetScaleY(long groupPtr);
    @FastNative
    private static native void nSetScaleY(long groupPtr, float scaleY);
    @FastNative
    private static native float nGetTranslateX(long groupPtr);
    @FastNative
    private static native void nSetTranslateX(long groupPtr, float translateX);
    @FastNative
    private static native float nGetTranslateY(long groupPtr);
    @FastNative
    private static native void nSetTranslateY(long groupPtr, float translateY);

    // Setters and getters for VPath during animation.
    @FastNative
    private static native void nSetPathData(long pathPtr, long pathDataPtr);
    @FastNative
    private static native float nGetStrokeWidth(long pathPtr);
    @FastNative
    private static native void nSetStrokeWidth(long pathPtr, float width);
    @FastNative
    private static native int nGetStrokeColor(long pathPtr);
    @FastNative
    private static native void nSetStrokeColor(long pathPtr, int strokeColor);
    @FastNative
    private static native float nGetStrokeAlpha(long pathPtr);
    @FastNative
    private static native void nSetStrokeAlpha(long pathPtr, float alpha);
    @FastNative
    private static native int nGetFillColor(long pathPtr);
    @FastNative
    private static native void nSetFillColor(long pathPtr, int fillColor);
    @FastNative
    private static native float nGetFillAlpha(long pathPtr);
    @FastNative
    private static native void nSetFillAlpha(long pathPtr, float fillAlpha);
    @FastNative
    private static native float nGetTrimPathStart(long pathPtr);
    @FastNative
    private static native void nSetTrimPathStart(long pathPtr, float trimPathStart);
    @FastNative
    private static native float nGetTrimPathEnd(long pathPtr);
    @FastNative
    private static native void nSetTrimPathEnd(long pathPtr, float trimPathEnd);
    @FastNative
    private static native float nGetTrimPathOffset(long pathPtr);
    @FastNative
    private static native void nSetTrimPathOffset(long pathPtr, float trimPathOffset);
}
