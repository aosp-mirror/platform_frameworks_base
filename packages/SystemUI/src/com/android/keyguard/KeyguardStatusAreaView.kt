package com.android.keyguard

import android.content.Context
import android.util.AttributeSet
import android.util.FloatProperty
import android.widget.LinearLayout
import com.android.systemui.R
import com.android.systemui.statusbar.notification.AnimatableProperty

class KeyguardStatusAreaView(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    var translateXFromClockDesign = 0f
        get() = field
        set(value) {
            field = value
            translationX = translateXFromAod + translateXFromClockDesign + translateXFromUnfold
        }

    var translateXFromAod = 0f
        get() = field
        set(value) {
            field = value
            translationX = translateXFromAod + translateXFromClockDesign + translateXFromUnfold
        }

    var translateXFromUnfold = 0F
        get() = field
        set(value) {
            field = value
            translationX = translateXFromAod + translateXFromClockDesign + translateXFromUnfold
        }

    var translateYFromClockSize = 0f
        get() = field
        set(value) {
            field = value
            translationY = value + translateYFromClockDesign
        }

    var translateYFromClockDesign = 0f
        get() = field
        set(value) {
            field = value
            translationY = value + translateYFromClockSize
        }

    companion object {
        @JvmField
        val TRANSLATE_X_CLOCK_DESIGN =
            AnimatableProperty.from(
                object : FloatProperty<KeyguardStatusAreaView>("TranslateXClockDesign") {
                    override fun setValue(view: KeyguardStatusAreaView, value: Float) {
                        view.translateXFromClockDesign = value
                    }

                    override fun get(view: KeyguardStatusAreaView): Float {
                        return view.translateXFromClockDesign
                    }
                },
                R.id.translate_x_clock_design_animator_tag,
                R.id.translate_x_clock_design_animator_start_tag,
                R.id.translate_x_clock_design_animator_end_tag
            )

        @JvmField
        val TRANSLATE_X_AOD =
            AnimatableProperty.from(
                object : FloatProperty<KeyguardStatusAreaView>("TranslateXAod") {
                    override fun setValue(view: KeyguardStatusAreaView, value: Float) {
                        view.translateXFromAod = value
                    }

                    override fun get(view: KeyguardStatusAreaView): Float {
                        return view.translateXFromAod
                    }
                },
                R.id.translate_x_aod_animator_tag,
                R.id.translate_x_aod_animator_start_tag,
                R.id.translate_x_aod_animator_end_tag
            )

        @JvmField
        val TRANSLATE_Y_CLOCK_SIZE =
            AnimatableProperty.from(
                object : FloatProperty<KeyguardStatusAreaView>("TranslateYClockSize") {
                    override fun setValue(view: KeyguardStatusAreaView, value: Float) {
                        view.translateYFromClockSize = value
                    }

                    override fun get(view: KeyguardStatusAreaView): Float {
                        return view.translateYFromClockSize
                    }
                },
                R.id.translate_y_clock_size_animator_tag,
                R.id.translate_y_clock_size_animator_start_tag,
                R.id.translate_y_clock_size_animator_end_tag
            )

        @JvmField
        val TRANSLATE_Y_CLOCK_DESIGN =
            AnimatableProperty.from(
                object : FloatProperty<KeyguardStatusAreaView>("TranslateYClockDesign") {
                    override fun setValue(view: KeyguardStatusAreaView, value: Float) {
                        view.translateYFromClockDesign = value
                    }

                    override fun get(view: KeyguardStatusAreaView): Float {
                        return view.translateYFromClockDesign
                    }
                },
                R.id.translate_y_clock_design_animator_tag,
                R.id.translate_y_clock_design_animator_start_tag,
                R.id.translate_y_clock_design_animator_end_tag
            )
    }
}
