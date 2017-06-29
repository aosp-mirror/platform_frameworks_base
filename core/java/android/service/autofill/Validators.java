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

import com.android.internal.util.Preconditions;

/**
 * Factory for {@link Validator} operations.
 *
 * <p>See {@link SaveInfo.Builder#setValidator(Validator)} for examples.
 */
public final class Validators {

    private Validators() {
        throw new UnsupportedOperationException("contains static methods only");
    }

    /**
     * Creates a validator that is only valid if all {@code validators} are valid.
     *
     * @throws IllegalArgumentException if any element of {@code validators} is an instance of a
     * class that is not provided by the Android System.
     */
    @NonNull
    public static Validator and(@NonNull Validator...validators) {
        return new RequiredValidators(getInternalValidators(validators));
    }

    /**
     * Creates a validator that is valid if any of the {@code validators} is valid.
     *
     * @throws IllegalArgumentException if any element of {@code validators} is an instance of a
     * class that is not provided by the Android System.
     */
    @NonNull
    public static Validator or(@NonNull Validator...validators) {
        return new OptionalValidators(getInternalValidators(validators));
    }

    private static InternalValidator[] getInternalValidators(Validator[] validators) {
        Preconditions.checkArrayElementsNotNull(validators, "validators");

        final InternalValidator[] internals = new InternalValidator[validators.length];

        for (int i = 0; i < validators.length; i++) {
            Preconditions.checkArgument((validators[i] instanceof InternalValidator),
                    "element " + i + " not provided by Android System: " + validators[i]);
            internals[i] = (InternalValidator) validators[i];
        }
        return internals;
    }
}
