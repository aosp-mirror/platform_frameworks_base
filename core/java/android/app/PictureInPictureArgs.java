/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.app;

import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a set of arguments used to initialize the picture-in-picture mode.
 */
public final class PictureInPictureArgs implements Parcelable {

    /**
     * The expected aspect ratio of the picture-in-picture.
     */
    @Nullable
    private Float mAspectRatio;

    /**
     * The set of actions that are associated with this activity when in picture in picture.
     */
    @Nullable
    private List<RemoteAction> mUserActions;

    PictureInPictureArgs(Parcel in) {
        if (in.readInt() != 0) {
            mAspectRatio = in.readFloat();
        }
        if (in.readInt() != 0) {
            mUserActions = new ArrayList<>();
            in.readParcelableList(mUserActions, RemoteAction.class.getClassLoader());
        }
    }

    /**
     * Creates a new set of picture-in-picture arguments.
     */
    public PictureInPictureArgs() {
        // Empty constructor
    }

    /**
     * Creates a new set of picture-in-picture arguments from the given {@param aspectRatio} and
     * {@param actions}.
     */
    public PictureInPictureArgs(float aspectRatio, List<RemoteAction> actions) {
        mAspectRatio = aspectRatio;
        if (actions != null) {
            mUserActions = new ArrayList<>(actions);
        }
    }

    /**
     * Copies the set parameters from the other picture-in-picture args.
     * @hide
     */
    public void copyOnlySet(PictureInPictureArgs otherArgs) {
        if (otherArgs.hasSetAspectRatio()) {
            mAspectRatio = otherArgs.mAspectRatio;
        }
        if (otherArgs.hasSetActions()) {
            mUserActions = otherArgs.mUserActions;
        }
    }

    /**
     * Sets the aspect ratio.
     * @param aspectRatio the new aspect ratio for picture-in-picture.
     */
    public void setAspectRatio(float aspectRatio) {
        mAspectRatio = aspectRatio;
    }

    /**
     * @return the aspect ratio. If none is set, return 0.
     * @hide
     */
    public float getAspectRatio() {
        if (mAspectRatio != null) {
            return mAspectRatio;
        }
        return 0f;
    }

    /**
     * @return whether the aspect ratio is set.
     * @hide
     */
    public boolean hasSetAspectRatio() {
        return mAspectRatio != null;
    }

    /**
     * Sets the user actions.
     * @param actions the new actions to show in the picture-in-picture menu.
     */
    public void setActions(List<RemoteAction> actions) {
        if (mUserActions != null) {
            mUserActions = null;
        }
        if (actions != null) {
            mUserActions = new ArrayList<>(actions);
        }
    }

    /**
     * @return the set of user actions.
     * @hide
     */
    public List<RemoteAction> getActions() {
        return mUserActions;
    }

    /**
     * @return whether the user actions are set.
     * @hide
     */
    public boolean hasSetActions() {
        return mUserActions != null;
    }

    @Override
    public PictureInPictureArgs clone() {
        return new PictureInPictureArgs(mAspectRatio, mUserActions);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        if (mAspectRatio != null) {
            out.writeInt(1);
            out.writeFloat(mAspectRatio);
        } else {
            out.writeInt(0);
        }
        if (mUserActions != null) {
            out.writeInt(1);
            out.writeParcelableList(mUserActions, 0);
        } else {
            out.writeInt(0);
        }
    }

    public static final Creator<PictureInPictureArgs> CREATOR =
            new Creator<PictureInPictureArgs>() {
                public PictureInPictureArgs createFromParcel(Parcel in) {
                    return new PictureInPictureArgs(in);
                }
                public PictureInPictureArgs[] newArray(int size) {
                    return new PictureInPictureArgs[size];
                }
            };
}