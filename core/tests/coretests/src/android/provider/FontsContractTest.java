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
package android.provider;

import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.Signature;
import android.database.MatrixCursor;
import android.graphics.Typeface;
import android.graphics.fonts.FontRequest;
import android.graphics.fonts.FontResult;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.support.test.filters.SmallTest;
import android.test.ProviderTestCase2;
import android.util.Base64;

import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Unit tests for {@link FontsContract}.
 */
@SmallTest
public class FontsContractTest extends ProviderTestCase2<TestFontsProvider> {
    private static final byte[] BYTE_ARRAY =
            Base64.decode("e04fd020ea3a6910a2d808002b30", Base64.DEFAULT);
    // Use a different instance to test byte array comparison
    private static final byte[] BYTE_ARRAY_COPY =
            Base64.decode("e04fd020ea3a6910a2d808002b30", Base64.DEFAULT);
    private static final byte[] BYTE_ARRAY_2 =
            Base64.decode("e04fd020ea3a6910a2d808002b32", Base64.DEFAULT);
    private static final String PACKAGE_NAME = "com.my.font.provider.package";

    private final FontRequest request = new FontRequest(
            TestFontsProvider.AUTHORITY, PACKAGE_NAME, "query");
    private TestFontsProvider mProvider;
    private FontsContract mContract;
    private ResultReceiver mResultReceiver;
    private PackageManager mPackageManager;

    public FontsContractTest() {
        super(TestFontsProvider.class, TestFontsProvider.AUTHORITY);
    }

    public void setUp() throws Exception {
        super.setUp();

        mProvider = getProvider();
        mPackageManager = mock(PackageManager.class);
        mContract = new FontsContract(getMockContext(), mPackageManager);
        mResultReceiver = mock(ResultReceiver.class);
    }

    public void testGetFontFromProvider_resultOK() {
        mContract.getFontFromProvider(request, mResultReceiver, TestFontsProvider.AUTHORITY);

        final ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(mResultReceiver).send(
                eq(FontsContract.Columns.RESULT_CODE_OK), bundleCaptor.capture());

        Bundle bundle = bundleCaptor.getValue();
        assertNotNull(bundle);
        List<FontResult> resultList =
                bundle.getParcelableArrayList(FontsContract.PARCEL_FONT_RESULTS);
        assertNotNull(resultList);
        assertEquals(1, resultList.size());
        FontResult fontResult = resultList.get(0);
        assertEquals(TestFontsProvider.TTC_INDEX, fontResult.getTtcIndex());
        assertEquals(TestFontsProvider.VARIATION_SETTINGS, fontResult.getFontVariationSettings());
        assertEquals(TestFontsProvider.NORMAL_WEIGHT, fontResult.getWeight());
        assertEquals(TestFontsProvider.ITALIC, fontResult.getItalic());
        assertNotNull(fontResult.getFileDescriptor());
    }

    public void testGetFontFromProvider_providerDoesntReturnAllFields() {
        mProvider.setReturnAllFields(false);

        final ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        mContract.getFontFromProvider(request, mResultReceiver, TestFontsProvider.AUTHORITY);
        verify(mResultReceiver).send(
                eq(FontsContract.Columns.RESULT_CODE_OK), bundleCaptor.capture());

        Bundle bundle = bundleCaptor.getValue();
        assertNotNull(bundle);
        List<FontResult> resultList =
                bundle.getParcelableArrayList(FontsContract.PARCEL_FONT_RESULTS);
        assertNotNull(resultList);
        assertEquals(1, resultList.size());
        FontResult fontResult = resultList.get(0);
        assertEquals(0, fontResult.getTtcIndex());
        assertNull(fontResult.getFontVariationSettings());
        assertEquals(400, fontResult.getWeight());
        assertFalse(fontResult.getItalic());
        assertNotNull(fontResult.getFileDescriptor());
    }

    public void testGetFontFromProvider_resultFontNotFound() {
        // Make the provider return unknown
        mProvider.setResultCode(FontsContract.Columns.RESULT_CODE_FONT_NOT_FOUND);
        mContract.getFontFromProvider(request, mResultReceiver, TestFontsProvider.AUTHORITY);

        verify(mResultReceiver).send(FontsContract.Columns.RESULT_CODE_FONT_NOT_FOUND,null);
    }

    public void testGetFontFromProvider_resultFontUnavailable() {
        // Make the provider return font unavailable
        mProvider.setResultCode(FontsContract.Columns.RESULT_CODE_FONT_UNAVAILABLE);
        mContract.getFontFromProvider(request, mResultReceiver, TestFontsProvider.AUTHORITY);

        verify(mResultReceiver).send(FontsContract.Columns.RESULT_CODE_FONT_UNAVAILABLE,null);
    }

    public void testGetFontFromProvider_resultMalformedQuery() {
        // Make the provider return font unavailable
        mProvider.setResultCode(FontsContract.Columns.RESULT_CODE_MALFORMED_QUERY);
        mContract.getFontFromProvider(request, mResultReceiver, TestFontsProvider.AUTHORITY);

        verify(mResultReceiver).send(FontsContract.Columns.RESULT_CODE_MALFORMED_QUERY,null);
    }

    public void testGetFontFromProvider_resultFontNotFoundSecondRow() {
        MatrixCursor cursor = new MatrixCursor(new String[] { FontsContract.Columns._ID,
                FontsContract.Columns.TTC_INDEX, FontsContract.Columns.VARIATION_SETTINGS,
                FontsContract.Columns.WEIGHT, FontsContract.Columns.ITALIC,
                FontsContract.Columns.RESULT_CODE });
        cursor.addRow(new Object[] { 1, 0, null, 400, 0, FontsContract.Columns.RESULT_CODE_OK});
        cursor.addRow(new Object[] { 1, 0, null, 400, 0,
                FontsContract.Columns.RESULT_CODE_FONT_NOT_FOUND});
        mProvider.setCustomCursor(cursor);
        mContract.getFontFromProvider(request, mResultReceiver, TestFontsProvider.AUTHORITY);

        verify(mResultReceiver).send(FontsContract.Columns.RESULT_CODE_FONT_NOT_FOUND, null);
    }

    public void testGetFontFromProvider_resultFontNotFoundOtherRow() {
        MatrixCursor cursor = new MatrixCursor(new String[] { FontsContract.Columns._ID,
                FontsContract.Columns.TTC_INDEX, FontsContract.Columns.VARIATION_SETTINGS,
                FontsContract.Columns.WEIGHT, FontsContract.Columns.ITALIC,
                FontsContract.Columns.RESULT_CODE });
        cursor.addRow(new Object[] { 1, 0, null, 400, 0, FontsContract.Columns.RESULT_CODE_OK});
        cursor.addRow(new Object[] { 1, 0, null, 400, 0,
                FontsContract.Columns.RESULT_CODE_FONT_NOT_FOUND});
        cursor.addRow(new Object[] { 1, 0, null, 400, 0, FontsContract.Columns.RESULT_CODE_OK});
        mProvider.setCustomCursor(cursor);
        mContract.getFontFromProvider(request, mResultReceiver, TestFontsProvider.AUTHORITY);

        verify(mResultReceiver).send(FontsContract.Columns.RESULT_CODE_FONT_NOT_FOUND, null);
    }

    public void testGetFontFromProvider_resultCodeIsNegativeNumber() {
        MatrixCursor cursor = new MatrixCursor(new String[] { FontsContract.Columns._ID,
                FontsContract.Columns.TTC_INDEX, FontsContract.Columns.VARIATION_SETTINGS,
                FontsContract.Columns.WEIGHT, FontsContract.Columns.ITALIC,
                FontsContract.Columns.RESULT_CODE });
        cursor.addRow(new Object[] { 1, 0, null, 400, 0, FontsContract.Columns.RESULT_CODE_OK});
        cursor.addRow(new Object[] { 1, 0, null, 400, 0, -5});
        mProvider.setCustomCursor(cursor);
        mContract.getFontFromProvider(request, mResultReceiver, TestFontsProvider.AUTHORITY);

        verify(mResultReceiver).send(FontsContract.Columns.RESULT_CODE_FONT_NOT_FOUND, null);
    }

    public void testGetProvider_providerNotFound() {
        when(mPackageManager.resolveContentProvider(anyString(), anyInt())).thenReturn(null);

        ProviderInfo result = mContract.getProvider(request, mResultReceiver);

        verify(mResultReceiver).send(FontsContract.RESULT_CODE_PROVIDER_NOT_FOUND, null);
        assertNull(result);
    }

    public void testGetProvider_providerIsSystemApp() throws PackageManager.NameNotFoundException {
        ProviderInfo info = setupPackageManager();
        info.applicationInfo.flags = ApplicationInfo.FLAG_SYSTEM;
        when(mPackageManager.resolveContentProvider(anyString(), anyInt())).thenReturn(info);

        ProviderInfo result = mContract.getProvider(request, mResultReceiver);

        verifyZeroInteractions(mResultReceiver);
        assertEquals(info, result);
    }

    public void testGetProvider_providerIsSystemAppWrongPackage()
            throws PackageManager.NameNotFoundException {
        ProviderInfo info = setupPackageManager();
        info.applicationInfo.flags = ApplicationInfo.FLAG_SYSTEM;
        when(mPackageManager.resolveContentProvider(anyString(), anyInt())).thenReturn(info);

        ProviderInfo result = mContract.getProvider(
                new FontRequest(TestFontsProvider.AUTHORITY, "com.wrong.package", "query"),
                mResultReceiver);

        verify(mResultReceiver).send(FontsContract.RESULT_CODE_PROVIDER_NOT_FOUND, null);
        assertNull(result);
    }

    public void testGetProvider_providerIsNonSystemAppNoCerts()
            throws PackageManager.NameNotFoundException {
        setupPackageManager();

        // The default request is missing the certificates info.
        ProviderInfo result = mContract.getProvider(request, mResultReceiver);

        verify(mResultReceiver).send(FontsContract.RESULT_CODE_WRONG_CERTIFICATES, null);
        assertNull(result);
    }

    public void testGetProvider_providerIsNonSystemAppWrongCerts()
            throws PackageManager.NameNotFoundException {
        setupPackageManager();

        byte[] wrongCert = Base64.decode("this is a wrong cert", Base64.DEFAULT);
        List<byte[]> certList = Arrays.asList(wrongCert);
        FontRequest requestWrongCerts = new FontRequest(
                TestFontsProvider.AUTHORITY, PACKAGE_NAME, "query", Arrays.asList(certList));
        ProviderInfo result = mContract.getProvider(requestWrongCerts, mResultReceiver);

        verify(mResultReceiver).send(FontsContract.RESULT_CODE_WRONG_CERTIFICATES, null);
        assertNull(result);
    }

    public void testGetProvider_providerIsNonSystemAppCorrectCerts()
            throws PackageManager.NameNotFoundException {
        ProviderInfo info = setupPackageManager();

        List<byte[]> certList = Arrays.asList(BYTE_ARRAY);
        FontRequest requestRightCerts = new FontRequest(
                TestFontsProvider.AUTHORITY, PACKAGE_NAME, "query", Arrays.asList(certList));
        ProviderInfo result = mContract.getProvider(requestRightCerts, mResultReceiver);

        verifyZeroInteractions(mResultReceiver);
        assertEquals(info, result);
    }

    public void testGetProvider_providerIsNonSystemAppMoreCerts()
            throws PackageManager.NameNotFoundException {
        setupPackageManager();

        byte[] wrongCert = Base64.decode("this is a wrong cert", Base64.DEFAULT);
        List<byte[]> certList = Arrays.asList(wrongCert, BYTE_ARRAY);
        FontRequest requestRightCerts = new FontRequest(
                TestFontsProvider.AUTHORITY, PACKAGE_NAME, "query", Arrays.asList(certList));
        ProviderInfo result = mContract.getProvider(requestRightCerts, mResultReceiver);

        // There is one too many certs, should fail as the set doesn't match.
        verify(mResultReceiver).send(FontsContract.RESULT_CODE_WRONG_CERTIFICATES, null);
        assertNull(result);
    }

    public void testGetProvider_providerIsNonSystemAppDuplicateCerts()
            throws PackageManager.NameNotFoundException {
        ProviderInfo info = new ProviderInfo();
        info.packageName = PACKAGE_NAME;
        info.applicationInfo = new ApplicationInfo();
        when(mPackageManager.resolveContentProvider(anyString(), anyInt())).thenReturn(info);
        PackageInfo packageInfo = new PackageInfo();
        Signature signature = mock(Signature.class);
        when(signature.toByteArray()).thenReturn(BYTE_ARRAY_COPY);
        Signature signature2 = mock(Signature.class);
        when(signature2.toByteArray()).thenReturn(BYTE_ARRAY_COPY);
        packageInfo.packageName = PACKAGE_NAME;
        packageInfo.signatures = new Signature[] { signature, signature2 };
        when(mPackageManager.getPackageInfo(anyString(), anyInt())).thenReturn(packageInfo);

        // The provider has {BYTE_ARRAY_COPY, BYTE_ARRAY_COPY}, the request has
        // {BYTE_ARRAY_2, BYTE_ARRAY_COPY}.
        List<byte[]> certList = Arrays.asList(BYTE_ARRAY_2, BYTE_ARRAY_COPY);
        FontRequest requestRightCerts = new FontRequest(
                TestFontsProvider.AUTHORITY, PACKAGE_NAME, "query", Arrays.asList(certList));
        ProviderInfo result = mContract.getProvider(requestRightCerts, mResultReceiver);

        // The given list includes an extra cert and doesn't have a second copy of the cert like
        // the provider does, so it should have failed.
        verify(mResultReceiver).send(FontsContract.RESULT_CODE_WRONG_CERTIFICATES, null);
        assertNull(result);
    }

    public void testGetProvider_providerIsNonSystemAppCorrectCertsSeveralSets()
            throws PackageManager.NameNotFoundException {
        ProviderInfo info = setupPackageManager();

        List<List<byte[]>> certList = new ArrayList<>();
        byte[] wrongCert = Base64.decode("this is a wrong cert", Base64.DEFAULT);
        certList.add(Arrays.asList(wrongCert));
        certList.add(Arrays.asList(BYTE_ARRAY));
        FontRequest requestRightCerts = new FontRequest(
                TestFontsProvider.AUTHORITY, PACKAGE_NAME, "query", certList);
        ProviderInfo result = mContract.getProvider(requestRightCerts, mResultReceiver);

        verifyZeroInteractions(mResultReceiver);
        assertEquals(info, result);
    }

    public void testGetProvider_providerIsNonSystemAppWrongPackage()
            throws PackageManager.NameNotFoundException {
        setupPackageManager();

        List<List<byte[]>> certList = new ArrayList<>();
        certList.add(Arrays.asList(BYTE_ARRAY));
        FontRequest requestRightCerts = new FontRequest(
                TestFontsProvider.AUTHORITY, "com.wrong.package.name", "query", certList);
        ProviderInfo result = mContract.getProvider(requestRightCerts, mResultReceiver);

        verify(mResultReceiver).send(FontsContract.RESULT_CODE_PROVIDER_NOT_FOUND, null);
        assertNull(result);
    }

    private ProviderInfo setupPackageManager()
            throws PackageManager.NameNotFoundException {
        ProviderInfo info = new ProviderInfo();
        info.packageName = PACKAGE_NAME;
        info.applicationInfo = new ApplicationInfo();
        when(mPackageManager.resolveContentProvider(anyString(), anyInt())).thenReturn(info);
        PackageInfo packageInfo = new PackageInfo();
        Signature signature = mock(Signature.class);
        when(signature.toByteArray()).thenReturn(BYTE_ARRAY_COPY);
        packageInfo.packageName = PACKAGE_NAME;
        packageInfo.signatures = new Signature[] { signature };
        when(mPackageManager.getPackageInfo(anyString(), anyInt())).thenReturn(packageInfo);
        return info;
    }
}
