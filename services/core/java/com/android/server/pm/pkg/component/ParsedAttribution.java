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

package com.android.server.pm.pkg.component;

import android.annotation.NonNull;
import android.annotation.StringRes;

import java.util.List;

/**
 * A {@link android.R.styleable#AndroidManifestAttribution &lt;attribution&gt;} tag parsed from the
 * manifest.
 *
 * @hide
 */
//@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
public interface ParsedAttribution {

    /**
     * Maximum length of attribution tag
     * @hide
     */
    int MAX_ATTRIBUTION_TAG_LEN = 50;

    /**
     * Ids of previously declared attributions this attribution inherits from
     */
    @NonNull List<String> getInheritFrom();

    /**
     * User visible label for the attribution
     */
    @StringRes int getLabel();

    /**
     * Tag of the attribution
     */
    @NonNull String getTag();
}
