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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.graphics.Typeface;
import android.os.Handler;
import android.provider.FontsContract.Columns;
import android.provider.FontsContract.FontFamilyResult;
import android.provider.FontsContract.FontInfo;
import android.provider.FontsContract;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class FontsContractE2ETest {
    private static final String AUTHORITY = "android.provider.fonts.font";
    private static final String PACKAGE = "com.android.frameworks.coretests";

    // Signature to be used for authentication to access content provider.
    // In this test case, the content provider and consumer live in the same package, self package's
    // signature works.
    private static List<List<byte[]>> SIGNATURE;
    static {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try {
            PackageManager manager = context.getPackageManager();
            PackageInfo info = manager.getPackageInfo(
                    context.getPackageName(), PackageManager.GET_SIGNATURES);
            ArrayList<byte[]> out = new ArrayList<>();
            for (Signature sig : info.signatures) {
                out.add(sig.toByteArray());
            }
            SIGNATURE = new ArrayList<>();
            SIGNATURE.add(out);
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    @Before
    public void setUp() {
        MockFontProvider.prepareFontFiles(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
    }

    @After
    public void tearDown() {
        MockFontProvider.cleanUpFontFiles(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
    }

    private static class TestCallback extends FontsContract.FontRequestCallback {
        private Typeface mTypeface;

        private int mSuccessCallCount;
        private int mFailedCallCount;

        public void onTypefaceRetrieved(Typeface typeface) {
            mTypeface = typeface;
            mSuccessCallCount++;
        }

        public void onTypefaceRequestFailed(int reason) {
            mFailedCallCount++;
        }

        public Typeface getTypeface() {
            return mTypeface;
        }

        public int getSuccessCallCount() {
            return mSuccessCallCount;
        }

        public int getFailedCallCount() {
            return mFailedCallCount;
        }
    }

    @Test
    public void typefaceCacheTest() throws NameNotFoundException {
        Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        Context ctx = inst.getTargetContext();

        final TestCallback callback = new TestCallback();
        inst.runOnMainSync(() -> {
            FontRequest request = new FontRequest(
                    AUTHORITY, PACKAGE, "singleFontFamily", SIGNATURE);
            FontsContract.requestFonts(ctx, request, new Handler(), null, callback);
        });
        inst.waitForIdleSync();
        assertEquals(1, callback.getSuccessCallCount());
        assertEquals(0, callback.getFailedCallCount());
        assertNotNull(callback.getTypeface());

        final TestCallback callback2 = new TestCallback();
        inst.runOnMainSync(() -> {
            FontRequest request = new FontRequest(
                    AUTHORITY, PACKAGE, "singleFontFamily", SIGNATURE);
            FontsContract.requestFonts(ctx, request, new Handler(), null, callback2);
        });
        inst.waitForIdleSync();
        assertEquals(1, callback2.getSuccessCallCount());
        assertEquals(0, callback2.getFailedCallCount());
        assertSame(callback.getTypeface(), callback2.getTypeface());

        final TestCallback callback3 = new TestCallback();
        inst.runOnMainSync(() -> {
            FontRequest request = new FontRequest(
                    AUTHORITY, PACKAGE, "singleFontFamily2", SIGNATURE);
            FontsContract.requestFonts(ctx, request, new Handler(), null, callback3);
        });
        inst.waitForIdleSync();
        assertEquals(1, callback3.getSuccessCallCount());
        assertEquals(0, callback3.getFailedCallCount());
        assertNotSame(callback.getTypeface(), callback3.getTypeface());
    }

    @Test
    public void typefaceNotCacheTest() throws NameNotFoundException {
        Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        Context ctx = inst.getTargetContext();

        FontRequest request = new FontRequest(
                AUTHORITY, PACKAGE, "singleFontFamily", SIGNATURE);
        FontFamilyResult result = FontsContract.fetchFonts(
                ctx, null /* cancellation signal */, request);
        assertEquals(FontFamilyResult.STATUS_OK, result.getStatusCode());
        Typeface typeface = FontsContract.buildTypeface(
                ctx, null /* cancellation signal */, result.getFonts());

        FontFamilyResult result2 = FontsContract.fetchFonts(
                ctx, null /* cancellation signal */, request);
        assertEquals(FontFamilyResult.STATUS_OK, result2.getStatusCode());
        Typeface typeface2 = FontsContract.buildTypeface(
                ctx, null /* cancellation signal */, result2.getFonts());

        // Neighter fetchFonts nor buildTypeface should cache the Typeface.
        assertNotSame(typeface, typeface2);
    }

    @Test
    public void typefaceNullFdTest() throws NameNotFoundException {
        Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        Context ctx = inst.getTargetContext();

        FontRequest request = new FontRequest(
                AUTHORITY, PACKAGE, MockFontProvider.NULL_FD_QUERY, SIGNATURE);
        FontFamilyResult result = FontsContract.fetchFonts(
                ctx, null /* cancellation signal */, request);
        assertNull(FontsContract.buildTypeface(
                ctx, null /* cancellation signal */, result.getFonts()));
    }

    @Test
    public void getFontSyncTest() {
        FontRequest request = new FontRequest(AUTHORITY, PACKAGE, "singleFontFamily", SIGNATURE);
        assertNotNull(FontsContract.getFontSync(request));
    }

    @Test
    public void getFontSyncTest_timeout() {
        FontRequest request = new FontRequest(
                AUTHORITY, PACKAGE, MockFontProvider.BLOCKING_QUERY, SIGNATURE);
        assertNull(FontsContract.getFontSync(request));
        MockFontProvider.unblock();
    }
}
