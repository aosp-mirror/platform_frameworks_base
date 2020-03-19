/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.content.pm;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.content.ComponentName;
import android.os.ParcelFileDescriptor;

import java.util.Map;

/**
 * This class represents the parameters used to configure a Data Loader.
 *
 * WARNING: This is a system API to aid internal development.
 * Use at your own risk. It will change or be removed without warning.
 * @hide
 */
@SystemApi
public class DataLoaderParams {
    @NonNull
    private final DataLoaderParamsParcel mData;

    /**
     * Creates and populates set of Data Loader parameters for Streaming installation.
     *
     * @param componentName Data Loader component supporting Streaming installation.
     * @param arguments free form installation arguments
     */
    public static final @NonNull DataLoaderParams forStreaming(@NonNull ComponentName componentName,
            @NonNull String arguments) {
        return new DataLoaderParams(DataLoaderType.STREAMING, componentName, arguments, null);
    }

    /**
     * Creates and populates set of Data Loader parameters for Incremental installation.
     *
     * @param componentName Data Loader component supporting Incremental installation.
     * @param arguments free form installation arguments
     */
    public static final @NonNull DataLoaderParams forIncremental(
            @NonNull ComponentName componentName, @NonNull String arguments) {
        return new DataLoaderParams(DataLoaderType.INCREMENTAL, componentName, arguments, null);
    }

    /** @hide */
    public DataLoaderParams(@NonNull @DataLoaderType int type, @NonNull ComponentName componentName,
            @NonNull String arguments, @Nullable Map<String, ParcelFileDescriptor> namedFds) {
        DataLoaderParamsParcel data = new DataLoaderParamsParcel();
        data.type = type;
        data.packageName = componentName.getPackageName();
        data.className = componentName.getClassName();
        data.arguments = arguments;
        if (namedFds == null || namedFds.isEmpty()) {
            data.dynamicArgs = new NamedParcelFileDescriptor[0];
        } else {
            data.dynamicArgs = new NamedParcelFileDescriptor[namedFds.size()];
            int i = 0;
            for (Map.Entry<String, ParcelFileDescriptor> namedFd : namedFds.entrySet()) {
                data.dynamicArgs[i] = new NamedParcelFileDescriptor();
                data.dynamicArgs[i].name = namedFd.getKey();
                data.dynamicArgs[i].fd = namedFd.getValue();
                i += 1;
            }
        }
        mData = data;
    }

    /** @hide */
    DataLoaderParams(@NonNull DataLoaderParamsParcel data) {
        mData = data;
    }

    /** @hide */
    public final @NonNull DataLoaderParamsParcel getData() {
        return mData;
    }

    /**
     * @return data loader type
     */
    public final @NonNull @DataLoaderType int getType() {
        return mData.type;
    }

    /**
     * @return data loader's component name
     */
    public final @NonNull ComponentName getComponentName() {
        return new ComponentName(mData.packageName, mData.className);
    }

    /**
     * @return data loader's arguments
     */
    public final @NonNull String getArguments() {
        return mData.arguments;
    }
}
