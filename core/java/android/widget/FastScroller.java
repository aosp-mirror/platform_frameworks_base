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

package android.widget;

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.annotation.StyleRes;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.TextUtils.TruncateAt;
import android.util.IntProperty;
import android.util.MathUtils;
import android.util.Property;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.ViewConfiguration;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewGroupOverlay;
import android.widget.AbsListView.OnScrollListener;
import android.widget.ImageView.ScaleType;

import com.android.internal.R;

/**
 * Helper class for AbsListView to draw and control the Fast Scroll thumb
 */
class FastScroller {
    /** Duration of fade-out animation. */
    private static final int DURATION_FADE_OUT = 300;

    /** Duration of fade-in animation. */
    private static final int DURATION_FADE_IN = 150;

    /** Duration of transition cross-fade animation. */
    private static final int DURATION_CROSS_FADE = 50;

    /** Duration of transition resize animation. */
    private static final int DURATION_RESIZE = 100;

    /** Inactivity timeout before fading controls. */
    private static final long FADE_TIMEOUT = 1500;

    /** Minimum number of pages to justify showing a fast scroll thumb. */
    private static final int MIN_PAGES = 4;

    /** Scroll thumb and preview not showing. */
    private static final int STATE_NONE = 0;

    /** Scroll thumb visible and moving along with the scrollbar. */
    private static final int STATE_VISIBLE = 1;

    /** Scroll thumb and preview being dragged by user. */
    private static final int STATE_DRAGGING = 2;

    // Positions for preview image and text.
    private static final int OVERLAY_FLOATING = 0;
    private static final int OVERLAY_AT_THUMB = 1;
    private static final int OVERLAY_ABOVE_THUMB = 2;

    // Positions for thumb in relation to track.
    private static final int THUMB_POSITION_MIDPOINT = 0;
    private static final int THUMB_POSITION_INSIDE = 1;

    // Indices for mPreviewResId.
    private static final int PREVIEW_LEFT = 0;
    private static final int PREVIEW_RIGHT = 1;

    /** Delay before considering a tap in the thumb area to be a drag. */
    private static final long TAP_TIMEOUT = ViewConfiguration.getTapTimeout();

    private final Rect mTempBounds = new Rect();
    private final Rect mTempMargins = new Rect();
    private final Rect mContainerRect = new Rect();

    private final AbsListView mList;
    private final ViewGroupOverlay mOverlay;
    private final TextView mPrimaryText;
    private final TextView mSecondaryText;
    private final ImageView mThumbImage;
    private final ImageView mTrackImage;
    private final View mPreviewImage;
    /**
     * Preview image resource IDs for left- and right-aligned layouts. See
     * {@link #PREVIEW_LEFT} and {@link #PREVIEW_RIGHT}.
     */
    private final int[] mPreviewResId = new int[2];

    /** The minimum touch target size in pixels. */
    private final int mMinimumTouchTarget;

    /**
     * Padding in pixels around the preview text. Applied as layout margins to
     * the preview text and padding to the preview image.
     */
    private int mPreviewPadding;

    private int mPreviewMinWidth;
    private int mPreviewMinHeight;
    private int mThumbMinWidth;
    private int mThumbMinHeight;

    /** Theme-specified text size. Used only if text appearance is not set. */
    private float mTextSize;

    /** Theme-specified text color. Used only if text appearance is not set. */
    private ColorStateList mTextColor;

    private Drawable mThumbDrawable;
    private Drawable mTrackDrawable;
    private int mTextAppearance;
    private int mThumbPosition;

    // Used to convert between y-coordinate and thumb position within track.
    private float mThumbOffset;
    private float mThumbRange;

    /** Total width of decorations. */
    private int mWidth;

    /** Set containing decoration transition animations. */
    private AnimatorSet mDecorAnimation;

    /** Set containing preview text transition animations. */
    private AnimatorSet mPreviewAnimation;

    /** Whether the primary text is showing. */
    private boolean mShowingPrimary;

    /** Whether we're waiting for completion of scrollTo(). */
    private boolean mScrollCompleted;

    /** The position of the first visible item in the list. */
    private int mFirstVisibleItem;

    /** The number of headers at the top of the view. */
    private int mHeaderCount;

    /** The index of the current section. */
    private int mCurrentSection = -1;

    /** The current scrollbar position. */
    private int mScrollbarPosition = -1;

    /** Whether the list is long enough to need a fast scroller. */
    private boolean mLongList;

    private Object[] mSections;

    /** Whether this view is currently performing layout. */
    private boolean mUpdatingLayout;

    /**
     * Current decoration state, one of:
     * <ul>
     * <li>{@link #STATE_NONE}, nothing visible
     * <li>{@link #STATE_VISIBLE}, showing track and thumb
     * <li>{@link #STATE_DRAGGING}, visible and showing preview
     * </ul>
     */
    private int mState;

    /** Whether the preview image is visible. */
    private boolean mShowingPreview;

    private Adapter mListAdapter;
    private SectionIndexer mSectionIndexer;

    /** Whether decorations should be laid out from right to left. */
    private boolean mLayoutFromRight;

    /** Whether the fast scroller is enabled. */
    private boolean mEnabled;

    /** Whether the scrollbar and decorations should always be shown. */
    private boolean mAlwaysShow;

    /**
     * Position for the preview image and text. One of:
     * <ul>
     * <li>{@link #OVERLAY_FLOATING}
     * <li>{@link #OVERLAY_AT_THUMB}
     * <li>{@link #OVERLAY_ABOVE_THUMB}
     * </ul>
     */
    private int mOverlayPosition;

    /** Current scrollbar style, including inset and overlay properties. */
    private int mScrollBarStyle;

    /** Whether to precisely match the thumb position to the list. */
    private boolean mMatchDragPosition;

    private float mInitialTouchY;
    private long mPendingDrag = -1;
    private int mScaledTouchSlop;

    private int mOldItemCount;
    private int mOldChildCount;

    /**
     * Used to delay hiding fast scroll decorations.
     */
    private final Runnable mDeferHide = new Runnable() {
        @Override
        public void run() {
            setState(STATE_NONE);
        }
    };

    /**
     * Used to effect a transition from primary to secondary text.
     */
    private final AnimatorListener mSwitchPrimaryListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            mShowingPrimary = !mShowingPrimary;
        }
    };

    public FastScroller(AbsListView listView, int styleResId) {
        mList = listView;
        mOldItemCount = listView.getCount();
        mOldChildCount = listView.getChildCount();

        final Context context = listView.getContext();
        mScaledTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mScrollBarStyle = listView.getScrollBarStyle();

        mScrollCompleted = true;
        mState = STATE_VISIBLE;
        mMatchDragPosition =
                context.getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.HONEYCOMB;

        mTrackImage = new ImageView(context);
        mTrackImage.setScaleType(ScaleType.FIT_XY);
        mThumbImage = new ImageView(context);
        mThumbImage.setScaleType(ScaleType.FIT_XY);
        mPreviewImage = new View(context);
        mPreviewImage.setAlpha(0f);

        mPrimaryText = createPreviewTextView(context);
        mSecondaryText = createPreviewTextView(context);

        mMinimumTouchTarget = listView.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.fast_scroller_minimum_touch_target);

        setStyle(styleResId);

        final ViewGroupOverlay overlay = listView.getOverlay();
        mOverlay = overlay;
        overlay.add(mTrackImage);
        overlay.add(mThumbImage);
        overlay.add(mPreviewImage);
        overlay.add(mPrimaryText);
        overlay.add(mSecondaryText);

        getSectionsFromIndexer();
        updateLongList(mOldChildCount, mOldItemCount);
        setScrollbarPosition(listView.getVerticalScrollbarPosition());
        postAutoHide();
    }

    private void updateAppearance() {
        int width = 0;

        // Add track to overlay if it has an image.
        mTrackImage.setImageDrawable(mTrackDrawable);
        if (mTrackDrawable != null) {
            width = Math.max(width, mTrackDrawable.getIntrinsicWidth());
        }

        // Add thumb to overlay if it has an image.
        mThumbImage.setImageDrawable(mThumbDrawable);
        mThumbImage.setMinimumWidth(mThumbMinWidth);
        mThumbImage.setMinimumHeight(mThumbMinHeight);
        if (mThumbDrawable != null) {
            width = Math.max(width, mThumbDrawable.getIntrinsicWidth());
        }

        // Account for minimum thumb width.
        mWidth = Math.max(width, mThumbMinWidth);

        if (mTextAppearance != 0) {
            mPrimaryText.setTextAppearance(mTextAppearance);
            mSecondaryText.setTextAppearance(mTextAppearance);
        }

        if (mTextColor != null) {
            mPrimaryText.setTextColor(mTextColor);
            mSecondaryText.setTextColor(mTextColor);
        }

        if (mTextSize > 0) {
            mPrimaryText.setTextSize(TypedValue.COMPLEX_UNIT_PX, mTextSize);
            mSecondaryText.setTextSize(TypedValue.COMPLEX_UNIT_PX, mTextSize);
        }

        final int padding = mPreviewPadding;
        mPrimaryText.setIncludeFontPadding(false);
        mPrimaryText.setPadding(padding, padding, padding, padding);
        mSecondaryText.setIncludeFontPadding(false);
        mSecondaryText.setPadding(padding, padding, padding, padding);

        refreshDrawablePressedState();
    }

    public void setStyle(@StyleRes int resId) {
        final Context context = mList.getContext();
        final TypedArray ta = context.obtainStyledAttributes(null,
                R.styleable.FastScroll, R.attr.fastScrollStyle, resId);
        final int N = ta.getIndexCount();
        for (int i = 0; i < N; i++) {
            final int index = ta.getIndex(i);
            switch (index) {
                case R.styleable.FastScroll_position:
                    mOverlayPosition = ta.getInt(index, OVERLAY_FLOATING);
                    break;
                case R.styleable.FastScroll_backgroundLeft:
                    mPreviewResId[PREVIEW_LEFT] = ta.getResourceId(index, 0);
                    break;
                case R.styleable.FastScroll_backgroundRight:
                    mPreviewResId[PREVIEW_RIGHT] = ta.getResourceId(index, 0);
                    break;
                case R.styleable.FastScroll_thumbDrawable:
                    mThumbDrawable = ta.getDrawable(index);
                    break;
                case R.styleable.FastScroll_trackDrawable:
                    mTrackDrawable = ta.getDrawable(index);
                    break;
                case R.styleable.FastScroll_textAppearance:
                    mTextAppearance = ta.getResourceId(index, 0);
                    break;
                case R.styleable.FastScroll_textColor:
                    mTextColor = ta.getColorStateList(index);
                    break;
                case R.styleable.FastScroll_textSize:
                    mTextSize = ta.getDimensionPixelSize(index, 0);
                    break;
                case R.styleable.FastScroll_minWidth:
                    mPreviewMinWidth = ta.getDimensionPixelSize(index, 0);
                    break;
                case R.styleable.FastScroll_minHeight:
                    mPreviewMinHeight = ta.getDimensionPixelSize(index, 0);
                    break;
                case R.styleable.FastScroll_thumbMinWidth:
                    mThumbMinWidth = ta.getDimensionPixelSize(index, 0);
                    break;
                case R.styleable.FastScroll_thumbMinHeight:
                    mThumbMinHeight = ta.getDimensionPixelSize(index, 0);
                    break;
                case R.styleable.FastScroll_padding:
                    mPreviewPadding = ta.getDimensionPixelSize(index, 0);
                    break;
                case R.styleable.FastScroll_thumbPosition:
                    mThumbPosition = ta.getInt(index, THUMB_POSITION_MIDPOINT);
                    break;
            }
        }
        ta.recycle();

        updateAppearance();
    }

    /**
     * Removes this FastScroller overlay from the host view.
     */
    public void remove() {
        mOverlay.remove(mTrackImage);
        mOverlay.remove(mThumbImage);
        mOverlay.remove(mPreviewImage);
        mOverlay.remove(mPrimaryText);
        mOverlay.remove(mSecondaryText);
    }

    /**
     * @param enabled Whether the fast scroll thumb is enabled.
     */
    public void setEnabled(boolean enabled) {
        if (mEnabled != enabled) {
            mEnabled = enabled;

            onStateDependencyChanged(true);
        }
    }

    /**
     * @return Whether the fast scroll thumb is enabled.
     */
    public boolean isEnabled() {
        return mEnabled && (mLongList || mAlwaysShow);
    }

    /**
     * @param alwaysShow Whether the fast scroll thumb should always be shown
     */
    public void setAlwaysShow(boolean alwaysShow) {
        if (mAlwaysShow != alwaysShow) {
            mAlwaysShow = alwaysShow;

            onStateDependencyChanged(false);
        }
    }

    /**
     * @return Whether the fast scroll thumb will always be shown
     * @see #setAlwaysShow(boolean)
     */
    public boolean isAlwaysShowEnabled() {
        return mAlwaysShow;
    }

    /**
     * Called when one of the variables affecting enabled state changes.
     *
     * @param peekIfEnabled whether the thumb should peek, if enabled
     */
    private void onStateDependencyChanged(boolean peekIfEnabled) {
        if (isEnabled()) {
            if (isAlwaysShowEnabled()) {
                setState(STATE_VISIBLE);
            } else if (mState == STATE_VISIBLE) {
                postAutoHide();
            } else if (peekIfEnabled) {
                setState(STATE_VISIBLE);
                postAutoHide();
            }
        } else {
            stop();
        }

        mList.resolvePadding();
    }

    public void setScrollBarStyle(int style) {
        if (mScrollBarStyle != style) {
            mScrollBarStyle = style;

            updateLayout();
        }
    }

    /**
     * Immediately transitions the fast scroller decorations to a hidden state.
     */
    public void stop() {
        setState(STATE_NONE);
    }

    public void setScrollbarPosition(int position) {
        if (position == View.SCROLLBAR_POSITION_DEFAULT) {
            position = mList.isLayoutRtl() ?
                    View.SCROLLBAR_POSITION_LEFT : View.SCROLLBAR_POSITION_RIGHT;
        }

        if (mScrollbarPosition != position) {
            mScrollbarPosition = position;
            mLayoutFromRight = position != View.SCROLLBAR_POSITION_LEFT;

            final int previewResId = mPreviewResId[mLayoutFromRight ? PREVIEW_RIGHT : PREVIEW_LEFT];
            mPreviewImage.setBackgroundResource(previewResId);

            // Propagate padding to text min width/height.
            final int textMinWidth = Math.max(0, mPreviewMinWidth - mPreviewImage.getPaddingLeft()
                    - mPreviewImage.getPaddingRight());
            mPrimaryText.setMinimumWidth(textMinWidth);
            mSecondaryText.setMinimumWidth(textMinWidth);

            final int textMinHeight = Math.max(0, mPreviewMinHeight - mPreviewImage.getPaddingTop()
                    - mPreviewImage.getPaddingBottom());
            mPrimaryText.setMinimumHeight(textMinHeight);
            mSecondaryText.setMinimumHeight(textMinHeight);

            // Requires re-layout.
            updateLayout();
        }
    }

    public int getWidth() {
        return mWidth;
    }

    public void onSizeChanged(int w, int h, int oldw, int oldh) {
        updateLayout();
    }

    public void onItemCountChanged(int childCount, int itemCount) {
        if (mOldItemCount != itemCount || mOldChildCount != childCount) {
            mOldItemCount = itemCount;
            mOldChildCount = childCount;

            final boolean hasMoreItems = itemCount - childCount > 0;
            if (hasMoreItems && mState != STATE_DRAGGING) {
                final int firstVisibleItem = mList.getFirstVisiblePosition();
                setThumbPos(getPosFromItemCount(firstVisibleItem, childCount, itemCount));
            }

            updateLongList(childCount, itemCount);
        }
    }

    private void updateLongList(int childCount, int itemCount) {
        final boolean longList = childCount > 0 && itemCount / childCount >= MIN_PAGES;
        if (mLongList != longList) {
            mLongList = longList;

            onStateDependencyChanged(false);
        }
    }

    /**
     * Creates a view into which preview text can be placed.
     */
    private TextView createPreviewTextView(Context context) {
        final LayoutParams params = new LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        final TextView textView = new TextView(context);
        textView.setLayoutParams(params);
        textView.setSingleLine(true);
        textView.setEllipsize(TruncateAt.MIDDLE);
        textView.setGravity(Gravity.CENTER);
        textView.setAlpha(0f);

        // Manually propagate inherited layout direction.
        textView.setLayoutDirection(mList.getLayoutDirection());

        return textView;
    }

    /**
     * Measures and layouts the scrollbar and decorations.
     */
    public void updateLayout() {
        // Prevent re-entry when RTL properties change as a side-effect of
        // resolving padding.
        if (mUpdatingLayout) {
            return;
        }

        mUpdatingLayout = true;

        updateContainerRect();

        layoutThumb();
        layoutTrack();

        updateOffsetAndRange();

        final Rect bounds = mTempBounds;
        measurePreview(mPrimaryText, bounds);
        applyLayout(mPrimaryText, bounds);
        measurePreview(mSecondaryText, bounds);
        applyLayout(mSecondaryText, bounds);

        if (mPreviewImage != null) {
            // Apply preview image padding.
            bounds.left -= mPreviewImage.getPaddingLeft();
            bounds.top -= mPreviewImage.getPaddingTop();
            bounds.right += mPreviewImage.getPaddingRight();
            bounds.bottom += mPreviewImage.getPaddingBottom();
            applyLayout(mPreviewImage, bounds);
        }

        mUpdatingLayout = false;
    }

    /**
     * Layouts a view within the specified bounds and pins the pivot point to
     * the appropriate edge.
     *
     * @param view The view to layout.
     * @param bounds Bounds at which to layout the view.
     */
    private void applyLayout(View view, Rect bounds) {
        view.layout(bounds.left, bounds.top, bounds.right, bounds.bottom);
        view.setPivotX(mLayoutFromRight ? bounds.right - bounds.left : 0);
    }

    /**
     * Measures the preview text bounds, taking preview image padding into
     * account. This method should only be called after {@link #layoutThumb()}
     * and {@link #layoutTrack()} have both been called at least once.
     *
     * @param v The preview text view to measure.
     * @param out Rectangle into which measured bounds are placed.
     */
    private void measurePreview(View v, Rect out) {
        // Apply the preview image's padding as layout margins.
        final Rect margins = mTempMargins;
        margins.left = mPreviewImage.getPaddingLeft();
        margins.top = mPreviewImage.getPaddingTop();
        margins.right = mPreviewImage.getPaddingRight();
        margins.bottom = mPreviewImage.getPaddingBottom();

        if (mOverlayPosition == OVERLAY_FLOATING) {
            measureFloating(v, margins, out);
        } else {
            measureViewToSide(v, mThumbImage, margins, out);
        }
    }

    /**
     * Measures the bounds for a view that should be laid out against the edge
     * of an adjacent view. If no adjacent view is provided, lays out against
     * the list edge.
     *
     * @param view The view to measure for layout.
     * @param adjacent (Optional) The adjacent view, may be null to align to the
     *            list edge.
     * @param margins Layout margins to apply to the view.
     * @param out Rectangle into which measured bounds are placed.
     */
    private void measureViewToSide(View view, View adjacent, Rect margins, Rect out) {
        final int marginLeft;
        final int marginTop;
        final int marginRight;
        if (margins == null) {
            marginLeft = 0;
            marginTop = 0;
            marginRight = 0;
        } else {
            marginLeft = margins.left;
            marginTop = margins.top;
            marginRight = margins.right;
        }

        final Rect container = mContainerRect;
        final int containerWidth = container.width();
        final int maxWidth;
        if (adjacent == null) {
            maxWidth = containerWidth;
        } else if (mLayoutFromRight) {
            maxWidth = adjacent.getLeft();
        } else {
            maxWidth = containerWidth - adjacent.getRight();
        }

        final int adjMaxHeight = Math.max(0, container.height());
        final int adjMaxWidth = Math.max(0, maxWidth - marginLeft - marginRight);
        final int widthMeasureSpec = MeasureSpec.makeMeasureSpec(adjMaxWidth, MeasureSpec.AT_MOST);
        final int heightMeasureSpec = MeasureSpec.makeSafeMeasureSpec(
                adjMaxHeight, MeasureSpec.UNSPECIFIED);
        view.measure(widthMeasureSpec, heightMeasureSpec);

        // Align to the left or right.
        final int width = Math.min(adjMaxWidth, view.getMeasuredWidth());
        final int left;
        final int right;
        if (mLayoutFromRight) {
            right = (adjacent == null ? container.right : adjacent.getLeft()) - marginRight;
            left = right - width;
        } else {
            left = (adjacent == null ? container.left : adjacent.getRight()) + marginLeft;
            right = left + width;
        }

        // Don't adjust the vertical position.
        final int top = marginTop;
        final int bottom = top + view.getMeasuredHeight();
        out.set(left, top, right, bottom);
    }

    private void measureFloating(View preview, Rect margins, Rect out) {
        final int marginLeft;
        final int marginTop;
        final int marginRight;
        if (margins == null) {
            marginLeft = 0;
            marginTop = 0;
            marginRight = 0;
        } else {
            marginLeft = margins.left;
            marginTop = margins.top;
            marginRight = margins.right;
        }

        final Rect container = mContainerRect;
        final int containerWidth = container.width();
        final int adjMaxHeight = Math.max(0, container.height());
        final int adjMaxWidth = Math.max(0, containerWidth - marginLeft - marginRight);
        final int widthMeasureSpec = MeasureSpec.makeMeasureSpec(adjMaxWidth, MeasureSpec.AT_MOST);
        final int heightMeasureSpec = MeasureSpec.makeSafeMeasureSpec(
                adjMaxHeight, MeasureSpec.UNSPECIFIED);
        preview.measure(widthMeasureSpec, heightMeasureSpec);

        // Align at the vertical center, 10% from the top.
        final int containerHeight = container.height();
        final int width = preview.getMeasuredWidth();
        final int top = containerHeight / 10 + marginTop + container.top;
        final int bottom = top + preview.getMeasuredHeight();
        final int left = (containerWidth - width) / 2 + container.left;
        final int right = left + width;
        out.set(left, top, right, bottom);
    }

    /**
     * Updates the container rectangle used for layout.
     */
    private void updateContainerRect() {
        final AbsListView list = mList;
        list.resolvePadding();

        final Rect container = mContainerRect;
        container.left = 0;
        container.top = 0;
        container.right = list.getWidth();
        container.bottom = list.getHeight();

        final int scrollbarStyle = mScrollBarStyle;
        if (scrollbarStyle == View.SCROLLBARS_INSIDE_INSET
                || scrollbarStyle == View.SCROLLBARS_INSIDE_OVERLAY) {
            container.left += list.getPaddingLeft();
            container.top += list.getPaddingTop();
            container.right -= list.getPaddingRight();
            container.bottom -= list.getPaddingBottom();

            // In inset mode, we need to adjust for padded scrollbar width.
            if (scrollbarStyle == View.SCROLLBARS_INSIDE_INSET) {
                final int width = getWidth();
                if (mScrollbarPosition == View.SCROLLBAR_POSITION_RIGHT) {
                    container.right += width;
                } else {
                    container.left -= width;
                }
            }
        }
    }

    /**
     * Lays out the thumb according to the current scrollbar position.
     */
    private void layoutThumb() {
        final Rect bounds = mTempBounds;
        measureViewToSide(mThumbImage, null, null, bounds);
        applyLayout(mThumbImage, bounds);
    }

    /**
     * Lays out the track centered on the thumb. Must be called after
     * {@link #layoutThumb}.
     */
    private void layoutTrack() {
        final View track = mTrackImage;
        final View thumb = mThumbImage;
        final Rect container = mContainerRect;
        final int maxWidth = Math.max(0, container.width());
        final int maxHeight = Math.max(0, container.height());
        final int widthMeasureSpec = MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST);
        final int heightMeasureSpec = MeasureSpec.makeSafeMeasureSpec(
                maxHeight, MeasureSpec.UNSPECIFIED);
        track.measure(widthMeasureSpec, heightMeasureSpec);

        final int top;
        final int bottom;
        if (mThumbPosition == THUMB_POSITION_INSIDE) {
            top = container.top;
            bottom = container.bottom;
        } else {
            final int thumbHalfHeight = thumb.getHeight() / 2;
            top = container.top + thumbHalfHeight;
            bottom = container.bottom - thumbHalfHeight;
        }

        final int trackWidth = track.getMeasuredWidth();
        final int left = thumb.getLeft() + (thumb.getWidth() - trackWidth) / 2;
        final int right = left + trackWidth;
        track.layout(left, top, right, bottom);
    }

    /**
     * Updates the offset and range used to convert from absolute y-position to
     * thumb position within the track.
     */
    private void updateOffsetAndRange() {
        final View trackImage = mTrackImage;
        final View thumbImage = mThumbImage;
        final float min;
        final float max;
        if (mThumbPosition == THUMB_POSITION_INSIDE) {
            final float halfThumbHeight = thumbImage.getHeight() / 2f;
            min = trackImage.getTop() + halfThumbHeight;
            max = trackImage.getBottom() - halfThumbHeight;
        } else{
            min = trackImage.getTop();
            max = trackImage.getBottom();
        }

        mThumbOffset = min;
        mThumbRange = max - min;
    }

    private void setState(int state) {
        mList.removeCallbacks(mDeferHide);

        if (mAlwaysShow && state == STATE_NONE) {
            state = STATE_VISIBLE;
        }

        if (state == mState) {
            return;
        }

        switch (state) {
            case STATE_NONE:
                transitionToHidden();
                break;
            case STATE_VISIBLE:
                transitionToVisible();
                break;
            case STATE_DRAGGING:
                if (transitionPreviewLayout(mCurrentSection)) {
                    transitionToDragging();
                } else {
                    transitionToVisible();
                }
                break;
        }

        mState = state;

        refreshDrawablePressedState();
    }

    private void refreshDrawablePressedState() {
        final boolean isPressed = mState == STATE_DRAGGING;
        mThumbImage.setPressed(isPressed);
        mTrackImage.setPressed(isPressed);
    }

    /**
     * Shows nothing.
     */
    private void transitionToHidden() {
        if (mDecorAnimation != null) {
            mDecorAnimation.cancel();
        }

        final Animator fadeOut = groupAnimatorOfFloat(View.ALPHA, 0f, mThumbImage, mTrackImage,
                mPreviewImage, mPrimaryText, mSecondaryText).setDuration(DURATION_FADE_OUT);

        // Push the thumb and track outside the list bounds.
        final float offset = mLayoutFromRight ? mThumbImage.getWidth() : -mThumbImage.getWidth();
        final Animator slideOut = groupAnimatorOfFloat(
                View.TRANSLATION_X, offset, mThumbImage, mTrackImage)
                .setDuration(DURATION_FADE_OUT);

        mDecorAnimation = new AnimatorSet();
        mDecorAnimation.playTogether(fadeOut, slideOut);
        mDecorAnimation.start();

        mShowingPreview = false;
    }

    /**
     * Shows the thumb and track.
     */
    private void transitionToVisible() {
        if (mDecorAnimation != null) {
            mDecorAnimation.cancel();
        }

        final Animator fadeIn = groupAnimatorOfFloat(View.ALPHA, 1f, mThumbImage, mTrackImage)
                .setDuration(DURATION_FADE_IN);
        final Animator fadeOut = groupAnimatorOfFloat(
                View.ALPHA, 0f, mPreviewImage, mPrimaryText, mSecondaryText)
                .setDuration(DURATION_FADE_OUT);
        final Animator slideIn = groupAnimatorOfFloat(
                View.TRANSLATION_X, 0f, mThumbImage, mTrackImage).setDuration(DURATION_FADE_IN);

        mDecorAnimation = new AnimatorSet();
        mDecorAnimation.playTogether(fadeIn, fadeOut, slideIn);
        mDecorAnimation.start();

        mShowingPreview = false;
    }

    /**
     * Shows the thumb, preview, and track.
     */
    private void transitionToDragging() {
        if (mDecorAnimation != null) {
            mDecorAnimation.cancel();
        }

        final Animator fadeIn = groupAnimatorOfFloat(
                View.ALPHA, 1f, mThumbImage, mTrackImage, mPreviewImage)
                .setDuration(DURATION_FADE_IN);
        final Animator slideIn = groupAnimatorOfFloat(
                View.TRANSLATION_X, 0f, mThumbImage, mTrackImage).setDuration(DURATION_FADE_IN);

        mDecorAnimation = new AnimatorSet();
        mDecorAnimation.playTogether(fadeIn, slideIn);
        mDecorAnimation.start();

        mShowingPreview = true;
    }

    private void postAutoHide() {
        mList.removeCallbacks(mDeferHide);
        mList.postDelayed(mDeferHide, FADE_TIMEOUT);
    }

    public void onScroll(int firstVisibleItem, int visibleItemCount, int totalItemCount) {
        if (!isEnabled()) {
            setState(STATE_NONE);
            return;
        }

        final boolean hasMoreItems = totalItemCount - visibleItemCount > 0;
        if (hasMoreItems && mState != STATE_DRAGGING) {
            setThumbPos(getPosFromItemCount(firstVisibleItem, visibleItemCount, totalItemCount));
        }

        mScrollCompleted = true;

        if (mFirstVisibleItem != firstVisibleItem) {
            mFirstVisibleItem = firstVisibleItem;

            // Show the thumb, if necessary, and set up auto-fade.
            if (mState != STATE_DRAGGING) {
                setState(STATE_VISIBLE);
                postAutoHide();
            }
        }
    }

    private void getSectionsFromIndexer() {
        mSectionIndexer = null;

        Adapter adapter = mList.getAdapter();
        if (adapter instanceof HeaderViewListAdapter) {
            mHeaderCount = ((HeaderViewListAdapter) adapter).getHeadersCount();
            adapter = ((HeaderViewListAdapter) adapter).getWrappedAdapter();
        }

        if (adapter instanceof ExpandableListConnector) {
            final ExpandableListAdapter expAdapter = ((ExpandableListConnector) adapter)
                    .getAdapter();
            if (expAdapter instanceof SectionIndexer) {
                mSectionIndexer = (SectionIndexer) expAdapter;
                mListAdapter = adapter;
                mSections = mSectionIndexer.getSections();
            }
        } else if (adapter instanceof SectionIndexer) {
            mListAdapter = adapter;
            mSectionIndexer = (SectionIndexer) adapter;
            mSections = mSectionIndexer.getSections();
        } else {
            mListAdapter = adapter;
            mSections = null;
        }
    }

    public void onSectionsChanged() {
        mListAdapter = null;
    }

    /**
     * Scrolls to a specific position within the section
     * @param position
     */
    private void scrollTo(float position) {
        mScrollCompleted = false;

        final int count = mList.getCount();
        final Object[] sections = mSections;
        final int sectionCount = sections == null ? 0 : sections.length;
        int sectionIndex;
        if (sections != null && sectionCount > 1) {
            final int exactSection = MathUtils.constrain(
                    (int) (position * sectionCount), 0, sectionCount - 1);
            int targetSection = exactSection;
            int targetIndex = mSectionIndexer.getPositionForSection(targetSection);
            sectionIndex = targetSection;

            // Given the expected section and index, the following code will
            // try to account for missing sections (no names starting with..)
            // It will compute the scroll space of surrounding empty sections
            // and interpolate the currently visible letter's range across the
            // available space, so that there is always some list movement while
            // the user moves the thumb.
            int nextIndex = count;
            int prevIndex = targetIndex;
            int prevSection = targetSection;
            int nextSection = targetSection + 1;

            // Assume the next section is unique
            if (targetSection < sectionCount - 1) {
                nextIndex = mSectionIndexer.getPositionForSection(targetSection + 1);
            }

            // Find the previous index if we're slicing the previous section
            if (nextIndex == targetIndex) {
                // Non-existent letter
                while (targetSection > 0) {
                    targetSection--;
                    prevIndex = mSectionIndexer.getPositionForSection(targetSection);
                    if (prevIndex != targetIndex) {
                        prevSection = targetSection;
                        sectionIndex = targetSection;
                        break;
                    } else if (targetSection == 0) {
                        // When section reaches 0 here, sectionIndex must follow it.
                        // Assuming mSectionIndexer.getPositionForSection(0) == 0.
                        sectionIndex = 0;
                        break;
                    }
                }
            }

            // Find the next index, in case the assumed next index is not
            // unique. For instance, if there is no P, then request for P's
            // position actually returns Q's. So we need to look ahead to make
            // sure that there is really a Q at Q's position. If not, move
            // further down...
            int nextNextSection = nextSection + 1;
            while (nextNextSection < sectionCount &&
                    mSectionIndexer.getPositionForSection(nextNextSection) == nextIndex) {
                nextNextSection++;
                nextSection++;
            }

            // Compute the beginning and ending scroll range percentage of the
            // currently visible section. This could be equal to or greater than
            // (1 / nSections). If the target position is near the previous
            // position, snap to the previous position.
            final float prevPosition = (float) prevSection / sectionCount;
            final float nextPosition = (float) nextSection / sectionCount;
            final float snapThreshold = (count == 0) ? Float.MAX_VALUE : .125f / count;
            if (prevSection == exactSection && position - prevPosition < snapThreshold) {
                targetIndex = prevIndex;
            } else {
                targetIndex = prevIndex + (int) ((nextIndex - prevIndex) * (position - prevPosition)
                    / (nextPosition - prevPosition));
            }

            // Clamp to valid positions.
            targetIndex = MathUtils.constrain(targetIndex, 0, count - 1);

            if (mList instanceof ExpandableListView) {
                final ExpandableListView expList = (ExpandableListView) mList;
                expList.setSelectionFromTop(expList.getFlatListPosition(
                        ExpandableListView.getPackedPositionForGroup(targetIndex + mHeaderCount)),
                        0);
            } else if (mList instanceof ListView) {
                ((ListView) mList).setSelectionFromTop(targetIndex + mHeaderCount, 0);
            } else {
                mList.setSelection(targetIndex + mHeaderCount);
            }
        } else {
            final int index = MathUtils.constrain((int) (position * count), 0, count - 1);

            if (mList instanceof ExpandableListView) {
                ExpandableListView expList = (ExpandableListView) mList;
                expList.setSelectionFromTop(expList.getFlatListPosition(
                        ExpandableListView.getPackedPositionForGroup(index + mHeaderCount)), 0);
            } else if (mList instanceof ListView) {
                ((ListView)mList).setSelectionFromTop(index + mHeaderCount, 0);
            } else {
                mList.setSelection(index + mHeaderCount);
            }

            sectionIndex = -1;
        }

        if (mCurrentSection != sectionIndex) {
            mCurrentSection = sectionIndex;

            final boolean hasPreview = transitionPreviewLayout(sectionIndex);
            if (!mShowingPreview && hasPreview) {
                transitionToDragging();
            } else if (mShowingPreview && !hasPreview) {
                transitionToVisible();
            }
        }
    }

    /**
     * Transitions the preview text to a new section. Handles animation,
     * measurement, and layout. If the new preview text is empty, returns false.
     *
     * @param sectionIndex The section index to which the preview should
     *            transition.
     * @return False if the new preview text is empty.
     */
    private boolean transitionPreviewLayout(int sectionIndex) {
        final Object[] sections = mSections;
        String text = null;
        if (sections != null && sectionIndex >= 0 && sectionIndex < sections.length) {
            final Object section = sections[sectionIndex];
            if (section != null) {
                text = section.toString();
            }
        }

        final Rect bounds = mTempBounds;
        final View preview = mPreviewImage;
        final TextView showing;
        final TextView target;
        if (mShowingPrimary) {
            showing = mPrimaryText;
            target = mSecondaryText;
        } else {
            showing = mSecondaryText;
            target = mPrimaryText;
        }

        // Set and layout target immediately.
        target.setText(text);
        measurePreview(target, bounds);
        applyLayout(target, bounds);

        if (mPreviewAnimation != null) {
            mPreviewAnimation.cancel();
        }

        // Cross-fade preview text.
        final Animator showTarget = animateAlpha(target, 1f).setDuration(DURATION_CROSS_FADE);
        final Animator hideShowing = animateAlpha(showing, 0f).setDuration(DURATION_CROSS_FADE);
        hideShowing.addListener(mSwitchPrimaryListener);

        // Apply preview image padding and animate bounds, if necessary.
        bounds.left -= preview.getPaddingLeft();
        bounds.top -= preview.getPaddingTop();
        bounds.right += preview.getPaddingRight();
        bounds.bottom += preview.getPaddingBottom();
        final Animator resizePreview = animateBounds(preview, bounds);
        resizePreview.setDuration(DURATION_RESIZE);

        mPreviewAnimation = new AnimatorSet();
        final AnimatorSet.Builder builder = mPreviewAnimation.play(hideShowing).with(showTarget);
        builder.with(resizePreview);

        // The current preview size is unaffected by hidden or showing. It's
        // used to set starting scales for things that need to be scaled down.
        final int previewWidth = preview.getWidth() - preview.getPaddingLeft()
                - preview.getPaddingRight();

        // If target is too large, shrink it immediately to fit and expand to
        // target size. Otherwise, start at target size.
        final int targetWidth = target.getWidth();
        if (targetWidth > previewWidth) {
            target.setScaleX((float) previewWidth / targetWidth);
            final Animator scaleAnim = animateScaleX(target, 1f).setDuration(DURATION_RESIZE);
            builder.with(scaleAnim);
        } else {
            target.setScaleX(1f);
        }

        // If showing is larger than target, shrink to target size.
        final int showingWidth = showing.getWidth();
        if (showingWidth > targetWidth) {
            final float scale = (float) targetWidth / showingWidth;
            final Animator scaleAnim = animateScaleX(showing, scale).setDuration(DURATION_RESIZE);
            builder.with(scaleAnim);
        }

        mPreviewAnimation.start();

        return !TextUtils.isEmpty(text);
    }

    /**
     * Positions the thumb and preview widgets.
     *
     * @param position The position, between 0 and 1, along the track at which
     *            to place the thumb.
     */
    private void setThumbPos(float position) {
        final float thumbMiddle = position * mThumbRange + mThumbOffset;
        mThumbImage.setTranslationY(thumbMiddle - mThumbImage.getHeight() / 2f);

        final View previewImage = mPreviewImage;
        final float previewHalfHeight = previewImage.getHeight() / 2f;
        final float previewPos;
        switch (mOverlayPosition) {
            case OVERLAY_AT_THUMB:
                previewPos = thumbMiddle;
                break;
            case OVERLAY_ABOVE_THUMB:
                previewPos = thumbMiddle - previewHalfHeight;
                break;
            case OVERLAY_FLOATING:
            default:
                previewPos = 0;
                break;
        }

        // Center the preview on the thumb, constrained to the list bounds.
        final Rect container = mContainerRect;
        final int top = container.top;
        final int bottom = container.bottom;
        final float minP = top + previewHalfHeight;
        final float maxP = bottom - previewHalfHeight;
        final float previewMiddle = MathUtils.constrain(previewPos, minP, maxP);
        final float previewTop = previewMiddle - previewHalfHeight;
        previewImage.setTranslationY(previewTop);

        mPrimaryText.setTranslationY(previewTop);
        mSecondaryText.setTranslationY(previewTop);
    }

    private float getPosFromMotionEvent(float y) {
        // If the list is the same height as the thumbnail or shorter,
        // effectively disable scrolling.
        if (mThumbRange <= 0) {
            return 0f;
        }

        return MathUtils.constrain((y - mThumbOffset) / mThumbRange, 0f, 1f);
    }

    /**
     * Calculates the thumb position based on the visible items.
     *
     * @param firstVisibleItem First visible item, >= 0.
     * @param visibleItemCount Number of visible items, >= 0.
     * @param totalItemCount Total number of items, >= 0.
     * @return
     */
    private float getPosFromItemCount(
            int firstVisibleItem, int visibleItemCount, int totalItemCount) {
        final SectionIndexer sectionIndexer = mSectionIndexer;
        if (sectionIndexer == null || mListAdapter == null) {
            getSectionsFromIndexer();
        }

        if (visibleItemCount == 0 || totalItemCount == 0) {
            // No items are visible.
            return 0;
        }

        final boolean hasSections = sectionIndexer != null && mSections != null
                && mSections.length > 0;
        if (!hasSections || !mMatchDragPosition) {
            if (visibleItemCount == totalItemCount) {
                // All items are visible.
                return 0;
            } else {
                return (float) firstVisibleItem / (totalItemCount - visibleItemCount);
            }
        }

        // Ignore headers.
        firstVisibleItem -= mHeaderCount;
        if (firstVisibleItem < 0) {
            return 0;
        }
        totalItemCount -= mHeaderCount;

        // Hidden portion of the first visible row.
        final View child = mList.getChildAt(0);
        final float incrementalPos;
        if (child == null || child.getHeight() == 0) {
            incrementalPos = 0;
        } else {
            incrementalPos = (float) (mList.getPaddingTop() - child.getTop()) / child.getHeight();
        }

        // Number of rows in this section.
        final int section = sectionIndexer.getSectionForPosition(firstVisibleItem);
        final int sectionPos = sectionIndexer.getPositionForSection(section);
        final int sectionCount = mSections.length;
        final int positionsInSection;
        if (section < sectionCount - 1) {
            final int nextSectionPos;
            if (section + 1 < sectionCount) {
                nextSectionPos = sectionIndexer.getPositionForSection(section + 1);
            } else {
                nextSectionPos = totalItemCount - 1;
            }
            positionsInSection = nextSectionPos - sectionPos;
        } else {
            positionsInSection = totalItemCount - sectionPos;
        }

        // Position within this section.
        final float posWithinSection;
        if (positionsInSection == 0) {
            posWithinSection = 0;
        } else {
            posWithinSection = (firstVisibleItem + incrementalPos - sectionPos)
                    / positionsInSection;
        }

        float result = (section + posWithinSection) / sectionCount;

        // Fake out the scroll bar for the last item. Since the section indexer
        // won't ever actually move the list in this end space, make scrolling
        // across the last item account for whatever space is remaining.
        if (firstVisibleItem > 0 && firstVisibleItem + visibleItemCount == totalItemCount) {
            final View lastChild = mList.getChildAt(visibleItemCount - 1);
            final int bottomPadding = mList.getPaddingBottom();
            final int maxSize;
            final int currentVisibleSize;
            if (mList.getClipToPadding()) {
                maxSize = lastChild.getHeight();
                currentVisibleSize = mList.getHeight() - bottomPadding - lastChild.getTop();
            } else {
                maxSize = lastChild.getHeight() + bottomPadding;
                currentVisibleSize = mList.getHeight() - lastChild.getTop();
            }
            if (currentVisibleSize > 0 && maxSize > 0) {
                result += (1 - result) * ((float) currentVisibleSize / maxSize );
            }
        }

        return result;
    }

    /**
     * Cancels an ongoing fling event by injecting a
     * {@link MotionEvent#ACTION_CANCEL} into the host view.
     */
    private void cancelFling() {
        final MotionEvent cancelFling = MotionEvent.obtain(
                0, 0, MotionEvent.ACTION_CANCEL, 0, 0, 0);
        mList.onTouchEvent(cancelFling);
        cancelFling.recycle();
    }

    /**
     * Cancels a pending drag.
     *
     * @see #startPendingDrag()
     */
    private void cancelPendingDrag() {
        mPendingDrag = -1;
    }

    /**
     * Delays dragging until after the framework has determined that the user is
     * scrolling, rather than tapping.
     */
    private void startPendingDrag() {
        mPendingDrag = SystemClock.uptimeMillis() + TAP_TIMEOUT;
    }

    private void beginDrag() {
        mPendingDrag = -1;

        setState(STATE_DRAGGING);

        if (mListAdapter == null && mList != null) {
            getSectionsFromIndexer();
        }

        if (mList != null) {
            mList.requestDisallowInterceptTouchEvent(true);
            mList.reportScrollStateChange(OnScrollListener.SCROLL_STATE_TOUCH_SCROLL);
        }

        cancelFling();
    }

    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (!isEnabled()) {
            return false;
        }

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (isPointInside(ev.getX(), ev.getY())) {
                    // If the parent has requested that its children delay
                    // pressed state (e.g. is a scrolling container) then we
                    // need to allow the parent time to decide whether it wants
                    // to intercept events. If it does, we will receive a CANCEL
                    // event.
                    if (!mList.isInScrollingContainer()) {
                        // This will get dispatched to onTouchEvent(). Start
                        // dragging there.
                        return true;
                    }

                    mInitialTouchY = ev.getY();
                    startPendingDrag();
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (!isPointInside(ev.getX(), ev.getY())) {
                    cancelPendingDrag();
                } else if (mPendingDrag >= 0 && mPendingDrag <= SystemClock.uptimeMillis()) {
                    beginDrag();

                    final float pos = getPosFromMotionEvent(mInitialTouchY);
                    scrollTo(pos);

                    // This may get dispatched to onTouchEvent(), but it
                    // doesn't really matter since we'll already be in a drag.
                    return onTouchEvent(ev);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                cancelPendingDrag();
                break;
        }

        return false;
    }

    public boolean onInterceptHoverEvent(MotionEvent ev) {
        if (!isEnabled()) {
            return false;
        }

        final int actionMasked = ev.getActionMasked();
        if ((actionMasked == MotionEvent.ACTION_HOVER_ENTER
                || actionMasked == MotionEvent.ACTION_HOVER_MOVE) && mState == STATE_NONE
                && isPointInside(ev.getX(), ev.getY())) {
            setState(STATE_VISIBLE);
            postAutoHide();
        }

        return false;
    }

    public PointerIcon onResolvePointerIcon(MotionEvent event, int pointerIndex) {
        if (mState == STATE_DRAGGING || isPointInside(event.getX(), event.getY())) {
            return PointerIcon.getSystemIcon(mList.getContext(), PointerIcon.TYPE_ARROW);
        }
        return null;
    }

    public boolean onTouchEvent(MotionEvent me) {
        if (!isEnabled()) {
            return false;
        }

        switch (me.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (isPointInside(me.getX(), me.getY())) {
                    if (!mList.isInScrollingContainer()) {
                        beginDrag();
                        return true;
                    }
                }
            } break;

            case MotionEvent.ACTION_UP: {
                if (mPendingDrag >= 0) {
                    // Allow a tap to scroll.
                    beginDrag();

                    final float pos = getPosFromMotionEvent(me.getY());
                    setThumbPos(pos);
                    scrollTo(pos);

                    // Will hit the STATE_DRAGGING check below
                }

                if (mState == STATE_DRAGGING) {
                    if (mList != null) {
                        // ViewGroup does the right thing already, but there might
                        // be other classes that don't properly reset on touch-up,
                        // so do this explicitly just in case.
                        mList.requestDisallowInterceptTouchEvent(false);
                        mList.reportScrollStateChange(OnScrollListener.SCROLL_STATE_IDLE);
                    }

                    setState(STATE_VISIBLE);
                    postAutoHide();

                    return true;
                }
            } break;

            case MotionEvent.ACTION_MOVE: {
                if (mPendingDrag >= 0 && Math.abs(me.getY() - mInitialTouchY) > mScaledTouchSlop) {
                    beginDrag();

                    // Will hit the STATE_DRAGGING check below
                }

                if (mState == STATE_DRAGGING) {
                    // TODO: Ignore jitter.
                    final float pos = getPosFromMotionEvent(me.getY());
                    setThumbPos(pos);

                    // If the previous scrollTo is still pending
                    if (mScrollCompleted) {
                        scrollTo(pos);
                    }

                    return true;
                }
            } break;

            case MotionEvent.ACTION_CANCEL: {
                cancelPendingDrag();
            } break;
        }

        return false;
    }

    /**
     * Returns whether a coordinate is inside the scroller's activation area. If
     * there is a track image, touching anywhere within the thumb-width of the
     * track activates scrolling. Otherwise, the user has to touch inside thumb
     * itself.
     *
     * @param x The x-coordinate.
     * @param y The y-coordinate.
     * @return Whether the coordinate is inside the scroller's activation area.
     */
    private boolean isPointInside(float x, float y) {
        return isPointInsideX(x) && (mTrackDrawable != null || isPointInsideY(y));
    }

    private boolean isPointInsideX(float x) {
        final float offset = mThumbImage.getTranslationX();
        final float left = mThumbImage.getLeft() + offset;
        final float right = mThumbImage.getRight() + offset;

        // Apply the minimum touch target size.
        final float targetSizeDiff = mMinimumTouchTarget - (right - left);
        final float adjust = targetSizeDiff > 0 ? targetSizeDiff : 0;

        if (mLayoutFromRight) {
            return x >= mThumbImage.getLeft() - adjust;
        } else {
            return x <= mThumbImage.getRight() + adjust;
        }
    }

    private boolean isPointInsideY(float y) {
        final float offset = mThumbImage.getTranslationY();
        final float top = mThumbImage.getTop() + offset;
        final float bottom = mThumbImage.getBottom() + offset;

        // Apply the minimum touch target size.
        final float targetSizeDiff = mMinimumTouchTarget - (bottom - top);
        final float adjust = targetSizeDiff > 0 ? targetSizeDiff / 2 : 0;

        return y >= (top - adjust) && y <= (bottom + adjust);
    }

    /**
     * Constructs an animator for the specified property on a group of views.
     * See {@link ObjectAnimator#ofFloat(Object, String, float...)} for
     * implementation details.
     *
     * @param property The property being animated.
     * @param value The value to which that property should animate.
     * @param views The target views to animate.
     * @return An animator for all the specified views.
     */
    private static Animator groupAnimatorOfFloat(
            Property<View, Float> property, float value, View... views) {
        AnimatorSet animSet = new AnimatorSet();
        AnimatorSet.Builder builder = null;

        for (int i = views.length - 1; i >= 0; i--) {
            final Animator anim = ObjectAnimator.ofFloat(views[i], property, value);
            if (builder == null) {
                builder = animSet.play(anim);
            } else {
                builder.with(anim);
            }
        }

        return animSet;
    }

    /**
     * Returns an animator for the view's scaleX value.
     */
    private static Animator animateScaleX(View v, float target) {
        return ObjectAnimator.ofFloat(v, View.SCALE_X, target);
    }

    /**
     * Returns an animator for the view's alpha value.
     */
    private static Animator animateAlpha(View v, float alpha) {
        return ObjectAnimator.ofFloat(v, View.ALPHA, alpha);
    }

    /**
     * A Property wrapper around the <code>left</code> functionality handled by the
     * {@link View#setLeft(int)} and {@link View#getLeft()} methods.
     */
    private static Property<View, Integer> LEFT = new IntProperty<View>("left") {
        @Override
        public void setValue(View object, int value) {
            object.setLeft(value);
        }

        @Override
        public Integer get(View object) {
            return object.getLeft();
        }
    };

    /**
     * A Property wrapper around the <code>top</code> functionality handled by the
     * {@link View#setTop(int)} and {@link View#getTop()} methods.
     */
    private static Property<View, Integer> TOP = new IntProperty<View>("top") {
        @Override
        public void setValue(View object, int value) {
            object.setTop(value);
        }

        @Override
        public Integer get(View object) {
            return object.getTop();
        }
    };

    /**
     * A Property wrapper around the <code>right</code> functionality handled by the
     * {@link View#setRight(int)} and {@link View#getRight()} methods.
     */
    private static Property<View, Integer> RIGHT = new IntProperty<View>("right") {
        @Override
        public void setValue(View object, int value) {
            object.setRight(value);
        }

        @Override
        public Integer get(View object) {
            return object.getRight();
        }
    };

    /**
     * A Property wrapper around the <code>bottom</code> functionality handled by the
     * {@link View#setBottom(int)} and {@link View#getBottom()} methods.
     */
    private static Property<View, Integer> BOTTOM = new IntProperty<View>("bottom") {
        @Override
        public void setValue(View object, int value) {
            object.setBottom(value);
        }

        @Override
        public Integer get(View object) {
            return object.getBottom();
        }
    };

    /**
     * Returns an animator for the view's bounds.
     */
    private static Animator animateBounds(View v, Rect bounds) {
        final PropertyValuesHolder left = PropertyValuesHolder.ofInt(LEFT, bounds.left);
        final PropertyValuesHolder top = PropertyValuesHolder.ofInt(TOP, bounds.top);
        final PropertyValuesHolder right = PropertyValuesHolder.ofInt(RIGHT, bounds.right);
        final PropertyValuesHolder bottom = PropertyValuesHolder.ofInt(BOTTOM, bounds.bottom);
        return ObjectAnimator.ofPropertyValuesHolder(v, left, top, right, bottom);
    }
}
