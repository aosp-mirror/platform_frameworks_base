/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.bubbles;

import android.annotation.Nullable;
import android.app.Notification;
import android.content.Context;
import android.content.pm.LauncherApps;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.util.AttributeSet;
import android.util.PathParser;
import android.widget.FrameLayout;

import com.android.internal.graphics.ColorUtils;
import com.android.launcher3.icons.BitmapInfo;
import com.android.launcher3.icons.ColorExtractor;
import com.android.launcher3.icons.ShadowGenerator;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

/**
 * A floating object on the screen that can post message updates.
 */
public class BubbleView extends FrameLayout {

    // Same value as Launcher3 badge code
    private static final float WHITE_SCRIM_ALPHA = 0.54f;
    private Context mContext;

    private BadgedImageView mBadgedImageView;
    private int mDotColor;
    private ColorExtractor mColorExtractor;

    // mBubbleIconFactory cannot be static because it depends on Context.
    private BubbleIconFactory mBubbleIconFactory;

    private boolean mSuppressDot;

    private Bubble mBubble;

    public BubbleView(Context context) {
        this(context, null);
    }

    public BubbleView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BubbleView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public BubbleView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mContext = context;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mBadgedImageView = findViewById(R.id.bubble_image);
        mColorExtractor = new ColorExtractor();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
    }

    /**
     * Populates this view with a bubble.
     * <p>
     * This should only be called when a new bubble is being set on the view, updates to the
     * current bubble should use {@link #update(Bubble)}.
     *
     * @param bubble the bubble to display in this view.
     */
    public void setBubble(Bubble bubble) {
        mBubble = bubble;
    }

    /**
     * @param factory Factory for creating normalized bubble icons.
     */
    public void setBubbleIconFactory(BubbleIconFactory factory) {
        mBubbleIconFactory = factory;
    }

    /**
     * The {@link NotificationEntry} associated with this view, if one exists.
     */
    @Nullable
    public NotificationEntry getEntry() {
        return mBubble != null ? mBubble.getEntry() : null;
    }

    /**
     * The key for the {@link NotificationEntry} associated with this view, if one exists.
     */
    @Nullable
    public String getKey() {
        return (mBubble != null) ? mBubble.getKey() : null;
    }

    /**
     * Updates the UI based on the bubble, updates badge and animates messages as needed.
     */
    public void update(Bubble bubble) {
        mBubble = bubble;
        updateViews();
    }

    /** Changes the dot's visibility to match the bubble view's state. */
    void updateDotVisibility(boolean animate) {
        updateDotVisibility(animate, null /* after */);
    }

    /**
     * Sets whether or not to hide the dot even if we'd otherwise show it. This is used while the
     * flyout is visible or animating, to hide the dot until the flyout visually transforms into it.
     */
    void setSuppressDot(boolean suppressDot, boolean animate) {
        mSuppressDot = suppressDot;
        updateDotVisibility(animate);
    }

    boolean isDotShowing() {
        return mBubble.showBubbleDot() && !mSuppressDot;
    }

    int getDotColor() {
        return mDotColor;
    }

    /** Sets the position of the 'new' dot, animating it out and back in if requested. */
    void setDotPosition(boolean onLeft, boolean animate) {
        if (animate && onLeft != mBadgedImageView.getDotOnLeft() && isDotShowing()) {
            animateDot(false /* showDot */, () -> {
                mBadgedImageView.setDotOnLeft(onLeft);
                animateDot(true /* showDot */, null);
            });
        } else {
            mBadgedImageView.setDotOnLeft(onLeft);
        }
    }

    float[] getDotCenter() {
        float[] unscaled = mBadgedImageView.getDotCenter();
        return new float[]{unscaled[0], unscaled[1]};
    }

    boolean getDotPositionOnLeft() {
        return mBadgedImageView.getDotOnLeft();
    }

    /**
     * Changes the dot's visibility to match the bubble view's state, running the provided callback
     * after animation if requested.
     */
    private void updateDotVisibility(boolean animate, Runnable after) {
        final boolean showDot = isDotShowing();
        if (animate) {
            animateDot(showDot, after);
        } else {
            mBadgedImageView.setShowDot(showDot);
            mBadgedImageView.setDotScale(showDot ? 1f : 0f);
        }
    }

    /**
     * Animates the badge to show or hide.
     */
    private void animateDot(boolean showDot, Runnable after) {
        if (mBadgedImageView.isShowingDot() == showDot) {
            return;
        }
        // Do NOT wait until after animation ends to setShowDot
        // to avoid overriding more recent showDot states.
        mBadgedImageView.setShowDot(showDot);
        mBadgedImageView.clearAnimation();
        mBadgedImageView.animate().setDuration(200)
                .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                .setUpdateListener((valueAnimator) -> {
                    float fraction = valueAnimator.getAnimatedFraction();
                    fraction = showDot ? fraction : 1f - fraction;
                    mBadgedImageView.setDotScale(fraction);
                }).withEndAction(() -> {
            mBadgedImageView.setDotScale(showDot ? 1f : 0f);
            if (after != null) {
                after.run();
            }
        }).start();
    }

    void updateViews() {
        if (mBubble == null || mBubbleIconFactory == null) {
            return;
        }

        Drawable bubbleDrawable = getBubbleDrawable(mContext);
        BitmapInfo badgeBitmapInfo = getBadgedBitmap();
        BitmapInfo bubbleBitmapInfo = getBubbleBitmap(bubbleDrawable, badgeBitmapInfo);
        mBadgedImageView.setImageBitmap(bubbleBitmapInfo.icon);

        // Update badge.
        mDotColor = ColorUtils.blendARGB(badgeBitmapInfo.color, Color.WHITE, WHITE_SCRIM_ALPHA);
        mBadgedImageView.setDotColor(mDotColor);

        // Update dot.
        Path iconPath = PathParser.createPathFromPathData(
                getResources().getString(com.android.internal.R.string.config_icon_mask));
        Matrix matrix = new Matrix();
        float scale = mBubbleIconFactory.getNormalizer().getScale(bubbleDrawable,
                null /* outBounds */, null /* path */, null /* outMaskShape */);
        float radius = BadgedImageView.DEFAULT_PATH_SIZE / 2f;
        matrix.setScale(scale /* x scale */, scale /* y scale */, radius /* pivot x */,
                radius /* pivot y */);
        iconPath.transform(matrix);
        mBadgedImageView.drawDot(iconPath);

        animateDot(isDotShowing(), null /* after */);
    }

    Drawable getBubbleDrawable(Context context) {
        if (mBubble.getShortcutInfo() != null && mBubble.usingShortcutInfo()) {
            LauncherApps launcherApps =
                    (LauncherApps) getContext().getSystemService(Context.LAUNCHER_APPS_SERVICE);
            int density = getContext().getResources().getConfiguration().densityDpi;
            return launcherApps.getShortcutIconDrawable(mBubble.getShortcutInfo(), density);
        } else {
            Notification.BubbleMetadata metadata = getEntry().getBubbleMetadata();
            Icon ic = metadata.getIcon();
            return ic.loadDrawable(context);
        }
    }

    BitmapInfo getBadgedBitmap() {
        Bitmap userBadgedBitmap = mBubbleIconFactory.createIconBitmap(
                mBubble.getUserBadgedAppIcon(), 1f, mBubbleIconFactory.getBadgeSize());

        Canvas c = new Canvas();
        ShadowGenerator shadowGenerator = new ShadowGenerator(mBubbleIconFactory.getBadgeSize());
        c.setBitmap(userBadgedBitmap);
        shadowGenerator.recreateIcon(Bitmap.createBitmap(userBadgedBitmap), c);
        BitmapInfo bitmapInfo = mBubbleIconFactory.createIconBitmap(userBadgedBitmap);
        return bitmapInfo;
    }

    BitmapInfo getBubbleBitmap(Drawable bubble, BitmapInfo badge) {
        BitmapInfo bubbleIconInfo = mBubbleIconFactory.createBadgedIconBitmap(bubble,
                null /* user */,
                true /* shrinkNonAdaptiveIcons */);

        mBubbleIconFactory.badgeWithDrawable(bubbleIconInfo.icon,
                new BitmapDrawable(mContext.getResources(), badge.icon));
        return bubbleIconInfo;
    }
}
