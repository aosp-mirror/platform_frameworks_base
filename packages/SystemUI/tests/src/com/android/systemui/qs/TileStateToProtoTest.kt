package com.android.systemui.qs

import android.content.ComponentName
import android.service.quicksettings.Tile
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.qs.external.CustomTile
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidTestingRunner::class)
@SmallTest
class TileStateToProtoTest : SysuiTestCase() {

    companion object {
        private const val TEST_LABEL = "label"
        private const val TEST_SUBTITLE = "subtitle"
        private const val TEST_SPEC = "spec"
        private val TEST_COMPONENT = ComponentName("test_pkg", "test_cls")
    }

    @Test
    fun platformTile_INACTIVE() {
        val state =
            QSTile.State().apply {
                spec = TEST_SPEC
                label = TEST_LABEL
                secondaryLabel = TEST_SUBTITLE
                state = Tile.STATE_INACTIVE
            }
        val proto = state.toProto()

        assertThat(proto).isNotNull()
        assertThat(proto?.hasSpec()).isTrue()
        assertThat(proto?.spec).isEqualTo(TEST_SPEC)
        assertThat(proto?.hasComponentName()).isFalse()
        assertThat(proto?.label).isEqualTo(TEST_LABEL)
        assertThat(proto?.secondaryLabel).isEqualTo(TEST_SUBTITLE)
        assertThat(proto?.state).isEqualTo(Tile.STATE_INACTIVE)
        assertThat(proto?.hasBooleanState()).isFalse()
    }

    @Test
    fun componentTile_UNAVAILABLE() {
        val state =
            QSTile.State().apply {
                spec = CustomTile.toSpec(TEST_COMPONENT)
                label = TEST_LABEL
                secondaryLabel = TEST_SUBTITLE
                state = Tile.STATE_UNAVAILABLE
            }
        val proto = state.toProto()

        assertThat(proto).isNotNull()
        assertThat(proto?.hasSpec()).isFalse()
        assertThat(proto?.hasComponentName()).isTrue()
        val componentName = proto?.componentName
        assertThat(componentName?.packageName).isEqualTo(TEST_COMPONENT.packageName)
        assertThat(componentName?.className).isEqualTo(TEST_COMPONENT.className)
        assertThat(proto?.label).isEqualTo(TEST_LABEL)
        assertThat(proto?.secondaryLabel).isEqualTo(TEST_SUBTITLE)
        assertThat(proto?.state).isEqualTo(Tile.STATE_UNAVAILABLE)
        assertThat(proto?.hasBooleanState()).isFalse()
    }

    @Test
    fun booleanState_ACTIVE() {
        val state =
            QSTile.BooleanState().apply {
                spec = TEST_SPEC
                label = TEST_LABEL
                secondaryLabel = TEST_SUBTITLE
                state = Tile.STATE_ACTIVE
                value = true
            }
        val proto = state.toProto()

        assertThat(proto).isNotNull()
        assertThat(proto?.hasSpec()).isTrue()
        assertThat(proto?.spec).isEqualTo(TEST_SPEC)
        assertThat(proto?.hasComponentName()).isFalse()
        assertThat(proto?.label).isEqualTo(TEST_LABEL)
        assertThat(proto?.secondaryLabel).isEqualTo(TEST_SUBTITLE)
        assertThat(proto?.state).isEqualTo(Tile.STATE_ACTIVE)
        assertThat(proto?.hasBooleanState()).isTrue()
        assertThat(proto?.booleanState).isTrue()
    }

    @Test
    fun noSpec_returnsNull() {
        val state =
            QSTile.State().apply {
                label = TEST_LABEL
                secondaryLabel = TEST_SUBTITLE
                state = Tile.STATE_ACTIVE
            }
        val proto = state.toProto()

        assertThat(proto).isNull()
    }
}
