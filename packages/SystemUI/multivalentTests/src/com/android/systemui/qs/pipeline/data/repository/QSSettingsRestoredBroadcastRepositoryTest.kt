package com.android.systemui.qs.pipeline.data.repository

import android.content.Intent
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.FakeBroadcastDispatcher
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.qs.pipeline.shared.logging.QSPipelineLogger
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class QSSettingsRestoredBroadcastRepositoryTest : SysuiTestCase() {
    private val dispatcher = StandardTestDispatcher()
    private val testScope = TestScope(dispatcher)

    @Mock private lateinit var pipelineLogger: QSPipelineLogger

    private lateinit var underTest: QSSettingsRestoredBroadcastRepository

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        underTest =
            QSSettingsRestoredBroadcastRepository(
                fakeBroadcastDispatcher,
                pipelineLogger,
                testScope.backgroundScope,
                dispatcher,
            )
    }

    @Test
    fun restoreDataAfterBothIntents_tilesRestoredFirst() =
        testScope.runTest {
            runCurrent()
            val restoreData by collectLastValue(underTest.restoreData)
            val user = 0

            val tilesIntent =
                createRestoreIntent(
                    RestoreType.TILES,
                    CURRENT_TILES,
                    RESTORED_TILES,
                )

            val autoAddIntent =
                createRestoreIntent(
                    RestoreType.AUTOADD,
                    CURRENT_AUTO_ADDED_TILES,
                    RESTORED_AUTO_ADDED_TILES,
                )

            sendIntentForUser(tilesIntent, user)

            // No restore data yet as we are missing one of the broadcasts
            assertThat(restoreData).isNull()

            // After the second event, we see the corresponding restore
            sendIntentForUser(autoAddIntent, user)

            with(restoreData!!) {
                assertThat(restoredTiles).isEqualTo(RESTORED_TILES.toTilesList())
                assertThat(restoredAutoAddedTiles).isEqualTo(RESTORED_AUTO_ADDED_TILES.toTilesSet())
                assertThat(userId).isEqualTo(user)
            }
        }

    @Test
    fun restoreDataAfterBothIntents_autoAddRestoredFirst() =
        testScope.runTest {
            runCurrent()
            val restoreData by collectLastValue(underTest.restoreData)
            val user = 0

            val tilesIntent =
                createRestoreIntent(
                    RestoreType.TILES,
                    CURRENT_TILES,
                    RESTORED_TILES,
                )

            val autoAddIntent =
                createRestoreIntent(
                    RestoreType.AUTOADD,
                    CURRENT_AUTO_ADDED_TILES,
                    RESTORED_AUTO_ADDED_TILES,
                )

            sendIntentForUser(autoAddIntent, user)

            // No restore data yet as we are missing one of the broadcasts
            assertThat(restoreData).isNull()

            // After the second event, we see the corresponding restore
            sendIntentForUser(tilesIntent, user)

            with(restoreData!!) {
                assertThat(restoredTiles).isEqualTo(RESTORED_TILES.toTilesList())
                assertThat(restoredAutoAddedTiles).isEqualTo(RESTORED_AUTO_ADDED_TILES.toTilesSet())
                assertThat(userId).isEqualTo(user)
            }
        }

    @Test
    fun interleavedBroadcastsFromDifferentUsers_onlysendDataForCorrectUser() =
        testScope.runTest {
            runCurrent()
            val restoreData by collectLastValue(underTest.restoreData)

            val user0 = 0
            val user10 = 10

            val currentTiles10 = "z,y,x"
            val restoredTiles10 = "x"
            val currentAutoAdded10 = "f"
            val restoredAutoAdded10 = "f,g"

            val tilesIntent0 =
                createRestoreIntent(
                    RestoreType.TILES,
                    CURRENT_TILES,
                    RESTORED_TILES,
                )
            val autoAddIntent0 =
                createRestoreIntent(
                    RestoreType.AUTOADD,
                    CURRENT_AUTO_ADDED_TILES,
                    RESTORED_AUTO_ADDED_TILES,
                )
            val tilesIntent10 =
                createRestoreIntent(
                    RestoreType.TILES,
                    currentTiles10,
                    restoredTiles10,
                )
            val autoAddIntent10 =
                createRestoreIntent(
                    RestoreType.AUTOADD,
                    currentAutoAdded10,
                    restoredAutoAdded10,
                )

            sendIntentForUser(tilesIntent0, user0)
            sendIntentForUser(autoAddIntent10, user10)
            assertThat(restoreData).isNull()

            sendIntentForUser(tilesIntent10, user10)

            with(restoreData!!) {
                assertThat(restoredTiles).isEqualTo(restoredTiles10.toTilesList())
                assertThat(restoredAutoAddedTiles).isEqualTo(restoredAutoAdded10.toTilesSet())
                assertThat(userId).isEqualTo(user10)
            }

            sendIntentForUser(autoAddIntent0, user0)

            with(restoreData!!) {
                assertThat(restoredTiles).isEqualTo(RESTORED_TILES.toTilesList())
                assertThat(restoredAutoAddedTiles).isEqualTo(RESTORED_AUTO_ADDED_TILES.toTilesSet())
                assertThat(userId).isEqualTo(user0)
            }
        }

    private fun sendIntentForUser(intent: Intent, userId: Int) {
        fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
            context,
            intent,
            FakeBroadcastDispatcher.fakePendingResultForUser(userId)
        )
    }

    companion object {
        private const val CURRENT_TILES = "a,b,c,d"
        private const val RESTORED_TILES = "b,a,c"
        private const val CURRENT_AUTO_ADDED_TILES = "d"
        private const val RESTORED_AUTO_ADDED_TILES = "e"

        private fun createRestoreIntent(
            type: RestoreType,
            previousValue: String,
            restoredValue: String,
        ): Intent {
            val setting =
                when (type) {
                    RestoreType.TILES -> Settings.Secure.QS_TILES
                    RestoreType.AUTOADD -> Settings.Secure.QS_AUTO_ADDED_TILES
                }
            return Intent(Intent.ACTION_SETTING_RESTORED)
                .putExtra(Intent.EXTRA_SETTING_NAME, setting)
                .putExtra(Intent.EXTRA_SETTING_PREVIOUS_VALUE, previousValue)
                .putExtra(Intent.EXTRA_SETTING_NEW_VALUE, restoredValue)
        }

        private fun String.toTilesList() = TilesSettingConverter.toTilesList(this)

        private fun String.toTilesSet() = TilesSettingConverter.toTilesSet(this)

        private enum class RestoreType {
            TILES,
            AUTOADD,
        }
    }
}
