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
 * An inspection companion provider that finds companions as inner classes or generated code.
 */
public class StaticInspectionCompanionProvider implements InspectionCompanionProvider {
    /**
     * The suffix used for the generated classes and inner classes
     */
    private static final String COMPANION_SUFFIX = "$InspectionCompanion";

    @Override
    @Nullable
    @SuppressWarnings("unchecked")
    public <T> InspectionCompanion<T> provide(@NonNull Class<T> cls) {
        final String companionName = cls.getName() + COMPANION_SUFFIX;

        try {
            final Class<InspectionCompanion<T>> companionClass =
                    (Class<InspectionCompanion<T>>) cls.getClassLoader().loadClass(companionName);

            if (InspectionCompanion.class.isAssignableFrom(companionClass)) {
                return companionClass.newInstance();
            } else {
                return null;
            }
        } catch (ClassNotFoundException e) {
            return null;
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (InstantiationException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new RuntimeException(cause);
        }
    }
}
