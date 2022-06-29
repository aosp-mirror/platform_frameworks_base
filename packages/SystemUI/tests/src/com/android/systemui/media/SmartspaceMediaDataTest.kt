package com.android.systemui.media

import android.app.smartspace.SmartspaceAction
import android.graphics.drawable.Icon
import androidx.test.filters.SmallTest
import com.android.internal.logging.InstanceId
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Test

@SmallTest
class SmartspaceMediaDataTest : SysuiTestCase() {

    private val icon: Icon = Icon.createWithResource(context, R.drawable.ic_media_play)

    @Test
    fun getValidRecommendations_onlyReturnsRecsWithIcons() {
        val withIcon1 = SmartspaceAction.Builder("id", "title").setIcon(icon).build()
        val withIcon2 = SmartspaceAction.Builder("id", "title").setIcon(icon).build()
        val withoutIcon1 = SmartspaceAction.Builder("id", "title").setIcon(null).build()
        val withoutIcon2 = SmartspaceAction.Builder("id", "title").setIcon(null).build()
        val recommendations = listOf(withIcon1, withoutIcon1, withIcon2, withoutIcon2)

        val data = DEFAULT_DATA.copy(recommendations = recommendations)

        assertThat(data.getValidRecommendations()).isEqualTo(listOf(withIcon1, withIcon2))
    }

    @Test
    fun isValid_emptyList_returnsFalse() {
        val data = DEFAULT_DATA.copy(recommendations = listOf())

        assertThat(data.isValid()).isFalse()
    }

    @Test
    fun isValid_tooFewRecs_returnsFalse() {
        val data = DEFAULT_DATA.copy(
            recommendations = listOf(
                SmartspaceAction.Builder("id", "title").setIcon(icon).build()
            )
        )

        assertThat(data.isValid()).isFalse()
    }

    @Test
    fun isValid_tooFewRecsWithIcons_returnsFalse() {
        val recommendations = mutableListOf<SmartspaceAction>()
        // Add one fewer recommendation w/ icon than the number required
        for (i in 1 until NUM_REQUIRED_RECOMMENDATIONS) {
            recommendations.add(
                SmartspaceAction.Builder("id", "title").setIcon(icon).build()
            )
        }
        for (i in 1 until 3) {
            recommendations.add(
                SmartspaceAction.Builder("id", "title").setIcon(null).build()
            )
        }

        val data = DEFAULT_DATA.copy(recommendations = recommendations)

        assertThat(data.isValid()).isFalse()
    }

    @Test
    fun isValid_enoughRecsWithIcons_returnsTrue() {
        val recommendations = mutableListOf<SmartspaceAction>()
        // Add the number of required recommendations
        for (i in 0 until NUM_REQUIRED_RECOMMENDATIONS) {
            recommendations.add(
                SmartspaceAction.Builder("id", "title").setIcon(icon).build()
            )
        }

        val data = DEFAULT_DATA.copy(recommendations = recommendations)

        assertThat(data.isValid()).isTrue()
    }

    @Test
    fun isValid_manyRecsWithIcons_returnsTrue() {
        val recommendations = mutableListOf<SmartspaceAction>()
        // Add more than enough recommendations
        for (i in 0 until NUM_REQUIRED_RECOMMENDATIONS + 3) {
            recommendations.add(
                SmartspaceAction.Builder("id", "title").setIcon(icon).build()
            )
        }

        val data = DEFAULT_DATA.copy(recommendations = recommendations)

        assertThat(data.isValid()).isTrue()
    }
}

private val DEFAULT_DATA = SmartspaceMediaData(
    targetId = "INVALID",
    isActive = false,
    packageName = "INVALID",
    cardAction = null,
    recommendations = emptyList(),
    dismissIntent = null,
    headphoneConnectionTimeMillis = 0,
    instanceId = InstanceId.fakeInstanceId(-1)
)
