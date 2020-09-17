/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.test

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class MainActivity : Activity() {
    val mSurfaceProxy = SurfaceProxy()
    private var mSurfaceHolder: SurfaceHolder? = null
    private val mDrawLock = ReentrantLock()

    val surface: Surface? get() = mSurfaceHolder?.surface

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addSurfaceView(Rect(0, 0, 500, 200))
    }

    fun addSurfaceView(size: Rect): CountDownLatch {
        val layout = findViewById<FrameLayout>(android.R.id.content)
        val surfaceReadyLatch = CountDownLatch(1)
        val surfaceView = createSurfaceView(applicationContext, size, surfaceReadyLatch)
        layout.addView(surfaceView,
                FrameLayout.LayoutParams(size.width(), size.height(), Gravity.TOP or Gravity.LEFT)
                        .also { it.setMargins(100, 100, 0, 0) })
        return surfaceReadyLatch
    }

    private fun createSurfaceView(
        context: Context,
        size: Rect,
        surfaceReadyLatch: CountDownLatch
    ): SurfaceView {
        val surfaceView = SurfaceView(context)
        surfaceView.setWillNotDraw(false)
        surfaceView.holder.setFixedSize(size.width(), size.height())
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                mDrawLock.withLock {
                    mSurfaceHolder = holder
                    mSurfaceProxy.setSurface(holder.surface)
                }
                surfaceReadyLatch.countDown()
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) {
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                mDrawLock.withLock {
                    mSurfaceHolder = null
                }
            }
        })
        return surfaceView
    }

    fun drawFrame(): Rect {
        mDrawLock.withLock {
            val holder = mSurfaceHolder ?: return Rect()
            val canvas = holder.lockCanvas()
            val canvasSize = Rect(0, 0, canvas.width, canvas.height)
            canvas.drawColor(Color.GREEN)
            val p = Paint()
            p.color = Color.RED
            canvas.drawRect(canvasSize, p)
            holder.unlockCanvasAndPost(canvas)
            return canvasSize
        }
    }
}