/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.frameworktest.view;

import android.os.Parcel;
import android.test.ActivityInstrumentationTestCase;
import android.test.suitebuilder.annotation.MediumTest;
import android.view.InflateException;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RemoteViews;

import com.android.frameworktest.R;
import com.android.frameworktest.view.RemoteViewsActivity;

public class RemoteViewsActivityTest extends ActivityInstrumentationTestCase<RemoteViewsActivity> {
    public RemoteViewsActivityTest() {
        super("com.android.frameworktest", RemoteViewsActivity.class);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
    }

    @MediumTest
    public void testGood() throws Exception {
        final RemoteViewsActivity activity = getActivity();

        RemoteViews orig = new RemoteViews("com.android.frameworktest",
                R.layout.remote_view_test_good);
        Parcel p = Parcel.obtain();
        orig.writeToParcel(p, 0);
        p.setDataPosition(0);
        
        RemoteViews r = RemoteViews.CREATOR.createFromParcel(p);
        
        ViewGroup parent = (ViewGroup) activity.findViewById(R.id.parent);
        
        View result = r.apply(activity, parent);
        
        p.recycle();
        
        assertTrue("LinearLayout not inflated", result.findViewById(R.id.linear) != null);
        assertTrue("TextView not inflated", result.findViewById(R.id.text) != null);
        assertTrue("ImageView not inflated", result.findViewById(R.id.image) != null);
        assertTrue("FrameLayout not inflated", result.findViewById(R.id.frame) != null);
        assertTrue("RelateiveLayout not inflated", result.findViewById(R.id.relative) != null);
        assertTrue("AbsoluteLayout not inflated", result.findViewById(R.id.absolute) != null);
        assertTrue("ProgressBar not inflated", result.findViewById(R.id.progress) != null);
        assertTrue("ImageButton not inflated", result.findViewById(R.id.image_button) != null);
        assertTrue("Button not inflated", result.findViewById(R.id.button) != null);
    }
    
    @MediumTest
    public void testDerivedClass() throws Exception {
        final RemoteViewsActivity activity = getActivity();
        
        RemoteViews orig = new RemoteViews("com.android.frameworktest",
                R.layout.remote_view_test_bad_1);
        Parcel p = Parcel.obtain();
        orig.writeToParcel(p, 0);
        p.setDataPosition(0);
        
        RemoteViews r = RemoteViews.CREATOR.createFromParcel(p);
        
        ViewGroup parent = (ViewGroup) activity.findViewById(R.id.parent);
        
        boolean exceptionThrown = false;
        View result = null;
        
        try {
            result = r.apply(activity, parent);
        } catch (InflateException e) {
            exceptionThrown = true;
        }
        
        p.recycle();
        
        assertTrue("Derived class (EditText) allowed to be inflated", exceptionThrown);
        assertNull("Derived class (EditText) allowed to be inflated", result);
    }
    
    @MediumTest
    public void testWebView() throws Exception {
        final RemoteViewsActivity activity = getActivity();
        
        RemoteViews orig = new RemoteViews("com.android.frameworktest",
                R.layout.remote_view_test_bad_2);
        Parcel p = Parcel.obtain();
        orig.writeToParcel(p, 0);
        p.setDataPosition(0);
        
        RemoteViews r = RemoteViews.CREATOR.createFromParcel(p);
        
        ViewGroup parent = (ViewGroup) activity.findViewById(R.id.parent);
        
        boolean exceptionThrown = false;
        View result = null;
        
        try {
            result = r.apply(activity, parent);
        } catch (InflateException e) {
            exceptionThrown = true;
        }
        
        p.recycle();
        
        assertTrue("WebView allowed to be inflated", exceptionThrown);
        assertNull("WebView allowed to be inflated", result);
    }
}
