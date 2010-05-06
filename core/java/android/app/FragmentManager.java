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

import android.content.res.TypedArray;
import android.os.Bundle;
import android.os.Handler;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;

import java.util.ArrayList;

interface BackStackState {
    public void popFromBackStack();
    public String getName();
    public int getTransition();
    public int getTransitionStyle();
}

/**
 * @hide
 * Container for fragments associated with an activity.
 */
public class FragmentManager {
    ArrayList<Fragment> mFragments;
    ArrayList<BackStackState> mBackStack;
    
    int mCurState = Fragment.INITIALIZING;
    Activity mActivity;
    
    Animation loadAnimation(Fragment fragment, int transit, boolean enter,
            int transitionStyle) {
        Animation animObj = fragment.onCreateAnimation(transitionStyle, enter);
        if (animObj != null) {
            return animObj;
        }
        
        if (transit == 0) {
            return null;
        }
        
        int styleIndex = transitToStyleIndex(transit, enter);
        if (styleIndex < 0) {
            return null;
        }
        
        if (transitionStyle == 0 && mActivity.getWindow() != null) {
            transitionStyle = mActivity.getWindow().getAttributes().windowAnimations;
        }
        if (transitionStyle == 0) {
            return null;
        }
        
        TypedArray attrs = mActivity.obtainStyledAttributes(transitionStyle,
                com.android.internal.R.styleable.WindowAnimation);
        int anim = attrs.getResourceId(styleIndex, 0);
        attrs.recycle();
        
        if (anim == 0) {
            return null;
        }
        
        return AnimationUtils.loadAnimation(mActivity, anim);
    }
    
    void moveToState(Fragment f, int newState, int transit, int transitionStyle) {
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
                        Animation anim = loadAnimation(f, transit, true, transitionStyle);
                        if (anim != null) {
                            f.mView.setAnimation(anim);
                        }
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
                        if (f.mContainer != null) {
                            Animation anim = loadAnimation(f, transit, false, transitionStyle);
                            if (anim != null) {
                                f.mView.setAnimation(anim);
                            }
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
        moveToState(newState, 0, 0, always);
    }
    
    void moveToState(int newState, int transit, int transitStyle, boolean always) {
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
                moveToState(f, newState, transit, transitStyle);
            }
        }
    }
    
    public void addFragment(Fragment fragment, boolean moveToStateNow) {
        if (mFragments == null) {
            mFragments = new ArrayList<Fragment>();
        }
        mFragments.add(fragment);
        if (moveToStateNow) {
            moveToState(fragment, mCurState, 0, 0);
        }
    }
    
    public void removeFragment(Fragment fragment, int transition, int transitionStyle) {
        mFragments.remove(fragment);
        moveToState(fragment, Fragment.INITIALIZING, transition, transitionStyle);
    }
    
    public Fragment findFragmentById(int id) {
        if (mFragments != null) {
            for (int i=mFragments.size()-1; i>=0; i--) {
                Fragment f = mFragments.get(i);
                if (f.mContainerId == id) {
                    return f;
                }
            }
        }
        return null;
    }
    
    public void addBackStackState(BackStackState state) {
        if (mBackStack == null) {
            mBackStack = new ArrayList<BackStackState>();
        }
        mBackStack.add(state);
    }
    
    public boolean popBackStackState(Handler handler, String name) {
        if (mBackStack == null) {
            return false;
        }
        if (name == null) {
            int last = mBackStack.size()-1;
            if (last < 0) {
                return false;
            }
            final BackStackState bss = mBackStack.remove(last);
            handler.post(new Runnable() {
                public void run() {
                    bss.popFromBackStack();
                    moveToState(mCurState, reverseTransit(bss.getTransition()),
                            bss.getTransitionStyle(), true);
                }
            });
        } else {
            int index = mBackStack.size()-1;
            while (index >= 0) {
                BackStackState bss = mBackStack.get(index);
                if (name.equals(bss.getName())) {
                    break;
                }
            }
            if (index < 0 || index == mBackStack.size()-1) {
                return false;
            }
            final ArrayList<BackStackState> states = new ArrayList<BackStackState>();
            for (int i=mBackStack.size()-1; i>index; i--) {
                states.add(mBackStack.remove(i));
            }
            handler.post(new Runnable() {
                public void run() {
                    for (int i=0; i<states.size(); i++) {
                        states.get(i).popFromBackStack();
                    }
                    moveToState(mCurState, true);
                }
            });
        }
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
    
    public static int reverseTransit(int transit) {
        int rev = 0;
        switch (transit) {
            case FragmentTransaction.TRANSIT_ENTER:
                rev = FragmentTransaction.TRANSIT_EXIT;
                break;
            case FragmentTransaction.TRANSIT_EXIT:
                rev = FragmentTransaction.TRANSIT_ENTER;
                break;
            case FragmentTransaction.TRANSIT_SHOW:
                rev = FragmentTransaction.TRANSIT_HIDE;
                break;
            case FragmentTransaction.TRANSIT_HIDE:
                rev = FragmentTransaction.TRANSIT_SHOW;
                break;
            case FragmentTransaction.TRANSIT_ACTIVITY_OPEN:
                rev = FragmentTransaction.TRANSIT_ACTIVITY_CLOSE;
                break;
            case FragmentTransaction.TRANSIT_ACTIVITY_CLOSE:
                rev = FragmentTransaction.TRANSIT_ACTIVITY_OPEN;
                break;
            case FragmentTransaction.TRANSIT_TASK_OPEN:
                rev = FragmentTransaction.TRANSIT_TASK_CLOSE;
                break;
            case FragmentTransaction.TRANSIT_TASK_CLOSE:
                rev = FragmentTransaction.TRANSIT_TASK_OPEN;
                break;
            case FragmentTransaction.TRANSIT_TASK_TO_FRONT:
                rev = FragmentTransaction.TRANSIT_TASK_TO_BACK;
                break;
            case FragmentTransaction.TRANSIT_TASK_TO_BACK:
                rev = FragmentTransaction.TRANSIT_TASK_TO_FRONT;
                break;
            case FragmentTransaction.TRANSIT_WALLPAPER_OPEN:
                rev = FragmentTransaction.TRANSIT_WALLPAPER_CLOSE;
                break;
            case FragmentTransaction.TRANSIT_WALLPAPER_CLOSE:
                rev = FragmentTransaction.TRANSIT_WALLPAPER_OPEN;
                break;
            case FragmentTransaction.TRANSIT_WALLPAPER_INTRA_OPEN:
                rev = FragmentTransaction.TRANSIT_WALLPAPER_INTRA_CLOSE;
                break;
            case FragmentTransaction.TRANSIT_WALLPAPER_INTRA_CLOSE:
                rev = FragmentTransaction.TRANSIT_WALLPAPER_INTRA_OPEN;
                break;
        }
        return rev;
        
    }
    
    public static int transitToStyleIndex(int transit, boolean enter) {
        int animAttr = -1;
        switch (transit) {
            case FragmentTransaction.TRANSIT_ENTER:
                animAttr = com.android.internal.R.styleable.WindowAnimation_windowEnterAnimation;
                break;
            case FragmentTransaction.TRANSIT_EXIT:
                animAttr = com.android.internal.R.styleable.WindowAnimation_windowExitAnimation;
                break;
            case FragmentTransaction.TRANSIT_SHOW:
                animAttr = com.android.internal.R.styleable.WindowAnimation_windowShowAnimation;
                break;
            case FragmentTransaction.TRANSIT_HIDE:
                animAttr = com.android.internal.R.styleable.WindowAnimation_windowHideAnimation;
                break;
            case FragmentTransaction.TRANSIT_ACTIVITY_OPEN:
                animAttr = enter
                        ? com.android.internal.R.styleable.WindowAnimation_activityOpenEnterAnimation
                        : com.android.internal.R.styleable.WindowAnimation_activityOpenExitAnimation;
                break;
            case FragmentTransaction.TRANSIT_ACTIVITY_CLOSE:
                animAttr = enter
                        ? com.android.internal.R.styleable.WindowAnimation_activityCloseEnterAnimation
                        : com.android.internal.R.styleable.WindowAnimation_activityCloseExitAnimation;
                break;
            case FragmentTransaction.TRANSIT_TASK_OPEN:
                animAttr = enter
                        ? com.android.internal.R.styleable.WindowAnimation_taskOpenEnterAnimation
                        : com.android.internal.R.styleable.WindowAnimation_taskOpenExitAnimation;
                break;
            case FragmentTransaction.TRANSIT_TASK_CLOSE:
                animAttr = enter
                        ? com.android.internal.R.styleable.WindowAnimation_taskCloseEnterAnimation
                        : com.android.internal.R.styleable.WindowAnimation_taskCloseExitAnimation;
                break;
            case FragmentTransaction.TRANSIT_TASK_TO_FRONT:
                animAttr = enter
                        ? com.android.internal.R.styleable.WindowAnimation_taskToFrontEnterAnimation
                        : com.android.internal.R.styleable.WindowAnimation_taskToFrontExitAnimation;
                break;
            case FragmentTransaction.TRANSIT_TASK_TO_BACK:
                animAttr = enter
                        ? com.android.internal.R.styleable.WindowAnimation_taskToBackEnterAnimation
                        : com.android.internal.R.styleable.WindowAnimation_taskToBackExitAnimation;
                break;
            case FragmentTransaction.TRANSIT_WALLPAPER_OPEN:
                animAttr = enter
                        ? com.android.internal.R.styleable.WindowAnimation_wallpaperOpenEnterAnimation
                        : com.android.internal.R.styleable.WindowAnimation_wallpaperOpenExitAnimation;
                break;
            case FragmentTransaction.TRANSIT_WALLPAPER_CLOSE:
                animAttr = enter
                        ? com.android.internal.R.styleable.WindowAnimation_wallpaperCloseEnterAnimation
                        : com.android.internal.R.styleable.WindowAnimation_wallpaperCloseExitAnimation;
                break;
            case FragmentTransaction.TRANSIT_WALLPAPER_INTRA_OPEN:
                animAttr = enter
                        ? com.android.internal.R.styleable.WindowAnimation_wallpaperIntraOpenEnterAnimation
                        : com.android.internal.R.styleable.WindowAnimation_wallpaperIntraOpenExitAnimation;
                break;
            case FragmentTransaction.TRANSIT_WALLPAPER_INTRA_CLOSE:
                animAttr = enter
                        ? com.android.internal.R.styleable.WindowAnimation_wallpaperIntraCloseEnterAnimation
                        : com.android.internal.R.styleable.WindowAnimation_wallpaperIntraCloseExitAnimation;
                break;
        }
        return animAttr;
    }
}
