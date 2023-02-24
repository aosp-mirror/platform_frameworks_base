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

package com.android.systemui.statusbar.pipeline.shared

import android.net.Network
import android.net.NetworkCapabilities
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogLevel
import com.android.systemui.log.dagger.StatusBarConnectivityLog
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger.Companion.toString
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach

@SysUISingleton
class ConnectivityPipelineLogger @Inject constructor(
    @StatusBarConnectivityLog private val buffer: LogBuffer,
) {
    /**
     * Logs a change in one of the **raw inputs** to the connectivity pipeline.
     *
     * Use this method for inputs that don't have any extra information besides their callback name.
     */
    fun logInputChange(callbackName: String) {
        buffer.log(
            SB_LOGGING_TAG,
            LogLevel.INFO,
            { str1 = callbackName },
            { "Input: $str1" }
        )
    }

    /**
     * Logs a change in one of the **raw inputs** to the connectivity pipeline.
     */
    fun logInputChange(callbackName: String, changeInfo: String?) {
        buffer.log(
                SB_LOGGING_TAG,
                LogLevel.INFO,
                {
                    str1 = callbackName
                    str2 = changeInfo
                },
                {
                    "Input: $str1: $str2"
                }
        )
    }

    /**
     * Logs a **data transformation** that we performed within the connectivity pipeline.
     */
    fun logTransformation(transformationName: String, oldValue: Any?, newValue: Any?) {
        if (oldValue == newValue) {
            buffer.log(
                SB_LOGGING_TAG,
                LogLevel.INFO,
                {
                    str1 = transformationName
                    str2 = oldValue.toString()
                },
                {
                    "Transform: $str1: $str2 (transformation didn't change it)"
                }
            )
        } else {
            buffer.log(
                SB_LOGGING_TAG,
                LogLevel.INFO,
                {
                    str1 = transformationName
                    str2 = oldValue.toString()
                    str3 = newValue.toString()
                },
                {
                    "Transform: $str1: $str2 -> $str3"
                }
            )
        }
    }

    /**
     * Logs a change in one of the **outputs** to the connectivity pipeline.
     */
    fun logOutputChange(outputParamName: String, changeInfo: String) {
        buffer.log(
                SB_LOGGING_TAG,
                LogLevel.INFO,
                {
                    str1 = outputParamName
                    str2 = changeInfo
                },
                {
                    "Output: $str1: $str2"
                }
        )
    }

    fun logOnCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
        buffer.log(
            SB_LOGGING_TAG,
            LogLevel.INFO,
            {
                int1 = network.getNetId()
                str1 = networkCapabilities.toString()
            },
            {
                "onCapabilitiesChanged: net=$int1 capabilities=$str1"
            }
        )
    }

    fun logOnLost(network: Network) {
        buffer.log(
            SB_LOGGING_TAG,
            LogLevel.INFO,
            {
                int1 = network.getNetId()
            },
            {
                "onLost: net=$int1"
            }
        )
    }

    companion object {
        const val SB_LOGGING_TAG = "SbConnectivity"

        /**
         * Log a change in one of the **inputs** to the connectivity pipeline.
         */
        fun Flow<Unit>.logInputChange(
            logger: ConnectivityPipelineLogger,
            inputParamName: String,
        ): Flow<Unit> {
            return this.onEach { logger.logInputChange(inputParamName) }
        }

        /**
         * Log a change in one of the **inputs** to the connectivity pipeline.
         *
         * @param prettyPrint an optional function to transform the value into a readable string.
         *   [toString] is used if no custom function is provided.
         */
        fun <T> Flow<T>.logInputChange(
            logger: ConnectivityPipelineLogger,
            inputParamName: String,
            prettyPrint: (T) -> String = { it.toString() }
        ): Flow<T> {
            return this.onEach {logger.logInputChange(inputParamName, prettyPrint(it)) }
        }

        /**
         * Log a change in one of the **outputs** to the connectivity pipeline.
         *
         * @param prettyPrint an optional function to transform the value into a readable string.
         *   [toString] is used if no custom function is provided.
         */
        fun <T> Flow<T>.logOutputChange(
                logger: ConnectivityPipelineLogger,
                outputParamName: String,
                prettyPrint: (T) -> String = { it.toString() }
        ): Flow<T> {
            return this.onEach { logger.logOutputChange(outputParamName, prettyPrint(it)) }
        }
    }
}
