/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.wm;

import static android.text.Html.FROM_HTML_MODE_COMPACT;

import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.text.Html;
import android.view.Window;
import android.view.WindowManager;

import com.android.internal.R;

/**
 * Show warning dialog when
 * - Uncompressed libs inside apk are not aligned to page size
 * - ELF Load segments are not page size aligned
 * This dialog will be shown everytime when app is launched. Apps can choose to override
 * by setting compat mode pageSizeCompat="enabled" in manifest or "disabled" to opt out.
 * Both cases will skip the PageSizeMismatchDialog.
 *
 */
class PageSizeMismatchDialog extends AppWarnings.BaseDialog {
    PageSizeMismatchDialog(
            final AppWarnings manager,
            Context context,
            ApplicationInfo appInfo,
            int userId,
            String warning) {
        super(manager, context, appInfo.packageName, userId);

        final PackageManager pm = context.getPackageManager();
        final CharSequence label =
                appInfo.loadSafeLabel(
                        pm,
                        PackageItemInfo.DEFAULT_MAX_LABEL_SIZE_PX,
                        PackageItemInfo.SAFE_LABEL_FLAG_FIRST_LINE
                                | PackageItemInfo.SAFE_LABEL_FLAG_TRIM);

        final AlertDialog.Builder builder =
                new AlertDialog.Builder(context)
                        .setPositiveButton(
                                R.string.ok,
                                (dialog, which) -> {/* Do nothing */})
                        .setMessage(Html.fromHtml(warning, FROM_HTML_MODE_COMPACT))
                        .setTitle(label);

        mDialog = builder.create();
        mDialog.create();

        final Window window = mDialog.getWindow();
        window.setType(WindowManager.LayoutParams.TYPE_PHONE);
    }
}
