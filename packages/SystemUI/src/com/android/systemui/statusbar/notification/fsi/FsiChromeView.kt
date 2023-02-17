package com.android.systemui.statusbar.notification.fsi

import android.content.Context
import android.graphics.Color
import android.graphics.Color.DKGRAY
import android.graphics.Outline
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.notification.fsi.FsiDebug.Companion.log

@SysUISingleton
class FsiChromeView
@JvmOverloads
constructor(
    context: Context?,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : LinearLayout(context, attrs, defStyleAttr, defStyleRes) {

    companion object {
        private const val classTag = "FsiChromeView"
    }

    lateinit var chromeContainer: LinearLayout
    lateinit var appIconImageView: ImageView
    lateinit var appNameTextView: TextView
    lateinit var dismissButton: Button
    lateinit var fullscreenButton: Button

    private val cornerRadius: Float =
        resources.getDimensionPixelSize(R.dimen.notification_corner_radius).toFloat()
    private val vertPadding: Int =
        resources.getDimensionPixelSize(R.dimen.fsi_chrome_vertical_padding)
    private val sidePadding: Int =
        resources.getDimensionPixelSize(R.dimen.notification_side_paddings)

    init {
        log("$classTag init")
    }

    override fun onFinishInflate() {
        log("$classTag onFinishInflate")
        super.onFinishInflate()

        setBackgroundColor(Color.TRANSPARENT)
        setPadding(
            sidePadding,
            vertPadding,
            sidePadding,
            vertPadding
        ) // Make smaller than fullscreen.

        chromeContainer = findViewById(R.id.fsi_chrome)
        chromeContainer.setBackgroundColor(DKGRAY)

        appIconImageView = findViewById(R.id.fsi_app_icon)
        appNameTextView = findViewById(R.id.fsi_app_name)
        dismissButton = findViewById(R.id.fsi_dismiss_button)
        fullscreenButton = findViewById(R.id.fsi_fullscreen_button)

        outlineProvider =
            object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(
                        /* left */ sidePadding,
                        /* top */ vertPadding,
                        /* right */ view.width - sidePadding,
                        /* bottom */ view.height - vertPadding,
                        cornerRadius
                    )
                }
            }
        clipToOutline = true
    }
}
