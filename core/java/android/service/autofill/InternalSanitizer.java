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
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.os.Parcelable;
import android.view.autofill.AutofillValue;

/**
 * Superclass of all sanitizers the system understands. As this is not public all public subclasses
 * have to implement {@link Sanitizer} again.
 *
 * @hide
 */
@TestApi
public abstract class InternalSanitizer implements Sanitizer, Parcelable {

    /**
     * Sanitizes an {@link AutofillValue}.
     *
     * @return sanitized value or {@code null} if value could not be sanitized (for example: didn't
     * match regex, it's an invalid type, regex failed, etc).
     */
    @Nullable
    public abstract AutofillValue sanitize(@NonNull AutofillValue value);
}
