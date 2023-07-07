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

package android.media;

/**
 * A @{@link Throwable} thrown from {@link MediaDrm} or @{@link MediaCrypto} APIs
 */
public interface MediaDrmThrowable {
    /**
     * Returns {@link MediaDrm} plugin vendor defined error code associated with this {@link
     * MediaDrmThrowable}.
     * <p>
     * Please consult the {@link MediaDrm} plugin vendor for details on the error code.
     *
     * @return an error code defined by the {@link MediaDrm} plugin vendor if available,
     * otherwise 0.
     */
    public default int getVendorError() {
        return 0;
    }

    /**
     * Returns OEM or SOC specific error code associated with this {@link
     * MediaDrmThrowable}.
     * <p>
     * Please consult the {@link MediaDrm} plugin, chip, or device vendor for details on the
     * error code.
     *
     * @return an OEM or SOC specific error code if available, otherwise 0.
     */
    public default int getOemError() {
        return 0;
    }

    /**
     * Returns {@link MediaDrm} plugin vendor defined error context associated with this {@link
     * MediaDrmThrowable}.
     * <p>
     * Please consult the {@link MediaDrm} plugin vendor for details on the error context.
     *
     * @return an opaque integer that would help the @{@link MediaDrm} vendor locate the
     * source of the error if available, otherwise 0.
     */
    public default int getErrorContext() {
        return 0;
    }

}
