/*
 * Copyright (C) 2006 The Android Open Source Project
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

import android.content.Context;
import android.content.res.Resources;
import android.test.AndroidTestCase;
import android.test.PerformanceTestCase;
import android.util.AttributeSet;

import androidx.test.filters.SmallTest;

import com.android.frameworks.coretests.R;

public class InflateTest extends AndroidTestCase implements PerformanceTestCase {
    private LayoutInflater mInflater;
    private Resources mResources;
    private View mView;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mInflater = LayoutInflater.from(mContext);
        mResources = mContext.getResources();

        // to try to make things consistent, before doing timing
        // do an initial instantiation of the layout and then clear
        // out the layout cache.
//            mInflater.inflate(mResId, null, null);
//            mResources.flushLayoutCache();
    }

    public int startPerformance(PerformanceTestCase.Intermediates intermediates) {
        return 0;
    }

    public boolean isPerformanceOnly() {
        return false;
    }

    public void inflateTest(int resourceId) {
        mView = mInflater.inflate(resourceId, null);
        mResources.flushLayoutCache();
    }

    public void inflateCachedTest(int resourceId) {
        // Make sure this layout is in the cache.
        mInflater.inflate(resourceId, null);

        mInflater.inflate(resourceId, null);
    }

    @SmallTest
    public void testLayout1() throws Exception {
        inflateTest(R.layout.layout_one);
    }

    @SmallTest
    public void testLayout2() throws Exception {
        inflateTest(R.layout.layout_two);
    }

    @SmallTest
    public void testLayout3() throws Exception {
        inflateTest(R.layout.layout_three);
    }

    @SmallTest
    public void testLayout4() throws Exception {
        inflateTest(R.layout.layout_four);
    }

    @SmallTest
    public void testLayout5() throws Exception {
        inflateTest(R.layout.layout_five);
    }

    @SmallTest
    public void testLayout6() throws Exception {
        inflateTest(R.layout.layout_six);
    }

    @SmallTest
    public void testCachedLayout1() throws Exception {
        inflateCachedTest(R.layout.layout_one);
    }

    @SmallTest
    public void testCachedLayout2() throws Exception {
        inflateCachedTest(R.layout.layout_two);
    }

    @SmallTest
    public void testCachedLayout3() throws Exception {
        inflateCachedTest(R.layout.layout_three);
    }

    @SmallTest
    public void testCachedLayout4() throws Exception {
        inflateCachedTest(R.layout.layout_four);
    }

    @SmallTest
    public void testCachedLayout5() throws Exception {
        inflateCachedTest(R.layout.layout_five);
    }

    @SmallTest
    public void testCachedLayout6() throws Exception {
        inflateCachedTest(R.layout.layout_six);
    }

//    public void testLayoutTag() throws Exception {
//        public void setUp
//        (Context
//        context){
//        setUp(context, R.layout.layout_tag);
//    }
//        public void run
//        ()
//        {
//            super.run();
//            if (!"MyTag".equals(mView.getTag())) {
//                throw new RuntimeException("Incorrect tag: " + mView.getTag());
//            }
//        }
//    }

    public static class ViewOne extends View {
        public ViewOne(Context context) {
            super(context);
        }

        public ViewOne(Context context, AttributeSet attrs) {
            super(context, attrs);
        }
    }
}
