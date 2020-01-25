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
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.InsetDrawable;
import android.util.AttributeSet;
import android.util.PathParser;
import android.widget.FrameLayout;

import com.android.internal.graphics.ColorUtils;
import com.android.launcher3.icons.ShadowGenerator;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;

/**
 * A floating object on the screen that can post message updates.
 */
public class BubbleView extends FrameLayout {

    private static final int DARK_ICON_ALPHA = 180;
    private static final double ICON_MIN_CONTRAST = 4.1;
    private static final int DEFAULT_BACKGROUND_COLOR = Color.LTGRAY;
    // Same value as Launcher3 badge code
    private static final float WHITE_SCRIM_ALPHA = 0.54f;
    private Context mContext;

    private BadgedImageView mBadgedImageView;
    private int mBadgeColor;
    private int mIconInset;
    private Drawable mUserBadgedAppIcon;

    // mBubbleIconFactory cannot be static because it depends on Context.
    private BubbleIconFactory mBubbleIconFactory;

    private boolean mSuppressDot = false;

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
        mIconInset = getResources().getDimensionPixelSize(R.dimen.bubble_icon_inset);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mBadgedImageView = findViewById(R.id.bubble_image);
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

    /**
     * @param factory Factory for creating normalized bubble icons.
     */
    public void setBubbleIconFactory(BubbleIconFactory factory) {
        mBubbleIconFactory = factory;
    }

    public void setAppIcon(Drawable appIcon) {
        mUserBadgedAppIcon = appIcon;
    }
    /**
     * @return the {@link ExpandableNotificationRow} view to display notification content when the
     * bubble is expanded.
     */
    @Nullable
    public ExpandableNotificationRow getRowView() {
        return (mBubble != null) ? mBubble.getEntry().getRow() : null;
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

    /** Sets the position of the 'new' dot, animating it out and back in if requested. */
    void setDotPosition(boolean onLeft, boolean animate) {
        if (animate && onLeft != mBadgedImageView.getDotOnLeft() && !mSuppressDot) {
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
        boolean showDot = mBubble.showBubbleDot() && !mSuppressDot;

        if (animate) {
            animateDot(showDot, after);
        } else {
            mBadgedImageView.setShowDot(showDot);
        }
    }

    /**
     * Animates the badge to show or hide.
     */
    private void animateDot(boolean showDot, Runnable after) {
        if (mBadgedImageView.isShowingDot() != showDot) {
            if (showDot) {
                mBadgedImageView.setShowDot(true);
            }
            mBadgedImageView.clearAnimation();
            mBadgedImageView.animate().setDuration(200)
                    .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                    .setUpdateListener((valueAnimator) -> {
                        float fraction = valueAnimator.getAnimatedFraction();
                        fraction = showDot ? fraction : 1f - fraction;
                        mBadgedImageView.setDotScale(fraction);
                    }).withEndAction(() -> {
                        if (!showDot) {
                            mBadgedImageView.setShowDot(false);
                        }

                        if (after != null) {
                            after.run();
                        }
            }).start();
        }
    }

    void updateViews() {
        if (mBubble == null || mBubbleIconFactory == null) {
            return;
        }
        // Update icon.
        Notification.BubbleMetadata metadata = mBubble.getEntry().getBubbleMetadata();
        Notification n = mBubble.getEntry().notification.getNotification();
        Icon ic = metadata.getIcon();
        boolean needsTint = ic.getType() != Icon.TYPE_ADAPTIVE_BITMAP;

        Drawable iconDrawable = ic.loadDrawable(mContext);
        if (needsTint) {
            iconDrawable = buildIconWithTint(iconDrawable, n.color);
        }
        Bitmap bubbleIcon = mBubbleIconFactory.createBadgedIconBitmap(iconDrawable,
                null /* user */,
                true /* shrinkNonAdaptiveIcons */).icon;

        // Give it a shadow
        Bitmap userBadgedBitmap = mBubbleIconFactory.createIconBitmap(mUserBadgedAppIcon,
                1f, mBubbleIconFactory.getBadgeSize());
        Canvas c = new Canvas();
        ShadowGenerator shadowGenerator = new ShadowGenerator(mBubbleIconFactory.getBadgeSize());
        c.setBitmap(userBadgedBitmap);
        shadowGenerator.recreateIcon(Bitmap.createBitmap(userBadgedBitmap), c);

        mBubbleIconFactory.badgeWithDrawable(bubbleIcon,
                new BitmapDrawable(mContext.getResources(), userBadgedBitmap));
        mBadgedImageView.setImageBitmap(bubbleIcon);

        // Update badge.
        int badgeColor = determineDominateColor(iconDrawable, n.color);
        mBadgeColor = badgeColor;
        mBadgedImageView.setDotColor(badgeColor);

        // Update dot.
        Path iconPath = PathParser.createPathFromPathData(
                getResources().getString(com.android.internal.R.string.config_icon_mask));
        Matrix matrix = new Matrix();
        float scale = mBubbleIconFactory.getNormalizer().getScale(iconDrawable,
                null /* outBounds */, null /* path */, null /* outMaskShape */);
        float radius = BadgedImageView.DEFAULT_PATH_SIZE / 2f;
        matrix.setScale(scale /* x scale */, scale /* y scale */, radius /* pivot x */,
                radius /* pivot y */);
        iconPath.transform(matrix);
        mBadgedImageView.drawDot(iconPath);

        animateDot(mBubble.showBubbleDot() /* showDot */, null /* after */);
    }

    int getBadgeColor() {
        return mBadgeColor;
    }

    private AdaptiveIconDrawable buildIconWithTint(Drawable iconDrawable, int backgroundColor) {
        iconDrawable = checkTint(iconDrawable, backgroundColor);
        InsetDrawable foreground = new InsetDrawable(iconDrawable, mIconInset);
        ColorDrawable background = new ColorDrawable(backgroundColor);
        return new AdaptiveIconDrawable(background, foreground);
    }

    private Drawable checkTint(Drawable iconDrawable, int backgroundColor) {
        backgroundColor = ColorUtils.setAlphaComponent(backgroundColor, 255 /* alpha */);
        if (backgroundColor == Color.TRANSPARENT) {
            // ColorUtils throws exception when background is translucent.
            backgroundColor = DEFAULT_BACKGROUND_COLOR;
        }
        iconDrawable.setTint(Color.WHITE);
        double contrastRatio = ColorUtils.calculateContrast(Color.WHITE, backgroundColor);
        if (contrastRatio < ICON_MIN_CONTRAST) {
            int dark = ColorUtils.setAlphaComponent(Color.BLACK, DARK_ICON_ALPHA);
            iconDrawable.setTint(dark);
        }
        return iconDrawable;
    }

    private int determineDominateColor(Drawable d, int defaultTint) {
        // XXX: should we pull from the drawable, app icon, notif tint?
        return ColorUtils.blendARGB(defaultTint, Color.WHITE, WHITE_SCRIM_ALPHA);
    }
}
