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

package android.service.controls;

import android.annotation.NonNull;
import android.os.Bundle;
import android.os.Parcel;

import com.android.internal.util.Preconditions;

/**
 * A template for a {@link Control} with a single button that can be toggled between two states.
 *
 * The states for the toggle correspond to the states in {@link ControlButton#isChecked()}.
 * An action on this template will originate a {@link BooleanAction} to change that state.
 *
 * @see BooleanAction
 * @hide
 */
public final class ToggleTemplate extends ControlTemplate {

    private static final String KEY_BUTTON = "key_button";
    private final @NonNull ControlButton mButton;

    /**
     * @param templateId the identifier for this template object
     * @param button a {@ControlButton} that can show the current state and toggle it
     */
    public ToggleTemplate(@NonNull String templateId, @NonNull ControlButton button) {
        super(templateId);
        Preconditions.checkNotNull(button);
        mButton = button;
    }

    ToggleTemplate(Bundle b) {
        super(b);
        mButton = b.getParcelable(KEY_BUTTON);
    }

    /**
     * The button provided to this object in {@link ToggleTemplate#ToggleTemplate}
     */
    @NonNull
    public ControlButton getButton() {
        return mButton;
    }

    /**
     * @return {@link ControlTemplate#TYPE_TOGGLE}
     */
    @Override
    public int getTemplateType() {
        return TYPE_TOGGLE;
    }

    @Override
    protected Bundle getDataBundle() {
        Bundle b =  super.getDataBundle();
        b.putObject(KEY_BUTTON, mButton);
        return b;
    }

    public static final Creator<ToggleTemplate> CREATOR = new Creator<ToggleTemplate>() {
        @Override
        public ToggleTemplate createFromParcel(Parcel source) {
            return new ToggleTemplate(source.readBundle());
        }

        @Override
        public ToggleTemplate[] newArray(int size) {
            return new ToggleTemplate[size];
        }
    };

}
