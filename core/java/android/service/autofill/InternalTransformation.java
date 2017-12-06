/*
 * Copyright (C) 2017 The Android Open Source Project
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
package android.service.autofill;

import android.annotation.NonNull;
import android.os.Parcelable;
import android.widget.RemoteViews;

/**
 * Superclass of all transformation the system understands. As this is not public all
 * subclasses have to implement {@link Transformation} again.
 *
 * @hide
 */
abstract class InternalTransformation implements Transformation, Parcelable {

    /**
     * Applies this transformation to a child view of a {@link android.widget.RemoteViews
     * presentation template}.
     *
     * @param finder object used to find the value of a field in the screen.
     * @param template the {@link RemoteViews presentation template}.
     * @param childViewId resource id of the child view inside the template.
     *
     * @hide
     */
    abstract void apply(@NonNull ValueFinder finder, @NonNull RemoteViews template,
            int childViewId) throws Exception;
}
