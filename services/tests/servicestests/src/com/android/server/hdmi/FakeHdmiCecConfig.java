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

package com.android.server.hdmi;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import android.annotation.NonNull;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;

import com.android.internal.R;

import java.util.HashMap;

/**
 * Fake class which stubs default system configuration with user-configurable
 * settings (useful for testing).
 */
final class FakeHdmiCecConfig extends HdmiCecConfig {
    private static final String TAG = "FakeHdmiCecConfig";

    private final HashMap<String, String> mSettings = new HashMap<>();

    public static Context buildContext(Context context) {
        Context contextSpy = spy(new ContextWrapper(context));
        doReturn(buildResources(context)).when(contextSpy).getResources();
        return contextSpy;
    }

    private static Resources buildResources(Context context) {
        Resources resources = spy(context.getResources());

        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecHdmiCecEnabled_userConfigurable);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecHdmiCecControlEnabled_allowed);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecHdmiCecControlEnabled_default);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecHdmiCecControlDisabled_allowed);
        doReturn(false).when(resources).getBoolean(
                R.bool.config_cecHdmiCecControlDisabled_default);

        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecHdmiCecVersion_userConfigurable);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecHdmiCecVersion14b_allowed);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecHdmiCecVersion14b_default);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecHdmiCecVersion20_allowed);
        doReturn(false).when(resources).getBoolean(
                R.bool.config_cecHdmiCecVersion20_default);

        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecPowerControlMode_userConfigurable);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecPowerControlModeTv_allowed);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecPowerControlModeTv_default);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecPowerControlModeBroadcast_allowed);
        doReturn(false).when(resources).getBoolean(
                R.bool.config_cecPowerControlModeBroadcast_default);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecPowerControlModeNone_allowed);
        doReturn(false).when(resources).getBoolean(
                R.bool.config_cecPowerControlModeNone_default);

        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecPowerStateChangeOnActiveSourceLost_userConfigurable);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecPowerStateChangeOnActiveSourceLostNone_allowed);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecPowerStateChangeOnActiveSourceLostNone_default);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecPowerStateChangeOnActiveSourceLostStandbyNow_allowed);
        doReturn(false).when(resources).getBoolean(
                R.bool.config_cecPowerStateChangeOnActiveSourceLostStandbyNow_default);

        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecSystemAudioModeMuting_userConfigurable);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecSystemAudioModeMutingEnabled_allowed);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecSystemAudioModeMutingEnabled_default);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecSystemAudioModeMutingDisabled_allowed);
        doReturn(false).when(resources).getBoolean(
                R.bool.config_cecSystemAudioModeMutingDisabled_default);

        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecVolumeControlMode_userConfigurable);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecVolumeControlModeEnabled_allowed);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecVolumeControlModeEnabled_default);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecVolumeControlModeDisabled_allowed);
        doReturn(false).when(resources).getBoolean(
                R.bool.config_cecVolumeControlModeDisabled_default);

        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecTvWakeOnOneTouchPlay_userConfigurable);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecTvWakeOnOneTouchPlayEnabled_allowed);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecTvWakeOnOneTouchPlayEnabled_default);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecTvWakeOnOneTouchPlayDisabled_allowed);
        doReturn(false).when(resources).getBoolean(
                R.bool.config_cecTvWakeOnOneTouchPlayDisabled_default);

        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecTvSendStandbyOnSleep_userConfigurable);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecTvSendStandbyOnSleepEnabled_allowed);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecTvSendStandbyOnSleepEnabled_default);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecTvSendStandbyOnSleepDisabled_allowed);
        doReturn(false).when(resources).getBoolean(
                R.bool.config_cecTvSendStandbyOnSleepDisabled_default);

        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecRcProfileTv_userConfigurable);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecRcProfileTvNone_allowed);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecRcProfileTvNone_default);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecRcProfileTvOne_allowed);
        doReturn(false).when(resources).getBoolean(
                R.bool.config_cecRcProfileTvOne_default);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecRcProfileTvTwo_allowed);
        doReturn(false).when(resources).getBoolean(
                R.bool.config_cecRcProfileTvTwo_default);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecRcProfileTvThree_allowed);
        doReturn(false).when(resources).getBoolean(
                R.bool.config_cecRcProfileTvThree_default);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecRcProfileTvFour_allowed);
        doReturn(false).when(resources).getBoolean(
                R.bool.config_cecRcProfileTvFour_default);

        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecRcProfileSourceRootMenu_userConfigurable);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecRcProfileSourceRootMenuHandled_allowed);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecRcProfileSourceRootMenuHandled_default);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecRcProfileSourceRootMenuNotHandled_allowed);
        doReturn(false).when(resources).getBoolean(
                R.bool.config_cecRcProfileSourceRootMenuNotHandled_default);

        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecRcProfileSourceSetupMenu_userConfigurable);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecRcProfileSourceSetupMenuHandled_allowed);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecRcProfileSourceSetupMenuHandled_default);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecRcProfileSourceSetupMenuNotHandled_allowed);
        doReturn(false).when(resources).getBoolean(
                R.bool.config_cecRcProfileSourceSetupMenuNotHandled_default);

        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecRcProfileSourceContentsMenu_userConfigurable);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecRcProfileSourceContentsMenuHandled_allowed);
        doReturn(false).when(resources).getBoolean(
                R.bool.config_cecRcProfileSourceContentsMenuHandled_default);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecRcProfileSourceContentsMenuNotHandled_allowed);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecRcProfileSourceContentsMenuNotHandled_default);

        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecRcProfileSourceTopMenu_userConfigurable);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecRcProfileSourceTopMenuHandled_allowed);
        doReturn(false).when(resources).getBoolean(
                R.bool.config_cecRcProfileSourceTopMenuHandled_default);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecRcProfileSourceTopMenuNotHandled_allowed);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecRcProfileSourceTopMenuNotHandled_default);

        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecRcProfileSourceMediaContextSensitiveMenu_userConfigurable);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecRcProfileSourceMediaContextSensitiveMenuHandled_allowed);
        doReturn(false).when(resources).getBoolean(
                R.bool.config_cecRcProfileSourceMediaContextSensitiveMenuHandled_default);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecRcProfileSourceMediaContextSensitiveMenuNotHandled_allowed);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecRcProfileSourceMediaContextSensitiveMenuNotHandled_default);

        return resources;
    }

    FakeHdmiCecConfig(@NonNull Context context) {
        super(buildContext(context), new StorageAdapter(context));
    }

    @Override
    protected String retrieveValue(@NonNull Setting setting, @NonNull String defaultValue) {
        return mSettings.getOrDefault(setting.getName(), defaultValue);
    }

    @Override
    protected void storeValue(@NonNull Setting setting, @NonNull String value) {
        mSettings.put(setting.getName(), value);
        notifySettingChanged(setting);
    }
}
