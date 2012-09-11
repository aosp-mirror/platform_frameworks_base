/*
 * Copyright (C) 2012 The Android Open Source Project
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

/**
 * Information the state of a window.
 *
 * @hide
 */
public class WindowInfo implements Parcelable {

    private static final int MAX_POOL_SIZE = 20;

    private static int UNDEFINED = -1;

    private static Object sPoolLock = new Object();
    private static WindowInfo sPool;
    private static int sPoolSize;

    private WindowInfo mNext;
    private boolean mInPool;

    public IBinder token;

    public final Rect frame = new Rect();

    public final Rect touchableRegion = new Rect();

    public int type = UNDEFINED;

    public float compatibilityScale = UNDEFINED;

    public boolean visible;

    public int displayId = UNDEFINED;

    public int layer = UNDEFINED;

    private WindowInfo() {
        /* do nothing - reduce visibility */
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeStrongBinder(token);
        parcel.writeParcelable(frame, 0);
        parcel.writeParcelable(touchableRegion, 0);
        parcel.writeInt(type);
        parcel.writeFloat(compatibilityScale);
        parcel.writeInt(visible ? 1 : 0);
        parcel.writeInt(displayId);
        parcel.writeInt(layer);
        recycle();
    }

    private void initFromParcel(Parcel parcel) {
        token = parcel.readStrongBinder();
        frame.set((Rect) parcel.readParcelable(null));
        touchableRegion.set((Rect) parcel.readParcelable(null));
        type = parcel.readInt();
        compatibilityScale = parcel.readFloat();
        visible = (parcel.readInt() == 1);
        displayId = parcel.readInt();
        layer = parcel.readInt();
    }

    public static WindowInfo obtain(WindowInfo other) {
        WindowInfo info = obtain();
        info.token = other.token;
        info.frame.set(other.frame);
        info.touchableRegion.set(other.touchableRegion);
        info.type = other.type;
        info.compatibilityScale = other.compatibilityScale;
        info.visible = other.visible;
        info.displayId = other.displayId;
        info.layer = other.layer;
        return info;
    }

    public static WindowInfo obtain() {
        synchronized (sPoolLock) {
            if (sPoolSize > 0) {
                WindowInfo info = sPool;
                sPool = info.mNext;
                info.mNext = null;
                info.mInPool = false;
                sPoolSize--;
                return info;
            } else {
                return new WindowInfo();
            }
        }
    }

    public void recycle() {
        if (mInPool) {
            throw new IllegalStateException("Already recycled.");
        }
        clear();
        synchronized (sPoolLock) {
            if (sPoolSize < MAX_POOL_SIZE) {
                mNext = sPool;
                sPool = this;
                mInPool = true;
                sPoolSize++;
            }
        }
    }

    private void clear() {
        token = null;
        frame.setEmpty();
        touchableRegion.setEmpty();
        type = UNDEFINED;
        compatibilityScale = UNDEFINED;
        visible = false;
        displayId = UNDEFINED;
        layer = UNDEFINED;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("Window [token:").append((token != null) ? token.hashCode() : null);
        builder.append(", displayId:").append(displayId);
        builder.append(", type:").append(type);
        builder.append(", visible:").append(visible);
        builder.append(", layer:").append(layer);
        builder.append(", compatibilityScale:").append(compatibilityScale);
        builder.append(", frame:").append(frame);
        builder.append(", touchableRegion:").append(touchableRegion);
        builder.append("]");
        return builder.toString();
    }

    /**
     * @see Parcelable.Creator
     */
    public static final Parcelable.Creator<WindowInfo> CREATOR =
            new Parcelable.Creator<WindowInfo>() {
        public WindowInfo createFromParcel(Parcel parcel) {
            WindowInfo info = WindowInfo.obtain();
            info.initFromParcel(parcel);
            return info;
        }

        public WindowInfo[] newArray(int size) {
            return new WindowInfo[size];
        }
    };
}
