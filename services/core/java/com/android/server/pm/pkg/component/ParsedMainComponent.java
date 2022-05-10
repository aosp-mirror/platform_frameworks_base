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

package com.android.server.pm.pkg.component;

import android.annotation.NonNull;
import android.annotation.Nullable;

/** @hide */
//@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
public interface ParsedMainComponent extends ParsedComponent {

    @NonNull
    String[] getAttributionTags();

    /**
     * A main component's name is a class name. This makes code slightly more readable.
     */
    @NonNull
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
