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

import android.content.pm.PackageParser;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.MediumTest;

import java.io.File;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import libcore.io.IoUtils;

@RunWith(AndroidJUnit4.class)
@MediumTest
public class PackageParserTest {
    private File mTmpDir;
    private static final File FRAMEWORK = new File("/system/framework/framework-res.apk");

    @Before
    public void setUp() {
        // Create a new temporary directory for each of our tests.
        mTmpDir = IoUtils.createTemporaryDirectory("PackageParserTest");
    }

    @Test
    public void testParse_noCache() throws Exception {
        PackageParser pp = new CachePackageNameParser();
        PackageParser.Package pkg = pp.parsePackage(FRAMEWORK, 0 /* parseFlags */,
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
        PackageParser pp = new CachePackageNameParser();

        pp.setCacheDir(mTmpDir);
        // The first parse will write this package to the cache.
        pp.parsePackage(FRAMEWORK, 0 /* parseFlags */, true /* useCaches */);

        // Now attempt to parse the package again, should return the
        // cached result.
        PackageParser.Package pkg = pp.parsePackage(FRAMEWORK, 0 /* parseFlags */,
                true /* useCaches */);
        assertEquals("cache_android", pkg.packageName);

        // Try again, with useCaches == false, shouldn't return the parsed
        // result.
        pkg = pp.parsePackage(FRAMEWORK, 0 /* parseFlags */, false /* useCaches */);
        assertEquals("android", pkg.packageName);

        // We haven't set a cache directory here : the parse should still succeed,
        // just not using the cached results.
        pp = new CachePackageNameParser();
        pkg = pp.parsePackage(FRAMEWORK, 0 /* parseFlags */, true /* useCaches */);
        assertEquals("android", pkg.packageName);

        pkg = pp.parsePackage(FRAMEWORK, 0 /* parseFlags */, false /* useCaches */);
        assertEquals("android", pkg.packageName);
    }

    /**
     * A trivial subclass of package parser that only caches the package name, and throws away
     * all other information.
     */
    public static class CachePackageNameParser extends PackageParser {
        @Override
        public byte[] toCacheEntry(Package pkg) {
            return ("cache_" + pkg.packageName).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public Package fromCacheEntry(byte[] cacheEntry) {
            return new Package(new String(cacheEntry, StandardCharsets.UTF_8));
        }
    }
}
