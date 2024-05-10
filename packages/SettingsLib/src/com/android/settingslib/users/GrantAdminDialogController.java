/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.RadioButton;
import android.widget.RadioGroup;

import com.android.settingslib.R;

import java.util.function.Consumer;

/**
 * This class encapsulates a Dialog for choosing whether to grant admin privileges.
 */
public class GrantAdminDialogController {

    /**
     * Creates a dialog with option to grant user admin privileges.
     */
    public Dialog createDialog(Activity activity,
            Consumer<Boolean> successCallback, Runnable cancelCallback) {
        LayoutInflater inflater = LayoutInflater.from(activity);
        View content = inflater.inflate(R.layout.grant_admin_dialog_content, null);
        RadioGroup radioGroup = content.findViewById(R.id.choose_admin);
        RadioButton radioButton = radioGroup.findViewById(R.id.grant_admin_yes);
        radioButton.setChecked(true);
        Dialog dlg = new AlertDialog.Builder(activity)
                .setView(content)
                .setTitle(R.string.user_grant_admin_title)
                .setMessage(R.string.user_grant_admin_message)
                .setPositiveButton(android.R.string.ok,
                        (dialog, which) ->  {
                            if (successCallback != null) {
                                successCallback.accept(radioButton.isChecked());
                            }
                        })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                    if (cancelCallback != null) {
                        cancelCallback.run();
                    }
                })
                .setOnCancelListener(dialog -> {
                    if (cancelCallback != null) {
                        cancelCallback.run();
                    }
                })
                .create();

        return dlg;
    }

}
