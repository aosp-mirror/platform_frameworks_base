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

import android.os.Bundle;
import android.transition.Transition;
import android.util.Pair;
import android.view.View;
import android.view.Window;

import java.util.ArrayList;

/**
 * This ActivityTransitionCoordinator is created in ActivityOptions#makeSceneTransitionAnimation
 * to govern the exit of the Scene and the shared elements when calling an Activity as well as
 * the reentry of the Scene when coming back from the called Activity.
 */
class ExitTransitionCoordinator extends ActivityTransitionCoordinator {
    private static final String TAG = "ExitTransitionCoordinator";

    /**
     * The Views that have exited and need to be restored to VISIBLE when returning to the
     * normal state.
     */
    private ArrayList<View> mTransitioningViews;

    /**
     * Has the exit started? We don't want to accidentally exit multiple times.
     */
    private boolean mExitStarted;

    /**
     * Has the called Activity's ResultReceiver been set?
     */
    private boolean mIsResultReceiverSet;

    /**
     * Has the exit transition completed? If so, we can notify as soon as the ResultReceiver
     * has been set.
     */
    private boolean mExitComplete;

    /**
     * Has the shared element transition completed? If so, we can notify as soon as the
     * ResultReceiver has been set.
     */
    private Bundle mSharedElements;

    /**
     * Has the shared element transition completed?
     */
    private boolean mSharedElementsComplete;

    public ExitTransitionCoordinator(Window window,
            ActivityOptions.ActivityTransitionListener listener) {
        super(window);
        setActivityTransitionListener(listener);
    }

    @Override
    protected void onSetResultReceiver() {
        mIsResultReceiverSet = true;
        notifyCompletions();
    }

    @Override
    protected void onPrepareRestore() {
        makeTransitioningViewsInvisible();
        setEnteringViews(mTransitioningViews);
        mTransitioningViews = null;
        super.onPrepareRestore();
    }

    @Override
    protected void onTakeSharedElements(ArrayList<String> sharedElementNames, Bundle state) {
        super.onTakeSharedElements(sharedElementNames, state);
        clearConnections();
    }

    @Override
    protected void onActivityStopped() {
        if (getViewsTransition() != null) {
            setViewVisibility(mTransitioningViews, View.VISIBLE);
        }
        super.onActivityStopped();
    }

    @Override
    protected void sharedElementTransitionComplete(Bundle bundle) {
        mSharedElements = bundle;
        mSharedElementsComplete = true;
        notifyCompletions();
    }

    @Override
    protected void onExitTransitionEnd() {
        mExitComplete = true;
        notifyCompletions();
        super.onExitTransitionEnd();
    }

    private void notifyCompletions() {
        if (mIsResultReceiverSet && mSharedElementsComplete) {
            if (mSharedElements != null) {
                notifySharedElementTransitionComplete(mSharedElements);
                mSharedElements = null;
            }
            if (mExitComplete) {
                notifyExitTransitionComplete();
            }
        }
    }

    @Override
    public void startExit() {
        if (!mExitStarted) {
            mExitStarted = true;
            setSharedElements();
            startExitTransition(getSharedElementNames());
        }
    }

    @Override
    protected Transition getViewsTransition() {
        if (!getWindow().hasFeature(Window.FEATURE_CONTENT_TRANSITIONS)) {
            return null;
        }
        return getWindow().getExitTransition();
    }

    @Override
    protected Transition getSharedElementTransition() {
        if (!getWindow().hasFeature(Window.FEATURE_CONTENT_TRANSITIONS)) {
            return null;
        }
        return getWindow().getSharedElementExitTransition();
    }

    private void makeTransitioningViewsInvisible() {
        if (getViewsTransition() != null) {
            setViewVisibility(mTransitioningViews, View.INVISIBLE);
        }
    }

    @Override
    protected void onStartExitTransition(ArrayList<View> exitingViews) {
        mTransitioningViews = new ArrayList<View>();
        if (exitingViews != null) {
            mTransitioningViews.addAll(exitingViews);
        }
        mTransitioningViews.addAll(getSharedElements());
    }

    @Override
    protected boolean allowOverlappingTransitions() {
        return getWindow().getAllowExitTransitionOverlap();
    }
}
