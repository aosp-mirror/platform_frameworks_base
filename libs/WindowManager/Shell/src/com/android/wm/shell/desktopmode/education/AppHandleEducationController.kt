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

package com.android.wm.shell.desktopmode.education

import android.app.ActivityManager.RunningTaskInfo
import android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM
import android.os.SystemProperties
import com.android.window.flags.Flags
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.desktopmode.education.data.AppHandleEducationDatastoreRepository
import com.android.wm.shell.shared.annotations.ShellMainThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Controls app handle education end to end.
 *
 * Listen to the user trigger for app handle education, calls an api to check if the education
 * should be shown and calls an api to show education.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
@kotlinx.coroutines.ExperimentalCoroutinesApi
class AppHandleEducationController(
    private val appHandleEducationFilter: AppHandleEducationFilter,
    shellTaskOrganizer: ShellTaskOrganizer,
    private val appHandleEducationDatastoreRepository: AppHandleEducationDatastoreRepository,
    @ShellMainThread private val applicationCoroutineScope: CoroutineScope
) {
  init {
    runIfEducationFeatureEnabled {
      // TODO: b/361038716 - Use app handle state flow instead of focus task change flow
      val focusTaskChangeFlow = focusTaskChangeFlow(shellTaskOrganizer)
      applicationCoroutineScope.launch {
        // Central block handling the app's educational flow end-to-end.
        // This flow listens to the changes to the result of
        // [WindowingEducationProto#hasEducationViewedTimestampMillis()] in datastore proto object
        isEducationViewedFlow()
            .flatMapLatest { isEducationViewed ->
              if (isEducationViewed) {
                // If the education is viewed then return emptyFlow() that completes immediately.
                // This will help us to not listen to focus task changes after the education has
                // been viewed already.
                emptyFlow()
              } else {
                // This flow listens for focus task changes, which trigger the app handle education.
                focusTaskChangeFlow
                    .filter { runningTaskInfo ->
                      runningTaskInfo.topActivityInfo?.packageName?.let {
                        appHandleEducationFilter.shouldShowAppHandleEducation(it)
                      } ?: false && runningTaskInfo.windowingMode != WINDOWING_MODE_FREEFORM
                    }
                    .distinctUntilChanged()
              }
            }
            .debounce(
                APP_HANDLE_EDUCATION_DELAY) // Wait for few seconds, if the focus task changes.
            // During the delay then current emission will be cancelled.
            .flowOn(Dispatchers.IO)
            .collectLatest {
              // Fire and forget show education suspend function, manage entire lifecycle of
              // tooltip in UI class.
            }
      }
    }
  }

  private inline fun runIfEducationFeatureEnabled(block: () -> Unit) {
    if (Flags.enableDesktopWindowingAppHandleEducation()) block()
  }

  private fun isEducationViewedFlow(): Flow<Boolean> =
      appHandleEducationDatastoreRepository.dataStoreFlow
          .map { preferences -> preferences.hasEducationViewedTimestampMillis() }
          .distinctUntilChanged()

  private fun focusTaskChangeFlow(shellTaskOrganizer: ShellTaskOrganizer): Flow<RunningTaskInfo> =
      callbackFlow {
        val focusTaskChange = ShellTaskOrganizer.FocusListener { taskInfo -> trySend(taskInfo) }
        shellTaskOrganizer.addFocusListener(focusTaskChange)
        awaitClose { shellTaskOrganizer.removeFocusListener(focusTaskChange) }
      }

  private companion object {
    val APP_HANDLE_EDUCATION_DELAY: Long
      get() = SystemProperties.getLong("persist.windowing_app_handle_education_delay", 3000L)
  }
}
