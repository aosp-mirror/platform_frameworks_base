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

package com.android.server.pm.pkg;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.processor.immutability.Immutable;

import com.android.internal.R;

import java.util.List;

/**
 * Representation of the parsed state of a single split APK. Note this includes the base.apk.
 *
 * The information here is very minimal, mostly used for loading a specific class, and most
 * important state is collected across all splits for a package into the parent
 * {@link AndroidPackage} values.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
@Immutable
public interface AndroidPackageSplit {

    /**
     * @return The unique name given to the split, or null if this is the base.
     */
    @Nullable
    String getName();

    /**
     * @return Physical location of the split APK on disk, pointing to a single file with the .apk
     * extension.
     */
    @NonNull
    String getPath();

    /**
     * @see R.styleable#AndroidManifest_revisionCode
     */
    int getRevisionCode();

    /**
     * @see R.styleable#AndroidManifestApplication_hasCode
     */
    boolean isHasCode();

    /**
     * @see R.styleable#AndroidManifestApplication_classLoader
     */
    @Nullable
    String getClassLoaderName();

    /**
     * @see R.styleable#AndroidManifestUsesSplit
     */
    @NonNull
    List<AndroidPackageSplit> getDependencies();
}
