package com.android.systemui.qs.pipeline.data.repository

import android.platform.test.annotations.EnabledOnRavenwood
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.qs.pipeline.data.model.RestoreData
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.pipeline.shared.logging.QSPipelineLogger
import com.android.systemui.util.settings.FakeSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
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
@EnabledOnRavenwood
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class UserTileSpecRepositoryTest : SysuiTestCase() {
    private val secureSettings = FakeSettings()
    private val defaultTilesRepository =
        object : DefaultTilesRepository {
            override val defaultTiles: List<TileSpec>
                get() = DEFAULT_TILES.toTileSpecs()
        }

    @Mock private lateinit var logger: QSPipelineLogger

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var underTest: UserTileSpecRepository

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        underTest =
            UserTileSpecRepository(
                USER,
                defaultTilesRepository,
                secureSettings,
                logger,
                testScope.backgroundScope,
                testDispatcher,
            )
    }

    @Test
    fun emptySetting_usesDefaultValue() =
        testScope.runTest {
            val tiles by collectLastValue(underTest.tiles())
            assertThat(tiles).isEqualTo(getDefaultTileSpecs())
        }

    @Test
    fun changeInSettings_valueDoesntChange() =
        testScope.runTest {
            storeTiles("a")
            val tiles by collectLastValue(underTest.tiles())

            assertThat(tiles).isEqualTo(listOf(TileSpec.create("a")))

            storeTiles("a,custom(b/c)")
            assertThat(tiles).isEqualTo(listOf(TileSpec.create("a")))
        }

    @Test
    fun changeInSettings_settingIsRestored() =
        testScope.runTest {
            storeTiles("a")
            val tiles by collectLastValue(underTest.tiles())
            runCurrent()

            storeTiles("a,custom(b/c)")
            assertThat(loadTiles()).isEqualTo("a")
        }

    @Test
    fun invalidTilesAreNotPresent() =
        testScope.runTest {
            val specs = "d,custom(bad)"
            storeTiles(specs)

            val tiles by collectLastValue(underTest.tiles())

            assertThat(tiles).isEqualTo(specs.toTileSpecs().filter { it != TileSpec.Invalid })
        }

    @Test
    fun noValidTiles_defaultSet() =
        testScope.runTest {
            storeTiles("custom(bad),custom()")

            val tiles by collectLastValue(underTest.tiles())

            assertThat(tiles).isEqualTo(getDefaultTileSpecs())
        }

    /*
     * Following tests are for the possible actions that can be performed to the list of tiles.
     * In general, the tests follow this scheme:
     *
     * 1. Set starting tiles in Settings
     * 2. Start collection of flows
     * 3. Call `runCurrent` so all collectors are started (side effects)
     * 4. Perform operation
     * 5. Check that the flow contains the right value
     * 6. Check that settings contains the right value.
     */

    @Test
    fun addTileAtEnd() =
        testScope.runTest {
            storeTiles("a")
            val tiles by collectLastValue(underTest.tiles())
            runCurrent()

            underTest.addTile(TileSpec.create("b"))

            val expected = "a,b"
            assertThat(tiles).isEqualTo(expected.toTileSpecs())
            assertThat(loadTiles()).isEqualTo(expected)
        }

    @Test
    fun addTileAtPosition() =
        testScope.runTest {
            storeTiles("a,custom(b/c)")
            val tiles by collectLastValue(underTest.tiles())
            runCurrent()

            underTest.addTile(TileSpec.create("d"), position = 1)

            val expected = "a,d,custom(b/c)"
            assertThat(tiles).isEqualTo(expected.toTileSpecs())
            assertThat(loadTiles()).isEqualTo(expected)
        }

    @Test
    fun addInvalidTile_noop() =
        testScope.runTest {
            val specs = "a,custom(b/c)"
            storeTiles(specs)
            val tiles by collectLastValue(underTest.tiles())
            runCurrent()

            underTest.addTile(TileSpec.Invalid)

            assertThat(tiles).isEqualTo(specs.toTileSpecs())
            assertThat(loadTiles()).isEqualTo(specs)
        }

    @Test
    fun addTileAtPosition_tooLarge_addedAtEnd() =
        testScope.runTest {
            val specs = "a,custom(b/c)"
            storeTiles(specs)
            val tiles by collectLastValue(underTest.tiles())
            runCurrent()

            underTest.addTile(TileSpec.create("d"), position = 100)

            val expected = "a,custom(b/c),d"
            assertThat(tiles).isEqualTo(expected.toTileSpecs())
            assertThat(loadTiles()).isEqualTo(expected)
        }

    @Test
    fun removeTiles() =
        testScope.runTest {
            storeTiles("a,b")
            val tiles by collectLastValue(underTest.tiles())
            runCurrent()

            underTest.removeTiles(listOf(TileSpec.create("a")))

            assertThat(tiles).isEqualTo("b".toTileSpecs())
            assertThat(loadTiles()).isEqualTo("b")
        }

    @Test
    fun removeTilesNotThere_noop() =
        testScope.runTest {
            val specs = "a,b"
            storeTiles(specs)
            val tiles by collectLastValue(underTest.tiles())
            runCurrent()

            underTest.removeTiles(listOf(TileSpec.create("c")))

            assertThat(tiles).isEqualTo(specs.toTileSpecs())
            assertThat(loadTiles()).isEqualTo(specs)
        }

    @Test
    fun removeInvalidTile_noop() =
        testScope.runTest {
            val specs = "a,b"
            storeTiles(specs)
            val tiles by collectLastValue(underTest.tiles())
            runCurrent()

            underTest.removeTiles(listOf(TileSpec.Invalid))

            assertThat(tiles).isEqualTo(specs.toTileSpecs())
            assertThat(loadTiles()).isEqualTo(specs)
        }

    @Test
    fun removeMultipleTiles() =
        testScope.runTest {
            storeTiles("a,b,c,d")
            val tiles by collectLastValue(underTest.tiles())
            runCurrent()

            underTest.removeTiles(listOf(TileSpec.create("a"), TileSpec.create("c")))

            assertThat(tiles).isEqualTo("b,d".toTileSpecs())
            assertThat(loadTiles()).isEqualTo("b,d")
        }

    @Test
    fun changeTiles() =
        testScope.runTest {
            val specs = "a,custom(b/c)"
            val tiles by collectLastValue(underTest.tiles())
            runCurrent()

            underTest.setTiles(specs.toTileSpecs())

            assertThat(tiles).isEqualTo(specs.toTileSpecs())
            assertThat(loadTiles()).isEqualTo(specs)
        }

    @Test
    fun changeTiles_ignoresInvalid() =
        testScope.runTest {
            val specs = "a,custom(b/c)"
            val tiles by collectLastValue(underTest.tiles())
            runCurrent()

            underTest.setTiles(listOf(TileSpec.Invalid) + specs.toTileSpecs())

            assertThat(tiles).isEqualTo(specs.toTileSpecs())
            assertThat(loadTiles()).isEqualTo(specs)
        }

    @Test
    fun changeTiles_empty_noChanges() =
        testScope.runTest {
            val specs = "a,b,c,d"
            storeTiles(specs)
            val tiles by collectLastValue(underTest.tiles())
            runCurrent()

            underTest.setTiles(emptyList())

            assertThat(tiles).isEqualTo(specs.toTileSpecs())
            assertThat(loadTiles()).isEqualTo(specs)
        }

    @Test
    fun multipleConcurrentRemovals_bothRemoved() =
        testScope.runTest {
            val specs = "a,b,c"
            storeTiles(specs)
            val tiles by collectLastValue(underTest.tiles())
            runCurrent()

            coroutineScope {
                underTest.removeTiles(listOf(TileSpec.create("c")))
                underTest.removeTiles(listOf(TileSpec.create("a")))
            }

            assertThat(tiles).isEqualTo("b".toTileSpecs())
            assertThat(loadTiles()).isEqualTo("b")
        }

    @Test
    fun emptyTilesReplacedByDefaultInSettings() =
        testScope.runTest {
            val tiles by collectLastValue(underTest.tiles())
            runCurrent()

            assertThat(loadTiles())
                .isEqualTo(getDefaultTileSpecs().map { it.spec }.joinToString(","))
        }

    @Test
    fun restoreDataIsProperlyReconciled() =
        testScope.runTest {
            // Tile b was just auto-added, so we should re-add it in position 1
            // Tile e was auto-added before, but the user had removed it (not in the restored set).
            // It should not be re-added
            val specsBeforeRestore = "a,b,c,d,e"
            val restoredSpecs = "a,c,d,f"
            val autoAddedBeforeRestore = "b,d"
            val restoredAutoAdded = "d,e"

            storeTiles(specsBeforeRestore)
            val tiles by collectLastValue(underTest.tiles())
            runCurrent()

            val restoreData =
                RestoreData(
                    restoredSpecs.toTileSpecs(),
                    restoredAutoAdded.toTilesSet(),
                    USER,
                )
            underTest.reconcileRestore(restoreData, autoAddedBeforeRestore.toTilesSet())
            runCurrent()

            val expected = "a,b,c,d,f"
            assertThat(tiles).isEqualTo(expected.toTileSpecs())
            assertThat(loadTiles()).isEqualTo(expected)
        }

    private fun getDefaultTileSpecs(): List<TileSpec> {
        return defaultTilesRepository.defaultTiles
    }

    private fun TestScope.storeTiles(specs: String) {
        secureSettings.putStringForUser(SETTING, specs, USER)
        runCurrent()
    }

    private fun loadTiles(): String? {
        return secureSettings.getStringForUser(SETTING, USER)
    }

    companion object {
        private const val USER = 10
        private const val DEFAULT_TILES = "a,b,c"
        private const val SETTING = Settings.Secure.QS_TILES

        private fun String.toTileSpecs() = TilesSettingConverter.toTilesList(this)
        private fun String.toTilesSet() = TilesSettingConverter.toTilesSet(this)
    }
}
