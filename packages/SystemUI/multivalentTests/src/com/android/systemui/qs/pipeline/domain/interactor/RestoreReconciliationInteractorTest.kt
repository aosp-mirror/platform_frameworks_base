package com.android.systemui.qs.pipeline.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.qs.pipeline.data.model.RestoreData
import com.android.systemui.qs.pipeline.data.model.RestoreProcessor
import com.android.systemui.qs.pipeline.data.repository.FakeAutoAddRepository
import com.android.systemui.qs.pipeline.data.repository.FakeQSSettingsRestoredRepository
import com.android.systemui.qs.pipeline.data.repository.FakeTileSpecRepository
import com.android.systemui.qs.pipeline.data.repository.TilesSettingConverter
import com.android.systemui.qs.pipeline.domain.interactor.RestoreReconciliationInteractorTest.TestableRestoreProcessor.Companion.POSTPROCESS
import com.android.systemui.qs.pipeline.domain.interactor.RestoreReconciliationInteractorTest.TestableRestoreProcessor.Companion.PREPROCESS
import com.android.systemui.qs.pipeline.shared.logging.QSPipelineLogger
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.inOrder

@RunWith(AndroidJUnit4::class)
@SmallTest
class RestoreReconciliationInteractorTest : SysuiTestCase() {

    private val tileSpecRepository = FakeTileSpecRepository()
    private val autoAddRepository = FakeAutoAddRepository()

    private val qsSettingsRestoredRepository = FakeQSSettingsRestoredRepository()

    private val restoreProcessor: TestableRestoreProcessor = TestableRestoreProcessor()
    private val qsLogger: QSPipelineLogger = mock()

    private lateinit var underTest: RestoreReconciliationInteractor

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setUp() {
        underTest =
            RestoreReconciliationInteractor(
                tileSpecRepository,
                autoAddRepository,
                qsSettingsRestoredRepository,
                setOf(restoreProcessor),
                qsLogger,
                testScope.backgroundScope,
                testDispatcher
            )
        underTest.start()
    }

    @Test
    fun reconciliationInCorrectOrder_hascurrentAutoAdded() =
        testScope.runTest {
            val user = 10
            val tiles by collectLastValue(tileSpecRepository.tilesSpecs(user))
            val autoAdd by collectLastValue(autoAddRepository.autoAddedTiles(user))

            // Tile b was just auto-added, so we should re-add it in position 1
            // Tile e was auto-added before, but the user had removed it (not in the restored set).
            // It should not be re-added
            val specsBeforeRestore = "a,b,c,d,e"
            val restoredSpecs = "a,c,d,f"
            val autoAddedBeforeRestore = "b,d"
            val restoredAutoAdded = "d,e"

            val restoreData =
                RestoreData(
                    restoredSpecs.toTilesList(),
                    restoredAutoAdded.toTilesSet(),
                    user,
                )

            autoAddedBeforeRestore.toTilesSet().forEach {
                autoAddRepository.markTileAdded(user, it)
            }
            tileSpecRepository.setTiles(user, specsBeforeRestore.toTilesList())

            qsSettingsRestoredRepository.onDataRestored(restoreData)
            runCurrent()

            val expectedTiles = "a,b,c,d,f"
            assertThat(tiles).isEqualTo(expectedTiles.toTilesList())

            val expectedAutoAdd = "b,d,e"
            assertThat(autoAdd).isEqualTo(expectedAutoAdd.toTilesSet())
        }

    @Test
    fun restoreProcessorsCalled() =
        testScope.runTest {
            val user = 10

            val restoredSpecs = "a,c,d,f"
            val restoredAutoAdded = "d,e"

            val restoreData =
                RestoreData(
                    restoredSpecs.toTilesList(),
                    restoredAutoAdded.toTilesSet(),
                    user,
                )

            qsSettingsRestoredRepository.onDataRestored(restoreData)
            runCurrent()

            assertThat(restoreProcessor.calls).containsExactly(PREPROCESS, POSTPROCESS).inOrder()
        }

    private class TestableRestoreProcessor : RestoreProcessor {
        val calls = mutableListOf<Any>()

        override suspend fun preProcessRestore(restoreData: RestoreData) {
            calls.add(PREPROCESS)
        }

        override suspend fun postProcessRestore(restoreData: RestoreData) {
            calls.add(POSTPROCESS)
        }

        companion object {
            val PREPROCESS = Any()
            val POSTPROCESS = Any()
        }
    }

    companion object {
        private fun String.toTilesList() = TilesSettingConverter.toTilesList(this)
        private fun String.toTilesSet() = TilesSettingConverter.toTilesSet(this)
    }
}
