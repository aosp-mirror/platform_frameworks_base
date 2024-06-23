/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.internal.accessibility.dialog;

import static android.view.WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL;

import static com.android.internal.accessibility.AccessibilityShortcutController.MAGNIFICATION_COMPONENT_NAME;
import static com.android.internal.accessibility.AccessibilityShortcutController.MAGNIFICATION_CONTROLLER_NAME;
import static com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType.SOFTWARE;
import static com.android.internal.accessibility.dialog.AccessibilityTargetHelper.getTargets;
import static com.android.internal.accessibility.util.AccessibilityStatsLogUtils.logAccessibilityButtonLongPressStatus;

import android.annotation.Nullable;
import android.app.Activity;
import android.content.ComponentName;
import android.os.Bundle;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import android.widget.GridView;
import android.widget.TextView;

import com.android.internal.R;
import com.android.internal.widget.ResolverDrawerLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Activity used to display and persist a service or feature target for the Accessibility button.
 */
public class AccessibilityButtonChooserActivity extends Activity {
    private final List<AccessibilityTarget> mTargets = new ArrayList<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.accessibility_button_chooser);

        final ResolverDrawerLayout rdl = findViewById(R.id.contentPanel);
        if (rdl != null) {
            rdl.setOnDismissedListener(this::finish);
        }

        final String component = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ACCESSIBILITY_BUTTON_TARGET_COMPONENT);

        final AccessibilityManager accessibilityManager =
                getSystemService(AccessibilityManager.class);
        final boolean isTouchExploreOn =
                accessibilityManager.isTouchExplorationEnabled();
        final boolean isGestureNavigateEnabled =
                NAV_BAR_MODE_GESTURAL == getResources().getInteger(
                        com.android.internal.R.integer.config_navBarInteractionMode);

        if (isGestureNavigateEnabled) {
            final TextView promptPrologue = findViewById(R.id.accessibility_button_prompt_prologue);
            promptPrologue.setText(isTouchExploreOn
                    ? R.string.accessibility_gesture_3finger_prompt_text
                    : R.string.accessibility_gesture_prompt_text);

            final TextView prompt = findViewById(R.id.accessibility_button_prompt);
            prompt.setText(isTouchExploreOn
                    ? R.string.accessibility_gesture_3finger_instructional_text
                    : R.string.accessibility_gesture_instructional_text);
        }

        mTargets.addAll(getTargets(this, SOFTWARE));

        final GridView gridview = findViewById(R.id.accessibility_button_chooser_grid);
        gridview.setAdapter(new ButtonTargetAdapter(mTargets));
        gridview.setOnItemClickListener((parent, view, position, id) -> {
            final String key = Settings.Secure.ACCESSIBILITY_BUTTON_TARGET_COMPONENT;
            String name = mTargets.get(position).getId();
            if (name.equals(MAGNIFICATION_CONTROLLER_NAME)) {
                name = MAGNIFICATION_COMPONENT_NAME.flattenToString();
            }
            final ComponentName componentName = ComponentName.unflattenFromString(name);
            logAccessibilityButtonLongPressStatus(componentName);
            Settings.Secure.putString(getContentResolver(), key, mTargets.get(position).getId());
            finish();
        });
    }
}
