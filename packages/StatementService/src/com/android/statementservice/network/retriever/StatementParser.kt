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

package com.android.statementservice.network.retriever

import android.util.JsonReader
import com.android.statementservice.retriever.AbstractAsset
import com.android.statementservice.retriever.AssetFactory
import com.android.statementservice.retriever.JsonParser
import com.android.statementservice.retriever.Relation
import com.android.statementservice.retriever.Statement
import com.android.statementservice.utils.Result
import com.android.statementservice.utils.StatementUtils
import java.io.StringReader
import java.util.ArrayList
import com.android.statementservice.retriever.WebAsset
import com.android.statementservice.retriever.AndroidAppAsset

/**
 * Parses JSON from the Digital Asset Links specification. For examples, see [WebAsset],
 * [AndroidAppAsset], and [Statement].
 */
object StatementParser {

    private const val FIELD_NOT_STRING_FORMAT_STRING = "Expected %s to be string."
    private const val FIELD_NOT_ARRAY_FORMAT_STRING = "Expected %s to be array."

    /**
     * Parses a JSON array of statements.
     */
    fun parseStatementList(statementList: String, source: AbstractAsset): Result<ParsedStatement> {
        val statements: MutableList<Statement> = ArrayList()
        val delegates: MutableList<String> = ArrayList()
        StringReader(statementList).use { stringReader ->
            JsonReader(stringReader).use { reader ->
                reader.isLenient = false
                reader.beginArray()
                while (reader.hasNext()) {
                    val result = parseOneStatement(reader, source)
                    if (result is Result.Failure) {
                        continue
                    }
                    result as Result.Success
                    statements.addAll(result.value.statements)
                    delegates.addAll(result.value.delegates)
                }
                reader.endArray()
            }
        }
        return Result.Success(ParsedStatement(statements, delegates))
    }

    /**
     * Parses a single JSON statement.
     */
    fun parseStatement(statementString: String, source: AbstractAsset) =
        StringReader(statementString).use { stringReader ->
            JsonReader(stringReader).use { reader ->
                reader.isLenient = false
                parseOneStatement(reader, source)
            }
        }

    /**
     * Parses a single JSON statement. This method guarantees that exactly one JSON object
     * will be consumed.
     */
    private fun parseOneStatement(
        reader: JsonReader,
        source: AbstractAsset
    ): Result<ParsedStatement> {
        val statement = JsonParser.parse(reader)
        val delegate = statement.optString(StatementUtils.DELEGATE_FIELD_DELEGATE)
        if (!delegate.isNullOrEmpty()) {
            return Result.Success(ParsedStatement(emptyList(), listOfNotNull(delegate)))
        }

        val targetObject = statement.optJSONObject(StatementUtils.ASSET_DESCRIPTOR_FIELD_TARGET)
            ?: return Result.Failure(
                FIELD_NOT_STRING_FORMAT_STRING.format(StatementUtils.ASSET_DESCRIPTOR_FIELD_TARGET)
            )
        val relations = statement.optJSONArray(StatementUtils.ASSET_DESCRIPTOR_FIELD_RELATION)
            ?: return Result.Failure(
                FIELD_NOT_ARRAY_FORMAT_STRING.format(StatementUtils.ASSET_DESCRIPTOR_FIELD_RELATION)
            )
        val target = AssetFactory.create(targetObject)

        val statements = (0 until relations.length())
            .map { relations.getString(it) }
            .map(Relation::create)
            .map { Statement.create(source, target, it) }
        return Result.Success(ParsedStatement(statements, listOfNotNull(delegate)))
    }

    data class ParsedStatement(val statements: List<Statement>, val delegates: List<String>)
}
