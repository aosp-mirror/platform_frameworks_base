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

package com.android.systemui.qs;

import android.content.Context;
import android.database.ContentObserver;
import android.hardware.display.ColorDisplayManager;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.provider.Settings;

import androidx.annotation.NonNull;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.policy.CallbackController;
import com.android.systemui.util.settings.SecureSettings;

import java.util.ArrayList;

import javax.inject.Inject;

/**
 * @hide
 */
@SysUISingleton
public class ReduceBrightColorsController implements
        CallbackController<ReduceBrightColorsController.Listener> {
    private final ColorDisplayManager mManager;
    private final UserTracker mUserTracker;
    private UserTracker.Callback mCurrentUserTrackerCallback;
    private final Handler mHandler;
    private final ContentObserver mContentObserver;
    private final SecureSettings mSecureSettings;
    private final ArrayList<ReduceBrightColorsController.Listener> mListeners = new ArrayList<>();

    @Inject
    public ReduceBrightColorsController(UserTracker userTracker,
            @Background Handler handler,
            ColorDisplayManager colorDisplayManager,
            SecureSettings secureSettings) {
        mManager = colorDisplayManager;
        mUserTracker = userTracker;
        mHandler = handler;
        mSecureSettings = secureSettings;
        mContentObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                super.onChange(selfChange, uri);
                final String setting = uri == null ? null : uri.getLastPathSegment();
                synchronized (mListeners) {
                    if (setting != null && mListeners.size() != 0) {
                        if (setting.equals(Settings.Secure.REDUCE_BRIGHT_COLORS_ACTIVATED)) {
                            for (Listener listener : mListeners) {
                                listener.onActivated(mManager.isReduceBrightColorsActivated());
                            }
                        }
                    }
                }
            }
        };

        mCurrentUserTrackerCallback = new UserTracker.Callback() {
            @Override
            public void onUserChanged(int newUser, Context userContext) {
                synchronized (mListeners) {
                    if (mListeners.size() > 0) {
                        mSecureSettings.unregisterContentObserver(mContentObserver);
                        mSecureSettings.registerContentObserverForUser(
                                Settings.Secure.getUriFor(
                                        Settings.Secure.REDUCE_BRIGHT_COLORS_ACTIVATED),
                                false, mContentObserver, newUser);
                    }
                }
            }
        };
        mUserTracker.addCallback(mCurrentUserTrackerCallback, new HandlerExecutor(handler));
    }

    @Override
    public void addCallback(@NonNull Listener listener) {
        synchronized (mListeners) {
            if (!mListeners.contains(listener)) {
                mListeners.add(listener);
                if (mListeners.size() == 1) {
                    mSecureSettings.registerContentObserverForUser(
                            Settings.Secure.getUriFor(
                                    Settings.Secure.REDUCE_BRIGHT_COLORS_ACTIVATED),
                            false, mContentObserver, mUserTracker.getUserId());
                }
            }
        }
    }

    @Override
    public void removeCallback(@androidx.annotation.NonNull Listener listener) {
        synchronized (mListeners) {
            if (mListeners.remove(listener) && mListeners.size() == 0) {
                mSecureSettings.unregisterContentObserver(mContentObserver);
            }
        }
    }

    /** Returns {@code true} if Reduce Bright Colors is activated */
    public boolean isReduceBrightColorsActivated() {
        return mManager.isReduceBrightColorsActivated();
    }

    /** Sets the activation state of Reduce Bright Colors */
    public void setReduceBrightColorsActivated(boolean activated) {
        mManager.setReduceBrightColorsActivated(activated);
    }

    /**
     * Listener invoked whenever the Reduce Bright Colors settings are changed.
     */
    public interface Listener {
        /**
         * Listener invoked when the activated state changes.
         *
         * @param activated {@code true} if Reduce Bright Colors is activated.
         */
        default void onActivated(boolean activated) {
        }
    }
}
