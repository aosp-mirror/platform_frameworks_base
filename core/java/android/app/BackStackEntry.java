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

import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;

final class BackStackState implements Parcelable {
    final int[] mOps;
    final int mTransition;
    final int mTransitionStyle;
    final String mName;
    
    public BackStackState(FragmentManager fm, BackStackEntry bse) {
        mOps = new int[bse.mNumOp*4];
        BackStackEntry.Op op = bse.mHead;
        int pos = 0;
        while (op != null) {
            mOps[pos++] = op.cmd;
            mOps[pos++] = op.fragment.mIndex;
            mOps[pos++] = op.enterAnim;
            mOps[pos++] = op.exitAnim;
            op = op.next;
        }
        mTransition = bse.mTransition;
        mTransitionStyle = bse.mTransitionStyle;
        mName = bse.mName;
    }
    
    public BackStackState(Parcel in) {
        mOps = in.createIntArray();
        mTransition = in.readInt();
        mTransitionStyle = in.readInt();
        mName = in.readString();
    }
    
    public BackStackEntry instantiate(FragmentManager fm) {
        BackStackEntry bse = new BackStackEntry(fm);
        int pos = 0;
        while (pos < mOps.length) {
            BackStackEntry.Op op = new BackStackEntry.Op();
            op.cmd = mOps[pos++];
            Fragment f = fm.mActive.get(mOps[pos++]);
            f.mBackStackNesting++;
            op.fragment = f;
            op.enterAnim = mOps[pos++];
            op.exitAnim = mOps[pos++];
            bse.addOp(op);
        }
        bse.mTransition = mTransition;
        bse.mTransitionStyle = mTransitionStyle;
        bse.mName = mName;
        return bse;
    }
    
    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeIntArray(mOps);
        dest.writeInt(mTransition);
        dest.writeInt(mTransitionStyle);
        dest.writeString(mName);
    }
    
    public static final Parcelable.Creator<BackStackState> CREATOR
            = new Parcelable.Creator<BackStackState>() {
        public BackStackState createFromParcel(Parcel in) {
            return new BackStackState(in);
        }
        
        public BackStackState[] newArray(int size) {
            return new BackStackState[size];
        }
    };
}

/**
 * @hide Entry of an operation on the fragment back stack.
 */
final class BackStackEntry implements FragmentTransaction, Runnable {
    final FragmentManager mManager;
    
    static final int OP_NULL = 0;
    static final int OP_ADD = 1;
    static final int OP_REPLACE = 2;
    static final int OP_REMOVE = 3;
    static final int OP_HIDE = 4;
    static final int OP_SHOW = 5;
    
    static final class Op {
        Op next;
        Op prev;
        int cmd;
        Fragment fragment;
        int enterAnim;
        int exitAnim;
        ArrayList<Fragment> removed;
    }
    
    Op mHead;
    Op mTail;
    int mNumOp;
    int mEnterAnim;
    int mExitAnim;
    int mTransition;
    int mTransitionStyle;
    boolean mAddToBackStack;
    String mName;
    boolean mCommitted;
    
    public BackStackEntry(FragmentManager manager) {
        mManager = manager;
    }
    
    void addOp(Op op) {
        if (mHead == null) {
            mHead = mTail = op;
        } else {
            op.prev = mTail;
            mTail.next = op;
            mTail = op;
        }
        op.enterAnim = mEnterAnim;
        op.exitAnim = mExitAnim;
        mNumOp++;
    }
        
    public FragmentTransaction add(Fragment fragment, String tag) {
        doAddOp(0, fragment, tag, OP_ADD);
        return this;
    }

    public FragmentTransaction add(int containerViewId, Fragment fragment) {
        doAddOp(containerViewId, fragment, null, OP_ADD);
        return this;
    }

    public FragmentTransaction add(int containerViewId, Fragment fragment, String tag) {
        doAddOp(containerViewId, fragment, tag, OP_ADD);
        return this;
    }

    private void doAddOp(int containerViewId, Fragment fragment, String tag, int opcmd) {
        if (fragment.mImmediateActivity != null) {
            throw new IllegalStateException("Fragment already added: " + fragment);
        }
        fragment.mImmediateActivity = mManager.mActivity;
        
        if (tag != null) {
            if (fragment.mTag != null && !tag.equals(fragment.mTag)) {
                throw new IllegalStateException("Can't change tag of fragment "
                        + fragment + ": was " + fragment.mTag
                        + " now " + tag);
            }
            fragment.mTag = tag;
        }
        
        if (containerViewId != 0) {
            if (fragment.mFragmentId != 0 && fragment.mFragmentId != containerViewId) {
                throw new IllegalStateException("Can't change container ID of fragment "
                        + fragment + ": was " + fragment.mFragmentId
                        + " now " + containerViewId);
            }
            fragment.mContainerId = fragment.mFragmentId = containerViewId;
        }
        
        Op op = new Op();
        op.cmd = opcmd;
        op.fragment = fragment;
        addOp(op);
    }

    public FragmentTransaction replace(int containerViewId, Fragment fragment) {
        return replace(containerViewId, fragment, null);
    }
    
    public FragmentTransaction replace(int containerViewId, Fragment fragment, String tag) {
        if (containerViewId == 0) {
            throw new IllegalArgumentException("Must use non-zero containerViewId");
        }
        
        doAddOp(containerViewId, fragment, tag, OP_REPLACE);
        return this;
    }
    
    public FragmentTransaction remove(Fragment fragment) {
        if (fragment.mImmediateActivity == null) {
            throw new IllegalStateException("Fragment not added: " + fragment);
        }
        fragment.mImmediateActivity = null;
        
        Op op = new Op();
        op.cmd = OP_REMOVE;
        op.fragment = fragment;
        addOp(op);
        
        return this;
    }

    public FragmentTransaction hide(Fragment fragment) {
        if (fragment.mImmediateActivity == null) {
            throw new IllegalStateException("Fragment not added: " + fragment);
        }
        
        Op op = new Op();
        op.cmd = OP_HIDE;
        op.fragment = fragment;
        addOp(op);
        
        return this;
    }
    
    public FragmentTransaction show(Fragment fragment) {
        if (fragment.mImmediateActivity == null) {
            throw new IllegalStateException("Fragment not added: " + fragment);
        }
        
        Op op = new Op();
        op.cmd = OP_SHOW;
        op.fragment = fragment;
        addOp(op);
        
        return this;
    }
    
    public FragmentTransaction setCustomAnimations(int enter, int exit) {
        mEnterAnim = enter;
        mExitAnim = exit;
        return this;
    }
    
    public FragmentTransaction setTransition(int transition) {
        mTransition = transition;
        return this;
    }
    
    public FragmentTransaction setTransitionStyle(int styleRes) {
        mTransitionStyle = styleRes;
        return this;
    }
    
    public FragmentTransaction addToBackStack(String name) {
        mAddToBackStack = true;
        mName = name;
        return this;
    }

    public void commit() {
        if (mCommitted) throw new IllegalStateException("commit already called");
        mCommitted = true;
        mManager.mActivity.mHandler.post(this);
    }
    
    public void run() {
        Op op = mHead;
        while (op != null) {
            switch (op.cmd) {
                case OP_ADD: {
                    Fragment f = op.fragment;
                    if (mAddToBackStack) {
                        f.mBackStackNesting++;
                    }
                    f.mNextAnim = op.enterAnim;
                    mManager.addFragment(f, false);
                } break;
                case OP_REPLACE: {
                    Fragment f = op.fragment;
                    if (mManager.mAdded != null) {
                        for (int i=0; i<mManager.mAdded.size(); i++) {
                            Fragment old = mManager.mAdded.get(i);
                            if (old.mContainerId == f.mContainerId) {
                                if (op.removed == null) {
                                    op.removed = new ArrayList<Fragment>();
                                }
                                op.removed.add(old);
                                if (mAddToBackStack) {
                                    old.mBackStackNesting++;
                                }
                                old.mNextAnim = op.exitAnim;
                                mManager.removeFragment(old, mTransition, mTransitionStyle);
                            }
                        }
                    }
                    if (mAddToBackStack) {
                        f.mBackStackNesting++;
                    }
                    f.mNextAnim = op.enterAnim;
                    mManager.addFragment(f, false);
                } break;
                case OP_REMOVE: {
                    Fragment f = op.fragment;
                    if (mAddToBackStack) {
                        f.mBackStackNesting++;
                    }
                    f.mNextAnim = op.exitAnim;
                    mManager.removeFragment(f, mTransition, mTransitionStyle);
                } break;
                case OP_HIDE: {
                    Fragment f = op.fragment;
                    if (mAddToBackStack) {
                        f.mBackStackNesting++;
                    }
                    f.mNextAnim = op.exitAnim;
                    mManager.hideFragment(f, mTransition, mTransitionStyle);
                } break;
                case OP_SHOW: {
                    Fragment f = op.fragment;
                    if (mAddToBackStack) {
                        f.mBackStackNesting++;
                    }
                    f.mNextAnim = op.enterAnim;
                    mManager.showFragment(f, mTransition, mTransitionStyle);
                } break;
                default: {
                    throw new IllegalArgumentException("Unknown cmd: " + op.cmd);
                }
            }
            
            op = op.next;
        }
        
        mManager.moveToState(mManager.mCurState, mTransition,
                mTransitionStyle, true);
        if (mManager.mNeedMenuInvalidate && mManager.mActivity != null) {
            mManager.mActivity.invalidateOptionsMenu();
            mManager.mNeedMenuInvalidate = false;
        }
        
        if (mAddToBackStack) {
            mManager.addBackStackState(this);
        }
    }
    
    public void popFromBackStack() {
        Op op = mTail;
        while (op != null) {
            switch (op.cmd) {
                case OP_ADD: {
                    Fragment f = op.fragment;
                    if (mAddToBackStack) {
                        f.mBackStackNesting--;
                    }
                    mManager.removeFragment(f,
                            FragmentManager.reverseTransit(mTransition),
                            mTransitionStyle);
                } break;
                case OP_REPLACE: {
                    Fragment f = op.fragment;
                    if (mAddToBackStack) {
                        f.mBackStackNesting--;
                    }
                    mManager.removeFragment(f,
                            FragmentManager.reverseTransit(mTransition),
                            mTransitionStyle);
                    if (op.removed != null) {
                        for (int i=0; i<op.removed.size(); i++) {
                            Fragment old = op.removed.get(i);
                            if (mAddToBackStack) {
                                old.mBackStackNesting--;
                            }
                            mManager.addFragment(old, false);
                        }
                    }
                } break;
                case OP_REMOVE: {
                    Fragment f = op.fragment;
                    if (mAddToBackStack) {
                        f.mBackStackNesting--;
                    }
                    mManager.addFragment(f, false);
                } break;
                case OP_HIDE: {
                    Fragment f = op.fragment;
                    if (mAddToBackStack) {
                        f.mBackStackNesting--;
                    }
                    mManager.showFragment(f,
                            FragmentManager.reverseTransit(mTransition), mTransitionStyle);
                } break;
                case OP_SHOW: {
                    Fragment f = op.fragment;
                    if (mAddToBackStack) {
                        f.mBackStackNesting--;
                    }
                    mManager.hideFragment(f,
                            FragmentManager.reverseTransit(mTransition), mTransitionStyle);
                } break;
                default: {
                    throw new IllegalArgumentException("Unknown cmd: " + op.cmd);
                }
            }
            
            op = op.prev;
        }
        
        mManager.moveToState(mManager.mCurState,
                FragmentManager.reverseTransit(mTransition), mTransitionStyle, true);
        if (mManager.mNeedMenuInvalidate && mManager.mActivity != null) {
            mManager.mActivity.invalidateOptionsMenu();
            mManager.mNeedMenuInvalidate = false;
        }
    }
    
    public String getName() {
        return mName;
    }
    
    public int getTransition() {
        return mTransition;
    }
    
    public int getTransitionStyle() {
        return mTransitionStyle;
    }
}
