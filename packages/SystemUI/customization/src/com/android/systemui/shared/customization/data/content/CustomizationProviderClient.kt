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

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Log
import androidx.annotation.DrawableRes
import com.android.systemui.shared.customization.data.content.CustomizationProviderContract as Contract
import java.net.URISyntaxException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

/** Client for using a content provider implementing the [Contract]. */
interface CustomizationProviderClient {

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
        slotId: String,
        affordanceId: String,
    )

    /** Returns all available slots supported by the device. */
    suspend fun querySlots(): List<Slot>

    /** Returns the list of flags. */
    suspend fun queryFlags(): List<Flag>

    /**
     * Returns [Flow] for observing the collection of slots.
     *
     * @see [querySlots]
     */
    fun observeSlots(): Flow<List<Slot>>

    /**
     * Returns [Flow] for observing the collection of flags.
     *
     * @see [queryFlags]
     */
    fun observeFlags(): Flow<List<Flag>>

    /**
     * Returns all available affordances supported by the device, regardless of current slot
     * placement.
     */
    suspend fun queryAffordances(): List<Affordance>

    /**
     * Returns [Flow] for observing the collection of affordances.
     *
     * @see [queryAffordances]
     */
    fun observeAffordances(): Flow<List<Affordance>>

    /** Returns the current slot-affordance selections. */
    suspend fun querySelections(): List<Selection>

    /**
     * Returns [Flow] for observing the collection of selections.
     *
     * @see [querySelections]
     */
    fun observeSelections(): Flow<List<Selection>>

    /** Unselects an affordance with the given ID from the slot with the given ID. */
    suspend fun deleteSelection(
        slotId: String,
        affordanceId: String,
    )

    /** Unselects all affordances from the slot with the given ID. */
    suspend fun deleteAllSelections(
        slotId: String,
    )

    /** Returns a [Drawable] with the given ID, loaded from the system UI package. */
    suspend fun getAffordanceIcon(
        @DrawableRes iconResourceId: Int,
        tintColor: Int = Color.WHITE,
    ): Drawable

    /** Models a slot. A position that quick affordances can be positioned in. */
    data class Slot(
        /** Unique ID of the slot. */
        val id: String,
        /**
         * The maximum number of quick affordances that are allowed to be positioned in this slot.
         */
        val capacity: Int,
    )

    /**
     * Models a quick affordance. An action that can be selected by the user to appear in one or
     * more slots on the lock screen.
     */
    data class Affordance(
        /** Unique ID of the quick affordance. */
        val id: String,
        /** User-facing label for this affordance. */
        val name: String,
        /**
         * Resource ID for the user-facing icon for this affordance. This resource is hosted by the
         * System UI process so it must be used with
         * `PackageManager.getResourcesForApplication(String)`.
         */
        val iconResourceId: Int,
        /**
         * Whether the affordance is enabled. Disabled affordances should be shown on the picker but
         * should be rendered as "disabled". When tapped, the enablement properties should be used
         * to populate UI that would explain to the user what to do in order to re-enable this
         * affordance.
         */
        val isEnabled: Boolean = true,
        /**
         * If the affordance is disabled, this is the explanation to be shown to the user when the
         * disabled affordance is selected. The instructions should help the user figure out what to
         * do in order to re-neable this affordance.
         */
        val enablementExplanation: String? = null,
        /**
         * If the affordance is disabled, this is a label for a button shown together with the set
         * of instruction messages when the disabled affordance is selected. The button should help
         * send the user to a flow that would help them achieve the instructions and re-enable this
         * affordance.
         *
         * If `null`, the button should not be shown.
         */
        val enablementActionText: String? = null,
        /**
         * If the affordance is disabled, this is an [Intent] to be used with `startActivity` when
         * the action button (shown together with the set of instruction messages when the disabled
         * affordance is selected) is clicked by the user. The button should help send the user to a
         * flow that would help them achieve the instructions and re-enable this affordance.
         *
         * If `null`, the button should not be shown.
         */
        val enablementActionIntent: Intent? = null,
        /** Optional [Intent] to use to start an activity to configure this affordance. */
        val configureIntent: Intent? = null,
    )

    /** Models a selection of a quick affordance on a slot. */
    data class Selection(
        /** The unique ID of the slot. */
        val slotId: String,
        /** The unique ID of the quick affordance. */
        val affordanceId: String,
        /** The user-visible label for the quick affordance. */
        val affordanceName: String,
    )

    /** Models a System UI flag. */
    data class Flag(
        /** The name of the flag. */
        val name: String,
        /** The value of the flag. */
        val value: Boolean,
    )
}

class CustomizationProviderClientImpl(
    private val context: Context,
    private val backgroundDispatcher: CoroutineDispatcher,
) : CustomizationProviderClient {

    override suspend fun insertSelection(
        slotId: String,
        affordanceId: String,
    ) {
        withContext(backgroundDispatcher) {
            context.contentResolver.insert(
                Contract.LockScreenQuickAffordances.SelectionTable.URI,
                ContentValues().apply {
                    put(Contract.LockScreenQuickAffordances.SelectionTable.Columns.SLOT_ID, slotId)
                    put(
                        Contract.LockScreenQuickAffordances.SelectionTable.Columns.AFFORDANCE_ID,
                        affordanceId
                    )
                }
            )
        }
    }

    override suspend fun querySlots(): List<CustomizationProviderClient.Slot> {
        return withContext(backgroundDispatcher) {
            context.contentResolver
                .query(
                    Contract.LockScreenQuickAffordances.SlotTable.URI,
                    null,
                    null,
                    null,
                    null,
                )
                ?.use { cursor ->
                    buildList {
                        val idColumnIndex =
                            cursor.getColumnIndex(
                                Contract.LockScreenQuickAffordances.SlotTable.Columns.ID
                            )
                        val capacityColumnIndex =
                            cursor.getColumnIndex(
                                Contract.LockScreenQuickAffordances.SlotTable.Columns.CAPACITY
                            )
                        if (idColumnIndex == -1 || capacityColumnIndex == -1) {
                            return@buildList
                        }

                        while (cursor.moveToNext()) {
                            add(
                                CustomizationProviderClient.Slot(
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

    override suspend fun queryFlags(): List<CustomizationProviderClient.Flag> {
        return withContext(backgroundDispatcher) {
            context.contentResolver
                .query(
                    Contract.FlagsTable.URI,
                    null,
                    null,
                    null,
                    null,
                )
                ?.use { cursor ->
                    buildList {
                        val nameColumnIndex =
                            cursor.getColumnIndex(Contract.FlagsTable.Columns.NAME)
                        val valueColumnIndex =
                            cursor.getColumnIndex(Contract.FlagsTable.Columns.VALUE)
                        if (nameColumnIndex == -1 || valueColumnIndex == -1) {
                            return@buildList
                        }

                        while (cursor.moveToNext()) {
                            add(
                                CustomizationProviderClient.Flag(
                                    name = cursor.getString(nameColumnIndex),
                                    value = cursor.getInt(valueColumnIndex) == 1,
                                )
                            )
                        }
                    }
                }
        }
            ?: emptyList()
    }

    override fun observeSlots(): Flow<List<CustomizationProviderClient.Slot>> {
        return observeUri(Contract.LockScreenQuickAffordances.SlotTable.URI).map { querySlots() }
    }

    override fun observeFlags(): Flow<List<CustomizationProviderClient.Flag>> {
        return observeUri(Contract.FlagsTable.URI).map { queryFlags() }
    }

    override suspend fun queryAffordances(): List<CustomizationProviderClient.Affordance> {
        return withContext(backgroundDispatcher) {
            context.contentResolver
                .query(
                    Contract.LockScreenQuickAffordances.AffordanceTable.URI,
                    null,
                    null,
                    null,
                    null,
                )
                ?.use { cursor ->
                    buildList {
                        val idColumnIndex =
                            cursor.getColumnIndex(
                                Contract.LockScreenQuickAffordances.AffordanceTable.Columns.ID
                            )
                        val nameColumnIndex =
                            cursor.getColumnIndex(
                                Contract.LockScreenQuickAffordances.AffordanceTable.Columns.NAME
                            )
                        val iconColumnIndex =
                            cursor.getColumnIndex(
                                Contract.LockScreenQuickAffordances.AffordanceTable.Columns.ICON
                            )
                        val isEnabledColumnIndex =
                            cursor.getColumnIndex(
                                Contract.LockScreenQuickAffordances.AffordanceTable.Columns
                                    .IS_ENABLED
                            )
                        val enablementExplanationColumnIndex =
                            cursor.getColumnIndex(
                                Contract.LockScreenQuickAffordances.AffordanceTable.Columns
                                    .ENABLEMENT_EXPLANATION
                            )
                        val enablementActionTextColumnIndex =
                            cursor.getColumnIndex(
                                Contract.LockScreenQuickAffordances.AffordanceTable.Columns
                                    .ENABLEMENT_ACTION_TEXT
                            )
                        val enablementActionIntentColumnIndex =
                            cursor.getColumnIndex(
                                Contract.LockScreenQuickAffordances.AffordanceTable.Columns
                                    .ENABLEMENT_ACTION_INTENT
                            )
                        val configureIntentColumnIndex =
                            cursor.getColumnIndex(
                                Contract.LockScreenQuickAffordances.AffordanceTable.Columns
                                    .CONFIGURE_INTENT
                            )
                        if (
                            idColumnIndex == -1 ||
                                nameColumnIndex == -1 ||
                                iconColumnIndex == -1 ||
                                isEnabledColumnIndex == -1 ||
                                enablementExplanationColumnIndex == -1 ||
                                enablementActionTextColumnIndex == -1 ||
                                enablementActionIntentColumnIndex == -1 ||
                                configureIntentColumnIndex == -1
                        ) {
                            return@buildList
                        }

                        while (cursor.moveToNext()) {
                            val affordanceId = cursor.getString(idColumnIndex)
                            add(
                                CustomizationProviderClient.Affordance(
                                    id = affordanceId,
                                    name = cursor.getString(nameColumnIndex),
                                    iconResourceId = cursor.getInt(iconColumnIndex),
                                    isEnabled = cursor.getInt(isEnabledColumnIndex) == 1,
                                    enablementExplanation =
                                        cursor.getString(enablementExplanationColumnIndex),
                                    enablementActionText =
                                        cursor.getString(enablementActionTextColumnIndex),
                                    enablementActionIntent =
                                        cursor
                                            .getString(enablementActionIntentColumnIndex)
                                            ?.toIntent(
                                                affordanceId = affordanceId,
                                            ),
                                    configureIntent =
                                        cursor
                                            .getString(configureIntentColumnIndex)
                                            ?.toIntent(
                                                affordanceId = affordanceId,
                                            ),
                                )
                            )
                        }
                    }
                }
        }
            ?: emptyList()
    }

    override fun observeAffordances(): Flow<List<CustomizationProviderClient.Affordance>> {
        return observeUri(Contract.LockScreenQuickAffordances.AffordanceTable.URI).map {
            queryAffordances()
        }
    }

    override suspend fun querySelections(): List<CustomizationProviderClient.Selection> {
        return withContext(backgroundDispatcher) {
            context.contentResolver
                .query(
                    Contract.LockScreenQuickAffordances.SelectionTable.URI,
                    null,
                    null,
                    null,
                    null,
                )
                ?.use { cursor ->
                    buildList {
                        val slotIdColumnIndex =
                            cursor.getColumnIndex(
                                Contract.LockScreenQuickAffordances.SelectionTable.Columns.SLOT_ID
                            )
                        val affordanceIdColumnIndex =
                            cursor.getColumnIndex(
                                Contract.LockScreenQuickAffordances.SelectionTable.Columns
                                    .AFFORDANCE_ID
                            )
                        val affordanceNameColumnIndex =
                            cursor.getColumnIndex(
                                Contract.LockScreenQuickAffordances.SelectionTable.Columns
                                    .AFFORDANCE_NAME
                            )
                        if (
                            slotIdColumnIndex == -1 ||
                                affordanceIdColumnIndex == -1 ||
                                affordanceNameColumnIndex == -1
                        ) {
                            return@buildList
                        }

                        while (cursor.moveToNext()) {
                            add(
                                CustomizationProviderClient.Selection(
                                    slotId = cursor.getString(slotIdColumnIndex),
                                    affordanceId = cursor.getString(affordanceIdColumnIndex),
                                    affordanceName = cursor.getString(affordanceNameColumnIndex),
                                )
                            )
                        }
                    }
                }
        }
            ?: emptyList()
    }

    override fun observeSelections(): Flow<List<CustomizationProviderClient.Selection>> {
        return observeUri(Contract.LockScreenQuickAffordances.SelectionTable.URI).map {
            querySelections()
        }
    }

    override suspend fun deleteSelection(
        slotId: String,
        affordanceId: String,
    ) {
        withContext(backgroundDispatcher) {
            context.contentResolver.delete(
                Contract.LockScreenQuickAffordances.SelectionTable.URI,
                "${Contract.LockScreenQuickAffordances.SelectionTable.Columns.SLOT_ID} = ? AND" +
                    " ${Contract.LockScreenQuickAffordances.SelectionTable.Columns.AFFORDANCE_ID}" +
                    " = ?",
                arrayOf(
                    slotId,
                    affordanceId,
                ),
            )
        }
    }

    override suspend fun deleteAllSelections(
        slotId: String,
    ) {
        withContext(backgroundDispatcher) {
            context.contentResolver.delete(
                Contract.LockScreenQuickAffordances.SelectionTable.URI,
                Contract.LockScreenQuickAffordances.SelectionTable.Columns.SLOT_ID,
                arrayOf(
                    slotId,
                ),
            )
        }
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    override suspend fun getAffordanceIcon(
        @DrawableRes iconResourceId: Int,
        tintColor: Int,
    ): Drawable {
        return withContext(backgroundDispatcher) {
            context.packageManager
                .getResourcesForApplication(SYSTEM_UI_PACKAGE_NAME)
                .getDrawable(iconResourceId, context.theme)
                .apply { setTint(tintColor) }
        }
    }

    private fun observeUri(
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
                )

                awaitClose { context.contentResolver.unregisterContentObserver(observer) }
            }
            .onStart { emit(Unit) }
            .flowOn(backgroundDispatcher)
    }

    private fun String.toIntent(
        affordanceId: String,
    ): Intent? {
        return try {
            Intent.parseUri(this, Intent.URI_INTENT_SCHEME)
        } catch (e: URISyntaxException) {
            Log.w(TAG, "Cannot parse Uri into Intent for affordance with ID \"$affordanceId\"!")
            null
        }
    }

    companion object {
        private const val TAG = "CustomizationProviderClient"
        private const val SYSTEM_UI_PACKAGE_NAME = "com.android.systemui"
    }
}
