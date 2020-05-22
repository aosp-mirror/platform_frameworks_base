package com.android.systemui.qs;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.android.systemui.R;

import java.util.ArrayList;

public class PageIndicator extends ViewGroup {

    private static final String TAG = "PageIndicator";
    private static final boolean DEBUG = false;

    private static final long ANIMATION_DURATION = 250;

    // The size of a single dot in relation to the whole animation.
    private static final float SINGLE_SCALE = .4f;

    private static final float MINOR_ALPHA = .42f;

    private final ArrayList<Integer> mQueuedPositions = new ArrayList<>();

    private final int mPageIndicatorWidth;
    private final int mPageIndicatorHeight;
    private final int mPageDotWidth;

    private int mPosition = -1;
    private boolean mAnimating;

    public PageIndicator(Context context, AttributeSet attrs) {
        super(context, attrs);
        mPageIndicatorWidth =
                (int) mContext.getResources().getDimension(R.dimen.qs_page_indicator_width);
        mPageIndicatorHeight =
                (int) mContext.getResources().getDimension(R.dimen.qs_page_indicator_height);
        mPageDotWidth = (int) (mPageIndicatorWidth * SINGLE_SCALE);
    }

    public void setNumPages(int numPages) {
        TypedArray array = getContext().obtainStyledAttributes(
                new int[]{android.R.attr.colorControlActivated});
        int color = array.getColor(0, 0);
        array.recycle();
        setNumPages(numPages, color);
    }

    /** Oveload of setNumPages that allows the indicator color to be specified.*/
    public void setNumPages(int numPages, int color) {
        setVisibility(numPages > 1 ? View.VISIBLE : View.GONE);
        if (mAnimating) {
            Log.w(TAG, "setNumPages during animation");
        }
        while (numPages < getChildCount()) {
            removeViewAt(getChildCount() - 1);
        }
        while (numPages > getChildCount()) {
            ImageView v = new ImageView(mContext);
            v.setImageResource(R.drawable.minor_a_b);
            v.setImageTintList(ColorStateList.valueOf(color));
            addView(v, new LayoutParams(mPageIndicatorWidth, mPageIndicatorHeight));
        }
        // Refresh state.
        setIndex(mPosition >> 1);
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
        avd.start();
        // TODO: Figure out how to user an AVD animation callback instead, which doesn't
        // seem to be working right now...
        postDelayed(mAnimationDone, ANIMATION_DURATION);
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
        int width = (mPageIndicatorWidth - mPageDotWidth) * (N - 1) + mPageDotWidth;
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

    private final Runnable mAnimationDone = new Runnable() {
        @Override
        public void run() {
            if (DEBUG) Log.d(TAG, "onAnimationEnd - queued: " + mQueuedPositions.size());
            mAnimating = false;
            if (mQueuedPositions.size() != 0) {
                setPosition(mQueuedPositions.remove(0));
            }
        }
    };
}
