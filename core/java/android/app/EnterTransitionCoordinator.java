/*
 * Copyright (C) 2014 The Android Open Source Project
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
package android.app;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.ResultReceiver;
import android.transition.Transition;
import android.transition.TransitionManager;
import android.util.ArrayMap;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroupOverlay;
import android.view.ViewTreeObserver;

import java.util.ArrayList;

/**
 * This ActivityTransitionCoordinator is created by the Activity to manage
 * the enter scene and shared element transfer into the Scene, either during
 * launch of an Activity or returning from a launched Activity.
 */
class EnterTransitionCoordinator extends ActivityTransitionCoordinator {
    private static final String TAG = "EnterTransitionCoordinator";

    private static final int MIN_ANIMATION_FRAMES = 2;

    private boolean mSharedElementTransitionStarted;
    private Activity mActivity;
    private boolean mHasStopped;
    private boolean mIsCanceled;
    private ObjectAnimator mBackgroundAnimator;
    private boolean mIsExitTransitionComplete;
    private boolean mIsReadyForTransition;
    private Bundle mSharedElementsBundle;
    private boolean mWasOpaque;
    private boolean mAreViewsReady;
    private boolean mIsViewsTransitionStarted;
    private boolean mIsViewsTransitionComplete;
    private boolean mIsSharedElementTransitionComplete;
    private ArrayList<Matrix> mSharedElementParentMatrices;

    public EnterTransitionCoordinator(Activity activity, ResultReceiver resultReceiver,
            ArrayList<String> sharedElementNames, boolean isReturning) {
        super(activity.getWindow(), sharedElementNames,
                getListener(activity, isReturning), isReturning);
        mActivity = activity;
        setResultReceiver(resultReceiver);
        prepareEnter();
        Bundle resultReceiverBundle = new Bundle();
        resultReceiverBundle.putParcelable(KEY_REMOTE_RECEIVER, this);
        mResultReceiver.send(MSG_SET_REMOTE_RECEIVER, resultReceiverBundle);
        getDecor().getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        if (mIsReadyForTransition) {
                            getDecor().getViewTreeObserver().removeOnPreDrawListener(this);
                        }
                        return mIsReadyForTransition;
                    }
                });
    }

    public void viewInstancesReady(ArrayList<String> accepted, ArrayList<View> localViews) {
        triggerViewsReady(mapSharedElements(accepted, localViews));
    }

    public void namedViewsReady(ArrayList<String> accepted, ArrayList<String> localNames) {
        triggerViewsReady(mapNamedElements(accepted, localNames));
    }

    @Override
    protected void viewsReady(ArrayMap<String, View> sharedElements) {
        super.viewsReady(sharedElements);
        mIsReadyForTransition = true;
        setTransitionAlpha(mSharedElements, 0);
        if (getViewsTransition() != null) {
            setTransitionAlpha(mTransitioningViews, 0);
        }
        if (mIsReturning) {
            sendSharedElementDestination();
        } else {
            setSharedElementMatrices();
            moveSharedElementsToOverlay();
        }
        if (mSharedElementsBundle != null) {
            onTakeSharedElements();
        }
    }

    private void triggerViewsReady(final ArrayMap<String, View> sharedElements) {
        if (mAreViewsReady) {
            return;
        }
        mAreViewsReady = true;
        // Ensure the views have been laid out before capturing the views -- we need the epicenter.
        if (sharedElements.isEmpty() || !sharedElements.valueAt(0).isLayoutRequested()) {
            viewsReady(sharedElements);
        } else {
            sharedElements.valueAt(0).getViewTreeObserver()
                    .addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    sharedElements.valueAt(0).getViewTreeObserver().removeOnPreDrawListener(this);
                    viewsReady(sharedElements);
                    return true;
                }
            });
        }
    }

    private ArrayMap<String, View> mapNamedElements(ArrayList<String> accepted,
            ArrayList<String> localNames) {
        ArrayMap<String, View> sharedElements = new ArrayMap<String, View>();
        getDecor().findNamedViews(sharedElements);
        if (accepted != null) {
            for (int i = 0; i < localNames.size(); i++) {
                String localName = localNames.get(i);
                String acceptedName = accepted.get(i);
                if (localName != null && !localName.equals(acceptedName)) {
                    View view = sharedElements.remove(localName);
                    if (view != null) {
                        sharedElements.put(acceptedName, view);
                    }
                }
            }
        }
        return sharedElements;
    }

    private void sendSharedElementDestination() {
        boolean allReady;
        if (allowOverlappingTransitions()) {
            allReady = false;
        } else {
            allReady = !getDecor().isLayoutRequested();
            if (allReady) {
                for (int i = 0; i < mSharedElements.size(); i++) {
                    if (mSharedElements.get(i).isLayoutRequested()) {
                        allReady = false;
                        break;
                    }
                }
            }
        }
        if (allReady) {
            Bundle state = captureSharedElementState();
            setSharedElementMatrices();
            moveSharedElementsToOverlay();
            mResultReceiver.send(MSG_SHARED_ELEMENT_DESTINATION, state);
        } else {
            getDecor().getViewTreeObserver()
                    .addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                        @Override
                        public boolean onPreDraw() {
                            getDecor().getViewTreeObserver().removeOnPreDrawListener(this);
                            Bundle state = captureSharedElementState();
                            setSharedElementMatrices();
                            moveSharedElementsToOverlay();
                            mResultReceiver.send(MSG_SHARED_ELEMENT_DESTINATION, state);
                            return true;
                        }
                    });
        }
        if (allowOverlappingTransitions()) {
            startEnterTransitionOnly();
        }
    }

    private static SharedElementListener getListener(Activity activity, boolean isReturning) {
        return isReturning ? activity.mExitTransitionListener : activity.mEnterTransitionListener;
    }

    @Override
    protected void onReceiveResult(int resultCode, Bundle resultData) {
        switch (resultCode) {
            case MSG_TAKE_SHARED_ELEMENTS:
                if (!mIsCanceled) {
                    mSharedElementsBundle = resultData;
                    onTakeSharedElements();
                }
                break;
            case MSG_EXIT_TRANSITION_COMPLETE:
                if (!mIsCanceled) {
                    mIsExitTransitionComplete = true;
                    if (mSharedElementTransitionStarted) {
                        onRemoteExitTransitionComplete();
                    }
                }
                break;
            case MSG_CANCEL:
                cancel();
                break;
        }
    }

    private void cancel() {
        if (!mIsCanceled) {
            mIsCanceled = true;
            if (getViewsTransition() == null || mIsViewsTransitionStarted) {
                setTransitionAlpha(mSharedElements, 1);
            } else {
                mTransitioningViews.addAll(mSharedElements);
            }
            mSharedElementNames.clear();
            mSharedElements.clear();
            mAllSharedElementNames.clear();
            startSharedElementTransition(null);
            onRemoteExitTransitionComplete();
        }
    }

    public boolean isReturning() {
        return mIsReturning;
    }

    protected void prepareEnter() {
        mActivity.overridePendingTransition(0, 0);
        if (!mIsReturning) {
            mWasOpaque = mActivity.convertToTranslucent(null, null);
            Drawable background = getDecor().getBackground();
            if (background != null) {
                getWindow().setBackgroundDrawable(null);
                background = background.mutate();
                background.setAlpha(0);
                getWindow().setBackgroundDrawable(background);
            }
        } else {
            mActivity = null; // all done with it now.
        }
    }

    @Override
    protected Transition getViewsTransition() {
        if (mIsReturning) {
            return getWindow().getReenterTransition();
        } else {
            return getWindow().getEnterTransition();
        }
    }

    protected Transition getSharedElementTransition() {
        if (mIsReturning) {
            return getWindow().getSharedElementReenterTransition();
        } else {
            return getWindow().getSharedElementEnterTransition();
        }
    }

    private void startSharedElementTransition(Bundle sharedElementState) {
        // Remove rejected shared elements
        ArrayList<String> rejectedNames = new ArrayList<String>(mAllSharedElementNames);
        rejectedNames.removeAll(mSharedElementNames);
        ArrayList<View> rejectedSnapshots = createSnapshots(sharedElementState, rejectedNames);
        mListener.handleRejectedSharedElements(rejectedSnapshots);
        startRejectedAnimations(rejectedSnapshots);

        // Now start shared element transition
        ArrayList<View> sharedElementSnapshots = createSnapshots(sharedElementState,
                mSharedElementNames);
        setTransitionAlpha(mSharedElements, 1);
        scheduleSetSharedElementEnd(sharedElementSnapshots);
        ArrayList<SharedElementOriginalState> originalImageViewState =
                setSharedElementState(sharedElementState, sharedElementSnapshots);
        requestLayoutForSharedElements();

        boolean startEnterTransition = allowOverlappingTransitions() && !mIsReturning;
        boolean startSharedElementTransition = true;
        setGhostVisibility(View.INVISIBLE);
        scheduleGhostVisibilityChange(View.INVISIBLE);
        Transition transition = beginTransition(startEnterTransition, startSharedElementTransition);
        scheduleGhostVisibilityChange(View.VISIBLE);
        setGhostVisibility(View.VISIBLE);

        if (startEnterTransition) {
            startEnterTransition(transition);
        }

        setOriginalSharedElementState(mSharedElements, originalImageViewState);

        if (mResultReceiver != null) {
            // We can't trust that the view will disappear on the same frame that the shared
            // element appears here. Assure that we get at least 2 frames for double-buffering.
            getDecor().postOnAnimation(new Runnable() {
                int mAnimations;
                @Override
                public void run() {
                    if (mAnimations++ < MIN_ANIMATION_FRAMES) {
                        getDecor().postOnAnimation(this);
                    } else {
                        mResultReceiver.send(MSG_HIDE_SHARED_ELEMENTS, null);
                        mResultReceiver = null; // all done sending messages.
                    }
                }
            });
        }
    }

    private void onTakeSharedElements() {
        if (!mIsReadyForTransition || mSharedElementsBundle == null) {
            return;
        }
        final Bundle sharedElementState = mSharedElementsBundle;
        mSharedElementsBundle = null;
        getDecor().getViewTreeObserver()
                .addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        getDecor().getViewTreeObserver().removeOnPreDrawListener(this);
                        startTransition(new Runnable() {
                            @Override
                            public void run() {
                                startSharedElementTransition(sharedElementState);
                            }
                        });
                        return false;
                    }
                });
        getDecor().invalidate();
    }

    private void requestLayoutForSharedElements() {
        int numSharedElements = mSharedElements.size();
        for (int i = 0; i < numSharedElements; i++) {
            mSharedElements.get(i).requestLayout();
        }
    }

    private Transition beginTransition(boolean startEnterTransition,
            boolean startSharedElementTransition) {
        Transition sharedElementTransition = null;
        if (startSharedElementTransition) {
            if (!mSharedElementNames.isEmpty()) {
                sharedElementTransition = configureTransition(getSharedElementTransition(), false);
            }
            if (sharedElementTransition == null) {
                sharedElementTransitionStarted();
                sharedElementTransitionComplete();
            } else {
                sharedElementTransition.addListener(new Transition.TransitionListenerAdapter() {
                    @Override
                    public void onTransitionStart(Transition transition) {
                        sharedElementTransitionStarted();
                    }

                    @Override
                    public void onTransitionEnd(Transition transition) {
                        transition.removeListener(this);
                        sharedElementTransitionComplete();
                    }
                });
            }
        }
        Transition viewsTransition = null;
        if (startEnterTransition) {
            mIsViewsTransitionStarted = true;
            if (!mTransitioningViews.isEmpty()) {
                viewsTransition = configureTransition(getViewsTransition(), true);
                if (viewsTransition != null && !mIsReturning) {
                    stripOffscreenViews();
                }
            }
            if (viewsTransition == null) {
                viewTransitionComplete();
            } else {
                viewsTransition.forceVisibility(View.INVISIBLE, true);
                setTransitionAlpha(mTransitioningViews, 1);
                viewsTransition.addListener(new ContinueTransitionListener() {
                    @Override
                    public void onTransitionEnd(Transition transition) {
                        transition.removeListener(this);
                        viewTransitionComplete();
                        super.onTransitionEnd(transition);
                    }
                });
            }
        }

        Transition transition = mergeTransitions(sharedElementTransition, viewsTransition);
        if (transition != null) {
            transition.addListener(new ContinueTransitionListener());
            TransitionManager.beginDelayedTransition(getDecor(), transition);
            if (startSharedElementTransition && !mSharedElementNames.isEmpty()) {
                mSharedElements.get(0).invalidate();
            } else if (startEnterTransition && !mTransitioningViews.isEmpty()) {
                mTransitioningViews.get(0).invalidate();
            }
        } else {
            transitionStarted();
        }
        return transition;
    }

    private void viewTransitionComplete() {
        mIsViewsTransitionComplete = true;
        if (mIsSharedElementTransitionComplete) {
            moveSharedElementsFromOverlay();
        }
    }

    private void sharedElementTransitionComplete() {
        mIsSharedElementTransitionComplete = true;
        if (mIsViewsTransitionComplete) {
            moveSharedElementsFromOverlay();
        }
    }

    private void sharedElementTransitionStarted() {
        mSharedElementTransitionStarted = true;
        if (mIsExitTransitionComplete) {
            send(MSG_EXIT_TRANSITION_COMPLETE, null);
        }
    }

    private void startEnterTransition(Transition transition) {
        if (!mIsReturning) {
            Drawable background = getDecor().getBackground();
            if (background != null) {
                background = background.mutate();
                mBackgroundAnimator = ObjectAnimator.ofInt(background, "alpha", 255);
                mBackgroundAnimator.setDuration(getFadeDuration());
                mBackgroundAnimator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        makeOpaque();
                    }
                });
                mBackgroundAnimator.start();
            } else if (transition != null) {
                transition.addListener(new Transition.TransitionListenerAdapter() {
                    @Override
                    public void onTransitionEnd(Transition transition) {
                        transition.removeListener(this);
                        makeOpaque();
                    }
                });
            } else {
                makeOpaque();
            }
        }
    }

    public void stop() {
        makeOpaque();
        mIsCanceled = true;
        mResultReceiver = null;
        if (mBackgroundAnimator != null) {
            mBackgroundAnimator.end();
            mBackgroundAnimator = null;
        }
        mActivity = null;
        moveSharedElementsFromOverlay();
        clearState();
    }

    public void cancelEnter() {
        setGhostVisibility(View.INVISIBLE);
        mHasStopped = true;
        mIsCanceled = true;
        mResultReceiver = null;
        if (mBackgroundAnimator != null) {
            mBackgroundAnimator.cancel();
            mBackgroundAnimator = null;
        }
        mActivity = null;
        clearState();
    }

    private void makeOpaque() {
        if (!mHasStopped && mActivity != null) {
            if (mWasOpaque) {
                mActivity.convertFromTranslucent();
            }
            mActivity = null;
        }
    }

    private boolean allowOverlappingTransitions() {
        return mIsReturning ? getWindow().getAllowExitTransitionOverlap()
                : getWindow().getAllowEnterTransitionOverlap();
    }

    private void startRejectedAnimations(final ArrayList<View> rejectedSnapshots) {
        if (rejectedSnapshots == null || rejectedSnapshots.isEmpty()) {
            return;
        }
        ViewGroupOverlay overlay = getDecor().getOverlay();
        ObjectAnimator animator = null;
        int numRejected = rejectedSnapshots.size();
        for (int i = 0; i < numRejected; i++) {
            View snapshot = rejectedSnapshots.get(i);
            overlay.add(snapshot);
            animator = ObjectAnimator.ofFloat(snapshot, View.ALPHA, 1, 0);
            animator.start();
        }
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                ViewGroupOverlay overlay = getDecor().getOverlay();
                int numRejected = rejectedSnapshots.size();
                for (int i = 0; i < numRejected; i++) {
                    overlay.remove(rejectedSnapshots.get(i));
                }
            }
        });
    }

    protected void onRemoteExitTransitionComplete() {
        if (!allowOverlappingTransitions()) {
            startEnterTransitionOnly();
        }
    }

    private void startEnterTransitionOnly() {
        startTransition(new Runnable() {
            @Override
            public void run() {
                boolean startEnterTransition = true;
                boolean startSharedElementTransition = false;
                Transition transition = beginTransition(startEnterTransition,
                        startSharedElementTransition);
                startEnterTransition(transition);
            }
        });
    }

    private void setSharedElementMatrices() {
        int numSharedElements = mSharedElements.size();
        if (numSharedElements > 0) {
            mSharedElementParentMatrices = new ArrayList<Matrix>(numSharedElements);
        }
        for (int i = 0; i < numSharedElements; i++) {
            View view = mSharedElements.get(i);

            // Find the location in the view's parent
            ViewGroup parent = (ViewGroup) view.getParent();
            Matrix matrix = new Matrix();
            parent.transformMatrixToLocal(matrix);

            mSharedElementParentMatrices.add(matrix);
        }
    }

    @Override
    protected void getSharedElementParentMatrix(View view, Matrix matrix) {
        int index = mSharedElementParentMatrices == null ? -1 : mSharedElements.indexOf(view);
        if (index < 0) {
            super.getSharedElementParentMatrix(view, matrix);
        } else {
            matrix.set(mSharedElementParentMatrices.get(index));
        }
    }
}
