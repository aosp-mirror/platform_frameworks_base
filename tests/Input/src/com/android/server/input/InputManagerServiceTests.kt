/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.input


import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.PermissionChecker
import android.content.pm.PackageManager
import android.content.pm.PackageManagerInternal
import android.hardware.display.DisplayManager
import android.hardware.display.DisplayViewport
import android.hardware.display.VirtualDisplay
import android.hardware.input.InputManager
import android.hardware.input.InputManagerGlobal
import android.os.InputEventInjectionSync
import android.os.SystemClock
import android.os.test.TestLooper
import android.platform.test.annotations.EnableFlags
import android.platform.test.annotations.Presubmit
import android.platform.test.flag.junit.SetFlagsRule
import android.provider.Settings
import android.view.View.OnKeyListener
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.test.mock.MockContentResolver
import androidx.test.platform.app.InstrumentationRegistry
import com.android.compatibility.common.util.SystemUtil.runWithShellPermissionIdentity
import com.android.dx.mockito.inline.extended.ExtendedMockito
import com.android.internal.policy.KeyInterceptionInfo
import com.android.internal.util.test.FakeSettingsProvider
import com.android.modules.utils.testing.ExtendedMockitoRule
import com.android.server.LocalServices
import com.android.server.wm.WindowManagerInternal
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyZeroInteractions
import org.mockito.Mockito.`when`
import org.mockito.stubbing.OngoingStubbing

/**
 * Tests for {@link InputManagerService}.
 *
 * Build/Install/Run:
 * atest InputTests:InputManagerServiceTests
 */
@Presubmit
class InputManagerServiceTests {

    companion object {
        val ACTION_KEY_EVENTS = listOf(
            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_META_LEFT),
            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_META_RIGHT),
            KeyEvent( /* downTime= */0, /* eventTime= */0, /* action= */0, /* code= */0,
                /* repeat= */0, KeyEvent.META_META_ON
            )
        )
    }

    @JvmField
    @Rule
    val extendedMockitoRule =
        ExtendedMockitoRule.Builder(this).mockStatic(LocalServices::class.java)
            .mockStatic(PermissionChecker::class.java).build()!!

    @JvmField
    @Rule
    val setFlagsRule = SetFlagsRule()

    @get:Rule
    val fakeSettingsProviderRule = FakeSettingsProvider.rule()!!

    @Mock
    private lateinit var native: NativeInputManagerService

    @Mock
    private lateinit var wmCallbacks: InputManagerService.WindowManagerCallbacks

    @Mock
    private lateinit var windowManagerInternal: WindowManagerInternal

    @Mock
    private lateinit var packageManagerInternal: PackageManagerInternal

    @Mock
    private lateinit var uEventManager: UEventManager

    @Mock
    private lateinit var kbdController: InputManagerService.KeyboardBacklightControllerInterface

    private lateinit var service: InputManagerService
    private lateinit var localService: InputManagerInternal
    private lateinit var context: Context
    private lateinit var testLooper: TestLooper
    private lateinit var contentResolver: MockContentResolver
    private lateinit var inputManagerGlobalSession: InputManagerGlobal.TestSession

    @Before
    fun setup() {
        context = spy(ContextWrapper(InstrumentationRegistry.getInstrumentation().getContext()))
        contentResolver = MockContentResolver(context)
        contentResolver.addProvider(Settings.AUTHORITY, FakeSettingsProvider())
        whenever(context.contentResolver).thenReturn(contentResolver)
        testLooper = TestLooper()
        service =
            InputManagerService(object : InputManagerService.Injector(
                    context, testLooper.looper, uEventManager) {
                override fun getNativeService(
                    service: InputManagerService?
                ): NativeInputManagerService {
                    return native
                }

                override fun registerLocalService(service: InputManagerInternal?) {
                    localService = service!!
                }

                override fun getKeyboardBacklightController(
                    nativeService: NativeInputManagerService?,
                    dataStore: PersistentDataStore?
                ): InputManagerService.KeyboardBacklightControllerInterface {
                    return kbdController
                }
            })
        inputManagerGlobalSession = InputManagerGlobal.createTestSession(service)
        val inputManager = InputManager(context)
        whenever(context.getSystemService(InputManager::class.java)).thenReturn(inputManager)
        whenever(context.getSystemService(Context.INPUT_SERVICE)).thenReturn(inputManager)
        whenever(context.checkCallingOrSelfPermission(Manifest.permission.MANAGE_KEY_GESTURES))
            .thenReturn(
                PackageManager.PERMISSION_GRANTED
            )

        ExtendedMockito.doReturn(windowManagerInternal).`when` {
            LocalServices.getService(eq(WindowManagerInternal::class.java))
        }
        ExtendedMockito.doReturn(packageManagerInternal).`when` {
            LocalServices.getService(eq(PackageManagerInternal::class.java))
        }

        assertTrue("Local service must be registered", this::localService.isInitialized)
        service.setWindowManagerCallbacks(wmCallbacks)
    }

    @After
    fun tearDown() {
        if (this::inputManagerGlobalSession.isInitialized) {
            inputManagerGlobalSession.close()
        }
    }

    @Test
    fun testStart() {
        verifyZeroInteractions(native)

        service.start()
        verify(native).start()
    }

    @Test
    fun testInputSettingsUpdatedOnSystemRunning() {
        verifyZeroInteractions(native)

        runWithShellPermissionIdentity {
            service.systemRunning()
        }

        verify(native).setPointerSpeed(anyInt())
        verify(native).setTouchpadPointerSpeed(anyInt())
        verify(native).setTouchpadNaturalScrollingEnabled(anyBoolean())
        verify(native).setTouchpadTapToClickEnabled(anyBoolean())
        verify(native).setTouchpadTapDraggingEnabled(anyBoolean())
        verify(native).setShouldNotifyTouchpadHardwareState(anyBoolean())
        verify(native).setTouchpadRightClickZoneEnabled(anyBoolean())
        verify(native).setShowTouches(anyBoolean())
        verify(native).setMotionClassifierEnabled(anyBoolean())
        verify(native).setMaximumObscuringOpacityForTouch(anyFloat())
        verify(native).setStylusPointerIconEnabled(anyBoolean())
        // Called thrice at boot, since there are individual callbacks to update the
        // key repeat timeout, the key repeat delay and whether key repeat enabled.
        verify(native, times(3)).setKeyRepeatConfiguration(anyInt(), anyInt(),
            anyBoolean())
    }

    @Test
    fun testPointerDisplayUpdatesWhenDisplayViewportsChanged() {
        val displayId = 123
        whenever(wmCallbacks.pointerDisplayId).thenReturn(displayId)
        val viewports = listOf<DisplayViewport>()
        localService.setDisplayViewports(viewports)
        verify(native).setDisplayViewports(any(Array<DisplayViewport>::class.java))
        verify(native).setPointerDisplayId(displayId)
    }

    @Test
    fun setDeviceTypeAssociation_setsDeviceTypeAssociation() {
        val inputPort = "inputPort"
        val type = "type"

        localService.setTypeAssociation(inputPort, type)

        assertThat(service.getDeviceTypeAssociations()).asList().containsExactly(inputPort, type)
            .inOrder()
    }

    @Test
    fun setAndUnsetDeviceTypeAssociation_deviceTypeAssociationIsMissing() {
        val inputPort = "inputPort"
        val type = "type"

        localService.setTypeAssociation(inputPort, type)
        localService.unsetTypeAssociation(inputPort)

        assertTrue(service.getDeviceTypeAssociations().isEmpty())
    }

    @Test
    fun testAddAndRemoveVirtualKeyboardLayoutAssociation() {
        val inputPort = "input port"
        val languageTag = "language"
        val layoutType = "layoutType"
        localService.addKeyboardLayoutAssociation(inputPort, languageTag, layoutType)
        verify(native).changeKeyboardLayoutAssociation()

        localService.removeKeyboardLayoutAssociation(inputPort)
        verify(native, times(2)).changeKeyboardLayoutAssociation()
    }

    @Test
    fun testActionKeyEventsForwardedToFocusedWindow_whenCorrectlyRequested() {
        service.systemRunning()
        overrideSendActionKeyEventsToFocusedWindow(
            /* hasPermission = */true,
            /* hasPrivateFlag = */true
        )
        whenever(wmCallbacks.interceptKeyBeforeDispatching(any(), any(), anyInt())).thenReturn(-1)

        for (event in ACTION_KEY_EVENTS) {
            assertEquals(0, service.interceptKeyBeforeDispatching(null, event, 0))
        }
    }

    @Test
    fun testActionKeyEventsNotForwardedToFocusedWindow_whenNoPermissions() {
        service.systemRunning()
        overrideSendActionKeyEventsToFocusedWindow(
            /* hasPermission = */false,
            /* hasPrivateFlag = */true
        )
        whenever(wmCallbacks.interceptKeyBeforeDispatching(any(), any(), anyInt())).thenReturn(-1)

        for (event in ACTION_KEY_EVENTS) {
            assertNotEquals(0, service.interceptKeyBeforeDispatching(null, event, 0))
        }
    }

    @Test
    fun testActionKeyEventsNotForwardedToFocusedWindow_whenNoPrivateFlag() {
        service.systemRunning()
        overrideSendActionKeyEventsToFocusedWindow(
            /* hasPermission = */true,
            /* hasPrivateFlag = */false
        )
        whenever(wmCallbacks.interceptKeyBeforeDispatching(any(), any(), anyInt())).thenReturn(-1)

        for (event in ACTION_KEY_EVENTS) {
            assertNotEquals(0, service.interceptKeyBeforeDispatching(null, event, 0))
        }
    }

    private fun createVirtualDisplays(count: Int): List<VirtualDisplay> {
        val displayManager: DisplayManager = context.getSystemService(
                DisplayManager::class.java
        ) as DisplayManager
        val virtualDisplays = mutableListOf<VirtualDisplay>()
        for (i in 0 until count) {
            virtualDisplays.add(displayManager.createVirtualDisplay(
                    /* displayName= */ "testVirtualDisplay$i",
                    /* width= */ 100,
                    /* height= */ 100,
                    /* densityDpi= */ 100,
                    /* surface= */ null,
                    /* flags= */ 0
            ))
        }
        return virtualDisplays
    }

    // Helper function that creates a KeyEvent with Keycode A with the given action
    private fun createKeycodeAEvent(inputDevice: InputDevice, action: Int): KeyEvent {
        val eventTime = SystemClock.uptimeMillis()
        return KeyEvent(
                /* downTime= */ eventTime,
                /* eventTime= */ eventTime,
                /* action= */ action,
                /* code= */ KeyEvent.KEYCODE_A,
                /* repeat= */ 0,
                /* metaState= */ 0,
                /* deviceId= */ inputDevice.id,
                /* scanCode= */ 0,
                /* flags= */ KeyEvent.FLAG_FROM_SYSTEM,
                /* source= */ InputDevice.SOURCE_KEYBOARD
        )
    }

    private fun createInputDevice(): InputDevice {
        return InputDevice.Builder()
                .setId(123)
                .setName("abc")
                .setDescriptor("def")
                .setSources(InputDevice.SOURCE_KEYBOARD)
                .build()
    }

    @Test
    fun addUniqueIdAssociationByDescriptor_verifyAssociations() {
        // Overall goal is to have 2 displays and verify that events from the InputDevice are
        // sent only to the view that is on the associated display.
        // So, associate the InputDevice with display 1, then send and verify KeyEvents.
        // Then remove associations, then associate the InputDevice with display 2, then send
        // and verify commands.

        // Make 2 virtual displays with some mock SurfaceViews
        val mockSurfaceView1 = mock(SurfaceView::class.java)
        val mockSurfaceView2 = mock(SurfaceView::class.java)
        val mockSurfaceHolder1 = mock(SurfaceHolder::class.java)
        `when`(mockSurfaceView1.holder).thenReturn(mockSurfaceHolder1)
        val mockSurfaceHolder2 = mock(SurfaceHolder::class.java)
        `when`(mockSurfaceView2.holder).thenReturn(mockSurfaceHolder2)

        val virtualDisplays = createVirtualDisplays(2)

        // Simulate an InputDevice
        val inputDevice = createInputDevice()

        // Associate input device with display
        service.addUniqueIdAssociationByDescriptor(
                inputDevice.descriptor,
                virtualDisplays[0].display.displayId.toString()
        )

        // Simulate 2 different KeyEvents
        val downEvent = createKeycodeAEvent(inputDevice, KeyEvent.ACTION_DOWN)
        val upEvent = createKeycodeAEvent(inputDevice, KeyEvent.ACTION_UP)

        // Create a mock OnKeyListener object
        val mockOnKeyListener = mock(OnKeyListener::class.java)

        // Verify that the event went to Display 1 not Display 2
        service.injectInputEvent(downEvent, InputEventInjectionSync.NONE)

        // Call the onKey method on the mock OnKeyListener object
        mockOnKeyListener.onKey(mockSurfaceView1, /* keyCode= */ KeyEvent.KEYCODE_A, downEvent)
        mockOnKeyListener.onKey(mockSurfaceView2, /* keyCode= */ KeyEvent.KEYCODE_A, upEvent)

        // Verify that the onKey method was called with the expected arguments
        verify(mockOnKeyListener).onKey(mockSurfaceView1, KeyEvent.KEYCODE_A, downEvent)
        verify(mockOnKeyListener, never()).onKey(mockSurfaceView2, KeyEvent.KEYCODE_A, downEvent)

        // Remove association
        service.removeUniqueIdAssociationByDescriptor(inputDevice.descriptor)

        // Associate with Display 2
        service.addUniqueIdAssociationByDescriptor(
                inputDevice.descriptor,
                virtualDisplays[1].display.displayId.toString()
        )

        // Simulate a KeyEvent
        service.injectInputEvent(upEvent, InputEventInjectionSync.NONE)

        // Verify that the event went to Display 2 not Display 1
        verify(mockOnKeyListener).onKey(mockSurfaceView2, KeyEvent.KEYCODE_A, upEvent)
        verify(mockOnKeyListener, never()).onKey(mockSurfaceView1, KeyEvent.KEYCODE_A, upEvent)
    }

    @Test
    fun addUniqueIdAssociationByPort_verifyAssociations() {
        // Overall goal is to have 2 displays and verify that events from the InputDevice are
        // sent only to the view that is on the associated display.
        // So, associate the InputDevice with display 1, then send and verify KeyEvents.
        // Then remove associations, then associate the InputDevice with display 2, then send
        // and verify commands.

        // Make 2 virtual displays with some mock SurfaceViews
        val mockSurfaceView1 = mock(SurfaceView::class.java)
        val mockSurfaceView2 = mock(SurfaceView::class.java)
        val mockSurfaceHolder1 = mock(SurfaceHolder::class.java)
        `when`(mockSurfaceView1.holder).thenReturn(mockSurfaceHolder1)
        val mockSurfaceHolder2 = mock(SurfaceHolder::class.java)
        `when`(mockSurfaceView2.holder).thenReturn(mockSurfaceHolder2)

        val virtualDisplays = createVirtualDisplays(2)

        // Simulate an InputDevice
        val inputDevice = createInputDevice()

        // Associate input device with display
        service.addUniqueIdAssociationByPort(
                inputDevice.name,
                virtualDisplays[0].display.displayId.toString()
        )

        // Simulate 2 different KeyEvents
        val downEvent = createKeycodeAEvent(inputDevice, KeyEvent.ACTION_DOWN)
        val upEvent = createKeycodeAEvent(inputDevice, KeyEvent.ACTION_UP)

        // Create a mock OnKeyListener object
        val mockOnKeyListener = mock(OnKeyListener::class.java)

        // Verify that the event went to Display 1 not Display 2
        service.injectInputEvent(downEvent, InputEventInjectionSync.NONE)

        // Call the onKey method on the mock OnKeyListener object
        mockOnKeyListener.onKey(mockSurfaceView1, /* keyCode= */ KeyEvent.KEYCODE_A, downEvent)
        mockOnKeyListener.onKey(mockSurfaceView2, /* keyCode= */ KeyEvent.KEYCODE_A, upEvent)

        // Verify that the onKey method was called with the expected arguments
        verify(mockOnKeyListener).onKey(mockSurfaceView1, KeyEvent.KEYCODE_A, downEvent)
        verify(mockOnKeyListener, never()).onKey(mockSurfaceView2, KeyEvent.KEYCODE_A, downEvent)

        // Remove association
        service.removeUniqueIdAssociationByPort(inputDevice.name)

        // Associate with Display 2
        service.addUniqueIdAssociationByPort(
                inputDevice.name,
                virtualDisplays[1].display.displayId.toString()
        )

        // Simulate a KeyEvent
        service.injectInputEvent(upEvent, InputEventInjectionSync.NONE)

        // Verify that the event went to Display 2 not Display 1
        verify(mockOnKeyListener).onKey(mockSurfaceView2, KeyEvent.KEYCODE_A, upEvent)
        verify(mockOnKeyListener, never()).onKey(mockSurfaceView1, KeyEvent.KEYCODE_A, upEvent)
    }

    @Test
    @EnableFlags(com.android.hardware.input.Flags.FLAG_USE_KEY_GESTURE_EVENT_HANDLER)
    fun handleKeyGestures_keyboardBacklight() {
        service.systemRunning()

        val backlightDownEvent = createKeyEvent(KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_DOWN)
        service.interceptKeyBeforeDispatching(null, backlightDownEvent, /* policyFlags = */0)
        verify(kbdController).decrementKeyboardBacklight(anyInt())

        val backlightUpEvent = createKeyEvent(KeyEvent.KEYCODE_KEYBOARD_BACKLIGHT_UP)
        service.interceptKeyBeforeDispatching(null, backlightUpEvent, /* policyFlags = */0)
        verify(kbdController).incrementKeyboardBacklight(anyInt())
    }

    @Test
    @EnableFlags(com.android.hardware.input.Flags.FLAG_USE_KEY_GESTURE_EVENT_HANDLER)
    fun handleKeyGestures_toggleCapsLock() {
        service.systemRunning()

        val metaDownEvent = createKeyEvent(KeyEvent.KEYCODE_META_LEFT)
        service.interceptKeyBeforeDispatching(null, metaDownEvent, /* policyFlags = */0)
        val altDownEvent =
            createKeyEvent(KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.META_META_ON, KeyEvent.ACTION_DOWN)
        service.interceptKeyBeforeDispatching(null, altDownEvent, /* policyFlags = */0)
        val altUpEvent =
            createKeyEvent(KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.META_META_ON, KeyEvent.ACTION_UP)
        service.interceptKeyBeforeDispatching(null, altUpEvent, /* policyFlags = */0)

        verify(native).toggleCapsLock(anyInt())
    }

    fun overrideSendActionKeyEventsToFocusedWindow(
        hasPermission: Boolean,
        hasPrivateFlag: Boolean
    ) {
        ExtendedMockito.doReturn(
            if (hasPermission) {
                PermissionChecker.PERMISSION_GRANTED
            } else {
                PermissionChecker.PERMISSION_HARD_DENIED
            }
        ).`when` {
            PermissionChecker.checkPermissionForDataDelivery(
                any(),
                eq(Manifest.permission.OVERRIDE_SYSTEM_KEY_BEHAVIOR_IN_FOCUSED_WINDOW),
                anyInt(),
                anyInt(),
                any(),
                any(),
                any()
            )
        }

        val info = KeyInterceptionInfo(
            /* type = */0,
            if (hasPrivateFlag) {
                WindowManager.LayoutParams.PRIVATE_FLAG_ALLOW_ACTION_KEY_EVENTS
            } else {
                0
            },
            "title",
            /* uid = */0
        )
        whenever(windowManagerInternal.getKeyInterceptionInfoFromToken(any())).thenReturn(info)
    }

    private fun createKeyEvent(
        keycode: Int,
        modifierState: Int = 0,
        action: Int = KeyEvent.ACTION_DOWN
    ): KeyEvent {
        return KeyEvent(
            /* downTime = */0,
            /* eventTime = */0,
            action,
            keycode,
            /* repeat = */0,
            modifierState,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            /* scancode = */0,
            /* flags = */0,
            InputDevice.SOURCE_KEYBOARD
        )
    }
}

private fun <T> whenever(methodCall: T): OngoingStubbing<T> = `when`(methodCall)
