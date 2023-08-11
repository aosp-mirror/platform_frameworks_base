package com.android.systemui.communal.ui.view.layout.blueprints

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.constraintlayout.widget.ConstraintSet
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
class DefaultCommunalBlueprintTest : SysuiTestCase() {
    private lateinit var blueprint: DefaultCommunalBlueprint

    @Before
    fun setup() {
        blueprint = DefaultCommunalBlueprint()
    }

    @Test
    fun apply_doesNothing() {
        val cs = ConstraintSet()
        blueprint.apply(cs)
        // Nothing happens yet.
    }
}