/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.view.autofill;

import android.graphics.Rect;
import android.view.WindowManager;

/**
 * This is a handle to the FillUi for controlling
 * when its window should be shown and hidden.
 *
 * {@hide}
 */
oneway interface IAutofillWindowPresenter {
    void show(in WindowManager.LayoutParams p, in Rect transitionEpicenter,
            boolean fitsSystemWindows, int layoutDirection);
    void hide(in Rect transitionEpicenter);
}
