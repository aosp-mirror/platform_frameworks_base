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

package com.android.systemui.screenshot.dagger;

import static com.android.systemui.Flags.screenshotShelfUi2;

import android.app.Service;
import android.view.accessibility.AccessibilityManager;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.screenshot.ImageCapture;
import com.android.systemui.screenshot.ImageCaptureImpl;
import com.android.systemui.screenshot.LegacyScreenshotViewProxy;
import com.android.systemui.screenshot.ScreenshotPolicy;
import com.android.systemui.screenshot.ScreenshotPolicyImpl;
import com.android.systemui.screenshot.ScreenshotShelfViewProxy;
import com.android.systemui.screenshot.ScreenshotSoundController;
import com.android.systemui.screenshot.ScreenshotSoundControllerImpl;
import com.android.systemui.screenshot.ScreenshotSoundProvider;
import com.android.systemui.screenshot.ScreenshotSoundProviderImpl;
import com.android.systemui.screenshot.ScreenshotViewProxy;
import com.android.systemui.screenshot.TakeScreenshotExecutor;
import com.android.systemui.screenshot.TakeScreenshotExecutorImpl;
import com.android.systemui.screenshot.TakeScreenshotService;
import com.android.systemui.screenshot.appclips.AppClipsScreenshotHelperService;
import com.android.systemui.screenshot.appclips.AppClipsService;
import com.android.systemui.screenshot.policy.ScreenshotPolicyModule;
import com.android.systemui.screenshot.proxy.SystemUiProxyModule;
import com.android.systemui.screenshot.ui.viewmodel.ScreenshotViewModel;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ClassKey;
import dagger.multibindings.IntoMap;

/**
 * Defines injectable resources for Screenshots
 */
@Module(includes = {ScreenshotPolicyModule.class, SystemUiProxyModule.class})
public abstract class ScreenshotModule {

    @Binds
    @IntoMap
    @ClassKey(TakeScreenshotService.class)
    abstract Service bindTakeScreenshotService(TakeScreenshotService service);

    @Binds
    @SysUISingleton
    abstract TakeScreenshotExecutor bindTakeScreenshotExecutor(
            TakeScreenshotExecutorImpl impl);

    @Binds
    abstract ScreenshotPolicy bindScreenshotPolicyImpl(ScreenshotPolicyImpl impl);

    @Binds
    abstract ImageCapture bindImageCaptureImpl(ImageCaptureImpl capture);

    @Binds
    @IntoMap
    @ClassKey(AppClipsScreenshotHelperService.class)
    abstract Service bindAppClipsScreenshotHelperService(AppClipsScreenshotHelperService service);

    @Binds
    @IntoMap
    @ClassKey(AppClipsService.class)
    abstract Service bindAppClipsService(AppClipsService service);

    @Binds
    abstract ScreenshotSoundProvider bindScreenshotSoundProvider(
            ScreenshotSoundProviderImpl screenshotSoundProviderImpl);

    @Binds
    abstract ScreenshotSoundController bindScreenshotSoundController(
            ScreenshotSoundControllerImpl screenshotSoundProviderImpl);

    @Provides
    @SysUISingleton
    static ScreenshotViewModel providesScreenshotViewModel(
            AccessibilityManager accessibilityManager) {
        return new ScreenshotViewModel(accessibilityManager);
    }

    @Provides
    static ScreenshotViewProxy.Factory providesScreenshotViewProxyFactory(
            ScreenshotShelfViewProxy.Factory shelfScreenshotViewProxyFactory,
            LegacyScreenshotViewProxy.Factory legacyScreenshotViewProxyFactory) {
        if (screenshotShelfUi2()) {
            return shelfScreenshotViewProxyFactory;
        } else {
            return legacyScreenshotViewProxyFactory;
        }
    }
}
