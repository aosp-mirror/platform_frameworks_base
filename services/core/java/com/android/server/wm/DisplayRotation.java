/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wm;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Resources;
import android.view.Surface;

import com.android.server.policy.WindowManagerPolicy;
import com.android.server.policy.WindowManagerPolicy.RotationSource;

import java.io.PrintWriter;

/**
 * Defines the mapping between orientation and rotation of a display.
 */
public class DisplayRotation {
    private final DisplayContent mDisplayContent;
    private final WindowManagerPolicy mPolicy;
    private final Context mContext;
    private RotationSource mRotationSource;

    int mCurrentAppOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    int mLandscapeRotation;  // default landscape
    int mSeascapeRotation;   // "other" landscape, 180 degrees from mLandscapeRotation
    int mPortraitRotation;   // default portrait
    int mUpsideDownRotation; // "other" portrait

    DisplayRotation(DisplayContent displayContent, WindowManagerPolicy policy, Context context) {
        mDisplayContent = displayContent;
        mPolicy = policy;
        mContext = context;
    }

    void configure() {
        mRotationSource = mPolicy.getRotationSource(mDisplayContent.getDisplayId());

        final int width = mDisplayContent.mBaseDisplayWidth;
        final int height = mDisplayContent.mBaseDisplayHeight;
        final Resources res = mContext.getResources();
        if (width > height) {
            mLandscapeRotation = Surface.ROTATION_0;
            mSeascapeRotation = Surface.ROTATION_180;
            if (res.getBoolean(com.android.internal.R.bool.config_reverseDefaultRotation)) {
                mPortraitRotation = Surface.ROTATION_90;
                mUpsideDownRotation = Surface.ROTATION_270;
            } else {
                mPortraitRotation = Surface.ROTATION_270;
                mUpsideDownRotation = Surface.ROTATION_90;
            }
        } else {
            mPortraitRotation = Surface.ROTATION_0;
            mUpsideDownRotation = Surface.ROTATION_180;
            if (res.getBoolean(com.android.internal.R.bool.config_reverseDefaultRotation)) {
                mLandscapeRotation = Surface.ROTATION_270;
                mSeascapeRotation = Surface.ROTATION_90;
            } else {
                mLandscapeRotation = Surface.ROTATION_90;
                mSeascapeRotation = Surface.ROTATION_270;
            }
        }

        mPolicy.setInitialDisplaySize(this, width, height, mDisplayContent.mBaseDisplayDensity);
    }

    public int getLandscapeRotation() {
        return mLandscapeRotation;
    }

    public int getSeascapeRotation() {
        return mSeascapeRotation;
    }

    public int getPortraitRotation() {
        return mPortraitRotation;
    }

    public int getUpsideDownRotation() {
        return mUpsideDownRotation;
    }

    public int getCurrentAppOrientation() {
        return mCurrentAppOrientation;
    }

    public int getSensorRotation() {
        return mRotationSource != null ? mRotationSource.getProposedRotation() : -1;
    }

    public boolean isDefaultDisplay() {
        return mDisplayContent.isDefaultDisplay;
    }

    void setRotation(int rotation) {
        if (mRotationSource != null) {
            mRotationSource.setCurrentRotation(rotation);
        }
    }

    void setCurrentOrientation(int newOrientation) {
        if (newOrientation != mCurrentAppOrientation) {
            mCurrentAppOrientation = newOrientation;
            // TODO(multi-display): Separate orientation listeners.
            if (mDisplayContent.isDefaultDisplay) {
                mPolicy.updateOrientationListener();
            }
        }
    }

    public int rotationForOrientation(int orientation, int lastRotation, int preferredRotation) {
        switch (orientation) {
            case ActivityInfo.SCREEN_ORIENTATION_PORTRAIT:
                // Return portrait unless overridden.
                if (isAnyPortrait(preferredRotation)) {
                    return preferredRotation;
                }
                return mPortraitRotation;

            case ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:
                // Return landscape unless overridden.
                if (isLandscapeOrSeascape(preferredRotation)) {
                    return preferredRotation;
                }
                return mLandscapeRotation;

            case ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT:
                // Return reverse portrait unless overridden.
                if (isAnyPortrait(preferredRotation)) {
                    return preferredRotation;
                }
                return mUpsideDownRotation;

            case ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE:
                // Return seascape unless overridden.
                if (isLandscapeOrSeascape(preferredRotation)) {
                    return preferredRotation;
                }
                return mSeascapeRotation;

            case ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE:
            case ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE:
                // Return either landscape rotation.
                if (isLandscapeOrSeascape(preferredRotation)) {
                    return preferredRotation;
                }
                if (isLandscapeOrSeascape(lastRotation)) {
                    return lastRotation;
                }
                return mLandscapeRotation;

            case ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT:
            case ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT:
                // Return either portrait rotation.
                if (isAnyPortrait(preferredRotation)) {
                    return preferredRotation;
                }
                if (isAnyPortrait(lastRotation)) {
                    return lastRotation;
                }
                return mPortraitRotation;

            default:
                // For USER, UNSPECIFIED, NOSENSOR, SENSOR and FULL_SENSOR,
                // just return the preferred orientation we already calculated.
                if (preferredRotation >= 0) {
                    return preferredRotation;
                }
                return Surface.ROTATION_0;
        }
    }

    /**
     * Given an orientation constant and a rotation, returns true if the rotation
     * has compatible metrics to the requested orientation.  For example, if
     * the application requested landscape and got seascape, then the rotation
     * has compatible metrics; if the application requested portrait and got landscape,
     * then the rotation has incompatible metrics; if the application did not specify
     * a preference, then anything goes.
     *
     * @param orientation An orientation constant, such as
     * {@link android.content.pm.ActivityInfo#SCREEN_ORIENTATION_LANDSCAPE}.
     * @param rotation The rotation to check.
     * @return True if the rotation is compatible with the requested orientation.
     */
    boolean rotationHasCompatibleMetrics(int orientation, int rotation) {
        switch (orientation) {
            case ActivityInfo.SCREEN_ORIENTATION_PORTRAIT:
            case ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT:
            case ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT:
                return isAnyPortrait(rotation);

            case ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:
            case ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE:
            case ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE:
                return isLandscapeOrSeascape(rotation);

            default:
                return true;
        }
    }

    public boolean isValidRotationChoice(final int preferredRotation) {
        // Determine if the given app orientation is compatible with the provided rotation choice.
        switch (mCurrentAppOrientation) {
            case ActivityInfo.SCREEN_ORIENTATION_FULL_USER:
                // Works with any of the 4 rotations.
                return preferredRotation >= 0;

            case ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT:
                // It's possible for the user pref to be set at 180 because of FULL_USER. This would
                // make switching to USER_PORTRAIT appear at 180. Provide choice to back to portrait
                // but never to go to 180.
                return preferredRotation == mPortraitRotation;

            case ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE:
                // Works landscape or seascape.
                return isLandscapeOrSeascape(preferredRotation);

            case ActivityInfo.SCREEN_ORIENTATION_USER:
            case ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED:
                // Works with any rotation except upside down.
                return (preferredRotation >= 0) && (preferredRotation != mUpsideDownRotation);
        }

        return false;
    }

    private boolean isLandscapeOrSeascape(int rotation) {
        return rotation == mLandscapeRotation || rotation == mSeascapeRotation;
    }

    private boolean isAnyPortrait(int rotation) {
        return rotation == mPortraitRotation || rotation == mUpsideDownRotation;
    }

    void dump(String prefix, PrintWriter pw) {
        pw.println(prefix + "DisplayRotation");
        pw.println(prefix + "  mCurrentAppOrientation="
                + ActivityInfo.screenOrientationToString(mCurrentAppOrientation));
        pw.print(prefix + "  mLandscapeRotation=" + Surface.rotationToString(mLandscapeRotation));
        pw.println(" mSeascapeRotation=" + Surface.rotationToString(mSeascapeRotation));
        pw.print(prefix + "  mPortraitRotation=" + Surface.rotationToString(mPortraitRotation));
        pw.println(" mUpsideDownRotation=" + Surface.rotationToString(mUpsideDownRotation));
    }
}
