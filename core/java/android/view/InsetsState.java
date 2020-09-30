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
import static android.view.WindowInsets.Type.SYSTEM_GESTURES;
import static android.view.WindowInsets.Type.displayCutout;
import static android.view.WindowInsets.Type.ime;
import static android.view.WindowInsets.Type.indexOf;
import static android.view.WindowInsets.Type.isVisibleInsetsType;
import static android.view.WindowInsets.Type.statusBars;
import static android.view.WindowInsets.Type.systemBars;
import static android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArraySet;
import android.util.SparseIntArray;
import android.view.WindowInsets.Type;
import android.view.WindowInsets.Type.InsetsType;
import android.view.WindowManager.LayoutParams.SoftInputModeFlags;

import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Holder for state of system windows that cause window insets for all other windows in the system.
 * @hide
 */
public class InsetsState implements Parcelable {

    public static final InsetsState EMPTY = new InsetsState();

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
            ITYPE_TOP_MANDATORY_GESTURES,
            ITYPE_BOTTOM_MANDATORY_GESTURES,
            ITYPE_LEFT_MANDATORY_GESTURES,
            ITYPE_RIGHT_MANDATORY_GESTURES,
            ITYPE_TOP_TAPPABLE_ELEMENT,
            ITYPE_BOTTOM_TAPPABLE_ELEMENT,
            ITYPE_LEFT_DISPLAY_CUTOUT,
            ITYPE_TOP_DISPLAY_CUTOUT,
            ITYPE_RIGHT_DISPLAY_CUTOUT,
            ITYPE_BOTTOM_DISPLAY_CUTOUT,
            ITYPE_IME,
            ITYPE_CLIMATE_BAR,
            ITYPE_EXTRA_NAVIGATION_BAR
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

    /** Additional gesture inset types that map into {@link Type.MANDATORY_SYSTEM_GESTURES}. */
    public static final int ITYPE_TOP_MANDATORY_GESTURES = 7;
    public static final int ITYPE_BOTTOM_MANDATORY_GESTURES = 8;
    public static final int ITYPE_LEFT_MANDATORY_GESTURES = 9;
    public static final int ITYPE_RIGHT_MANDATORY_GESTURES = 10;

    public static final int ITYPE_TOP_TAPPABLE_ELEMENT = 11;
    public static final int ITYPE_BOTTOM_TAPPABLE_ELEMENT = 12;

    public static final int ITYPE_LEFT_DISPLAY_CUTOUT = 13;
    public static final int ITYPE_TOP_DISPLAY_CUTOUT = 14;
    public static final int ITYPE_RIGHT_DISPLAY_CUTOUT = 15;
    public static final int ITYPE_BOTTOM_DISPLAY_CUTOUT = 16;

    /** Input method window. */
    public static final int ITYPE_IME = 17;

    /** Additional system decorations inset type. */
    public static final int ITYPE_CLIMATE_BAR = 18;
    public static final int ITYPE_EXTRA_NAVIGATION_BAR = 19;

    static final int LAST_TYPE = ITYPE_EXTRA_NAVIGATION_BAR;
    public static final int SIZE = LAST_TYPE + 1;

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

    private InsetsSource[] mSources = new InsetsSource[SIZE];

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
            int legacySoftInputMode, int legacyWindowFlags, int legacySystemUiFlags,
            @Nullable @InternalInsetsSide SparseIntArray typeSideMap) {
        Insets[] typeInsetsMap = new Insets[Type.SIZE];
        Insets[] typeMaxInsetsMap = new Insets[Type.SIZE];
        boolean[] typeVisibilityMap = new boolean[SIZE];
        final Rect relativeFrame = new Rect(frame);
        final Rect relativeFrameMax = new Rect(frame);
        for (int type = FIRST_TYPE; type <= LAST_TYPE; type++) {
            InsetsSource source = mSources[type];
            if (source == null) {
                int index = indexOf(toPublicType(type));
                if (typeInsetsMap[index] == null) {
                    typeInsetsMap[index] = Insets.NONE;
                }
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

        @InsetsType int compatInsetsTypes = systemBars() | displayCutout();
        if (softInputAdjustMode == SOFT_INPUT_ADJUST_RESIZE) {
            compatInsetsTypes |= ime();
        }
        if ((legacyWindowFlags & FLAG_FULLSCREEN) != 0) {
            compatInsetsTypes &= ~statusBars();
        }

        return new WindowInsets(typeInsetsMap, typeMaxInsetsMap, typeVisibilityMap, isScreenRound,
                alwaysConsumeSystemBars, cutout, compatInsetsTypes,
                sNewInsetsMode == NEW_INSETS_MODE_FULL
                        && (legacySystemUiFlags & SYSTEM_UI_FLAG_LAYOUT_STABLE) != 0);
    }

    public Rect calculateVisibleInsets(Rect frame, @SoftInputModeFlags int softInputMode) {
        Insets insets = Insets.NONE;
        for (int type = FIRST_TYPE; type <= LAST_TYPE; type++) {
            InsetsSource source = mSources[type];
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

    /**
     * Calculate which insets *cannot* be controlled, because the frame does not cover the
     * respective side of the inset.
     *
     * If the frame of our window doesn't cover the entire inset, the control API makes very
     * little sense, as we don't deal with negative insets.
     */
    @InsetsType
    public int calculateUncontrollableInsetsFromFrame(Rect frame) {
        int blocked = 0;
        for (int type = FIRST_TYPE; type <= LAST_TYPE; type++) {
            InsetsSource source = mSources[type];
            if (source == null) {
                continue;
            }
            if (!canControlSide(frame, getInsetSide(
                    source.calculateInsets(frame, true /* ignoreVisibility */)))) {
                blocked |= toPublicType(type);
            }
        }
        return blocked;
    }

    private boolean canControlSide(Rect frame, int side) {
        switch (side) {
            case ISIDE_LEFT:
            case ISIDE_RIGHT:
                return frame.left == mDisplayFrame.left && frame.right == mDisplayFrame.right;
            case ISIDE_TOP:
            case ISIDE_BOTTOM:
                return frame.top == mDisplayFrame.top && frame.bottom == mDisplayFrame.bottom;
            case ISIDE_FLOATING:
                return true;
            default:
                return false;
        }
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
        InsetsSource source = mSources[type];
        if (source != null) {
            return source;
        }
        source = new InsetsSource(type);
        mSources[type] = source;
        return source;
    }

    public @Nullable InsetsSource peekSource(@InternalInsetsType int type) {
        return mSources[type];
    }

    public boolean hasSources() {
        for (int i = 0; i < SIZE; i++) {
            if (mSources[i] != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the source visibility or the default visibility if the source doesn't exist. This is
     * useful if when treating this object as a request.
     *
     * @param type The {@link InternalInsetsType} to query.
     * @return {@code true} if the source is visible or the type is default visible and the source
     *         doesn't exist.
     */
    public boolean getSourceOrDefaultVisibility(@InternalInsetsType int type) {
        final InsetsSource source = mSources[type];
        return source != null ? source.isVisible() : getDefaultVisibility(type);
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
        mSources[type] = null;
    }

    /**
     * A shortcut for setting the visibility of the source.
     *
     * @param type The {@link InternalInsetsType} of the source to set the visibility
     * @param visible {@code true} for visible
     */
    public void setSourceVisible(@InternalInsetsType int type, boolean visible) {
        InsetsSource source = mSources[type];
        if (source != null) {
            source.setVisible(visible);
        }
    }

    public void set(InsetsState other) {
        set(other, false /* copySources */);
    }

    public void set(InsetsState other, boolean copySources) {
        mDisplayFrame.set(other.mDisplayFrame);
        if (copySources) {
            for (int i = 0; i < SIZE; i++) {
                InsetsSource source = other.mSources[i];
                mSources[i] = source != null ? new InsetsSource(source) : null;
            }
        } else {
            for (int i = 0; i < SIZE; i++) {
                mSources[i] = other.mSources[i];
            }
        }
    }

    public void addSource(InsetsSource source) {
        mSources[source.getType()] = source;
    }

    public static @InternalInsetsType ArraySet<Integer> toInternalType(@InsetsType int types) {
        final ArraySet<Integer> result = new ArraySet<>();
        if ((types & Type.STATUS_BARS) != 0) {
            result.add(ITYPE_STATUS_BAR);
            result.add(ITYPE_CLIMATE_BAR);
        }
        if ((types & Type.NAVIGATION_BARS) != 0) {
            result.add(ITYPE_NAVIGATION_BAR);
            result.add(ITYPE_EXTRA_NAVIGATION_BAR);
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
            case ITYPE_CLIMATE_BAR:
                return Type.STATUS_BARS;
            case ITYPE_NAVIGATION_BAR:
            case ITYPE_EXTRA_NAVIGATION_BAR:
                return Type.NAVIGATION_BARS;
            case ITYPE_CAPTION_BAR:
                return Type.CAPTION_BAR;
            case ITYPE_IME:
                return Type.IME;
            case ITYPE_TOP_GESTURES:
            case ITYPE_BOTTOM_GESTURES:
            case ITYPE_TOP_MANDATORY_GESTURES:
            case ITYPE_BOTTOM_MANDATORY_GESTURES:
            case ITYPE_LEFT_MANDATORY_GESTURES:
            case ITYPE_RIGHT_MANDATORY_GESTURES:
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

    public static boolean getDefaultVisibility(@InternalInsetsType int type) {
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
        for (int i = 0; i < SIZE; i++) {
            InsetsSource source = mSources[i];
            if (source == null) continue;
            source.dump(prefix + "  ", pw);
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
            case ITYPE_TOP_MANDATORY_GESTURES:
                return "ITYPE_TOP_MANDATORY_GESTURES";
            case ITYPE_BOTTOM_MANDATORY_GESTURES:
                return "ITYPE_BOTTOM_MANDATORY_GESTURES";
            case ITYPE_LEFT_MANDATORY_GESTURES:
                return "ITYPE_LEFT_MANDATORY_GESTURES";
            case ITYPE_RIGHT_MANDATORY_GESTURES:
                return "ITYPE_RIGHT_MANDATORY_GESTURES";
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
            case ITYPE_CLIMATE_BAR:
                return "ITYPE_CLIMATE_BAR";
            case ITYPE_EXTRA_NAVIGATION_BAR:
                return "ITYPE_EXTRA_NAVIGATION_BAR";
            default:
                return "ITYPE_UNKNOWN_" + type;
        }
    }

    @Override
    public boolean equals(Object o) {
        return equals(o, false, false);
    }

    /**
     * An equals method can exclude the caption insets. This is useful because we assemble the
     * caption insets information on the client side, and when we communicate with server, it's
     * excluded.
     * @param excludingCaptionInsets {@code true} if we want to compare two InsetsState objects but
     *                                           ignore the caption insets source value.
     * @param excludeInvisibleImeFrames If {@link #ITYPE_IME} frames should be ignored when IME is
     *                                  not visible.
     * @return {@code true} if the two InsetsState objects are equal, {@code false} otherwise.
     */
    @VisibleForTesting
    public boolean equals(Object o, boolean excludingCaptionInsets,
            boolean excludeInvisibleImeFrames) {
        if (this == o) { return true; }
        if (o == null || getClass() != o.getClass()) { return false; }

        InsetsState state = (InsetsState) o;

        if (!mDisplayFrame.equals(state.mDisplayFrame)) {
            return false;
        }
        for (int i = 0; i < SIZE; i++) {
            if (excludingCaptionInsets) {
                if (i == ITYPE_CAPTION_BAR) continue;
            }
            InsetsSource source = mSources[i];
            InsetsSource otherSource = state.mSources[i];
            if (source == null && otherSource == null) {
                continue;
            }
            if (source != null && otherSource == null || source == null && otherSource != null) {
                return false;
            }
            if (!otherSource.equals(source, excludeInvisibleImeFrames)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mDisplayFrame, Arrays.hashCode(mSources));
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
        dest.writeParcelableArray(mSources, 0);
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
        mDisplayFrame.set(in.readParcelable(null /* loader */));
        mSources = in.readParcelableArray(null, InsetsSource.class);
    }

    @Override
    public String toString() {
        StringJoiner joiner = new StringJoiner(", ");
        for (int i = 0; i < SIZE; i++) {
            InsetsSource source = mSources[i];
            if (source != null) {
                joiner.add(source.toString());
            }
        }
        return "InsetsState: {"
                + "mDisplayFrame=" + mDisplayFrame
                + ", mSources= { " + joiner
                + " }";
    }
}

