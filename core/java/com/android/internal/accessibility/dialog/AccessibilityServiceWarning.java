/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.BidiFormatter;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;

import java.util.Locale;

/**
 * Utility class for creating the dialog that asks the user for explicit permission
 * before an accessibility service is enabled.
 */
public class AccessibilityServiceWarning {

    /**
     * Returns an {@link AlertDialog} to be shown to confirm that the user
     * wants to enable an {@link android.accessibilityservice.AccessibilityService}.
     */
    public static AlertDialog createAccessibilityServiceWarningDialog(@NonNull Context context,
            @NonNull AccessibilityServiceInfo info,
            @NonNull View.OnClickListener allowListener,
            @NonNull View.OnClickListener denyListener,
            @NonNull View.OnClickListener uninstallListener) {
        final AlertDialog ad = new AlertDialog.Builder(context)
                .setView(createAccessibilityServiceWarningDialogContentView(
                                context, info, allowListener, denyListener, uninstallListener))
                .setCancelable(true)
                .create();
        Window window = ad.getWindow();
        WindowManager.LayoutParams params = window.getAttributes();
        params.privateFlags |= SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;
        params.type = WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG;
        window.setAttributes(params);
        return ad;
    }

    @VisibleForTesting
    public static View createAccessibilityServiceWarningDialogContentView(Context context,
            AccessibilityServiceInfo info,
            View.OnClickListener allowListener,
            View.OnClickListener denyListener,
            View.OnClickListener uninstallListener) {
        final LayoutInflater inflater = context.getSystemService(LayoutInflater.class);
        final View content = inflater.inflate(R.layout.accessibility_service_warning, null);

        final Drawable icon;
        if (info.getResolveInfo().getIconResource() == 0) {
            icon = context.getDrawable(R.drawable.ic_accessibility_generic);
        } else {
            icon = info.getResolveInfo().loadIcon(context.getPackageManager());
        }
        final ImageView permissionDialogIcon = content.findViewById(
                R.id.accessibility_permissionDialog_icon);
        permissionDialogIcon.setImageDrawable(icon);

        final TextView permissionDialogTitle = content.findViewById(
                R.id.accessibility_permissionDialog_title);
        permissionDialogTitle.setText(context.getString(R.string.accessibility_enable_service_title,
                getServiceName(context, info)));

        final Button permissionAllowButton = content.findViewById(
                R.id.accessibility_permission_enable_allow_button);
        final Button permissionDenyButton = content.findViewById(
                R.id.accessibility_permission_enable_deny_button);
        permissionAllowButton.setOnClickListener(allowListener);
        permissionAllowButton.setOnTouchListener(getTouchConsumingListener());
        permissionDenyButton.setOnClickListener(denyListener);

        final Button uninstallButton = content.findViewById(
                R.id.accessibility_permission_enable_uninstall_button);
        // Show an uninstall button to help users quickly remove non-preinstalled apps.
        if (!info.getResolveInfo().serviceInfo.applicationInfo.isSystemApp()) {
            uninstallButton.setVisibility(View.VISIBLE);
            uninstallButton.setOnClickListener(uninstallListener);
        }
        return content;
    }

    @VisibleForTesting
    @SuppressLint("ClickableViewAccessibility") // Touches are intentionally consumed
    public static View.OnTouchListener getTouchConsumingListener() {
        return (view, event) -> {
            // Filter obscured touches by consuming them.
            if (((event.getFlags() & MotionEvent.FLAG_WINDOW_IS_OBSCURED) != 0)
                    || ((event.getFlags() & MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0)) {
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    Toast.makeText(view.getContext(),
                            R.string.accessibility_dialog_touch_filtered_warning,
                            Toast.LENGTH_SHORT).show();
                }
                return true;
            }
            return false;
        };
    }

    // Get the service name and bidi wrap it to protect from bidi side effects.
    private static CharSequence getServiceName(Context context, AccessibilityServiceInfo info) {
        final Locale locale = context.getResources().getConfiguration().getLocales().get(0);
        final CharSequence label =
                info.getResolveInfo().loadLabel(context.getPackageManager());
        return BidiFormatter.getInstance(locale).unicodeWrap(label);
    }
}
