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

package android.view;

import android.annotation.NonNull;
import android.compat.annotation.UnsupportedAppUsage;
import android.graphics.Rect;
import android.graphics.Region;
import android.util.ArrayMap;
import android.view.accessibility.AccessibilityNodeInfo.TouchDelegateInfo;

/**
 * Helper class to handle situations where you want a view to have a larger touch area than its
 * actual view bounds. The view whose touch area is changed is called the delegate view. This
 * class should be used by an ancestor of the delegate. To use a TouchDelegate, first create an
 * instance that specifies the bounds that should be mapped to the delegate and the delegate
 * view itself.
 * <p>
 * The ancestor should then forward all of its touch events received in its
 * {@link android.view.View#onTouchEvent(MotionEvent)} to {@link #onTouchEvent(MotionEvent)}.
 * </p>
 */
public class TouchDelegate {

    /**
     * View that should receive forwarded touch events
     */
    private View mDelegateView;

    /**
     * Bounds in local coordinates of the containing view that should be mapped to the delegate
     * view. This rect is used for initial hit testing.
     */
    private Rect mBounds;

    /**
     * mBounds inflated to include some slop. This rect is to track whether the motion events
     * should be considered to be within the delegate view.
     */
    private Rect mSlopBounds;

    /**
     * True if the delegate had been targeted on a down event (intersected mBounds).
     */
    @UnsupportedAppUsage
    private boolean mDelegateTargeted;

    /**
     * The touchable region of the View extends above its actual extent.
     */
    public static final int ABOVE = 1;

    /**
     * The touchable region of the View extends below its actual extent.
     */
    public static final int BELOW = 2;

    /**
     * The touchable region of the View extends to the left of its actual extent.
     */
    public static final int TO_LEFT = 4;

    /**
     * The touchable region of the View extends to the right of its actual extent.
     */
    public static final int TO_RIGHT = 8;

    private int mSlop;

    /**
     * Touch delegate information for accessibility
     */
    private TouchDelegateInfo mTouchDelegateInfo;

    /**
     * Constructor
     *
     * @param bounds Bounds in local coordinates of the containing view that should be mapped to
     *        the delegate view
     * @param delegateView The view that should receive motion events
     */
    public TouchDelegate(Rect bounds, View delegateView) {
        mBounds = bounds;

        mSlop = ViewConfiguration.get(delegateView.getContext()).getScaledTouchSlop();
        mSlopBounds = new Rect(bounds);
        mSlopBounds.inset(-mSlop, -mSlop);
        mDelegateView = delegateView;
    }

    /**
     * Forward touch events to the delegate view if the event is within the bounds
     * specified in the constructor.
     *
     * @param event The touch event to forward
     * @return True if the event was consumed by the delegate, false otherwise.
     */
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        int x = (int)event.getX();
        int y = (int)event.getY();
        boolean sendToDelegate = false;
        boolean hit = true;
        boolean handled = false;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDelegateTargeted = mBounds.contains(x, y);
                sendToDelegate = mDelegateTargeted;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_MOVE:
                sendToDelegate = mDelegateTargeted;
                if (sendToDelegate) {
                    Rect slopBounds = mSlopBounds;
                    if (!slopBounds.contains(x, y)) {
                        hit = false;
                    }
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                sendToDelegate = mDelegateTargeted;
                mDelegateTargeted = false;
                break;
        }
        if (sendToDelegate) {
            if (hit) {
                // Offset event coordinates to be inside the target view
                event.setLocation(mDelegateView.getWidth() / 2, mDelegateView.getHeight() / 2);
            } else {
                // Offset event coordinates to be outside the target view (in case it does
                // something like tracking pressed state)
                int slop = mSlop;
                event.setLocation(-(slop * 2), -(slop * 2));
            }
            handled = mDelegateView.dispatchTouchEvent(event);
        }
        return handled;
    }

    /**
     * Forward hover events to the delegate view if the event is within the bounds
     * specified in the constructor and touch exploration is enabled.
     *
     * <p>This method is provided for accessibility purposes so touch exploration, which is
     * commonly used by screen readers, can properly place accessibility focus on views that
     * use touch delegates. Therefore, touch exploration must be enabled for hover events
     * to be dispatched through the delegate.</p>
     *
     * @param event The hover event to forward
     * @return True if the event was consumed by the delegate, false otherwise.
     *
     * @see android.view.accessibility.AccessibilityManager#isTouchExplorationEnabled
     */
    public boolean onTouchExplorationHoverEvent(@NonNull MotionEvent event) {
        if (mBounds == null) {
            return false;
        }

        final int x = (int) event.getX();
        final int y = (int) event.getY();
        boolean hit = true;
        boolean handled = false;

        final boolean isInbound = mBounds.contains(x, y);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_HOVER_ENTER:
                mDelegateTargeted = isInbound;
                break;
            case MotionEvent.ACTION_HOVER_MOVE:
                if (isInbound) {
                    mDelegateTargeted = true;
                } else {
                    // delegated previously
                    if (mDelegateTargeted && !mSlopBounds.contains(x, y)) {
                        hit = false;
                    }
                }
                break;
            case MotionEvent.ACTION_HOVER_EXIT:
                mDelegateTargeted = true;
                break;
        }
        if (mDelegateTargeted) {
            if (hit) {
                event.setLocation(mDelegateView.getWidth() / 2, mDelegateView.getHeight() / 2);
            } else {
                mDelegateTargeted = false;
            }
            handled = mDelegateView.dispatchHoverEvent(event);
        }
        return handled;
    }

    /**
     * Return a {@link TouchDelegateInfo} mapping from regions (in view coordinates) to
     * delegated views for accessibility usage.
     *
     * @return A TouchDelegateInfo.
     */
    @NonNull
    public TouchDelegateInfo getTouchDelegateInfo() {
        if (mTouchDelegateInfo == null) {
            final ArrayMap<Region, View> targetMap = new ArrayMap<>(1);
            Rect bounds = mBounds;
            if (bounds == null) {
                bounds = new Rect();
            }
            targetMap.put(new Region(bounds), mDelegateView);
            mTouchDelegateInfo = new TouchDelegateInfo(targetMap);
        }
        return mTouchDelegateInfo;
    }
}
