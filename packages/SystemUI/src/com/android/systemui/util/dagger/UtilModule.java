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

package com.android.systemui.util.dagger;

import com.android.systemui.util.RingerModeTracker;
import com.android.systemui.util.RingerModeTrackerImpl;
import com.android.systemui.util.animation.data.repository.AnimationStatusRepository;
import com.android.systemui.util.animation.data.repository.AnimationStatusRepositoryImpl;
import com.android.systemui.util.wrapper.UtilWrapperModule;

import dagger.Binds;
import dagger.Module;

/** Dagger Module for code in the util package. */
@Module(includes = {
                UtilWrapperModule.class
        })
public interface UtilModule {
    /** */
    @Binds
    RingerModeTracker provideRingerModeTracker(RingerModeTrackerImpl ringerModeTrackerImpl);

    @Binds
    AnimationStatusRepository provideAnimationStatus(
            AnimationStatusRepositoryImpl ringerModeTrackerImpl);
}
