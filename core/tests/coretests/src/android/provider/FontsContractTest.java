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

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.Signature;
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

    public void testGetFontFromProvider() {
        mContract.getFontFromProvider(request, mResultReceiver, TestFontsProvider.AUTHORITY);

        final ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(mResultReceiver).send(eq(FontsContract.RESULT_CODE_OK), bundleCaptor.capture());

        Bundle bundle = bundleCaptor.getValue();
        assertNotNull(bundle);
        List<FontResult> resultList =
                bundle.getParcelableArrayList(FontsContract.PARCEL_FONT_RESULTS);
        assertNotNull(resultList);
        assertEquals(1, resultList.size());
        FontResult fontResult = resultList.get(0);
        assertEquals(TestFontsProvider.TTC_INDEX, fontResult.getTtcIndex());
        assertEquals(TestFontsProvider.VARIATION_SETTINGS, fontResult.getFontVariationSettings());
        assertEquals(TestFontsProvider.STYLE, fontResult.getStyle());
        assertNotNull(fontResult.getFileDescriptor());
    }

    public void testGetFontFromProvider_providerDoesntReturnAllFields() {
        mProvider.setReturnAllFields(false);

        final ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        mContract.getFontFromProvider(request, mResultReceiver, TestFontsProvider.AUTHORITY);
        verify(mResultReceiver).send(eq(FontsContract.RESULT_CODE_OK), bundleCaptor.capture());

        Bundle bundle = bundleCaptor.getValue();
        assertNotNull(bundle);
        List<FontResult> resultList =
                bundle.getParcelableArrayList(FontsContract.PARCEL_FONT_RESULTS);
        assertNotNull(resultList);
        assertEquals(1, resultList.size());
        FontResult fontResult = resultList.get(0);
        assertEquals(0, fontResult.getTtcIndex());
        assertNull(fontResult.getFontVariationSettings());
        assertEquals(Typeface.NORMAL, fontResult.getStyle());
        assertNotNull(fontResult.getFileDescriptor());
    }

    public void testGetProvider_providerNotFound() {
        when(mPackageManager.resolveContentProvider(anyString(), anyInt())).thenReturn(null);

        ProviderInfo result = mContract.getProvider(request);

        assertNull(result);
    }

    public void testGetProvider_providerIsSystemApp() throws PackageManager.NameNotFoundException {
        ProviderInfo info = setupPackageManager();
        info.applicationInfo.flags = ApplicationInfo.FLAG_SYSTEM;
        when(mPackageManager.resolveContentProvider(anyString(), anyInt())).thenReturn(info);

        ProviderInfo result = mContract.getProvider(request);

        assertEquals(info, result);
    }

    public void testGetProvider_providerIsSystemAppWrongPackage()
            throws PackageManager.NameNotFoundException {
        ProviderInfo info = setupPackageManager();
        info.applicationInfo.flags = ApplicationInfo.FLAG_SYSTEM;
        when(mPackageManager.resolveContentProvider(anyString(), anyInt())).thenReturn(info);

        ProviderInfo result = mContract.getProvider(
                new FontRequest(TestFontsProvider.AUTHORITY, "com.wrong.package", "query"));

        assertNull(result);
    }

    public void testGetProvider_providerIsNonSystemAppNoCerts()
            throws PackageManager.NameNotFoundException {
        setupPackageManager();

        // The default request is missing the certificates info.
        ProviderInfo result = mContract.getProvider(request);

        assertNull(result);
    }

    public void testGetProvider_providerIsNonSystemAppWrongCerts()
            throws PackageManager.NameNotFoundException {
        setupPackageManager();

        byte[] wrongCert = Base64.decode("this is a wrong cert", Base64.DEFAULT);
        List<byte[]> certList = Arrays.asList(wrongCert);
        FontRequest requestWrongCerts = new FontRequest(
                TestFontsProvider.AUTHORITY, PACKAGE_NAME, "query", Arrays.asList(certList));
        ProviderInfo result = mContract.getProvider(requestWrongCerts);

        assertNull(result);
    }

    public void testGetProvider_providerIsNonSystemAppCorrectCerts()
            throws PackageManager.NameNotFoundException {
        ProviderInfo info = setupPackageManager();

        List<byte[]> certList = Arrays.asList(BYTE_ARRAY);
        FontRequest requestRightCerts = new FontRequest(
                TestFontsProvider.AUTHORITY, PACKAGE_NAME, "query", Arrays.asList(certList));
        ProviderInfo result = mContract.getProvider(requestRightCerts);

        assertEquals(info, result);
    }

    public void testGetProvider_providerIsNonSystemAppMoreCerts()
            throws PackageManager.NameNotFoundException {
        setupPackageManager();

        byte[] wrongCert = Base64.decode("this is a wrong cert", Base64.DEFAULT);
        List<byte[]> certList = Arrays.asList(wrongCert, BYTE_ARRAY);
        FontRequest requestRightCerts = new FontRequest(
                TestFontsProvider.AUTHORITY, PACKAGE_NAME, "query", Arrays.asList(certList));
        ProviderInfo result = mContract.getProvider(requestRightCerts);

        // There is one too many certs, should fail as the set doesn't match.
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
        ProviderInfo result = mContract.getProvider(requestRightCerts);

        assertEquals(info, result);
    }

    public void testGetProvider_providerIsNonSystemAppWrongPackage()
            throws PackageManager.NameNotFoundException {
        setupPackageManager();

        List<List<byte[]>> certList = new ArrayList<>();
        certList.add(Arrays.asList(BYTE_ARRAY));
        FontRequest requestRightCerts = new FontRequest(
                TestFontsProvider.AUTHORITY, "com.wrong.package.name", "query", certList);
        ProviderInfo result = mContract.getProvider(requestRightCerts);

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
        when(signature.toByteArray()).thenReturn(BYTE_ARRAY);
        packageInfo.packageName = PACKAGE_NAME;
        packageInfo.signatures = new Signature[] { signature };
        when(mPackageManager.getPackageInfo(anyString(), anyInt())).thenReturn(packageInfo);
        return info;
    }
}
