/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package android.pim.vcard.test_utils;

import android.content.ContentResolver;
import android.content.Context;
import android.content.EntityIterator;
import android.net.Uri;
import android.pim.vcard.VCardComposer;
import android.pim.vcard.VCardConfig;
import android.pim.vcard.VCardEntryConstructor;
import android.pim.vcard.VCardInterpreter;
import android.pim.vcard.VCardInterpreterCollection;
import android.pim.vcard.VCardParser;
import android.pim.vcard.VCardUtils;
import android.pim.vcard.exception.VCardException;
import android.test.AndroidTestCase;
import android.test.mock.MockContext;
import android.text.TextUtils;
import android.util.Log;

import junit.framework.TestCase;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * <p>
 * The class lets users checks that given expected vCard data are same as given actual vCard data.
 * Able to verify both vCard importer/exporter.
 * </p>
 * <p>
 * First a user has to initialize the object by calling either
 * {@link #initForImportTest(int, int)} or {@link #initForExportTest(int)}.
 * "Round trip test" (import -> export -> import, or export -> import -> export) is not supported.
 * </p>
 */
public class VCardVerifier {
    private static final String LOG_TAG = "VCardVerifier";

    private static class CustomMockContext extends MockContext {
        final ContentResolver mResolver;
        public CustomMockContext(ContentResolver resolver) {
            mResolver = resolver;
        }

        @Override
        public ContentResolver getContentResolver() {
            return mResolver;
        }
    }

    private class VCardVerifierInternal implements VCardComposer.OneEntryHandler {
        public boolean onInit(Context context) {
            return true;
        }
        public boolean onEntryCreated(String vcard) {
            verifyOneVCardForExport(vcard);
            return true;
        }
        public void onTerminate() {
        }
    }

    private final AndroidTestCase mAndroidTestCase;
    private final VCardVerifierInternal mVCardVerifierInternal;
    private int mVCardType;
    private boolean mIsDoCoMo;

    // Only one of them must be non-empty.
    private ExportTestResolver mExportTestResolver;
    private InputStream mInputStream;

    // To allow duplication, use list instead of set.
    // When null, we don't need to do the verification.
    private PropertyNodesVerifier mPropertyNodesVerifier;
    private LineVerifier mLineVerifier;
    private ContentValuesVerifier mContentValuesVerifier;
    private boolean mInitialized;
    private boolean mVerified = false;
    private String mCharset;

    // Called by VCardTestsBase
    public VCardVerifier(AndroidTestCase androidTestCase) {
        mAndroidTestCase = androidTestCase;
        mVCardVerifierInternal = new VCardVerifierInternal();
        mExportTestResolver = null;
        mInputStream = null;
        mInitialized = false;
        mVerified = false;
    }

    // Should be called at the beginning of each import test.
    public void initForImportTest(int vcardType, int resId) {
        if (mInitialized) {
            AndroidTestCase.fail("Already initialized");
        }
        mVCardType = vcardType;
        mIsDoCoMo = VCardConfig.isDoCoMo(vcardType);
        setInputResourceId(resId);
        mInitialized = true;
    }

    // Should be called at the beginning of each export test.
    public void initForExportTest(int vcardType) {
        initForExportTest(vcardType, "UTF-8");
    }

    public void initForExportTest(int vcardType, String charset) {
        if (mInitialized) {
            AndroidTestCase.fail("Already initialized");
        }
        mExportTestResolver = new ExportTestResolver(mAndroidTestCase);
        mVCardType = vcardType;
        mIsDoCoMo = VCardConfig.isDoCoMo(vcardType);
        mInitialized = true;
        if (TextUtils.isEmpty(charset)) {
            mCharset = "UTF-8";
        } else {
            mCharset = charset;
        }
    }

    private void setInputResourceId(int resId) {
        InputStream inputStream = mAndroidTestCase.getContext().getResources().openRawResource(resId);
        if (inputStream == null) {
            AndroidTestCase.fail("Wrong resId: " + resId);
        }
        setInputStream(inputStream);
    }

    private void setInputStream(InputStream inputStream) {
        if (mExportTestResolver != null) {
            AndroidTestCase.fail("addInputEntry() is called.");
        } else if (mInputStream != null) {
            AndroidTestCase.fail("InputStream is already set");
        }
        mInputStream = inputStream;
    }

    public ContactEntry addInputEntry() {
        if (!mInitialized) {
            AndroidTestCase.fail("Not initialized");
        }
        if (mInputStream != null) {
            AndroidTestCase.fail("setInputStream is called");
        }
        return mExportTestResolver.addInputContactEntry();
    }

    public PropertyNodesVerifierElem addPropertyNodesVerifierElemWithoutVersion() {
        if (!mInitialized) {
            AndroidTestCase.fail("Not initialized");
        }
        if (mPropertyNodesVerifier == null) {
            mPropertyNodesVerifier = new PropertyNodesVerifier(mAndroidTestCase);
        }
        return mPropertyNodesVerifier.addPropertyNodesVerifierElem();
    }
    
    public PropertyNodesVerifierElem addPropertyNodesVerifierElem() {
        final PropertyNodesVerifierElem elem = addPropertyNodesVerifierElemWithoutVersion();
        final String versionString;
        if (VCardConfig.isVersion21(mVCardType)) {
            versionString = "2.1";
        } else if (VCardConfig.isVersion30(mVCardType)) {
            versionString = "3.0";
        } else if (VCardConfig.isVersion40(mVCardType)) {
            versionString = "4.0";
        } else {
            throw new RuntimeException("Unexpected vcard type during a unit test");
        }
        elem.addExpectedNodeWithOrder("VERSION", versionString);

        return elem;
    }

    public PropertyNodesVerifierElem addPropertyNodesVerifierElemWithEmptyName() {
        if (!mInitialized) {
            AndroidTestCase.fail("Not initialized");
        }
        final PropertyNodesVerifierElem elem = addPropertyNodesVerifierElem();
        if (VCardConfig.isVersion40(mVCardType)) {
            elem.addExpectedNodeWithOrder("FN", "");
        } else if (VCardConfig.isVersion30(mVCardType)) {
            elem.addExpectedNodeWithOrder("N", "");
            elem.addExpectedNodeWithOrder("FN", "");
        } else if (mIsDoCoMo) {
            elem.addExpectedNodeWithOrder("N", "");
        }
        return elem;
    }

    public LineVerifierElem addLineVerifierElem() {
        if (!mInitialized) {
            AndroidTestCase.fail("Not initialized");
        }
        if (mLineVerifier == null) {
            mLineVerifier = new LineVerifier(mAndroidTestCase, mVCardType);
        }
        return mLineVerifier.addLineVerifierElem();
    }

    public ContentValuesVerifierElem addContentValuesVerifierElem() {
        if (!mInitialized) {
            AndroidTestCase.fail("Not initialized");
        }
        if (mContentValuesVerifier == null) {
            mContentValuesVerifier = new ContentValuesVerifier();
        }

        return mContentValuesVerifier.addElem(mAndroidTestCase);
    }

    /**
     * Sets up sub-verifiers correctly and try parse given vCard as InputStream.
     * Errors around InputStream must be handled outside this method.
     *
     * Used both from {@link #verifyForImportTest()} and from {@link #verifyForExportTest()}.
     */
    private void verifyWithInputStream(InputStream is) throws IOException {
        final VCardInterpreter interpreter;
        if (mContentValuesVerifier != null) {
            final VCardEntryConstructor constructor = new VCardEntryConstructor(mVCardType);
            constructor.addEntryHandler(mContentValuesVerifier);
            if (mPropertyNodesVerifier != null) {
                interpreter = new VCardInterpreterCollection(Arrays.asList(
                        mPropertyNodesVerifier, constructor));
            } else {
                interpreter = constructor;
            }
        } else {
            if (mPropertyNodesVerifier != null) {
                interpreter = mPropertyNodesVerifier;
            } else {
                interpreter = null;
            }
        }

        try {
            // Note: we must not specify charset toward vCard parsers. This code checks whether
            // those parsers are able to encode given binary without any extra information for
            // charset.
            final VCardParser parser = VCardUtils.getAppropriateParser(mVCardType);
            parser.parse(is, interpreter);
        } catch (VCardException e) {
            AndroidTestCase.fail("Unexpected VCardException: " + e.getMessage());
        }
    }

    private void verifyOneVCardForExport(final String vcard) {
        Log.d(LOG_TAG, vcard);
        InputStream is = null;
        try {
            is = new ByteArrayInputStream(vcard.getBytes(mCharset));
            verifyWithInputStream(is);
        } catch (IOException e) {
            AndroidTestCase.fail("Unexpected IOException: " + e.getMessage());
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                    AndroidTestCase.fail("Unexpected IOException: " + e.getMessage());
                }
            }
        }
    }

    public void verify() {
        if (!mInitialized) {
            TestCase.fail("Not initialized.");
        }
        if (mVerified) {
            TestCase.fail("verify() was called twice.");
        }

        if (mInputStream != null) {
            if (mExportTestResolver != null){
                TestCase.fail("There are two input sources.");
            }
            verifyForImportTest();
        } else if (mExportTestResolver != null){
            verifyForExportTest();
        } else {
            TestCase.fail("No input is determined");
        }
        mVerified = true;
    }

    private void verifyForImportTest() {
        if (mLineVerifier != null) {
            AndroidTestCase.fail("Not supported now.");
        }

        try {
            verifyWithInputStream(mInputStream);
        } catch (IOException e) {
            AndroidTestCase.fail("IOException was thrown: " + e.getMessage());
        } finally {
            if (mInputStream != null) {
                try {
                    mInputStream.close();
                } catch (IOException e) {
                }
            }
        }
    }

    public static EntityIterator mockGetEntityIteratorMethod(
            final ContentResolver resolver,
            final Uri uri, final String selection,
            final String[] selectionArgs, final String sortOrder) {
        if (ExportTestResolver.class.equals(resolver.getClass())) {
            return ((ExportTestResolver)resolver).getProvider().queryEntities(
                    uri, selection, selectionArgs, sortOrder);
        }

        Log.e(LOG_TAG, "Unexpected provider given.");
        return null;
    }

    private Method getMockGetEntityIteratorMethod()
            throws SecurityException, NoSuchMethodException {
        return this.getClass().getMethod("mockGetEntityIteratorMethod",
                ContentResolver.class, Uri.class, String.class, String[].class, String.class);
    }

    private void verifyForExportTest() {
       final VCardComposer composer =
            new VCardComposer(new CustomMockContext(mExportTestResolver), mVCardType, mCharset);
        composer.addHandler(mLineVerifier);
        composer.addHandler(mVCardVerifierInternal);
        if (!composer.init(VCardComposer.CONTACTS_TEST_CONTENT_URI, null, null, null)) {
            AndroidTestCase.fail("init() failed. Reason: " + composer.getErrorReason());
        }
        AndroidTestCase.assertFalse(composer.isAfterLast());
        try {
            while (!composer.isAfterLast()) {
                try {
                    final Method mockGetEntityIteratorMethod = getMockGetEntityIteratorMethod();
                    AndroidTestCase.assertNotNull(mockGetEntityIteratorMethod);
                    AndroidTestCase.assertTrue(
                            composer.createOneEntry(mockGetEntityIteratorMethod));
                } catch (Exception e) {
                    e.printStackTrace();
                    AndroidTestCase.fail(e.toString());
                }
            }
        } finally {
            composer.terminate();
        }
    }
}
