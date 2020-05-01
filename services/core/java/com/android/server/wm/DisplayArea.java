/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.wm;

import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_BEHIND;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSET;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.view.WindowManager.TRANSIT_KEYGUARD_UNOCCLUDE;
import static android.view.WindowManagerPolicyConstants.APPLICATION_LAYER;
import static android.window.DisplayAreaOrganizer.FEATURE_ROOT;
import static android.window.DisplayAreaOrganizer.FEATURE_UNDEFINED;
import static android.window.DisplayAreaOrganizer.FEATURE_WINDOW_TOKENS;

import static com.android.internal.util.Preconditions.checkState;
import static com.android.server.wm.DisplayAreaProto.NAME;
import static com.android.server.wm.DisplayAreaProto.WINDOW_CONTAINER;
import static com.android.server.wm.ProtoLogGroup.WM_DEBUG_ORIENTATION;
import static com.android.server.wm.WindowContainerChildProto.DISPLAY_AREA;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.util.proto.ProtoOutputStream;
import android.window.DisplayAreaInfo;
import android.window.IDisplayAreaOrganizer;

import com.android.server.policy.WindowManagerPolicy;
import com.android.server.protolog.common.ProtoLog;

import java.util.Comparator;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Container for grouping WindowContainer below DisplayContent.
 *
 * DisplayAreas are managed by a {@link DisplayAreaPolicy}, and can override configurations and
 * can be leashed.
 *
 * DisplayAreas can contain nested DisplayAreas.
 *
 * DisplayAreas come in three flavors, to ensure that windows have the right Z-Order:
 * - BELOW_TASKS: Can only contain BELOW_TASK DisplayAreas and WindowTokens that go below tasks.
 * - ABOVE_TASKS: Can only contain ABOVE_TASK DisplayAreas and WindowTokens that go above tasks.
 * - ANY: Can contain any kind of DisplayArea, and any kind of WindowToken or the Task container.
 *
 * @param <T> type of the children of the DisplayArea.
 */
public class DisplayArea<T extends WindowContainer> extends WindowContainer<T> {

    protected final Type mType;
    private final String mName;
    final int mFeatureId;
    private final DisplayAreaOrganizerController mOrganizerController;
    IDisplayAreaOrganizer mOrganizer;

    DisplayArea(WindowManagerService wms, Type type, String name) {
        this(wms, type, name, FEATURE_UNDEFINED);
    }

    DisplayArea(WindowManagerService wms, Type type, String name, int featureId) {
        super(wms);
        // TODO(display-area): move this up to ConfigurationContainer
        mOrientation = SCREEN_ORIENTATION_UNSET;
        mType = type;
        mName = name;
        mFeatureId = featureId;
        mRemoteToken = new RemoteToken(this);
        mOrganizerController =
                wms.mAtmService.mWindowOrganizerController.mDisplayAreaOrganizerController;
    }

    @Override
    void onChildPositionChanged(WindowContainer child) {
        super.onChildPositionChanged(child);

        // Verify that we have proper ordering
        Type.checkChild(mType, Type.typeOf(child));

        if (child instanceof ActivityStack) {
            // TODO(display-area): ActivityStacks are type ANY, but are allowed to have siblings.
            //                     They might need a separate type.
            return;
        }

        for (int i = 1; i < getChildCount(); i++) {
            final WindowContainer top = getChildAt(i - 1);
            final WindowContainer bottom = getChildAt(i);
            if (child == top || child == bottom) {
                Type.checkSiblings(Type.typeOf(top), Type.typeOf(bottom));
            }
        }
    }

    @Override
    boolean fillsParent() {
        return true;
    }

    @Override
    String getName() {
        return mName;
    }

    @Override
    public String toString() {
        return mName + "@" + System.identityHashCode(this);
    }

    @Override
    public final void dumpDebug(ProtoOutputStream proto, long fieldId, int logLevel) {
        final long token = proto.start(fieldId);
        super.dumpDebug(proto, WINDOW_CONTAINER, logLevel);
        proto.write(NAME, mName);
        proto.end(token);
    }

    @Override
    long getProtoFieldId() {
        return DISPLAY_AREA;
    }

    void forAllDisplayAreas(Consumer<DisplayArea> callback) {
        super.forAllDisplayAreas(callback);
        callback.accept(this);
    }

    void setOrganizer(IDisplayAreaOrganizer organizer) {
        if (mOrganizer == organizer) return;
        IDisplayAreaOrganizer lastOrganizer = mOrganizer;
        // Update the new display area organizer before calling sendDisplayAreaVanished since it
        // could result in a new SurfaceControl getting created that would notify the old organizer
        // about it.
        mOrganizer = organizer;
        sendDisplayAreaVanished(lastOrganizer);
        sendDisplayAreaAppeared();
    }

    void sendDisplayAreaAppeared() {
        if (mOrganizer == null) return;
        mOrganizerController.onDisplayAreaAppeared(mOrganizer, this);
    }

    void sendDisplayAreaVanished(IDisplayAreaOrganizer organizer) {
        if (organizer == null) return;
        migrateToNewSurfaceControl();
        mOrganizerController.onDisplayAreaVanished(organizer, this);
    }

    @Override
    public void onConfigurationChanged(Configuration newParentConfig) {
        super.onConfigurationChanged(newParentConfig);
        if (mOrganizer != null) {
            mOrganizerController.onDisplayAreaInfoChanged(mOrganizer, this);
        }
    }

    @Override
    boolean isOrganized() {
        return mOrganizer != null;
    }


    DisplayAreaInfo getDisplayAreaInfo() {
        DisplayAreaInfo info = new DisplayAreaInfo(mRemoteToken.toWindowContainerToken(),
                getDisplayContent().getDisplayId(), mFeatureId);
        info.configuration.setTo(getConfiguration());
        return info;
    }

    /**
     * DisplayArea that contains WindowTokens, and orders them according to their type.
     */
    public static class Tokens extends DisplayArea<WindowToken> {
        int mLastKeyguardForcedOrientation = SCREEN_ORIENTATION_UNSPECIFIED;

        private final Comparator<WindowToken> mWindowComparator =
                Comparator.comparingInt(WindowToken::getWindowLayerFromType);

        private final Predicate<WindowState> mGetOrientingWindow = w -> {
            final WindowManagerPolicy policy = mWmService.mPolicy;
            if (policy.isKeyguardHostWindow(w.mAttrs)) {
                if (mWmService.mKeyguardGoingAway) {
                    return false;
                }
                // Consider unoccluding only when all unknown visibilities have been
                // resolved, as otherwise we just may be starting another occluding activity.
                final boolean isUnoccluding =
                        mDisplayContent.mAppTransition.getAppTransition()
                                == TRANSIT_KEYGUARD_UNOCCLUDE
                                && mDisplayContent.mUnknownAppVisibilityController.allResolved();
                // If keyguard is showing, or we're unoccluding, force the keyguard's orientation,
                // even if SystemUI hasn't updated the attrs yet.
                if (policy.isKeyguardShowingAndNotOccluded() || isUnoccluding) {
                    return true;
                }
            }
            final int req = w.mAttrs.screenOrientation;
            if (req == SCREEN_ORIENTATION_UNSPECIFIED || req == SCREEN_ORIENTATION_BEHIND
                    || req == SCREEN_ORIENTATION_UNSET) {
                return false;
            }
            return true;
        };

        Tokens(WindowManagerService wms, Type type, String name) {
            super(wms, type, name, FEATURE_WINDOW_TOKENS);
        }

        void addChild(WindowToken token) {
            addChild(token, mWindowComparator);
        }

        @Override
        int getOrientation(int candidate) {
            // Find a window requesting orientation.
            final WindowState win = getWindow(mGetOrientingWindow);

            if (win == null) {
                return candidate;
            }
            int req = win.mAttrs.screenOrientation;
            ProtoLog.v(WM_DEBUG_ORIENTATION, "%s forcing orientation to %d for display id=%d",
                    win, req, mDisplayContent.getDisplayId());
            if (mWmService.mPolicy.isKeyguardHostWindow(win.mAttrs)) {
                // SystemUI controls the Keyguard orientation asynchronously, and mAttrs may be
                // stale. We record / use the last known override.
                if (req != SCREEN_ORIENTATION_UNSET && req != SCREEN_ORIENTATION_UNSPECIFIED) {
                    mLastKeyguardForcedOrientation = req;
                } else {
                    req = mLastKeyguardForcedOrientation;
                }
            }
            return req;
        }
    }

    @Override
    DisplayArea getDisplayArea() {
        return this;
    }

    /**
     * Top-most DisplayArea under DisplayContent.
     */
    public static class Root extends DisplayArea<DisplayArea> {
        private final Dimmer mDimmer = new Dimmer(this);
        private final Rect mTmpDimBoundsRect = new Rect();

        Root(WindowManagerService wms) {
            super(wms, Type.ANY, "DisplayArea.Root", FEATURE_ROOT);
        }

        @Override
        Dimmer getDimmer() {
            return mDimmer;
        }

        @Override
        void prepareSurfaces() {
            mDimmer.resetDimStates();
            super.prepareSurfaces();
            getBounds(mTmpDimBoundsRect);

            // If SystemUI is dragging for recents, we want to reset the dim state so any dim layer
            // on the display level fades out.
            if (forAllTasks(task -> !task.canAffectSystemUiFlags())) {
                mDimmer.resetDimStates();
            }

            if (mDimmer.updateDims(getPendingTransaction(), mTmpDimBoundsRect)) {
                scheduleAnimation();
            }
        }
    }

    enum Type {
        /** Can only contain WindowTokens above the APPLICATION_LAYER. */
        ABOVE_TASKS,
        /** Can only contain WindowTokens below the APPLICATION_LAYER. */
        BELOW_TASKS,
        /** Can contain anything. */
        ANY;

        static void checkSiblings(Type bottom, Type top) {
            checkState(!(bottom != BELOW_TASKS && top == BELOW_TASKS),
                    bottom + " must be above BELOW_TASKS");
            checkState(!(bottom == ABOVE_TASKS && top != ABOVE_TASKS),
                    top + " must be below ABOVE_TASKS");
        }

        static void checkChild(Type parent, Type child) {
            switch (parent) {
                case ABOVE_TASKS:
                    checkState(child == ABOVE_TASKS, "ABOVE_TASKS can only contain ABOVE_TASKS");
                    break;
                case BELOW_TASKS:
                    checkState(child == BELOW_TASKS, "BELOW_TASKS can only contain BELOW_TASKS");
                    break;
            }
        }

        static Type typeOf(WindowContainer c) {
            if (c instanceof DisplayArea) {
                return ((DisplayArea) c).mType;
            } else if (c instanceof WindowToken && !(c instanceof ActivityRecord)) {
                return typeOf((WindowToken) c);
            } else if (c instanceof ActivityStack) {
                return ANY;
            } else {
                throw new IllegalArgumentException("Unknown container: " + c);
            }
        }

        private static Type typeOf(WindowToken c) {
            return c.getWindowLayerFromType() < APPLICATION_LAYER ? BELOW_TASKS : ABOVE_TASKS;
        }
    }
}
