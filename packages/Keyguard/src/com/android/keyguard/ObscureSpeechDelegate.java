/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.keyguard;

import android.content.ContentResolver;
import android.content.Context;
import android.media.AudioManager;
import android.provider.Settings;
import android.view.View;
import android.view.View.AccessibilityDelegate;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.android.internal.R;

/**
 * Accessibility delegate that obscures speech for a view when the user has
 * not turned on the "speak passwords" preference and is not listening
 * through headphones.
 */
class ObscureSpeechDelegate extends AccessibilityDelegate {
    /** Whether any client has announced the "headset" notification. */
    static boolean sAnnouncedHeadset = false;

    private final ContentResolver mContentResolver;
    private final AudioManager mAudioManager;

    public ObscureSpeechDelegate(Context context) {
        mContentResolver = context.getContentResolver();
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    @Override
    public void sendAccessibilityEvent(View host, int eventType) {
        super.sendAccessibilityEvent(host, eventType);

        // Play the "headset required" announcement the first time the user
        // places accessibility focus on a key.
        if ((eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
                && !sAnnouncedHeadset && shouldObscureSpeech()) {
            sAnnouncedHeadset = true;
            host.announceForAccessibility(host.getContext().getString(
                    R.string.keyboard_headset_required_to_hear_password));
        }
    }

    @Override
    public void onPopulateAccessibilityEvent(View host, AccessibilityEvent event) {
        super.onPopulateAccessibilityEvent(host, event);

        if ((event.getEventType() != AccessibilityEvent.TYPE_ANNOUNCEMENT)
                && shouldObscureSpeech()) {
            event.getText().clear();
            event.setContentDescription(host.getContext().getString(
                    R.string.keyboard_password_character_no_headset));
        }
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(host, info);

        if (shouldObscureSpeech()) {
            final Context ctx = host.getContext();
            info.setText(null);
            info.setContentDescription(
                    ctx.getString(R.string.keyboard_password_character_no_headset));
        }
    }

    @SuppressWarnings("deprecation")
    private boolean shouldObscureSpeech() {
        // The user can optionally force speaking passwords.
        if (Settings.Secure.getInt(mContentResolver,
                Settings.Secure.ACCESSIBILITY_SPEAK_PASSWORD, 0) != 0) {
            return false;
        }

        // Always speak if the user is listening through headphones.
        if (mAudioManager.isWiredHeadsetOn() || mAudioManager.isBluetoothA2dpOn()) {
            return false;
        }

        // Don't speak since this key is used to type a password.
        return true;
    }
}