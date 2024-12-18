/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.systemui.statusbar;

import static com.android.systemui.plugins.DarkIconDispatcher.getTint;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.IntDef;
import android.app.ActivityManager;
import android.app.Notification;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Trace;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.FloatProperty;
import android.util.Log;
import android.util.Property;
import android.util.TypedValue;
import android.view.ViewDebug;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.Interpolator;

import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import com.android.app.animation.Interpolators;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.statusbar.StatusBarIcon.Shape;
import com.android.internal.util.ContrastColorUtil;
import com.android.systemui.Flags;
import com.android.systemui.modes.shared.ModesUiIcons;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.notification.NotificationContentDescription;
import com.android.systemui.statusbar.notification.NotificationDozeHelper;
import com.android.systemui.statusbar.notification.NotificationUtils;
import com.android.systemui.util.drawable.DrawableSize;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;

public class StatusBarIconView extends AnimatedImageView implements StatusIconDisplayable {
    public static final int NO_COLOR = 0;

    /**
     * Multiply alpha values with (1+DARK_ALPHA_BOOST) when dozing. The chosen value boosts
     * everything above 30% to 50%, making it appear on 1bit color depths.
     */
    private static final float DARK_ALPHA_BOOST = 0.67f;
    /**
     * Status icons are currently drawn with the intention of being 17dp tall, but we
     * want to scale them (in a way that doesn't require an asset dump) down 2dp. So
     * 17dp * (15 / 17) = 15dp, the new height. After the first call to {@link #reloadDimens} all
     * values will be in px.
     */
    private float mSystemIconDesiredHeight = 15f;
    private float mSystemIconIntrinsicHeight = 17f;
    private float mSystemIconDefaultScale = mSystemIconDesiredHeight / mSystemIconIntrinsicHeight;
    private final int ANIMATION_DURATION_FAST = 100;

    public static final int STATE_ICON = 0;
    public static final int STATE_DOT = 1;
    public static final int STATE_HIDDEN = 2;

    public static final float APP_ICON_SCALE = .75f;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({STATE_ICON, STATE_DOT, STATE_HIDDEN})
    public @interface VisibleState { }

    /** Returns a human-readable string of {@link VisibleState}. */
    public static String getVisibleStateString(@VisibleState int state) {
        switch(state) {
            case STATE_ICON: return "ICON";
            case STATE_DOT: return "DOT";
            case STATE_HIDDEN: return "HIDDEN";
            default: return "UNKNOWN";
        }
    }

    private static final String TAG = "StatusBarIconView";
    private static final Property<StatusBarIconView, Float> ICON_APPEAR_AMOUNT
            = new FloatProperty<StatusBarIconView>("iconAppearAmount") {

        @Override
        public void setValue(StatusBarIconView object, float value) {
            object.setIconAppearAmount(value);
        }

        @Override
        public Float get(StatusBarIconView object) {
            return object.getIconAppearAmount();
        }
    };
    private static final Property<StatusBarIconView, Float> DOT_APPEAR_AMOUNT
            = new FloatProperty<StatusBarIconView>("dot_appear_amount") {

        @Override
        public void setValue(StatusBarIconView object, float value) {
            object.setDotAppearAmount(value);
        }

        @Override
        public Float get(StatusBarIconView object) {
            return object.getDotAppearAmount();
        }
    };

    private int mStatusBarIconDrawingSizeIncreased = 1;
    @VisibleForTesting int mStatusBarIconDrawingSize = 1;

    @VisibleForTesting int mOriginalStatusBarIconSize = 1;
    @VisibleForTesting int mNewStatusBarIconSize = 1;
    @VisibleForTesting float mScaleToFitNewIconSize = 1;
    private StatusBarIcon mIcon;
    @ViewDebug.ExportedProperty private String mSlot;
    private StatusBarNotification mNotification;
    private final boolean mBlocked;
    private Configuration mConfiguration;
    private boolean mNightMode;
    private float mIconScale = 1.0f;
    private final Paint mDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float mDotRadius;
    private int mStaticDotRadius;
    @StatusBarIconView.VisibleState
    private int mVisibleState = STATE_ICON;
    private float mIconAppearAmount = 1.0f;
    private ObjectAnimator mIconAppearAnimator;
    private ObjectAnimator mDotAnimator;
    private float mDotAppearAmount;
    private int mDrawableColor;
    private int mIconColor;
    private int mDecorColor;
    private ValueAnimator mColorAnimator;
    private int mCurrentSetColor = NO_COLOR;
    private int mAnimationStartColor = NO_COLOR;
    private final ValueAnimator.AnimatorUpdateListener mColorUpdater
            = animation -> {
        int newColor = NotificationUtils.interpolateColors(mAnimationStartColor, mIconColor,
                animation.getAnimatedFraction());
        setColorInternal(newColor);
    };
    private int mContrastedDrawableColor;
    private int mCachedContrastBackgroundColor = NO_COLOR;
    private float[] mMatrix;
    private ColorMatrixColorFilter mMatrixColorFilter;
    private Runnable mLayoutRunnable;
    private boolean mDismissed;
    private Runnable mOnDismissListener;
    private boolean mIncreasedSize;
    private boolean mShowsConversation;
    private float mDozeAmount;
    private final NotificationDozeHelper mDozer;

    public StatusBarIconView(Context context, String slot, StatusBarNotification sbn) {
        this(context, slot, sbn, false);
    }

    public StatusBarIconView(Context context, String slot, StatusBarNotification sbn,
            boolean blocked) {
        super(context);
        mDozer = new NotificationDozeHelper();
        mBlocked = blocked;
        mSlot = slot;
        setNotification(sbn);
        setScaleType(ScaleType.CENTER);
        mConfiguration = new Configuration(context.getResources().getConfiguration());
        mNightMode = (mConfiguration.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        initializeDecorColor();
        reloadDimens();
        maybeUpdateIconScaleDimens();

        if (Flags.statusBarMonochromeIconsFix()) {
            setCropToPadding(true);
        }
    }

    /** Should always be preceded by {@link #reloadDimens()} */
    @VisibleForTesting
    public void maybeUpdateIconScaleDimens() {
        // We scale notification icons (on the left) plus icons on the right that explicitly
        // want FIXED_SPACE.
        boolean useNonSystemIconScaling = isNotification()
                || (ModesUiIcons.isEnabled() && mIcon != null && mIcon.shape == Shape.FIXED_SPACE);

        if (useNonSystemIconScaling) {
            updateIconScaleForNonSystemIcons();
        } else {
            updateIconScaleForSystemIcons();
        }
    }

    private void updateIconScaleForNonSystemIcons() {
        float iconScale;
        // we need to scale the image size to be same as the original size
        // (fit mOriginalStatusBarIconSize), then we can scale it with mScaleToFitNewIconSize
        // to fit mNewStatusBarIconSize
        float scaleToOriginalDrawingSize = 1.0f;
        ViewGroup.LayoutParams lp = getLayoutParams();
        if (getDrawable() != null && (lp != null && lp.width > 0 && lp.height > 0)) {
            final int iconViewWidth = lp.width;
            final int iconViewHeight = lp.height;
            // first we estimate the image exact size when put the drawable in scaled iconView size,
            // then we can compute the scaleToOriginalDrawingSize to make the image size fit in
            // mOriginalStatusBarIconSize
            final int drawableWidth = getDrawable().getIntrinsicWidth();
            final int drawableHeight = getDrawable().getIntrinsicHeight();
            float scaleToFitIconView = Math.min(
                    (float) iconViewWidth / drawableWidth,
                    (float) iconViewHeight / drawableHeight);
            // if the drawable size <= the icon view size, the drawable won't be scaled
            if (scaleToFitIconView > 1.0f) {
                scaleToFitIconView = 1.0f;
            }
            final float scaledImageWidth = drawableWidth * scaleToFitIconView;
            final float scaledImageHeight = drawableHeight * scaleToFitIconView;
            scaleToOriginalDrawingSize = Math.min(
                    (float) mOriginalStatusBarIconSize / scaledImageWidth,
                    (float) mOriginalStatusBarIconSize / scaledImageHeight);
            if (scaleToOriginalDrawingSize > 1.0f) {
                // per b/296026932, if the scaled image size <= mOriginalStatusBarIconSize, we need
                // to scale up the scaled image to fit in mOriginalStatusBarIconSize. But if both
                // the raw drawable intrinsic width/height are less than mOriginalStatusBarIconSize,
                // then we just scale up the scaled image back to the raw drawable size.
                scaleToOriginalDrawingSize = Math.min(
                        scaleToOriginalDrawingSize, 1f / scaleToFitIconView);
            }
        }
        iconScale = scaleToOriginalDrawingSize;

        final float imageBounds = mIncreasedSize ?
                mStatusBarIconDrawingSizeIncreased : mStatusBarIconDrawingSize;
        final int originalOuterBounds = mOriginalStatusBarIconSize;
        iconScale = iconScale * (imageBounds / (float) originalOuterBounds);

        // scale image to fit new icon size
        mIconScale = iconScale * mScaleToFitNewIconSize;

        updatePivot();
    }

    // Makes sure that all icons are scaled to the same height (15dp). If we cannot get a height
    // for the icon, it uses the default SCALE (15f / 17f) which is the old behavior
    private void updateIconScaleForSystemIcons() {
        float iconScale;
        float iconHeight = getIconHeight();
        if (iconHeight != 0) {
            iconScale = mSystemIconDesiredHeight / iconHeight;
        } else {
            iconScale = mSystemIconDefaultScale;
        }

        // scale image to fit new icon size
        mIconScale = iconScale * mScaleToFitNewIconSize;
    }

    private float getIconHeight() {
        Drawable d = getDrawable();
        if (d != null) {
            return (float) getDrawable().getIntrinsicHeight();
        } else {
            return mSystemIconIntrinsicHeight;
        }
    }

    public float getIconScaleIncreased() {
        return (float) mStatusBarIconDrawingSizeIncreased / mStatusBarIconDrawingSize;
    }

    public float getIconScale() {
        return mIconScale;
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        final int configDiff = newConfig.diff(mConfiguration);
        mConfiguration.setTo(newConfig);
        if ((configDiff & (ActivityInfo.CONFIG_DENSITY | ActivityInfo.CONFIG_FONT_SCALE)) != 0) {
            updateIconDimens();
        }
        boolean nightMode = (newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        if (nightMode != mNightMode) {
            mNightMode = nightMode;
            initializeDecorColor();
        }
    }

    /**
     * Update the icon dimens and drawable with current resources
     */
    public void updateIconDimens() {
        Trace.beginSection("StatusBarIconView#updateIconDimens");
        try {
            reloadDimens();
            updateDrawable();
            maybeUpdateIconScaleDimens();
        } finally {
            Trace.endSection();
        }
    }

    private void reloadDimens() {
        boolean applyRadius = mDotRadius == mStaticDotRadius;
        Resources res = getResources();
        mStaticDotRadius = res.getDimensionPixelSize(R.dimen.overflow_dot_radius);
        mOriginalStatusBarIconSize = res.getDimensionPixelSize(R.dimen.status_bar_icon_size);
        mNewStatusBarIconSize = res.getDimensionPixelSize(R.dimen.status_bar_icon_size_sp);
        mScaleToFitNewIconSize = (float) mNewStatusBarIconSize / mOriginalStatusBarIconSize;
        mStatusBarIconDrawingSizeIncreased =
                res.getDimensionPixelSize(R.dimen.status_bar_icon_drawing_size_dark);
        mStatusBarIconDrawingSize =
                res.getDimensionPixelSize(R.dimen.status_bar_icon_drawing_size);
        if (applyRadius) {
            mDotRadius = mStaticDotRadius;
        }
        mSystemIconDesiredHeight = res.getDimension(
                com.android.internal.R.dimen.status_bar_system_icon_size);
        mSystemIconIntrinsicHeight = res.getDimension(
                com.android.internal.R.dimen.status_bar_system_icon_intrinsic_size);
        mSystemIconDefaultScale = mSystemIconDesiredHeight / mSystemIconIntrinsicHeight;
    }

    public void setNotification(StatusBarNotification notification) {
        CharSequence contentDescription = null;
        if (notification != null) {
            contentDescription = NotificationContentDescription
                    .contentDescForNotification(mContext, notification.getNotification());
        }
        setNotification(notification, contentDescription);
    }

    /**
     * Sets the notification with a pre-set content description.
     */
    public void setNotification(@Nullable StatusBarNotification notification,
            @Nullable CharSequence notificationContentDescription) {
        mNotification = notification;
        if (!TextUtils.isEmpty(notificationContentDescription)) {
            setContentDescription(notificationContentDescription);
        }
        maybeUpdateIconScaleDimens();
    }

    private boolean isNotification() {
        return mNotification != null;
    }

    public boolean equalIcons(Icon a, Icon b) {
        if (a == b) return true;
        if (a.getType() != b.getType()) return false;
        switch (a.getType()) {
            case Icon.TYPE_RESOURCE:
                return a.getResPackage().equals(b.getResPackage()) && a.getResId() == b.getResId();
            case Icon.TYPE_URI:
            case Icon.TYPE_URI_ADAPTIVE_BITMAP:
                return a.getUriString().equals(b.getUriString());
            default:
                return false;
        }
    }
    /**
     * Returns whether the set succeeded.
     */
    public boolean set(StatusBarIcon icon) {
        final boolean iconEquals = mIcon != null && equalIcons(mIcon.icon, icon.icon);
        final boolean levelEquals = iconEquals
                && mIcon.iconLevel == icon.iconLevel;
        final boolean visibilityEquals = mIcon != null
                && mIcon.visible == icon.visible;
        mIcon = icon.clone();
        setContentDescription(icon.contentDescription);
        if (!iconEquals) {
            if (!updateDrawable(false /* no clear */)) return false;
            // we have to clear the grayscale tag since it may have changed
            setTag(R.id.icon_is_grayscale, null);
            // Maybe set scale based on icon height
            maybeUpdateIconScaleDimens();
        }
        if (!levelEquals) {
            setImageLevel(icon.iconLevel);
        }
        if (ModesUiIcons.isEnabled() && icon.shape == Shape.FIXED_SPACE) {
            setScaleType(ScaleType.FIT_CENTER);
        }
        if (!visibilityEquals) {
            setVisibility(icon.visible && !mBlocked ? VISIBLE : GONE);
        }
        return true;
    }

    public void updateDrawable() {
        updateDrawable(true /* with clear */);
    }

    private boolean updateDrawable(boolean withClear) {
        if (mIcon == null) {
            return false;
        }
        Drawable drawable;
        try {
            Trace.beginSection("StatusBarIconView#updateDrawable()");
            drawable = getIcon(mIcon);
        } catch (OutOfMemoryError e) {
            Log.w(TAG, "OOM while inflating " + mIcon.icon + " for slot " + mSlot);
            return false;
        } finally {
            Trace.endSection();
        }

        if (drawable == null) {
            Log.w(TAG, "No icon for slot " + mSlot + "; " + mIcon.icon);
            return false;
        }

        if (withClear) {
            setImageDrawable(null);
        }
        setImageDrawable(drawable);
        return true;
    }

    public Icon getSourceIcon() {
        return mIcon.icon;
    }

    Drawable getIcon(StatusBarIcon icon) {
        Context notifContext = getContext();
        if (isNotification()) {
            notifContext = mNotification.getPackageContext(getContext());
        }
        return getIcon(getContext(), notifContext != null ? notifContext : getContext(), icon);
    }

    /**
     * Returns the right icon to use for this item
     *
     * @param sysuiContext Context to use to get scale factor
     * @param context Context to use to get resources of notification icon
     * @return Drawable for this item, or null if the package or item could not
     *         be found
     */
    private Drawable getIcon(Context sysuiContext,
            Context context, StatusBarIcon statusBarIcon) {
        Drawable icon = loadDrawable(context, statusBarIcon);

        TypedValue typedValue = new TypedValue();
        sysuiContext.getResources().getValue(R.dimen.status_bar_icon_scale_factor,
                typedValue, true);
        float scaleFactor = typedValue.getFloat();

        if (icon != null) {
            // We downscale the loaded drawable to reasonable size to protect against applications
            // using too much memory. The size can be tweaked in config.xml. Drawables that are
            // already sized properly won't be touched.
            boolean isLowRamDevice = ActivityManager.isLowRamDeviceStatic();
            Resources res = sysuiContext.getResources();
            int maxIconSize = res.getDimensionPixelSize(isLowRamDevice
                    ? com.android.internal.R.dimen.notification_small_icon_size_low_ram
                    : com.android.internal.R.dimen.notification_small_icon_size);
            icon = DrawableSize.downscaleToSize(res, icon, maxIconSize, maxIconSize);
        }

        // No need to scale the icon, so return it as is.
        if (scaleFactor == 1.f) {
            return icon;
        }

        return new ScalingDrawableWrapper(icon, scaleFactor);
    }

    @Nullable
    private Drawable loadDrawable(Context context, StatusBarIcon statusBarIcon) {
        if (ModesUiIcons.isEnabled() && statusBarIcon.preloadedIcon != null) {
            Drawable.ConstantState cached = statusBarIcon.preloadedIcon.getConstantState();
            if (cached != null) {
                return cached.newDrawable(mContext.getResources()).mutate();
            } else {
                return statusBarIcon.preloadedIcon.mutate();
            }
        } else {
            int userId = statusBarIcon.user.getIdentifier();
            if (userId == UserHandle.USER_ALL) {
                userId = UserHandle.USER_SYSTEM;
            }

            // Try to load the monochrome app icon if applicable
            Drawable icon = maybeGetMonochromeAppIcon(context, statusBarIcon);
            // Otherwise, just use the icon normally
            if (icon == null) {
                icon = statusBarIcon.icon.loadDrawableAsUser(context, userId);
            }
            return icon;
        }
    }

    @Nullable
    private Drawable maybeGetMonochromeAppIcon(Context context,
            StatusBarIcon statusBarIcon) {
        if (android.app.Flags.notificationsUseMonochromeAppIcon()
                && statusBarIcon.type == StatusBarIcon.Type.MaybeMonochromeAppIcon) {
            // Check if we have a monochrome app icon
            PackageManager pm = context.getPackageManager();
            Drawable appIcon = context.getApplicationInfo().loadIcon(pm);
            if (appIcon instanceof AdaptiveIconDrawable) {
                Drawable monochrome = ((AdaptiveIconDrawable) appIcon).getMonochrome();
                if (monochrome != null) {
                    setCropToPadding(true);
                    setScaleType(ScaleType.CENTER);
                    return new ScalingDrawableWrapper(monochrome, APP_ICON_SCALE);
                }
            }
        }
        return null;
    }

    public StatusBarIcon getStatusBarIcon() {
        return mIcon;
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        if (isNotification()) {
            event.setParcelableData(mNotification.getNotification());
        }
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        super.onRtlPropertiesChanged(layoutDirection);
        updateDrawable();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        if (!isNotification()) {
            // for system icons, calculated measured width from super is for image drawable real
            // width (17dp). We may scale the image with font scale, so we also need to scale the
            // measured width so that scaled measured width and image width would be fit.
            int measuredWidth = getMeasuredWidth();
            int measuredHeight = getMeasuredHeight();
            setMeasuredDimension((int) (measuredWidth * mScaleToFitNewIconSize), measuredHeight);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // In this method, for width/height division computation we intend to discard the
        // fractional part as the original behavior.
        if (mIconAppearAmount > 0.0f) {
            canvas.save();
            int px = getWidth() / 2;
            int py = getHeight() / 2;
            canvas.scale(mIconScale * mIconAppearAmount, mIconScale * mIconAppearAmount,
                    (float) px, (float) py);
            super.onDraw(canvas);
            canvas.restore();
        }

        if (mDotAppearAmount != 0.0f) {
            float radius;
            float alpha = Color.alpha(mDecorColor) / 255.f;
            if (mDotAppearAmount <= 1.0f) {
                radius = mDotRadius * mDotAppearAmount;
            } else {
                float fadeOutAmount = mDotAppearAmount - 1.0f;
                alpha = alpha * (1.0f - fadeOutAmount);
                int end = getWidth() / 4;
                radius = NotificationUtils.interpolate(mDotRadius, (float) end, fadeOutAmount);
            }
            mDotPaint.setAlpha((int) (alpha * 255));
            int cx = mNewStatusBarIconSize / 2;
            int cy = getHeight() / 2;
            canvas.drawCircle(
                    (float) cx, (float) cy,
                    radius, mDotPaint);
        }
    }

    @Override
    protected void debug(int depth) {
        super.debug(depth);
        Log.d("View", debugIndent(depth) + "slot=" + mSlot);
        Log.d("View", debugIndent(depth) + "icon=" + mIcon);
    }

    @Override
    public String toString() {
        return "StatusBarIconView("
                + "slot='" + mSlot + "' alpha=" + getAlpha() + " icon=" + mIcon
                + " visibleState=" + getVisibleStateString(getVisibleState())
                + " iconColor=#" + Integer.toHexString(mIconColor)
                + " staticDrawableColor=#" + Integer.toHexString(mDrawableColor)
                + " decorColor=#" + Integer.toHexString(mDecorColor)
                + " animationStartColor=#" + Integer.toHexString(mAnimationStartColor)
                + " currentSetColor=#" + Integer.toHexString(mCurrentSetColor)
                + " notification=" + mNotification + ')';
    }

    public StatusBarNotification getNotification() {
        return mNotification;
    }

    public String getSlot() {
        return mSlot;
    }

    /**
     * Set the color that is used to draw decoration like the overflow dot. This will not be applied
     * to the drawable.
     */
    public void setDecorColor(int iconTint) {
        mDecorColor = iconTint;
        updateDecorColor();
    }

    private void initializeDecorColor() {
        if (isNotification()) {
            setDecorColor(getContext().getColor(mNightMode
                    ? com.android.internal.R.color.notification_default_color_dark
                    : com.android.internal.R.color.notification_default_color_light));
        }
    }

    private void updateDecorColor() {
        int color = NotificationUtils.interpolateColors(mDecorColor, Color.WHITE, mDozeAmount);
        if (mDotPaint.getColor() != color) {
            mDotPaint.setColor(color);

            if (mDotAppearAmount != 0) {
                invalidate();
            }
        }
    }

    /**
     * Set the static color that should be used for the drawable of this icon if it's not
     * transitioning this also immediately sets the color.
     */
    public void setStaticDrawableColor(int color) {
        mDrawableColor = color;
        setColorInternal(color);
        updateContrastedStaticColor();
        mIconColor = color;
    }

    private void setColorInternal(int color) {
        mCurrentSetColor = color;
        updateIconColor();
    }

    private void updateIconColor() {
        if (mShowsConversation) {
            setColorFilter(null);
            return;
        }

        if (mCurrentSetColor != NO_COLOR) {
            if (mMatrixColorFilter == null) {
                mMatrix = new float[4 * 5];
                mMatrixColorFilter = new ColorMatrixColorFilter(mMatrix);
            }
            int color = NotificationUtils.interpolateColors(
                    mCurrentSetColor, Color.WHITE, mDozeAmount);
            updateTintMatrix(mMatrix, color, DARK_ALPHA_BOOST * mDozeAmount);
            mMatrixColorFilter.setColorMatrixArray(mMatrix);
            setColorFilter(null);  // setColorFilter only invalidates if the instance changed.
            setColorFilter(mMatrixColorFilter);
        } else {
            mDozer.updateGrayscale(this, mDozeAmount);
        }
    }

    /**
     * Updates {@param array} such that it represents a matrix that changes RGB to {@param color}
     * and multiplies the alpha channel with the color's alpha+{@param alphaBoost}.
     */
    private static void updateTintMatrix(float[] array, int color, float alphaBoost) {
        Arrays.fill(array, 0);
        array[4] = Color.red(color);
        array[9] = Color.green(color);
        array[14] = Color.blue(color);
        array[18] = Color.alpha(color) / 255f + alphaBoost;
    }

    public void setIconColor(int iconColor, boolean animate) {
        if (mIconColor != iconColor) {
            mIconColor = iconColor;
            if (mColorAnimator != null) {
                mColorAnimator.cancel();
            }
            if (mCurrentSetColor == iconColor) {
                return;
            }
            if (animate && mCurrentSetColor != NO_COLOR) {
                mAnimationStartColor = mCurrentSetColor;
                mColorAnimator = ValueAnimator.ofFloat(0.0f, 1.0f);
                mColorAnimator.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
                mColorAnimator.setDuration(ANIMATION_DURATION_FAST);
                mColorAnimator.addUpdateListener(mColorUpdater);
                mColorAnimator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        mColorAnimator = null;
                        mAnimationStartColor = NO_COLOR;
                    }
                });
                mColorAnimator.start();
            } else {
                setColorInternal(iconColor);
            }
        }
    }

    public int getStaticDrawableColor() {
        return mDrawableColor;
    }

    /**
     * A drawable color that passes GAR on a specific background.
     * This value is cached.
     *
     * @param backgroundColor Background to test against.
     * @return GAR safe version of {@link StatusBarIconView#getStaticDrawableColor()}.
     */
    int getContrastedStaticDrawableColor(int backgroundColor) {
        if (mCachedContrastBackgroundColor != backgroundColor) {
            mCachedContrastBackgroundColor = backgroundColor;
            updateContrastedStaticColor();
        }
        return mContrastedDrawableColor;
    }

    private void updateContrastedStaticColor() {
        if (Color.alpha(mCachedContrastBackgroundColor) != 255) {
            mContrastedDrawableColor = mDrawableColor;
            return;
        }
        // We'll modify the color if it doesn't pass GAR
        int contrastedColor = mDrawableColor;
        if (!ContrastColorUtil.satisfiesTextContrast(mCachedContrastBackgroundColor,
                contrastedColor)) {
            float[] hsl = new float[3];
            ColorUtils.colorToHSL(mDrawableColor, hsl);
            // This is basically a light grey, pushing the color will only distort it.
            // Best thing to do in here is to fallback to the default color.
            if (hsl[1] < 0.2f) {
                contrastedColor = Notification.COLOR_DEFAULT;
            }
            boolean isDark = !ContrastColorUtil.isColorLight(mCachedContrastBackgroundColor);
            contrastedColor = ContrastColorUtil.resolveContrastColor(mContext,
                    contrastedColor, mCachedContrastBackgroundColor, isDark);
        }
        mContrastedDrawableColor = contrastedColor;
    }

    @Override
    public void setVisibleState(@StatusBarIconView.VisibleState int state) {
        setVisibleState(state, true /* animate */, null /* endRunnable */);
    }

    @Override
    public void setVisibleState(@StatusBarIconView.VisibleState int state, boolean animate) {
        setVisibleState(state, animate, null);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    public void setVisibleState(int visibleState, boolean animate, Runnable endRunnable) {
        setVisibleState(visibleState, animate, endRunnable, 0);
    }

    /**
     * Set the visibleState of this view.
     *
     * @param visibleState The new state.
     * @param animate Should we animate?
     * @param endRunnable The runnable to run at the end.
     * @param duration The duration of an animation or 0 if the default should be taken.
     */
    public void setVisibleState(int visibleState, boolean animate, Runnable endRunnable,
            long duration) {
        boolean runnableAdded = false;
        if (visibleState != mVisibleState) {
            mVisibleState = visibleState;
            if (mIconAppearAnimator != null) {
                mIconAppearAnimator.cancel();
            }
            if (mDotAnimator != null) {
                mDotAnimator.cancel();
            }
            if (animate) {
                float targetAmount = 0.0f;
                Interpolator interpolator = Interpolators.FAST_OUT_LINEAR_IN;
                if (visibleState == STATE_ICON) {
                    targetAmount = 1.0f;
                    interpolator = Interpolators.LINEAR_OUT_SLOW_IN;
                }
                float currentAmount = getIconAppearAmount();
                if (targetAmount != currentAmount) {
                    mIconAppearAnimator = ObjectAnimator.ofFloat(this, ICON_APPEAR_AMOUNT,
                            currentAmount, targetAmount);
                    mIconAppearAnimator.setInterpolator(interpolator);
                    mIconAppearAnimator.setDuration(duration == 0 ? ANIMATION_DURATION_FAST
                            : duration);
                    mIconAppearAnimator.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            mIconAppearAnimator = null;
                            runRunnable(endRunnable);
                        }
                    });
                    mIconAppearAnimator.start();
                    runnableAdded = true;
                }

                targetAmount = visibleState == STATE_ICON ? 2.0f : 0.0f;
                interpolator = Interpolators.FAST_OUT_LINEAR_IN;
                if (visibleState == STATE_DOT) {
                    targetAmount = 1.0f;
                    interpolator = Interpolators.LINEAR_OUT_SLOW_IN;
                }
                currentAmount = getDotAppearAmount();
                if (targetAmount != currentAmount) {
                    mDotAnimator = ObjectAnimator.ofFloat(this, DOT_APPEAR_AMOUNT,
                            currentAmount, targetAmount);
                    mDotAnimator.setInterpolator(interpolator);
                    mDotAnimator.setDuration(duration == 0 ? ANIMATION_DURATION_FAST
                            : duration);
                    final boolean runRunnable = !runnableAdded;
                    mDotAnimator.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            mDotAnimator = null;
                            if (runRunnable) {
                                runRunnable(endRunnable);
                            }
                        }
                    });
                    mDotAnimator.start();
                    runnableAdded = true;
                }
            } else {
                setIconAppearAmount(visibleState == STATE_ICON ? 1.0f : 0.0f);
                setDotAppearAmount(visibleState == STATE_DOT ? 1.0f
                        : visibleState == STATE_ICON ? 2.0f
                        : 0.0f);
            }
        }
        if (!runnableAdded) {
            runRunnable(endRunnable);
        }
    }

    private void runRunnable(Runnable runnable) {
        if (runnable != null) {
            runnable.run();
        }
    }

    public void setIconAppearAmount(float iconAppearAmount) {
        if (mIconAppearAmount != iconAppearAmount) {
            mIconAppearAmount = iconAppearAmount;
            invalidate();
        }
    }

    public float getIconAppearAmount() {
        return mIconAppearAmount;
    }

    @StatusBarIconView.VisibleState
    public int getVisibleState() {
        return mVisibleState;
    }

    public void setDotAppearAmount(float dotAppearAmount) {
        if (mDotAppearAmount != dotAppearAmount) {
            mDotAppearAmount = dotAppearAmount;
            invalidate();
        }
    }

    public float getDotAppearAmount() {
        return mDotAppearAmount;
    }

    public void setTintAlpha(float tintAlpha) {
        setDozeAmount(tintAlpha);
    }

    private void setDozeAmount(float dozeAmount) {
        mDozeAmount = dozeAmount;
        updateDecorColor();
        updateIconColor();
    }

    private void updateAllowAnimation() {
        if (mDozeAmount == 0 || mDozeAmount == 1) {
            setAllowAnimation(mDozeAmount == 0);
        }
    }

    /**
     * This method returns the drawing rect for the view which is different from the regular
     * drawing rect, since we layout all children at position 0 and usually the translation is
     * neglected. The standard implementation doesn't account for translation.
     *
     * @param outRect The (scrolled) drawing bounds of the view.
     */
    @Override
    public void getDrawingRect(Rect outRect) {
        super.getDrawingRect(outRect);
        float translationX = getTranslationX();
        float translationY = getTranslationY();
        outRect.left += translationX;
        outRect.right += translationX;
        outRect.top += translationY;
        outRect.bottom += translationY;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (mLayoutRunnable != null) {
            mLayoutRunnable.run();
            mLayoutRunnable = null;
        }
        updatePivot();
    }

    private void updatePivot() {
        if (isLayoutRtl()) {
            setPivotX((1 + mIconScale) / 2.0f * getWidth());
        } else {
            setPivotX((1 - mIconScale) / 2.0f * getWidth());
        }
        setPivotY((getHeight() - mIconScale * getWidth()) / 2.0f);
    }

    public void executeOnLayout(Runnable runnable) {
        mLayoutRunnable = runnable;
    }

    public void setDismissed() {
        mDismissed = true;
        if (mOnDismissListener != null) {
            mOnDismissListener.run();
        }
    }

    public boolean isDismissed() {
        return mDismissed;
    }

    public void setOnDismissListener(Runnable onDismissListener) {
        mOnDismissListener = onDismissListener;
    }

    @Override
    public void onDarkChanged(ArrayList<Rect> areas, float darkIntensity, int tint) {
        int areaTint = getTint(areas, this, tint);
        ColorStateList color = ColorStateList.valueOf(areaTint);
        setImageTintList(color);
        setDecorColor(areaTint);
    }

    @Override
    public boolean isIconVisible() {
        return mIcon != null && mIcon.visible;
    }

    @Override
    public boolean isIconBlocked() {
        return mBlocked;
    }

    public void setIncreasedSize(boolean increasedSize) {
        mIncreasedSize = increasedSize;
        maybeUpdateIconScaleDimens();
    }

    /**
     * Sets whether this icon shows a person and should be tinted.
     * If the state differs from the supplied setting, this
     * will update the icon colors.
     *
     * @param showsConversation Whether the icon shows a person
     */
    public void setShowsConversation(boolean showsConversation) {
        if (mShowsConversation != showsConversation) {
            mShowsConversation = showsConversation;
            updateIconColor();
        }
    }

    /**
     * @return if this icon shows a conversation
     */
    public boolean showsConversation() {
        return mShowsConversation;
    }
}
