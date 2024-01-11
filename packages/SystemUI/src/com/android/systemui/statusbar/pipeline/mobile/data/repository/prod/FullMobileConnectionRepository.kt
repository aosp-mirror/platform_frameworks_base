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
import com.android.systemui.statusbar.pipeline.mobile.data.model.SubscriptionModel
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
    subscriptionModel: Flow<SubscriptionModel?>,
    private val defaultNetworkName: NetworkNameModel,
    private val networkNameSeparator: String,
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
            subscriptionModel,
            defaultNetworkName,
            networkNameSeparator,
        )
    }

    private val carrierMergedRepo: MobileConnectionRepository by lazy {
        carrierMergedRepoFactory.build(subId, tableLogBuffer)
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

    override val carrierId =
        activeRepo
            .flatMapLatest { it.carrierId }
            .stateIn(scope, SharingStarted.WhileSubscribed(), activeRepo.value.carrierId.value)

    override val cdmaRoaming =
        activeRepo
            .flatMapLatest { it.cdmaRoaming }
            .stateIn(scope, SharingStarted.WhileSubscribed(), activeRepo.value.cdmaRoaming.value)

    override val isEmergencyOnly =
        activeRepo
            .flatMapLatest { it.isEmergencyOnly }
            .logDiffsForTable(
                tableLogBuffer,
                columnPrefix = "",
                columnName = COL_EMERGENCY,
                activeRepo.value.isEmergencyOnly.value
            )
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                activeRepo.value.isEmergencyOnly.value
            )

    override val isRoaming =
        activeRepo
            .flatMapLatest { it.isRoaming }
            .logDiffsForTable(
                tableLogBuffer,
                columnPrefix = "",
                columnName = COL_ROAMING,
                activeRepo.value.isRoaming.value
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), activeRepo.value.isRoaming.value)

    override val operatorAlphaShort =
        activeRepo
            .flatMapLatest { it.operatorAlphaShort }
            .logDiffsForTable(
                tableLogBuffer,
                columnPrefix = "",
                columnName = COL_OPERATOR,
                activeRepo.value.operatorAlphaShort.value
            )
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                activeRepo.value.operatorAlphaShort.value
            )

    override val isInService =
        activeRepo
            .flatMapLatest { it.isInService }
            .logDiffsForTable(
                tableLogBuffer,
                columnPrefix = "",
                columnName = COL_IS_IN_SERVICE,
                activeRepo.value.isInService.value
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), activeRepo.value.isInService.value)

    override val isNonTerrestrial =
        activeRepo
            .flatMapLatest { it.isNonTerrestrial }
            .logDiffsForTable(
                tableLogBuffer,
                columnPrefix = "",
                columnName = COL_IS_NTN,
                activeRepo.value.isNonTerrestrial.value
            )
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                activeRepo.value.isNonTerrestrial.value
            )

    override val isGsm =
        activeRepo
            .flatMapLatest { it.isGsm }
            .logDiffsForTable(
                tableLogBuffer,
                columnPrefix = "",
                columnName = COL_IS_GSM,
                activeRepo.value.isGsm.value
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), activeRepo.value.isGsm.value)

    override val cdmaLevel =
        activeRepo
            .flatMapLatest { it.cdmaLevel }
            .logDiffsForTable(
                tableLogBuffer,
                columnPrefix = "",
                columnName = COL_CDMA_LEVEL,
                activeRepo.value.cdmaLevel.value
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), activeRepo.value.cdmaLevel.value)

    override val primaryLevel =
        activeRepo
            .flatMapLatest { it.primaryLevel }
            .logDiffsForTable(
                tableLogBuffer,
                columnPrefix = "",
                columnName = COL_PRIMARY_LEVEL,
                activeRepo.value.primaryLevel.value
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), activeRepo.value.primaryLevel.value)

    override val dataConnectionState =
        activeRepo
            .flatMapLatest { it.dataConnectionState }
            .logDiffsForTable(
                tableLogBuffer,
                columnPrefix = "",
                activeRepo.value.dataConnectionState.value
            )
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                activeRepo.value.dataConnectionState.value
            )

    override val dataActivityDirection =
        activeRepo
            .flatMapLatest { it.dataActivityDirection }
            .logDiffsForTable(
                tableLogBuffer,
                columnPrefix = "",
                activeRepo.value.dataActivityDirection.value
            )
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                activeRepo.value.dataActivityDirection.value
            )

    override val carrierNetworkChangeActive =
        activeRepo
            .flatMapLatest { it.carrierNetworkChangeActive }
            .logDiffsForTable(
                tableLogBuffer,
                columnPrefix = "",
                columnName = COL_CARRIER_NETWORK_CHANGE,
                activeRepo.value.carrierNetworkChangeActive.value
            )
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                activeRepo.value.carrierNetworkChangeActive.value
            )

    override val resolvedNetworkType =
        activeRepo
            .flatMapLatest { it.resolvedNetworkType }
            .logDiffsForTable(
                tableLogBuffer,
                columnPrefix = "",
                activeRepo.value.resolvedNetworkType.value
            )
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                activeRepo.value.resolvedNetworkType.value
            )

    override val dataEnabled =
        activeRepo
            .flatMapLatest { it.dataEnabled }
            .logDiffsForTable(
                tableLogBuffer,
                columnPrefix = "",
                columnName = "dataEnabled",
                initialValue = activeRepo.value.dataEnabled.value,
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), activeRepo.value.dataEnabled.value)

    override val numberOfLevels =
        activeRepo
            .flatMapLatest { it.numberOfLevels }
            .stateIn(scope, SharingStarted.WhileSubscribed(), activeRepo.value.numberOfLevels.value)

    override val networkName =
        activeRepo
            .flatMapLatest { it.networkName }
            .logDiffsForTable(
                tableLogBuffer,
                columnPrefix = "",
                initialValue = activeRepo.value.networkName.value,
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), activeRepo.value.networkName.value)

    override val carrierName =
        activeRepo
            .flatMapLatest { it.carrierName }
            .logDiffsForTable(
                tableLogBuffer,
                columnPrefix = "",
                initialValue = activeRepo.value.carrierName.value,
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), activeRepo.value.carrierName.value)

    override val isAllowedDuringAirplaneMode =
        activeRepo
            .flatMapLatest { it.isAllowedDuringAirplaneMode }
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                activeRepo.value.isAllowedDuringAirplaneMode.value,
            )

    override val hasPrioritizedNetworkCapabilities =
        activeRepo
            .flatMapLatest { it.hasPrioritizedNetworkCapabilities }
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                activeRepo.value.hasPrioritizedNetworkCapabilities.value,
            )

    override suspend fun isInEcmMode(): Boolean = activeRepo.value.isInEcmMode()

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
            subscriptionModel: Flow<SubscriptionModel?>,
            defaultNetworkName: NetworkNameModel,
            networkNameSeparator: String,
        ): FullMobileConnectionRepository {
            val mobileLogger =
                logFactory.getOrCreate(tableBufferLogName(subId), MOBILE_CONNECTION_BUFFER_SIZE)

            return FullMobileConnectionRepository(
                subId,
                startingIsCarrierMerged,
                mobileLogger,
                subscriptionModel,
                defaultNetworkName,
                networkNameSeparator,
                scope,
                mobileRepoFactory,
                carrierMergedRepoFactory,
            )
        }

        companion object {
            /** The buffer size to use for logging. */
            const val MOBILE_CONNECTION_BUFFER_SIZE = 100

            /** Returns a log buffer name for a mobile connection with the given [subId]. */
            fun tableBufferLogName(subId: Int): String = "MobileConnectionLog[$subId]"
        }
    }

    companion object {
        const val COL_CARRIER_ID = "carrierId"
        const val COL_CARRIER_NETWORK_CHANGE = "carrierNetworkChangeActive"
        const val COL_CDMA_LEVEL = "cdmaLevel"
        const val COL_EMERGENCY = "emergencyOnly"
        const val COL_IS_NTN = "isNtn"
        const val COL_IS_GSM = "isGsm"
        const val COL_IS_IN_SERVICE = "isInService"
        const val COL_OPERATOR = "operatorName"
        const val COL_PRIMARY_LEVEL = "primaryLevel"
        const val COL_ROAMING = "roaming"
    }
}
