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
import com.android.systemui.hdmi.HdmiCecSetMenuLanguageActivity;
import com.android.systemui.keyguard.WorkLockActivity;
import com.android.systemui.people.PeopleSpaceActivity;
import com.android.systemui.people.widget.LaunchConversationActivity;
import com.android.systemui.screenshot.LongScreenshotActivity;
import com.android.systemui.sensorprivacy.SensorUseStartedActivity;
import com.android.systemui.sensorprivacy.television.TvUnblockSensorActivity;
import com.android.systemui.settings.brightness.BrightnessDialog;
import com.android.systemui.statusbar.tv.notifications.TvNotificationPanelActivity;
import com.android.systemui.tuner.TunerActivity;
import com.android.systemui.usb.UsbAccessoryUriActivity;
import com.android.systemui.usb.UsbConfirmActivity;
import com.android.systemui.usb.UsbDebuggingActivity;
import com.android.systemui.usb.UsbDebuggingSecondaryUserActivity;
import com.android.systemui.usb.UsbPermissionActivity;
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

    /** Inject into UsbPermissionActivity. */
    @Binds
    @IntoMap
    @ClassKey(UsbPermissionActivity.class)
    public abstract Activity bindUsbPermissionActivity(UsbPermissionActivity activity);

    /** Inject into UsbConfirmActivity. */
    @Binds
    @IntoMap
    @ClassKey(UsbConfirmActivity.class)
    public abstract Activity bindUsbConfirmActivity(UsbConfirmActivity activity);

    /** Inject into UsbAccessoryUriActivity. */
    @Binds
    @IntoMap
    @ClassKey(UsbAccessoryUriActivity.class)
    public abstract Activity bindUsbAccessoryUriActivity(UsbAccessoryUriActivity activity);

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

    /** Inject into SensorUseStartedActivity. */
    @Binds
    @IntoMap
    @ClassKey(SensorUseStartedActivity.class)
    public abstract Activity bindSensorUseStartedActivity(SensorUseStartedActivity activity);

    /** Inject into TvUnblockSensorActivity. */
    @Binds
    @IntoMap
    @ClassKey(TvUnblockSensorActivity.class)
    public abstract Activity bindTvUnblockSensorActivity(TvUnblockSensorActivity activity);

    /** Inject into HdmiCecSetMenuLanguageActivity. */
    @Binds
    @IntoMap
    @ClassKey(HdmiCecSetMenuLanguageActivity.class)
    public abstract Activity bindHdmiCecSetMenuLanguageActivity(
            HdmiCecSetMenuLanguageActivity activity);
}
