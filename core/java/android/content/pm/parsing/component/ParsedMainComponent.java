/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.content.pm.parsing.component;

import android.annotation.Nullable;

/** @hide */
public interface ParsedMainComponent extends ParsedComponent {

    @Nullable
    String[] getAttributionTags();

    /**
     * A main component's name is a class name. This makes code slightly more readable.
     */
    String getClassName();

    boolean isDirectBootAware();

    boolean isEnabled();

    boolean isExported();

    int getOrder();

    @Nullable
    String getProcessName();

    @Nullable
    String getSplitName();
}
