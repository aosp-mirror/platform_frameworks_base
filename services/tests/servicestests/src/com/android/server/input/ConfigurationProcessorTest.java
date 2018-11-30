/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.util.Pair;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.util.List;

/**
 * Build/Install/Run:
 * atest ConfigurationProcessorTest
 */
@RunWith(AndroidJUnit4.class)
public class ConfigurationProcessorTest {

    private Context mContext;

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getContext();
    }

    @Test
    public void testGetInputPortAssociations() {
        final int res = com.android.frameworks.servicestests.R.raw.input_port_associations;
        InputStream xml = mContext.getResources().openRawResource(res);
        List<Pair<String, String>> associations = null;
        try {
            associations = ConfigurationProcessor.processInputPortAssociations(xml);
        } catch (Exception e) {
            fail("Could not process xml file for input associations");
        }
        assertNotNull(associations);
        assertEquals(2, associations.size());
        assertTrue(associations.contains(Pair.create("USB1", "0")));
        assertTrue(associations.contains(Pair.create("USB2", "1")));
    }

    @Test
    public void testGetInputPortAssociationsBadDisplayport() {
        final int res =
                com.android.frameworks.servicestests.R.raw.input_port_associations_bad_displayport;
        InputStream xml = mContext.getResources().openRawResource(res);
        List<Pair<String, String>> associations = null;
        try {
            associations = ConfigurationProcessor.processInputPortAssociations(xml);
        } catch (Exception e) {
            fail("Could not process xml file for input associations");
        }
        assertNotNull(associations);
        assertEquals(0, associations.size());
    }

    @Test
    public void testGetInputPortAssociationsEmptyConfig() {
        final int res = com.android.frameworks.servicestests.R.raw.input_port_associations_bad_xml;
        InputStream xml = mContext.getResources().openRawResource(res);
        try {
            ConfigurationProcessor.processInputPortAssociations(xml);
            fail("Parsing should fail, because xml contains bad data");
        } catch (Exception e) {
            // This is expected
        }
    }
}
