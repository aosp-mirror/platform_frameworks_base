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
import android.app.Notification;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Parcelable;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.FloatProperty;
import android.util.Log;
import android.util.Property;
import android.util.TypedValue;
import android.view.ViewDebug;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.Interpolator;

import androidx.core.graphics.ColorUtils;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.util.ContrastColorUtil;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.statusbar.notification.NotificationIconDozeHelper;
import com.android.systemui.statusbar.notification.NotificationUtils;

import java.text.NumberFormat;
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

    private boolean mAlwaysScaleIcon;
    private int mStatusBarIconDrawingSizeDark = 1;
    private int mStatusBarIconDrawingSize = 1;
    private int mStatusBarIconSize = 1;
    private StatusBarIcon mIcon;
    @ViewDebug.ExportedProperty private String mSlot;
    private Drawable mNumberBackground;
    private Paint mNumberPain;
    private int mNumberX;
    private int mNumberY;
    private String mNumberText;
    private StatusBarNotification mNotification;
    private final boolean mBlocked;
    private int mDensity;
    private boolean mNightMode;
    private float mIconScale = 1.0f;
    private final Paint mDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float mDotRadius;
    private int mStaticDotRadius;
    private int mVisibleState = STATE_ICON;
    private float mIconAppearAmount = 1.0f;
    private ObjectAnimator mIconAppearAnimator;
    private ObjectAnimator mDotAnimator;
    private float mDotAppearAmount;
    private OnVisibilityChangedListener mOnVisibilityChangedListener;
    private int mDrawableColor;
    private int mIconColor;
    private int mDecorColor;
    private float mDarkAmount;
    private ValueAnimator mColorAnimator;
    private int mCurrentSetColor = NO_COLOR;
    private int mAnimationStartColor = NO_COLOR;
    private final ValueAnimator.AnimatorUpdateListener mColorUpdater
            = animation -> {
        int newColor = NotificationUtils.interpolateColors(mAnimationStartColor, mIconColor,
                animation.getAnimatedFraction());
        setColorInternal(newColor);
    };
    private final NotificationIconDozeHelper mDozer;
    private int mContrastedDrawableColor;
    private int mCachedContrastBackgroundColor = NO_COLOR;
    private float[] mMatrix;
    private ColorMatrixColorFilter mMatrixColorFilter;
    private boolean mIsInShelf;
    private Runnable mLayoutRunnable;
    private boolean mDismissed;
    private Runnable mOnDismissListener;

    public StatusBarIconView(Context context, String slot, StatusBarNotification sbn) {
        this(context, slot, sbn, false);
    }

    public StatusBarIconView(Context context, String slot, StatusBarNotification sbn,
            boolean blocked) {
        super(context);
        mDozer = new NotificationIconDozeHelper(context);
        mBlocked = blocked;
        mSlot = slot;
        mNumberPain = new Paint();
        mNumberPain.setTextAlign(Paint.Align.CENTER);
        mNumberPain.setColor(context.getColor(R.drawable.notification_number_text_color));
        mNumberPain.setAntiAlias(true);
        setNotification(sbn);
        setScaleType(ScaleType.CENTER);
        mDensity = context.getResources().getDisplayMetrics().densityDpi;
        Configuration configuration = context.getResources().getConfiguration();
        mNightMode = (configuration.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        initializeDecorColor();
        reloadDimens();
        maybeUpdateIconScaleDimens();
    }

    /** Should always be preceded by {@link #reloadDimens()} */
    private void maybeUpdateIconScaleDimens() {
        // We do not resize and scale system icons (on the right), only notification icons (on the
        // left).
        if (mNotification != null || mAlwaysScaleIcon) {
            updateIconScaleForNotifications();
        } else {
            updateIconScaleForSystemIcons();
        }
    }

    private void updateIconScaleForNotifications() {
        final float imageBounds = NotificationUtils.interpolate(
                mStatusBarIconDrawingSize,
                mStatusBarIconDrawingSizeDark,
                mDarkAmount);
        final int outerBounds = mStatusBarIconSize;
        mIconScale = (float)imageBounds / (float)outerBounds;
        updatePivot();
    }

    // Makes sure that all icons are scaled to the same height (15dp). If we cannot get a height
    // for the icon, it uses the default SCALE (15f / 17f) which is the old behavior
    private void updateIconScaleForSystemIcons() {
        float iconHeight = getIconHeight();
        if (iconHeight != 0) {
            mIconScale = mSystemIconDesiredHeight / iconHeight;
        } else {
            mIconScale = mSystemIconDefaultScale;
        }
    }

    private float getIconHeight() {
        Drawable d = getDrawable();
        if (d != null) {
            return (float) getDrawable().getIntrinsicHeight();
        } else {
            return mSystemIconIntrinsicHeight;
        }
    }

    public float getIconScaleFullyDark() {
        return (float) mStatusBarIconDrawingSizeDark / mStatusBarIconDrawingSize;
    }

    public float getIconScale() {
        return mIconScale;
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int density = newConfig.densityDpi;
        if (density != mDensity) {
            mDensity = density;
            reloadDimens();
            updateDrawable();
            maybeUpdateIconScaleDimens();
        }
        boolean nightMode = (newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        if (nightMode != mNightMode) {
            mNightMode = nightMode;
            initializeDecorColor();
        }
    }

    private void reloadDimens() {
        boolean applyRadius = mDotRadius == mStaticDotRadius;
        Resources res = getResources();
        mStaticDotRadius = res.getDimensionPixelSize(R.dimen.overflow_dot_radius);
        mStatusBarIconSize = res.getDimensionPixelSize(R.dimen.status_bar_icon_size);
        mStatusBarIconDrawingSizeDark =
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
        mNotification = notification;
        if (notification != null) {
            setContentDescription(notification.getNotification());
        }
        maybeUpdateIconScaleDimens();
    }

    public StatusBarIconView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDozer = new NotificationIconDozeHelper(context);
        mBlocked = false;
        mAlwaysScaleIcon = true;
        reloadDimens();
        maybeUpdateIconScaleDimens();
        mDensity = context.getResources().getDisplayMetrics().densityDpi;
    }

    private static boolean streq(String a, String b) {
        if (a == b) {
            return true;
        }
        if (a == null && b != null) {
            return false;
        }
        if (a != null && b == null) {
            return false;
        }
        return a.equals(b);
    }

    public boolean equalIcons(Icon a, Icon b) {
        if (a == b) return true;
        if (a.getType() != b.getType()) return false;
        switch (a.getType()) {
            case Icon.TYPE_RESOURCE:
                return a.getResPackage().equals(b.getResPackage()) && a.getResId() == b.getResId();
            case Icon.TYPE_URI:
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
        final boolean numberEquals = mIcon != null
                && mIcon.number == icon.number;
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

        if (!numberEquals) {
            if (icon.number > 0 && getContext().getResources().getBoolean(
                        R.bool.config_statusBarShowNumber)) {
                if (mNumberBackground == null) {
                    mNumberBackground = getContext().getResources().getDrawable(
                            R.drawable.ic_notification_overlay);
                }
                placeNumber();
            } else {
                mNumberBackground = null;
                mNumberText = null;
            }
            invalidate();
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
            drawable = getIcon(mIcon);
        } catch (OutOfMemoryError e) {
            Log.w(TAG, "OOM while inflating " + mIcon.icon + " for slot " + mSlot);
            return false;
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

    private Drawable getIcon(StatusBarIcon icon) {
        return getIcon(getContext(), icon);
    }

    /**
     * Returns the right icon to use for this item
     *
     * @param context Context to use to get resources
     * @return Drawable for this item, or null if the package or item could not
     *         be found
     */
    public static Drawable getIcon(Context context, StatusBarIcon statusBarIcon) {
        int userId = statusBarIcon.user.getIdentifier();
        if (userId == UserHandle.USER_ALL) {
            userId = UserHandle.USER_SYSTEM;
        }

        Drawable icon = statusBarIcon.icon.loadDrawableAsUser(context, userId);

        TypedValue typedValue = new TypedValue();
        context.getResources().getValue(R.dimen.status_bar_icon_scale_factor, typedValue, true);
        float scaleFactor = typedValue.getFloat();

        // No need to scale the icon, so return it as is.
        if (scaleFactor == 1.f) {
            return icon;
        }

        return new ScalingDrawableWrapper(icon, scaleFactor);
    }

    public StatusBarIcon getStatusBarIcon() {
        return mIcon;
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        if (mNotification != null) {
            event.setParcelableData(mNotification.getNotification());
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (mNumberBackground != null) {
            placeNumber();
        }
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        super.onRtlPropertiesChanged(layoutDirection);
        updateDrawable();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mIconAppearAmount > 0.0f) {
            canvas.save();
            canvas.scale(mIconScale * mIconAppearAmount, mIconScale * mIconAppearAmount,
                    getWidth() / 2, getHeight() / 2);
            super.onDraw(canvas);
            canvas.restore();
        }

        if (mNumberBackground != null) {
            mNumberBackground.draw(canvas);
            canvas.drawText(mNumberText, mNumberX, mNumberY, mNumberPain);
        }
        if (mDotAppearAmount != 0.0f) {
            float radius;
            float alpha = Color.alpha(mDecorColor) / 255.f;
            if (mDotAppearAmount <= 1.0f) {
                radius = mDotRadius * mDotAppearAmount;
            } else {
                float fadeOutAmount = mDotAppearAmount - 1.0f;
                alpha = alpha * (1.0f - fadeOutAmount);
                radius = NotificationUtils.interpolate(mDotRadius, getWidth() / 4, fadeOutAmount);
            }
            mDotPaint.setAlpha((int) (alpha * 255));
            canvas.drawCircle(mStatusBarIconSize / 2, getHeight() / 2, radius, mDotPaint);
        }
    }

    @Override
    protected void debug(int depth) {
        super.debug(depth);
        Log.d("View", debugIndent(depth) + "slot=" + mSlot);
        Log.d("View", debugIndent(depth) + "icon=" + mIcon);
    }

    void placeNumber() {
        final String str;
        final int tooBig = getContext().getResources().getInteger(
                android.R.integer.status_bar_notification_info_maxnum);
        if (mIcon.number > tooBig) {
            str = getContext().getResources().getString(
                        android.R.string.status_bar_notification_info_overflow);
        } else {
            NumberFormat f = NumberFormat.getIntegerInstance();
            str = f.format(mIcon.number);
        }
        mNumberText = str;

        final int w = getWidth();
        final int h = getHeight();
        final Rect r = new Rect();
        mNumberPain.getTextBounds(str, 0, str.length(), r);
        final int tw = r.right - r.left;
        final int th = r.bottom - r.top;
        mNumberBackground.getPadding(r);
        int dw = r.left + tw + r.right;
        if (dw < mNumberBackground.getMinimumWidth()) {
            dw = mNumberBackground.getMinimumWidth();
        }
        mNumberX = w-r.right-((dw-r.right-r.left)/2);
        int dh = r.top + th + r.bottom;
        if (dh < mNumberBackground.getMinimumWidth()) {
            dh = mNumberBackground.getMinimumWidth();
        }
        mNumberY = h-r.bottom-((dh-r.top-th-r.bottom)/2);
        mNumberBackground.setBounds(w-dw, h-dh, w, h);
    }

    private void setContentDescription(Notification notification) {
        if (notification != null) {
            String d = contentDescForNotification(mContext, notification);
            if (!TextUtils.isEmpty(d)) {
                setContentDescription(d);
            }
        }
    }

    public String toString() {
        return "StatusBarIconView(slot=" + mSlot + " icon=" + mIcon
            + " notification=" + mNotification + ")";
    }

    public StatusBarNotification getNotification() {
        return mNotification;
    }

    public String getSlot() {
        return mSlot;
    }


    public static String contentDescForNotification(Context c, Notification n) {
        String appName = "";
        try {
            Notification.Builder builder = Notification.Builder.recoverBuilder(c, n);
            appName = builder.loadHeaderAppName();
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to recover builder", e);
            // Trying to get the app name from the app info instead.
            Parcelable appInfo = n.extras.getParcelable(
                    Notification.EXTRA_BUILDER_APPLICATION_INFO);
            if (appInfo instanceof ApplicationInfo) {
                appName = String.valueOf(((ApplicationInfo) appInfo).loadLabel(
                        c.getPackageManager()));
            }
        }

        CharSequence title = n.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = n.extras.getCharSequence(Notification.EXTRA_TEXT);
        CharSequence ticker = n.tickerText;

        // Some apps just put the app name into the title
        CharSequence titleOrText = TextUtils.equals(title, appName) ? text : title;

        CharSequence desc = !TextUtils.isEmpty(titleOrText) ? titleOrText
                : !TextUtils.isEmpty(ticker) ? ticker : "";

        return c.getString(R.string.accessibility_desc_notification_icon, appName, desc);
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
        if (mNotification != null) {
            setDecorColor(getContext().getColor(mNightMode
                    ? com.android.internal.R.color.notification_default_color_dark
                    : com.android.internal.R.color.notification_default_color_light));
        }
    }

    private void updateDecorColor() {
        int color = NotificationUtils.interpolateColors(mDecorColor, Color.WHITE, mDarkAmount);
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
        mDozer.setColor(color);
    }

    private void setColorInternal(int color) {
        mCurrentSetColor = color;
        updateIconColor();
    }

    private void updateIconColor() {
        if (mCurrentSetColor != NO_COLOR) {
            if (mMatrixColorFilter == null) {
                mMatrix = new float[4 * 5];
                mMatrixColorFilter = new ColorMatrixColorFilter(mMatrix);
            }
            int color = NotificationUtils.interpolateColors(
                    mCurrentSetColor, Color.WHITE, mDarkAmount);
            updateTintMatrix(mMatrix, color, DARK_ALPHA_BOOST * mDarkAmount);
            mMatrixColorFilter.setColorMatrixArray(mMatrix);
            setColorFilter(null);  // setColorFilter only invalidates if the instance changed.
            setColorFilter(mMatrixColorFilter);
        } else {
            mDozer.updateGrayscale(this, mDarkAmount);
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
    public void setVisibleState(int state) {
        setVisibleState(state, true /* animate */, null /* endRunnable */);
    }

    public void setVisibleState(int state, boolean animate) {
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
                    mDotAnimator.setInterpolator(interpolator);;
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

    public int getVisibleState() {
        return mVisibleState;
    }

    public void setDotAppearAmount(float dotAppearAmount) {
        if (mDotAppearAmount != dotAppearAmount) {
            mDotAppearAmount = dotAppearAmount;
            invalidate();
        }
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if (mOnVisibilityChangedListener != null) {
            mOnVisibilityChangedListener.onVisibilityChanged(visibility);
        }
    }

    public float getDotAppearAmount() {
        return mDotAppearAmount;
    }

    public void setOnVisibilityChangedListener(OnVisibilityChangedListener listener) {
        mOnVisibilityChangedListener = listener;
    }

    public void setDark(boolean dark, boolean fade, long delay) {
        mDozer.setIntensityDark(f -> {
            mDarkAmount = f;
            maybeUpdateIconScaleDimens();
            updateDecorColor();
            updateIconColor();
            updateAllowAnimation();
        }, dark, fade, delay, this);
    }

    private void updateAllowAnimation() {
        if (mDarkAmount == 0 || mDarkAmount == 1) {
            setAllowAnimation(mDarkAmount == 0);
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

    public void setIsInShelf(boolean isInShelf) {
        mIsInShelf = isInShelf;
    }

    public boolean isInShelf() {
        return mIsInShelf;
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
        setPivotX((1 - mIconScale) / 2.0f * getWidth());
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
    public void onDarkChanged(Rect area, float darkIntensity, int tint) {
        int areaTint = getTint(area, this, tint);
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

    public interface OnVisibilityChangedListener {
        void onVisibilityChanged(int newVisibility);
    }
}
