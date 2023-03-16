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

package com.android.systemui.dreams.complication;

import static com.android.systemui.dreams.dagger.DreamModule.DREAM_PRETEXT_MONITOR;

import android.database.ContentObserver;
import android.os.UserHandle;
import android.provider.Settings;

import com.android.settingslib.dream.DreamBackend;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dreams.DreamOverlayStateController;
import com.android.systemui.shared.condition.Monitor;
import com.android.systemui.util.condition.ConditionalCoreStartable;
import com.android.systemui.util.settings.SecureSettings;

import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * {@link ComplicationTypesUpdater} observes the state of available complication types set by the
 * user, and pushes updates to {@link DreamOverlayStateController}.
 */
@SysUISingleton
public class ComplicationTypesUpdater extends ConditionalCoreStartable {
    private final DreamBackend mDreamBackend;
    private final Executor mExecutor;
    private final SecureSettings mSecureSettings;

    private final DreamOverlayStateController mDreamOverlayStateController;

    @Inject
    ComplicationTypesUpdater(
            DreamBackend dreamBackend,
            @Main Executor executor,
            SecureSettings secureSettings,
            DreamOverlayStateController dreamOverlayStateController,
            @Named(DREAM_PRETEXT_MONITOR) Monitor monitor) {
        super(monitor);
        mDreamBackend = dreamBackend;
        mExecutor = executor;
        mSecureSettings = secureSettings;
        mDreamOverlayStateController = dreamOverlayStateController;
    }

    @Override
    public void onStart() {
        final ContentObserver settingsObserver = new ContentObserver(null /*handler*/) {
            @Override
            public void onChange(boolean selfChange) {
                mExecutor.execute(() -> mDreamOverlayStateController.setAvailableComplicationTypes(
                        getAvailableComplicationTypes()));
            }
        };

        mSecureSettings.registerContentObserverForUser(
                Settings.Secure.SCREENSAVER_COMPLICATIONS_ENABLED,
                settingsObserver,
                UserHandle.myUserId());
        mSecureSettings.registerContentObserverForUser(
                Settings.Secure.SCREENSAVER_HOME_CONTROLS_ENABLED,
                settingsObserver,
                UserHandle.myUserId());
        settingsObserver.onChange(false);
    }

    /**
     * Returns complication types that are currently available by user setting.
     */
    @Complication.ComplicationType
    private int getAvailableComplicationTypes() {
        return ComplicationUtils.convertComplicationTypes(mDreamBackend.getEnabledComplications());
    }
}
