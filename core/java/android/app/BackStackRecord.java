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

import com.android.internal.util.FastPrintWriter;

import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.transition.Transition;
import android.transition.TransitionInflater;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.util.ArrayMap;
import android.util.Log;
import android.util.LogWriter;
import android.util.Pair;
import android.view.View;
import android.view.ViewGroup;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;

final class BackStackState implements Parcelable {
    final int[] mOps;
    final int mTransition;
    final int mTransitionStyle;
    final int mCustomTransition;
    final int mSceneRoot;
    final String mName;
    final int mIndex;
    final int mBreadCrumbTitleRes;
    final CharSequence mBreadCrumbTitleText;
    final int mBreadCrumbShortTitleRes;
    final CharSequence mBreadCrumbShortTitleText;
    final ArrayList<String> mSharedElementSourceNames;
    final ArrayList<String> mSharedElementTargetNames;

    public BackStackState(FragmentManagerImpl fm, BackStackRecord bse) {
        int numRemoved = 0;
        BackStackRecord.Op op = bse.mHead;
        while (op != null) {
            if (op.removed != null) {
                numRemoved += op.removed.size();
            }
            op = op.next;
        }
        mOps = new int[bse.mNumOp * 7 + numRemoved];

        if (!bse.mAddToBackStack) {
            throw new IllegalStateException("Not on back stack");
        }

        op = bse.mHead;
        int pos = 0;
        while (op != null) {
            mOps[pos++] = op.cmd;
            mOps[pos++] = op.fragment != null ? op.fragment.mIndex : -1;
            mOps[pos++] = op.enterAnim;
            mOps[pos++] = op.exitAnim;
            mOps[pos++] = op.popEnterAnim;
            mOps[pos++] = op.popExitAnim;
            if (op.removed != null) {
                final int N = op.removed.size();
                mOps[pos++] = N;
                for (int i = 0; i < N; i++) {
                    mOps[pos++] = op.removed.get(i).mIndex;
                }
            } else {
                mOps[pos++] = 0;
            }
            op = op.next;
        }
        mTransition = bse.mTransition;
        mTransitionStyle = bse.mTransitionStyle;
        mName = bse.mName;
        mIndex = bse.mIndex;
        mBreadCrumbTitleRes = bse.mBreadCrumbTitleRes;
        mBreadCrumbTitleText = bse.mBreadCrumbTitleText;
        mBreadCrumbShortTitleRes = bse.mBreadCrumbShortTitleRes;
        mBreadCrumbShortTitleText = bse.mBreadCrumbShortTitleText;
        mCustomTransition = bse.mCustomTransition;
        mSceneRoot = bse.mSceneRoot;
        mSharedElementSourceNames = bse.mSharedElementSourceNames;
        mSharedElementTargetNames = bse.mSharedElementTargetNames;
    }

    public BackStackState(Parcel in) {
        mOps = in.createIntArray();
        mTransition = in.readInt();
        mTransitionStyle = in.readInt();
        mName = in.readString();
        mIndex = in.readInt();
        mBreadCrumbTitleRes = in.readInt();
        mBreadCrumbTitleText = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
        mBreadCrumbShortTitleRes = in.readInt();
        mBreadCrumbShortTitleText = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
        mCustomTransition = in.readInt();
        mSceneRoot = in.readInt();
        mSharedElementSourceNames = in.createStringArrayList();
        mSharedElementTargetNames = in.createStringArrayList();
    }

    public BackStackRecord instantiate(FragmentManagerImpl fm) {
        BackStackRecord bse = new BackStackRecord(fm);
        int pos = 0;
        int num = 0;
        while (pos < mOps.length) {
            BackStackRecord.Op op = new BackStackRecord.Op();
            op.cmd = mOps[pos++];
            if (FragmentManagerImpl.DEBUG) {
                Log.v(FragmentManagerImpl.TAG,
                        "Instantiate " + bse + " op #" + num + " base fragment #" + mOps[pos]);
            }
            int findex = mOps[pos++];
            if (findex >= 0) {
                Fragment f = fm.mActive.get(findex);
                op.fragment = f;
            } else {
                op.fragment = null;
            }
            op.enterAnim = mOps[pos++];
            op.exitAnim = mOps[pos++];
            op.popEnterAnim = mOps[pos++];
            op.popExitAnim = mOps[pos++];
            final int N = mOps[pos++];
            if (N > 0) {
                op.removed = new ArrayList<Fragment>(N);
                for (int i = 0; i < N; i++) {
                    if (FragmentManagerImpl.DEBUG) {
                        Log.v(FragmentManagerImpl.TAG,
                                "Instantiate " + bse + " set remove fragment #" + mOps[pos]);
                    }
                    Fragment r = fm.mActive.get(mOps[pos++]);
                    op.removed.add(r);
                }
            }
            bse.addOp(op);
            num++;
        }
        bse.mTransition = mTransition;
        bse.mTransitionStyle = mTransitionStyle;
        bse.mName = mName;
        bse.mIndex = mIndex;
        bse.mAddToBackStack = true;
        bse.mBreadCrumbTitleRes = mBreadCrumbTitleRes;
        bse.mBreadCrumbTitleText = mBreadCrumbTitleText;
        bse.mBreadCrumbShortTitleRes = mBreadCrumbShortTitleRes;
        bse.mBreadCrumbShortTitleText = mBreadCrumbShortTitleText;
        bse.mCustomTransition = mCustomTransition;
        bse.mSceneRoot = mSceneRoot;
        bse.mSharedElementSourceNames = mSharedElementSourceNames;
        bse.mSharedElementTargetNames = mSharedElementTargetNames;
        bse.bumpBackStackNesting(1);
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
        dest.writeInt(mIndex);
        dest.writeInt(mBreadCrumbTitleRes);
        TextUtils.writeToParcel(mBreadCrumbTitleText, dest, 0);
        dest.writeInt(mBreadCrumbShortTitleRes);
        TextUtils.writeToParcel(mBreadCrumbShortTitleText, dest, 0);
        dest.writeInt(mCustomTransition);
        dest.writeInt(mSceneRoot);
        dest.writeStringList(mSharedElementSourceNames);
        dest.writeStringList(mSharedElementTargetNames);
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
final class BackStackRecord extends FragmentTransaction implements
        FragmentManager.BackStackEntry, Runnable {
    static final String TAG = FragmentManagerImpl.TAG;

    final FragmentManagerImpl mManager;

    static final int OP_NULL = 0;
    static final int OP_ADD = 1;
    static final int OP_REPLACE = 2;
    static final int OP_REMOVE = 3;
    static final int OP_HIDE = 4;
    static final int OP_SHOW = 5;
    static final int OP_DETACH = 6;
    static final int OP_ATTACH = 7;

    static final class Op {
        Op next;
        Op prev;
        int cmd;
        Fragment fragment;
        int enterAnim;
        int exitAnim;
        int popEnterAnim;
        int popExitAnim;
        ArrayList<Fragment> removed;
    }

    Op mHead;
    Op mTail;
    int mNumOp;
    int mEnterAnim;
    int mExitAnim;
    int mPopEnterAnim;
    int mPopExitAnim;
    int mTransition;
    int mTransitionStyle;
    boolean mAddToBackStack;
    boolean mAllowAddToBackStack = true;
    String mName;
    boolean mCommitted;
    int mIndex = -1;

    int mBreadCrumbTitleRes;
    CharSequence mBreadCrumbTitleText;
    int mBreadCrumbShortTitleRes;
    CharSequence mBreadCrumbShortTitleText;

    int mCustomTransition;
    int mSceneRoot;
    ArrayList<String> mSharedElementSourceNames;
    ArrayList<String> mSharedElementTargetNames;

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(128);
        sb.append("BackStackEntry{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        if (mIndex >= 0) {
            sb.append(" #");
            sb.append(mIndex);
        }
        if (mName != null) {
            sb.append(" ");
            sb.append(mName);
        }
        sb.append("}");
        return sb.toString();
    }

    public void dump(String prefix, FileDescriptor fd, PrintWriter writer, String[] args) {
        dump(prefix, writer, true);
    }

    void dump(String prefix, PrintWriter writer, boolean full) {
        if (full) {
            writer.print(prefix);
            writer.print("mName=");
            writer.print(mName);
            writer.print(" mIndex=");
            writer.print(mIndex);
            writer.print(" mCommitted=");
            writer.println(mCommitted);
            if (mTransition != FragmentTransaction.TRANSIT_NONE) {
                writer.print(prefix);
                writer.print("mTransition=#");
                writer.print(Integer.toHexString(mTransition));
                writer.print(" mTransitionStyle=#");
                writer.println(Integer.toHexString(mTransitionStyle));
            }
            if (mEnterAnim != 0 || mExitAnim != 0) {
                writer.print(prefix);
                writer.print("mEnterAnim=#");
                writer.print(Integer.toHexString(mEnterAnim));
                writer.print(" mExitAnim=#");
                writer.println(Integer.toHexString(mExitAnim));
            }
            if (mPopEnterAnim != 0 || mPopExitAnim != 0) {
                writer.print(prefix);
                writer.print("mPopEnterAnim=#");
                writer.print(Integer.toHexString(mPopEnterAnim));
                writer.print(" mPopExitAnim=#");
                writer.println(Integer.toHexString(mPopExitAnim));
            }
            if (mBreadCrumbTitleRes != 0 || mBreadCrumbTitleText != null) {
                writer.print(prefix);
                writer.print("mBreadCrumbTitleRes=#");
                writer.print(Integer.toHexString(mBreadCrumbTitleRes));
                writer.print(" mBreadCrumbTitleText=");
                writer.println(mBreadCrumbTitleText);
            }
            if (mBreadCrumbShortTitleRes != 0 || mBreadCrumbShortTitleText != null) {
                writer.print(prefix);
                writer.print("mBreadCrumbShortTitleRes=#");
                writer.print(Integer.toHexString(mBreadCrumbShortTitleRes));
                writer.print(" mBreadCrumbShortTitleText=");
                writer.println(mBreadCrumbShortTitleText);
            }
        }

        if (mHead != null) {
            writer.print(prefix);
            writer.println("Operations:");
            String innerPrefix = prefix + "    ";
            Op op = mHead;
            int num = 0;
            while (op != null) {
                String cmdStr;
                switch (op.cmd) {
                    case OP_NULL:
                        cmdStr = "NULL";
                        break;
                    case OP_ADD:
                        cmdStr = "ADD";
                        break;
                    case OP_REPLACE:
                        cmdStr = "REPLACE";
                        break;
                    case OP_REMOVE:
                        cmdStr = "REMOVE";
                        break;
                    case OP_HIDE:
                        cmdStr = "HIDE";
                        break;
                    case OP_SHOW:
                        cmdStr = "SHOW";
                        break;
                    case OP_DETACH:
                        cmdStr = "DETACH";
                        break;
                    case OP_ATTACH:
                        cmdStr = "ATTACH";
                        break;
                    default:
                        cmdStr = "cmd=" + op.cmd;
                        break;
                }
                writer.print(prefix);
                writer.print("  Op #");
                writer.print(num);
                writer.print(": ");
                writer.print(cmdStr);
                writer.print(" ");
                writer.println(op.fragment);
                if (full) {
                    if (op.enterAnim != 0 || op.exitAnim != 0) {
                        writer.print(innerPrefix);
                        writer.print("enterAnim=#");
                        writer.print(Integer.toHexString(op.enterAnim));
                        writer.print(" exitAnim=#");
                        writer.println(Integer.toHexString(op.exitAnim));
                    }
                    if (op.popEnterAnim != 0 || op.popExitAnim != 0) {
                        writer.print(innerPrefix);
                        writer.print("popEnterAnim=#");
                        writer.print(Integer.toHexString(op.popEnterAnim));
                        writer.print(" popExitAnim=#");
                        writer.println(Integer.toHexString(op.popExitAnim));
                    }
                }
                if (op.removed != null && op.removed.size() > 0) {
                    for (int i = 0; i < op.removed.size(); i++) {
                        writer.print(innerPrefix);
                        if (op.removed.size() == 1) {
                            writer.print("Removed: ");
                        } else {
                            if (i == 0) {
                                writer.println("Removed:");
                            }
                            writer.print(innerPrefix);
                            writer.print("  #");
                            writer.print(i);
                            writer.print(": ");
                        }
                        writer.println(op.removed.get(i));
                    }
                }
                op = op.next;
                num++;
            }
        }
    }

    public BackStackRecord(FragmentManagerImpl manager) {
        mManager = manager;
    }

    public int getId() {
        return mIndex;
    }

    public int getBreadCrumbTitleRes() {
        return mBreadCrumbTitleRes;
    }

    public int getBreadCrumbShortTitleRes() {
        return mBreadCrumbShortTitleRes;
    }

    public CharSequence getBreadCrumbTitle() {
        if (mBreadCrumbTitleRes != 0) {
            return mManager.mActivity.getText(mBreadCrumbTitleRes);
        }
        return mBreadCrumbTitleText;
    }

    public CharSequence getBreadCrumbShortTitle() {
        if (mBreadCrumbShortTitleRes != 0) {
            return mManager.mActivity.getText(mBreadCrumbShortTitleRes);
        }
        return mBreadCrumbShortTitleText;
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
        op.popEnterAnim = mPopEnterAnim;
        op.popExitAnim = mPopExitAnim;
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
        fragment.mFragmentManager = mManager;

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
        Op op = new Op();
        op.cmd = OP_REMOVE;
        op.fragment = fragment;
        addOp(op);

        return this;
    }

    public FragmentTransaction hide(Fragment fragment) {
        Op op = new Op();
        op.cmd = OP_HIDE;
        op.fragment = fragment;
        addOp(op);

        return this;
    }

    public FragmentTransaction show(Fragment fragment) {
        Op op = new Op();
        op.cmd = OP_SHOW;
        op.fragment = fragment;
        addOp(op);

        return this;
    }

    public FragmentTransaction detach(Fragment fragment) {
        Op op = new Op();
        op.cmd = OP_DETACH;
        op.fragment = fragment;
        addOp(op);

        return this;
    }

    public FragmentTransaction attach(Fragment fragment) {
        Op op = new Op();
        op.cmd = OP_ATTACH;
        op.fragment = fragment;
        addOp(op);

        return this;
    }

    public FragmentTransaction setCustomAnimations(int enter, int exit) {
        return setCustomAnimations(enter, exit, 0, 0);
    }

    public FragmentTransaction setCustomAnimations(int enter, int exit,
            int popEnter, int popExit) {
        mEnterAnim = enter;
        mExitAnim = exit;
        mPopEnterAnim = popEnter;
        mPopExitAnim = popExit;
        return this;
    }

    public FragmentTransaction setTransition(int transition) {
        mTransition = transition;
        return this;
    }

    @Override
    public FragmentTransaction setCustomTransition(int sceneRootId, int transitionId) {
        mSceneRoot = sceneRootId;
        mCustomTransition = transitionId;
        return this;
    }

    @Override
    public FragmentTransaction addSharedElement(View sharedElement, String name) {
        String transitionName = sharedElement.getTransitionName();
        if (transitionName == null) {
            throw new IllegalArgumentException("Unique transitionNames are required for all" +
                    " sharedElements");
        }
        if (mSharedElementSourceNames == null) {
            mSharedElementSourceNames = new ArrayList<String>();
            mSharedElementTargetNames = new ArrayList<String>();
        }
        mSharedElementSourceNames.add(transitionName);
        mSharedElementTargetNames.add(name);
        return this;
    }

    /** TODO: remove this */
    @Override
    public FragmentTransaction setSharedElement(View sharedElement, String name) {
        String transitionName = sharedElement.getTransitionName();
        if (transitionName == null) {
            throw new IllegalArgumentException("Unique transitionNames are required for all" +
                    " sharedElements");
        }
        mSharedElementSourceNames = new ArrayList<String>(1);
        mSharedElementSourceNames.add(transitionName);

        mSharedElementTargetNames = new ArrayList<String>(1);
        mSharedElementTargetNames.add(name);
        return this;
    }

    /** TODO: remove this */
    @Override
    public FragmentTransaction setSharedElements(Pair<View, String>... sharedElements) {
        if (sharedElements == null || sharedElements.length == 0) {
            mSharedElementSourceNames = null;
            mSharedElementTargetNames = null;
        } else {
            ArrayList<String> sourceNames = new ArrayList<String>(sharedElements.length);
            ArrayList<String> targetNames = new ArrayList<String>(sharedElements.length);
            for (int i = 0; i < sharedElements.length; i++) {
                String transitionName = sharedElements[i].first.getTransitionName();
                if (transitionName == null) {
                    throw new IllegalArgumentException("Unique transitionNames are required for all"
                            + " sharedElements");
                }
                sourceNames.add(transitionName);
                targetNames.add(sharedElements[i].second);
            }
            mSharedElementSourceNames = sourceNames;
            mSharedElementTargetNames = targetNames;
        }
        return this;
    }

    public FragmentTransaction setTransitionStyle(int styleRes) {
        mTransitionStyle = styleRes;
        return this;
    }

    public FragmentTransaction addToBackStack(String name) {
        if (!mAllowAddToBackStack) {
            throw new IllegalStateException(
                    "This FragmentTransaction is not allowed to be added to the back stack.");
        }
        mAddToBackStack = true;
        mName = name;
        return this;
    }

    public boolean isAddToBackStackAllowed() {
        return mAllowAddToBackStack;
    }

    public FragmentTransaction disallowAddToBackStack() {
        if (mAddToBackStack) {
            throw new IllegalStateException(
                    "This transaction is already being added to the back stack");
        }
        mAllowAddToBackStack = false;
        return this;
    }

    public FragmentTransaction setBreadCrumbTitle(int res) {
        mBreadCrumbTitleRes = res;
        mBreadCrumbTitleText = null;
        return this;
    }

    public FragmentTransaction setBreadCrumbTitle(CharSequence text) {
        mBreadCrumbTitleRes = 0;
        mBreadCrumbTitleText = text;
        return this;
    }

    public FragmentTransaction setBreadCrumbShortTitle(int res) {
        mBreadCrumbShortTitleRes = res;
        mBreadCrumbShortTitleText = null;
        return this;
    }

    public FragmentTransaction setBreadCrumbShortTitle(CharSequence text) {
        mBreadCrumbShortTitleRes = 0;
        mBreadCrumbShortTitleText = text;
        return this;
    }

    void bumpBackStackNesting(int amt) {
        if (!mAddToBackStack) {
            return;
        }
        if (FragmentManagerImpl.DEBUG) {
            Log.v(TAG, "Bump nesting in " + this
                    + " by " + amt);
        }
        Op op = mHead;
        while (op != null) {
            if (op.fragment != null) {
                op.fragment.mBackStackNesting += amt;
                if (FragmentManagerImpl.DEBUG) {
                    Log.v(TAG, "Bump nesting of "
                            + op.fragment + " to " + op.fragment.mBackStackNesting);
                }
            }
            if (op.removed != null) {
                for (int i = op.removed.size() - 1; i >= 0; i--) {
                    Fragment r = op.removed.get(i);
                    r.mBackStackNesting += amt;
                    if (FragmentManagerImpl.DEBUG) {
                        Log.v(TAG, "Bump nesting of "
                                + r + " to " + r.mBackStackNesting);
                    }
                }
            }
            op = op.next;
        }
    }

    public int commit() {
        return commitInternal(false);
    }

    public int commitAllowingStateLoss() {
        return commitInternal(true);
    }

    int commitInternal(boolean allowStateLoss) {
        if (mCommitted) {
            throw new IllegalStateException("commit already called");
        }
        if (FragmentManagerImpl.DEBUG) {
            Log.v(TAG, "Commit: " + this);
            LogWriter logw = new LogWriter(Log.VERBOSE, TAG);
            PrintWriter pw = new FastPrintWriter(logw, false, 1024);
            dump("  ", null, pw, null);
            pw.flush();
        }
        mCommitted = true;
        if (mAddToBackStack) {
            mIndex = mManager.allocBackStackIndex(this);
        } else {
            mIndex = -1;
        }
        mManager.enqueueAction(this, allowStateLoss);
        return mIndex;
    }

    public void run() {
        if (FragmentManagerImpl.DEBUG) {
            Log.v(TAG, "Run: " + this);
        }

        if (mAddToBackStack) {
            if (mIndex < 0) {
                throw new IllegalStateException("addToBackStack() called after commit()");
            }
        }

        bumpBackStackNesting(1);

        TransitionState state = beginTransition(mSharedElementSourceNames,
                mSharedElementTargetNames);

        Op op = mHead;
        while (op != null) {
            switch (op.cmd) {
                case OP_ADD: {
                    Fragment f = op.fragment;
                    f.mNextAnim = op.enterAnim;
                    mManager.addFragment(f, false);
                }
                break;
                case OP_REPLACE: {
                    Fragment f = op.fragment;
                    if (mManager.mAdded != null) {
                        for (int i = 0; i < mManager.mAdded.size(); i++) {
                            Fragment old = mManager.mAdded.get(i);
                            if (FragmentManagerImpl.DEBUG) {
                                Log.v(TAG,
                                        "OP_REPLACE: adding=" + f + " old=" + old);
                            }
                            if (f == null || old.mContainerId == f.mContainerId) {
                                if (old == f) {
                                    op.fragment = f = null;
                                } else {
                                    if (op.removed == null) {
                                        op.removed = new ArrayList<Fragment>();
                                    }
                                    op.removed.add(old);
                                    old.mNextAnim = op.exitAnim;
                                    if (mAddToBackStack) {
                                        old.mBackStackNesting += 1;
                                        if (FragmentManagerImpl.DEBUG) {
                                            Log.v(TAG, "Bump nesting of "
                                                    + old + " to " + old.mBackStackNesting);
                                        }
                                    }
                                    mManager.removeFragment(old, mTransition, mTransitionStyle);
                                }
                            }
                        }
                    }
                    if (f != null) {
                        f.mNextAnim = op.enterAnim;
                        mManager.addFragment(f, false);
                    }
                }
                break;
                case OP_REMOVE: {
                    Fragment f = op.fragment;
                    f.mNextAnim = op.exitAnim;
                    mManager.removeFragment(f, mTransition, mTransitionStyle);
                }
                break;
                case OP_HIDE: {
                    Fragment f = op.fragment;
                    f.mNextAnim = op.exitAnim;
                    mManager.hideFragment(f, mTransition, mTransitionStyle);
                }
                break;
                case OP_SHOW: {
                    Fragment f = op.fragment;
                    f.mNextAnim = op.enterAnim;
                    mManager.showFragment(f, mTransition, mTransitionStyle);
                }
                break;
                case OP_DETACH: {
                    Fragment f = op.fragment;
                    f.mNextAnim = op.exitAnim;
                    mManager.detachFragment(f, mTransition, mTransitionStyle);
                }
                break;
                case OP_ATTACH: {
                    Fragment f = op.fragment;
                    f.mNextAnim = op.enterAnim;
                    mManager.attachFragment(f, mTransition, mTransitionStyle);
                }
                break;
                default: {
                    throw new IllegalArgumentException("Unknown cmd: " + op.cmd);
                }
            }

            op = op.next;
        }

        mManager.moveToState(mManager.mCurState, mTransition,
                mTransitionStyle, true);

        if (mAddToBackStack) {
            mManager.addBackStackState(this);
        }

        if (state != null) {
            updateTransitionEndState(state, mSharedElementTargetNames);
        }
    }

    private TransitionState beginTransition(ArrayList<String> sourceNames,
            ArrayList<String> targetNames) {
        if (mCustomTransition <= 0 || mSceneRoot <= 0) {
            return null;
        }
        View rootView = mManager.mContainer.findViewById(mSceneRoot);
        if (!(rootView instanceof ViewGroup)) {
            throw new IllegalArgumentException("SceneRoot is not a ViewGroup");
        }
        TransitionState state = new TransitionState();
        // get Transition scene root and create Transitions
        state.sceneRoot = (ViewGroup) rootView;
        state.sceneRoot.captureTransitioningViews(state.transitioningViews);

        state.exitTransition = TransitionInflater.from(mManager.mActivity)
                .inflateTransition(mCustomTransition);
        state.sharedElementTransition = TransitionInflater.from(mManager.mActivity)
                .inflateTransition(mCustomTransition);
        state.enterTransition = TransitionInflater.from(mManager.mActivity)
                .inflateTransition(mCustomTransition);
        // Adding a non-existent target view makes sure that the transitions don't target
        // any views by default. They'll only target the views we tell add. If we don't
        // add any, then no views will be targeted.
        View nonExistentView = new View(mManager.mActivity);
        state.enterTransition.addTarget(nonExistentView);
        state.exitTransition.addTarget(nonExistentView);
        state.sharedElementTransition.addTarget(nonExistentView);

        setSharedElementEpicenter(state.enterTransition, state);

        state.excludingTransition = new TransitionSet()
                .addTransition(state.exitTransition)
                .addTransition(state.enterTransition);

        if (sourceNames != null) {
            // Map shared elements.
            state.sceneRoot.findNamedViews(state.namedViews);
            state.namedViews.retainAll(sourceNames);
            View epicenterView = state.namedViews.get(sourceNames.get(0));
            if (epicenterView != null) {
                // The epicenter is only the first shared element.
                setEpicenter(state.exitTransition, epicenterView);
                setEpicenter(state.sharedElementTransition, epicenterView);
            }
            state.transitioningViews.removeAll(state.namedViews.values());
            state.excludingTransition.addTransition(state.sharedElementTransition);
            addTransitioningViews(state.sharedElementTransition, state.namedViews.values());
        }

        // Adds the (maybe) exiting views, not including the shared element.
        // If some stay, that's ok.
        addTransitioningViews(state.exitTransition, state.transitioningViews);

        // Prepare for shared element name mapping. This could be chained in the case
        // of popping several back stack states.
        state.excludingTransition.setNameOverrides(new ArrayMap<String, String>());
        setNameOverrides(state, sourceNames, targetNames);

        // Don't include any subtree in the views that are hidden when capturing the
        // view hierarchy transitions. They should be as if not there.
        excludeHiddenFragments(state, true);

        TransitionManager.beginDelayedTransition(state.sceneRoot, state.excludingTransition);
        return state;
    }

    private void updateTransitionEndState(TransitionState state, ArrayList<String> names) {
        // Find all views that are entering.
        ArrayList<View> enteringViews = new ArrayList<View>();
        state.sceneRoot.captureTransitioningViews(enteringViews);
        enteringViews.removeAll(state.transitioningViews);

        if (names != null) {
            // find all shared elements.
            state.namedViews.clear();
            state.sceneRoot.findNamedViews(state.namedViews);
            state.namedViews.retainAll(names);
            if (!state.namedViews.isEmpty()) {
                enteringViews.removeAll(state.namedViews.values());
                addTransitioningViews(state.sharedElementTransition, state.namedViews.values());
                // now we know the epicenter of the entering transition.
                state.mEnteringEpicenterView = state.namedViews.get(names.get(0));
            }
        }

        // Add all entering views to the enter transition.
        addTransitioningViews(state.enterTransition, enteringViews);

        // Don't allow capturing state for the newly-hidden fragments.
        excludeHiddenFragments(state, false);

        // Allow capturing state for the newly-shown fragments
        includeVisibleFragments(state.excludingTransition);
    }

    private void addTransitioningViews(Transition transition, Collection<View> views) {
        if (views.isEmpty()) {
            // Add a view so that we can modify the valid views at the end of the
            // fragment transaction.
            transition.addTarget(new View(mManager.mActivity));
        } else {
            for (View view : views) {
                transition.addTarget(view);
            }
        }
    }

    private void excludeHiddenFragments(TransitionState state, boolean forceExclude) {
        if (mManager.mAdded != null) {
            for (int i = 0; i < mManager.mAdded.size(); i++) {
                Fragment fragment = mManager.mAdded.get(i);
                if (fragment.mView != null && fragment.mHidden
                        && (forceExclude || !state.hiddenViews.contains(fragment.mView))) {
                    state.excludingTransition.excludeTarget(fragment.mView, true);
                    state.hiddenViews.add(fragment.mView);
                }
            }
        }
        if (forceExclude && state.hiddenViews.isEmpty()) {
            state.excludingTransition.excludeTarget(new View(mManager.mActivity), true);
        }
    }

    private void includeVisibleFragments(Transition transition) {
        if (mManager.mAdded != null) {
            for (int i = 0; i < mManager.mAdded.size(); i++) {
                Fragment fragment = mManager.mAdded.get(i);
                if (fragment.mView != null && !fragment.mHidden) {
                    transition.excludeTarget(fragment.mView, false);
                }
            }
        }
    }

    private static void setEpicenter(Transition transition, View view) {
        final Rect epicenter = new Rect();
        view.getBoundsOnScreen(epicenter);

        transition.setEpicenterCallback(new Transition.EpicenterCallback() {
            @Override
            public Rect onGetEpicenter(Transition transition) {
                return epicenter;
            }
        });
    }

    private void setSharedElementEpicenter(Transition transition, final TransitionState state) {
        transition.setEpicenterCallback(new Transition.EpicenterCallback() {
            private Rect mEpicenter;

            @Override
            public Rect onGetEpicenter(Transition transition) {
                if (mEpicenter == null && state.mEnteringEpicenterView != null) {
                    mEpicenter = new Rect();
                    state.mEnteringEpicenterView.getBoundsOnScreen(mEpicenter);
                }
                return mEpicenter;
            }
        });
    }

    public TransitionState popFromBackStack(boolean doStateMove, TransitionState state) {
        if (FragmentManagerImpl.DEBUG) {
            Log.v(TAG, "popFromBackStack: " + this);
            LogWriter logw = new LogWriter(Log.VERBOSE, TAG);
            PrintWriter pw = new FastPrintWriter(logw, false, 1024);
            dump("  ", null, pw, null);
            pw.flush();
        }

        if (state == null) {
            state = beginTransition(mSharedElementTargetNames, mSharedElementSourceNames);
        } else {
            setNameOverrides(state, mSharedElementTargetNames, mSharedElementSourceNames);
        }

        bumpBackStackNesting(-1);

        Op op = mTail;
        while (op != null) {
            switch (op.cmd) {
                case OP_ADD: {
                    Fragment f = op.fragment;
                    f.mNextAnim = op.popExitAnim;
                    mManager.removeFragment(f,
                            FragmentManagerImpl.reverseTransit(mTransition),
                            mTransitionStyle);
                }
                break;
                case OP_REPLACE: {
                    Fragment f = op.fragment;
                    if (f != null) {
                        f.mNextAnim = op.popExitAnim;
                        mManager.removeFragment(f,
                                FragmentManagerImpl.reverseTransit(mTransition),
                                mTransitionStyle);
                    }
                    if (op.removed != null) {
                        for (int i = 0; i < op.removed.size(); i++) {
                            Fragment old = op.removed.get(i);
                            old.mNextAnim = op.popEnterAnim;
                            mManager.addFragment(old, false);
                        }
                    }
                }
                break;
                case OP_REMOVE: {
                    Fragment f = op.fragment;
                    f.mNextAnim = op.popEnterAnim;
                    mManager.addFragment(f, false);
                }
                break;
                case OP_HIDE: {
                    Fragment f = op.fragment;
                    f.mNextAnim = op.popEnterAnim;
                    mManager.showFragment(f,
                            FragmentManagerImpl.reverseTransit(mTransition), mTransitionStyle);
                }
                break;
                case OP_SHOW: {
                    Fragment f = op.fragment;
                    f.mNextAnim = op.popExitAnim;
                    mManager.hideFragment(f,
                            FragmentManagerImpl.reverseTransit(mTransition), mTransitionStyle);
                }
                break;
                case OP_DETACH: {
                    Fragment f = op.fragment;
                    f.mNextAnim = op.popEnterAnim;
                    mManager.attachFragment(f,
                            FragmentManagerImpl.reverseTransit(mTransition), mTransitionStyle);
                }
                break;
                case OP_ATTACH: {
                    Fragment f = op.fragment;
                    f.mNextAnim = op.popExitAnim;
                    mManager.detachFragment(f,
                            FragmentManagerImpl.reverseTransit(mTransition), mTransitionStyle);
                }
                break;
                default: {
                    throw new IllegalArgumentException("Unknown cmd: " + op.cmd);
                }
            }

            op = op.prev;
        }

        if (doStateMove) {
            mManager.moveToState(mManager.mCurState,
                    FragmentManagerImpl.reverseTransit(mTransition), mTransitionStyle, true);
            if (state != null) {
                updateTransitionEndState(state, mSharedElementSourceNames);
                state = null;
            }
        }

        if (mIndex >= 0) {
            mManager.freeBackStackIndex(mIndex);
            mIndex = -1;
        }
        return state;
    }

    private static void setNameOverride(Transition transition, String source, String target) {
        ArrayMap<String, String> overrides = transition.getNameOverrides();
        for (int index = 0; index < overrides.size(); index++) {
            if (source.equals(overrides.valueAt(index))) {
                overrides.setValueAt(index, target);
                return;
            }
        }
        overrides.put(source, target);
    }

    private static void setNameOverrides(TransitionState state, ArrayList<String> sourceNames,
            ArrayList<String> targetNames) {
        if (sourceNames != null) {
            for (int i = 0; i < sourceNames.size(); i++) {
                String source = sourceNames.get(i);
                String target = targetNames.get(i);
                setNameOverride(state.excludingTransition, source, target);
            }
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

    public boolean isEmpty() {
        return mNumOp == 0;
    }

    public class TransitionState {
        public ArrayList<View> hiddenViews = new ArrayList<View>();
        public ArrayList<View> transitioningViews = new ArrayList<View>();
        public ArrayMap<String, View> namedViews = new ArrayMap<String, View>();
        public Transition exitTransition;
        public Transition sharedElementTransition;
        public Transition enterTransition;
        public TransitionSet excludingTransition;
        public ViewGroup sceneRoot;
        public View mEnteringEpicenterView;
    }
}
