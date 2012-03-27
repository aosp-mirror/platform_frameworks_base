/*
 * Copyright (C) 2011 The Android Open Source Project
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


package android.filterfw.io;

import android.content.Context;
import android.filterfw.core.FilterGraph;
import android.filterfw.core.KeyValueMap;
import android.filterfw.io.GraphIOException;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.StringWriter;

/**
 * @hide
 */
public abstract class GraphReader {

    protected KeyValueMap mReferences = new KeyValueMap();

    public abstract FilterGraph readGraphString(String graphString) throws GraphIOException;

    public abstract KeyValueMap readKeyValueAssignments(String assignments) throws GraphIOException;

    public FilterGraph readGraphResource(Context context, int resourceId) throws GraphIOException {
        InputStream inputStream = context.getResources().openRawResource(resourceId);
        InputStreamReader reader = new InputStreamReader(inputStream);
        StringWriter writer = new StringWriter();
        char[] buffer = new char[1024];
        try {
            int bytesRead;
            while ((bytesRead = reader.read(buffer, 0, 1024)) > 0) {
                writer.write(buffer, 0, bytesRead);
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not read specified resource file!");
        }
        return readGraphString(writer.toString());
    }

    public void addReference(String name, Object object) {
        mReferences.put(name, object);
    }

    public void addReferencesByMap(KeyValueMap refs) {
        mReferences.putAll(refs);
    }

    public void addReferencesByKeysAndValues(Object... references) {
        mReferences.setKeyValues(references);
    }

}
