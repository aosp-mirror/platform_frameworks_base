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

package com.android.systemui.qs.dagger;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.statusbar.FeatureFlags;
import com.android.systemui.util.settings.SecureSettings;

import javax.inject.Named;

import dagger.Module;
import dagger.Provides;

@Module
public interface QSFlagsModule {
    String QS_LABELS_FLAG = "qs_labels_flag";
    String QS_SIDE_LABELS = "qs_side_labels";

    @Provides
    @SysUISingleton
    @Named(QS_LABELS_FLAG)
    static boolean provideQSFlag(FeatureFlags featureFlags) {
        return featureFlags.isQSLabelsEnabled();
    }

    @Provides
    @SysUISingleton
    @Named(QS_SIDE_LABELS)
    static boolean provideSideLabels(SecureSettings secureSettings,
            @Named(QS_LABELS_FLAG) boolean qsLabels) {
        return qsLabels && secureSettings.getInt("sysui_side_labels", 0) != 0;
    }
}
