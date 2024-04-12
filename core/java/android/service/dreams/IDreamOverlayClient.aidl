/**
 * Copyright (c) 2023, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.service.dreams;

import android.service.dreams.IDreamOverlayCallback;
import android.view.WindowManager.LayoutParams;

/**
* {@link IDreamOverlayClient} allows {@link DreamService} instances to act upon the dream overlay.
*
* @hide
*/
interface IDreamOverlayClient {
    /**
    * @param params The {@link LayoutParams} for the associated DreamWindow, including the window
                    token of the Dream Activity.
    * @param callback The {@link IDreamOverlayCallback} for requesting actions such as exiting the
    *                dream.
    * @param dreamComponent The component name of the dream service requesting overlay.
    * @param shouldShowComplications Whether the dream overlay should show complications, e.g. clock
    *                and weather.
    */
    void startDream(in LayoutParams params, in IDreamOverlayCallback callback,
        in String dreamComponent, in boolean shouldShowComplications);

    /** Called when the dream is waking, to do any exit animations */
    void wakeUp();

    /** Called when the dream has ended. */
    void endDream();

    /** Called when wake up has been redirected to the overlay. */
    void onWakeRequested();

    /** Called when the dream is coming to the front. */
    void comeToFront();
}
