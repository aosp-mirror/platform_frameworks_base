/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.view;

import static android.view.ViewRootImpl.NEW_INSETS_MODE_FULL;
import static android.view.ViewRootImpl.NEW_INSETS_MODE_IME;
import static android.view.ViewRootImpl.NEW_INSETS_MODE_NONE;
import static android.view.WindowInsets.Type.IME;
import static android.view.WindowInsets.Type.SIZE;
import static android.view.WindowInsets.Type.indexOf;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.WindowInsets.Type;
import android.view.WindowInsets.Type.InsetType;
import android.view.WindowManager.LayoutParams;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Objects;

/**
 * Holder for state of system windows that cause window insets for all other windows in the system.
 * @hide
 */
public class InsetsState implements Parcelable {

    /**
     * Internal representation of inset source types. This is different from the public API in
     * {@link WindowInsets.Type} as one type from the public API might indicate multiple windows
     * at the same time.
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "TYPE", value = {
            TYPE_TOP_BAR,
            TYPE_SIDE_BAR_1,
            TYPE_SIDE_BAR_2,
            TYPE_SIDE_BAR_3,
            TYPE_IME
    })
    public @interface InternalInsetType {}

    static final int FIRST_TYPE = 0;

    /** Top bar. Can be status bar or caption in freeform windowing mode. */
    public static final int TYPE_TOP_BAR = FIRST_TYPE;

    /**
     * Up to 3 side bars that appear on left/right/bottom. On phones there is only one side bar
     * (the navigation bar, see {@link #TYPE_NAVIGATION_BAR}), but other form factors might have
     * multiple, like Android Auto.
     */
    public static final int TYPE_SIDE_BAR_1 = 1;
    public static final int TYPE_SIDE_BAR_2 = 2;
    public static final int TYPE_SIDE_BAR_3 = 3;

    /** Input method window. */
    public static final int TYPE_IME = 4;
    static final int LAST_TYPE = TYPE_IME;

    // Derived types

    /** First side bar is navigation bar. */
    public static final int TYPE_NAVIGATION_BAR = TYPE_SIDE_BAR_1;

    /** A shelf is the same as the navigation bar. */
    public static final int TYPE_SHELF = TYPE_NAVIGATION_BAR;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "INSET_SIDE", value = {
            INSET_SIDE_LEFT,
            INSET_SIDE_TOP,
            INSET_SIDE_RIGHT,
            INSET_SIDE_BOTTOM,
            INSET_SIDE_UNKNWON
    })
    public @interface InsetSide {}
    static final int INSET_SIDE_LEFT = 0;
    static final int INSET_SIDE_TOP = 1;
    static final int INSET_SIDE_RIGHT = 2;
    static final int INSET_SIDE_BOTTOM = 3;
    static final int INSET_SIDE_UNKNWON = 4;

    private final ArrayMap<Integer, InsetsSource> mSources = new ArrayMap<>();

    /**
     * The frame of the display these sources are relative to.
     */
    private final Rect mDisplayFrame = new Rect();

    public InsetsState() {
    }

    public InsetsState(InsetsState copy) {
        set(copy);
    }

    public InsetsState(InsetsState copy, boolean copySources) {
        set(copy, copySources);
    }

    /**
     * Calculates {@link WindowInsets} based on the current source configuration.
     *
     * @param frame The frame to calculate the insets relative to.
     * @return The calculated insets.
     */
    public WindowInsets calculateInsets(Rect frame, boolean isScreenRound,
            boolean alwaysConsumeNavBar, DisplayCutout cutout,
            @Nullable Rect legacyContentInsets, @Nullable Rect legacyStableInsets,
            int legacySoftInputMode, @Nullable @InsetSide SparseIntArray typeSideMap) {
        Insets[] typeInsetsMap = new Insets[Type.SIZE];
        Insets[] typeMaxInsetsMap = new Insets[Type.SIZE];
        boolean[] typeVisibilityMap = new boolean[SIZE];
        final Rect relativeFrame = new Rect(frame);
        final Rect relativeFrameMax = new Rect(frame);
        if (ViewRootImpl.sNewInsetsMode != NEW_INSETS_MODE_FULL
                && legacyContentInsets != null && legacyStableInsets != null) {
            WindowInsets.assignCompatInsets(typeInsetsMap, legacyContentInsets);
            WindowInsets.assignCompatInsets(typeMaxInsetsMap, legacyStableInsets);

            // TODO: set system gesture insets based on actual system gesture area.
            typeInsetsMap[Type.indexOf(Type.systemGestures())] = Insets.of(legacyContentInsets);
            typeMaxInsetsMap[Type.indexOf(Type.systemGestures())] = Insets.of(legacyContentInsets);
        }
        for (int type = FIRST_TYPE; type <= LAST_TYPE; type++) {
            InsetsSource source = mSources.get(type);
            if (source == null) {
                continue;
            }

            boolean skipSystemBars = ViewRootImpl.sNewInsetsMode != NEW_INSETS_MODE_FULL
                    && (type == TYPE_TOP_BAR || type == TYPE_NAVIGATION_BAR);
            boolean skipIme = source.getType() == TYPE_IME
                    && (legacySoftInputMode & LayoutParams.SOFT_INPUT_ADJUST_RESIZE) == 0;
            if (skipSystemBars || skipIme) {
                typeVisibilityMap[indexOf(toPublicType(type))] = source.isVisible();
                continue;
            }

            processSource(source, relativeFrame, false /* ignoreVisibility */, typeInsetsMap,
                    typeSideMap, typeVisibilityMap);

            // IME won't be reported in max insets as the size depends on the EditorInfo of the IME
            // target.
            if (source.getType() != TYPE_IME) {
                processSource(source, relativeFrameMax, true /* ignoreVisibility */,
                        typeMaxInsetsMap, null /* typeSideMap */, null /* typeVisibilityMap */);
            }
        }
        return new WindowInsets(typeInsetsMap, typeMaxInsetsMap, typeVisibilityMap, isScreenRound,
                alwaysConsumeNavBar, cutout);
    }

    private void processSource(InsetsSource source, Rect relativeFrame, boolean ignoreVisibility,
            Insets[] typeInsetsMap, @Nullable @InsetSide SparseIntArray typeSideMap,
            @Nullable boolean[] typeVisibilityMap) {
        Insets insets = source.calculateInsets(relativeFrame, ignoreVisibility);

        int index = indexOf(toPublicType(source.getType()));
        Insets existing = typeInsetsMap[index];
        if (existing == null) {
            typeInsetsMap[index] = insets;
        } else {
            typeInsetsMap[index] = Insets.max(existing, insets);
        }

        if (typeVisibilityMap != null) {
            typeVisibilityMap[index] = source.isVisible();
        }

        if (typeSideMap != null && !Insets.NONE.equals(insets)) {
            @InsetSide int insetSide = getInsetSide(insets);
            if (insetSide != INSET_SIDE_UNKNWON) {
                typeSideMap.put(source.getType(), getInsetSide(insets));
            }
        }
    }

    /**
     * Retrieves the side for a certain {@code insets}. It is required that only one field l/t/r/b
     * is set in order that this method returns a meaningful result.
     */
    private @InsetSide int getInsetSide(Insets insets) {
        if (insets.left != 0) {
            return INSET_SIDE_LEFT;
        }
        if (insets.top != 0) {
            return INSET_SIDE_TOP;
        }
        if (insets.right != 0) {
            return INSET_SIDE_RIGHT;
        }
        if (insets.bottom != 0) {
            return INSET_SIDE_BOTTOM;
        }
        return INSET_SIDE_UNKNWON;
    }

    public InsetsSource getSource(@InternalInsetType int type) {
        return mSources.computeIfAbsent(type, InsetsSource::new);
    }

    public void setDisplayFrame(Rect frame) {
        mDisplayFrame.set(frame);
    }

    public Rect getDisplayFrame() {
        return mDisplayFrame;
    }

    /**
     * Modifies the state of this class to exclude a certain type to make it ready for dispatching
     * to the client.
     *
     * @param type The {@link InternalInsetType} of the source to remove
     */
    public void removeSource(int type) {
        mSources.remove(type);
    }

    public void set(InsetsState other) {
        set(other, false /* copySources */);
    }

    public void set(InsetsState other, boolean copySources) {
        mDisplayFrame.set(other.mDisplayFrame);
        mSources.clear();
        if (copySources) {
            for (int i = 0; i < other.mSources.size(); i++) {
                InsetsSource source = other.mSources.valueAt(i);
                mSources.put(source.getType(), new InsetsSource(source));
            }
        } else {
            mSources.putAll(other.mSources);
        }
    }

    public void addSource(InsetsSource source) {
        mSources.put(source.getType(), source);
    }

    public int getSourcesCount() {
        return mSources.size();
    }

    public InsetsSource sourceAt(int index) {
        return mSources.valueAt(index);
    }

    public static @InternalInsetType ArraySet<Integer> toInternalType(@InsetType int insetTypes) {
        final ArraySet<Integer> result = new ArraySet<>();
        if ((insetTypes & Type.TOP_BAR) != 0) {
            result.add(TYPE_TOP_BAR);
        }
        if ((insetTypes & Type.SIDE_BARS) != 0) {
            result.add(TYPE_SIDE_BAR_1);
            result.add(TYPE_SIDE_BAR_2);
            result.add(TYPE_SIDE_BAR_3);
        }
        if ((insetTypes & Type.IME) != 0) {
            result.add(TYPE_IME);
        }
        return result;
    }

    static @InsetType int toPublicType(@InternalInsetType int type) {
        switch (type) {
            case TYPE_TOP_BAR:
                return Type.TOP_BAR;
            case TYPE_SIDE_BAR_1:
            case TYPE_SIDE_BAR_2:
            case TYPE_SIDE_BAR_3:
                return Type.SIDE_BARS;
            case TYPE_IME:
                return Type.IME;
            default:
                throw new IllegalArgumentException("Unknown type: " + type);
        }
    }

    public static boolean getDefaultVisibility(@InsetType int type) {
        switch (type) {
            case TYPE_TOP_BAR:
            case TYPE_SIDE_BAR_1:
            case TYPE_SIDE_BAR_2:
            case TYPE_SIDE_BAR_3:
                return true;
            case TYPE_IME:
                return false;
            default:
                return true;
        }
    }

    public void dump(String prefix, PrintWriter pw) {
        pw.println(prefix + "InsetsState");
        for (int i = mSources.size() - 1; i >= 0; i--) {
            mSources.valueAt(i).dump(prefix + "  ", pw);
        }
    }

    public static String typeToString(int type) {
        switch (type) {
            case TYPE_TOP_BAR:
                return "TYPE_TOP_BAR";
            case TYPE_SIDE_BAR_1:
                return "TYPE_SIDE_BAR_1";
            case TYPE_SIDE_BAR_2:
                return "TYPE_SIDE_BAR_2";
            case TYPE_SIDE_BAR_3:
                return "TYPE_SIDE_BAR_3";
            case TYPE_IME:
                return "TYPE_IME";
            default:
                return "TYPE_UNKNOWN";
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) { return true; }
        if (o == null || getClass() != o.getClass()) { return false; }

        InsetsState state = (InsetsState) o;

        if (!mDisplayFrame.equals(state.mDisplayFrame)) {
            return false;
        }
        if (mSources.size() != state.mSources.size()) {
            return false;
        }
        for (int i = mSources.size() - 1; i >= 0; i--) {
            InsetsSource source = mSources.valueAt(i);
            InsetsSource otherSource = state.mSources.get(source.getType());
            if (otherSource == null) {
                return false;
            }
            if (!otherSource.equals(source)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mDisplayFrame, mSources);
    }

    public InsetsState(Parcel in) {
        readFromParcel(in);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(mDisplayFrame, flags);
        dest.writeInt(mSources.size());
        for (int i = 0; i < mSources.size(); i++) {
            dest.writeParcelable(mSources.valueAt(i), flags);
        }
    }

    public static final @android.annotation.NonNull Creator<InsetsState> CREATOR = new Creator<InsetsState>() {

        public InsetsState createFromParcel(Parcel in) {
            return new InsetsState(in);
        }

        public InsetsState[] newArray(int size) {
            return new InsetsState[size];
        }
    };

    public void readFromParcel(Parcel in) {
        mSources.clear();
        mDisplayFrame.set(in.readParcelable(null /* loader */));
        final int size = in.readInt();
        for (int i = 0; i < size; i++) {
            final InsetsSource source = in.readParcelable(null /* loader */);
            mSources.put(source.getType(), source);
        }
    }
}

