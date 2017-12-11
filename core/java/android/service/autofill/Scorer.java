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

import android.annotation.TestApi;

/**
 * Helper class used to calculate a score.
 *
 * <p>Typically used to calculate the field classification score between an actual
 * {@link android.view.autofill.AutofillValue}  filled by the user and the expected value predicted
 * by an autofill service.
 *
 * TODO(b/67867469):
 * - improve javadoc
 * - unhide / remove testApi
 * @hide
 */
@TestApi
public interface Scorer {

}
