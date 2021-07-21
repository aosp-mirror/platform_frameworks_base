/*
 * Copyright (C) 2020 The Android Open Source Project
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

import java.util.Locale

private val camelHumpBoundary = Regex(
    "-"
    + "|_"
    + "|(?<=[0-9])(?=[^0-9])"
    + "|(?<=[A-Z])(?=[^A-Za-z]|[A-Z][a-z])"
    + "|(?<=[a-z])(?=[^a-z])"
)

private fun String.toCamelHumps(): List<String> = split(camelHumpBoundary)

fun String.toUpperCamelCase(): String =
    toCamelHumps().joinToString("") { it.toLowerCase(Locale.ROOT).capitalize(Locale.ROOT) }

fun String.toLowerCamelCase(): String = toUpperCamelCase().decapitalize(Locale.ROOT)

fun String.toUpperKebabCase(): String =
    toCamelHumps().joinToString("-") { it.toUpperCase(Locale.ROOT) }

fun String.toLowerKebabCase(): String =
    toCamelHumps().joinToString("-") { it.toLowerCase(Locale.ROOT) }

fun String.toUpperSnakeCase(): String =
    toCamelHumps().joinToString("_") { it.toUpperCase(Locale.ROOT) }

fun String.toLowerSnakeCase(): String =
    toCamelHumps().joinToString("_") { it.toLowerCase(Locale.ROOT) }
