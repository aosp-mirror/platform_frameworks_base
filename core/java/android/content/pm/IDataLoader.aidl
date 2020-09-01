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

import android.content.pm.DataLoaderParamsParcel;
import android.content.pm.FileSystemControlParcel;
import android.content.pm.IDataLoaderStatusListener;
import android.content.pm.InstallationFileParcel;
import java.util.List;

/**
 * @hide
 */
oneway interface IDataLoader {
   void create(int id, in DataLoaderParamsParcel params,
           in FileSystemControlParcel control,
           IDataLoaderStatusListener listener);
   void start(int id);
   void stop(int id);
   void destroy(int id);

   void prepareImage(int id, in InstallationFileParcel[] addedFiles, in @utf8InCpp String[] removedFiles);
}
