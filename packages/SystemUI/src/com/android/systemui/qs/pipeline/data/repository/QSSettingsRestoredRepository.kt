package com.android.systemui.qs.pipeline.data.repository

import android.content.Intent
import android.content.IntentFilter
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.qs.pipeline.data.model.RestoreData
import com.android.systemui.qs.pipeline.data.repository.QSSettingsRestoredRepository.Companion.BUFFER_CAPACITY
import com.android.systemui.qs.pipeline.data.repository.TilesSettingConverter.toTilesList
import com.android.systemui.qs.pipeline.shared.logging.QSPipelineLogger
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn

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
    logger: QSPipelineLogger,
    @Application private val scope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) : QSSettingsRestoredRepository {

    override val restoreData =
        flow {
                val firstIntent = mutableMapOf<Int, Intent>()
                broadcastDispatcher
                    .broadcastFlow(INTENT_FILTER, UserHandle.ALL) { intent, receiver ->
                        intent to receiver.sendingUserId
                    }
                    .filter { it.first.isCorrectSetting() }
                    .collect { (intent, user) ->
                        if (user !in firstIntent) {
                            firstIntent[user] = intent
                        } else {
                            val firstRestored = firstIntent.remove(user)!!
                            emit(processIntents(user, firstRestored, intent))
                        }
                    }
            }
            .catch { Log.e(TAG, "Error parsing broadcast", it) }
            .flowOn(backgroundDispatcher)
            .buffer(BUFFER_CAPACITY)
            .shareIn(scope, SharingStarted.Eagerly)
            .onEach(logger::logSettingsRestored)

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
