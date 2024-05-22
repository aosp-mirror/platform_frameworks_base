package com.android.wm.shell.windowdecor.viewholder

import android.annotation.ColorInt
import android.app.ActivityManager.RunningTaskInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Configuration.UI_MODE_NIGHT_MASK
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.View.OnLongClickListener
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.withStyledAttributes
import androidx.core.view.isVisible
import com.android.internal.R.attr.materialColorOnSecondaryContainer
import com.android.internal.R.attr.materialColorOnSurface
import com.android.internal.R.attr.materialColorOnSurfaceInverse
import com.android.internal.R.attr.materialColorSecondaryContainer
import com.android.internal.R.attr.materialColorSurfaceContainerHigh
import com.android.internal.R.attr.materialColorSurfaceContainerLow
import com.android.internal.R.attr.materialColorSurfaceDim
import com.android.internal.R.attr.materialColorSurfaceInverse
import com.android.window.flags.Flags
import com.android.wm.shell.R
import com.android.wm.shell.windowdecor.MaximizeButtonView
import com.android.wm.shell.windowdecor.extension.isLightCaptionBarAppearance
import com.android.wm.shell.windowdecor.extension.isTransparentCaptionBarAppearance

/**
 * A desktop mode window decoration used when the window is floating (i.e. freeform). It hosts
 * finer controls such as a close window button and an "app info" section to pull up additional
 * controls.
 */
internal class DesktopModeAppControlsWindowDecorationViewHolder(
        rootView: View,
        onCaptionTouchListener: View.OnTouchListener,
        onCaptionButtonClickListener: View.OnClickListener,
        onLongClickListener: OnLongClickListener,
        onCaptionGenericMotionListener: View.OnGenericMotionListener,
        appName: CharSequence,
        appIconBitmap: Bitmap,
        onMaximizeHoverAnimationFinishedListener: () -> Unit
) : DesktopModeWindowDecorationViewHolder(rootView) {

    private val captionView: View = rootView.requireViewById(R.id.desktop_mode_caption)
    private val captionHandle: View = rootView.requireViewById(R.id.caption_handle)
    private val openMenuButton: View = rootView.requireViewById(R.id.open_menu_button)
    private val closeWindowButton: ImageButton = rootView.requireViewById(R.id.close_window)
    private val expandMenuButton: ImageButton = rootView.requireViewById(R.id.expand_menu_button)
    private val maximizeButtonView: MaximizeButtonView =
            rootView.requireViewById(R.id.maximize_button_view)
    private val maximizeWindowButton: ImageButton = rootView.requireViewById(R.id.maximize_window)
    private val appNameTextView: TextView = rootView.requireViewById(R.id.application_name)
    private val appIconImageView: ImageView = rootView.requireViewById(R.id.application_icon)
    val appNameTextWidth: Int
        get() = appNameTextView.width

    init {
        captionView.setOnTouchListener(onCaptionTouchListener)
        captionHandle.setOnTouchListener(onCaptionTouchListener)
        openMenuButton.setOnClickListener(onCaptionButtonClickListener)
        openMenuButton.setOnTouchListener(onCaptionTouchListener)
        closeWindowButton.setOnClickListener(onCaptionButtonClickListener)
        maximizeWindowButton.setOnClickListener(onCaptionButtonClickListener)
        maximizeWindowButton.setOnTouchListener(onCaptionTouchListener)
        maximizeWindowButton.setOnGenericMotionListener(onCaptionGenericMotionListener)
        maximizeWindowButton.onLongClickListener = onLongClickListener
        closeWindowButton.setOnTouchListener(onCaptionTouchListener)
        appNameTextView.text = appName
        appIconImageView.setImageBitmap(appIconBitmap)
        maximizeButtonView.onHoverAnimationFinishedListener =
                onMaximizeHoverAnimationFinishedListener
    }

    override fun bindData(taskInfo: RunningTaskInfo) {
        if (Flags.enableThemedAppHeaders()) {
            bindDataWithThemedHeaders(taskInfo)
        } else {
            bindDataLegacy(taskInfo)
        }
    }

    private fun bindDataLegacy(taskInfo: RunningTaskInfo) {
        captionView.setBackgroundColor(getCaptionBackgroundColor(taskInfo))
        val color = getAppNameAndButtonColor(taskInfo)
        val alpha = Color.alpha(color)
        closeWindowButton.imageTintList = ColorStateList.valueOf(color)
        maximizeWindowButton.imageTintList = ColorStateList.valueOf(color)
        expandMenuButton.imageTintList = ColorStateList.valueOf(color)
        appNameTextView.isVisible = !taskInfo.isTransparentCaptionBarAppearance
        appNameTextView.setTextColor(color)
        appIconImageView.imageAlpha = alpha
        maximizeWindowButton.imageAlpha = alpha
        closeWindowButton.imageAlpha = alpha
        expandMenuButton.imageAlpha = alpha

        maximizeButtonView.setAnimationTints(isDarkMode())
    }

    private fun bindDataWithThemedHeaders(taskInfo: RunningTaskInfo) {
        val header = fillHeaderInfo(taskInfo)
        val headerStyle = getHeaderStyle(header)

        // Caption Background
        val headerBackground = captionView.background as LayerDrawable
        val backLayer = headerBackground.findDrawableByLayerId(R.id.backLayer) as GradientDrawable
        val frontLayer = headerBackground.findDrawableByLayerId(R.id.frontLayer) as GradientDrawable
        when (headerStyle.background) {
            is HeaderStyle.Background.Opaque -> {
                backLayer.setColor(headerStyle.background.backLayerColor ?: Color.BLACK)
                frontLayer.setColor(headerStyle.background.frontLayerColor)
                frontLayer.alpha = headerStyle.background.frontLayerOpacity
            }
            HeaderStyle.Background.Transparent -> {
                backLayer.setColor(Color.TRANSPARENT)
                frontLayer.setColor(Color.TRANSPARENT)
                frontLayer.alpha = OPACITY_100
            }
        }

        // Caption Foreground
        val foregroundColor = headerStyle.foreground.color
        val foregroundAlpha = headerStyle.foreground.opacity
        val colorStateList = ColorStateList.valueOf(foregroundColor).withAlpha(foregroundAlpha)
        closeWindowButton.imageTintList = colorStateList
        expandMenuButton.imageTintList = colorStateList
        with (appNameTextView) {
            isVisible = header.type == Header.Type.DEFAULT
            setTextColor(colorStateList)
        }
        appIconImageView.imageAlpha = foregroundAlpha
        maximizeButtonView.setAnimationTints(
            darkMode = header.appTheme == Header.Theme.DARK,
            iconForegroundColor = colorStateList,
            baseForegroundColor = foregroundColor
        )
    }

    override fun onHandleMenuOpened() {}

    override fun onHandleMenuClosed() {}

    fun setAnimatingTaskResize(animatingTaskResize: Boolean) {
        // If animating a task resize, cancel any running hover animations
        if (animatingTaskResize) {
            maximizeButtonView.cancelHoverAnimation()
        }
        maximizeButtonView.hoverDisabled = animatingTaskResize
    }

    fun onMaximizeWindowHoverExit() {
        maximizeButtonView.cancelHoverAnimation()
    }

    fun onMaximizeWindowHoverEnter() {
        maximizeButtonView.startHoverAnimation()
    }

    private fun getHeaderStyle(header: Header): HeaderStyle {
        return HeaderStyle(
            background = getHeaderBackground(header),
            foreground = getHeaderForeground(header)
        )
    }

    private fun getHeaderBackground(
        header: Header
    ): HeaderStyle.Background {
        when (header.type) {
            Header.Type.DEFAULT -> {
                if (header.systemTheme.isLight() && header.appTheme.isLight() && header.isFocused) {
                    return HeaderStyle.Background.Opaque(
                        frontLayerColor = attrToColor(materialColorSecondaryContainer),
                        frontLayerOpacity = OPACITY_100,
                        backLayerColor = null
                    )
                }
                if (header.systemTheme.isLight() && header.appTheme.isLight() &&
                    !header.isFocused) {
                    return HeaderStyle.Background.Opaque(
                        frontLayerColor = attrToColor(materialColorSurfaceContainerLow),
                        frontLayerOpacity = OPACITY_100,
                        backLayerColor = null
                    )
                }
                if (header.systemTheme.isDark() && header.appTheme.isDark() && header.isFocused) {
                    return HeaderStyle.Background.Opaque(
                        frontLayerColor = attrToColor(materialColorSurfaceContainerHigh),
                        frontLayerOpacity = OPACITY_100,
                        backLayerColor = null
                    )
                }
                if (header.systemTheme.isDark() && header.appTheme.isDark() && !header.isFocused) {
                    return HeaderStyle.Background.Opaque(
                        frontLayerColor = attrToColor(materialColorSurfaceDim),
                        frontLayerOpacity = OPACITY_100,
                        backLayerColor = null
                    )
                }
                if (header.systemTheme.isLight() && header.appTheme.isDark() && header.isFocused) {
                    return HeaderStyle.Background.Opaque(
                        frontLayerColor = attrToColor(materialColorSurfaceInverse),
                        frontLayerOpacity = OPACITY_100,
                        backLayerColor = null
                    )
                }
                if (header.systemTheme.isLight() && header.appTheme.isDark() && !header.isFocused) {
                    return HeaderStyle.Background.Opaque(
                        frontLayerColor = attrToColor(materialColorSurfaceInverse),
                        frontLayerOpacity = OPACITY_30,
                        backLayerColor = Color.BLACK
                    )
                }
                if (header.systemTheme.isDark() && header.appTheme.isLight() && header.isFocused) {
                    return HeaderStyle.Background.Opaque(
                        frontLayerColor = attrToColor(materialColorSurfaceInverse),
                        frontLayerOpacity = OPACITY_100,
                        backLayerColor = null
                    )
                }
                if (header.systemTheme.isDark() && header.appTheme.isLight() && !header.isFocused) {
                    return HeaderStyle.Background.Opaque(
                        frontLayerColor = attrToColor(materialColorSurfaceInverse),
                        frontLayerOpacity = OPACITY_55,
                        backLayerColor = Color.WHITE
                    )
                }
                error("No other combination expected header=$header")
            }
            Header.Type.CUSTOM -> return HeaderStyle.Background.Transparent
        }
    }

    private fun getHeaderForeground(header: Header): HeaderStyle.Foreground {
        when (header.type) {
            Header.Type.DEFAULT -> {
                if (header.systemTheme.isLight() && header.appTheme.isLight() && header.isFocused) {
                    return HeaderStyle.Foreground(
                        color = attrToColor(materialColorOnSecondaryContainer),
                        opacity = OPACITY_100
                    )
                }
                if (header.systemTheme.isLight() && header.appTheme.isLight() &&
                    !header.isFocused) {
                    return HeaderStyle.Foreground(
                        color = attrToColor(materialColorOnSecondaryContainer),
                        opacity = OPACITY_65
                    )
                }
                if (header.systemTheme.isDark() && header.appTheme.isDark() && header.isFocused) {
                    return HeaderStyle.Foreground(
                        color = attrToColor(materialColorOnSurface),
                        opacity = OPACITY_100
                    )
                }
                if (header.systemTheme.isDark() && header.appTheme.isDark() && !header.isFocused) {
                    return HeaderStyle.Foreground(
                        color = attrToColor(materialColorOnSurface),
                        opacity = OPACITY_55
                    )
                }
                if (header.systemTheme.isLight() && header.appTheme.isDark() && header.isFocused) {
                    return HeaderStyle.Foreground(
                        color = attrToColor(materialColorOnSurfaceInverse),
                        opacity = OPACITY_100
                    )
                }
                if (header.systemTheme.isLight() && header.appTheme.isDark() && !header.isFocused) {
                    return HeaderStyle.Foreground(
                        color = attrToColor(materialColorOnSurfaceInverse),
                        opacity = OPACITY_65
                    )
                }
                if (header.systemTheme.isDark() && header.appTheme.isLight() && header.isFocused) {
                    return HeaderStyle.Foreground(
                        color = attrToColor(materialColorOnSurfaceInverse),
                        opacity = OPACITY_100
                    )
                }
                if (header.systemTheme.isDark() && header.appTheme.isLight() && !header.isFocused) {
                    return HeaderStyle.Foreground(
                        color = attrToColor(materialColorOnSurfaceInverse),
                        opacity = OPACITY_70
                    )
                }
                error("No other combination expected header=$header")
            }
            Header.Type.CUSTOM -> {
                if (header.systemTheme.isLight() && header.isAppearanceCaptionLight &&
                    header.isFocused) {
                    return HeaderStyle.Foreground(
                        color = attrToColor(materialColorOnSecondaryContainer),
                        opacity = OPACITY_100
                    )
                }
                if (header.systemTheme.isLight() && header.isAppearanceCaptionLight &&
                    !header.isFocused) {
                    return HeaderStyle.Foreground(
                        color = attrToColor(materialColorOnSecondaryContainer),
                        opacity = OPACITY_65
                    )
                }
                if (header.systemTheme.isDark() && !header.isAppearanceCaptionLight &&
                    header.isFocused) {
                    return HeaderStyle.Foreground(
                        color = attrToColor(materialColorOnSurface),
                        opacity = OPACITY_100
                    )
                }
                if (header.systemTheme.isDark() && !header.isAppearanceCaptionLight &&
                    !header.isFocused) {
                    return HeaderStyle.Foreground(
                        color = attrToColor(materialColorOnSurface),
                        opacity = OPACITY_55
                    )
                }
                if (header.systemTheme.isLight() && !header.isAppearanceCaptionLight &&
                    header.isFocused) {
                    return HeaderStyle.Foreground(
                        color = attrToColor(materialColorOnSurfaceInverse),
                        opacity = OPACITY_100
                    )
                }
                if (header.systemTheme.isLight() && !header.isAppearanceCaptionLight &&
                    !header.isFocused) {
                    return HeaderStyle.Foreground(
                        color = attrToColor(materialColorOnSurfaceInverse),
                        opacity = OPACITY_65
                    )
                }
                if (header.systemTheme.isDark() && header.isAppearanceCaptionLight &&
                    header.isFocused) {
                    return HeaderStyle.Foreground(
                        color = attrToColor(materialColorOnSurfaceInverse),
                        opacity = OPACITY_100
                    )
                }
                if (header.systemTheme.isDark() && header.isAppearanceCaptionLight &&
                    !header.isFocused) {
                    return HeaderStyle.Foreground(
                        color = attrToColor(materialColorOnSurfaceInverse),
                        opacity = OPACITY_70
                    )
                }
                error("No other combination expected header=$header")
            }
        }
    }

    private fun fillHeaderInfo(taskInfo: RunningTaskInfo): Header {
        return Header(
            type = if (taskInfo.isTransparentCaptionBarAppearance) {
                Header.Type.CUSTOM
            } else {
                Header.Type.DEFAULT
            },
            systemTheme = getSystemTheme(),
            appTheme = getAppTheme(taskInfo),
            isFocused = taskInfo.isFocused,
            isAppearanceCaptionLight = taskInfo.isLightCaptionBarAppearance
        )
    }

    private fun getSystemTheme(): Header.Theme {
        return if ((context.resources.configuration.uiMode and UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES) {
            Header.Theme.DARK
        } else {
            Header.Theme.LIGHT
        }
    }

    private fun getAppTheme(taskInfo: RunningTaskInfo): Header.Theme {
        // TODO: use app's uiMode to find its actual light/dark value. It needs to be added to the
        //   TaskInfo/TaskDescription.
        val backgroundColor = taskInfo.taskDescription?.backgroundColor ?: return getSystemTheme()
        return if (Color.valueOf(backgroundColor).luminance() < 0.5) {
            Header.Theme.DARK
        } else {
            Header.Theme.LIGHT
        }
    }

    @ColorInt
    private fun attrToColor(attr: Int): Int {
        context.withStyledAttributes(null, intArrayOf(attr), 0, 0) {
            return getColor(0, 0)
        }
        return Color.BLACK
    }

    data class Header(
        val type: Type,
        val systemTheme: Theme,
        val appTheme: Theme,
        val isFocused: Boolean,
        val isAppearanceCaptionLight: Boolean,
    ) {
        enum class Type { DEFAULT, CUSTOM }
        enum class Theme { LIGHT, DARK }
    }

    private fun Header.Theme.isLight(): Boolean = this == Header.Theme.LIGHT

    private fun Header.Theme.isDark(): Boolean = this == Header.Theme.DARK

    data class HeaderStyle(
        val background: Background,
        val foreground: Foreground
    ) {
        data class Foreground(
            @ColorInt val color: Int,
            val opacity: Int
        )

        sealed class Background {
            data object Transparent : Background()
            data class Opaque(
                @ColorInt val frontLayerColor: Int,
                val frontLayerOpacity: Int,
                @ColorInt val backLayerColor: Int?
            ) : Background()
        }
    }

    @ColorInt
    private fun getCaptionBackgroundColor(taskInfo: RunningTaskInfo): Int {
        if (taskInfo.isTransparentCaptionBarAppearance) {
            return Color.TRANSPARENT
        }
        val materialColorAttr: Int =
            if (isDarkMode()) {
                if (!taskInfo.isFocused) {
                    materialColorSurfaceContainerHigh
                } else {
                    materialColorSurfaceDim
                }
            } else {
                if (!taskInfo.isFocused) {
                    materialColorSurfaceContainerLow
                } else {
                    materialColorSecondaryContainer
                }
        }
        context.withStyledAttributes(null, intArrayOf(materialColorAttr), 0, 0) {
            return getColor(0, 0)
        }
        return 0
    }

    @ColorInt
    private fun getAppNameAndButtonColor(taskInfo: RunningTaskInfo): Int {
        val materialColorAttr = when {
            taskInfo.isTransparentCaptionBarAppearance &&
                    taskInfo.isLightCaptionBarAppearance -> materialColorOnSecondaryContainer
            taskInfo.isTransparentCaptionBarAppearance &&
                    !taskInfo.isLightCaptionBarAppearance -> materialColorOnSurface
            isDarkMode() -> materialColorOnSurface
            else -> materialColorOnSecondaryContainer
        }
        val appDetailsOpacity = when {
            isDarkMode() && !taskInfo.isFocused -> DARK_THEME_UNFOCUSED_OPACITY
            !isDarkMode() && !taskInfo.isFocused -> LIGHT_THEME_UNFOCUSED_OPACITY
            else -> FOCUSED_OPACITY
        }
        context.withStyledAttributes(null, intArrayOf(materialColorAttr), 0, 0) {
            val color = getColor(0, 0)
            return if (appDetailsOpacity == FOCUSED_OPACITY) {
                color
            } else {
                Color.argb(
                    appDetailsOpacity,
                    Color.red(color),
                    Color.green(color),
                    Color.blue(color)
                )
            }
        }
        return 0
    }

    private fun isDarkMode(): Boolean {
        return context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
    }

    companion object {
        private const val TAG = "DesktopModeAppControlsWindowDecorationViewHolder"

        private const val DARK_THEME_UNFOCUSED_OPACITY = 140 // 55%
        private const val LIGHT_THEME_UNFOCUSED_OPACITY = 166 // 65%
        private const val FOCUSED_OPACITY = 255

        private const val OPACITY_100 = 255
        private const val OPACITY_30 = 77
        private const val OPACITY_55 = 140
        private const val OPACITY_65 = 166
        private const val OPACITY_70 = 179
    }
}
