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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.TestApi;
import android.os.Bundle;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

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

    private @Nullable Boolean mPendingIntentBalAllowed = null;
    private boolean mPendingIntentBalAllowedByPermission = false;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"MODE_BACKGROUND_ACTIVITY_START_"}, value = {
            MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED,
            MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
            MODE_BACKGROUND_ACTIVITY_START_DENIED})
    public @interface BackgroundActivityStartMode {}
    /**
     * No explicit value chosen. The system will decide whether to grant privileges.
     * @hide
     */
    @TestApi
    public static final int MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED = 0;
    /**
     * Allow the {@link PendingIntent} to use the background activity start privileges.
     * @hide
     */
    @TestApi
    public static final int MODE_BACKGROUND_ACTIVITY_START_ALLOWED = 1;
    /**
     * Deny the {@link PendingIntent} to use the background activity start privileges.
     * @hide
     */
    @TestApi
    public static final int MODE_BACKGROUND_ACTIVITY_START_DENIED = 2;

    ComponentOptions() {
    }

    ComponentOptions(Bundle opts) {
        // If the remote side sent us bad parcelables, they won't get the
        // results they want, which is their loss.
        opts.setDefusable(true);

        boolean pendingIntentBalAllowedIsSetExplicitly =
                opts.containsKey(KEY_PENDING_INTENT_BACKGROUND_ACTIVITY_ALLOWED);
        if (pendingIntentBalAllowedIsSetExplicitly) {
            mPendingIntentBalAllowed =
                    opts.getBoolean(KEY_PENDING_INTENT_BACKGROUND_ACTIVITY_ALLOWED);
        }
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
        mPendingIntentBalAllowed = allowed;
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
        if (mPendingIntentBalAllowed == null) {
            // cannot return null, so return the value used up to API level 33 for compatibility
            return true;
        }
        return mPendingIntentBalAllowed;
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
                mPendingIntentBalAllowed = null;
                break;
            case MODE_BACKGROUND_ACTIVITY_START_ALLOWED:
                mPendingIntentBalAllowed = true;
                break;
            case MODE_BACKGROUND_ACTIVITY_START_DENIED:
                mPendingIntentBalAllowed = false;
                break;
            default:
                throw new IllegalArgumentException(state + " is not valid");
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
        if (mPendingIntentBalAllowed == null) {
            return MODE_BACKGROUND_ACTIVITY_START_SYSTEM_DEFINED;
        } else if (mPendingIntentBalAllowed) {
            return MODE_BACKGROUND_ACTIVITY_START_ALLOWED;
        } else {
            return MODE_BACKGROUND_ACTIVITY_START_DENIED;
        }
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
        if (mPendingIntentBalAllowed != null) {
            b.putBoolean(KEY_PENDING_INTENT_BACKGROUND_ACTIVITY_ALLOWED, mPendingIntentBalAllowed);
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
