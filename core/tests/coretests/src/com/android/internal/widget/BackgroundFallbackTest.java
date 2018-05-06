/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.internal.widget;

import static android.view.View.VISIBLE;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.support.test.InstrumentationRegistry;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

public class BackgroundFallbackTest {

    private static final int NAVBAR_BOTTOM = 0;
    private static final int NAVBAR_LEFT = 1;
    private static final int NAVBAR_RIGHT = 2;

    private static final int SCREEN_HEIGHT = 2000;
    private static final int SCREEN_WIDTH = 1000;
    private static final int STATUS_HEIGHT = 100;
    private static final int NAV_SIZE = 200;

    private static final boolean INSET_CONTENT_VIEWS = true;
    private static final boolean DONT_INSET_CONTENT_VIEWS = false;

    BackgroundFallback mFallback;
    Drawable mDrawableMock;

    ViewGroup mStatusBarView;
    ViewGroup mNavigationBarView;

    ViewGroup mDecorViewMock;
    ViewGroup mContentRootMock;
    ViewGroup mContentContainerMock;
    ViewGroup mContentMock;

    int mLastTop = 0;

    @Before
    public void setUp() throws Exception {
        mFallback = new BackgroundFallback();
        mDrawableMock = mock(Drawable.class);

        mFallback.setDrawable(mDrawableMock);

    }

    @Test
    public void hasFallback_withDrawable_true() {
        mFallback.setDrawable(mDrawableMock);
        assertThat(mFallback.hasFallback(), is(true));
    }

    @Test
    public void hasFallback_withoutDrawable_false() {
        mFallback.setDrawable(null);
        assertThat(mFallback.hasFallback(), is(false));
    }

    @Test
    public void draw_portrait_noFallback() {
        setUpViewHierarchy(INSET_CONTENT_VIEWS, NAVBAR_BOTTOM);

        mFallback.draw(mDecorViewMock, mContentRootMock, null /* canvas */, mContentContainerMock,
                mStatusBarView, mNavigationBarView);

        verifyNoMoreInteractions(mDrawableMock);
    }

    @Test
    public void draw_portrait_translucentBars_fallback() {
        setUpViewHierarchy(INSET_CONTENT_VIEWS, NAVBAR_BOTTOM);
        setTranslucent(mStatusBarView);
        setTranslucent(mNavigationBarView);

        mFallback.draw(mDecorViewMock, mContentRootMock, null /* canvas */, mContentContainerMock,
                mStatusBarView, mNavigationBarView);

        verifyFallbackTop(STATUS_HEIGHT);
        verifyFallbackBottom(NAV_SIZE);
        verifyNoMoreInteractions(mDrawableMock);
    }

    @Test
    public void draw_landscape_translucentBars_fallback() {
        setUpViewHierarchy(INSET_CONTENT_VIEWS, NAVBAR_RIGHT);
        setTranslucent(mStatusBarView);
        setTranslucent(mNavigationBarView);

        mFallback.draw(mDecorViewMock, mContentRootMock, null /* canvas */, mContentContainerMock,
                mStatusBarView, mNavigationBarView);

        verifyFallbackTop(STATUS_HEIGHT);
        verifyFallbackRight(NAV_SIZE);
        verifyNoMoreInteractions(mDrawableMock);
    }

    @Test
    public void draw_seascape_translucentBars_fallback() {
        setUpViewHierarchy(INSET_CONTENT_VIEWS, NAVBAR_LEFT);
        setTranslucent(mStatusBarView);
        setTranslucent(mNavigationBarView);

        mFallback.draw(mDecorViewMock, mContentRootMock, null /* canvas */, mContentContainerMock,
                mStatusBarView, mNavigationBarView);

        verifyFallbackTop(STATUS_HEIGHT);
        verifyFallbackLeft(NAV_SIZE);
        verifyNoMoreInteractions(mDrawableMock);
    }

    @Test
    public void draw_landscape_noFallback() {
        setUpViewHierarchy(INSET_CONTENT_VIEWS, NAVBAR_RIGHT);

        mFallback.draw(mDecorViewMock, mContentRootMock, null /* canvas */, mContentContainerMock,
                mStatusBarView, mNavigationBarView);

        verifyNoMoreInteractions(mDrawableMock);
    }

    @Test
    public void draw_seascape_noFallback() {
        setUpViewHierarchy(INSET_CONTENT_VIEWS, NAVBAR_LEFT);

        mFallback.draw(mDecorViewMock, mContentRootMock, null /* canvas */, mContentContainerMock,
                mStatusBarView, mNavigationBarView);

        verifyNoMoreInteractions(mDrawableMock);
    }

    @Test
    public void draw_seascape_translucentBars_noInsets_noFallback() {
        setUpViewHierarchy(DONT_INSET_CONTENT_VIEWS, NAVBAR_LEFT);
        setTranslucent(mStatusBarView);
        setTranslucent(mNavigationBarView);

        mFallback.draw(mDecorViewMock, mContentRootMock, null /* canvas */, mContentContainerMock,
                mStatusBarView, mNavigationBarView);

        verifyNoMoreInteractions(mDrawableMock);
    }

    private void verifyFallbackTop(int size) {
        verify(mDrawableMock).setBounds(0, 0, SCREEN_WIDTH, size);
        verify(mDrawableMock, atLeastOnce()).draw(any());
        mLastTop = size;
    }

    private void verifyFallbackLeft(int size) {
        verify(mDrawableMock).setBounds(0, mLastTop, size, SCREEN_HEIGHT);
        verify(mDrawableMock, atLeastOnce()).draw(any());
    }

    private void verifyFallbackRight(int size) {
        verify(mDrawableMock).setBounds(SCREEN_WIDTH - size, mLastTop, SCREEN_WIDTH, SCREEN_HEIGHT);
        verify(mDrawableMock, atLeastOnce()).draw(any());
    }

    private void verifyFallbackBottom(int size) {
        verify(mDrawableMock).setBounds(0, SCREEN_HEIGHT - size, SCREEN_WIDTH, SCREEN_HEIGHT);
        verify(mDrawableMock, atLeastOnce()).draw(any());
    }

    private void setUpViewHierarchy(boolean insetContentViews, int navBarPosition) {
        int insetLeft = 0;
        int insetTop = 0;
        int insetRight = 0;
        int insetBottom = 0;

        mStatusBarView = mockView(0, 0, SCREEN_WIDTH, STATUS_HEIGHT,
                new ColorDrawable(Color.BLACK), VISIBLE, emptyList());
        if (insetContentViews) {
            insetTop = STATUS_HEIGHT;
        }

        switch (navBarPosition) {
            case NAVBAR_BOTTOM:
                mNavigationBarView = mockView(0, SCREEN_HEIGHT - NAV_SIZE, SCREEN_WIDTH,
                        SCREEN_HEIGHT, new ColorDrawable(Color.BLACK), VISIBLE, emptyList());
                if (insetContentViews) {
                    insetBottom = NAV_SIZE;
                }
                break;
            case NAVBAR_LEFT:
                mNavigationBarView = mockView(0, 0, NAV_SIZE, SCREEN_HEIGHT,
                        new ColorDrawable(Color.BLACK), VISIBLE, emptyList());
                if (insetContentViews) {
                    insetLeft = NAV_SIZE;
                }
                break;
            case NAVBAR_RIGHT:
                mNavigationBarView = mockView(SCREEN_WIDTH - NAV_SIZE, 0, SCREEN_WIDTH,
                        SCREEN_HEIGHT, new ColorDrawable(Color.BLACK), VISIBLE, emptyList());
                if (insetContentViews) {
                    insetRight = NAV_SIZE;
                }
                break;
        }

        mContentMock = mockView(0, 0, SCREEN_WIDTH - insetLeft - insetRight,
                SCREEN_HEIGHT - insetTop - insetBottom, null, VISIBLE, emptyList());
        mContentContainerMock = mockView(0, 0, SCREEN_WIDTH - insetLeft - insetRight,
                SCREEN_HEIGHT - insetTop - insetBottom, null, VISIBLE, asList(mContentMock));
        mContentRootMock = mockView(insetLeft, insetTop, SCREEN_WIDTH - insetRight,
                SCREEN_HEIGHT - insetBottom, null, VISIBLE, asList(mContentContainerMock));

        mDecorViewMock = mockView(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT, null, VISIBLE,
                asList(mContentRootMock, mStatusBarView, mNavigationBarView));
    }

    private void setTranslucent(ViewGroup bar) {
        bar.setBackground(new ColorDrawable(Color.TRANSPARENT));
    }

    private ViewGroup mockView(int left, int top, int right, int bottom, Drawable background,
            int visibility, List<ViewGroup> children) {
        final ViewGroup v = new FrameLayout(InstrumentationRegistry.getTargetContext());

        v.layout(left, top, right, bottom);
        v.setBackground(background);
        v.setVisibility(visibility);

        for (ViewGroup c : children) {
            v.addView(c);
        }

        return v;
    }
}