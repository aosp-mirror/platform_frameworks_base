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

import android.os.Bundle;
import android.os.Handler;
import android.view.ViewGroup;

import java.util.ArrayList;

interface BackStackState {
    public void popFromBackStack();
}

/**
 * Container for fragments associated with an activity.
 */
class FragmentManager {
    ArrayList<Fragment> mFragments;
    ArrayList<BackStackState> mBackStack;
    
    int mCurState = Fragment.INITIALIZING;
    Activity mActivity;
    
    void moveToState(Fragment f, int newState) {
        if (f.mState < newState) {
            switch (f.mState) {
                case Fragment.INITIALIZING:
                    f.mActivity = mActivity;
                    f.mCalled = false;
                    f.onAttach(mActivity);
                    if (!f.mCalled) {
                        throw new SuperNotCalledException("Fragment " + f
                                + " did not call through to super.onAttach()");
                    }
                    f.mCalled = false;
                    f.onCreate(null);
                    if (!f.mCalled) {
                        throw new SuperNotCalledException("Fragment " + f
                                + " did not call through to super.onCreate()");
                    }
                    
                    ViewGroup container = null;
                    if (f.mContainerId != 0) {
                        container = (ViewGroup)mActivity.findViewById(f.mContainerId);
                        if (container == null) {
                            throw new IllegalArgumentException("New view found for id 0x"
                                    + Integer.toHexString(f.mContainerId)
                                    + " for fragment " + f);
                        }
                    }
                    f.mContainer = container;
                    f.mView = f.onCreateView(mActivity.getLayoutInflater(), container);
                    if (container != null && f.mView != null) {
                        container.addView(f.mView);
                    }
                    
                case Fragment.CREATED:
                    if (newState > Fragment.CREATED) {
                        f.mCalled = false;
                        f.onStart();
                        if (!f.mCalled) {
                            throw new SuperNotCalledException("Fragment " + f
                                    + " did not call through to super.onStart()");
                        }
                    }
                case Fragment.STARTED:
                    if (newState > Fragment.STARTED) {
                        f.mCalled = false;
                        f.onResume();
                        if (!f.mCalled) {
                            throw new SuperNotCalledException("Fragment " + f
                                    + " did not call through to super.onResume()");
                        }
                    }
            }
        } else if (f.mState > newState) {
            switch (f.mState) {
                case Fragment.RESUMED:
                    if (newState < Fragment.RESUMED) {
                        f.mCalled = false;
                        f.onPause();
                        if (!f.mCalled) {
                            throw new SuperNotCalledException("Fragment " + f
                                    + " did not call through to super.onPause()");
                        }
                    }
                case Fragment.STARTED:
                    if (newState < Fragment.STARTED) {
                        f.mCalled = false;
                        f.onStop();
                        if (!f.mCalled) {
                            throw new SuperNotCalledException("Fragment " + f
                                    + " did not call through to super.onStop()");
                        }
                    }
                case Fragment.CREATED:
                    if (newState < Fragment.CREATED) {
                        if (f.mContainer != null && f.mView != null) {
                            f.mContainer.removeView(f.mView);
                        }
                        f.mContainer = null;
                        f.mView = null;
                        
                        f.mCalled = false;
                        f.onDestroy();
                        if (!f.mCalled) {
                            throw new SuperNotCalledException("Fragment " + f
                                    + " did not call through to super.onDestroy()");
                        }
                        f.mCalled = false;
                        f.onDetach();
                        if (!f.mCalled) {
                            throw new SuperNotCalledException("Fragment " + f
                                    + " did not call through to super.onDetach()");
                        }
                        f.mActivity = null;
                    }
            }
        }
        
        f.mState = newState;
    }
    
    void moveToState(int newState, boolean always) {
        if (mActivity == null && newState != Fragment.INITIALIZING) {
            throw new IllegalStateException("No activity");
        }
        
        if (!always && mCurState == newState) {
            return;
        }
        
        mCurState = newState;
        if (mFragments != null) {
            for (int i=0; i<mFragments.size(); i++) {
                Fragment f = mFragments.get(i);
                moveToState(f, newState);
            }
        }
    }
    
    public void addFragment(Fragment fragment, boolean moveToStateNow) {
        if (mFragments == null) {
            mFragments = new ArrayList<Fragment>();
        }
        mFragments.add(fragment);
        if (moveToStateNow) {
            moveToState(fragment, mCurState);
        }
    }
    
    public void removeFragment(Fragment fragment) {
        mFragments.remove(fragment);
        moveToState(fragment, Fragment.INITIALIZING);
    }
    
    public void addBackStackState(BackStackState state) {
        if (mBackStack == null) {
            mBackStack = new ArrayList<BackStackState>();
        }
        mBackStack.add(state);
    }
    
    public boolean popBackStackState(Handler handler) {
        if (mBackStack == null) {
            return false;
        }
        int last = mBackStack.size()-1;
        if (last < 0) {
            return false;
        }
        final BackStackState bss = mBackStack.remove(last);
        handler.post(new Runnable() {
            public void run() {
                bss.popFromBackStack();
            }
        });
        return true;
    }
    
    public void attachActivity(Activity activity) {
        if (mActivity != null) throw new IllegalStateException();
        mActivity = activity;
    }
    
    public void dispatchCreate(Bundle state) {
        moveToState(Fragment.CREATED, false);
    }
    
    public void dispatchStart() {
        moveToState(Fragment.STARTED, false);
    }
    
    public void dispatchResume() {
        moveToState(Fragment.RESUMED, false);
    }
    
    public void dispatchPause() {
        moveToState(Fragment.STARTED, false);
    }
    
    public void dispatchStop() {
        moveToState(Fragment.CREATED, false);
    }
    
    public void dispatchDestroy() {
        moveToState(Fragment.INITIALIZING, false);
        mActivity = null;
    }
}
