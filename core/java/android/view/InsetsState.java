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

import static android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
import static android.view.ViewRootImpl.NEW_INSETS_MODE_FULL;
import static android.view.ViewRootImpl.NEW_INSETS_MODE_IME;
import static android.view.ViewRootImpl.NEW_INSETS_MODE_NONE;
import static android.view.ViewRootImpl.sNewInsetsMode;
import static android.view.WindowInsets.Type.MANDATORY_SYSTEM_GESTURES;
import static android.view.WindowInsets.Type.SIZE;
import static android.view.WindowInsets.Type.SYSTEM_GESTURES;
import static android.view.WindowInsets.Type.displayCutout;
import static android.view.WindowInsets.Type.ime;
import static android.view.WindowInsets.Type.indexOf;
import static android.view.WindowInsets.Type.isVisibleInsetsType;
import static android.view.WindowInsets.Type.systemBars;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.SparseIntArray;
import android.view.WindowInsets.Type;
import android.view.WindowInsets.Type.InsetsType;
import android.view.WindowManager.LayoutParams.SoftInputModeFlags;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
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
    @IntDef(prefix = "ITYPE", value = {
            ITYPE_STATUS_BAR,
            ITYPE_NAVIGATION_BAR,
            ITYPE_CAPTION_BAR,
            ITYPE_TOP_GESTURES,
            ITYPE_BOTTOM_GESTURES,
            ITYPE_LEFT_GESTURES,
            ITYPE_RIGHT_GESTURES,
            ITYPE_TOP_TAPPABLE_ELEMENT,
            ITYPE_BOTTOM_TAPPABLE_ELEMENT,
            ITYPE_LEFT_DISPLAY_CUTOUT,
            ITYPE_TOP_DISPLAY_CUTOUT,
            ITYPE_RIGHT_DISPLAY_CUTOUT,
            ITYPE_BOTTOM_DISPLAY_CUTOUT,
            ITYPE_IME
    })
    public @interface InternalInsetsType {}

    /**
     * Special value to be used to by methods returning an {@link InternalInsetsType} to indicate
     * that the objects/parameters aren't associated with an {@link InternalInsetsType}
     */
    public static final int ITYPE_INVALID = -1;

    static final int FIRST_TYPE = 0;

    public static final int ITYPE_STATUS_BAR = FIRST_TYPE;
    public static final int ITYPE_NAVIGATION_BAR = 1;
    public static final int ITYPE_CAPTION_BAR = 2;

    public static final int ITYPE_TOP_GESTURES = 3;
    public static final int ITYPE_BOTTOM_GESTURES = 4;
    public static final int ITYPE_LEFT_GESTURES = 5;
    public static final int ITYPE_RIGHT_GESTURES = 6;
    public static final int ITYPE_TOP_TAPPABLE_ELEMENT = 7;
    public static final int ITYPE_BOTTOM_TAPPABLE_ELEMENT = 8;

    public static final int ITYPE_LEFT_DISPLAY_CUTOUT = 9;
    public static final int ITYPE_TOP_DISPLAY_CUTOUT = 10;
    public static final int ITYPE_RIGHT_DISPLAY_CUTOUT = 11;
    public static final int ITYPE_BOTTOM_DISPLAY_CUTOUT = 12;

    /** Input method window. */
    public static final int ITYPE_IME = 13;

    static final int LAST_TYPE = ITYPE_IME;

    // Derived types

    /** A shelf is the same as the navigation bar. */
    public static final int ITYPE_SHELF = ITYPE_NAVIGATION_BAR;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "IINSETS_SIDE", value = {
            ISIDE_LEFT,
            ISIDE_TOP,
            ISIDE_RIGHT,
            ISIDE_BOTTOM,
            ISIDE_FLOATING,
            ISIDE_UNKNOWN
    })
    public @interface InternalInsetsSide {}
    static final int ISIDE_LEFT = 0;
    static final int ISIDE_TOP = 1;
    static final int ISIDE_RIGHT = 2;
    static final int ISIDE_BOTTOM = 3;
    static final int ISIDE_FLOATING = 4;
    static final int ISIDE_UNKNOWN = 5;

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
     * @param ignoringVisibilityState {@link InsetsState} used to calculate
     *        {@link WindowInsets#getInsetsIgnoringVisibility(int)} information, or pass
     *        {@code null} to use this state to calculate that information.
     * @return The calculated insets.
     */
    public WindowInsets calculateInsets(Rect frame, @Nullable InsetsState ignoringVisibilityState,
            boolean isScreenRound, boolean alwaysConsumeSystemBars, DisplayCutout cutout,
            @Nullable Rect legacyContentInsets, @Nullable Rect legacyStableInsets,
            int legacySoftInputMode, int legacySystemUiFlags,
            @Nullable @InternalInsetsSide SparseIntArray typeSideMap) {
        Insets[] typeInsetsMap = new Insets[Type.SIZE];
        Insets[] typeMaxInsetsMap = new Insets[Type.SIZE];
        boolean[] typeVisibilityMap = new boolean[SIZE];
        final Rect relativeFrame = new Rect(frame);
        final Rect relativeFrameMax = new Rect(frame);
        if (ViewRootImpl.sNewInsetsMode != NEW_INSETS_MODE_FULL
                && legacyContentInsets != null && legacyStableInsets != null) {
            WindowInsets.assignCompatInsets(typeInsetsMap, legacyContentInsets);
            WindowInsets.assignCompatInsets(typeMaxInsetsMap, legacyStableInsets);
        }
        for (int type = FIRST_TYPE; type <= LAST_TYPE; type++) {
            InsetsSource source = mSources.get(type);
            if (source == null) {
                continue;
            }

            boolean skipNonImeInImeMode = ViewRootImpl.sNewInsetsMode == NEW_INSETS_MODE_IME
                    && source.getType() != ITYPE_IME;
            boolean skipSystemBars = ViewRootImpl.sNewInsetsMode != NEW_INSETS_MODE_FULL
                    && (type == ITYPE_STATUS_BAR || type == ITYPE_NAVIGATION_BAR);
            boolean skipLegacyTypes = ViewRootImpl.sNewInsetsMode == NEW_INSETS_MODE_NONE
                    && (type == ITYPE_STATUS_BAR || type == ITYPE_NAVIGATION_BAR
                            || type == ITYPE_IME);
            if (skipSystemBars || skipLegacyTypes || skipNonImeInImeMode) {
                typeVisibilityMap[indexOf(toPublicType(type))] = source.isVisible();
                continue;
            }

            processSource(source, relativeFrame, false /* ignoreVisibility */, typeInsetsMap,
                    typeSideMap, typeVisibilityMap);

            // IME won't be reported in max insets as the size depends on the EditorInfo of the IME
            // target.
            if (source.getType() != ITYPE_IME) {
                InsetsSource ignoringVisibilitySource = ignoringVisibilityState != null
                        ? ignoringVisibilityState.getSource(type)
                        : source;
                if (ignoringVisibilitySource == null) {
                    continue;
                }
                processSource(ignoringVisibilitySource, relativeFrameMax,
                        true /* ignoreVisibility */, typeMaxInsetsMap, null /* typeSideMap */,
                        null /* typeVisibilityMap */);
            }
        }
        final int softInputAdjustMode = legacySoftInputMode & SOFT_INPUT_MASK_ADJUST;
        return new WindowInsets(typeInsetsMap, typeMaxInsetsMap, typeVisibilityMap, isScreenRound,
                alwaysConsumeSystemBars, cutout, softInputAdjustMode == SOFT_INPUT_ADJUST_RESIZE
                        ? systemBars() | displayCutout() | ime()
                        : systemBars() | displayCutout(),
                sNewInsetsMode == NEW_INSETS_MODE_FULL
                        && (legacySystemUiFlags & SYSTEM_UI_FLAG_LAYOUT_STABLE) != 0);
    }

    public Rect calculateVisibleInsets(Rect frame, Rect legacyVisibleInsets,
            @SoftInputModeFlags int softInputMode) {
        if (sNewInsetsMode == NEW_INSETS_MODE_NONE) {
            return legacyVisibleInsets;
        }

        Insets insets = Insets.NONE;
        for (int type = FIRST_TYPE; type <= LAST_TYPE; type++) {
            InsetsSource source = mSources.get(type);
            if (source == null) {
                continue;
            }
            if (sNewInsetsMode != NEW_INSETS_MODE_FULL && type != ITYPE_IME) {
                continue;
            }

            // Ignore everything that's not a system bar or IME.
            int publicType = InsetsState.toPublicType(type);
            if (!isVisibleInsetsType(publicType, softInputMode)) {
                continue;
            }
            insets = Insets.max(source.calculateVisibleInsets(frame), insets);
        }
        return insets.toRect();
    }

    private void processSource(InsetsSource source, Rect relativeFrame, boolean ignoreVisibility,
            Insets[] typeInsetsMap, @Nullable @InternalInsetsSide SparseIntArray typeSideMap,
            @Nullable boolean[] typeVisibilityMap) {
        Insets insets = source.calculateInsets(relativeFrame, ignoreVisibility);

        int type = toPublicType(source.getType());
        processSourceAsPublicType(source, typeInsetsMap, typeSideMap, typeVisibilityMap,
                insets, type);

        if (type == MANDATORY_SYSTEM_GESTURES) {
            // Mandatory system gestures are also system gestures.
            // TODO: find a way to express this more generally. One option would be to define
            //       Type.systemGestureInsets() as NORMAL | MANDATORY, but then we lose the
            //       ability to set systemGestureInsets() independently from
            //       mandatorySystemGestureInsets() in the Builder.
            processSourceAsPublicType(source, typeInsetsMap, typeSideMap, typeVisibilityMap,
                    insets, SYSTEM_GESTURES);
        }
    }

    private void processSourceAsPublicType(InsetsSource source, Insets[] typeInsetsMap,
            @InternalInsetsSide @Nullable SparseIntArray typeSideMap,
            @Nullable boolean[] typeVisibilityMap, Insets insets, int type) {
        int index = indexOf(type);
        Insets existing = typeInsetsMap[index];
        if (existing == null) {
            typeInsetsMap[index] = insets;
        } else {
            typeInsetsMap[index] = Insets.max(existing, insets);
        }

        if (typeVisibilityMap != null) {
            typeVisibilityMap[index] = source.isVisible();
        }

        if (typeSideMap != null) {
            @InternalInsetsSide int insetSide = getInsetSide(insets);
            if (insetSide != ISIDE_UNKNOWN) {
                typeSideMap.put(source.getType(), insetSide);
            }
        }
    }

    /**
     * Retrieves the side for a certain {@code insets}. It is required that only one field l/t/r/b
     * is set in order that this method returns a meaningful result.
     */
    private @InternalInsetsSide int getInsetSide(Insets insets) {
        if (Insets.NONE.equals(insets)) {
            return ISIDE_FLOATING;
        }
        if (insets.left != 0) {
            return ISIDE_LEFT;
        }
        if (insets.top != 0) {
            return ISIDE_TOP;
        }
        if (insets.right != 0) {
            return ISIDE_RIGHT;
        }
        if (insets.bottom != 0) {
            return ISIDE_BOTTOM;
        }
        return ISIDE_UNKNOWN;
    }

    public InsetsSource getSource(@InternalInsetsType int type) {
        return mSources.computeIfAbsent(type, InsetsSource::new);
    }

    public @Nullable InsetsSource peekSource(@InternalInsetsType int type) {
        return mSources.get(type);
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
     * @param type The {@link InternalInsetsType} of the source to remove
     */
    public void removeSource(@InternalInsetsType int type) {
        mSources.remove(type);
    }

    /**
     * A shortcut for setting the visibility of the source.
     *
     * @param type The {@link InternalInsetsType} of the source to set the visibility
     * @param visible {@code true} for visible
     */
    public void setSourceVisible(@InternalInsetsType int type, boolean visible) {
        InsetsSource source = mSources.get(type);
        if (source != null) {
            source.setVisible(visible);
        }
    }

    /**
     * A shortcut for setting the visibility of the source.
     *
     * @param type The {@link InternalInsetsType} of the source to set the visibility
     * @param referenceState The {@link InsetsState} for reference
     */
    public void setSourceVisible(@InternalInsetsType int type, InsetsState referenceState) {
        InsetsSource source = mSources.get(type);
        InsetsSource referenceSource = referenceState.mSources.get(type);
        if (source != null && referenceSource != null) {
            source.setVisible(referenceSource.isVisible());
        }
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

    public static @InternalInsetsType ArraySet<Integer> toInternalType(@InsetsType int types) {
        final ArraySet<Integer> result = new ArraySet<>();
        if ((types & Type.STATUS_BARS) != 0) {
            result.add(ITYPE_STATUS_BAR);
        }
        if ((types & Type.NAVIGATION_BARS) != 0) {
            result.add(ITYPE_NAVIGATION_BAR);
        }
        if ((types & Type.CAPTION_BAR) != 0) {
            result.add(ITYPE_CAPTION_BAR);
        }
        if ((types & Type.DISPLAY_CUTOUT) != 0) {
            result.add(ITYPE_LEFT_DISPLAY_CUTOUT);
            result.add(ITYPE_TOP_DISPLAY_CUTOUT);
            result.add(ITYPE_RIGHT_DISPLAY_CUTOUT);
            result.add(ITYPE_BOTTOM_DISPLAY_CUTOUT);
        }
        if ((types & Type.IME) != 0) {
            result.add(ITYPE_IME);
        }
        return result;
    }

    /**
     * Converting a internal type to the public type.
     * @param type internal insets type, {@code InternalInsetsType}.
     * @return public insets type, {@code Type.InsetsType}.
     */
    public static @Type.InsetsType int toPublicType(@InternalInsetsType int type) {
        switch (type) {
            case ITYPE_STATUS_BAR:
                return Type.STATUS_BARS;
            case ITYPE_NAVIGATION_BAR:
                return Type.NAVIGATION_BARS;
            case ITYPE_CAPTION_BAR:
                return Type.CAPTION_BAR;
            case ITYPE_IME:
                return Type.IME;
            case ITYPE_TOP_GESTURES:
            case ITYPE_BOTTOM_GESTURES:
                return Type.MANDATORY_SYSTEM_GESTURES;
            case ITYPE_LEFT_GESTURES:
            case ITYPE_RIGHT_GESTURES:
                return Type.SYSTEM_GESTURES;
            case ITYPE_TOP_TAPPABLE_ELEMENT:
            case ITYPE_BOTTOM_TAPPABLE_ELEMENT:
                return Type.TAPPABLE_ELEMENT;
            case ITYPE_LEFT_DISPLAY_CUTOUT:
            case ITYPE_TOP_DISPLAY_CUTOUT:
            case ITYPE_RIGHT_DISPLAY_CUTOUT:
            case ITYPE_BOTTOM_DISPLAY_CUTOUT:
                return Type.DISPLAY_CUTOUT;
            default:
                throw new IllegalArgumentException("Unknown type: " + type);
        }
    }

    public static boolean getDefaultVisibility(@InsetsType int type) {
        return type != ITYPE_IME;
    }

    public static boolean containsType(@InternalInsetsType int[] types,
            @InternalInsetsType int type) {
        if (types == null) {
            return false;
        }
        for (int t : types) {
            if (t == type) {
                return true;
            }
        }
        return false;
    }

    public void dump(String prefix, PrintWriter pw) {
        pw.println(prefix + "InsetsState");
        for (int i = mSources.size() - 1; i >= 0; i--) {
            mSources.valueAt(i).dump(prefix + "  ", pw);
        }
    }

    public static String typeToString(@InternalInsetsType int type) {
        switch (type) {
            case ITYPE_STATUS_BAR:
                return "ITYPE_STATUS_BAR";
            case ITYPE_NAVIGATION_BAR:
                return "ITYPE_NAVIGATION_BAR";
            case ITYPE_CAPTION_BAR:
                return "ITYPE_CAPTION_BAR";
            case ITYPE_TOP_GESTURES:
                return "ITYPE_TOP_GESTURES";
            case ITYPE_BOTTOM_GESTURES:
                return "ITYPE_BOTTOM_GESTURES";
            case ITYPE_LEFT_GESTURES:
                return "ITYPE_LEFT_GESTURES";
            case ITYPE_RIGHT_GESTURES:
                return "ITYPE_RIGHT_GESTURES";
            case ITYPE_TOP_TAPPABLE_ELEMENT:
                return "ITYPE_TOP_TAPPABLE_ELEMENT";
            case ITYPE_BOTTOM_TAPPABLE_ELEMENT:
                return "ITYPE_BOTTOM_TAPPABLE_ELEMENT";
            case ITYPE_LEFT_DISPLAY_CUTOUT:
                return "ITYPE_LEFT_DISPLAY_CUTOUT";
            case ITYPE_TOP_DISPLAY_CUTOUT:
                return "ITYPE_TOP_DISPLAY_CUTOUT";
            case ITYPE_RIGHT_DISPLAY_CUTOUT:
                return "ITYPE_RIGHT_DISPLAY_CUTOUT";
            case ITYPE_BOTTOM_DISPLAY_CUTOUT:
                return "ITYPE_BOTTOM_DISPLAY_CUTOUT";
            case ITYPE_IME:
                return "ITYPE_IME";
            default:
                return "ITYPE_UNKNOWN_" + type;
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

    @Override
    public String toString() {
        return "InsetsState: {"
                + "mDisplayFrame=" + mDisplayFrame
                + ", mSources=" + mSources
                + "}";
    }
}

