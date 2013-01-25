package android.os;

import android.util.Log;

import java.util.Arrays;

/**
 * Describes the source of some work that may be done by someone else.
 * Currently the public representation of what a work source is is not
 * defined; this is an opaque container.
 */
public class WorkSource implements Parcelable {
    static final String TAG = "WorkSource";
    static final boolean DEBUG = false;

    int mNum;
    int[] mUids;
    String[] mNames;

    /**
     * Internal statics to avoid object allocations in some operations.
     * The WorkSource object itself is not thread safe, but we need to
     * hold sTmpWorkSource lock while working with these statics.
     */
    static final WorkSource sTmpWorkSource = new WorkSource(0);
    /**
     * For returning newbie work from a modification operation.
     */
    static WorkSource sNewbWork;
    /**
     * For returning gone work form a modification operation.
     */
    static WorkSource sGoneWork;

    /**
     * Create an empty work source.
     */
    public WorkSource() {
        mNum = 0;
    }

    /**
     * Create a new WorkSource that is a copy of an existing one.
     * If <var>orig</var> is null, an empty WorkSource is created.
     */
    public WorkSource(WorkSource orig) {
        if (orig == null) {
            mNum = 0;
            return;
        }
        mNum = orig.mNum;
        if (orig.mUids != null) {
            mUids = orig.mUids.clone();
            mNames = orig.mNames != null ? orig.mNames.clone() : null;
        } else {
            mUids = null;
            mNames = null;
        }
    }

    /** @hide */
    public WorkSource(int uid) {
        mNum = 1;
        mUids = new int[] { uid, 0 };
        mNames = null;
    }

    /** @hide */
    public WorkSource(int uid, String name) {
        if (name == null) {
            throw new NullPointerException("Name can't be null");
        }
        mNum = 1;
        mUids = new int[] { uid, 0 };
        mNames = new String[] { name, null };
    }

    WorkSource(Parcel in) {
        mNum = in.readInt();
        mUids = in.createIntArray();
        mNames = in.createStringArray();
    }

    /** @hide */
    public int size() {
        return mNum;
    }

    /** @hide */
    public int get(int index) {
        return mUids[index];
    }

    /** @hide */
    public String getName(int index) {
        return mNames != null ? mNames[index] : null;
    }

    /**
     * Clear this WorkSource to be empty.
     */
    public void clear() {
        mNum = 0;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof WorkSource && !diff((WorkSource)o);
    }

    @Override
    public int hashCode() {
        int result = 0;
        for (int i = 0; i < mNum; i++) {
            result = ((result << 4) | (result >>> 28)) ^ mUids[i];
        }
        if (mNames != null) {
            for (int i = 0; i < mNum; i++) {
                result = ((result << 4) | (result >>> 28)) ^ mNames[i].hashCode();
            }
        }
        return result;
    }

    /**
     * Compare this WorkSource with another.
     * @param other The WorkSource to compare against.
     * @return If there is a difference, true is returned.
     */
    public boolean diff(WorkSource other) {
        int N = mNum;
        if (N != other.mNum) {
            return true;
        }
        final int[] uids1 = mUids;
        final int[] uids2 = other.mUids;
        final String[] names1 = mNames;
        final String[] names2 = other.mNames;
        for (int i=0; i<N; i++) {
            if (uids1[i] != uids2[i]) {
                return true;
            }
            if (names1 != null && names2 != null && !names1[i].equals(names2[i])) {
                return true;
            }
        }
        return false;
    }

    /**
     * Replace the current contents of this work source with the given
     * work source.  If <var>other</var> is null, the current work source
     * will be made empty.
     */
    public void set(WorkSource other) {
        if (other == null) {
            mNum = 0;
            return;
        }
        mNum = other.mNum;
        if (other.mUids != null) {
            if (mUids != null && mUids.length >= mNum) {
                System.arraycopy(other.mUids, 0, mUids, 0, mNum);
            } else {
                mUids = other.mUids.clone();
            }
            if (other.mNames != null) {
                if (mNames != null && mNames.length >= mNum) {
                    System.arraycopy(other.mNames, 0, mNames, 0, mNum);
                } else {
                    mNames = other.mNames.clone();
                }
            } else {
                mNames = null;
            }
        } else {
            mUids = null;
            mNames = null;
        }
    }

    /** @hide */
    public void set(int uid) {
        mNum = 1;
        if (mUids == null) mUids = new int[2];
        mUids[0] = uid;
        mNames = null;
    }

    /** @hide */
    public void set(int uid, String name) {
        if (name == null) {
            throw new NullPointerException("Name can't be null");
        }
        mNum = 1;
        if (mUids == null) {
            mUids = new int[2];
            mNames = new String[2];
        }
        mUids[0] = uid;
        mNames[0] = name;
        mNames = null;
    }

    /** @hide */
    public WorkSource[] setReturningDiffs(WorkSource other) {
        synchronized (sTmpWorkSource) {
            sNewbWork = null;
            sGoneWork = null;
            updateLocked(other, true, true);
            if (sNewbWork != null || sGoneWork != null) {
                WorkSource[] diffs = new WorkSource[2];
                diffs[0] = sNewbWork;
                diffs[1] = sGoneWork;
                return diffs;
            }
            return null;
        }
    }

    /**
     * Merge the contents of <var>other</var> WorkSource in to this one.
     *
     * @param other The other WorkSource whose contents are to be merged.
     * @return Returns true if any new sources were added.
     */
    public boolean add(WorkSource other) {
        synchronized (sTmpWorkSource) {
            return updateLocked(other, false, false);
        }
    }

    /** @hide */
    public WorkSource addReturningNewbs(WorkSource other) {
        synchronized (sTmpWorkSource) {
            sNewbWork = null;
            updateLocked(other, false, true);
            return sNewbWork;
        }
    }

    /** @hide */
    public boolean add(int uid) {
        if (mNum <= 0) {
            mNames = null;
            insert(0, uid);
            return true;
        }
        if (mNames != null) {
            throw new IllegalArgumentException("Adding without name to named " + this);
        }
        int i = Arrays.binarySearch(mUids, 0, mNum, uid);
        if (DEBUG) Log.d(TAG, "Adding uid " + uid + " to " + this + ": binsearch res = " + i);
        if (i >= 0) {
            return false;
        }
        insert(-i-1, uid);
        return true;
    }

    /** @hide */
    public boolean add(int uid, String name) {
        if (mNum <= 0) {
            insert(0, uid, name);
            return true;
        }
        if (mNames == null) {
            throw new IllegalArgumentException("Adding name to unnamed " + this);
        }
        int i;
        for (i=0; i<mNum; i++) {
            if (mUids[i] > uid) {
                break;
            }
            if (mUids[i] == uid) {
                int diff = mNames[i].compareTo(name); 
                if (diff > 0) {
                    break;
                }
                if (diff == 0) {
                    return false;
                }
            }
        }
        insert(i, uid, name);
        return true;
    }

    /** @hide */
    public WorkSource addReturningNewbs(int uid) {
        synchronized (sTmpWorkSource) {
            sNewbWork = null;
            sTmpWorkSource.mUids[0] = uid;
            updateLocked(sTmpWorkSource, false, true);
            return sNewbWork;
        }
    }

    public boolean remove(WorkSource other) {
        if (mNum <= 0 || other.mNum <= 0) {
            return false;
        }
        if (mNames == null && other.mNames == null) {
            return removeUids(other);
        } else {
            if (mNames == null) {
                throw new IllegalArgumentException("Other " + other + " has names, but target "
                        + this + " does not");
            }
            if (other.mNames == null) {
                throw new IllegalArgumentException("Target " + this + " has names, but other "
                        + other + " does not");
            }
            return removeUidsAndNames(other);
        }
    }

    /** @hide */
    public WorkSource stripNames() {
        if (mNum <= 0) {
            return new WorkSource();
        }
        WorkSource result = new WorkSource();
        int lastUid = -1;
        for (int i=0; i<mNum; i++) {
            int uid = mUids[i];
            if (i == 0 || lastUid != uid) {
                result.add(uid);
            }
        }
        return result;
    }

    private boolean removeUids(WorkSource other) {
        int N1 = mNum;
        final int[] uids1 = mUids;
        final int N2 = other.mNum;
        final int[] uids2 = other.mUids;
        boolean changed = false;
        int i1 = 0, i2 = 0;
        if (DEBUG) Log.d(TAG, "Remove " + other + " from " + this);
        while (i1 < N1 && i2 < N2) {
            if (DEBUG) Log.d(TAG, "Step: target @ " + i1 + " of " + N1 + ", other @ " + i2
                    + " of " + N2);
            if (uids2[i2] == uids1[i1]) {
                if (DEBUG) Log.d(TAG, "i1=" + i1 + " i2=" + i2 + " N1=" + N1
                        + ": remove " + uids1[i1]);
                N1--;
                changed = true;
                if (i1 < N1) System.arraycopy(uids1, i1+1, uids1, i1, N1-i1);
                i2++;
            } else if (uids2[i2] > uids1[i1]) {
                if (DEBUG) Log.d(TAG, "i1=" + i1 + " i2=" + i2 + " N1=" + N1 + ": skip i1");
                i1++;
            } else {
                if (DEBUG) Log.d(TAG, "i1=" + i1 + " i2=" + i2 + " N1=" + N1 + ": skip i2");
                i2++;
            }
        }

        mNum = N1;

        return changed;
    }

    private boolean removeUidsAndNames(WorkSource other) {
        int N1 = mNum;
        final int[] uids1 = mUids;
        final String[] names1 = mNames;
        final int N2 = other.mNum;
        final int[] uids2 = other.mUids;
        final String[] names2 = other.mNames;
        boolean changed = false;
        int i1 = 0, i2 = 0;
        if (DEBUG) Log.d(TAG, "Remove " + other + " from " + this);
        while (i1 < N1 && i2 < N2) {
            if (DEBUG) Log.d(TAG, "Step: target @ " + i1 + " of " + N1 + ", other @ " + i2
                    + " of " + N2 + ": " + uids1[i1] + " " + names1[i1]);
            if (uids2[i2] == uids1[i1] && names2[i2].equals(names1[i1])) {
                if (DEBUG) Log.d(TAG, "i1=" + i1 + " i2=" + i2 + " N1=" + N1
                        + ": remove " + uids1[i1] + " " + names1[i1]);
                N1--;
                changed = true;
                if (i1 < N1) {
                    System.arraycopy(uids1, i1+1, uids1, i1, N1-i1);
                    System.arraycopy(names1, i1+1, names1, i1, N1-i1);
                }
                i2++;
            } else if (uids2[i2] > uids1[i1]
                    || (uids2[i2] == uids1[i1] && names2[i2].compareTo(names1[i1]) > 0)) {
                if (DEBUG) Log.d(TAG, "i1=" + i1 + " i2=" + i2 + " N1=" + N1 + ": skip i1");
                i1++;
            } else {
                if (DEBUG) Log.d(TAG, "i1=" + i1 + " i2=" + i2 + " N1=" + N1 + ": skip i2");
                i2++;
            }
        }

        mNum = N1;

        return changed;
    }

    private boolean updateLocked(WorkSource other, boolean set, boolean returnNewbs) {
        if (mNames == null && other.mNames == null) {
            return updateUidsLocked(other, set, returnNewbs);
        } else {
            if (mNum > 0 && mNames == null) {
                throw new IllegalArgumentException("Other " + other + " has names, but target "
                        + this + " does not");
            }
            if (other.mNum > 0 && other.mNames == null) {
                throw new IllegalArgumentException("Target " + this + " has names, but other "
                        + other + " does not");
            }
            return updateUidsAndNamesLocked(other, set, returnNewbs);
        }
    }

    private static WorkSource addWork(WorkSource cur, int newUid) {
        if (cur == null) {
            return new WorkSource(newUid);
        }
        cur.insert(cur.mNum, newUid);
        return cur;
    }

    private boolean updateUidsLocked(WorkSource other, boolean set, boolean returnNewbs) {
        int N1 = mNum;
        int[] uids1 = mUids;
        final int N2 = other.mNum;
        final int[] uids2 = other.mUids;
        boolean changed = false;
        int i1 = 0, i2 = 0;
        if (DEBUG) Log.d(TAG, "Update " + this + " with " + other + " set=" + set
                + " returnNewbs=" + returnNewbs);
        while (i1 < N1 || i2 < N2) {
            if (DEBUG) Log.d(TAG, "Step: target @ " + i1 + " of " + N1 + ", other @ " + i2
                    + " of " + N2);
            if (i1 >= N1 || (i2 < N2 && uids2[i2] < uids1[i1])) {
                // Need to insert a new uid.
                if (DEBUG) Log.d(TAG, "i1=" + i1 + " i2=" + i2 + " N1=" + N1
                        + ": insert " + uids2[i2]);
                changed = true;
                if (uids1 == null) {
                    uids1 = new int[4];
                    uids1[0] = uids2[i2];
                } else if (N1 >= uids1.length) {
                    int[] newuids = new int[(uids1.length*3)/2];
                    if (i1 > 0) System.arraycopy(uids1, 0, newuids, 0, i1);
                    if (i1 < N1) System.arraycopy(uids1, i1, newuids, i1+1, N1-i1);
                    uids1 = newuids;
                    uids1[i1] = uids2[i2];
                } else {
                    if (i1 < N1) System.arraycopy(uids1, i1, uids1, i1+1, N1-i1);
                    uids1[i1] = uids2[i2];
                }
                if (returnNewbs) {
                    sNewbWork = addWork(sNewbWork, uids2[i2]);
                }
                N1++;
                i1++;
                i2++;
            } else {
                if (!set) {
                    // Skip uids that already exist or are not in 'other'.
                    if (DEBUG) Log.d(TAG, "i1=" + i1 + " i2=" + i2 + " N1=" + N1 + ": skip");
                    if (i2 < N2 && uids2[i2] == uids1[i1]) {
                        i2++;
                    }
                    i1++;
                } else {
                    // Remove any uids that don't exist in 'other'.
                    int start = i1;
                    while (i1 < N1 && (i2 >= N2 || uids2[i2] > uids1[i1])) {
                        if (DEBUG) Log.d(TAG, "i1=" + i1 + " i2=" + i2 + ": remove " + uids1[i1]);
                        sGoneWork = addWork(sGoneWork, uids1[i1]);
                        i1++;
                    }
                    if (start < i1) {
                        System.arraycopy(uids1, i1, uids1, start, N1-i1);
                        N1 -= i1-start;
                        i1 = start;
                    }
                    // If there is a matching uid, skip it.
                    if (i1 < N1 && i2 < N2 && uids2[i2] == uids1[i1]) {
                        if (DEBUG) Log.d(TAG, "i1=" + i1 + " i2=" + i2 + " N1=" + N1 + ": skip");
                        i1++;
                        i2++;
                    }
                }
            }
        }

        mNum = N1;
        mUids = uids1;

        return changed;
    }

    /**
     * Returns 0 if equal, negative if 'this' is before 'other', positive if 'this' is after 'other'.
     */
    private int compare(WorkSource other, int i1, int i2) {
        final int diff = mUids[i1] - other.mUids[i2];
        if (diff != 0) {
            return diff;
        }
        return mNames[i1].compareTo(other.mNames[i2]);
    }

    private static WorkSource addWork(WorkSource cur, int newUid, String newName) {
        if (cur == null) {
            return new WorkSource(newUid, newName);
        }
        cur.insert(cur.mNum, newUid, newName);
        return cur;
    }

    private boolean updateUidsAndNamesLocked(WorkSource other, boolean set, boolean returnNewbs) {
        final int N2 = other.mNum;
        final int[] uids2 = other.mUids;
        String[] names2 = other.mNames;
        boolean changed = false;
        int i1 = 0, i2 = 0;
        if (DEBUG) Log.d(TAG, "Update " + this + " with " + other + " set=" + set
                + " returnNewbs=" + returnNewbs);
        while (i1 < mNum || i2 < N2) {
            if (DEBUG) Log.d(TAG, "Step: target @ " + i1 + " of " + mNum + ", other @ " + i2
                    + " of " + N2);
            int diff = -1;
            if (i1 >= mNum || (i2 < N2 && (diff=compare(other, i1, i2)) > 0)) {
                // Need to insert a new uid.
                changed = true;
                if (DEBUG) Log.d(TAG, "i1=" + i1 + " i2=" + i2 + " N1=" + mNum
                        + ": insert " + uids2[i2] + " " + names2[i2]);
                insert(i1, uids2[i2], names2[i2]);
                if (returnNewbs) {
                    sNewbWork = addWork(sNewbWork, uids2[i2], names2[i2]);
                }
                i1++;
                i2++;
            } else {
                if (!set) {
                    if (DEBUG) Log.d(TAG, "i1=" + i1 + " i2=" + i2 + " N1=" + mNum + ": skip");
                    if (i2 < N2 && diff == 0) {
                        i2++;
                    }
                    i1++;
                } else {
                    // Remove any uids that don't exist in 'other'.
                    int start = i1;
                    while (diff < 0) {
                        if (DEBUG) Log.d(TAG, "i1=" + i1 + " i2=" + i2 + ": remove " + mUids[i1]
                                + " " + mNames[i1]);
                        sGoneWork = addWork(sGoneWork, mUids[i1], mNames[i1]);
                        i1++;
                        if (i1 >= mNum) {
                            break;
                        }
                        diff = i2 < N2 ? compare(other, i1, i2) : -1;
                    }
                    if (start < i1) {
                        System.arraycopy(mUids, i1, mUids, start, mNum-i1);
                        System.arraycopy(mNames, i1, mNames, start, mNum-i1);
                        mNum -= i1-start;
                        i1 = start;
                    }
                    // If there is a matching uid, skip it.
                    if (i1 < mNum && diff == 0) {
                        if (DEBUG) Log.d(TAG, "i1=" + i1 + " i2=" + i2 + " N1=" + mNum + ": skip");
                        i1++;
                        i2++;
                    }
                }
            }
        }

        return changed;
    }

    private void insert(int index, int uid)  {
        if (DEBUG) Log.d(TAG, "Insert in " + this + " @ " + index + " uid " + uid);
        if (mUids == null) {
            mUids = new int[4];
            mUids[0] = uid;
            mNum = 1;
        } else if (mNum >= mUids.length) {
            int[] newuids = new int[(mNum*3)/2];
            if (index > 0) {
                System.arraycopy(mUids, 0, newuids, 0, index);
            }
            if (index < mNum) {
                System.arraycopy(mUids, index, newuids, index+1, mNum-index);
            }
            mUids = newuids;
            mUids[index] = uid;
            mNum++;
        } else {
            if (index < mNum) {
                System.arraycopy(mUids, index, mUids, index+1, mNum-index);
            }
            mUids[index] = uid;
            mNum++;
        }
    }

    private void insert(int index, int uid, String name)  {
        if (mUids == null) {
            mUids = new int[4];
            mUids[0] = uid;
            mNames = new String[4];
            mNames[0] = name;
            mNum = 1;
        } else if (mNum >= mUids.length) {
            int[] newuids = new int[(mNum*3)/2];
            String[] newnames = new String[(mNum*3)/2];
            if (index > 0) {
                System.arraycopy(mUids, 0, newuids, 0, index);
                System.arraycopy(mNames, 0, newnames, 0, index);
            }
            if (index < mNum) {
                System.arraycopy(mUids, index, newuids, index+1, mNum-index);
                System.arraycopy(mNames, index, newnames, index+1, mNum-index);
            }
            mUids = newuids;
            mNames = newnames;
            mUids[index] = uid;
            mNames[index] = name;
            mNum++;
        } else {
            if (index < mNum) {
                System.arraycopy(mUids, index, mUids, index+1, mNum-index);
                System.arraycopy(mNames, index, mNames, index+1, mNum-index);
            }
            mUids[index] = uid;
            mNames[index] = name;
            mNum++;
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mNum);
        dest.writeIntArray(mUids);
        dest.writeStringArray(mNames);
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder();
        result.append("WorkSource{");
        for (int i = 0; i < mNum; i++) {
            if (i != 0) {
                result.append(", ");
            }
            result.append(mUids[i]);
            if (mNames != null) {
                result.append(" ");
                result.append(mNames[i]);
            }
        }
        result.append("}");
        return result.toString();
    }

    public static final Parcelable.Creator<WorkSource> CREATOR
            = new Parcelable.Creator<WorkSource>() {
        public WorkSource createFromParcel(Parcel in) {
            return new WorkSource(in);
        }
        public WorkSource[] newArray(int size) {
            return new WorkSource[size];
        }
    };
}
