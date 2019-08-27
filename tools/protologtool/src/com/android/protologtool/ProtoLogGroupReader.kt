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

import com.android.protologtool.Constants.ENUM_VALUES_METHOD
import com.android.protologtool.Constants.GET_TAG_METHOD
import com.android.protologtool.Constants.IS_ENABLED_METHOD
import com.android.protologtool.Constants.IS_LOG_TO_LOGCAT_METHOD
import java.io.File
import java.lang.RuntimeException
import java.net.URLClassLoader

class ProtoLogGroupReader {
    private fun getClassloaderForJar(jarPath: String): ClassLoader {
        val jarFile = File(jarPath)
        val url = jarFile.toURI().toURL()
        return URLClassLoader(arrayOf(url), ProtoLogGroupReader::class.java.classLoader)
    }

    private fun getEnumValues(clazz: Class<*>): List<Enum<*>> {
        val valuesMethod = clazz.getMethod(ENUM_VALUES_METHOD)
        @Suppress("UNCHECKED_CAST")
        return (valuesMethod.invoke(null) as Array<Enum<*>>).toList()
    }

    private fun getLogGroupFromEnumValue(group: Any, clazz: Class<*>): LogGroup {
        val enabled = clazz.getMethod(IS_ENABLED_METHOD).invoke(group) as Boolean
        val textEnabled = clazz.getMethod(IS_LOG_TO_LOGCAT_METHOD).invoke(group) as Boolean
        val tag = clazz.getMethod(GET_TAG_METHOD).invoke(group) as String
        val name = (group as Enum<*>).name
        return LogGroup(name, enabled, textEnabled, tag)
    }

    fun loadFromJar(jarPath: String, className: String): Map<String, LogGroup> {
        try {
            val classLoader = getClassloaderForJar(jarPath)
            val clazz = classLoader.loadClass(className)
            val values = getEnumValues(clazz)
            return values.map { group ->
                group.name to getLogGroupFromEnumValue(group, clazz)
            }.toMap()
        } catch (ex: ReflectiveOperationException) {
            throw RuntimeException("Unable to load ProtoLogGroup enum class", ex)
        }
    }
}
