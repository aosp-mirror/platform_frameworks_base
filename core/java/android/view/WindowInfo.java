/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.app.ActivityTaskManager;
import android.graphics.Region;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Pools;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * This class represents information about a window from the
 * window manager to another part of the system.
 *
 * @hide
 */
public class WindowInfo implements Parcelable {
    private static final int MAX_POOL_SIZE = 10;

    private static final Pools.SynchronizedPool<WindowInfo> sPool =
            new Pools.SynchronizedPool<WindowInfo>(MAX_POOL_SIZE);

    public int type;
    public int layer;
    public IBinder token;
    public IBinder parentToken;
    public IBinder activityToken;
    public boolean focused;
    public Region regionInScreen = new Region();
    public List<IBinder> childTokens;
    public CharSequence title;
    public long accessibilityIdOfAnchor = AccessibilityNodeInfo.UNDEFINED_NODE_ID;
    public boolean inPictureInPicture;
    public boolean hasFlagWatchOutsideTouch;
    public int displayId = Display.INVALID_DISPLAY;
    public int taskId = ActivityTaskManager.INVALID_TASK_ID;

    private WindowInfo() {
        /* do nothing - hide constructor */
    }

    public static WindowInfo obtain() {
        WindowInfo window = sPool.acquire();
        if (window == null) {
            window = new WindowInfo();
        }
        return window;
    }

    public static WindowInfo obtain(WindowInfo other) {
        WindowInfo window = obtain();
        window.displayId = other.displayId;
        window.taskId = other.taskId;
        window.type = other.type;
        window.layer = other.layer;
        window.token = other.token;
        window.parentToken = other.parentToken;
        window.activityToken = other.activityToken;
        window.focused = other.focused;
        window.regionInScreen.set(other.regionInScreen);
        window.title = other.title;
        window.accessibilityIdOfAnchor = other.accessibilityIdOfAnchor;
        window.inPictureInPicture = other.inPictureInPicture;
        window.hasFlagWatchOutsideTouch = other.hasFlagWatchOutsideTouch;

        if (other.childTokens != null && !other.childTokens.isEmpty()) {
            if (window.childTokens == null) {
                window.childTokens = new ArrayList<IBinder>(other.childTokens);
            } else {
                window.childTokens.addAll(other.childTokens);
            }
        }

        return window;
    }

    public void recycle() {
        clear();
        sPool.release(this);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(displayId);
        parcel.writeInt(taskId);
        parcel.writeInt(type);
        parcel.writeInt(layer);
        parcel.writeStrongBinder(token);
        parcel.writeStrongBinder(parentToken);
        parcel.writeStrongBinder(activityToken);
        parcel.writeInt(focused ? 1 : 0);
        regionInScreen.writeToParcel(parcel, flags);
        parcel.writeCharSequence(title);
        parcel.writeLong(accessibilityIdOfAnchor);
        parcel.writeInt(inPictureInPicture ? 1 : 0);
        parcel.writeInt(hasFlagWatchOutsideTouch ? 1 : 0);

        if (childTokens != null && !childTokens.isEmpty()) {
            parcel.writeInt(1);
            parcel.writeBinderList(childTokens);
        } else {
            parcel.writeInt(0);
        }
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("WindowInfo[");
        builder.append("title=").append(title);
        builder.append(", displayId=").append(displayId);
        builder.append(", taskId=").append(taskId);
        builder.append(", type=").append(type);
        builder.append(", layer=").append(layer);
        builder.append(", token=").append(token);
        builder.append(", region=").append(regionInScreen);
        builder.append(", bounds=").append(regionInScreen.getBounds());
        builder.append(", parent=").append(parentToken);
        builder.append(", focused=").append(focused);
        builder.append(", children=").append(childTokens);
        builder.append(", accessibility anchor=").append(accessibilityIdOfAnchor);
        builder.append(", pictureInPicture=").append(inPictureInPicture);
        builder.append(", watchOutsideTouch=").append(hasFlagWatchOutsideTouch);
        builder.append(']');
        return builder.toString();
    }

    private void initFromParcel(Parcel parcel) {
        displayId = parcel.readInt();
        taskId = parcel.readInt();
        type = parcel.readInt();
        layer = parcel.readInt();
        token = parcel.readStrongBinder();
        parentToken = parcel.readStrongBinder();
        activityToken = parcel.readStrongBinder();
        focused = (parcel.readInt() == 1);
        regionInScreen = Region.CREATOR.createFromParcel(parcel);
        title = parcel.readCharSequence();
        accessibilityIdOfAnchor = parcel.readLong();
        inPictureInPicture = (parcel.readInt() == 1);
        hasFlagWatchOutsideTouch = (parcel.readInt() == 1);

        final boolean hasChildren = (parcel.readInt() == 1);
        if (hasChildren) {
            if (childTokens == null) {
                childTokens = new ArrayList<IBinder>();
            }
            parcel.readBinderList(childTokens);
        }
    }

    private void clear() {
        displayId = Display.INVALID_DISPLAY;
        taskId = ActivityTaskManager.INVALID_TASK_ID;
        type = 0;
        layer = 0;
        token = null;
        parentToken = null;
        activityToken = null;
        focused = false;
        regionInScreen.setEmpty();
        if (childTokens != null) {
            childTokens.clear();
        }
        inPictureInPicture = false;
        hasFlagWatchOutsideTouch = false;
    }

    public static final @android.annotation.NonNull Parcelable.Creator<WindowInfo> CREATOR =
            new Creator<WindowInfo>() {
        @Override
        public WindowInfo createFromParcel(Parcel parcel) {
            WindowInfo window = obtain();
            window.initFromParcel(parcel);
            return window;
        }

        @Override
        public WindowInfo[] newArray(int size) {
            return new WindowInfo[size];
        }
    };
}
