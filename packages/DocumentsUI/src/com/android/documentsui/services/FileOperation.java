/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.documentsui.services;

import static com.android.documentsui.services.FileOperationService.OPERATION_COPY;
import static com.android.documentsui.services.FileOperationService.OPERATION_DELETE;
import static com.android.documentsui.services.FileOperationService.OPERATION_MOVE;
import static com.android.documentsui.services.FileOperationService.OPERATION_UNKNOWN;

import android.content.Context;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.support.annotation.VisibleForTesting;

import com.android.documentsui.clipping.UrisSupplier;
import com.android.documentsui.model.DocumentStack;
import com.android.documentsui.services.FileOperationService.OpType;

/**
 * FileOperation describes a file operation, such as move/copy/delete etc.
 */
public abstract class FileOperation implements Parcelable {
    private final @OpType int mOpType;

    private final UrisSupplier mSrcs;
    private DocumentStack mDestination;

    @VisibleForTesting
    FileOperation(@OpType int opType, UrisSupplier srcs, DocumentStack destination) {
        assert(opType != OPERATION_UNKNOWN);
        assert(srcs.getItemCount() > 0);

        mOpType = opType;
        mSrcs = srcs;
        mDestination = destination;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public @OpType int getOpType() {
        return mOpType;
    }

    public UrisSupplier getSrc() {
        return mSrcs;
    }

    public DocumentStack getDestination() {
        return mDestination;
    }

    public void setDestination(DocumentStack destination) {
        mDestination = destination;
    }

    public void dispose() {
        mSrcs.dispose();
    }

    abstract Job createJob(Context service, Job.Listener listener, String id);

    private void appendInfoTo(StringBuilder builder) {
        builder.append("opType=").append(mOpType);
        builder.append(", srcs=").append(mSrcs.toString());
        builder.append(", destination=").append(mDestination.toString());
    }

    @Override
    public void writeToParcel(Parcel out, int flag) {
        out.writeInt(mOpType);
        out.writeParcelable(mSrcs, flag);
        out.writeParcelable(mDestination, flag);
    }

    private FileOperation(Parcel in) {
        mOpType = in.readInt();
        mSrcs = in.readParcelable(FileOperation.class.getClassLoader());
        mDestination = in.readParcelable(FileOperation.class.getClassLoader());
    }

    public static class CopyOperation extends FileOperation {
        private CopyOperation(UrisSupplier srcs, DocumentStack destination) {
            super(OPERATION_COPY, srcs, destination);
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();

            builder.append("CopyOperation{");
            super.appendInfoTo(builder);
            builder.append("}");

            return builder.toString();
        }

        CopyJob createJob(Context service, Job.Listener listener, String id) {
            return new CopyJob(service, listener, id, getDestination(), getSrc());
        }

        private CopyOperation(Parcel in) {
            super(in);
        }

        public static final Parcelable.Creator<CopyOperation> CREATOR =
                new Parcelable.Creator<CopyOperation>() {

                    @Override
                    public CopyOperation createFromParcel(Parcel source) {
                        return new CopyOperation(source);
                    }

                    @Override
                    public CopyOperation[] newArray(int size) {
                        return new CopyOperation[size];
                    }
                };
    }

    public static class MoveDeleteOperation extends FileOperation {
        private final Uri mSrcParent;

        private MoveDeleteOperation(
                @OpType int opType, UrisSupplier srcs, Uri srcParent, DocumentStack destination) {
            super(opType, srcs, destination);

            assert(srcParent != null);
            mSrcParent = srcParent;
        }

        @Override
        Job createJob(Context service, Job.Listener listener, String id) {
            switch(getOpType()) {
                case OPERATION_MOVE:
                    return new MoveJob(
                            service, listener, id, mSrcParent, getDestination(), getSrc());
                case OPERATION_DELETE:
                    return new DeleteJob(
                            service, listener, id, mSrcParent, getDestination(), getSrc());
                default:
                    throw new UnsupportedOperationException("Unsupported op type: " + getOpType());
            }
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();

            builder.append("MoveDeleteOperation{");
            super.appendInfoTo(builder);
            builder.append(", srcParent=").append(mSrcParent.toString());
            builder.append("}");

            return builder.toString();
        }

        @Override
        public void writeToParcel(Parcel out, int flag) {
            super.writeToParcel(out, flag);
            out.writeParcelable(mSrcParent, flag);
        }

        private MoveDeleteOperation(Parcel in) {
            super(in);
            mSrcParent = in.readParcelable(null);
        }

        public static final Parcelable.Creator<MoveDeleteOperation> CREATOR =
                new Parcelable.Creator<MoveDeleteOperation>() {


            @Override
            public MoveDeleteOperation createFromParcel(Parcel source) {
                return new MoveDeleteOperation(source);
            }

            @Override
            public MoveDeleteOperation[] newArray(int size) {
                return new MoveDeleteOperation[size];
            }
        };
    }

    public static class Builder {
        private @OpType int mOpType;
        private Uri mSrcParent;
        private UrisSupplier mSrcs;
        private DocumentStack mDestination;

        public Builder withOpType(@OpType int opType) {
            mOpType = opType;
            return this;
        }

        public Builder withSrcParent(Uri srcParent) {
            mSrcParent = srcParent;
            return this;
        }

        public Builder withSrcs(UrisSupplier srcs) {
            mSrcs = srcs;
            return this;
        }

        public Builder withDestination(DocumentStack destination) {
            mDestination = destination;
            return this;
        }

        public FileOperation build() {
            switch (mOpType) {
                case OPERATION_COPY:
                    return new CopyOperation(mSrcs, mDestination);
                case OPERATION_MOVE:
                case OPERATION_DELETE:
                    return new MoveDeleteOperation(mOpType, mSrcs, mSrcParent, mDestination);
                default:
                    throw new UnsupportedOperationException("Unsupported op type: " + mOpType);
            }
        }
    }
}
