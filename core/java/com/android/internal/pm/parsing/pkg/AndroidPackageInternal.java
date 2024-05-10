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

package com.android.internal.pm.parsing.pkg;

import android.annotation.NonNull;

import com.android.internal.content.om.OverlayConfig;
import com.android.server.pm.pkg.AndroidPackage;

/**
 * The last state of a package during parsing/install before it is available in {@link
 * com.android.server.pm.PackageManagerService#mPackages}.
 * <p>
 * It is the responsibility of the caller to understand what data is available at what step of the
 * parsing or install process.
 * <p>
 *
 * @hide
 */
public interface AndroidPackageInternal extends AndroidPackage,
        OverlayConfig.PackageProvider.Package {
    @NonNull
    String[] getUsesLibrariesSorted();

    @NonNull
    String[] getUsesOptionalLibrariesSorted();

    @NonNull
    String[] getUsesSdkLibrariesSorted();

    @NonNull
    String[] getUsesStaticLibrariesSorted();
}
