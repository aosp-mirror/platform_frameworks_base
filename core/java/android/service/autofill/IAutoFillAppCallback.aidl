/*
 * Copyright (C) 2016 The Android Open Source Project
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

import java.util.List;

import android.view.autofill.Dataset;

/**
 * Object running in the application process and responsible for auto-filling it.
 *
 * @hide
 */
// TODO(b/33197203): rename IAutoFillAppSession
oneway interface IAutoFillAppCallback {
    /**
      * Auto-fills the activity with the contents of a dataset.
      */
    void autoFill(in Dataset dataset);
}
