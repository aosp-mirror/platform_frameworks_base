package com.android.systemui.qs;

import static com.android.systemui.qs.PageIndicator.PageScrollActionListener.LEFT;
import static com.android.systemui.qs.PageIndicator.PageScrollActionListener.RIGHT;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.drawable.Animatable2;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;

import com.android.settingslib.Utils;
import com.android.systemui.res.R;

import java.util.ArrayList;

/**
 * Page indicator for using with pageable layouts
 *
 * Supports {@code android.R.attr.tint}. If missing, it will use the current accent color.
 */
public class PageIndicator extends ViewGroup {

    private static final String TAG = "PageIndicator";
    private static final boolean DEBUG = false;

    private static final long ANIMATION_DURATION = 250;

    private static final float MINOR_ALPHA = .42f;

    private final ArrayList<Integer> mQueuedPositions = new ArrayList<>();

    private int mPageIndicatorWidth;
    private int mPageIndicatorHeight;
    private int mPageDotWidth;
    private @NonNull ColorStateList mTint;

    private int mPosition = -1;
    private boolean mAnimating;
    private PageScrollActionListener mPageScrollActionListener;

    private final Animatable2.AnimationCallback mAnimationCallback =
            new Animatable2.AnimationCallback() {

                @Override
                public void onAnimationEnd(Drawable drawable) {
                    super.onAnimationEnd(drawable);
                    if (DEBUG) Log.d(TAG, "onAnimationEnd - queued: " + mQueuedPositions.size());
                    if (drawable instanceof AnimatedVectorDrawable) {
                        ((AnimatedVectorDrawable) drawable).unregisterAnimationCallback(
                                mAnimationCallback);
                    }
                    mAnimating = false;
                    if (mQueuedPositions.size() != 0) {
                        setPosition(mQueuedPositions.remove(0));
                    }
                }
            };

    public PageIndicator(Context context, AttributeSet attrs) {
        super(context, attrs);

        TypedArray array = context.obtainStyledAttributes(attrs, new int[]{android.R.attr.tint});
        if (array.hasValue(0)) {
            mTint = array.getColorStateList(0);
        } else {
            mTint = Utils.getColorAccent(context);
        }
        array.recycle();

        Resources res = context.getResources();
        mPageIndicatorWidth = res.getDimensionPixelSize(R.dimen.qs_page_indicator_width);
        mPageIndicatorHeight = res.getDimensionPixelSize(R.dimen.qs_page_indicator_height);
        mPageDotWidth = res.getDimensionPixelSize(R.dimen.qs_page_indicator_dot_width);
        LeftRightArrowPressedListener arrowListener =
                LeftRightArrowPressedListener.createAndRegisterListenerForView(this);
        arrowListener.setArrowKeyPressedListener(keyCode -> {
            if (mPageScrollActionListener != null) {
                int swipeDirection = keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? LEFT : RIGHT;
                mPageScrollActionListener.onScrollActionTriggered(swipeDirection);
            }
        });
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateResources();
    }

    private void updateResources() {
        Resources res = getResources();
        boolean changed = false;
        int pageIndicatorWidth = res.getDimensionPixelSize(R.dimen.qs_page_indicator_width);
        if (pageIndicatorWidth != mPageIndicatorWidth) {
            mPageIndicatorWidth = pageIndicatorWidth;
            changed = true;
        }
        int pageIndicatorHeight = res.getDimensionPixelSize(R.dimen.qs_page_indicator_height);
        if (pageIndicatorHeight != mPageIndicatorHeight) {
            mPageIndicatorHeight = pageIndicatorHeight;
            changed = true;
        }
        int pageIndicatorDotWidth = res.getDimensionPixelSize(R.dimen.qs_page_indicator_dot_width);
        if (pageIndicatorDotWidth != mPageDotWidth) {
            mPageDotWidth = pageIndicatorDotWidth;
            changed = true;
        }
        if (changed) {
            invalidate();
        }
    }

    public void setNumPages(int numPages) {
        setVisibility(numPages > 1 ? View.VISIBLE : View.GONE);
        int childCount = getChildCount();
        // We're checking if the width needs to be updated as it's possible that the number of pages
        // was changed while the page indicator was not visible, automatically skipping onMeasure.
        if (numPages == childCount && calculateWidth(childCount) == getMeasuredWidth()) {
            return;
        }
        if (mAnimating) {
            Log.w(TAG, "setNumPages during animation");
        }
        while (numPages < getChildCount()) {
            removeViewAt(getChildCount() - 1);
        }
        while (numPages > getChildCount()) {
            ImageView v = new ImageView(mContext);
            v.setImageResource(R.drawable.minor_a_b);
            v.setImageTintList(mTint);
            addView(v, new LayoutParams(mPageIndicatorWidth, mPageIndicatorHeight));
        }
        // Refresh state.
        setIndex(mPosition >> 1);
        requestLayout();
    }

    /**
     * @return the current tint list for this view.
     */
    @NonNull
    public ColorStateList getTintList() {
        return mTint;
    }

    /**
     * Set the color for this view.
     * <br>
     * Calling this will change the color of the current view and any new dots that are added to it.
     * @param color the new color
     */
    public void setTintList(@NonNull ColorStateList color) {
        if (color.equals(mTint)) {
            return;
        }
        mTint = color;
        final int N = getChildCount();
        for (int i = 0; i < N; i++) {
            View v = getChildAt(i);
            if (v instanceof ImageView) {
                ((ImageView) v).setImageTintList(mTint);
            }
        }
    }

    public void setLocation(float location) {
        int index = (int) location;
        setContentDescription(getContext().getString(R.string.accessibility_quick_settings_page,
                (index + 1), getChildCount()));
        int position = index << 1 | ((location != index) ? 1 : 0);
        if (DEBUG) Log.d(TAG, "setLocation " + location + " " + index + " " + position);

        int lastPosition = mPosition;
        if (mQueuedPositions.size() != 0) {
            lastPosition = mQueuedPositions.get(mQueuedPositions.size() - 1);
        }
        if (position == lastPosition) return;
        if (mAnimating) {
            if (DEBUG) Log.d(TAG, "Queueing transition to " + Integer.toHexString(position));
            mQueuedPositions.add(position);
            return;
        }

        setPosition(position);
    }

    private void setPosition(int position) {
        if (isVisibleToUser() && Math.abs(mPosition - position) == 1) {
            animate(mPosition, position);
        } else {
            if (DEBUG) Log.d(TAG, "Skipping animation " + isVisibleToUser() + " " + mPosition
                    + " " + position);
            setIndex(position >> 1);
        }
        mPosition = position;
    }

    private void setIndex(int index) {
        final int N = getChildCount();
        for (int i = 0; i < N; i++) {
            ImageView v = (ImageView) getChildAt(i);
            // Clear out any animation positioning.
            v.setTranslationX(0);
            v.setImageResource(R.drawable.major_a_b);
            v.setAlpha(getAlpha(i == index));
        }
    }

    private void animate(int from, int to) {
        if (DEBUG) Log.d(TAG, "Animating from " + Integer.toHexString(from) + " to "
                + Integer.toHexString(to));
        int fromIndex = from >> 1;
        int toIndex = to >> 1;

        // Set the position of everything, then we will manually control the two views involved
        // in the animation.
        setIndex(fromIndex);

        boolean fromTransition = (from & 1) != 0;
        boolean isAState = fromTransition ? from > to : from < to;
        int firstIndex = Math.min(fromIndex, toIndex);
        int secondIndex = Math.max(fromIndex, toIndex);
        if (secondIndex == firstIndex) {
            secondIndex++;
        }
        ImageView first = (ImageView) getChildAt(firstIndex);
        ImageView second = (ImageView) getChildAt(secondIndex);
        if (first == null || second == null) {
            // may happen during reInflation or other weird cases
            return;
        }
        // Lay the two views on top of each other.
        second.setTranslationX(first.getX() - second.getX());

        playAnimation(first, getTransition(fromTransition, isAState, false));
        first.setAlpha(getAlpha(false));

        playAnimation(second, getTransition(fromTransition, isAState, true));
        second.setAlpha(getAlpha(true));

        mAnimating = true;
    }

    private float getAlpha(boolean isMajor) {
        return isMajor ? 1 : MINOR_ALPHA;
    }

    private void playAnimation(ImageView imageView, int res) {
        final AnimatedVectorDrawable avd = (AnimatedVectorDrawable) getContext().getDrawable(res);
        imageView.setImageDrawable(avd);
        avd.forceAnimationOnUI();
        avd.registerAnimationCallback(mAnimationCallback);
        avd.start();
    }

    private int getTransition(boolean fromB, boolean isMajorAState, boolean isMajor) {
        if (isMajor) {
            if (fromB) {
                if (isMajorAState) {
                    return R.drawable.major_b_a_animation;
                } else {
                    return R.drawable.major_b_c_animation;
                }
            } else {
                if (isMajorAState) {
                    return R.drawable.major_a_b_animation;
                } else {
                    return R.drawable.major_c_b_animation;
                }
            }
        } else {
            if (fromB) {
                if (isMajorAState) {
                    return R.drawable.minor_b_c_animation;
                } else {
                    return R.drawable.minor_b_a_animation;
                }
            } else {
                if (isMajorAState) {
                    return R.drawable.minor_c_b_animation;
                } else {
                    return R.drawable.minor_a_b_animation;
                }
            }
        }
    }

    private int calculateWidth(int numPages) {
        return (mPageIndicatorWidth - mPageDotWidth) * (numPages - 1) + mPageDotWidth;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int N = getChildCount();
        if (N == 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        final int widthChildSpec = MeasureSpec.makeMeasureSpec(mPageIndicatorWidth,
                MeasureSpec.EXACTLY);
        final int heightChildSpec = MeasureSpec.makeMeasureSpec(mPageIndicatorHeight,
                MeasureSpec.EXACTLY);
        for (int i = 0; i < N; i++) {
            getChildAt(i).measure(widthChildSpec, heightChildSpec);
        }
        int width = calculateWidth(N);
        setMeasuredDimension(width, mPageIndicatorHeight);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        final int N = getChildCount();
        if (N == 0) {
            return;
        }
        for (int i = 0; i < N; i++) {
            int left = (mPageIndicatorWidth - mPageDotWidth) * i;
            getChildAt(i).layout(left, 0, mPageIndicatorWidth + left, mPageIndicatorHeight);
        }
    }

    void setPageScrollActionListener(PageScrollActionListener listener) {
        mPageScrollActionListener = listener;
    }

    interface PageScrollActionListener {

        @IntDef({LEFT, RIGHT})
        @interface Direction { }

        int LEFT = 0;
        int RIGHT = 1;

        void onScrollActionTriggered(@Direction int swipeDirection);
    }
}
