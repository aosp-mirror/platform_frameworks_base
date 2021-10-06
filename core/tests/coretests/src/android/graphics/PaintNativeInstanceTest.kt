/*
 * Copyright 2020 The Android Open Source Project
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

package android.graphics

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Verify that various calls to getNativeInstance do not deadlock or otherwise fail.
@RunWith(AndroidJUnit4::class)
class PaintNativeInstanceTest {

    // Force a GC after each test, so that if there was a double free, it would happen now, rather
    // than later during other tests.
    @After
    fun runGcAndFinalizersSync() {
        Runtime.getRuntime().gc()
        Runtime.getRuntime().runFinalization()
        val fence = CountDownLatch(1)
        object : Any() {
            @Throws(Throwable::class)
            protected fun finalize() = fence.countDown()
        }
        try {
            do {
                Runtime.getRuntime().gc()
                Runtime.getRuntime().runFinalization()
            } while (!fence.await(100, TimeUnit.MILLISECONDS))
        } catch (ex: InterruptedException) {
            throw RuntimeException(ex)
        }
    }

    private fun setupComposeShader(test: (Paint, ComposeShader, Shader, Shader) -> Unit) {
        val size = 255f
        val blue = LinearGradient(0f, 0f, size, 0f, Color.GREEN, Color.BLUE,
                Shader.TileMode.MIRROR)
        val red = LinearGradient(0f, 0f, 0f, size, Color.GREEN, Color.RED,
                Shader.TileMode.MIRROR)
        val compose = ComposeShader(blue, red, BlendMode.SCREEN)
        val paint = Paint().apply {
            shader = compose
        }
        test(paint, compose, blue, red)
    }

    // Change the matrix arbitrarily to invalidate the shader.
    private fun Shader.changeMatrix() {
        val matrix = Matrix().apply {
            setScale(2f, 2f)
        }
        setLocalMatrix(matrix)
    }

    @Test
    fun testUnchangedPaintNativeInstance() = setupComposeShader {
        paint, compose, shaderA, shaderB ->
        val nativeInstance = paint.nativeInstance
        for (shader in listOf(compose, shaderA, shaderB)) {
            shader.changeMatrix()
            // Although the shader is invalidated, the Paint's nativeInstance remains the same.
            assertEquals(nativeInstance, paint.nativeInstance)
        }
    }

    @Test
    fun testInvalidateSubShader() = setupComposeShader {
        paint, compose, shaderA, shaderB ->
        // Trigger the creation of native objects.
        shaderA.nativeInstance
        compose.nativeInstance
        val instanceB = shaderB.nativeInstance

        // Changing shaderA's matrix invalidates shaderA and compose. A new instance will be lazily
        // created for each of them. We cannot assert that the new nativeInstance does not match,
        // since it might be allocated at the same location. But we can verify that shaderB did not
        // change, and that there was no deadlock.
        shaderA.changeMatrix()
        assertEquals(instanceB, shaderB.nativeInstance)
        paint.nativeInstance
    }

    @Test
    fun testInvalidateSubShaderDraw() = setupComposeShader {
        paint, _, _, shaderB ->

        val original = PaintTask(paint).call()

        // Change one of the subshaders and verify that the paint now draws differently.
        shaderB.changeMatrix()
        val changed = PaintTask(paint).call()
        assertFalse(changed.sameAs(original))
    }

    /*
     * This task will trigger the creation of native objects, if they have not already been
     * created.
     */
    class PaintTask(private val mPaint: Paint) : Callable<Bitmap> {
        private val size = 255 // matches size of gradients in setupComposeShader
        override fun call(): Bitmap = Bitmap.createBitmap(size, size,
                Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            canvas.drawPaint(mPaint)
        }
    }

    @Test
    fun testMultiThreadShader() = setupComposeShader {
        paint, _, _, _ ->
        // Create an arbitrary number of tasks and try to start them at approximately the same time.
        // They will race to create the native objects, but this should be safe.
        val tasks = List(5) { PaintTask(paint) }
        val results = Executors.newCachedThreadPool().invokeAll(tasks)
        var expectedBitmap: Bitmap? = null
        for (result in results) {
            if (expectedBitmap == null) {
                expectedBitmap = result.get()
            } else {
                assertTrue(expectedBitmap.sameAs(result.get()))
            }
        }
    }

    @Test
    fun testMultiThreadColorFilter() {
        val paint = Paint().apply {
            color = Color.MAGENTA
            colorFilter = LightingColorFilter(Color.BLUE, Color.GREEN)
        }
        // Create an arbitrary number of tasks and try to start them at approximately the same time.
        // They will race to create the native objects, but this should be safe.
        val tasks = List(5) { PaintTask(paint) }
        val results = Executors.newCachedThreadPool().invokeAll(tasks)
        for (result in results) {
            assertEquals(Color.CYAN, result.get().getPixel(0, 0))
        }
    }
}
