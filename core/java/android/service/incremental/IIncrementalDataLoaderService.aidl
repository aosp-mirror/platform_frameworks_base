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

package android.service.incremental;

import android.os.incremental.IncrementalDataLoaderParamsParcel;
import android.os.incremental.IncrementalFileSystemControlParcel;
import android.service.incremental.IIncrementalDataLoaderStatusListener;

/** @hide */
oneway interface IIncrementalDataLoaderService {
   void createDataLoader(in int storageId,
                 in IncrementalFileSystemControlParcel control,
                 in IncrementalDataLoaderParamsParcel params,
                 in IIncrementalDataLoaderStatusListener listener,
                 in boolean start);
   void startDataLoader(in int storageId);
   void stopDataLoader(in int storageId);
   void destroyDataLoader(in int storageId);
   void onFileCreated(in int storageId, in long inode, in byte[] metadata);
}
