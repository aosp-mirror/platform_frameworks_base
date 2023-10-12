package com.android.systemui.communal.layout.ui.compose.config

import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class CommunalGridLayoutConfigTest {
    @Test
    fun cardWidth() {
        Truth.assertThat(
                CommunalGridLayoutConfig(
                        gridColumnSize = 5.dp,
                        gridGutter = 3.dp,
                        gridHeight = 17.dp,
                        gridColumnsPerCard = 1,
                    )
                    .cardWidth
            )
            .isEqualTo(5.dp)

        Truth.assertThat(
                CommunalGridLayoutConfig(
                        gridColumnSize = 5.dp,
                        gridGutter = 3.dp,
                        gridHeight = 17.dp,
                        gridColumnsPerCard = 2,
                    )
                    .cardWidth
            )
            .isEqualTo(13.dp)

        Truth.assertThat(
                CommunalGridLayoutConfig(
                        gridColumnSize = 5.dp,
                        gridGutter = 3.dp,
                        gridHeight = 17.dp,
                        gridColumnsPerCard = 3,
                    )
                    .cardWidth
            )
            .isEqualTo(21.dp)
    }

    @Test
    fun cardHeight() {
        val config =
            CommunalGridLayoutConfig(
                gridColumnSize = 5.dp,
                gridGutter = 2.dp,
                gridHeight = 10.dp,
                gridColumnsPerCard = 3,
            )

        Truth.assertThat(config.cardHeight(CommunalGridLayoutCard.Size.FULL)).isEqualTo(10.dp)
        Truth.assertThat(config.cardHeight(CommunalGridLayoutCard.Size.HALF)).isEqualTo(4.dp)
        Truth.assertThat(config.cardHeight(CommunalGridLayoutCard.Size.THIRD)).isEqualTo(2.dp)
    }
}
