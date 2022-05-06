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

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

/**
 * A nop {@link Activity} to make sure that the test starts from a deterministic state.
 *
 * <p>Currently this {@link Activity} makes sure the following things</p>
 * <li>
 *     <ul>Hide the software keyboard with
 *     {@link android.view.WindowManager.LayoutParams#SOFT_INPUT_STATE_ALWAYS_HIDDEN}</ul>
 *     <ul>Make sure that the navigation bar (if supported) is rendered with {@link Color#BLACK}.
 *     </ul>
 * </li>
 */
public class ImeStateInitializeActivity extends Activity {
    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        final View view = new View(this);
        view.setBackgroundColor(Color.WHITE);
        view.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Make sure that navigation bar is rendered with black (if supported).
        getWindow().setNavigationBarColor(Color.BLACK);

        setContentView(view);
    }
}
