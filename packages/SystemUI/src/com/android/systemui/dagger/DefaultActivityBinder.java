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

package com.android.systemui.dagger;

import android.app.Activity;

import com.android.systemui.ForegroundServicesDialog;
import com.android.systemui.keyguard.WorkLockActivity;
import com.android.systemui.people.PeopleSpaceActivity;
import com.android.systemui.people.widget.LaunchConversationActivity;
import com.android.systemui.screenrecord.ScreenRecordDialog;
import com.android.systemui.screenshot.LongScreenshotActivity;
import com.android.systemui.settings.brightness.BrightnessDialog;
import com.android.systemui.statusbar.tv.notifications.TvNotificationPanelActivity;
import com.android.systemui.tuner.TunerActivity;
import com.android.systemui.usb.UsbDebuggingActivity;
import com.android.systemui.usb.UsbDebuggingSecondaryUserActivity;
import com.android.systemui.user.CreateUserActivity;

import dagger.Binds;
import dagger.Module;
import dagger.multibindings.ClassKey;
import dagger.multibindings.IntoMap;

/**
 * Activities that are injectable should go here.
 */
@Module
public abstract class DefaultActivityBinder {
    /** Inject into TunerActivity. */
    @Binds
    @IntoMap
    @ClassKey(TunerActivity.class)
    public abstract Activity bindTunerActivity(TunerActivity activity);

    /** Inject into ForegroundServicesDialog. */
    @Binds
    @IntoMap
    @ClassKey(ForegroundServicesDialog.class)
    public abstract Activity bindForegroundServicesDialog(ForegroundServicesDialog activity);

    /** Inject into WorkLockActivity. */
    @Binds
    @IntoMap
    @ClassKey(WorkLockActivity.class)
    public abstract Activity bindWorkLockActivity(WorkLockActivity activity);

    /** Inject into BrightnessDialog. */
    @Binds
    @IntoMap
    @ClassKey(BrightnessDialog.class)
    public abstract Activity bindBrightnessDialog(BrightnessDialog activity);

    /** Inject into ScreenRecordDialog */
    @Binds
    @IntoMap
    @ClassKey(ScreenRecordDialog.class)
    public abstract Activity bindScreenRecordDialog(ScreenRecordDialog activity);

    /** Inject into UsbDebuggingActivity. */
    @Binds
    @IntoMap
    @ClassKey(UsbDebuggingActivity.class)
    public abstract Activity bindUsbDebuggingActivity(UsbDebuggingActivity activity);

    /** Inject into UsbDebuggingSecondaryUserActivity. */
    @Binds
    @IntoMap
    @ClassKey(UsbDebuggingSecondaryUserActivity.class)
    public abstract Activity bindUsbDebuggingSecondaryUserActivity(
            UsbDebuggingSecondaryUserActivity activity);

    /** Inject into CreateUserActivity. */
    @Binds
    @IntoMap
    @ClassKey(CreateUserActivity.class)
    public abstract Activity bindCreateUserActivity(CreateUserActivity activity);

    /** Inject into TvNotificationPanelActivity. */
    @Binds
    @IntoMap
    @ClassKey(TvNotificationPanelActivity.class)
    public abstract Activity bindTvNotificationPanelActivity(TvNotificationPanelActivity activity);

    /** Inject into PeopleSpaceActivity. */
    @Binds
    @IntoMap
    @ClassKey(PeopleSpaceActivity.class)
    public abstract Activity bindPeopleSpaceActivity(PeopleSpaceActivity activity);

    /** Inject into LongScreenshotActivity. */
    @Binds
    @IntoMap
    @ClassKey(LongScreenshotActivity.class)
    public abstract Activity bindLongScreenshotActivity(LongScreenshotActivity activity);

    /** Inject into LaunchConversationActivity. */
    @Binds
    @IntoMap
    @ClassKey(LaunchConversationActivity.class)
    public abstract Activity bindLaunchConversationActivity(LaunchConversationActivity activity);
}
