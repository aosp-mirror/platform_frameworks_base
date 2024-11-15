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

@file:OptIn(InternalNoteTaskApi::class)

package com.android.systemui.notetask

import android.app.Activity
import android.app.Service
import android.app.role.RoleManager
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.notetask.NoteTaskBubblesController.NoteTaskBubblesService
import com.android.systemui.notetask.quickaffordance.NoteTaskQuickAffordanceModule
import com.android.systemui.notetask.shortcut.CreateNoteTaskShortcutActivity
import com.android.systemui.notetask.shortcut.LaunchNoteTaskActivity
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.shared.model.TileCategory
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.qs.tiles.NotesTile
import com.android.systemui.qs.tiles.base.interactor.QSTileAvailabilityInteractor
import com.android.systemui.qs.tiles.base.viewmodel.QSTileViewModelFactory
import com.android.systemui.qs.tiles.impl.notes.domain.NotesTileMapper
import com.android.systemui.qs.tiles.impl.notes.domain.interactor.NotesTileDataInteractor
import com.android.systemui.qs.tiles.impl.notes.domain.interactor.NotesTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.notes.domain.model.NotesTileModel
import com.android.systemui.qs.tiles.viewmodel.QSTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileUIConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileViewModel
import com.android.systemui.qs.tiles.viewmodel.StubQSTileViewModel
import com.android.systemui.res.R
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

/** Compose all dependencies required by Note Task feature. */
@Module(includes = [NoteTaskQuickAffordanceModule::class])
interface NoteTaskModule {

    @[Binds IntoMap ClassKey(NoteTaskControllerUpdateService::class)]
    fun bindNoteTaskControllerUpdateService(service: NoteTaskControllerUpdateService): Service

    @[Binds IntoMap ClassKey(NoteTaskBubblesService::class)]
    fun bindNoteTaskBubblesService(service: NoteTaskBubblesService): Service

    @[Binds IntoMap ClassKey(LaunchNoteTaskActivity::class)]
    fun bindNoteTaskLauncherActivity(activity: LaunchNoteTaskActivity): Activity

    @[Binds IntoMap ClassKey(LaunchNotesRoleSettingsTrampolineActivity::class)]
    fun bindLaunchNotesRoleSettingsTrampolineActivity(
        activity: LaunchNotesRoleSettingsTrampolineActivity
    ): Activity

    @[Binds IntoMap ClassKey(CreateNoteTaskShortcutActivity::class)]
    fun bindNoteTaskShortcutActivity(activity: CreateNoteTaskShortcutActivity): Activity

    @Binds
    @IntoMap
    @StringKey(NOTES_TILE_SPEC)
    fun provideNotesAvailabilityInteractor(
        impl: NotesTileDataInteractor
    ): QSTileAvailabilityInteractor

    @Binds
    @IntoMap
    @StringKey(NotesTile.TILE_SPEC)
    fun bindNotesTile(notesTile: NotesTile): QSTileImpl<*>

    companion object {

        const val NOTES_TILE_SPEC = "notes"

        @[Provides NoteTaskEnabledKey]
        fun provideIsNoteTaskEnabled(
            featureFlags: FeatureFlags,
            roleManager: RoleManager,
        ): Boolean {
            val isRoleAvailable = roleManager.isRoleAvailable(RoleManager.ROLE_NOTES)
            val isFeatureEnabled = featureFlags.isEnabled(Flags.NOTE_TASKS)
            return isRoleAvailable && isFeatureEnabled
        }

        /** Inject NotesTile into tileViewModelMap in QSModule */
        @Provides
        @IntoMap
        @StringKey(NOTES_TILE_SPEC)
        fun provideNotesTileViewModel(
            factory: QSTileViewModelFactory.Static<NotesTileModel>,
            mapper: NotesTileMapper,
            stateInteractor: NotesTileDataInteractor,
            userActionInteractor: NotesTileUserActionInteractor,
        ): QSTileViewModel =
            if (com.android.systemui.Flags.qsNewTilesFuture())
                factory.create(
                    TileSpec.create(NOTES_TILE_SPEC),
                    userActionInteractor,
                    stateInteractor,
                    mapper,
                )
            else StubQSTileViewModel

        @Provides
        @IntoMap
        @StringKey(NOTES_TILE_SPEC)
        fun provideNotesTileConfig(uiEventLogger: QsEventLogger): QSTileConfig =
            QSTileConfig(
                tileSpec = TileSpec.create(NOTES_TILE_SPEC),
                uiConfig =
                    QSTileUIConfig.Resource(
                        iconRes = R.drawable.ic_qs_notes,
                        labelRes = R.string.quick_settings_notes_label,
                    ),
                instanceId = uiEventLogger.getNewInstanceId(),
                category = TileCategory.UTILITIES,
            )
    }
}
