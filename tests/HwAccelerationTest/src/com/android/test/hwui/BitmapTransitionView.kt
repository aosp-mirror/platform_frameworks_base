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
import android.content.Context
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

class BitmapTransitionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val mPaint = Paint()
    private val mImageA = ImageDecoder.decodeBitmap(
            ImageDecoder.createSource(context.resources, R.drawable.large_photo))
    private val mImageB = ImageDecoder.decodeBitmap(
            ImageDecoder.createSource(context.resources, R.drawable.very_large_photo))
    private val mShaderA = BitmapShader(mImageA, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    private val mShaderB = BitmapShader(mImageB, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    private val mShader = RuntimeShader(AGSL)
    private var mCurrentProgress = -1f
    private var mForwardProgress = true
    private var mCurrentAnimator = ValueAnimator.ofFloat(-1f, 1f)

    init {
        isClickable = true

        mCurrentAnimator.duration = 1500
        mCurrentAnimator.addUpdateListener { animation ->
            mCurrentProgress = animation.animatedValue as Float
            postInvalidate()
        }
    }

    override fun performClick(): Boolean {
        if (super.performClick()) return true

        if (mCurrentAnimator.isRunning) {
            mCurrentAnimator.reverse()
            return true
        }

        if (mForwardProgress) {
            mCurrentAnimator.setFloatValues(-1f, 1f)
            mForwardProgress = false
        } else {
            mCurrentAnimator.setFloatValues(1f, -1f)
            mForwardProgress = true
        }

        mCurrentAnimator.start()
        postInvalidate()
        return true
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        val matrixA = Matrix()
        val matrixB = Matrix()

        matrixA.postScale(width.toFloat() / mImageA.width, height.toFloat() / mImageA.height)
        matrixB.postScale(width.toFloat() / mImageB.width, height.toFloat() / mImageB.height)

        mShaderA.setLocalMatrix(matrixA)
        mShaderB.setLocalMatrix(matrixB)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        mShader.setInputShader("imageA", mShaderA)
        mShader.setInputShader("imageB", mShaderB)
        mShader.setIntUniform("imageDimensions", width, height)
        mShader.setFloatUniform("progress", mCurrentProgress)

        mPaint.shader = mShader
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), mPaint)
    }

    private companion object {
        const val AGSL = """
        uniform shader imageA;
        uniform shader imageB;
        uniform ivec2 imageDimensions;
        uniform float progress;

        const vec2 iSize = vec2(48.0, 48.0);
        const float iDir = 0.5;
        const  float iRand = 0.81;

        float hash12(vec2 p) {
            vec3 p3  = fract(vec3(p.xyx) * .1031);
            p3 += dot(p3, p3.yzx + 33.33);
            return fract((p3.x + p3.y) * p3.z);
        }

        float ramp(float2 p) {
          return mix(hash12(p),
                     dot(p/vec2(imageDimensions), float2(iDir, 1 - iDir)),
                     iRand);
        }

        half4 main(float2 p) {
          float2 lowRes = p / iSize;
          float2 cellCenter = (floor(lowRes) + 0.5) * iSize;
          float2 posInCell = fract(lowRes) * 2 - 1;

          float v = ramp(cellCenter) + progress;
          float distToCenter = max(abs(posInCell.x), abs(posInCell.y));

          return distToCenter > v ? imageA.eval(p).rgb1 : imageB.eval(p).rgb1;
        }
        """
    }
}