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

package com.android.server.pm.parsing;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.Pair;

import com.android.internal.pm.pkg.component.ParsedComponent;
import com.android.server.pm.pkg.PackageStateInternal;

/**
 * For exposing internal fields to the rest of the server, enforcing that any overridden state from
 * a {@link com.android.server.pm.PackageSetting} is applied.
 *
 * TODO(chiuwinson): The fields on ParsedComponent are not actually hidden. Will need to find a
 *   way to enforce the mechanism now that they exist in core instead of server. Can't rely on
 *   package-private.
 *
 * @hide
 */
public class ParsedComponentStateUtils {

    @NonNull
    public static Pair<CharSequence, Integer> getNonLocalizedLabelAndIcon(ParsedComponent component,
            @Nullable PackageStateInternal pkgSetting, int userId) {
        CharSequence label = component.getNonLocalizedLabel();
        int icon = component.getIcon();

        Pair<String, Integer> overrideLabelIcon = pkgSetting == null ? null :
                pkgSetting.getUserStateOrDefault(userId)
                        .getOverrideLabelIconForComponent(component.getComponentName());
        if (overrideLabelIcon != null) {
            if (overrideLabelIcon.first != null) {
                label = overrideLabelIcon.first;
            }
            if (overrideLabelIcon.second != null) {
                icon = overrideLabelIcon.second;
            }
        }

        return Pair.create(label, icon);
    }
}
