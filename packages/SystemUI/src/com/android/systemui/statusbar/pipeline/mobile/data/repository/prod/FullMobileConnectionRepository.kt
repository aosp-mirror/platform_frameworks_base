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

package com.android.systemui.statusbar.pipeline.mobile.data.repository.prod

import androidx.annotation.VisibleForTesting
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.TableLogBufferFactory
import com.android.systemui.log.table.logDiffsForTable
import com.android.systemui.statusbar.pipeline.mobile.data.model.NetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

/**
 * A repository that fully implements a mobile connection.
 *
 * This connection could either be a typical mobile connection (see [MobileConnectionRepositoryImpl]
 * or a carrier merged connection (see [CarrierMergedConnectionRepository]). This repository
 * switches between the two types of connections based on whether the connection is currently
 * carrier merged (see [setIsCarrierMerged]).
 */
@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
class FullMobileConnectionRepository(
    override val subId: Int,
    startingIsCarrierMerged: Boolean,
    override val tableLogBuffer: TableLogBuffer,
    private val defaultNetworkName: NetworkNameModel,
    private val networkNameSeparator: String,
    private val globalMobileDataSettingChangedEvent: Flow<Unit>,
    @Application scope: CoroutineScope,
    private val mobileRepoFactory: MobileConnectionRepositoryImpl.Factory,
    private val carrierMergedRepoFactory: CarrierMergedConnectionRepository.Factory,
) : MobileConnectionRepository {
    /**
     * Sets whether this connection is a typical mobile connection or a carrier merged connection.
     */
    fun setIsCarrierMerged(isCarrierMerged: Boolean) {
        _isCarrierMerged.value = isCarrierMerged
    }

    /**
     * Returns true if this repo is currently for a carrier merged connection and false otherwise.
     */
    @VisibleForTesting fun getIsCarrierMerged() = _isCarrierMerged.value

    private val _isCarrierMerged = MutableStateFlow(startingIsCarrierMerged)
    private val isCarrierMerged: StateFlow<Boolean> =
        _isCarrierMerged
            .logDiffsForTable(
                tableLogBuffer,
                columnPrefix = "",
                columnName = "isCarrierMerged",
                initialValue = startingIsCarrierMerged,
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), startingIsCarrierMerged)

    private val mobileRepo: MobileConnectionRepository by lazy {
        mobileRepoFactory.build(
            subId,
            tableLogBuffer,
            defaultNetworkName,
            networkNameSeparator,
            globalMobileDataSettingChangedEvent,
        )
    }

    private val carrierMergedRepo: MobileConnectionRepository by lazy {
        carrierMergedRepoFactory.build(subId, tableLogBuffer, defaultNetworkName)
    }

    @VisibleForTesting
    internal val activeRepo: StateFlow<MobileConnectionRepository> = run {
        val initial =
            if (startingIsCarrierMerged) {
                carrierMergedRepo
            } else {
                mobileRepo
            }

        this.isCarrierMerged
            .mapLatest { isCarrierMerged ->
                if (isCarrierMerged) {
                    carrierMergedRepo
                } else {
                    mobileRepo
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), initial)
    }

    override val cdmaRoaming =
        activeRepo
            .flatMapLatest { it.cdmaRoaming }
            .stateIn(scope, SharingStarted.WhileSubscribed(), activeRepo.value.cdmaRoaming.value)

    override val connectionInfo =
        activeRepo
            .flatMapLatest { it.connectionInfo }
            .stateIn(scope, SharingStarted.WhileSubscribed(), activeRepo.value.connectionInfo.value)

    override val dataEnabled =
        activeRepo
            .flatMapLatest { it.dataEnabled }
            .stateIn(scope, SharingStarted.WhileSubscribed(), activeRepo.value.dataEnabled.value)

    override val numberOfLevels =
        activeRepo
            .flatMapLatest { it.numberOfLevels }
            .stateIn(scope, SharingStarted.WhileSubscribed(), activeRepo.value.numberOfLevels.value)

    override val networkName =
        activeRepo
            .flatMapLatest { it.networkName }
            .stateIn(scope, SharingStarted.WhileSubscribed(), activeRepo.value.networkName.value)

    class Factory
    @Inject
    constructor(
        @Application private val scope: CoroutineScope,
        private val logFactory: TableLogBufferFactory,
        private val mobileRepoFactory: MobileConnectionRepositoryImpl.Factory,
        private val carrierMergedRepoFactory: CarrierMergedConnectionRepository.Factory,
    ) {
        fun build(
            subId: Int,
            startingIsCarrierMerged: Boolean,
            defaultNetworkName: NetworkNameModel,
            networkNameSeparator: String,
            globalMobileDataSettingChangedEvent: Flow<Unit>,
        ): FullMobileConnectionRepository {
            val mobileLogger =
                logFactory.getOrCreate(tableBufferLogName(subId), MOBILE_CONNECTION_BUFFER_SIZE)

            return FullMobileConnectionRepository(
                subId,
                startingIsCarrierMerged,
                mobileLogger,
                defaultNetworkName,
                networkNameSeparator,
                globalMobileDataSettingChangedEvent,
                scope,
                mobileRepoFactory,
                carrierMergedRepoFactory,
            )
        }

        companion object {
            /** The buffer size to use for logging. */
            const val MOBILE_CONNECTION_BUFFER_SIZE = 100

            /** Returns a log buffer name for a mobile connection with the given [subId]. */
            fun tableBufferLogName(subId: Int): String = "MobileConnectionLog [$subId]"
        }
    }
}
