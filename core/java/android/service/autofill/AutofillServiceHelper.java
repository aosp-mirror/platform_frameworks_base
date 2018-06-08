/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.annotation.Nullable;
import android.view.autofill.AutofillId;

import com.android.internal.util.Preconditions;

/** @hide */
final class AutofillServiceHelper {

    static AutofillId[] assertValid(@Nullable AutofillId[] ids) {
        Preconditions.checkArgument(ids != null && ids.length > 0, "must have at least one id");
        // Can't use Preconditions.checkArrayElementsNotNull() because it throws NPE instead of IAE
        for (int i = 0; i < ids.length; ++i) {
            if (ids[i] == null) {
                throw new IllegalArgumentException("ids[" + i + "] must not be null");
            }
        }
        return ids;
    }

    private AutofillServiceHelper() {
        throw new UnsupportedOperationException("contains static members only");
    }
}
