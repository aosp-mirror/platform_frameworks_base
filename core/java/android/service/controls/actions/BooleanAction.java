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

package android.service.controls.actions;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.service.controls.Control;
import android.service.controls.templates.ToggleRangeTemplate;
import android.service.controls.templates.ToggleTemplate;

/**
 * Action sent by user toggling a {@link Control} between checked/unchecked.
 *
 * This action is available when the {@link Control} was constructed with either a
 * {@link ToggleTemplate} or a {@link ToggleRangeTemplate}.
 */
public final class BooleanAction extends ControlAction {

    private static final @ActionType int TYPE = TYPE_BOOLEAN;
    private static final String KEY_NEW_STATE = "key_new_state";

    private final boolean mNewState;

    /**
     * @param templateId the identifier of the {@link ToggleTemplate} that produced this action.
     * @param newState new value for the state displayed by the {@link ToggleTemplate}.
     */
    public BooleanAction(@NonNull String templateId, boolean newState) {
        this(templateId, newState, null);
    }

    /**
     * @param templateId the identifier of the template that originated this action.
     * @param newState new value for the state displayed by the template.
     * @param challengeValue a value sent by the user along with the action to authenticate. {@code}
     *                       null is sent when no authentication is needed or has not been
     *                       requested.
     */
    public BooleanAction(@NonNull String templateId, boolean newState,
            @Nullable String challengeValue) {
        super(templateId, challengeValue);
        mNewState = newState;
    }

    /**
     * @param b
     * @hide
     */
    BooleanAction(Bundle b) {
        super(b);
        mNewState = b.getBoolean(KEY_NEW_STATE);
    }

    /**
     * The new state set for the button in the corresponding {@link ToggleTemplate}.
     *
     * @return {@code true} if the button was toggled from unchecked to checked.
     */
    public boolean getNewState() {
        return mNewState;
    }

    /**
     * @return {@link ControlAction#TYPE_BOOLEAN}
     */
    @Override
    public int getActionType() {
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
        b.putBoolean(KEY_NEW_STATE, mNewState);
        return b;
    }
}
