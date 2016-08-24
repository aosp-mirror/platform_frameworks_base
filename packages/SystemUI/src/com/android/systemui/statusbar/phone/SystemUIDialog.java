/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.app.AlertDialog;
import android.content.Context;
import android.view.WindowManager;

import com.android.systemui.R;

/**
 * Base class for dialogs that should appear over panels and keyguard.
 */
public class SystemUIDialog extends AlertDialog {

    private final Context mContext;

    public SystemUIDialog(Context context) {
        this(context, R.style.Theme_SystemUI_Dialog);
    }

    public SystemUIDialog(Context context, int theme) {
        super(context, theme);
        mContext = context;

        applyFlags(this);
        WindowManager.LayoutParams attrs = getWindow().getAttributes();
        attrs.setTitle(getClass().getSimpleName());
        getWindow().setAttributes(attrs);
    }

    public void setShowForAllUsers(boolean show) {
        setShowForAllUsers(this, show);
    }

    public void setMessage(int resId) {
        setMessage(mContext.getString(resId));
    }

    public void setPositiveButton(int resId, OnClickListener onClick) {
        setButton(BUTTON_POSITIVE, mContext.getString(resId), onClick);
    }

    public void setNegativeButton(int resId, OnClickListener onClick) {
        setButton(BUTTON_NEGATIVE, mContext.getString(resId), onClick);
    }

    public static void setShowForAllUsers(AlertDialog dialog, boolean show) {
        if (show) {
            dialog.getWindow().getAttributes().privateFlags |=
                    WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
        } else {
            dialog.getWindow().getAttributes().privateFlags &=
                    ~WindowManager.LayoutParams.PRIVATE_FLAG_SHOW_FOR_ALL_USERS;
        }
    }

    public static void applyFlags(AlertDialog dialog) {
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_STATUS_BAR_PANEL);
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
    }
}
