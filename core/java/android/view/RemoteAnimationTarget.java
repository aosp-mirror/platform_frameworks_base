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
 * limitations under the License
 */

package android.view;

import static android.graphics.GraphicsProtos.dumpPointProto;
import static android.view.RemoteAnimationTargetProto.CLIP_RECT;
import static android.view.RemoteAnimationTargetProto.CONTENT_INSETS;
import static android.view.RemoteAnimationTargetProto.IS_TRANSLUCENT;
import static android.view.RemoteAnimationTargetProto.LEASH;
import static android.view.RemoteAnimationTargetProto.LOCAL_BOUNDS;
import static android.view.RemoteAnimationTargetProto.MODE;
import static android.view.RemoteAnimationTargetProto.POSITION;
import static android.view.RemoteAnimationTargetProto.PREFIX_ORDER_INDEX;
import static android.view.RemoteAnimationTargetProto.SCREEN_SPACE_BOUNDS;
import static android.view.RemoteAnimationTargetProto.SOURCE_CONTAINER_BOUNDS;
import static android.view.RemoteAnimationTargetProto.START_BOUNDS;
import static android.view.RemoteAnimationTargetProto.START_LEASH;
import static android.view.RemoteAnimationTargetProto.TASK_ID;
import static android.view.RemoteAnimationTargetProto.WINDOW_CONFIGURATION;
import static android.view.WindowManager.LayoutParams.INVALID_WINDOW_TYPE;

import android.annotation.IntDef;
import android.app.ActivityManager;
import android.app.TaskInfo;
import android.app.WindowConfiguration;
import android.compat.annotation.UnsupportedAppUsage;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.proto.ProtoOutputStream;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Describes an activity to be animated as part of a remote animation.
 *
 * @hide
 */
public class RemoteAnimationTarget implements Parcelable {

    /**
     * The app is in the set of opening apps of this transition.
     */
    public static final int MODE_OPENING = 0;

    /**
     * The app is in the set of closing apps of this transition.
     */
    public static final int MODE_CLOSING = 1;

    /**
     * The app is in the set of resizing apps (eg. mode change) of this transition.
     */
    public static final int MODE_CHANGING = 2;

    @IntDef(prefix = { "MODE_" }, value = {
            MODE_OPENING,
            MODE_CLOSING,
            MODE_CHANGING
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Mode {}

    /**
     * The {@link Mode} to describe whether this app is opening or closing.
     */
    @UnsupportedAppUsage
    public final @Mode int mode;

    /**
     * The id of the task this app belongs to.
     */
    @UnsupportedAppUsage
    public final int taskId;

    /**
     * The {@link SurfaceControl} object to actually control the transform of the app.
     */
    @UnsupportedAppUsage
    public final SurfaceControl leash;

    /**
     * The {@link SurfaceControl} for the starting state of a target if this transition is
     * MODE_CHANGING, {@code null)} otherwise. This is relative to the app window.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public final SurfaceControl startLeash;

    /**
     * Whether the app is translucent and may reveal apps behind.
     */
    @UnsupportedAppUsage
    public final boolean isTranslucent;

    /**
     * The clip rect window manager applies when clipping the app's main surface in screen space
     * coordinates. This is just a hint to the animation runner: If running a clip-rect animation,
     * anything that extends beyond these bounds will not have any effect. This implies that any
     * clip-rect animation should likely stop at these bounds.
     */
    @UnsupportedAppUsage
    public final Rect clipRect;

    /**
     * The insets of the main app window.
     */
    @UnsupportedAppUsage
    public final Rect contentInsets;

    /**
     * The index of the element in the tree in prefix order. This should be used for z-layering
     * to preserve original z-layer order in the hierarchy tree assuming no "boosting" needs to
     * happen.
     */
    @UnsupportedAppUsage
    public final int prefixOrderIndex;

    /**
     * The source position of the app, in screen spaces coordinates. If the position of the leash
     * is modified from the controlling app, any animation transform needs to be offset by this
     * amount.
     * @deprecated Use {@link #localBounds} instead.
     */
    @Deprecated
    @UnsupportedAppUsage
    public final Point position;

    /**
     * Bounds of the target relative to its parent.
     * When the app target animating on its parent, we need to use the local coordinates relative to
     * its parent with {@code localBounds.left} & {@code localBounds.top} rather than using
     * {@code position} in screen coordinates.
     */
    public final Rect localBounds;

    /**
     * The bounds of the source container the app lives in, in screen space coordinates. If the crop
     * of the leash is modified from the controlling app, it needs to take the source container
     * bounds into account when calculating the crop.
     * @deprecated Renamed to {@link #screenSpaceBounds}
     */
    @Deprecated
    @UnsupportedAppUsage
    public final Rect sourceContainerBounds;

    /**
     * Bounds of the target relative to the screen. If the crop of the leash is modified from the
     * controlling app, it needs to take the screen space bounds into account when calculating the
     * crop.
     */
    public final Rect screenSpaceBounds;

    /**
     * The starting bounds of the source container in screen space coordinates. This is {@code null}
     * if the animation target isn't MODE_CHANGING. Since this is the starting bounds, it's size
     * should be equivalent to the size of the starting thumbnail. Note that sourceContainerBounds
     * is the end bounds of a change transition.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public final Rect startBounds;

    /**
     * The window configuration for the target.
     */
    @UnsupportedAppUsage
    public final WindowConfiguration windowConfiguration;

    /**
     * Whether the task is not presented in Recents UI.
     */
    @UnsupportedAppUsage
    public boolean isNotInRecents;

    /**
     * {@link TaskInfo} to allow the controller to identify information about the task.
     *
     * TODO: add this to proto dump
     */
    public ActivityManager.RunningTaskInfo taskInfo;

    /**
     * {@code true} if picture-in-picture permission is granted in {@link android.app.AppOpsManager}
     */
    @UnsupportedAppUsage
    public boolean allowEnterPip;

    /**
     * The {@link android.view.WindowManager.LayoutParams.WindowType} of this window. It's only used
     * for non-app window.
     */
    public final @WindowManager.LayoutParams.WindowType int windowType;

    /**
     * {@code true} if its parent is also a {@link RemoteAnimationTarget} in the same transition.
     *
     * For example, when a TaskFragment is resizing while one of its children is open/close, both
     * windows will be animation targets. This value will be {@code true} for the child, so that
     * the handler can choose to handle it differently.
     */
    public boolean hasAnimatingParent;

    public RemoteAnimationTarget(int taskId, int mode, SurfaceControl leash, boolean isTranslucent,
            Rect clipRect, Rect contentInsets, int prefixOrderIndex, Point position,
            Rect localBounds, Rect screenSpaceBounds,
            WindowConfiguration windowConfig, boolean isNotInRecents,
            SurfaceControl startLeash, Rect startBounds, ActivityManager.RunningTaskInfo taskInfo,
            boolean allowEnterPip) {
        this(taskId, mode, leash, isTranslucent, clipRect, contentInsets, prefixOrderIndex,
                position, localBounds, screenSpaceBounds, windowConfig, isNotInRecents, startLeash,
                startBounds, taskInfo, allowEnterPip, INVALID_WINDOW_TYPE);
    }

    public RemoteAnimationTarget(int taskId, int mode, SurfaceControl leash, boolean isTranslucent,
            Rect clipRect, Rect contentInsets, int prefixOrderIndex, Point position,
            Rect localBounds, Rect screenSpaceBounds,
            WindowConfiguration windowConfig, boolean isNotInRecents,
            SurfaceControl startLeash, Rect startBounds,
            ActivityManager.RunningTaskInfo taskInfo, boolean allowEnterPip,
            @WindowManager.LayoutParams.WindowType int windowType) {
        this.mode = mode;
        this.taskId = taskId;
        this.leash = leash;
        this.isTranslucent = isTranslucent;
        this.clipRect = new Rect(clipRect);
        this.contentInsets = new Rect(contentInsets);
        this.prefixOrderIndex = prefixOrderIndex;
        this.position = position == null ? new Point() : new Point(position);
        this.localBounds = new Rect(localBounds);
        this.sourceContainerBounds = new Rect(screenSpaceBounds);
        this.screenSpaceBounds = new Rect(screenSpaceBounds);
        this.windowConfiguration = windowConfig;
        this.isNotInRecents = isNotInRecents;
        this.startLeash = startLeash;
        this.startBounds = startBounds == null ? null : new Rect(startBounds);
        this.taskInfo = taskInfo;
        this.allowEnterPip = allowEnterPip;
        this.windowType = windowType;
    }

    public RemoteAnimationTarget(Parcel in) {
        taskId = in.readInt();
        mode = in.readInt();
        leash = in.readTypedObject(SurfaceControl.CREATOR);
        isTranslucent = in.readBoolean();
        clipRect = in.readTypedObject(Rect.CREATOR);
        contentInsets = in.readTypedObject(Rect.CREATOR);
        prefixOrderIndex = in.readInt();
        position = in.readTypedObject(Point.CREATOR);
        localBounds = in.readTypedObject(Rect.CREATOR);
        sourceContainerBounds = in.readTypedObject(Rect.CREATOR);
        screenSpaceBounds = in.readTypedObject(Rect.CREATOR);
        windowConfiguration = in.readTypedObject(WindowConfiguration.CREATOR);
        isNotInRecents = in.readBoolean();
        startLeash = in.readTypedObject(SurfaceControl.CREATOR);
        startBounds = in.readTypedObject(Rect.CREATOR);
        taskInfo = in.readTypedObject(ActivityManager.RunningTaskInfo.CREATOR);
        allowEnterPip = in.readBoolean();
        windowType = in.readInt();
        hasAnimatingParent = in.readBoolean();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(taskId);
        dest.writeInt(mode);
        dest.writeTypedObject(leash, 0 /* flags */);
        dest.writeBoolean(isTranslucent);
        dest.writeTypedObject(clipRect, 0 /* flags */);
        dest.writeTypedObject(contentInsets, 0 /* flags */);
        dest.writeInt(prefixOrderIndex);
        dest.writeTypedObject(position, 0 /* flags */);
        dest.writeTypedObject(localBounds, 0 /* flags */);
        dest.writeTypedObject(sourceContainerBounds, 0 /* flags */);
        dest.writeTypedObject(screenSpaceBounds, 0 /* flags */);
        dest.writeTypedObject(windowConfiguration, 0 /* flags */);
        dest.writeBoolean(isNotInRecents);
        dest.writeTypedObject(startLeash, 0 /* flags */);
        dest.writeTypedObject(startBounds, 0 /* flags */);
        dest.writeTypedObject(taskInfo, 0 /* flags */);
        dest.writeBoolean(allowEnterPip);
        dest.writeInt(windowType);
        dest.writeBoolean(hasAnimatingParent);
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("mode="); pw.print(mode);
        pw.print(" taskId="); pw.print(taskId);
        pw.print(" isTranslucent="); pw.print(isTranslucent);
        pw.print(" clipRect="); clipRect.printShortString(pw);
        pw.print(" contentInsets="); contentInsets.printShortString(pw);
        pw.print(" prefixOrderIndex="); pw.print(prefixOrderIndex);
        pw.print(" position="); printPoint(position, pw);
        pw.print(" sourceContainerBounds="); sourceContainerBounds.printShortString(pw);
        pw.print(" screenSpaceBounds="); screenSpaceBounds.printShortString(pw);
        pw.print(" localBounds="); localBounds.printShortString(pw);
        pw.println();
        pw.print(prefix); pw.print("windowConfiguration="); pw.println(windowConfiguration);
        pw.print(prefix); pw.print("leash="); pw.println(leash);
        pw.print(prefix); pw.print("taskInfo="); pw.println(taskInfo);
        pw.print(prefix); pw.print("allowEnterPip="); pw.println(allowEnterPip);
        pw.print(prefix); pw.print("windowType="); pw.print(windowType);
        pw.print(prefix); pw.print("hasAnimatingParent="); pw.print(hasAnimatingParent);
    }

    public void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.write(TASK_ID, taskId);
        proto.write(MODE, mode);
        leash.dumpDebug(proto, LEASH);
        proto.write(IS_TRANSLUCENT, isTranslucent);
        clipRect.dumpDebug(proto, CLIP_RECT);
        contentInsets.dumpDebug(proto, CONTENT_INSETS);
        proto.write(PREFIX_ORDER_INDEX, prefixOrderIndex);
        dumpPointProto(position, proto, POSITION);
        sourceContainerBounds.dumpDebug(proto, SOURCE_CONTAINER_BOUNDS);
        screenSpaceBounds.dumpDebug(proto, SCREEN_SPACE_BOUNDS);
        localBounds.dumpDebug(proto, LOCAL_BOUNDS);
        windowConfiguration.dumpDebug(proto, WINDOW_CONFIGURATION);
        if (startLeash != null) {
            startLeash.dumpDebug(proto, START_LEASH);
        }
        if (startBounds != null) {
            startBounds.dumpDebug(proto, START_BOUNDS);
        }
        proto.end(token);
    }

    private static void printPoint(Point p, PrintWriter pw) {
        pw.print("["); pw.print(p.x); pw.print(","); pw.print(p.y); pw.print("]");
    }

    public static final @android.annotation.NonNull Creator<RemoteAnimationTarget> CREATOR
            = new Creator<RemoteAnimationTarget>() {
        public RemoteAnimationTarget createFromParcel(Parcel in) {
            return new RemoteAnimationTarget(in);
        }

        public RemoteAnimationTarget[] newArray(int size) {
            return new RemoteAnimationTarget[size];
        }
    };
}
