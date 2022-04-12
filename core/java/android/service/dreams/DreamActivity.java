/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.service.dreams;

import android.annotation.Nullable;
import android.app.Activity;
import android.os.Bundle;
import android.text.TextUtils;

import com.android.internal.R;

/**
 * The Activity used by the {@link DreamService} to draw screensaver content
 * on the screen. This activity runs in dream application's process, but is started by a
 * specialized method: {@link com.android.server.wm.ActivityTaskManagerService#startDreamActivity}.
 * Hence, it does not have to be declared in the dream application's manifest.
 *
 * We use an activity as the dream canvas, because it interacts easier with other activities on
 * the screen (compared to a hover window). However, the DreamService is in charge of the dream and
 * it receives all Window.Callbacks from its main window. Since a window can have only one callback
 * receiver, the activity will not receive any window callbacks.
 *
 * Prior to the DreamActivity, the DreamService used to work with a hovering window and give the
 * screensaver application control over that window. The DreamActivity is a replacement to that
 * hover window. Using an activity allows for better-defined interactions with the rest of the
 * activities on screen. The switch to DreamActivity should be transparent to the screensaver
 * application, i.e. the application will still use DreamService APIs and not notice that the
 * system is using an activity behind the scenes.
 *
 * @hide
 */
public class DreamActivity extends Activity {
    static final String EXTRA_CALLBACK = "binder";
    static final String EXTRA_DREAM_TITLE = "title";

    public DreamActivity() {}

    @Override
    public void onCreate(@Nullable Bundle bundle) {
        super.onCreate(bundle);

        final String title = getIntent().getStringExtra(EXTRA_DREAM_TITLE);
        if (!TextUtils.isEmpty(title)) {
            setTitle(title);
        }

        DreamService.DreamServiceWrapper callback =
                (DreamService.DreamServiceWrapper) getIntent().getIBinderExtra(EXTRA_CALLBACK);

        if (callback != null) {
            callback.onActivityCreated(this);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        overridePendingTransition(R.anim.dream_activity_open_enter,
                                  R.anim.dream_activity_open_exit);
    }

    @Override
    public void finishAndRemoveTask() {
        super.finishAndRemoveTask();
        overridePendingTransition(0, R.anim.dream_activity_close_exit);
    }
}
