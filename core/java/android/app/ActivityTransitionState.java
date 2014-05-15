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
import android.os.ResultReceiver;
import android.util.ArrayMap;
import android.view.View;
import android.view.Window;

import java.util.ArrayList;

/**
 * This class contains all persistence-related functionality for Activity Transitions.
 * Activities start exit and enter Activity Transitions through this class.
 */
class ActivityTransitionState {

    private static final String ENTERING_SHARED_ELEMENTS = "android:enteringSharedElements";

    private static final String ENTERING_MAPPED_FROM = "android:enteringMappedFrom";

    private static final String ENTERING_MAPPED_TO = "android:enteringMappedTo";

    private static final String EXITING_MAPPED_FROM = "android:exitingMappedFrom";

    private static final String EXITING_MAPPED_TO = "android:exitingMappedTo";

    /**
     * The shared elements that the calling Activity has said that they transferred to this
     * Activity.
     */
    private ArrayList<String> mEnteringNames;

    /**
     * The shared elements that this Activity as accepted and mapped to local Views.
     */
    private ArrayList<String> mEnteringFrom;

    /**
     * The names of local Views that are mapped to those elements in mEnteringFrom.
     */
    private ArrayList<String> mEnteringTo;

    /**
     * The names of shared elements that were shared to the called Activity.
     */
    private ArrayList<String> mExitingFrom;

    /**
     * The names of local Views that were shared out, mapped to those elements in mExitingFrom.
     */
    private ArrayList<String> mExitingTo;

    /**
     * The ActivityOptions used to call an Activity. Used to make the elements restore
     * Visibility of exited Views.
     */
    private ActivityOptions mCalledActivityOptions;

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

    public ActivityTransitionState() {
    }

    public void readState(Bundle bundle) {
        if (bundle != null) {
            if (mEnterTransitionCoordinator == null || mEnterTransitionCoordinator.isReturning()) {
                mEnteringNames = bundle.getStringArrayList(ENTERING_SHARED_ELEMENTS);
                mEnteringFrom = bundle.getStringArrayList(ENTERING_MAPPED_FROM);
                mEnteringTo = bundle.getStringArrayList(ENTERING_MAPPED_TO);
            }
            if (mEnterTransitionCoordinator == null) {
                mExitingFrom = bundle.getStringArrayList(EXITING_MAPPED_FROM);
                mExitingTo = bundle.getStringArrayList(EXITING_MAPPED_TO);
            }
        }
    }

    public void saveState(Bundle bundle) {
        if (mEnteringNames != null) {
            bundle.putStringArrayList(ENTERING_SHARED_ELEMENTS, mEnteringNames);
            bundle.putStringArrayList(ENTERING_MAPPED_FROM, mEnteringFrom);
            bundle.putStringArrayList(ENTERING_MAPPED_TO, mEnteringTo);
        }
        if (mExitingFrom != null) {
            bundle.putStringArrayList(EXITING_MAPPED_FROM, mExitingFrom);
            bundle.putStringArrayList(EXITING_MAPPED_TO, mExitingTo);
        }
    }

    public void setEnterActivityOptions(Activity activity, ActivityOptions options) {
        if (activity.getWindow().hasFeature(Window.FEATURE_CONTENT_TRANSITIONS)
                && options != null && mEnterActivityOptions == null
                && options.getAnimationType() == ActivityOptions.ANIM_SCENE_TRANSITION) {
            mEnterActivityOptions = options;
            if (mEnterActivityOptions.isReturning()) {
                int result = mEnterActivityOptions.getResultCode();
                if (result != 0) {
                    activity.onActivityReenter(result, mEnterActivityOptions.getResultData());
                }
            }
        }
    }

    public void enterReady(Activity activity) {
        if (mEnterActivityOptions == null) {
            return;
        }
        mHasExited = false;
        ArrayList<String> sharedElementNames = mEnterActivityOptions.getSharedElementNames();
        ResultReceiver resultReceiver = mEnterActivityOptions.getResultReceiver();
        if (mEnterActivityOptions.isReturning()) {
            restoreExitedViews();
            activity.getWindow().getDecorView().setVisibility(View.VISIBLE);
            mEnterTransitionCoordinator = new EnterTransitionCoordinator(activity,
                    resultReceiver, sharedElementNames, mExitingFrom, mExitingTo);
        } else {
            mEnterTransitionCoordinator = new EnterTransitionCoordinator(activity,
                    resultReceiver, sharedElementNames, null, null);
            mEnteringNames = sharedElementNames;
            mEnteringFrom = mEnterTransitionCoordinator.getAcceptedNames();
            mEnteringTo = mEnterTransitionCoordinator.getMappedNames();
        }
        mExitingFrom = null;
        mExitingTo = null;
        mEnterActivityOptions = null;
    }

    public void onStop() {
        restoreExitedViews();
        if (mEnterTransitionCoordinator != null) {
            mEnterTransitionCoordinator.stop();
            mEnterTransitionCoordinator = null;
        }
    }

    public void onResume() {
        restoreExitedViews();
    }

    private void restoreExitedViews() {
        if (mCalledActivityOptions != null) {
            mCalledActivityOptions.dispatchActivityStopped();
            mCalledActivityOptions = null;
        }
    }

    public boolean startExitBackTransition(Activity activity) {
        if (mEnteringNames == null) {
            return false;
        } else {
            if (!mHasExited) {
                mHasExited = true;
                if (mEnterTransitionCoordinator != null) {
                    mEnterTransitionCoordinator.stop();
                    mEnterTransitionCoordinator = null;
                }
                ArrayMap<String, View> sharedElements = new ArrayMap<String, View>();
                activity.getWindow().getDecorView().findNamedViews(sharedElements);

                ExitTransitionCoordinator exitCoordinator =
                        new ExitTransitionCoordinator(activity, mEnteringNames, mEnteringFrom,
                                mEnteringTo, true);
                exitCoordinator.startExit(activity.mResultCode, activity.mResultData);
            }
            return true;
        }
    }

    public void startExitOutTransition(Activity activity, Bundle options) {
        if (!activity.getWindow().hasFeature(Window.FEATURE_CONTENT_TRANSITIONS)) {
            return;
        }
        mCalledActivityOptions = new ActivityOptions(options);
        if (mCalledActivityOptions.getAnimationType() == ActivityOptions.ANIM_SCENE_TRANSITION) {
            mExitingFrom = mCalledActivityOptions.getSharedElementNames();
            mExitingTo = mCalledActivityOptions.getLocalSharedElementNames();
            mCalledActivityOptions.dispatchStartExit();
        }
    }
}
