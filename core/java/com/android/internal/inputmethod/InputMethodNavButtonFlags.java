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

package com.android.internal.inputmethod;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.IntDef;

import java.lang.annotation.Retention;

/**
 * A set of flags notified from {@link com.android.server.inputmethod.InputMethodManagerService} to
 * {@link android.inputmethodservice.InputMethodService} regarding how
 * {@link android.inputmethodservice.NavigationBarController} should behave.
 *
 * <p>These flags will take effect when and only when
 * {@link android.inputmethodservice.InputMethodService#canImeRenderGesturalNavButtons} returns
 * {@code true}.</p>
 */
@Retention(SOURCE)
@IntDef(flag = true, value = {
        InputMethodNavButtonFlags.IME_DRAWS_IME_NAV_BAR,
        InputMethodNavButtonFlags.SHOW_IME_SWITCHER_WHEN_IME_IS_SHOWN,
})
public @interface InputMethodNavButtonFlags {
    /**
     * When set, the IME process needs to render and handle the navigation bar buttons such as the
     * back button and the IME switcher button.
     */
    int IME_DRAWS_IME_NAV_BAR = 1;
    /**
     * When set, the IME switcher icon needs to be shown on the navigation bar.
     */
    int SHOW_IME_SWITCHER_WHEN_IME_IS_SHOWN = 2;
}
