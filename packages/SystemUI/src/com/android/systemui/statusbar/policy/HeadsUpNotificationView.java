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
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Outline;
import android.graphics.Rect;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.ArrayMap;
import android.graphics.Outline;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.ExpandHelper;
import com.android.systemui.Gefingerpoken;
import com.android.systemui.R;
import com.android.systemui.SwipeHelper;
import com.android.systemui.statusbar.ExpandableView;
import com.android.systemui.statusbar.NotificationData;
import com.android.systemui.statusbar.phone.PhoneStatusBar;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;

public class HeadsUpNotificationView extends FrameLayout implements SwipeHelper.Callback, ExpandHelper.Callback,
        ViewTreeObserver.OnComputeInternalInsetsListener {
    private static final String TAG = "HeadsUpNotificationView";
    private static final boolean DEBUG = false;
    private static final boolean SPEW = DEBUG;
    private static final String SETTING_HEADS_UP_SNOOZE_LENGTH_MS = "heads_up_snooze_length_ms";

    Rect mTmpRect = new Rect();
    int[] mTmpTwoArray = new int[2];

    private final int mHeadsUpNotificationDecay;
    private final int mMinimumDisplayTime;

    private final int mTouchSensitivityDelay;
    private final float mMaxAlpha = 1f;
    private final ArrayMap<String, Long> mSnoozedPackages;
    private final int mDefaultSnoozeLengthMs;

    private SwipeHelper mSwipeHelper;
    private EdgeSwipeHelper mEdgeSwipeHelper;

    private PhoneStatusBar mBar;

    private long mLingerUntilMs;
    private long mStartTouchTime;
    private ViewGroup mContentHolder;
    private int mSnoozeLengthMs;
    private ContentObserver mSettingsObserver;

    private NotificationData.Entry mHeadsUp;
    private int mUser;
    private String mMostRecentPackageName;
    private boolean mTouched;
    private Clock mClock;

    public static class Clock {
        public long currentTimeMillis() {
            return SystemClock.elapsedRealtime();
        }
    }

    public HeadsUpNotificationView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public HeadsUpNotificationView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        Resources resources = context.getResources();
        mTouchSensitivityDelay = resources.getInteger(R.integer.heads_up_sensitivity_delay);
        if (DEBUG) Log.v(TAG, "create() " + mTouchSensitivityDelay);
        mSnoozedPackages = new ArrayMap<>();
        mDefaultSnoozeLengthMs = resources.getInteger(R.integer.heads_up_default_snooze_length_ms);
        mSnoozeLengthMs = mDefaultSnoozeLengthMs;
        mMinimumDisplayTime = resources.getInteger(R.integer.heads_up_notification_minimum_time);
        mHeadsUpNotificationDecay = resources.getInteger(R.integer.heads_up_notification_decay);
        mClock = new Clock();
    }

    @VisibleForTesting
    public HeadsUpNotificationView(Context context, Clock clock, SwipeHelper swipeHelper,
            EdgeSwipeHelper edgeSwipeHelper, int headsUpNotificationDecay, int minimumDisplayTime,
            int touchSensitivityDelay, int snoozeLength) {
        super(context, null);
        mClock = clock;
        mSwipeHelper = swipeHelper;
        mEdgeSwipeHelper = edgeSwipeHelper;
        mMinimumDisplayTime = minimumDisplayTime;
        mHeadsUpNotificationDecay = headsUpNotificationDecay;
        mTouchSensitivityDelay = touchSensitivityDelay;
        mSnoozedPackages = new ArrayMap<>();
        mDefaultSnoozeLengthMs = snoozeLength;
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

    public PhoneStatusBar getBar() {
        return mBar;
    }

    public ViewGroup getHolder() {
        return mContentHolder;
    }

    /**
     * Called when posting a new notification to the heads up.
     */
    public void showNotification(NotificationData.Entry headsUp) {
        if (DEBUG) Log.v(TAG, "showNotification");
        if (mHeadsUp != null) {
            // bump any previous heads up back to the shade
            releaseImmediately();
        }
        mTouched = false;
        updateNotification(headsUp, true);
        mLingerUntilMs = mClock.currentTimeMillis() + mMinimumDisplayTime;
    }

    /**
     * Called when updating or posting a notification to the heads up.
     */
    public void updateNotification(NotificationData.Entry headsUp, boolean alert) {
        if (DEBUG) Log.v(TAG, "updateNotification");

        if (alert) {
            mBar.scheduleHeadsUpDecay(mHeadsUpNotificationDecay);
        }
        invalidate();

        if (mHeadsUp == headsUp) {
            // This is an in-place update.  Noting more to do.
            return;
        }

        mHeadsUp = headsUp;

        if (mContentHolder != null) {
            mContentHolder.removeAllViews();
        }

        if (mHeadsUp != null) {
            mMostRecentPackageName = mHeadsUp.notification.getPackageName();
            if (mHeadsUp.row != null) {  // only null in tests
                mHeadsUp.row.setSystemExpanded(true);
                mHeadsUp.row.setSensitive(false);
                mHeadsUp.row.setHeadsUp(true);
                mHeadsUp.row.setHideSensitive(
                        false, false /* animated */, 0 /* delay */, 0 /* duration */);
            }

            mStartTouchTime = SystemClock.elapsedRealtime() + mTouchSensitivityDelay;
            if (mContentHolder != null) {  // only null in tests and before we are attached to a window
                mContentHolder.setX(0);
                mContentHolder.setVisibility(View.VISIBLE);
                mContentHolder.setAlpha(mMaxAlpha);
                mContentHolder.addView(mHeadsUp.row);
                sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);

                mSwipeHelper.snapChild(mContentHolder, 1f);
            }

            mHeadsUp.setInterruption();

            // Make sure the heads up window is open.
            mBar.scheduleHeadsUpOpen();
        }
    }

    /**
     * Possibly enter the lingering state by delaying the closing of the window.
     *
     * @return true if the notification has entered the lingering state.
     */
    private boolean startLingering(boolean removed) {
        final long now = mClock.currentTimeMillis();
        if (!mTouched && mHeadsUp != null && now < mLingerUntilMs) {
            if (removed) {
                mHeadsUp = null;
            }
            mBar.scheduleHeadsUpDecay(mLingerUntilMs - now);
            return true;
        }
        return false;
    }

    /**
     * React to the removal of the notification in the heads up.
     */
    public void removeNotification(String key) {
        if (DEBUG) Log.v(TAG, "remove");
        if (mHeadsUp == null || !mHeadsUp.key.equals(key)) {
            return;
        }
        if (!startLingering(/* removed */ true)) {
            mHeadsUp = null;
            releaseImmediately();
        }
    }

    /**
     * Ask for any current Heads Up notification to be pushed down into the shade.
     */
    public void release() {
        if (DEBUG) Log.v(TAG, "release");
        if (!startLingering(/* removed */ false)) {
            releaseImmediately();
        }
    }

    /**
     * Push any current Heads Up notification down into the shade.
     */
    public void releaseImmediately() {
        if (DEBUG) Log.v(TAG, "releaseImmediately");
        if (mHeadsUp != null) {
            mBar.displayNotificationFromHeadsUp(mHeadsUp.notification);
        }
        mHeadsUp = null;
        mBar.scheduleHeadsUpClose();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (DEBUG) Log.v(TAG, "onVisibilityChanged: " + visibility);
        if (changedView.getVisibility() == VISIBLE) {
            mStartTouchTime = mClock.currentTimeMillis() + mTouchSensitivityDelay;
            sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        }
    }

    public boolean isSnoozed(String packageName) {
        final String key = snoozeKey(packageName, mUser);
        Long snoozedUntil = mSnoozedPackages.get(key);
        if (snoozedUntil != null) {
            if (snoozedUntil > SystemClock.elapsedRealtime()) {
                if (DEBUG) Log.v(TAG, key + " snoozed");
                return true;
            }
            mSnoozedPackages.remove(packageName);
        }
        return false;
    }

    private void snooze() {
        if (mMostRecentPackageName != null) {
            mSnoozedPackages.put(snoozeKey(mMostRecentPackageName, mUser),
                    SystemClock.elapsedRealtime() + mSnoozeLengthMs);
        }
        releaseImmediately();
    }

    private static String snoozeKey(String packageName, int user) {
        return user + "," + packageName;
    }

    public boolean isShowing(String key) {
        return mHeadsUp != null && mHeadsUp.key.equals(key);
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
        mEdgeSwipeHelper = new EdgeSwipeHelper(this, touchSlop);

        int minHeight = getResources().getDimensionPixelSize(R.dimen.notification_min_height);
        int maxHeight = getResources().getDimensionPixelSize(R.dimen.notification_max_height);

        mContentHolder = (ViewGroup) findViewById(R.id.content_holder);
        mContentHolder.setOutlineProvider(CONTENT_HOLDER_OUTLINE_PROVIDER);

        mSnoozeLengthMs = Settings.Global.getInt(mContext.getContentResolver(),
                SETTING_HEADS_UP_SNOOZE_LENGTH_MS, mDefaultSnoozeLengthMs);
        mSettingsObserver = new ContentObserver(getHandler()) {
            @Override
            public void onChange(boolean selfChange) {
                final int packageSnoozeLengthMs = Settings.Global.getInt(
                        mContext.getContentResolver(), SETTING_HEADS_UP_SNOOZE_LENGTH_MS, -1);
                if (packageSnoozeLengthMs > -1 && packageSnoozeLengthMs != mSnoozeLengthMs) {
                    mSnoozeLengthMs = packageSnoozeLengthMs;
                    if (DEBUG) Log.v(TAG, "mSnoozeLengthMs = " + mSnoozeLengthMs);
                }
            }
        };
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(SETTING_HEADS_UP_SNOOZE_LENGTH_MS), false,
                mSettingsObserver);
        if (DEBUG) Log.v(TAG, "mSnoozeLengthMs = " + mSnoozeLengthMs);

        if (mHeadsUp != null) {
            // whoops, we're on already!
            showNotification(mHeadsUp);
        }

        getViewTreeObserver().addOnComputeInternalInsetsListener(this);
    }


    @Override
    protected void onDetachedFromWindow() {
        mContext.getContentResolver().unregisterContentObserver(mSettingsObserver);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (DEBUG) Log.v(TAG, "onInterceptTouchEvent()");
        if (mClock.currentTimeMillis() < mStartTouchTime) {
            return true;
        }
        mTouched = true;
        return mEdgeSwipeHelper.onInterceptTouchEvent(ev)
                || mSwipeHelper.onInterceptTouchEvent(ev)
                || mHeadsUp == null // lingering
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
        if (mClock.currentTimeMillis() < mStartTouchTime) {
            return false;
        }

        final boolean wasRemoved = mHeadsUp == null;
        if (!wasRemoved) {
            mBar.scheduleHeadsUpDecay(mHeadsUpNotificationDecay);
        }
        return mEdgeSwipeHelper.onTouchEvent(ev)
                || mSwipeHelper.onTouchEvent(ev)
                || wasRemoved
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
    public float getFalsingThresholdFactor() {
        return 1.0f;
    }

    @Override
    public void onChildDismissed(View v) {
        Log.v(TAG, "User swiped heads up to dismiss");
        if (mHeadsUp != null && mHeadsUp.notification.isClearable()) {
            mBar.onNotificationClear(mHeadsUp.notification);
            mHeadsUp = null;
        }
        releaseImmediately();
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

    public void setUser(int user) {
        mUser = user;
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("HeadsUpNotificationView state:");
        pw.print("  mTouchSensitivityDelay="); pw.println(mTouchSensitivityDelay);
        pw.print("  mSnoozeLengthMs="); pw.println(mSnoozeLengthMs);
        pw.print("  mLingerUntilMs="); pw.println(mLingerUntilMs);
        pw.print("  mTouched="); pw.println(mTouched);
        pw.print("  mMostRecentPackageName="); pw.println(mMostRecentPackageName);
        pw.print("  mStartTouchTime="); pw.println(mStartTouchTime);
        pw.print("  now="); pw.println(SystemClock.elapsedRealtime());
        pw.print("  mUser="); pw.println(mUser);
        if (mHeadsUp == null) {
            pw.println("  mHeadsUp=null");
        } else {
            pw.print("  mHeadsUp="); pw.println(mHeadsUp.notification.getKey());
        }
        int N = mSnoozedPackages.size();
        pw.println("  snoozed packages: " + N);
        for (int i = 0; i < N; i++) {
            pw.print("    "); pw.print(mSnoozedPackages.valueAt(i));
            pw.print(", "); pw.println(mSnoozedPackages.keyAt(i));
        }
    }

    public static class EdgeSwipeHelper implements Gefingerpoken {
        private static final boolean DEBUG_EDGE_SWIPE = false;
        private final float mTouchSlop;
        private final HeadsUpNotificationView mHeadsUpView;
        private boolean mConsuming;
        private float mFirstY;
        private float mFirstX;

        public EdgeSwipeHelper(HeadsUpNotificationView headsUpView, float touchSlop) {
            mHeadsUpView = headsUpView;
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
                    if (!mConsuming && daX < daY && daY > mTouchSlop) {
                        mHeadsUpView.snooze();
                        if (dY > 0) {
                            if (DEBUG_EDGE_SWIPE) Log.d(TAG, "found an open");
                            mHeadsUpView.getBar().animateExpandNotificationsPanel();
                        }
                        mConsuming = true;
                    }
                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (DEBUG_EDGE_SWIPE) Log.d(TAG, "action done");
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
