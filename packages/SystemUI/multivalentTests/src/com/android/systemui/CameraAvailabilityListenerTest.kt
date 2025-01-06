package com.android.systemui

import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.camera2.CameraManager
import android.util.PathParser
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.res.R
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Expect
import java.util.concurrent.Executor
import kotlin.math.roundToInt
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class CameraAvailabilityListenerTest : SysuiTestCase() {
    companion object {
        private const val EXCLUDED_PKG = "test.excluded.package"
        private const val LOGICAL_CAMERA_ID_NOT_SPECIFIED = ""
        private const val LOGICAL_CAMERA_ID_REAR = "0"
        private const val LOGICAL_CAMERA_ID_FRONT = "1"
        private const val PHYSICAL_CAMERA_ID_OUTER_FRONT = "5"
        private const val PHYSICAL_CAMERA_ID_INNER_FRONT = "6"
        private const val PROTECTION_PATH_STRING_OUTER_FRONT =
            "M 50,50 a 20,20 0 1 0 40,0 a 20,20 0 1 0 -40,0 Z"
        private const val PROTECTION_PATH_STRING_INNER_FRONT =
            "M 40,40 a 10,10 0 1 0 20,0 a 10,10 0 1 0 -20,0 Z"
        private val PATH_RECT_FRONT =
            rectFromPath(pathFromString(PROTECTION_PATH_STRING_OUTER_FRONT))
        private val PATH_RECT_INNER =
            rectFromPath(pathFromString(PROTECTION_PATH_STRING_INNER_FRONT))

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

    @get:Rule val expect: Expect = Expect.create()

    private val cameraManager = mock<CameraManager>()
    private val cameraTransitionCallback = TestCameraTransitionCallback()

    private lateinit var cameraAvailabilityCallback: CameraManager.AvailabilityCallback

    @Before
    fun setUp() {
        overrideResource(R.string.config_cameraProtectionExcludedPackages, EXCLUDED_PKG)
        setOuterFrontCameraId(LOGICAL_CAMERA_ID_FRONT)
        setOuterFrontPhysicalCameraId(PHYSICAL_CAMERA_ID_OUTER_FRONT)
        overrideResource(
            R.string.config_frontBuiltInDisplayCutoutProtection,
            PROTECTION_PATH_STRING_OUTER_FRONT
        )
        setInnerFrontCameraId(LOGICAL_CAMERA_ID_FRONT)
        setInnerFrontPhysicalCameraId(PHYSICAL_CAMERA_ID_INNER_FRONT)
        overrideResource(
            R.string.config_innerBuiltInDisplayCutoutProtection,
            PROTECTION_PATH_STRING_INNER_FRONT
        )
        context.addMockSystemService(CameraManager::class.java, cameraManager)

        whenever(
                cameraManager.registerAvailabilityCallback(
                    any(Executor::class.java),
                    any(CameraManager.AvailabilityCallback::class.java)
                )
            )
            .thenAnswer {
                cameraAvailabilityCallback = it.arguments[1] as CameraManager.AvailabilityCallback
                return@thenAnswer Unit
            }
    }

    @Test
    fun onCameraOpened_matchesOuterFrontInfo_showsOuterProtection() {
        setInnerFrontCameraId(LOGICAL_CAMERA_ID_NOT_SPECIFIED)
        createAndStartSut()

        openCamera(LOGICAL_CAMERA_ID_FRONT)

        assertOuterProtectionShowing()
    }

    @Test
    fun onCameraOpened_matchesInnerFrontInfo_showsInnerProtection() {
        setOuterFrontCameraId(LOGICAL_CAMERA_ID_NOT_SPECIFIED)
        createAndStartSut()

        openCamera(LOGICAL_CAMERA_ID_FRONT)

        assertInnerProtectionShowing()
    }

    @Test
    fun onCameraOpened_doesNotMatchAnyProtectionInfo_doesNotShowProtection() {
        createAndStartSut()

        openCamera(LOGICAL_CAMERA_ID_REAR)

        assertProtectionNotShowing()
    }

    @Test
    fun onCameraOpened_matchesInnerAndOuter_innerUnavailable_showsOuterProtection() {
        val dupeCameraId = "1"
        setInnerFrontCameraId(dupeCameraId)
        setOuterFrontCameraId(dupeCameraId)
        createAndStartSut()

        setPhysicalCameraUnavailable(dupeCameraId, PHYSICAL_CAMERA_ID_INNER_FRONT)
        openCamera(dupeCameraId)

        assertOuterProtectionShowing()
    }

    @Test
    fun onCameraOpened_matchesInnerAndOuter_outerUnavailable_showsInnerFrontProtection() {
        val dupeCameraId = "1"
        setInnerFrontCameraId(dupeCameraId)
        setOuterFrontCameraId(dupeCameraId)
        createAndStartSut()

        setPhysicalCameraUnavailable(dupeCameraId, PHYSICAL_CAMERA_ID_OUTER_FRONT)
        openCamera(dupeCameraId)

        assertInnerProtectionShowing()
    }

    @Test
    fun onCameraOpened_matchesOuterFrontInfo_packageExcluded_doesNotShowProtection() {
        setInnerFrontCameraId(LOGICAL_CAMERA_ID_NOT_SPECIFIED)
        createAndStartSut()

        openCamera(LOGICAL_CAMERA_ID_FRONT, EXCLUDED_PKG)

        assertProtectionNotShowing()
    }

    @Test
    fun onCameraOpened_matchesInnerFrontInfo_packageExcluded_doesNotShowProtection() {
        setOuterFrontCameraId(LOGICAL_CAMERA_ID_NOT_SPECIFIED)
        createAndStartSut()

        openCamera(LOGICAL_CAMERA_ID_FRONT, EXCLUDED_PKG)

        assertProtectionNotShowing()
    }

    @Test
    fun onCameraClosed_matchesActiveOuterFrontProtection_hidesProtection() {
        setInnerFrontCameraId(LOGICAL_CAMERA_ID_NOT_SPECIFIED)
        createAndStartSut()
        openCamera(LOGICAL_CAMERA_ID_FRONT)

        closeCamera(LOGICAL_CAMERA_ID_FRONT)

        assertProtectionNotShowing()
    }

    @Test
    fun onCameraClosed_matchesActiveInnerFrontProtection_hidesProtection() {
        setOuterFrontCameraId(LOGICAL_CAMERA_ID_NOT_SPECIFIED)
        createAndStartSut()
        openCamera(LOGICAL_CAMERA_ID_FRONT)

        closeCamera(LOGICAL_CAMERA_ID_FRONT)

        assertProtectionNotShowing()
    }

    @Test
    fun onCameraClosed_doesNotMatchActiveOuterFrontProtection_keepsShowingProtection() {
        setInnerFrontCameraId(LOGICAL_CAMERA_ID_NOT_SPECIFIED)
        createAndStartSut()
        openCamera(LOGICAL_CAMERA_ID_FRONT)

        closeCamera(LOGICAL_CAMERA_ID_REAR)

        assertOuterProtectionShowing()
    }

    @Test
    fun onCameraClosed_doesNotMatchActiveInnerFrontProtection_keepsShowingProtection() {
        setOuterFrontCameraId(LOGICAL_CAMERA_ID_NOT_SPECIFIED)
        createAndStartSut()
        openCamera(LOGICAL_CAMERA_ID_FRONT)

        closeCamera(LOGICAL_CAMERA_ID_REAR)

        assertInnerProtectionShowing()
    }

    @Test
    fun onPhysicalCameraAvailable_cameraOpen_matchesOuterFront_showsOuterFrontProtection() {
        val logicalCameraId = "1"
        setOuterFrontCameraId(logicalCameraId)
        setInnerFrontCameraId(logicalCameraId)
        createAndStartSut()
        setPhysicalCameraUnavailable(logicalCameraId, PHYSICAL_CAMERA_ID_OUTER_FRONT)
        openCamera(logicalCameraId)

        setPhysicalCameraAvailable(logicalCameraId, PHYSICAL_CAMERA_ID_OUTER_FRONT)

        assertOuterProtectionShowing()
    }

    @Test
    fun onPhysicalCameraAvailable_cameraClosed_matchesOuterFront_doesNotShowProtection() {
        val logicalCameraId = "1"
        setOuterFrontCameraId(logicalCameraId)
        setInnerFrontCameraId(logicalCameraId)
        createAndStartSut()
        setPhysicalCameraUnavailable(logicalCameraId, PHYSICAL_CAMERA_ID_OUTER_FRONT)

        setPhysicalCameraAvailable(logicalCameraId, PHYSICAL_CAMERA_ID_OUTER_FRONT)

        assertProtectionNotShowing()
    }

    @Test
    fun onPhysicalCameraAvailable_cameraOpen_matchesInnerFront_showsInnerFrontProtection() {
        val logicalCameraId = "1"
        setOuterFrontCameraId(logicalCameraId)
        setInnerFrontCameraId(logicalCameraId)
        createAndStartSut()
        setPhysicalCameraUnavailable(logicalCameraId, PHYSICAL_CAMERA_ID_INNER_FRONT)
        openCamera(logicalCameraId)

        setPhysicalCameraAvailable(logicalCameraId, PHYSICAL_CAMERA_ID_INNER_FRONT)

        assertInnerProtectionShowing()
    }

    @Test
    fun onPhysicalCameraAvailable_cameraClosed_matchesInnerFront_doesNotShowProtection() {
        val logicalCameraId = "1"
        setOuterFrontCameraId(logicalCameraId)
        setInnerFrontCameraId(logicalCameraId)
        createAndStartSut()
        setPhysicalCameraUnavailable(logicalCameraId, PHYSICAL_CAMERA_ID_INNER_FRONT)

        setPhysicalCameraAvailable(logicalCameraId, PHYSICAL_CAMERA_ID_INNER_FRONT)

        assertProtectionNotShowing()
    }

    @Test
    fun onPhysicalCameraUnavailable_matchesActiveProtection_hidesProtection() {
        setInnerFrontCameraId(LOGICAL_CAMERA_ID_NOT_SPECIFIED)
        createAndStartSut()
        openCamera(LOGICAL_CAMERA_ID_FRONT)

        setPhysicalCameraUnavailable(LOGICAL_CAMERA_ID_FRONT, PHYSICAL_CAMERA_ID_OUTER_FRONT)

        assertProtectionNotShowing()
    }

    @Test
    fun onPhysicalCameraUnavailable_doesNotMatchActiveProtection_keepsShowingProtection() {
        setInnerFrontCameraId(LOGICAL_CAMERA_ID_NOT_SPECIFIED)
        createAndStartSut()
        openCamera(LOGICAL_CAMERA_ID_FRONT)

        setPhysicalCameraUnavailable(LOGICAL_CAMERA_ID_FRONT, PHYSICAL_CAMERA_ID_INNER_FRONT)

        assertOuterProtectionShowing()
    }

    private fun openCamera(logicalCameraId: String, packageId: String = "") {
        cameraAvailabilityCallback.onCameraOpened(logicalCameraId, packageId)
    }

    private fun closeCamera(logicalCameraId: String) {
        cameraAvailabilityCallback.onCameraClosed(logicalCameraId)
    }

    private fun setPhysicalCameraAvailable(logicalCameraId: String, physicalCameraId: String) {
        cameraAvailabilityCallback.onPhysicalCameraAvailable(logicalCameraId, physicalCameraId)
    }

    private fun setPhysicalCameraUnavailable(logicalCameraId: String, physicalCameraId: String) {
        cameraAvailabilityCallback.onPhysicalCameraUnavailable(logicalCameraId, physicalCameraId)
    }

    private fun assertOuterProtectionShowing() {
        expect.that(cameraTransitionCallback.shouldShowProtection).isTrue()
        expect.that(cameraTransitionCallback.protectionPath).isNotNull()
        expect.that(cameraTransitionCallback.protectionBounds).isEqualTo(PATH_RECT_FRONT)
    }

    private fun assertInnerProtectionShowing() {
        expect.that(cameraTransitionCallback.shouldShowProtection).isTrue()
        expect.that(cameraTransitionCallback.protectionPath).isNotNull()
        expect.that(cameraTransitionCallback.protectionBounds).isEqualTo(PATH_RECT_INNER)
    }

    private fun assertProtectionNotShowing() {
        expect.that(cameraTransitionCallback.shouldShowProtection).isFalse()
        expect.that(cameraTransitionCallback.protectionBounds).isNull()
        expect.that(cameraTransitionCallback.protectionPath).isNull()
    }

    private fun setOuterFrontCameraId(id: String) {
        overrideResource(R.string.config_protectedCameraId, id)
    }

    private fun setOuterFrontPhysicalCameraId(id: String) {
        overrideResource(R.string.config_protectedPhysicalCameraId, id)
    }

    private fun setInnerFrontCameraId(id: String) {
        overrideResource(R.string.config_protectedInnerCameraId, id)
    }

    private fun setInnerFrontPhysicalCameraId(id: String) {
        overrideResource(R.string.config_protectedInnerPhysicalCameraId, id)
    }

    private fun createAndStartSut(): CameraAvailabilityListener {
        return CameraAvailabilityListener.build(
                context,
                context.mainExecutor,
                CameraProtectionLoaderImpl((context))
        )
            .also {
                it.addTransitionCallback(cameraTransitionCallback)
                it.startListening()
            }
    }

    private class TestCameraTransitionCallback :
        CameraAvailabilityListener.CameraTransitionCallback {
        var shouldShowProtection = false
        var protectionPath: Path? = null
        var protectionBounds: Rect? = null

        override fun onApplyCameraProtection(protectionPath: Path, bounds: Rect) {
            shouldShowProtection = true
            this.protectionPath = protectionPath
            this.protectionBounds = bounds
        }

        override fun onHideCameraProtection() {
            shouldShowProtection = false
            protectionPath = null
            protectionBounds = null
        }
    }
}
