/*
 * Copyright 2019 The Android Open Source Project
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

package android.view.inspector;

import android.annotation.NonNull;
import android.annotation.Nullable;

/**
 * An inspection companion provider that loads pre-generated inspection companions
 *
 * @see android.processor.view.inspector.PlatformInspectableProcessor
 */
public class GeneratedInspectionCompanionProvider implements InspectionCompanionProvider {
    /**
     * The suffix used for the generated class
     */
    private static final String COMPANION_SUFFIX = "$$InspectionCompanion";

    @Override
    @Nullable
    @SuppressWarnings("unchecked")
    public <T> InspectionCompanion<T> provide(@NonNull Class<T> cls) {
        final String companionName = cls.getName() + COMPANION_SUFFIX;

        try {
            final Class<InspectionCompanion<T>> companionClass =
                    (Class<InspectionCompanion<T>>) cls.getClassLoader().loadClass(companionName);
            return companionClass.newInstance();
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException e) {
            return null;
        }
    }
}
