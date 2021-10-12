/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.wallet.ui;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerViewAccessibilityDelegate;

import com.android.systemui.R;

import java.util.Collections;
import java.util.List;

/**
 * Card Carousel for displaying Quick Access Wallet cards.
 */
public class WalletCardCarousel extends RecyclerView {

    // A negative card margin is required because card shrinkage pushes the cards too far apart
    private static final float CARD_MARGIN_RATIO = -.03f;
    // Size of the unselected card as a ratio to size of selected card.
    private static final float UNSELECTED_CARD_SCALE = .83f;
    private static final float CORNER_RADIUS_RATIO = 25f / 700f;
    private static final float CARD_ASPECT_RATIO = 700f / 440f;
    private static final float CARD_VIEW_WIDTH_RATIO = 0.69f;


    static final int CARD_ANIM_ALPHA_DURATION = 100;
    static final int CARD_ANIM_ALPHA_DELAY = 50;

    private final Rect mSystemGestureExclusionZone = new Rect();
    private final WalletCardCarouselAdapter mWalletCardCarouselAdapter;
    private int mExpectedViewWidth;
    private int mCardMarginPx;
    private int mCardWidthPx;
    private int mCardHeightPx;
    private float mCornerRadiusPx;
    private int mTotalCardWidth;
    private float mCardEdgeToCenterDistance;

    private OnSelectionListener mSelectionListener;
    private OnCardScrollListener mCardScrollListener;
    // Adapter position of the child that is closest to the center of the recycler view, will also
    // be used in DotIndicatorDecoration.
    int mCenteredAdapterPosition = RecyclerView.NO_POSITION;
    // Pixel distance, along y-axis, from the center of the recycler view to the nearest child, will
    // also be used in DotIndicatorDecoration.
    float mEdgeToCenterDistance = Float.MAX_VALUE;
    private float mCardCenterToScreenCenterDistancePx = Float.MAX_VALUE;

    interface OnSelectionListener {
        /**
         * The card was moved to the center, thus selecting it.
         */
        void onCardSelected(@NonNull WalletCardViewInfo card);

        /**
         * The card was clicked.
         */
        void onCardClicked(@NonNull WalletCardViewInfo card);

        /**
         * Cards should be re-queried due to a layout change
         */
        void queryWalletCards();
    }

    interface OnCardScrollListener {
        void onCardScroll(WalletCardViewInfo centerCard, WalletCardViewInfo nextCard,
                float percentDistanceFromCenter);
    }

    public WalletCardCarousel(Context context) {
        this(context, null);
    }

    public WalletCardCarousel(Context context, @Nullable AttributeSet attributeSet) {
        super(context, attributeSet);

        setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false));
        addOnScrollListener(new CardCarouselScrollListener());
        new CarouselSnapHelper().attachToRecyclerView(this);
        mWalletCardCarouselAdapter = new WalletCardCarouselAdapter();
        mWalletCardCarouselAdapter.setHasStableIds(true);
        setAdapter(mWalletCardCarouselAdapter);
        ViewCompat.setAccessibilityDelegate(this, new CardCarouselAccessibilityDelegate(this));

        addItemDecoration(new DotIndicatorDecoration(getContext()));
    }

    /**
     * We need to know the card width before we query cards. Card width depends on layout width.
     * But the carousel isn't laid out until set to visible, which only happens after cards are
     * returned. Setting the expected view width breaks the chicken-and-egg problem.
     */
    void setExpectedViewWidth(int width) {
        if (mExpectedViewWidth == width) {
            return;
        }
        mExpectedViewWidth = width;
        Resources res = getResources();
        DisplayMetrics metrics = res.getDisplayMetrics();
        int screenWidth = Math.min(metrics.widthPixels, metrics.heightPixels);
        mCardWidthPx = Math.round(Math.min(width, screenWidth) * CARD_VIEW_WIDTH_RATIO);
        mCardHeightPx = Math.round(mCardWidthPx / CARD_ASPECT_RATIO);
        mCornerRadiusPx = mCardWidthPx * CORNER_RADIUS_RATIO;
        mCardMarginPx = Math.round(mCardWidthPx * CARD_MARGIN_RATIO);
        mTotalCardWidth = mCardWidthPx + res.getDimensionPixelSize(R.dimen.card_margin) * 2;
        mCardEdgeToCenterDistance = mTotalCardWidth / 2f;
        updatePadding(width);
        if (mSelectionListener != null) {
            mSelectionListener.queryWalletCards();
        }
    }

    @Override
    public void onViewAdded(View child) {
        super.onViewAdded(child);
        LayoutParams layoutParams = (LayoutParams) child.getLayoutParams();
        layoutParams.leftMargin = mCardMarginPx;
        layoutParams.rightMargin = mCardMarginPx;
        child.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateCardView(child));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        int width = getWidth();
        if (mWalletCardCarouselAdapter.getItemCount() > 1 && width < mTotalCardWidth * 1.5) {
            // When 2 or more cards are available but only one whole card can be shown on screen at
            // a time, the entire carousel is opted out from system gesture to help users swipe
            // between cards without accidentally performing the 'back' gesture. When there is only
            // one card or when the carousel is large enough to accommodate several whole cards,
            // there is no need to disable the back gesture since either the user can't swipe or has
            // plenty of room with which to do so.
            mSystemGestureExclusionZone.set(0, 0, width, getHeight());
            setSystemGestureExclusionRects(Collections.singletonList(mSystemGestureExclusionZone));
        }
        if (width != mExpectedViewWidth) {
            updatePadding(width);
        }
    }

    void setSelectionListener(OnSelectionListener selectionListener) {
        mSelectionListener = selectionListener;
    }

    void setCardScrollListener(OnCardScrollListener scrollListener) {
        mCardScrollListener = scrollListener;
    }

    int getCardWidthPx() {
        return mCardWidthPx;
    }

    int getCardHeightPx() {
        return mCardHeightPx;
    }

    /**
     * Returns true if the data set is changed.
     */
    boolean setData(List<WalletCardViewInfo> data, int selectedIndex, boolean hasLockStateChanged) {
        boolean hasDataChanged = mWalletCardCarouselAdapter.setData(data, hasLockStateChanged);
        scrollToPosition(selectedIndex);
        WalletCardViewInfo selectedCard = data.get(selectedIndex);
        mCardScrollListener.onCardScroll(selectedCard, selectedCard, 0);
        return hasDataChanged;
    }

    @Override
    public void scrollToPosition(int position) {
        super.scrollToPosition(position);
        mSelectionListener.onCardSelected(mWalletCardCarouselAdapter.mData.get(position));
    }

    /**
     * The padding pushes the first and last cards in the list to the center when they are
     * selected.
     */
    private void updatePadding(int viewWidth) {
        int paddingHorizontal = (viewWidth - mTotalCardWidth) / 2 - mCardMarginPx;
        paddingHorizontal = Math.max(0, paddingHorizontal); // just in case
        setPadding(paddingHorizontal, getPaddingTop(), paddingHorizontal, getPaddingBottom());

        // re-center selected card after changing padding (if card is selected)
        if (mWalletCardCarouselAdapter != null
                && mWalletCardCarouselAdapter.getItemCount() > 0
                && mCenteredAdapterPosition != NO_POSITION) {
            ViewHolder viewHolder = findViewHolderForAdapterPosition(mCenteredAdapterPosition);
            if (viewHolder != null) {
                View cardView = viewHolder.itemView;
                int cardCenter = (cardView.getLeft() + cardView.getRight()) / 2;
                int viewCenter = (getLeft() + getRight()) / 2;
                int scrollX = cardCenter - viewCenter;
                scrollBy(scrollX, 0);
            }
        }
    }

    private void updateCardView(View view) {
        WalletCardViewHolder viewHolder = (WalletCardViewHolder) view.getTag();
        CardView cardView = viewHolder.mCardView;
        float center = (float) getWidth() / 2f;
        float viewCenter = (view.getRight() + view.getLeft()) / 2f;
        float viewWidth = view.getWidth();
        float position = (viewCenter - center) / viewWidth;
        float scaleFactor = Math.max(UNSELECTED_CARD_SCALE, 1f - Math.abs(position));

        cardView.setScaleX(scaleFactor);
        cardView.setScaleY(scaleFactor);

        // a card is the "centered card" until its edge has moved past the center of the recycler
        // view. note that we also need to factor in the negative margin.
        // Find the edge that is closer to the center.
        int edgePosition =
                viewCenter < center ? view.getRight() + mCardMarginPx
                        : view.getLeft() - mCardMarginPx;

        if (Math.abs(viewCenter - center) < mCardCenterToScreenCenterDistancePx) {
            int childAdapterPosition = getChildAdapterPosition(view);
            if (childAdapterPosition == RecyclerView.NO_POSITION) {
                return;
            }
            mCenteredAdapterPosition = getChildAdapterPosition(view);
            mEdgeToCenterDistance = edgePosition - center;
            mCardCenterToScreenCenterDistancePx = Math.abs(viewCenter - center);
        }
    }

    private class CardCarouselScrollListener extends OnScrollListener {

        private int mOldState = -1;

        @Override
        public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
            if (newState == RecyclerView.SCROLL_STATE_IDLE && newState != mOldState) {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            }
            mOldState = newState;
        }

        /**
         * Callback method to be invoked when the RecyclerView has been scrolled. This will be
         * called after the scroll has completed.
         *
         * <p>This callback will also be called if visible item range changes after a layout
         * calculation. In that case, dx and dy will be 0.
         *
         * @param recyclerView The RecyclerView which scrolled.
         * @param dx           The amount of horizontal scroll.
         * @param dy           The amount of vertical scroll.
         */
        @Override
        public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
            mCenteredAdapterPosition = RecyclerView.NO_POSITION;
            mEdgeToCenterDistance = Float.MAX_VALUE;
            mCardCenterToScreenCenterDistancePx = Float.MAX_VALUE;
            for (int i = 0; i < getChildCount(); i++) {
                updateCardView(getChildAt(i));
            }
            if (mCenteredAdapterPosition == RecyclerView.NO_POSITION || dx == 0) {
                return;
            }

            int nextAdapterPosition =
                    mCenteredAdapterPosition + (mEdgeToCenterDistance > 0 ? 1 : -1);
            if (nextAdapterPosition < 0
                    || nextAdapterPosition >= mWalletCardCarouselAdapter.mData.size()) {
                return;
            }

            // Update the label text based on the currently selected card and the next one
            WalletCardViewInfo centerCard =
                    mWalletCardCarouselAdapter.mData.get(mCenteredAdapterPosition);
            WalletCardViewInfo nextCard = mWalletCardCarouselAdapter.mData.get(nextAdapterPosition);
            float percentDistanceFromCenter =
                    Math.abs(mEdgeToCenterDistance) / mCardEdgeToCenterDistance;
            mCardScrollListener.onCardScroll(centerCard, nextCard, percentDistanceFromCenter);
        }
    }

    private class CarouselSnapHelper extends PagerSnapHelper {

        private static final float MILLISECONDS_PER_INCH = 200.0F;
        private static final int MAX_SCROLL_ON_FLING_DURATION = 80; // ms

        @Override
        public View findSnapView(LayoutManager layoutManager) {
            View view = super.findSnapView(layoutManager);
            if (view == null) {
                // implementation decides not to snap
                return null;
            }
            WalletCardViewHolder viewHolder = (WalletCardViewHolder) view.getTag();
            WalletCardViewInfo card = viewHolder.mCardViewInfo;
            mSelectionListener.onCardSelected(card);
            mCardScrollListener.onCardScroll(card, card, 0);
            return view;
        }

        /**
         * The default SnapScroller is a little sluggish
         */
        @Override
        protected LinearSmoothScroller createScroller(LayoutManager layoutManager) {
            return new LinearSmoothScroller(getContext()) {
                @Override
                protected void onTargetFound(View targetView, State state, Action action) {
                    int[] snapDistances = calculateDistanceToFinalSnap(layoutManager, targetView);
                    final int dx = snapDistances[0];
                    final int dy = snapDistances[1];
                    final int time = calculateTimeForDeceleration(
                            Math.max(Math.abs(dx), Math.abs(dy)));
                    if (time > 0) {
                        action.update(dx, dy, time, mDecelerateInterpolator);
                    }
                }

                @Override
                protected float calculateSpeedPerPixel(DisplayMetrics displayMetrics) {
                    return MILLISECONDS_PER_INCH / displayMetrics.densityDpi;
                }

                @Override
                protected int calculateTimeForScrolling(int dx) {
                    return Math.min(MAX_SCROLL_ON_FLING_DURATION,
                            super.calculateTimeForScrolling(dx));
                }
            };
        }
    }

    private class WalletCardCarouselAdapter extends Adapter<WalletCardViewHolder> {

        private List<WalletCardViewInfo> mData = Collections.EMPTY_LIST;

        @NonNull
        @Override
        public WalletCardViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int i) {
            LayoutInflater inflater = LayoutInflater.from(viewGroup.getContext());
            View view = inflater.inflate(R.layout.wallet_card_view, viewGroup, false);
            WalletCardViewHolder viewHolder = new WalletCardViewHolder(view);
            CardView cardView = viewHolder.mCardView;
            cardView.setRadius(mCornerRadiusPx);
            ViewGroup.LayoutParams layoutParams = cardView.getLayoutParams();
            layoutParams.width = mCardWidthPx;
            layoutParams.height = mCardHeightPx;
            view.setTag(viewHolder);
            return viewHolder;
        }

        @Override
        public void onBindViewHolder(@NonNull WalletCardViewHolder viewHolder, int position) {
            WalletCardViewInfo cardViewInfo = mData.get(position);
            viewHolder.mCardViewInfo = cardViewInfo;
            if (cardViewInfo.getCardId().isEmpty()) {
                viewHolder.mImageView.setScaleType(ImageView.ScaleType.CENTER);
            }
            viewHolder.mImageView.setImageDrawable(cardViewInfo.getCardDrawable());
            viewHolder.mCardView.setContentDescription(cardViewInfo.getContentDescription());
            viewHolder.mCardView.setOnClickListener(
                    v -> {
                        if (position != mCenteredAdapterPosition) {
                            smoothScrollToPosition(position);
                        } else {
                            mSelectionListener.onCardClicked(cardViewInfo);
                        }
                    });
        }

        @Override
        public int getItemCount() {
            return mData.size();
        }

        @Override
        public long getItemId(int position) {
            return mData.get(position).getCardId().hashCode();
        }

        private boolean setData(List<WalletCardViewInfo> data, boolean hasLockedStateChanged) {
            List<WalletCardViewInfo> oldData = mData;
            mData = data;
            if (hasLockedStateChanged || !isUiEquivalent(oldData, data)) {
                notifyDataSetChanged();
                return true;
            }
            return false;
        }

        private boolean isUiEquivalent(
                List<WalletCardViewInfo> oldData, List<WalletCardViewInfo> newData) {
            if (oldData.size() != newData.size()) {
                return false;
            }
            for (int i = 0; i < newData.size(); i++) {
                WalletCardViewInfo oldItem = oldData.get(i);
                WalletCardViewInfo newItem = newData.get(i);
                if (!oldItem.isUiEquivalent(newItem)) {
                    return false;
                }
            }
            return true;
        }
    }

    private class CardCarouselAccessibilityDelegate extends RecyclerViewAccessibilityDelegate {

        private CardCarouselAccessibilityDelegate(@NonNull RecyclerView recyclerView) {
            super(recyclerView);
        }

        @Override
        public boolean onRequestSendAccessibilityEvent(
                ViewGroup viewGroup, View view, AccessibilityEvent accessibilityEvent) {
            int eventType = accessibilityEvent.getEventType();
            if (eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) {
                scrollToPosition(getChildAdapterPosition(view));
            }
            return super.onRequestSendAccessibilityEvent(viewGroup, view, accessibilityEvent);
        }
    }
}
