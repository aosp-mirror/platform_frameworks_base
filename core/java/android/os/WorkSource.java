package android.os;

import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.content.Context;
import android.os.WorkSourceProto;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.util.Log;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
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

    private ArrayList<WorkChain> mChains;

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
        mChains = null;
    }

    /**
     * Create a new WorkSource that is a copy of an existing one.
     * If <var>orig</var> is null, an empty WorkSource is created.
     */
    public WorkSource(WorkSource orig) {
        if (orig == null) {
            mNum = 0;
            mChains = null;
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

        if (orig.mChains != null) {
            // Make a copy of all WorkChains that exist on |orig| since they are mutable.
            mChains = new ArrayList<>(orig.mChains.size());
            for (WorkChain chain : orig.mChains) {
                mChains.add(new WorkChain(chain));
            }
        } else {
            mChains = null;
        }
    }

    /** @hide */
    @TestApi
    public WorkSource(int uid) {
        mNum = 1;
        mUids = new int[] { uid, 0 };
        mNames = null;
        mChains = null;
    }

    /** @hide */
    public WorkSource(int uid, String name) {
        if (name == null) {
            throw new NullPointerException("Name can't be null");
        }
        mNum = 1;
        mUids = new int[] { uid, 0 };
        mNames = new String[] { name, null };
        mChains = null;
    }

    WorkSource(Parcel in) {
        mNum = in.readInt();
        mUids = in.createIntArray();
        mNames = in.createStringArray();

        int numChains = in.readInt();
        if (numChains > 0) {
            mChains = new ArrayList<>(numChains);
            in.readParcelableList(mChains, WorkChain.class.getClassLoader());
        } else {
            mChains = null;
        }
    }

    /**
     * Whether system services should create {@code WorkChains} (wherever possible) in the place
     * of flat UID lists.
     *
     * @hide
     */
    public static boolean isChainedBatteryAttributionEnabled(Context context) {
        return Settings.Global.getInt(context.getContentResolver(),
                Global.CHAINED_BATTERY_ATTRIBUTION_ENABLED, 0) == 1;
    }

    /** @hide */
    @TestApi
    public int size() {
        return mNum;
    }

    /** @hide */
    @TestApi
    public int get(int index) {
        return mUids[index];
    }

    /**
     * Return the UID to which this WorkSource should be attributed to, i.e, the UID that
     * initiated the work and not the UID performing it. If the WorkSource has no UIDs, returns -1
     * instead.
     *
     * @hide
     */
    public int getAttributionUid() {
        if (isEmpty()) {
            return -1;
        }

        return mNum > 0 ? mUids[0] : mChains.get(0).getAttributionUid();
    }

    /** @hide */
    @TestApi
    public String getName(int index) {
        return mNames != null ? mNames[index] : null;
    }

    /**
     * Clear names from this WorkSource. Uids are left intact. WorkChains if any, are left
     * intact.
     *
     * <p>Useful when combining with another WorkSource that doesn't have names.
     * @hide
     */
    public void clearNames() {
        if (mNames != null) {
            mNames = null;
            // Clear out any duplicate uids now that we don't have names to disambiguate them.
            int destIndex = 1;
            int newNum = mNum;
            for (int sourceIndex = 1; sourceIndex < mNum; sourceIndex++) {
                if (mUids[sourceIndex] == mUids[sourceIndex - 1]) {
                    newNum--;
                } else {
                    mUids[destIndex] = mUids[sourceIndex];
                    destIndex++;
                }
            }
            mNum = newNum;
        }
    }

    /**
     * Clear this WorkSource to be empty.
     */
    public void clear() {
        mNum = 0;
        if (mChains != null) {
            mChains.clear();
        }
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof WorkSource) {
            WorkSource other = (WorkSource) o;

            if (diff(other)) {
                return false;
            }

            if (mChains != null && !mChains.isEmpty()) {
                return mChains.equals(other.mChains);
            } else {
                return other.mChains == null || other.mChains.isEmpty();
            }
        }

        return false;
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

        if (mChains != null) {
            result = ((result << 4) | (result >>> 28)) ^ mChains.hashCode();
        }

        return result;
    }

    /**
     * Compare this WorkSource with another.
     * @param other The WorkSource to compare against.
     * @return If there is a difference, true is returned.
     */
    // TODO: This is a public API so it cannot be renamed. Because it is used in several places,
    // we keep its semantics the same and ignore any differences in WorkChains (if any).
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
     * work source.  If {@code other} is null, the current work source
     * will be made empty.
     */
    public void set(WorkSource other) {
        if (other == null) {
            mNum = 0;
            if (mChains != null) {
                mChains.clear();
            }
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

        if (other.mChains != null) {
            if (mChains != null) {
                mChains.clear();
            } else {
                mChains = new ArrayList<>(other.mChains.size());
            }

            for (WorkChain chain : other.mChains) {
                mChains.add(new WorkChain(chain));
            }
        }
    }

    /** @hide */
    public void set(int uid) {
        mNum = 1;
        if (mUids == null) mUids = new int[2];
        mUids[0] = uid;
        mNames = null;
        if (mChains != null) {
            mChains.clear();
        }
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
        if (mChains != null) {
            mChains.clear();
        }
    }

    /**
     * Legacy API, DO NOT USE: Only deals with flat UIDs and tags. No chains are transferred, and no
     * differences in chains are returned. This will be removed once its callers have been
     * rewritten.
     *
     * NOTE: This is currently only used in GnssLocationProvider.
     *
     * @hide
     * @deprecated for internal use only. WorkSources are opaque and no external callers should need
     *     to be aware of internal differences.
     */
    @Deprecated
    @TestApi
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
            boolean uidAdded = updateLocked(other, false, false);

            boolean chainAdded = false;
            if (other.mChains != null) {
                // NOTE: This is quite an expensive operation, especially if the number of chains
                // is large. We could look into optimizing it if it proves problematic.
                if (mChains == null) {
                    mChains = new ArrayList<>(other.mChains.size());
                }

                for (WorkChain wc : other.mChains) {
                    if (!mChains.contains(wc)) {
                        mChains.add(new WorkChain(wc));
                    }
                }
            }

            return uidAdded || chainAdded;
        }
    }

    /**
     * Legacy API: DO NOT USE. Only in use from unit tests.
     *
     * @hide
     * @deprecated meant for unit testing use only. Will be removed in a future API revision.
     */
    @Deprecated
    @TestApi
    public WorkSource addReturningNewbs(WorkSource other) {
        synchronized (sTmpWorkSource) {
            sNewbWork = null;
            updateLocked(other, false, true);
            return sNewbWork;
        }
    }

    /** @hide */
    @TestApi
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
    @TestApi
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

    public boolean remove(WorkSource other) {
        if (isEmpty() || other.isEmpty()) {
            return false;
        }

        boolean uidRemoved;
        if (mNames == null && other.mNames == null) {
            uidRemoved = removeUids(other);
        } else {
            if (mNames == null) {
                throw new IllegalArgumentException("Other " + other + " has names, but target "
                        + this + " does not");
            }
            if (other.mNames == null) {
                throw new IllegalArgumentException("Target " + this + " has names, but other "
                        + other + " does not");
            }
            uidRemoved = removeUidsAndNames(other);
        }

        boolean chainRemoved = false;
        if (other.mChains != null && mChains != null) {
            chainRemoved = mChains.removeAll(other.mChains);
        }

        return uidRemoved || chainRemoved;
    }

    /**
     * Create a new {@code WorkChain} associated with this WorkSource and return it.
     *
     * @hide
     */
    @SystemApi
    public WorkChain createWorkChain() {
        if (mChains == null) {
            mChains = new ArrayList<>(4);
        }

        final WorkChain wc = new WorkChain();
        mChains.add(wc);

        return wc;
    }

    /**
     * Returns {@code true} iff. this work source contains zero UIDs and zero WorkChains to
     * attribute usage to.
     *
     * @hide for internal use only.
     */
    public boolean isEmpty() {
        return mNum == 0 && (mChains == null || mChains.isEmpty());
    }

    /**
     * @return the list of {@code WorkChains} associated with this {@code WorkSource}.
     * @hide
     */
    public ArrayList<WorkChain> getWorkChains() {
        return mChains;
    }

    /**
     * DO NOT USE: Hacky API provided solely for {@code GnssLocationProvider}. See
     * {@code setReturningDiffs} as well.
     *
     * @hide
     */
    public void transferWorkChains(WorkSource other) {
        if (mChains != null) {
            mChains.clear();
        }

        if (other.mChains == null || other.mChains.isEmpty()) {
            return;
        }

        if (mChains == null) {
            mChains = new ArrayList<>(4);
        }

        mChains.addAll(other.mChains);
        other.mChains.clear();
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

    /**
     * Represents an attribution chain for an item of work being performed. An attribution chain is
     * an indexed list of {code (uid, tag)} nodes. The node at {@code index == 0} is the initiator
     * of the work, and the node at the highest index performs the work. Nodes at other indices
     * are intermediaries that facilitate the work. Examples :
     *
     * (1) Work being performed by uid=2456 (no chaining):
     * <pre>
     * WorkChain {
     *   mUids = { 2456 }
     *   mTags = { null }
     *   mSize = 1;
     * }
     * </pre>
     *
     * (2) Work being performed by uid=2456 (from component "c1") on behalf of uid=5678:
     *
     * <pre>
     * WorkChain {
     *   mUids = { 5678, 2456 }
     *   mTags = { null, "c1" }
     *   mSize = 1
     * }
     * </pre>
     *
     * Attribution chains are mutable, though the only operation that can be performed on them
     * is the addition of a new node at the end of the attribution chain to represent
     *
     * @hide
     */
    @SystemApi
    public static final class WorkChain implements Parcelable {
        private int mSize;
        private int[] mUids;
        private String[] mTags;

        // @VisibleForTesting
        public WorkChain() {
            mSize = 0;
            mUids = new int[4];
            mTags = new String[4];
        }

        /** @hide */
        @VisibleForTesting
        public WorkChain(WorkChain other) {
            mSize = other.mSize;
            mUids = other.mUids.clone();
            mTags = other.mTags.clone();
        }

        private WorkChain(Parcel in) {
            mSize = in.readInt();
            mUids = in.createIntArray();
            mTags = in.createStringArray();
        }

        /**
         * Append a node whose uid is {@code uid} and whose optional tag is {@code tag} to this
         * {@code WorkChain}.
         */
        public WorkChain addNode(int uid, @Nullable String tag) {
            if (mSize == mUids.length) {
                resizeArrays();
            }

            mUids[mSize] = uid;
            mTags[mSize] = tag;
            mSize++;

            return this;
        }

        /**
         * Return the UID to which this WorkChain should be attributed to, i.e, the UID that
         * initiated the work and not the UID performing it. Returns -1 if the chain is empty.
         */
        public int getAttributionUid() {
            return mSize > 0 ? mUids[0] : -1;
        }

        /**
         * Return the tag associated with the attribution UID. See (@link #getAttributionUid}.
         * Returns null if the chain is empty.
         */
        public String getAttributionTag() {
            return mTags.length > 0 ? mTags[0] : null;
        }

        // TODO: The following three trivial getters are purely for testing and will be removed
        // once we have higher level logic in place, e.g for serializing this WorkChain to a proto,
        // diffing it etc.


        /** @hide */
        @VisibleForTesting
        public int[] getUids() {
            int[] uids = new int[mSize];
            System.arraycopy(mUids, 0, uids, 0, mSize);
            return uids;
        }

        /** @hide */
        @VisibleForTesting
        public String[] getTags() {
            String[] tags = new String[mSize];
            System.arraycopy(mTags, 0, tags, 0, mSize);
            return tags;
        }

        /** @hide */
        @VisibleForTesting
        public int getSize() {
            return mSize;
        }

        private void resizeArrays() {
            final int newSize = mSize * 2;
            int[] uids = new int[newSize];
            String[] tags = new String[newSize];

            System.arraycopy(mUids, 0, uids, 0, mSize);
            System.arraycopy(mTags, 0, tags, 0, mSize);

            mUids = uids;
            mTags = tags;
        }

        @Override
        public String toString() {
            StringBuilder result = new StringBuilder("WorkChain{");
            for (int i = 0; i < mSize; ++i) {
                if (i != 0) {
                    result.append(", ");
                }
                result.append("(");
                result.append(mUids[i]);
                if (mTags[i] != null) {
                    result.append(", ");
                    result.append(mTags[i]);
                }
                result.append(")");
            }

            result.append("}");
            return result.toString();
        }

        @Override
        public int hashCode() {
            return (mSize + 31 * Arrays.hashCode(mUids)) * 31 + Arrays.hashCode(mTags);
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof WorkChain) {
                WorkChain other = (WorkChain) o;

                return mSize == other.mSize
                    && Arrays.equals(mUids, other.mUids)
                    && Arrays.equals(mTags, other.mTags);
            }

            return false;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mSize);
            dest.writeIntArray(mUids);
            dest.writeStringArray(mTags);
        }

        public static final @android.annotation.NonNull Parcelable.Creator<WorkChain> CREATOR =
                new Parcelable.Creator<WorkChain>() {
                    public WorkChain createFromParcel(Parcel in) {
                        return new WorkChain(in);
                    }
                    public WorkChain[] newArray(int size) {
                        return new WorkChain[size];
                    }
                };
    }

    /**
     * Computes the differences in WorkChains contained between {@code oldWs} and {@code newWs}.
     *
     * Returns {@code null} if no differences exist, otherwise returns a two element array. The
     * first element is a list of "new" chains, i.e WorkChains present in {@code newWs} but not in
     * {@code oldWs}. The second element is a list of "gone" chains, i.e WorkChains present in
     * {@code oldWs} but not in {@code newWs}.
     *
     * @hide
     */
    public static ArrayList<WorkChain>[] diffChains(WorkSource oldWs, WorkSource newWs) {
        ArrayList<WorkChain> newChains = null;
        ArrayList<WorkChain> goneChains = null;

        // TODO(narayan): This is a dumb O(M*N) algorithm that determines what has changed across
        // WorkSource objects. We can replace this with something smarter, for e.g by defining
        // a Comparator between WorkChains. It's unclear whether that will be more efficient if
        // the number of chains associated with a WorkSource is expected to be small
        if (oldWs.mChains != null) {
            for (int i = 0; i < oldWs.mChains.size(); ++i) {
                final WorkChain wc = oldWs.mChains.get(i);
                if (newWs.mChains == null || !newWs.mChains.contains(wc)) {
                    if (goneChains == null) {
                        goneChains = new ArrayList<>(oldWs.mChains.size());
                    }
                    goneChains.add(wc);
                }
            }
        }

        if (newWs.mChains != null) {
            for (int i = 0; i < newWs.mChains.size(); ++i) {
                final WorkChain wc = newWs.mChains.get(i);
                if (oldWs.mChains == null || !oldWs.mChains.contains(wc)) {
                    if (newChains == null) {
                        newChains = new ArrayList<>(newWs.mChains.size());
                    }
                    newChains.add(wc);
                }
            }
        }

        if (newChains != null || goneChains != null) {
            return new ArrayList[] { newChains, goneChains };
        }

        return null;
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

        if (mChains == null) {
            dest.writeInt(-1);
        } else {
            dest.writeInt(mChains.size());
            dest.writeParcelableList(mChains, flags);
        }
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

        if (mChains != null) {
            result.append(" chains=");
            for (int i = 0; i < mChains.size(); ++i) {
                if (i != 0) {
                    result.append(", ");
                }
                result.append(mChains.get(i));
            }
        }

        result.append("}");
        return result.toString();
    }

    /** @hide */
    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        final long workSourceToken = proto.start(fieldId);
        for (int i = 0; i < mNum; i++) {
            final long contentProto = proto.start(WorkSourceProto.WORK_SOURCE_CONTENTS);
            proto.write(WorkSourceProto.WorkSourceContentProto.UID, mUids[i]);
            if (mNames != null) {
                proto.write(WorkSourceProto.WorkSourceContentProto.NAME, mNames[i]);
            }
            proto.end(contentProto);
        }

        if (mChains != null) {
            for (int i = 0; i < mChains.size(); i++) {
                final WorkChain wc = mChains.get(i);
                final long workChain = proto.start(WorkSourceProto.WORK_CHAINS);

                final String[] tags = wc.getTags();
                final int[] uids = wc.getUids();
                for (int j = 0; j < tags.length; j++) {
                    final long contentProto = proto.start(WorkSourceProto.WORK_SOURCE_CONTENTS);
                    proto.write(WorkSourceProto.WorkSourceContentProto.UID, uids[j]);
                    proto.write(WorkSourceProto.WorkSourceContentProto.NAME, tags[j]);
                    proto.end(contentProto);
                }

                proto.end(workChain);
            }
        }

        proto.end(workSourceToken);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<WorkSource> CREATOR
            = new Parcelable.Creator<WorkSource>() {
        public WorkSource createFromParcel(Parcel in) {
            return new WorkSource(in);
        }
        public WorkSource[] newArray(int size) {
            return new WorkSource[size];
        }
    };
}
