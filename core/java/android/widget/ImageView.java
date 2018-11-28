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

package android.widget;

import android.annotation.DrawableRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.annotation.UnsupportedAppUsage;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Xfermode;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.RemotableViewMethod;
import android.view.View;
import android.view.ViewDebug;
import android.view.ViewHierarchyEncoder;
import android.view.accessibility.AccessibilityEvent;
import android.widget.RemoteViews.RemoteView;

import com.android.internal.R;

import java.io.IOException;

/**
 * Displays image resources, for example {@link android.graphics.Bitmap}
 * or {@link android.graphics.drawable.Drawable} resources.
 * ImageView is also commonly used to {@link #setImageTintMode(PorterDuff.Mode)
 * apply tints to an image} and handle {@link #setScaleType(ScaleType) image scaling}.
 *
 * <p>
 * The following XML snippet is a common example of using an ImageView to display an image resource:
 * </p>
 * <pre>
 * &lt;LinearLayout
 *     xmlns:android="http://schemas.android.com/apk/res/android"
 *     android:layout_width="match_parent"
 *     android:layout_height="match_parent"&gt;
 *     &lt;ImageView
 *         android:layout_width="wrap_content"
 *         android:layout_height="wrap_content"
 *         android:src="@mipmap/ic_launcher"
 *         /&gt;
 * &lt;/LinearLayout&gt;
 * </pre>
 *
 * <p>
 * To learn more about Drawables, see: <a href="{@docRoot}guide/topics/resources/drawable-resource.html">Drawable Resources</a>.
 * To learn more about working with Bitmaps, see: <a href="{@docRoot}topic/performance/graphics/index.html">Handling Bitmaps</a>.
 * </p>
 *
 * @attr ref android.R.styleable#ImageView_adjustViewBounds
 * @attr ref android.R.styleable#ImageView_src
 * @attr ref android.R.styleable#ImageView_maxWidth
 * @attr ref android.R.styleable#ImageView_maxHeight
 * @attr ref android.R.styleable#ImageView_tint
 * @attr ref android.R.styleable#ImageView_scaleType
 * @attr ref android.R.styleable#ImageView_cropToPadding
 */
@RemoteView
public class ImageView extends View {
    private static final String LOG_TAG = "ImageView";

    // settable by the client
    @UnsupportedAppUsage
    private Uri mUri;
    @UnsupportedAppUsage
    private int mResource = 0;
    private Matrix mMatrix;
    private ScaleType mScaleType;
    private boolean mHaveFrame = false;
    @UnsupportedAppUsage
    private boolean mAdjustViewBounds = false;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    private int mMaxWidth = Integer.MAX_VALUE;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    private int mMaxHeight = Integer.MAX_VALUE;

    // these are applied to the drawable
    private ColorFilter mColorFilter = null;
    private boolean mHasColorFilter = false;
    private Xfermode mXfermode;
    private boolean mHasXfermode = false;
    @UnsupportedAppUsage
    private int mAlpha = 255;
    private boolean mHasAlpha = false;
    private final int mViewAlphaScale = 256;

    @UnsupportedAppUsage
    private Drawable mDrawable = null;
    @UnsupportedAppUsage
    private BitmapDrawable mRecycleableBitmapDrawable = null;
    private ColorStateList mDrawableTintList = null;
    private PorterDuff.Mode mDrawableTintMode = null;
    private boolean mHasDrawableTint = false;
    private boolean mHasDrawableTintMode = false;

    private int[] mState = null;
    private boolean mMergeState = false;
    private int mLevel = 0;
    @UnsupportedAppUsage
    private int mDrawableWidth;
    @UnsupportedAppUsage
    private int mDrawableHeight;
    @UnsupportedAppUsage
    private Matrix mDrawMatrix = null;

    // Avoid allocations...
    private final RectF mTempSrc = new RectF();
    private final RectF mTempDst = new RectF();

    @UnsupportedAppUsage
    private boolean mCropToPadding;

    private int mBaseline = -1;
    private boolean mBaselineAlignBottom = false;

    /** Compatibility modes dependent on targetSdkVersion of the app. */
    private static boolean sCompatDone;

    /** AdjustViewBounds behavior will be in compatibility mode for older apps. */
    private static boolean sCompatAdjustViewBounds;

    /** Whether to pass Resources when creating the source from a stream. */
    private static boolean sCompatUseCorrectStreamDensity;

    /** Whether to use pre-Nougat drawable visibility dispatching conditions. */
    private static boolean sCompatDrawableVisibilityDispatch;

    private static final ScaleType[] sScaleTypeArray = {
        ScaleType.MATRIX,
        ScaleType.FIT_XY,
        ScaleType.FIT_START,
        ScaleType.FIT_CENTER,
        ScaleType.FIT_END,
        ScaleType.CENTER,
        ScaleType.CENTER_CROP,
        ScaleType.CENTER_INSIDE
    };

    public ImageView(Context context) {
        super(context);
        initImageView();
    }

    public ImageView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ImageView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public ImageView(Context context, @Nullable AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        initImageView();

        // ImageView is not important by default, unless app developer overrode attribute.
        if (getImportantForAutofill() == IMPORTANT_FOR_AUTOFILL_AUTO) {
            setImportantForAutofill(IMPORTANT_FOR_AUTOFILL_NO);
        }

        final TypedArray a = context.obtainStyledAttributes(
                attrs, R.styleable.ImageView, defStyleAttr, defStyleRes);

        final Drawable d = a.getDrawable(R.styleable.ImageView_src);
        if (d != null) {
            setImageDrawable(d);
        }

        mBaselineAlignBottom = a.getBoolean(R.styleable.ImageView_baselineAlignBottom, false);
        mBaseline = a.getDimensionPixelSize(R.styleable.ImageView_baseline, -1);

        setAdjustViewBounds(a.getBoolean(R.styleable.ImageView_adjustViewBounds, false));
        setMaxWidth(a.getDimensionPixelSize(R.styleable.ImageView_maxWidth, Integer.MAX_VALUE));
        setMaxHeight(a.getDimensionPixelSize(R.styleable.ImageView_maxHeight, Integer.MAX_VALUE));

        final int index = a.getInt(R.styleable.ImageView_scaleType, -1);
        if (index >= 0) {
            setScaleType(sScaleTypeArray[index]);
        }

        if (a.hasValue(R.styleable.ImageView_tint)) {
            mDrawableTintList = a.getColorStateList(R.styleable.ImageView_tint);
            mHasDrawableTint = true;

            // Prior to L, this attribute would always set a color filter with
            // blending mode SRC_ATOP. Preserve that default behavior.
            mDrawableTintMode = PorterDuff.Mode.SRC_ATOP;
            mHasDrawableTintMode = true;
        }

        if (a.hasValue(R.styleable.ImageView_tintMode)) {
            mDrawableTintMode = Drawable.parseTintMode(a.getInt(
                    R.styleable.ImageView_tintMode, -1), mDrawableTintMode);
            mHasDrawableTintMode = true;
        }

        applyImageTint();

        final int alpha = a.getInt(R.styleable.ImageView_drawableAlpha, 255);
        if (alpha != 255) {
            setImageAlpha(alpha);
        }

        mCropToPadding = a.getBoolean(
                R.styleable.ImageView_cropToPadding, false);

        a.recycle();

        //need inflate syntax/reader for matrix
    }

    private void initImageView() {
        mMatrix = new Matrix();
        mScaleType = ScaleType.FIT_CENTER;

        if (!sCompatDone) {
            final int targetSdkVersion = mContext.getApplicationInfo().targetSdkVersion;
            sCompatAdjustViewBounds = targetSdkVersion <= Build.VERSION_CODES.JELLY_BEAN_MR1;
            sCompatUseCorrectStreamDensity = targetSdkVersion > Build.VERSION_CODES.M;
            sCompatDrawableVisibilityDispatch = targetSdkVersion < Build.VERSION_CODES.N;
            sCompatDone = true;
        }
    }

    @Override
    protected boolean verifyDrawable(@NonNull Drawable dr) {
        return mDrawable == dr || super.verifyDrawable(dr);
    }

    @Override
    public void jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState();
        if (mDrawable != null) mDrawable.jumpToCurrentState();
    }

    @Override
    public void invalidateDrawable(@NonNull Drawable dr) {
        if (dr == mDrawable) {
            if (dr != null) {
                // update cached drawable dimensions if they've changed
                final int w = dr.getIntrinsicWidth();
                final int h = dr.getIntrinsicHeight();
                if (w != mDrawableWidth || h != mDrawableHeight) {
                    mDrawableWidth = w;
                    mDrawableHeight = h;
                    // updates the matrix, which is dependent on the bounds
                    configureBounds();
                }
            }
            /* we invalidate the whole view in this case because it's very
             * hard to know where the drawable actually is. This is made
             * complicated because of the offsets and transformations that
             * can be applied. In theory we could get the drawable's bounds
             * and run them through the transformation and offsets, but this
             * is probably not worth the effort.
             */
            invalidate();
        } else {
            super.invalidateDrawable(dr);
        }
    }

    @Override
    public boolean hasOverlappingRendering() {
        return (getBackground() != null && getBackground().getCurrent() != null);
    }

    /** @hide */
    @Override
    public void onPopulateAccessibilityEventInternal(AccessibilityEvent event) {
        super.onPopulateAccessibilityEventInternal(event);

        final CharSequence contentDescription = getContentDescription();
        if (!TextUtils.isEmpty(contentDescription)) {
            event.getText().add(contentDescription);
        }
    }

    /**
     * True when ImageView is adjusting its bounds
     * to preserve the aspect ratio of its drawable
     *
     * @return whether to adjust the bounds of this view
     * to preserve the original aspect ratio of the drawable
     *
     * @see #setAdjustViewBounds(boolean)
     *
     * @attr ref android.R.styleable#ImageView_adjustViewBounds
     */
    public boolean getAdjustViewBounds() {
        return mAdjustViewBounds;
    }

    /**
     * Set this to true if you want the ImageView to adjust its bounds
     * to preserve the aspect ratio of its drawable.
     *
     * <p><strong>Note:</strong> If the application targets API level 17 or lower,
     * adjustViewBounds will allow the drawable to shrink the view bounds, but not grow
     * to fill available measured space in all cases. This is for compatibility with
     * legacy {@link android.view.View.MeasureSpec MeasureSpec} and
     * {@link android.widget.RelativeLayout RelativeLayout} behavior.</p>
     *
     * @param adjustViewBounds Whether to adjust the bounds of this view
     * to preserve the original aspect ratio of the drawable.
     *
     * @see #getAdjustViewBounds()
     *
     * @attr ref android.R.styleable#ImageView_adjustViewBounds
     */
    @android.view.RemotableViewMethod
    public void setAdjustViewBounds(boolean adjustViewBounds) {
        mAdjustViewBounds = adjustViewBounds;
        if (adjustViewBounds) {
            setScaleType(ScaleType.FIT_CENTER);
        }
    }

    /**
     * The maximum width of this view.
     *
     * @return The maximum width of this view
     *
     * @see #setMaxWidth(int)
     *
     * @attr ref android.R.styleable#ImageView_maxWidth
     */
    public int getMaxWidth() {
        return mMaxWidth;
    }

    /**
     * An optional argument to supply a maximum width for this view. Only valid if
     * {@link #setAdjustViewBounds(boolean)} has been set to true. To set an image to be a maximum
     * of 100 x 100 while preserving the original aspect ratio, do the following: 1) set
     * adjustViewBounds to true 2) set maxWidth and maxHeight to 100 3) set the height and width
     * layout params to WRAP_CONTENT.
     *
     * <p>
     * Note that this view could be still smaller than 100 x 100 using this approach if the original
     * image is small. To set an image to a fixed size, specify that size in the layout params and
     * then use {@link #setScaleType(android.widget.ImageView.ScaleType)} to determine how to fit
     * the image within the bounds.
     * </p>
     *
     * @param maxWidth maximum width for this view
     *
     * @see #getMaxWidth()
     *
     * @attr ref android.R.styleable#ImageView_maxWidth
     */
    @android.view.RemotableViewMethod
    public void setMaxWidth(int maxWidth) {
        mMaxWidth = maxWidth;
    }

    /**
     * The maximum height of this view.
     *
     * @return The maximum height of this view
     *
     * @see #setMaxHeight(int)
     *
     * @attr ref android.R.styleable#ImageView_maxHeight
     */
    public int getMaxHeight() {
        return mMaxHeight;
    }

    /**
     * An optional argument to supply a maximum height for this view. Only valid if
     * {@link #setAdjustViewBounds(boolean)} has been set to true. To set an image to be a
     * maximum of 100 x 100 while preserving the original aspect ratio, do the following: 1) set
     * adjustViewBounds to true 2) set maxWidth and maxHeight to 100 3) set the height and width
     * layout params to WRAP_CONTENT.
     *
     * <p>
     * Note that this view could be still smaller than 100 x 100 using this approach if the original
     * image is small. To set an image to a fixed size, specify that size in the layout params and
     * then use {@link #setScaleType(android.widget.ImageView.ScaleType)} to determine how to fit
     * the image within the bounds.
     * </p>
     *
     * @param maxHeight maximum height for this view
     *
     * @see #getMaxHeight()
     *
     * @attr ref android.R.styleable#ImageView_maxHeight
     */
    @android.view.RemotableViewMethod
    public void setMaxHeight(int maxHeight) {
        mMaxHeight = maxHeight;
    }

    /**
     * Gets the current Drawable, or null if no Drawable has been
     * assigned.
     *
     * @return the view's drawable, or null if no drawable has been
     * assigned.
     */
    public Drawable getDrawable() {
        if (mDrawable == mRecycleableBitmapDrawable) {
            // Consider our cached version dirty since app code now has a reference to it
            mRecycleableBitmapDrawable = null;
        }
        return mDrawable;
    }

    private class ImageDrawableCallback implements Runnable {

        private final Drawable drawable;
        private final Uri uri;
        private final int resource;

        ImageDrawableCallback(Drawable drawable, Uri uri, int resource) {
            this.drawable = drawable;
            this.uri = uri;
            this.resource = resource;
        }

        @Override
        public void run() {
            setImageDrawable(drawable);
            mUri = uri;
            mResource = resource;
        }
    }

    /**
     * Sets a drawable as the content of this ImageView.
     * <p class="note">This does Bitmap reading and decoding on the UI
     * thread, which can cause a latency hiccup.  If that's a concern,
     * consider using {@link #setImageDrawable(android.graphics.drawable.Drawable)} or
     * {@link #setImageBitmap(android.graphics.Bitmap)} and
     * {@link android.graphics.BitmapFactory} instead.</p>
     *
     * @param resId the resource identifier of the drawable
     *
     * @attr ref android.R.styleable#ImageView_src
     */
    @android.view.RemotableViewMethod(asyncImpl="setImageResourceAsync")
    public void setImageResource(@DrawableRes int resId) {
        // The resource configuration may have changed, so we should always
        // try to load the resource even if the resId hasn't changed.
        final int oldWidth = mDrawableWidth;
        final int oldHeight = mDrawableHeight;

        updateDrawable(null);
        mResource = resId;
        mUri = null;

        resolveUri();

        if (oldWidth != mDrawableWidth || oldHeight != mDrawableHeight) {
            requestLayout();
        }
        invalidate();
    }

    /** @hide **/
    @UnsupportedAppUsage
    public Runnable setImageResourceAsync(@DrawableRes int resId) {
        Drawable d = null;
        if (resId != 0) {
            try {
                d = getContext().getDrawable(resId);
            } catch (Exception e) {
                Log.w(LOG_TAG, "Unable to find resource: " + resId, e);
                resId = 0;
            }
        }
        return new ImageDrawableCallback(d, null, resId);
    }

    /**
     * Sets the content of this ImageView to the specified Uri.
     * Note that you use this method to load images from a local Uri only.
     * <p/>
     * To learn how to display images from a remote Uri see: <a href="https://developer.android.com/topic/performance/graphics/index.html">Handling Bitmaps</a>
     * <p/>
     * <p class="note">This does Bitmap reading and decoding on the UI
     * thread, which can cause a latency hiccup.  If that's a concern,
     * consider using {@link #setImageDrawable(Drawable)} or
     * {@link #setImageBitmap(android.graphics.Bitmap)} and
     * {@link android.graphics.BitmapFactory} instead.</p>
     *
     * <p class="note">On devices running SDK < 24, this method will fail to
     * apply correct density scaling to images loaded from
     * {@link ContentResolver#SCHEME_CONTENT content} and
     * {@link ContentResolver#SCHEME_FILE file} schemes. Applications running
     * on devices with SDK >= 24 <strong>MUST</strong> specify the
     * {@code targetSdkVersion} in their manifest as 24 or above for density
     * scaling to be applied to images loaded from these schemes.</p>
     *
     * @param uri the Uri of an image, or {@code null} to clear the content
     */
    @android.view.RemotableViewMethod(asyncImpl="setImageURIAsync")
    public void setImageURI(@Nullable Uri uri) {
        if (mResource != 0 || (mUri != uri && (uri == null || mUri == null || !uri.equals(mUri)))) {
            updateDrawable(null);
            mResource = 0;
            mUri = uri;

            final int oldWidth = mDrawableWidth;
            final int oldHeight = mDrawableHeight;

            resolveUri();

            if (oldWidth != mDrawableWidth || oldHeight != mDrawableHeight) {
                requestLayout();
            }
            invalidate();
        }
    }

    /** @hide **/
    @UnsupportedAppUsage
    public Runnable setImageURIAsync(@Nullable Uri uri) {
        if (mResource != 0 || (mUri != uri && (uri == null || mUri == null || !uri.equals(mUri)))) {
            Drawable d = uri == null ? null : getDrawableFromUri(uri);
            if (d == null) {
                // Do not set the URI if the drawable couldn't be loaded.
                uri = null;
            }
            return new ImageDrawableCallback(d, uri, 0);
        }
        return null;
    }

    /**
     * Sets a drawable as the content of this ImageView.
     *
     * @param drawable the Drawable to set, or {@code null} to clear the
     *                 content
     */
    public void setImageDrawable(@Nullable Drawable drawable) {
        if (mDrawable != drawable) {
            mResource = 0;
            mUri = null;

            final int oldWidth = mDrawableWidth;
            final int oldHeight = mDrawableHeight;

            updateDrawable(drawable);

            if (oldWidth != mDrawableWidth || oldHeight != mDrawableHeight) {
                requestLayout();
            }
            invalidate();
        }
    }

    /**
     * Sets the content of this ImageView to the specified Icon.
     *
     * <p class="note">Depending on the Icon type, this may do Bitmap reading
     * and decoding on the UI thread, which can cause UI jank.  If that's a
     * concern, consider using
     * {@link Icon#loadDrawableAsync(Context, Icon.OnDrawableLoadedListener, Handler)}
     * and then {@link #setImageDrawable(android.graphics.drawable.Drawable)}
     * instead.</p>
     *
     * @param icon an Icon holding the desired image, or {@code null} to clear
     *             the content
     */
    @android.view.RemotableViewMethod(asyncImpl="setImageIconAsync")
    public void setImageIcon(@Nullable Icon icon) {
        setImageDrawable(icon == null ? null : icon.loadDrawable(mContext));
    }

    /** @hide **/
    public Runnable setImageIconAsync(@Nullable Icon icon) {
        return new ImageDrawableCallback(icon == null ? null : icon.loadDrawable(mContext), null, 0);
    }

    /**
     * Applies a tint to the image drawable. Does not modify the current tint
     * mode, which is {@link PorterDuff.Mode#SRC_IN} by default.
     * <p>
     * Subsequent calls to {@link #setImageDrawable(Drawable)} will automatically
     * mutate the drawable and apply the specified tint and tint mode using
     * {@link Drawable#setTintList(ColorStateList)}.
     * <p>
     * <em>Note:</em> The default tint mode used by this setter is NOT
     * consistent with the default tint mode used by the
     * {@link android.R.styleable#ImageView_tint android:tint}
     * attribute. If the {@code android:tint} attribute is specified, the
     * default tint mode will be set to {@link PorterDuff.Mode#SRC_ATOP} to
     * ensure consistency with earlier versions of the platform.
     *
     * @param tint the tint to apply, may be {@code null} to clear tint
     *
     * @attr ref android.R.styleable#ImageView_tint
     * @see #getImageTintList()
     * @see Drawable#setTintList(ColorStateList)
     */
    public void setImageTintList(@Nullable ColorStateList tint) {
        mDrawableTintList = tint;
        mHasDrawableTint = true;

        applyImageTint();
    }

    /**
     * Get the current {@link android.content.res.ColorStateList} used to tint the image Drawable,
     * or null if no tint is applied.
     *
     * @return the tint applied to the image drawable
     * @attr ref android.R.styleable#ImageView_tint
     * @see #setImageTintList(ColorStateList)
     */
    @Nullable
    public ColorStateList getImageTintList() {
        return mDrawableTintList;
    }

    /**
     * Specifies the blending mode used to apply the tint specified by
     * {@link #setImageTintList(ColorStateList)}} to the image drawable. The default
     * mode is {@link PorterDuff.Mode#SRC_IN}.
     *
     * @param tintMode the blending mode used to apply the tint, may be
     *                 {@code null} to clear tint
     * @attr ref android.R.styleable#ImageView_tintMode
     * @see #getImageTintMode()
     * @see Drawable#setTintMode(PorterDuff.Mode)
     */
    public void setImageTintMode(@Nullable PorterDuff.Mode tintMode) {
        mDrawableTintMode = tintMode;
        mHasDrawableTintMode = true;

        applyImageTint();
    }

    /**
     * Gets the blending mode used to apply the tint to the image Drawable
     * @return the blending mode used to apply the tint to the image Drawable
     * @attr ref android.R.styleable#ImageView_tintMode
     * @see #setImageTintMode(PorterDuff.Mode)
     */
    @Nullable
    public PorterDuff.Mode getImageTintMode() {
        return mDrawableTintMode;
    }

    private void applyImageTint() {
        if (mDrawable != null && (mHasDrawableTint || mHasDrawableTintMode)) {
            mDrawable = mDrawable.mutate();

            if (mHasDrawableTint) {
                mDrawable.setTintList(mDrawableTintList);
            }

            if (mHasDrawableTintMode) {
                mDrawable.setTintMode(mDrawableTintMode);
            }

            // The drawable (or one of its children) may not have been
            // stateful before applying the tint, so let's try again.
            if (mDrawable.isStateful()) {
                mDrawable.setState(getDrawableState());
            }
        }
    }

    /**
     * Sets a Bitmap as the content of this ImageView.
     *
     * @param bm The bitmap to set
     */
    @android.view.RemotableViewMethod
    public void setImageBitmap(Bitmap bm) {
        // Hacky fix to force setImageDrawable to do a full setImageDrawable
        // instead of doing an object reference comparison
        mDrawable = null;
        if (mRecycleableBitmapDrawable == null) {
            mRecycleableBitmapDrawable = new BitmapDrawable(mContext.getResources(), bm);
        } else {
            mRecycleableBitmapDrawable.setBitmap(bm);
        }
        setImageDrawable(mRecycleableBitmapDrawable);
    }

    /**
     * Set the state of the current {@link android.graphics.drawable.StateListDrawable}.
     * For more information about State List Drawables, see: <a href="https://developer.android.com/guide/topics/resources/drawable-resource.html#StateList">the Drawable Resource Guide</a>.
     *
     * @param state the state to set for the StateListDrawable
     * @param merge if true, merges the state values for the state you specify into the current state
     */
    public void setImageState(int[] state, boolean merge) {
        mState = state;
        mMergeState = merge;
        if (mDrawable != null) {
            refreshDrawableState();
            resizeFromDrawable();
        }
    }

    @Override
    public void setSelected(boolean selected) {
        super.setSelected(selected);
        resizeFromDrawable();
    }

    /**
     * Sets the image level, when it is constructed from a
     * {@link android.graphics.drawable.LevelListDrawable}.
     *
     * @param level The new level for the image.
     */
    @android.view.RemotableViewMethod
    public void setImageLevel(int level) {
        mLevel = level;
        if (mDrawable != null) {
            mDrawable.setLevel(level);
            resizeFromDrawable();
        }
    }

    /**
     * Options for scaling the bounds of an image to the bounds of this view.
     */
    public enum ScaleType {
        /**
         * Scale using the image matrix when drawing. The image matrix can be set using
         * {@link ImageView#setImageMatrix(Matrix)}. From XML, use this syntax:
         * <code>android:scaleType="matrix"</code>.
         */
        MATRIX      (0),
        /**
         * Scale the image using {@link Matrix.ScaleToFit#FILL}.
         * From XML, use this syntax: <code>android:scaleType="fitXY"</code>.
         */
        FIT_XY      (1),
        /**
         * Scale the image using {@link Matrix.ScaleToFit#START}.
         * From XML, use this syntax: <code>android:scaleType="fitStart"</code>.
         */
        FIT_START   (2),
        /**
         * Scale the image using {@link Matrix.ScaleToFit#CENTER}.
         * From XML, use this syntax:
         * <code>android:scaleType="fitCenter"</code>.
         */
        FIT_CENTER  (3),
        /**
         * Scale the image using {@link Matrix.ScaleToFit#END}.
         * From XML, use this syntax: <code>android:scaleType="fitEnd"</code>.
         */
        FIT_END     (4),
        /**
         * Center the image in the view, but perform no scaling.
         * From XML, use this syntax: <code>android:scaleType="center"</code>.
         */
        CENTER      (5),
        /**
         * Scale the image uniformly (maintain the image's aspect ratio) so
         * that both dimensions (width and height) of the image will be equal
         * to or larger than the corresponding dimension of the view
         * (minus padding). The image is then centered in the view.
         * From XML, use this syntax: <code>android:scaleType="centerCrop"</code>.
         */
        CENTER_CROP (6),
        /**
         * Scale the image uniformly (maintain the image's aspect ratio) so
         * that both dimensions (width and height) of the image will be equal
         * to or less than the corresponding dimension of the view
         * (minus padding). The image is then centered in the view.
         * From XML, use this syntax: <code>android:scaleType="centerInside"</code>.
         */
        CENTER_INSIDE (7);

        ScaleType(int ni) {
            nativeInt = ni;
        }
        final int nativeInt;
    }

    /**
     * Controls how the image should be resized or moved to match the size
     * of this ImageView.
     *
     * @param scaleType The desired scaling mode.
     *
     * @attr ref android.R.styleable#ImageView_scaleType
     */
    public void setScaleType(ScaleType scaleType) {
        if (scaleType == null) {
            throw new NullPointerException();
        }

        if (mScaleType != scaleType) {
            mScaleType = scaleType;

            requestLayout();
            invalidate();
        }
    }

    /**
     * Returns the current ScaleType that is used to scale the bounds of an image to the bounds of the ImageView.
     * @return The ScaleType used to scale the image.
     * @see ImageView.ScaleType
     * @attr ref android.R.styleable#ImageView_scaleType
     */
    public ScaleType getScaleType() {
        return mScaleType;
    }

    /** Returns the view's optional matrix. This is applied to the
        view's drawable when it is drawn. If there is no matrix,
        this method will return an identity matrix.
        Do not change this matrix in place but make a copy.
        If you want a different matrix applied to the drawable,
        be sure to call setImageMatrix().
    */
    public Matrix getImageMatrix() {
        if (mDrawMatrix == null) {
            return new Matrix(Matrix.IDENTITY_MATRIX);
        }
        return mDrawMatrix;
    }

    /**
     * Adds a transformation {@link Matrix} that is applied
     * to the view's drawable when it is drawn.  Allows custom scaling,
     * translation, and perspective distortion.
     *
     * @param matrix The transformation parameters in matrix form.
     */
    public void setImageMatrix(Matrix matrix) {
        // collapse null and identity to just null
        if (matrix != null && matrix.isIdentity()) {
            matrix = null;
        }

        // don't invalidate unless we're actually changing our matrix
        if (matrix == null && !mMatrix.isIdentity() ||
                matrix != null && !mMatrix.equals(matrix)) {
            mMatrix.set(matrix);
            configureBounds();
            invalidate();
        }
    }

    /**
     * Return whether this ImageView crops to padding.
     *
     * @return whether this ImageView crops to padding
     *
     * @see #setCropToPadding(boolean)
     *
     * @attr ref android.R.styleable#ImageView_cropToPadding
     */
    public boolean getCropToPadding() {
        return mCropToPadding;
    }

    /**
     * Sets whether this ImageView will crop to padding.
     *
     * @param cropToPadding whether this ImageView will crop to padding
     *
     * @see #getCropToPadding()
     *
     * @attr ref android.R.styleable#ImageView_cropToPadding
     */
    public void setCropToPadding(boolean cropToPadding) {
        if (mCropToPadding != cropToPadding) {
            mCropToPadding = cropToPadding;
            requestLayout();
            invalidate();
        }
    }

    @UnsupportedAppUsage
    private void resolveUri() {
        if (mDrawable != null) {
            return;
        }

        if (getResources() == null) {
            return;
        }

        Drawable d = null;

        if (mResource != 0) {
            try {
                d = mContext.getDrawable(mResource);
            } catch (Exception e) {
                Log.w(LOG_TAG, "Unable to find resource: " + mResource, e);
                // Don't try again.
                mResource = 0;
            }
        } else if (mUri != null) {
            d = getDrawableFromUri(mUri);

            if (d == null) {
                Log.w(LOG_TAG, "resolveUri failed on bad bitmap uri: " + mUri);
                // Don't try again.
                mUri = null;
            }
        } else {
            return;
        }

        updateDrawable(d);
    }

    private Drawable getDrawableFromUri(Uri uri) {
        final String scheme = uri.getScheme();
        if (ContentResolver.SCHEME_ANDROID_RESOURCE.equals(scheme)) {
            try {
                // Load drawable through Resources, to get the source density information
                ContentResolver.OpenResourceIdResult r =
                        mContext.getContentResolver().getResourceId(uri);
                return r.r.getDrawable(r.id, mContext.getTheme());
            } catch (Exception e) {
                Log.w(LOG_TAG, "Unable to open content: " + uri, e);
            }
        } else if (ContentResolver.SCHEME_CONTENT.equals(scheme)
                || ContentResolver.SCHEME_FILE.equals(scheme)) {
            try {
                Resources res = sCompatUseCorrectStreamDensity ? getResources() : null;
                ImageDecoder.Source src = ImageDecoder.createSource(mContext.getContentResolver(),
                        uri, res);
                return ImageDecoder.decodeDrawable(src, (decoder, info, s) -> {
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
            } catch (IOException e) {
                Log.w(LOG_TAG, "Unable to open content: " + uri, e);
            }
        } else {
            return Drawable.createFromPath(uri.toString());
        }
        return null;
    }

    @Override
    public int[] onCreateDrawableState(int extraSpace) {
        if (mState == null) {
            return super.onCreateDrawableState(extraSpace);
        } else if (!mMergeState) {
            return mState;
        } else {
            return mergeDrawableStates(
                    super.onCreateDrawableState(extraSpace + mState.length), mState);
        }
    }

    @UnsupportedAppUsage
    private void updateDrawable(Drawable d) {
        if (d != mRecycleableBitmapDrawable && mRecycleableBitmapDrawable != null) {
            mRecycleableBitmapDrawable.setBitmap(null);
        }

        boolean sameDrawable = false;

        if (mDrawable != null) {
            sameDrawable = mDrawable == d;
            mDrawable.setCallback(null);
            unscheduleDrawable(mDrawable);
            if (!sCompatDrawableVisibilityDispatch && !sameDrawable && isAttachedToWindow()) {
                mDrawable.setVisible(false, false);
            }
        }

        mDrawable = d;

        if (d != null) {
            d.setCallback(this);
            d.setLayoutDirection(getLayoutDirection());
            if (d.isStateful()) {
                d.setState(getDrawableState());
            }
            if (!sameDrawable || sCompatDrawableVisibilityDispatch) {
                final boolean visible = sCompatDrawableVisibilityDispatch
                        ? getVisibility() == VISIBLE
                        : isAttachedToWindow() && getWindowVisibility() == VISIBLE && isShown();
                d.setVisible(visible, true);
            }
            d.setLevel(mLevel);
            mDrawableWidth = d.getIntrinsicWidth();
            mDrawableHeight = d.getIntrinsicHeight();
            applyImageTint();
            applyColorFilter();
            applyAlpha();
            applyXfermode();

            configureBounds();
        } else {
            mDrawableWidth = mDrawableHeight = -1;
        }
    }

    @UnsupportedAppUsage
    private void resizeFromDrawable() {
        final Drawable d = mDrawable;
        if (d != null) {
            int w = d.getIntrinsicWidth();
            if (w < 0) w = mDrawableWidth;
            int h = d.getIntrinsicHeight();
            if (h < 0) h = mDrawableHeight;
            if (w != mDrawableWidth || h != mDrawableHeight) {
                mDrawableWidth = w;
                mDrawableHeight = h;
                requestLayout();
            }
        }
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        super.onRtlPropertiesChanged(layoutDirection);

        if (mDrawable != null) {
            mDrawable.setLayoutDirection(layoutDirection);
        }
    }

    private static final Matrix.ScaleToFit[] sS2FArray = {
        Matrix.ScaleToFit.FILL,
        Matrix.ScaleToFit.START,
        Matrix.ScaleToFit.CENTER,
        Matrix.ScaleToFit.END
    };

    @UnsupportedAppUsage
    private static Matrix.ScaleToFit scaleTypeToScaleToFit(ScaleType st)  {
        // ScaleToFit enum to their corresponding Matrix.ScaleToFit values
        return sS2FArray[st.nativeInt - 1];
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        resolveUri();
        int w;
        int h;

        // Desired aspect ratio of the view's contents (not including padding)
        float desiredAspect = 0.0f;

        // We are allowed to change the view's width
        boolean resizeWidth = false;

        // We are allowed to change the view's height
        boolean resizeHeight = false;

        final int widthSpecMode = MeasureSpec.getMode(widthMeasureSpec);
        final int heightSpecMode = MeasureSpec.getMode(heightMeasureSpec);

        if (mDrawable == null) {
            // If no drawable, its intrinsic size is 0.
            mDrawableWidth = -1;
            mDrawableHeight = -1;
            w = h = 0;
        } else {
            w = mDrawableWidth;
            h = mDrawableHeight;
            if (w <= 0) w = 1;
            if (h <= 0) h = 1;

            // We are supposed to adjust view bounds to match the aspect
            // ratio of our drawable. See if that is possible.
            if (mAdjustViewBounds) {
                resizeWidth = widthSpecMode != MeasureSpec.EXACTLY;
                resizeHeight = heightSpecMode != MeasureSpec.EXACTLY;

                desiredAspect = (float) w / (float) h;
            }
        }

        final int pleft = mPaddingLeft;
        final int pright = mPaddingRight;
        final int ptop = mPaddingTop;
        final int pbottom = mPaddingBottom;

        int widthSize;
        int heightSize;

        if (resizeWidth || resizeHeight) {
            /* If we get here, it means we want to resize to match the
                drawables aspect ratio, and we have the freedom to change at
                least one dimension.
            */

            // Get the max possible width given our constraints
            widthSize = resolveAdjustedSize(w + pleft + pright, mMaxWidth, widthMeasureSpec);

            // Get the max possible height given our constraints
            heightSize = resolveAdjustedSize(h + ptop + pbottom, mMaxHeight, heightMeasureSpec);

            if (desiredAspect != 0.0f) {
                // See what our actual aspect ratio is
                final float actualAspect = (float)(widthSize - pleft - pright) /
                                        (heightSize - ptop - pbottom);

                if (Math.abs(actualAspect - desiredAspect) > 0.0000001) {

                    boolean done = false;

                    // Try adjusting width to be proportional to height
                    if (resizeWidth) {
                        int newWidth = (int)(desiredAspect * (heightSize - ptop - pbottom)) +
                                pleft + pright;

                        // Allow the width to outgrow its original estimate if height is fixed.
                        if (!resizeHeight && !sCompatAdjustViewBounds) {
                            widthSize = resolveAdjustedSize(newWidth, mMaxWidth, widthMeasureSpec);
                        }

                        if (newWidth <= widthSize) {
                            widthSize = newWidth;
                            done = true;
                        }
                    }

                    // Try adjusting height to be proportional to width
                    if (!done && resizeHeight) {
                        int newHeight = (int)((widthSize - pleft - pright) / desiredAspect) +
                                ptop + pbottom;

                        // Allow the height to outgrow its original estimate if width is fixed.
                        if (!resizeWidth && !sCompatAdjustViewBounds) {
                            heightSize = resolveAdjustedSize(newHeight, mMaxHeight,
                                    heightMeasureSpec);
                        }

                        if (newHeight <= heightSize) {
                            heightSize = newHeight;
                        }
                    }
                }
            }
        } else {
            /* We are either don't want to preserve the drawables aspect ratio,
               or we are not allowed to change view dimensions. Just measure in
               the normal way.
            */
            w += pleft + pright;
            h += ptop + pbottom;

            w = Math.max(w, getSuggestedMinimumWidth());
            h = Math.max(h, getSuggestedMinimumHeight());

            widthSize = resolveSizeAndState(w, widthMeasureSpec, 0);
            heightSize = resolveSizeAndState(h, heightMeasureSpec, 0);
        }

        setMeasuredDimension(widthSize, heightSize);
    }

    private int resolveAdjustedSize(int desiredSize, int maxSize,
                                   int measureSpec) {
        int result = desiredSize;
        final int specMode = MeasureSpec.getMode(measureSpec);
        final int specSize =  MeasureSpec.getSize(measureSpec);
        switch (specMode) {
            case MeasureSpec.UNSPECIFIED:
                /* Parent says we can be as big as we want. Just don't be larger
                   than max size imposed on ourselves.
                */
                result = Math.min(desiredSize, maxSize);
                break;
            case MeasureSpec.AT_MOST:
                // Parent says we can be as big as we want, up to specSize.
                // Don't be larger than specSize, and don't be larger than
                // the max size imposed on ourselves.
                result = Math.min(Math.min(desiredSize, specSize), maxSize);
                break;
            case MeasureSpec.EXACTLY:
                // No choice. Do what we are told.
                result = specSize;
                break;
        }
        return result;
    }

    @Override
    protected boolean setFrame(int l, int t, int r, int b) {
        final boolean changed = super.setFrame(l, t, r, b);
        mHaveFrame = true;
        configureBounds();
        return changed;
    }

    private void configureBounds() {
        if (mDrawable == null || !mHaveFrame) {
            return;
        }

        final int dwidth = mDrawableWidth;
        final int dheight = mDrawableHeight;

        final int vwidth = getWidth() - mPaddingLeft - mPaddingRight;
        final int vheight = getHeight() - mPaddingTop - mPaddingBottom;

        final boolean fits = (dwidth < 0 || vwidth == dwidth)
                && (dheight < 0 || vheight == dheight);

        if (dwidth <= 0 || dheight <= 0 || ScaleType.FIT_XY == mScaleType) {
            /* If the drawable has no intrinsic size, or we're told to
                scaletofit, then we just fill our entire view.
            */
            mDrawable.setBounds(0, 0, vwidth, vheight);
            mDrawMatrix = null;
        } else {
            // We need to do the scaling ourself, so have the drawable
            // use its native size.
            mDrawable.setBounds(0, 0, dwidth, dheight);

            if (ScaleType.MATRIX == mScaleType) {
                // Use the specified matrix as-is.
                if (mMatrix.isIdentity()) {
                    mDrawMatrix = null;
                } else {
                    mDrawMatrix = mMatrix;
                }
            } else if (fits) {
                // The bitmap fits exactly, no transform needed.
                mDrawMatrix = null;
            } else if (ScaleType.CENTER == mScaleType) {
                // Center bitmap in view, no scaling.
                mDrawMatrix = mMatrix;
                mDrawMatrix.setTranslate(Math.round((vwidth - dwidth) * 0.5f),
                                         Math.round((vheight - dheight) * 0.5f));
            } else if (ScaleType.CENTER_CROP == mScaleType) {
                mDrawMatrix = mMatrix;

                float scale;
                float dx = 0, dy = 0;

                if (dwidth * vheight > vwidth * dheight) {
                    scale = (float) vheight / (float) dheight;
                    dx = (vwidth - dwidth * scale) * 0.5f;
                } else {
                    scale = (float) vwidth / (float) dwidth;
                    dy = (vheight - dheight * scale) * 0.5f;
                }

                mDrawMatrix.setScale(scale, scale);
                mDrawMatrix.postTranslate(Math.round(dx), Math.round(dy));
            } else if (ScaleType.CENTER_INSIDE == mScaleType) {
                mDrawMatrix = mMatrix;
                float scale;
                float dx;
                float dy;

                if (dwidth <= vwidth && dheight <= vheight) {
                    scale = 1.0f;
                } else {
                    scale = Math.min((float) vwidth / (float) dwidth,
                            (float) vheight / (float) dheight);
                }

                dx = Math.round((vwidth - dwidth * scale) * 0.5f);
                dy = Math.round((vheight - dheight * scale) * 0.5f);

                mDrawMatrix.setScale(scale, scale);
                mDrawMatrix.postTranslate(dx, dy);
            } else {
                // Generate the required transform.
                mTempSrc.set(0, 0, dwidth, dheight);
                mTempDst.set(0, 0, vwidth, vheight);

                mDrawMatrix = mMatrix;
                mDrawMatrix.setRectToRect(mTempSrc, mTempDst, scaleTypeToScaleToFit(mScaleType));
            }
        }
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();

        final Drawable drawable = mDrawable;
        if (drawable != null && drawable.isStateful()
                && drawable.setState(getDrawableState())) {
            invalidateDrawable(drawable);
        }
    }

    @Override
    public void drawableHotspotChanged(float x, float y) {
        super.drawableHotspotChanged(x, y);

        if (mDrawable != null) {
            mDrawable.setHotspot(x, y);
        }
    }

    /**
     * Applies a temporary transformation {@link Matrix} to the view's drawable when it is drawn.
     * Allows custom scaling, translation, and perspective distortion during an animation.
     *
     * This method is a lightweight analogue of {@link ImageView#setImageMatrix(Matrix)} to use
     * only during animations as this matrix will be cleared after the next drawable
     * update or view's bounds change.
     *
     * @param matrix The transformation parameters in matrix form.
     */
    public void animateTransform(@Nullable Matrix matrix) {
        if (mDrawable == null) {
            return;
        }
        if (matrix == null) {
            final int vwidth = getWidth() - mPaddingLeft - mPaddingRight;
            final int vheight = getHeight() - mPaddingTop - mPaddingBottom;
            mDrawable.setBounds(0, 0, vwidth, vheight);
            mDrawMatrix = null;
        } else {
            mDrawable.setBounds(0, 0, mDrawableWidth, mDrawableHeight);
            if (mDrawMatrix == null) {
                mDrawMatrix = new Matrix();
            }
            mDrawMatrix.set(matrix);
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (mDrawable == null) {
            return; // couldn't resolve the URI
        }

        if (mDrawableWidth == 0 || mDrawableHeight == 0) {
            return;     // nothing to draw (empty bounds)
        }

        if (mDrawMatrix == null && mPaddingTop == 0 && mPaddingLeft == 0) {
            mDrawable.draw(canvas);
        } else {
            final int saveCount = canvas.getSaveCount();
            canvas.save();

            if (mCropToPadding) {
                final int scrollX = mScrollX;
                final int scrollY = mScrollY;
                canvas.clipRect(scrollX + mPaddingLeft, scrollY + mPaddingTop,
                        scrollX + mRight - mLeft - mPaddingRight,
                        scrollY + mBottom - mTop - mPaddingBottom);
            }

            canvas.translate(mPaddingLeft, mPaddingTop);

            if (mDrawMatrix != null) {
                canvas.concat(mDrawMatrix);
            }
            mDrawable.draw(canvas);
            canvas.restoreToCount(saveCount);
        }
    }

    /**
     * <p>Return the offset of the widget's text baseline from the widget's top
     * boundary. </p>
     *
     * @return the offset of the baseline within the widget's bounds or -1
     *         if baseline alignment is not supported.
     */
    @Override
    @ViewDebug.ExportedProperty(category = "layout")
    public int getBaseline() {
        if (mBaselineAlignBottom) {
            return getMeasuredHeight();
        } else {
            return mBaseline;
        }
    }

    /**
     * <p>Set the offset of the widget's text baseline from the widget's top
     * boundary.  This value is overridden by the {@link #setBaselineAlignBottom(boolean)}
     * property.</p>
     *
     * @param baseline The baseline to use, or -1 if none is to be provided.
     *
     * @see #setBaseline(int)
     * @attr ref android.R.styleable#ImageView_baseline
     */
    public void setBaseline(int baseline) {
        if (mBaseline != baseline) {
            mBaseline = baseline;
            requestLayout();
        }
    }

    /**
     * Sets whether the baseline of this view to the bottom of the view.
     * Setting this value overrides any calls to setBaseline.
     *
     * @param aligned If true, the image view will be baseline aligned by its bottom edge.
     *
     * @attr ref android.R.styleable#ImageView_baselineAlignBottom
     */
    public void setBaselineAlignBottom(boolean aligned) {
        if (mBaselineAlignBottom != aligned) {
            mBaselineAlignBottom = aligned;
            requestLayout();
        }
    }

    /**
     * Checks whether this view's baseline is considered the bottom of the view.
     *
     * @return True if the ImageView's baseline is considered the bottom of the view, false if otherwise.
     * @see #setBaselineAlignBottom(boolean)
     */
    public boolean getBaselineAlignBottom() {
        return mBaselineAlignBottom;
    }

    /**
     * Sets a tinting option for the image.
     *
     * @param color Color tint to apply.
     * @param mode How to apply the color.  The standard mode is
     * {@link PorterDuff.Mode#SRC_ATOP}
     *
     * @attr ref android.R.styleable#ImageView_tint
     */
    public final void setColorFilter(int color, PorterDuff.Mode mode) {
        setColorFilter(new PorterDuffColorFilter(color, mode));
    }

    /**
     * Set a tinting option for the image. Assumes
     * {@link PorterDuff.Mode#SRC_ATOP} blending mode.
     *
     * @param color Color tint to apply.
     * @attr ref android.R.styleable#ImageView_tint
     */
    @RemotableViewMethod
    public final void setColorFilter(int color) {
        setColorFilter(color, PorterDuff.Mode.SRC_ATOP);
    }

    /**
     * Removes the image's {@link android.graphics.ColorFilter}.
     *
     * @see #setColorFilter(int)
     * @see #getColorFilter()
     */
    public final void clearColorFilter() {
        setColorFilter(null);
    }

    /**
     * @hide Candidate for future API inclusion
     */
    public final void setXfermode(Xfermode mode) {
        if (mXfermode != mode) {
            mXfermode = mode;
            mHasXfermode = true;
            applyXfermode();
            invalidate();
        }
    }

    /**
     * Returns the active color filter for this ImageView.
     *
     * @return the active color filter for this ImageView
     *
     * @see #setColorFilter(android.graphics.ColorFilter)
     */
    public ColorFilter getColorFilter() {
        return mColorFilter;
    }

    /**
     * Apply an arbitrary colorfilter to the image.
     *
     * @param cf the colorfilter to apply (may be null)
     *
     * @see #getColorFilter()
     */
    public void setColorFilter(ColorFilter cf) {
        if (mColorFilter != cf) {
            mColorFilter = cf;
            mHasColorFilter = true;
            applyColorFilter();
            invalidate();
        }
    }

    /**
     * Returns the alpha that will be applied to the drawable of this ImageView.
     *
     * @return the alpha value that will be applied to the drawable of this
     * ImageView (between 0 and 255 inclusive, with 0 being transparent and
     * 255 being opaque)
     *
     * @see #setImageAlpha(int)
     */
    public int getImageAlpha() {
        return mAlpha;
    }

    /**
     * Sets the alpha value that should be applied to the image.
     *
     * @param alpha the alpha value that should be applied to the image (between
     * 0 and 255 inclusive, with 0 being transparent and 255 being opaque)
     *
     * @see #getImageAlpha()
     */
    @RemotableViewMethod
    public void setImageAlpha(int alpha) {
        setAlpha(alpha);
    }

    /**
     * Sets the alpha value that should be applied to the image.
     *
     * @param alpha the alpha value that should be applied to the image
     *
     * @deprecated use #setImageAlpha(int) instead
     */
    @Deprecated
    @RemotableViewMethod
    public void setAlpha(int alpha) {
        alpha &= 0xFF;          // keep it legal
        if (mAlpha != alpha) {
            mAlpha = alpha;
            mHasAlpha = true;
            applyAlpha();
            invalidate();
        }
    }

    private void applyXfermode() {
        if (mDrawable != null && mHasXfermode) {
            mDrawable = mDrawable.mutate();
            mDrawable.setXfermode(mXfermode);
        }
    }

    private void applyColorFilter() {
        if (mDrawable != null && mHasColorFilter) {
            mDrawable = mDrawable.mutate();
            mDrawable.setColorFilter(mColorFilter);
        }
    }

    private void applyAlpha() {
        if (mDrawable != null && mHasAlpha) {
            mDrawable = mDrawable.mutate();
            mDrawable.setAlpha(mAlpha * mViewAlphaScale >> 8);
        }
    }

    @Override
    public boolean isOpaque() {
        return super.isOpaque() || mDrawable != null && mXfermode == null
                && mDrawable.getOpacity() == PixelFormat.OPAQUE
                && mAlpha * mViewAlphaScale >> 8 == 255
                && isFilledByImage();
    }

    private boolean isFilledByImage() {
        if (mDrawable == null) {
            return false;
        }

        final Rect bounds = mDrawable.getBounds();
        final Matrix matrix = mDrawMatrix;
        if (matrix == null) {
            return bounds.left <= 0 && bounds.top <= 0 && bounds.right >= getWidth()
                    && bounds.bottom >= getHeight();
        } else if (matrix.rectStaysRect()) {
            final RectF boundsSrc = mTempSrc;
            final RectF boundsDst = mTempDst;
            boundsSrc.set(bounds);
            matrix.mapRect(boundsDst, boundsSrc);
            return boundsDst.left <= 0 && boundsDst.top <= 0 && boundsDst.right >= getWidth()
                    && boundsDst.bottom >= getHeight();
        } else {
            // If the matrix doesn't map to a rectangle, assume the worst.
            return false;
        }
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        // Only do this for new apps post-Nougat
        if (mDrawable != null && !sCompatDrawableVisibilityDispatch) {
            mDrawable.setVisible(isVisible, false);
        }
    }

    @RemotableViewMethod
    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        // Only do this for old apps pre-Nougat; new apps use onVisibilityAggregated
        if (mDrawable != null && sCompatDrawableVisibilityDispatch) {
            mDrawable.setVisible(visibility == VISIBLE, false);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Only do this for old apps pre-Nougat; new apps use onVisibilityAggregated
        if (mDrawable != null && sCompatDrawableVisibilityDispatch) {
            mDrawable.setVisible(getVisibility() == VISIBLE, false);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // Only do this for old apps pre-Nougat; new apps use onVisibilityAggregated
        if (mDrawable != null && sCompatDrawableVisibilityDispatch) {
            mDrawable.setVisible(false, false);
        }
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return ImageView.class.getName();
    }

    /** @hide */
    @Override
    protected void encodeProperties(@NonNull ViewHierarchyEncoder stream) {
        super.encodeProperties(stream);
        stream.addProperty("layout:baseline", getBaseline());
    }

    /** @hide */
    @Override
    @TestApi
    public boolean isDefaultFocusHighlightNeeded(Drawable background, Drawable foreground) {
        final boolean lackFocusState = mDrawable == null || !mDrawable.isStateful()
                || !mDrawable.hasFocusStateSpecified();
        return super.isDefaultFocusHighlightNeeded(background, foreground) && lackFocusState;
    }
}
