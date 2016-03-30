/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs;

import android.test.suitebuilder.annotation.SmallTest;
import android.view.View;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.qs.TouchAnimator.Listener;
import org.mockito.Mockito;

@SmallTest
public class TouchAnimatorTests extends SysuiTestCase {

    private Listener mTouchListener;
    private View mTestView;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mTestView = new View(getContext());
        mTouchListener = Mockito.mock(Listener.class);
    }

    public void testSetValueFloat() {
        TouchAnimator animator = new TouchAnimator.Builder()
                .addFloat(mTestView, "x", 0, 50)
                .build();

        animator.setPosition(0);
        assertEquals(0f, mTestView.getX());

        animator.setPosition(.5f);
        assertEquals(25f, mTestView.getX());

        animator.setPosition(1);
        assertEquals(50f, mTestView.getX());
    }

    public void testSetValueInt() {
        TouchAnimator animator = new TouchAnimator.Builder()
                .addInt(mTestView, "top", 0, 50)
                .build();

        animator.setPosition(0);
        assertEquals(0, mTestView.getTop());

        animator.setPosition(.5f);
        assertEquals(25, mTestView.getTop());

        animator.setPosition(1);
        assertEquals(50, mTestView.getTop());
    }

    public void testStartDelay() {
        TouchAnimator animator = new TouchAnimator.Builder()
                .addFloat(mTestView, "x", 0, 50)
                .setStartDelay(.5f)
                .build();

        animator.setPosition(0);
        assertEquals(0f, mTestView.getX());

        animator.setPosition(.5f);
        assertEquals(0f, mTestView.getX());

        animator.setPosition(.75f);
        assertEquals(25f, mTestView.getX());

        animator.setPosition(1);
        assertEquals(50f, mTestView.getX());
    }

    public void testEndDelay() {
        TouchAnimator animator = new TouchAnimator.Builder()
                .addFloat(mTestView, "x", 0, 50)
                .setEndDelay(.5f)
                .build();

        animator.setPosition(0);
        assertEquals(0f, mTestView.getX());

        animator.setPosition(.25f);
        assertEquals(25f, mTestView.getX());

        animator.setPosition(.5f);
        assertEquals(50f, mTestView.getX());

        animator.setPosition(1);
        assertEquals(50f, mTestView.getX());
    }

    public void testOnAnimationAtStartCallback() {
        TouchAnimator animator = new TouchAnimator.Builder()
                .setListener(mTouchListener)
                .build();

        // Called on init.
        animator.setPosition(0);
        verifyOnAnimationAtStart(1);

        // Not called from same state.
        animator.setPosition(0);
        verifyOnAnimationAtStart(1);

        // Called after starting and moving back to start.
        animator.setPosition(.5f);
        animator.setPosition(0);
        verifyOnAnimationAtStart(2);

        // Called when move from end to end.
        animator.setPosition(1);
        animator.setPosition(0);
        verifyOnAnimationAtStart(3);
    }

    public void testOnAnimationAtEndCallback() {
        TouchAnimator animator = new TouchAnimator.Builder()
                .setListener(mTouchListener)
                .build();

        // Called on init.
        animator.setPosition(1);
        verifyOnAnimationAtEnd(1);

        // Not called from same state.
        animator.setPosition(1);
        verifyOnAnimationAtEnd(1);

        // Called after starting and moving back to end.
        animator.setPosition(.5f);
        animator.setPosition(1);
        verifyOnAnimationAtEnd(2);

        // Called when move from end to end.
        animator.setPosition(0);
        animator.setPosition(1);
        verifyOnAnimationAtEnd(3);
    }

    public void testOnAnimationStartedCallback() {
        TouchAnimator animator = new TouchAnimator.Builder()
                .setListener(mTouchListener)
                .build();

        // Called on init.
        animator.setPosition(.5f);
        verifyOnAnimationStarted(1);

        // Not called from same state.
        animator.setPosition(.6f);
        verifyOnAnimationStarted(1);

        // Called after going to end then moving again.
        animator.setPosition(1);
        animator.setPosition(.5f);
        verifyOnAnimationStarted(2);

        // Called after moving to start then moving again.
        animator.setPosition(0);
        animator.setPosition(.5f);
        verifyOnAnimationStarted(3);
    }

    // TODO: Add test for interpolator.

    private void verifyOnAnimationAtStart(int times) {
        Mockito.verify(mTouchListener, Mockito.times(times)).onAnimationAtStart();
    }

    private void verifyOnAnimationAtEnd(int times) {
        Mockito.verify(mTouchListener, Mockito.times(times)).onAnimationAtEnd();
    }

    private void verifyOnAnimationStarted(int times) {
        Mockito.verify(mTouchListener, Mockito.times(times)).onAnimationStarted();
    }
}
