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
 * An interface for services that can provide inspection companions for a class.
 */
public interface InspectionCompanionProvider {
    /**
     * Provide an {@link InspectionCompanion} for the supplied class.
     *
     * Implementing classes must not cache companion instances, and should instantiate a new one
     * for each request.
     *
     * @param cls A {@link Class} representing the inspectable type
     * @param <T> The type to find the companion for
     * @return The inspection companion for the supplied type
     */
    @Nullable
    <T> InspectionCompanion<T> provide(@NonNull Class<T> cls);
}
