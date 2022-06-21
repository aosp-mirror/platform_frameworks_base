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

package android.view;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Rect;
import android.view.inputmethod.InputMethodManager;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Initiates handwriting mode once it detects stylus movement in handwritable areas.
 *
 * It is designed to be used by  {@link ViewRootImpl}. For every stylus related MotionEvent that is
 * dispatched to view tree, ViewRootImpl should call {@link #onTouchEvent} method of this class.
 * And it will automatically request to enter the handwriting mode when the conditions meet.
 *
 * Notice that ViewRootImpl should still dispatch MotionEvents to view tree as usual.
 * And if it successfully enters the handwriting mode, the ongoing MotionEvent stream will be
 * routed to the input method. Input system will fabricate an ACTION_CANCEL and send to
 * ViewRootImpl.
 *
 * This class does nothing if:
 * a) MotionEvents are not from stylus.
 * b) The user taps or long-clicks with a stylus etc.
 * c) Stylus pointer down position is not within a handwritable area.
 *
 * Used by InputMethodManager.
 * @hide
 */
public class HandwritingInitiator {
    /**
     * The maximum amount of distance a stylus touch can wander before it is considered
     * handwriting.
     */
    private final int mHandwritingSlop;
    /**
     * The timeout used to distinguish tap or long click from handwriting. If the stylus doesn't
     * move before this timeout, it's not considered as handwriting.
     */
    private final long mHandwritingTimeoutInMillis;

    private State mState;
    private final HandwritingAreaTracker mHandwritingAreasTracker = new HandwritingAreaTracker();

    /** The reference to the View that currently has the input connection. */
    @Nullable
    @VisibleForTesting
    public WeakReference<View> mConnectedView = null;

    /**
     * When InputConnection restarts for a View, View#onInputConnectionCreatedInternal
     * might be called before View#onInputConnectionClosedInternal, so we need to count the input
     * connections and only set mConnectedView to null when mConnectionCount is zero.
     */
    private int mConnectionCount = 0;
    private final InputMethodManager mImm;

    @VisibleForTesting
    public HandwritingInitiator(@NonNull ViewConfiguration viewConfiguration,
            @NonNull InputMethodManager inputMethodManager) {
        mHandwritingSlop = viewConfiguration.getScaledHandwritingSlop();
        mHandwritingTimeoutInMillis = ViewConfiguration.getLongPressTimeout();
        mImm = inputMethodManager;
    }

    /**
     * Notify the HandwritingInitiator that a new MotionEvent has arrived.
     *
     * <p>The return value indicates whether the event has been fully handled by the
     * HandwritingInitiator and should not be dispatched to the view tree. This will be true for
     * ACTION_MOVE events from a stylus gesture after handwriting mode has been initiated, in order
     * to suppress other actions such as scrolling.
     *
     * <p>If HandwritingInitiator triggers the handwriting mode, a fabricated ACTION_CANCEL event
     * will be sent to the ViewRootImpl.
     *
     * @param motionEvent the stylus {@link MotionEvent}
     * @return true if the event has been fully handled by the {@link HandwritingInitiator} and
     * should not be dispatched to the {@link View} tree, or false if the event should be dispatched
     * to the {@link View} tree as usual
     */
    @VisibleForTesting
    public boolean onTouchEvent(@NonNull MotionEvent motionEvent) {
        final int maskedAction = motionEvent.getActionMasked();
        switch (maskedAction) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                mState = null;
                final int actionIndex = motionEvent.getActionIndex();
                final int toolType = motionEvent.getToolType(actionIndex);
                // TOOL_TYPE_ERASER is also from stylus. This indicates that the user is holding
                // the eraser button during handwriting.
                if (toolType != MotionEvent.TOOL_TYPE_STYLUS
                        && toolType != MotionEvent.TOOL_TYPE_ERASER) {
                    // The motion event is not from a stylus event, ignore it.
                    return false;
                }
                if (!mImm.isStylusHandwritingAvailable()) {
                    return false;
                }
                mState = new State(motionEvent);
                break;
            case MotionEvent.ACTION_POINTER_UP:
                final int pointerId = motionEvent.getPointerId(motionEvent.getActionIndex());
                if (mState == null || pointerId != mState.mStylusPointerId) {
                    // ACTION_POINTER_UP is from another stylus pointer, ignore the event.
                    return false;
                }
                // Deliberately fall through.
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                // If it's ACTION_CANCEL or ACTION_UP, all the pointers go up. There is no need to
                // check whether the stylus we are tracking goes up.
                if (mState != null) {
                    mState.mShouldInitHandwriting = false;
                }
                return false;
            case MotionEvent.ACTION_MOVE:
                if (mState == null) {
                    return false;
                }

                // Either we've already tried to initiate handwriting, or the ongoing MotionEvent
                // sequence is considered to be tap, long-click or other gestures.
                if (!mState.mShouldInitHandwriting || mState.mExceedHandwritingSlop) {
                    return mState.mHasInitiatedHandwriting;
                }

                final long timeElapsed =
                        motionEvent.getEventTime() - mState.mStylusDownTimeInMillis;
                if (timeElapsed > mHandwritingTimeoutInMillis) {
                    mState.mShouldInitHandwriting = false;
                    return mState.mHasInitiatedHandwriting;
                }

                final int pointerIndex = motionEvent.findPointerIndex(mState.mStylusPointerId);
                final float x = motionEvent.getX(pointerIndex);
                final float y = motionEvent.getY(pointerIndex);
                if (largerThanTouchSlop(x, y, mState.mStylusDownX, mState.mStylusDownY)) {
                    mState.mExceedHandwritingSlop = true;
                    View candidateView =
                            findBestCandidateView(mState.mStylusDownX, mState.mStylusDownY);
                    if (candidateView != null) {
                        if (candidateView == getConnectedView()) {
                            startHandwriting(candidateView);
                        } else {
                            candidateView.requestFocus();
                        }
                    }
                }
                return mState.mHasInitiatedHandwriting;
        }
        return false;
    }

    @Nullable
    private View getConnectedView() {
        if (mConnectedView == null) return null;
        return mConnectedView.get();
    }

    private void clearConnectedView() {
        mConnectedView = null;
        mConnectionCount = 0;
    }

    /**
     * Notify HandwritingInitiator that a new InputConnection is created.
     * The caller of this method should guarantee that each onInputConnectionCreated call
     * is paired with a onInputConnectionClosed call.
     * @param view the view that created the current InputConnection.
     * @see  #onInputConnectionClosed(View)
     */
    public void onInputConnectionCreated(@NonNull View view) {
        if (!view.isAutoHandwritingEnabled()) {
            clearConnectedView();
            return;
        }

        final View connectedView = getConnectedView();
        if (connectedView == view) {
            ++mConnectionCount;
        } else {
            mConnectedView = new WeakReference<>(view);
            mConnectionCount = 1;
            if (mState != null && mState.mShouldInitHandwriting) {
                tryStartHandwriting();
            }
        }
    }

    /**
     * Notify HandwritingInitiator that the InputConnection has closed for the given view.
     * The caller of this method should guarantee that each onInputConnectionClosed call
     * is paired with a onInputConnectionCreated call.
     * @param view the view that closed the InputConnection.
     */
    public void onInputConnectionClosed(@NonNull View view) {
        final View connectedView = getConnectedView();
        if (connectedView == null) return;
        if (connectedView == view) {
            --mConnectionCount;
            if (mConnectionCount == 0) {
                clearConnectedView();
            }
        } else {
            // Unexpected branch, set mConnectedView to null to avoid further problem.
            clearConnectedView();
        }
    }

    /**
     * Try to initiate handwriting. For this method to successfully send startHandwriting signal,
     * the following 3 conditions should meet:
     *   a) The stylus movement exceeds the touchSlop.
     *   b) A View has built InputConnection with IME.
     *   c) The stylus event lands into the connected View's boundary.
     * This method will immediately fail without any side effect if condition a or b is not met.
     * However, if both condition a and b are met but the condition c is not met, it will reset the
     * internal states. And HandwritingInitiator won't attempt to call startHandwriting until the
     * next ACTION_DOWN.
     */
    private void tryStartHandwriting() {
        if (!mState.mExceedHandwritingSlop) {
            return;
        }
        final View connectedView = getConnectedView();
        if (connectedView == null) {
            return;
        }

        if (!connectedView.isAutoHandwritingEnabled()) {
            clearConnectedView();
            return;
        }

        final Rect handwritingArea = getViewHandwritingArea(connectedView);
        if (isInHandwritingArea(handwritingArea, mState.mStylusDownX,
                mState.mStylusDownY, connectedView)) {
            startHandwriting(connectedView);
        } else {
            mState.mShouldInitHandwriting = false;
        }
    }

    /** For test only. */
    @VisibleForTesting
    public void startHandwriting(@NonNull View view) {
        mImm.startStylusHandwriting(view);
        mState.mHasInitiatedHandwriting = true;
        mState.mShouldInitHandwriting = false;
    }

    /**
     * Notify that the handwriting area for the given view might be updated.
     * @param view the view whose handwriting area might be updated.
     */
    public void updateHandwritingAreasForView(@NonNull View view) {
        mHandwritingAreasTracker.updateHandwritingAreaForView(view);
    }

    /**
     * Given the location of the stylus event, return the best candidate view to initialize
     * handwriting mode.
     *
     * @param x the x coordinates of the stylus event, in the coordinates of the window.
     * @param y the y coordinates of the stylus event, in the coordinates of the window.
     */
    @Nullable
    private View findBestCandidateView(float x, float y) {
        float minDistance = Float.MAX_VALUE;
        View bestCandidate = null;

        // If the connectedView is not null and do not set any handwriting area, it will check
        // whether the connectedView's boundary contains the initial stylus position. If true,
        // directly return the connectedView.
        final View connectedView = getConnectedView();
        if (connectedView != null && connectedView.isAutoHandwritingEnabled()) {
            Rect handwritingArea = getViewHandwritingArea(connectedView);
            if (isInHandwritingArea(handwritingArea, x, y, connectedView)) {
                final float distance = distance(handwritingArea, x, y);
                if (distance == 0f) return connectedView;

                bestCandidate = connectedView;
                minDistance = distance;
            }
        }

        // Check the registered handwriting areas.
        final List<HandwritableViewInfo> handwritableViewInfos =
                mHandwritingAreasTracker.computeViewInfos();
        for (HandwritableViewInfo viewInfo : handwritableViewInfos) {
            final View view = viewInfo.getView();
            final Rect handwritingArea = viewInfo.getHandwritingArea();
            if (!isInHandwritingArea(handwritingArea, x, y, view)) continue;

            final float distance = distance(handwritingArea, x, y);

            if (distance == 0f) return view;
            if (distance < minDistance) {
                minDistance = distance;
                bestCandidate = view;
            }
        }
        return bestCandidate;
    }

    /**
     *  Return the square of the distance from point (x, y) to the given rect, which is mainly used
     *  for comparison. The distance is defined to be: the shortest distance between (x, y) to any
     *  point on rect. When (x, y) is contained by the rect, return 0f.
     */
    private static float distance(@NonNull Rect rect, float x, float y) {
        if (contains(rect, x, y, 0f, 0f, 0f, 0f)) {
            return 0f;
        }

        /* The distance between point (x, y) and rect, there are 2 basic cases:
         * a) The distance is the distance from (x, y) to the closest corner on rect.
         *                    o |     |
         *         ---+-----+---
         *            |     |
         *         ---+-----+---
         *            |     |
         * b) The distance is the distance from (x, y) to the closest edge on rect.
         *                      |  o  |
         *         ---+-----+---
         *            |     |
         *         ---+-----+---
         *            |     |
         * We define xDistance as following(similar for yDistance):
         *   If x is in [left, right) 0, else min(abs(x - left), abs(x - y))
         * For case a, sqrt(xDistance^2 + yDistance^2) is the final distance.
         * For case b, distance should be yDistance, which is also equal to
         * sqrt(xDistance^2 + yDistance^2) because xDistance is 0.
         */
        final float xDistance;
        if (x >= rect.left && x < rect.right) {
            xDistance = 0f;
        } else if (x < rect.left) {
            xDistance = rect.left - x;
        } else {
            xDistance = x - rect.right;
        }

        final float yDistance;
        if (y >= rect.top && y < rect.bottom) {
            yDistance = 0f;
        } else if (y < rect.top) {
            yDistance = rect.top - y;
        } else {
            yDistance = y - rect.bottom;
        }
        // We can omit sqrt here because we only need the distance for comparison.
        return xDistance * xDistance + yDistance * yDistance;
    }

    /**
     * Return the handwriting area of the given view, represented in the window's coordinate.
     * If the view didn't set any handwriting area, it will return the view's boundary.
     * It will return null if the view or its handwriting area is not visible.
     *
     * The handwriting area is clipped to its visible part.
     * Notice that the returned rectangle is the view's original handwriting area without the
     * view's handwriting area extends.
     */
    @Nullable
    private static Rect getViewHandwritingArea(@NonNull View view) {
        final ViewParent viewParent = view.getParent();
        if (viewParent != null && view.isAttachedToWindow() && view.isAggregatedVisible()) {
            final Rect localHandwritingArea = view.getHandwritingArea();
            final Rect globalHandwritingArea = new Rect();
            if (localHandwritingArea != null) {
                globalHandwritingArea.set(localHandwritingArea);
            } else {
                globalHandwritingArea.set(0, 0, view.getWidth(), view.getHeight());
            }
            if (viewParent.getChildVisibleRect(view, globalHandwritingArea, null)) {
                return globalHandwritingArea;
            }
        }
        return null;
    }

    /**
     * Return true if the (x, y) is inside by the given {@link Rect} with the View's
     * handwriting bounds with offsets applied.
     */
    private boolean isInHandwritingArea(@Nullable Rect handwritingArea,
            float x, float y, View view) {
        if (handwritingArea == null) return false;

        return contains(handwritingArea, x, y,
                view.getHandwritingBoundsOffsetLeft(),
                view.getHandwritingBoundsOffsetTop(),
                view.getHandwritingBoundsOffsetRight(),
                view.getHandwritingBoundsOffsetBottom());
    }

    /**
     * Return true if the (x, y) is inside by the given {@link Rect} offset by the given
     * offsetLeft, offsetTop, offsetRight and offsetBottom.
     */
    private static boolean contains(@NonNull Rect rect, float x, float y,
            float offsetLeft, float offsetTop, float offsetRight, float offsetBottom) {
        return x >= rect.left - offsetLeft && x < rect.right  + offsetRight
                && y >= rect.top - offsetTop && y < rect.bottom + offsetBottom;
    }

    private boolean largerThanTouchSlop(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2;
        float dy = y1 - y2;
        return dx * dx + dy * dy > mHandwritingSlop * mHandwritingSlop;
    }

    /** Object that keeps the MotionEvent related states for HandwritingInitiator. */
    private static class State {
        /**
         * Whether it should initiate handwriting mode for the current MotionEvent sequence.
         * (A series of MotionEvents from ACTION_DOWN to ACTION_UP)
         *
         * The purpose of this boolean value is:
         * a) We should only request to start handwriting mode ONCE for each MotionEvent sequence.
         * If we've already requested to enter handwriting mode for the ongoing MotionEvent
         * sequence, this boolean is set to false. And it won't request to start handwriting again.
         *
         * b) If the MotionEvent sequence is considered to be tap, long-click or other gestures.
         * This boolean will be set to false, and it won't request to start handwriting.
         */
        private boolean mShouldInitHandwriting;
        /**
         * Whether handwriting mode has already been initiated for the current MotionEvent sequence.
         */
        private boolean mHasInitiatedHandwriting;
        /**
         * Whether the current ongoing stylus MotionEvent sequence already exceeds the
         * handwriting slop.
         * It's used for the case where the stylus exceeds handwriting slop before the target View
         * built InputConnection.
         */
        private boolean mExceedHandwritingSlop;

        /** The pointer id of the stylus pointer that is being tracked. */
        private final int mStylusPointerId;
        /** The time stamp when the stylus pointer goes down. */
        private final long mStylusDownTimeInMillis;
        /** The initial location where the stylus pointer goes down. */
        private final float mStylusDownX;
        private final float mStylusDownY;

        private State(MotionEvent motionEvent) {
            final int actionIndex = motionEvent.getActionIndex();
            mStylusPointerId = motionEvent.getPointerId(actionIndex);
            mStylusDownTimeInMillis = motionEvent.getEventTime();
            mStylusDownX = motionEvent.getX(actionIndex);
            mStylusDownY = motionEvent.getY(actionIndex);

            mShouldInitHandwriting = true;
            mHasInitiatedHandwriting = false;
            mExceedHandwritingSlop = false;
        }
    }

    /** The helper method to check if the given view is still active for handwriting. */
    private static boolean isViewActive(@Nullable View view) {
        return view != null && view.isAttachedToWindow() && view.isAggregatedVisible()
                && view.isAutoHandwritingEnabled();
    }

    /**
     * A class used to track the handwriting areas set by the Views.
     *
     * @hide
     */
    @VisibleForTesting
    public static class HandwritingAreaTracker {
        private final List<HandwritableViewInfo> mHandwritableViewInfos;

        public HandwritingAreaTracker() {
            mHandwritableViewInfos = new ArrayList<>();
        }

        /**
         * Notify this tracker that the handwriting area of the given view has been updated.
         * This method does three things:
         * a) iterate over the all the tracked ViewInfos and remove those already invalid ones.
         * b) mark the given view's ViewInfo to be dirty. So that next time when
         * {@link #computeViewInfos} is called, this view's handwriting area will be recomputed.
         * c) If no the given view is not in the tracked ViewInfo list, a new ViewInfo object will
         * be created and added to the list.
         *
         * @param view the view whose handwriting area is updated.
         */
        public void updateHandwritingAreaForView(@NonNull View view) {
            Iterator<HandwritableViewInfo> iterator = mHandwritableViewInfos.iterator();
            boolean found = false;
            while (iterator.hasNext()) {
                final HandwritableViewInfo handwritableViewInfo = iterator.next();
                final View curView = handwritableViewInfo.getView();
                if (!isViewActive(curView)) {
                    iterator.remove();
                }
                if (curView == view) {
                    found = true;
                    handwritableViewInfo.mIsDirty = true;
                }
            }
            if (!found && isViewActive(view)) {
                // The given view is not tracked. Create a new HandwritableViewInfo for it and add
                // to the list.
                mHandwritableViewInfos.add(new HandwritableViewInfo(view));
            }
        }

        /**
         * Update the handwriting areas and return a list of ViewInfos containing the view
         * reference and its handwriting area.
         */
        @NonNull
        public List<HandwritableViewInfo> computeViewInfos() {
            mHandwritableViewInfos.removeIf(viewInfo -> !viewInfo.update());
            return mHandwritableViewInfos;
        }
    }

    /**
     * A class that reference to a View and its handwriting area(in the ViewRoot's coordinate.)
     *
     * @hide
     */
    @VisibleForTesting
    public static class HandwritableViewInfo {
        final WeakReference<View> mViewRef;
        Rect mHandwritingArea = null;
        @VisibleForTesting
        public boolean mIsDirty = true;

        @VisibleForTesting
        public HandwritableViewInfo(@NonNull View view) {
            mViewRef = new WeakReference<>(view);
        }

        /** Return the tracked view. */
        @Nullable
        public View getView() {
            return mViewRef.get();
        }

        /**
         * Return the tracked handwriting area, represented in the ViewRoot's coordinates.
         * Notice, the caller should not modify the returned Rect.
         */
        @Nullable
        public Rect getHandwritingArea() {
            return mHandwritingArea;
        }

        /**
         * Update the handwriting area in this ViewInfo.
         *
         * @return true if this ViewInfo is still valid. Or false if this ViewInfo has become
         * invalid due to either view is no longer visible, or the handwriting area set by the
         * view is removed. {@link HandwritingAreaTracker} no longer need to keep track of this
         * HandwritableViewInfo this method returns false.
         */
        public boolean update() {
            final View view = getView();
            if (!isViewActive(view)) {
                return false;
            }

            if (!mIsDirty) {
                return true;
            }
            final Rect handwritingArea = view.getHandwritingArea();
            if (handwritingArea == null) {
                return false;
            }

            ViewParent parent = view.getParent();
            if (parent != null) {
                if (mHandwritingArea == null) {
                    mHandwritingArea = new Rect();
                }
                mHandwritingArea.set(handwritingArea);
                if (!parent.getChildVisibleRect(view, mHandwritingArea, null /* offset */)) {
                    mHandwritingArea = null;
                }
            }
            mIsDirty = false;
            return true;
        }
    }
}
