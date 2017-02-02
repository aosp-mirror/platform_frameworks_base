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

package com.android.systemui;

import android.annotation.Nullable;
import android.app.Fragment;
import android.app.FragmentController;
import android.app.FragmentHostCallback;
import android.app.FragmentManagerNonConfig;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.widget.FrameLayout;

import com.android.systemui.utils.ViewUtils;
import com.android.systemui.utils.leaks.LeakCheckedTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Base class for fragment class tests.  Just adding one for any fragment will push it through
 * general lifecycle events and ensure no basic leaks are happening.  This class also implements
 * the host for subclasses, so they can push it into desired states and do any unit testing
 * required.
 */
public abstract class FragmentTestCase extends LeakCheckedTest {

    private static final int VIEW_ID = 42;
    private final Class<? extends Fragment> mCls;
    private Handler mHandler;
    private FrameLayout mView;
    protected FragmentController mFragments;
    protected Fragment mFragment;

    public FragmentTestCase(Class<? extends Fragment> cls) {
        mCls = cls;
    }

    @Before
    public void setupFragment() throws IllegalAccessException, InstantiationException {
        mView = new FrameLayout(mContext);
        mView.setId(VIEW_ID);
        mHandler = new Handler(Looper.getMainLooper());
        mFragment = mCls.newInstance();
        postAndWait(() -> {
            mFragments = FragmentController.createController(new HostCallbacks());
            mFragments.attachHost(null);
            mFragments.getFragmentManager().beginTransaction()
                    .replace(VIEW_ID, mFragment)
                    .commit();
        });
    }

    @After
    public void tearDown() {
        if (mFragments != null) {
            // Set mFragments to null to let it know not to destroy.
            postAndWait(() -> mFragments.dispatchDestroy());
        }
    }

    @Test
    public void testCreateDestroy() {
        postAndWait(() -> mFragments.dispatchCreate());
        destroyFragments();
    }

    @Test
    public void testStartStop() {
        postAndWait(() -> mFragments.dispatchStart());
        postAndWait(() -> mFragments.dispatchStop());
    }

    @Test
    public void testResumePause() {
        postAndWait(() -> mFragments.dispatchResume());
        postAndWait(() -> mFragments.dispatchPause());
    }

    @Test
    public void testAttachDetach() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT,
                LayoutParams.TYPE_SYSTEM_ALERT,
                0, PixelFormat.TRANSLUCENT);
        postAndWait(() -> mFragments.dispatchResume());
        attachFragmentToWindow();
        detachFragmentToWindow();
        postAndWait(() -> mFragments.dispatchPause());
    }

    protected void attachFragmentToWindow() {
        ViewUtils.attachView(mView);
    }

    protected void detachFragmentToWindow() {
        ViewUtils.detachView(mView);
    }

    @Test
    public void testRecreate() {
        postAndWait(() -> mFragments.dispatchResume());
        postAndWait(() -> {
            mFragments.dispatchPause();
            Parcelable p = mFragments.saveAllState();
            mFragments.dispatchDestroy();

            mFragments = FragmentController.createController(new HostCallbacks());
            mFragments.attachHost(null);
            mFragments.restoreAllState(p, (FragmentManagerNonConfig) null);
            mFragments.dispatchResume();
        });
    }

    @Test
    public void testMultipleResumes() {
        postAndWait(() -> mFragments.dispatchResume());
        postAndWait(() -> mFragments.dispatchStop());
        postAndWait(() -> mFragments.dispatchResume());
    }

    protected void destroyFragments() {
        postAndWait(() -> mFragments.dispatchDestroy());
        mFragments = null;
    }

    protected void postAndWait(Runnable r) {
        mHandler.post(r);
        waitForFragments();
    }

    protected void waitForFragments() {
        waitForIdleSync(mHandler);
    }

    private View findViewById(int id) {
        return mView.findViewById(id);
    }

    private class HostCallbacks extends FragmentHostCallback<FragmentTestCase> {
        public HostCallbacks() {
            super(mContext, FragmentTestCase.this.mHandler, 0);
        }

        @Override
        public FragmentTestCase onGetHost() {
            return FragmentTestCase.this;
        }

        @Override
        public void onDump(String prefix, FileDescriptor fd, PrintWriter writer, String[] args) {
        }

        @Override
        public boolean onShouldSaveFragmentState(Fragment fragment) {
            return true; // True for now.
        }

        @Override
        public LayoutInflater onGetLayoutInflater() {
            return LayoutInflater.from(mContext);
        }

        @Override
        public boolean onUseFragmentManagerInflaterFactory() {
            return true;
        }

        @Override
        public boolean onHasWindowAnimations() {
            return false;
        }

        @Override
        public int onGetWindowAnimations() {
            return 0;
        }

        @Override
        public void onAttachFragment(Fragment fragment) {
        }

        @Nullable
        @Override
        public View onFindViewById(int id) {
            return FragmentTestCase.this.findViewById(id);
        }

        @Override
        public boolean onHasView() {
            return true;
        }
    }
}
