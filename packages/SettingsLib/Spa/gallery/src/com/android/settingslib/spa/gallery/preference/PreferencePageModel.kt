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

package com.android.settingslib.spa.gallery.preference

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.settingslib.spa.framework.common.PageModel
import com.android.settingslib.spa.framework.common.SpaEnvironmentFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "PreferencePageModel"

class PreferencePageModel : PageModel() {
    companion object {
        // Defines all the resources for this page.
        // In real Settings App, resources data is defined in xml, rather than SPP.
        const val PAGE_TITLE = "Sample Preference"
        const val SIMPLE_PREFERENCE_TITLE = "Preference"
        const val SIMPLE_PREFERENCE_SUMMARY = "Simple summary"
        const val DISABLE_PREFERENCE_TITLE = "Disabled"
        const val DISABLE_PREFERENCE_SUMMARY = "Disabled summary"
        const val ASYNC_PREFERENCE_TITLE = "Async Preference"
        const val ASYNC_PREFERENCE_SUMMARY = "Async summary"
        const val MANUAL_UPDATE_PREFERENCE_TITLE = "Manual Updater"
        const val AUTO_UPDATE_PREFERENCE_TITLE = "Auto Updater"
        val SIMPLE_PREFERENCE_KEYWORDS = listOf("simple keyword1", "simple keyword2")

        @Composable
        fun create(): PreferencePageModel {
            val pageModel: PreferencePageModel = viewModel()
            pageModel.initOnce()
            return pageModel
        }
    }

    private val spaLogger = SpaEnvironmentFactory.instance.logger

    val asyncSummary = mutableStateOf("(loading)")
    val asyncEnable = mutableStateOf(false)

    private val manualUpdater = mutableStateOf(0)

    private val autoUpdater = object : MutableLiveData<String>(" ") {
        private var tick = 0
        private var updateJob: Job? = null
        override fun onActive() {
            spaLogger.message(TAG, "autoUpdater.active")
            updateJob = viewModelScope.launch(Dispatchers.IO) {
                while (true) {
                    delay(1000L)
                    tick++
                    spaLogger.message(TAG, "autoUpdater.value $tick")
                    postValue(tick.toString())
                }
            }
        }

        override fun onInactive() {
            spaLogger.message(TAG, "autoUpdater.inactive")
            updateJob?.cancel()
        }
    }

    override fun initialize(arguments: Bundle?) {
        spaLogger.message(TAG, "initialize with args " + arguments.toString())
        viewModelScope.launch(Dispatchers.IO) {
            // Loading your data here.
            delay(2000L)
            asyncSummary.value = ASYNC_PREFERENCE_SUMMARY
            asyncEnable.value = true
        }
    }

    fun getManualUpdaterSummary(): State<String> {
        spaLogger.message(TAG, "getManualUpdaterSummary")
        return derivedStateOf { manualUpdater.value.toString() }
    }

    fun manualUpdaterOnClick() {
        spaLogger.message(TAG, "manualUpdaterOnClick")
        manualUpdater.value = manualUpdater.value + 1
    }

    fun getAutoUpdaterSummary(): LiveData<String> {
        spaLogger.message(TAG, "getAutoUpdaterSummary")
        return autoUpdater
    }
}
