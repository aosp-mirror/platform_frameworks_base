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
import android.os.Handler;
import android.util.Log;
import android.view.KeyEvent;
import android.view.KeyboardShortcutGroup;
import android.view.KeyboardShortcutInfo;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowManager.KeyboardShortcutsReceiver;

import com.android.systemui.R;
import com.android.systemui.recents.Recents;

import java.util.List;

import static android.content.Context.LAYOUT_INFLATER_SERVICE;
import static android.graphics.Color.TRANSPARENT;
import static android.view.Gravity.TOP;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG;

/**
 * Contains functionality for handling keyboard shortcuts.
 */
public class KeyboardShortcuts {
    private static final String TAG = "KeyboardShortcuts";

    private Dialog mKeyboardShortcutsDialog;

    public KeyboardShortcuts() {}

    public void toggleKeyboardShortcuts(final Context context) {
        if (mKeyboardShortcutsDialog == null) {
            Recents.getSystemServices().requestKeyboardShortcuts(context,
                new KeyboardShortcutsReceiver() {
                    @Override
                    public void onKeyboardShortcutsReceived(
                            final List<KeyboardShortcutGroup> result) {
                        KeyboardShortcutGroup systemGroup = new KeyboardShortcutGroup(
                            context.getString(R.string.keyboard_shortcut_group_system));
                        systemGroup.addItem(new KeyboardShortcutInfo(
                            context.getString(R.string.keyboard_shortcut_group_system_home),
                            '\u2386', KeyEvent.META_META_ON));
                        systemGroup.addItem(new KeyboardShortcutInfo(
                            context.getString(R.string.keyboard_shortcut_group_system_back),
                            '\u007F', KeyEvent.META_META_ON));
                        systemGroup.addItem(new KeyboardShortcutInfo(
                            context.getString(R.string.keyboard_shortcut_group_system_recents),
                            '\u0009', KeyEvent.META_ALT_ON));
                        result.add(systemGroup);
                        Log.i(TAG, "Keyboard shortcuts received: " + String.valueOf(result));
                        showKeyboardShortcutsDialog(context);
                    }
                });
        } else {
            dismissKeyboardShortcutsDialog();
        }
    }

    private void showKeyboardShortcutsDialog(Context context) {
        // Create dialog.
        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(context);
        LayoutInflater inflater = (LayoutInflater) context.getSystemService(
                LAYOUT_INFLATER_SERVICE);
        final View keyboardShortcutsView = inflater.inflate(
                R.layout.keyboard_shortcuts_view, null);

        populateKeyboardShortcuts(keyboardShortcutsView.findViewById(
                R.id.keyboard_shortcuts_wrapper));
        dialogBuilder.setView(keyboardShortcutsView);
        mKeyboardShortcutsDialog = dialogBuilder.create();
        mKeyboardShortcutsDialog.setCanceledOnTouchOutside(true);

        // Setup window.
        Window keyboardShortcutsWindow = mKeyboardShortcutsDialog.getWindow();
        keyboardShortcutsWindow.setType(TYPE_SYSTEM_DIALOG);
        keyboardShortcutsWindow.setBackgroundDrawable(
                new ColorDrawable(TRANSPARENT));
        keyboardShortcutsWindow.setGravity(TOP);
        keyboardShortcutsView.post(new Runnable() {
            public void run() {
                mKeyboardShortcutsDialog.show();
            }
        });
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
