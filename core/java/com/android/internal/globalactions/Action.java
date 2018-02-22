/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.internal.globalactions;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

/** What each item in the global actions dialog must be able to support. */
public interface Action {
    /** @return Text that will be announced when dialog is created. {@code null} for none. */
    CharSequence getLabelForAccessibility(Context context);

    /** Create the view that represents this action. */
    View create(Context context, View convertView, ViewGroup parent, LayoutInflater inflater);

    /** Called when the action is selected by the user. */
    void onPress();

    /** @return whether this action should appear in the dialog when the keygaurd is showing. */
    boolean showDuringKeyguard();

    /** @return whether this action should appear in the dialog before the device is provisioned. */
    boolean showBeforeProvisioning();

    /** @return {@code true} if the action is enabled for user interaction. */
    boolean isEnabled();
}
