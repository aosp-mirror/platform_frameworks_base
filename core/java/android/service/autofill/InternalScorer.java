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
import android.annotation.TestApi;
import android.os.Parcelable;
import android.view.autofill.AutofillValue;

/**
 * Superclass of all scorer the system understands. As this is not public all
 * subclasses have to implement {@link Scorer} again.
 *
 * @hide
 */
@TestApi
public abstract class InternalScorer implements Scorer, Parcelable {

    /**
     * Returns the classification score between an actual {@link AutofillValue} filled
     * by the user and the expected value predicted by an autofill service.
     *
     * <p>A full-match is {@code 1.0} (representing 100%), a full mismatch is {@code 0.0} and
     * partial mathces are something in between, typically using edit-distance algorithms.
     */
    public abstract float getScore(@NonNull AutofillValue actualValue, @NonNull String userData);
}
