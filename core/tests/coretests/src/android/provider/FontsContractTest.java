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

import static android.provider.FontsContract.Columns.RESULT_CODE_FONT_NOT_FOUND;
import static android.provider.FontsContract.Columns.RESULT_CODE_FONT_UNAVAILABLE;
import static android.provider.FontsContract.Columns.RESULT_CODE_MALFORMED_QUERY;
import static android.provider.FontsContract.Columns.RESULT_CODE_OK;

import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ProviderInfo;
import android.content.pm.Signature;
import android.database.MatrixCursor;
import android.graphics.fonts.FontVariationAxis;
import android.provider.FontsContract.FontInfo;
import android.test.ProviderTestCase2;
import android.util.Base64;

import androidx.test.filters.SmallTest;

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
    private PackageManager mPackageManager;

    public FontsContractTest() {
        super(TestFontsProvider.class, TestFontsProvider.AUTHORITY);
    }

    public void setUp() throws Exception {
        super.setUp();

        mProvider = getProvider();
        mPackageManager = mock(PackageManager.class);
    }

    public void testGetFontFromProvider_resultOK() {
        FontInfo[] fonts = FontsContract.getFontFromProvider(
                getMockContext(), request, TestFontsProvider.AUTHORITY, null);
        assertNotNull(fonts);
        assertEquals(1, fonts.length);
        FontInfo font = fonts[0];
        assertEquals(TestFontsProvider.TTC_INDEX, font.getTtcIndex());
        FontVariationAxis[] actual = font.getAxes();
        assertEquals(1, actual.length);
        assertEquals("wdth", actual[0].getTag());
        assertEquals(1.0f, actual[0].getStyleValue(), 0);
        assertEquals(TestFontsProvider.NORMAL_WEIGHT, font.getWeight());
        assertEquals(TestFontsProvider.ITALIC, font.isItalic());
        assertNotNull(font.getUri());
        assertEquals(RESULT_CODE_OK, font.getResultCode());
    }

    public void testGetFontFromProvider_providerDoesntReturnAllFields() {
        mProvider.setReturnAllFields(false);

        FontInfo[] fonts = FontsContract.getFontFromProvider(
                getMockContext(), request, TestFontsProvider.AUTHORITY, null);
        assertNotNull(fonts);
        assertEquals(1, fonts.length);
        FontInfo font = fonts[0];
        assertEquals(0, font.getTtcIndex());
        assertNull(font.getAxes());
        assertEquals(400, font.getWeight());
        assertFalse(font.isItalic());
        assertNotNull(font.getUri());
        assertEquals(RESULT_CODE_OK, font.getResultCode());
    }

    public void testGetFontFromProvider_resultFontNotFound() {
        // Make the provider return unknown
        mProvider.setResultCode(RESULT_CODE_FONT_NOT_FOUND);
        FontInfo[] fonts = FontsContract.getFontFromProvider(
                getMockContext(), request, TestFontsProvider.AUTHORITY, null);
        assertNotNull(fonts);
        assertEquals(1, fonts.length);
        FontInfo font = fonts[0];
        assertEquals(TestFontsProvider.TTC_INDEX, font.getTtcIndex());
        assertNotNull(font.getUri());
        assertEquals(RESULT_CODE_FONT_NOT_FOUND, font.getResultCode());
    }

    public void testGetFontFromProvider_resultFontUnavailable() {
        // Make the provider return font unavailable
        mProvider.setResultCode(RESULT_CODE_FONT_UNAVAILABLE);
        FontInfo[] fonts = FontsContract.getFontFromProvider(
                getMockContext(), request, TestFontsProvider.AUTHORITY, null);

        assertNotNull(fonts);
        assertEquals(1, fonts.length);
        FontInfo font = fonts[0];
        assertEquals(TestFontsProvider.TTC_INDEX, font.getTtcIndex());
        FontVariationAxis[] actual = font.getAxes();
        assertEquals(1, actual.length);
        assertEquals("wdth", actual[0].getTag());
        assertEquals(1.0f, actual[0].getStyleValue(), 0);
        assertEquals(TestFontsProvider.NORMAL_WEIGHT, font.getWeight());
        assertEquals(TestFontsProvider.ITALIC, font.isItalic());
        assertNotNull(font.getUri());
        assertEquals(RESULT_CODE_FONT_UNAVAILABLE, font.getResultCode());
    }

    public void testGetFontFromProvider_resultMalformedQuery() {
        // Make the provider return font unavailable
        mProvider.setResultCode(RESULT_CODE_MALFORMED_QUERY);
        FontInfo[] fonts = FontsContract.getFontFromProvider(
                getMockContext(), request, TestFontsProvider.AUTHORITY, null);

        assertNotNull(fonts);
        assertEquals(1, fonts.length);
        FontInfo font = fonts[0];
        assertEquals(TestFontsProvider.TTC_INDEX, font.getTtcIndex());
        FontVariationAxis[] actual = font.getAxes();
        assertEquals(1, actual.length);
        assertEquals("wdth", actual[0].getTag());
        assertEquals(1.0f, actual[0].getStyleValue(), 0);
        assertEquals(TestFontsProvider.NORMAL_WEIGHT, font.getWeight());
        assertEquals(TestFontsProvider.ITALIC, font.isItalic());
        assertNotNull(font.getUri());
        assertEquals(RESULT_CODE_MALFORMED_QUERY, font.getResultCode());
    }

    public void testGetFontFromProvider_resultFontNotFoundSecondRow() {
        MatrixCursor cursor = new MatrixCursor(new String[] { FontsContract.Columns._ID,
                FontsContract.Columns.TTC_INDEX, FontsContract.Columns.VARIATION_SETTINGS,
                FontsContract.Columns.WEIGHT, FontsContract.Columns.ITALIC,
                FontsContract.Columns.RESULT_CODE });
        cursor.addRow(new Object[] { 1, 0, null, 400, 0, RESULT_CODE_OK});
        cursor.addRow(new Object[] { 1, 0, null, 400, 0,
                RESULT_CODE_FONT_NOT_FOUND});
        mProvider.setCustomCursor(cursor);
        FontInfo[] fonts = FontsContract.getFontFromProvider(
                getMockContext(), request, TestFontsProvider.AUTHORITY, null);

        assertNotNull(fonts);
        assertEquals(2, fonts.length);

        FontInfo font = fonts[0];
        assertEquals(0, font.getTtcIndex());
        assertNull(font.getAxes());
        assertEquals(400, font.getWeight());
        assertFalse(font.isItalic());
        assertNotNull(font.getUri());
        assertEquals(RESULT_CODE_OK, font.getResultCode());

        font = fonts[1];
        assertEquals(0, font.getTtcIndex());
        assertNull(font.getAxes());
        assertEquals(400, font.getWeight());
        assertFalse(font.isItalic());
        assertNotNull(font.getUri());
        assertEquals(RESULT_CODE_FONT_NOT_FOUND, font.getResultCode());
    }

    public void testGetFontFromProvider_resultFontNotFoundOtherRow() {
        MatrixCursor cursor = new MatrixCursor(new String[] { FontsContract.Columns._ID,
                FontsContract.Columns.TTC_INDEX, FontsContract.Columns.VARIATION_SETTINGS,
                FontsContract.Columns.WEIGHT, FontsContract.Columns.ITALIC,
                FontsContract.Columns.RESULT_CODE });
        cursor.addRow(new Object[] { 1, 0, null, 400, 0, RESULT_CODE_OK});
        cursor.addRow(new Object[] { 1, 0, null, 400, 0,
                RESULT_CODE_FONT_NOT_FOUND});
        cursor.addRow(new Object[] { 1, 0, null, 400, 0, RESULT_CODE_OK});
        mProvider.setCustomCursor(cursor);
        FontInfo[] fonts = FontsContract.getFontFromProvider(
                getMockContext(), request, TestFontsProvider.AUTHORITY, null);

        assertNotNull(fonts);
        assertEquals(3, fonts.length);

        FontInfo font = fonts[0];
        assertEquals(0, font.getTtcIndex());
        assertNull(font.getAxes());
        assertEquals(400, font.getWeight());
        assertFalse(font.isItalic());
        assertNotNull(font.getUri());
        assertEquals(RESULT_CODE_OK, font.getResultCode());

        font = fonts[1];
        assertEquals(0, font.getTtcIndex());
        assertNull(font.getAxes());
        assertEquals(400, font.getWeight());
        assertFalse(font.isItalic());
        assertNotNull(font.getUri());
        assertEquals(RESULT_CODE_FONT_NOT_FOUND, font.getResultCode());

        font = fonts[2];
        assertEquals(0, font.getTtcIndex());
        assertNull(font.getAxes());
        assertEquals(400, font.getWeight());
        assertFalse(font.isItalic());
        assertNotNull(font.getUri());
        assertEquals(RESULT_CODE_OK, font.getResultCode());
    }

    public void testGetProvider_providerNotFound() {
        when(mPackageManager.resolveContentProvider(anyString(), anyInt())).thenReturn(null);

        try {
            FontsContract.getProvider(mPackageManager, request);
            fail();
        } catch (NameNotFoundException e) {
            // pass
        }
    }

    public void testGetProvider_providerIsSystemApp() throws PackageManager.NameNotFoundException {
        ProviderInfo info = setupPackageManager();
        info.applicationInfo.flags = ApplicationInfo.FLAG_SYSTEM;
        when(mPackageManager.resolveContentProvider(anyString(), anyInt())).thenReturn(info);

        ProviderInfo result = FontsContract.getProvider(mPackageManager, request);
        assertEquals(info, result);
    }

    public void testGetProvider_providerIsSystemAppWrongPackage()
            throws PackageManager.NameNotFoundException {
        ProviderInfo info = setupPackageManager();
        info.applicationInfo.flags = ApplicationInfo.FLAG_SYSTEM;
        when(mPackageManager.resolveContentProvider(anyString(), anyInt())).thenReturn(info);

        try {
            FontsContract.getProvider(
                mPackageManager,
                new FontRequest(TestFontsProvider.AUTHORITY, "com.wrong.package", "query"));
            fail();
        } catch (NameNotFoundException e) {
            // pass
        }

    }

    public void testGetProvider_providerIsNonSystemAppNoCerts()
            throws PackageManager.NameNotFoundException {
        setupPackageManager();

        assertNull(FontsContract.getProvider(mPackageManager, request));
    }

    public void testGetProvider_providerIsNonSystemAppWrongCerts()
            throws PackageManager.NameNotFoundException {
        setupPackageManager();

        byte[] wrongCert = Base64.decode("this is a wrong cert", Base64.DEFAULT);
        List<byte[]> certList = Arrays.asList(wrongCert);
        FontRequest requestWrongCerts = new FontRequest(
                TestFontsProvider.AUTHORITY, PACKAGE_NAME, "query", Arrays.asList(certList));

        assertNull(FontsContract.getProvider(mPackageManager, requestWrongCerts));
    }

    public void testGetProvider_providerIsNonSystemAppCorrectCerts()
            throws PackageManager.NameNotFoundException {
        ProviderInfo info = setupPackageManager();

        List<byte[]> certList = Arrays.asList(BYTE_ARRAY);
        FontRequest requestRightCerts = new FontRequest(
                TestFontsProvider.AUTHORITY, PACKAGE_NAME, "query", Arrays.asList(certList));
        ProviderInfo result = FontsContract.getProvider(
                mPackageManager, requestRightCerts);

        assertEquals(info, result);
    }

    public void testGetProvider_providerIsNonSystemAppMoreCerts()
            throws PackageManager.NameNotFoundException {
        setupPackageManager();

        byte[] wrongCert = Base64.decode("this is a wrong cert", Base64.DEFAULT);
        List<byte[]> certList = Arrays.asList(wrongCert, BYTE_ARRAY);
        FontRequest requestRightCerts = new FontRequest(
                TestFontsProvider.AUTHORITY, PACKAGE_NAME, "query", Arrays.asList(certList));
        assertNull(FontsContract.getProvider(mPackageManager, requestRightCerts));
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
        assertNull(FontsContract.getProvider(mPackageManager, requestRightCerts));
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
        ProviderInfo result = FontsContract.getProvider(mPackageManager, requestRightCerts);

        assertEquals(info, result);
    }

    public void testGetProvider_providerIsNonSystemAppWrongPackage()
            throws PackageManager.NameNotFoundException {
        setupPackageManager();

        List<List<byte[]>> certList = new ArrayList<>();
        certList.add(Arrays.asList(BYTE_ARRAY));
        FontRequest requestRightCerts = new FontRequest(
                TestFontsProvider.AUTHORITY, "com.wrong.package.name", "query", certList);
        try {
            FontsContract.getProvider(mPackageManager, requestRightCerts);
            fail();
        } catch (NameNotFoundException e) {
            // pass
        }
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
