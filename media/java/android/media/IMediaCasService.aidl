/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.media;

import android.media.IDescrambler;
import android.media.ICas;
import android.media.ICasListener;
import android.media.MediaCas;

/** @hide */
interface IMediaCasService {
    MediaCas.ParcelableCasPluginDescriptor[] enumeratePlugins();
    boolean isSystemIdSupported(int CA_system_id);
    ICas createPlugin(int CA_system_id, ICasListener listener);
    boolean isDescramblerSupported(int CA_system_id);
    IDescrambler createDescrambler(int CA_system_id);
}

