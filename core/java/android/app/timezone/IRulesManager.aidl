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

package android.app.timezone;

import android.app.timezone.ICallback;
import android.app.timezone.RulesState;
import android.os.ParcelFileDescriptor;

 /**
  * Interface to the TimeZone Rules Manager Service.
  *
  * <p>This interface is only intended for system apps to call. They should use the
  * {@link android.app.timezone.RulesManager} class rather than going through this
  * Binder interface directly. See {@link android.app.timezone.RulesManager} for more complete
  * documentation.
  *
  * {@hide}
  */
interface IRulesManager {

    /**
     * Returns information about the current time zone rules state such as the IANA version of
     * the system and any currently installed distro. This method is intended to allow clients to
     * determine if the current state can be improved; for example by passing the information to a
     * server that may provide a new distro for download.
     */
    RulesState getRulesState();

    /**
     * Requests installation of the supplied distro. The distro must have been checked for integrity
     * by the caller or have been received via a trusted mechanism.
     *
     * @param distroFileDescriptor the file descriptor for the distro
     * @param checkToken an optional token provided if the install was triggered in response to a
     *     {@link RulesUpdaterContract#ACTION_TRIGGER_RULES_UPDATE_CHECK} intent
     * @param callback the {@link ICallback} to receive callbacks related to the
     *     installation
     * @return zero if the installation will be attempted; nonzero on error
     */
    int requestInstall(in ParcelFileDescriptor distroFileDescriptor, in byte[] checkToken,
            ICallback callback);

    /**
     * Requests uninstallation of the currently installed distro (leaving the device with no
     * distro installed).
     *
     * @param checkToken an optional token provided if the uninstall was triggered in response to a
     *     {@link RulesUpdaterContract#ACTION_TRIGGER_RULES_UPDATE_CHECK} intent
     * @param callback the {@link ICallback} to receive callbacks related to the
     *     uninstall
     * @return zero if the uninstallation will be attempted; nonzero on error
     */
    int requestUninstall(in byte[] checkToken, ICallback callback);

    /**
     * Requests the system does not modify the currently installed time zone distro, if any. This
     * method records the fact that a time zone check operation triggered by the system is now
     * complete and there was nothing to do. The token passed should be the one presented when the
     * check was triggered.
     *
     * <p>Note: Passing {@code success == false} may result in more checks being triggered. Clients
     * should be careful not to pass false if the failure is unlikely to resolve by itself.
     *
     * @param checkToken an optional token provided if the install was triggered in response to a
     *     {@link RulesUpdaterContract#ACTION_TRIGGER_RULES_UPDATE_CHECK} intent
     * @param success true if the check was successful, false if it was not successful but may
     *     succeed if it is retried
     */
    void requestNothing(in byte[] token, boolean success);
}
