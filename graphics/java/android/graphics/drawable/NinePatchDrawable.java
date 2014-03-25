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

package android.graphics.drawable;

import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Insets;
import android.graphics.NinePatch;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff.Mode;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.Region;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.LayoutDirection;
import android.util.TypedValue;

import com.android.internal.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;

/**
 *
 * A resizeable bitmap, with stretchable areas that you define. This type of image
 * is defined in a .png file with a special format.
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about how to use a NinePatchDrawable, read the
 * <a href="{@docRoot}guide/topics/graphics/2d-graphics.html#nine-patch">
 * Canvas and Drawables</a> developer guide. For information about creating a NinePatch image
 * file using the draw9patch tool, see the
 * <a href="{@docRoot}guide/developing/tools/draw9patch.html">Draw 9-patch</a> tool guide.</p></div>
 */
public class NinePatchDrawable extends Drawable {
    // dithering helps a lot, and is pretty cheap, so default is true
    private static final boolean DEFAULT_DITHER = false;
    private NinePatchState mNinePatchState;
    private NinePatch mNinePatch;
    private PorterDuffColorFilter mTintFilter;
    private Rect mPadding;
    private Insets mOpticalInsets = Insets.NONE;
    private Paint mPaint;
    private boolean mMutated;

    private int mTargetDensity = DisplayMetrics.DENSITY_DEFAULT;

    // These are scaled to match the target density.
    private int mBitmapWidth;
    private int mBitmapHeight;

    NinePatchDrawable() {
        mNinePatchState = new NinePatchState();
    }

    /**
     * Create drawable from raw nine-patch data, not dealing with density.
     * @deprecated Use {@link #NinePatchDrawable(Resources, Bitmap, byte[], Rect, String)}
     * to ensure that the drawable has correctly set its target density.
     */
    @Deprecated
    public NinePatchDrawable(Bitmap bitmap, byte[] chunk, Rect padding, String srcName) {
        this(new NinePatchState(new NinePatch(bitmap, chunk, srcName), padding), null, null);
    }

    /**
     * Create drawable from raw nine-patch data, setting initial target density
     * based on the display metrics of the resources.
     */
    public NinePatchDrawable(Resources res, Bitmap bitmap, byte[] chunk,
            Rect padding, String srcName) {
        this(new NinePatchState(new NinePatch(bitmap, chunk, srcName), padding), res, null);
        mNinePatchState.mTargetDensity = mTargetDensity;
    }

    /**
     * Create drawable from raw nine-patch data, setting initial target density
     * based on the display metrics of the resources.
     *
     * @hide
     */
    public NinePatchDrawable(Resources res, Bitmap bitmap, byte[] chunk,
            Rect padding, Rect opticalInsets, String srcName) {
        this(new NinePatchState(new NinePatch(bitmap, chunk, srcName), padding, opticalInsets),
                res, null);
        mNinePatchState.mTargetDensity = mTargetDensity;
    }

    /**
     * Create drawable from existing nine-patch, not dealing with density.
     * @deprecated Use {@link #NinePatchDrawable(Resources, NinePatch)}
     * to ensure that the drawable has correctly set its target density.
     */
    @Deprecated
    public NinePatchDrawable(NinePatch patch) {
        this(new NinePatchState(patch, new Rect()), null, null);
    }

    /**
     * Create drawable from existing nine-patch, setting initial target density
     * based on the display metrics of the resources.
     */
    public NinePatchDrawable(Resources res, NinePatch patch) {
        this(new NinePatchState(patch, new Rect()), res, null);
        mNinePatchState.mTargetDensity = mTargetDensity;
    }

    /**
     * Set the density scale at which this drawable will be rendered. This
     * method assumes the drawable will be rendered at the same density as the
     * specified canvas.
     *
     * @param canvas The Canvas from which the density scale must be obtained.
     *
     * @see android.graphics.Bitmap#setDensity(int)
     * @see android.graphics.Bitmap#getDensity()
     */
    public void setTargetDensity(Canvas canvas) {
        setTargetDensity(canvas.getDensity());
    }

    /**
     * Set the density scale at which this drawable will be rendered.
     *
     * @param metrics The DisplayMetrics indicating the density scale for this drawable.
     *
     * @see android.graphics.Bitmap#setDensity(int)
     * @see android.graphics.Bitmap#getDensity()
     */
    public void setTargetDensity(DisplayMetrics metrics) {
        setTargetDensity(metrics.densityDpi);
    }

    /**
     * Set the density at which this drawable will be rendered.
     *
     * @param density The density scale for this drawable.
     *
     * @see android.graphics.Bitmap#setDensity(int)
     * @see android.graphics.Bitmap#getDensity()
     */
    public void setTargetDensity(int density) {
        if (density != mTargetDensity) {
            mTargetDensity = density == 0 ? DisplayMetrics.DENSITY_DEFAULT : density;
            if (mNinePatch != null) {
                computeBitmapSize();
            }
            invalidateSelf();
        }
    }

    private static Insets scaleFromDensity(Insets insets, int sdensity, int tdensity) {
        int left = Bitmap.scaleFromDensity(insets.left, sdensity, tdensity);
        int top = Bitmap.scaleFromDensity(insets.top, sdensity, tdensity);
        int right = Bitmap.scaleFromDensity(insets.right, sdensity, tdensity);
        int bottom = Bitmap.scaleFromDensity(insets.bottom, sdensity, tdensity);
        return Insets.of(left, top, right, bottom);
    }

    private void computeBitmapSize() {
        final int sdensity = mNinePatch.getDensity();
        final int tdensity = mTargetDensity;
        if (sdensity == tdensity) {
            mBitmapWidth = mNinePatch.getWidth();
            mBitmapHeight = mNinePatch.getHeight();
            mOpticalInsets = mNinePatchState.mOpticalInsets;
        } else {
            mBitmapWidth = Bitmap.scaleFromDensity(mNinePatch.getWidth(), sdensity, tdensity);
            mBitmapHeight = Bitmap.scaleFromDensity(mNinePatch.getHeight(), sdensity, tdensity);
            if (mNinePatchState.mPadding != null && mPadding != null) {
                Rect dest = mPadding;
                Rect src = mNinePatchState.mPadding;
                if (dest == src) {
                    mPadding = dest = new Rect(src);
                }
                dest.left = Bitmap.scaleFromDensity(src.left, sdensity, tdensity);
                dest.top = Bitmap.scaleFromDensity(src.top, sdensity, tdensity);
                dest.right = Bitmap.scaleFromDensity(src.right, sdensity, tdensity);
                dest.bottom = Bitmap.scaleFromDensity(src.bottom, sdensity, tdensity);
            }
            mOpticalInsets = scaleFromDensity(mNinePatchState.mOpticalInsets, sdensity, tdensity);
        }
    }

    private void setNinePatch(NinePatch ninePatch) {
        if (ninePatch != mNinePatch) {
            mNinePatch = ninePatch;
            if (ninePatch != null) {
                computeBitmapSize();
            } else {
                mBitmapWidth = mBitmapHeight = -1;
                mOpticalInsets = Insets.NONE;
            }
            invalidateSelf();
        }
    }

    @Override
    public void draw(Canvas canvas) {
        final Rect bounds = getBounds();

        final boolean clearColorFilter;
        if (mTintFilter != null && getPaint().getColorFilter() == null) {
            mPaint.setColorFilter(mTintFilter);
            clearColorFilter = true;
        } else {
            clearColorFilter = false;
        }

        final boolean needsMirroring = needsMirroring();
        if (needsMirroring) {
            canvas.save();
            // Mirror the 9patch
            canvas.translate(bounds.right - bounds.left, 0);
            canvas.scale(-1.0f, 1.0f);
        }

        mNinePatch.draw(canvas, bounds, mPaint);

        if (needsMirroring) {
            canvas.restore();
        }

        if (clearColorFilter) {
            mPaint.setColorFilter(null);
        }
    }

    @Override
    public int getChangingConfigurations() {
        return super.getChangingConfigurations() | mNinePatchState.mChangingConfigurations;
    }

    @Override
    public boolean getPadding(Rect padding) {
        final Rect scaledPadding = mPadding;
        if (scaledPadding != null) {
            if (needsMirroring()) {
                padding.set(scaledPadding.right, scaledPadding.top,
                        scaledPadding.left, scaledPadding.bottom);
            } else {
                padding.set(scaledPadding);
            }
            return (padding.left | padding.top | padding.right | padding.bottom) != 0;
        }
        return false;
    }

    /**
     * @hide
     */
    @Override
    public Insets getOpticalInsets() {
        if (needsMirroring()) {
            return Insets.of(mOpticalInsets.right, mOpticalInsets.top, mOpticalInsets.right,
                    mOpticalInsets.bottom);
        } else {
            return mOpticalInsets;
        }
    }

    @Override
    public void setAlpha(int alpha) {
        if (mPaint == null && alpha == 0xFF) {
            // Fast common case -- leave at normal alpha.
            return;
        }
        getPaint().setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public int getAlpha() {
        if (mPaint == null) {
            // Fast common case -- normal alpha.
            return 0xFF;
        }
        return getPaint().getAlpha();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        if (mPaint == null && cf == null) {
            // Fast common case -- leave at no color filter.
            return;
        }
        getPaint().setColorFilter(cf);
        invalidateSelf();
    }

    /**
     * Specifies a tint for this drawable.
     * <p>
     * Setting a color filter via {@link #setColorFilter(ColorFilter)} overrides
     * tint.
     *
     * @param tint Color state list to use for tinting this drawable, or null to
     *            clear the tint
     */
    public void setTint(ColorStateList tint) {
        if (mNinePatchState.mTint != tint) {
            mNinePatchState.mTint = tint;
            updateTintFilter();
            invalidateSelf();
        }
    }

    /**
     * Returns the tint color for this drawable.
     *
     * @return Color state list to use for tinting this drawable, or null if
     *         none set
     */
    public ColorStateList getTint() {
        return mNinePatchState.mTint;
    }

    /**
     * Specifies the blending mode used to apply tint.
     *
     * @param tintMode A Porter-Duff blending mode
     * @hide Pending finalization of supported Modes
     */
    public void setTintMode(Mode tintMode) {
        if (mNinePatchState.mTintMode != tintMode) {
            mNinePatchState.mTintMode = tintMode;
            updateTintFilter();
            invalidateSelf();
        }
    }

    /**
     * Ensures the tint filter is consistent with the current tint color and
     * mode.
     */
    private void updateTintFilter() {
        final ColorStateList tint = mNinePatchState.mTint;
        final Mode tintMode = mNinePatchState.mTintMode;
        if (tint != null && tintMode != null) {
            if (mTintFilter == null) {
                mTintFilter = new PorterDuffColorFilter(0, tintMode);
            } else {
                mTintFilter.setMode(tintMode);
            }
        } else {
            mTintFilter = null;
        }
    }

    @Override
    public void setDither(boolean dither) {
        //noinspection PointlessBooleanExpression
        if (mPaint == null && dither == DEFAULT_DITHER) {
            // Fast common case -- leave at default dither.
            return;
        }

        getPaint().setDither(dither);
        invalidateSelf();
    }

    @Override
    public void setAutoMirrored(boolean mirrored) {
        mNinePatchState.mAutoMirrored = mirrored;
    }

    private boolean needsMirroring() {
        return isAutoMirrored() && getLayoutDirection() == LayoutDirection.RTL;
    }

    @Override
    public boolean isAutoMirrored() {
        return mNinePatchState.mAutoMirrored;
    }

    @Override
    public void setFilterBitmap(boolean filter) {
        getPaint().setFilterBitmap(filter);
        invalidateSelf();
    }

    @Override
    public void inflate(Resources r, XmlPullParser parser, AttributeSet attrs, Theme theme)
            throws XmlPullParserException, IOException {
        super.inflate(r, parser, attrs, theme);

        final TypedArray a = obtainAttributes(
                r, theme, attrs, R.styleable.NinePatchDrawable);
        inflateStateFromTypedArray(a);
        a.recycle();
    }

    /**
     * Initializes the constant state from the values in the typed array.
     */
    private void inflateStateFromTypedArray(TypedArray a) throws XmlPullParserException {
        final Resources r = a.getResources();
        final NinePatchState ninePatchState = mNinePatchState;

        // Extract the theme attributes, if any.
        final int[] themeAttrs = a.extractThemeAttrs();
        ninePatchState.mThemeAttrs = themeAttrs;

        if (themeAttrs == null || themeAttrs[R.styleable.NinePatchDrawable_dither] == 0) {
            final boolean dither = a.getBoolean(
                    R.styleable.NinePatchDrawable_dither, DEFAULT_DITHER);
            ninePatchState.mDither = dither;
        }

        if (themeAttrs == null || themeAttrs[R.styleable.NinePatchDrawable_src] == 0) {
            final int id = a.getResourceId(R.styleable.NinePatchDrawable_src, 0);
            if (id == 0) {
                throw new XmlPullParserException(a.getPositionDescription() +
                        ": <nine-patch> requires a valid src attribute");
            }

            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inDither = !ninePatchState.mDither;
            options.inScreenDensity = r.getDisplayMetrics().noncompatDensityDpi;

            final Rect padding = new Rect();
            final Rect opticalInsets = new Rect();
            Bitmap bitmap = null;

            try {
                final TypedValue value = new TypedValue();
                final InputStream is = r.openRawResource(id, value);

                bitmap = BitmapFactory.decodeResourceStream(r, value, is, padding, options);

                is.close();
            } catch (IOException e) {
                // Ignore
            }

            if (bitmap == null) {
                throw new XmlPullParserException(a.getPositionDescription() +
                        ": <nine-patch> requires a valid src attribute");
            } else if (bitmap.getNinePatchChunk() == null) {
                throw new XmlPullParserException(a.getPositionDescription() +
                        ": <nine-patch> requires a valid 9-patch source image");
            }

            final NinePatch ninePatch = new NinePatch(bitmap, bitmap.getNinePatchChunk());
            ninePatchState.mNinePatch = ninePatch;
            ninePatchState.mPadding = padding;
            ninePatchState.mOpticalInsets = Insets.of(opticalInsets);
        }

        if (themeAttrs == null || themeAttrs[R.styleable.NinePatchDrawable_autoMirrored] == 0) {
            final boolean autoMirrored = a.getBoolean(
                    R.styleable.NinePatchDrawable_autoMirrored, false);
            ninePatchState.mAutoMirrored = autoMirrored;
        }

        if (themeAttrs == null || themeAttrs[R.styleable.NinePatchDrawable_tintMode] == 0) {
            final int tintModeValue = a.getInt(R.styleable.NinePatchDrawable_tintMode, -1);
            ninePatchState.mTintMode = Drawable.parseTintMode(tintModeValue, Mode.SRC_IN);
        }

        if (themeAttrs == null || themeAttrs[R.styleable.NinePatchDrawable_tint] == 0) {
            ninePatchState.mTint = a.getColorStateList(R.styleable.NinePatchDrawable_tint);
            if (ninePatchState.mTint != null) {
                final int color = ninePatchState.mTint.getColorForState(getState(), 0);
                mTintFilter = new PorterDuffColorFilter(color, ninePatchState.mTintMode);
            }
        }

        // Apply the constant state to the paint.
        initializeWithState(ninePatchState, r);

        // Push density applied by setNinePatchState into state.
        ninePatchState.mTargetDensity = mTargetDensity;
    }

    @Override
    public void applyTheme(Theme t) {
        super.applyTheme(t);

        final NinePatchState state = mNinePatchState;
        if (state == null) {
            throw new RuntimeException("Can't apply theme to <nine-patch> with no constant state");
        }

        final int[] themeAttrs = state.mThemeAttrs;
        if (themeAttrs != null) {
            final TypedArray a = t.resolveAttributes(
                    themeAttrs, R.styleable.NinePatchDrawable, 0, 0);
            updateStateFromTypedArray(a);
            a.recycle();
        }
    }

    /**
     * Updates the constant state from the values in the typed array.
     */
    private void updateStateFromTypedArray(TypedArray a) {
        final Resources r = a.getResources();
        final NinePatchState state = mNinePatchState;

        if (a.hasValue(R.styleable.NinePatchDrawable_dither)) {
            state.mDither = a.getBoolean(R.styleable.NinePatchDrawable_dither, DEFAULT_DITHER);
        }

        if (a.hasValue(R.styleable.NinePatchDrawable_autoMirrored)) {
            state.mAutoMirrored = a.getBoolean(R.styleable.NinePatchDrawable_autoMirrored, false);
        }

        if (a.hasValue(R.styleable.NinePatchDrawable_src)) {
            final int id = a.getResourceId(R.styleable.NinePatchDrawable_src, 0);
            if (id == 0) {
                throw new RuntimeException(a.getPositionDescription() +
                        ": <nine-patch> requires a valid src attribute");
            }

            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inDither = !state.mDither;
            options.inScreenDensity = r.getDisplayMetrics().noncompatDensityDpi;

            final Rect padding = new Rect();
            final Rect opticalInsets = new Rect();
            Bitmap bitmap = null;

            try {
                final TypedValue value = new TypedValue();
                final InputStream is = r.openRawResource(id, value);

                bitmap = BitmapFactory.decodeResourceStream(r, value, is, padding, options);

                is.close();
            } catch (IOException e) {
                // Ignore
            }

            if (bitmap == null) {
                throw new RuntimeException(a.getPositionDescription() +
                        ": <nine-patch> requires a valid src attribute");
            } else if (bitmap.getNinePatchChunk() == null) {
                throw new RuntimeException(a.getPositionDescription() +
                        ": <nine-patch> requires a valid 9-patch source image");
            }

            state.mNinePatch = new NinePatch(bitmap, bitmap.getNinePatchChunk());
            state.mPadding = padding;
            state.mOpticalInsets = Insets.of(opticalInsets);
        }

        if (a.hasValue(R.styleable.NinePatchDrawable_tintMode)) {
            final int modeValue = a.getInt(R.styleable.NinePatchDrawable_tintMode, -1);
            state.mTintMode = Drawable.parseTintMode(modeValue, Mode.SRC_IN);
        }

        if (a.hasValue(R.styleable.NinePatchDrawable_tint)) {
            final ColorStateList tint = a.getColorStateList(R.styleable.NinePatchDrawable_tint);
            if (tint != null) {
                state.mTint = tint;
                final int color = tint.getColorForState(getState(), 0);
                mTintFilter = new PorterDuffColorFilter(color, state.mTintMode);
            }
        }

        // Apply the constant state to the paint.
        initializeWithState(state, r);

        // Push density applied by setNinePatchState into state.
        state.mTargetDensity = mTargetDensity;
    }

    @Override
    public boolean canApplyTheme() {
        return mNinePatchState != null && mNinePatchState.mThemeAttrs != null;
    }

    public Paint getPaint() {
        if (mPaint == null) {
            mPaint = new Paint();
            mPaint.setDither(DEFAULT_DITHER);
        }
        return mPaint;
    }

    /**
     * Retrieves the width of the source .png file (before resizing).
     */
    @Override
    public int getIntrinsicWidth() {
        return mBitmapWidth;
    }

    /**
     * Retrieves the height of the source .png file (before resizing).
     */
    @Override
    public int getIntrinsicHeight() {
        return mBitmapHeight;
    }

    @Override
    public int getMinimumWidth() {
        return mBitmapWidth;
    }

    @Override
    public int getMinimumHeight() {
        return mBitmapHeight;
    }

    /**
     * Returns a {@link android.graphics.PixelFormat graphics.PixelFormat}
     * value of OPAQUE or TRANSLUCENT.
     */
    @Override
    public int getOpacity() {
        return mNinePatch.hasAlpha() || (mPaint != null && mPaint.getAlpha() < 255) ?
                PixelFormat.TRANSLUCENT : PixelFormat.OPAQUE;
    }

    @Override
    public Region getTransparentRegion() {
        return mNinePatch.getTransparentRegion(getBounds());
    }

    @Override
    public ConstantState getConstantState() {
        mNinePatchState.mChangingConfigurations = getChangingConfigurations();
        return mNinePatchState;
    }

    @Override
    public Drawable mutate() {
        if (!mMutated && super.mutate() == this) {
            mNinePatchState = new NinePatchState(mNinePatchState);
            mNinePatch = mNinePatchState.mNinePatch;
            mMutated = true;
        }
        return this;
    }

    @Override
    protected boolean onStateChange(int[] stateSet) {
        final ColorStateList tint = mNinePatchState.mTint;
        if (tint != null) {
            final int newColor = tint.getColorForState(stateSet, 0);
            final int oldColor = mTintFilter.getColor();
            if (oldColor != newColor) {
                mTintFilter.setColor(newColor);
                invalidateSelf();
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean isStateful() {
        final NinePatchState s = mNinePatchState;
        return super.isStateful() || (s.mTint != null && s.mTint.isStateful());
    }

    final static class NinePatchState extends ConstantState {
        NinePatch mNinePatch;
        ColorStateList mTint;
        Mode mTintMode = Mode.SRC_IN;
        Rect mPadding;
        Insets mOpticalInsets;
        boolean mDither;
        int[] mThemeAttrs;
        int mChangingConfigurations;
        int mTargetDensity = DisplayMetrics.DENSITY_DEFAULT;
        boolean mAutoMirrored;
        
        NinePatchState() {
            // Empty constructor.
        }

        NinePatchState(NinePatch ninePatch, Rect padding) {
            this(ninePatch, padding, new Rect(), DEFAULT_DITHER, false);
        }

        NinePatchState(NinePatch ninePatch, Rect padding, Rect opticalInsets) {
            this(ninePatch, padding, opticalInsets, DEFAULT_DITHER, false);
        }

        NinePatchState(NinePatch ninePatch, Rect rect, Rect opticalInsets, boolean dither,
                boolean autoMirror) {
            mNinePatch = ninePatch;
            mPadding = rect;
            mOpticalInsets = Insets.of(opticalInsets);
            mDither = dither;
            mAutoMirrored = autoMirror;
        }

        // Copy constructor

        NinePatchState(NinePatchState state) {
            // We don't deep-copy any fields because they are all immutable.
            mNinePatch = state.mNinePatch;
            mTint = state.mTint;
            mTintMode = state.mTintMode;
            mThemeAttrs = state.mThemeAttrs;
            mPadding = state.mPadding;
            mOpticalInsets = state.mOpticalInsets;
            mDither = state.mDither;
            mChangingConfigurations = state.mChangingConfigurations;
            mTargetDensity = state.mTargetDensity;
            mAutoMirrored = state.mAutoMirrored;
        }

        @Override
        public boolean canApplyTheme() {
            return mThemeAttrs != null;
        }

        @Override
        public Bitmap getBitmap() {
            return mNinePatch.getBitmap();
        }

        @Override
        public Drawable newDrawable() {
            return new NinePatchDrawable(this, null, null);
        }

        @Override
        public Drawable newDrawable(Resources res) {
            return new NinePatchDrawable(this, res, null);
        }

        @Override
        public Drawable newDrawable(Resources res, Theme theme) {
            return new NinePatchDrawable(this, res, theme);
        }

        @Override
        public int getChangingConfigurations() {
            return mChangingConfigurations;
        }
    }

    private NinePatchDrawable(NinePatchState state, Resources res, Theme theme) {
        if (theme != null && state.canApplyTheme()) {
            mNinePatchState = new NinePatchState(state);
            applyTheme(theme);
        } else {
            mNinePatchState = state;
        }

        initializeWithState(state, res);
    }

    /**
     * Initializes local dynamic properties from state.
     */
    private void initializeWithState(NinePatchState state, Resources res) {
        if (res != null) {
            mTargetDensity = res.getDisplayMetrics().densityDpi;
        } else {
            mTargetDensity = state.mTargetDensity;
        }

        // If we can, avoid calling any methods that initialize Paint.
        if (state.mDither != DEFAULT_DITHER) {
            setDither(state.mDither);
        }

        if (state.mTint != null) {
            final int color = state.mTint.getColorForState(getState(), 0);
            mTintFilter = new PorterDuffColorFilter(color, state.mTintMode);
        }

        final Rect statePadding = state.mPadding;
        mPadding =  statePadding != null ? new Rect(statePadding) : null;

        setNinePatch(state.mNinePatch);
    }
}
