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

package com.android.internal.pm.pkg.component;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.pm.PackageManager.Property;
import android.os.Bundle;

import java.util.List;
import java.util.Map;

/** @hide */
//@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
public interface ParsedComponent {

    int getBanner();

    @NonNull
    ComponentName getComponentName();

    int getDescriptionRes();

    int getFlags();

    int getIcon();

    @NonNull
    List<ParsedIntentInfo> getIntents();

    int getLabelRes();

    int getLogo();

    @NonNull
    Bundle getMetaData();

    @NonNull
    String getName();

    @Nullable
    CharSequence getNonLocalizedLabel();

    @NonNull
    String getPackageName();

    @NonNull
    Map<String, Property> getProperties();
}
