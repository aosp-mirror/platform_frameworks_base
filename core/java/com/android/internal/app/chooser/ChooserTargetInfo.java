/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.internal.app.chooser;

import android.service.chooser.ChooserTarget;
import android.text.TextUtils;

/**
 * A TargetInfo for Direct Share. Includes a {@link ChooserTarget} representing the
 * Direct Share deep link into an application.
 */
public interface ChooserTargetInfo extends TargetInfo {
    float getModifiedScore();

    ChooserTarget getChooserTarget();

    /**
     * Do not label as 'equals', since this doesn't quite work
     * as intended with java 8.
     */
    default boolean isSimilar(ChooserTargetInfo other) {
        if (other == null) return false;

        ChooserTarget ct1 = getChooserTarget();
        ChooserTarget ct2 = other.getChooserTarget();

        // If either is null, there is not enough info to make an informed decision
        // about equality, so just exit
        if (ct1 == null || ct2 == null) return false;

        if (ct1.getComponentName().equals(ct2.getComponentName())
                && TextUtils.equals(getDisplayLabel(), other.getDisplayLabel())
                && TextUtils.equals(getExtendedInfo(), other.getExtendedInfo())) {
            return true;
        }

        return false;
    }
}
