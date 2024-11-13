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

package com.android.server.display.config

import android.util.Spline
import android.util.Xml
import com.android.server.display.config.HighBrightnessModeData.HDR_PERCENT_OF_SCREEN_REQUIRED_DEFAULT
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import org.xmlpull.v1.XmlSerializer

@JvmOverloads
fun createSensorData(
    type: String? = null,
    name: String? = null,
    minRefreshRate: Float = 0f,
    maxRefreshRate: Float = Float.POSITIVE_INFINITY,
    supportedModes: List<SupportedModeData> = emptyList()
): SensorData {
    return SensorData(type, name, minRefreshRate, maxRefreshRate, supportedModes)
}

fun createRefreshRateData(
    defaultRefreshRate: Int = 60,
    defaultPeakRefreshRate: Int = 60,
    defaultRefreshRateInHbmHdr: Int = 60,
    defaultRefreshRateInHbmSunlight: Int = 60,
    lowPowerSupportedModes: List<SupportedModeData> = emptyList(),
    lowLightBlockingZoneSupportedModes: List<SupportedModeData> = emptyList()
): RefreshRateData {
    return RefreshRateData(
        defaultRefreshRate, defaultPeakRefreshRate,
        defaultRefreshRateInHbmHdr, defaultRefreshRateInHbmSunlight,
        lowPowerSupportedModes, lowLightBlockingZoneSupportedModes
    )
}

@JvmOverloads
fun createHdrBrightnessData(
    maxBrightnessLimits: Map<Float, Float> = mapOf(Pair(500f, 0.6f)),
    brightnessIncreaseDebounceMillis: Long = 1000,
    screenBrightnessRampIncrease: Float = 0.02f,
    brightnessDecreaseDebounceMillis: Long = 3000,
    screenBrightnessRampDecrease: Float = 0.04f,
    transitionPoint: Float = 0.65f,
    minimumHdrPercentOfScreenForNbm: Float = HDR_PERCENT_OF_SCREEN_REQUIRED_DEFAULT,
    minimumHdrPercentOfScreenForHbm: Float = HDR_PERCENT_OF_SCREEN_REQUIRED_DEFAULT,
    allowInLowPowerMode: Boolean = false,
    sdrToHdrRatioSpline: Spline? = null,
    highestHdrSdrRatio: Float = 1f
): HdrBrightnessData {
    return HdrBrightnessData(
        maxBrightnessLimits,
        brightnessIncreaseDebounceMillis,
        screenBrightnessRampIncrease,
        brightnessDecreaseDebounceMillis,
        screenBrightnessRampDecrease,
        transitionPoint,
        minimumHdrPercentOfScreenForNbm,
        minimumHdrPercentOfScreenForHbm,
        allowInLowPowerMode,
        sdrToHdrRatioSpline,
        highestHdrSdrRatio
    )
}

fun XmlSerializer.highBrightnessMode(
    enabled: String = "true",
    transitionPoint: String = "0.67",
    minimumLux: String = "2500",
    timeWindowSecs: String = "200",
    timeMaxSecs: String = "30",
    timeMinSecs: String = "3",
    refreshRateRange: Pair<String, String>? = null,
    allowInLowPowerMode: String? = null,
    minimumHdrPercentOfScreen: String? = null,
    sdrHdrRatioMap: List<Pair<String, String>>? = null,
) {
    element("highBrightnessMode") {
        attribute("", "enabled", enabled)
        element("transitionPoint", transitionPoint)
        element("minimumLux", minimumLux)
        element("timing") {
            element("timeWindowSecs", timeWindowSecs)
            element("timeMaxSecs", timeMaxSecs)
            element("timeMinSecs", timeMinSecs)
        }
        pair("refreshRate", "minimum", "maximum", refreshRateRange)
        element("allowInLowPowerMode", allowInLowPowerMode)
        element("minimumHdrPercentOfScreen", minimumHdrPercentOfScreen)
        map("sdrHdrRatioMap", "point", "sdrNits", "hdrRatio", sdrHdrRatioMap)
    }
}

fun XmlSerializer.hdrBrightnessConfig(
    brightnessMap: List<Pair<String, String>> = listOf(Pair("500", "0.6")),
    brightnessIncreaseDebounceMillis: String = "1000",
    screenBrightnessRampIncrease: String = "0.02",
    brightnessDecreaseDebounceMillis: String = "3000",
    screenBrightnessRampDecrease: String = "0.04",
    minimumHdrPercentOfScreenForNbm: String? = null,
    minimumHdrPercentOfScreenForHbm: String? = null,
    allowInLowPowerMode: String? = null,
    sdrHdrRatioMap: List<Pair<String, String>>? = null,
) {
    element("hdrBrightnessConfig") {
        map("brightnessMap", "point", "first", "second", brightnessMap)
        element("brightnessIncreaseDebounceMillis", brightnessIncreaseDebounceMillis)
        element("screenBrightnessRampIncrease", screenBrightnessRampIncrease)
        element("brightnessDecreaseDebounceMillis", brightnessDecreaseDebounceMillis)
        element("screenBrightnessRampDecrease", screenBrightnessRampDecrease)
        element("minimumHdrPercentOfScreenForNbm", minimumHdrPercentOfScreenForNbm)
        element("minimumHdrPercentOfScreenForHbm", minimumHdrPercentOfScreenForHbm)
        element("allowInLowPowerMode", allowInLowPowerMode)
        map("sdrHdrRatioMap", "point", "first", "second", sdrHdrRatioMap)
    }
}

fun createDisplayConfiguration(content: XmlSerializer.() -> Unit = { }): DisplayConfiguration {
    val byteArrayOutputStream = ByteArrayOutputStream()
    val xmlSerializer = Xml.newSerializer()
    OutputStreamWriter(byteArrayOutputStream).use { writer ->
        xmlSerializer.setOutput(writer)
        xmlSerializer.startDocument("UTF-8", true)
        xmlSerializer.startTag("", "displayConfiguration")
        xmlSerializer.content()
        xmlSerializer.endTag("", "displayConfiguration")
        xmlSerializer.endDocument()
    }
    return XmlParser.read(ByteArrayInputStream(byteArrayOutputStream.toByteArray()))
}

private fun XmlSerializer.map(
    rootName: String,
    nodeName: String,
    keyName: String,
    valueName: String,
    map: List<Pair<String, String>>?
) {
    map?.let { m ->
        element(rootName) {
            m.forEach { e -> pair(nodeName, keyName, valueName, e) }
        }
    }
}

private fun XmlSerializer.pair(
    nodeName: String,
    keyName: String,
    valueName: String,
    pair: Pair<String, String>?
) {
    pair?.let {
        element(nodeName) {
            element(keyName, pair.first)
            element(valueName, pair.second)
        }
    }
}

private fun XmlSerializer.element(name: String, content: String?) {
    if (content != null) {
        startTag("", name)
        text(content)
        endTag("", name)
    }
}

private fun XmlSerializer.element(name: String, content: XmlSerializer.() -> Unit) {
    startTag("", name)
    content()
    endTag("", name)
}