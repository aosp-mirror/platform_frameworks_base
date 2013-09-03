/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.documentsui.model;

import static com.android.documentsui.DocumentsActivity.TAG;
import static com.android.documentsui.model.DocumentInfo.asFileNotFoundException;

import android.content.ContentResolver;
import android.net.Uri;
import android.util.Log;

import com.android.documentsui.RootsCache;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.FileNotFoundException;
import java.util.LinkedList;

/**
 * Representation of a stack of {@link DocumentInfo}, usually the result of a
 * user-driven traversal.
 */
public class DocumentStack extends LinkedList<DocumentInfo> {

    public static String serialize(DocumentStack stack) {
        final JSONArray json = new JSONArray();
        for (int i = 0; i < stack.size(); i++) {
            json.put(stack.get(i).uri);
        }
        return json.toString();
    }

    public static DocumentStack deserialize(ContentResolver resolver, String raw)
            throws FileNotFoundException {
        Log.d(TAG, "deserialize: " + raw);

        final DocumentStack stack = new DocumentStack();
        try {
            final JSONArray json = new JSONArray(raw);
            for (int i = 0; i < json.length(); i++) {
                final Uri uri = Uri.parse(json.getString(i));
                final DocumentInfo doc = DocumentInfo.fromUri(resolver, uri);
                stack.add(doc);
            }
        } catch (JSONException e) {
            throw asFileNotFoundException(e);
        }

        // TODO: handle roots that have gone missing
        return stack;
    }

    public RootInfo getRoot(RootsCache roots) {
        return roots.findRoot(getLast().uri);
    }

    public String getTitle(RootsCache roots) {
        if (size() == 1) {
            return getRoot(roots).title;
        } else if (size() > 1) {
            return peek().displayName;
        } else {
            return null;
        }
    }
}
