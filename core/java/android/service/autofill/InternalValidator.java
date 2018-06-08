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

/**
 * Superclass of all validators the system understands. As this is not public all public subclasses
 * have to implement {@link Validator} again.
 *
 * @hide
 */
@TestApi
public abstract class InternalValidator implements Validator, Parcelable {

    /**
     * Decides whether the contents of the screen are valid.
     *
     * @param finder object used to find the value of a field in the screen.
     * @return {@code true} if the contents are valid, {@code false} otherwise.
     *
     * @hide
     */
    @TestApi
    public abstract boolean isValid(@NonNull ValueFinder finder);
}
