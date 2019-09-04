/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.os;

import android.app.Service;
import android.content.Intent;

/**
 * Service used by {@link BinderWorkSourceTest}.
 */
public class BinderWorkSourceService extends Service {
    private final IBinderWorkSourceService.Stub mBinder =
            new IBinderWorkSourceService.Stub() {
        public int getBinderCallingUid() {
            return Binder.getCallingUid();
        }

        public int getIncomingWorkSourceUid() {
            return Binder.getCallingWorkSourceUid();
        }

        public int getThreadLocalWorkSourceUid() {
            return ThreadLocalWorkSource.getUid();
        }

        public void setWorkSourceProvider(int uid) {
            Binder.setWorkSourceProvider((x) -> uid);
        }

        public void clearWorkSourceProvider() {
            Binder.setWorkSourceProvider((x) -> Binder.getCallingUid());
        }
    };

    public IBinder onBind(Intent intent) {
        return mBinder;
    }
}
