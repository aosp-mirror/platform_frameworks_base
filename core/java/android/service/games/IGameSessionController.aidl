/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.service.games;

import android.content.Intent;
import com.android.internal.infra.AndroidFuture;

/**
 * @hide
 */
oneway interface IGameSessionController {
    void takeScreenshot(int taskId, in AndroidFuture gameScreenshotResultFuture);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MANAGE_GAME_ACTIVITY)")
    void restartGame(in int taskId);
}
