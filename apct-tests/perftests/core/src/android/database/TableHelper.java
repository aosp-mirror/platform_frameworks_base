/*
 * Copyright (C) 2017 The Android Open Source Project
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

import java.util.Date;
import java.util.UUID;

/**
 * Helper class for creating and querying data from a database in performance tests.
 *
 * Subclasses can define different table/query formats.
 */
public abstract class TableHelper {

    public interface CursorReader {
        void read();
    }

    public abstract String createSql();
    public abstract String insertSql();
    public abstract Object[] createItem(int id);
    public abstract String readSql();
    public abstract CursorReader createReader(Cursor cursor);

    /**
     * 1 column, single integer
     */
    public static TableHelper INT_1 = new TableHelper() {
        @Override
        public String createSql() {
            return "CREATE TABLE `Int1` ("
                    + "`a` INTEGER,"
                    + " PRIMARY KEY(`a`))";
        }

        @Override
        public String insertSql() {
            return "INSERT INTO `Int1`(`a`)"
                    + " VALUES (?)";
        }

        @Override
        public Object[] createItem(int id) {
            return new Object[] {
                    id,
            };
        }

        @Override
        public String readSql() {
            return "SELECT * from Int1";
        }

        @Override
        public CursorReader createReader(final Cursor cursor) {
            final int cursorIndexOfA = cursor.getColumnIndexOrThrow("a");
            return () -> {
                cursor.getInt(cursorIndexOfA);
            };
        }
    };
    /**
     * 10 columns of integers
     */
    public static TableHelper INT_10 = new TableHelper() {
        @Override
        public String createSql() {
            return "CREATE TABLE `Int10` ("
                    + "`a` INTEGER,"
                    + " `b` INTEGER,"
                    + " `c` INTEGER,"
                    + " `d` INTEGER,"
                    + " `e` INTEGER,"
                    + " `f` INTEGER,"
                    + " `g` INTEGER,"
                    + " `h` INTEGER,"
                    + " `i` INTEGER,"
                    + " `j` INTEGER,"
                    + " PRIMARY KEY(`a`))";
        }

        @Override
        public String insertSql() {
            return "INSERT INTO `Int10`(`a`,`b`,`c`,`d`,`e`,`f`,`g`,`h`,`i`,`j`)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?)";
        }

        @Override
        public Object[] createItem(int id) {
            return new Object[] {
                    id,
                    id + 1,
                    id + 2,
                    id + 3,
                    id + 4,
                    id + 5,
                    id + 6,
                    id + 7,
                    id + 8,
                    id + 9,
            };
        }

        @Override
        public String readSql() {
            return "SELECT * from Int10";
        }

        @Override
        public CursorReader createReader(final Cursor cursor) {
            final int cursorIndexOfA = cursor.getColumnIndexOrThrow("a");
            final int cursorIndexOfB = cursor.getColumnIndexOrThrow("b");
            final int cursorIndexOfC = cursor.getColumnIndexOrThrow("c");
            final int cursorIndexOfD = cursor.getColumnIndexOrThrow("d");
            final int cursorIndexOfE = cursor.getColumnIndexOrThrow("e");
            final int cursorIndexOfF = cursor.getColumnIndexOrThrow("f");
            final int cursorIndexOfG = cursor.getColumnIndexOrThrow("g");
            final int cursorIndexOfH = cursor.getColumnIndexOrThrow("h");
            final int cursorIndexOfI = cursor.getColumnIndexOrThrow("i");
            final int cursorIndexOfJ = cursor.getColumnIndexOrThrow("j");
            return () -> {
                cursor.getInt(cursorIndexOfA);
                cursor.getInt(cursorIndexOfB);
                cursor.getInt(cursorIndexOfC);
                cursor.getInt(cursorIndexOfD);
                cursor.getInt(cursorIndexOfE);
                cursor.getInt(cursorIndexOfF);
                cursor.getInt(cursorIndexOfG);
                cursor.getInt(cursorIndexOfH);
                cursor.getInt(cursorIndexOfI);
                cursor.getInt(cursorIndexOfJ);
            };
        }
    };

    /**
     * Mock up of 'user' table with various ints/strings
     */
    public static TableHelper USER = new TableHelper() {
        @Override
        public String createSql() {
            return "CREATE TABLE `User` ("
                    + "`mId` INTEGER,"
                    + " `mName` TEXT,"
                    + " `mLastName` TEXT,"
                    + " `mAge` INTEGER,"
                    + " `mAdmin` INTEGER,"
                    + " `mWeight` DOUBLE,"
                    + " `mBirthday` INTEGER,"
                    + " `mMoreText` TEXT,"
                    + " PRIMARY KEY(`mId`))";
        }

        @Override
        public String insertSql() {
            return "INSERT INTO `User`(`mId`,`mName`,`mLastName`,`mAge`,"
                    + "`mAdmin`,`mWeight`,`mBirthday`,`mMoreText`) VALUES (?,?,?,?,?,?,?,?)";
        }

        @Override
        public Object[] createItem(int id) {
            return new Object[] {
                    id,
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    (int) (10 + Math.random() * 50),
                    0,
                    (float)0,
                    new Date().getTime(),
                    UUID.randomUUID().toString(),
            };
        }

        @Override
        public String readSql() {
            return "SELECT * from User";
        }

        @Override
        public CursorReader createReader(final Cursor cursor) {
            final int cursorIndexOfMId = cursor.getColumnIndexOrThrow("mId");
            final int cursorIndexOfMName = cursor.getColumnIndexOrThrow("mName");
            final int cursorIndexOfMLastName = cursor.getColumnIndexOrThrow("mLastName");
            final int cursorIndexOfMAge = cursor.getColumnIndexOrThrow("mAge");
            final int cursorIndexOfMAdmin = cursor.getColumnIndexOrThrow("mAdmin");
            final int cursorIndexOfMWeight = cursor.getColumnIndexOrThrow("mWeight");
            final int cursorIndexOfMBirthday = cursor.getColumnIndexOrThrow("mBirthday");
            final int cursorIndexOfMMoreTextField = cursor.getColumnIndexOrThrow("mMoreText");
            return () -> {
                cursor.getInt(cursorIndexOfMId);
                cursor.getString(cursorIndexOfMName);
                cursor.getString(cursorIndexOfMLastName);
                cursor.getInt(cursorIndexOfMAge);
                cursor.getInt(cursorIndexOfMAdmin);
                cursor.getFloat(cursorIndexOfMWeight);
                if (!cursor.isNull(cursorIndexOfMBirthday)) {
                    cursor.getLong(cursorIndexOfMBirthday);
                }
                cursor.getString(cursorIndexOfMMoreTextField);
            };
        }
    };

    public static TableHelper[] TABLE_HELPERS = new TableHelper[] {
            INT_1,
            INT_10,
            USER,
    };
}
