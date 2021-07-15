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

package android.hardware.input;

import static android.hardware.lights.LightsRequest.Builder;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertNotNull;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.lights.Light;
import android.hardware.lights.LightState;
import android.hardware.lights.LightsManager;
import android.hardware.lights.LightsRequest;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.util.ArrayMap;
import android.view.InputDevice;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Tests for {@link InputDeviceLightsManager}.
 *
 * Build/Install/Run:
 * atest FrameworksCoreTests:InputDeviceLightsManagerTest
 */
@Presubmit
@RunWith(MockitoJUnitRunner.class)
public class InputDeviceLightsManagerTest {
    private static final String TAG = "InputDeviceLightsManagerTest";

    private static final int DEVICE_ID = 1000;
    private static final int PLAYER_ID = 3;

    @Rule public final MockitoRule mockito = MockitoJUnit.rule();

    private InputManager mInputManager;

    @Mock private IInputManager mIInputManagerMock;

    @Before
    public void setUp() throws Exception {
        when(mIInputManagerMock.getInputDeviceIds()).thenReturn(new int[]{DEVICE_ID});

        when(mIInputManagerMock.getInputDevice(eq(DEVICE_ID))).thenReturn(
                createInputDevice(DEVICE_ID));

        mInputManager = InputManager.resetInstance(mIInputManagerMock);

        ArrayMap<Integer, LightState> lightStatesById = new ArrayMap<>();
        doAnswer(invocation -> {
            final int[] lightIds = (int[]) invocation.getArguments()[1];
            final LightState[] lightStates =
                    (LightState[]) invocation.getArguments()[2];
            for (int i = 0; i < lightIds.length; i++) {
                lightStatesById.put(lightIds[i], lightStates[i]);
            }
            return null;
        }).when(mIInputManagerMock).setLightStates(eq(DEVICE_ID),
                any(int[].class), any(LightState[].class), any(IBinder.class));

        doAnswer(invocation -> {
            int lightId = (int) invocation.getArguments()[1];
            if (lightStatesById.containsKey(lightId)) {
                return lightStatesById.get(lightId);
            }
            return new LightState(0);
        }).when(mIInputManagerMock).getLightState(eq(DEVICE_ID), anyInt());
    }

    @After
    public void tearDown() {
        InputManager.clearInstance();
    }

    private InputDevice createInputDevice(int id) {
        return new InputDevice(id, 0 /* generation */, 0 /* controllerNumber */, "name",
                0 /* vendorId */, 0 /* productId */, "descriptor", true /* isExternal */,
                0 /* sources */, 0 /* keyboardType */, null /* keyCharacterMap */,
                false /* hasVibrator */, false /* hasMicrophone */, false /* hasButtonUnderpad */,
                false /* hasSensor */, false /* hasBattery */);
    }

    private void mockLights(Light[] lights) throws Exception {
        // Mock the Lights returned form InputManagerService
        when(mIInputManagerMock.getLights(eq(DEVICE_ID))).thenReturn(
                new ArrayList(Arrays.asList(lights)));
    }

    @Test
    public void testGetInputDeviceLights() throws Exception {
        InputDevice device = mInputManager.getInputDevice(DEVICE_ID);
        assertNotNull(device);

        Light[] mockedLights = {
            new Light(1 /* id */, "Light1", 0 /* ordinal */, Light.LIGHT_TYPE_INPUT,
                        Light.LIGHT_CAPABILITY_BRIGHTNESS),
            new Light(2 /* id */, "Light2", 0 /* ordinal */, Light.LIGHT_TYPE_INPUT,
                        Light.LIGHT_CAPABILITY_RGB),
            new Light(3 /* id */, "Light3", 0 /* ordinal */, Light.LIGHT_TYPE_INPUT,
                        0 /* capabilities */)
        };
        mockLights(mockedLights);

        LightsManager lightsManager = device.getLightsManager();
        List<Light> lights = lightsManager.getLights();
        verify(mIInputManagerMock).getLights(eq(DEVICE_ID));
        assertEquals(lights, Arrays.asList(mockedLights));
    }

    @Test
    public void testControlMultipleLights() throws Exception {
        InputDevice device = mInputManager.getInputDevice(DEVICE_ID);
        assertNotNull(device);

        Light[] mockedLights = {
            new Light(1 /* id */, "Light1", 0 /* ordinal */, Light.LIGHT_TYPE_INPUT,
                        Light.LIGHT_CAPABILITY_RGB),
            new Light(2 /* id */, "Light2", 0 /* ordinal */, Light.LIGHT_TYPE_INPUT,
                        Light.LIGHT_CAPABILITY_RGB),
            new Light(3 /* id */, "Light3", 0 /* ordinal */, Light.LIGHT_TYPE_INPUT,
                        Light.LIGHT_CAPABILITY_RGB),
            new Light(4 /* id */, "Light4", 0 /* ordinal */, Light.LIGHT_TYPE_INPUT,
                        Light.LIGHT_CAPABILITY_RGB)
        };
        mockLights(mockedLights);

        LightsManager lightsManager = device.getLightsManager();
        List<Light> lightList = lightsManager.getLights();
        LightState[] states = new LightState[]{new LightState(0xf1), new LightState(0xf2),
                new LightState(0xf3)};
        // Open a session to request turn 3/4 lights on:
        LightsManager.LightsSession session = lightsManager.openSession();
        session.requestLights(new Builder()
                .addLight(lightsManager.getLights().get(0), states[0])
                .addLight(lightsManager.getLights().get(1), states[1])
                .addLight(lightsManager.getLights().get(2), states[2])
                .build());
        IBinder token = session.getToken();

        verify(mIInputManagerMock).openLightSession(eq(DEVICE_ID),
                any(String.class), eq(token));
        verify(mIInputManagerMock).setLightStates(eq(DEVICE_ID), eq(new int[]{1, 2, 3}),
                eq(states), eq(token));

        // Then all 3 should turn on.
        assertThat(lightsManager.getLightState(lightsManager.getLights().get(0)).getColor())
                .isEqualTo(0xf1);
        assertThat(lightsManager.getLightState(lightsManager.getLights().get(1)).getColor())
                .isEqualTo(0xf2);
        assertThat(lightsManager.getLightState(lightsManager.getLights().get(2)).getColor())
                .isEqualTo(0xf3);

        // And the 4th should remain off.
        assertThat(lightsManager.getLightState(lightsManager.getLights().get(3)).getColor())
                .isEqualTo(0x00);

        // close session
        session.close();
        verify(mIInputManagerMock).closeLightSession(eq(DEVICE_ID), eq(token));
    }

    @Test
    public void testControlPlayerIdLight() throws Exception {
        InputDevice device = mInputManager.getInputDevice(DEVICE_ID);
        assertNotNull(device);

        Light[] mockedLights = {
                new Light(1 /* id */, "Light1", 0 /* ordinal */, Light.LIGHT_TYPE_PLAYER_ID,
                            0 /* capabilities */),
                new Light(2 /* id */, "Light2", 0 /* ordinal */, Light.LIGHT_TYPE_INPUT,
                            Light.LIGHT_CAPABILITY_RGB | Light.LIGHT_CAPABILITY_BRIGHTNESS),
                new Light(3 /* id */, "Light3", 0 /* ordinal */, Light.LIGHT_TYPE_INPUT,
                            Light.LIGHT_CAPABILITY_BRIGHTNESS)
        };
        mockLights(mockedLights);

        LightsManager lightsManager = device.getLightsManager();
        List<Light> lightList = lightsManager.getLights();
        LightState[] states = new LightState[]{new LightState(0xf1, PLAYER_ID)};
        // Open a session to request set Player ID light:
        LightsManager.LightsSession session = lightsManager.openSession();
        session.requestLights(new Builder()
                .addLight(lightsManager.getLights().get(0), states[0])
                .build());
        IBinder token = session.getToken();

        verify(mIInputManagerMock).openLightSession(eq(DEVICE_ID),
                any(String.class), eq(token));
        verify(mIInputManagerMock).setLightStates(eq(DEVICE_ID), eq(new int[]{1}),
                eq(states), eq(token));

        // Verify the light state
        assertThat(lightsManager.getLightState(lightsManager.getLights().get(0)).getColor())
                .isEqualTo(0xf1);
        assertThat(lightsManager.getLightState(lightsManager.getLights().get(0)).getPlayerId())
                .isEqualTo(PLAYER_ID);

        // close session
        session.close();
        verify(mIInputManagerMock).closeLightSession(eq(DEVICE_ID), eq(token));
    }

    @Test
    public void testLightCapabilities() throws Exception {
        Light light = new Light(1 /* id */, "Light1", 0 /* ordinal */, Light.LIGHT_TYPE_INPUT,
                Light.LIGHT_CAPABILITY_RGB | Light.LIGHT_CAPABILITY_BRIGHTNESS);
        assertThat(light.getType()).isEqualTo(Light.LIGHT_TYPE_INPUT);
        assertThat(light.getCapabilities()).isEqualTo(Light.LIGHT_CAPABILITY_RGB
                | Light.LIGHT_CAPABILITY_BRIGHTNESS);
        assertTrue(light.hasBrightnessControl());
        assertTrue(light.hasRgbControl());
    }

    @Test
    public void testLightsRequest() throws Exception {
        Light light1 = new Light(1 /* id */, "Light1", 0 /* ordinal */, Light.LIGHT_TYPE_INPUT,
                0 /* capabilities */);
        Light light2 = new Light(2 /* id */, "Light2", 0 /* ordinal */, Light.LIGHT_TYPE_PLAYER_ID,
                0 /* capabilities */);
        LightState state1 = new LightState(0xf1);
        LightState state2 = new LightState(0xf2, PLAYER_ID);
        LightsRequest request = new Builder().addLight(light1, state1)
                .addLight(light2, state2).build();

        // Covers the LightsRequest.getLights
        assertThat(request.getLights().size()).isEqualTo(2);
        assertThat(request.getLights().get(0)).isEqualTo(1);
        assertThat(request.getLights().get(1)).isEqualTo(2);

        // Covers the LightsRequest.getLightStates
        assertThat(request.getLightStates().size()).isEqualTo(2);
        assertThat(request.getLightStates().get(0)).isEqualTo(state1);
        assertThat(request.getLightStates().get(1)).isEqualTo(state2);

        // Covers the LightsRequest.getLightsAndStates
        assertThat(request.getLightsAndStates().size()).isEqualTo(2);
        assertThat(request.getLightsAndStates().containsKey(light1)).isTrue();
        assertThat(request.getLightsAndStates().get(light1)).isEqualTo(state1);
        assertThat(request.getLightsAndStates().get(light2)).isEqualTo(state2);
    }

}
