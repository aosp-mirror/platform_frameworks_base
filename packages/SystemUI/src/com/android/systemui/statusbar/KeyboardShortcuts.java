/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.statusbar;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import com.android.systemui.R;

/**
 * Contains functionality for handling keyboard shortcuts.
 */
public class KeyboardShortcuts {
    private Dialog mKeyboardShortcutsDialog;

    public KeyboardShortcuts() {}

    public void toggleKeyboardShortcuts(Context context) {
        if (mKeyboardShortcutsDialog == null) {
            // Create dialog.
            AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(context);
            LayoutInflater inflater = (LayoutInflater) context.getSystemService(
                    Context.LAYOUT_INFLATER_SERVICE);
            final View keyboardShortcutsView = inflater.inflate(
                    R.layout.keyboard_shortcuts_view, null);

            populateKeyboardShortcuts(keyboardShortcutsView.findViewById(
                    R.id.keyboard_shortcuts_wrapper));
            dialogBuilder.setView(keyboardShortcutsView);
            mKeyboardShortcutsDialog = dialogBuilder.create();
            mKeyboardShortcutsDialog.setCanceledOnTouchOutside(true);

            // Setup window.
            Window keyboardShortcutsWindow = mKeyboardShortcutsDialog.getWindow();
            keyboardShortcutsWindow.setType(
                    WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG);
            keyboardShortcutsWindow.setBackgroundDrawable(
                    new ColorDrawable(android.graphics.Color.TRANSPARENT));
            keyboardShortcutsWindow.setGravity(Gravity.TOP);
            mKeyboardShortcutsDialog.show();
        } else {
            dismissKeyboardShortcutsDialog();
        }
    }

    public void dismissKeyboardShortcutsDialog() {
        if (mKeyboardShortcutsDialog != null) {
            mKeyboardShortcutsDialog.dismiss();
            mKeyboardShortcutsDialog = null;
        }
    }

    /**
     * @return {@code true} if the keyboard shortcuts have been successfully populated.
     */
    private boolean populateKeyboardShortcuts(View keyboardShortcutsLayout) {
        // TODO: Populate shortcuts.
        return true;
    }
}
