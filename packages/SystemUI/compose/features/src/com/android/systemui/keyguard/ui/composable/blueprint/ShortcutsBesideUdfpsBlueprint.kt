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

package com.android.systemui.keyguard.ui.composable.blueprint

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.IntOffset
import com.android.compose.animation.scene.SceneScope
import com.android.compose.modifiers.height
import com.android.compose.modifiers.width
import com.android.systemui.keyguard.ui.composable.section.AmbientIndicationSection
import com.android.systemui.keyguard.ui.composable.section.BottomAreaSection
import com.android.systemui.keyguard.ui.composable.section.ClockSection
import com.android.systemui.keyguard.ui.composable.section.LockSection
import com.android.systemui.keyguard.ui.composable.section.NotificationSection
import com.android.systemui.keyguard.ui.composable.section.SmartSpaceSection
import com.android.systemui.keyguard.ui.composable.section.StatusBarSection
import com.android.systemui.keyguard.ui.viewmodel.LockscreenContentViewModel
import com.android.systemui.res.R
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Renders the lockscreen scene when showing with the default layout (e.g. vertical phone form
 * factor).
 */
class ShortcutsBesideUdfpsBlueprint
@Inject
constructor(
    private val viewModel: LockscreenContentViewModel,
    private val statusBarSection: StatusBarSection,
    private val clockSection: ClockSection,
    private val smartSpaceSection: SmartSpaceSection,
    private val notificationSection: NotificationSection,
    private val lockSection: LockSection,
    private val ambientIndicationSection: AmbientIndicationSection,
    private val bottomAreaSection: BottomAreaSection,
) : LockscreenSceneBlueprint {

    override val id: String = "shortcuts-besides-udfps"

    @Composable
    override fun SceneScope.Content(modifier: Modifier) {
        val context = LocalContext.current
        val lockIconBounds = lockSection.lockIconBounds(context)
        val isUdfpsVisible = viewModel.isUdfpsVisible

        Box(
            modifier = modifier,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().height { lockIconBounds.top },
            ) {
                with(statusBarSection) { StatusBar(modifier = Modifier.fillMaxWidth()) }
                with(clockSection) { SmallClock(modifier = Modifier.fillMaxWidth()) }
                with(smartSpaceSection) { SmartSpace(modifier = Modifier.fillMaxWidth()) }
                with(clockSection) { LargeClock(modifier = Modifier.fillMaxWidth()) }
                with(notificationSection) {
                    Notifications(modifier = Modifier.fillMaxWidth().weight(1f))
                }
                if (!isUdfpsVisible) {
                    with(ambientIndicationSection) {
                        AmbientIndication(modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            val shortcutSizePx =
                with(LocalDensity.current) { bottomAreaSection.shortcutSizeDp().toSize() }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier.fillMaxWidth().offset {
                        val rowTop =
                            if (shortcutSizePx.height > lockIconBounds.height()) {
                                (lockIconBounds.top -
                                        (shortcutSizePx.height + lockIconBounds.height()) / 2)
                                    .roundToInt()
                            } else {
                                lockIconBounds.top
                            }

                        IntOffset(0, rowTop)
                    },
            ) {
                Spacer(Modifier.weight(1f))

                with(bottomAreaSection) { Shortcut(isStart = true) }

                Spacer(Modifier.weight(1f))

                with(lockSection) {
                    LockIcon(
                        modifier =
                            Modifier.width { lockIconBounds.width() }
                                .height { lockIconBounds.height() }
                    )
                }

                Spacer(Modifier.weight(1f))

                with(bottomAreaSection) { Shortcut(isStart = false) }

                Spacer(Modifier.weight(1f))
            }

            Column(modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter)) {
                if (isUdfpsVisible) {
                    with(ambientIndicationSection) {
                        AmbientIndication(modifier = Modifier.fillMaxWidth())
                    }
                }

                with(bottomAreaSection) {
                    IndicationArea(
                        modifier =
                            Modifier.fillMaxWidth()
                                .padding(
                                    horizontal =
                                        dimensionResource(
                                            R.dimen.keyguard_affordance_horizontal_offset
                                        )
                                )
                                .padding(
                                    bottom =
                                        dimensionResource(
                                            R.dimen.keyguard_affordance_vertical_offset
                                        )
                                )
                                .heightIn(min = shortcutSizeDp().height),
                    )
                }
            }
        }
    }
}

@Module
interface ShortcutsBesideUdfpsBlueprintModule {
    @Binds
    @IntoSet
    fun blueprint(blueprint: ShortcutsBesideUdfpsBlueprint): LockscreenSceneBlueprint
}
