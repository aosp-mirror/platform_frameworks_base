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
import android.service.controls.templates.TemperatureControlTemplate;

/**
 * Action sent by the user to indicate a change of mode.
 *
 * This action is available when the {@link Control} was created with a
 * {@link TemperatureControlTemplate}.
 */
public final class ModeAction extends ControlAction {

    private static final @ActionType int TYPE = TYPE_MODE;
    private static final String KEY_MODE = "key_mode";

    private final int mNewMode;

    /**
     * @return {@link ControlAction#TYPE_MODE}.
     */
    @Override
    public int getActionType() {
        return TYPE;
    }

    /**
     * @param templateId the identifier of the {@link TemperatureControlTemplate} that originated
     *                   this action.
     * @param newMode new value for the mode.
     * @param challengeValue a value sent by the user along with the action to authenticate. {@code}
     *                       null is sent when no authentication is needed or has not been
     *                       requested.
     */
    public ModeAction(@NonNull String templateId, int newMode, @Nullable String challengeValue) {
        super(templateId, challengeValue);
        mNewMode = newMode;
    }

    /**
     * @param templateId the identifier of the {@link TemperatureControlTemplate} that originated
     *                   this action.
     * @param newMode new value for the mode.
     */
    public ModeAction(@NonNull String templateId, int newMode) {
        this(templateId, newMode, null);
    }

    /**
     * @param b
     * @hide
     */
    ModeAction(Bundle b) {
        super(b);
        mNewMode = b.getInt(KEY_MODE);
    }

    /**
     * @return
     * @hide
     */
    @Override
    @NonNull
    Bundle getDataBundle() {
        Bundle b = super.getDataBundle();
        b.putInt(KEY_MODE, mNewMode);
        return b;
    }

    public int getNewMode() {
        return mNewMode;
    }
}
