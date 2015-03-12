/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.databinding

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import com.android.databinding.LayoutXmlProcessor
import kotlin.properties.Delegates
import com.android.databinding.util.Log
import java.io.File

open class DataBindingProcessLayoutsTask : DefaultTask() {
    {
        Log.d {"created data binding task"}
    }
    var xmlProcessor: LayoutXmlProcessor by Delegates.notNull()
    var sdkDir : File by Delegates.notNull()
    [TaskAction]
    public fun doIt() {
        Log.d {"running process layouts task"}
        xmlProcessor.processResources()
        xmlProcessor.writeIntermediateFile(sdkDir)
    }
}