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
package com.android.platform.test.ravenwood.ravenizer.adapter

import com.android.hoststubgen.asm.ClassNodes
import com.android.hoststubgen.visitors.OPCODE_VERSION
import com.android.platform.test.ravenwood.ravenizer.isTestLookingClass
import org.objectweb.asm.ClassVisitor

/**
 * Class visitor to rewrite the test runner for Ravenwood
 *
 * TODO: Implement it.
 */
class TestRunnerRewritingAdapter(
    protected val classes: ClassNodes,
    nextVisitor: ClassVisitor,
) : ClassVisitor(OPCODE_VERSION, nextVisitor) {
   companion object {
       /**
        * Returns true if a target class is interesting to this adapter.
        */
       fun shouldProcess(classes: ClassNodes, className: String): Boolean {
            return isTestLookingClass(classes, className)
       }
    }
}
