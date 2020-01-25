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
import android.service.controls.templates.RangeTemplate;
import android.service.controls.templates.ToggleRangeTemplate;

/**
 * Action sent by a {@link RangeTemplate}, {@link ToggleRangeTemplate}.
 */
public final class FloatAction extends ControlAction {

    private static final @ActionType int TYPE = TYPE_FLOAT;
    private static final String KEY_NEW_VALUE = "key_new_value";

    private final float mNewValue;

    /**
     * @param templateId the identifier of the {@link RangeTemplate} that produced this action.
     * @param newValue new value for the state displayed by the {@link RangeTemplate}.
     */
    public FloatAction(@NonNull String templateId, float newValue) {
        this(templateId, newValue, null);
    }

    /**
     * @param templateId the identifier of the {@link RangeTemplate} that originated this action.
     * @param newValue new value for the state of the {@link RangeTemplate}.
     * @param challengeValue a value sent by the user along with the action to authenticate. {@code}
     *                       null is sent when no authentication is needed or has not been
     *                       requested.
     */

    public FloatAction(@NonNull String templateId, float newValue,
            @Nullable String challengeValue) {
        super(templateId, challengeValue);
        mNewValue = newValue;
    }

    /**
     * @param b
     * @hide
     */
    FloatAction(Bundle b) {
        super(b);
        mNewValue = b.getFloat(KEY_NEW_VALUE);
    }

    /**
     * The new value set for the range in the corresponding {@link RangeTemplate}.
     */
    public float getNewValue() {
        return mNewValue;
    }

    /**
     * @return {@link ControlAction#TYPE_FLOAT}
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
        Bundle b = super.getDataBundle();
        b.putFloat(KEY_NEW_VALUE, mNewValue);
        return b;
    }
}
