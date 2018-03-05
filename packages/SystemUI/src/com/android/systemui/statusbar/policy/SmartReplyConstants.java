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

package com.android.systemui.statusbar.policy;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;
import android.util.KeyValueListParser;
import android.util.Log;

import com.android.systemui.R;

public final class SmartReplyConstants extends ContentObserver {

    private static final String TAG = "SmartReplyConstants";

    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_REQUIRES_TARGETING_P = "requires_targeting_p";
    private static final String KEY_MAX_SQUEEZE_REMEASURE_ATTEMPTS =
            "max_squeeze_remeasure_attempts";

    private final boolean mDefaultEnabled;
    private final boolean mDefaultRequiresP;
    private final int mDefaultMaxSqueezeRemeasureAttempts;

    private boolean mEnabled;
    private boolean mRequiresTargetingP;
    private int mMaxSqueezeRemeasureAttempts;

    private final Context mContext;
    private final KeyValueListParser mParser = new KeyValueListParser(',');

    public SmartReplyConstants(Handler handler, Context context) {
        super(handler);

        mContext = context;
        final Resources resources = mContext.getResources();
        mDefaultEnabled = resources.getBoolean(
                R.bool.config_smart_replies_in_notifications_enabled);
        mDefaultRequiresP = resources.getBoolean(
                R.bool.config_smart_replies_in_notifications_requires_targeting_p);
        mDefaultMaxSqueezeRemeasureAttempts = resources.getInteger(
                R.integer.config_smart_replies_in_notifications_max_squeeze_remeasure_attempts);

        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.SMART_REPLIES_IN_NOTIFICATIONS_FLAGS),
                false, this);
        updateConstants();
    }

    @Override
    public void onChange(boolean selfChange, Uri uri) {
        updateConstants();
    }

    private void updateConstants() {
        synchronized (SmartReplyConstants.this) {
            try {
                mParser.setString(Settings.Global.getString(mContext.getContentResolver(),
                        Settings.Global.SMART_REPLIES_IN_NOTIFICATIONS_FLAGS));
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Bad smart reply constants", e);
            }
            mEnabled = mParser.getBoolean(KEY_ENABLED, mDefaultEnabled);
            mRequiresTargetingP = mParser.getBoolean(KEY_REQUIRES_TARGETING_P, mDefaultRequiresP);
            mMaxSqueezeRemeasureAttempts = mParser.getInt(
                    KEY_MAX_SQUEEZE_REMEASURE_ATTEMPTS, mDefaultMaxSqueezeRemeasureAttempts);
        }
    }

    /** Returns whether smart replies in notifications are enabled. */
    public boolean isEnabled() {
        return mEnabled;
    }

    /**
     * Returns whether smart replies in notifications should be disabled when the app targets a
     * version of Android older than P.
     */
    public boolean requiresTargetingP() {
        return mRequiresTargetingP;
    }

    /**
     * Returns the maximum number of times {@link SmartReplyView#onMeasure(int, int)} will try to
     * find a better (narrower) line-break for a double-line smart reply button.
     */
    public int getMaxSqueezeRemeasureAttempts() {
        return mMaxSqueezeRemeasureAttempts;
    }
}
