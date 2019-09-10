/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.protologtool

import com.github.javaparser.ast.Node
import java.lang.Exception
import java.lang.RuntimeException

class HashCollisionException(message: String) : RuntimeException(message)

class IllegalImportException(message: String) : Exception(message)

class InvalidProtoLogCallException(message: String, node: Node)
    : RuntimeException("$message\nAt: $node")

class InvalidViewerConfigException : Exception {
    constructor(message: String) : super(message)

    constructor(message: String, ex: Exception) : super(message, ex)
}

class InvalidFormatStringException : Exception {
    constructor(message: String) : super(message)

    constructor(message: String, ex: Exception) : super(message, ex)
}

class InvalidInputException(message: String) : Exception(message)

class InvalidCommandException(message: String) : Exception(message)
