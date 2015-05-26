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
import android.util.JsonToken;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A helper class that creates a {@link JSONObject} from a {@link JsonReader}.
 */
public final class JsonParser {

    private JsonParser() {}

    /**
     * Consumes and parses exactly one JSON object from the {@link JsonReader}.
     * The object's fields can only be objects, strings or arrays of strings.
     */
    public static JSONObject parse(JsonReader reader) throws IOException, JSONException {
        JSONObject output = new JSONObject();
        String errorMsg = null;

        reader.beginObject();
        while (reader.hasNext()) {
            String fieldName = reader.nextName();

            if (output.has(fieldName)) {
                errorMsg = "Duplicate field name.";
                reader.skipValue();
                continue;
            }

            JsonToken token = reader.peek();
            if (token.equals(JsonToken.BEGIN_ARRAY)) {
                output.put(fieldName, new JSONArray(parseArray(reader)));
            } else if (token.equals(JsonToken.STRING)) {
                output.put(fieldName, reader.nextString());
            } else if (token.equals(JsonToken.BEGIN_OBJECT)) {
                try {
                    output.put(fieldName, parse(reader));
                } catch (JSONException e) {
                    errorMsg = e.getMessage();
                }
            } else {
                reader.skipValue();
                errorMsg = "Unsupported value type.";
            }
        }
        reader.endObject();

        if (errorMsg != null) {
            throw new JSONException(errorMsg);
        }

        return output;
    }

    /**
     * Parses one string array from the {@link JsonReader}.
     */
    public static List<String> parseArray(JsonReader reader) throws IOException {
        ArrayList<String> output = new ArrayList<>();

        reader.beginArray();
        while (reader.hasNext()) {
            output.add(reader.nextString());
        }
        reader.endArray();

        return output;
    }
}
