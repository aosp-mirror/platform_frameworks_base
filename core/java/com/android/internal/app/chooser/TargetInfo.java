/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.internal.app.chooser;


import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.UserHandle;

import com.android.internal.app.ResolverActivity;

import java.util.List;

/**
 * A single target as represented in the chooser.
 */
public interface TargetInfo {
    /**
     * Get the resolved intent that represents this target. Note that this may not be the
     * intent that will be launched by calling one of the <code>start</code> methods provided;
     * this is the intent that will be credited with the launch.
     *
     * @return the resolved intent for this target
     */
    Intent getResolvedIntent();

    /**
     * Get the resolved component name that represents this target. Note that this may not
     * be the component that will be directly launched by calling one of the <code>start</code>
     * methods provided; this is the component that will be credited with the launch.
     *
     * @return the resolved ComponentName for this target
     */
    ComponentName getResolvedComponentName();

    /**
     * Start the activity referenced by this target.
     *
     * @param activity calling Activity performing the launch
     * @param options ActivityOptions bundle
     * @return true if the start completed successfully
     */
    boolean start(Activity activity, Bundle options);

    /**
     * Start the activity referenced by this target as if the ResolverActivity's caller
     * was performing the start operation.
     *
     * @param activity calling Activity (actually) performing the launch
     * @param options ActivityOptions bundle
     * @param userId userId to start as or {@link UserHandle#USER_NULL} for activity's caller
     * @return true if the start completed successfully
     */
    boolean startAsCaller(ResolverActivity activity, Bundle options, int userId);

    /**
     * Start the activity referenced by this target as a given user.
     *
     * @param activity calling activity performing the launch
     * @param options ActivityOptions bundle
     * @param user handle for the user to start the activity as
     * @return true if the start completed successfully
     */
    boolean startAsUser(Activity activity, Bundle options, UserHandle user);

    /**
     * Return the ResolveInfo about how and why this target matched the original query
     * for available targets.
     *
     * @return ResolveInfo representing this target's match
     */
    ResolveInfo getResolveInfo();

    /**
     * Return the human-readable text label for this target.
     *
     * @return user-visible target label
     */
    CharSequence getDisplayLabel();

    /**
     * Return any extended info for this target. This may be used to disambiguate
     * otherwise identical targets.
     *
     * @return human-readable disambig string or null if none present
     */
    CharSequence getExtendedInfo();

    /**
     * @return The drawable that should be used to represent this target including badge
     * @param context
     */
    Drawable getDisplayIcon(Context context);

    /**
     * Clone this target with the given fill-in information.
     */
    TargetInfo cloneFilledIn(Intent fillInIntent, int flags);

    /**
     * @return the list of supported source intents deduped against this single target
     */
    List<Intent> getAllSourceIntents();

    /**
     * @return true if this target cannot be selected by the user
     */
    boolean isSuspended();

    /**
     * @return true if this target should be pinned to the front by the request of the user
     */
    boolean isPinned();

    /**
     * Fix the URIs in {@code intent} if cross-profile sharing is required. This should be called
     * before launching the intent as another user.
     */
    static void prepareIntentForCrossProfileLaunch(Intent intent, int targetUserId) {
        final int currentUserId = UserHandle.myUserId();
        if (targetUserId != currentUserId) {
            intent.fixUris(currentUserId);
        }
    }
}
