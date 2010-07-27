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

package android.database.sqlite;

import android.test.suitebuilder.annotation.LargeTest;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Minimal test for JDBC driver
 */
public class SQLiteJDBCDriverTest extends AbstractJDBCDriverTest {

    private File dbFile;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        dbFile = File.createTempFile("sqliteTestDB", null);
    }
    
    @Override
    protected void tearDown() throws Exception {
        if(dbFile != null) {
            dbFile.delete();
        }
        super.tearDown();
    }

    @Override
    protected String getConnectionURL() {
        return "jdbc:sqlite:/" + dbFile;
    }

    @Override
    protected File getDbFile() {
        return dbFile;
    }

    @Override
    protected String getJDBCDriverClassName() {
        return "SQLite.JDBCDriver";
    }
    
    // Regression test for (Noser) #255: PreparedStatement.executeUpdate results
    // in VM crashing with SIGABRT.
    @LargeTest
    public void test_connection3() throws Exception {
        PreparedStatement prst = null;
        Statement st = null;
        Connection conn = null;
        try {
            Class.forName("SQLite.JDBCDriver").newInstance();
            if (dbFile.exists()) {
                dbFile.delete();
            }
            conn = DriverManager.getConnection("jdbc:sqlite:/"
                        + dbFile.getPath());
            assertNotNull(conn);

            // create table
            st = conn.createStatement();
            String sql = "CREATE TABLE zoo (ZID INTEGER NOT NULL, family VARCHAR (20) NOT NULL, name VARCHAR (20) NOT NULL, PRIMARY KEY(ZID) )";
            st.executeUpdate(sql);
            
            String update = "update zoo set family = ? where name = ?;";
            prst = conn.prepareStatement(update);
            prst.setString(1, "cat");
            prst.setString(2, "Yasha");
            // st = conn.createStatement();
            // st.execute("select * from zoo where family = 'cat'");
            // ResultSet rs = st.getResultSet();
            // assertEquals(0, getCount(rs));
            prst.executeUpdate();
            // st.execute("select * from zoo where family = 'cat'");
            // ResultSet rs1 = st.getResultSet();
            // assertEquals(1, getCount(rs1));
            try {
                prst = conn.prepareStatement("");
                prst.execute();
                fail("SQLException is not thrown");
            } catch (SQLException e) {
                // expected
            }
            
            try {
                conn.prepareStatement(null);
                fail("NPE is not thrown");
            } catch (Exception e) {
                // expected
            }
            try {
                st = conn.createStatement();
                st.execute("drop table if exists zoo");
                
            } catch (SQLException e) {
                fail("Couldn't drop table: " + e.getMessage());
            } finally {
                try {
                    st.close();
                    conn.close();
                } catch (SQLException ee) {
                }
            }
        } finally {
            try {
                if (prst != null) {
                    prst.close();
                }
                if (st != null) {
                    st.close();
                }
            } catch (SQLException ee) {
            }
        }

    }
    
}
