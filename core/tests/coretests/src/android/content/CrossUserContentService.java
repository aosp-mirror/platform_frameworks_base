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
 * limitations under the License
 */

package android.content;

import android.app.Service;
import android.app.activity.LocalProvider;
import android.net.Uri;
import android.os.IBinder;

public class CrossUserContentService extends Service {

    @Override
    public IBinder onBind(Intent intent) {
        return mLocalService.asBinder();
    }

    private ICrossUserContentService mLocalService = new ICrossUserContentService.Stub() {
        @Override
        public void updateContent(Uri uri, String key, int value) {
            final ContentValues values = new ContentValues();
            values.put(LocalProvider.COLUMN_TEXT_NAME, key);
            values.put(LocalProvider.COLUMN_INTEGER_NAME, value);
            getContentResolver().update(uri, values, null, null);
        }

        @Override
        public void notifyForUriAsUser(Uri uri, int userId) {
            getContentResolver().notifyChange(uri, null, false, userId);
        }
    };
}