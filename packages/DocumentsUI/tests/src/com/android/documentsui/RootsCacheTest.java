/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.documentsui;

import static com.android.documentsui.RootsCache.getMatchingRoots;
import static com.google.common.collect.Lists.newArrayList;

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.documentsui.model.RootInfo;

import com.google.common.collect.Lists;

import java.util.List;

@SmallTest
public class RootsCacheTest extends AndroidTestCase {

    private static RootInfo mNull = new RootInfo();
    private static RootInfo mEmpty = buildForMimeTypes();
    private static RootInfo mWild = buildForMimeTypes("*/*");
    private static RootInfo mImages = buildForMimeTypes("image/*");
    private static RootInfo mAudio = buildForMimeTypes(
            "audio/*", "application/ogg", "application/x-flac");
    private static RootInfo mDocs = buildForMimeTypes(
            "application/msword", "application/vnd.ms-excel");
    private static RootInfo mMalformed1 = buildForMimeTypes("meow");
    private static RootInfo mMalformed2 = buildForMimeTypes("*/meow");

    private List<RootInfo> mRoots;

    private State mState;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mRoots = Lists.newArrayList(
                mNull, mWild, mEmpty, mImages, mAudio, mDocs, mMalformed1, mMalformed2);

        mState = new State();
        mState.action = State.ACTION_OPEN;
        mState.showAdvanced = true;
        mState.localOnly = false;
    }

    public void testMatchingRoots_Everything() throws Exception {
        mState.acceptMimes = new String[] { "*/*" };
        assertContainsExactly(
                newArrayList(mNull, mWild, mImages, mAudio, mDocs, mMalformed1, mMalformed2),
                getMatchingRoots(mRoots, mState));
    }

    public void testMatchingRoots_DirectoryCopy() throws Exception {
        RootInfo downloads = buildForMimeTypes("*/*");
        downloads.authority = "com.android.providers.downloads.documents";
        mRoots.add(downloads);

        mState.acceptMimes = new String[] { "*/*" };
        mState.directoryCopy = true;

        // basically we're asserting that the results don't contain downloads
        assertContainsExactly(
                newArrayList(mNull, mWild, mImages, mAudio, mDocs, mMalformed1, mMalformed2),
                getMatchingRoots(mRoots, mState));
    }

    public void testMatchingRoots_PngOrWild() throws Exception {
        mState.acceptMimes = new String[] { "image/png", "*/*" };
        assertContainsExactly(
                newArrayList(mNull, mWild, mImages, mAudio, mDocs, mMalformed1, mMalformed2),
                getMatchingRoots(mRoots, mState));
    }

    public void testMatchingRoots_AudioWild() throws Exception {
        mState.acceptMimes = new String[] { "audio/*" };
        assertContainsExactly(
                newArrayList(mNull, mWild, mAudio),
                getMatchingRoots(mRoots, mState));
    }

    public void testMatchingRoots_AudioWildOrImageWild() throws Exception {
        mState.acceptMimes = new String[] { "audio/*", "image/*" };
        assertContainsExactly(
                newArrayList(mNull, mWild, mAudio, mImages),
                getMatchingRoots(mRoots, mState));
    }

    public void testMatchingRoots_AudioSpecific() throws Exception {
        mState.acceptMimes = new String[] { "audio/mpeg" };
        assertContainsExactly(
                newArrayList(mNull, mWild, mAudio),
                getMatchingRoots(mRoots, mState));
    }

    public void testMatchingRoots_Document() throws Exception {
        mState.acceptMimes = new String[] { "application/msword" };
        assertContainsExactly(
                newArrayList(mNull, mWild, mDocs),
                getMatchingRoots(mRoots, mState));
    }

    public void testMatchingRoots_Application() throws Exception {
        mState.acceptMimes = new String[] { "application/*" };
        assertContainsExactly(
                newArrayList(mNull, mWild, mAudio, mDocs),
                getMatchingRoots(mRoots, mState));
    }

    public void testMatchingRoots_FlacOrPng() throws Exception {
        mState.acceptMimes = new String[] { "application/x-flac", "image/png" };
        assertContainsExactly(
                newArrayList(mNull, mWild, mAudio, mImages),
                getMatchingRoots(mRoots, mState));
    }

    public void testExcludedAuthorities() throws Exception {
        final List<RootInfo> roots = newArrayList();

        // Set up some roots
        for (int i = 0; i < 5; ++i) {
            RootInfo root = new RootInfo();
            root.authority = "authority" + i;
            roots.add(root);
        }
        // Make some allowed authorities
        List<RootInfo> allowedRoots = newArrayList(
            roots.get(0), roots.get(2), roots.get(4));
        // Set up the excluded authority list
        for (RootInfo root: roots) {
            if (!allowedRoots.contains(root)) {
                mState.excludedAuthorities.add(root.authority);
            }
        }
        mState.acceptMimes = new String[] { "*/*" };

        assertContainsExactly(
            allowedRoots,
            getMatchingRoots(roots, mState));
    }

    private static void assertContainsExactly(List<?> expected, List<?> actual) {
        assertEquals(expected.size(), actual.size());
        for (Object o : expected) {
            assertTrue(actual.contains(o));
        }
    }

    private static RootInfo buildForMimeTypes(String... mimeTypes) {
        final RootInfo root = new RootInfo();
        root.derivedMimeTypes = mimeTypes;
        return root;
    }
}
