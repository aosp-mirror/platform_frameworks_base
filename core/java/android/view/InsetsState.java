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

import static android.view.InsetsStateProto.DISPLAY_CUTOUT;
import static android.view.InsetsStateProto.DISPLAY_FRAME;
import static android.view.InsetsStateProto.SOURCES;
import static android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
import static android.view.WindowInsets.Type.displayCutout;
import static android.view.WindowInsets.Type.ime;
import static android.view.WindowInsets.Type.indexOf;
import static android.view.WindowInsets.Type.statusBars;
import static android.view.WindowInsets.Type.systemBars;
import static android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.WindowConfiguration;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArraySet;
import android.util.SparseIntArray;
import android.util.proto.ProtoOutputStream;
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
            ITYPE_EXTRA_NAVIGATION_BAR,
            ITYPE_LEFT_GENERIC_OVERLAY,
            ITYPE_TOP_GENERIC_OVERLAY,
            ITYPE_RIGHT_GENERIC_OVERLAY,
            ITYPE_BOTTOM_GENERIC_OVERLAY
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

    public static final int ITYPE_TOP_MANDATORY_GESTURES = 7;
    public static final int ITYPE_BOTTOM_MANDATORY_GESTURES = 8;
    public static final int ITYPE_LEFT_MANDATORY_GESTURES = 9;
    public static final int ITYPE_RIGHT_MANDATORY_GESTURES = 10;

    public static final int ITYPE_LEFT_DISPLAY_CUTOUT = 11;
    public static final int ITYPE_TOP_DISPLAY_CUTOUT = 12;
    public static final int ITYPE_RIGHT_DISPLAY_CUTOUT = 13;
    public static final int ITYPE_BOTTOM_DISPLAY_CUTOUT = 14;

    public static final int ITYPE_LEFT_TAPPABLE_ELEMENT = 15;
    public static final int ITYPE_TOP_TAPPABLE_ELEMENT = 16;
    public static final int ITYPE_RIGHT_TAPPABLE_ELEMENT = 17;
    public static final int ITYPE_BOTTOM_TAPPABLE_ELEMENT = 18;

    /** Input method window. */
    public static final int ITYPE_IME = 19;

    /** Additional system decorations inset type. */
    public static final int ITYPE_CLIMATE_BAR = 20;
    public static final int ITYPE_EXTRA_NAVIGATION_BAR = 21;

    /** Additional types for local insets. **/
    public static final int ITYPE_LEFT_GENERIC_OVERLAY = 22;
    public static final int ITYPE_TOP_GENERIC_OVERLAY = 23;
    public static final int ITYPE_RIGHT_GENERIC_OVERLAY = 24;
    public static final int ITYPE_BOTTOM_GENERIC_OVERLAY = 25;

    static final int LAST_TYPE = ITYPE_BOTTOM_GENERIC_OVERLAY;
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

    private final InsetsSource[] mSources = new InsetsSource[SIZE];

    /**
     * The frame of the display these sources are relative to.
     */
    private final Rect mDisplayFrame = new Rect();

    /** The area cut from the display. */
    private final DisplayCutout.ParcelableWrapper mDisplayCutout =
            new DisplayCutout.ParcelableWrapper();

    /**
     * The frame that rounded corners are relative to.
     *
     * There are 2 cases that will draw fake rounded corners:
     *   1. In split-screen mode
     *   2. Devices with a task bar
     * We need to report these fake rounded corners to apps by re-calculating based on this frame.
     */
    private final Rect mRoundedCornerFrame = new Rect();

    /** The rounded corners on the display */
    private RoundedCorners mRoundedCorners = RoundedCorners.NO_ROUNDED_CORNERS;

    /** The bounds of the Privacy Indicator */
    private PrivacyIndicatorBounds mPrivacyIndicatorBounds =
            new PrivacyIndicatorBounds();

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
            boolean isScreenRound, boolean alwaysConsumeSystemBars,
            int legacySoftInputMode, int legacyWindowFlags, int legacySystemUiFlags,
            int windowType, @WindowConfiguration.WindowingMode int windowingMode,
            @Nullable @InternalInsetsSide SparseIntArray typeSideMap) {
        Insets[] typeInsetsMap = new Insets[Type.SIZE];
        Insets[] typeMaxInsetsMap = new Insets[Type.SIZE];
        boolean[] typeVisibilityMap = new boolean[Type.SIZE];
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
        if (clearsCompatInsets(windowType, legacyWindowFlags, windowingMode)) {
            compatInsetsTypes = 0;
        }

        return new WindowInsets(typeInsetsMap, typeMaxInsetsMap, typeVisibilityMap, isScreenRound,
                alwaysConsumeSystemBars, calculateRelativeCutout(frame),
                calculateRelativeRoundedCorners(frame),
                calculateRelativePrivacyIndicatorBounds(frame),
                compatInsetsTypes, (legacySystemUiFlags & SYSTEM_UI_FLAG_LAYOUT_STABLE) != 0);
    }

    private DisplayCutout calculateRelativeCutout(Rect frame) {
        final DisplayCutout raw = mDisplayCutout.get();
        if (mDisplayFrame.equals(frame)) {
            return raw;
        }
        if (frame == null) {
            return DisplayCutout.NO_CUTOUT;
        }
        final int insetLeft = frame.left - mDisplayFrame.left;
        final int insetTop = frame.top - mDisplayFrame.top;
        final int insetRight = mDisplayFrame.right - frame.right;
        final int insetBottom = mDisplayFrame.bottom - frame.bottom;
        if (insetLeft >= raw.getSafeInsetLeft()
                && insetTop >= raw.getSafeInsetTop()
                && insetRight >= raw.getSafeInsetRight()
                && insetBottom >= raw.getSafeInsetBottom()) {
            return DisplayCutout.NO_CUTOUT;
        }
        return raw.inset(insetLeft, insetTop, insetRight, insetBottom);
    }

    private RoundedCorners calculateRelativeRoundedCorners(Rect frame) {
        if (frame == null) {
            return RoundedCorners.NO_ROUNDED_CORNERS;
        }
        // If mRoundedCornerFrame is set, we should calculate the new RoundedCorners based on this
        // frame.
        final Rect roundedCornerFrame = new Rect(mRoundedCornerFrame);
        for (InsetsSource source : mSources) {
            if (source != null && source.getInsetsRoundedCornerFrame()) {
                final Insets insets = source.calculateInsets(roundedCornerFrame, false);
                roundedCornerFrame.inset(insets);
            }
        }
        if (!roundedCornerFrame.isEmpty() && !roundedCornerFrame.equals(mDisplayFrame)) {
            return mRoundedCorners.insetWithFrame(frame, roundedCornerFrame);
        }
        if (mDisplayFrame.equals(frame)) {
            return mRoundedCorners;
        }
        final int insetLeft = frame.left - mDisplayFrame.left;
        final int insetTop = frame.top - mDisplayFrame.top;
        final int insetRight = mDisplayFrame.right - frame.right;
        final int insetBottom = mDisplayFrame.bottom - frame.bottom;
        return mRoundedCorners.inset(insetLeft, insetTop, insetRight, insetBottom);
    }

    private PrivacyIndicatorBounds calculateRelativePrivacyIndicatorBounds(Rect frame) {
        if (mDisplayFrame.equals(frame)) {
            return mPrivacyIndicatorBounds;
        }
        if (frame == null) {
            return null;
        }
        final int insetLeft = frame.left - mDisplayFrame.left;
        final int insetTop = frame.top - mDisplayFrame.top;
        final int insetRight = mDisplayFrame.right - frame.right;
        final int insetBottom = mDisplayFrame.bottom - frame.bottom;
        return mPrivacyIndicatorBounds.inset(insetLeft, insetTop, insetRight, insetBottom);
    }

    public Insets calculateInsets(Rect frame, @InsetsType int types, boolean ignoreVisibility) {
        Insets insets = Insets.NONE;
        for (int type = FIRST_TYPE; type <= LAST_TYPE; type++) {
            InsetsSource source = mSources[type];
            if (source == null) {
                continue;
            }
            int publicType = InsetsState.toPublicType(type);
            if ((publicType & types) == 0) {
                continue;
            }
            insets = Insets.max(source.calculateInsets(frame, ignoreVisibility), insets);
        }
        return insets;
    }

    public Insets calculateInsets(Rect frame, @InsetsType int types,
            InsetsVisibilities overrideVisibilities) {
        Insets insets = Insets.NONE;
        for (int type = FIRST_TYPE; type <= LAST_TYPE; type++) {
            InsetsSource source = mSources[type];
            if (source == null) {
                continue;
            }
            int publicType = InsetsState.toPublicType(type);
            if ((publicType & types) == 0) {
                continue;
            }
            if (!overrideVisibilities.getVisibility(type)) {
                continue;
            }
            insets = Insets.max(source.calculateInsets(frame, true), insets);
        }
        return insets;
    }

    public Insets calculateVisibleInsets(Rect frame, int windowType, int windowingMode,
            @SoftInputModeFlags int softInputMode, int windowFlags) {
        if (clearsCompatInsets(windowType, windowFlags, windowingMode)) {
            return Insets.NONE;
        }
        final int softInputAdjustMode = softInputMode & SOFT_INPUT_MASK_ADJUST;
        final int visibleInsetsTypes = softInputAdjustMode != SOFT_INPUT_ADJUST_NOTHING
                ? systemBars() | ime()
                : systemBars();
        Insets insets = Insets.NONE;
        for (int type = FIRST_TYPE; type <= LAST_TYPE; type++) {
            InsetsSource source = mSources[type];
            if (source == null) {
                continue;
            }
            final int publicType = InsetsState.toPublicType(type);
            if ((publicType & visibleInsetsTypes) == 0) {
                continue;
            }
            insets = Insets.max(source.calculateVisibleInsets(frame), insets);
        }
        return insets;
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
            if (!canControlSource(frame, source)) {
                blocked |= toPublicType(type);
            }
        }
        return blocked;
    }

    private static boolean canControlSource(Rect frame, InsetsSource source) {
        final Insets insets = source.calculateInsets(frame, true /* ignoreVisibility */);
        final Rect sourceFrame = source.getFrame();
        final int sourceWidth = sourceFrame.width();
        final int sourceHeight = sourceFrame.height();
        return insets.left == sourceWidth || insets.right == sourceWidth
                || insets.top == sourceHeight || insets.bottom == sourceHeight;
    }

    private void processSource(InsetsSource source, Rect relativeFrame, boolean ignoreVisibility,
            Insets[] typeInsetsMap, @Nullable @InternalInsetsSide SparseIntArray typeSideMap,
            @Nullable boolean[] typeVisibilityMap) {
        Insets insets = source.calculateInsets(relativeFrame, ignoreVisibility);

        int type = toPublicType(source.getType());
        processSourceAsPublicType(source, typeInsetsMap, typeSideMap, typeVisibilityMap,
                insets, type);

        if (type == Type.MANDATORY_SYSTEM_GESTURES) {
            // Mandatory system gestures are also system gestures.
            // TODO: find a way to express this more generally. One option would be to define
            //       Type.systemGestureInsets() as NORMAL | MANDATORY, but then we lose the
            //       ability to set systemGestureInsets() independently from
            //       mandatorySystemGestureInsets() in the Builder.
            processSourceAsPublicType(source, typeInsetsMap, typeSideMap, typeVisibilityMap,
                    insets, Type.SYSTEM_GESTURES);
        }
        if (type == Type.CAPTION_BAR) {
            // Caption should also be gesture and tappable elements. This should not be needed when
            // the caption is added from the shell, as the shell can add other types at the same
            // time.
            processSourceAsPublicType(source, typeInsetsMap, typeSideMap, typeVisibilityMap,
                    insets, Type.SYSTEM_GESTURES);
            processSourceAsPublicType(source, typeInsetsMap, typeSideMap, typeVisibilityMap,
                    insets, Type.MANDATORY_SYSTEM_GESTURES);
            processSourceAsPublicType(source, typeInsetsMap, typeSideMap, typeVisibilityMap,
                    insets, Type.TAPPABLE_ELEMENT);
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
    static @InternalInsetsSide int getInsetSide(Insets insets) {
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

    public void setDisplayCutout(DisplayCutout cutout) {
        mDisplayCutout.set(cutout);
    }

    public DisplayCutout getDisplayCutout() {
        return mDisplayCutout.get();
    }

    public void getDisplayCutoutSafe(Rect outBounds) {
        outBounds.set(
                WindowLayout.MIN_X, WindowLayout.MIN_Y, WindowLayout.MAX_X, WindowLayout.MAX_Y);
        final DisplayCutout cutout = mDisplayCutout.get();
        final Rect displayFrame = mDisplayFrame;
        if (!cutout.isEmpty()) {
            if (cutout.getSafeInsetLeft() > 0) {
                outBounds.left = displayFrame.left + cutout.getSafeInsetLeft();
            }
            if (cutout.getSafeInsetTop() > 0) {
                outBounds.top = displayFrame.top + cutout.getSafeInsetTop();
            }
            if (cutout.getSafeInsetRight() > 0) {
                outBounds.right = displayFrame.right - cutout.getSafeInsetRight();
            }
            if (cutout.getSafeInsetBottom() > 0) {
                outBounds.bottom = displayFrame.bottom - cutout.getSafeInsetBottom();
            }
        }
    }

    public void setRoundedCorners(RoundedCorners roundedCorners) {
        mRoundedCorners = roundedCorners;
    }

    public RoundedCorners getRoundedCorners() {
        return mRoundedCorners;
    }

    /**
     * Set the frame that will be used to calculate the rounded corners.
     *
     * @see #mRoundedCornerFrame
     */
    public void setRoundedCornerFrame(Rect frame) {
        mRoundedCornerFrame.set(frame);
    }

    public void setPrivacyIndicatorBounds(PrivacyIndicatorBounds bounds) {
        mPrivacyIndicatorBounds = bounds;
    }

    public PrivacyIndicatorBounds getPrivacyIndicatorBounds() {
        return mPrivacyIndicatorBounds;
    }

    /**
     * Modifies the state of this class to exclude a certain type to make it ready for dispatching
     * to the client.
     *
     * @param type The {@link InternalInsetsType} of the source to remove
     * @return {@code true} if this InsetsState was modified; {@code false} otherwise.
     */
    public boolean removeSource(@InternalInsetsType int type) {
        if (mSources[type] == null) {
            return false;
        }
        mSources[type] = null;
        return true;
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

    /**
     * Scales the frame and the visible frame (if there is one) of each source.
     *
     * @param scale the scale to be applied
     */
    public void scale(float scale) {
        mDisplayFrame.scale(scale);
        mDisplayCutout.scale(scale);
        mRoundedCorners = mRoundedCorners.scale(scale);
        mRoundedCornerFrame.scale(scale);
        mPrivacyIndicatorBounds = mPrivacyIndicatorBounds.scale(scale);
        for (int i = 0; i < SIZE; i++) {
            final InsetsSource source = mSources[i];
            if (source != null) {
                source.getFrame().scale(scale);
                final Rect visibleFrame = source.getVisibleFrame();
                if (visibleFrame != null) {
                    visibleFrame.scale(scale);
                }
            }
        }
    }

    public void set(InsetsState other) {
        set(other, false /* copySources */);
    }

    public void set(InsetsState other, boolean copySources) {
        mDisplayFrame.set(other.mDisplayFrame);
        mDisplayCutout.set(other.mDisplayCutout);
        mRoundedCorners = other.getRoundedCorners();
        mRoundedCornerFrame.set(other.mRoundedCornerFrame);
        mPrivacyIndicatorBounds = other.getPrivacyIndicatorBounds();
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

    /**
     * Sets the values from the other InsetsState. But for sources, only specific types of source
     * would be set.
     *
     * @param other the other InsetsState.
     * @param types the only types of sources would be set.
     */
    public void set(InsetsState other, @InsetsType int types) {
        mDisplayFrame.set(other.mDisplayFrame);
        mDisplayCutout.set(other.mDisplayCutout);
        mRoundedCorners = other.getRoundedCorners();
        mRoundedCornerFrame.set(other.mRoundedCornerFrame);
        mPrivacyIndicatorBounds = other.getPrivacyIndicatorBounds();
        final ArraySet<Integer> t = toInternalType(types);
        for (int i = t.size() - 1; i >= 0; i--) {
            final int type = t.valueAt(i);
            mSources[type] = other.mSources[type];
        }
    }

    public void addSource(InsetsSource source) {
        mSources[source.getType()] = source;
    }

    public static boolean clearsCompatInsets(int windowType, int windowFlags, int windowingMode) {
        return (windowFlags & FLAG_LAYOUT_NO_LIMITS) != 0
                && windowType != TYPE_WALLPAPER && windowType != TYPE_SYSTEM_ERROR
                && !WindowConfiguration.inMultiWindowMode(windowingMode);
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
        if ((types & Type.GENERIC_OVERLAYS) != 0) {
            result.add(ITYPE_LEFT_GENERIC_OVERLAY);
            result.add(ITYPE_TOP_GENERIC_OVERLAY);
            result.add(ITYPE_RIGHT_GENERIC_OVERLAY);
            result.add(ITYPE_BOTTOM_GENERIC_OVERLAY);
        }
        if ((types & Type.CAPTION_BAR) != 0) {
            result.add(ITYPE_CAPTION_BAR);
        }
        if ((types & Type.SYSTEM_GESTURES) != 0) {
            result.add(ITYPE_LEFT_GESTURES);
            result.add(ITYPE_TOP_GESTURES);
            result.add(ITYPE_RIGHT_GESTURES);
            result.add(ITYPE_BOTTOM_GESTURES);
        }
        if ((types & Type.MANDATORY_SYSTEM_GESTURES) != 0) {
            result.add(ITYPE_LEFT_MANDATORY_GESTURES);
            result.add(ITYPE_TOP_MANDATORY_GESTURES);
            result.add(ITYPE_RIGHT_MANDATORY_GESTURES);
            result.add(ITYPE_BOTTOM_MANDATORY_GESTURES);
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
            case ITYPE_LEFT_GENERIC_OVERLAY:
            case ITYPE_TOP_GENERIC_OVERLAY:
            case ITYPE_RIGHT_GENERIC_OVERLAY:
            case ITYPE_BOTTOM_GENERIC_OVERLAY:
                return Type.GENERIC_OVERLAYS;
            case ITYPE_CAPTION_BAR:
                return Type.CAPTION_BAR;
            case ITYPE_IME:
                return Type.IME;
            case ITYPE_TOP_MANDATORY_GESTURES:
            case ITYPE_BOTTOM_MANDATORY_GESTURES:
            case ITYPE_LEFT_MANDATORY_GESTURES:
            case ITYPE_RIGHT_MANDATORY_GESTURES:
                return Type.MANDATORY_SYSTEM_GESTURES;
            case ITYPE_TOP_GESTURES:
            case ITYPE_BOTTOM_GESTURES:
            case ITYPE_LEFT_GESTURES:
            case ITYPE_RIGHT_GESTURES:
                return Type.SYSTEM_GESTURES;
            case ITYPE_LEFT_TAPPABLE_ELEMENT:
            case ITYPE_TOP_TAPPABLE_ELEMENT:
            case ITYPE_RIGHT_TAPPABLE_ELEMENT:
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
        final String newPrefix = prefix + "  ";
        pw.println(prefix + "InsetsState");
        pw.println(newPrefix + "mDisplayFrame=" + mDisplayFrame);
        pw.println(newPrefix + "mDisplayCutout=" + mDisplayCutout.get());
        pw.println(newPrefix + "mRoundedCorners=" + mRoundedCorners);
        pw.println(newPrefix + "mRoundedCornerFrame=" + mRoundedCornerFrame);
        pw.println(newPrefix + "mPrivacyIndicatorBounds=" + mPrivacyIndicatorBounds);
        for (int i = 0; i < SIZE; i++) {
            InsetsSource source = mSources[i];
            if (source == null) continue;
            source.dump(newPrefix + "  ", pw);
        }
    }

    void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        InsetsSource source = mSources[ITYPE_IME];
        if (source != null) {
            source.dumpDebug(proto, SOURCES);
        }
        mDisplayFrame.dumpDebug(proto, DISPLAY_FRAME);
        mDisplayCutout.get().dumpDebug(proto, DISPLAY_CUTOUT);
        proto.end(token);
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
            case ITYPE_LEFT_TAPPABLE_ELEMENT:
                return "ITYPE_LEFT_TAPPABLE_ELEMENT";
            case ITYPE_TOP_TAPPABLE_ELEMENT:
                return "ITYPE_TOP_TAPPABLE_ELEMENT";
            case ITYPE_RIGHT_TAPPABLE_ELEMENT:
                return "ITYPE_RIGHT_TAPPABLE_ELEMENT";
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
            case ITYPE_LEFT_GENERIC_OVERLAY:
                return "ITYPE_LEFT_GENERIC_OVERLAY";
            case ITYPE_TOP_GENERIC_OVERLAY:
                return "ITYPE_TOP_GENERIC_OVERLAY";
            case ITYPE_RIGHT_GENERIC_OVERLAY:
                return "ITYPE_RIGHT_GENERIC_OVERLAY";
            case ITYPE_BOTTOM_GENERIC_OVERLAY:
                return "ITYPE_BOTTOM_GENERIC_OVERLAY";
            default:
                return "ITYPE_UNKNOWN_" + type;
        }
    }

    @Override
    public boolean equals(@Nullable Object o) {
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
    public boolean equals(@Nullable Object o, boolean excludingCaptionInsets,
            boolean excludeInvisibleImeFrames) {
        if (this == o) { return true; }
        if (o == null || getClass() != o.getClass()) { return false; }

        InsetsState state = (InsetsState) o;

        if (!mDisplayFrame.equals(state.mDisplayFrame)
                || !mDisplayCutout.equals(state.mDisplayCutout)
                || !mRoundedCorners.equals(state.mRoundedCorners)
                || !mRoundedCornerFrame.equals(state.mRoundedCornerFrame)
                || !mPrivacyIndicatorBounds.equals(state.mPrivacyIndicatorBounds)) {
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
            if (excludeInvisibleImeFrames && i == ITYPE_IME
                    && ((source == null && !otherSource.isVisible())
                            || (otherSource == null && !source.isVisible()))) {
                continue;
            }
            if (source == null || otherSource == null) {
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
        return Objects.hash(mDisplayFrame, mDisplayCutout, Arrays.hashCode(mSources),
                mRoundedCorners, mPrivacyIndicatorBounds, mRoundedCornerFrame);
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
        mDisplayFrame.writeToParcel(dest, flags);
        mDisplayCutout.writeToParcel(dest, flags);
        dest.writeTypedArray(mSources, 0 /* parcelableFlags */);
        dest.writeTypedObject(mRoundedCorners, flags);
        mRoundedCornerFrame.writeToParcel(dest, flags);
        dest.writeTypedObject(mPrivacyIndicatorBounds, flags);
    }

    public static final @NonNull Creator<InsetsState> CREATOR = new Creator<InsetsState>() {

        public InsetsState createFromParcel(Parcel in) {
            return new InsetsState(in);
        }

        public InsetsState[] newArray(int size) {
            return new InsetsState[size];
        }
    };

    public void readFromParcel(Parcel in) {
        mDisplayFrame.readFromParcel(in);
        mDisplayCutout.readFromParcel(in);
        in.readTypedArray(mSources, InsetsSource.CREATOR);
        mRoundedCorners = in.readTypedObject(RoundedCorners.CREATOR);
        mRoundedCornerFrame.readFromParcel(in);
        mPrivacyIndicatorBounds = in.readTypedObject(PrivacyIndicatorBounds.CREATOR);
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
                + ", mDisplayCutout=" + mDisplayCutout
                + ", mRoundedCorners=" + mRoundedCorners
                + "  mRoundedCornerFrame=" + mRoundedCornerFrame
                + ", mPrivacyIndicatorBounds=" + mPrivacyIndicatorBounds
                + ", mSources= { " + joiner
                + " }";
    }
}

