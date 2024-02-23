package com.android.systemui.qs.pipeline.data.repository

import android.content.Intent
import android.content.IntentFilter
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.common.shared.model.PackageChangeModel.Empty.user
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.qs.pipeline.data.model.RestoreData
import com.android.systemui.qs.pipeline.data.repository.QSSettingsRestoredRepository.Companion.BUFFER_CAPACITY
import com.android.systemui.qs.pipeline.data.repository.TilesSettingConverter.toTilesList
import com.android.systemui.qs.pipeline.shared.logging.QSPipelineLogger
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.util.kotlin.emitOnStart
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flattenConcat
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Provides restored data (from Backup and Restore) for Quick Settings pipeline */
interface QSSettingsRestoredRepository {
    val restoreData: Flow<RestoreData>

    companion object {
        // This capacity is the number of restore data that we will keep buffered in the shared
        // flow. It is unlikely that at any given time there would be this many restores being
        // processed by consumers, but just in case that a couple of users are restored at the
        // same time and they need to be replayed for the consumers of the flow.
        const val BUFFER_CAPACITY = 10
    }
}

@SysUISingleton
class QSSettingsRestoredBroadcastRepository
@Inject
constructor(
    broadcastDispatcher: BroadcastDispatcher,
    private val deviceProvisionedController: DeviceProvisionedController,
    logger: QSPipelineLogger,
    @Application private val scope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) : QSSettingsRestoredRepository {

    private val onUserSetupChangedForSomeUser =
        conflatedCallbackFlow {
                val callback =
                    object : DeviceProvisionedController.DeviceProvisionedListener {
                        override fun onUserSetupChanged() {
                            trySend(Unit)
                        }
                    }
                deviceProvisionedController.addCallback(callback)
                awaitClose { deviceProvisionedController.removeCallback(callback) }
            }
            .emitOnStart()

    @OptIn(ExperimentalCoroutinesApi::class)
    override val restoreData =
        run {
                val mutex = Mutex()
                val firstIntent = mutableMapOf<Int, Intent>()

                val restoresFromTwoBroadcasts: Flow<RestoreData> =
                    broadcastDispatcher
                        .broadcastFlow(INTENT_FILTER, UserHandle.ALL) { intent, receiver ->
                            intent to receiver.sendingUserId
                        }
                        .filter { it.first.isCorrectSetting() }
                        .mapNotNull { (intent, user) ->
                            mutex.withLock {
                                if (user !in firstIntent) {
                                    firstIntent[user] = intent
                                    null
                                } else {
                                    val firstRestored = firstIntent.remove(user)!!
                                    processIntents(user, firstRestored, intent)
                                }
                            }
                        }
                        .catch { Log.e(TAG, "Error parsing broadcast", it) }

                val restoresFromUserSetup: Flow<RestoreData> =
                    onUserSetupChangedForSomeUser
                        .map {
                            mutex.withLock {
                                firstIntent
                                    .filter { (userId, _) ->
                                        deviceProvisionedController.isUserSetup(userId)
                                    }
                                    .onEach { firstIntent.remove(it.key) }
                                    .map { processSingleIntent(it.key, it.value) }
                                    .asFlow()
                            }
                        }
                        .flattenConcat()
                        .catch { Log.e(TAG, "Error parsing tiles intent after user setup", it) }
                        .onEach { logger.logSettingsRestoredOnUserSetupComplete(it.userId) }
                merge(restoresFromTwoBroadcasts, restoresFromUserSetup)
            }
            .flowOn(backgroundDispatcher)
            .buffer(BUFFER_CAPACITY)
            .shareIn(scope, SharingStarted.Eagerly)
            .onEach(logger::logSettingsRestored)

    private fun processSingleIntent(user: Int, intent: Intent): RestoreData {
        intent.validateIntent()
        if (intent.getStringExtra(Intent.EXTRA_SETTING_NAME) != TILES_SETTING) {
            throw IllegalStateException(
                "Single intent restored for user $user is not tiles: $intent"
            )
        }
        return RestoreData(
            (intent.getStringExtra(Intent.EXTRA_SETTING_NEW_VALUE) ?: "").toTilesList(),
            emptySet(),
            user,
        )
    }

    private fun processIntents(user: Int, intent1: Intent, intent2: Intent): RestoreData {
        intent1.validateIntent()
        intent2.validateIntent()
        val setting1 = intent1.getStringExtra(Intent.EXTRA_SETTING_NAME)
        val setting2 = intent2.getStringExtra(Intent.EXTRA_SETTING_NAME)
        val (tiles, autoAdd) =
            if (setting1 == TILES_SETTING && setting2 == AUTO_ADD_SETTING) {
                intent1 to intent2
            } else if (setting1 == AUTO_ADD_SETTING && setting2 == TILES_SETTING) {
                intent2 to intent1
            } else {
                throw IllegalStateException("Wrong intents ($intent1, $intent2)")
            }

        return RestoreData(
            (tiles.getStringExtra(Intent.EXTRA_SETTING_NEW_VALUE) ?: "").toTilesList(),
            (autoAdd.getStringExtra(Intent.EXTRA_SETTING_NEW_VALUE) ?: "").toTilesSet(),
            user,
        )
    }

    private companion object {
        private const val TAG = "QSSettingsRestoredBroadcastRepository"

        private val INTENT_FILTER = IntentFilter(Intent.ACTION_SETTING_RESTORED)
        private const val TILES_SETTING = Settings.Secure.QS_TILES
        private const val AUTO_ADD_SETTING = Settings.Secure.QS_AUTO_ADDED_TILES
        private val requiredExtras =
            listOf(
                Intent.EXTRA_SETTING_NAME,
                Intent.EXTRA_SETTING_PREVIOUS_VALUE,
                Intent.EXTRA_SETTING_NEW_VALUE,
            )

        private fun Intent.isCorrectSetting(): Boolean {
            val setting = getStringExtra(Intent.EXTRA_SETTING_NAME)
            return setting == TILES_SETTING || setting == AUTO_ADD_SETTING
        }

        private fun Intent.validateIntent() {
            requiredExtras.forEach { extra ->
                if (!hasExtra(extra)) {
                    throw IllegalStateException("$this doesn't have $extra")
                }
            }
        }

        private fun String.toTilesList() = toTilesList(this)

        private fun String.toTilesSet() = TilesSettingConverter.toTilesSet(this)
    }
}
