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

package com.android.wm.shell.automotive

import com.android.wm.shell.ShellTaskOrganizer

/**
 * A [TaskListener] which simplifies the interface when used for
 * [ShellTaskOrganizer.createRootTask].
 *
 * [onRootTaskStackCreated], [onRootTaskStackInfoChanged], [onRootTaskStackDestroyed] will be called
 * for the underlying root task.
 * The [onTaskAppeared], [onTaskInfoChanged], [onTaskVanished] are called for the children tasks.
 */
interface RootTaskStackListener : ShellTaskOrganizer.TaskListener {
    fun onRootTaskStackCreated(rootTaskStack: RootTaskStack)
    fun onRootTaskStackInfoChanged(rootTaskStack: RootTaskStack)
    fun onRootTaskStackDestroyed(rootTaskStack: RootTaskStack)
}