package com.android.systemui.communal.layout

import android.util.SizeF
import androidx.compose.material3.Card
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.communal.layout.ui.compose.config.CommunalGridLayoutCard
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class CommunalLayoutEngineTest {
    @Test
    fun distribution_fullLayout() {
        val cards =
            listOf(
                generateCard(CommunalGridLayoutCard.Size.FULL),
                generateCard(CommunalGridLayoutCard.Size.HALF),
                generateCard(CommunalGridLayoutCard.Size.HALF),
                generateCard(CommunalGridLayoutCard.Size.THIRD),
                generateCard(CommunalGridLayoutCard.Size.THIRD),
                generateCard(CommunalGridLayoutCard.Size.THIRD),
            )
        val expected =
            listOf(
                listOf(
                    CommunalGridLayoutCard.Size.FULL,
                ),
                listOf(
                    CommunalGridLayoutCard.Size.HALF,
                    CommunalGridLayoutCard.Size.HALF,
                ),
                listOf(
                    CommunalGridLayoutCard.Size.THIRD,
                    CommunalGridLayoutCard.Size.THIRD,
                    CommunalGridLayoutCard.Size.THIRD,
                ),
            )

        assertDistribution(cards, expected)
    }

    @Test
    fun distribution_layoutWithGaps() {
        val cards =
            listOf(
                generateCard(CommunalGridLayoutCard.Size.HALF),
                generateCard(CommunalGridLayoutCard.Size.THIRD),
                generateCard(CommunalGridLayoutCard.Size.HALF),
                generateCard(CommunalGridLayoutCard.Size.FULL),
                generateCard(CommunalGridLayoutCard.Size.THIRD),
            )
        val expected =
            listOf(
                listOf(
                    CommunalGridLayoutCard.Size.HALF,
                    CommunalGridLayoutCard.Size.THIRD,
                ),
                listOf(
                    CommunalGridLayoutCard.Size.HALF,
                ),
                listOf(
                    CommunalGridLayoutCard.Size.FULL,
                ),
                listOf(
                    CommunalGridLayoutCard.Size.THIRD,
                ),
            )

        assertDistribution(cards, expected)
    }

    private fun assertDistribution(
        cards: List<CommunalGridLayoutCard>,
        expected: List<List<CommunalGridLayoutCard.Size>>,
    ) {
        val result = CommunalLayoutEngine.distributeCardsIntoColumns(cards)

        for (c in expected.indices) {
            for (r in expected[c].indices) {
                assertThat(result[c][r].size).isEqualTo(expected[c][r])
            }
        }
    }

    private fun generateCard(size: CommunalGridLayoutCard.Size): CommunalGridLayoutCard {
        return object : CommunalGridLayoutCard() {
            override val supportedSizes = listOf(size)

            @Composable
            override fun Content(modifier: Modifier, size: SizeF) {
                Card(modifier = modifier, content = {})
            }
        }
    }
}
