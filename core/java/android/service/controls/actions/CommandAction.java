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
import android.service.controls.templates.StatelessTemplate;

/**
 * A simple {@link ControlAction} indicating that the user has interacted with a {@link Control}
 * created using a {@link StatelessTemplate}.
 */
public final class CommandAction extends ControlAction {

    private static final @ActionType int TYPE = TYPE_COMMAND;

    /**
     * @param templateId the identifier of the {@link StatelessTemplate} that originated this
     *                   action.
     * @param challengeValue a value sent by the user along with the action to authenticate. {@code}
     *                       null is sent when no authentication is needed or has not been
     *                       requested.
     */
    public CommandAction(@NonNull String templateId, @Nullable String challengeValue) {
        super(templateId, challengeValue);
    }

    /**
     * @param templateId the identifier of the {@link StatelessTemplate} that originated this
     *                   action.
     */
    public CommandAction(@NonNull String templateId) {
        this(templateId, null);
    }

    /**
     * @param b
     * @hide
     */
    CommandAction(Bundle b) {
        super(b);
    }

    /**
     * @return {@link ControlAction#TYPE_COMMAND}
     */
    @Override
    public int getActionType() {
        return TYPE;
    }
}
