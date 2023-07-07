/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.view.stylus;

import android.content.ComponentName;
import android.inputmethodservice.InputMethodService;

public class HandwritingImeService extends InputMethodService {
    private static final String PACKAGE_NAME = "com.android.frameworks.coretests";

    private static ComponentName getComponentName() {
        return new ComponentName(PACKAGE_NAME, HandwritingImeService.class.getName());
    }

    static String getImeId() {
        return getComponentName().flattenToShortString();
    }
}
