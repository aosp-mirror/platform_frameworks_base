/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Outline;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import com.android.systemui.ExpandHelper;
import com.android.systemui.Gefingerpoken;
import com.android.systemui.R;
import com.android.systemui.SwipeHelper;
import com.android.systemui.statusbar.ExpandableView;
import com.android.systemui.statusbar.NotificationData;
import com.android.systemui.statusbar.phone.PhoneStatusBar;

public class HeadsUpNotificationView extends FrameLayout implements SwipeHelper.Callback, ExpandHelper.Callback,
        ViewTreeObserver.OnComputeInternalInsetsListener {
    private static final String TAG = "HeadsUpNotificationView";
    private static final boolean DEBUG = false;
    private static final boolean SPEW = DEBUG;

    Rect mTmpRect = new Rect();
    int[] mTmpTwoArray = new int[2];

    private final int mTouchSensitivityDelay;
    private final float mMaxAlpha = 1f;
    private SwipeHelper mSwipeHelper;
    private EdgeSwipeHelper mEdgeSwipeHelper;

    private PhoneStatusBar mBar;
    private ExpandHelper mExpandHelper;

    private long mStartTouchTime;
    private ViewGroup mContentHolder;

    private NotificationData.Entry mHeadsUp;

    public HeadsUpNotificationView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public HeadsUpNotificationView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mTouchSensitivityDelay = getResources().getInteger(R.integer.heads_up_sensitivity_delay);
        if (DEBUG) Log.v(TAG, "create() " + mTouchSensitivityDelay);
    }

    public void updateResources() {
        if (mContentHolder != null) {
            final LayoutParams lp = (LayoutParams) mContentHolder.getLayoutParams();
            lp.width = getResources().getDimensionPixelSize(R.dimen.notification_panel_width);
            lp.gravity = getResources().getInteger(R.integer.notification_panel_layout_gravity);
            mContentHolder.setLayoutParams(lp);
        }
    }

    public void setBar(PhoneStatusBar bar) {
        mBar = bar;
    }

    public ViewGroup getHolder() {
        return mContentHolder;
    }

    public boolean showNotification(NotificationData.Entry headsUp) {
        if (mHeadsUp != null && headsUp != null && !mHeadsUp.key.equals(headsUp.key)) {
            // bump any previous heads up back to the shade
            release();
        }

        mHeadsUp = headsUp;
        if (mContentHolder != null) {
            mContentHolder.removeAllViews();
        }

        if (mHeadsUp != null) {
            mHeadsUp.row.setSystemExpanded(true);
            mHeadsUp.row.setSensitive(false);
            mHeadsUp.row.setHideSensitive(
                    false, false /* animated */, 0 /* delay */, 0 /* duration */);
            if (mContentHolder == null) {
                // too soon!
                return false;
            }
            mContentHolder.setX(0);
            mContentHolder.setVisibility(View.VISIBLE);
            mContentHolder.setAlpha(mMaxAlpha);
            mContentHolder.addView(mHeadsUp.row);

            mSwipeHelper.snapChild(mContentHolder, 1f);
            mStartTouchTime = System.currentTimeMillis() + mTouchSensitivityDelay;

            mHeadsUp.setInterruption();

            // 2. Animate mHeadsUpNotificationView in
            mBar.scheduleHeadsUpOpen();

            // 3. Set alarm to age the notification off
            mBar.resetHeadsUpDecayTimer();
        }
        return true;
    }

    public boolean isShowing(String key) {
        return mHeadsUp != null && mHeadsUp.key.equals(key);
    }

    /** Discard the Heads Up notification. */
    public void clear() {
        mHeadsUp = null;
        mBar.scheduleHeadsUpClose();
    }

    /** Respond to dismissal of the Heads Up window. */
    public void dismiss() {
        if (mHeadsUp == null) return;
        if (mHeadsUp.notification.isClearable()) {
            mBar.onNotificationClear(mHeadsUp.notification);
        } else {
            release();
        }
        mHeadsUp = null;
        mBar.scheduleHeadsUpClose();
    }

    /** Push any current Heads Up notification down into the shade. */
    public void release() {
        if (mHeadsUp != null) {
            mBar.displayNotificationFromHeadsUp(mHeadsUp.notification);
        }
        mHeadsUp = null;
    }

    public void releaseAndClose() {
        release();
        mBar.scheduleHeadsUpClose();
    }

    public NotificationData.Entry getEntry() {
        return mHeadsUp;
    }

    public boolean isClearable() {
        return mHeadsUp == null || mHeadsUp.notification.isClearable();
    }

    // ViewGroup methods

    private static final ViewOutlineProvider CONTENT_HOLDER_OUTLINE_PROVIDER =
            new ViewOutlineProvider() {
        @Override
        public void getOutline(View view, Outline outline) {
            int outlineLeft = view.getPaddingLeft();
            int outlineTop = view.getPaddingTop();

            // Apply padding to shadow.
            outline.setRect(outlineLeft, outlineTop,
                    view.getWidth() - outlineLeft - view.getPaddingRight(),
                    view.getHeight() - outlineTop - view.getPaddingBottom());
        }
    };

    @Override
    public void onAttachedToWindow() {
        final ViewConfiguration viewConfiguration = ViewConfiguration.get(getContext());
        float touchSlop = viewConfiguration.getScaledTouchSlop();
        mSwipeHelper = new SwipeHelper(SwipeHelper.X, this, getContext());
        mSwipeHelper.setMaxSwipeProgress(mMaxAlpha);
        mEdgeSwipeHelper = new EdgeSwipeHelper(touchSlop);

        int minHeight = getResources().getDimensionPixelSize(R.dimen.notification_min_height);
        int maxHeight = getResources().getDimensionPixelSize(R.dimen.notification_max_height);
        mExpandHelper = new ExpandHelper(getContext(), this, minHeight, maxHeight);

        mContentHolder = (ViewGroup) findViewById(R.id.content_holder);
        mContentHolder.setOutlineProvider(CONTENT_HOLDER_OUTLINE_PROVIDER);

        if (mHeadsUp != null) {
            // whoops, we're on already!
            showNotification(mHeadsUp);
        }

        getViewTreeObserver().addOnComputeInternalInsetsListener(this);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (DEBUG) Log.v(TAG, "onInterceptTouchEvent()");
        if (System.currentTimeMillis() < mStartTouchTime) {
            return true;
        }
        return mEdgeSwipeHelper.onInterceptTouchEvent(ev)
                || mSwipeHelper.onInterceptTouchEvent(ev)
                || mExpandHelper.onInterceptTouchEvent(ev)
                || super.onInterceptTouchEvent(ev);
    }

    // View methods

    @Override
    public void onDraw(android.graphics.Canvas c) {
        super.onDraw(c);
        if (DEBUG) {
            //Log.d(TAG, "onDraw: canvas height: " + c.getHeight() + "px; measured height: "
            //        + getMeasuredHeight() + "px");
            c.save();
            c.clipRect(6, 6, c.getWidth() - 6, getMeasuredHeight() - 6,
                    android.graphics.Region.Op.DIFFERENCE);
            c.drawColor(0xFFcc00cc);
            c.restore();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (System.currentTimeMillis() < mStartTouchTime) {
            return false;
        }
        mBar.resetHeadsUpDecayTimer();
        return mEdgeSwipeHelper.onTouchEvent(ev)
                || mSwipeHelper.onTouchEvent(ev)
                || mExpandHelper.onTouchEvent(ev)
                || super.onTouchEvent(ev);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        float densityScale = getResources().getDisplayMetrics().density;
        mSwipeHelper.setDensityScale(densityScale);
        float pagingTouchSlop = ViewConfiguration.get(getContext()).getScaledPagingTouchSlop();
        mSwipeHelper.setPagingTouchSlop(pagingTouchSlop);
    }

    // ExpandHelper.Callback methods

    @Override
    public ExpandableView getChildAtRawPosition(float x, float y) {
        return getChildAtPosition(x, y);
    }

    @Override
    public ExpandableView getChildAtPosition(float x, float y) {
        return mHeadsUp == null ? null : mHeadsUp.row;
    }

    @Override
    public boolean canChildBeExpanded(View v) {
        return mHeadsUp != null && mHeadsUp.row == v && mHeadsUp.row.isExpandable();
    }

    @Override
    public void setUserExpandedChild(View v, boolean userExpanded) {
        if (mHeadsUp != null && mHeadsUp.row == v) {
            mHeadsUp.row.setUserExpanded(userExpanded);
        }
    }

    @Override
    public void setUserLockedChild(View v, boolean userLocked) {
        if (mHeadsUp != null && mHeadsUp.row == v) {
            mHeadsUp.row.setUserLocked(userLocked);
        }
    }

    @Override
    public void expansionStateChanged(boolean isExpanding) {

    }

    // SwipeHelper.Callback methods

    @Override
    public boolean canChildBeDismissed(View v) {
        return true;
    }

    @Override
    public boolean isAntiFalsingNeeded() {
        return false;
    }

    @Override
    public void onChildDismissed(View v) {
        Log.v(TAG, "User swiped heads up to dismiss");
        mBar.onHeadsUpDismissed();
    }

    @Override
    public void onBeginDrag(View v) {
    }

    @Override
    public void onDragCancelled(View v) {
        mContentHolder.setAlpha(mMaxAlpha); // sometimes this isn't quite reset
    }

    @Override
    public void onChildSnappedBack(View animView) {
    }

    @Override
    public boolean updateSwipeProgress(View animView, boolean dismissable, float swipeProgress) {
        getBackground().setAlpha((int) (255 * swipeProgress));
        return false;
    }

    @Override
    public View getChildAtPosition(MotionEvent ev) {
        return mContentHolder;
    }

    @Override
    public View getChildContentView(View v) {
        return mContentHolder;
    }

    @Override
    public void onComputeInternalInsets(ViewTreeObserver.InternalInsetsInfo info) {
        mContentHolder.getLocationOnScreen(mTmpTwoArray);

        info.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION);
        info.touchableRegion.set(mTmpTwoArray[0], mTmpTwoArray[1],
                mTmpTwoArray[0] + mContentHolder.getWidth(),
                mTmpTwoArray[1] + mContentHolder.getHeight());
    }

    public void escalate() {
        mBar.scheduleHeadsUpEscalation();
    }

    public String getKey() {
        return mHeadsUp == null ? null : mHeadsUp.notification.getKey();
    }

    private class EdgeSwipeHelper implements Gefingerpoken {
        private static final boolean DEBUG_EDGE_SWIPE = false;
        private final float mTouchSlop;
        private boolean mConsuming;
        private float mFirstY;
        private float mFirstX;

        public EdgeSwipeHelper(float touchSlop) {
            mTouchSlop = touchSlop;
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (DEBUG_EDGE_SWIPE) Log.d(TAG, "action down " + ev.getY());
                    mFirstX = ev.getX();
                    mFirstY = ev.getY();
                    mConsuming = false;
                    break;

                case MotionEvent.ACTION_MOVE:
                    if (DEBUG_EDGE_SWIPE) Log.d(TAG, "action move " + ev.getY());
                    final float dY = ev.getY() - mFirstY;
                    final float daX = Math.abs(ev.getX() - mFirstX);
                    final float daY = Math.abs(dY);
                    if (!mConsuming && (4f * daX) < daY && daY > mTouchSlop) {
                        if (dY > 0) {
                            if (DEBUG_EDGE_SWIPE) Log.d(TAG, "found an open");
                            mBar.animateExpandNotificationsPanel();
                        }
                        if (dY < 0) {
                            if (DEBUG_EDGE_SWIPE) Log.d(TAG, "found a close");
                            mBar.onHeadsUpDismissed();
                        }
                        mConsuming = true;
                    }
                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (DEBUG_EDGE_SWIPE) Log.d(TAG, "action done" );
                    mConsuming = false;
                    break;
            }
            return mConsuming;
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            return mConsuming;
        }
    }
}
