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
import android.annotation.SuppressLint;
import android.content.pm.ApplicationInfo;
import android.util.ArrayMap;

import java.util.Set;

/** @hide */
//@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
public interface ParsedProcess {

    @NonNull
    Set<String> getDeniedPermissions();

    @ApplicationInfo.GwpAsanMode
    int getGwpAsanMode();

    @ApplicationInfo.MemtagMode
    int getMemtagMode();

    @NonNull
    String getName();

    /**
     * The app class names in this (potentially shared) process, from a package name to
     * the application class name.
     * It's a map, because in shared processes, different packages can have different application
     * classes.
     */
    @SuppressLint("ConcreteCollection")
    @NonNull
    ArrayMap<String, String> getAppClassNamesByPackage();

    @ApplicationInfo.NativeHeapZeroInitialized
    int getNativeHeapZeroInitialized();
}
