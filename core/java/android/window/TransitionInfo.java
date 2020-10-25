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

package android.window;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.SurfaceControl;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Used to communicate information about what is changing during a transition to a TransitionPlayer.
 * @hide
 */
public final class TransitionInfo implements Parcelable {

    /** No transition mode. This is a placeholder, don't use this as an actual mode. */
    public static final int TRANSIT_NONE = 0;

    /** The container didn't exist before but will exist and be visible after. */
    public static final int TRANSIT_OPEN = 1;

    /** The container existed and was visible before but won't exist after. */
    public static final int TRANSIT_CLOSE = 2;

    /** The container existed before but was invisible and will be visible after. */
    public static final int TRANSIT_SHOW = 3;

    /** The container is going from visible to invisible but it will still exist after. */
    public static final int TRANSIT_HIDE = 4;

    /** The container exists and is visible before and after but it changes. */
    public static final int TRANSIT_CHANGE = 5;

    /** @hide */
    @IntDef(prefix = { "TRANSIT_" }, value = {
            TRANSIT_NONE,
            TRANSIT_OPEN,
            TRANSIT_CLOSE,
            TRANSIT_SHOW,
            TRANSIT_HIDE,
            TRANSIT_CHANGE
    })
    public @interface TransitionMode {}

    private final @WindowManager.TransitionOldType int mType;
    private final ArrayList<Change> mChanges = new ArrayList<>();

    /** @hide */
    public TransitionInfo(@WindowManager.TransitionOldType int type) {
        mType = type;
    }

    private TransitionInfo(Parcel in) {
        mType = in.readInt();
        in.readList(mChanges, null /* classLoader */);
    }

    @Override
    /** @hide */
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mType);
        dest.writeList(mChanges);
    }

    @NonNull
    public static final Creator<TransitionInfo> CREATOR =
            new Creator<TransitionInfo>() {
                @Override
                public TransitionInfo createFromParcel(Parcel in) {
                    return new TransitionInfo(in);
                }

                @Override
                public TransitionInfo[] newArray(int size) {
                    return new TransitionInfo[size];
                }
            };

    @Override
    /** @hide */
    public int describeContents() {
        return 0;
    }

    public int getType() {
        return mType;
    }

    @NonNull
    public List<Change> getChanges() {
        return mChanges;
    }

    /**
     * @return the Change that a window is undergoing or {@code null} if not directly
     * represented.
     */
    @Nullable
    public Change getChange(@NonNull WindowContainerToken token) {
        for (int i = mChanges.size() - 1; i >= 0; --i) {
            if (mChanges.get(i).mContainer == token) {
                return mChanges.get(i);
            }
        }
        return null;
    }

    /**
     * Add a {@link Change} to this transition.
     */
    public void addChange(@NonNull Change change) {
        mChanges.add(change);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("{t=" + mType + " c=[");
        for (int i = 0; i < mChanges.size(); ++i) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(mChanges.get(i));
        }
        sb.append("]}");
        return sb.toString();
    }

    /** Converts a transition mode/action to its string representation. */
    @NonNull
    public static String modeToString(@TransitionMode int mode) {
        switch(mode) {
            case TRANSIT_NONE: return "NONE";
            case TRANSIT_OPEN: return "OPEN";
            case TRANSIT_CLOSE: return "CLOSE";
            case TRANSIT_SHOW: return "SHOW";
            case TRANSIT_HIDE: return "HIDE";
            case TRANSIT_CHANGE: return "CHANGE";
            default: return "<unknown:" + mode + ">";
        }
    }

    /** Represents the change a WindowContainer undergoes during a transition */
    public static final class Change implements Parcelable {
        private final WindowContainerToken mContainer;
        private WindowContainerToken mParent;
        private final SurfaceControl mLeash;
        private int mMode = TRANSIT_NONE;
        private final Rect mStartBounds = new Rect();
        private final Rect mEndBounds = new Rect();

        public Change(@NonNull WindowContainerToken container, @NonNull SurfaceControl leash) {
            mContainer = container;
            mLeash = leash;
        }

        private Change(Parcel in) {
            mContainer = WindowContainerToken.CREATOR.createFromParcel(in);
            mParent = in.readParcelable(WindowContainerToken.class.getClassLoader());
            mLeash = new SurfaceControl();
            mLeash.readFromParcel(in);
            mMode = in.readInt();
            mStartBounds.readFromParcel(in);
            mEndBounds.readFromParcel(in);
        }

        /** Sets the parent of this change's container. The parent must be a participant or null. */
        public void setParent(@Nullable WindowContainerToken parent) {
            mParent = parent;
        }

        /** Sets the transition mode for this change */
        public void setMode(@TransitionMode int mode) {
            mMode = mode;
        }

        /** Sets the bounds this container occupied before the change */
        public void setStartBounds(@Nullable Rect rect) {
            mStartBounds.set(rect);
        }

        /** Sets the bounds this container will occupy after the change */
        public void setEndBounds(@Nullable Rect rect) {
            mEndBounds.set(rect);
        }

        /** @return the container that is changing */
        @NonNull
        public WindowContainerToken getContainer() {
            return mContainer;
        }

        /**
         * @return the parent of the changing container. This is the parent within the participants,
         * not necessarily the actual parent.
         */
        @Nullable
        public WindowContainerToken getParent() {
            return mParent;
        }

        /** @return which action this change represents. */
        public @TransitionMode int getMode() {
            return mMode;
        }

        /**
         * @return the bounds of the container before the change. It may be empty if the container
         * is coming into existence.
         */
        @NonNull
        public Rect getStartBounds() {
            return mStartBounds;
        }

        /**
         * @return the bounds of the container after the change. It may be empty if the container
         * is disappearing.
         */
        @NonNull
        public Rect getEndBounds() {
            return mEndBounds;
        }

        /** @return the leash or surface to animate for this container */
        @NonNull
        public SurfaceControl getLeash() {
            return mLeash;
        }

        @Override
        /** @hide */
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            mContainer.writeToParcel(dest, flags);
            dest.writeParcelable(mParent, 0);
            mLeash.writeToParcel(dest, flags);
            dest.writeInt(mMode);
            mStartBounds.writeToParcel(dest, flags);
            mEndBounds.writeToParcel(dest, flags);
        }

        @NonNull
        public static final Creator<Change> CREATOR =
                new Creator<Change>() {
                    @Override
                    public Change createFromParcel(Parcel in) {
                        return new Change(in);
                    }

                    @Override
                    public Change[] newArray(int size) {
                        return new Change[size];
                    }
                };

        @Override
        /** @hide */
        public int describeContents() {
            return 0;
        }

        @Override
        public String toString() {
            return "{" + mContainer + "(" + mParent + ") leash=" + mLeash
                    + " m=" + modeToString(mMode) + " sb=" + mStartBounds
                    + " eb=" + mEndBounds + "}";
        }
    }
}
