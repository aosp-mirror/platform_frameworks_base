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

import android.graphics.Rect;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Pools;

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
    public boolean focused;
    public final Rect boundsInScreen = new Rect();
    public List<IBinder> childTokens;

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
        window.type = other.type;
        window.layer = other.layer;
        window.token = other.token;
        window.parentToken = other.parentToken;
        window.focused = other.focused;
        window.boundsInScreen.set(other.boundsInScreen);

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
        parcel.writeInt(type);
        parcel.writeInt(layer);
        parcel.writeStrongBinder(token);
        parcel.writeStrongBinder(parentToken);
        parcel.writeInt(focused ? 1 : 0);
        boundsInScreen.writeToParcel(parcel, flags);

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
        builder.append("type=").append(type);
        builder.append(", layer=").append(layer);
        builder.append(", token=").append(token);
        builder.append(", bounds=").append(boundsInScreen);
        builder.append(", parent=").append(parentToken);
        builder.append(", focused=").append(focused);
        builder.append(", children=").append(childTokens);
        builder.append(']');
        return builder.toString();
    }

    private void initFromParcel(Parcel parcel) {
        type = parcel.readInt();
        layer = parcel.readInt();
        token = parcel.readStrongBinder();
        parentToken = parcel.readStrongBinder();
        focused = (parcel.readInt() == 1);
        boundsInScreen.readFromParcel(parcel);

        final boolean hasChildren = (parcel.readInt() == 1);
        if (hasChildren) {
            if (childTokens == null) {
                childTokens = new ArrayList<IBinder>();
            }
            parcel.readBinderList(childTokens);
        }
    }

    private void clear() {
        type = 0;
        layer = 0;
        token = null;
        parentToken = null;
        focused = false;
        boundsInScreen.setEmpty();
        if (childTokens != null) {
            childTokens.clear();
        }
    }

    public static final Parcelable.Creator<WindowInfo> CREATOR =
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
