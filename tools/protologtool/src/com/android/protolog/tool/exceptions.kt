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

package com.android.protolog.tool

import java.lang.Exception

open class CodeProcessingException(message: String, context: ParsingContext)
    : Exception("Code processing error in ${context.filePath}:${context.lineNumber}:\n" +
        "  $message")

class HashCollisionException(message: String, context: ParsingContext) :
        CodeProcessingException(message, context)

class IllegalImportException(message: String, context: ParsingContext) :
        CodeProcessingException("Illegal import: $message", context)

class InvalidProtoLogCallException(message: String, context: ParsingContext)
    : CodeProcessingException("InvalidProtoLogCall: $message", context)

class ParsingException(message: String, context: ParsingContext)
    : CodeProcessingException(message, context)

class InvalidViewerConfigException(message: String) : Exception(message)

class InvalidInputException(message: String) : Exception(message)

class InvalidCommandException(message: String) : Exception(message)
