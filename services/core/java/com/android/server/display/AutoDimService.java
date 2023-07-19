/*
 * Copyright (C) 2023 Yet Another AOSP Project
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
package com.android.server.display;

import static android.provider.Settings.Secure.REDUCE_BRIGHT_COLORS_ACTIVATED;
import static android.provider.Settings.Secure.EXTRA_DIM_AUTO_MODE;
import static android.provider.Settings.Secure.EXTRA_DIM_AUTO_TIME;
import static com.android.internal.util.xtended.AutoSettingConsts.MODE_DISABLED;

import android.content.Context;
import android.hardware.display.ColorDisplayManager;
import android.net.Uri;
import android.os.UserHandle;
import android.provider.Settings;

import com.android.server.AutoSettingService;

import java.util.ArrayList;
import java.util.List;

public class AutoDimService extends AutoSettingService {

    private static final String TAG = "AutoDimService";
    private static final List<Uri> LISTEN_URIS = new ArrayList<>(List.of(
        Settings.Secure.getUriFor(EXTRA_DIM_AUTO_MODE),
        Settings.Secure.getUriFor(EXTRA_DIM_AUTO_TIME),
        Settings.Secure.getUriFor(REDUCE_BRIGHT_COLORS_ACTIVATED)
    ));

    private static final int ON = 1;
    private static final int OFF = 0;

    public AutoDimService(Context context) {
        super(context);
    }

    @Override
    public void publish() {
        publishLocalService(AutoSettingService.class, this);
    }

    @Override
    public List<Uri> getObserveUris() {
        return LISTEN_URIS;
    }

    @Override
    public String getMainSetting() {
        return REDUCE_BRIGHT_COLORS_ACTIVATED;
    }

    @Override
    public int getModeValue() {
        return Settings.Secure.getIntForUser(mContext.getContentResolver(),
                EXTRA_DIM_AUTO_MODE, MODE_DISABLED, UserHandle.USER_CURRENT);
    }

    @Override
    public String getTimeValue() {
        return Settings.Secure.getStringForUser(mContext.getContentResolver(),
                EXTRA_DIM_AUTO_TIME, UserHandle.USER_CURRENT);
    }

    @Override
    public boolean getIsActive() {
        return Settings.Secure.getIntForUser(mContext.getContentResolver(),
                REDUCE_BRIGHT_COLORS_ACTIVATED, OFF, UserHandle.USER_CURRENT) == ON;
    }

    @Override
    public void setActive(boolean active) {
        Settings.Secure.putIntForUser(mContext.getContentResolver(),
                REDUCE_BRIGHT_COLORS_ACTIVATED, active ? ON : OFF, UserHandle.USER_CURRENT);
    }

    @Override
    public String getTag() {
        return TAG;
    }
}
