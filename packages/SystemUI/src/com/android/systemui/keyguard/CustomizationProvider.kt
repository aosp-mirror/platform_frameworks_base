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

package com.android.systemui.keyguard

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Log
import com.android.systemui.SystemUIAppComponentFactoryBase
import com.android.systemui.SystemUIAppComponentFactoryBase.ContextAvailableCallback
import com.android.systemui.keyguard.domain.interactor.KeyguardQuickAffordanceInteractor
import com.android.systemui.keyguard.ui.preview.KeyguardRemotePreviewManager
import com.android.systemui.shared.customization.data.content.CustomizationProviderContract as Contract
import javax.inject.Inject
import kotlinx.coroutines.runBlocking

class CustomizationProvider :
    ContentProvider(), SystemUIAppComponentFactoryBase.ContextInitializer {

    @Inject lateinit var interactor: KeyguardQuickAffordanceInteractor
    @Inject lateinit var previewManager: KeyguardRemotePreviewManager

    private lateinit var contextAvailableCallback: ContextAvailableCallback

    private val uriMatcher =
        UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(
                Contract.AUTHORITY,
                Contract.LockScreenQuickAffordances.qualifiedTablePath(
                    Contract.LockScreenQuickAffordances.SlotTable.TABLE_NAME,
                ),
                MATCH_CODE_ALL_SLOTS,
            )
            addURI(
                Contract.AUTHORITY,
                Contract.LockScreenQuickAffordances.qualifiedTablePath(
                    Contract.LockScreenQuickAffordances.AffordanceTable.TABLE_NAME,
                ),
                MATCH_CODE_ALL_AFFORDANCES,
            )
            addURI(
                Contract.AUTHORITY,
                Contract.LockScreenQuickAffordances.qualifiedTablePath(
                    Contract.LockScreenQuickAffordances.SelectionTable.TABLE_NAME,
                ),
                MATCH_CODE_ALL_SELECTIONS,
            )
            addURI(
                Contract.AUTHORITY,
                Contract.FlagsTable.TABLE_NAME,
                MATCH_CODE_ALL_FLAGS,
            )
        }

    override fun onCreate(): Boolean {
        return true
    }

    override fun attachInfo(context: Context?, info: ProviderInfo?) {
        contextAvailableCallback.onContextAvailable(checkNotNull(context))
        super.attachInfo(context, info)
    }

    override fun setContextAvailableCallback(callback: ContextAvailableCallback) {
        contextAvailableCallback = callback
    }

    override fun getType(uri: Uri): String? {
        val prefix =
            when (uriMatcher.match(uri)) {
                MATCH_CODE_ALL_SLOTS,
                MATCH_CODE_ALL_AFFORDANCES,
                MATCH_CODE_ALL_FLAGS,
                MATCH_CODE_ALL_SELECTIONS -> "vnd.android.cursor.dir/vnd."
                else -> null
            }

        val tableName =
            when (uriMatcher.match(uri)) {
                MATCH_CODE_ALL_SLOTS ->
                    Contract.LockScreenQuickAffordances.qualifiedTablePath(
                        Contract.LockScreenQuickAffordances.SlotTable.TABLE_NAME,
                    )
                MATCH_CODE_ALL_AFFORDANCES ->
                    Contract.LockScreenQuickAffordances.qualifiedTablePath(
                        Contract.LockScreenQuickAffordances.AffordanceTable.TABLE_NAME,
                    )
                MATCH_CODE_ALL_SELECTIONS ->
                    Contract.LockScreenQuickAffordances.qualifiedTablePath(
                        Contract.LockScreenQuickAffordances.SelectionTable.TABLE_NAME,
                    )
                MATCH_CODE_ALL_FLAGS -> Contract.FlagsTable.TABLE_NAME
                else -> null
            }

        if (prefix == null || tableName == null) {
            return null
        }

        return "$prefix${Contract.AUTHORITY}.$tableName"
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (uriMatcher.match(uri) != MATCH_CODE_ALL_SELECTIONS) {
            throw UnsupportedOperationException()
        }

        return insertSelection(values)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        return when (uriMatcher.match(uri)) {
            MATCH_CODE_ALL_AFFORDANCES -> runBlocking { queryAffordances() }
            MATCH_CODE_ALL_SLOTS -> querySlots()
            MATCH_CODE_ALL_SELECTIONS -> runBlocking { querySelections() }
            MATCH_CODE_ALL_FLAGS -> queryFlags()
            else -> null
        }
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        Log.e(TAG, "Update is not supported!")
        return 0
    }

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        if (uriMatcher.match(uri) != MATCH_CODE_ALL_SELECTIONS) {
            throw UnsupportedOperationException()
        }

        return deleteSelection(uri, selectionArgs)
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        return if (
            requireContext()
                .checkPermission(
                    android.Manifest.permission.BIND_WALLPAPER,
                    Binder.getCallingPid(),
                    Binder.getCallingUid(),
                ) == PackageManager.PERMISSION_GRANTED
        ) {
            previewManager.preview(extras)
        } else {
            null
        }
    }

    private fun insertSelection(values: ContentValues?): Uri? {
        if (values == null) {
            throw IllegalArgumentException("Cannot insert selection, no values passed in!")
        }

        if (
            !values.containsKey(Contract.LockScreenQuickAffordances.SelectionTable.Columns.SLOT_ID)
        ) {
            throw IllegalArgumentException(
                "Cannot insert selection, " +
                    "\"${Contract.LockScreenQuickAffordances.SelectionTable.Columns.SLOT_ID}\"" +
                    " not specified!"
            )
        }

        if (
            !values.containsKey(
                Contract.LockScreenQuickAffordances.SelectionTable.Columns.AFFORDANCE_ID
            )
        ) {
            throw IllegalArgumentException(
                "Cannot insert selection, " +
                    "\"${Contract.LockScreenQuickAffordances
                        .SelectionTable.Columns.AFFORDANCE_ID}\" not specified!"
            )
        }

        val slotId =
            values.getAsString(Contract.LockScreenQuickAffordances.SelectionTable.Columns.SLOT_ID)
        val affordanceId =
            values.getAsString(
                Contract.LockScreenQuickAffordances.SelectionTable.Columns.AFFORDANCE_ID
            )

        if (slotId.isNullOrEmpty()) {
            throw IllegalArgumentException("Cannot insert selection, slot ID was empty!")
        }

        if (affordanceId.isNullOrEmpty()) {
            throw IllegalArgumentException("Cannot insert selection, affordance ID was empty!")
        }

        val success =
            interactor.select(
                slotId = slotId,
                affordanceId = affordanceId,
            )

        return if (success) {
            Log.d(TAG, "Successfully selected $affordanceId for slot $slotId")
            context
                ?.contentResolver
                ?.notifyChange(Contract.LockScreenQuickAffordances.SelectionTable.URI, null)
            Contract.LockScreenQuickAffordances.SelectionTable.URI
        } else {
            Log.d(TAG, "Failed to select $affordanceId for slot $slotId")
            null
        }
    }

    private suspend fun querySelections(): Cursor {
        return MatrixCursor(
                arrayOf(
                    Contract.LockScreenQuickAffordances.SelectionTable.Columns.SLOT_ID,
                    Contract.LockScreenQuickAffordances.SelectionTable.Columns.AFFORDANCE_ID,
                    Contract.LockScreenQuickAffordances.SelectionTable.Columns.AFFORDANCE_NAME,
                )
            )
            .apply {
                val affordanceRepresentationsBySlotId = interactor.getSelections()
                affordanceRepresentationsBySlotId.entries.forEach {
                    (slotId, affordanceRepresentations) ->
                    affordanceRepresentations.forEach { affordanceRepresentation ->
                        addRow(
                            arrayOf(
                                slotId,
                                affordanceRepresentation.id,
                                affordanceRepresentation.name,
                            )
                        )
                    }
                }
            }
    }

    private suspend fun queryAffordances(): Cursor {
        return MatrixCursor(
                arrayOf(
                    Contract.LockScreenQuickAffordances.AffordanceTable.Columns.ID,
                    Contract.LockScreenQuickAffordances.AffordanceTable.Columns.NAME,
                    Contract.LockScreenQuickAffordances.AffordanceTable.Columns.ICON,
                    Contract.LockScreenQuickAffordances.AffordanceTable.Columns.IS_ENABLED,
                    Contract.LockScreenQuickAffordances.AffordanceTable.Columns
                        .ENABLEMENT_INSTRUCTIONS,
                    Contract.LockScreenQuickAffordances.AffordanceTable.Columns
                        .ENABLEMENT_ACTION_TEXT,
                    Contract.LockScreenQuickAffordances.AffordanceTable.Columns
                        .ENABLEMENT_COMPONENT_NAME,
                    Contract.LockScreenQuickAffordances.AffordanceTable.Columns.CONFIGURE_INTENT,
                )
            )
            .apply {
                interactor.getAffordancePickerRepresentations().forEach { representation ->
                    addRow(
                        arrayOf(
                            representation.id,
                            representation.name,
                            representation.iconResourceId,
                            if (representation.isEnabled) 1 else 0,
                            representation.instructions?.joinToString(
                                Contract.LockScreenQuickAffordances.AffordanceTable
                                    .ENABLEMENT_INSTRUCTIONS_DELIMITER
                            ),
                            representation.actionText,
                            representation.actionComponentName,
                            representation.configureIntent?.toUri(0),
                        )
                    )
                }
            }
    }

    private fun querySlots(): Cursor {
        return MatrixCursor(
                arrayOf(
                    Contract.LockScreenQuickAffordances.SlotTable.Columns.ID,
                    Contract.LockScreenQuickAffordances.SlotTable.Columns.CAPACITY,
                )
            )
            .apply {
                interactor.getSlotPickerRepresentations().forEach { representation ->
                    addRow(
                        arrayOf(
                            representation.id,
                            representation.maxSelectedAffordances,
                        )
                    )
                }
            }
    }

    private fun queryFlags(): Cursor {
        return MatrixCursor(
                arrayOf(
                    Contract.FlagsTable.Columns.NAME,
                    Contract.FlagsTable.Columns.VALUE,
                )
            )
            .apply {
                interactor.getPickerFlags().forEach { flag ->
                    addRow(
                        arrayOf(
                            flag.name,
                            if (flag.value) {
                                1
                            } else {
                                0
                            },
                        )
                    )
                }
            }
    }

    private fun deleteSelection(
        uri: Uri,
        selectionArgs: Array<out String>?,
    ): Int {
        if (selectionArgs == null) {
            throw IllegalArgumentException(
                "Cannot delete selection, selection arguments not included!"
            )
        }

        val (slotId, affordanceId) =
            when (selectionArgs.size) {
                1 -> Pair(selectionArgs[0], null)
                2 -> Pair(selectionArgs[0], selectionArgs[1])
                else ->
                    throw IllegalArgumentException(
                        "Cannot delete selection, selection arguments has wrong size, expected to" +
                            " have 1 or 2 arguments, had ${selectionArgs.size} instead!"
                    )
            }

        val deleted =
            interactor.unselect(
                slotId = slotId,
                affordanceId = affordanceId,
            )

        return if (deleted) {
            Log.d(TAG, "Successfully unselected $affordanceId for slot $slotId")
            context?.contentResolver?.notifyChange(uri, null)
            1
        } else {
            Log.d(TAG, "Failed to unselect $affordanceId for slot $slotId")
            0
        }
    }

    companion object {
        private const val TAG = "KeyguardQuickAffordanceProvider"
        private const val MATCH_CODE_ALL_SLOTS = 1
        private const val MATCH_CODE_ALL_AFFORDANCES = 2
        private const val MATCH_CODE_ALL_SELECTIONS = 3
        private const val MATCH_CODE_ALL_FLAGS = 4
    }
}
