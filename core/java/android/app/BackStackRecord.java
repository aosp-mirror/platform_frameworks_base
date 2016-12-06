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

import android.graphics.Rect;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.transition.Transition;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.util.ArrayMap;
import android.util.Log;
import android.util.LogWriter;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import com.android.internal.util.FastPrintWriter;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

final class BackStackState implements Parcelable {
    final int[] mOps;
    final int mTransition;
    final int mTransitionStyle;
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
            bse.mEnterAnim = op.enterAnim;
            bse.mExitAnim = op.exitAnim;
            bse.mPopEnterAnim = op.popEnterAnim;
            bse.mPopExitAnim = op.popExitAnim;
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
            return mManager.mHost.getContext().getText(mBreadCrumbTitleRes);
        }
        return mBreadCrumbTitleText;
    }

    public CharSequence getBreadCrumbShortTitle() {
        if (mBreadCrumbShortTitleRes != 0) {
            return mManager.mHost.getContext().getText(mBreadCrumbShortTitleRes);
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
            if (containerViewId == View.NO_ID) {
                throw new IllegalArgumentException("Can't add fragment "
                        + fragment + " with tag " + tag + " to container view with no id");
            }
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

    @Override
    public void commitNow() {
        disallowAddToBackStack();
        mManager.execSingleAction(this, false);
    }

    @Override
    public void commitNowAllowingStateLoss() {
        disallowAddToBackStack();
        mManager.execSingleAction(this, true);
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

        if (mManager.mCurState >= Fragment.CREATED) {
            SparseArray<Fragment> firstOutFragments = new SparseArray<Fragment>();
            SparseArray<Fragment> lastInFragments = new SparseArray<Fragment>();
            calculateFragments(firstOutFragments, lastInFragments);
            beginTransition(firstOutFragments, lastInFragments, false);
        }

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
                    int containerId = f.mContainerId;
                    if (mManager.mAdded != null) {
                        for (int i = mManager.mAdded.size() - 1; i >= 0; i--) {
                            Fragment old = mManager.mAdded.get(i);
                            if (FragmentManagerImpl.DEBUG) {
                                Log.v(TAG,
                                        "OP_REPLACE: adding=" + f + " old=" + old);
                            }
                            if (old.mContainerId == containerId) {
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
    }

    private static void setFirstOut(SparseArray<Fragment> firstOutFragments,
                            SparseArray<Fragment> lastInFragments, Fragment fragment) {
        if (fragment != null) {
            int containerId = fragment.mContainerId;
            if (containerId != 0 && !fragment.isHidden()) {
                if (fragment.isAdded() && fragment.getView() != null
                        && firstOutFragments.get(containerId) == null) {
                    firstOutFragments.put(containerId, fragment);
                }
                if (lastInFragments.get(containerId) == fragment) {
                    lastInFragments.remove(containerId);
                }
            }
        }
    }

    private void setLastIn(SparseArray<Fragment> firstOutFragments,
            SparseArray<Fragment> lastInFragments, Fragment fragment) {
        if (fragment != null) {
            int containerId = fragment.mContainerId;
            if (containerId != 0) {
                if (!fragment.isAdded()) {
                    lastInFragments.put(containerId, fragment);
                }
                if (firstOutFragments.get(containerId) == fragment) {
                    firstOutFragments.remove(containerId);
                }
            }
            /**
             * Ensure that fragments that are entering are at least at the CREATED state
             * so that they may load Transitions using TransitionInflater.
             */
            if (fragment.mState < Fragment.CREATED && mManager.mCurState >= Fragment.CREATED &&
                    mManager.mHost.getContext().getApplicationInfo().targetSdkVersion >=
                    Build.VERSION_CODES.N) {
                mManager.makeActive(fragment);
                mManager.moveToState(fragment, Fragment.CREATED, 0, 0, false);
            }
        }
    }

    /**
     * Finds the first removed fragment and last added fragments when going forward.
     * If none of the fragments have transitions, then both lists will be empty.
     *
     * @param firstOutFragments The list of first fragments to be removed, keyed on the
     *                          container ID. This list will be modified by the method.
     * @param lastInFragments The list of last fragments to be added, keyed on the
     *                        container ID. This list will be modified by the method.
     */
    private void calculateFragments(SparseArray<Fragment> firstOutFragments,
            SparseArray<Fragment> lastInFragments) {
        if (!mManager.mContainer.onHasView()) {
            return; // nothing to see, so no transitions
        }
        Op op = mHead;
        while (op != null) {
            switch (op.cmd) {
                case OP_ADD:
                    setLastIn(firstOutFragments, lastInFragments, op.fragment);
                    break;
                case OP_REPLACE: {
                    Fragment f = op.fragment;
                    if (mManager.mAdded != null) {
                        for (int i = 0; i < mManager.mAdded.size(); i++) {
                            Fragment old = mManager.mAdded.get(i);
                            if (f == null || old.mContainerId == f.mContainerId) {
                                if (old == f) {
                                    f = null;
                                    lastInFragments.remove(old.mContainerId);
                                } else {
                                    setFirstOut(firstOutFragments, lastInFragments, old);
                                }
                            }
                        }
                    }
                    setLastIn(firstOutFragments, lastInFragments, op.fragment);
                    break;
                }
                case OP_REMOVE:
                    setFirstOut(firstOutFragments, lastInFragments, op.fragment);
                    break;
                case OP_HIDE:
                    setFirstOut(firstOutFragments, lastInFragments, op.fragment);
                    break;
                case OP_SHOW:
                    setLastIn(firstOutFragments, lastInFragments, op.fragment);
                    break;
                case OP_DETACH:
                    setFirstOut(firstOutFragments, lastInFragments, op.fragment);
                    break;
                case OP_ATTACH:
                    setLastIn(firstOutFragments, lastInFragments, op.fragment);
                    break;
            }

            op = op.next;
        }
    }

    /**
     * Finds the first removed fragment and last added fragments when popping the back stack.
     * If none of the fragments have transitions, then both lists will be empty.
     *
     * @param firstOutFragments The list of first fragments to be removed, keyed on the
     *                          container ID. This list will be modified by the method.
     * @param lastInFragments The list of last fragments to be added, keyed on the
     *                        container ID. This list will be modified by the method.
     */
    public void calculateBackFragments(SparseArray<Fragment> firstOutFragments,
            SparseArray<Fragment> lastInFragments) {
        if (!mManager.mContainer.onHasView()) {
            return; // nothing to see, so no transitions
        }
        Op op = mTail;
        while (op != null) {
            switch (op.cmd) {
                case OP_ADD:
                    setFirstOut(firstOutFragments, lastInFragments, op.fragment);
                    break;
                case OP_REPLACE:
                    if (op.removed != null) {
                        for (int i = op.removed.size() - 1; i >= 0; i--) {
                            setLastIn(firstOutFragments, lastInFragments, op.removed.get(i));
                        }
                    }
                    setFirstOut(firstOutFragments, lastInFragments, op.fragment);
                    break;
                case OP_REMOVE:
                    setLastIn(firstOutFragments, lastInFragments, op.fragment);
                    break;
                case OP_HIDE:
                    setLastIn(firstOutFragments, lastInFragments, op.fragment);
                    break;
                case OP_SHOW:
                    setFirstOut(firstOutFragments, lastInFragments, op.fragment);
                    break;
                case OP_DETACH:
                    setLastIn(firstOutFragments, lastInFragments, op.fragment);
                    break;
                case OP_ATTACH:
                    setFirstOut(firstOutFragments, lastInFragments, op.fragment);
                    break;
            }

            op = op.prev;
        }
    }

    /**
     * When custom fragment transitions are used, this sets up the state for each transition
     * and begins the transition. A different transition is started for each fragment container
     * and consists of up to 3 different transitions: the exit transition, a shared element
     * transition and an enter transition.
     *
     * <p>The exit transition operates against the leaf nodes of the first fragment
     * with a view that was removed. If no such fragment was removed, then no exit
     * transition is executed. The exit transition comes from the outgoing fragment.</p>
     *
     * <p>The enter transition operates against the last fragment that was added. If
     * that fragment does not have a view or no fragment was added, then no enter
     * transition is executed. The enter transition comes from the incoming fragment.</p>
     *
     * <p>The shared element transition operates against all views and comes either
     * from the outgoing fragment or the incoming fragment, depending on whether this
     * is going forward or popping the back stack. When going forward, the incoming
     * fragment's enter shared element transition is used, but when going back, the
     * outgoing fragment's return shared element transition is used. Shared element
     * transitions only operate if there is both an incoming and outgoing fragment.</p>
     *
     * @param firstOutFragments The list of first fragments to be removed, keyed on the
     *                          container ID.
     * @param lastInFragments The list of last fragments to be added, keyed on the
     *                        container ID.
     * @param isBack true if this is popping the back stack or false if this is a
     *               forward operation.
     * @return The TransitionState used to complete the operation of the transition
     * in {@link #setNameOverrides(android.app.BackStackRecord.TransitionState, java.util.ArrayList,
     * java.util.ArrayList)}.
     */
    private TransitionState beginTransition(SparseArray<Fragment> firstOutFragments,
            SparseArray<Fragment> lastInFragments, boolean isBack) {
        TransitionState state = new TransitionState();

        // Adding a non-existent target view makes sure that the transitions don't target
        // any views by default. They'll only target the views we tell add. If we don't
        // add any, then no views will be targeted.
        state.nonExistentView = new View(mManager.mHost.getContext());

        // Go over all leaving fragments.
        for (int i = 0; i < firstOutFragments.size(); i++) {
            int containerId = firstOutFragments.keyAt(i);
            configureTransitions(containerId, state, isBack, firstOutFragments,
                    lastInFragments);
        }

        // Now go over all entering fragments that didn't have a leaving fragment.
        for (int i = 0; i < lastInFragments.size(); i++) {
            int containerId = lastInFragments.keyAt(i);
            if (firstOutFragments.get(containerId) == null) {
                configureTransitions(containerId, state, isBack, firstOutFragments,
                        lastInFragments);
            }
        }
        return state;
    }

    private static Transition cloneTransition(Transition transition) {
        if (transition != null) {
            transition = transition.clone();
        }
        return transition;
    }

    private static Transition getEnterTransition(Fragment inFragment, boolean isBack) {
        if (inFragment == null) {
            return null;
        }
        return cloneTransition(isBack ? inFragment.getReenterTransition() :
                inFragment.getEnterTransition());
    }

    private static Transition getExitTransition(Fragment outFragment, boolean isBack) {
        if (outFragment == null) {
            return null;
        }
        return cloneTransition(isBack ? outFragment.getReturnTransition() :
                outFragment.getExitTransition());
    }

    private static TransitionSet getSharedElementTransition(Fragment inFragment,
            Fragment outFragment, boolean isBack) {
        if (inFragment == null || outFragment == null) {
            return null;
        }
        Transition transition = cloneTransition(isBack
                ? outFragment.getSharedElementReturnTransition()
                : inFragment.getSharedElementEnterTransition());
        if (transition == null) {
            return null;
        }
        TransitionSet transitionSet = new TransitionSet();
        transitionSet.addTransition(transition);
        return transitionSet;
    }

    private static ArrayList<View> captureExitingViews(Transition exitTransition,
            Fragment outFragment, ArrayMap<String, View> namedViews, View nonExistentView) {
        ArrayList<View> viewList = null;
        if (exitTransition != null) {
            viewList = new ArrayList<View>();
            View root = outFragment.getView();
            root.captureTransitioningViews(viewList);
            if (namedViews != null) {
                viewList.removeAll(namedViews.values());
            }
            if (!viewList.isEmpty()) {
                viewList.add(nonExistentView);
                addTargets(exitTransition, viewList);
            }
        }
        return viewList;
    }

    private ArrayMap<String, View> remapSharedElements(TransitionState state, Fragment outFragment,
            boolean isBack) {
        ArrayMap<String, View> namedViews = new ArrayMap<String, View>();
        if (mSharedElementSourceNames != null) {
            outFragment.getView().findNamedViews(namedViews);
            if (isBack) {
                namedViews.retainAll(mSharedElementTargetNames);
            } else {
                namedViews = remapNames(mSharedElementSourceNames, mSharedElementTargetNames,
                        namedViews);
            }
        }

        if (isBack) {
            outFragment.mEnterTransitionCallback.onMapSharedElements(
                    mSharedElementTargetNames, namedViews);
            setBackNameOverrides(state, namedViews, false);
        } else {
            outFragment.mExitTransitionCallback.onMapSharedElements(
                    mSharedElementTargetNames, namedViews);
            setNameOverrides(state, namedViews, false);
        }

        return namedViews;
    }

    /**
     * Prepares the enter transition by adding a non-existent view to the transition's target list
     * and setting it epicenter callback. By adding a non-existent view to the target list,
     * we can prevent any view from being targeted at the beginning of the transition.
     * We will add to the views before the end state of the transition is captured so that the
     * views will appear. At the start of the transition, we clear the list of targets so that
     * we can restore the state of the transition and use it again.
     *
     * <p>The shared element transition maps its shared elements immediately prior to
     * capturing the final state of the Transition.</p>
     */
    private ArrayList<View> addTransitionTargets(final TransitionState state,
            final Transition enterTransition, final TransitionSet sharedElementTransition,
            final Transition exitTransition, final Transition overallTransition,
            final View container, final Fragment inFragment, final Fragment outFragment,
            final ArrayList<View> hiddenFragmentViews, final boolean isBack,
            final ArrayList<View> sharedElementTargets) {
        if (enterTransition == null && sharedElementTransition == null &&
                overallTransition == null) {
            return null;
        }
        final ArrayList<View> enteringViews = new ArrayList<View>();
        container.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        container.getViewTreeObserver().removeOnPreDrawListener(this);

                        // Don't include any newly-hidden fragments in the transition.
                        if (inFragment != null) {
                            excludeHiddenFragments(hiddenFragmentViews, inFragment.mContainerId,
                                    overallTransition);
                        }

                        ArrayMap<String, View> namedViews = null;
                        if (sharedElementTransition != null) {
                            namedViews = mapSharedElementsIn(state, isBack, inFragment);
                            removeTargets(sharedElementTransition, sharedElementTargets);
                            // keep the nonExistentView as excluded so the list doesn't get emptied
                            sharedElementTargets.remove(state.nonExistentView);
                            excludeViews(exitTransition, sharedElementTransition,
                                    sharedElementTargets, false);
                            excludeViews(enterTransition, sharedElementTransition,
                                    sharedElementTargets, false);

                            setSharedElementTargets(sharedElementTransition,
                                    state.nonExistentView, namedViews, sharedElementTargets);

                            setEpicenterIn(namedViews, state);

                            callSharedElementEnd(state, inFragment, outFragment, isBack,
                                    namedViews);
                        }

                        if (enterTransition != null) {
                            enterTransition.removeTarget(state.nonExistentView);
                            View view = inFragment.getView();
                            if (view != null) {
                                view.captureTransitioningViews(enteringViews);
                                if (namedViews != null) {
                                    enteringViews.removeAll(namedViews.values());
                                }
                                enteringViews.add(state.nonExistentView);
                                // We added this earlier to prevent any views being targeted.
                                addTargets(enterTransition, enteringViews);
                            }
                            setSharedElementEpicenter(enterTransition, state);
                        }

                        excludeViews(exitTransition, enterTransition, enteringViews, true);
                        excludeViews(exitTransition, sharedElementTransition, sharedElementTargets,
                                true);
                        excludeViews(enterTransition, sharedElementTransition, sharedElementTargets,
                                true);
                        return true;
                    }
                });
        return enteringViews;
    }

    private void callSharedElementEnd(TransitionState state, Fragment inFragment,
            Fragment outFragment, boolean isBack, ArrayMap<String, View> namedViews) {
        SharedElementCallback sharedElementCallback = isBack ?
                outFragment.mEnterTransitionCallback :
                inFragment.mEnterTransitionCallback;
        ArrayList<String> names = new ArrayList<String>(namedViews.keySet());
        ArrayList<View> views = new ArrayList<View>(namedViews.values());
        sharedElementCallback.onSharedElementEnd(names, views, null);
    }

    private void setEpicenterIn(ArrayMap<String, View> namedViews, TransitionState state) {
        if (mSharedElementTargetNames != null && !namedViews.isEmpty()) {
            // now we know the epicenter of the entering transition.
            View epicenter = namedViews
                    .get(mSharedElementTargetNames.get(0));
            if (epicenter != null) {
                state.enteringEpicenterView = epicenter;
            }
        }
    }

    private ArrayMap<String, View> mapSharedElementsIn(TransitionState state,
            boolean isBack, Fragment inFragment) {
        // Now map the shared elements in the incoming fragment
        ArrayMap<String, View> namedViews = mapEnteringSharedElements(state, inFragment, isBack);

        // remap shared elements and set the name mapping used
        // in the shared element transition.
        if (isBack) {
            inFragment.mExitTransitionCallback.onMapSharedElements(
                    mSharedElementTargetNames, namedViews);
            setBackNameOverrides(state, namedViews, true);
        } else {
            inFragment.mEnterTransitionCallback.onMapSharedElements(
                    mSharedElementTargetNames, namedViews);
            setNameOverrides(state, namedViews, true);
        }
        return namedViews;
    }

    private static Transition mergeTransitions(Transition enterTransition,
            Transition exitTransition, Transition sharedElementTransition, Fragment inFragment,
            boolean isBack) {
        boolean overlap = true;
        if (enterTransition != null && exitTransition != null && inFragment != null) {
            overlap = isBack ? inFragment.getAllowReturnTransitionOverlap() :
                    inFragment.getAllowEnterTransitionOverlap();
        }

        // Wrap the transitions. Explicit targets like in enter and exit will cause the
        // views to be targeted regardless of excluded views. If that happens, then the
        // excluded fragments views (hidden fragments) will still be in the transition.

        Transition transition;
        if (overlap) {
            // Regular transition -- do it all together
            TransitionSet transitionSet = new TransitionSet();
            if (enterTransition != null) {
                transitionSet.addTransition(enterTransition);
            }
            if (exitTransition != null) {
                transitionSet.addTransition(exitTransition);
            }
            if (sharedElementTransition != null) {
                transitionSet.addTransition(sharedElementTransition);
            }
            transition = transitionSet;
        } else {
            // First do exit, then enter, but allow shared element transition to happen
            // during both.
            Transition staggered = null;
            if (exitTransition != null && enterTransition != null) {
                staggered = new TransitionSet()
                        .addTransition(exitTransition)
                        .addTransition(enterTransition)
                        .setOrdering(TransitionSet.ORDERING_SEQUENTIAL);
            } else if (exitTransition != null) {
                staggered = exitTransition;
            } else if (enterTransition != null) {
                staggered = enterTransition;
            }
            if (sharedElementTransition != null) {
                TransitionSet together = new TransitionSet();
                if (staggered != null) {
                    together.addTransition(staggered);
                }
                together.addTransition(sharedElementTransition);
                transition = together;
            } else {
                transition = staggered;
            }
        }
        return transition;
    }

    /**
     * Configures custom transitions for a specific fragment container.
     *
     * @param containerId The container ID of the fragments to configure the transition for.
     * @param state The Transition State keeping track of the executing transitions.
     * @param firstOutFragments The list of first fragments to be removed, keyed on the
     *                          container ID.
     * @param lastInFragments The list of last fragments to be added, keyed on the
     *                        container ID.
     * @param isBack true if this is popping the back stack or false if this is a
     *               forward operation.
     */
    private void configureTransitions(int containerId, TransitionState state, boolean isBack,
            SparseArray<Fragment> firstOutFragments, SparseArray<Fragment> lastInFragments) {
        ViewGroup sceneRoot = (ViewGroup) mManager.mContainer.onFindViewById(containerId);
        if (sceneRoot != null) {
            Fragment inFragment = lastInFragments.get(containerId);
            Fragment outFragment = firstOutFragments.get(containerId);

            Transition enterTransition = getEnterTransition(inFragment, isBack);
            TransitionSet sharedElementTransition =
                    getSharedElementTransition(inFragment, outFragment, isBack);
            Transition exitTransition = getExitTransition(outFragment, isBack);

            if (enterTransition == null && sharedElementTransition == null &&
                    exitTransition == null) {
                return; // no transitions!
            }
            if (enterTransition != null) {
                enterTransition.addTarget(state.nonExistentView);
            }
            ArrayMap<String, View> namedViews = null;
            ArrayList<View> sharedElementTargets = new ArrayList<View>();
            if (sharedElementTransition != null) {
                namedViews = remapSharedElements(state, outFragment, isBack);
                setSharedElementTargets(sharedElementTransition,
                        state.nonExistentView, namedViews, sharedElementTargets);

                // Notify the start of the transition.
                SharedElementCallback callback = isBack ?
                        outFragment.mEnterTransitionCallback :
                        inFragment.mEnterTransitionCallback;
                ArrayList<String> names = new ArrayList<String>(namedViews.keySet());
                ArrayList<View> views = new ArrayList<View>(namedViews.values());
                callback.onSharedElementStart(names, views, null);
            }

            ArrayList<View> exitingViews = captureExitingViews(exitTransition, outFragment,
                    namedViews, state.nonExistentView);
            if (exitingViews == null || exitingViews.isEmpty()) {
                exitTransition = null;
            }
            excludeViews(enterTransition, exitTransition, exitingViews, true);
            excludeViews(enterTransition, sharedElementTransition, sharedElementTargets, true);
            excludeViews(exitTransition, sharedElementTransition, sharedElementTargets, true);

            // Set the epicenter of the exit transition
            if (mSharedElementTargetNames != null && namedViews != null) {
                View epicenterView = namedViews.get(mSharedElementTargetNames.get(0));
                if (epicenterView != null) {
                    if (exitTransition != null) {
                        setEpicenter(exitTransition, epicenterView);
                    }
                    if (sharedElementTransition != null) {
                        setEpicenter(sharedElementTransition, epicenterView);
                    }
                }
            }

            Transition transition = mergeTransitions(enterTransition, exitTransition,
                    sharedElementTransition, inFragment, isBack);

            if (transition != null) {
                ArrayList<View> hiddenFragments = new ArrayList<View>();
                ArrayList<View> enteringViews = addTransitionTargets(state, enterTransition,
                        sharedElementTransition, exitTransition, transition, sceneRoot, inFragment,
                        outFragment, hiddenFragments, isBack, sharedElementTargets);

                transition.setNameOverrides(state.nameOverrides);
                // We want to exclude hidden views later, so we need a non-null list in the
                // transition now.
                transition.excludeTarget(state.nonExistentView, true);
                // Now exclude all currently hidden fragments.
                excludeHiddenFragments(hiddenFragments, containerId, transition);
                TransitionManager.beginDelayedTransition(sceneRoot, transition);
                // Remove the view targeting after the transition starts
                removeTargetedViewsFromTransitions(sceneRoot, state.nonExistentView,
                        enterTransition, enteringViews, exitTransition, exitingViews,
                        sharedElementTransition, sharedElementTargets, transition,
                        hiddenFragments);
            }
        }
    }

    /**
     * Finds all children of the shared elements and sets the wrapping TransitionSet
     * targets to point to those. It also limits transitions that have no targets to the
     * specific shared elements. This allows developers to target child views of the
     * shared elements specifically, but this doesn't happen by default.
     */
    private static void setSharedElementTargets(TransitionSet transition,
            View nonExistentView, ArrayMap<String, View> namedViews,
            ArrayList<View> sharedElementTargets) {
        sharedElementTargets.clear();
        sharedElementTargets.addAll(namedViews.values());

        final List<View> views = transition.getTargets();
        views.clear();
        final int count = sharedElementTargets.size();
        for (int i = 0; i < count; i++) {
            final View view = sharedElementTargets.get(i);
            bfsAddViewChildren(views, view);
        }
        sharedElementTargets.add(nonExistentView);
        addTargets(transition, sharedElementTargets);
    }

    /**
     * Uses a breadth-first scheme to add startView and all of its children to views.
     * It won't add a child if it is already in views.
     */
    private static void bfsAddViewChildren(final List<View> views, final View startView) {
        final int startIndex = views.size();
        if (containedBeforeIndex(views, startView, startIndex)) {
            return; // This child is already in the list, so all its children are also.
        }
        views.add(startView);
        for (int index = startIndex; index < views.size(); index++) {
            final View view = views.get(index);
            if (view instanceof ViewGroup) {
                ViewGroup viewGroup = (ViewGroup) view;
                final int childCount =  viewGroup.getChildCount();
                for (int childIndex = 0; childIndex < childCount; childIndex++) {
                    final View child = viewGroup.getChildAt(childIndex);
                    if (!containedBeforeIndex(views, child, startIndex)) {
                        views.add(child);
                    }
                }
            }
        }
    }

    /**
     * Does a linear search through views for view, limited to maxIndex.
     */
    private static boolean containedBeforeIndex(final List<View> views, final View view,
            final int maxIndex) {
        for (int i = 0; i < maxIndex; i++) {
            if (views.get(i) == view) {
                return true;
            }
        }
        return false;
    }

    private static void excludeViews(Transition transition, Transition fromTransition,
            ArrayList<View> views, boolean exclude) {
        if (transition != null) {
            final int viewCount = fromTransition == null ? 0 : views.size();
            for (int i = 0; i < viewCount; i++) {
                transition.excludeTarget(views.get(i), exclude);
            }
        }
    }

    /**
     * After the transition has started, remove all targets that we added to the transitions
     * so that the transitions are left in a clean state.
     */
    private void removeTargetedViewsFromTransitions(
            final ViewGroup sceneRoot, final View nonExistingView,
            final Transition enterTransition, final ArrayList<View> enteringViews,
            final Transition exitTransition, final ArrayList<View> exitingViews,
            final Transition sharedElementTransition, final ArrayList<View> sharedElementTargets,
            final Transition overallTransition, final ArrayList<View> hiddenViews) {
        if (overallTransition != null) {
            sceneRoot.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    sceneRoot.getViewTreeObserver().removeOnPreDrawListener(this);
                    if (enterTransition != null) {
                        removeTargets(enterTransition, enteringViews);
                        excludeViews(enterTransition, exitTransition, exitingViews, false);
                        excludeViews(enterTransition, sharedElementTransition, sharedElementTargets,
                                false);
                    }
                    if (exitTransition != null) {
                        removeTargets(exitTransition, exitingViews);
                        excludeViews(exitTransition, enterTransition, enteringViews, false);
                        excludeViews(exitTransition, sharedElementTransition, sharedElementTargets,
                                false);
                    }
                    if (sharedElementTransition != null) {
                        removeTargets(sharedElementTransition, sharedElementTargets);
                    }
                    int numViews = hiddenViews.size();
                    for (int i = 0; i < numViews; i++) {
                        overallTransition.excludeTarget(hiddenViews.get(i), false);
                    }
                    overallTransition.excludeTarget(nonExistingView, false);
                    return true;
                }
            });
        }
    }

    /**
     * This method removes the views from transitions that target ONLY those views.
     * The views list should match those added in addTargets and should contain
     * one view that is not in the view hierarchy (state.nonExistentView).
     */
    public static void removeTargets(Transition transition, ArrayList<View> views) {
        if (transition instanceof TransitionSet) {
            TransitionSet set = (TransitionSet) transition;
            int numTransitions = set.getTransitionCount();
            for (int i = 0; i < numTransitions; i++) {
                Transition child = set.getTransitionAt(i);
                removeTargets(child, views);
            }
        } else if (!hasSimpleTarget(transition)) {
            List<View> targets = transition.getTargets();
            if (targets != null && targets.size() == views.size() &&
                    targets.containsAll(views)) {
                // We have an exact match. We must have added these earlier in addTargets
                for (int i = views.size() - 1; i >= 0; i--) {
                    transition.removeTarget(views.get(i));
                }
            }
        }
    }

    /**
     * This method adds views as targets to the transition, but only if the transition
     * doesn't already have a target. It is best for views to contain one View object
     * that does not exist in the view hierarchy (state.nonExistentView) so that
     * when they are removed later, a list match will suffice to remove the targets.
     * Otherwise, if you happened to have targeted the exact views for the transition,
     * the removeTargets call will remove them unexpectedly.
     */
    public static void addTargets(Transition transition, ArrayList<View> views) {
        if (transition instanceof TransitionSet) {
            TransitionSet set = (TransitionSet) transition;
            int numTransitions = set.getTransitionCount();
            for (int i = 0; i < numTransitions; i++) {
                Transition child = set.getTransitionAt(i);
                addTargets(child, views);
            }
        } else if (!hasSimpleTarget(transition)) {
            List<View> targets = transition.getTargets();
            if (isNullOrEmpty(targets)) {
                // We can just add the target views
                int numViews = views.size();
                for (int i = 0; i < numViews; i++) {
                    transition.addTarget(views.get(i));
                }
            }
        }
    }

    private static boolean hasSimpleTarget(Transition transition) {
        return !isNullOrEmpty(transition.getTargetIds()) ||
                !isNullOrEmpty(transition.getTargetNames()) ||
                !isNullOrEmpty(transition.getTargetTypes());
    }

    private static boolean isNullOrEmpty(List list) {
        return list == null || list.isEmpty();
    }

    /**
     * Remaps a name-to-View map, substituting different names for keys.
     *
     * @param inMap A list of keys found in the map, in the order in toGoInMap
     * @param toGoInMap A list of keys to use for the new map, in the order of inMap
     * @param namedViews The current mapping
     * @return a new Map after it has been mapped with the new names as keys.
     */
    private static ArrayMap<String, View> remapNames(ArrayList<String> inMap,
            ArrayList<String> toGoInMap, ArrayMap<String, View> namedViews) {
        ArrayMap<String, View> remappedViews = new ArrayMap<String, View>();
        if (!namedViews.isEmpty()) {
            int numKeys = inMap.size();
            for (int i = 0; i < numKeys; i++) {
                View view = namedViews.get(inMap.get(i));

                if (view != null) {
                    remappedViews.put(toGoInMap.get(i), view);
                }
            }
        }
        return remappedViews;
    }

    /**
     * Maps shared elements to views in the entering fragment.
     *
     * @param state The transition State as returned from {@link #beginTransition(
     * android.util.SparseArray, android.util.SparseArray, boolean)}.
     * @param inFragment The last fragment to be added.
     * @param isBack true if this is popping the back stack or false if this is a
     *               forward operation.
     */
    private ArrayMap<String, View> mapEnteringSharedElements(TransitionState state,
            Fragment inFragment, boolean isBack) {
        ArrayMap<String, View> namedViews = new ArrayMap<String, View>();
        View root = inFragment.getView();
        if (root != null) {
            if (mSharedElementSourceNames != null) {
                root.findNamedViews(namedViews);
                if (isBack) {
                    namedViews = remapNames(mSharedElementSourceNames,
                            mSharedElementTargetNames, namedViews);
                } else {
                    namedViews.retainAll(mSharedElementTargetNames);
                }
            }
        }
        return namedViews;
    }

    private void excludeHiddenFragments(final ArrayList<View> hiddenFragmentViews, int containerId,
            Transition transition) {
        if (mManager.mAdded != null) {
            for (int i = 0; i < mManager.mAdded.size(); i++) {
                Fragment fragment = mManager.mAdded.get(i);
                if (fragment.mView != null && fragment.mContainer != null &&
                        fragment.mContainerId == containerId) {
                    if (fragment.mHidden) {
                        if (!hiddenFragmentViews.contains(fragment.mView)) {
                            transition.excludeTarget(fragment.mView, true);
                            hiddenFragmentViews.add(fragment.mView);
                        }
                    } else {
                        transition.excludeTarget(fragment.mView, false);
                        hiddenFragmentViews.remove(fragment.mView);
                    }
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
                if (mEpicenter == null && state.enteringEpicenterView != null) {
                    mEpicenter = new Rect();
                    state.enteringEpicenterView.getBoundsOnScreen(mEpicenter);
                }
                return mEpicenter;
            }
        });
    }

    public TransitionState popFromBackStack(boolean doStateMove, TransitionState state,
            SparseArray<Fragment> firstOutFragments, SparseArray<Fragment> lastInFragments) {
        if (FragmentManagerImpl.DEBUG) {
            Log.v(TAG, "popFromBackStack: " + this);
            LogWriter logw = new LogWriter(Log.VERBOSE, TAG);
            PrintWriter pw = new FastPrintWriter(logw, false, 1024);
            dump("  ", null, pw, null);
            pw.flush();
        }

        if (mManager.mCurState >= Fragment.CREATED) {
            if (state == null) {
                if (firstOutFragments.size() != 0 || lastInFragments.size() != 0) {
                    state = beginTransition(firstOutFragments, lastInFragments, true);
                }
            } else if (!doStateMove) {
                setNameOverrides(state, mSharedElementTargetNames, mSharedElementSourceNames);
            }
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
            state = null;
        }

        if (mIndex >= 0) {
            mManager.freeBackStackIndex(mIndex);
            mIndex = -1;
        }
        return state;
    }

    private static void setNameOverride(ArrayMap<String, String> overrides,
            String source, String target) {
        if (source != null && target != null && !source.equals(target)) {
            for (int index = 0; index < overrides.size(); index++) {
                if (source.equals(overrides.valueAt(index))) {
                    overrides.setValueAt(index, target);
                    return;
                }
            }
            overrides.put(source, target);
        }
    }

    private static void setNameOverrides(TransitionState state, ArrayList<String> sourceNames,
            ArrayList<String> targetNames) {
        if (sourceNames != null && targetNames != null) {
            for (int i = 0; i < sourceNames.size(); i++) {
                String source = sourceNames.get(i);
                String target = targetNames.get(i);
                setNameOverride(state.nameOverrides, source, target);
            }
        }
    }

    private void setBackNameOverrides(TransitionState state, ArrayMap<String, View> namedViews,
            boolean isEnd) {
        int targetCount = mSharedElementTargetNames == null ? 0 : mSharedElementTargetNames.size();
        int sourceCount = mSharedElementSourceNames == null ? 0 : mSharedElementSourceNames.size();
        final int count = Math.min(targetCount, sourceCount);
        for (int i = 0; i < count; i++) {
            String source = mSharedElementSourceNames.get(i);
            String originalTarget = mSharedElementTargetNames.get(i);
            View view = namedViews.get(originalTarget);
            if (view != null) {
                String target = view.getTransitionName();
                if (isEnd) {
                    setNameOverride(state.nameOverrides, source, target);
                } else {
                    setNameOverride(state.nameOverrides, target, source);
                }
            }
        }
    }

    private void setNameOverrides(TransitionState state, ArrayMap<String, View> namedViews,
            boolean isEnd) {
        int count = namedViews == null ? 0 : namedViews.size();
        for (int i = 0; i < count; i++) {
            String source = namedViews.keyAt(i);
            String target = namedViews.valueAt(i).getTransitionName();
            if (isEnd) {
                setNameOverride(state.nameOverrides, source, target);
            } else {
                setNameOverride(state.nameOverrides, target, source);
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
        public ArrayMap<String, String> nameOverrides = new ArrayMap<String, String>();
        public View enteringEpicenterView;
        public View nonExistentView;
    }
}
