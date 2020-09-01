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
import android.service.controls.actions.CommandAction;

/**
 * A template for a {@link Control} which has no state.
 *
 * @see CommandAction
 */
public final class StatelessTemplate extends ControlTemplate {

    /**
     * @return {@link ControlTemplate#TYPE_STATELESS}
     */
    @Override
    public int getTemplateType() {
        return TYPE_STATELESS;
    }

    /**
     * Construct a new {@link StatelessTemplate} from a {@link Bundle}
     * @hide
     */
    StatelessTemplate(@NonNull Bundle b) {
        super(b);
    }

    /**
     * Construct a new {@link StatelessTemplate}
     * @param templateId the identifier for this template
     */
    public StatelessTemplate(@NonNull String templateId) {
        super(templateId);
    }
}
