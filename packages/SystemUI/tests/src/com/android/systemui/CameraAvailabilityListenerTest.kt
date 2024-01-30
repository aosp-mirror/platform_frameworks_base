package com.android.systemui

import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.camera2.CameraManager
import android.testing.AndroidTestingRunner
import android.util.PathParser
import androidx.test.filters.SmallTest
import com.android.systemui.res.R
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.withArgCaptor
import java.util.concurrent.Executor
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@SmallTest
class CameraAvailabilityListenerTest : SysuiTestCase() {
    companion object {
        const val EXCLUDED_PKG = "test.excluded.package"
        const val CAMERA_ID_FRONT = "0"
        const val CAMERA_ID_INNER = "1"
        const val PROTECTION_PATH_STRING_FRONT = "M 50,50 a 20,20 0 1 0 40,0 a 20,20 0 1 0 -40,0 Z"
        const val PROTECTION_PATH_STRING_INNER = "M 40,40 a 10,10 0 1 0 20,0 a 10,10 0 1 0 -20,0 Z"
        val PATH_RECT_FRONT = rectFromPath(pathFromString(PROTECTION_PATH_STRING_FRONT))
        val PATH_RECT_INNER = rectFromPath(pathFromString(PROTECTION_PATH_STRING_INNER))

        private fun pathFromString(pathString: String): Path {
            val spec = pathString.trim()
            val p: Path
            try {
                p = PathParser.createPathFromPathData(spec)
            } catch (e: Throwable) {
                throw IllegalArgumentException("Invalid protection path", e)
            }

            return p
        }

        private fun rectFromPath(path: Path): Rect {
            val computed = RectF()
            path.computeBounds(computed)
            return Rect(
                computed.left.roundToInt(),
                computed.top.roundToInt(),
                computed.right.roundToInt(),
                computed.bottom.roundToInt()
            )
        }
    }

    @Mock private lateinit var cameraManager: CameraManager
    @Mock
    private lateinit var cameraTransitionCb: CameraAvailabilityListener.CameraTransitionCallback
    private lateinit var cameraAvailabilityListener: CameraAvailabilityListener

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        context
            .getOrCreateTestableResources()
            .addOverride(R.string.config_cameraProtectionExcludedPackages, EXCLUDED_PKG)
        context
            .getOrCreateTestableResources()
            .addOverride(R.string.config_protectedCameraId, CAMERA_ID_FRONT)
        context
            .getOrCreateTestableResources()
            .addOverride(
                R.string.config_frontBuiltInDisplayCutoutProtection,
                PROTECTION_PATH_STRING_FRONT
            )
        context
            .getOrCreateTestableResources()
            .addOverride(R.string.config_protectedInnerCameraId, CAMERA_ID_INNER)
        context
            .getOrCreateTestableResources()
            .addOverride(
                R.string.config_innerBuiltInDisplayCutoutProtection,
                PROTECTION_PATH_STRING_INNER
            )

        context.addMockSystemService(CameraManager::class.java, cameraManager)

        cameraAvailabilityListener =
            CameraAvailabilityListener.Factory.build(context, context.mainExecutor)
    }

    @Test
    fun testFrontCamera() {
        var path: Path? = null
        var rect: Rect? = null
        val callback =
            object : CameraAvailabilityListener.CameraTransitionCallback {
                override fun onApplyCameraProtection(protectionPath: Path, bounds: Rect) {
                    path = protectionPath
                    rect = bounds
                }

                override fun onHideCameraProtection() {}
            }

        cameraAvailabilityListener.addTransitionCallback(callback)
        cameraAvailabilityListener.startListening()

        val callbackCaptor = withArgCaptor {
            verify(cameraManager).registerAvailabilityCallback(any(Executor::class.java), capture())
        }

        callbackCaptor.onCameraOpened(CAMERA_ID_FRONT, "")
        assertNotNull(path)
        assertEquals(PATH_RECT_FRONT, rect)
    }

    @Test
    fun testInnerCamera() {
        var path: Path? = null
        var rect: Rect? = null
        val callback =
            object : CameraAvailabilityListener.CameraTransitionCallback {
                override fun onApplyCameraProtection(protectionPath: Path, bounds: Rect) {
                    path = protectionPath
                    rect = bounds
                }

                override fun onHideCameraProtection() {}
            }

        cameraAvailabilityListener.addTransitionCallback(callback)
        cameraAvailabilityListener.startListening()

        val callbackCaptor = withArgCaptor {
            verify(cameraManager).registerAvailabilityCallback(any(Executor::class.java), capture())
        }

        callbackCaptor.onCameraOpened(CAMERA_ID_INNER, "")
        assertNotNull(path)
        assertEquals(PATH_RECT_INNER, rect)
    }

    @Test
    fun testExcludedPackage() {
        cameraAvailabilityListener.addTransitionCallback(cameraTransitionCb)
        cameraAvailabilityListener.startListening()

        val callbackCaptor = withArgCaptor {
            verify(cameraManager).registerAvailabilityCallback(any(Executor::class.java), capture())
        }
        callbackCaptor.onCameraOpened(CAMERA_ID_FRONT, EXCLUDED_PKG)

        verify(cameraTransitionCb, never())
            .onApplyCameraProtection(any(Path::class.java), any(Rect::class.java))
    }
}
