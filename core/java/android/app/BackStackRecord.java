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

import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Log;
import android.util.LogWriter;
import android.view.View;

import com.android.internal.util.FastPrintWriter;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.reflect.Modifier;
import java.util.ArrayList;

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
    final boolean mReorderingAllowed;

    public BackStackState(FragmentManagerImpl fm, BackStackRecord bse) {
        final int numOps = bse.mOps.size();
        mOps = new int[numOps * 6];

        if (!bse.mAddToBackStack) {
            throw new IllegalStateException("Not on back stack");
        }

        int pos = 0;
        for (int opNum = 0; opNum < numOps; opNum++) {
            final BackStackRecord.Op op = bse.mOps.get(opNum);
            mOps[pos++] = op.cmd;
            mOps[pos++] = op.fragment != null ? op.fragment.mIndex : -1;
            mOps[pos++] = op.enterAnim;
            mOps[pos++] = op.exitAnim;
            mOps[pos++] = op.popEnterAnim;
            mOps[pos++] = op.popExitAnim;
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
        mReorderingAllowed = bse.mReorderingAllowed;
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
        mReorderingAllowed = in.readInt() != 0;
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
        bse.mReorderingAllowed = mReorderingAllowed;
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
        dest.writeInt(mReorderingAllowed ? 1 : 0);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<BackStackState> CREATOR
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
        FragmentManager.BackStackEntry, FragmentManagerImpl.OpGenerator {
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
    static final int OP_SET_PRIMARY_NAV = 8;
    static final int OP_UNSET_PRIMARY_NAV = 9;

    static final class Op {
        int cmd;
        Fragment fragment;
        int enterAnim;
        int exitAnim;
        int popEnterAnim;
        int popExitAnim;

        Op() {
        }

        Op(int cmd, Fragment fragment) {
            this.cmd = cmd;
            this.fragment = fragment;
        }
    }

    ArrayList<Op> mOps = new ArrayList<>();
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
    boolean mReorderingAllowed;

    ArrayList<Runnable> mCommitRunnables;

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

        if (!mOps.isEmpty()) {
            writer.print(prefix);
            writer.println("Operations:");
            String innerPrefix = prefix + "    ";
            final int numOps = mOps.size();
            for (int opNum = 0; opNum < numOps; opNum++) {
                final Op op = mOps.get(opNum);
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
                    case OP_SET_PRIMARY_NAV:
                        cmdStr="SET_PRIMARY_NAV";
                        break;
                    case OP_UNSET_PRIMARY_NAV:
                        cmdStr="UNSET_PRIMARY_NAV";
                        break;

                    default:
                        cmdStr = "cmd=" + op.cmd;
                        break;
                }
                writer.print(prefix);
                writer.print("  Op #");
                writer.print(opNum);
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
            }
        }
    }

    public BackStackRecord(FragmentManagerImpl manager) {
        mManager = manager;
        mReorderingAllowed = mManager.getTargetSdk() > Build.VERSION_CODES.N_MR1;
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
        if (mBreadCrumbTitleRes != 0 && mManager.mHost != null) {
            return mManager.mHost.getContext().getText(mBreadCrumbTitleRes);
        }
        return mBreadCrumbTitleText;
    }

    public CharSequence getBreadCrumbShortTitle() {
        if (mBreadCrumbShortTitleRes != 0 && mManager.mHost != null) {
            return mManager.mHost.getContext().getText(mBreadCrumbShortTitleRes);
        }
        return mBreadCrumbShortTitleText;
    }

    void addOp(Op op) {
        mOps.add(op);
        op.enterAnim = mEnterAnim;
        op.exitAnim = mExitAnim;
        op.popEnterAnim = mPopEnterAnim;
        op.popExitAnim = mPopExitAnim;
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
        if (mManager.getTargetSdk() > Build.VERSION_CODES.N_MR1) {
            final Class fragmentClass = fragment.getClass();
            final int modifiers = fragmentClass.getModifiers();
            if ((fragmentClass.isAnonymousClass() || !Modifier.isPublic(modifiers)
                    || (fragmentClass.isMemberClass() && !Modifier.isStatic(modifiers)))) {
                throw new IllegalStateException("Fragment " + fragmentClass.getCanonicalName()
                        + " must be a public static class to be  properly recreated from"
                        + " instance state.");
            }
        }
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

        addOp(new Op(opcmd, fragment));
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
        addOp(new Op(OP_REMOVE, fragment));

        return this;
    }

    public FragmentTransaction hide(Fragment fragment) {
        addOp(new Op(OP_HIDE, fragment));

        return this;
    }

    public FragmentTransaction show(Fragment fragment) {
        addOp(new Op(OP_SHOW, fragment));

        return this;
    }

    public FragmentTransaction detach(Fragment fragment) {
        addOp(new Op(OP_DETACH, fragment));

        return this;
    }

    public FragmentTransaction attach(Fragment fragment) {
        addOp(new Op(OP_ATTACH, fragment));

        return this;
    }

    public FragmentTransaction setPrimaryNavigationFragment(Fragment fragment) {
        addOp(new Op(OP_SET_PRIMARY_NAV, fragment));

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
        } else if (mSharedElementTargetNames.contains(name)) {
            throw new IllegalArgumentException("A shared element with the target name '"
                    + name + "' has already been added to the transaction.");
        } else if (mSharedElementSourceNames.contains(transitionName)) {
            throw new IllegalArgumentException("A shared element with the source name '"
                    + transitionName + " has already been added to the transaction.");
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
        final int numOps = mOps.size();
        for (int opNum = 0; opNum < numOps; opNum++) {
            final Op op = mOps.get(opNum);
            if (op.fragment != null) {
                op.fragment.mBackStackNesting += amt;
                if (FragmentManagerImpl.DEBUG) {
                    Log.v(TAG, "Bump nesting of "
                            + op.fragment + " to " + op.fragment.mBackStackNesting);
                }
            }
        }
    }

    @Override
    public FragmentTransaction runOnCommit(Runnable runnable) {
        if (runnable == null) {
            throw new IllegalArgumentException("runnable cannot be null");
        }
        disallowAddToBackStack();
        if (mCommitRunnables == null) {
            mCommitRunnables = new ArrayList<>();
        }
        mCommitRunnables.add(runnable);
        return this;
    }

    public void runOnCommitRunnables() {
        if (mCommitRunnables != null) {
            for (int i = 0, N = mCommitRunnables.size(); i < N; i++) {
                mCommitRunnables.get(i).run();
            }
            mCommitRunnables = null;
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

    @Override
    public FragmentTransaction setReorderingAllowed(boolean reorderingAllowed) {
        mReorderingAllowed = reorderingAllowed;
        return this;
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

    /**
     * Implementation of {@link android.app.FragmentManagerImpl.OpGenerator}.
     * This operation is added to the list of pending actions during {@link #commit()}, and
     * will be executed on the UI thread to run this FragmentTransaction.
     *
     * @param records Modified to add this BackStackRecord
     * @param isRecordPop Modified to add a false (this isn't a pop)
     * @return true always because the records and isRecordPop will always be changed
     */
    @Override
    public boolean generateOps(ArrayList<BackStackRecord> records, ArrayList<Boolean> isRecordPop) {
        if (FragmentManagerImpl.DEBUG) {
            Log.v(TAG, "Run: " + this);
        }

        records.add(this);
        isRecordPop.add(false);
        if (mAddToBackStack) {
            mManager.addBackStackState(this);
        }
        return true;
    }

    boolean interactsWith(int containerId) {
        final int numOps = mOps.size();
        for (int opNum = 0; opNum < numOps; opNum++) {
            final Op op = mOps.get(opNum);
            final int fragContainer = op.fragment != null ? op.fragment.mContainerId : 0;
            if (fragContainer != 0 && fragContainer == containerId) {
                return true;
            }
        }
        return false;
    }

    boolean interactsWith(ArrayList<BackStackRecord> records, int startIndex, int endIndex) {
        if (endIndex == startIndex) {
            return false;
        }
        final int numOps = mOps.size();
        int lastContainer = -1;
        for (int opNum = 0; opNum < numOps; opNum++) {
            final Op op = mOps.get(opNum);
            final int container = op.fragment != null ? op.fragment.mContainerId : 0;
            if (container != 0 && container != lastContainer) {
                lastContainer = container;
                for (int i = startIndex; i < endIndex; i++) {
                    BackStackRecord record = records.get(i);
                    final int numThoseOps = record.mOps.size();
                    for (int thoseOpIndex = 0; thoseOpIndex < numThoseOps; thoseOpIndex++) {
                        final Op thatOp = record.mOps.get(thoseOpIndex);
                        final int thatContainer = thatOp.fragment != null
                                ? thatOp.fragment.mContainerId : 0;
                        if (thatContainer == container) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * Executes the operations contained within this transaction. The Fragment states will only
     * be modified if optimizations are not allowed.
     */
    void executeOps() {
        final int numOps = mOps.size();
        for (int opNum = 0; opNum < numOps; opNum++) {
            final Op op = mOps.get(opNum);
            final Fragment f = op.fragment;
            if (f != null) {
                f.setNextTransition(mTransition, mTransitionStyle);
            }
            switch (op.cmd) {
                case OP_ADD:
                    f.setNextAnim(op.enterAnim);
                    mManager.addFragment(f, false);
                    break;
                case OP_REMOVE:
                    f.setNextAnim(op.exitAnim);
                    mManager.removeFragment(f);
                    break;
                case OP_HIDE:
                    f.setNextAnim(op.exitAnim);
                    mManager.hideFragment(f);
                    break;
                case OP_SHOW:
                    f.setNextAnim(op.enterAnim);
                    mManager.showFragment(f);
                    break;
                case OP_DETACH:
                    f.setNextAnim(op.exitAnim);
                    mManager.detachFragment(f);
                    break;
                case OP_ATTACH:
                    f.setNextAnim(op.enterAnim);
                    mManager.attachFragment(f);
                    break;
                case OP_SET_PRIMARY_NAV:
                    mManager.setPrimaryNavigationFragment(f);
                    break;
                case OP_UNSET_PRIMARY_NAV:
                    mManager.setPrimaryNavigationFragment(null);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown cmd: " + op.cmd);
            }
            if (!mReorderingAllowed && op.cmd != OP_ADD && f != null) {
                mManager.moveFragmentToExpectedState(f);
            }
        }
        if (!mReorderingAllowed) {
            // Added fragments are added at the end to comply with prior behavior.
            mManager.moveToState(mManager.mCurState, true);
        }
    }

    /**
     * Reverses the execution of the operations within this transaction. The Fragment states will
     * only be modified if optimizations are not allowed.
     *
     * @param moveToState {@code true} if added fragments should be moved to their final state
     *                    in unoptimized transactions
     */
    void executePopOps(boolean moveToState) {
        for (int opNum = mOps.size() - 1; opNum >= 0; opNum--) {
            final Op op = mOps.get(opNum);
            Fragment f = op.fragment;
            if (f != null) {
                f.setNextTransition(FragmentManagerImpl.reverseTransit(mTransition),
                        mTransitionStyle);
            }
            switch (op.cmd) {
                case OP_ADD:
                    f.setNextAnim(op.popExitAnim);
                    mManager.removeFragment(f);
                    break;
                case OP_REMOVE:
                    f.setNextAnim(op.popEnterAnim);
                    mManager.addFragment(f, false);
                    break;
                case OP_HIDE:
                    f.setNextAnim(op.popEnterAnim);
                    mManager.showFragment(f);
                    break;
                case OP_SHOW:
                    f.setNextAnim(op.popExitAnim);
                    mManager.hideFragment(f);
                    break;
                case OP_DETACH:
                    f.setNextAnim(op.popEnterAnim);
                    mManager.attachFragment(f);
                    break;
                case OP_ATTACH:
                    f.setNextAnim(op.popExitAnim);
                    mManager.detachFragment(f);
                    break;
                case OP_SET_PRIMARY_NAV:
                    mManager.setPrimaryNavigationFragment(null);
                    break;
                case OP_UNSET_PRIMARY_NAV:
                    mManager.setPrimaryNavigationFragment(f);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown cmd: " + op.cmd);
            }
            if (!mReorderingAllowed && op.cmd != OP_REMOVE && f != null) {
                mManager.moveFragmentToExpectedState(f);
            }
        }
        if (!mReorderingAllowed && moveToState) {
            mManager.moveToState(mManager.mCurState, true);
        }
    }

    /**
     * Expands all meta-ops into their more primitive equivalents. This must be called prior to
     * {@link #executeOps()} or any other call that operations on mOps for forward navigation.
     * It should not be called for pop/reverse navigation operations.
     *
     * <p>Removes all OP_REPLACE ops and replaces them with the proper add and remove
     * operations that are equivalent to the replace.</p>
     *
     * <p>Adds OP_UNSET_PRIMARY_NAV ops to match OP_SET_PRIMARY_NAV, OP_REMOVE and OP_DETACH
     * ops so that we can restore the old primary nav fragment later. Since callers call this
     * method in a loop before running ops from several transactions at once, the caller should
     * pass the return value from this method as the oldPrimaryNav parameter for the next call.
     * The first call in such a loop should pass the value of
     * {@link FragmentManager#getPrimaryNavigationFragment()}.</p>
     *
     * @param added Initialized to the fragments that are in the mManager.mAdded, this
     *              will be modified to contain the fragments that will be in mAdded
     *              after the execution ({@link #executeOps()}.
     * @param oldPrimaryNav The tracked primary navigation fragment as of the beginning of
     *                      this set of ops
     * @return the new oldPrimaryNav fragment after this record's ops would be run
     */
    @SuppressWarnings("ReferenceEquality")
    Fragment expandOps(ArrayList<Fragment> added, Fragment oldPrimaryNav) {
        for (int opNum = 0; opNum < mOps.size(); opNum++) {
            final Op op = mOps.get(opNum);
            switch (op.cmd) {
                case OP_ADD:
                case OP_ATTACH:
                    added.add(op.fragment);
                    break;
                case OP_REMOVE:
                case OP_DETACH: {
                    added.remove(op.fragment);
                    if (op.fragment == oldPrimaryNav) {
                        mOps.add(opNum, new Op(OP_UNSET_PRIMARY_NAV, op.fragment));
                        opNum++;
                        oldPrimaryNav = null;
                    }
                }
                break;
                case OP_REPLACE: {
                    final Fragment f = op.fragment;
                    final int containerId = f.mContainerId;
                    boolean alreadyAdded = false;
                    for (int i = added.size() - 1; i >= 0; i--) {
                        final Fragment old = added.get(i);
                        if (old.mContainerId == containerId) {
                            if (old == f) {
                                alreadyAdded = true;
                            } else {
                                // This is duplicated from above since we only make
                                // a single pass for expanding ops. Unset any outgoing primary nav.
                                if (old == oldPrimaryNav) {
                                    mOps.add(opNum, new Op(OP_UNSET_PRIMARY_NAV, old));
                                    opNum++;
                                    oldPrimaryNav = null;
                                }
                                final Op removeOp = new Op(OP_REMOVE, old);
                                removeOp.enterAnim = op.enterAnim;
                                removeOp.popEnterAnim = op.popEnterAnim;
                                removeOp.exitAnim = op.exitAnim;
                                removeOp.popExitAnim = op.popExitAnim;
                                mOps.add(opNum, removeOp);
                                added.remove(old);
                                opNum++;
                            }
                        }
                    }
                    if (alreadyAdded) {
                        mOps.remove(opNum);
                        opNum--;
                    } else {
                        op.cmd = OP_ADD;
                        added.add(f);
                    }
                }
                break;
                case OP_SET_PRIMARY_NAV: {
                    // It's ok if this is null, that means we will restore to no active
                    // primary navigation fragment on a pop.
                    mOps.add(opNum, new Op(OP_UNSET_PRIMARY_NAV, oldPrimaryNav));
                    opNum++;
                    // Will be set by the OP_SET_PRIMARY_NAV we inserted before when run
                    oldPrimaryNav = op.fragment;
                }
                break;
            }
        }
        return oldPrimaryNav;
    }

    /**
     * Removes fragments that are added or removed during a pop operation.
     *
     * @param added Initialized to the fragments that are in the mManager.mAdded, this
     *              will be modified to contain the fragments that will be in mAdded
     *              after the execution ({@link #executeOps()}.
     */
    void trackAddedFragmentsInPop(ArrayList<Fragment> added) {
        for (int opNum = 0; opNum < mOps.size(); opNum++) {
            final Op op = mOps.get(opNum);
            switch (op.cmd) {
                case OP_ADD:
                case OP_ATTACH:
                    added.remove(op.fragment);
                    break;
                case OP_REMOVE:
                case OP_DETACH:
                    added.add(op.fragment);
                    break;
            }
        }
    }

    boolean isPostponed() {
        for (int opNum = 0; opNum < mOps.size(); opNum++) {
            final Op op = mOps.get(opNum);
            if (isFragmentPostponed(op)) {
                return true;
            }
        }
        return false;
    }

    void setOnStartPostponedListener(Fragment.OnStartEnterTransitionListener listener) {
        for (int opNum = 0; opNum < mOps.size(); opNum++) {
            final Op op = mOps.get(opNum);
            if (isFragmentPostponed(op)) {
                op.fragment.setOnStartEnterTransitionListener(listener);
            }
        }
    }

    private static boolean isFragmentPostponed(Op op) {
        final Fragment fragment = op.fragment;
        return fragment != null && fragment.mAdded && fragment.mView != null && !fragment.mDetached
                && !fragment.mHidden && fragment.isPostponed();
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
        return mOps.isEmpty();
    }
}
