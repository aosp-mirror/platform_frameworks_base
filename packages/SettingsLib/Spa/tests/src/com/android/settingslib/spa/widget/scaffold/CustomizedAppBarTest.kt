/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settingslib.spa.widget.scaffold

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.text.TextStyle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.testutils.rootWidth
import com.android.settingslib.spa.testutils.setContentForSizeAssertions
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class CustomizedAppBarTest {

    @get:Rule val rule = createComposeRule()

    @Test
    fun smallTopAppBar_expandsToScreen() {
        rule
            .setContentForSizeAssertions { CustomizedTopAppBar(title = { Text("Title") }) }
            .assertHeightIsEqualTo(ContainerHeight)
            .assertWidthIsEqualTo(rule.rootWidth())
    }

    @Test
    fun smallTopAppBar_withTitle() {
        val title = "Title"
        rule.setContent {
            Box(Modifier.testTag(TopAppBarTestTag)) { CustomizedTopAppBar(title = { Text(title) }) }
        }
        rule.onNodeWithText(title).assertIsDisplayed()
    }

    @Test
    fun smallTopAppBar_titleDefaultStyle() {
        var textStyle: TextStyle? = null
        var expectedTextStyle: TextStyle? = null
        rule.setContent {
            CustomizedTopAppBar(
                title = {
                    Text("Title")
                    textStyle = LocalTextStyle.current
                    expectedTextStyle = MaterialTheme.typography.titleMedium
                },
            )
        }
        assertThat(textStyle).isNotNull()
        assertThat(textStyle).isEqualTo(expectedTextStyle)
    }

    @Test
    fun smallTopAppBar_contentColor() {
        var titleColor: Color = Color.Unspecified
        var navigationIconColor: Color = Color.Unspecified
        var actionsColor: Color = Color.Unspecified
        var expectedTitleColor: Color = Color.Unspecified
        var expectedNavigationIconColor: Color = Color.Unspecified
        var expectedActionsColor: Color = Color.Unspecified

        rule.setContent {
            CustomizedTopAppBar(
                navigationIcon = {
                    FakeIcon(Modifier.testTag(NavigationIconTestTag))
                    navigationIconColor = LocalContentColor.current
                    expectedNavigationIconColor =
                        TopAppBarDefaults.topAppBarColors().navigationIconContentColor
                    // fraction = 0f to indicate no scroll.
                },
                title = {
                    Text("Title", Modifier.testTag(TitleTestTag))
                    titleColor = LocalContentColor.current
                    expectedTitleColor = TopAppBarDefaults.topAppBarColors().titleContentColor
                },
                actions = {
                    FakeIcon(Modifier.testTag(ActionsTestTag))
                    actionsColor = LocalContentColor.current
                    expectedActionsColor =
                        TopAppBarDefaults.topAppBarColors().actionIconContentColor
                }
            )
        }
        assertThat(navigationIconColor).isNotNull()
        assertThat(titleColor).isNotNull()
        assertThat(actionsColor).isNotNull()
        assertThat(navigationIconColor).isEqualTo(expectedNavigationIconColor)
        assertThat(titleColor).isEqualTo(expectedTitleColor)
        assertThat(actionsColor).isEqualTo(expectedActionsColor)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun topAppBar_enterAlways_allowHorizontalScroll() {
        lateinit var state: LazyListState
        rule.setContent {
            state = rememberLazyListState()
            MultiPageContent(TopAppBarDefaults.enterAlwaysScrollBehavior(), state)
        }

        rule.onNodeWithTag(LazyListTag).performTouchInput { swipeLeft() }
        rule.runOnIdle { assertThat(state.firstVisibleItemIndex).isEqualTo(1) }

        rule.onNodeWithTag(LazyListTag).performTouchInput { swipeRight() }
        rule.runOnIdle { assertThat(state.firstVisibleItemIndex).isEqualTo(0) }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun topAppBar_exitUntilCollapsed_allowHorizontalScroll() {
        lateinit var state: LazyListState
        rule.setContent {
            state = rememberLazyListState()
            MultiPageContent(TopAppBarDefaults.exitUntilCollapsedScrollBehavior(), state)
        }

        rule.onNodeWithTag(LazyListTag).performTouchInput { swipeLeft() }
        rule.runOnIdle { assertThat(state.firstVisibleItemIndex).isEqualTo(1) }

        rule.onNodeWithTag(LazyListTag).performTouchInput { swipeRight() }
        rule.runOnIdle { assertThat(state.firstVisibleItemIndex).isEqualTo(0) }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun topAppBar_pinned_allowHorizontalScroll() {
        lateinit var state: LazyListState
        rule.setContent {
            state = rememberLazyListState()
            MultiPageContent(TopAppBarDefaults.pinnedScrollBehavior(), state)
        }

        rule.onNodeWithTag(LazyListTag).performTouchInput { swipeLeft() }
        rule.runOnIdle { assertThat(state.firstVisibleItemIndex).isEqualTo(1) }

        rule.onNodeWithTag(LazyListTag).performTouchInput { swipeRight() }
        rule.runOnIdle { assertThat(state.firstVisibleItemIndex).isEqualTo(0) }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MultiPageContent(scrollBehavior: TopAppBarScrollBehavior, state: LazyListState) {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                CustomizedTopAppBar(
                    title = { Text(text = "Title") },
                )
            }
        ) { contentPadding ->
            LazyRow(Modifier.fillMaxSize().testTag(LazyListTag), state) {
                items(2) { page ->
                    LazyColumn(
                        modifier = Modifier.fillParentMaxSize(),
                        contentPadding = contentPadding
                    ) {
                        items(50) {
                            Text(
                                modifier = Modifier.fillParentMaxWidth(),
                                text = "Item #$page x $it"
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * An [IconButton] with an [Icon] inside for testing positions.
     *
     * An [IconButton] is defaulted to be 48X48dp, while its child [Icon] is defaulted to 24x24dp.
     */
    private val FakeIcon =
        @Composable { modifier: Modifier ->
            IconButton(
                onClick = { /* doSomething() */ },
                modifier = modifier.semantics(mergeDescendants = true) {}
            ) {
                Icon(ColorPainter(Color.Red), null)
            }
        }

    private val LazyListTag = "lazyList"
    private val TopAppBarTestTag = "bar"
    private val NavigationIconTestTag = "navigationIcon"
    private val TitleTestTag = "title"
    private val ActionsTestTag = "actions"
}
