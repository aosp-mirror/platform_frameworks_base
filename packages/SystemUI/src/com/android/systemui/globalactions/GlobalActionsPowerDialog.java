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
import android.view.CrossWindowBlurListeners;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ListAdapter;

import com.android.systemui.statusbar.BlurUtils;
import com.android.systemui.dump.DumpManager;

import androidx.constraintlayout.helper.widget.Flow;

/**
 * Creates a customized Dialog for displaying the Shut Down and Restart actions.
 */
public class GlobalActionsPowerDialog {

    /**
     * Create a dialog for displaying Shut Down and Restart actions.
     */
    public static Dialog create(@NonNull Context context, ListAdapter adapter) {
        ViewGroup listView = (ViewGroup) LayoutInflater.from(context).inflate(
                com.android.systemui.R.layout.global_actions_power_dialog_flow, null);

        Flow flow = listView.findViewById(com.android.systemui.R.id.power_flow);

        for (int i = 0; i < adapter.getCount(); i++) {
            View action = adapter.getView(i, null, listView);
            action.setId(View.generateViewId());
            listView.addView(action);
            flow.addView(action);
        }

        Resources res = context.getResources();

        Dialog dialog = new Dialog(context,
                com.android.systemui.R.style.Theme_SystemUI_Dialog_GlobalActionsLite);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(listView);

        BlurUtils blurUtils = new BlurUtils(context.getResources(),
                CrossWindowBlurListeners.getInstance(), new DumpManager());

        Window window = dialog.getWindow();
        window.setType(WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY);
        window.setTitle(""); // prevent Talkback from speaking first item name twice
        window.setBackgroundDrawable(res.getDrawable(
                com.android.systemui.R.drawable.global_actions_lite_background, context.getTheme()));
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        window.setDimAmount(blurUtils.supportsBlursOnWindows() ? 0.54f : 0.88f);

        return dialog;
    }
}
