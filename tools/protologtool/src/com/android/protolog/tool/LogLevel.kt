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

import com.github.javaparser.ast.Node

enum class LogLevel {
    DEBUG, VERBOSE, INFO, WARN, ERROR, WTF;

    companion object {
        fun getLevelForMethodName(name: String, node: Node, context: ParsingContext): LogLevel {
            return when (name) {
                "d" -> DEBUG
                "v" -> VERBOSE
                "i" -> INFO
                "w" -> WARN
                "e" -> ERROR
                "wtf" -> WTF
                else ->
                    throw InvalidProtoLogCallException("Unknown log level $name in $node", context)
            }
        }
    }
}
