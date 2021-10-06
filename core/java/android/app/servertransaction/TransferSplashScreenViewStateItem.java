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

package android.app.servertransaction;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityThread;
import android.app.ClientTransactionHandler;
import android.os.Parcel;
import android.window.SplashScreenView.SplashScreenViewParcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Transfer a splash screen view to an Activity.
 * @hide
 */
public class TransferSplashScreenViewStateItem extends ActivityTransactionItem {

    private SplashScreenViewParcelable mSplashScreenViewParcelable;
    private @TransferRequest int mRequest;

    @IntDef(value = {
            ATTACH_TO,
            HANDOVER_TO
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface TransferRequest {}
    // request client to attach the view on it.
    public static final int ATTACH_TO = 0;
    // tell client that you can handle the splash screen view.
    public static final int HANDOVER_TO = 1;

    @Override
    public void execute(@NonNull ClientTransactionHandler client,
            @NonNull ActivityThread.ActivityClientRecord r,
            PendingTransactionActions pendingActions) {
        switch (mRequest) {
            case ATTACH_TO:
                client.handleAttachSplashScreenView(r, mSplashScreenViewParcelable);
                break;
            case HANDOVER_TO:
                client.handOverSplashScreenView(r);
                break;
        }
    }

    @Override
    public void recycle() {
        ObjectPool.recycle(this);
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mRequest);
        dest.writeTypedObject(mSplashScreenViewParcelable, flags);
    }

    private TransferSplashScreenViewStateItem() {}
    private TransferSplashScreenViewStateItem(Parcel in) {
        mRequest = in.readInt();
        mSplashScreenViewParcelable = in.readTypedObject(SplashScreenViewParcelable.CREATOR);
    }

    /** Obtain an instance initialized with provided params. */
    public static TransferSplashScreenViewStateItem obtain(@TransferRequest int state,
            @Nullable SplashScreenViewParcelable parcelable) {
        TransferSplashScreenViewStateItem instance =
                ObjectPool.obtain(TransferSplashScreenViewStateItem.class);
        if (instance == null) {
            instance = new TransferSplashScreenViewStateItem();
        }
        instance.mRequest = state;
        instance.mSplashScreenViewParcelable = parcelable;

        return instance;
    }

    public static final @NonNull Creator<TransferSplashScreenViewStateItem> CREATOR =
            new Creator<TransferSplashScreenViewStateItem>() {
                public TransferSplashScreenViewStateItem createFromParcel(Parcel in) {
                    return new TransferSplashScreenViewStateItem(in);
                }

                public TransferSplashScreenViewStateItem[] newArray(int size) {
                    return new TransferSplashScreenViewStateItem[size];
                }
            };
}
