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

import android.app.Service;

import com.android.systemui.SystemUIService;
import com.android.systemui.communal.widgets.GlanceableHubWidgetManagerService;
import com.android.systemui.doze.DozeService;
import com.android.systemui.dreams.DreamOverlayService;
import com.android.systemui.dump.SystemUIAuxiliaryDumpService;
import com.android.systemui.keyguard.KeyguardService;
import com.android.systemui.recordissue.IssueRecordingService;
import com.android.systemui.screenrecord.RecordingService;
import com.android.systemui.statusbar.phone.NotificationListenerWithPlugins;
import com.android.systemui.wallpapers.ImageWallpaper;

import dagger.Binds;
import dagger.Module;
import dagger.multibindings.ClassKey;
import dagger.multibindings.IntoMap;

/**
 * Services that are injectable should go here.
 */
@Module
public abstract class DefaultServiceBinder {
    /** */
    @Binds
    @IntoMap
    @ClassKey(DozeService.class)
    public abstract Service bindDozeService(DozeService service);

    /** */
    @Binds
    @IntoMap
    @ClassKey(ImageWallpaper.class)
    public abstract Service bindImageWallpaper(ImageWallpaper service);

    /** */
    @Binds
    @IntoMap
    @ClassKey(KeyguardService.class)
    public abstract Service bindKeyguardService(KeyguardService service);

    /** */
    @Binds
    @IntoMap
    @ClassKey(DreamOverlayService.class)
    public abstract Service bindDreamOverlayService(DreamOverlayService service);

    /** */
    @Binds
    @IntoMap
    @ClassKey(NotificationListenerWithPlugins.class)
    public abstract Service bindNotificationListenerWithPlugins(
            NotificationListenerWithPlugins service);

    /** */
    @Binds
    @IntoMap
    @ClassKey(SystemUIService.class)
    public abstract Service bindSystemUIService(SystemUIService service);

    /** */
    @Binds
    @IntoMap
    @ClassKey(SystemUIAuxiliaryDumpService.class)
    public abstract Service bindSystemUIAuxiliaryDumpService(SystemUIAuxiliaryDumpService service);

    /** Inject into RecordingService */
    @Binds
    @IntoMap
    @ClassKey(RecordingService.class)
    public abstract Service bindRecordingService(RecordingService service);

    /** Inject into IssueRecordingService */
    @Binds
    @IntoMap
    @ClassKey(IssueRecordingService.class)
    public abstract Service bindIssueRecordingService(IssueRecordingService service);

    /** Inject into GlanceableHubWidgetManagerService */
    @Binds
    @IntoMap
    @ClassKey(GlanceableHubWidgetManagerService.class)
    public abstract Service bindGlanceableHubWidgetManagerService(
            GlanceableHubWidgetManagerService service);
}
