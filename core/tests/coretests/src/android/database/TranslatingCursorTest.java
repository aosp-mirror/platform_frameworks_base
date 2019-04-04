/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.database;

import static com.google.common.truth.Truth.assertThat;

import android.database.TranslatingCursor.Translator;
import android.net.Uri;

import junit.framework.TestCase;

public class TranslatingCursorTest extends TestCase {

    public void testDuplicateColumnName() {
        MatrixCursor base = new MatrixCursor(new String[] {"_id", "colA", "colB", "colA"});
        base.addRow(new Object[] { 0, "r1_a", "r1_b", "r1_a"});
        base.addRow(new Object[] { 1, "r2_a", "r2_b", "r2_a"});
        Translator translator = (data, idIndex, matchingColumn, cursor) -> data.toUpperCase();
        TranslatingCursor.Config config = new TranslatingCursor.Config(Uri.EMPTY, "_id", "colA");
        TranslatingCursor translating = new TranslatingCursor(base, config, translator, false);

        translating.moveToNext();
        String[] expected = new String[] { "ignored", "R1_A", "r1_b", "R1_A" };
        for (int i = 1; i < translating.getColumnCount(); i++) {
            assertThat(translating.getString(i)).isEqualTo(expected[i]);
        }
        translating.moveToNext();
        expected = new String[] { "ignored", "R2_A", "r2_b", "R2_A" };
        for (int i = 1; i < translating.getColumnCount(); i++) {
            assertThat(translating.getString(i)).isEqualTo(expected[i]);
        }
    }

}
