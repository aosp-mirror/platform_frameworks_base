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

package com.android.systemui.shared.keyguard.data.content

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.UserHandle
import androidx.annotation.DrawableRes
import com.android.systemui.shared.keyguard.data.content.KeyguardQuickAffordanceProviderContract as Contract
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

/** Collection of utility functions for using a content provider implementing the [Contract]. */
object KeyguardQuickAffordanceProviderClient {

    /**
     * Selects an affordance with the given ID for a slot on the lock screen with the given ID.
     *
     * Note that the maximum number of selected affordances on this slot is automatically enforced.
     * Selecting a slot that is already full (e.g. already has a number of selected affordances at
     * its maximum capacity) will automatically remove the oldest selected affordance before adding
     * the one passed in this call. Additionally, selecting an affordance that's already one of the
     * selected affordances on the slot will move the selected affordance to the newest location in
     * the slot.
     */
    suspend fun insertSelection(
        context: Context,
        slotId: String,
        affordanceId: String,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) {
        withContext(dispatcher) {
            context.contentResolver.insert(
                Contract.SelectionTable.URI,
                ContentValues().apply {
                    put(Contract.SelectionTable.Columns.SLOT_ID, slotId)
                    put(Contract.SelectionTable.Columns.AFFORDANCE_ID, affordanceId)
                }
            )
        }
    }

    /** Returns all available slots supported by the device. */
    suspend fun querySlots(
        context: Context,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): List<Slot> {
        return withContext(dispatcher) {
            context.contentResolver
                .query(
                    Contract.SlotTable.URI,
                    null,
                    null,
                    null,
                    null,
                )
                ?.use { cursor ->
                    buildList {
                        val idColumnIndex = cursor.getColumnIndex(Contract.SlotTable.Columns.ID)
                        val capacityColumnIndex =
                            cursor.getColumnIndex(Contract.SlotTable.Columns.CAPACITY)
                        if (idColumnIndex == -1 || capacityColumnIndex == -1) {
                            return@buildList
                        }

                        while (cursor.moveToNext()) {
                            add(
                                Slot(
                                    id = cursor.getString(idColumnIndex),
                                    capacity = cursor.getInt(capacityColumnIndex),
                                )
                            )
                        }
                    }
                }
        }
            ?: emptyList()
    }

    /**
     * Returns [Flow] for observing the collection of slots.
     *
     * @see [querySlots]
     */
    fun observeSlots(
        context: Context,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): Flow<List<Slot>> {
        return observeUri(
                context,
                Contract.SlotTable.URI,
            )
            .map { querySlots(context, dispatcher) }
    }

    /**
     * Returns all available affordances supported by the device, regardless of current slot
     * placement.
     */
    suspend fun queryAffordances(
        context: Context,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): List<Affordance> {
        return withContext(dispatcher) {
            context.contentResolver
                .query(
                    Contract.AffordanceTable.URI,
                    null,
                    null,
                    null,
                    null,
                )
                ?.use { cursor ->
                    buildList {
                        val idColumnIndex =
                            cursor.getColumnIndex(Contract.AffordanceTable.Columns.ID)
                        val nameColumnIndex =
                            cursor.getColumnIndex(Contract.AffordanceTable.Columns.NAME)
                        val iconColumnIndex =
                            cursor.getColumnIndex(Contract.AffordanceTable.Columns.ICON)
                        if (idColumnIndex == -1 || nameColumnIndex == -1 || iconColumnIndex == -1) {
                            return@buildList
                        }

                        while (cursor.moveToNext()) {
                            add(
                                Affordance(
                                    id = cursor.getString(idColumnIndex),
                                    name = cursor.getString(nameColumnIndex),
                                    iconResourceId = cursor.getInt(iconColumnIndex),
                                )
                            )
                        }
                    }
                }
        }
            ?: emptyList()
    }

    /**
     * Returns [Flow] for observing the collection of affordances.
     *
     * @see [queryAffordances]
     */
    fun observeAffordances(
        context: Context,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): Flow<List<Affordance>> {
        return observeUri(
                context,
                Contract.AffordanceTable.URI,
            )
            .map { queryAffordances(context, dispatcher) }
    }

    /** Returns the current slot-affordance selections. */
    suspend fun querySelections(
        context: Context,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): List<Selection> {
        return withContext(dispatcher) {
            context.contentResolver
                .query(
                    Contract.SelectionTable.URI,
                    null,
                    null,
                    null,
                    null,
                )
                ?.use { cursor ->
                    buildList {
                        val slotIdColumnIndex =
                            cursor.getColumnIndex(Contract.SelectionTable.Columns.SLOT_ID)
                        val affordanceIdColumnIndex =
                            cursor.getColumnIndex(Contract.SelectionTable.Columns.AFFORDANCE_ID)
                        if (slotIdColumnIndex == -1 || affordanceIdColumnIndex == -1) {
                            return@buildList
                        }

                        while (cursor.moveToNext()) {
                            add(
                                Selection(
                                    slotId = cursor.getString(slotIdColumnIndex),
                                    affordanceId = cursor.getString(affordanceIdColumnIndex),
                                )
                            )
                        }
                    }
                }
        }
            ?: emptyList()
    }

    /**
     * Returns [Flow] for observing the collection of selections.
     *
     * @see [querySelections]
     */
    fun observeSelections(
        context: Context,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): Flow<List<Selection>> {
        return observeUri(
                context,
                Contract.SelectionTable.URI,
            )
            .map { querySelections(context, dispatcher) }
    }

    /** Unselects an affordance with the given ID from the slot with the given ID. */
    suspend fun deleteSelection(
        context: Context,
        slotId: String,
        affordanceId: String,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) {
        withContext(dispatcher) {
            context.contentResolver.delete(
                Contract.SelectionTable.URI,
                "${Contract.SelectionTable.Columns.SLOT_ID} = ? AND" +
                    " ${Contract.SelectionTable.Columns.AFFORDANCE_ID} = ?",
                arrayOf(
                    slotId,
                    affordanceId,
                ),
            )
        }
    }

    /** Unselects all affordances from the slot with the given ID. */
    suspend fun deleteAllSelections(
        context: Context,
        slotId: String,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) {
        withContext(dispatcher) {
            context.contentResolver.delete(
                Contract.SelectionTable.URI,
                "${Contract.SelectionTable.Columns.SLOT_ID}",
                arrayOf(
                    slotId,
                ),
            )
        }
    }

    private fun observeUri(
        context: Context,
        uri: Uri,
    ): Flow<Unit> {
        return callbackFlow {
                val observer =
                    object : ContentObserver(null) {
                        override fun onChange(selfChange: Boolean) {
                            trySend(Unit)
                        }
                    }

                context.contentResolver.registerContentObserver(
                    uri,
                    /* notifyForDescendants= */ true,
                    observer,
                    UserHandle.USER_CURRENT,
                )

                awaitClose { context.contentResolver.unregisterContentObserver(observer) }
            }
            .onStart { emit(Unit) }
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    suspend fun getAffordanceIcon(
        context: Context,
        @DrawableRes iconResourceId: Int,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): Drawable {
        return withContext(dispatcher) {
            context.packageManager
                .getResourcesForApplication(SYSTEM_UI_PACKAGE_NAME)
                .getDrawable(iconResourceId)
        }
    }

    data class Slot(
        val id: String,
        val capacity: Int,
    )

    data class Affordance(
        val id: String,
        val name: String,
        val iconResourceId: Int,
    )

    data class Selection(
        val slotId: String,
        val affordanceId: String,
    )

    private const val SYSTEM_UI_PACKAGE_NAME = "com.android.systemui"
}
