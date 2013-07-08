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

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.NinePatchDrawable;
import android.os.Handler;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.AbsListView.OnScrollListener;

/**
 * Helper class for AbsListView to draw and control the Fast Scroll thumb
 */
class FastScroller {
    private static final String TAG = "FastScroller";
   
    // Minimum number of pages to justify showing a fast scroll thumb
    private static int MIN_PAGES = 4;
    // Scroll thumb not showing
    private static final int STATE_NONE = 0;
    // Not implemented yet - fade-in transition
    private static final int STATE_ENTER = 1;
    // Scroll thumb visible and moving along with the scrollbar
    private static final int STATE_VISIBLE = 2;
    // Scroll thumb being dragged by user
    private static final int STATE_DRAGGING = 3;
    // Scroll thumb fading out due to inactivity timeout
    private static final int STATE_EXIT = 4;

    private static final int[] PRESSED_STATES = new int[] {
        android.R.attr.state_pressed
    };

    private static final int[] DEFAULT_STATES = new int[0];

    private static final int[] ATTRS = new int[] {
        android.R.attr.fastScrollTextColor,
        android.R.attr.fastScrollThumbDrawable,
        android.R.attr.fastScrollTrackDrawable,
        android.R.attr.fastScrollPreviewBackgroundLeft,
        android.R.attr.fastScrollPreviewBackgroundRight,
        android.R.attr.fastScrollOverlayPosition
    };

    private static final int TEXT_COLOR = 0;
    private static final int THUMB_DRAWABLE = 1;
    private static final int TRACK_DRAWABLE = 2;
    private static final int PREVIEW_BACKGROUND_LEFT = 3;
    private static final int PREVIEW_BACKGROUND_RIGHT = 4;
    private static final int OVERLAY_POSITION = 5;

    private static final int OVERLAY_FLOATING = 0;
    private static final int OVERLAY_AT_THUMB = 1;
    
    private Drawable mThumbDrawable;
    private Drawable mOverlayDrawable;
    private Drawable mTrackDrawable;

    private Drawable mOverlayDrawableLeft;
    private Drawable mOverlayDrawableRight;

    int mThumbH;
    int mThumbW;
    int mThumbY;

    private RectF mOverlayPos;
    private int mOverlaySize;

    AbsListView mList;
    boolean mScrollCompleted;
    private int mVisibleItem;
    private Paint mPaint;
    private int mListOffset;
    private int mItemCount = -1;
    private boolean mLongList;
    
    private Object [] mSections;
    private String mSectionText;
    private boolean mDrawOverlay;
    private ScrollFade mScrollFade;
    
    private int mState;
    
    private Handler mHandler = new Handler();
    
    BaseAdapter mListAdapter;
    private SectionIndexer mSectionIndexer;

    private boolean mChangedBounds;
    
    private int mPosition;

    private boolean mAlwaysShow;

    private int mOverlayPosition;

    private boolean mMatchDragPosition;

    float mInitialTouchY;
    boolean mPendingDrag;
    private int mScaledTouchSlop;

    private static final int FADE_TIMEOUT = 1500;
    private static final int PENDING_DRAG_DELAY = 180;

    private final Rect mTmpRect = new Rect();

    private final Runnable mDeferStartDrag = new Runnable() {
        public void run() {
            if (mList.mIsAttached) {
                beginDrag();

                final int viewHeight = mList.getHeight();
                // Jitter
                int newThumbY = (int) mInitialTouchY - mThumbH + 10;
                if (newThumbY < 0) {
                    newThumbY = 0;
                } else if (newThumbY + mThumbH > viewHeight) {
                    newThumbY = viewHeight - mThumbH;
                }
                mThumbY = newThumbY;
                scrollTo((float) mThumbY / (viewHeight - mThumbH));
            }

            mPendingDrag = false;
        }
    };

    public FastScroller(Context context, AbsListView listView) {
        mList = listView;
        init(context);
    }

    public void setAlwaysShow(boolean alwaysShow) {
        mAlwaysShow = alwaysShow;
        if (alwaysShow) {
            mHandler.removeCallbacks(mScrollFade);
            setState(STATE_VISIBLE);
        } else if (mState == STATE_VISIBLE) {
            mHandler.postDelayed(mScrollFade, FADE_TIMEOUT);
        }
    }

    public boolean isAlwaysShowEnabled() {
        return mAlwaysShow;
    }

    private void refreshDrawableState() {
        int[] state = mState == STATE_DRAGGING ? PRESSED_STATES : DEFAULT_STATES;

        if (mThumbDrawable != null && mThumbDrawable.isStateful()) {
            mThumbDrawable.setState(state);
        }
        if (mTrackDrawable != null && mTrackDrawable.isStateful()) {
            mTrackDrawable.setState(state);
        }
    }

    public void setScrollbarPosition(int position) {
        if (position == View.SCROLLBAR_POSITION_DEFAULT) {
            position = mList.isLayoutRtl() ?
                    View.SCROLLBAR_POSITION_LEFT : View.SCROLLBAR_POSITION_RIGHT;
        }
        mPosition = position;
        switch (position) {
            default:
            case View.SCROLLBAR_POSITION_RIGHT:
                mOverlayDrawable = mOverlayDrawableRight;
                break;
            case View.SCROLLBAR_POSITION_LEFT:
                mOverlayDrawable = mOverlayDrawableLeft;
                break;
        }
    }

    public int getWidth() {
        return mThumbW;
    }

    public void setState(int state) {
        switch (state) {
            case STATE_NONE:
                mHandler.removeCallbacks(mScrollFade);
                mList.invalidate();
                break;
            case STATE_VISIBLE:
                if (mState != STATE_VISIBLE) { // Optimization
                    resetThumbPos();
                }
                // Fall through
            case STATE_DRAGGING:
                mHandler.removeCallbacks(mScrollFade);
                break;
            case STATE_EXIT:
                int viewWidth = mList.getWidth();
                mList.invalidate(viewWidth - mThumbW, mThumbY, viewWidth, mThumbY + mThumbH);
                break;
        }
        mState = state;
        refreshDrawableState();
    }
    
    public int getState() {
        return mState;
    }
    
    private void resetThumbPos() {
        final int viewWidth = mList.getWidth();
        // Bounds are always top right. Y coordinate get's translated during draw
        switch (mPosition) {
            case View.SCROLLBAR_POSITION_RIGHT:
                mThumbDrawable.setBounds(viewWidth - mThumbW, 0, viewWidth, mThumbH);
                break;
            case View.SCROLLBAR_POSITION_LEFT:
                mThumbDrawable.setBounds(0, 0, mThumbW, mThumbH);
                break;
        }
        mThumbDrawable.setAlpha(ScrollFade.ALPHA_MAX);
    }
    
    private void useThumbDrawable(Context context, Drawable drawable) {
        mThumbDrawable = drawable;
        if (drawable instanceof NinePatchDrawable) {
            mThumbW = context.getResources().getDimensionPixelSize(
                    com.android.internal.R.dimen.fastscroll_thumb_width);
            mThumbH = context.getResources().getDimensionPixelSize(
                    com.android.internal.R.dimen.fastscroll_thumb_height);
        } else {
            mThumbW = drawable.getIntrinsicWidth();
            mThumbH = drawable.getIntrinsicHeight();
        }
        mChangedBounds = true;
    }

    private void init(Context context) {
        // Get both the scrollbar states drawables
        TypedArray ta = context.getTheme().obtainStyledAttributes(ATTRS);
        useThumbDrawable(context, ta.getDrawable(THUMB_DRAWABLE));
        mTrackDrawable = ta.getDrawable(TRACK_DRAWABLE);
        
        mOverlayDrawableLeft = ta.getDrawable(PREVIEW_BACKGROUND_LEFT);
        mOverlayDrawableRight = ta.getDrawable(PREVIEW_BACKGROUND_RIGHT);
        mOverlayPosition = ta.getInt(OVERLAY_POSITION, OVERLAY_FLOATING);
        
        mScrollCompleted = true;

        getSectionsFromIndexer();

        mOverlaySize = context.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.fastscroll_overlay_size);
        mOverlayPos = new RectF();
        mScrollFade = new ScrollFade();
        mPaint = new Paint();
        mPaint.setAntiAlias(true);
        mPaint.setTextAlign(Paint.Align.CENTER);
        mPaint.setTextSize(mOverlaySize / 2);

        ColorStateList textColor = ta.getColorStateList(TEXT_COLOR);
        int textColorNormal = textColor.getDefaultColor();
        mPaint.setColor(textColorNormal);
        mPaint.setStyle(Paint.Style.FILL_AND_STROKE);

        // to show mOverlayDrawable properly
        if (mList.getWidth() > 0 && mList.getHeight() > 0) {
            onSizeChanged(mList.getWidth(), mList.getHeight(), 0, 0);
        }
        
        mState = STATE_NONE;
        refreshDrawableState();

        ta.recycle();

        mScaledTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        mMatchDragPosition = context.getApplicationInfo().targetSdkVersion >=
                android.os.Build.VERSION_CODES.HONEYCOMB;

        setScrollbarPosition(mList.getVerticalScrollbarPosition());
    }
    
    void stop() {
        setState(STATE_NONE);
    }
    
    boolean isVisible() {
        return !(mState == STATE_NONE);
    }
    
    public void draw(Canvas canvas) {
        
        if (mState == STATE_NONE) {
            // No need to draw anything
            return;
        }

        final int y = mThumbY;
        final int viewWidth = mList.getWidth();
        final FastScroller.ScrollFade scrollFade = mScrollFade;

        int alpha = -1;
        if (mState == STATE_EXIT) {
            alpha = scrollFade.getAlpha();
            if (alpha < ScrollFade.ALPHA_MAX / 2) {
                mThumbDrawable.setAlpha(alpha * 2);
            }
            int left = 0;
            switch (mPosition) {
                case View.SCROLLBAR_POSITION_RIGHT:
                    left = viewWidth - (mThumbW * alpha) / ScrollFade.ALPHA_MAX;
                    break;
                case View.SCROLLBAR_POSITION_LEFT:
                    left = -mThumbW + (mThumbW * alpha) / ScrollFade.ALPHA_MAX;
                    break;
            }
            mThumbDrawable.setBounds(left, 0, left + mThumbW, mThumbH);
            mChangedBounds = true;
        }

        if (mTrackDrawable != null) {
            final Rect thumbBounds = mThumbDrawable.getBounds();
            final int left = thumbBounds.left;
            final int halfThumbHeight = (thumbBounds.bottom - thumbBounds.top) / 2;
            final int trackWidth = mTrackDrawable.getIntrinsicWidth();
            final int trackLeft = (left + mThumbW / 2) - trackWidth / 2;
            mTrackDrawable.setBounds(trackLeft, halfThumbHeight,
                    trackLeft + trackWidth, mList.getHeight() - halfThumbHeight);
            mTrackDrawable.draw(canvas);
        }

        canvas.translate(0, y);
        mThumbDrawable.draw(canvas);
        canvas.translate(0, -y);

        // If user is dragging the scroll bar, draw the alphabet overlay
        if (mState == STATE_DRAGGING && mDrawOverlay) {
            if (mOverlayPosition == OVERLAY_AT_THUMB) {
                int left = 0;
                switch (mPosition) {
                    default:
                    case View.SCROLLBAR_POSITION_RIGHT:
                        left = Math.max(0,
                                mThumbDrawable.getBounds().left - mThumbW - mOverlaySize);
                        break;
                    case View.SCROLLBAR_POSITION_LEFT:
                        left = Math.min(mThumbDrawable.getBounds().right + mThumbW,
                                mList.getWidth() - mOverlaySize);
                        break;
                }

                int top = Math.max(0,
                        Math.min(y + (mThumbH - mOverlaySize) / 2, mList.getHeight() - mOverlaySize));

                final RectF pos = mOverlayPos;
                pos.left = left;
                pos.right = pos.left + mOverlaySize;
                pos.top = top;
                pos.bottom = pos.top + mOverlaySize;
                if (mOverlayDrawable != null) {
                    mOverlayDrawable.setBounds((int) pos.left, (int) pos.top,
                            (int) pos.right, (int) pos.bottom);
                }
            }
            mOverlayDrawable.draw(canvas);
            final Paint paint = mPaint;
            float descent = paint.descent();
            final RectF rectF = mOverlayPos;
            final Rect tmpRect = mTmpRect;
            mOverlayDrawable.getPadding(tmpRect);
            final int hOff = (tmpRect.right - tmpRect.left) / 2;
            final int vOff = (tmpRect.bottom - tmpRect.top) / 2;
            canvas.drawText(mSectionText, (int) (rectF.left + rectF.right) / 2 - hOff,
                    (int) (rectF.bottom + rectF.top) / 2 + mOverlaySize / 4 - descent - vOff,
                    paint);
        } else if (mState == STATE_EXIT) {
            if (alpha == 0) { // Done with exit
                setState(STATE_NONE);
            } else if (mTrackDrawable != null) {
                mList.invalidate(viewWidth - mThumbW, 0, viewWidth, mList.getHeight());
            } else {
                mList.invalidate(viewWidth - mThumbW, y, viewWidth, y + mThumbH);
            }
        }
    }

    void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (mThumbDrawable != null) {
            switch (mPosition) {
                default:
                case View.SCROLLBAR_POSITION_RIGHT:
                    mThumbDrawable.setBounds(w - mThumbW, 0, w, mThumbH);
                    break;
                case View.SCROLLBAR_POSITION_LEFT:
                    mThumbDrawable.setBounds(0, 0, mThumbW, mThumbH);
                    break;
            }
        }
        if (mOverlayPosition == OVERLAY_FLOATING) {
            final RectF pos = mOverlayPos;
            pos.left = (w - mOverlaySize) / 2;
            pos.right = pos.left + mOverlaySize;
            pos.top = h / 10; // 10% from top
            pos.bottom = pos.top + mOverlaySize;
            if (mOverlayDrawable != null) {
                mOverlayDrawable.setBounds((int) pos.left, (int) pos.top,
                        (int) pos.right, (int) pos.bottom);
            }
        }
    }

    void onItemCountChanged(int oldCount, int newCount) {
        if (mAlwaysShow) {
            mLongList = true;
        }
    }

    void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, 
            int totalItemCount) {
        // Are there enough pages to require fast scroll? Recompute only if total count changes
        if (mItemCount != totalItemCount && visibleItemCount > 0) {
            mItemCount = totalItemCount;
            mLongList = mItemCount / visibleItemCount >= MIN_PAGES;
        }
        if (mAlwaysShow) {
            mLongList = true;
        }
        if (!mLongList) {
            if (mState != STATE_NONE) {
                setState(STATE_NONE);
            }
            return;
        }
        if (totalItemCount - visibleItemCount > 0 && mState != STATE_DRAGGING) {
            mThumbY = getThumbPositionForListPosition(firstVisibleItem, visibleItemCount,
                    totalItemCount);
            if (mChangedBounds) {
                resetThumbPos();
                mChangedBounds = false;
            }
        }
        mScrollCompleted = true;
        if (firstVisibleItem == mVisibleItem) {
            return;
        }
        mVisibleItem = firstVisibleItem;
        if (mState != STATE_DRAGGING) {
            setState(STATE_VISIBLE);
            if (!mAlwaysShow) {
                mHandler.postDelayed(mScrollFade, FADE_TIMEOUT);
            }
        }
    }

    SectionIndexer getSectionIndexer() {
        return mSectionIndexer;
    }

    Object[] getSections() {
        if (mListAdapter == null && mList != null) {
            getSectionsFromIndexer();
        }
        return mSections;
    }

    void getSectionsFromIndexer() {
        Adapter adapter = mList.getAdapter();
        mSectionIndexer = null;
        if (adapter instanceof HeaderViewListAdapter) {
            mListOffset = ((HeaderViewListAdapter)adapter).getHeadersCount();
            adapter = ((HeaderViewListAdapter)adapter).getWrappedAdapter();
        }
        if (adapter instanceof ExpandableListConnector) {
            ExpandableListAdapter expAdapter = ((ExpandableListConnector)adapter).getAdapter();
            if (expAdapter instanceof SectionIndexer) {
                mSectionIndexer = (SectionIndexer) expAdapter;
                mListAdapter = (BaseAdapter) adapter;
                mSections = mSectionIndexer.getSections();
            }
        } else {
            if (adapter instanceof SectionIndexer) {
                mListAdapter = (BaseAdapter) adapter;
                mSectionIndexer = (SectionIndexer) adapter;
                mSections = mSectionIndexer.getSections();
                if (mSections == null) {
                    mSections = new String[] { " " };
                }
            } else {
                mListAdapter = (BaseAdapter) adapter;
                mSections = new String[] { " " };
            }
        }
    }

    public void onSectionsChanged() {
        mListAdapter = null;
    }

    void scrollTo(float position) {
        int count = mList.getCount();
        mScrollCompleted = false;
        float fThreshold = (1.0f / count) / 8;
        final Object[] sections = mSections;
        int sectionIndex;
        if (sections != null && sections.length > 1) {
            final int nSections = sections.length;
            int section = (int) (position * nSections);
            if (section >= nSections) {
                section = nSections - 1;
            }
            int exactSection = section;
            sectionIndex = section;
            int index = mSectionIndexer.getPositionForSection(section);
            // Given the expected section and index, the following code will
            // try to account for missing sections (no names starting with..)
            // It will compute the scroll space of surrounding empty sections
            // and interpolate the currently visible letter's range across the
            // available space, so that there is always some list movement while
            // the user moves the thumb.
            int nextIndex = count;
            int prevIndex = index;
            int prevSection = section;
            int nextSection = section + 1;
            // Assume the next section is unique
            if (section < nSections - 1) {
                nextIndex = mSectionIndexer.getPositionForSection(section + 1);
            }
            
            // Find the previous index if we're slicing the previous section
            if (nextIndex == index) {
                // Non-existent letter
                while (section > 0) {
                    section--;
                    prevIndex = mSectionIndexer.getPositionForSection(section);
                    if (prevIndex != index) {
                        prevSection = section;
                        sectionIndex = section;
                        break;
                    } else if (section == 0) {
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
            while (nextNextSection < nSections &&
                    mSectionIndexer.getPositionForSection(nextNextSection) == nextIndex) {
                nextNextSection++;
                nextSection++;
            }
            // Compute the beginning and ending scroll range percentage of the
            // currently visible letter. This could be equal to or greater than
            // (1 / nSections). 
            float fPrev = (float) prevSection / nSections;
            float fNext = (float) nextSection / nSections;
            if (prevSection == exactSection && position - fPrev < fThreshold) {
                index = prevIndex;
            } else {
                index = prevIndex + (int) ((nextIndex - prevIndex) * (position - fPrev) 
                    / (fNext - fPrev));
            }
            // Don't overflow
            if (index > count - 1) index = count - 1;
            
            if (mList instanceof ExpandableListView) {
                ExpandableListView expList = (ExpandableListView) mList;
                expList.setSelectionFromTop(expList.getFlatListPosition(
                        ExpandableListView.getPackedPositionForGroup(index + mListOffset)), 0);
            } else if (mList instanceof ListView) {
                ((ListView)mList).setSelectionFromTop(index + mListOffset, 0);
            } else {
                mList.setSelection(index + mListOffset);
            }
        } else {
            int index = (int) (position * count);
            // Don't overflow
            if (index > count - 1) index = count - 1;

            if (mList instanceof ExpandableListView) {
                ExpandableListView expList = (ExpandableListView) mList;
                expList.setSelectionFromTop(expList.getFlatListPosition(
                        ExpandableListView.getPackedPositionForGroup(index + mListOffset)), 0);
            } else if (mList instanceof ListView) {
                ((ListView)mList).setSelectionFromTop(index + mListOffset, 0);
            } else {
                mList.setSelection(index + mListOffset);
            }
            sectionIndex = -1;
        }

        if (sectionIndex >= 0) {
            String text = mSectionText = sections[sectionIndex].toString();
            mDrawOverlay = (text.length() != 1 || text.charAt(0) != ' ') &&
                    sectionIndex < sections.length;
        } else {
            mDrawOverlay = false;
        }
    }

    private int getThumbPositionForListPosition(int firstVisibleItem, int visibleItemCount,
            int totalItemCount) {
        if (mSectionIndexer == null || mListAdapter == null) {
            getSectionsFromIndexer();
        }
        if (mSectionIndexer == null || !mMatchDragPosition) {
            return ((mList.getHeight() - mThumbH) * firstVisibleItem)
                    / (totalItemCount - visibleItemCount);
        }

        firstVisibleItem -= mListOffset;
        if (firstVisibleItem < 0) {
            return 0;
        }
        totalItemCount -= mListOffset;

        final int trackHeight = mList.getHeight() - mThumbH;

        final int section = mSectionIndexer.getSectionForPosition(firstVisibleItem);
        final int sectionPos = mSectionIndexer.getPositionForSection(section);
        final int nextSectionPos;
        final int sectionCount = mSections.length;
        if (section + 1 < sectionCount) {
            nextSectionPos = mSectionIndexer.getPositionForSection(section + 1);
        } else {
            nextSectionPos = totalItemCount - 1;
        }
        final int positionsInSection = nextSectionPos - sectionPos;

        final View child = mList.getChildAt(0);
        final float incrementalPos = child == null ? 0 : firstVisibleItem +
                (float) (mList.getPaddingTop() - child.getTop()) / child.getHeight();
        final float posWithinSection = (incrementalPos - sectionPos) / positionsInSection;
        int result = (int) ((section + posWithinSection) / sectionCount * trackHeight);

        // Fake out the scrollbar for the last item. Since the section indexer won't
        // ever actually move the list in this end space, make scrolling across the last item
        // account for whatever space is remaining.
        if (firstVisibleItem > 0 && firstVisibleItem + visibleItemCount == totalItemCount) {
            final View lastChild = mList.getChildAt(visibleItemCount - 1);
            final float lastItemVisible = (float) (mList.getHeight() - mList.getPaddingBottom()
                    - lastChild.getTop()) / lastChild.getHeight();
            result += (trackHeight - result) * lastItemVisible;
        }

        return result;
    }

    private void cancelFling() {
        // Cancel the list fling
        MotionEvent cancelFling = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0, 0, 0);
        mList.onTouchEvent(cancelFling);
        cancelFling.recycle();
    }
    
    void cancelPendingDrag() {
        mList.removeCallbacks(mDeferStartDrag);
        mPendingDrag = false;
    }

    void startPendingDrag() {
        mPendingDrag = true;
        mList.postDelayed(mDeferStartDrag, PENDING_DRAG_DELAY);
    }

    void beginDrag() {
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

    boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (mState > STATE_NONE && isPointInside(ev.getX(), ev.getY())) {
                    if (!mList.isInScrollingContainer()) {
                        beginDrag();
                        return true;
                    }
                    mInitialTouchY = ev.getY();
                    startPendingDrag();
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                cancelPendingDrag();
                break;
        }
        return false;
    }

    boolean onTouchEvent(MotionEvent me) {
        if (mState == STATE_NONE) {
            return false;
        }

        final int action = me.getAction();

        if (action == MotionEvent.ACTION_DOWN) {
            if (isPointInside(me.getX(), me.getY())) {
                if (!mList.isInScrollingContainer()) {
                    beginDrag();
                    return true;
                }
                mInitialTouchY = me.getY();
                startPendingDrag();
            }
        } else if (action == MotionEvent.ACTION_UP) { // don't add ACTION_CANCEL here
            if (mPendingDrag) {
                // Allow a tap to scroll.
                beginDrag();

                final int viewHeight = mList.getHeight();
                // Jitter
                int newThumbY = (int) me.getY() - mThumbH + 10;
                if (newThumbY < 0) {
                    newThumbY = 0;
                } else if (newThumbY + mThumbH > viewHeight) {
                    newThumbY = viewHeight - mThumbH;
                }
                mThumbY = newThumbY;
                scrollTo((float) mThumbY / (viewHeight - mThumbH));

                cancelPendingDrag();
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
                final Handler handler = mHandler;
                handler.removeCallbacks(mScrollFade);
                if (!mAlwaysShow) {
                    handler.postDelayed(mScrollFade, 1000);
                }

                mList.invalidate();
                return true;
            }
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (mPendingDrag) {
                final float y = me.getY();
                if (Math.abs(y - mInitialTouchY) > mScaledTouchSlop) {
                    setState(STATE_DRAGGING);
                    if (mListAdapter == null && mList != null) {
                        getSectionsFromIndexer();
                    }
                    if (mList != null) {
                        mList.requestDisallowInterceptTouchEvent(true);
                        mList.reportScrollStateChange(OnScrollListener.SCROLL_STATE_TOUCH_SCROLL);
                    }

                    cancelFling();
                    cancelPendingDrag();
                    // Will hit the STATE_DRAGGING check below
                }
            }
            if (mState == STATE_DRAGGING) {
                final int viewHeight = mList.getHeight();
                // Jitter
                int newThumbY = (int) me.getY() - mThumbH + 10;
                if (newThumbY < 0) {
                    newThumbY = 0;
                } else if (newThumbY + mThumbH > viewHeight) {
                    newThumbY = viewHeight - mThumbH;
                }
                if (Math.abs(mThumbY - newThumbY) < 2) {
                    return true;
                }
                mThumbY = newThumbY;
                // If the previous scrollTo is still pending
                if (mScrollCompleted) {
                    scrollTo((float) mThumbY / (viewHeight - mThumbH));
                }
                return true;
            }
        } else if (action == MotionEvent.ACTION_CANCEL) {
            cancelPendingDrag();
        }
        return false;
    }

    boolean isPointInside(float x, float y) {
        boolean inTrack = false;
        switch (mPosition) {
            default:
            case View.SCROLLBAR_POSITION_RIGHT:
                inTrack = x > mList.getWidth() - mThumbW;
                break;
            case View.SCROLLBAR_POSITION_LEFT:
                inTrack = x < mThumbW;
                break;
        }

        // Allow taps in the track to start moving.
        return inTrack && (mTrackDrawable != null || y >= mThumbY && y <= mThumbY + mThumbH);
    }

    public class ScrollFade implements Runnable {
        
        long mStartTime;
        long mFadeDuration;
        static final int ALPHA_MAX = 208;
        static final long FADE_DURATION = 200;
        
        void startFade() {
            mFadeDuration = FADE_DURATION;
            mStartTime = SystemClock.uptimeMillis();
            setState(STATE_EXIT);
        }
        
        int getAlpha() {
            if (getState() != STATE_EXIT) {
                return ALPHA_MAX;
            }
            int alpha;
            long now = SystemClock.uptimeMillis();
            if (now > mStartTime + mFadeDuration) {
                alpha = 0;
            } else {
                alpha = (int) (ALPHA_MAX - ((now - mStartTime) * ALPHA_MAX) / mFadeDuration); 
            }
            return alpha;
        }
        
        public void run() {
            if (getState() != STATE_EXIT) {
                startFade();
                return;
            }
            
            if (getAlpha() > 0) {
                mList.invalidate();
            } else {
                setState(STATE_NONE);
            }
        }
    }
}
