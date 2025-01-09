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

package com.android.systemui.statusbar.notification.row

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.widget.FrameLayout
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.internal.graphics.ColorUtils
import com.android.systemui.res.R
import com.android.systemui.surfaceeffects.shaderutil.ShaderUtilLibrary
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A background style for smarter-smart-actions. The style is composed by a simplex3d noise,
 * overlaid with sparkles.
 */
class MagicActionBackgroundDrawable(
    context: Context,
    primaryContainer: Int? = null,
    private val seed: Float = 0f,
) : Drawable() {

    private val pixelDensity = context.resources.displayMetrics.density
    private val cornerRadius =
        context.resources.getDimensionPixelSize(R.dimen.smart_reply_button_corner_radius).toFloat()
    private val outlineStrokeWidth =
        context.resources
            .getDimensionPixelSize(R.dimen.smart_action_button_outline_stroke_width)
            .toFloat()
    private val buttonShape = Path()
    private val paddingVertical =
        context.resources
            .getDimensionPixelSize(R.dimen.smart_reply_button_padding_vertical)
            .toFloat()

    /** The color of the button background. */
    private val mainColor =
        primaryContainer
            ?: context.getColor(com.android.internal.R.color.materialColorPrimaryContainer)

    /** Slightly dimmed down version of [mainColor] used on the simplex noise. */
    private val dimColor: Int
        get() {
            val labColor = arrayOf(0.0, 0.0, 0.0).toDoubleArray()
            ColorUtils.colorToLAB(mainColor, labColor)
            val camColor = ColorUtils.colorToCAM(mainColor)
            return ColorUtils.CAMToColor(
                camColor.hue,
                camColor.chroma,
                max(0f, (labColor[0] - 20).toFloat()),
            )
        }

    private val bgShader = MagicActionBackgroundShader()
    private val bgPaint = Paint()
    private val outlinePaint = Paint()

    init {
        bgShader.setColorUniform("in_color", mainColor)
        bgShader.setColorUniform("in_dimColor", dimColor)
        bgPaint.shader = bgShader
        outlinePaint.style = Paint.Style.STROKE
        // Stroke is doubled in width and then clipped, to avoid anti-aliasing artifacts at the edge
        // of the rectangle.
        outlinePaint.strokeWidth = outlineStrokeWidth * 2
        outlinePaint.blendMode = BlendMode.SCREEN
        outlinePaint.alpha = (255 * 0.32f).roundToInt()
    }

    override fun draw(canvas: Canvas) {
        // We clip instead of drawing 2 rounded rects, otherwise there will be artifacts where
        // around the button background and the outline.
        canvas.clipPath(buttonShape)

        canvas.drawRect(bounds, bgPaint)
        canvas.drawRoundRect(
            bounds.left.toFloat(),
            bounds.top + paddingVertical,
            bounds.right.toFloat(),
            bounds.bottom - paddingVertical,
            cornerRadius,
            cornerRadius,
            outlinePaint,
        )
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)

        val width = bounds.width().toFloat()
        val height = bounds.height() - paddingVertical * 2
        if (width == 0f || height == 0f) return

        bgShader.setFloatUniform("in_gridNum", NOISE_SIZE)
        bgShader.setFloatUniform("in_spkarkleAlpha", SPARKLE_ALPHA)
        bgShader.setFloatUniform("in_noiseMove", 0f, 0f, 0f)
        bgShader.setFloatUniform("in_size", width, height)
        bgShader.setFloatUniform("in_aspectRatio", width / height)
        bgShader.setFloatUniform("in_time", seed)
        bgShader.setFloatUniform("in_pixelDensity", pixelDensity)

        buttonShape.reset()
        buttonShape.addRoundRect(
            bounds.left.toFloat(),
            bounds.top + paddingVertical,
            bounds.right.toFloat(),
            bounds.bottom - paddingVertical,
            cornerRadius,
            cornerRadius,
            Path.Direction.CW,
        )

        val outlineGradient =
            LinearGradient(
                bounds.left.toFloat(),
                0f,
                bounds.right.toFloat(),
                0f,
                mainColor,
                ColorUtils.setAlphaComponent(mainColor, 0),
                Shader.TileMode.CLAMP,
            )
        outlinePaint.shader = outlineGradient
    }

    override fun setAlpha(alpha: Int) {
        bgPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        bgPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /** Smoothness of the turbulence. Larger numbers yield more detail. */
        private const val NOISE_SIZE = 0.7f

        /** Strength of the sparkles overlaid on the turbulence. */
        private const val SPARKLE_ALPHA = 0.15f
    }
}

private class MagicActionBackgroundShader : RuntimeShader(SHADER) {

    // language=AGSL
    companion object {
        private const val UNIFORMS =
            """
            uniform float in_gridNum;
            uniform vec3 in_noiseMove;
            uniform vec2 in_size;
            uniform float in_aspectRatio;
            uniform half in_time;
            uniform half in_pixelDensity;
            uniform float in_spkarkleAlpha;
            layout(color) uniform vec4 in_color;
            layout(color) uniform vec4 in_dimColor;
        """
        private const val MAIN_SHADER =
            """vec4 main(vec2 p) {
            vec2 uv = p / in_size.xy;
            uv.x *= in_aspectRatio;
            vec3 noiseP = vec3(uv + in_noiseMove.xy, in_noiseMove.z) * in_gridNum;
            half luma = 1.0 - getLuminosity(half3(simplex3d(noiseP)));
            half4 turbulenceColor = mix(in_color, in_dimColor, luma);
            float sparkle = sparkles(p - mod(p, in_pixelDensity * 0.8), in_time);
            sparkle = min(sparkle * in_spkarkleAlpha, in_spkarkleAlpha);
            return turbulenceColor + half4(half3(sparkle), 1.0);
        }
        """
        private const val SHADER = UNIFORMS + ShaderUtilLibrary.SHADER_LIB + MAIN_SHADER
    }
}

// @Preview
@Composable
fun DrawablePreview() {
    AndroidView(
        factory = { context ->
            FrameLayout(context).apply {
                background =
                    MagicActionBackgroundDrawable(
                        context = context,
                        primaryContainer = Color.parseColor("#c5eae2"),
                        seed = 0f,
                    )
            }
        },
        modifier = Modifier.size(100.dp, 50.dp),
    )
}
