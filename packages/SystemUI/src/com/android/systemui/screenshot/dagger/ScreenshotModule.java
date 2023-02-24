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

import android.app.Service;

import com.android.systemui.screenshot.ImageCapture;
import com.android.systemui.screenshot.ImageCaptureImpl;
import com.android.systemui.screenshot.ScreenshotPolicy;
import com.android.systemui.screenshot.ScreenshotPolicyImpl;
import com.android.systemui.screenshot.ScreenshotProxyService;
import com.android.systemui.screenshot.TakeScreenshotService;

import dagger.Binds;
import dagger.Module;
import dagger.multibindings.ClassKey;
import dagger.multibindings.IntoMap;

/**
 * Defines injectable resources for Screenshots
 */
@Module
public abstract class ScreenshotModule {

    @Binds
    @IntoMap
    @ClassKey(TakeScreenshotService.class)
    abstract Service bindTakeScreenshotService(TakeScreenshotService service);

    @Binds
    @IntoMap
    @ClassKey(ScreenshotProxyService.class)
    abstract Service bindScreenshotProxyService(ScreenshotProxyService service);

    @Binds
    abstract ScreenshotPolicy bindScreenshotPolicyImpl(ScreenshotPolicyImpl impl);

    @Binds
    abstract ImageCapture bindImageCaptureImpl(ImageCaptureImpl capture);

}
