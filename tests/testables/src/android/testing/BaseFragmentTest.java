/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.testing;

import static org.junit.Assert.assertNotNull;

import android.annotation.Nullable;
import android.app.Fragment;
import android.app.FragmentController;
import android.app.FragmentHostCallback;
import android.app.FragmentManagerNonConfig;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.widget.FrameLayout;

import androidx.test.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Base class for fragment class tests.  Just adding one for any fragment will push it through
 * general lifecycle events and ensure no basic leaks are happening.  This class also implements
 * the host for subclasses, so they can push it into desired states and do any unit testing
 * required.
 */
public abstract class BaseFragmentTest {

    private static final int VIEW_ID = 42;
    private final Class<? extends Fragment> mCls;
    private Handler mHandler;
    protected FrameLayout mView;
    protected FragmentController mFragments;
    protected Fragment mFragment;

    @Rule
    public final TestableContext mContext = getContext();

    public BaseFragmentTest(Class<? extends Fragment> cls) {
        mCls = cls;
    }

    protected void createRootView() {
        mView = new FrameLayout(mContext);
    }

    @Before
    public void setupFragment() throws Exception {
        createRootView();
        mView.setId(VIEW_ID);

        assertNotNull("BaseFragmentTest must be tagged with @RunWithLooper",
                TestableLooper.get(this));
        TestableLooper.get(this).runWithLooper(() -> {
            mHandler = new Handler();

            mFragment = instantiate(mContext, mCls.getName(), null);
            mFragments = FragmentController.createController(new HostCallbacks());
            mFragments.attachHost(null);
            mFragments.getFragmentManager().beginTransaction()
                    .replace(VIEW_ID, mFragment)
                    .commit();
        });
    }

    /**
     * Allows tests to sub-class TestableContext if they want to provide any extended functionality
     * or provide a {@link LeakCheck} to the TestableContext upon instantiation.
     */
    protected TestableContext getContext() {
        return new TestableContext(InstrumentationRegistry.getContext());
    }

    @After
    public void tearDown() throws Exception {
        if (mFragments != null) {
            // Set mFragments to null to let it know not to destroy.
            TestableLooper.get(this).runWithLooper(() -> mFragments.dispatchDestroy());
        }
    }

    @Test
    public void testCreateDestroy() {
        mFragments.dispatchCreate();
        processAllMessages();
        destroyFragments();
    }

    @Test
    public void testStartStop() {
        mFragments.dispatchStart();
        processAllMessages();
        mFragments.dispatchStop();
        processAllMessages();
    }

    @Test
    public void testResumePause() {
        mFragments.dispatchResume();
        processAllMessages();
        mFragments.dispatchPause();
        processAllMessages();
    }

    @Test
    public void testAttachDetach() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT,
                LayoutParams.TYPE_SYSTEM_ALERT,
                0, PixelFormat.TRANSLUCENT);
        mFragments.dispatchResume();
        processAllMessages();
        attachFragmentToWindow();
        detachFragmentToWindow();
        mFragments.dispatchPause();
        processAllMessages();
    }

    @Test
    public void testRecreate() {
        mFragments.dispatchResume();
        processAllMessages();
        recreateFragment();
        processAllMessages();
    }

    @Test
    public void testMultipleResumes() {
        mFragments.dispatchResume();
        processAllMessages();
        mFragments.dispatchStop();
        processAllMessages();
        mFragments.dispatchResume();
        processAllMessages();
    }

    protected void recreateFragment() {
        mFragments.dispatchPause();
        Parcelable p = mFragments.saveAllState();
        mFragments.dispatchDestroy();

        mFragments = FragmentController.createController(new HostCallbacks());
        mFragments.attachHost(null);
        mFragments.restoreAllState(p, (FragmentManagerNonConfig) null);
        mFragments.dispatchResume();
        mFragment = mFragments.getFragmentManager().findFragmentById(VIEW_ID);
    }

    protected void attachFragmentToWindow() {
        ViewUtils.attachView(mView);
        TestableLooper.get(this).processAllMessages();
    }

    protected void detachFragmentToWindow() {
        ViewUtils.detachView(mView);
        TestableLooper.get(this).processAllMessages();
    }

    protected void destroyFragments() {
        mFragments.dispatchDestroy();
        processAllMessages();
        mFragments = null;
    }

    protected void processAllMessages() {
        TestableLooper.get(this).processAllMessages();
    }

    /**
     * Method available for override to replace fragment instantiation.
     */
    protected Fragment instantiate(Context context, String className, @Nullable Bundle arguments) {
        return Fragment.instantiate(context, className, arguments);
    }

    private View findViewById(int id) {
        return mView.findViewById(id);
    }

    private class HostCallbacks extends FragmentHostCallback<BaseFragmentTest> {
        public HostCallbacks() {
            super(mContext, BaseFragmentTest.this.mHandler, 0);
        }

        @Override
        public BaseFragmentTest onGetHost() {
            return BaseFragmentTest.this;
        }

        @Override
        public void onDump(String prefix, FileDescriptor fd, PrintWriter writer, String[] args) {
        }

        @Override
        public Fragment instantiate(Context context, String className, Bundle arguments) {
            return BaseFragmentTest.this.instantiate(context, className, arguments);
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
            return BaseFragmentTest.this.findViewById(id);
        }

        @Override
        public boolean onHasView() {
            return true;
        }
    }
}
