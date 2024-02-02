/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static android.os.Trace.TRACE_TAG_VIEW;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Trace;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Controller to request refresh rate preference operations to the {@link ViewRootImpl}.
 *
 * @hide
 */
public class ViewRootRefreshRateController {

    private static final String TAG = "VRRefreshRateController";

    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final float TARGET_REFRESH_RATE_UPPER_BOUND = 60f;

    @NonNull
    private final ViewRootImpl mViewRootImpl;

    private final RefreshRateParams mRateParams;

    private final boolean mHasPreferredRefreshRate;

    private int mRefreshRatePref = RefreshRatePref.NONE;

    private boolean mMaxRefreshRateOverride = false;

    @IntDef(value = {
            RefreshRatePref.NONE,
            RefreshRatePref.LOWER,
            RefreshRatePref.RESTORE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RefreshRatePref {
        /**
         * Indicates that no refresh rate preference.
         */
        int NONE = 0;

        /**
         * Indicates that apply the lower refresh rate.
         */
        int LOWER = 1;

        /**
         * Indicates that restore to previous refresh rate.
         */
        int RESTORE = 2;
    }

    public ViewRootRefreshRateController(@NonNull ViewRootImpl viewRoot) {
        mViewRootImpl = viewRoot;
        mRateParams = new RefreshRateParams(getLowerSupportedRefreshRate());
        mHasPreferredRefreshRate = hasPreferredRefreshRate();
        if (mHasPreferredRefreshRate && DEBUG) {
            Log.d(TAG, "App has preferred refresh rate. name:" + viewRoot);
        }
    }

    /**
     * Updates the preference to {@link ViewRootRefreshRateController#mRefreshRatePref},
     * and check if it's needed to update the preferred refresh rate on demand. Like if the
     * user is typing, try to apply the {@link RefreshRateParams#mTargetRefreshRate}.
     *
     * @param refreshRatePref to indicate the refresh rate preference
     */
    public void updateRefreshRatePreference(@RefreshRatePref int refreshRatePref) {
        mRefreshRatePref = refreshRatePref;
        doRefreshRateCheck();
    }

    private void doRefreshRateCheck() {
        if (mRefreshRatePref == RefreshRatePref.NONE) {
            return;
        }
        if (mHasPreferredRefreshRate) {
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "mMaxRefreshRateOverride:" + mMaxRefreshRateOverride
                    + ", mRefreshRatePref:" + refreshRatePrefToString(mRefreshRatePref));
        }

        switch (mRefreshRatePref) {
            case RefreshRatePref.LOWER :
                if (!mMaxRefreshRateOverride) {
                    // Save previous preferred rate before update
                    mRateParams.savePreviousRefreshRateParams(mViewRootImpl.mWindowAttributes);
                    updateMaxRefreshRate();
                } else if (mViewRootImpl.mDisplay.getRefreshRate()
                        > mRateParams.mTargetRefreshRate) {
                    // Boosted, try to update again.
                    updateMaxRefreshRate();
                }
                break;
            case RefreshRatePref.RESTORE :
                resetRefreshRate();
                break;
            default :
                throw new RuntimeException("Unexpected value: " + mRefreshRatePref);
        }
    }

    private void updateMaxRefreshRate() {
        Trace.traceBegin(TRACE_TAG_VIEW, "VRRC.updateMaxRefreshRate");
        WindowManager.LayoutParams params = mViewRootImpl.mWindowAttributes;
        params.preferredMaxDisplayRefreshRate = mRateParams.mTargetRefreshRate;
        mViewRootImpl.setLayoutParams(params, false);
        mMaxRefreshRateOverride = true;
        Trace.instant(TRACE_TAG_VIEW, "VRRC update preferredMax="
                + mRateParams.mTargetRefreshRate);
        Trace.traceEnd(TRACE_TAG_VIEW);
        if (DEBUG) {
            Log.d(TAG, "update max refresh rate to: " + params.preferredMaxDisplayRefreshRate);
        }
    }

    private void resetRefreshRate() {
        if (!mMaxRefreshRateOverride) {
            return;
        }
        Trace.traceBegin(TRACE_TAG_VIEW, "VRRC.resetRefreshRate");
        WindowManager.LayoutParams params = mViewRootImpl.mWindowAttributes;
        params.preferredMaxDisplayRefreshRate = mRateParams.mPreviousPreferredMaxRefreshRate;
        mViewRootImpl.setLayoutParams(params, false);
        mMaxRefreshRateOverride = false;
        Trace.instant(TRACE_TAG_VIEW, "VRRC restore previous="
                + mRateParams.mPreviousPreferredMaxRefreshRate);
        Trace.traceEnd(TRACE_TAG_VIEW);
        if (DEBUG) {
            Log.d(TAG, "reset max refresh rate to: " + params.preferredMaxDisplayRefreshRate);
        }
    }

    private boolean hasPreferredRefreshRate() {
        WindowManager.LayoutParams params = mViewRootImpl.mWindowAttributes;
        return params.preferredRefreshRate > 0
                || params.preferredMaxDisplayRefreshRate > 0
                || params.preferredMinDisplayRefreshRate > 0
                || params.preferredDisplayModeId > 0;
    }

    private float getLowerSupportedRefreshRate() {
        final Display display = mViewRootImpl.mDisplay;
        final Display.Mode defaultMode = display.getDefaultMode();
        float targetRefreshRate = defaultMode.getRefreshRate();
        for (Display.Mode mode : display.getSupportedModes()) {
            if (mode.getRefreshRate() < targetRefreshRate) {
                targetRefreshRate = mode.getRefreshRate();
            }
        }
        if (targetRefreshRate < TARGET_REFRESH_RATE_UPPER_BOUND) {
            targetRefreshRate = TARGET_REFRESH_RATE_UPPER_BOUND;
        }
        return targetRefreshRate;
    }

    private static String refreshRatePrefToString(@RefreshRatePref int pref) {
        switch (pref) {
            case RefreshRatePref.NONE:
                return "NONE";
            case RefreshRatePref.LOWER:
                return "LOWER";
            case RefreshRatePref.RESTORE:
                return "RESTORE";
            default:
                return "Unknown pref=" + pref;
        }
    }

    /**
     * A class for recording refresh rate parameters of the target view, including the target
     * refresh rate we want to apply when entering particular states, and the original preferred
     * refresh rate for restoring when leaving the state.
     */
    private static class RefreshRateParams {
        float mTargetRefreshRate;

        float mPreviousPreferredMaxRefreshRate = 0;

        RefreshRateParams(float targetRefreshRate) {
            mTargetRefreshRate = targetRefreshRate;
            if (DEBUG) {
                Log.d(TAG, "The target rate: " + targetRefreshRate);
            }
        }
        void savePreviousRefreshRateParams(WindowManager.LayoutParams param) {
            mPreviousPreferredMaxRefreshRate = param.preferredMaxDisplayRefreshRate;
            if (DEBUG) {
                Log.d(TAG, "Save previous params, preferred: " + param.preferredRefreshRate
                        + ", Max: " + param.preferredMaxDisplayRefreshRate);
            }
        }
    }
}
