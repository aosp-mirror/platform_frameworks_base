/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.grammaticalinflection;

import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.assertEquals;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.common.collect.Maps;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class GrammaticalInflectionBackupTest {
    private static final int DEFAULT_USER_ID = 0;
    private static final String DEFAULT_PACKAGE_NAME = "com.test.package.name";

    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    @Mock
    private PackageManager mMockPackageManager;
    @Mock
    private GrammaticalInflectionService mGrammaticalInflectionService;

    private GrammaticalInflectionBackupHelper mBackupHelper;

    @Before
    public void setUp() throws Exception {
        mBackupHelper = new GrammaticalInflectionBackupHelper(
                null, mGrammaticalInflectionService, mMockPackageManager);
    }

    @Test
    public void testBackupPayload_noAppsInstalled_returnsNull() {
        assertNull(mBackupHelper.getBackupPayload(DEFAULT_USER_ID));
    }

    @Test
    public void testBackupPayload_AppsInstalled_returnsGender()
            throws IOException, ClassNotFoundException {
        mockAppInstalled();
        mockGetApplicationGrammaticalGender(Configuration.GRAMMATICAL_GENDER_MASCULINE);

        HashMap<String, Integer> payload =
                readFromByteArray(mBackupHelper.getBackupPayload(DEFAULT_USER_ID));

        // verify the payload
        HashMap<String, Integer> expectationMap = new HashMap<>();
        expectationMap.put(DEFAULT_PACKAGE_NAME, Configuration.GRAMMATICAL_GENDER_MASCULINE);
        assertTrue(Maps.difference(payload, expectationMap).areEqual());
    }

    @Test
    public void testApplyPayload_onPackageAdded_setApplicationGrammaticalGender()
            throws IOException {
        mockAppInstalled();

        HashMap<String, Integer> testData = new HashMap<>();
        testData.put(DEFAULT_PACKAGE_NAME, Configuration.GRAMMATICAL_GENDER_NEUTRAL);
        mBackupHelper.stageAndApplyRestoredPayload(convertToByteArray(testData), DEFAULT_USER_ID);
        mBackupHelper.onPackageAdded(DEFAULT_PACKAGE_NAME, DEFAULT_USER_ID);

        verify(mGrammaticalInflectionService).setRequestedApplicationGrammaticalGender(
                eq(DEFAULT_PACKAGE_NAME),
                eq(DEFAULT_USER_ID),
                eq(Configuration.GRAMMATICAL_GENDER_NEUTRAL));
    }

    @Test
    public void testSystemBackupPayload_returnsGender()
            throws IOException, ClassNotFoundException {
        doReturn(Configuration.GRAMMATICAL_GENDER_MASCULINE).when(mGrammaticalInflectionService)
                .getSystemGrammaticalGender(eq(DEFAULT_USER_ID));

        int gender = convertByteArrayToInt(mBackupHelper.getSystemBackupPayload(DEFAULT_USER_ID));

        assertEquals(gender, Configuration.GRAMMATICAL_GENDER_MASCULINE);
    }

    @Test
    public void testApplySystemPayload_setSystemWideGrammaticalGender()
            throws IOException {
        mBackupHelper.applyRestoredSystemPayload(
                intToByteArray(Configuration.GRAMMATICAL_GENDER_NEUTRAL), DEFAULT_USER_ID);

        verify(mGrammaticalInflectionService).setSystemWideGrammaticalGender(
                eq(Configuration.GRAMMATICAL_GENDER_NEUTRAL),
                eq(DEFAULT_USER_ID));
    }

    private void mockAppInstalled() {
        ApplicationInfo dummyApp = new ApplicationInfo();
        dummyApp.packageName = DEFAULT_PACKAGE_NAME;
        doReturn(List.of(dummyApp)).when(mMockPackageManager)
                .getInstalledApplicationsAsUser(any(), anyInt());
    }

    private void mockGetApplicationGrammaticalGender(int grammaticalGender) {
        doReturn(grammaticalGender).when(mGrammaticalInflectionService)
                .getApplicationGrammaticalGender(
                        eq(DEFAULT_PACKAGE_NAME), eq(DEFAULT_USER_ID));
    }

    private byte[] convertToByteArray(HashMap<String, Integer> pkgGenderInfo) throws IOException{
        try (final ByteArrayOutputStream out = new ByteArrayOutputStream();
             final ObjectOutputStream objStream = new ObjectOutputStream(out)) {
            objStream.writeObject(pkgGenderInfo);
            return out.toByteArray();
        } catch (IOException e) {
            throw e;
        }
    }

    private HashMap<String, Integer> readFromByteArray(byte[] payload)
            throws IOException, ClassNotFoundException {
        HashMap<String, Integer> data;

        try (ByteArrayInputStream byteIn = new ByteArrayInputStream(payload);
             ObjectInputStream in = new ObjectInputStream(byteIn)) {
            data = (HashMap<String, Integer>) in.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw e;
        }
        return data;
    }

    private byte[] intToByteArray(final int gender) {
        ByteBuffer bb = ByteBuffer.allocate(4);
        bb.putInt(gender);
        return bb.array();
    }

    private int convertByteArrayToInt(byte[] intBytes) {
        ByteBuffer byteBuffer = ByteBuffer.wrap(intBytes);
        return byteBuffer.getInt();
    }
}
