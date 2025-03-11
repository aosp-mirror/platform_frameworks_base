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

package com.android.statementservice.database

import android.content.UriRelativeFilter
import android.content.UriRelativeFilterGroup
import android.util.JsonReader
import androidx.room.TypeConverter
import org.json.JSONArray
import org.json.JSONObject
import java.io.StringReader
import java.util.ArrayList

class Converters {
    companion object {
        private const val ACTION_NAME = "action"
        private const val FILTERS_NAME = "filters"
        private const val URI_PART_NAME = "uriPart"
        private const val PATTERN_TYPE_NAME = "patternType"
        private const val FILTER_NAME = "filter"
    }

    @TypeConverter
    fun groupsToJson(groups: List<UriRelativeFilterGroup>): String {
        val json = JSONArray()
        for (group in groups) {
            json.put(groupToJson(group))
        }
        return json.toString()
    }

    @TypeConverter
    fun stringToGroups(json: String): List<UriRelativeFilterGroup> {
        val groups = ArrayList<UriRelativeFilterGroup>()
        StringReader(json).use { stringReader ->
            JsonReader(stringReader).use { reader ->
                reader.beginArray()
                while (reader.hasNext()) {
                    groups.add(parseGroup(reader))
                }
                reader.endArray()
            }
        }
        return groups
    }

    private fun groupToJson(group: UriRelativeFilterGroup): JSONObject {
        val jsonObject = JSONObject()
        jsonObject.put(ACTION_NAME, group.action)
        val filters = JSONArray()
        for (filter in group.uriRelativeFilters) {
            filters.put(filterToJson(filter))
        }
        jsonObject.put(FILTERS_NAME, filters)
        return jsonObject
    }

    private fun filterToJson(filter: UriRelativeFilter): JSONObject {
        val jsonObject = JSONObject()
        jsonObject.put(URI_PART_NAME, filter.uriPart)
        jsonObject.put(PATTERN_TYPE_NAME, filter.patternType)
        jsonObject.put(FILTER_NAME, filter.filter)
        return jsonObject
    }

    private fun parseGroup(reader: JsonReader): UriRelativeFilterGroup {
        val jsonObject = JSONObject()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            when (name) {
                ACTION_NAME -> jsonObject.put(ACTION_NAME, reader.nextInt())
                FILTERS_NAME -> jsonObject.put(FILTERS_NAME, parseFilters(reader))
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        val group = UriRelativeFilterGroup(jsonObject.getInt(ACTION_NAME))
        val filters = jsonObject.getJSONArray(FILTERS_NAME)
        for (i in 0 until filters.length()) {
            val filter = filters.getJSONObject(i)
            group.addUriRelativeFilter(UriRelativeFilter(
                filter.getInt(URI_PART_NAME),
                filter.getInt(PATTERN_TYPE_NAME),
                filter.getString(FILTER_NAME)
            ))
        }
        return group
    }

    private fun parseFilters(reader: JsonReader): JSONArray {
        val filters = JSONArray()
        reader.beginArray()
        while (reader.hasNext()) {
            filters.put(parseFilter(reader))
        }
        reader.endArray()
        return filters
    }

    private fun parseFilter(reader: JsonReader): JSONObject {
        reader.beginObject()
        val jsonObject = JSONObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            when (name) {
                URI_PART_NAME, PATTERN_TYPE_NAME -> jsonObject.put(name, reader.nextInt())
                FILTER_NAME -> jsonObject.put(name, reader.nextString())
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return jsonObject
    }
}