/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.hoststubgen

import java.io.File

/**
 * We will not print the stack trace for exceptions implementing it.
 */
interface UserErrorException

/**
 * Exceptions about parsing class files.
 */
class ClassParseException(message: String) : Exception(message)

/**
 * Use it for internal exception that really shouldn't happen.
 */
class HostStubGenInternalException(message: String) : Exception(message)

/**
 * Exceptions about the content in a jar file.
 */
class InvalidJarFileException(message: String) : Exception(message), UserErrorException

/**
 * Exceptions missing classes, fields, methods, etc.
 */
class UnknownApiException(message: String) : Exception(message), UserErrorException

/**
 * Exceptions related to invalid annotations -- e.g. more than one visibility annotation
 * on a single API.
 */
class InvalidAnnotationException(message: String) : Exception(message), UserErrorException

/**
 * We use this for general "user" errors.
 */
class GeneralUserErrorException(message: String) : Exception(message), UserErrorException

/** Base exception class for invalid command line arguments. */
open class ArgumentsException(message: String?) : Exception(message), UserErrorException

/** Thrown when the same annotation is used with different annotation arguments. */
class DuplicateAnnotationException(annotationName: String?) :
    ArgumentsException("Duplicate annotation specified: '$annotationName'")

/** Thrown when an input file does not exist. */
class InputFileNotFoundException(filename: String) :
    ArgumentsException("File '$filename' not found")

fun String.ensureFileExists(): String {
    if (!File(this).exists()) {
        throw InputFileNotFoundException(this)
    }
    return this
}
