/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.test.hwui

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Shader
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Bundle
import android.view.View

class RenderEffectViewActivity : Activity() {

    private val mDropsShader = RuntimeShader(dropsAGSL)
    private var mDropsAnimator = ValueAnimator.ofFloat(0f, 1f)
    private var mStartTime = System.currentTimeMillis()
    private lateinit var mScratchesImage: Bitmap
    private lateinit var mScratchesShader: BitmapShader

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.view_runtime_shader)

        val dropsView = findViewById<View>(R.id.CardView)!!
        dropsView.isClickable = true
        dropsView.setOnClickListener {
            if (mDropsAnimator.isRunning) {
                mDropsAnimator.cancel()
                dropsView.setRenderEffect(null)
            } else {
                mDropsAnimator.start()
            }
        }

        val imgSource = ImageDecoder.createSource(resources, R.drawable.scratches)
        mScratchesImage = ImageDecoder.decodeBitmap(imgSource)
        mScratchesShader = BitmapShader(mScratchesImage,
                                        Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)

        mDropsAnimator.duration = 1000
        mDropsAnimator.repeatCount = ValueAnimator.INFINITE
        mDropsAnimator.addUpdateListener { _ ->
            val viewWidth = dropsView.width.toFloat()
            val viewHeight = dropsView.height.toFloat()
            val scratchesMatrix = Matrix()
            scratchesMatrix.postScale(viewWidth / mScratchesImage.width,
                    viewHeight / mScratchesImage.height)
            mScratchesShader.setLocalMatrix(scratchesMatrix)

            mDropsShader.setInputShader("scratches", mScratchesShader)
            mDropsShader.setFloatUniform("elapsedSeconds",
                                    (System.currentTimeMillis() - mStartTime) / 1000f)
            mDropsShader.setFloatUniform("viewDimensions", viewWidth, viewHeight)

            val dropsEffect = RenderEffect.createRuntimeShaderEffect(mDropsShader, "background")
            val blurEffect = RenderEffect.createBlurEffect(10f, 10f, Shader.TileMode.CLAMP)

            dropsView.setRenderEffect(RenderEffect.createChainEffect(dropsEffect, blurEffect))
        }
    }

    private companion object {
        const val dropsAGSL = """
            uniform float elapsedSeconds;
            uniform vec2 viewDimensions;
            uniform shader background;
            uniform shader scratches;

            vec2 dropsUV(vec2 fragCoord ) {
                vec2 uv = fragCoord.xy / viewDimensions.xy; // 0 <> 1
                vec2 offs = vec2(0.);
                return (offs + uv).xy;
            }

            const vec3  iFrostColorRGB = vec3(0.5, 0.5, 0.5);
            const float iFrostColorAlpha = .3;

            half4 main(float2 fragCoord) {
                half4 bg = background.eval(dropsUV(fragCoord)*viewDimensions.xy);
                float2 scratchCoord = fragCoord.xy / viewDimensions.xy;;
                scratchCoord += 1.5;
                scratchCoord = mod(scratchCoord, 1);
                half scratch = scratches.eval(scratchCoord*viewDimensions.xy).r;
                bg.rgb = mix(bg.rgb, iFrostColorRGB, iFrostColorAlpha);
                bg.rgb = mix(bg.rgb, half3(1), pow(scratch,3));
                return bg;
            }
        """
    }
}