/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.systemui.statusbar;

import static com.android.systemui.Flags.keyboardShortcutHelperRewrite;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.shared.recents.utilities.Utilities;

import javax.inject.Inject;

/** Receiver for the Keyboard Shortcuts Helper. */
public class KeyboardShortcutsReceiver extends BroadcastReceiver {

    private final FeatureFlags mFeatureFlags;

    @Inject
    public KeyboardShortcutsReceiver(FeatureFlags featureFlags) {
        mFeatureFlags = featureFlags;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (keyboardShortcutHelperRewrite()) {
            return;
        }
        if (isTabletLayoutFlagEnabled() && Utilities.isLargeScreen(context)) {
            if (Intent.ACTION_SHOW_KEYBOARD_SHORTCUTS.equals(intent.getAction())) {
                KeyboardShortcutListSearch.show(context, -1 /* deviceId unknown */);
            } else if (Intent.ACTION_DISMISS_KEYBOARD_SHORTCUTS.equals(intent.getAction())) {
                KeyboardShortcutListSearch.dismiss();
            }
        } else {
            if (Intent.ACTION_SHOW_KEYBOARD_SHORTCUTS.equals(intent.getAction())) {
                KeyboardShortcuts.show(context, -1 /* deviceId unknown */);
            } else if (Intent.ACTION_DISMISS_KEYBOARD_SHORTCUTS.equals(intent.getAction())) {
                KeyboardShortcuts.dismiss();
            }
        }
    }

    private boolean isTabletLayoutFlagEnabled() {
        return mFeatureFlags.isEnabled(Flags.SHORTCUT_LIST_SEARCH_LAYOUT);
    }
}
