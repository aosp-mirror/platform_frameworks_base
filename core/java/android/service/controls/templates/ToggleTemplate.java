/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.service.controls.templates;

import android.annotation.NonNull;
import android.os.Bundle;
import android.service.controls.Control;
import android.service.controls.actions.BooleanAction;

import com.android.internal.util.Preconditions;

/**
 * A template for a {@link Control} with a single button that can be toggled between two states.
 *
 * The states for the toggle correspond to the states in {@link ControlButton#isChecked()}.
 * An action on this template will originate a {@link BooleanAction} to change that state.
 *
 * @see BooleanAction
 */
public final class ToggleTemplate extends ControlTemplate {

    private static final @TemplateType int TYPE = TYPE_TOGGLE;
    private static final String KEY_BUTTON = "key_button";
    private final @NonNull ControlButton mButton;

    /**
     * @param templateId the identifier for this template object
     * @param button a {@link ControlButton} that can show the current state and toggle it
     */
    public ToggleTemplate(@NonNull String templateId, @NonNull ControlButton button) {
        super(templateId);
        Preconditions.checkNotNull(button);
        mButton = button;
    }

    /**
     * @param b
     * @hide
     */
    ToggleTemplate(Bundle b) {
        super(b);
        mButton = b.getParcelable(KEY_BUTTON);
    }

    public boolean isChecked() {
        return mButton.isChecked();
    }

    @NonNull
    public CharSequence getContentDescription() {
        return mButton.getActionDescription();
    }

    /**
     * @return {@link ControlTemplate#TYPE_TOGGLE}
     */
    @Override
    public int getTemplateType() {
        return TYPE;
    }

    /**
     * @return
     * @hide
     */
    @Override
    @NonNull
    Bundle getDataBundle() {
        Bundle b =  super.getDataBundle();
        b.putParcelable(KEY_BUTTON, mButton);
        return b;
    }
}
