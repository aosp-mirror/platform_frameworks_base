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

package com.android.systemui.statusbar.notification.people

import dagger.Binds
import dagger.Module

@Module
abstract class PeopleHubModule {

    @Binds
    abstract fun peopleHubSectionFooterViewAdapter(
        impl: PeopleHubViewAdapterImpl
    ): PeopleHubViewAdapter

    @Binds
    abstract fun peopleHubDataSource(impl: PeopleHubDataSourceImpl): DataSource<PeopleHubModel>

    @Binds
    abstract fun peopleHubSettingChangeDataSource(
        impl: PeopleHubSettingChangeDataSourceImpl
    ): DataSource<Boolean>

    @Binds
    abstract fun peopleHubViewModelFactoryDataSource(
        impl: PeopleHubViewModelFactoryDataSourceImpl
    ): DataSource<PeopleHubViewModelFactory>

    @Binds
    abstract fun peopleNotificationIdentifier(
        impl: PeopleNotificationIdentifierImpl
    ): PeopleNotificationIdentifier

    @Binds
    abstract fun notificationPersonExtractor(
        pluginImpl: NotificationPersonExtractorPluginBoundary
    ): NotificationPersonExtractor
}