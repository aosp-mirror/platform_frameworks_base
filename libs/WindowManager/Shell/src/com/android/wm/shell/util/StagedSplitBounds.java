/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.wm.shell.util;

import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Container of various information needed to display split screen
 * tasks/leashes/etc in Launcher
 */
public class StagedSplitBounds implements Parcelable {
    public final Rect leftTopBounds;
    public final Rect rightBottomBounds;
    /** This rect represents the actual gap between the two apps */
    public final Rect visualDividerBounds;
    // This class is orientation-agnostic, so we compute both for later use
    public final float topTaskPercent;
    public final float leftTaskPercent;
    /**
     * If {@code true}, that means at the time of creation of this object, the
     * split-screened apps were vertically stacked. This is useful in scenarios like
     * rotation where the bounds won't change, but this variable can indicate what orientation
     * the bounds were originally in
     */
    public final boolean appsStackedVertically;
    public final int leftTopTaskId;
    public final int rightBottomTaskId;

    public StagedSplitBounds(Rect leftTopBounds, Rect rightBottomBounds,
            int leftTopTaskId, int rightBottomTaskId) {
        this.leftTopBounds = leftTopBounds;
        this.rightBottomBounds = rightBottomBounds;
        this.leftTopTaskId = leftTopTaskId;
        this.rightBottomTaskId = rightBottomTaskId;

        if (rightBottomBounds.top > leftTopBounds.top) {
            // vertical apps, horizontal divider
            this.visualDividerBounds = new Rect(leftTopBounds.left, leftTopBounds.bottom,
                    leftTopBounds.right, rightBottomBounds.top);
            appsStackedVertically = true;
        } else {
            // horizontal apps, vertical divider
            this.visualDividerBounds = new Rect(leftTopBounds.right, leftTopBounds.top,
                    rightBottomBounds.left, leftTopBounds.bottom);
            appsStackedVertically = false;
        }

        leftTaskPercent = this.leftTopBounds.width() / (float) rightBottomBounds.right;
        topTaskPercent = this.leftTopBounds.height() / (float) rightBottomBounds.bottom;
    }

    public StagedSplitBounds(Parcel parcel) {
        leftTopBounds = parcel.readTypedObject(Rect.CREATOR);
        rightBottomBounds = parcel.readTypedObject(Rect.CREATOR);
        visualDividerBounds = parcel.readTypedObject(Rect.CREATOR);
        topTaskPercent = parcel.readFloat();
        leftTaskPercent = parcel.readFloat();
        appsStackedVertically = parcel.readBoolean();
        leftTopTaskId = parcel.readInt();
        rightBottomTaskId = parcel.readInt();
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeTypedObject(leftTopBounds, flags);
        parcel.writeTypedObject(rightBottomBounds, flags);
        parcel.writeTypedObject(visualDividerBounds, flags);
        parcel.writeFloat(topTaskPercent);
        parcel.writeFloat(leftTaskPercent);
        parcel.writeBoolean(appsStackedVertically);
        parcel.writeInt(leftTopTaskId);
        parcel.writeInt(rightBottomTaskId);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof StagedSplitBounds)) {
            return false;
        }
        // Only need to check the base fields (the other fields are derived from these)
        final StagedSplitBounds other = (StagedSplitBounds) obj;
        return Objects.equals(leftTopBounds, other.leftTopBounds)
                && Objects.equals(rightBottomBounds, other.rightBottomBounds)
                && leftTopTaskId == other.leftTopTaskId
                && rightBottomTaskId == other.rightBottomTaskId;
    }

    @Override
    public int hashCode() {
        return Objects.hash(leftTopBounds, rightBottomBounds, leftTopTaskId, rightBottomTaskId);
    }

    @Override
    public String toString() {
        return "LeftTop: " + leftTopBounds + ", taskId: " + leftTopTaskId + "\n"
                + "RightBottom: " + rightBottomBounds + ", taskId: " + rightBottomTaskId +  "\n"
                + "Divider: " + visualDividerBounds + "\n"
                + "AppsVertical? " + appsStackedVertically;
    }

    public static final Creator<StagedSplitBounds> CREATOR = new Creator<StagedSplitBounds>() {
        @Override
        public StagedSplitBounds createFromParcel(Parcel in) {
            return new StagedSplitBounds(in);
        }

        @Override
        public StagedSplitBounds[] newArray(int size) {
            return new StagedSplitBounds[size];
        }
    };
}
