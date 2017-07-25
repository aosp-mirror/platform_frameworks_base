/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.keyguard;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

/**
 * Replaces fancy colons with regular colons. Only works on TextViews.
 */
class KeyguardClockAccessibilityDelegate extends View.AccessibilityDelegate {
    private final String mFancyColon;

    public KeyguardClockAccessibilityDelegate(Context context) {
        mFancyColon = context.getString(R.string.keyguard_fancy_colon);
    }

    @Override
    public void onInitializeAccessibilityEvent(View host, AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(host, event);
        if (TextUtils.isEmpty(mFancyColon)) {
            return;
        }
        CharSequence text = event.getContentDescription();
        if (!TextUtils.isEmpty(text)) {
            event.setContentDescription(replaceFancyColon(text));
        }
    }

    @Override
    public void onPopulateAccessibilityEvent(View host, AccessibilityEvent event) {
        if (TextUtils.isEmpty(mFancyColon)) {
            super.onPopulateAccessibilityEvent(host, event);
        } else {
            CharSequence text = ((TextView) host).getText();
            if (!TextUtils.isEmpty(text)) {
                event.getText().add(replaceFancyColon(text));
            }
        }
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(host, info);
        if (TextUtils.isEmpty(mFancyColon)) {
            return;
        }
        if (!TextUtils.isEmpty(info.getText())) {
            info.setText(replaceFancyColon(info.getText()));
        }
        if (!TextUtils.isEmpty(info.getContentDescription())) {
            info.setContentDescription(replaceFancyColon(info.getContentDescription()));
        }
    }

    private CharSequence replaceFancyColon(CharSequence text) {
        if (TextUtils.isEmpty(mFancyColon)) {
            return text;
        }
        return text.toString().replace(mFancyColon, ":");
    }

    public static boolean isNeeded(Context context) {
        return !TextUtils.isEmpty(context.getString(R.string.keyguard_fancy_colon));
    }
}
