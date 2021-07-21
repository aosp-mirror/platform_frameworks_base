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

package android.app;

import android.compat.annotation.UnsupportedAppUsage;
import android.content.ContentProviderNative;
import android.content.IContentProvider;
import android.content.pm.ProviderInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Information you can retrieve about a particular application.
 *
 * @hide
 */
public class ContentProviderHolder implements Parcelable {
    @UnsupportedAppUsage
    public final ProviderInfo info;
    @UnsupportedAppUsage
    public IContentProvider provider;
    public IBinder connection;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public boolean noReleaseNeeded;

    /**
     * Whether the provider here is a local provider or not.
     */
    public boolean mLocal;

    @UnsupportedAppUsage
    public ContentProviderHolder(ProviderInfo _info) {
        info = _info;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        info.writeToParcel(dest, 0);
        if (provider != null) {
            dest.writeStrongBinder(provider.asBinder());
        } else {
            dest.writeStrongBinder(null);
        }
        dest.writeStrongBinder(connection);
        dest.writeInt(noReleaseNeeded ? 1 : 0);
        dest.writeInt(mLocal ? 1 : 0);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<ContentProviderHolder> CREATOR
            = new Parcelable.Creator<ContentProviderHolder>() {
        @Override
        public ContentProviderHolder createFromParcel(Parcel source) {
            return new ContentProviderHolder(source);
        }

        @Override
        public ContentProviderHolder[] newArray(int size) {
            return new ContentProviderHolder[size];
        }
    };

    @UnsupportedAppUsage
    private ContentProviderHolder(Parcel source) {
        info = ProviderInfo.CREATOR.createFromParcel(source);
        provider = ContentProviderNative.asInterface(
                source.readStrongBinder());
        connection = source.readStrongBinder();
        noReleaseNeeded = source.readInt() != 0;
        mLocal = source.readInt() != 0;
    }
}
