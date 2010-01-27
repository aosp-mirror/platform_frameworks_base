/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.core;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import junit.framework.TestCase;
import android.test.suitebuilder.annotation.MediumTest;

/**
 * Tests for the most commonly used methods of sql like creating a connection,
 * inserting, selecting, updating.
 */
public abstract class AbstractJDBCDriverTest extends TestCase {

    @MediumTest
    public void testJDBCDriver() throws Exception {
        Connection firstConnection = null;
        Connection secondConnection = null;
        File dbFile = getDbFile();
        String connectionURL = getConnectionURL();
        Statement firstStmt = null;
        Statement secondStmt = null;
        try {
            Class.forName(getJDBCDriverClassName());
            firstConnection = DriverManager.getConnection(connectionURL);
            secondConnection = DriverManager.getConnection(connectionURL);

            String[] ones = {"hello!", "goodbye"};
            short[] twos = {10, 20};
            String[] onesUpdated = new String[ones.length];
            for (int i = 0; i < ones.length; i++) {
                onesUpdated[i] = ones[i] + twos[i];
            }
            firstStmt = firstConnection.createStatement();
            firstStmt.execute("create table tbl1(one varchar(10), two smallint)");
            secondStmt = secondConnection.createStatement();

            autoCommitInsertSelectTest(firstStmt, ones, twos);
            updateSelectCommitSelectTest(firstStmt, secondStmt, ones, onesUpdated, twos);
            updateSelectRollbackSelectTest(firstStmt, secondStmt, onesUpdated, ones, twos);
        } finally {
            closeConnections(firstConnection, secondConnection, dbFile, firstStmt, secondStmt);
        }
    }

    protected abstract String getJDBCDriverClassName();
    protected abstract String getConnectionURL();
    protected abstract File getDbFile();

    private void closeConnections(Connection firstConnection, Connection secondConnection,
            File dbFile, Statement firstStmt, Statement secondStmt) {
        String failText = null;
        try {
            if (firstStmt != null) {
                firstStmt.execute("drop table tbl1");
            }
        } catch (SQLException e) {
            failText = e.getLocalizedMessage();
        }
        try {
            if (firstStmt != null) {
                firstStmt.close();
            }
        } catch (SQLException e) {
            failText = e.getLocalizedMessage();
        }
        try {
            if (firstConnection != null) {
                firstConnection.close();
            }
        } catch (SQLException e) {
            failText = e.getLocalizedMessage();
        }
        try {
            if (secondStmt != null) {
                secondStmt.close();
            }
        } catch (SQLException e) {
            failText = e.getLocalizedMessage();
        }
        try {
            if (secondConnection != null) {
                secondConnection.close();
            }
        } catch (SQLException e) {
            failText = e.getLocalizedMessage();
        }
        dbFile.delete();
        assertNull(failText, failText);
    }

    /**
     * Inserts the values from 'ones' with the values from 'twos' into 'tbl1'
     * @param stmt the statement to use for the inserts.
     * @param ones the string values to insert into tbl1.
     * @param twos the corresponding numerical values to insert into tbl1.
     * @throws SQLException in case of a problem during insert.
     */
    private void autoCommitInsertSelectTest(Statement stmt, String[] ones,
            short[] twos) throws SQLException {
        for (int i = 0; i < ones.length; i++) {
            stmt.execute("insert into tbl1 values('" + ones[i] + "'," + twos[i]
                    + ")");
        }
        assertAllFromTbl1(stmt, ones, twos);
    }

    /**
     * Asserts that all values that where added to tbl1 are actually in tbl1.
     * @param stmt the statement to use for the select.
     * @param ones the string values that where added.
     * @param twos the numerical values that where added.
     * @throws SQLException in case of a problem during select.
     */
    private void assertAllFromTbl1(Statement stmt, String[] ones, short[] twos)
            throws SQLException {
        ResultSet rs = stmt.executeQuery("select * from tbl1");
        int i = 0;
        for (; rs.next(); i++) {
            assertTrue(i < ones.length);
            assertEquals(ones[i], rs.getString("one"));
            assertEquals(twos[i], rs.getShort("two"));
        }
        assertEquals(i, ones.length);
    }

    /**
     * Tests the results of an update followed bz a select on a diffrent statement.
     * After that the first statement commits its update. and now the second 
     * statement should also be able to see the changed values in a select.
     * @param firstStmt the statement to use for the update and commit.
     * @param secondStmt the statement that should be used to check if the commit works
     * @param ones the original string values.
     * @param onesUpdated the updated string values.
     * @param twos the numerical values.
     * @throws SQLException in case of a problem during any of the executed commands.
     */
    private void updateSelectCommitSelectTest(Statement firstStmt,
            Statement secondStmt, String[] ones, String[] onesUpdated,
            short[] twos) throws SQLException {
        firstStmt.getConnection().setAutoCommit(false);
        try {
            updateOnes(firstStmt, onesUpdated, twos);
            assertAllFromTbl1(secondStmt, ones, twos);
            firstStmt.getConnection().commit();
            assertAllFromTbl1(secondStmt, onesUpdated, twos);
        } finally {
            firstStmt.getConnection().setAutoCommit(true);
        }
    }

    /**
     * Tests if an update followed by a select works. After that a rollback will 
     * be made and again a select should show that the rollback worked. 
     * @param firstStmt the statement to use for the update and the rollback
     * @param secondStmt the statement to use for checking if the rollback worked as intended.
     * @param ones the original string values.
     * @param onesUpdated the updated string values.
     * @param twos the nomerical values.
     * @throws SQLException in case of a problem during any command.
     */
    private void updateSelectRollbackSelectTest(Statement firstStmt,
            Statement secondStmt, String[] ones, String[] onesUpdated,
            short[] twos) throws SQLException {
        firstStmt.getConnection().setAutoCommit(false);
        try {
            updateOnes(firstStmt, onesUpdated, twos);
            assertAllFromTbl1(secondStmt, ones, twos);
            firstStmt.getConnection().rollback();
            assertAllFromTbl1(secondStmt, ones, twos);
        } finally {
            firstStmt.getConnection().setAutoCommit(true);
        }
    }

    /**
     * updates the sring values. the original values are stored in 'ones'
     * and the updated values in 'ones_updated'
     * @param stmt the statement to use for the update.
     * @param onesUpdated the new string values.
     * @param twos the numerical values.
     * @throws SQLException in case of a problem during update.
     */
    private void updateOnes(Statement stmt, String[] onesUpdated, short[] twos)
            throws SQLException {
        for (int i = 0; i < onesUpdated.length; i++) {
            stmt.execute("UPDATE tbl1 SET one = '" + onesUpdated[i]
                    + "' WHERE two = " + twos[i]);
        }
    }
}
