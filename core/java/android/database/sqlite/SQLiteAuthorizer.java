/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.database.sqlite;

import android.annotation.IntDef;
import android.annotation.Nullable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Authorizer which is consulted during compilation of a SQL statement.
 * <p>
 * During compilation, this callback will be invoked to determine if each action
 * requested by the SQL statement is allowed.
 * <p>
 * This can be useful to dynamically block interaction with private, internal,
 * or otherwise sensitive columns or tables inside a database, such as when
 * compiling an untrusted SQL statement.
 */
public interface SQLiteAuthorizer {
    /** @hide */
    @IntDef(prefix = { "SQLITE_AUTHORIZER_RESULT_" }, value = {
            SQLITE_AUTHORIZER_RESULT_OK,
            SQLITE_AUTHORIZER_RESULT_DENY,
            SQLITE_AUTHORIZER_RESULT_IGNORE,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface AuthorizerResult {}

    /** @hide */
    @IntDef(prefix = { "SQLITE_ACTION_" }, value = {
            SQLITE_ACTION_CREATE_INDEX,
            SQLITE_ACTION_CREATE_TABLE,
            SQLITE_ACTION_CREATE_TEMP_INDEX,
            SQLITE_ACTION_CREATE_TEMP_TABLE,
            SQLITE_ACTION_CREATE_TEMP_TRIGGER,
            SQLITE_ACTION_CREATE_TEMP_VIEW,
            SQLITE_ACTION_CREATE_TRIGGER,
            SQLITE_ACTION_CREATE_VIEW,
            SQLITE_ACTION_DELETE,
            SQLITE_ACTION_DROP_INDEX,
            SQLITE_ACTION_DROP_TABLE,
            SQLITE_ACTION_DROP_TEMP_INDEX,
            SQLITE_ACTION_DROP_TEMP_TABLE,
            SQLITE_ACTION_DROP_TEMP_TRIGGER,
            SQLITE_ACTION_DROP_TEMP_VIEW,
            SQLITE_ACTION_DROP_TRIGGER,
            SQLITE_ACTION_DROP_VIEW,
            SQLITE_ACTION_INSERT,
            SQLITE_ACTION_PRAGMA,
            SQLITE_ACTION_READ,
            SQLITE_ACTION_SELECT,
            SQLITE_ACTION_TRANSACTION,
            SQLITE_ACTION_UPDATE,
            SQLITE_ACTION_ATTACH,
            SQLITE_ACTION_DETACH,
            SQLITE_ACTION_ALTER_TABLE,
            SQLITE_ACTION_REINDEX,
            SQLITE_ACTION_ANALYZE,
            SQLITE_ACTION_CREATE_VTABLE,
            SQLITE_ACTION_DROP_VTABLE,
            SQLITE_ACTION_FUNCTION,
            SQLITE_ACTION_SAVEPOINT,
            SQLITE_ACTION_RECURSIVE,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface AuthorizerAction {}

    /** Successful result */
    int SQLITE_AUTHORIZER_RESULT_OK = 0;
    /** Abort the SQL statement with an error */
    int SQLITE_AUTHORIZER_RESULT_DENY = 1;
    /** Don't allow access, but don't generate an error */
    int SQLITE_AUTHORIZER_RESULT_IGNORE = 2;

    /** Authorizer action for {@code CREATE INDEX} */
    int SQLITE_ACTION_CREATE_INDEX          = 1;
    /** Authorizer action for {@code CREATE TABLE} */
    int SQLITE_ACTION_CREATE_TABLE          = 2;
    /** Authorizer action for {@code CREATE TEMP INDEX} */
    int SQLITE_ACTION_CREATE_TEMP_INDEX     = 3;
    /** Authorizer action for {@code CREATE TEMP TABLE} */
    int SQLITE_ACTION_CREATE_TEMP_TABLE     = 4;
    /** Authorizer action for {@code CREATE TEMP TRIGGER} */
    int SQLITE_ACTION_CREATE_TEMP_TRIGGER   = 5;
    /** Authorizer action for {@code CREATE TEMP VIEW} */
    int SQLITE_ACTION_CREATE_TEMP_VIEW      = 6;
    /** Authorizer action for {@code CREATE TRIGGER} */
    int SQLITE_ACTION_CREATE_TRIGGER        = 7;
    /** Authorizer action for {@code CREATE VIEW} */
    int SQLITE_ACTION_CREATE_VIEW           = 8;
    /** Authorizer action for {@code DELETE} */
    int SQLITE_ACTION_DELETE                = 9;
    /** Authorizer action for {@code DROP INDEX} */
    int SQLITE_ACTION_DROP_INDEX           = 10;
    /** Authorizer action for {@code DROP TABLE} */
    int SQLITE_ACTION_DROP_TABLE           = 11;
    /** Authorizer action for {@code DROP TEMP INDEX} */
    int SQLITE_ACTION_DROP_TEMP_INDEX      = 12;
    /** Authorizer action for {@code DROP TEMP TABLE} */
    int SQLITE_ACTION_DROP_TEMP_TABLE      = 13;
    /** Authorizer action for {@code DROP TEMP TRIGGER} */
    int SQLITE_ACTION_DROP_TEMP_TRIGGER    = 14;
    /** Authorizer action for {@code DROP TEMP VIEW} */
    int SQLITE_ACTION_DROP_TEMP_VIEW       = 15;
    /** Authorizer action for {@code DROP TRIGGER} */
    int SQLITE_ACTION_DROP_TRIGGER         = 16;
    /** Authorizer action for {@code DROP VIEW} */
    int SQLITE_ACTION_DROP_VIEW            = 17;
    /** Authorizer action for {@code INSERT} */
    int SQLITE_ACTION_INSERT               = 18;
    /** Authorizer action for {@code PRAGMA} */
    int SQLITE_ACTION_PRAGMA               = 19;
    /** Authorizer action for read access on a specific table and column */
    int SQLITE_ACTION_READ                 = 20;
    /** Authorizer action for {@code SELECT} */
    int SQLITE_ACTION_SELECT               = 21;
    /** Authorizer action for transaction operations */
    int SQLITE_ACTION_TRANSACTION          = 22;
    /** Authorizer action for {@code UPDATE} */
    int SQLITE_ACTION_UPDATE               = 23;
    /** Authorizer action for {@code ATTACH} */
    int SQLITE_ACTION_ATTACH               = 24;
    /** Authorizer action for {@code DETACH} */
    int SQLITE_ACTION_DETACH               = 25;
    /** Authorizer action for {@code ALTER TABLE} */
    int SQLITE_ACTION_ALTER_TABLE          = 26;
    /** Authorizer action for {@code REINDEX} */
    int SQLITE_ACTION_REINDEX              = 27;
    /** Authorizer action for {@code ANALYZE} */
    int SQLITE_ACTION_ANALYZE              = 28;
    /** Authorizer action for {@code CREATE VIRTUAL TABLE} */
    int SQLITE_ACTION_CREATE_VTABLE        = 29;
    /** Authorizer action for {@code DROP VIRTUAL TABLE} */
    int SQLITE_ACTION_DROP_VTABLE          = 30;
    /** Authorizer action for invocation of a function */
    int SQLITE_ACTION_FUNCTION             = 31;
    /** Authorizer action for savepoint operations */
    int SQLITE_ACTION_SAVEPOINT            = 32;
    /** Authorizer action for recursive operations */
    int SQLITE_ACTION_RECURSIVE            = 33;

    /**
     * Test if the given action should be allowed.
     *
     * @param action The action requested by the SQL statement currently being
     *            compiled.
     * @param arg3 Optional argument relevant to the given action.
     * @param arg4 Optional argument relevant to the given action.
     * @param arg5 Optional argument relevant to the given action.
     * @param arg6 Optional argument relevant to the given action.
     * @return {@link SQLiteConstants#SQLITE_AUTHORIZER_RESULT_OK} to allow the action,
     *         {@link SQLiteConstants#SQLITE_AUTHORIZER_RESULT_IGNORE} to disallow the specific
     *         action but allow the SQL statement to continue to be compiled, or
     *         {@link SQLiteConstants#SQLITE_AUTHORIZER_RESULT_DENY} to cause the entire SQL
     *         statement to be rejected with an error.
     * @see <a href="https://www.sqlite.org/c3ref/c_alter_table.html">Upstream
     *      SQLite documentation</a> that describes possible actions and their
     *      arguments.
     */
    @AuthorizerResult int onAuthorize(@AuthorizerAction int action, @Nullable String arg3,
            @Nullable String arg4, @Nullable String arg5, @Nullable String arg6);
}
