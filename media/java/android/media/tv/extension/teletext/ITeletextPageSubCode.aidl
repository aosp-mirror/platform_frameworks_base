/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.media.tv.extension.teletext;

import android.os.Bundle;

/**
 * @hide
 */
interface ITeletextPageSubCode {
    // Get Teletext page number
    Bundle getTeletextPageNumber(String sessionToken);
    // Set Teletext page number.
    void setTeleltextPageNumber(String sessionToken, int pageNumber);
    // Get Teletext sub page number.
    Bundle getTeletextPageSubCode(String sessionToken);
    // Set Teletext sub page number.
    void setTeletextPageSubCode(String sessionToken, int pageSubCode);
    // Get Teletext TopInfo.
    Bundle getTeletextHasTopInfo(String sessionToken);
    // Get Teletext TopBlockList.
    Bundle getTeletextTopBlockList(String sessionToken);
    // Get Teletext TopGroupList.
    Bundle getTeletextTopGroupList(String sessionToken, int indexGroup);
    // Get Teletext TopPageList.
    Bundle getTeletextTopPageList(String sessionToken, int indexPage);
}
