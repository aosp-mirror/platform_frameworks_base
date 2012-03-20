/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.content.Context;
import android.os.Bundle;

/**
 * Helper class for building an options Bundle that can be used with
 * {@link android.content.Context#startActivity(android.content.Intent, android.os.Bundle)
 * Context.startActivity(Intent, Bundle)} and related methods.
 */
public class ActivityOptions {
    /**
     * The package name that created the options.
     * @hide
     */
    public static final String KEY_PACKAGE_NAME = "android:packageName";

    /**
     * Custom enter animation resource ID.
     * @hide
     */
    public static final String KEY_ANIM_ENTER_RES_ID = "android:animEnterRes";

    /**
     * Custom exit animation resource ID.
     * @hide
     */
    public static final String KEY_ANIM_EXIT_RES_ID = "android:animExitRes";

    private String mPackageName;
    private boolean mIsCustomAnimation;
    private int mCustomEnterResId;
    private int mCustomExitResId;

    /**
     * Create an ActivityOptions specifying a custom animation to run when
     * the activity is displayed.
     *
     * @param context Who is defining this.  This is the application that the
     * animation resources will be loaded from.
     * @param enterResId A resource ID of the animation resource to use for
     * the incoming activity.  Use 0 for no animation.
     * @param exitResId A resource ID of the animation resource to use for
     * the outgoing activity.  Use 0 for no animation.
     * @return Returns a new ActivityOptions object that you can use to
     * supply these options as the options Bundle when starting an activity.
     */
    public static ActivityOptions makeCustomAnimation(Context context,
            int enterResId, int exitResId) {
        ActivityOptions opts = new ActivityOptions();
        opts.mPackageName = context.getPackageName();
        opts.mIsCustomAnimation = true;
        opts.mCustomEnterResId = enterResId;
        opts.mCustomExitResId = exitResId;
        return opts;
    }

    private ActivityOptions() {
    }

    /** @hide */
    public ActivityOptions(Bundle opts) {
        mPackageName = opts.getString(KEY_PACKAGE_NAME);
        if (opts.containsKey(KEY_ANIM_ENTER_RES_ID)) {
            mIsCustomAnimation = true;
            mCustomEnterResId = opts.getInt(KEY_ANIM_ENTER_RES_ID, 0);
            mCustomExitResId = opts.getInt(KEY_ANIM_EXIT_RES_ID, 0);
        }
    }

    /** @hide */
    public String getPackageName() {
        return mPackageName;
    }

    /** @hide */
    public boolean isCustomAnimation() {
        return mIsCustomAnimation;
    }

    /** @hide */
    public int getCustomEnterResId() {
        return mCustomEnterResId;
    }

    /** @hide */
    public int getCustomExitResId() {
        return mCustomExitResId;
    }

    /**
     * Join the values in <var>otherOptions</var> in to this one.  Any values
     * defined in <var>otherOptions</var> replace those in the base options.
     */
    public void join(ActivityOptions otherOptions) {
        if (otherOptions.mPackageName != null) {
            mPackageName = otherOptions.mPackageName;
        }
        if (otherOptions.mIsCustomAnimation) {
            mIsCustomAnimation = true;
            mCustomEnterResId = otherOptions.mCustomEnterResId;
            mCustomExitResId = otherOptions.mCustomExitResId;
        }
    }

    /**
     * Returns the created options as a Bundle, which can be passed to
     * {@link android.content.Context#startActivity(android.content.Intent, android.os.Bundle)
     * Context.startActivity(Intent, Bundle)} and related methods.
     * Note that the returned Bundle is still owned by the ActivityOptions
     * object; you must not modify it, but can supply it to the startActivity
     * methods that take an options Bundle.
     */
    public Bundle toBundle() {
        Bundle b = new Bundle();
        if (mPackageName != null) {
            b.putString(KEY_PACKAGE_NAME, mPackageName);
        }
        if (mIsCustomAnimation) {
            b.putInt(KEY_ANIM_ENTER_RES_ID, mCustomEnterResId);
            b.putInt(KEY_ANIM_EXIT_RES_ID, mCustomExitResId);
        }
        return b;
    }
}
