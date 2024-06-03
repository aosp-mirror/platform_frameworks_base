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

import static android.app.ActivityOptions.BackgroundActivityStartMode;
import static android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED;
import static android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_COMPAT;
import static android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_DENIED;
import static android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.TestApi;
import android.os.Bundle;

/**
 * Base class for {@link ActivityOptions} and {@link BroadcastOptions}.
 * @hide
 */
// Expose the methods and constants required to test the SystemApis in subclasses.
@TestApi
// Suppressed since lint is recommending class have a suffix of Params.
@SuppressLint("UserHandleName")
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class ComponentOptions {

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

    private Integer mPendingIntentBalAllowed = MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED;
    private boolean mPendingIntentBalAllowedByPermission = false;

    ComponentOptions() {
    }

    ComponentOptions(Bundle opts) {
        // If the remote side sent us bad parcelables, they won't get the
        // results they want, which is their loss.
        opts.setDefusable(true);

        mPendingIntentBalAllowed =
                opts.getInt(KEY_PENDING_INTENT_BACKGROUND_ACTIVITY_ALLOWED,
                        MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED);
        setPendingIntentBackgroundActivityLaunchAllowedByPermission(
                opts.getBoolean(
                        KEY_PENDING_INTENT_BACKGROUND_ACTIVITY_ALLOWED_BY_PERMISSION, false));
    }

    /**
     * Set PendingIntent activity is allowed to be started in the background if the caller
     * can start background activities.
     *
     * @deprecated use #setPendingIntentBackgroundActivityStartMode(int) to set the full range
     * of states
     * @hide
     */
    @Deprecated public void setPendingIntentBackgroundActivityLaunchAllowed(boolean allowed) {
        mPendingIntentBalAllowed = allowed ? MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                : MODE_BACKGROUND_ACTIVITY_START_DENIED;
    }

    /**
     * Get PendingIntent activity is allowed to be started in the background if the caller can start
     * background activities.
     *
     * @deprecated use {@link #getPendingIntentBackgroundActivityStartMode()} since for apps
     * targeting {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE} or higher this value might
     * not match the actual behavior if the value was not explicitly set.
     * @hide
     */
    @Deprecated public boolean isPendingIntentBackgroundActivityLaunchAllowed() {
        // cannot return all detail, so return the value used up to API level 33 for compatibility
        return mPendingIntentBalAllowed != MODE_BACKGROUND_ACTIVITY_START_DENIED;
    }

    /**
     * Sets the mode for allowing or denying the senders privileges to start background activities
     * to the PendingIntent.
     *
     * This is typically used in when executing {@link PendingIntent#send(Bundle)} or similar
     * methods. A privileged sender of a PendingIntent should only grant
     * {@link #MODE_BACKGROUND_ACTIVITY_START_ALLOWED} if the PendingIntent is from a trusted source
     * and/or executed on behalf the user.
     * @hide
     */
    public @NonNull ComponentOptions setPendingIntentBackgroundActivityStartMode(
            @BackgroundActivityStartMode int state) {
        switch (state) {
            case MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED:
            case MODE_BACKGROUND_ACTIVITY_START_DENIED:
            case MODE_BACKGROUND_ACTIVITY_START_COMPAT:
            case MODE_BACKGROUND_ACTIVITY_START_ALLOWED:
                mPendingIntentBalAllowed = state;
                break;
            default:
                // Assume that future values are some variant of allowing the start.
                mPendingIntentBalAllowed = MODE_BACKGROUND_ACTIVITY_START_ALLOWED;
                break;
        }
        return this;
    }

    /**
     * Gets the mode for allowing or denying the senders privileges to start background activities
     * to the PendingIntent.
     * @hide
     *
     * @see #setPendingIntentBackgroundActivityStartMode(int)
     */
    public @BackgroundActivityStartMode int getPendingIntentBackgroundActivityStartMode() {
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

    /** @hide */
    public Bundle toBundle() {
        Bundle b = new Bundle();
        if (mPendingIntentBalAllowed != MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED) {
            b.putInt(KEY_PENDING_INTENT_BACKGROUND_ACTIVITY_ALLOWED, mPendingIntentBalAllowed);
        }
        if (mPendingIntentBalAllowedByPermission) {
            b.putBoolean(KEY_PENDING_INTENT_BACKGROUND_ACTIVITY_ALLOWED_BY_PERMISSION,
                    mPendingIntentBalAllowedByPermission);
        }
        return b;
    }

    /** @hide */
    public static @Nullable ComponentOptions fromBundle(@Nullable Bundle options) {
        return (options != null) ? new ComponentOptions(options) : null;
    }
}
