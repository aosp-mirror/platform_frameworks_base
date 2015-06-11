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

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.documentsui.BaseActivity.State;
import com.android.documentsui.model.RootInfo;
import com.google.android.collect.Lists;

import java.util.List;

@SmallTest
public class RootsCacheTest extends AndroidTestCase {

    private static RootInfo buildForMimeTypes(String... mimeTypes) {
        final RootInfo root = new RootInfo();
        root.derivedMimeTypes = mimeTypes;
        return root;
    }

    private RootInfo mNull = new RootInfo();
    private RootInfo mEmpty = buildForMimeTypes();
    private RootInfo mWild = buildForMimeTypes("*/*");
    private RootInfo mImages = buildForMimeTypes("image/*");
    private RootInfo mAudio = buildForMimeTypes("audio/*", "application/ogg", "application/x-flac");
    private RootInfo mDocs = buildForMimeTypes("application/msword", "application/vnd.ms-excel");
    private RootInfo mMalformed1 = buildForMimeTypes("meow");
    private RootInfo mMalformed2 = buildForMimeTypes("*/meow");

    private List<RootInfo> mRoots = Lists.newArrayList(
            mNull, mWild, mEmpty, mImages, mAudio, mDocs, mMalformed1, mMalformed2);

    private State mState;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mState = new State();
        mState.action = State.ACTION_OPEN;
        mState.showAdvanced = true;
        mState.localOnly = false;
    }

    public void testMatchingRootsEverything() throws Exception {
        mState.acceptMimes = new String[] { "*/*" };
        assertContainsExactly(
                Lists.newArrayList(mNull, mWild, mImages, mAudio, mDocs, mMalformed1, mMalformed2),
                RootsCache.getMatchingRoots(mRoots, mState));
    }

    public void testMatchingRootsPngOrWild() throws Exception {
        mState.acceptMimes = new String[] { "image/png", "*/*" };
        assertContainsExactly(
                Lists.newArrayList(mNull, mWild, mImages, mAudio, mDocs, mMalformed1, mMalformed2),
                RootsCache.getMatchingRoots(mRoots, mState));
    }

    public void testMatchingRootsAudioWild() throws Exception {
        mState.acceptMimes = new String[] { "audio/*" };
        assertContainsExactly(
                Lists.newArrayList(mNull, mWild, mAudio),
                RootsCache.getMatchingRoots(mRoots, mState));
    }

    public void testMatchingRootsAudioWildOrImageWild() throws Exception {
        mState.acceptMimes = new String[] { "audio/*", "image/*" };
        assertContainsExactly(
                Lists.newArrayList(mNull, mWild, mAudio, mImages),
                RootsCache.getMatchingRoots(mRoots, mState));
    }

    public void testMatchingRootsAudioSpecific() throws Exception {
        mState.acceptMimes = new String[] { "audio/mpeg" };
        assertContainsExactly(
                Lists.newArrayList(mNull, mWild, mAudio),
                RootsCache.getMatchingRoots(mRoots, mState));
    }

    public void testMatchingRootsDocument() throws Exception {
        mState.acceptMimes = new String[] { "application/msword" };
        assertContainsExactly(
                Lists.newArrayList(mNull, mWild, mDocs),
                RootsCache.getMatchingRoots(mRoots, mState));
    }

    public void testMatchingRootsApplication() throws Exception {
        mState.acceptMimes = new String[] { "application/*" };
        assertContainsExactly(
                Lists.newArrayList(mNull, mWild, mAudio, mDocs),
                RootsCache.getMatchingRoots(mRoots, mState));
    }

    public void testMatchingRootsFlacOrPng() throws Exception {
        mState.acceptMimes = new String[] { "application/x-flac", "image/png" };
        assertContainsExactly(
                Lists.newArrayList(mNull, mWild, mAudio, mImages),
                RootsCache.getMatchingRoots(mRoots, mState));
    }

    public void testExcludedAuthorities() throws Exception {
        final List<RootInfo> roots = Lists.newArrayList();

        // Set up some roots
        for (int i = 0; i < 5; ++i) {
            RootInfo root = new RootInfo();
            root.authority = "authority" + i;
            roots.add(root);
        }
        // Make some allowed authorities
        List<RootInfo> allowedRoots = Lists.newArrayList(
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
            RootsCache.getMatchingRoots(roots, mState));
    }

    private static void assertContainsExactly(List<?> expected, List<?> actual) {
        assertEquals(expected.size(), actual.size());
        for (Object o : expected) {
            assertTrue(actual.contains(o));
        }
    }
}
