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

package com.android.wm.shell.dagger

import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.shared.annotations.ShellBackgroundThread
import com.android.wm.shell.shared.annotations.ShellMainThread
import dagger.Module
import dagger.Provides
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher

/** Providers for various WmShell-specific coroutines-related constructs. */
@Module
class WMShellCoroutinesModule {
  @Provides
  @ShellMainThread
  fun provideMainDispatcher(@ShellMainThread mainExecutor: ShellExecutor): CoroutineDispatcher =
      mainExecutor.asCoroutineDispatcher()

  @Provides
  @ShellBackgroundThread
  fun provideBackgroundDispatcher(
      @ShellBackgroundThread backgroundExecutor: ShellExecutor
  ): CoroutineDispatcher = backgroundExecutor.asCoroutineDispatcher()

  @Provides
  @WMSingleton
  @ShellMainThread
  fun provideApplicationScope(
      @ShellMainThread applicationDispatcher: CoroutineDispatcher,
  ): CoroutineScope = CoroutineScope(applicationDispatcher)

  @Provides
  @WMSingleton
  @ShellBackgroundThread
  fun provideBackgroundCoroutineScope(
      @ShellBackgroundThread backgroundDispatcher: CoroutineDispatcher,
  ): CoroutineScope = CoroutineScope(backgroundDispatcher)

  @Provides
  @WMSingleton
  @ShellBackgroundThread
  fun provideBackgroundCoroutineContext(
      @ShellBackgroundThread backgroundDispatcher: CoroutineDispatcher
  ): CoroutineContext = backgroundDispatcher + SupervisorJob()
}
