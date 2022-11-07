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
 *
 */

package com.android.systemui.keyguard.data.repository

import android.content.Context
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceConfig
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceLegacySettingSyncer
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceSelectionManager
import com.android.systemui.keyguard.shared.model.KeyguardQuickAffordancePickerRepresentation
import com.android.systemui.keyguard.shared.model.KeyguardSlotPickerRepresentation
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Abstracts access to application state related to keyguard quick affordances. */
@SysUISingleton
class KeyguardQuickAffordanceRepository
@Inject
constructor(
    @Application private val appContext: Context,
    @Application private val scope: CoroutineScope,
    private val selectionManager: KeyguardQuickAffordanceSelectionManager,
    legacySettingSyncer: KeyguardQuickAffordanceLegacySettingSyncer,
    private val configs: Set<@JvmSuppressWildcards KeyguardQuickAffordanceConfig>,
) {
    /**
     * List of [KeyguardQuickAffordanceConfig] instances of the affordances at the slot with the
     * given ID. The configs are sorted in descending priority order.
     */
    val selections: StateFlow<Map<String, List<KeyguardQuickAffordanceConfig>>> =
        selectionManager.selections
            .map { selectionsBySlotId ->
                selectionsBySlotId.mapValues { (_, selections) ->
                    configs.filter { selections.contains(it.key) }
                }
            }
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = emptyMap(),
            )

    private val _slotPickerRepresentations: List<KeyguardSlotPickerRepresentation> by lazy {
        fun parseSlot(unparsedSlot: String): Pair<String, Int> {
            val split = unparsedSlot.split(SLOT_CONFIG_DELIMITER)
            check(split.size == 2)
            val slotId = split[0]
            val slotCapacity = split[1].toInt()
            return slotId to slotCapacity
        }

        val unparsedSlots =
            appContext.resources.getStringArray(R.array.config_keyguardQuickAffordanceSlots)

        val seenSlotIds = mutableSetOf<String>()
        unparsedSlots.mapNotNull { unparsedSlot ->
            val (slotId, slotCapacity) = parseSlot(unparsedSlot)
            check(!seenSlotIds.contains(slotId)) { "Duplicate slot \"$slotId\"!" }
            seenSlotIds.add(slotId)
            KeyguardSlotPickerRepresentation(
                id = slotId,
                maxSelectedAffordances = slotCapacity,
            )
        }
    }

    init {
        legacySettingSyncer.startSyncing()
    }

    /**
     * Returns a snapshot of the [KeyguardQuickAffordanceConfig] instances of the affordances at the
     * slot with the given ID. The configs are sorted in descending priority order.
     */
    fun getSelections(slotId: String): List<KeyguardQuickAffordanceConfig> {
        val selections = selectionManager.getSelections().getOrDefault(slotId, emptyList())
        return configs.filter { selections.contains(it.key) }
    }

    /**
     * Returns a snapshot of the IDs of the selected affordances, indexed by slot ID. The configs
     * are sorted in descending priority order.
     */
    fun getSelections(): Map<String, List<String>> {
        return selectionManager.getSelections()
    }

    /**
     * Updates the IDs of affordances to show at the slot with the given ID. The order of affordance
     * IDs should be descending priority order.
     */
    fun setSelections(
        slotId: String,
        affordanceIds: List<String>,
    ) {
        selectionManager.setSelections(
            slotId = slotId,
            affordanceIds = affordanceIds,
        )
    }

    /**
     * Returns the list of representation objects for all known affordances, regardless of what is
     * selected. This is useful for building experiences like the picker/selector or user settings
     * so the user can see everything that can be selected in a menu.
     */
    fun getAffordancePickerRepresentations(): List<KeyguardQuickAffordancePickerRepresentation> {
        return configs.map { config ->
            KeyguardQuickAffordancePickerRepresentation(
                id = config.key,
                name = config.pickerName,
                iconResourceId = config.pickerIconResourceId,
            )
        }
    }

    /**
     * Returns the list of representation objects for all available slots on the keyguard. This is
     * useful for building experiences like the picker/selector or user settings so the user can see
     * each slot and select which affordance(s) is/are installed in each slot on the keyguard.
     */
    fun getSlotPickerRepresentations(): List<KeyguardSlotPickerRepresentation> {
        return _slotPickerRepresentations
    }

    companion object {
        private const val SLOT_CONFIG_DELIMITER = ":"
    }
}
