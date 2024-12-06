/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.server.pm;

import static android.content.UriRelativeFilter.PATH;
import static android.content.UriRelativeFilter.QUERY;
import static android.content.UriRelativeFilter.FRAGMENT;
import static android.content.UriRelativeFilterGroup.ACTION_ALLOW;
import static android.content.UriRelativeFilterGroup.ACTION_BLOCK;
import static android.content.pm.ApplicationInfo.PRIVATE_FLAG_EXT_ALLOWLISTED_FOR_HIDDEN_APIS;
import static android.os.PatternMatcher.PATTERN_ADVANCED_GLOB;
import static android.os.PatternMatcher.PATTERN_LITERAL;
import static android.os.PatternMatcher.PATTERN_PREFIX;
import static android.os.PatternMatcher.PATTERN_SIMPLE_GLOB;
import static android.os.PatternMatcher.PATTERN_SUFFIX;

import static com.android.internal.pm.permission.CompatibilityPermissionInfo.COMPAT_PERMS;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import static java.lang.Boolean.TRUE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.util.stream.Collectors.toList;

import android.annotation.NonNull;
import android.content.Context;
import android.content.IntentFilter;
import android.content.UriRelativeFilter;
import android.content.UriRelativeFilterGroup;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ConfigurationInfo;
import android.content.pm.FeatureGroupInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.Flags;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.Property;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.content.pm.SigningDetails;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.platform.test.annotations.Presubmit;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.util.ArraySet;

import androidx.annotation.Nullable;
import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.pm.parsing.PackageParser2;
import com.android.internal.pm.parsing.PackageParserException;
import com.android.internal.pm.parsing.pkg.PackageImpl;
import com.android.internal.pm.parsing.pkg.ParsedPackage;
import com.android.internal.pm.permission.CompatibilityPermissionInfo;
import com.android.internal.pm.pkg.component.ParsedActivity;
import com.android.internal.pm.pkg.component.ParsedActivityImpl;
import com.android.internal.pm.pkg.component.ParsedApexSystemService;
import com.android.internal.pm.pkg.component.ParsedComponent;
import com.android.internal.pm.pkg.component.ParsedInstrumentation;
import com.android.internal.pm.pkg.component.ParsedInstrumentationImpl;
import com.android.internal.pm.pkg.component.ParsedIntentInfo;
import com.android.internal.pm.pkg.component.ParsedIntentInfoImpl;
import com.android.internal.pm.pkg.component.ParsedPermission;
import com.android.internal.pm.pkg.component.ParsedPermissionGroup;
import com.android.internal.pm.pkg.component.ParsedPermissionGroupImpl;
import com.android.internal.pm.pkg.component.ParsedPermissionImpl;
import com.android.internal.pm.pkg.component.ParsedPermissionUtils;
import com.android.internal.pm.pkg.component.ParsedProvider;
import com.android.internal.pm.pkg.component.ParsedProviderImpl;
import com.android.internal.pm.pkg.component.ParsedService;
import com.android.internal.pm.pkg.component.ParsedServiceImpl;
import com.android.internal.pm.pkg.component.ParsedUsesPermission;
import com.android.internal.pm.pkg.component.ParsedUsesPermissionImpl;
import com.android.internal.pm.pkg.parsing.ParsingPackage;
import com.android.internal.pm.pkg.parsing.ParsingPackageUtils;
import com.android.internal.util.ArrayUtils;
import com.android.server.pm.parsing.PackageCacher;
import com.android.server.pm.parsing.PackageInfoUtils;
import com.android.server.pm.parsing.PackageParserUtils;
import com.android.server.pm.parsing.TestPackageParser2;
import com.android.server.pm.parsing.pkg.AndroidPackageUtils;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageUserStateInternal;

import com.google.android.collect.Sets;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Presubmit
@RunWith(AndroidJUnit4.class)
@MediumTest
public class PackageParserTest {
    // TODO(b/135203078): Update this test with all fields and validate equality. Initial change
    //  was just migrating to new interfaces. Consider adding actual equals() methods.

    @Rule
    public TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    private File mTmpDir;
    private static final File FRAMEWORK = new File("/system/framework/framework-res.apk");
    private static final String TEST_APP1_APK = "PackageParserTestApp1.apk";
    private static final String TEST_APP2_APK = "PackageParserTestApp2.apk";
    private static final String TEST_APP3_APK = "PackageParserTestApp3.apk";
    private static final String TEST_APP4_APK = "PackageParserTestApp4.apk";
    private static final String TEST_APP5_APK = "PackageParserTestApp5.apk";
    private static final String TEST_APP6_APK = "PackageParserTestApp6.apk";
    private static final String TEST_APP7_APK = "PackageParserTestApp7.apk";
    private static final String TEST_APP8_APK = "PackageParserTestApp8.apk";
    private static final String PACKAGE_NAME = "com.android.servicestests.apps.packageparserapp";

    @Before
    public void setUp() throws IOException {
        // Create a new temporary directory for each of our tests.
        mTmpDir = mTemporaryFolder.newFolder("PackageParserTest");
    }

    @Test
    public void testParse_noCache() throws Exception {
        CachePackageNameParser pp = new CachePackageNameParser(null);
        ParsedPackage pkg = pp.parsePackage(FRAMEWORK, 0 /* parseFlags */,
                false /* useCaches */);
        assertNotNull(pkg);

        pp.setCacheDir(mTmpDir);
        pkg = pp.parsePackage(FRAMEWORK, 0 /* parseFlags */,
                false /* useCaches */);
        assertNotNull(pkg);

        // Make sure that we always write out a cache entry for future reference,
        // whether or not we're asked to use caches.
        assertEquals(1, mTmpDir.list().length);
    }

    @Test
    public void testParse_withCache() throws Exception {
        CachePackageNameParser pp = new CachePackageNameParser(null);

        pp.setCacheDir(mTmpDir);
        // The first parse will write this package to the cache.
        pp.parsePackage(FRAMEWORK, 0 /* parseFlags */, true /* useCaches */);

        // Now attempt to parse the package again, should return the
        // cached result.
        ParsedPackage pkg = pp.parsePackage(FRAMEWORK, 0 /* parseFlags */,
                true /* useCaches */);
        assertEquals("cache_android", pkg.getPackageName());

        // Try again, with useCaches == false, shouldn't return the parsed
        // result.
        pkg = pp.parsePackage(FRAMEWORK, 0 /* parseFlags */, false /* useCaches */);
        assertEquals("android", pkg.getPackageName());

        // We haven't set a cache directory here : the parse should still succeed,
        // just not using the cached results.
        pp = new CachePackageNameParser(null);
        pkg = pp.parsePackage(FRAMEWORK, 0 /* parseFlags */, true /* useCaches */);
        assertEquals("android", pkg.getPackageName());

        pkg = pp.parsePackage(FRAMEWORK, 0 /* parseFlags */, false /* useCaches */);
        assertEquals("android", pkg.getPackageName());
    }

    @Test
    public void testParse_withCache_hiddenApiAllowlist() throws Exception {
        CachePackageNameParser pp = new CachePackageNameParser(null);

        pp.setCacheDir(mTmpDir);
        // The first parse will write this package to the cache.
        pp.parsePackage(FRAMEWORK, 0 /* parseFlags */, true /* useCaches */);

        // Now attempt to parse the package again, should return the
        // cached result.
        ParsedPackage pkg = pp.parsePackage(FRAMEWORK, 0 /* parseFlags */,
                true /* useCaches */);
        assertEquals("cache_android", pkg.getPackageName());

        // Create application info
        pkg.hideAsFinal();
        ApplicationInfo aInfo = PackageInfoUtils.generateApplicationInfo(pkg, 0,
                PackageUserStateInternal.DEFAULT, 0, mockPkgSetting(pkg));

        // verify ext flag for hidden APIs allowlist
        assertEquals(PRIVATE_FLAG_EXT_ALLOWLISTED_FOR_HIDDEN_APIS,
                aInfo.privateFlagsExt & PRIVATE_FLAG_EXT_ALLOWLISTED_FOR_HIDDEN_APIS);
    }

    @Test
    public void test_serializePackage() throws Exception {
        try (PackageParser2 pp = PackageParserUtils.forParsingFileWithDefaults()) {
            AndroidPackage pkg = pp.parsePackage(FRAMEWORK, 0 /* parseFlags */,
                    true /* useCaches */).hideAsFinal();

            Parcel p = Parcel.obtain();
            ((Parcelable) pkg).writeToParcel(p, 0 /* flags */);

            p.setDataPosition(0);
            ParsedPackage deserialized = new PackageImpl(p);

            assertPackagesEqual(pkg, deserialized);
        }
    }

    @Test
    @SmallTest
    @Presubmit
    public void test_roundTripKnownFields() throws Exception {
        ParsingPackage pkg = PackageImpl.forTesting("foo");
        setKnownFields(pkg);

        Parcel p = Parcel.obtain();
        ((Parcelable) pkg).writeToParcel(p, 0 /* flags */);

        p.setDataPosition(0);
        ParsedPackage deserialized = new PackageImpl(p);
        assertAllFieldsExist(deserialized);
    }

    @Test
    public void test_stringInterning() throws Exception {
        ParsingPackage pkg = PackageImpl.forTesting("foo");
        setKnownFields(pkg);

        Parcel p = Parcel.obtain();
        ((Parcelable) pkg.hideAsParsed().hideAsFinal()).writeToParcel(p, 0 /* flags */);

        p.setDataPosition(0);
        AndroidPackage deserialized = new PackageImpl(p);

        p.setDataPosition(0);
        AndroidPackage deserialized2 = new PackageImpl(p);

        assertSame(deserialized.getPackageName(), deserialized2.getPackageName());
        assertSame(deserialized.getPermission(),
                deserialized2.getPermission());
        assertSame(deserialized.getRequestedPermissions().iterator().next(),
                deserialized2.getRequestedPermissions().iterator().next());

        List<String> protectedBroadcastsOne = new ArrayList<>(1);
        protectedBroadcastsOne.addAll(deserialized.getProtectedBroadcasts());

        List<String> protectedBroadcastsTwo = new ArrayList<>(1);
        protectedBroadcastsTwo.addAll(deserialized2.getProtectedBroadcasts());

        assertSame(protectedBroadcastsOne.get(0), protectedBroadcastsTwo.get(0));

        assertSame(deserialized.getUsesLibraries().get(0),
                deserialized2.getUsesLibraries().get(0));
        assertSame(deserialized.getUsesOptionalLibraries().get(0),
                deserialized2.getUsesOptionalLibraries().get(0));
        assertSame(deserialized.getVersionName(), deserialized2.getVersionName());
        assertSame(deserialized.getSharedUserId(), deserialized2.getSharedUserId());
    }

    private File extractFile(String filename) throws Exception {
        final Context context = InstrumentationRegistry.getTargetContext();
        final File tmpFile = File.createTempFile(filename, ".apk");
        try (InputStream inputStream = context.getAssets().openNonAsset(filename)) {
            Files.copy(inputStream, tmpFile.toPath(), REPLACE_EXISTING);
        }
        return tmpFile;
    }

    /**
     * Extracts the asset file to $mTmpDir/$dirname/$filename.
     */
    private File extractFile(String filename, String dirname) throws Exception {
        final Context context = InstrumentationRegistry.getTargetContext();
        File dir = new File(mTmpDir, dirname);
        dir.mkdir();
        final File tmpFile = new File(dir, filename);
        try (InputStream inputStream = context.getAssets().openNonAsset(filename)) {
            Files.copy(inputStream, tmpFile.toPath(), REPLACE_EXISTING);
        }
        return tmpFile;
    }

    /**
     * Tests the path of cached ParsedPackage.
     */
    @Test
    public void testCache_SameFileName() throws Exception {
        // Prepare 2 package files with the same name but different paths
        TestPackageParser2 parser = new TestPackageParser2(mTmpDir);
        final File f1 = extractFile(TEST_APP1_APK, "dir1");
        final File f2 = extractFile(TEST_APP1_APK, "dir2");
        // Sleep for a while so that the cache file will be newer and valid
        Thread.sleep(1000);
        ParsedPackage pr1 = parser.parsePackage(f1, 0, true);
        ParsedPackage pr2 = parser.parsePackage(f2, 0, true);
        // Check the path of cached ParsedPackage
        assertThat(pr1.getPath()).isEqualTo(f1.getAbsolutePath());
        assertThat(pr2.getPath()).isEqualTo(f2.getAbsolutePath());
    }

    /**
     * Tests AndroidManifest.xml with no android:isolatedSplits attribute.
     */
    @Test
    public void testParseIsolatedSplitsDefault() throws Exception {
        final File testFile = extractFile(TEST_APP1_APK);
        try {
            final ParsedPackage pkg = new TestPackageParser2().parsePackage(testFile, 0, false);
            assertFalse("isolatedSplits", pkg.isIsolatedSplitLoading());
        } finally {
            testFile.delete();
        }
    }

    /**
     * Tests AndroidManifest.xml with an android:isolatedSplits attribute set to a constant.
     */
    @Test
    public void testParseIsolatedSplitsConstant() throws Exception {
        final File testFile = extractFile(TEST_APP2_APK);
        try {
            final ParsedPackage pkg = new TestPackageParser2().parsePackage(testFile, 0, false);
            assertTrue("isolatedSplits", pkg.isIsolatedSplitLoading());
        } finally {
            testFile.delete();
        }
    }

    /**
     * Tests AndroidManifest.xml with an android:isolatedSplits attribute set to a resource.
     */
    @Test
    public void testParseIsolatedSplitsResource() throws Exception {
        final File testFile = extractFile(TEST_APP3_APK);
        try {
            final ParsedPackage pkg = new TestPackageParser2().parsePackage(testFile, 0, false);
            assertTrue("isolatedSplits", pkg.isIsolatedSplitLoading());
        } finally {
            testFile.delete();
        }
    }

    @Test
    public void testParseActivityRequiredDisplayCategoryValid() throws Exception {
        final File testFile = extractFile(TEST_APP4_APK);
        String actualDisplayCategory = null;
        try {
            final ParsedPackage pkg = new TestPackageParser2().parsePackage(testFile, 0, false);
            final List<ParsedActivity> activities = pkg.getActivities();
            for (ParsedActivity activity : activities) {
                if ((PACKAGE_NAME + ".MyActivity").equals(activity.getName())) {
                    actualDisplayCategory = activity.getRequiredDisplayCategory();
                }
            }
        } finally {
            testFile.delete();
        }
        assertEquals("automotive", actualDisplayCategory);
    }

    @Test
    public void testParseActivityRequiredDisplayCategoryInvalid() throws Exception {
        final File testFile = extractFile(TEST_APP6_APK);
        String actualDisplayCategory = null;
        try {
            final ParsedPackage pkg = new TestPackageParser2().parsePackage(testFile, 0, false);
            final List<ParsedActivity> activities = pkg.getActivities();
            for (ParsedActivity activity : activities) {
                if ((PACKAGE_NAME + ".MyActivity").equals(activity.getName())) {
                    actualDisplayCategory = activity.getRequiredDisplayCategory();
                }
            }
        } catch (PackageParserException e) {
            assertThat(e.getMessage()).contains(
                    "requiredDisplayCategory attribute can only consist"
                            + " of alphanumeric characters, '_', and '.'");
        } finally {
            testFile.delete();
        }
        assertNotEquals("$automotive", actualDisplayCategory);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_RELATIVE_REFERENCE_INTENT_FILTERS)
    public void testParseUriRelativeFilterGroups() throws Exception {
        final File testFile = extractFile(TEST_APP7_APK);
        try {
            final ParsedPackage pkg = new TestPackageParser2().parsePackage(testFile, 0, false);
            final List<ParsedActivity> activities = pkg.getActivities();
            final List<ParsedIntentInfo> intents = activities.get(0).getIntents();
            final IntentFilter intentFilter = intents.get(0).getIntentFilter();
            assertEquals(7, intentFilter.countUriRelativeFilterGroups());

            UriRelativeFilterGroup group = intentFilter.getUriRelativeFilterGroup(0);
            Collection<UriRelativeFilter> filters = group.getUriRelativeFilters();
            assertEquals(ACTION_BLOCK, group.getAction());
            assertEquals(3, filters.size());
            assertTrue(filters.contains(new UriRelativeFilter(PATH, PATTERN_PREFIX, "/gizmos")));
            assertTrue(filters.contains(new UriRelativeFilter(QUERY, PATTERN_SIMPLE_GLOB,
                    ".*query=string.*")));
            assertTrue(filters.contains(new UriRelativeFilter(FRAGMENT, PATTERN_LITERAL,
                    "fragment")));

            group = intentFilter.getUriRelativeFilterGroup(1);
            filters = group.getUriRelativeFilters();
            assertEquals(ACTION_ALLOW, group.getAction());
            assertEquals(2, filters.size());
            assertTrue(filters.contains(new UriRelativeFilter(QUERY, PATTERN_LITERAL,
                    "query=string")));
            assertTrue(filters.contains(new UriRelativeFilter(FRAGMENT, PATTERN_SUFFIX,
                    "fragment")));

            group = intentFilter.getUriRelativeFilterGroup(2);
            filters = group.getUriRelativeFilters();
            assertEquals(ACTION_ALLOW, group.getAction());
            assertTrue(filters.contains(new UriRelativeFilter(PATH, PATTERN_LITERAL, "/gizmos")));
            assertTrue(filters.contains(new UriRelativeFilter(QUERY, PATTERN_LITERAL,
                    ".*query=string.*")));
            assertTrue(filters.contains(new UriRelativeFilter(FRAGMENT, PATTERN_LITERAL,
                    "fragment")));

            group = intentFilter.getUriRelativeFilterGroup(3);
            filters = group.getUriRelativeFilters();
            assertEquals(ACTION_ALLOW, group.getAction());
            assertTrue(filters.contains(new UriRelativeFilter(PATH, PATTERN_PREFIX, "/gizmos")));
            assertTrue(filters.contains(new UriRelativeFilter(QUERY, PATTERN_PREFIX,
                    ".*query=string.*")));
            assertTrue(filters.contains(new UriRelativeFilter(FRAGMENT, PATTERN_PREFIX,
                    "fragment")));

            group = intentFilter.getUriRelativeFilterGroup(4);
            filters = group.getUriRelativeFilters();
            assertEquals(ACTION_ALLOW, group.getAction());
            assertTrue(filters.contains(new UriRelativeFilter(PATH, PATTERN_SIMPLE_GLOB,
                    "/gizmos")));
            assertTrue(filters.contains(new UriRelativeFilter(QUERY, PATTERN_SIMPLE_GLOB,
                    ".*query=string.*")));
            assertTrue(filters.contains(new UriRelativeFilter(FRAGMENT, PATTERN_SIMPLE_GLOB,
                    "fragment")));

            group = intentFilter.getUriRelativeFilterGroup(5);
            filters = group.getUriRelativeFilters();
            assertEquals(ACTION_ALLOW, group.getAction());
            assertTrue(filters.contains(new UriRelativeFilter(PATH, PATTERN_ADVANCED_GLOB,
                    "/gizmos")));
            assertTrue(filters.contains(new UriRelativeFilter(QUERY, PATTERN_ADVANCED_GLOB,
                    ".*query=string.*")));
            assertTrue(filters.contains(new UriRelativeFilter(FRAGMENT, PATTERN_ADVANCED_GLOB,
                    "fragment")));

            group = intentFilter.getUriRelativeFilterGroup(6);
            filters = group.getUriRelativeFilters();
            assertEquals(ACTION_ALLOW, group.getAction());
            assertTrue(filters.contains(new UriRelativeFilter(PATH, PATTERN_SUFFIX, "/gizmos")));
            assertTrue(filters.contains(new UriRelativeFilter(QUERY, PATTERN_SUFFIX,
                    ".*query=string.*")));
            assertTrue(filters.contains(new UriRelativeFilter(FRAGMENT, PATTERN_SUFFIX,
                    "fragment")));
        } finally {
            testFile.delete();
        }
    }

    private static final int PROPERTY_TYPE_BOOLEAN = 1;
    private static final int PROPERTY_TYPE_FLOAT = 2;
    private static final int PROPERTY_TYPE_INTEGER = 3;
    private static final int PROPERTY_TYPE_RESOURCE = 4;
    private static final int PROPERTY_TYPE_STRING = 5;
    public void assertProperty(Map<String, Property> properties, String propertyName,
            int propertyType, Object propertyValue) {
        assertTrue(properties.containsKey(propertyName));

        final Property testProperty = properties.get(propertyName);
        assertEquals(propertyType, testProperty.getType());

        if (propertyType == PROPERTY_TYPE_BOOLEAN) {
            assertTrue(testProperty.isBoolean());
            assertFalse(testProperty.isFloat());
            assertFalse(testProperty.isInteger());
            assertFalse(testProperty.isResourceId());
            assertFalse(testProperty.isString());

            // assert the property's type is set correctly
            final Boolean boolValue = (Boolean) propertyValue;
            if (boolValue.booleanValue()) {
                assertTrue(testProperty.getBoolean());
            } else {
                assertFalse(testProperty.getBoolean());
            }
            // assert the other values have an appropriate default
            assertEquals(0.0f, testProperty.getFloat(), 0.0f);
            assertEquals(0, testProperty.getInteger());
            assertEquals(0, testProperty.getResourceId());
            assertEquals(null, testProperty.getString());
        } else if (propertyType == PROPERTY_TYPE_FLOAT) {
            assertFalse(testProperty.isBoolean());
            assertTrue(testProperty.isFloat());
            assertFalse(testProperty.isInteger());
            assertFalse(testProperty.isResourceId());
            assertFalse(testProperty.isString());

            // assert the property's type is set correctly
            final Float floatValue = (Float) propertyValue;
            assertEquals(floatValue.floatValue(), testProperty.getFloat(), 0.0f);
            // assert the other values have an appropriate default
            assertFalse(testProperty.getBoolean());
            assertEquals(0, testProperty.getInteger());
            assertEquals(0, testProperty.getResourceId());
            assertEquals(null, testProperty.getString());
        } else if (propertyType == PROPERTY_TYPE_INTEGER) {
            assertFalse(testProperty.isBoolean());
            assertFalse(testProperty.isFloat());
            assertTrue(testProperty.isInteger());
            assertFalse(testProperty.isResourceId());
            assertFalse(testProperty.isString());

            // assert the property's type is set correctly
            final Integer integerValue = (Integer) propertyValue;
            assertEquals(integerValue.intValue(), testProperty.getInteger());
            // assert the other values have an appropriate default
            assertFalse(testProperty.getBoolean());
            assertEquals(0.0f, testProperty.getFloat(), 0.0f);
            assertEquals(0, testProperty.getResourceId());
            assertEquals(null, testProperty.getString());
        } else if (propertyType == PROPERTY_TYPE_RESOURCE) {
            assertFalse(testProperty.isBoolean());
            assertFalse(testProperty.isFloat());
            assertFalse(testProperty.isInteger());
            assertTrue(testProperty.isResourceId());
            assertFalse(testProperty.isString());

            // assert the property's type is set correctly
            final Integer resourceValue = (Integer) propertyValue;
            assertEquals(resourceValue.intValue(), testProperty.getResourceId());
            // assert the other values have an appropriate default
            assertFalse(testProperty.getBoolean());
            assertEquals(0.0f, testProperty.getFloat(), 0.0f);
            assertEquals(0, testProperty.getInteger());
            assertEquals(null, testProperty.getString());
        } else if (propertyType == PROPERTY_TYPE_STRING) {
            assertFalse(testProperty.isBoolean());
            assertFalse(testProperty.isFloat());
            assertFalse(testProperty.isInteger());
            assertFalse(testProperty.isResourceId());
            assertTrue(testProperty.isString());

            // assert the property's type is set correctly
            final String stringValue = (String) propertyValue;
            assertEquals(stringValue, testProperty.getString());
            // assert the other values have an appropriate default
            assertFalse(testProperty.getBoolean());
            assertEquals(0.0f, testProperty.getFloat(), 0.0f);
            assertEquals(0, testProperty.getInteger());
            assertEquals(0, testProperty.getResourceId());
        } else {
            fail("Unknown property type");
        }
    }

    @Test
    public void testParseApplicationProperties() throws Exception {
        final File testFile = extractFile(TEST_APP4_APK);
        try {
            final ParsedPackage pkg = new TestPackageParser2().parsePackage(testFile, 0, false);
            final Map<String, Property> properties = pkg.getProperties();
            assertEquals(10, properties.size());
            assertProperty(properties,
                    "android.cts.PROPERTY_RESOURCE_XML", PROPERTY_TYPE_RESOURCE, 0x7f060000);
            assertProperty(properties,
                    "android.cts.PROPERTY_RESOURCE_INTEGER", PROPERTY_TYPE_RESOURCE, 0x7f040000);
            assertProperty(properties,
                    "android.cts.PROPERTY_BOOLEAN", PROPERTY_TYPE_BOOLEAN, TRUE);
            assertProperty(properties,
                    "android.cts.PROPERTY_BOOLEAN_VIA_RESOURCE", PROPERTY_TYPE_BOOLEAN, TRUE);
            assertProperty(properties,
                    "android.cts.PROPERTY_FLOAT", PROPERTY_TYPE_FLOAT, 3.14f);
            assertProperty(properties,
                    "android.cts.PROPERTY_FLOAT_VIA_RESOURCE", PROPERTY_TYPE_FLOAT, 2.718f);
            assertProperty(properties,
                    "android.cts.PROPERTY_INTEGER", PROPERTY_TYPE_INTEGER, 42);
            assertProperty(properties,
                    "android.cts.PROPERTY_INTEGER_VIA_RESOURCE", PROPERTY_TYPE_INTEGER, 123);
            assertProperty(properties,
                    "android.cts.PROPERTY_STRING", PROPERTY_TYPE_STRING, "koala");
            assertProperty(properties,
                    "android.cts.PROPERTY_STRING_VIA_RESOURCE", PROPERTY_TYPE_STRING, "giraffe");
        } finally {
            testFile.delete();
        }
    }

    @Test
    public void testParseActivityProperties() throws Exception {
        final File testFile = extractFile(TEST_APP4_APK);
        try {
            final ParsedPackage pkg = new TestPackageParser2().parsePackage(testFile, 0, false);
            final List<ParsedActivity> activities = pkg.getActivities();
            for (ParsedActivity activity : activities) {
                final Map<String, Property> properties = activity.getProperties();
                if ((PACKAGE_NAME + ".MyActivityAlias").equals(activity.getName())) {
                    assertEquals(2, properties.size());
                    assertProperty(properties,
                            "android.cts.PROPERTY_ACTIVITY_ALIAS", PROPERTY_TYPE_INTEGER, 123);
                    assertProperty(properties,
                            "android.cts.PROPERTY_COMPONENT", PROPERTY_TYPE_INTEGER, 123);
                } else if ((PACKAGE_NAME + ".MyActivity").equals(activity.getName())) {
                    assertEquals(3, properties.size());
                    assertProperty(properties,
                            "android.cts.PROPERTY_ACTIVITY", PROPERTY_TYPE_INTEGER, 123);
                    assertProperty(properties,
                            "android.cts.PROPERTY_COMPONENT", PROPERTY_TYPE_INTEGER, 123);
                    assertProperty(properties,
                            "android.cts.PROPERTY_STRING", PROPERTY_TYPE_STRING, "koala activity");
                } else if ("android.app.AppDetailsActivity".equals(activity.getName())) {
                    // ignore default added activity
                } else {
                    fail("Found unknown activity; name = " + activity.getName());
                }
            }
        } finally {
            testFile.delete();
        }
    }

    @Test
    public void testParseProviderProperties() throws Exception {
        final File testFile = extractFile(TEST_APP4_APK);
        try {
            final ParsedPackage pkg = new TestPackageParser2().parsePackage(testFile, 0, false);
            final List<ParsedProvider> providers = pkg.getProviders();
            for (ParsedProvider provider : providers) {
                final Map<String, Property> properties = provider.getProperties();
                if ((PACKAGE_NAME + ".MyProvider").equals(provider.getName())) {
                    assertEquals(1, properties.size());
                    assertProperty(properties,
                            "android.cts.PROPERTY_PROVIDER", PROPERTY_TYPE_INTEGER, 123);
                } else {
                    fail("Found unknown provider; name = " + provider.getName());
                }
            }
        } finally {
            testFile.delete();
        }
    }

    @Test
    public void testParseReceiverProperties() throws Exception {
        final File testFile = extractFile(TEST_APP4_APK);
        try {
            final ParsedPackage pkg = new TestPackageParser2().parsePackage(testFile, 0, false);
            final List<ParsedActivity> receivers = pkg.getReceivers();
            for (ParsedActivity receiver : receivers) {
                final Map<String, Property> properties = receiver.getProperties();
                if ((PACKAGE_NAME + ".MyReceiver").equals(receiver.getName())) {
                    assertEquals(2, properties.size());
                    assertProperty(properties,
                            "android.cts.PROPERTY_RECEIVER", PROPERTY_TYPE_INTEGER, 123);
                    assertProperty(properties,
                            "android.cts.PROPERTY_STRING", PROPERTY_TYPE_STRING, "koala receiver");
                } else {
                    fail("Found unknown receiver; name = " + receiver.getName());
                }
            }
        } finally {
            testFile.delete();
        }
    }

    @Test
    public void testParseServiceProperties() throws Exception {
        final File testFile = extractFile(TEST_APP4_APK);
        try {
            final ParsedPackage pkg = new TestPackageParser2().parsePackage(testFile, 0, false);
            final List<ParsedService> services = pkg.getServices();
            for (ParsedService service : services) {
                final Map<String, Property> properties = service.getProperties();
                if ((PACKAGE_NAME + ".MyService").equals(service.getName())) {
                    assertEquals(2, properties.size());
                    assertProperty(properties,
                            "android.cts.PROPERTY_SERVICE", PROPERTY_TYPE_INTEGER, 123);
                    assertProperty(properties,
                            "android.cts.PROPERTY_COMPONENT", PROPERTY_TYPE_RESOURCE, 0x7f040000);
                } else {
                    fail("Found unknown service; name = " + service.getName());
                }
            }
        } finally {
            testFile.delete();
        }
    }

    @Test
    public void testParseApexSystemService() throws Exception {
        final File testFile = extractFile(TEST_APP4_APK);
        try {
            final ParsedPackage pkg = new TestPackageParser2().parsePackage(testFile, 0, false);
            final List<ParsedApexSystemService> systemServices = pkg.getApexSystemServices();
            for (ParsedApexSystemService systemService: systemServices) {
                assertEquals(PACKAGE_NAME + ".SystemService", systemService.getName());
                assertEquals("service-test.jar", systemService.getJarPath());
                assertEquals("30", systemService.getMinSdkVersion());
                assertEquals("31", systemService.getMaxSdkVersion());
            }
        } finally {
            testFile.delete();
        }
    }

    @Test
    public void testParseModernPackageHasNoCompatPermissions() throws Exception {
        final File testFile = extractFile(TEST_APP1_APK);
        try {
            final ParsedPackage pkg = new TestPackageParser2()
                    .parsePackage(testFile, 0 /*flags*/, false /*useCaches*/);
            final List<String> compatPermissions =
                    Arrays.stream(COMPAT_PERMS).map(CompatibilityPermissionInfo::getName)
                            .collect(toList());
            assertWithMessage(
                    "Compatibility permissions shouldn't be added into uses permissions.")
                    .that(pkg.getUsesPermissions().stream().map(ParsedUsesPermission::getName)
                            .collect(toList()))
                    .containsNoneIn(compatPermissions);
            assertWithMessage(
                    "Compatibility permissions shouldn't be added into requested permissions.")
                    .that(pkg.getRequestedPermissions()).containsNoneIn(compatPermissions);
            assertWithMessage(
                    "Compatibility permissions shouldn't be added into implicit permissions.")
                    .that(pkg.getImplicitPermissions()).containsNoneIn(compatPermissions);
        } finally {
            testFile.delete();
        }
    }

    @Test
    public void testParseLegacyPackageHasCompatPermissions() throws Exception {
        final File testFile = extractFile(TEST_APP5_APK);
        try {
            final ParsedPackage pkg = new TestPackageParser2()
                    .parsePackage(testFile, 0 /*flags*/, false /*useCaches*/);
            assertWithMessage(
                    "Compatibility permissions should be added into uses permissions.")
                    .that(Arrays.stream(COMPAT_PERMS).map(CompatibilityPermissionInfo::getName)
                            .allMatch(pkg.getUsesPermissions().stream()
                                    .map(ParsedUsesPermission::getName)
                            .collect(toList())::contains))
                    .isTrue();
            assertWithMessage(
                    "Compatibility permissions should be added into requested permissions.")
                    .that(Arrays.stream(COMPAT_PERMS).map(CompatibilityPermissionInfo::getName)
                            .allMatch(pkg.getRequestedPermissions()::contains))
                    .isTrue();
            assertWithMessage(
                    "Compatibility permissions should be added into implicit permissions.")
                    .that(Arrays.stream(COMPAT_PERMS).map(CompatibilityPermissionInfo::getName)
                            .allMatch(pkg.getImplicitPermissions()::contains))
                    .isTrue();
        } finally {
            testFile.delete();
        }
    }

    @Test
    public void testNoComponentMetadataIsCoercedToNullForInfoObject() throws Exception {
        final File testFile = extractFile(TEST_APP4_APK);
        try {
            final ParsedPackage pkg = new TestPackageParser2().parsePackage(testFile, 0, false);
            var pkgSetting = mockPkgSetting(pkg);
            ApplicationInfo appInfo = PackageInfoUtils.generateApplicationInfo(pkg, 0,
                    PackageUserStateInternal.DEFAULT, 0, pkgSetting);
            for (ParsedActivity activity : pkg.getActivities()) {
                assertNotNull(activity.getMetaData());
                assertNull(PackageInfoUtils.generateActivityInfo(pkg, activity, 0,
                        PackageUserStateInternal.DEFAULT, appInfo, 0, pkgSetting)
                        .metaData);
            }
            for (ParsedProvider provider : pkg.getProviders()) {
                assertNotNull(provider.getMetaData());
                assertNull(PackageInfoUtils.generateProviderInfo(pkg, provider, 0,
                        PackageUserStateInternal.DEFAULT, appInfo, 0, pkgSetting)
                        .metaData);
            }
            for (ParsedActivity receiver : pkg.getReceivers()) {
                assertNotNull(receiver.getMetaData());
                assertNull(PackageInfoUtils.generateActivityInfo(pkg, receiver, 0,
                        PackageUserStateInternal.DEFAULT, appInfo, 0, pkgSetting)
                        .metaData);
            }
            for (ParsedService service : pkg.getServices()) {
                assertNotNull(service.getMetaData());
                assertNull(PackageInfoUtils.generateServiceInfo(pkg, service, 0,
                        PackageUserStateInternal.DEFAULT, appInfo, 0, pkgSetting)
                        .metaData);
            }
        } finally {
            testFile.delete();
        }
    }

    @Test
    @RequiresFlagsEnabled(android.content.res.Flags.FLAG_MANIFEST_FLAGGING)
    public void testParseWithFeatureFlagAttributes() throws Exception {
        final File testFile = extractFile(TEST_APP8_APK);
        try (PackageParser2 parser = new TestPackageParser2()) {
            Map<String, Boolean> flagValues = new HashMap<>();
            flagValues.put("my.flag1", true);
            flagValues.put("my.flag2", false);
            flagValues.put("my.flag3", false);
            flagValues.put("my.flag4", true);
            ParsingPackageUtils.getAconfigFlags().addFlagValuesForTesting(flagValues);

            // The manifest has:
            //    <permission android:name="PERM1" android:featureFlag="my.flag1 " />
            //    <permission android:name="PERM2" android:featureFlag=" !my.flag2" />
            //    <permission android:name="PERM3" android:featureFlag="my.flag3" />
            //    <permission android:name="PERM4" android:featureFlag="!my.flag4" />
            //    <permission android:name="PERM5" android:featureFlag="unknown.flag" />
            // Therefore with the above flag values, only PERM1 and PERM2 should be present.

            final ParsedPackage pkg = parser.parsePackage(testFile, 0, false);
            List<String> permissionNames =
                    pkg.getPermissions().stream().map(ParsedComponent::getName).toList();
            assertThat(permissionNames).contains(PACKAGE_NAME + ".PERM1");
            assertThat(permissionNames).contains(PACKAGE_NAME + ".PERM2");
            assertThat(permissionNames).doesNotContain(PACKAGE_NAME + ".PERM3");
            assertThat(permissionNames).doesNotContain(PACKAGE_NAME + ".PERM4");
            assertThat(permissionNames).doesNotContain(PACKAGE_NAME + ".PERM5");
        } finally {
            testFile.delete();
        }
    }

    /**
     * A subclass of package parser that adds a "cache_" prefix to the package name for the cached
     * results. This is used by tests to tell if a ParsedPackage is generated from the cache or not.
     */
    public static class CachePackageNameParser extends PackageParser2 {

        private static final Callback CALLBACK = new Callback() {
            @Override
            public boolean isChangeEnabled(long changeId, @NonNull ApplicationInfo appInfo) {
                return true;
            }

            @Override
            public boolean hasFeature(String feature) {
                return false;
            }

            @Override
            public Set<String> getHiddenApiWhitelistedApps() {
                return Sets.newArraySet("cache_android");
            }

            @Override
            public Set<String> getInstallConstraintsAllowlist() {
                return new ArraySet<>();
            }
        };

        CachePackageNameParser(@Nullable File cacheDir) {
            super(null, null, null, CALLBACK);
            if (cacheDir != null) {
                setCacheDir(cacheDir);
            }
        }

        void setCacheDir(@NonNull File cacheDir) {
            this.mCacher = new PackageCacher(cacheDir, CALLBACK) {
                @Override
                public ParsedPackage fromCacheEntry(byte[] cacheEntry) {
                    ParsedPackage parsed = super.fromCacheEntry(cacheEntry);
                    parsed.setPackageName("cache_" + parsed.getPackageName());
                    return parsed;
                }
            };
        }
    }

    private static PackageSetting mockPkgSetting(AndroidPackage pkg) {
        return new PackageSettingBuilder()
                .setName(pkg.getPackageName())
                .setRealName(pkg.getManifestPackageName())
                .setCodePath(pkg.getPath())
                .setPrimaryCpuAbiString(AndroidPackageUtils.getRawPrimaryCpuAbi(pkg))
                .setSecondaryCpuAbiString(AndroidPackageUtils.getRawSecondaryCpuAbi(pkg))
                .setPVersionCode(pkg.getLongVersionCode())
                .setPkgFlags(PackageInfoUtils.appInfoFlags(pkg, null))
                .setPrivateFlags(PackageInfoUtils.appInfoPrivateFlags(pkg, null))
                .setSharedUserId(pkg.getSharedUserLabelResourceId())
                .build();
    }

    // NOTE: The equality assertions below are based on code autogenerated by IntelliJ.

    public static void assertPackagesEqual(AndroidPackage a, AndroidPackage b) {
        assertEquals(a.getBaseRevisionCode(), b.getBaseRevisionCode());
        assertEquals(a.isHardwareAccelerated(), b.isHardwareAccelerated());
        assertEquals(a.getLongVersionCode(), b.getLongVersionCode());
        assertEquals(a.getSharedUserLabelResourceId(), b.getSharedUserLabelResourceId());
        assertEquals(a.getInstallLocation(), b.getInstallLocation());
        assertEquals(a.isCoreApp(), b.isCoreApp());
        assertEquals(a.isRequiredForAllUsers(), b.isRequiredForAllUsers());
        assertEquals(a.getCompileSdkVersion(), b.getCompileSdkVersion());
        assertEquals(a.getCompileSdkVersionCodeName(), b.getCompileSdkVersionCodeName());
        assertEquals(a.is32BitAbiPreferred(), b.is32BitAbiPreferred());
        assertEquals(a.getPackageName(), b.getPackageName());
        assertArrayEquals(a.getSplitNames(), b.getSplitNames());
        assertEquals(a.getVolumeUuid(), b.getVolumeUuid());
        assertEquals(a.getPath(), b.getPath());
        assertEquals(a.getBaseApkPath(), b.getBaseApkPath());
        assertArrayEquals(a.getSplitCodePaths(), b.getSplitCodePaths());
        assertArrayEquals(a.getSplitRevisionCodes(), b.getSplitRevisionCodes());
        assertArrayEquals(a.getSplitFlags(), b.getSplitFlags());

        PackageInfo aInfo = PackageInfoUtils.generate(a, new int[]{}, 0, 0, 0,
                Collections.emptySet(), Collections.emptySet(), PackageUserStateInternal.DEFAULT, 0,
                mockPkgSetting(a));
        PackageInfo bInfo = PackageInfoUtils.generate(b, new int[]{}, 0, 0, 0,
                Collections.emptySet(), Collections.emptySet(), PackageUserStateInternal.DEFAULT, 0,
                mockPkgSetting(b));
        assertApplicationInfoEqual(aInfo.applicationInfo, bInfo.applicationInfo);

        assertEquals(ArrayUtils.size(a.getPermissions()), ArrayUtils.size(b.getPermissions()));
        for (int i = 0; i < ArrayUtils.size(a.getPermissions()); ++i) {
            assertPermissionsEqual(a.getPermissions().get(i), b.getPermissions().get(i));
        }

        assertEquals(ArrayUtils.size(a.getPermissionGroups()),
                ArrayUtils.size(b.getPermissionGroups()));
        for (int i = 0; i < a.getPermissionGroups().size(); ++i) {
            assertPermissionGroupsEqual(a.getPermissionGroups().get(i),
                    b.getPermissionGroups().get(i));
        }

        assertEquals(ArrayUtils.size(a.getActivities()), ArrayUtils.size(b.getActivities()));
        for (int i = 0; i < ArrayUtils.size(a.getActivities()); ++i) {
            assertActivitiesEqual(a, a.getActivities().get(i), b, b.getActivities().get(i));
        }

        assertEquals(ArrayUtils.size(a.getReceivers()), ArrayUtils.size(b.getReceivers()));
        for (int i = 0; i < ArrayUtils.size(a.getReceivers()); ++i) {
            assertActivitiesEqual(a, a.getReceivers().get(i), b, b.getReceivers().get(i));
        }

        assertEquals(ArrayUtils.size(a.getProviders()), ArrayUtils.size(b.getProviders()));
        for (int i = 0; i < ArrayUtils.size(a.getProviders()); ++i) {
            assertProvidersEqual(a, a.getProviders().get(i), b, b.getProviders().get(i));
        }

        assertEquals(ArrayUtils.size(a.getServices()), ArrayUtils.size(b.getServices()));
        for (int i = 0; i < ArrayUtils.size(a.getServices()); ++i) {
            assertServicesEqual(a, a.getServices().get(i), b, b.getServices().get(i));
        }

        assertEquals(ArrayUtils.size(a.getInstrumentations()),
                ArrayUtils.size(b.getInstrumentations()));
        for (int i = 0; i < ArrayUtils.size(a.getInstrumentations()); ++i) {
            assertInstrumentationEqual(a.getInstrumentations().get(i),
                    b.getInstrumentations().get(i));
        }

        assertEquals(a.getProperties().size(), b.getProperties().size());
        final Iterator<String> iter = a.getProperties().keySet().iterator();
        while (iter.hasNext()) {
            final String key = iter.next();
            assertEquals(a.getProperties().get(key), b.getProperties().get(key));
        }

        assertEquals(a.getRequestedPermissions(), b.getRequestedPermissions());
        assertEquals(a.getProtectedBroadcasts(), b.getProtectedBroadcasts());
        assertEquals(a.getLibraryNames(), b.getLibraryNames());
        assertEquals(a.getUsesLibraries(), b.getUsesLibraries());
        assertEquals(a.getUsesOptionalLibraries(), b.getUsesOptionalLibraries());
        assertEquals(a.getOriginalPackages(), b.getOriginalPackages());
        assertEquals(a.getManifestPackageName(), b.getManifestPackageName());
        assertEquals(a.getAdoptPermissions(), b.getAdoptPermissions());
        assertBundleApproximateEquals(a.getMetaData(), b.getMetaData());
        assertEquals(a.getVersionName(), b.getVersionName());
        assertEquals(a.getSharedUserId(), b.getSharedUserId());
        assertArrayEquals(a.getSigningDetails().getSignatures(),
                b.getSigningDetails().getSignatures());
        assertEquals(a.getRestrictedAccountType(), b.getRestrictedAccountType());
        assertEquals(a.getRequiredAccountType(), b.getRequiredAccountType());
        assertEquals(a.getOverlayTarget(), b.getOverlayTarget());
        assertEquals(a.getOverlayTargetOverlayableName(), b.getOverlayTargetOverlayableName());
        assertEquals(a.getOverlayCategory(), b.getOverlayCategory());
        assertEquals(a.getOverlayPriority(), b.getOverlayPriority());
        assertEquals(a.isOverlayIsStatic(), b.isOverlayIsStatic());
        assertEquals(a.getSigningDetails().getPublicKeys(), b.getSigningDetails().getPublicKeys());
        assertEquals(a.getUpgradeKeySets(), b.getUpgradeKeySets());
        assertEquals(a.getKeySetMapping(), b.getKeySetMapping());
        assertArrayEquals(a.getRestrictUpdateHash(), b.getRestrictUpdateHash());
    }

    private static void assertBundleApproximateEquals(Bundle a, Bundle b) {
        if (a == b) {
            return;
        }

        // Force the bundles to be unparceled.
        a.getBoolean("foo");
        b.getBoolean("foo");

        assertEquals(a.toString(), b.toString());
    }

    private static void assertComponentsEqual(ParsedComponent a, ParsedComponent b) {
        assertEquals(a.getName(), b.getName());
        assertBundleApproximateEquals(a.getMetaData(), b.getMetaData());
        assertEquals(a.getComponentName(), b.getComponentName());

        if (a.getIntents() != null && b.getIntents() != null) {
            assertEquals(a.getIntents().size(), b.getIntents().size());
        } else if (a.getIntents() == null || b.getIntents() == null) {
            return;
        }

        for (int i = 0; i < a.getIntents().size(); ++i) {
            ParsedIntentInfo aIntent = a.getIntents().get(i);
            ParsedIntentInfo bIntent = b.getIntents().get(i);

            assertEquals(aIntent.isHasDefault(), bIntent.isHasDefault());
            assertEquals(aIntent.getLabelRes(), bIntent.getLabelRes());
            assertEquals(aIntent.getNonLocalizedLabel(), bIntent.getNonLocalizedLabel());
            assertEquals(aIntent.getIcon(), bIntent.getIcon());
        }

        assertEquals(a.getProperties().size(), b.getProperties().size());
        final Iterator<String> iter = a.getProperties().keySet().iterator();
        while (iter.hasNext()) {
            final String key = iter.next();
            assertEquals(a.getProperties().get(key), b.getProperties().get(key));
        }
    }

    private static void assertPermissionsEqual(ParsedPermission a, ParsedPermission b) {
        assertComponentsEqual(a, b);
        assertEquals(a.isTree(), b.isTree());

        // Verify basic flags in PermissionInfo to make sure they're consistent. We don't perform
        // a full structural equality here because the code that serializes them isn't parser
        // specific and is tested elsewhere.
        assertEquals(ParsedPermissionUtils.getProtection(a),
                ParsedPermissionUtils.getProtection(b));
        assertEquals(a.getGroup(), b.getGroup());
        assertEquals(a.getFlags(), b.getFlags());

        if (a.getParsedPermissionGroup() != null && b.getParsedPermissionGroup() != null) {
            assertPermissionGroupsEqual(a.getParsedPermissionGroup(), b.getParsedPermissionGroup());
        } else if (a.getParsedPermissionGroup() != null || b.getParsedPermissionGroup() != null) {
            throw new AssertionError();
        }
    }

    private static void assertInstrumentationEqual(ParsedInstrumentation a,
            ParsedInstrumentation b) {
        assertComponentsEqual(a, b);

        // Validity check for InstrumentationInfo.
        assertEquals(a.getTargetPackage(), b.getTargetPackage());
        assertEquals(a.getTargetProcesses(), b.getTargetProcesses());
        assertEquals(a.isHandleProfiling(), b.isHandleProfiling());
        assertEquals(a.isFunctionalTest(), b.isFunctionalTest());
    }

    private static void assertServicesEqual(
            AndroidPackage aPkg,
            ParsedService a,
            AndroidPackage bPkg,
            ParsedService b
    ) {
        assertComponentsEqual(a, b);

        // Validity check for ServiceInfo.
        ServiceInfo aInfo = PackageInfoUtils.generateServiceInfo(aPkg, a, 0,
                PackageUserStateInternal.DEFAULT, 0, mockPkgSetting(aPkg));
        ServiceInfo bInfo = PackageInfoUtils.generateServiceInfo(bPkg, b, 0,
                PackageUserStateInternal.DEFAULT, 0, mockPkgSetting(bPkg));
        assertApplicationInfoEqual(aInfo.applicationInfo, bInfo.applicationInfo);
        assertEquals(a.getName(), b.getName());
    }

    private static void assertProvidersEqual(
            AndroidPackage aPkg,
            ParsedProvider a,
            AndroidPackage bPkg,
            ParsedProvider b
    ) {
        assertComponentsEqual(a, b);
        assertEquals(a.getName(), b.getName());
    }

    private static void assertActivitiesEqual(
            AndroidPackage aPkg,
            ParsedActivity a,
            AndroidPackage bPkg,
            ParsedActivity b
    ) {
        assertComponentsEqual(a, b);

        // Validity check for ActivityInfo.
        ActivityInfo aInfo = PackageInfoUtils.generateActivityInfo(aPkg, a, 0,
                PackageUserStateInternal.DEFAULT, 0, mockPkgSetting(aPkg));
        ActivityInfo bInfo = PackageInfoUtils.generateActivityInfo(bPkg, b, 0,
                PackageUserStateInternal.DEFAULT, 0, mockPkgSetting(bPkg));
        assertApplicationInfoEqual(aInfo.applicationInfo, bInfo.applicationInfo);
        assertEquals(a.getName(), b.getName());
    }

    private static void assertPermissionGroupsEqual(ParsedPermissionGroup a,
            ParsedPermissionGroup b) {
        assertComponentsEqual(a, b);

        // Validity check for PermissionGroupInfo.
        assertEquals(a.getName(), b.getName());
        assertEquals(a.getDescriptionRes(), b.getDescriptionRes());
    }

    private static void assertApplicationInfoEqual(ApplicationInfo a, ApplicationInfo that) {
        assertEquals(a.descriptionRes, that.descriptionRes);
        assertEquals(a.theme, that.theme);
        assertEquals(a.fullBackupContent, that.fullBackupContent);
        assertEquals(a.uiOptions, that.uiOptions);
        assertEquals(Integer.toBinaryString(a.flags), Integer.toBinaryString(that.flags));
        assertEquals(a.privateFlags, that.privateFlags);
        assertEquals(a.requiresSmallestWidthDp, that.requiresSmallestWidthDp);
        assertEquals(a.compatibleWidthLimitDp, that.compatibleWidthLimitDp);
        assertEquals(a.largestWidthLimitDp, that.largestWidthLimitDp);
        assertEquals(a.nativeLibraryRootRequiresIsa, that.nativeLibraryRootRequiresIsa);
        assertEquals(a.uid, that.uid);
        assertEquals(a.minSdkVersion, that.minSdkVersion);
        assertEquals(a.targetSdkVersion, that.targetSdkVersion);
        assertEquals(a.versionCode, that.versionCode);
        assertEquals(a.enabled, that.enabled);
        assertEquals(a.enabledSetting, that.enabledSetting);
        assertEquals(a.installLocation, that.installLocation);
        assertEquals(a.networkSecurityConfigRes, that.networkSecurityConfigRes);
        assertEquals(a.taskAffinity, that.taskAffinity);
        assertEquals(a.permission, that.permission);
        assertEquals(a.getKnownActivityEmbeddingCerts(), that.getKnownActivityEmbeddingCerts());
        assertEquals(a.processName, that.processName);
        assertEquals(a.className, that.className);
        assertEquals(a.manageSpaceActivityName, that.manageSpaceActivityName);
        assertEquals(a.backupAgentName, that.backupAgentName);
        assertEquals(a.volumeUuid, that.volumeUuid);
        assertEquals(a.scanSourceDir, that.scanSourceDir);
        assertEquals(a.scanPublicSourceDir, that.scanPublicSourceDir);
        assertEquals(a.sourceDir, that.sourceDir);
        assertEquals(a.publicSourceDir, that.publicSourceDir);
        assertArrayEquals(a.splitSourceDirs, that.splitSourceDirs);
        assertArrayEquals(a.splitPublicSourceDirs, that.splitPublicSourceDirs);
        assertArrayEquals(a.resourceDirs, that.resourceDirs);
        assertArrayEquals(a.overlayPaths, that.overlayPaths);
        assertEquals(a.seInfo, that.seInfo);
        assertArrayEquals(a.sharedLibraryFiles, that.sharedLibraryFiles);
        assertEquals(a.dataDir, that.dataDir);
        assertEquals(a.deviceProtectedDataDir, that.deviceProtectedDataDir);
        assertEquals(a.credentialProtectedDataDir, that.credentialProtectedDataDir);
        assertEquals(a.nativeLibraryDir, that.nativeLibraryDir);
        assertEquals(a.secondaryNativeLibraryDir, that.secondaryNativeLibraryDir);
        assertEquals(a.nativeLibraryRootDir, that.nativeLibraryRootDir);
        assertEquals(a.primaryCpuAbi, that.primaryCpuAbi);
        assertEquals(a.secondaryCpuAbi, that.secondaryCpuAbi);
    }

    public static void setKnownFields(ParsingPackage pkg) {
        Bundle bundle = new Bundle();
        bundle.putString("key", "value");

        ParsedPermissionImpl permission = new ParsedPermissionImpl();
        permission.setParsedPermissionGroup(new ParsedPermissionGroupImpl());

        ((ParsedPackage) pkg.setBaseRevisionCode(100)
                .setHardwareAccelerated(true)
                .setSharedUserLabelResourceId(100)
                .setInstallLocation(100)
                .setRequiredForAllUsers(true)
                .asSplit(
                        new String[]{"foo2"},
                        new String[]{"foo6"},
                        new int[]{100},
                        null
                )
                .set32BitAbiPreferred(true)
                .setVolumeUuid("d52ef59a-7def-4541-bf21-4c28ed4b65a0")
                .addPermission(permission)
                .addPermissionGroup(new ParsedPermissionGroupImpl())
                .addActivity(new ParsedActivityImpl())
                .addReceiver(new ParsedActivityImpl())
                .addProvider(new ParsedProviderImpl())
                .addService(new ParsedServiceImpl())
                .addInstrumentation(new ParsedInstrumentationImpl())
                .addUsesPermission(new ParsedUsesPermissionImpl("foo7", 0))
                .addImplicitPermission("foo25")
                .addProtectedBroadcast("foo8")
                .setSdkLibraryName("sdk12")
                .setSdkLibVersionMajor(42)
                .addUsesSdkLibrary("sdk23", 200, new String[]{"digest2"}, true)
                .setStaticSharedLibraryName("foo23")
                .setStaticSharedLibraryVersion(100)
                .addUsesStaticLibrary("foo23", 100, new String[]{"digest"})
                .addLibraryName("foo10")
                .addUsesLibrary("foo11")
                .addUsesOptionalLibrary("foo12")
                .addOriginalPackage("foo14")
                .addAdoptPermission("foo16")
                .setMetaData(bundle)
                .setVersionName("foo17")
                .setSharedUserId("foo18")
                .setSigningDetails(
                        new SigningDetails(
                                new Signature[]{new Signature(new byte[16])},
                                SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V2,
                                new ArraySet<>(),
                                null)
                )
                .setRestrictedAccountType("foo19")
                .setRequiredAccountType("foo20")
                .setOverlayTarget("foo21")
                .setOverlayPriority(100)
                .setUpgradeKeySets(new ArraySet<>())
                .addPreferredActivityFilter("className", new ParsedIntentInfoImpl())
                .addConfigPreference(new ConfigurationInfo())
                .addReqFeature(new FeatureInfo())
                .addFeatureGroup(new FeatureGroupInfo())
                .setCompileSdkVersionCodeName("foo23")
                .setCompileSdkVersion(100)
                .setOverlayCategory("foo24")
                .setOverlayIsStatic(true)
                .setOverlayTargetOverlayableName("foo26")
                .setVisibleToInstantApps(true)
                .setSplitHasCode(0, true)
                .hideAsParsed())
                .setBaseApkPath("foo5")
                .setPath("foo4")
                .setVersionCode(100)
                .setRestrictUpdateHash(new byte[16])
                .setVersionCodeMajor(100)
                .setCoreApp(true)
                .hideAsFinal();
    }

    private static void assertAllFieldsExist(ParsedPackage pkg) throws Exception {
        Field[] fields = ParsedPackage.class.getDeclaredFields();

        Set<String> nonSerializedFields = new HashSet<>();
        nonSerializedFields.add("mExtras");
        nonSerializedFields.add("packageUsageTimeMillis");
        nonSerializedFields.add("isStub");

        for (Field f : fields) {
            final Class<?> fieldType = f.getType();

            if (nonSerializedFields.contains(f.getName())) {
                continue;
            }

            if (List.class.isAssignableFrom(fieldType)) {
                // Validity check for list fields: Assume they're non-null and contain precisely
                // one element.
                List<?> list = (List<?>) f.get(pkg);
                assertNotNull("List was null: " + f, list);
                assertEquals(1, list.size());
            } else if (fieldType.getComponentType() != null) {
                // Validity check for array fields: Assume they're non-null and contain precisely
                // one element.
                Object array = f.get(pkg);
                assertNotNull(Array.get(array, 0));
            } else if (fieldType == String.class) {
                // String fields: Check that they're set to "foo".
                String value = (String) f.get(pkg);

                assertTrue("Bad value for field: " + f, value != null && value.startsWith("foo"));
            } else if (fieldType == int.class) {
                // int fields: Check that they're set to 100.
                int value = (int) f.get(pkg);
                assertEquals("Bad value for field: " + f, 100, value);
            } else if (fieldType == boolean.class) {
                // boolean fields: Check that they're set to true.
                boolean value = (boolean) f.get(pkg);
                assertTrue("Bad value for field: " + f, value);
            } else {
                // All other fields: Check that they're set.
                Object o = f.get(pkg);
                assertNotNull("Field was null: " + f, o);
            }
        }
    }
}

