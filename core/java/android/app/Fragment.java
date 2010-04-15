/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.app;

import android.content.ComponentCallbacks;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

/**
 * A Fragment is a piece of an application's user interface or behavior
 * that can be placed in an {@link Activity}.
 */
public class Fragment implements ComponentCallbacks {
    static final int INITIALIZING = 0;  // Not yet created.
    static final int CREATED = 1;       // Created.
    static final int STARTED = 2;       // Created and started, not resumed.
    static final int RESUMED = 3;       // Created started and resumed.
    
    String mName;
    
    int mState = INITIALIZING;
    Activity mActivity;
    
    boolean mCalled;
    int mContainerId;
    
    ViewGroup mContainer;
    View mView;
    
    public Fragment() {
    }
    
    public Fragment(String name) {
        mName = name;
    }
    
    public String getName() {
        return mName;
    }
    
    public Activity getActivity() {
        return mActivity;
    }
    
    public void onAttach(Activity activity) {
        mCalled = true;
    }
    
    public void onCreate(Bundle savedInstanceState) {
        mCalled = true;
    }
    
    public View onCreateView(LayoutInflater inflater, ViewGroup container) {
        return null;
    }
    
    public void onRestoreInstanceState(Bundle savedInstanceState) {
    }
    
    public void onStart() {
        mCalled = true;
    }
    
    public void onRestart() {
        mCalled = true;
    }
    
    public void onResume() {
        mCalled = true;
    }
    
    public void onSaveInstanceState(Bundle outState) {
    }
    
    public void onConfigurationChanged(Configuration newConfig) {
        mCalled = true;
    }
    
    public Object onRetainNonConfigurationInstance() {
        return null;
    }
    
    public void onPause() {
        mCalled = true;
    }
    
    public void onStop() {
        mCalled = true;
    }
    
    public void onLowMemory() {
        mCalled = true;
    }
    
    public void onDestroy() {
        mCalled = true;
    }
    
    public void onDetach() {
        mCalled = true;
    }
}
