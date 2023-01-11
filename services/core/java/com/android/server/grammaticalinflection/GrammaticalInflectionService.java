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

package com.android.server.grammaticalinflection;

import static android.content.res.Configuration.GRAMMATICAL_GENDER_NOT_SPECIFIED;

import android.app.IGrammaticalInflectionManager;
import android.content.Context;
import android.os.IBinder;
import android.os.SystemProperties;

import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.wm.ActivityTaskManagerInternal;

/**
 * The implementation of IGrammaticalInflectionManager.aidl.
 *
 * <p>This service is API entry point for storing app-specific grammatical inflection.
 */
public class GrammaticalInflectionService extends SystemService {

    private final ActivityTaskManagerInternal mActivityTaskManagerInternal;
    private static final String GRAMMATICAL_INFLECTION_ENABLED =
            "i18n.grammatical_Inflection.enabled";

    /**
     * Initializes the system service.
     * <p>
     * Subclasses must define a single argument constructor that accepts the context
     * and passes it to super.
     * </p>
     *
     * @param context The system server context.
     *
     * @hide
     */
    public GrammaticalInflectionService(Context context) {
        super(context);
        mActivityTaskManagerInternal = LocalServices.getService(ActivityTaskManagerInternal.class);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.GRAMMATICAL_INFLECTION_SERVICE, mService);
    }

    private final IBinder mService = new IGrammaticalInflectionManager.Stub() {
        @Override
        public void setRequestedApplicationGrammaticalGender(
                String appPackageName, int userId, int gender) {
            GrammaticalInflectionService.this.setRequestedApplicationGrammaticalGender(
                    appPackageName, userId, gender);
        }
    };

    private void setRequestedApplicationGrammaticalGender(
            String appPackageName, int userId, int gender) {
        if (!SystemProperties.getBoolean(GRAMMATICAL_INFLECTION_ENABLED, true)) {
            return;
        }

        final ActivityTaskManagerInternal.PackageConfigurationUpdater updater =
                mActivityTaskManagerInternal.createPackageConfigurationUpdater(appPackageName,
                        userId);

        updater.setGrammaticalGender(gender).commit();
    }
}
