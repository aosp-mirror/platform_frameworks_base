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

package com.android.settingslib.users;

import android.app.AlertDialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.android.settingslib.R;

/**
 * Dialog to show when a user creation is in progress.
 */
public class UserCreatingDialog extends AlertDialog {

    public UserCreatingDialog(Context context) {
        // hardcoding theme to be consistent with UserSwitchingDialog's theme
        // todo replace both to adapt to the device's theme
        super(context, com.android.internal.R.style.Theme_DeviceDefault_Light_Dialog_Alert);

        inflateContent();
        getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ERROR);

        WindowManager.LayoutParams attrs = getWindow().getAttributes();
        attrs.privateFlags = WindowManager.LayoutParams.PRIVATE_FLAG_SYSTEM_ERROR
                | WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        getWindow().setAttributes(attrs);
    }

    private void inflateContent() {
        // using the same design as UserSwitchingDialog
        setCancelable(false);
        View view = LayoutInflater.from(getContext())
                .inflate(R.layout.user_creation_progress_dialog, null);
        String message = getContext().getString(R.string.creating_new_user_dialog_message);
        view.setAccessibilityPaneTitle(message);
        ((TextView) view.findViewById(R.id.message)).setText(message);
        setView(view);
    }

}
