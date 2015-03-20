/*
 * Copyright (C) 2015 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.databinding.testapp;

import android.databinding.testapp.generated.LeakTestBinding;
import android.test.ActivityInstrumentationTestCase2;
import android.util.Log;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;

public class LeakTest extends ActivityInstrumentationTestCase2<TestActivity> {
    WeakReference<LeakTestBinding> mWeakReference = new WeakReference<LeakTestBinding>(null);

    public LeakTest() {
        super(TestActivity.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        try {
            getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        LeakTestBinding binding = LeakTestBinding.inflate(getActivity());
                        getActivity().setContentView(binding.getRoot());
                        mWeakReference = new WeakReference<LeakTestBinding>(binding);
                        binding.setName("hello world");
                        binding.executePendingBindings();
                    } catch (Exception e) {
                        e.printStackTrace();
                        throw e;
                    }
                }
            });
            getInstrumentation().waitForIdleSync();
        } catch (Throwable t) {
            throw new Exception(t);
        }
    }

    public void testBindingLeak() throws Throwable {
        assertNotNull(mWeakReference.get());
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                getActivity().setContentView(new FrameLayout(getActivity()));
            }
        });
        System.gc();
        assertNull(mWeakReference.get());
    }

    // Test to ensure that when the View is detached that it doesn't rebind
    // the dirty Views. The rebind should happen only after the root view is
    // reattached.
    public void testNoChangeWhenDetached() throws Throwable {
        final LeakTestBinding binding = mWeakReference.get();
        final AnimationWatcher watcher = new AnimationWatcher();

        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                getActivity().setContentView(new FrameLayout(getActivity()));
                binding.setName("goodbye world");
                binding.getRoot().postOnAnimation(watcher);
            }
        });

        watcher.waitForAnimationThread();

        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertEquals("hello world", binding.getTextView().getText().toString());
                getActivity().setContentView(binding.getRoot());
                binding.getRoot().postOnAnimation(watcher);
            }
        });

        watcher.waitForAnimationThread();

        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                assertEquals("goodbye world", binding.getTextView().getText().toString());
            }
        });
    }

    private static class AnimationWatcher implements Runnable {
        private boolean mWaiting = true;

        public void waitForAnimationThread() throws InterruptedException {
            synchronized (this) {
                while (mWaiting) {
                    this.wait();
                }
                mWaiting = true;
            }
        }


        @Override
        public void run() {
            synchronized (this) {
                mWaiting = false;
                this.notifyAll();
            }
        }
    }
}
