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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility class that parses JSON-formatted statements.
 */
/* package private */ final class StatementParser {

    /**
     * Parses a JSON array of statements.
     */
    static ParsedStatement parseStatementList(String statementList, AbstractAsset source)
            throws JSONException, AssociationServiceException {
        List<Statement> statements = new ArrayList<Statement>();
        List<String> delegates = new ArrayList<String>();

        JSONArray statementsJson = new JSONArray(statementList);
        for (int i = 0; i < statementsJson.length(); i++) {
            ParsedStatement result = parseStatement(statementsJson.getString(i), source);
            statements.addAll(result.getStatements());
            delegates.addAll(result.getDelegates());
        }

        return new ParsedStatement(statements, delegates);
    }

    /**
     * Parses a single JSON statement.
     */
    static ParsedStatement parseStatement(String statementString, AbstractAsset source)
            throws JSONException, AssociationServiceException {
        List<Statement> statements = new ArrayList<Statement>();
        List<String> delegates = new ArrayList<String>();
        JSONObject statement = new JSONObject(statementString);
        if (statement.optString(Utils.DELEGATE_FIELD_DELEGATE, null) != null) {
            delegates.add(statement.optString(Utils.DELEGATE_FIELD_DELEGATE));
        } else {
            AbstractAsset target = AssetFactory
                    .create(statement.getString(Utils.ASSET_DESCRIPTOR_FIELD_TARGET));
            JSONArray relations = statement.getJSONArray(
                    Utils.ASSET_DESCRIPTOR_FIELD_RELATION);
            for (int i = 0; i < relations.length(); i++) {
                statements.add(Statement
                        .create(source, target, Relation.create(relations.getString(i))));
            }
        }

        return new ParsedStatement(statements, delegates);
    }
}
