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

package com.android.systemui.accessibility;

import static android.provider.Settings.Secure.ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU;
import static android.provider.Settings.Secure.ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR;

import android.annotation.IntDef;
import android.annotation.MainThread;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.dagger.SysUISingleton;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.inject.Inject;

/**
 * Observes changes of the accessibility button mode
 * {@link Settings.Secure#ACCESSIBILITY_BUTTON_MODE} and notify its listeners.
 */
@MainThread
@SysUISingleton
public class AccessibilityButtonModeObserver {

    private static final int ACCESSIBILITY_BUTTON_MODE_DEFAULT =
            ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR;

    private final ContentResolver mContentResolver;
    @VisibleForTesting
    final ContentObserver mContentObserver;

    private final List<ModeChangedListener> mListeners = new ArrayList<>();

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR,
            ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU
    })
    private @interface AccessibilityButtonMode {}

    /** Listener for accessibility button mode changes. */
    public interface ModeChangedListener {

        /**
         * Called when accessibility button mode changes.
         *
         * @param mode Current accessibility button mode.
         */
        void onAccessibilityButtonModeChanged(@AccessibilityButtonMode int mode);
    }

    @Inject
    public AccessibilityButtonModeObserver(Context context) {
        mContentResolver = context.getContentResolver();
        mContentObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                updateAccessibilityButtonModeChanged();
            }
        };
    }

    /**
     * Registers a listener to receive updates from settings key {@link
     * Settings.Secure#ACCESSIBILITY_BUTTON_MODE}.
     *
     * @param listener A {@link ModeChangedListener} object.
     */
    public void addListener(@NonNull ModeChangedListener listener) {
        Objects.requireNonNull(listener, "listener must be non-null");

        mListeners.add(listener);

        if (mListeners.size() == 1) {
            mContentResolver.registerContentObserver(
                    Settings.Secure.getUriFor(
                            Settings.Secure.ACCESSIBILITY_BUTTON_MODE), /* notifyForDescendants= */
                    false, mContentObserver);
        }
    }

    /**
     * Unregisters a listener previously registered with {@link #addListener(ModeChangedListener)}.
     *
     * @param listener A {@link ModeChangedListener} object.
     */
    public void removeListener(@NonNull ModeChangedListener listener) {
        Objects.requireNonNull(listener, "listener must be non-null");

        mListeners.remove(listener);

        if (mListeners.isEmpty()) {
            mContentResolver.unregisterContentObserver(mContentObserver);
        }
    }

    /**
     * Gets the current accessibility button mode from the current user's settings.
     *
     * See {@link Settings.Secure#ACCESSIBILITY_BUTTON_MODE}.
     */
    @AccessibilityButtonMode
    public int getCurrentAccessibilityButtonMode() {
        return Settings.Secure.getInt(mContentResolver, Settings.Secure.ACCESSIBILITY_BUTTON_MODE,
                ACCESSIBILITY_BUTTON_MODE_DEFAULT);
    }

    private void updateAccessibilityButtonModeChanged() {
        final int mode = getCurrentAccessibilityButtonMode();
        final int listenerSize = mListeners.size();
        for (int i = 0; i < listenerSize; i++) {
            mListeners.get(i).onAccessibilityButtonModeChanged(mode);
        }
    }
}
