/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.internal.policy;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;

import java.util.Collection;

/**
 * A ContentObserver for listening {@link Settings.Secure#NAV_BAR_FORCE_VISIBLE} setting key.
 *
 * @hide
 */
public class ForceShowNavBarSettingsObserver extends ContentObserver {
    private Context mContext;
    private Runnable mOnChangeRunnable;

    public ForceShowNavBarSettingsObserver(Handler handler, Context context) {
        super(handler);
        mContext = context;
    }

    public void setOnChangeRunnable(Runnable r) {
        mOnChangeRunnable = r;
    }

    /**
     * Registers the observer.
     */
    public void register() {
        final ContentResolver r = mContext.getContentResolver();
        r.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.NAV_BAR_FORCE_VISIBLE),
                false, this, UserHandle.USER_ALL);
    }

    /**
     * Unregisters the observer.
     */
    public void unregister() {
        mContext.getContentResolver().unregisterContentObserver(this);
    }

    @Override
    public void onChange(boolean selfChange, @NonNull Collection<Uri> uris, int flags, int userId) {
        if (userId != ActivityManager.getCurrentUser()) {
            return;
        }

        if (mOnChangeRunnable != null) {
            mOnChangeRunnable.run();
        }
    }

    /**
     * Returns true only when it's in orce show navigation bar mode. Otherwise, return false.
     */
    public boolean isEnabled() {
        return Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.NAV_BAR_FORCE_VISIBLE, 0, UserHandle.USER_CURRENT) == 1;
    }
}
