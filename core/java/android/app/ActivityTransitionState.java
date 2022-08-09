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

import android.content.Intent;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.transition.Transition;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import com.android.internal.view.OneShotPreDrawListener;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * This class contains all persistence-related functionality for Activity Transitions.
 * Activities start exit and enter Activity Transitions through this class.
 */
class ActivityTransitionState {

    private static final String PENDING_EXIT_SHARED_ELEMENTS = "android:pendingExitSharedElements";

    private static final String EXITING_MAPPED_FROM = "android:exitingMappedFrom";

    private static final String EXITING_MAPPED_TO = "android:exitingMappedTo";

    /**
     * The shared elements that the calling Activity has said that they transferred to this
     * Activity and will be transferred back during exit animation.
     */
    private ArrayList<String> mPendingExitNames;

    /**
     * The names of shared elements that were shared to the called Activity.
     */
    private ArrayList<String> mExitingFrom;

    /**
     * The names of local Views that were shared out, mapped to those elements in mExitingFrom.
     */
    private ArrayList<String> mExitingTo;

    /**
     * The local Views that were shared out, mapped to those elements in mExitingFrom.
     */
    private ArrayList<View> mExitingToView;

    /**
     * The ExitTransitionCoordinator used to start an Activity. Used to make the elements restore
     * Visibility of exited Views.
     */
    private ExitTransitionCoordinator mCalledExitCoordinator;

    /**
     * The ExitTransitionCoordinator used to return to a previous Activity when called with
     * {@link android.app.Activity#finishAfterTransition()}.
     */
    private ExitTransitionCoordinator mReturnExitCoordinator;

    /**
     * We must be able to cancel entering transitions to stop changing the Window to
     * opaque when we exit before making the Window opaque.
     */
    private EnterTransitionCoordinator mEnterTransitionCoordinator;

    /**
     * ActivityOptions used on entering this Activity.
     */
    private ActivityOptions mEnterActivityOptions;

    /**
     * Has an exit transition been started? If so, we don't want to double-exit.
     */
    private boolean mHasExited;

    /**
     * Postpone painting and starting the enter transition until this is false.
     */
    private boolean mIsEnterPostponed;

    /**
     * Potential exit transition coordinators.
     */
    private SparseArray<WeakReference<ExitTransitionCoordinator>> mExitTransitionCoordinators;

    /**
     * Next key for mExitTransitionCoordinator.
     */
    private int mExitTransitionCoordinatorsKey = 1;

    private boolean mIsEnterTriggered;

    public ActivityTransitionState() {
    }

    public int addExitTransitionCoordinator(ExitTransitionCoordinator exitTransitionCoordinator) {
        if (mExitTransitionCoordinators == null) {
            mExitTransitionCoordinators = new SparseArray<>();
        }
        WeakReference<ExitTransitionCoordinator> ref = new WeakReference(exitTransitionCoordinator);
        // clean up old references:
        for (int i = mExitTransitionCoordinators.size() - 1; i >= 0; i--) {
            WeakReference<ExitTransitionCoordinator> oldRef
                    = mExitTransitionCoordinators.valueAt(i);
            if (oldRef.refersTo(null)) {
                mExitTransitionCoordinators.removeAt(i);
            }
        }
        int newKey = mExitTransitionCoordinatorsKey++;
        mExitTransitionCoordinators.append(newKey, ref);
        return newKey;
    }

    public void readState(Bundle bundle) {
        if (bundle != null) {
            if (mEnterTransitionCoordinator == null || mEnterTransitionCoordinator.isReturning()) {
                mPendingExitNames = bundle.getStringArrayList(PENDING_EXIT_SHARED_ELEMENTS);
            }
            if (mEnterTransitionCoordinator == null) {
                mExitingFrom = bundle.getStringArrayList(EXITING_MAPPED_FROM);
                mExitingTo = bundle.getStringArrayList(EXITING_MAPPED_TO);
            }
        }
    }

    /**
     * Returns the element names to be used for exit animation. It caches the list internally so
     * that it is preserved through activty destroy and restore.
     */
    private ArrayList<String> getPendingExitNames() {
        if (mPendingExitNames == null && mEnterTransitionCoordinator != null) {
            mPendingExitNames = mEnterTransitionCoordinator.getPendingExitSharedElementNames();
        }
        return mPendingExitNames;
    }

    public void saveState(Bundle bundle) {
        ArrayList<String> pendingExitNames = getPendingExitNames();
        if (pendingExitNames != null) {
            bundle.putStringArrayList(PENDING_EXIT_SHARED_ELEMENTS, pendingExitNames);
        }
        if (mExitingFrom != null) {
            bundle.putStringArrayList(EXITING_MAPPED_FROM, mExitingFrom);
            bundle.putStringArrayList(EXITING_MAPPED_TO, mExitingTo);
        }
    }

    public void setEnterActivityOptions(Activity activity, ActivityOptions options) {
        final Window window = activity.getWindow();
        if (window == null) {
            return;
        }
        // ensure Decor View has been created so that the window features are activated
        window.getDecorView();
        if (window.hasFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
                && options != null && mEnterActivityOptions == null
                && mEnterTransitionCoordinator == null
                && options.getAnimationType() == ActivityOptions.ANIM_SCENE_TRANSITION) {
            mEnterActivityOptions = options;
            mIsEnterTriggered = false;
            if (mEnterActivityOptions.isReturning()) {
                restoreExitedViews();
                int result = mEnterActivityOptions.getResultCode();
                if (result != 0) {
                    Intent intent = mEnterActivityOptions.getResultData();
                    if (intent != null) {
                        intent.setExtrasClassLoader(activity.getClassLoader());
                    }
                    activity.onActivityReenter(result, intent);
                }
            }
        }
    }

    public void enterReady(Activity activity) {
        if (mEnterActivityOptions == null || mIsEnterTriggered) {
            return;
        }
        mIsEnterTriggered = true;
        mHasExited = false;
        ArrayList<String> sharedElementNames = mEnterActivityOptions.getSharedElementNames();
        ResultReceiver resultReceiver = mEnterActivityOptions.getResultReceiver();
        final boolean isReturning = mEnterActivityOptions.isReturning();
        if (isReturning) {
            restoreExitedViews();
            activity.getWindow().getDecorView().setVisibility(View.VISIBLE);
        }
        mEnterTransitionCoordinator = new EnterTransitionCoordinator(activity,
                resultReceiver, sharedElementNames, mEnterActivityOptions.isReturning(),
                mEnterActivityOptions.isCrossTask());
        if (mEnterActivityOptions.isCrossTask()) {
            mExitingFrom = new ArrayList<>(mEnterActivityOptions.getSharedElementNames());
            mExitingTo = new ArrayList<>(mEnterActivityOptions.getSharedElementNames());
        }

        if (!mIsEnterPostponed) {
            startEnter();
        }
    }

    public void postponeEnterTransition() {
        mIsEnterPostponed = true;
    }

    public void startPostponedEnterTransition() {
        if (mIsEnterPostponed) {
            mIsEnterPostponed = false;
            if (mEnterTransitionCoordinator != null) {
                startEnter();
            }
        }
    }

    private void startEnter() {
        if (mEnterTransitionCoordinator.isReturning()) {
            if (mExitingToView != null) {
                mEnterTransitionCoordinator.viewInstancesReady(mExitingFrom, mExitingTo,
                        mExitingToView);
            } else {
                mEnterTransitionCoordinator.namedViewsReady(mExitingFrom, mExitingTo);
            }
        } else {
            mEnterTransitionCoordinator.namedViewsReady(null, null);
            mPendingExitNames = null;
        }

        mExitingFrom = null;
        mExitingTo = null;
        mExitingToView = null;
        mEnterActivityOptions = null;
    }

    public void onStop(Activity activity) {
        restoreExitedViews();
        if (mEnterTransitionCoordinator != null) {
            mEnterTransitionCoordinator.stop();
            mEnterTransitionCoordinator = null;
        }
        if (mReturnExitCoordinator != null) {
            mReturnExitCoordinator.stop(activity);
            mReturnExitCoordinator = null;
        }
    }

    public void onResume(Activity activity) {
        // After orientation change, the onResume can come in before the top Activity has
        // left, so if the Activity is not top, wait a second for the top Activity to exit.
        if (mEnterTransitionCoordinator == null || activity.isTopOfTask()) {
            restoreExitedViews();
            restoreReenteringViews();
        } else {
            activity.mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (mEnterTransitionCoordinator == null ||
                            mEnterTransitionCoordinator.isWaitingForRemoteExit()) {
                        restoreExitedViews();
                        restoreReenteringViews();
                    } else if (mEnterTransitionCoordinator.isReturning()) {
                        mEnterTransitionCoordinator.runAfterTransitionsComplete(() -> {
                            mEnterTransitionCoordinator = null;
                        });
                    }
                }
            }, 1000);
        }
    }

    public void clear() {
        mPendingExitNames = null;
        mExitingFrom = null;
        mExitingTo = null;
        mExitingToView = null;
        mCalledExitCoordinator = null;
        mEnterTransitionCoordinator = null;
        mEnterActivityOptions = null;
        mExitTransitionCoordinators = null;
    }

    private void restoreExitedViews() {
        if (mCalledExitCoordinator != null) {
            mCalledExitCoordinator.resetViews();
            mCalledExitCoordinator = null;
        }
    }

    private void restoreReenteringViews() {
        if (mEnterTransitionCoordinator != null && mEnterTransitionCoordinator.isReturning() &&
                !mEnterTransitionCoordinator.isCrossTask()) {
            mEnterTransitionCoordinator.forceViewsToAppear();
            mExitingFrom = null;
            mExitingTo = null;
            mExitingToView = null;
        }
    }

    public boolean startExitBackTransition(final Activity activity) {
        ArrayList<String> pendingExitNames = getPendingExitNames();
        if (pendingExitNames == null || mCalledExitCoordinator != null) {
            return false;
        } else {
            if (!mHasExited) {
                mHasExited = true;
                Transition enterViewsTransition = null;
                ViewGroup decor = null;
                boolean delayExitBack = false;
                if (mEnterTransitionCoordinator != null) {
                    enterViewsTransition = mEnterTransitionCoordinator.getEnterViewsTransition();
                    decor = mEnterTransitionCoordinator.getDecor();
                    delayExitBack = mEnterTransitionCoordinator.cancelEnter();
                    mEnterTransitionCoordinator = null;
                    if (enterViewsTransition != null && decor != null) {
                        enterViewsTransition.pause(decor);
                    }
                }

                mReturnExitCoordinator = new ExitTransitionCoordinator(
                        new ExitTransitionCoordinator.ActivityExitTransitionCallbacks(activity),
                        activity.getWindow(), activity.mEnterTransitionListener, pendingExitNames,
                        null, null, true);
                if (enterViewsTransition != null && decor != null) {
                    enterViewsTransition.resume(decor);
                }
                if (delayExitBack && decor != null) {
                    final ViewGroup finalDecor = decor;
                    OneShotPreDrawListener.add(decor, () -> {
                        if (mReturnExitCoordinator != null) {
                            mReturnExitCoordinator.startExit(activity);
                        }
                    });
                } else {
                    mReturnExitCoordinator.startExit(activity);
                }
            }
            return true;
        }
    }

    public boolean isTransitionRunning() {
        // Note that *only* enter *or* exit will be running at any given time
        if (mEnterTransitionCoordinator != null) {
            if (mEnterTransitionCoordinator.isTransitionRunning()) {
                return true;
            }
        }
        if (mCalledExitCoordinator != null) {
            if (mCalledExitCoordinator.isTransitionRunning()) {
                return true;
            }
        }
        if (mReturnExitCoordinator != null) {
            if (mReturnExitCoordinator.isTransitionRunning()) {
                return true;
            }
        }
        return false;
    }

    public void startExitOutTransition(Activity activity, Bundle options) {
        mEnterTransitionCoordinator = null;
        if (!activity.getWindow().hasFeature(Window.FEATURE_ACTIVITY_TRANSITIONS) ||
                mExitTransitionCoordinators == null) {
            return;
        }
        ActivityOptions activityOptions = new ActivityOptions(options);
        if (activityOptions.getAnimationType() == ActivityOptions.ANIM_SCENE_TRANSITION) {
            int key = activityOptions.getExitCoordinatorKey();
            int index = mExitTransitionCoordinators.indexOfKey(key);
            if (index >= 0) {
                mCalledExitCoordinator = mExitTransitionCoordinators.valueAt(index).get();
                mExitTransitionCoordinators.removeAt(index);
                if (mCalledExitCoordinator != null) {
                    mExitingFrom = mCalledExitCoordinator.getAcceptedNames();
                    mExitingTo = mCalledExitCoordinator.getMappedNames();
                    mExitingToView = mCalledExitCoordinator.copyMappedViews();
                    mCalledExitCoordinator.startExit();
                }
            }
        }
    }
}
