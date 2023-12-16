package com.android.systemui.qs.pipeline.data.repository

import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.qs.pipeline.data.model.RestoreData
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.pipeline.shared.logging.QSPipelineLogger
import com.android.systemui.util.settings.FakeSettings
import com.google.common.truth.Truth
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

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class UserAutoAddRepositoryTest : SysuiTestCase() {
    private val secureSettings = FakeSettings()

    @Mock private lateinit var logger: QSPipelineLogger

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var underTest: UserAutoAddRepository

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        underTest =
            UserAutoAddRepository(
                USER,
                secureSettings,
                logger,
                testScope.backgroundScope,
                testDispatcher,
            )
    }

    @Test
    fun nonExistentSetting_emptySet() =
        testScope.runTest {
            val specs by collectLastValue(underTest.autoAdded())

            assertThat(specs).isEmpty()
        }

    @Test
    fun settingsChange_noChanges() =
        testScope.runTest {
            val value = "a,custom(b/c)"
            store(value)
            val specs by collectLastValue(underTest.autoAdded())
            runCurrent()

            assertThat(specs).isEqualTo(value.toTilesSet())

            val newValue = "a"
            store(newValue)

            assertThat(specs).isEqualTo(value.toTilesSet())
        }

    @Test
    fun noInvalidTileSpecs() =
        testScope.runTest {
            val specs = "d,custom(bad)"
            store(specs)
            val tiles by collectLastValue(underTest.autoAdded())
            runCurrent()

            assertThat(tiles).isEqualTo("d".toTilesSet())
        }

    @Test
    fun markAdded() =
        testScope.runTest {
            val specs = mutableSetOf(TileSpec.create("a"))
            val autoAdded by collectLastValue(underTest.autoAdded())
            runCurrent()

            underTest.markTileAdded(TileSpec.create("a"))

            assertThat(autoAdded).containsExactlyElementsIn(specs)

            specs.add(TileSpec.create("b"))
            underTest.markTileAdded(TileSpec.create("b"))

            assertThat(autoAdded).containsExactlyElementsIn(specs)
        }

    @Test
    fun markAdded_Invalid_noop() =
        testScope.runTest {
            val autoAdded by collectLastValue(underTest.autoAdded())
            runCurrent()

            underTest.markTileAdded(TileSpec.Invalid)

            Truth.assertThat(autoAdded).isEmpty()
        }

    @Test
    fun unmarkAdded() =
        testScope.runTest {
            val specs = "a,custom(b/c)"
            store(specs)
            val autoAdded by collectLastValue(underTest.autoAdded())
            runCurrent()

            underTest.unmarkTileAdded(TileSpec.create("a"))

            assertThat(autoAdded).containsExactlyElementsIn(setOf(TileSpec.create("custom(b/c)")))
        }

    @Test
    fun restore_addsRestoredTiles() =
        testScope.runTest {
            val specs = "a,b"
            val restored = "b,c"
            store(specs)
            val autoAdded by collectLastValue(underTest.autoAdded())
            runCurrent()

            val restoreData =
                RestoreData(
                    emptyList(),
                    restored.toTilesSet(),
                    USER,
                )
            underTest.reconcileRestore(restoreData)

            assertThat(autoAdded).containsExactlyElementsIn("a,b,c".toTilesSet())
        }

    private fun store(specs: String) {
        secureSettings.putStringForUser(SETTING, specs, USER)
    }

    companion object {
        private const val USER = 10
        private const val SETTING = Settings.Secure.QS_AUTO_ADDED_TILES

        private fun String.toTilesSet() = TilesSettingConverter.toTilesSet(this)
    }
}
