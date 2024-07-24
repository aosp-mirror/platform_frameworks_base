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

package com.android.systemui.util.settings;

import com.android.systemui.util.settings.repository.UserAwareSecureSettingsRepository;
import com.android.systemui.util.settings.repository.UserAwareSecureSettingsRepositoryImpl;

import dagger.Binds;
import dagger.Module;

/**
 * Dagger Module for classes within com.android.systemui.util.settings.
 */
@Module
public interface SettingsUtilModule {

    /** Bind SecureSettingsImpl to SecureSettings. */
    @Binds
    SecureSettings bindsSecureSettings(SecureSettingsImpl impl);

    /** Bind SystemSettingsImpl to SystemSettings. */
    @Binds
    SystemSettings bindsSystemSettings(SystemSettingsImpl impl);

    /** Bind GlobalSettingsImpl to GlobalSettings. */
    @Binds
    GlobalSettings bindsGlobalSettings(GlobalSettingsImpl impl);

    /** Bind UserAwareSecureSettingsRepositoryImpl to UserAwareSecureSettingsRepository. */
    @Binds
    UserAwareSecureSettingsRepository bindsUserAwareSecureSettingsRepository(
            UserAwareSecureSettingsRepositoryImpl impl);
}
