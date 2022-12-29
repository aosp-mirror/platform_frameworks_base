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

package com.android.systemui.shared.customization.data.content

import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.android.systemui.shared.keyguard.shared.model.KeyguardQuickAffordanceSlots
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine

class FakeCustomizationProviderClient(
    slots: List<CustomizationProviderClient.Slot> =
        listOf(
            CustomizationProviderClient.Slot(
                id = KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_START,
                capacity = 1,
            ),
            CustomizationProviderClient.Slot(
                id = KeyguardQuickAffordanceSlots.SLOT_ID_BOTTOM_END,
                capacity = 1,
            ),
        ),
    affordances: List<CustomizationProviderClient.Affordance> =
        listOf(
            CustomizationProviderClient.Affordance(
                id = AFFORDANCE_1,
                name = AFFORDANCE_1,
                iconResourceId = 1,
            ),
            CustomizationProviderClient.Affordance(
                id = AFFORDANCE_2,
                name = AFFORDANCE_2,
                iconResourceId = 2,
            ),
            CustomizationProviderClient.Affordance(
                id = AFFORDANCE_3,
                name = AFFORDANCE_3,
                iconResourceId = 3,
            ),
        ),
    flags: List<CustomizationProviderClient.Flag> =
        listOf(
            CustomizationProviderClient.Flag(
                name =
                    CustomizationProviderContract.FlagsTable
                        .FLAG_NAME_CUSTOM_LOCK_SCREEN_QUICK_AFFORDANCES_ENABLED,
                value = true,
            )
        ),
) : CustomizationProviderClient {

    private val slots = MutableStateFlow(slots)
    private val affordances = MutableStateFlow(affordances)
    private val flags = MutableStateFlow(flags)

    private val selections = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    override suspend fun insertSelection(slotId: String, affordanceId: String) {
        val slotCapacity =
            querySlots().find { it.id == slotId }?.capacity
                ?: error("Slot with ID \"$slotId\" not found!")
        val affordances = selections.value.getOrDefault(slotId, mutableListOf()).toMutableList()
        while (affordances.size + 1 > slotCapacity) {
            affordances.removeAt(0)
        }
        affordances.remove(affordanceId)
        affordances.add(affordanceId)
        selections.value = selections.value.toMutableMap().apply { this[slotId] = affordances }
    }

    override suspend fun querySlots(): List<CustomizationProviderClient.Slot> {
        return slots.value
    }

    override suspend fun queryFlags(): List<CustomizationProviderClient.Flag> {
        return flags.value
    }

    override fun observeSlots(): Flow<List<CustomizationProviderClient.Slot>> {
        return slots.asStateFlow()
    }

    override fun observeFlags(): Flow<List<CustomizationProviderClient.Flag>> {
        return flags.asStateFlow()
    }

    override suspend fun queryAffordances(): List<CustomizationProviderClient.Affordance> {
        return affordances.value
    }

    override fun observeAffordances(): Flow<List<CustomizationProviderClient.Affordance>> {
        return affordances.asStateFlow()
    }

    override suspend fun querySelections(): List<CustomizationProviderClient.Selection> {
        return toSelectionList(selections.value, affordances.value)
    }

    override fun observeSelections(): Flow<List<CustomizationProviderClient.Selection>> {
        return combine(selections, affordances) { selections, affordances ->
            toSelectionList(selections, affordances)
        }
    }

    override suspend fun deleteSelection(slotId: String, affordanceId: String) {
        val affordances = selections.value.getOrDefault(slotId, mutableListOf()).toMutableList()
        affordances.remove(affordanceId)

        selections.value = selections.value.toMutableMap().apply { this[slotId] = affordances }
    }

    override suspend fun deleteAllSelections(slotId: String) {
        selections.value = selections.value.toMutableMap().apply { this[slotId] = emptyList() }
    }

    override suspend fun getAffordanceIcon(iconResourceId: Int, tintColor: Int): Drawable {
        return when (iconResourceId) {
            1 -> ICON_1
            2 -> ICON_2
            3 -> ICON_3
            else -> BitmapDrawable()
        }
    }

    fun setFlag(
        name: String,
        value: Boolean,
    ) {
        flags.value =
            flags.value.toMutableList().apply {
                removeIf { it.name == name }
                add(CustomizationProviderClient.Flag(name = name, value = value))
            }
    }

    fun setSlotCapacity(slotId: String, capacity: Int) {
        slots.value =
            slots.value.toMutableList().apply {
                val index = indexOfFirst { it.id == slotId }
                check(index != -1) { "Slot with ID \"$slotId\" doesn't exist!" }
                set(index, CustomizationProviderClient.Slot(id = slotId, capacity = capacity))
            }
    }

    fun addAffordance(affordance: CustomizationProviderClient.Affordance): Int {
        affordances.value = affordances.value + listOf(affordance)
        return affordances.value.size - 1
    }

    private fun toSelectionList(
        selections: Map<String, List<String>>,
        affordances: List<CustomizationProviderClient.Affordance>,
    ): List<CustomizationProviderClient.Selection> {
        return selections
            .map { (slotId, affordanceIds) ->
                affordanceIds.map { affordanceId ->
                    val affordanceName =
                        affordances.find { it.id == affordanceId }?.name
                            ?: error("No affordance with ID of \"$affordanceId\"!")
                    CustomizationProviderClient.Selection(
                        slotId = slotId,
                        affordanceId = affordanceId,
                        affordanceName = affordanceName,
                    )
                }
            }
            .flatten()
    }

    companion object {
        const val AFFORDANCE_1 = "affordance_1"
        const val AFFORDANCE_2 = "affordance_2"
        const val AFFORDANCE_3 = "affordance_3"
        val ICON_1 = BitmapDrawable()
        val ICON_2 = BitmapDrawable()
        val ICON_3 = BitmapDrawable()
    }
}
