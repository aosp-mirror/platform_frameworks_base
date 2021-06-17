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

package com.android.keyguard.dagger;

import com.android.keyguard.KeyguardClockSwitch;
import com.android.keyguard.KeyguardSliceView;
import com.android.keyguard.KeyguardStatusView;
import com.android.systemui.R;

import dagger.Module;
import dagger.Provides;

/** Dagger module for {@link KeyguardStatusViewComponent}. */
@Module
public abstract class KeyguardStatusViewModule {
    @Provides
    static KeyguardClockSwitch getKeyguardClockSwitch(KeyguardStatusView keyguardPresentation) {
        return keyguardPresentation.findViewById(R.id.keyguard_clock_container);
    }

    @Provides
    static KeyguardSliceView getKeyguardSliceView(KeyguardClockSwitch keyguardClockSwitch) {
        return keyguardClockSwitch.findViewById(R.id.keyguard_status_area);
    }
}
