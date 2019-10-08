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

import static android.view.autofill.Helper.sDebug;

import android.annotation.NonNull;
import android.annotation.TestApi;
import android.os.Parcelable;
import android.util.Log;
import android.util.Pair;
import android.widget.RemoteViews;

import java.util.ArrayList;

/**
 * Superclass of all transformation the system understands. As this is not public all
 * subclasses have to implement {@link Transformation} again.
 *
 * @hide
 */
@TestApi
public abstract class InternalTransformation implements Transformation, Parcelable {

    private static final String TAG = "InternalTransformation";

    /**
     * Applies this transformation to a child view of a {@link android.widget.RemoteViews
     * presentation template}.
     *
     * @param finder object used to find the value of a field in the screen.
     * @param template the {@link RemoteViews presentation template}.
     * @param childViewId resource id of the child view inside the template.
     */
    abstract void apply(@NonNull ValueFinder finder, @NonNull RemoteViews template,
            int childViewId) throws Exception;

    /**
     * Applies multiple transformations to the children views of a
     * {@link android.widget.RemoteViews presentation template}.
     *
     * @param finder object used to find the value of a field in the screen.
     * @param template the {@link RemoteViews presentation template}.
     * @param transformations map of resource id of the child view inside the template to
     * transformation.
     */
    public static boolean batchApply(@NonNull ValueFinder finder, @NonNull RemoteViews template,
            @NonNull ArrayList<Pair<Integer, InternalTransformation>> transformations) {
        final int size = transformations.size();
        if (sDebug) Log.d(TAG, "getPresentation(): applying " + size + " transformations");
        for (int i = 0; i < size; i++) {
            final Pair<Integer, InternalTransformation> pair = transformations.get(i);
            final int id = pair.first;
            final InternalTransformation transformation = pair.second;
            if (sDebug) Log.d(TAG, "#" + i + ": " + transformation);

            try {
                transformation.apply(finder, template, id);
            } catch (Exception e) {
                // Do not log full exception to avoid PII leaking
                Log.e(TAG, "Could not apply transformation " + transformation + ": "
                        + e.getClass());
                return false;
            }
        }
        return true;
    }
}
