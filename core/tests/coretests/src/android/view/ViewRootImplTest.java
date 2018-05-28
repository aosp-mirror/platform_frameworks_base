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

package android.view;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;

import android.content.Context;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class ViewRootImplTest {

    private Context mContext;
    private ViewRootImplAccessor mViewRootImpl;

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getContext();

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mViewRootImpl = new ViewRootImplAccessor(
                    new ViewRootImpl(mContext, mContext.getDisplay()));
        });
    }

    @Test
    public void negativeInsets_areSetToZero() throws Exception {
        mViewRootImpl.getAttachInfo().getContentInsets().set(-10, -20, -30 , -40);
        mViewRootImpl.getAttachInfo().getStableInsets().set(-10, -20, -30 , -40);
        final WindowInsets insets = mViewRootImpl.getWindowInsets(true /* forceConstruct */);

        assertThat(insets.getSystemWindowInsets(), equalTo(new Rect()));
        assertThat(new Rect(insets.getStableInsetLeft(), insets.getStableInsetTop(),
                insets.getStableInsetRight(), insets.getStableInsetBottom()), equalTo(new Rect()));
    }

    @Test
    public void negativeInsets_areSetToZero_positiveAreLeftAsIs() throws Exception {
        mViewRootImpl.getAttachInfo().getContentInsets().set(-10, 20, -30 , 40);
        mViewRootImpl.getAttachInfo().getStableInsets().set(10, -20, 30 , -40);
        final WindowInsets insets = mViewRootImpl.getWindowInsets(true /* forceConstruct */);

        assertThat(insets.getSystemWindowInsets(), equalTo(new Rect(0, 20, 0, 40)));
        assertThat(new Rect(insets.getStableInsetLeft(), insets.getStableInsetTop(),
                insets.getStableInsetRight(), insets.getStableInsetBottom()),
                equalTo(new Rect(10, 0, 30, 0)));
    }

    @Test
    public void positiveInsets_areLeftAsIs() throws Exception {
        mViewRootImpl.getAttachInfo().getContentInsets().set(10, 20, 30 , 40);
        mViewRootImpl.getAttachInfo().getStableInsets().set(10, 20, 30 , 40);
        final WindowInsets insets = mViewRootImpl.getWindowInsets(true /* forceConstruct */);

        assertThat(insets.getSystemWindowInsets(), equalTo(new Rect(10, 20, 30, 40)));
        assertThat(new Rect(insets.getStableInsetLeft(), insets.getStableInsetTop(),
                insets.getStableInsetRight(), insets.getStableInsetBottom()),
                equalTo(new Rect(10, 20, 30, 40)));
    }

    private static class ViewRootImplAccessor {

        private final ViewRootImpl mViewRootImpl;

        ViewRootImplAccessor(ViewRootImpl viewRootImpl) {
            mViewRootImpl = viewRootImpl;
        }

        public ViewRootImpl get() {
            return mViewRootImpl;
        }

        AttachInfoAccessor getAttachInfo() throws Exception {
            return new AttachInfoAccessor(
                    getField(mViewRootImpl, ViewRootImpl.class.getDeclaredField("mAttachInfo")));
        }

        WindowInsets getWindowInsets(boolean forceConstruct) throws Exception {
            return (WindowInsets) invokeMethod(mViewRootImpl,
                    ViewRootImpl.class.getDeclaredMethod("getWindowInsets", boolean.class),
                    forceConstruct);
        }

        class AttachInfoAccessor {

            private final Class<?> mClass;
            private final Object mAttachInfo;

            AttachInfoAccessor(Object attachInfo) throws Exception {
                mAttachInfo = attachInfo;
                mClass = ViewRootImpl.class.getClassLoader().loadClass(
                        "android.view.View$AttachInfo");
            }

            Rect getContentInsets() throws Exception {
                return (Rect) getField(mAttachInfo, mClass.getDeclaredField("mContentInsets"));
            }

            Rect getStableInsets() throws Exception {
                return (Rect) getField(mAttachInfo, mClass.getDeclaredField("mStableInsets"));
            }
        }

        private static Object getField(Object o, Field field) throws Exception {
            field.setAccessible(true);
            return field.get(o);
        }

        private static Object invokeMethod(Object o, Method method, Object... args)
                throws Exception {
            method.setAccessible(true);
            return method.invoke(o, args);
        }
    }
}
