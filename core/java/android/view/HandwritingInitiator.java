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

import static com.android.text.flags.Flags.handwritingCursorPosition;
import static com.android.text.flags.Flags.handwritingTrackDisabled;
import static com.android.text.flags.Flags.handwritingUnsupportedMessage;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.text.TextUtils;
import android.view.inputmethod.ConnectionlessHandwritingCallback;
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.Flags;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.Editor;
import android.widget.TextView;
import android.widget.Toast;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

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

    /**
     * The reference to the View that currently has focus.
     * This replaces mConnecteView when {@code Flags#intitiationWithoutInputConnection()} is
     * enabled.
     */
    @Nullable
    @VisibleForTesting
    public WeakReference<View> mFocusedView = null;

    private final InputMethodManager mImm;

    private final int[] mTempLocation = new int[2];

    private final Rect mTempRect = new Rect();

    private final RectF mTempRectF = new RectF();

    private final Region mTempRegion = new Region();

    private final Matrix mTempMatrix = new Matrix();

    /**
     * The handwrite-able View that is currently the target of a hovering stylus pointer. This is
     * used to help determine whether the handwriting PointerIcon should be shown in
     * {@link #onResolvePointerIcon(Context, MotionEvent)} so that we can reduce the number of calls
     * to {@link #findBestCandidateView(float, float, boolean)}.
     */
    @Nullable
    private WeakReference<View> mCachedHoverTarget = null;

    /**
     * Whether to show the hover icon for the current connected view.
     * Hover icon should be hidden for the current connected view after handwriting is initiated
     * for it until one of the following events happens:
     * a) user performs a click or long click. In other words, if it receives a series of motion
     * events that don't trigger handwriting, show hover icon again.
     * b) the stylus hovers on another editor that supports handwriting (or a handwriting delegate).
     * c) the current connected editor lost focus.
     *
     * If the stylus is hovering on an unconnected editor that supports handwriting, we always show
     * the hover icon.
     * TODO(b/308827131): Rename to FocusedView after Flag is flipped.
     */
    private boolean mShowHoverIconForConnectedView = true;

    /** When flag is enabled, touched editors don't wait for InputConnection for initiation.
     * However, delegation still waits for InputConnection.
     */
    private final boolean mInitiateWithoutConnection = Flags.initiationWithoutInputConnection();

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
                if (!motionEvent.isStylusPointer()) {
                    // The motion event is not from a stylus event, ignore it.
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
                    if (!mState.mHandled) {
                        // The user just did a click, long click or another stylus gesture,
                        // show hover icon again for the connected view.
                        mShowHoverIconForConnectedView = true;
                    }
                }
                return false;
            case MotionEvent.ACTION_MOVE:
                if (mState == null) {
                    return false;
                }

                // Either we've already tried to initiate handwriting, or the ongoing MotionEvent
                // sequence is considered to be tap, long-click or other gestures.
                if (!mState.mShouldInitHandwriting || mState.mExceedHandwritingSlop) {
                    return mState.mHandled;
                }

                final long timeElapsed =
                        motionEvent.getEventTime() - mState.mStylusDownTimeInMillis;
                if (timeElapsed > mHandwritingTimeoutInMillis) {
                    mState.mShouldInitHandwriting = false;
                    return mState.mHandled;
                }

                final int pointerIndex = motionEvent.findPointerIndex(mState.mStylusPointerId);
                final float x = motionEvent.getX(pointerIndex);
                final float y = motionEvent.getY(pointerIndex);
                if (largerThanTouchSlop(x, y, mState.mStylusDownX, mState.mStylusDownY)) {
                    mState.mExceedHandwritingSlop = true;
                    View candidateView = findBestCandidateView(mState.mStylusDownX,
                            mState.mStylusDownY, /* isHover */ false);
                    if (candidateView != null && candidateView.isEnabled()) {
                        boolean candidateHasFocus = candidateView.hasFocus();
                        if (!candidateView.isStylusHandwritingAvailable()) {
                            mState.mShouldInitHandwriting = false;
                            return false;
                        } else if (shouldShowHandwritingUnavailableMessageForView(candidateView)) {
                            int messagesResId = (candidateView instanceof TextView tv
                                    && tv.isAnyPasswordInputType())
                                    ? R.string.error_handwriting_unsupported_password
                                    : R.string.error_handwriting_unsupported;
                            Toast.makeText(candidateView.getContext(), messagesResId,
                                    Toast.LENGTH_SHORT).show();
                            if (!candidateView.hasFocus()) {
                                requestFocusWithoutReveal(candidateView);
                            }
                            mImm.showSoftInput(candidateView, 0);
                            mState.mHandled = true;
                            mState.mShouldInitHandwriting = false;
                            motionEvent.setAction((motionEvent.getAction()
                                    & MotionEvent.ACTION_POINTER_INDEX_MASK)
                                    | MotionEvent.ACTION_CANCEL);
                            candidateView.getRootView().dispatchTouchEvent(motionEvent);
                        } else if (candidateView == getConnectedOrFocusedView()) {
                            if (!candidateHasFocus) {
                                requestFocusWithoutReveal(candidateView);
                            }
                            startHandwriting(candidateView);
                        } else if (candidateView.getHandwritingDelegatorCallback() != null) {
                            prepareDelegation(candidateView);
                        } else {
                            if (mInitiateWithoutConnection) {
                                if (!candidateHasFocus) {
                                    // schedule for view focus.
                                    mState.mPendingFocusedView = new WeakReference<>(candidateView);
                                    requestFocusWithoutReveal(candidateView);
                                }
                            } else {
                                mState.mPendingConnectedView = new WeakReference<>(candidateView);
                                if (!candidateHasFocus) {
                                    requestFocusWithoutReveal(candidateView);
                                }
                            }
                        }
                    }
                }
                return mState.mHandled;
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
     * Notify HandwritingInitiator that a delegate view (see {@link View#isHandwritingDelegate})
     * gained focus.
     */
    public void onDelegateViewFocused(@NonNull View view) {
        if (mInitiateWithoutConnection) {
            onEditorFocused(view);
        }
        if (view == getConnectedView()) {
            tryAcceptStylusHandwritingDelegation(view);
        }
    }

    /**
     * Notify HandwritingInitiator that a new InputConnection is created.
     * The caller of this method should guarantee that each onInputConnectionCreated call
     * is paired with a onInputConnectionClosed call.
     * @param view the view that created the current InputConnection.
     * @see  #onInputConnectionClosed(View)
     */
    public void onInputConnectionCreated(@NonNull View view) {
        if (mInitiateWithoutConnection && !view.isHandwritingDelegate()) {
            // When flag is enabled, only delegation continues to wait for InputConnection.
            return;
        }
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
            // A new view just gain focus. By default, we should show hover icon for it.
            mShowHoverIconForConnectedView = true;
            if (view.isHandwritingDelegate() && tryAcceptStylusHandwritingDelegation(view)) {
                // tryAcceptStylusHandwritingDelegation should set boolean below, however, we
                // cannot mock IMM to return true for acceptStylusDelegation().
                // TODO(b/324670412): we should move any dependent tests to integration and remove
                //  the assignment below.
                mShowHoverIconForConnectedView = false;
                return;
            }
            if (!mInitiateWithoutConnection && mState != null
                    && mState.mPendingConnectedView != null
                    && mState.mPendingConnectedView.get() == view) {
                startHandwriting(view);
            }
        }
    }

    /**
     * Notify HandwritingInitiator that a new editor is focused.
     * @param view the view that received focus.
     */
    @VisibleForTesting
    public void onEditorFocused(@NonNull View view) {
        if (!mInitiateWithoutConnection) {
            return;
        }

        final View focusedView = getFocusedView();

        if (!handwritingTrackDisabled() && !view.isAutoHandwritingEnabled()) {
            clearFocusedView(focusedView);
            return;
        }

        if (focusedView == view) {
            return;
        }
        updateFocusedView(view);

        if (mState != null && mState.mPendingFocusedView != null
                && mState.mPendingFocusedView.get() == view
                && (!handwritingTrackDisabled() || view.isAutoHandwritingEnabled())) {
            startHandwriting(view);
        }
    }

    /**
     * Notify HandwritingInitiator that the InputConnection has closed for the given view.
     * The caller of this method should guarantee that each onInputConnectionClosed call
     * is paired with a onInputConnectionCreated call.
     * @param view the view that closed the InputConnection.
     */
    public void onInputConnectionClosed(@NonNull View view) {
        if (mInitiateWithoutConnection && !view.isHandwritingDelegate()) {
            return;
        }
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

    @Nullable
    private View getFocusedView() {
        if (mFocusedView == null) return null;
        return mFocusedView.get();
    }

    /**
     * Clear the tracked focused view tracked for handwriting initiation.
     * @param view the focused view.
     */
    public void clearFocusedView(View view) {
        if (view == null || mFocusedView == null) {
            return;
        }
        if (mFocusedView.get() == view) {
            mFocusedView = null;
        }
    }

    /**
     * Called when new {@link Editor} is focused.
     * @return {@code true} if handwriting can initiate for given view.
     */
    @VisibleForTesting
    public boolean updateFocusedView(@NonNull View view) {
        if (!handwritingTrackDisabled() && !view.shouldInitiateHandwriting()) {
            mFocusedView = null;
            return false;
        }

        final View focusedView = getFocusedView();
        if (focusedView != view) {
            mFocusedView = new WeakReference<>(view);
            if (!handwritingTrackDisabled() || view.shouldInitiateHandwriting()) {
                // A new view just gain focus. By default, we should show hover icon for it.
                mShowHoverIconForConnectedView = true;
            }
        }

        return true;
    }

    /** Starts a stylus handwriting session for the view. */
    @VisibleForTesting
    public void startHandwriting(@NonNull View view) {
        mImm.startStylusHandwriting(view);
        mState.mHandled = true;
        mState.mShouldInitHandwriting = false;
        mShowHoverIconForConnectedView = false;
        if (view instanceof TextView) {
            ((TextView) view).hideHint();
        }
    }

    private void prepareDelegation(View view) {
        String delegatePackageName = view.getAllowedHandwritingDelegatePackageName();
        if (delegatePackageName == null) {
            delegatePackageName = view.getContext().getOpPackageName();
        }
        if (mImm.isConnectionlessStylusHandwritingAvailable()) {
            // No other view should have focus during the connectionless handwriting session, as
            // this could cause user confusion about the input target for the session.
            view.getViewRootImpl().getView().clearFocus();
            mImm.startConnectionlessStylusHandwritingForDelegation(
                    view, getCursorAnchorInfoForConnectionless(view), delegatePackageName,
                    view::post, new DelegationCallback(view, delegatePackageName));
            mState.mShouldInitHandwriting = false;
        } else {
            mImm.prepareStylusHandwritingDelegation(view, delegatePackageName);
            view.getHandwritingDelegatorCallback().run();
        }
        mState.mHandled = true;
    }

    /**
     * Starts a stylus handwriting session for the delegate view, if {@link
     * InputMethodManager#prepareStylusHandwritingDelegation} was previously called.
     */
    @VisibleForTesting
    public boolean tryAcceptStylusHandwritingDelegation(@NonNull View view) {
        if (Flags.useZeroJankProxy()) {
            tryAcceptStylusHandwritingDelegationAsync(view);
        } else {
            return tryAcceptStylusHandwritingDelegationInternal(view);
        }
        return false;
    }

    private boolean tryAcceptStylusHandwritingDelegationInternal(@NonNull View view) {
        String delegatorPackageName =
                view.getAllowedHandwritingDelegatorPackageName();
        if (delegatorPackageName == null) {
            delegatorPackageName = view.getContext().getOpPackageName();
        }
        if (mImm.acceptStylusHandwritingDelegation(view, delegatorPackageName)) {
            onDelegationAccepted(view);
            return true;
        }
        return false;
    }

    @FlaggedApi(Flags.FLAG_USE_ZERO_JANK_PROXY)
    private void tryAcceptStylusHandwritingDelegationAsync(@NonNull View view) {
        String delegatorPackageName =
                view.getAllowedHandwritingDelegatorPackageName();
        if (delegatorPackageName == null) {
            delegatorPackageName = view.getContext().getOpPackageName();
        }
        WeakReference<View> viewRef = new WeakReference<>(view);
        Consumer<Boolean> consumer = delegationAccepted -> {
            if (delegationAccepted) {
                onDelegationAccepted(viewRef.get());
            }
        };
        mImm.acceptStylusHandwritingDelegation(view, delegatorPackageName, view::post, consumer);
    }

    private void onDelegationAccepted(View view) {
        if (mState != null) {
            mState.mHandled = true;
            mState.mShouldInitHandwriting = false;
        }
        if (view == null) {
            // can be null if view was detached and was GCed.
            return;
        }
        if (view instanceof TextView) {
            ((TextView) view).hideHint();
        }
        // A handwriting delegate view is accepted and handwriting starts; hide the
        // hover icon.
        mShowHoverIconForConnectedView = false;
    }

    /**
     * Notify that the handwriting area for the given view might be updated.
     * @param view the view whose handwriting area might be updated.
     */
    public void updateHandwritingAreasForView(@NonNull View view) {
        mHandwritingAreasTracker.updateHandwritingAreaForView(view);
    }

    private static boolean shouldTriggerStylusHandwritingForView(@NonNull View view) {
        if (!view.shouldInitiateHandwriting()) {
            return false;
        }
        // The view may be a handwriting initiation delegator, in which case it is not the editor
        // view for which handwriting would be started. However, in almost all cases, the return
        // values of View#isStylusHandwritingAvailable will be the same for the delegator view and
        // the delegate editor view. So the delegator view can be used to decide whether handwriting
        // should be triggered.
        return view.isStylusHandwritingAvailable();
    }

    private static boolean shouldShowHandwritingUnavailableMessageForView(@NonNull View view) {
        return (view instanceof TextView) && !shouldTriggerStylusHandwritingForView(view);
    }

    private static boolean shouldTriggerHandwritingOrShowUnavailableMessageForView(
            @NonNull View view) {
        return (view instanceof TextView) || shouldTriggerStylusHandwritingForView(view);
    }

    /**
     * Returns the pointer icon for the motion event, or null if it doesn't specify the icon.
     * This gives HandwritingInitiator a chance to show the stylus handwriting icon over a
     * handwrite-able area.
     */
    public PointerIcon onResolvePointerIcon(Context context, MotionEvent event) {
        final View hoverView = findHoverView(event);
        if (hoverView == null || !shouldTriggerStylusHandwritingForView(hoverView)) {
            return null;
        }

        if (mShowHoverIconForConnectedView) {
            return PointerIcon.getSystemIcon(context, PointerIcon.TYPE_HANDWRITING);
        }

        if (hoverView != getConnectedOrFocusedView()) {
            // The stylus is hovering on another view that supports handwriting. We should show
            // hover icon. Also reset the mShowHoverIconForFocusedView so that hover
            // icon is displayed again next time when the stylus hovers on focused view.
            mShowHoverIconForConnectedView = true;
            return PointerIcon.getSystemIcon(context, PointerIcon.TYPE_HANDWRITING);
        }
        return null;
    }

    // TODO(b/308827131): Remove once Flag is flipped.
    private View getConnectedOrFocusedView() {
        if (mInitiateWithoutConnection) {
            return mFocusedView == null ? null : mFocusedView.get();
        } else {
            return mConnectedView == null ? null : mConnectedView.get();
        }
    }

    private View getCachedHoverTarget() {
        if (mCachedHoverTarget == null) {
            return null;
        }
        return mCachedHoverTarget.get();
    }

    private View findHoverView(MotionEvent event) {
        if (!event.isStylusPointer() || !event.isHoverEvent()) {
            return null;
        }

        if (event.getActionMasked() == MotionEvent.ACTION_HOVER_ENTER
                || event.getActionMasked() == MotionEvent.ACTION_HOVER_MOVE) {
            final float hoverX = event.getX(event.getActionIndex());
            final float hoverY = event.getY(event.getActionIndex());

            final View cachedHoverTarget = getCachedHoverTarget();
            if (cachedHoverTarget != null) {
                final Rect handwritingArea = mTempRect;
                if (getViewHandwritingArea(cachedHoverTarget, handwritingArea)
                        && isInHandwritingArea(handwritingArea, hoverX, hoverY, cachedHoverTarget,
                        /* isHover */ true)
                        && shouldTriggerStylusHandwritingForView(cachedHoverTarget)) {
                    return cachedHoverTarget;
                }
            }

            final View candidateView = findBestCandidateView(hoverX, hoverY, /* isHover */ true);

            if (candidateView != null) {
                if (!handwritingUnsupportedMessage()) {
                    mCachedHoverTarget = new WeakReference<>(candidateView);
                }
                return candidateView;
            }
        }

        mCachedHoverTarget = null;
        return null;
    }

    private void requestFocusWithoutReveal(View view) {
        if (!handwritingCursorPosition() && view instanceof EditText editText
                && !mState.mStylusDownWithinEditorBounds) {
            // If the stylus down point was inside the EditText's bounds, then the EditText will
            // automatically set its cursor position nearest to the stylus down point when it
            // gains focus. If the stylus down point was outside the EditText's bounds (within
            // the extended handwriting bounds), then we must calculate and set the cursor
            // position manually.
            view.getLocationInWindow(mTempLocation);
            int offset = editText.getOffsetForPosition(
                    mState.mStylusDownX - mTempLocation[0],
                    mState.mStylusDownY - mTempLocation[1]);
            editText.setSelection(offset);
        }
        if (view.getRevealOnFocusHint()) {
            view.setRevealOnFocusHint(false);
            view.requestFocus();
            view.setRevealOnFocusHint(true);
        } else {
            view.requestFocus();
        }
        if (handwritingCursorPosition() && view instanceof EditText editText) {
            // Move the cursor to the end of the paragraph closest to the stylus down point.
            view.getLocationInWindow(mTempLocation);
            int line = editText.getLineAtCoordinate(mState.mStylusDownY - mTempLocation[1]);
            int paragraphEnd = TextUtils.indexOf(editText.getText(), '\n',
                    editText.getLayout().getLineStart(line));
            if (paragraphEnd < 0) {
                paragraphEnd = editText.getText().length();
            }
            editText.setSelection(paragraphEnd);
        }
    }

    /**
     * Given the location of the stylus event, return the best candidate view to initialize
     * handwriting mode or show the handwriting unavailable error message.
     *
     * @param x the x coordinates of the stylus event, in the coordinates of the window.
     * @param y the y coordinates of the stylus event, in the coordinates of the window.
     */
    @Nullable
    private View findBestCandidateView(float x, float y, boolean isHover) {
        // TODO(b/308827131): Rename to FocusedView after Flag is flipped.
        // If the connectedView is not null and do not set any handwriting area, it will check
        // whether the connectedView's boundary contains the initial stylus position. If true,
        // directly return the connectedView.
        final View connectedOrFocusedView = getConnectedOrFocusedView();
        if (connectedOrFocusedView != null) {
            Rect handwritingArea = mTempRect;
            if (getViewHandwritingArea(connectedOrFocusedView, handwritingArea)
                    && isInHandwritingArea(handwritingArea, x, y, connectedOrFocusedView, isHover)
                    && shouldTriggerHandwritingOrShowUnavailableMessageForView(
                            connectedOrFocusedView)) {
                if (!isHover && mState != null) {
                    mState.mStylusDownWithinEditorBounds =
                            contains(handwritingArea, x, y, 0f, 0f, 0f, 0f);
                }
                return connectedOrFocusedView;
            }
        }

        float minDistance = Float.MAX_VALUE;
        View bestCandidate = null;
        // Check the registered handwriting areas.
        final List<HandwritableViewInfo> handwritableViewInfos =
                mHandwritingAreasTracker.computeViewInfos();
        for (HandwritableViewInfo viewInfo : handwritableViewInfos) {
            final View view = viewInfo.getView();
            final Rect handwritingArea = viewInfo.getHandwritingArea();
            if (!isInHandwritingArea(handwritingArea, x, y, view, isHover)
                    || !shouldTriggerHandwritingOrShowUnavailableMessageForView(view)) {
                continue;
            }

            final float distance = distance(handwritingArea, x, y);
            if (distance == 0f) {
                if (!isHover && mState != null) {
                    mState.mStylusDownWithinEditorBounds = true;
                }
                return view;
            }
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
         *          o |     |
         *         ---+-----+---
         *            |     |
         *         ---+-----+---
         *            |     |
         * b) The distance is the distance from (x, y) to the closest edge on rect.
         *            |  o  |
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
     *
     * <p> The handwriting area is clipped to its visible part.
     * Notice that the returned rectangle is the view's original handwriting area without the
     * view's handwriting area extends. </p>
     *
     * @param view the {@link View} whose handwriting area we want to compute.
     * @param rect the {@link Rect} to receive the result.
     *
     * @return true if the view's handwriting area is still visible, or false if it's clipped and
     * fully invisible. This method only consider the clip by given view's parents, but not the case
     * where a view is covered by its sibling view.
     */
    private static boolean getViewHandwritingArea(@NonNull View view, @NonNull Rect rect) {
        final ViewParent viewParent = view.getParent();
        if (viewParent != null && view.isAttachedToWindow() && view.isAggregatedVisible()) {
            final Rect localHandwritingArea = view.getHandwritingArea();
            if (localHandwritingArea != null) {
                rect.set(localHandwritingArea);
            } else {
                rect.set(0, 0, view.getWidth(), view.getHeight());
            }
            return viewParent.getChildVisibleRect(view, rect, null);
        }
        return false;
    }

    /**
     * Return true if the (x, y) is inside by the given {@link Rect} with the View's
     * handwriting bounds with offsets applied.
     */
    private boolean isInHandwritingArea(@Nullable Rect handwritingArea,
            float x, float y, View view, boolean isHover) {
        if (handwritingArea == null) return false;

        if (!contains(handwritingArea, x, y,
                view.getHandwritingBoundsOffsetLeft(),
                view.getHandwritingBoundsOffsetTop(),
                view.getHandwritingBoundsOffsetRight(),
                view.getHandwritingBoundsOffsetBottom())) {
            return false;
        }

        // The returned handwritingArea computed by ViewParent#getChildVisibleRect didn't consider
        // the case where a view is stacking on top of the editor. (e.g. DrawerLayout, popup)
        // We must check the hit region of the editor again, and avoid the case where another
        // view on top of the editor is handling MotionEvents.
        ViewParent parent = view.getParent();
        if (parent == null) {
            return true;
        }

        Region region = mTempRegion;
        mTempRegion.set(0, 0, view.getWidth(), view.getHeight());
        Matrix matrix = mTempMatrix;
        matrix.reset();
        if (!parent.getChildLocalHitRegion(view, region, matrix, isHover)) {
            return false;
        }

        // It's not easy to extend the region by the given handwritingBoundsOffset. Instead, we
        // create a rectangle surrounding the motion event location and check if this rectangle
        // overlaps with the hit region of the editor.
        float left = x - view.getHandwritingBoundsOffsetRight();
        float top = y - view.getHandwritingBoundsOffsetBottom();
        float right = Math.max(x + view.getHandwritingBoundsOffsetLeft(), left + 1);
        float bottom =  Math.max(y + view.getHandwritingBoundsOffsetTop(), top + 1);
        RectF rectF = mTempRectF;
        rectF.set(left, top, right, bottom);
        matrix.mapRect(rectF);

        return region.op(Math.round(rectF.left), Math.round(rectF.top),
                Math.round(rectF.right), Math.round(rectF.bottom), Region.Op.INTERSECT);
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
         * Whether the current MotionEvent sequence has been handled by the handwriting initiator,
         * either by initiating handwriting mode, or by preparing handwriting delegation.
         */
        private boolean mHandled;

        /**
         * Whether the current ongoing stylus MotionEvent sequence already exceeds the
         * handwriting slop.
         * It's used for the case where the stylus exceeds handwriting slop before the target View
         * built InputConnection.
         */
        private boolean mExceedHandwritingSlop;

        /**
         * Whether the stylus down point of the MotionEvent sequence was within the editor's bounds
         * (not including the extended handwriting bounds).
         */
        private boolean mStylusDownWithinEditorBounds;

        /**
         * A view which has requested focus and is pending input connection creation. When an input
         * connection is created for the view, a handwriting session should be started for the view.
         */
        private WeakReference<View> mPendingConnectedView = null;

        /**
         * A view which has requested focus and is yet to receive it.
         * When view receives focus, a handwriting session should be started for the view.
         */
        private WeakReference<View> mPendingFocusedView = null;

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
            mHandled = false;
            mExceedHandwritingSlop = false;
        }
    }

    /** The helper method to check if the given view is still active for handwriting. */
    private static boolean isViewActive(@Nullable View view) {
        return view != null && view.isAttachedToWindow() && view.isAggregatedVisible()
                && view.shouldTrackHandwritingArea();
    }

    private CursorAnchorInfo getCursorAnchorInfoForConnectionless(View view) {
        CursorAnchorInfo.Builder builder = new CursorAnchorInfo.Builder();
        // Fake editor views will usually display hint text. The hint text view can be used to
        // populate the CursorAnchorInfo.
        TextView textView = findFirstTextViewDescendent(view);
        if (textView != null) {
            textView.getCursorAnchorInfo(0, builder, mTempMatrix);
            if (textView.getSelectionStart() < 0) {
                // Insertion marker location is not populated if selection start is negative, so
                // make a best guess.
                float bottom = textView.getHeight() - textView.getExtendedPaddingBottom();
                builder.setInsertionMarkerLocation(
                        /* horizontalPosition= */ textView.getCompoundPaddingStart(),
                        /* lineTop= */ textView.getExtendedPaddingTop(),
                        /* lineBaseline= */ bottom,
                        /* lineBottom= */ bottom,
                        /* flags= */ 0);
            }
        } else {
            // If there is no TextView descendent, just populate the insertion marker with the start
            // edge of the view.
            mTempMatrix.reset();
            view.transformMatrixToGlobal(mTempMatrix);
            builder.setMatrix(mTempMatrix);
            builder.setInsertionMarkerLocation(
                    /* horizontalPosition= */ view.isLayoutRtl() ? view.getWidth() : 0,
                    /* lineTop= */ 0,
                    /* lineBaseline= */ view.getHeight(),
                    /* lineBottom= */ view.getHeight(),
                    /* flags= */ 0);
        }
        return builder.build();
    }

    @Nullable
    private static TextView findFirstTextViewDescendent(View view) {
        if (view instanceof ViewGroup viewGroup) {
            TextView textView;
            for (int i = 0; i < viewGroup.getChildCount(); ++i) {
                View child = viewGroup.getChildAt(i);
                textView = (child instanceof TextView tv)
                        ? tv : findFirstTextViewDescendent(viewGroup.getChildAt(i));
                if (textView != null
                        && textView.isAggregatedVisible()
                        && (!TextUtils.isEmpty(textView.getText())
                                || !TextUtils.isEmpty(textView.getHint()))) {
                    return textView;
                }
            }
        }
        return null;
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

    private class DelegationCallback implements ConnectionlessHandwritingCallback {
        private final View mView;
        private final String mDelegatePackageName;

        private DelegationCallback(View view, String delegatePackageName) {
            mView = view;
            mDelegatePackageName = delegatePackageName;
        }

        @Override
        public void onResult(@NonNull CharSequence text) {
            mView.getHandwritingDelegatorCallback().run();
        }

        @Override
        public void onError(int errorCode) {
            switch (errorCode) {
                case CONNECTIONLESS_HANDWRITING_ERROR_NO_TEXT_RECOGNIZED:
                    mView.getHandwritingDelegatorCallback().run();
                    break;
                case CONNECTIONLESS_HANDWRITING_ERROR_UNSUPPORTED:
                    // Fall back to the old delegation flow
                    mImm.prepareStylusHandwritingDelegation(mView, mDelegatePackageName);
                    mView.getHandwritingDelegatorCallback().run();
                    break;
            }
        }
    }
}
