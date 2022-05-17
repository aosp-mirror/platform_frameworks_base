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
                R.bool.config_cecRoutingControl_userConfigurable);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecRoutingControlEnabled_allowed);
        doReturn(false).when(resources).getBoolean(
                R.bool.config_cecRoutingControlEnabled_default);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecRoutingControlDisabled_allowed);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecRoutingControlDisabled_default);

        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecPowerControlMode_userConfigurable);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecPowerControlModeTv_allowed);
        doReturn(false).when(resources).getBoolean(
                R.bool.config_cecPowerControlModeTv_default);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecPowerControlModeTvAndAudioSystem_allowed);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecPowerControlModeTvAndAudioSystem_default);
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
                R.bool.config_cecSystemAudioControl_userConfigurable);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecSystemAudioControlEnabled_allowed);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecSystemAudioControlEnabled_default);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecSystemAudioControlDisabled_allowed);
        doReturn(false).when(resources).getBoolean(
                R.bool.config_cecSystemAudioControlDisabled_default);

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
                R.bool.config_cecSetMenuLanguage_userConfigurable);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecSetMenuLanguageEnabled_allowed);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecSetMenuLanguageEnabled_default);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecSetMenuLanguageDisabled_allowed);
        doReturn(false).when(resources).getBoolean(
                R.bool.config_cecSetMenuLanguageDisabled_default);

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

        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadLpcm_userConfigurable);
        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadLpcmEnabled_allowed);
        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadLpcmEnabled_default);
        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadLpcmDisabled_allowed);
        doReturn(false).when(resources).getBoolean(R.bool.config_cecQuerySadLpcmDisabled_default);

        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadDd_userConfigurable);
        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadDdEnabled_allowed);
        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadDdEnabled_default);
        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadDdDisabled_allowed);
        doReturn(false).when(resources).getBoolean(R.bool.config_cecQuerySadDdDisabled_default);

        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadMpeg1_userConfigurable);
        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadMpeg1Enabled_allowed);
        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadMpeg1Enabled_default);
        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadMpeg1Disabled_allowed);
        doReturn(false).when(resources).getBoolean(R.bool.config_cecQuerySadMpeg1Disabled_default);

        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadMp3_userConfigurable);
        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadMp3Enabled_allowed);
        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadMp3Enabled_default);
        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadMp3Disabled_allowed);
        doReturn(false).when(resources).getBoolean(R.bool.config_cecQuerySadMp3Disabled_default);

        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadMpeg2_userConfigurable);
        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadMpeg2Enabled_allowed);
        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadMpeg2Enabled_default);
        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadMpeg2Disabled_allowed);
        doReturn(false).when(resources).getBoolean(R.bool.config_cecQuerySadMpeg2Disabled_default);

        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadAac_userConfigurable);
        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadAacEnabled_allowed);
        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadAacEnabled_default);
        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadAacDisabled_allowed);
        doReturn(false).when(resources).getBoolean(R.bool.config_cecQuerySadAacDisabled_default);

        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadDts_userConfigurable);
        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadDtsEnabled_allowed);
        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadDtsEnabled_default);
        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadDtsDisabled_allowed);
        doReturn(false).when(resources).getBoolean(R.bool.config_cecQuerySadDtsDisabled_default);

        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadAtrac_userConfigurable);
        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadAtracEnabled_allowed);
        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadAtracEnabled_default);
        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadAtracDisabled_allowed);
        doReturn(false).when(resources).getBoolean(R.bool.config_cecQuerySadAtracDisabled_default);

        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecQuerySadOnebitaudio_userConfigurable);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecQuerySadOnebitaudioEnabled_allowed);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecQuerySadOnebitaudioEnabled_default);
        doReturn(true).when(resources).getBoolean(
                R.bool.config_cecQuerySadOnebitaudioDisabled_allowed);
        doReturn(false).when(resources).getBoolean(
                R.bool.config_cecQuerySadOnebitaudioDisabled_default);

        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadDdp_userConfigurable);
        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadDdpEnabled_allowed);
        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadDdpEnabled_default);
        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadDdpDisabled_allowed);
        doReturn(false).when(resources).getBoolean(R.bool.config_cecQuerySadDdpDisabled_default);

        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadDtshd_userConfigurable);
        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadDtshdEnabled_allowed);
        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadDtshdEnabled_default);
        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadDtshdDisabled_allowed);
        doReturn(false).when(resources).getBoolean(R.bool.config_cecQuerySadDtshdDisabled_default);

        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadTruehd_userConfigurable);
        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadTruehdEnabled_allowed);
        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadTruehdEnabled_default);
        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadTruehdDisabled_allowed);
        doReturn(false).when(resources).getBoolean(R.bool.config_cecQuerySadTruehdDisabled_default);

        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadDst_userConfigurable);
        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadDstEnabled_allowed);
        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadDstEnabled_default);
        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadDstDisabled_allowed);
        doReturn(false).when(resources).getBoolean(R.bool.config_cecQuerySadDstDisabled_default);

        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadWmapro_userConfigurable);
        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadWmaproEnabled_allowed);
        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadWmaproEnabled_default);
        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadWmaproDisabled_allowed);
        doReturn(false).when(resources).getBoolean(R.bool.config_cecQuerySadWmaproDisabled_default);

        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadMax_userConfigurable);
        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadMaxEnabled_allowed);
        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadMaxEnabled_default);
        doReturn(true).when(resources).getBoolean(R.bool.config_cecQuerySadMaxDisabled_allowed);
        doReturn(false).when(resources).getBoolean(R.bool.config_cecQuerySadMaxDisabled_default);

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
