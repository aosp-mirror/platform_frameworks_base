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

package android.telephony.mbms;

import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Describes a file service available from the carrier from which files can be downloaded via
 * cell-broadcast.
 * @hide
 */
public final class FileServiceInfo extends ServiceInfo implements Parcelable {
    private final List<FileInfo> files;

    /** @hide */
    //@SystemApi
    public FileServiceInfo(Map<Locale, String> newNames, String newClassName,
            List<Locale> newLocales, String newServiceId, Date start, Date end,
            List<FileInfo> newFiles) {
        super(newNames, newClassName, newLocales, newServiceId, start, end);
        files = new ArrayList<>(newFiles);
    }

    public static final Parcelable.Creator<FileServiceInfo> CREATOR =
            new Parcelable.Creator<FileServiceInfo>() {
        @Override
        public FileServiceInfo createFromParcel(Parcel source) {
            return new FileServiceInfo(source);
        }

        @Override
        public FileServiceInfo[] newArray(int size) {
            return new FileServiceInfo[size];
        }
    };

    FileServiceInfo(Parcel in) {
        super(in);
        files = new ArrayList<FileInfo>();
        in.readList(files, FileInfo.class.getClassLoader());
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeList(files);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * @return A list of files available from this service. Note that this list may not be
     * exhaustive -- the middleware may not have information on all files that are available.
     * Consult the carrier for an authoritative and exhaustive list.
     */
    public List<FileInfo> getFiles() {
        return files;
    }
}
