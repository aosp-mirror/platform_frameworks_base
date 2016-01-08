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

package com.android.documentsui.dirlist;

import android.content.Context;
import android.database.MatrixCursor;
import android.provider.DocumentsContract.Document;

import com.android.documentsui.DirectoryResult;
import com.android.documentsui.RootCursorWrapper;
import com.android.documentsui.dirlist.MultiSelectManager.Selection;

import java.util.Random;
import java.util.Set;

public class TestModel extends Model {

    static final String[] COLUMNS = new String[]{
        RootCursorWrapper.COLUMN_AUTHORITY,
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_FLAGS,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_SIZE,
        Document.COLUMN_MIME_TYPE
    };

    private final String mAuthority;
    private Set<String> mDeleted;

    /**
     * Creates a new context. context must be configured with provider for authority.
     * @see TestContext#createStorageTestContext(Context, String).
     */
    public TestModel(Context context, String authority) {
        super(context);
        mAuthority = authority;
    }

    void update(String... names) {
        Random rand = new Random();

        MatrixCursor c = new MatrixCursor(COLUMNS);
        for (int i = 0; i < names.length; i++) {
            MatrixCursor.RowBuilder row = c.newRow();
            row.add(RootCursorWrapper.COLUMN_AUTHORITY, mAuthority);
            row.add(Document.COLUMN_DOCUMENT_ID, Integer.toString(i));
            row.add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_DELETE);
            // Generate random document names and sizes. This forces the model's internal sort code
            // to actually do something.
            row.add(Document.COLUMN_DISPLAY_NAME, names[i]);
            row.add(Document.COLUMN_SIZE, rand.nextInt());
        }

        DirectoryResult r = new DirectoryResult();
        r.cursor = c;
        update(r);
    }

    // Note that model id includes authority qualifier and is distinct
    // WRT documentId because of this.
    String idForPosition(int p) {
        return createModelId(mAuthority, Integer.toString(p));
    }

    @Override
    public void delete(Selection selected, DeletionListener listener) {
        for (String id : selected.getAll()) {
            mDeleted.add(id);
        }
        listener.onCompletion();
    }
}
