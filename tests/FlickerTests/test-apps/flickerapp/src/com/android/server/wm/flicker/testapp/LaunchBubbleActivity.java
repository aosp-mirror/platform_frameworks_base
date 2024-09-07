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

package com.android.server.wm.flicker.testapp;


import static android.Manifest.permission.POST_NOTIFICATIONS;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.app.Activity;
import android.app.Person;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.view.View;

import java.util.Arrays;

public class LaunchBubbleActivity extends Activity {

    private BubbleHelper mBubbleHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(POST_NOTIFICATIONS) != PERMISSION_GRANTED) {
            // POST_NOTIFICATIONS permission required for notification post sdk 33.
            requestPermissions(new String[] { POST_NOTIFICATIONS }, 0);
        }

        addInboxShortcut(getApplicationContext());
        mBubbleHelper = BubbleHelper.getInstance(this);
        setContentView(R.layout.activity_main);
        findViewById(R.id.button_create).setOnClickListener(this::add);
        findViewById(R.id.button_cancel).setOnClickListener(this::cancel);
        findViewById(R.id.button_cancel_all).setOnClickListener(this::cancelAll);
    }

    private void add(View v) {
        mBubbleHelper.addNewBubble(false /* autoExpand */, false /* suppressNotif */);
    }

    private void cancel(View v) {
        mBubbleHelper.cancelLast();
    }

    private void cancelAll(View v) {
        mBubbleHelper.cancelAll();
    }

    private void addInboxShortcut(Context context) {
        Icon icon = Icon.createWithResource(this, R.drawable.bg);
        Person[] persons = new Person[4];
        for (int i = 0; i < persons.length; i++) {
            persons[i] = new Person.Builder()
                    .setBot(false)
                    .setIcon(icon)
                    .setName("google" + i)
                    .setImportant(true)
                    .build();
        }

        ShortcutInfo shortcut = new ShortcutInfo.Builder(context, "BubbleChat")
                .setShortLabel("BubbleChat")
                .setLongLived(true)
                .setIntent(new Intent(Intent.ACTION_VIEW))
                .setIcon(Icon.createWithResource(context, R.drawable.ic_message))
                .setPersons(persons)
                .build();
        ShortcutManager scmanager = context.getSystemService(ShortcutManager.class);
        scmanager.addDynamicShortcuts(Arrays.asList(shortcut));
    }

}
