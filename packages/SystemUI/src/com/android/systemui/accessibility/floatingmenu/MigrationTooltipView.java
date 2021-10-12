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

package com.android.systemui.accessibility.floatingmenu;

import static com.android.internal.accessibility.AccessibilityShortcutController.ACCESSIBILITY_BUTTON_COMPONENT_NAME;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.text.method.LinkMovementMethod;

import com.android.systemui.R;

/**
 * Migration tooltip view that shows the information about the Accessibility button was replaced
 * with the floating menu.
 */
class MigrationTooltipView extends BaseTooltipView {
    MigrationTooltipView(Context context, AccessibilityFloatingMenuView anchorView) {
        super(context, anchorView);

        final Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(Intent.EXTRA_COMPONENT_NAME,
                ACCESSIBILITY_BUTTON_COMPONENT_NAME.flattenToShortString());

        final AnnotationLinkSpan.LinkInfo linkInfo = new AnnotationLinkSpan.LinkInfo(
                AnnotationLinkSpan.LinkInfo.DEFAULT_ANNOTATION,
                v -> {
                    getContext().startActivity(intent);
                    hide();
                });

        final int textResId = R.string.accessibility_floating_button_migration_tooltip;
        setDescription(AnnotationLinkSpan.linkify(getContext().getText(textResId), linkInfo));
        setMovementMethod(LinkMovementMethod.getInstance());
    }
}
