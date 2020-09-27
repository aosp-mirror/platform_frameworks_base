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

package com.android.systemui.statusbar.notification.dagger

import android.annotation.StringRes
import android.provider.Settings
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.notification.collection.render.NodeController
import com.android.systemui.statusbar.notification.collection.render.SectionHeaderController
import com.android.systemui.statusbar.notification.collection.render.SectionHeaderNodeControllerImpl
import dagger.Binds
import dagger.BindsInstance
import dagger.Module
import dagger.Provides
import dagger.Subcomponent
import javax.inject.Provider
import javax.inject.Qualifier
import javax.inject.Scope

@Module(subcomponents = [SectionHeaderControllerSubcomponent::class])
object NotificationSectionHeadersModule {

    @Provides
    @IncomingHeader
    @SysUISingleton
    @JvmStatic fun providesIncomingHeaderSubcomponent(
        builder: Provider<SectionHeaderControllerSubcomponent.Builder>
    ) = builder.get()
            .nodeLabel("incoming header")
            .headerText(R.string.notification_section_header_incoming)
            .clickIntentAction(Settings.ACTION_NOTIFICATION_SETTINGS)
            .build()

    @Provides
    @AlertingHeader
    @SysUISingleton
    @JvmStatic fun providesAlertingHeaderSubcomponent(
        builder: Provider<SectionHeaderControllerSubcomponent.Builder>
    ) = builder.get()
            .nodeLabel("alerting header")
            .headerText(R.string.notification_section_header_alerting)
            .clickIntentAction(Settings.ACTION_NOTIFICATION_SETTINGS)
            .build()

    @Provides
    @PeopleHeader
    @SysUISingleton
    @JvmStatic fun providesPeopleHeaderSubcomponent(
        builder: Provider<SectionHeaderControllerSubcomponent.Builder>
    ) = builder.get()
            .nodeLabel("people header")
            .headerText(R.string.notification_section_header_conversations)
            .clickIntentAction(Settings.ACTION_CONVERSATION_SETTINGS)
            .build()

    @Provides
    @SilentHeader
    @SysUISingleton
    @JvmStatic fun providesSilentHeaderSubcomponent(
        builder: Provider<SectionHeaderControllerSubcomponent.Builder>
    ) = builder.get()
            .nodeLabel("silent header")
            .headerText(R.string.notification_section_header_gentle)
            .clickIntentAction(Settings.ACTION_NOTIFICATION_SETTINGS)
            .build()

    @Provides
    @SilentHeader
    @JvmStatic fun providesSilentHeaderNodeController(
        @SilentHeader subcomponent: SectionHeaderControllerSubcomponent
    ) = subcomponent.nodeController

    @Provides
    @SilentHeader
    @JvmStatic fun providesSilentHeaderController(
        @SilentHeader subcomponent: SectionHeaderControllerSubcomponent
    ) = subcomponent.headerController

    @Provides
    @AlertingHeader
    @JvmStatic fun providesAlertingHeaderNodeController(
        @AlertingHeader subcomponent: SectionHeaderControllerSubcomponent
    ) = subcomponent.nodeController

    @Provides
    @AlertingHeader
    @JvmStatic fun providesAlertingHeaderController(
        @AlertingHeader subcomponent: SectionHeaderControllerSubcomponent
    ) = subcomponent.headerController

    @Provides
    @PeopleHeader
    @JvmStatic fun providesPeopleHeaderNodeController(
        @PeopleHeader subcomponent: SectionHeaderControllerSubcomponent
    ) = subcomponent.nodeController

    @Provides
    @PeopleHeader
    @JvmStatic fun providesPeopleHeaderController(
        @PeopleHeader subcomponent: SectionHeaderControllerSubcomponent
    ) = subcomponent.headerController

    @Provides
    @IncomingHeader
    @JvmStatic fun providesIncomingHeaderNodeController(
        @IncomingHeader subcomponent: SectionHeaderControllerSubcomponent
    ) = subcomponent.nodeController

    @Provides
    @IncomingHeader
    @JvmStatic fun providesIncomingHeaderController(
        @IncomingHeader subcomponent: SectionHeaderControllerSubcomponent
    ) = subcomponent.headerController
}

@Subcomponent(modules = [ SectionHeaderBindingModule::class ])
@SectionHeaderScope
interface SectionHeaderControllerSubcomponent {

    val nodeController: NodeController
    val headerController: SectionHeaderController

    @Subcomponent.Builder
    interface Builder {
        fun build(): SectionHeaderControllerSubcomponent
        @BindsInstance fun nodeLabel(@NodeLabel nodeLabel: String): Builder
        @BindsInstance fun headerText(@HeaderText @StringRes headerText: Int): Builder
        @BindsInstance fun clickIntentAction(@HeaderClickAction clickIntentAction: String): Builder
    }
}

@Module
private abstract class SectionHeaderBindingModule {
    @Binds abstract fun bindsNodeController(impl: SectionHeaderNodeControllerImpl): NodeController
    @Binds abstract fun bindsSectionHeaderController(
        impl: SectionHeaderNodeControllerImpl
    ): SectionHeaderController
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class HeaderText

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IncomingHeader

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AlertingHeader

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SilentHeader

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PeopleHeader

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class NodeLabel

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class HeaderClickAction

@Scope
@Retention(AnnotationRetention.BINARY)
annotation class SectionHeaderScope