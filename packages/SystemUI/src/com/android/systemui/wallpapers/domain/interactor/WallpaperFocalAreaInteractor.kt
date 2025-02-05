/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.wallpapers.domain.interactor

import android.content.Context
import android.content.res.Resources
import android.graphics.PointF
import android.graphics.RectF
import android.util.TypedValue
import androidx.annotation.VisibleForTesting
import com.android.app.animation.MathUtils
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.res.R
import com.android.systemui.shade.data.repository.ShadeRepository
import com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsInteractor
import com.android.systemui.wallpapers.data.repository.WallpaperFocalAreaRepository
import com.android.systemui.wallpapers.data.repository.WallpaperRepository
import javax.inject.Inject
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

@SysUISingleton
class WallpaperFocalAreaInteractor
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val context: Context,
    private val wallpaperFocalAreaRepository: WallpaperFocalAreaRepository,
    shadeRepository: ShadeRepository,
    activeNotificationsInteractor: ActiveNotificationsInteractor,
    val wallpaperRepository: WallpaperRepository,
) {
    // When there's notifications in splitshade, the focal area should be left aligned
    @VisibleForTesting
    val notificationInShadeWideLayout: Flow<Boolean> =
        combine(
            shadeRepository.isShadeLayoutWide,
            activeNotificationsInteractor.areAnyNotificationsPresent,
        ) { isShadeLayoutWide, areAnyNotificationsPresent: Boolean ->
            when {
                !isShadeLayoutWide -> false
                !areAnyNotificationsPresent -> false
                else -> true
            }
        }

    val hasFocalArea = wallpaperRepository.shouldSendFocalArea

    val wallpaperFocalAreaBounds: Flow<RectF> =
        combine(
                shadeRepository.isShadeLayoutWide,
                notificationInShadeWideLayout,
                wallpaperFocalAreaRepository.notificationStackAbsoluteBottom,
                wallpaperFocalAreaRepository.shortcutAbsoluteTop,
                wallpaperFocalAreaRepository.notificationDefaultTop,
            ) {
                isShadeLayoutWide,
                notificationInShadeWideLayout,
                notificationStackAbsoluteBottom,
                shortcutAbsoluteTop,
                notificationDefaultTop ->
                // Wallpaper will be zoomed in with config_wallpaperMaxScale in lockscreen
                // so we need to give a bounds taking this scale in consideration
                val wallpaperZoomedInScale = getSystemWallpaperMaximumScale(context)

                val screenBounds =
                    RectF(
                        0F,
                        0F,
                        context.resources.displayMetrics.widthPixels.toFloat(),
                        context.resources.displayMetrics.heightPixels.toFloat(),
                    )
                val scaledBounds =
                    RectF(
                        screenBounds.centerX() - screenBounds.width() / 2F / wallpaperZoomedInScale,
                        screenBounds.centerY() -
                            screenBounds.height() / 2F / wallpaperZoomedInScale,
                        screenBounds.centerX() + screenBounds.width() / 2F / wallpaperZoomedInScale,
                        screenBounds.centerY() + screenBounds.height() / 2F / wallpaperZoomedInScale,
                    )

                val maxFocalAreaWidth =
                    TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        FOCAL_AREA_MAX_WIDTH_DP.toFloat(),
                        context.resources.displayMetrics,
                    )

                val (left, right) =
                // tablet landscape
                if (context.resources.getBoolean(R.bool.center_align_focal_area_shape)) {
                        Pair(
                            scaledBounds.centerX() - maxFocalAreaWidth / 2F,
                            scaledBounds.centerX() + maxFocalAreaWidth / 2F,
                        )
                        // unfold foldable landscape
                    } else if (isShadeLayoutWide) {
                        if (notificationInShadeWideLayout) {
                            Pair(scaledBounds.left, scaledBounds.centerX())
                        } else {
                            Pair(scaledBounds.centerX(), scaledBounds.right)
                        }
                        // handheld / portrait
                    } else {
                        val focalAreaWidth = min(scaledBounds.width(), maxFocalAreaWidth)
                        Pair(
                            scaledBounds.centerX() - focalAreaWidth / 2F,
                            scaledBounds.centerX() + focalAreaWidth / 2F,
                        )
                    }
                val scaledBottomMargin =
                    (context.resources.displayMetrics.heightPixels - shortcutAbsoluteTop) /
                        wallpaperZoomedInScale
                val top =
                    // tablet landscape
                    if (context.resources.getBoolean(R.bool.center_align_focal_area_shape)) {
                        // no strict constraints for top, use bottom margin to make it symmetric
                        // vertically
                        scaledBounds.top + scaledBottomMargin
                    }
                    // unfold foldable landscape
                    else if (isShadeLayoutWide) {
                        // For all landscape, we should use bottom of smartspace to constrain
                        scaledBounds.top + notificationDefaultTop / wallpaperZoomedInScale
                        // handheld / portrait
                    } else {
                        scaledBounds.top +
                            MathUtils.max(notificationDefaultTop, notificationStackAbsoluteBottom) /
                                wallpaperZoomedInScale
                    }
                val bottom = scaledBounds.bottom - scaledBottomMargin
                RectF(left, top, right, bottom)
            }
            .distinctUntilChanged()

    fun setFocalAreaBounds(bounds: RectF) {
        wallpaperFocalAreaRepository.setWallpaperFocalAreaBounds(bounds)
    }

    fun setNotificationDefaultTop(top: Float) {
        wallpaperFocalAreaRepository.setNotificationDefaultTop(top)
    }

    fun setTapPosition(x: Float, y: Float) {
        // Focal area should only react to touch event within its bounds
        val wallpaperZoomedInScale = getSystemWallpaperMaximumScale(context)
        // Because there's a scale applied on wallpaper in lockscreen
        // we should map it to the unscaled position on wallpaper
        val screenCenterX = context.resources.displayMetrics.widthPixels / 2F
        val newX = (x - screenCenterX) / wallpaperZoomedInScale + screenCenterX
        val screenCenterY = context.resources.displayMetrics.heightPixels / 2F
        val newY = (y - screenCenterY) / wallpaperZoomedInScale + screenCenterY
        if (wallpaperFocalAreaRepository.wallpaperFocalAreaBounds.value.contains(newX, newY)) {
            wallpaperFocalAreaRepository.setTapPosition(PointF(newX, newY))
        }
    }

    companion object {
        fun getSystemWallpaperMaximumScale(context: Context): Float {
            val scale =
                context.resources.getFloat(
                    Resources.getSystem()
                        .getIdentifier(
                            /* name= */ "config_wallpaperMaxScale",
                            /* defType= */ "dimen",
                            /* defPackage= */ "android",
                        )
                )
            return if (scale == 0f) 1f else scale
        }

        // A max width for focal area shape effects bounds, to avoid
        // it becoming too large in large screen portrait mode
        const val FOCAL_AREA_MAX_WIDTH_DP = 500
    }
}
