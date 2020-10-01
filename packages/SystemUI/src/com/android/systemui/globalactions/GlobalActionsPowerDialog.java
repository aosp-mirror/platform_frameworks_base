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
package com.android.systemui.globalactions;

import android.annotation.NonNull;
import android.app.Dialog;
import android.content.Context;
import android.content.res.Resources;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ListAdapter;

/**
 * Creates a customized Dialog for displaying the Shut Down and Restart actions.
 */
public class GlobalActionsPowerDialog {

    /**
     * Create a dialog for displaying Shut Down and Restart actions.
     */
    public static Dialog create(@NonNull Context context, ListAdapter adapter) {
        ViewGroup listView = (ViewGroup) LayoutInflater.from(context).inflate(
                com.android.systemui.R.layout.global_actions_power_dialog, null);

        for (int i = 0; i < adapter.getCount(); i++) {
            View action = adapter.getView(i, null, listView);
            listView.addView(action);
        }

        Resources res = context.getResources();

        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(listView);

        Window window = dialog.getWindow();
        window.setType(WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY);
        window.setTitle(""); // prevent Talkback from speaking first item name twice
        window.setBackgroundDrawable(res.getDrawable(
                com.android.systemui.R.drawable.control_background, context.getTheme()));
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);

        return dialog;
    }
}
