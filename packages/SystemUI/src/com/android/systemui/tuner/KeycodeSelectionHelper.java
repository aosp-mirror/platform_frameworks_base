/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.tuner;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.view.KeyEvent;

import com.android.systemui.R;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;

public class KeycodeSelectionHelper {

    private static final ArrayList<String> mKeycodeStrings = new ArrayList<>();
    private static final ArrayList<Integer> mKeycodes = new ArrayList<>();

    private static final String KEYCODE_STRING = "KEYCODE_";

    static {
        Class<KeyEvent> cls = KeyEvent.class;
        for (Field field : cls.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    && field.getName().startsWith(KEYCODE_STRING)
                    && field.getType().equals(int.class)) {
                try {
                    mKeycodeStrings.add(formatString(field.getName()));
                    mKeycodes.add((Integer) field.get(null));
                } catch (IllegalAccessException e) {
                }
            }
        }
    }

    // Force the string into something somewhat readable.
    private static String formatString(String name) {
        StringBuilder str = new StringBuilder(name.replace(KEYCODE_STRING, "").replace("_", " ")
                .toLowerCase());
        for (int i = 0; i < str.length(); i++) {
            if (i == 0 || str.charAt(i - 1) == ' ') {
                str.setCharAt(i, Character.toUpperCase(str.charAt(i)));
            }
        }
        return str.toString();
    }

    public static void showKeycodeSelect(Context context, final OnSelectionComplete listener) {
        new AlertDialog.Builder(context)
                .setTitle(R.string.select_keycode)
                .setItems(mKeycodeStrings.toArray(new String[0]),
                        new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        listener.onSelectionComplete(mKeycodes.get(which));
                    }
                }).show();
    }

    public static Intent getSelectImageIntent() {
        return new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*");
    }

    public interface OnSelectionComplete {
        void onSelectionComplete(int code);
    }
}
