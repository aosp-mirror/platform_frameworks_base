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

import android.os.Bundle;
import android.content.pm.IDataLoaderStatusListener;
import android.content.pm.InstallationFile;
import java.util.List;

/**
 * TODO: update with new APIs
 * @hide
 */
oneway interface IDataLoader {
   void create(int id, in Bundle params, IDataLoaderStatusListener listener);
   void start();
   void stop();
   void destroy();

   void prepareImage(in List<InstallationFile> addedFiles, in List<String> removedFiles);
}
