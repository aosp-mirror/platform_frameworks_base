/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.content.om;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;

/**
 * A subset of {@link OverlayInfo} fields that when changed cause the overlay's settings to be
 * completely reinitialized.
 *
 * @hide
 */
public interface CriticalOverlayInfo {

    /**
     * @return the package name of the overlay.
     */
    @NonNull
    String getPackageName();

    /**
     * @return the unique name of the overlay within its containing package.
     */
    @Nullable
    String getOverlayName();

    /**
     * @return the target package name of the overlay.
     */
    @NonNull
    String getTargetPackageName();

    /**
     * @return the name of the target overlayable declaration.
     */
    @Nullable
    String getTargetOverlayableName();

    /**
     * @return an identifier representing the current overlay.
     */
    @NonNull
    OverlayIdentifier getOverlayIdentifier();

    /**
     * Returns whether or not the overlay is a {@link FabricatedOverlay}.
     */
    boolean isFabricated();
}
