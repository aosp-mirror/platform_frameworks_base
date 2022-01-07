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
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.ref.WeakReference;

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
     * The touchSlop from {@link ViewConfiguration} used to decide whether a pointer is considered
     * moving or stationary.
     */
    private final int mTouchSlop;
    /**
     * The timeout used to distinguish tap from handwriting. If the stylus doesn't move before this
     * timeout, it's not considered as handwriting.
     */
    private final long mTapTimeoutInMillis;

    private State mState = new State();

    /**
     * Helper method to reset the internal state of this class.
     * Calling this method will also prevent the following MotionEvents
     * triggers handwriting until the next stylus ACTION_DOWN/ACTION_POINTER_DOWN
     * arrives.
     */
    private void reset() {
        mState = new State();
    }

    /** The reference to the View that currently has the input connection. */
    @Nullable
    @VisibleForTesting
    public WeakReference<View> mConnectedView = null;

    /** The editor bound reported by the connected View. */
    @Nullable
    @VisibleForTesting
    public Rect mEditorBound = null;

    /**
     * When InputConnection restarts for a View, View#onInputConnectionCreatedInternal
     * might be called before View#onInputConnectionClosedInternal, so we need to count the input
     * connections and only set mConnectedView to null when mConnectionCount is zero.
     */
    private int mConnectionCount = 0;
    private final InputMethodManager mImm;

    @VisibleForTesting
    public HandwritingInitiator(ViewConfiguration viewConfiguration,
            InputMethodManager inputMethodManager) {
        mTouchSlop = viewConfiguration.getScaledTouchSlop();
        mTapTimeoutInMillis = ViewConfiguration.getTapTimeout();
        mImm = inputMethodManager;
    }

    /**
     * Notify the HandwritingInitiator that a new MotionEvent has arrived.
     * This method is non-block, and the event passed to this method should be dispatched to the
     * View tree as usual. If HandwritingInitiator triggers the handwriting mode, an fabricated
     * ACTION_CANCEL event will be sent to the ViewRootImpl.
     * @param motionEvent the stylus MotionEvent.
     */
    @VisibleForTesting
    public void onTouchEvent(MotionEvent motionEvent) {
        final int maskedAction = motionEvent.getActionMasked();
        switch (maskedAction) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                final int actionIndex = motionEvent.getActionIndex();
                final int toolType = motionEvent.getToolType(actionIndex);
                // TOOL_TYPE_ERASER is also from stylus. This indicates that the user is holding
                // the eraser button during handwriting.
                if (toolType != MotionEvent.TOOL_TYPE_STYLUS
                        && toolType != MotionEvent.TOOL_TYPE_ERASER) {
                    // The motion event is not from a stylus event, ignore it.
                    return;
                }
                mState.mStylusPointerId = motionEvent.getPointerId(actionIndex);
                mState.mStylusDownTimeInMillis = motionEvent.getEventTime();
                mState.mStylusDownX = motionEvent.getX(actionIndex);
                mState.mStylusDownY = motionEvent.getY(actionIndex);
                mState.mShouldInitHandwriting = true;
                mState.mExceedTouchSlop = false;
                break;
            case MotionEvent.ACTION_POINTER_UP:
                final int pointerId = motionEvent.getPointerId(motionEvent.getActionIndex());
                if (pointerId != mState.mStylusPointerId) {
                    // ACTION_POINTER_UP is from another stylus pointer, ignore the event.
                    return;
                }
                // Deliberately fall through.
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                // If it's ACTION_CANCEL or ACTION_UP, all the pointers go up. There is no need to
                // check whether the stylus we are tracking goes up.
                reset();
                break;
            case MotionEvent.ACTION_MOVE:
                // Either we've already tried to initiate handwriting, or the ongoing MotionEvent
                // sequence is considered to be tap, long-click or other gestures.
                if (!mState.mShouldInitHandwriting || mState.mExceedTouchSlop) {
                    return;
                }

                final long timeElapsed =
                        motionEvent.getEventTime() - mState.mStylusDownTimeInMillis;
                if (timeElapsed > mTapTimeoutInMillis) {
                    reset();
                    return;
                }

                final int pointerIndex = motionEvent.findPointerIndex(mState.mStylusPointerId);
                final float x = motionEvent.getX(pointerIndex);
                final float y = motionEvent.getY(pointerIndex);
                if (largerThanTouchSlop(x, y, mState.mStylusDownX, mState.mStylusDownY)) {
                    mState.mExceedTouchSlop = true;
                    tryStartHandwriting();
                }
        }
    }

    private View getConnectedView() {
        if (mConnectedView == null) return null;
        return mConnectedView.get();
    }

    /**
     * Notify HandwritingInitiator that a new InputConnection is created.
     * The caller of this method should guarantee that each onInputConnectionCreated call
     * is paired with a onInputConnectionClosed call.
     * @param view the view that created the current InputConnection.
     * @see  #onInputConnectionClosed(View)
     */
    public void onInputConnectionCreated(@NonNull View view, @NonNull EditorInfo editorInfo) {
        final View connectedView = getConnectedView();
//        updateEditorBound(editorInfo.getInitialEditorBound());
        if (connectedView == view) {
            ++mConnectionCount;
        } else {
            mConnectedView = new WeakReference<>(view);
            mConnectionCount = 1;
            tryStartHandwriting();
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
        if (connectedView == view) {
            --mConnectionCount;
            if (mConnectionCount == 0) {
                mConnectedView = null;
                mEditorBound = null;
            }
        } else {
            // Unexpected branch, set mConnectedView to null to avoid further problem.
            mConnectedView = null;
            mEditorBound = null;
            mConnectionCount = 0;
        }
    }

    /**
     * Notify the HandwritingInitiator that editor bound of the connected view(the view with
     * active InputConnection) has be updated.
     * @param editorBound new the editor bounds of the connected view.
     */
    public void updateEditorBound(@NonNull Rect editorBound) {
        if (mEditorBound == null) {
            mEditorBound = new Rect(editorBound);
        } else {
            mEditorBound.left = editorBound.left;
            mEditorBound.top = editorBound.top;
            mEditorBound.right = editorBound.right;
            mEditorBound.bottom = editorBound.bottom;
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
        if (!mState.mExceedTouchSlop) {
            return;
        }
        final View connectedView = getConnectedView();
        if (connectedView == null || mEditorBound == null) {
            return;
        }
        final ViewParent viewParent = connectedView.getParent();
        // Do a final check before startHandwriting.
        if (viewParent != null && connectedView.isAttachedToWindow()) {
            final Rect editorBounds = new Rect(mEditorBound);
            if (viewParent.getChildVisibleRect(connectedView, editorBounds, null)) {
                final int roundedInitX = Math.round(mState.mStylusDownX);
                final int roundedInitY = Math.round(mState.mStylusDownY);
                if (editorBounds.contains(roundedInitX, roundedInitY)) {
                    startHandwriting(mConnectedView.get());
                }
            }
        }
        reset();
    }

    /** For test only. */
    @VisibleForTesting
    public void startHandwriting(View view) {
        // mImm.startHandwriting(view);
    }

    private boolean largerThanTouchSlop(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2;
        float dy = y1 - y2;
        return dx * dx + dy * dy > mTouchSlop * mTouchSlop;
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
        private boolean mShouldInitHandwriting = false;
        /**
         * Whether the current ongoing stylus MotionEvent sequence already exceeds the touchSlop.
         * It's used for the case where the stylus exceeds touchSlop before the target View built
         * InputConnection.
         */
        private boolean mExceedTouchSlop = false;

        /** The pointer id of the stylus pointer that is being tracked. */
        private int mStylusPointerId = -1;
        /** The time stamp when the stylus pointer goes down. */
        private long mStylusDownTimeInMillis = -1;
        /** The initial location where the stylus pointer goes down. */
        private float mStylusDownX = Float.NaN;
        private float mStylusDownY = Float.NaN;
    }
}
