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
package android.hardware.camera2.impl;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * @hide
 */
public class CaptureResultExtras implements Parcelable {
    private int requestId;
    private int subsequenceId;
    private int afTriggerId;
    private int precaptureTriggerId;
    private long frameNumber;
    private int partialResultCount;
    private int errorStreamId;
    private String errorPhysicalCameraId;

    public static final @android.annotation.NonNull Parcelable.Creator<CaptureResultExtras> CREATOR =
            new Parcelable.Creator<CaptureResultExtras>() {
        @Override
        public CaptureResultExtras createFromParcel(Parcel in) {
            return new CaptureResultExtras(in);
        }

        @Override
        public CaptureResultExtras[] newArray(int size) {
            return new CaptureResultExtras[size];
        }
    };

    private CaptureResultExtras(Parcel in) {
        readFromParcel(in);
    }

    public CaptureResultExtras(int requestId, int subsequenceId, int afTriggerId,
                               int precaptureTriggerId, long frameNumber,
                               int partialResultCount, int errorStreamId,
                               String errorPhysicalCameraId) {
        this.requestId = requestId;
        this.subsequenceId = subsequenceId;
        this.afTriggerId = afTriggerId;
        this.precaptureTriggerId = precaptureTriggerId;
        this.frameNumber = frameNumber;
        this.partialResultCount = partialResultCount;
        this.errorStreamId = errorStreamId;
        this.errorPhysicalCameraId = errorPhysicalCameraId;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(requestId);
        dest.writeInt(subsequenceId);
        dest.writeInt(afTriggerId);
        dest.writeInt(precaptureTriggerId);
        dest.writeLong(frameNumber);
        dest.writeInt(partialResultCount);
        dest.writeInt(errorStreamId);
        if ((errorPhysicalCameraId != null) && !errorPhysicalCameraId.isEmpty()) {
            dest.writeBoolean(true);
            dest.writeString(errorPhysicalCameraId);
        } else {
            dest.writeBoolean(false);
        }
    }

    public void readFromParcel(Parcel in) {
        requestId = in.readInt();
        subsequenceId = in.readInt();
        afTriggerId = in.readInt();
        precaptureTriggerId = in.readInt();
        frameNumber = in.readLong();
        partialResultCount = in.readInt();
        errorStreamId = in.readInt();
        boolean errorPhysicalCameraIdPresent = in.readBoolean();
        if (errorPhysicalCameraIdPresent) {
            errorPhysicalCameraId = in.readString();
        }
    }

    public String getErrorPhysicalCameraId() {
        return errorPhysicalCameraId;
    }

    public int getRequestId() {
        return requestId;
    }

    public int getSubsequenceId() {
        return subsequenceId;
    }

    public int getAfTriggerId() {
        return afTriggerId;
    }

    public int getPrecaptureTriggerId() {
        return precaptureTriggerId;
    }

    public long getFrameNumber() {
        return frameNumber;
    }

    public int getPartialResultCount() {
        return partialResultCount;
    }

    public int getErrorStreamId() {
        return errorStreamId;
    }
}
