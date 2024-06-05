package com.android.systemui.qs.pipeline.data.repository

import android.platform.test.annotations.EnabledOnRavenwood
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@EnabledOnRavenwood
@RunWith(AndroidJUnit4::class)
class TilesSettingConverterTest : SysuiTestCase() {

    @Test
    fun toTilesList_correctContentAndOrdering() {
        val specString =
            listOf(
                    "c",
                    "b",
                    "custom(x/y)",
                    "d",
                )
                .joinToString(DELIMITER)

        val expected =
            listOf(
                TileSpec.create("c"),
                TileSpec.create("b"),
                TileSpec.create("custom(x/y)"),
                TileSpec.create("d"),
            )

        assertThat(TilesSettingConverter.toTilesList(specString)).isEqualTo(expected)
    }

    @Test
    fun toTilesList_removesInvalid() {
        val specString =
            listOf(
                    "a",
                    "",
                    "b",
                )
                .joinToString(DELIMITER)
        assertThat(TileSpec.create("")).isEqualTo(TileSpec.Invalid)
        val expected =
            listOf(
                TileSpec.create("a"),
                TileSpec.create("b"),
            )
        assertThat(TilesSettingConverter.toTilesList(specString)).isEqualTo(expected)
    }

    @Test
    fun toTilesSet_correctContent() {
        val specString =
            listOf(
                    "c",
                    "b",
                    "custom(x/y)",
                    "d",
                )
                .joinToString(DELIMITER)

        val expected =
            setOf(
                TileSpec.create("c"),
                TileSpec.create("b"),
                TileSpec.create("custom(x/y)"),
                TileSpec.create("d"),
            )

        assertThat(TilesSettingConverter.toTilesSet(specString)).isEqualTo(expected)
    }

    @Test
    fun toTilesSet_removesInvalid() {
        val specString =
            listOf(
                    "a",
                    "",
                    "b",
                )
                .joinToString(DELIMITER)
        assertThat(TileSpec.create("")).isEqualTo(TileSpec.Invalid)
        val expected =
            setOf(
                TileSpec.create("a"),
                TileSpec.create("b"),
            )
        assertThat(TilesSettingConverter.toTilesSet(specString)).isEqualTo(expected)
    }

    companion object {
        private const val DELIMITER = ","
    }
}
