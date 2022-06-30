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

package android.app;

import android.os.Bundle;

/**
 * @hide
 */
public class ComponentOptions {

    /**
     * Default value for KEY_PENDING_INTENT_BACKGROUND_ACTIVITY_ALLOWED.
     * @hide
     **/
    public static final boolean PENDING_INTENT_BAL_ALLOWED_DEFAULT = true;

    /**
     * PendingIntent caller allows activity start even if PendingIntent creator is in background.
     * This only works if the PendingIntent caller is allowed to start background activities,
     * for example if it's in the foreground, or has BAL permission.
     * @hide
     */
    public static final String KEY_PENDING_INTENT_BACKGROUND_ACTIVITY_ALLOWED =
            "android.pendingIntent.backgroundActivityAllowed";

    /**
     * PendingIntent caller allows activity to be started if caller has BAL permission.
     * @hide
     */
    public static final String KEY_PENDING_INTENT_BACKGROUND_ACTIVITY_ALLOWED_BY_PERMISSION =
            "android.pendingIntent.backgroundActivityAllowedByPermission";

    private boolean mPendingIntentBalAllowed = PENDING_INTENT_BAL_ALLOWED_DEFAULT;
    private boolean mPendingIntentBalAllowedByPermission = false;

    ComponentOptions() {
    }

    ComponentOptions(Bundle opts) {
        // If the remote side sent us bad parcelables, they won't get the
        // results they want, which is their loss.
        opts.setDefusable(true);
        setPendingIntentBackgroundActivityLaunchAllowed(
                opts.getBoolean(KEY_PENDING_INTENT_BACKGROUND_ACTIVITY_ALLOWED,
                        PENDING_INTENT_BAL_ALLOWED_DEFAULT));
        setPendingIntentBackgroundActivityLaunchAllowedByPermission(
                opts.getBoolean(KEY_PENDING_INTENT_BACKGROUND_ACTIVITY_ALLOWED_BY_PERMISSION,
                        false));
    }

    /**
     * Set PendingIntent activity is allowed to be started in the background if the caller
     * can start background activities.
     */
    public void setPendingIntentBackgroundActivityLaunchAllowed(boolean allowed) {
        mPendingIntentBalAllowed = allowed;
    }

    /**
     * Get PendingIntent activity is allowed to be started in the background if the caller
     * can start background activities.
     */
    public boolean isPendingIntentBackgroundActivityLaunchAllowed() {
        return mPendingIntentBalAllowed;
    }

    /**
     * Set PendingIntent activity can be launched from background if caller has BAL permission.
     * @hide
     */
    public void setPendingIntentBackgroundActivityLaunchAllowedByPermission(boolean allowed) {
        mPendingIntentBalAllowedByPermission = allowed;
    }

    /**
     * Get PendingIntent activity is allowed to be started in the background if the caller
     * has BAL permission.
     * @hide
     */
    public boolean isPendingIntentBackgroundActivityLaunchAllowedByPermission() {
        return mPendingIntentBalAllowedByPermission;
    }

    public Bundle toBundle() {
        Bundle b = new Bundle();
        b.putBoolean(KEY_PENDING_INTENT_BACKGROUND_ACTIVITY_ALLOWED, mPendingIntentBalAllowed);
        if (mPendingIntentBalAllowedByPermission) {
            b.putBoolean(KEY_PENDING_INTENT_BACKGROUND_ACTIVITY_ALLOWED_BY_PERMISSION,
                    mPendingIntentBalAllowedByPermission);
        }
        return b;
    }
}
