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

package com.android.statementservice.retriever;

import android.util.JsonReader;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class that parses JSON-formatted statements.
 */
/* package private */ final class StatementParser {

    private static final String FIELD_NOT_STRING_FORMAT_STRING = "Expected %s to be string.";
    private static final String FIELD_NOT_ARRAY_FORMAT_STRING = "Expected %s to be array.";

    /**
     * Parses a JSON array of statements.
     */
    static ParsedStatement parseStatementList(String statementList, AbstractAsset source)
            throws JSONException, IOException {
        List<Statement> statements = new ArrayList<Statement>();
        List<String> delegates = new ArrayList<String>();

        JsonReader reader = new JsonReader(new StringReader(statementList));
        reader.setLenient(false);

        reader.beginArray();
        while (reader.hasNext()) {
            ParsedStatement result;
            try {
                result = parseStatement(reader, source);
            } catch (AssociationServiceException e) {
                // The element in the array is well formatted Json but not a well-formed Statement.
                continue;
            }
            statements.addAll(result.getStatements());
            delegates.addAll(result.getDelegates());
        }
        reader.endArray();

        return new ParsedStatement(statements, delegates);
    }

    /**
     * Parses a single JSON statement.
     */
    static ParsedStatement parseStatement(String statementString, AbstractAsset source)
            throws AssociationServiceException, IOException, JSONException {
        JsonReader reader = new JsonReader(new StringReader(statementString));
        reader.setLenient(false);
        return parseStatement(reader, source);
    }

    /**
     * Parses a single JSON statement. This method guarantees that exactly one JSON object
     * will be consumed.
     */
    static ParsedStatement parseStatement(JsonReader reader, AbstractAsset source)
            throws JSONException, AssociationServiceException, IOException {
        List<Statement> statements = new ArrayList<Statement>();
        List<String> delegates = new ArrayList<String>();

        JSONObject statement = JsonParser.parse(reader);

        if (statement.optString(Utils.DELEGATE_FIELD_DELEGATE, null) != null) {
            delegates.add(statement.optString(Utils.DELEGATE_FIELD_DELEGATE));
        } else {
            JSONObject targetObject = statement.optJSONObject(Utils.ASSET_DESCRIPTOR_FIELD_TARGET);
            if (targetObject == null) {
                throw new AssociationServiceException(String.format(
                        FIELD_NOT_STRING_FORMAT_STRING, Utils.ASSET_DESCRIPTOR_FIELD_TARGET));
            }

            JSONArray relations = statement.optJSONArray(Utils.ASSET_DESCRIPTOR_FIELD_RELATION);
            if (relations == null) {
                throw new AssociationServiceException(String.format(
                        FIELD_NOT_ARRAY_FORMAT_STRING, Utils.ASSET_DESCRIPTOR_FIELD_RELATION));
            }

            AbstractAsset target = AssetFactory.create(targetObject);
            for (int i = 0; i < relations.length(); i++) {
                statements.add(Statement
                        .create(source, target, Relation.create(relations.getString(i))));
            }
        }

        return new ParsedStatement(statements, delegates);
    }
}
