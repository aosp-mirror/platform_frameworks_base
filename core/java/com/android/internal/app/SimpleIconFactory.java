/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.internal.app;

import static android.content.Context.ACTIVITY_SERVICE;
import static android.graphics.Paint.DITHER_FLAG;
import static android.graphics.Paint.FILTER_BITMAP_FLAG;
import static android.graphics.drawable.AdaptiveIconDrawable.getExtraInsetFraction;

import android.annotation.AttrRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.BlurMaskFilter.Blur;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PaintFlagsDrawFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.os.UserHandle;
import android.util.AttributeSet;
import android.util.Pools.SynchronizedPool;
import android.util.TypedValue;

import com.android.internal.R;

import org.xmlpull.v1.XmlPullParser;

import java.nio.ByteBuffer;
import java.util.Optional;


/**
 * @deprecated Use the Launcher3 Iconloaderlib at packages/apps/Launcher3/iconloaderlib. This class
 * is a temporary fork of Iconloader. It combines all necessary methods to render app icons that are
 * possibly badged. It is intended to be used only by Sharesheet for the Q release with custom code.
 */
@Deprecated
public class SimpleIconFactory {


    private static final SynchronizedPool<SimpleIconFactory> sPool =
            new SynchronizedPool<>(Runtime.getRuntime().availableProcessors());

    private static final int DEFAULT_WRAPPER_BACKGROUND = Color.WHITE;
    private static final float BLUR_FACTOR = 1.5f / 48;

    private Context mContext;
    private Canvas mCanvas;
    private PackageManager mPm;

    private int mFillResIconDpi;
    private int mIconBitmapSize;
    private int mBadgeBitmapSize;
    private int mWrapperBackgroundColor;

    private Drawable mWrapperIcon;
    private final Rect mOldBounds = new Rect();

    /**
     * Obtain a SimpleIconFactory from a pool objects.
     *
     * @deprecated Do not use, functionality will be replaced by iconloader lib eventually.
     */
    @Deprecated
    public static SimpleIconFactory obtain(Context ctx) {
        SimpleIconFactory instance = sPool.acquire();
        if (instance == null) {
            final ActivityManager am = (ActivityManager) ctx.getSystemService(ACTIVITY_SERVICE);
            final int iconDpi = (am == null) ? 0 : am.getLauncherLargeIconDensity();

            final int iconSize = getIconSizeFromContext(ctx);
            final int badgeSize = getBadgeSizeFromContext(ctx);
            instance = new SimpleIconFactory(ctx, iconDpi, iconSize, badgeSize);
            instance.setWrapperBackgroundColor(Color.WHITE);
        }

        return instance;
    }

    private static int getAttrDimFromContext(Context ctx, @AttrRes int attrId, String errorMsg) {
        final Resources res = ctx.getResources();
        TypedValue outVal = new TypedValue();
        if (!ctx.getTheme().resolveAttribute(attrId, outVal, true)) {
            throw new IllegalStateException(errorMsg);
        }
        return res.getDimensionPixelSize(outVal.resourceId);
    }

    private static int getIconSizeFromContext(Context ctx) {
        return getAttrDimFromContext(ctx,
                com.android.internal.R.attr.iconfactoryIconSize,
                "Expected theme to define iconfactoryIconSize.");
    }

    private static int getBadgeSizeFromContext(Context ctx) {
        return getAttrDimFromContext(ctx,
                com.android.internal.R.attr.iconfactoryBadgeSize,
                "Expected theme to define iconfactoryBadgeSize.");
    }

    /**
     * Recycles the SimpleIconFactory so others may use it.
     *
     * @deprecated Do not use, functionality will be replaced by iconloader lib eventually.
     */
    @Deprecated
    public void recycle() {
        // Return to default background color
        setWrapperBackgroundColor(Color.WHITE);
        sPool.release(this);
    }

    /**
     * @deprecated Do not use, functionality will be replaced by iconloader lib eventually.
     */
    @Deprecated
    private SimpleIconFactory(Context context, int fillResIconDpi, int iconBitmapSize,
            int badgeBitmapSize) {
        mContext = context.getApplicationContext();
        mPm = mContext.getPackageManager();
        mIconBitmapSize = iconBitmapSize;
        mBadgeBitmapSize = badgeBitmapSize;
        mFillResIconDpi = fillResIconDpi;

        mCanvas = new Canvas();
        mCanvas.setDrawFilter(new PaintFlagsDrawFilter(DITHER_FLAG, FILTER_BITMAP_FLAG));

        // Normalizer init
        // Use twice the icon size as maximum size to avoid scaling down twice.
        mMaxSize = iconBitmapSize * 2;
        mBitmap = Bitmap.createBitmap(mMaxSize, mMaxSize, Bitmap.Config.ALPHA_8);
        mScaleCheckCanvas = new Canvas(mBitmap);
        mPixels = new byte[mMaxSize * mMaxSize];
        mLeftBorder = new float[mMaxSize];
        mRightBorder = new float[mMaxSize];
        mBounds = new Rect();
        mAdaptiveIconBounds = new Rect();
        mAdaptiveIconScale = SCALE_NOT_INITIALIZED;

        // Shadow generator init
        mDefaultBlurMaskFilter = new BlurMaskFilter(iconBitmapSize * BLUR_FACTOR,
                Blur.NORMAL);
    }

    /**
     * Sets the background color used for wrapped adaptive icon
     *
     * @deprecated Do not use, functionality will be replaced by iconloader lib eventually.
     */
    @Deprecated
    void setWrapperBackgroundColor(int color) {
        mWrapperBackgroundColor = (Color.alpha(color) < 255) ? DEFAULT_WRAPPER_BACKGROUND : color;
    }

    /**
     * Creates bitmap using the source drawable and various parameters.
     * The bitmap is visually normalized with other icons and has enough spacing to add shadow.
     * Note: this method has been modified from iconloaderlib to remove a profile diff check.
     *
     * @param icon                      source of the icon associated with a user that has no badge,
     *                                  likely user 0
     * @param user                      info can be used for a badge
     * @return a bitmap suitable for disaplaying as an icon at various system UIs.
     *
     * @deprecated Do not use, functionality will be replaced by iconloader lib eventually.
     */
    @Deprecated
    Bitmap createUserBadgedIconBitmap(@Nullable Drawable icon, @Nullable UserHandle user) {
        float [] scale = new float[1];

        // If no icon is provided use the system default
        if (icon == null) {
            icon = getFullResDefaultActivityIcon(mFillResIconDpi);
        }
        icon = normalizeAndWrapToAdaptiveIcon(icon, null, scale);
        Bitmap bitmap = createIconBitmap(icon, scale[0]);
        if (icon instanceof AdaptiveIconDrawable) {
            mCanvas.setBitmap(bitmap);
            recreateIcon(Bitmap.createBitmap(bitmap), mCanvas);
            mCanvas.setBitmap(null);
        }

        final Bitmap result;
        if (user != null /* if modification from iconloaderlib */) {
            BitmapDrawable drawable = new FixedSizeBitmapDrawable(bitmap);
            Drawable badged = mPm.getUserBadgedIcon(drawable, user);
            if (badged instanceof BitmapDrawable) {
                result = ((BitmapDrawable) badged).getBitmap();
            } else {
                result = createIconBitmap(badged, 1f);
            }
        } else {
            result = bitmap;
        }

        return result;
    }

    /**
     * Creates bitmap using the source drawable and flattened pre-rendered app icon.
     * The bitmap is visually normalized with other icons and has enough spacing to add shadow.
     * This is custom functionality added to Iconloaderlib that will need to be ported.
     *
     * @param icon                      source of the icon associated with a user that has no badge
     * @param renderedAppIcon           pre-rendered app icon to use as a badge, likely the output
     *                                  of createUserBadgedIconBitmap for user 0
     * @return a bitmap suitable for disaplaying as an icon at various system UIs.
     *
     * @deprecated Do not use, functionality will be replaced by iconloader lib eventually.
     */
    @Deprecated
    public Bitmap createAppBadgedIconBitmap(@Nullable Drawable icon, Bitmap renderedAppIcon) {
        // If no icon is provided use the system default
        if (icon == null) {
            icon = getFullResDefaultActivityIcon(mFillResIconDpi);
        }

        // Direct share icons cannot be adaptive, most will arrive as bitmaps. To get reliable
        // presentation, force all DS icons to be circular. Scale DS image so it completely fills.
        int w = icon.getIntrinsicWidth();
        int h = icon.getIntrinsicHeight();
        float scale = 1;
        if (h > w && w > 0) {
            scale = (float) h / w;
        } else if (w > h && h > 0) {
            scale = (float) w / h;
        }
        Bitmap bitmap = createIconBitmapNoInsetOrMask(icon, scale);
        bitmap = maskBitmapToCircle(bitmap);
        icon = new BitmapDrawable(mContext.getResources(), bitmap);

        // We now have a circular masked and scaled icon, inset and apply shadow
        scale = getScale(icon, null);
        bitmap = createIconBitmap(icon, scale);

        mCanvas.setBitmap(bitmap);
        recreateIcon(Bitmap.createBitmap(bitmap), mCanvas);

        if (renderedAppIcon != null) {
            // Now scale down and apply the badge to the bottom right corner of the flattened icon
            renderedAppIcon = Bitmap.createScaledBitmap(renderedAppIcon, mBadgeBitmapSize,
                    mBadgeBitmapSize, false);

            // Paint the provided badge on top of the flattened icon
            mCanvas.drawBitmap(renderedAppIcon, mIconBitmapSize - mBadgeBitmapSize,
                    mIconBitmapSize - mBadgeBitmapSize, null);
        }

        mCanvas.setBitmap(null);

        return bitmap;
    }

    private Bitmap maskBitmapToCircle(Bitmap bitmap) {
        final Bitmap output = Bitmap.createBitmap(bitmap.getWidth(),
                bitmap.getHeight(), Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(output);
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG
                | Paint.FILTER_BITMAP_FLAG);

        // Apply an offset to enable shadow to be drawn
        final int size = bitmap.getWidth();
        int offset = Math.max((int) Math.ceil(BLUR_FACTOR * size), 1);

        // Draw mask
        paint.setColor(0xffffffff);
        canvas.drawARGB(0, 0, 0, 0);
        canvas.drawCircle(bitmap.getWidth() / 2f,
                bitmap.getHeight() / 2f,
                bitmap.getWidth() / 2f - offset,
                paint);

        // Draw masked bitmap
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        final Rect rect = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
        canvas.drawBitmap(bitmap, rect, rect, paint);

        return output;
    }

    private static Drawable getFullResDefaultActivityIcon(int iconDpi) {
        return Resources.getSystem().getDrawableForDensity(android.R.mipmap.sym_def_app_icon,
                iconDpi);
    }

    private Bitmap createIconBitmap(Drawable icon, float scale) {
        return createIconBitmap(icon, scale, mIconBitmapSize, true, false);
    }

    private Bitmap createIconBitmapNoInsetOrMask(Drawable icon, float scale) {
        return createIconBitmap(icon, scale, mIconBitmapSize, false, true);
    }

    /**
     * @param icon drawable that should be flattened to a bitmap
     * @param scale the scale to apply before drawing {@param icon} on the canvas
     * @param insetAdiForShadow when rendering AdaptiveIconDrawables inset to make room for a shadow
     * @param ignoreAdiMask when rendering AdaptiveIconDrawables ignore the current system mask
     */
    private Bitmap createIconBitmap(Drawable icon, float scale, int size, boolean insetAdiForShadow,
            boolean ignoreAdiMask) {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);

        mCanvas.setBitmap(bitmap);
        mOldBounds.set(icon.getBounds());

        if (icon instanceof AdaptiveIconDrawable) {
            final AdaptiveIconDrawable adi = (AdaptiveIconDrawable) icon;

            // By default assumes the output bitmap will have a shadow directly applied and makes
            // room for it by insetting. If there are intermediate steps before applying the shadow
            // insetting is disableable.
            int offset = Math.round(size * (1 - scale) / 2);
            if (insetAdiForShadow) {
                offset = Math.max((int) Math.ceil(BLUR_FACTOR * size), offset);
            }
            Rect bounds = new Rect(offset, offset, size - offset, size - offset);

            // AdaptiveIconDrawables are by default masked by the user's icon shape selection.
            // If further masking is to be done, directly render to avoid the system masking.
            if (ignoreAdiMask) {
                final int cX = bounds.width() / 2;
                final int cY = bounds.height() / 2;
                final float portScale = 1f / (1 + 2 * getExtraInsetFraction());
                final int insetWidth = (int) (bounds.width() / (portScale * 2));
                final int insetHeight = (int) (bounds.height() / (portScale * 2));

                Rect childRect = new Rect(cX - insetWidth, cY - insetHeight, cX + insetWidth,
                        cY + insetHeight);
                Optional.ofNullable(adi.getBackground()).ifPresent(drawable -> {
                    drawable.setBounds(childRect);
                    drawable.draw(mCanvas);
                });
                Optional.ofNullable(adi.getForeground()).ifPresent(drawable -> {
                    drawable.setBounds(childRect);
                    drawable.draw(mCanvas);
                });
            } else {
                adi.setBounds(bounds);
                adi.draw(mCanvas);
            }
        } else {
            if (icon instanceof BitmapDrawable) {
                BitmapDrawable bitmapDrawable = (BitmapDrawable) icon;
                Bitmap b = bitmapDrawable.getBitmap();
                if (bitmap != null && b.getDensity() == Bitmap.DENSITY_NONE) {
                    bitmapDrawable.setTargetDensity(mContext.getResources().getDisplayMetrics());
                }
            }
            int width = size;
            int height = size;

            int intrinsicWidth = icon.getIntrinsicWidth();
            int intrinsicHeight = icon.getIntrinsicHeight();
            if (intrinsicWidth > 0 && intrinsicHeight > 0) {
                // Scale the icon proportionally to the icon dimensions
                final float ratio = (float) intrinsicWidth / intrinsicHeight;
                if (intrinsicWidth > intrinsicHeight) {
                    height = (int) (width / ratio);
                } else if (intrinsicHeight > intrinsicWidth) {
                    width = (int) (height * ratio);
                }
            }
            final int left = (size - width) / 2;
            final int top = (size - height) / 2;
            icon.setBounds(left, top, left + width, top + height);
            mCanvas.save();
            mCanvas.scale(scale, scale, size / 2, size / 2);
            icon.draw(mCanvas);
            mCanvas.restore();

        }

        icon.setBounds(mOldBounds);
        mCanvas.setBitmap(null);
        return bitmap;
    }

    private Drawable normalizeAndWrapToAdaptiveIcon(Drawable icon, RectF outIconBounds,
            float[] outScale) {
        float scale = 1f;

        if (mWrapperIcon == null) {
            mWrapperIcon = mContext.getDrawable(
                    R.drawable.iconfactory_adaptive_icon_drawable_wrapper).mutate();
        }

        AdaptiveIconDrawable dr = (AdaptiveIconDrawable) mWrapperIcon;
        dr.setBounds(0, 0, 1, 1);
        scale = getScale(icon, outIconBounds);
        if (!(icon instanceof AdaptiveIconDrawable)) {
            FixedScaleDrawable fsd = ((FixedScaleDrawable) dr.getForeground());
            fsd.setDrawable(icon);
            fsd.setScale(scale);
            icon = dr;
            scale = getScale(icon, outIconBounds);

            ((ColorDrawable) dr.getBackground()).setColor(mWrapperBackgroundColor);
        }

        outScale[0] = scale;
        return icon;
    }


    /* Normalization block */

    private static final float SCALE_NOT_INITIALIZED = 0;
    // Ratio of icon visible area to full icon size for a square shaped icon
    private static final float MAX_SQUARE_AREA_FACTOR = 375.0f / 576;
    // Ratio of icon visible area to full icon size for a circular shaped icon
    private static final float MAX_CIRCLE_AREA_FACTOR = 380.0f / 576;

    private static final float CIRCLE_AREA_BY_RECT = (float) Math.PI / 4;

    // Slope used to calculate icon visible area to full icon size for any generic shaped icon.
    private static final float LINEAR_SCALE_SLOPE =
            (MAX_CIRCLE_AREA_FACTOR - MAX_SQUARE_AREA_FACTOR) / (1 - CIRCLE_AREA_BY_RECT);

    private static final int MIN_VISIBLE_ALPHA = 40;

    private float mAdaptiveIconScale;
    private final Rect mAdaptiveIconBounds;
    private final Rect mBounds;
    private final int mMaxSize;
    private final byte[] mPixels;
    private final float[] mLeftBorder;
    private final float[] mRightBorder;
    private final Bitmap mBitmap;
    private final Canvas mScaleCheckCanvas;

    /**
     * Returns the amount by which the {@param d} should be scaled (in both dimensions) so that it
     * matches the design guidelines for a launcher icon.
     *
     * We first calculate the convex hull of the visible portion of the icon.
     * This hull then compared with the bounding rectangle of the hull to find how closely it
     * resembles a circle and a square, by comparing the ratio of the areas. Note that this is not
     * an ideal solution but it gives satisfactory result without affecting the performance.
     *
     * This closeness is used to determine the ratio of hull area to the full icon size.
     * Refer {@link #MAX_CIRCLE_AREA_FACTOR} and {@link #MAX_SQUARE_AREA_FACTOR}
     *
     * @param outBounds optional rect to receive the fraction distance from each edge.
     */
    private synchronized float getScale(@NonNull Drawable d, @Nullable RectF outBounds) {
        if (d instanceof AdaptiveIconDrawable) {
            if (mAdaptiveIconScale != SCALE_NOT_INITIALIZED) {
                if (outBounds != null) {
                    outBounds.set(mAdaptiveIconBounds);
                }
                return mAdaptiveIconScale;
            }
        }
        int width = d.getIntrinsicWidth();
        int height = d.getIntrinsicHeight();
        if (width <= 0 || height <= 0) {
            width = width <= 0 || width > mMaxSize ? mMaxSize : width;
            height = height <= 0 || height > mMaxSize ? mMaxSize : height;
        } else if (width > mMaxSize || height > mMaxSize) {
            int max = Math.max(width, height);
            width = mMaxSize * width / max;
            height = mMaxSize * height / max;
        }

        mBitmap.eraseColor(Color.TRANSPARENT);
        d.setBounds(0, 0, width, height);
        d.draw(mScaleCheckCanvas);

        ByteBuffer buffer = ByteBuffer.wrap(mPixels);
        buffer.rewind();
        mBitmap.copyPixelsToBuffer(buffer);

        // Overall bounds of the visible icon.
        int topY = -1;
        int bottomY = -1;
        int leftX = mMaxSize + 1;
        int rightX = -1;

        // Create border by going through all pixels one row at a time and for each row find
        // the first and the last non-transparent pixel. Set those values to mLeftBorder and
        // mRightBorder and use -1 if there are no visible pixel in the row.

        // buffer position
        int index = 0;
        // buffer shift after every row, width of buffer = mMaxSize
        int rowSizeDiff = mMaxSize - width;
        // first and last position for any row.
        int firstX, lastX;

        for (int y = 0; y < height; y++) {
            firstX = lastX = -1;
            for (int x = 0; x < width; x++) {
                if ((mPixels[index] & 0xFF) > MIN_VISIBLE_ALPHA) {
                    if (firstX == -1) {
                        firstX = x;
                    }
                    lastX = x;
                }
                index++;
            }
            index += rowSizeDiff;

            mLeftBorder[y] = firstX;
            mRightBorder[y] = lastX;

            // If there is at least one visible pixel, update the overall bounds.
            if (firstX != -1) {
                bottomY = y;
                if (topY == -1) {
                    topY = y;
                }

                leftX = Math.min(leftX, firstX);
                rightX = Math.max(rightX, lastX);
            }
        }

        if (topY == -1 || rightX == -1) {
            // No valid pixels found. Do not scale.
            return 1;
        }

        convertToConvexArray(mLeftBorder, 1, topY, bottomY);
        convertToConvexArray(mRightBorder, -1, topY, bottomY);

        // Area of the convex hull
        float area = 0;
        for (int y = 0; y < height; y++) {
            if (mLeftBorder[y] <= -1) {
                continue;
            }
            area += mRightBorder[y] - mLeftBorder[y] + 1;
        }

        // Area of the rectangle required to fit the convex hull
        float rectArea = (bottomY + 1 - topY) * (rightX + 1 - leftX);
        float hullByRect = area / rectArea;

        float scaleRequired;
        if (hullByRect < CIRCLE_AREA_BY_RECT) {
            scaleRequired = MAX_CIRCLE_AREA_FACTOR;
        } else {
            scaleRequired = MAX_SQUARE_AREA_FACTOR + LINEAR_SCALE_SLOPE * (1 - hullByRect);
        }
        mBounds.left = leftX;
        mBounds.right = rightX;

        mBounds.top = topY;
        mBounds.bottom = bottomY;

        if (outBounds != null) {
            outBounds.set(((float) mBounds.left) / width, ((float) mBounds.top) / height,
                    1 - ((float) mBounds.right) / width,
                    1 - ((float) mBounds.bottom) / height);
        }
        float areaScale = area / (width * height);
        // Use sqrt of the final ratio as the images is scaled across both width and height.
        float scale = areaScale > scaleRequired ? (float) Math.sqrt(scaleRequired / areaScale) : 1;
        if (d instanceof AdaptiveIconDrawable && mAdaptiveIconScale == SCALE_NOT_INITIALIZED) {
            mAdaptiveIconScale = scale;
            mAdaptiveIconBounds.set(mBounds);
        }
        return scale;
    }

    /**
     * Modifies {@param xCoordinates} to represent a convex border. Fills in all missing values
     * (except on either ends) with appropriate values.
     * @param xCoordinates map of x coordinate per y.
     * @param direction 1 for left border and -1 for right border.
     * @param topY the first Y position (inclusive) with a valid value.
     * @param bottomY the last Y position (inclusive) with a valid value.
     */
    private static void convertToConvexArray(
            float[] xCoordinates, int direction, int topY, int bottomY) {
        int total = xCoordinates.length;
        // The tangent at each pixel.
        float[] angles = new float[total - 1];

        int first = topY; // First valid y coordinate
        int last = -1;    // Last valid y coordinate which didn't have a missing value

        float lastAngle = Float.MAX_VALUE;

        for (int i = topY + 1; i <= bottomY; i++) {
            if (xCoordinates[i] <= -1) {
                continue;
            }
            int start;

            if (lastAngle == Float.MAX_VALUE) {
                start = first;
            } else {
                float currentAngle = (xCoordinates[i] - xCoordinates[last]) / (i - last);
                start = last;
                // If this position creates a concave angle, keep moving up until we find a
                // position which creates a convex angle.
                if ((currentAngle - lastAngle) * direction < 0) {
                    while (start > first) {
                        start--;
                        currentAngle = (xCoordinates[i] - xCoordinates[start]) / (i - start);
                        if ((currentAngle - angles[start]) * direction >= 0) {
                            break;
                        }
                    }
                }
            }

            // Reset from last check
            lastAngle = (xCoordinates[i] - xCoordinates[start]) / (i - start);
            // Update all the points from start.
            for (int j = start; j < i; j++) {
                angles[j] = lastAngle;
                xCoordinates[j] = xCoordinates[start] + lastAngle * (j - start);
            }
            last = i;
        }
    }

    /* Shadow generator block */

    private static final float KEY_SHADOW_DISTANCE = 1f / 48;
    private static final int KEY_SHADOW_ALPHA = 10;
    private static final int AMBIENT_SHADOW_ALPHA = 7;

    private Paint mBlurPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private Paint mDrawPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private BlurMaskFilter mDefaultBlurMaskFilter;

    private synchronized void recreateIcon(Bitmap icon, Canvas out) {
        recreateIcon(icon, mDefaultBlurMaskFilter, AMBIENT_SHADOW_ALPHA, KEY_SHADOW_ALPHA, out);
    }

    private synchronized void recreateIcon(Bitmap icon, BlurMaskFilter blurMaskFilter,
            int ambientAlpha, int keyAlpha, Canvas out) {
        int[] offset = new int[2];
        mBlurPaint.setMaskFilter(blurMaskFilter);
        Bitmap shadow = icon.extractAlpha(mBlurPaint, offset);

        // Draw ambient shadow
        mDrawPaint.setAlpha(ambientAlpha);
        out.drawBitmap(shadow, offset[0], offset[1], mDrawPaint);

        // Draw key shadow
        mDrawPaint.setAlpha(keyAlpha);
        out.drawBitmap(shadow, offset[0], offset[1] + KEY_SHADOW_DISTANCE * mIconBitmapSize,
                mDrawPaint);

        // Draw the icon
        mDrawPaint.setAlpha(255); // TODO if b/128609682 not fixed by launch use .setAlpha(254)
        out.drawBitmap(icon, 0, 0, mDrawPaint);
    }

    /* Classes */

    /**
     * Extension of {@link DrawableWrapper} which scales the child drawables by a fixed amount.
     */
    public static class FixedScaleDrawable extends DrawableWrapper {

        private static final float LEGACY_ICON_SCALE = .7f * .6667f;
        private float mScaleX, mScaleY;

        public FixedScaleDrawable() {
            super(new ColorDrawable());
            mScaleX = LEGACY_ICON_SCALE;
            mScaleY = LEGACY_ICON_SCALE;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            int saveCount = canvas.save();
            canvas.scale(mScaleX, mScaleY,
                    getBounds().exactCenterX(), getBounds().exactCenterY());
            super.draw(canvas);
            canvas.restoreToCount(saveCount);
        }

        @Override
        public void inflate(Resources r, XmlPullParser parser, AttributeSet attrs) { }

        @Override
        public void inflate(Resources r, XmlPullParser parser, AttributeSet attrs, Theme theme) { }

        /**
         * Sets the scale associated with this drawable
         * @param scale
         */
        public void setScale(float scale) {
            float h = getIntrinsicHeight();
            float w = getIntrinsicWidth();
            mScaleX = scale * LEGACY_ICON_SCALE;
            mScaleY = scale * LEGACY_ICON_SCALE;
            if (h > w && w > 0) {
                mScaleX *= w / h;
            } else if (w > h && h > 0) {
                mScaleY *= h / w;
            }
        }
    }

    /**
     * An extension of {@link BitmapDrawable} which returns the bitmap pixel size as intrinsic size.
     * This allows the badging to be done based on the action bitmap size rather than
     * the scaled bitmap size.
     */
    private static class FixedSizeBitmapDrawable extends BitmapDrawable {

        FixedSizeBitmapDrawable(Bitmap bitmap) {
            super(null, bitmap);
        }

        @Override
        public int getIntrinsicHeight() {
            return getBitmap().getWidth();
        }

        @Override
        public int getIntrinsicWidth() {
            return getBitmap().getWidth();
        }
    }

}
