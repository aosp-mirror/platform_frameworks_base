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
import android.util.Pair;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroupOverlay;
import android.view.ViewTreeObserver;
import android.widget.ImageView;

import java.util.ArrayList;

/**
 * This ActivityTransitionCoordinator is created by the Activity to manage
 * the enter scene and shared element transfer into the Scene, either during
 * launch of an Activity or returning from a launched Activity.
 */
class EnterTransitionCoordinator extends ActivityTransitionCoordinator {
    private static final String TAG = "EnterTransitionCoordinator";

    private static final long MAX_WAIT_MS = 1000;

    private boolean mSharedElementTransitionStarted;
    private Activity mActivity;
    private boolean mHasStopped;
    private Handler mHandler;
    private boolean mIsCanceled;
    private ObjectAnimator mBackgroundAnimator;
    private boolean mIsExitTransitionComplete;
    private boolean mIsReadyForTransition;
    private Bundle mSharedElementsBundle;

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
        getDecor().getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (mIsReadyForTransition) {
                    getDecor().getViewTreeObserver().removeOnPreDrawListener(this);
                }
                return mIsReadyForTransition;
            }
        });
    }

    public void viewsReady(ArrayList<String> accepted, ArrayList<String> localNames) {
        if (mIsReadyForTransition) {
            return;
        }
        super.viewsReady(accepted, localNames);

        mIsReadyForTransition = true;
        if (mIsReturning) {
            mHandler = new Handler() {
                @Override
                public void handleMessage(Message msg) {
                    cancel();
                }
            };
            mHandler.sendEmptyMessageDelayed(MSG_CANCEL, MAX_WAIT_MS);
            send(MSG_SEND_SHARED_ELEMENT_DESTINATION, null);
        }
        setViewVisibility(mSharedElements, View.INVISIBLE);
        if (getViewsTransition() != null) {
            setViewVisibility(mTransitioningViews, View.INVISIBLE);
        }
        if (mSharedElementsBundle != null) {
            onTakeSharedElements();
        }
    }

    private void sendSharedElementDestination() {
        ViewGroup decor = getDecor();
        if (!decor.isLayoutRequested()) {
            Bundle state = captureSharedElementState();
            mResultReceiver.send(MSG_SHARED_ELEMENT_DESTINATION, state);
        } else {
            getDecor().getViewTreeObserver()
                    .addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                        @Override
                        public boolean onPreDraw() {
                            getDecor().getViewTreeObserver().removeOnPreDrawListener(this);
                            return true;
                        }
                    });
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
                    if (mHandler != null) {
                        mHandler.removeMessages(MSG_CANCEL);
                    }
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
            case MSG_SEND_SHARED_ELEMENT_DESTINATION:
                sendSharedElementDestination();
                break;
        }
    }

    private void cancel() {
        if (!mIsCanceled) {
            mIsCanceled = true;
            if (getViewsTransition() == null) {
                setViewVisibility(mSharedElements, View.VISIBLE);
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
            mActivity.convertToTranslucent(null, null);
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
            return getWindow().getExitTransition();
        } else {
            return getWindow().getEnterTransition();
        }
    }

    protected Transition getSharedElementTransition() {
        if (mIsReturning) {
            return getWindow().getSharedElementExitTransition();
        } else {
            return getWindow().getSharedElementEnterTransition();
        }
    }

    protected void onTakeSharedElements() {
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
                        startSharedElementTransition(sharedElementState);
                        return false;
                    }
                });
        getDecor().invalidate();
    }

    private void startSharedElementTransition(Bundle sharedElementState) {
        setEpicenter();
        // Remove rejected shared elements
        ArrayList<String> rejectedNames = new ArrayList<String>(mAllSharedElementNames);
        rejectedNames.removeAll(mSharedElementNames);
        ArrayList<View> rejectedSnapshots = createSnapshots(sharedElementState, rejectedNames);
        mListener.handleRejectedSharedElements(rejectedSnapshots);
        startRejectedAnimations(rejectedSnapshots);

        // Now start shared element transition
        ArrayList<View> sharedElementSnapshots = createSnapshots(sharedElementState,
                mSharedElementNames);
        setViewVisibility(mSharedElements, View.VISIBLE);
        ArrayMap<ImageView, Pair<ImageView.ScaleType, Matrix>> originalImageViewState =
                setSharedElementState(sharedElementState, sharedElementSnapshots);
        requestLayoutForSharedElements();

        boolean startEnterTransition = allowOverlappingTransitions();
        boolean startSharedElementTransition = true;
        Transition transition = beginTransition(startEnterTransition, startSharedElementTransition);

        if (startEnterTransition) {
            startEnterTransition(transition);
        }

        setOriginalImageViewState(originalImageViewState);

        if (mResultReceiver != null) {
            mResultReceiver.send(MSG_HIDE_SHARED_ELEMENTS, null);
        }
        mResultReceiver = null; // all done sending messages.
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
        if (startSharedElementTransition && !mSharedElementNames.isEmpty()) {
            sharedElementTransition = configureTransition(getSharedElementTransition());
        }
        Transition viewsTransition = null;
        if (startEnterTransition && !mTransitioningViews.isEmpty()) {
            viewsTransition = configureTransition(getViewsTransition());
            viewsTransition = addTargets(viewsTransition, mTransitioningViews);
        }

        Transition transition = mergeTransitions(sharedElementTransition, viewsTransition);
        if (startSharedElementTransition) {
            if (transition == null) {
                sharedElementTransitionStarted();
            } else {
                transition.addListener(new Transition.TransitionListenerAdapter() {
                    @Override
                    public void onTransitionStart(Transition transition) {
                        transition.removeListener(this);
                        sharedElementTransitionStarted();
                    }
                });
            }
        }
        if (transition != null) {
            TransitionManager.beginDelayedTransition(getDecor(), transition);
            if (startSharedElementTransition && !mSharedElementNames.isEmpty()) {
                mSharedElements.get(0).invalidate();
            } else if (startEnterTransition && !mTransitioningViews.isEmpty()) {
                mTransitioningViews.get(0).invalidate();
            }
        }
        return transition;
    }

    private void sharedElementTransitionStarted() {
        mSharedElementTransitionStarted = true;
        if (mIsExitTransitionComplete) {
            send(MSG_EXIT_TRANSITION_COMPLETE, null);
        }
    }

    private void startEnterTransition(Transition transition) {
        setViewVisibility(mTransitioningViews, View.VISIBLE);
        if (!mIsReturning) {
            Drawable background = getDecor().getBackground();
            if (background != null) {
                background = background.mutate();
                mBackgroundAnimator = ObjectAnimator.ofInt(background, "alpha", 255);
                mBackgroundAnimator.setDuration(FADE_BACKGROUND_DURATION_MS);
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
        mHasStopped = true;
        mIsCanceled = true;
        mResultReceiver = null;
        if (mBackgroundAnimator != null) {
            mBackgroundAnimator.cancel();
            mBackgroundAnimator = null;
        }
    }

    private void makeOpaque() {
        if (!mHasStopped && mActivity != null) {
            mActivity.convertFromTranslucent();
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
            boolean startEnterTransition = true;
            boolean startSharedElementTransition = false;
            Transition transition = beginTransition(startEnterTransition,
                    startSharedElementTransition);
            startEnterTransition(transition);
        }
    }
}
