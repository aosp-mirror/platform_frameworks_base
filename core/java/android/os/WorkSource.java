package android.os;

/**
 * Describes the source of some work that may be done by someone else.
 * Currently the public representation of what a work source is is not
 * defined; this is an opaque container.
 */
public class WorkSource implements Parcelable {
    int mNum;
    int[] mUids;

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
        } else {
            mUids = null;
        }
    }

    /** @hide */
    public WorkSource(int uid) {
        mNum = 1;
        mUids = new int[] { uid, 0 };
    }

    WorkSource(Parcel in) {
        mNum = in.readInt();
        mUids = in.createIntArray();
    }

    /** @hide */
    public int size() {
        return mNum;
    }

    /** @hide */
    public int get(int index) {
        return mUids[index];
    }

    /**
     * Clear this WorkSource to be empty.
     */
    public void clear() {
        mNum = 0;
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
        for (int i=0; i<N; i++) {
            if (uids1[i] != uids2[i]) {
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
        } else {
            mUids = null;
        }
    }

    /** @hide */
    public void set(int uid) {
        mNum = 1;
        if (mUids == null) mUids = new int[2];
        mUids[0] = uid;
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
        synchronized (sTmpWorkSource) {
            sTmpWorkSource.mUids[0] = uid;
            return updateLocked(sTmpWorkSource, false, false);
        }
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
        int N1 = mNum;
        final int[] uids1 = mUids;
        final int N2 = other.mNum;
        final int[] uids2 = other.mUids;
        boolean changed = false;
        int i1 = 0;
        for (int i2=0; i2<N2 && i1<N1; i2++) {
            if (uids2[i2] == uids1[i1]) {
                N1--;
                if (i1 < N1) System.arraycopy(uids1, i1+1, uids1, i1, N1-i1);
            }
            while (i1 < N1 && uids2[i2] > uids1[i1]) {
                i1++;
            }
        }

        mNum = N1;

        return changed;
    }

    private boolean updateLocked(WorkSource other, boolean set, boolean returnNewbs) {
        int N1 = mNum;
        int[] uids1 = mUids;
        final int N2 = other.mNum;
        final int[] uids2 = other.mUids;
        boolean changed = false;
        int i1 = 0;
        for (int i2=0; i2<N2; i2++) {
            if (i1 >= N1 || uids2[i2] < uids1[i1]) {
                // Need to insert a new uid.
                changed = true;
                if (uids1 == null) {
                    uids1 = new int[4];
                    uids1[0] = uids2[i2];
                } else if (i1 >= uids1.length) {
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
                    if (sNewbWork == null) {
                        sNewbWork = new WorkSource(uids2[i2]);
                    } else {
                        sNewbWork.addLocked(uids2[i2]);
                    }
                }
                N1++;
                i1++;
            } else {
                if (!set) {
                    // Skip uids that already exist or are not in 'other'.
                    do {
                        i1++;
                    } while (i1 < N1 && uids2[i2] >= uids1[i1]);
                } else {
                    // Remove any uids that don't exist in 'other'.
                    int start = i1;
                    while (i1 < N1 && uids2[i2] > uids1[i1]) {
                        if (sGoneWork == null) {
                            sGoneWork = new WorkSource(uids1[i1]);
                        } else {
                            sGoneWork.addLocked(uids1[i1]);
                        }
                        i1++;
                    }
                    if (start < i1) {
                        System.arraycopy(uids1, i1, uids1, start, i1-start);
                        N1 -= i1-start;
                        i1 = start;
                    }
                    // If there is a matching uid, skip it.
                    if (i1 < N1 && uids2[i1] == uids1[i1]) {
                        i1++;
                    }
                }
            }
        }

        mNum = N1;
        mUids = uids1;

        return changed;
    }

    private void addLocked(int uid) {
        if (mUids == null) {
            mUids = new int[4];
            mUids[0] = uid;
            mNum = 1;
            return;
        }
        if (mNum >= mUids.length) {
            int[] newuids = new int[(mNum*3)/2];
            System.arraycopy(mUids, 0, newuids, 0, mNum);
            mUids = newuids;
        }

        mUids[mNum] = uid;
        mNum++;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mNum);
        dest.writeIntArray(mUids);
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
