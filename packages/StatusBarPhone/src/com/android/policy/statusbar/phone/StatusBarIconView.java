/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.policy.statusbar.phone;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.graphics.Canvas;
import android.util.Slog;
import android.view.ViewDebug;
import android.widget.FrameLayout;

import com.android.internal.statusbar.StatusBarIcon;

public class StatusBarIconView extends AnimatedImageView {
    private static final String TAG = "StatusBarIconView";

    private StatusBarIcon mIcon;
    @ViewDebug.ExportedProperty private String mSlot;

    public StatusBarIconView(Context context, String slot) {
        super(context);
        mSlot = slot;
    }

    private static boolean streq(String a, String b) {
        if (a == null && b != null) {
            return false;
        }
        if (a != null && b == null) {
            return false;
        }
        return a.equals(b);
    }

    public void set(StatusBarIcon icon) {
        final boolean iconEquals = mIcon != null
                && streq(mIcon.iconPackage, icon.iconPackage)
                && mIcon.iconId == icon.iconId;
        final boolean levelEquals = iconEquals
                && mIcon.iconLevel == icon.iconLevel;
        final boolean visibilityEquals = mIcon != null
                && mIcon.visible == icon.visible;
        if (!iconEquals) {
            setImageDrawable(getIcon(icon));
        }
        if (!levelEquals) {
            setImageLevel(icon.iconLevel);
        }
        if (!visibilityEquals) {
            setVisibility(icon.visible ? VISIBLE : GONE);
        }
        mIcon = icon.clone();
    }

    /**
     * Returns the right icon to use for this item, respecting the iconId and
     * iconPackage (if set)
     * 
     * @param context Context to use to get resources if iconPackage is not set
     * @return Drawable for this item, or null if the package or item could not
     *         be found
     */
    private Drawable getIcon(StatusBarIcon icon) {
        Context context = getContext();
        Resources r = null;

        if (icon.iconPackage != null) {
            try {
                r = context.getPackageManager().getResourcesForApplication(icon.iconPackage);
            } catch (PackageManager.NameNotFoundException ex) {
                Slog.e(PhoneStatusBarService.TAG, "Icon package not found: "+icon.iconPackage, ex);
                return null;
            }
        } else {
            r = context.getResources();
        }

        if (icon.iconId == 0) {
            Slog.w(PhoneStatusBarService.TAG, "No icon ID for slot " + mSlot);
            return null;
        }
        
        try {
            return r.getDrawable(icon.iconId);
        } catch (RuntimeException e) {
            Slog.w(PhoneStatusBarService.TAG, "Icon not found in "
                  + (icon.iconPackage != null ? icon.iconId : "<system>")
                  + ": " + Integer.toHexString(icon.iconId));
        }

        return null;
    }
}
