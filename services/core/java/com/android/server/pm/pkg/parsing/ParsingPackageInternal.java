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

package com.android.server.pm.pkg.parsing;

import android.annotation.Nullable;
import android.content.pm.PackageInfo;

import com.android.internal.R;

/**
 * Methods which would've been a part of {@link PkgWithoutStatePackageInfo} or {@link
 * PkgWithoutStateAppInfo}, but are removed/deprecated.
 * <p>
 * This is different from {@link ParsingPackageHidden}. The methods in that interface cannot be
 * accessed by anyone except the parsing utilities, whereas the methods in this interface are valid
 * and can be accessed by any internal caller that needs it.
 *
 * @hide
 */
interface ParsingPackageInternal {

    /**
     * @see PackageInfo#overlayCategory
     * @see R.styleable#AndroidManifestResourceOverlay_category
     */
    @Nullable
    String getOverlayCategory();

    /**
     * @see PackageInfo#overlayPriority
     * @see R.styleable#AndroidManifestResourceOverlay_priority
     */
    int getOverlayPriority();

    /**
     * @see PackageInfo#overlayTarget
     * @see R.styleable#AndroidManifestResourceOverlay_targetPackage
     */
    @Nullable
    String getOverlayTarget();

    /**
     * @see PackageInfo#targetOverlayableName
     * @see R.styleable#AndroidManifestResourceOverlay_targetName
     */
    @Nullable
    String getOverlayTargetOverlayableName();

    /**
     * @see PackageInfo#mOverlayIsStatic
     */
    boolean isOverlayIsStatic();
}
