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

package com.android.server.accessibility.magnification;

import android.content.Context;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.MathUtils;
import android.util.SparseArray;
import android.view.Display;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;

/**
 * Supplies setter/getter of the magnification scale for the given display. Only the value of the
 * default play is persisted. It also constraints the range of applied magnification scale between
 * [MIN_SCALE, MAX_SCALE] which is consistent with the range provided by
 * {@code AccessibilityService.MagnificationController#setScale()}.
 */
public class MagnificationScaleProvider {

    @VisibleForTesting
    protected static final float DEFAULT_MAGNIFICATION_SCALE = 2.0f;
    public static final float MIN_SCALE = 1.0f;
    public static final float MAX_SCALE = 8.0f;

    private final Context mContext;
    // Stores the scale for non-default displays.
    @GuardedBy("mLock")
    private final SparseArray<SparseArray<Float>> mUsersScales = new SparseArray();
    private int mCurrentUserId = UserHandle.USER_SYSTEM;
    private final Object mLock = new Object();

    public MagnificationScaleProvider(Context context) {
        mContext = context;
    }

    /**
     *  Stores the user settings scale associated to the given display. Only the scale of the
     *  default display is persistent.
     *
     * @param scale the magnification scale
     * @param displayId the id of the display
     */
    void putScale(float scale, int displayId) {
        if (displayId == Display.DEFAULT_DISPLAY) {
            BackgroundThread.getHandler().post(
                    () -> Settings.Secure.putFloatForUser(mContext.getContentResolver(),
                            Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE, scale,
                            mCurrentUserId));
        } else {
            synchronized (mLock) {
                getScalesWithCurrentUser().put(displayId, scale);
            }
        }
    }

    /**
     * Gets the user settings scale with the given display.
     *
     * @param displayId the id of the display
     * @return the magnification scale.
     */
    float getScale(int displayId) {
        if (displayId == Display.DEFAULT_DISPLAY) {
            return Settings.Secure.getFloatForUser(mContext.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_DISPLAY_MAGNIFICATION_SCALE,
                    DEFAULT_MAGNIFICATION_SCALE, mCurrentUserId);
        } else {
            synchronized (mLock) {
                return getScalesWithCurrentUser().get(displayId, DEFAULT_MAGNIFICATION_SCALE);
            }
        }
    }


    @GuardedBy("mLock")
    private SparseArray<Float> getScalesWithCurrentUser() {
        SparseArray<Float> scales = mUsersScales.get(mCurrentUserId);
        if (scales == null) {
            scales = new SparseArray<>();
            mUsersScales.put(mCurrentUserId, scales);
        }

        return scales;
    }

    void onUserChanged(int userId) {
        synchronized (mLock) {
            mCurrentUserId = userId;
        }
    }

    void onUserRemoved(int userId) {
        synchronized (mLock) {
            mUsersScales.remove(userId);
        }
    }

    void onDisplayRemoved(int displayId) {
        synchronized (mLock) {
            final int userCounts = mUsersScales.size();
            for (int i = userCounts - 1; i >= 0; i--) {
                mUsersScales.get(i).remove(displayId);
            }
        }
    }

    @Override
    public String toString() {
        synchronized (mLock) {
            return "MagnificationScaleProvider{"
                    + "mCurrentUserId=" + mCurrentUserId
                    + "Scale on the default display=" + getScale(Display.DEFAULT_DISPLAY)
                    + "Scales on non-default displays=" + getScalesWithCurrentUser()
                    + '}';
        }
    }

    static float constrainScale(float scale) {
        return MathUtils.constrain(scale, MIN_SCALE, MAX_SCALE);
    }
}
