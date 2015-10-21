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
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PixelFormat;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.PorterDuff.Mode;
import android.util.ArrayMap;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.LayoutDirection;
import android.util.Log;
import android.util.MathUtils;
import android.util.PathParser;
import android.util.Xml;

import com.android.internal.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Stack;

/**
 * This lets you create a drawable based on an XML vector graphic. It can be
 * defined in an XML file with the <code>&lt;vector></code> element.
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
 * <dd>The Porter-Duff blending mode for the tint color. The default value is src_in.</dd>
 * <dt><code>android:autoMirrored</code></dt>
 * <dd>Indicates if the drawable needs to be mirrored when its layout direction is
 * RTL (right-to-left).</dd>
 * <dt><code>android:alpha</code></dt>
 * <dd>The opacity of this drawable.</dd>
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
 * <dd>The degrees of rotation of the group.</dd>
 * <dt><code>android:pivotX</code></dt>
 * <dd>The X coordinate of the pivot for the scale and rotation of the group.
 * This is defined in the viewport space.</dd>
 * <dt><code>android:pivotY</code></dt>
 * <dd>The Y coordinate of the pivot for the scale and rotation of the group.
 * This is defined in the viewport space.</dd>
 * <dt><code>android:scaleX</code></dt>
 * <dd>The amount of scale on the X Coordinate.</dd>
 * <dt><code>android:scaleY</code></dt>
 * <dd>The amount of scale on the Y coordinate.</dd>
 * <dt><code>android:translateX</code></dt>
 * <dd>The amount of translation on the X coordinate.
 * This is defined in the viewport space.</dd>
 * <dt><code>android:translateY</code></dt>
 * <dd>The amount of translation on the Y coordinate.
 * This is defined in the viewport space.</dd>
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
 * <dd>Specifies the color used to fill the path. May be a color or (SDK 24+ only) a color state
 * list. If this property is animated, any value set by the animation will override the original
 * value. No path fill is drawn if this property is not specified.</dd>
 * <dt><code>android:strokeColor</code></dt>
 * <dd>Specifies the color used to draw the path outline. May be a color or (SDK 24+ only) a color
 * state list. If this property is animated, any value set by the animation will override the
 * original value. No path outline is drawn if this property is not specified.</dd>
 * <dt><code>android:strokeWidth</code></dt>
 * <dd>The width a path stroke.</dd>
 * <dt><code>android:strokeAlpha</code></dt>
 * <dd>The opacity of a path stroke.</dd>
 * <dt><code>android:fillAlpha</code></dt>
 * <dd>The opacity to fill the path with.</dd>
 * <dt><code>android:trimPathStart</code></dt>
 * <dd>The fraction of the path to trim from the start, in the range from 0 to 1.</dd>
 * <dt><code>android:trimPathEnd</code></dt>
 * <dd>The fraction of the path to trim from the end, in the range from 0 to 1.</dd>
 * <dt><code>android:trimPathOffset</code></dt>
 * <dd>Shift trim region (allows showed region to include the start and end), in the range
 * from 0 to 1.</dd>
 * <dt><code>android:strokeLineCap</code></dt>
 * <dd>Sets the linecap for a stroked path: butt, round, square.</dd>
 * <dt><code>android:strokeLineJoin</code></dt>
 * <dd>Sets the lineJoin for a stroked path: miter,round,bevel.</dd>
 * <dt><code>android:strokeMiterLimit</code></dt>
 * <dd>Sets the Miter limit for a stroked path.</dd>
 * </dl></dd>
 * </dl>
 *
 * <dl>
 * <dt><code>&lt;clip-path></code></dt>
 * <dd>Defines path to be the current clip. Note that the clip path only apply to
 * the current group and its children.
 * <dl>
 * <dt><code>android:name</code></dt>
 * <dd>Defines the name of the clip path.</dd>
 * <dt><code>android:pathData</code></dt>
 * <dd>Defines clip path using the same format as "d" attribute
 * in the SVG's path data.</dd>
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
 * </pre></li>
 */

public class VectorDrawable extends Drawable {
    private static final String LOGTAG = VectorDrawable.class.getSimpleName();

    private static final String SHAPE_CLIP_PATH = "clip-path";
    private static final String SHAPE_GROUP = "group";
    private static final String SHAPE_PATH = "path";
    private static final String SHAPE_VECTOR = "vector";

    private static final int LINECAP_BUTT = 0;
    private static final int LINECAP_ROUND = 1;
    private static final int LINECAP_SQUARE = 2;

    private static final int LINEJOIN_MITER = 0;
    private static final int LINEJOIN_ROUND = 1;
    private static final int LINEJOIN_BEVEL = 2;

    // Cap the bitmap size, such that it won't hurt the performance too much
    // and it won't crash due to a very large scale.
    // The drawable will look blurry above this size.
    private static final int MAX_CACHED_BITMAP_SIZE = 2048;

    private static final boolean DBG_VECTOR_DRAWABLE = false;

    private VectorDrawableState mVectorState;

    private PorterDuffColorFilter mTintFilter;
    private ColorFilter mColorFilter;

    private boolean mMutated;

    // AnimatedVectorDrawable needs to turn off the cache all the time, otherwise,
    // caching the bitmap by default is allowed.
    private boolean mAllowCaching = true;

    /** The density of the display on which this drawable will be rendered. */
    private int mTargetDensity;

    // Given the virtual display setup, the dpi can be different than the inflation's dpi.
    // Therefore, we need to scale the values we got from the getDimension*().
    private int mDpiScaledWidth = 0;
    private int mDpiScaledHeight = 0;
    private Insets mDpiScaledInsets = Insets.NONE;

    // Temp variable, only for saving "new" operation at the draw() time.
    private final float[] mTmpFloats = new float[9];
    private final Matrix mTmpMatrix = new Matrix();
    private final Rect mTmpBounds = new Rect();

    public VectorDrawable() {
        this(new VectorDrawableState(), null);
    }

    /**
     * The one constructor to rule them all. This is called by all public
     * constructors to set the state and initialize local properties.
     */
    private VectorDrawable(@NonNull VectorDrawableState state, @Nullable Resources res) {
        mVectorState = state;

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
        if (res != null) {
            final int densityDpi = res.getDisplayMetrics().densityDpi;
            mTargetDensity = densityDpi == 0 ? DisplayMetrics.DENSITY_DEFAULT : densityDpi;
        } else {
            mTargetDensity = mVectorState.mVPathRenderer.mSourceDensity;
        }

        mTintFilter = updateTintFilter(mTintFilter, mVectorState.mTint, mVectorState.mTintMode);
        computeVectorSize();
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
        return mVectorState.mVPathRenderer.mVGTargetsMap.get(name);
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

        // The imageView can scale the canvas in different ways, in order to
        // avoid blurry scaling, we have to draw into a bitmap with exact pixel
        // size first. This bitmap size is determined by the bounds and the
        // canvas scale.
        canvas.getMatrix(mTmpMatrix);
        mTmpMatrix.getValues(mTmpFloats);
        float canvasScaleX = Math.abs(mTmpFloats[Matrix.MSCALE_X]);
        float canvasScaleY = Math.abs(mTmpFloats[Matrix.MSCALE_Y]);
        int scaledWidth = (int) (mTmpBounds.width() * canvasScaleX);
        int scaledHeight = (int) (mTmpBounds.height() * canvasScaleY);
        scaledWidth = Math.min(MAX_CACHED_BITMAP_SIZE, scaledWidth);
        scaledHeight = Math.min(MAX_CACHED_BITMAP_SIZE, scaledHeight);

        if (scaledWidth <= 0 || scaledHeight <= 0) {
            return;
        }

        final int saveCount = canvas.save();
        canvas.translate(mTmpBounds.left, mTmpBounds.top);

        // Handle RTL mirroring.
        final boolean needMirroring = needMirroring();
        if (needMirroring) {
            canvas.translate(mTmpBounds.width(), 0);
            canvas.scale(-1.0f, 1.0f);
        }

        // At this point, canvas has been translated to the right position.
        // And we use this bound for the destination rect for the drawBitmap, so
        // we offset to (0, 0);
        mTmpBounds.offsetTo(0, 0);

        mVectorState.createCachedBitmapIfNeeded(scaledWidth, scaledHeight);
        if (!mAllowCaching) {
            mVectorState.updateCachedBitmap(scaledWidth, scaledHeight);
        } else {
            if (!mVectorState.canReuseCache()) {
                mVectorState.updateCachedBitmap(scaledWidth, scaledHeight);
                mVectorState.updateCacheStates();
            }
        }
        mVectorState.drawCachedBitmapWithRootAlpha(canvas, colorFilter, mTmpBounds);
        canvas.restoreToCount(saveCount);
    }

    @Override
    public int getAlpha() {
        return mVectorState.mVPathRenderer.getRootAlpha();
    }

    @Override
    public void setAlpha(int alpha) {
        if (mVectorState.mVPathRenderer.getRootAlpha() != alpha) {
            mVectorState.mVPathRenderer.setRootAlpha(alpha);
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

    @Override
    protected boolean onStateChange(int[] stateSet) {
        boolean changed = false;

        final VectorDrawableState state = mVectorState;
        if (state.mVPathRenderer != null && state.mVPathRenderer.onStateChange(stateSet)) {
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
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return mDpiScaledWidth;
    }

    @Override
    public int getIntrinsicHeight() {
        return mDpiScaledHeight;
    }

    /** @hide */
    @Override
    public Insets getOpticalInsets() {
        return mDpiScaledInsets;
    }

    /*
     * Update local dimensions to adjust for a target density that may differ
     * from the source density against which the constant state was loaded.
     */
    void computeVectorSize() {
        final VPathRenderer pathRenderer = mVectorState.mVPathRenderer;
        final Insets opticalInsets = pathRenderer.mOpticalInsets;

        final int sourceDensity = pathRenderer.mSourceDensity;
        final int targetDensity = mTargetDensity;
        if (targetDensity != sourceDensity) {
            mDpiScaledWidth = Bitmap.scaleFromDensity(
                    (int) pathRenderer.mBaseWidth, sourceDensity, targetDensity);
            mDpiScaledHeight = Bitmap.scaleFromDensity(
                    (int) pathRenderer.mBaseHeight,sourceDensity, targetDensity);
            final int left = Bitmap.scaleFromDensity(
                    opticalInsets.left, sourceDensity, targetDensity);
            final int right = Bitmap.scaleFromDensity(
                    opticalInsets.right, sourceDensity, targetDensity);
            final int top = Bitmap.scaleFromDensity(
                    opticalInsets.top, sourceDensity, targetDensity);
            final int bottom = Bitmap.scaleFromDensity(
                    opticalInsets.bottom, sourceDensity, targetDensity);
            mDpiScaledInsets = Insets.of(left, top, right, bottom);
        } else {
            mDpiScaledWidth = (int) pathRenderer.mBaseWidth;
            mDpiScaledHeight = (int) pathRenderer.mBaseHeight;
            mDpiScaledInsets = opticalInsets;
        }
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
        }

        // Apply theme to contained color state list.
        if (state.mTint != null && state.mTint.canApplyTheme()) {
            state.mTint = state.mTint.obtainForTheme(t);
        }

        final VPathRenderer path = state.mVPathRenderer;
        if (path != null && path.canApplyTheme()) {
            path.applyTheme(t);
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
        if (mVectorState == null || mVectorState.mVPathRenderer == null ||
                mVectorState.mVPathRenderer.mBaseWidth == 0 ||
                mVectorState.mVPathRenderer.mBaseHeight == 0 ||
                mVectorState.mVPathRenderer.mViewportHeight == 0 ||
                mVectorState.mVPathRenderer.mViewportWidth == 0) {
            return 1; // fall back to 1:1 pixel mapping.
        }
        float intrinsicWidth = mVectorState.mVPathRenderer.mBaseWidth;
        float intrinsicHeight = mVectorState.mVPathRenderer.mBaseHeight;
        float viewportWidth = mVectorState.mVPathRenderer.mViewportWidth;
        float viewportHeight = mVectorState.mVPathRenderer.mViewportHeight;
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

    private static int applyAlpha(int color, float alpha) {
        int alphaBytes = Color.alpha(color);
        color &= 0x00FFFFFF;
        color |= ((int) (alphaBytes * alpha)) << 24;
        return color;
    }

    @Override
    public void inflate(Resources res, XmlPullParser parser, AttributeSet attrs, Theme theme)
            throws XmlPullParserException, IOException {
        final VectorDrawableState state = mVectorState;
        final VPathRenderer pathRenderer = new VPathRenderer();
        state.mVPathRenderer = pathRenderer;

        final TypedArray a = obtainAttributes(res, theme, attrs, R.styleable.VectorDrawable);
        updateStateFromTypedArray(a);
        a.recycle();

        state.mCacheDirty = true;
        inflateInternal(res, parser, attrs, theme);

        // Update local properties.
        updateLocalState(res);
    }

    private void updateStateFromTypedArray(TypedArray a) throws XmlPullParserException {
        final VectorDrawableState state = mVectorState;
        final VPathRenderer pathRenderer = state.mVPathRenderer;

        // Account for any configuration changes.
        state.mChangingConfigurations |= a.getChangingConfigurations();

        // Extract the theme attributes, if any.
        state.mThemeAttrs = a.extractThemeAttrs();

        // The density may have changed since the last update (if any). Any
        // dimension-type attributes will need their default values scaled.
        final int densityDpi = a.getResources().getDisplayMetrics().densityDpi;
        final int newSourceDensity = densityDpi == 0 ? DisplayMetrics.DENSITY_DEFAULT : densityDpi;
        final int oldSourceDensity = pathRenderer.mSourceDensity;
        final float densityScale = newSourceDensity / (float) oldSourceDensity;
        pathRenderer.mSourceDensity = newSourceDensity;

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

        pathRenderer.mViewportWidth = a.getFloat(
                R.styleable.VectorDrawable_viewportWidth, pathRenderer.mViewportWidth);
        pathRenderer.mViewportHeight = a.getFloat(
                R.styleable.VectorDrawable_viewportHeight, pathRenderer.mViewportHeight);

        if (pathRenderer.mViewportWidth <= 0) {
            throw new XmlPullParserException(a.getPositionDescription() +
                    "<vector> tag requires viewportWidth > 0");
        } else if (pathRenderer.mViewportHeight <= 0) {
            throw new XmlPullParserException(a.getPositionDescription() +
                    "<vector> tag requires viewportHeight > 0");
        }

        pathRenderer.mBaseWidth = a.getDimension(
                R.styleable.VectorDrawable_width,
                pathRenderer.mBaseWidth * densityScale);
        pathRenderer.mBaseHeight = a.getDimension(
                R.styleable.VectorDrawable_height,
                pathRenderer.mBaseHeight * densityScale);

        if (pathRenderer.mBaseWidth <= 0) {
            throw new XmlPullParserException(a.getPositionDescription() +
                    "<vector> tag requires width > 0");
        } else if (pathRenderer.mBaseHeight <= 0) {
            throw new XmlPullParserException(a.getPositionDescription() +
                    "<vector> tag requires height > 0");
        }

        final int insetLeft = a.getDimensionPixelSize(
                R.styleable.VectorDrawable_opticalInsetLeft,
                (int) (pathRenderer.mOpticalInsets.left * densityScale));
        final int insetTop = a.getDimensionPixelSize(
                R.styleable.VectorDrawable_opticalInsetTop,
                (int) (pathRenderer.mOpticalInsets.top * densityScale));
        final int insetRight = a.getDimensionPixelSize(
                R.styleable.VectorDrawable_opticalInsetRight,
                (int) (pathRenderer.mOpticalInsets.right * densityScale));
        final int insetBottom = a.getDimensionPixelSize(
                R.styleable.VectorDrawable_opticalInsetBottom,
                (int) (pathRenderer.mOpticalInsets.bottom * densityScale));
        pathRenderer.mOpticalInsets = Insets.of(insetLeft, insetTop, insetRight, insetBottom);

        final float alphaInFloat = a.getFloat(R.styleable.VectorDrawable_alpha,
                pathRenderer.getAlpha());
        pathRenderer.setAlpha(alphaInFloat);

        final String name = a.getString(R.styleable.VectorDrawable_name);
        if (name != null) {
            pathRenderer.mRootName = name;
            pathRenderer.mVGTargetsMap.put(name, pathRenderer);
        }
    }

    private void inflateInternal(Resources res, XmlPullParser parser, AttributeSet attrs,
            Theme theme) throws XmlPullParserException, IOException {
        final VectorDrawableState state = mVectorState;
        final VPathRenderer pathRenderer = state.mVPathRenderer;
        boolean noPathTag = true;

        // Use a stack to help to build the group tree.
        // The top of the stack is always the current group.
        final Stack<VGroup> groupStack = new Stack<VGroup>();
        groupStack.push(pathRenderer.mRootGroup);

        int eventType = parser.getEventType();
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                final String tagName = parser.getName();
                final VGroup currentGroup = groupStack.peek();

                if (SHAPE_PATH.equals(tagName)) {
                    final VFullPath path = new VFullPath();
                    path.inflate(res, attrs, theme);
                    currentGroup.addChild(path);
                    if (path.getPathName() != null) {
                        pathRenderer.mVGTargetsMap.put(path.getPathName(), path);
                    }
                    noPathTag = false;
                    state.mChangingConfigurations |= path.mChangingConfigurations;
                } else if (SHAPE_CLIP_PATH.equals(tagName)) {
                    final VClipPath path = new VClipPath();
                    path.inflate(res, attrs, theme);
                    currentGroup.addChild(path);
                    if (path.getPathName() != null) {
                        pathRenderer.mVGTargetsMap.put(path.getPathName(), path);
                    }
                    state.mChangingConfigurations |= path.mChangingConfigurations;
                } else if (SHAPE_GROUP.equals(tagName)) {
                    VGroup newChildGroup = new VGroup();
                    newChildGroup.inflate(res, attrs, theme);
                    currentGroup.addChild(newChildGroup);
                    groupStack.push(newChildGroup);
                    if (newChildGroup.getGroupName() != null) {
                        pathRenderer.mVGTargetsMap.put(newChildGroup.getGroupName(),
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

        // Print the tree out for debug.
        if (DBG_VECTOR_DRAWABLE) {
            pathRenderer.printGroupTree();
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
    public int getChangingConfigurations() {
        return super.getChangingConfigurations() | mVectorState.getChangingConfigurations();
    }

    void setAllowCaching(boolean allowCaching) {
        mAllowCaching = allowCaching;
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

    private static class VectorDrawableState extends ConstantState {
        int[] mThemeAttrs;
        int mChangingConfigurations;
        VPathRenderer mVPathRenderer;
        ColorStateList mTint = null;
        Mode mTintMode = DEFAULT_TINT_MODE;
        boolean mAutoMirrored;

        Bitmap mCachedBitmap;
        int[] mCachedThemeAttrs;
        ColorStateList mCachedTint;
        Mode mCachedTintMode;
        int mCachedRootAlpha;
        boolean mCachedAutoMirrored;
        boolean mCacheDirty;
        /** Temporary paint object used to draw cached bitmaps. */
        Paint mTempPaint;

        // Deep copy for mutate() or implicitly mutate.
        public VectorDrawableState(VectorDrawableState copy) {
            if (copy != null) {
                mThemeAttrs = copy.mThemeAttrs;
                mChangingConfigurations = copy.mChangingConfigurations;
                mVPathRenderer = new VPathRenderer(copy.mVPathRenderer);
                mTint = copy.mTint;
                mTintMode = copy.mTintMode;
                mAutoMirrored = copy.mAutoMirrored;
            }
        }

        public void drawCachedBitmapWithRootAlpha(Canvas canvas, ColorFilter filter,
                Rect originalBounds) {
            // The bitmap's size is the same as the bounds.
            final Paint p = getPaint(filter);
            canvas.drawBitmap(mCachedBitmap, null, originalBounds, p);
        }

        public boolean hasTranslucentRoot() {
            return mVPathRenderer.getRootAlpha() < 255;
        }

        /**
         * @return null when there is no need for alpha paint.
         */
        public Paint getPaint(ColorFilter filter) {
            if (!hasTranslucentRoot() && filter == null) {
                return null;
            }

            if (mTempPaint == null) {
                mTempPaint = new Paint();
                mTempPaint.setFilterBitmap(true);
            }
            mTempPaint.setAlpha(mVPathRenderer.getRootAlpha());
            mTempPaint.setColorFilter(filter);
            return mTempPaint;
        }

        public void updateCachedBitmap(int width, int height) {
            mCachedBitmap.eraseColor(Color.TRANSPARENT);
            Canvas tmpCanvas = new Canvas(mCachedBitmap);
            mVPathRenderer.draw(tmpCanvas, width, height, null);
        }

        public void createCachedBitmapIfNeeded(int width, int height) {
            if (mCachedBitmap == null || !canReuseBitmap(width, height)) {
                mCachedBitmap = Bitmap.createBitmap(width, height,
                        Bitmap.Config.ARGB_8888);
                mCacheDirty = true;
            }

        }

        public boolean canReuseBitmap(int width, int height) {
            if (width == mCachedBitmap.getWidth()
                    && height == mCachedBitmap.getHeight()) {
                return true;
            }
            return false;
        }

        public boolean canReuseCache() {
            if (!mCacheDirty
                    && mCachedThemeAttrs == mThemeAttrs
                    && mCachedTint == mTint
                    && mCachedTintMode == mTintMode
                    && mCachedAutoMirrored == mAutoMirrored
                    && mCachedRootAlpha == mVPathRenderer.getRootAlpha()) {
                return true;
            }
            return false;
        }

        public void updateCacheStates() {
            // Use shallow copy here and shallow comparison in canReuseCache(),
            // likely hit cache miss more, but practically not much difference.
            mCachedThemeAttrs = mThemeAttrs;
            mCachedTint = mTint;
            mCachedTintMode = mTintMode;
            mCachedRootAlpha = mVPathRenderer.getRootAlpha();
            mCachedAutoMirrored = mAutoMirrored;
            mCacheDirty = false;
        }

        @Override
        public boolean canApplyTheme() {
            return mThemeAttrs != null
                    || (mVPathRenderer != null && mVPathRenderer.canApplyTheme())
                    || (mTint != null && mTint.canApplyTheme())
                    || super.canApplyTheme();
        }

        public VectorDrawableState() {
            mVPathRenderer = new VPathRenderer();
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
        public int getChangingConfigurations() {
            return mChangingConfigurations
                    | (mTint != null ? mTint.getChangingConfigurations() : 0);
        }

        public boolean isStateful() {
            return (mTint != null && mTint.isStateful())
                    || (mVPathRenderer != null && mVPathRenderer.isStateful());
        }
    }

    private static class VPathRenderer {
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
        private final TempState mTempState = new TempState();

        /////////////////////////////////////////////////////
        // Variables below need to be copied (deep copy if applicable) for mutation.
        private int mChangingConfigurations;
        private final VGroup mRootGroup;
        float mBaseWidth = 0;
        float mBaseHeight = 0;
        float mViewportWidth = 0;
        float mViewportHeight = 0;
        Insets mOpticalInsets = Insets.NONE;
        int mRootAlpha = 0xFF;
        String mRootName = null;

        int mSourceDensity = DisplayMetrics.DENSITY_DEFAULT;

        final ArrayMap<String, Object> mVGTargetsMap = new ArrayMap<>();

        public VPathRenderer() {
            mRootGroup = new VGroup();
        }

        public void setRootAlpha(int alpha) {
            mRootAlpha = alpha;
        }

        public int getRootAlpha() {
            return mRootAlpha;
        }

        // setAlpha() and getAlpha() are used mostly for animation purpose, since
        // Animator like to use alpha from 0 to 1.
        public void setAlpha(float alpha) {
            setRootAlpha((int) (alpha * 255));
        }

        @SuppressWarnings("unused")
        public float getAlpha() {
            return getRootAlpha() / 255.0f;
        }

        public VPathRenderer(VPathRenderer copy) {
            mRootGroup = new VGroup(copy.mRootGroup, mVGTargetsMap);
            mBaseWidth = copy.mBaseWidth;
            mBaseHeight = copy.mBaseHeight;
            mViewportWidth = copy.mViewportWidth;
            mViewportHeight = copy.mViewportHeight;
            mOpticalInsets = copy.mOpticalInsets;
            mChangingConfigurations = copy.mChangingConfigurations;
            mRootAlpha = copy.mRootAlpha;
            mRootName = copy.mRootName;
            mSourceDensity = copy.mSourceDensity;
            if (copy.mRootName != null) {
                mVGTargetsMap.put(copy.mRootName, this);
            }
        }

        public boolean canApplyTheme() {
            return mRootGroup.canApplyTheme();
        }

        public void applyTheme(Theme t) {
            mRootGroup.applyTheme(t);
        }

        public boolean onStateChange(int[] stateSet) {
            return mRootGroup.onStateChange(stateSet);
        }

        public boolean isStateful() {
            return mRootGroup.isStateful();
        }

        public void draw(Canvas canvas, int w, int h, ColorFilter filter) {
            final float scaleX = w / mViewportWidth;
            final float scaleY = h / mViewportHeight;
            mRootGroup.draw(canvas, mTempState, Matrix.IDENTITY_MATRIX, filter, scaleX, scaleY);
        }

        public void printGroupTree() {
            mRootGroup.printGroupTree("");
        }
    }

    private static class VGroup implements VObject {
        private static final String GROUP_INDENT = "    ";

        // mStackedMatrix is only used temporarily when drawing, it combines all
        // the parents' local matrices with the current one.
        private final Matrix mStackedMatrix = new Matrix();

        /////////////////////////////////////////////////////
        // Variables below need to be copied (deep copy if applicable) for mutation.
        private final ArrayList<VObject> mChildren = new ArrayList<>();

        private float mRotate = 0;
        private float mPivotX = 0;
        private float mPivotY = 0;
        private float mScaleX = 1;
        private float mScaleY = 1;
        private float mTranslateX = 0;
        private float mTranslateY = 0;
        private boolean mIsStateful;

        // mLocalMatrix is updated based on the update of transformation information,
        // either parsed from the XML or by animation.
        private final Matrix mLocalMatrix = new Matrix();
        private int mChangingConfigurations;
        private int[] mThemeAttrs;
        private String mGroupName = null;

        public VGroup(VGroup copy, ArrayMap<String, Object> targetsMap) {
            mRotate = copy.mRotate;
            mPivotX = copy.mPivotX;
            mPivotY = copy.mPivotY;
            mScaleX = copy.mScaleX;
            mScaleY = copy.mScaleY;
            mTranslateX = copy.mTranslateX;
            mTranslateY = copy.mTranslateY;
            mIsStateful = copy.mIsStateful;
            mThemeAttrs = copy.mThemeAttrs;
            mGroupName = copy.mGroupName;
            mChangingConfigurations = copy.mChangingConfigurations;
            if (mGroupName != null) {
                targetsMap.put(mGroupName, this);
            }

            mLocalMatrix.set(copy.mLocalMatrix);

            final ArrayList<VObject> children = copy.mChildren;
            for (int i = 0; i < children.size(); i++) {
                final VObject copyChild = children.get(i);
                if (copyChild instanceof VGroup) {
                    final VGroup copyGroup = (VGroup) copyChild;
                    mChildren.add(new VGroup(copyGroup, targetsMap));
                } else {
                    final VPath newPath;
                    if (copyChild instanceof VFullPath) {
                        newPath = new VFullPath((VFullPath) copyChild);
                    } else if (copyChild instanceof VClipPath) {
                        newPath = new VClipPath((VClipPath) copyChild);
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

        public VGroup() {
        }

        public String getGroupName() {
            return mGroupName;
        }

        public Matrix getLocalMatrix() {
            return mLocalMatrix;
        }

        public void addChild(VObject child) {
            mChildren.add(child);

            mIsStateful |= child.isStateful();
        }

        @Override
        public void draw(Canvas canvas, TempState temp, Matrix currentMatrix,
                ColorFilter filter, float scaleX, float scaleY) {
            // Calculate current group's matrix by preConcat the parent's and
            // and the current one on the top of the stack.
            // Basically the Mfinal = Mviewport * M0 * M1 * M2;
            // Mi the local matrix at level i of the group tree.
            mStackedMatrix.set(currentMatrix);
            mStackedMatrix.preConcat(mLocalMatrix);

            // Save the current clip information, which is local to this group.
            canvas.save();

            // Draw the group tree in the same order as the XML file.
            for (int i = 0, count = mChildren.size(); i < count; i++) {
                final VObject child = mChildren.get(i);
                child.draw(canvas, temp, mStackedMatrix, filter, scaleX, scaleY);
            }

            // Restore the previous clip information.
            canvas.restore();
        }

        @Override
        public void inflate(Resources res, AttributeSet attrs, Theme theme) {
            final TypedArray a = obtainAttributes(res, theme, attrs,
                    R.styleable.VectorDrawableGroup);
            updateStateFromTypedArray(a);
            a.recycle();
        }

        private void updateStateFromTypedArray(TypedArray a) {
            // Account for any configuration changes.
            mChangingConfigurations |= a.getChangingConfigurations();

            // Extract the theme attributes, if any.
            mThemeAttrs = a.extractThemeAttrs();

            mRotate = a.getFloat(R.styleable.VectorDrawableGroup_rotation, mRotate);
            mPivotX = a.getFloat(R.styleable.VectorDrawableGroup_pivotX, mPivotX);
            mPivotY = a.getFloat(R.styleable.VectorDrawableGroup_pivotY, mPivotY);
            mScaleX = a.getFloat(R.styleable.VectorDrawableGroup_scaleX, mScaleX);
            mScaleY = a.getFloat(R.styleable.VectorDrawableGroup_scaleY, mScaleY);
            mTranslateX = a.getFloat(R.styleable.VectorDrawableGroup_translateX, mTranslateX);
            mTranslateY = a.getFloat(R.styleable.VectorDrawableGroup_translateY, mTranslateY);

            final String groupName = a.getString(R.styleable.VectorDrawableGroup_name);
            if (groupName != null) {
                mGroupName = groupName;
            }

            updateLocalMatrix();
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

        private void updateLocalMatrix() {
            // The order we apply is the same as the
            // RenderNode.cpp::applyViewPropertyTransforms().
            mLocalMatrix.reset();
            mLocalMatrix.postTranslate(-mPivotX, -mPivotY);
            mLocalMatrix.postScale(mScaleX, mScaleY);
            mLocalMatrix.postRotate(mRotate, 0, 0);
            mLocalMatrix.postTranslate(mTranslateX + mPivotX, mTranslateY + mPivotY);
        }

        public void printGroupTree(String indent) {
            Log.v(LOGTAG, indent + "group:" + getGroupName() + " rotation is " + mRotate);
            Log.v(LOGTAG, indent + "matrix:" + getLocalMatrix().toString());

            final int count = mChildren.size();
            if (count > 0) {
                indent += GROUP_INDENT;
            }

            // Then print all the children groups.
            for (int i = 0; i < count; i++) {
                final VObject child = mChildren.get(i);
                if (child instanceof VGroup) {
                    ((VGroup) child).printGroupTree(indent);
                }
            }
        }

        /* Setters and Getters, used by animator from AnimatedVectorDrawable. */
        @SuppressWarnings("unused")
        public float getRotation() {
            return mRotate;
        }

        @SuppressWarnings("unused")
        public void setRotation(float rotation) {
            if (rotation != mRotate) {
                mRotate = rotation;
                updateLocalMatrix();
            }
        }

        @SuppressWarnings("unused")
        public float getPivotX() {
            return mPivotX;
        }

        @SuppressWarnings("unused")
        public void setPivotX(float pivotX) {
            if (pivotX != mPivotX) {
                mPivotX = pivotX;
                updateLocalMatrix();
            }
        }

        @SuppressWarnings("unused")
        public float getPivotY() {
            return mPivotY;
        }

        @SuppressWarnings("unused")
        public void setPivotY(float pivotY) {
            if (pivotY != mPivotY) {
                mPivotY = pivotY;
                updateLocalMatrix();
            }
        }

        @SuppressWarnings("unused")
        public float getScaleX() {
            return mScaleX;
        }

        @SuppressWarnings("unused")
        public void setScaleX(float scaleX) {
            if (scaleX != mScaleX) {
                mScaleX = scaleX;
                updateLocalMatrix();
            }
        }

        @SuppressWarnings("unused")
        public float getScaleY() {
            return mScaleY;
        }

        @SuppressWarnings("unused")
        public void setScaleY(float scaleY) {
            if (scaleY != mScaleY) {
                mScaleY = scaleY;
                updateLocalMatrix();
            }
        }

        @SuppressWarnings("unused")
        public float getTranslateX() {
            return mTranslateX;
        }

        @SuppressWarnings("unused")
        public void setTranslateX(float translateX) {
            if (translateX != mTranslateX) {
                mTranslateX = translateX;
                updateLocalMatrix();
            }
        }

        @SuppressWarnings("unused")
        public float getTranslateY() {
            return mTranslateY;
        }

        @SuppressWarnings("unused")
        public void setTranslateY(float translateY) {
            if (translateY != mTranslateY) {
                mTranslateY = translateY;
                updateLocalMatrix();
            }
        }
    }

    /**
     * Common Path information for clip path and normal path.
     */
    private static abstract class VPath implements VObject {
        protected PathParser.PathDataNode[] mNodes = null;
        String mPathName;
        int mChangingConfigurations;

        public VPath() {
            // Empty constructor.
        }

        public VPath(VPath copy) {
            mPathName = copy.mPathName;
            mChangingConfigurations = copy.mChangingConfigurations;
            mNodes = PathParser.deepCopyNodes(copy.mNodes);
        }

        public String getPathName() {
            return mPathName;
        }

        public boolean isClipPath() {
            return false;
        }

        /* Setters and Getters, used by animator from AnimatedVectorDrawable. */
        @SuppressWarnings("unused")
        public PathParser.PathDataNode[] getPathData() {
            return mNodes;
        }

        @SuppressWarnings("unused")
        public void setPathData(PathParser.PathDataNode[] nodes) {
            if (!PathParser.canMorph(mNodes, nodes)) {
                // This should not happen in the middle of animation.
                mNodes = PathParser.deepCopyNodes(nodes);
            } else {
                PathParser.updateNodes(mNodes, nodes);
            }
        }

        @Override
        public final void draw(Canvas canvas, TempState temp, Matrix groupStackedMatrix,
                ColorFilter filter, float scaleX, float scaleY) {
            final float matrixScale = VPath.getMatrixScale(groupStackedMatrix);
            if (matrixScale == 0) {
                // When either x or y is scaled to 0, we don't need to draw anything.
                return;
            }

            final Path path = temp.path;
            path.reset();
            toPath(temp, path);

            final Matrix pathMatrix = temp.pathMatrix;
            pathMatrix.set(groupStackedMatrix);
            pathMatrix.postScale(scaleX, scaleY);

            final Path renderPath = temp.renderPath;
            renderPath.reset();
            renderPath.addPath(path, pathMatrix);

            final float minScale = Math.min(scaleX, scaleY);
            final float strokeScale = minScale * matrixScale;
            drawPath(temp, renderPath, canvas, filter, strokeScale);
        }

        /**
         * Writes the path's nodes to an output Path for rendering.
         *
         * @param temp temporary state variables
         * @param outPath the output path
         */
        protected void toPath(TempState temp, Path outPath) {
            if (mNodes != null) {
                PathParser.PathDataNode.nodesToPath(mNodes, outPath);
            }
        }

        /**
         * Draws the specified path into the supplied canvas.
         */
        protected abstract void drawPath(TempState temp, Path path, Canvas canvas,
                ColorFilter filter, float strokeScale);

        private static float getMatrixScale(Matrix groupStackedMatrix) {
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
            float[] unitVectors = new float[] {0, 1, 1, 0};
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
    }

    /**
     * Clip path, which only has name and pathData.
     */
    private static class VClipPath extends VPath {
        public VClipPath() {
            // Empty constructor.
        }

        public VClipPath(VClipPath copy) {
            super(copy);
        }

        @Override
        protected void drawPath(TempState temp, Path renderPath, Canvas canvas, ColorFilter filter,
                float strokeScale) {
            canvas.clipPath(renderPath);
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

        private void updateStateFromTypedArray(TypedArray a) {
            // Account for any configuration changes.
            mChangingConfigurations |= a.getChangingConfigurations();

            final String pathName = a.getString(R.styleable.VectorDrawableClipPath_name);
            if (pathName != null) {
                mPathName = pathName;
            }

            final String pathData = a.getString(R.styleable.VectorDrawableClipPath_pathData);
            if (pathData != null) {
                mNodes = PathParser.createNodesFromPathData(pathData);
            }
        }

        @Override
        public boolean isClipPath() {
            return true;
        }
    }

    /**
     * Normal path, which contains all the fill / paint information.
     */
    private static class VFullPath extends VPath {
        /////////////////////////////////////////////////////
        // Variables below need to be copied (deep copy if applicable) for mutation.
        private int[] mThemeAttrs;

        ColorStateList mStrokeColors = null;
        int mStrokeColor = Color.TRANSPARENT;
        float mStrokeWidth = 0;

        ColorStateList mFillColors = null;
        int mFillColor = Color.TRANSPARENT;
        float mStrokeAlpha = 1.0f;
        int mFillRule;
        float mFillAlpha = 1.0f;
        float mTrimPathStart = 0;
        float mTrimPathEnd = 1;
        float mTrimPathOffset = 0;

        Paint.Cap mStrokeLineCap = Paint.Cap.BUTT;
        Paint.Join mStrokeLineJoin = Paint.Join.MITER;
        float mStrokeMiterlimit = 4;

        public VFullPath() {
            // Empty constructor.
        }

        public VFullPath(VFullPath copy) {
            super(copy);

            mThemeAttrs = copy.mThemeAttrs;

            mStrokeColors = copy.mStrokeColors;
            mStrokeColor = copy.mStrokeColor;
            mStrokeWidth = copy.mStrokeWidth;
            mStrokeAlpha = copy.mStrokeAlpha;
            mFillColors = copy.mFillColors;
            mFillColor = copy.mFillColor;
            mFillRule = copy.mFillRule;
            mFillAlpha = copy.mFillAlpha;
            mTrimPathStart = copy.mTrimPathStart;
            mTrimPathEnd = copy.mTrimPathEnd;
            mTrimPathOffset = copy.mTrimPathOffset;

            mStrokeLineCap = copy.mStrokeLineCap;
            mStrokeLineJoin = copy.mStrokeLineJoin;
            mStrokeMiterlimit = copy.mStrokeMiterlimit;
        }

        private Paint.Cap getStrokeLineCap(int id, Paint.Cap defValue) {
            switch (id) {
                case LINECAP_BUTT:
                    return Paint.Cap.BUTT;
                case LINECAP_ROUND:
                    return Paint.Cap.ROUND;
                case LINECAP_SQUARE:
                    return Paint.Cap.SQUARE;
                default:
                    return defValue;
            }
        }

        private Paint.Join getStrokeLineJoin(int id, Paint.Join defValue) {
            switch (id) {
                case LINEJOIN_MITER:
                    return Paint.Join.MITER;
                case LINEJOIN_ROUND:
                    return Paint.Join.ROUND;
                case LINEJOIN_BEVEL:
                    return Paint.Join.BEVEL;
                default:
                    return defValue;
            }
        }

        @Override
        public boolean onStateChange(int[] stateSet) {
            boolean changed = false;

            if (mStrokeColors != null) {
                final int oldStrokeColor = mStrokeColor;
                mStrokeColor = mStrokeColors.getColorForState(stateSet, oldStrokeColor);
                changed |= oldStrokeColor != mStrokeColor;
            }

            if (mFillColors != null) {
                final int oldFillColor = mFillColor;
                mFillColor = mFillColors.getColorForState(stateSet, oldFillColor);
                changed |= oldFillColor != mFillColor;
            }

            return changed;
        }

        @Override
        public boolean isStateful() {
            return mStrokeColors != null || mFillColors != null;
        }

        @Override
        public void toPath(TempState temp, Path path) {
            super.toPath(temp, path);

            if (mTrimPathStart != 0.0f || mTrimPathEnd != 1.0f) {
                VFullPath.applyTrim(temp, path, mTrimPathStart, mTrimPathEnd, mTrimPathOffset);
            }
        }

        @Override
        protected void drawPath(TempState temp, Path path, Canvas canvas, ColorFilter filter,
                float strokeScale) {
            drawPathFill(temp, path, canvas, filter);
            drawPathStroke(temp, path, canvas, filter, strokeScale);
        }

        /**
         * Draws this path's fill, if necessary.
         */
        private void drawPathFill(TempState temp, Path path, Canvas canvas, ColorFilter filter) {
            if (mFillColor == Color.TRANSPARENT) {
                return;
            }

            if (temp.mFillPaint == null) {
                temp.mFillPaint = new Paint();
                temp.mFillPaint.setStyle(Paint.Style.FILL);
                temp.mFillPaint.setAntiAlias(true);
            }

            final Paint fillPaint = temp.mFillPaint;
            fillPaint.setColor(applyAlpha(mFillColor, mFillAlpha));
            fillPaint.setColorFilter(filter);
            canvas.drawPath(path, fillPaint);
        }

        /**
         * Draws this path's stroke, if necessary.
         */
        private void drawPathStroke(TempState temp, Path path, Canvas canvas, ColorFilter filter,
                float strokeScale) {
            if (mStrokeColor == Color.TRANSPARENT) {
                return;
            }

            if (temp.mStrokePaint == null) {
                temp.mStrokePaint = new Paint();
                temp.mStrokePaint.setStyle(Paint.Style.STROKE);
                temp.mStrokePaint.setAntiAlias(true);
            }

            final Paint strokePaint = temp.mStrokePaint;
            if (mStrokeLineJoin != null) {
                strokePaint.setStrokeJoin(mStrokeLineJoin);
            }

            if (mStrokeLineCap != null) {
                strokePaint.setStrokeCap(mStrokeLineCap);
            }

            strokePaint.setStrokeMiter(mStrokeMiterlimit);
            strokePaint.setColor(applyAlpha(mStrokeColor, mStrokeAlpha));
            strokePaint.setColorFilter(filter);
            strokePaint.setStrokeWidth(mStrokeWidth * strokeScale);
            canvas.drawPath(path, strokePaint);
        }

        /**
         * Applies trimming to the specified path.
         */
        private static void applyTrim(TempState temp, Path path, float mTrimPathStart,
                float mTrimPathEnd, float mTrimPathOffset) {
            if (mTrimPathStart == 0.0f && mTrimPathEnd == 1.0f) {
                // No trimming necessary.
                return;
            }

            if (temp.mPathMeasure == null) {
                temp.mPathMeasure = new PathMeasure();
            }
            final PathMeasure pathMeasure = temp.mPathMeasure;
            pathMeasure.setPath(path, false);

            final float len = pathMeasure.getLength();
            final float start = len * ((mTrimPathStart + mTrimPathOffset) % 1.0f);
            final float end = len * ((mTrimPathEnd + mTrimPathOffset) % 1.0f);
            path.reset();
            if (start > end) {
                pathMeasure.getSegment(start, len, path, true);
                pathMeasure.getSegment(0, end, path, true);
            } else {
                pathMeasure.getSegment(start, end, path, true);
            }

            // Fix bug in measure.
            path.rLineTo(0, 0);
        }

        @Override
        public void inflate(Resources r, AttributeSet attrs, Theme theme) {
            final TypedArray a = obtainAttributes(r, theme, attrs,
                    R.styleable.VectorDrawablePath);
            updateStateFromTypedArray(a);
            a.recycle();
        }

        private void updateStateFromTypedArray(TypedArray a) {
            // Account for any configuration changes.
            mChangingConfigurations |= a.getChangingConfigurations();

            // Extract the theme attributes, if any.
            mThemeAttrs = a.extractThemeAttrs();

            final String pathName = a.getString(R.styleable.VectorDrawablePath_name);
            if (pathName != null) {
                mPathName = pathName;
            }

            final String pathData = a.getString(R.styleable.VectorDrawablePath_pathData);
            if (pathData != null) {
                mNodes = PathParser.createNodesFromPathData(pathData);
            }

            final ColorStateList fillColors = a.getColorStateList(
                    R.styleable.VectorDrawablePath_fillColor);
            if (fillColors != null) {
                // If the color state list isn't stateful, discard the state
                // list and keep the default (e.g. the only) color.
                mFillColors = fillColors.isStateful() ? fillColors : null;
                mFillColor = fillColors.getDefaultColor();
            }

            final ColorStateList strokeColors = a.getColorStateList(
                    R.styleable.VectorDrawablePath_strokeColor);
            if (strokeColors != null) {
                // If the color state list isn't stateful, discard the state
                // list and keep the default (e.g. the only) color.
                mStrokeColors = strokeColors.isStateful() ? strokeColors : null;
                mStrokeColor = strokeColors.getDefaultColor();
            }

            mFillAlpha = a.getFloat(R.styleable.VectorDrawablePath_fillAlpha, mFillAlpha);
            mStrokeLineCap = getStrokeLineCap(a.getInt(
                    R.styleable.VectorDrawablePath_strokeLineCap, -1), mStrokeLineCap);
            mStrokeLineJoin = getStrokeLineJoin(a.getInt(
                    R.styleable.VectorDrawablePath_strokeLineJoin, -1), mStrokeLineJoin);
            mStrokeMiterlimit = a.getFloat(
                    R.styleable.VectorDrawablePath_strokeMiterLimit, mStrokeMiterlimit);
            mStrokeAlpha = a.getFloat(R.styleable.VectorDrawablePath_strokeAlpha, mStrokeAlpha);
            mStrokeWidth = a.getFloat(R.styleable.VectorDrawablePath_strokeWidth, mStrokeWidth);
            mTrimPathEnd = a.getFloat(R.styleable.VectorDrawablePath_trimPathEnd, mTrimPathEnd);
            mTrimPathOffset = a.getFloat(
                    R.styleable.VectorDrawablePath_trimPathOffset, mTrimPathOffset);
            mTrimPathStart = a.getFloat(
                    R.styleable.VectorDrawablePath_trimPathStart, mTrimPathStart);
        }

        @Override
        public boolean canApplyTheme() {
            return mThemeAttrs != null;
        }

        @Override
        public void applyTheme(Theme t) {
            if (mThemeAttrs == null) {
                return;
            }

            final TypedArray a = t.resolveAttributes(mThemeAttrs, R.styleable.VectorDrawablePath);
            updateStateFromTypedArray(a);
            a.recycle();
        }

        /* Setters and Getters, used by animator from AnimatedVectorDrawable. */
        @SuppressWarnings("unused")
        int getStrokeColor() {
            return mStrokeColor;
        }

        @SuppressWarnings("unused")
        void setStrokeColor(int strokeColor) {
            mStrokeColors = null;
            mStrokeColor = strokeColor;
        }

        @SuppressWarnings("unused")
        float getStrokeWidth() {
            return mStrokeWidth;
        }

        @SuppressWarnings("unused")
        void setStrokeWidth(float strokeWidth) {
            mStrokeWidth = strokeWidth;
        }

        @SuppressWarnings("unused")
        float getStrokeAlpha() {
            return mStrokeAlpha;
        }

        @SuppressWarnings("unused")
        void setStrokeAlpha(float strokeAlpha) {
            mStrokeAlpha = strokeAlpha;
        }

        @SuppressWarnings("unused")
        int getFillColor() {
            return mFillColor;
        }

        @SuppressWarnings("unused")
        void setFillColor(int fillColor) {
            mFillColors = null;
            mFillColor = fillColor;
        }

        @SuppressWarnings("unused")
        float getFillAlpha() {
            return mFillAlpha;
        }

        @SuppressWarnings("unused")
        void setFillAlpha(float fillAlpha) {
            mFillAlpha = fillAlpha;
        }

        @SuppressWarnings("unused")
        float getTrimPathStart() {
            return mTrimPathStart;
        }

        @SuppressWarnings("unused")
        void setTrimPathStart(float trimPathStart) {
            mTrimPathStart = trimPathStart;
        }

        @SuppressWarnings("unused")
        float getTrimPathEnd() {
            return mTrimPathEnd;
        }

        @SuppressWarnings("unused")
        void setTrimPathEnd(float trimPathEnd) {
            mTrimPathEnd = trimPathEnd;
        }

        @SuppressWarnings("unused")
        float getTrimPathOffset() {
            return mTrimPathOffset;
        }

        @SuppressWarnings("unused")
        void setTrimPathOffset(float trimPathOffset) {
            mTrimPathOffset = trimPathOffset;
        }
    }

    static class TempState {
        final Matrix pathMatrix = new Matrix();
        final Path path = new Path();
        final Path renderPath = new Path();

        PathMeasure mPathMeasure;
        Paint mFillPaint;
        Paint mStrokePaint;
    }

    interface VObject {
        void draw(Canvas canvas, TempState temp, Matrix currentMatrix,
                ColorFilter filter, float scaleX, float scaleY);
        void inflate(Resources r, AttributeSet attrs, Theme theme);
        boolean canApplyTheme();
        void applyTheme(Theme t);
        boolean onStateChange(int[] state);
        boolean isStateful();
    }
}
