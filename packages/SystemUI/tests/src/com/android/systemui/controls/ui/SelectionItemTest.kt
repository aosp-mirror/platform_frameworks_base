package com.android.systemui.controls.ui

import android.content.ComponentName
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.controls.controller.StructureInfo
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
class SelectionItemTest : SysuiTestCase() {

    @Test
    fun testMatchBadComponentName_false() {
        val selectionItem =
            SelectionItem(
                appName = "app",
                structure = "structure",
                icon = mock(),
                componentName = ComponentName("pkg", "cls"),
                uid = 0,
                panelComponentName = null
            )

        assertThat(
                selectionItem.matches(
                    SelectedItem.StructureItem(
                        StructureInfo(ComponentName("", ""), "s", emptyList())
                    )
                )
            )
            .isFalse()
        assertThat(selectionItem.matches(SelectedItem.PanelItem("name", ComponentName("", ""))))
            .isFalse()
    }

    @Test
    fun testMatchSameComponentName_panelSelected_true() {
        val componentName = ComponentName("pkg", "cls")

        val selectionItem =
            SelectionItem(
                appName = "app",
                structure = "structure",
                icon = mock(),
                componentName = componentName,
                uid = 0,
                panelComponentName = null
            )
        assertThat(selectionItem.matches(SelectedItem.PanelItem("name", componentName))).isTrue()
    }

    @Test
    fun testMatchSameComponentName_panelSelection_true() {
        val componentName = ComponentName("pkg", "cls")

        val selectionItem =
            SelectionItem(
                appName = "app",
                structure = "structure",
                icon = mock(),
                componentName = componentName,
                uid = 0,
                panelComponentName = ComponentName("pkg", "panel")
            )
        assertThat(selectionItem.matches(SelectedItem.PanelItem("name", componentName))).isTrue()
    }

    @Test
    fun testMatchSameComponentSameStructure_true() {
        val componentName = ComponentName("pkg", "cls")
        val structureName = "structure"

        val structureItem =
            SelectedItem.StructureItem(StructureInfo(componentName, structureName, emptyList()))

        val selectionItem =
            SelectionItem(
                appName = "app",
                structure = structureName,
                icon = mock(),
                componentName = componentName,
                uid = 0,
                panelComponentName = null
            )
        assertThat(selectionItem.matches(structureItem)).isTrue()
    }

    @Test
    fun testMatchSameComponentDifferentStructure_false() {
        val componentName = ComponentName("pkg", "cls")
        val structureName = "structure"

        val structureItem =
            SelectedItem.StructureItem(StructureInfo(componentName, structureName, emptyList()))

        val selectionItem =
            SelectionItem(
                appName = "app",
                structure = "other",
                icon = mock(),
                componentName = componentName,
                uid = 0,
                panelComponentName = null
            )
        assertThat(selectionItem.matches(structureItem)).isFalse()
    }
}
