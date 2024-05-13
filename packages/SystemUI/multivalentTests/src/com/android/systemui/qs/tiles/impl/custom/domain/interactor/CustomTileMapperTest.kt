/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.qs.tiles.impl.custom.domain.interactor

import android.app.IUriGrantsManager
import android.content.ComponentName
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.graphics.drawable.TestStubDrawable
import android.os.UserHandle
import android.service.quicksettings.Tile
import android.widget.Button
import android.widget.Switch
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.impl.custom.QSTileStateSubject.Companion.assertThat
import com.android.systemui.qs.tiles.impl.custom.customTileQsTileConfig
import com.android.systemui.qs.tiles.impl.custom.customTileSpec
import com.android.systemui.qs.tiles.impl.custom.domain.CustomTileMapper
import com.android.systemui.qs.tiles.impl.custom.domain.entity.CustomTileDataModel
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class CustomTileMapperTest : SysuiTestCase() {

    private val uriGrantsManager: IUriGrantsManager = mock {}
    private val kosmos =
        testKosmos().apply { customTileSpec = TileSpec.Companion.create(TEST_COMPONENT) }
    private val underTest by lazy {
        CustomTileMapper(
            context = mock { whenever(createContextAsUser(any(), any())).thenReturn(context) },
            uriGrantsManager = uriGrantsManager,
        )
    }

    @Test
    fun stateHasPendingBinding() =
        with(kosmos) {
            testScope.runTest {
                val actual =
                    underTest.map(
                        customTileQsTileConfig,
                        createModel(hasPendingBind = true),
                    )
                val expected =
                    createTileState(
                        activationState = QSTileState.ActivationState.UNAVAILABLE,
                        actions = setOf(QSTileState.UserAction.LONG_CLICK),
                    )

                assertThat(actual).isEqualTo(expected)
            }
        }

    @Test
    fun stateActive() =
        with(kosmos) {
            testScope.runTest {
                val actual =
                    underTest.map(
                        customTileQsTileConfig,
                        createModel(tileState = Tile.STATE_ACTIVE),
                    )
                val expected =
                    createTileState(
                        activationState = QSTileState.ActivationState.ACTIVE,
                    )

                assertThat(actual).isEqualTo(expected)
            }
        }

    @Test
    fun stateInactive() =
        with(kosmos) {
            testScope.runTest {
                val actual =
                    underTest.map(
                        customTileQsTileConfig,
                        createModel(tileState = Tile.STATE_INACTIVE),
                    )
                val expected =
                    createTileState(
                        activationState = QSTileState.ActivationState.INACTIVE,
                    )

                assertThat(actual).isEqualTo(expected)
            }
        }

    @Test
    fun stateUnavailable() =
        with(kosmos) {
            testScope.runTest {
                val actual =
                    underTest.map(
                        customTileQsTileConfig,
                        createModel(tileState = Tile.STATE_UNAVAILABLE),
                    )
                val expected =
                    createTileState(
                        activationState = QSTileState.ActivationState.UNAVAILABLE,
                        actions = setOf(QSTileState.UserAction.LONG_CLICK),
                    )

                assertThat(actual).isEqualTo(expected)
            }
        }

    @Test
    fun tileWithChevron() =
        with(kosmos) {
            testScope.runTest {
                val actual =
                    underTest.map(
                        customTileQsTileConfig,
                        createModel(isToggleable = false),
                    )
                val expected =
                    createTileState(
                        sideIcon = QSTileState.SideViewIcon.Chevron,
                        a11yClass = Button::class.qualifiedName,
                    )

                assertThat(actual).isEqualTo(expected)
            }
        }

    @Test
    fun defaultIconFallback() =
        with(kosmos) {
            testScope.runTest {
                val actual =
                    underTest.map(
                        customTileQsTileConfig,
                        createModel(tileIcon = createIcon(RuntimeException(), false)),
                    )
                val expected =
                    createTileState(
                        activationState = QSTileState.ActivationState.INACTIVE,
                        icon = DEFAULT_DRAWABLE,
                    )

                assertThat(actual).isEqualTo(expected)
            }
        }

    @Test
    fun failedToLoadIconTileIsInactive() =
        with(kosmos) {
            testScope.runTest {
                val actual =
                    underTest.map(
                        customTileQsTileConfig,
                        createModel(
                            tileIcon = createIcon(RuntimeException(), false),
                            defaultTileIcon = createIcon(null, true)
                        ),
                    )
                val expected =
                    createTileState(
                        icon = null,
                        activationState = QSTileState.ActivationState.INACTIVE,
                    )

                assertThat(actual).isEqualTo(expected)
            }
        }

    private fun Kosmos.createModel(
        tileState: Int = Tile.STATE_ACTIVE,
        tileIcon: Icon = createIcon(DRAWABLE, false),
        hasPendingBind: Boolean = false,
        isToggleable: Boolean = true,
        defaultTileIcon: Icon = createIcon(DEFAULT_DRAWABLE, true),
    ) =
        CustomTileDataModel(
            UserHandle.of(1),
            customTileSpec.componentName,
            Tile().apply {
                state = tileState
                label = "test label"
                subtitle = "test subtitle"
                icon = tileIcon
                contentDescription = "test content description"
            },
            callingAppUid = 0,
            hasPendingBind = hasPendingBind,
            isToggleable = isToggleable,
            defaultTileLabel = "test default tile label",
            defaultTileIcon = defaultTileIcon,
        )

    private fun createIcon(drawable: Drawable?, isDefault: Boolean): Icon = mock {
        if (isDefault) {
            whenever(loadDrawable(any())).thenReturn(drawable)
        } else {
            whenever(loadDrawableCheckingUriGrant(any(), any(), any(), any())).thenReturn(drawable)
        }
    }

    private fun createIcon(exception: RuntimeException, isDefault: Boolean): Icon = mock {
        if (isDefault) {
            whenever(loadDrawable(any())).thenThrow(exception)
        } else {
            whenever(loadDrawableCheckingUriGrant(any(), eq(uriGrantsManager), any(), any()))
                .thenThrow(exception)
        }
    }

    private fun createTileState(
        activationState: QSTileState.ActivationState = QSTileState.ActivationState.ACTIVE,
        icon: Drawable? = DRAWABLE,
        sideIcon: QSTileState.SideViewIcon = QSTileState.SideViewIcon.None,
        actions: Set<QSTileState.UserAction> =
            setOf(QSTileState.UserAction.CLICK, QSTileState.UserAction.LONG_CLICK),
        a11yClass: String? = Switch::class.qualifiedName,
    ): QSTileState {
        return QSTileState(
            { icon?.let { com.android.systemui.common.shared.model.Icon.Loaded(icon, null) } },
            "test label",
            activationState,
            "test subtitle",
            actions,
            "test content description",
            null,
            sideIcon,
            QSTileState.EnabledState.ENABLED,
            a11yClass,
        )
    }

    private companion object {
        val TEST_COMPONENT = ComponentName("test.pkg", "test.cls")

        val DEFAULT_DRAWABLE = TestStubDrawable("default_icon_drawable")
        val DRAWABLE = TestStubDrawable("icon_drawable")
    }
}
