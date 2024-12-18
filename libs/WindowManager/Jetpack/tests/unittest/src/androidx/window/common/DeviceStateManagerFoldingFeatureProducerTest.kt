/*
 * Copyright (C) 2024 The Android Open Source Project
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

package androidx.window.common

import android.content.Context
import android.content.res.Resources
import android.hardware.devicestate.DeviceState
import android.hardware.devicestate.DeviceStateManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.window.common.layout.CommonFoldingFeature
import androidx.window.common.layout.CommonFoldingFeature.COMMON_STATE_FLAT
import androidx.window.common.layout.CommonFoldingFeature.COMMON_STATE_HALF_OPENED
import androidx.window.common.layout.CommonFoldingFeature.COMMON_STATE_NO_FOLDING_FEATURES
import androidx.window.common.layout.CommonFoldingFeature.COMMON_STATE_UNKNOWN
import androidx.window.common.layout.CommonFoldingFeature.COMMON_STATE_USE_BASE_STATE
import androidx.window.common.layout.DisplayFoldFeatureCommon
import androidx.window.common.layout.DisplayFoldFeatureCommon.DISPLAY_FOLD_FEATURE_PROPERTY_SUPPORTS_HALF_OPENED
import androidx.window.common.layout.DisplayFoldFeatureCommon.DISPLAY_FOLD_FEATURE_TYPE_SCREEN_FOLD_IN
import com.android.internal.R
import com.google.common.truth.Truth.assertThat
import java.util.Optional
import java.util.concurrent.Executor
import java.util.function.Consumer
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify

/**
 * Test class for [DeviceStateManagerFoldingFeatureProducer].
 *
 * Build/Install/Run:
 *  atest WMJetpackUnitTests:DeviceStateManagerFoldingFeatureProducerTest
 */
@RunWith(AndroidJUnit4::class)
class DeviceStateManagerFoldingFeatureProducerTest {
    private val mMockDeviceStateManager = mock<DeviceStateManager>()
    private val mMockResources = mock<Resources> {
        on { getStringArray(R.array.config_device_state_postures) } doReturn DEVICE_STATE_POSTURES
    }
    private val mMockContext = mock<Context> {
        on { resources } doReturn mMockResources
    }
    private val mRawFoldSupplier = mock<RawFoldingFeatureProducer> {
        on { currentData } doReturn Optional.of(DISPLAY_FEATURES)
        on { getData(any<Consumer<String>>()) } doAnswer { invocation ->
            val callback = invocation.getArgument(0) as Consumer<String>
            callback.accept(DISPLAY_FEATURES)
        }
    }

    @Test
    fun testRegisterCallback_initialCallbackOnMainThread_executesDirectly() {
        DeviceStateManagerFoldingFeatureProducer(
            mMockContext,
            mRawFoldSupplier,
            mMockDeviceStateManager,
        )
        val callbackCaptor = argumentCaptor<DeviceStateManager.DeviceStateCallback>()
        verify(mMockDeviceStateManager).registerCallback(any(), callbackCaptor.capture())

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            callbackCaptor.firstValue.onDeviceStateChanged(DEVICE_STATE_HALF_OPENED)
        }

        verify(mMockContext, never()).getMainExecutor()
    }

    @Test
    fun testRegisterCallback_subsequentCallbacks_postsToMainThread() {
        val mockMainExecutor = mock<Executor>()
        mMockContext.stub {
            on { getMainExecutor() } doReturn mockMainExecutor
        }
        DeviceStateManagerFoldingFeatureProducer(
            mMockContext,
            mRawFoldSupplier,
            mMockDeviceStateManager,
        )
        val callbackCaptor = argumentCaptor<DeviceStateManager.DeviceStateCallback>()
        verify(mMockDeviceStateManager).registerCallback(any(), callbackCaptor.capture())

        callbackCaptor.firstValue.onDeviceStateChanged(DEVICE_STATE_HALF_OPENED)

        verify(mockMainExecutor).execute(any())
    }

    @Test
    fun testGetCurrentData_validCurrentState_returnsFoldingFeatureWithState() {
        val ffp = DeviceStateManagerFoldingFeatureProducer(
            mMockContext,
            mRawFoldSupplier,
            mMockDeviceStateManager,
        )
        ffp.onDeviceStateChanged(DEVICE_STATE_HALF_OPENED)

        val currentData = ffp.getCurrentData()

        assertThat(currentData).isPresent()
        assertThat(currentData.get()).containsExactlyElementsIn(HALF_OPENED_FOLDING_FEATURES)
    }

    @Test
    fun testGetCurrentData_invalidCurrentState_returnsEmptyOptionalFoldingFeature() {
        val ffp = DeviceStateManagerFoldingFeatureProducer(
            mMockContext,
            mRawFoldSupplier,
            mMockDeviceStateManager,
        )

        val currentData = ffp.getCurrentData()

        assertThat(currentData).isEmpty()
    }

    @Test
    fun testGetFoldsWithUnknownState_validFoldingFeature_returnsFoldingFeaturesWithUnknownState() {
        val ffp = DeviceStateManagerFoldingFeatureProducer(
            mMockContext,
            mRawFoldSupplier,
            mMockDeviceStateManager,
        )

        val result = ffp.getFoldsWithUnknownState()

        assertThat(result).containsExactlyElementsIn(UNKNOWN_STATE_FOLDING_FEATURES)
    }

    @Test
    fun testGetFoldsWithUnknownState_emptyFoldingFeature_returnsEmptyList() {
        mRawFoldSupplier.stub {
            on { currentData } doReturn Optional.empty()
        }
        val ffp = DeviceStateManagerFoldingFeatureProducer(
            mMockContext,
            mRawFoldSupplier,
            mMockDeviceStateManager,
        )

        val result = ffp.getFoldsWithUnknownState()

        assertThat(result).isEmpty()
    }

    @Test
    fun testGetDisplayFeatures_validFoldingFeature_returnsDisplayFoldFeatures() {
        mRawFoldSupplier.stub {
            on { currentData } doReturn Optional.of(DISPLAY_FEATURES_HALF_OPENED_HINGE)
        }
        val ffp = DeviceStateManagerFoldingFeatureProducer(
            mMockContext,
            mRawFoldSupplier,
            mMockDeviceStateManager,
        )

        val result = ffp.displayFeatures

        assertThat(result).containsExactly(
            DisplayFoldFeatureCommon(
                DISPLAY_FOLD_FEATURE_TYPE_SCREEN_FOLD_IN,
                setOf(DISPLAY_FOLD_FEATURE_PROPERTY_SUPPORTS_HALF_OPENED),
            ),
        )
    }

    @Test
    fun testIsHalfOpenedSupported_withHalfOpenedPostures_returnsTrue() {
        val ffp = DeviceStateManagerFoldingFeatureProducer(
            mMockContext,
            mRawFoldSupplier,
            mMockDeviceStateManager,
        )

        assertThat(ffp.isHalfOpenedSupported).isTrue()
    }

    @Test
    fun testIsHalfOpenedSupported_withEmptyPostures_returnsFalse() {
        mMockResources.stub {
            on { getStringArray(R.array.config_device_state_postures) } doReturn emptyArray()
        }
        val ffp = DeviceStateManagerFoldingFeatureProducer(
            mMockContext,
            mRawFoldSupplier,
            mMockDeviceStateManager,
        )

        assertThat(ffp.isHalfOpenedSupported).isFalse()
    }

    @Test
    fun testGetData_emptyDisplayFeaturesString_callsConsumerWithEmptyList() {
        mRawFoldSupplier.stub {
            on { getData(any<Consumer<String>>()) } doAnswer { invocation ->
                val callback = invocation.getArgument(0) as Consumer<String>
                callback.accept("")
            }
        }
        val ffp = DeviceStateManagerFoldingFeatureProducer(
            mMockContext,
            mRawFoldSupplier,
            mMockDeviceStateManager,
        )
        val storeFeaturesConsumer = mock<Consumer<List<CommonFoldingFeature>>>()

        ffp.getData(storeFeaturesConsumer)

        verify(storeFeaturesConsumer).accept(emptyList())
    }

    @Test
    fun testGetData_validState_callsConsumerWithFoldingFeatures() {
        val ffp = DeviceStateManagerFoldingFeatureProducer(
            mMockContext,
            mRawFoldSupplier,
            mMockDeviceStateManager,
        )
        ffp.onDeviceStateChanged(DEVICE_STATE_HALF_OPENED)
        val storeFeaturesConsumer = mock<Consumer<List<CommonFoldingFeature>>>()

        ffp.getData(storeFeaturesConsumer)

        verify(storeFeaturesConsumer).accept(HALF_OPENED_FOLDING_FEATURES)
    }

    @Test
    fun testGetData_invalidState_addsAcceptOnceConsumerToDataChangedCallback() {
        val ffp = DeviceStateManagerFoldingFeatureProducer(
            mMockContext,
            mRawFoldSupplier,
            mMockDeviceStateManager,
        )
        val storeFeaturesConsumer = mock<Consumer<List<CommonFoldingFeature>>>()

        ffp.getData(storeFeaturesConsumer)

        verify(storeFeaturesConsumer, never()).accept(any())
        ffp.onDeviceStateChanged(DEVICE_STATE_HALF_OPENED)
        ffp.onDeviceStateChanged(DEVICE_STATE_OPENED)
        verify(storeFeaturesConsumer).accept(HALF_OPENED_FOLDING_FEATURES)
    }

    @Test
    fun testDeviceStateMapper_malformedDeviceStatePosturePair_skipsPair() {
        val malformedDeviceStatePostures = arrayOf(
            // Missing the posture.
            "0",
            // Empty string.
            "",
            // Too many elements.
            "0:1:2",
        )
        mMockResources.stub {
            on { getStringArray(R.array.config_device_state_postures) } doReturn
                    malformedDeviceStatePostures
        }

        DeviceStateManagerFoldingFeatureProducer(
            mMockContext,
            mRawFoldSupplier,
            mMockDeviceStateManager,
        )

        verify(mMockDeviceStateManager, never()).registerCallback(any(), any())
    }

    @Test
    fun testDeviceStateMapper_invalidNumberFormat_skipsPair() {
        val invalidNumberFormatDeviceStatePostures = arrayOf("a:1", "0:b", "a:b", ":1")
        mMockResources.stub {
            on { getStringArray(R.array.config_device_state_postures) } doReturn
                    invalidNumberFormatDeviceStatePostures
        }

        DeviceStateManagerFoldingFeatureProducer(
            mMockContext,
            mRawFoldSupplier,
            mMockDeviceStateManager,
        )

        verify(mMockDeviceStateManager, never()).registerCallback(any(), any())
    }

    companion object {
        // Supported device states configuration.
        private enum class SupportedDeviceStates {
            CLOSED, HALF_OPENED, OPENED, REAR_DISPLAY, CONCURRENT;

            override fun toString() = ordinal.toString()

            fun toDeviceState(): DeviceState =
                DeviceState(DeviceState.Configuration.Builder(ordinal, name).build())
        }

        // Map of supported device states supplied by DeviceStateManager to WM Jetpack posture.
        private val DEVICE_STATE_POSTURES =
            arrayOf(
                "${SupportedDeviceStates.CLOSED}:$COMMON_STATE_NO_FOLDING_FEATURES",
                "${SupportedDeviceStates.HALF_OPENED}:$COMMON_STATE_HALF_OPENED",
                "${SupportedDeviceStates.OPENED}:$COMMON_STATE_FLAT",
                "${SupportedDeviceStates.REAR_DISPLAY}:$COMMON_STATE_NO_FOLDING_FEATURES",
                "${SupportedDeviceStates.CONCURRENT}:$COMMON_STATE_USE_BASE_STATE",
            )
        private val DEVICE_STATE_HALF_OPENED = SupportedDeviceStates.HALF_OPENED.toDeviceState()
        private val DEVICE_STATE_OPENED = SupportedDeviceStates.OPENED.toDeviceState()

        // WindowsManager Jetpack display features.
        private val DISPLAY_FEATURES = "fold-[1104,0,1104,1848]"
        private val DISPLAY_FEATURES_HALF_OPENED_HINGE = "$DISPLAY_FEATURES-half-opened"
        private val HALF_OPENED_FOLDING_FEATURES = CommonFoldingFeature.parseListFromString(
            DISPLAY_FEATURES,
            COMMON_STATE_HALF_OPENED,
        )
        private val UNKNOWN_STATE_FOLDING_FEATURES = CommonFoldingFeature.parseListFromString(
            DISPLAY_FEATURES,
            COMMON_STATE_UNKNOWN,
        )
    }
}
