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

package android.hardware.camera2;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureRequest.Key;
import android.hardware.camera2.impl.ExtensionKey;
import android.hardware.camera2.impl.PublicKey;

import com.android.internal.camera.flags.Flags;

/**
 * ExtensionCaptureRequest contains definitions for extension-specific CaptureRequest keys that
 * can be used to configure a {@link android.hardware.camera2.CaptureRequest} during a
 * {@link android.hardware.camera2.CameraExtensionSession}.
 *
 * Note that ExtensionCaptureRequest is not intended to be used as a replacement
 * for CaptureRequest in the extensions. It serves as a supplementary class providing
 * extension-specific CaptureRequest keys. Developers should use these keys in conjunction
 * with regular CaptureRequest objects during a
 * {@link android.hardware.camera2.CameraExtensionSession}.
 *
 * @see CaptureRequest
 * @see CameraExtensionSession
 */
@FlaggedApi(Flags.FLAG_CONCERT_MODE)
public final class ExtensionCaptureRequest {

    /**
     * <p>Used to apply an additional digital zoom factor for the
     * {@link android.hardware.camera2.CameraExtensionCharacteristics#EXTENSION_EYES_FREE_VIDEOGRAPHY }
     * extension in {@link android.hardware.camera2.ExtensionCaptureRequest#EFV_STABILIZATION_MODE_LOCKED } mode.</p>
     * <p>For the {@link android.hardware.camera2.CameraExtensionCharacteristics#EXTENSION_EYES_FREE_VIDEOGRAPHY }
     * feature, an additional zoom factor is applied on top of the existing {@link CaptureRequest#CONTROL_ZOOM_RATIO android.control.zoomRatio}.
     * This additional zoom factor serves as a buffer to provide more flexibility for the
     * {@link android.hardware.camera2.ExtensionCaptureRequest#EFV_STABILIZATION_MODE_LOCKED }
     * mode. If {@link ExtensionCaptureRequest#EFV_PADDING_ZOOM_FACTOR } is not set, the default will be used.
     * The effectiveness of the stabilization may be influenced by the amount of padding zoom
     * applied. A higher padding zoom factor can stabilize the target region more effectively
     * with greater flexibility but may potentially impact image quality. Conversely, a lower
     * padding zoom factor may be used to prioritize preserving image quality, albeit with less
     * leeway in stabilizing the target region. It is recommended to set the
     * {@link android.hardware.camera2.ExtensionCaptureRequest#EFV_PADDING_ZOOM_FACTOR } to at least 1.5.</p>
     * <p>If {@link ExtensionCaptureRequest#EFV_AUTO_ZOOM } is enabled, the requested {@link ExtensionCaptureRequest#EFV_PADDING_ZOOM_FACTOR } will be overridden.
     * {@link ExtensionCaptureRequest#EFV_MAX_PADDING_ZOOM_FACTOR } can be checked for more details on controlling the
     * padding zoom factor during {@link ExtensionCaptureRequest#EFV_AUTO_ZOOM }.</p>
     * <p><b>Range of valid values:</b><br>
     * {@link CameraExtensionCharacteristics#EFV_PADDING_ZOOM_FACTOR_RANGE }</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     *
     * @see CaptureRequest#CONTROL_ZOOM_RATIO
     * @see ExtensionCaptureRequest#EFV_AUTO_ZOOM
     * @see ExtensionCaptureRequest#EFV_MAX_PADDING_ZOOM_FACTOR
     * @see ExtensionCaptureRequest#EFV_PADDING_ZOOM_FACTOR
     * @see CameraExtensionCharacteristics#EFV_PADDING_ZOOM_FACTOR_RANGE
     */
    @PublicKey
    @NonNull
    @ExtensionKey
    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    public static final Key<Float> EFV_PADDING_ZOOM_FACTOR = CaptureRequest.EFV_PADDING_ZOOM_FACTOR;

    /**
     * <p>Used to enable or disable auto zoom for the
     * {@link android.hardware.camera2.CameraExtensionCharacteristics#EXTENSION_EYES_FREE_VIDEOGRAPHY }
     * extension in {@link android.hardware.camera2.ExtensionCaptureRequest#EFV_STABILIZATION_MODE_LOCKED } mode.</p>
     * <p>Turn on auto zoom to let the
     * {@link android.hardware.camera2.CameraExtensionCharacteristics#EXTENSION_EYES_FREE_VIDEOGRAPHY }
     * feature decide at any given point a combination of
     * {@link CaptureRequest#CONTROL_ZOOM_RATIO android.control.zoomRatio} and {@link ExtensionCaptureRequest#EFV_PADDING_ZOOM_FACTOR }
     * to keep the target region in view and stabilized. The combination chosen by the
     * {@link android.hardware.camera2.CameraExtensionCharacteristics#EXTENSION_EYES_FREE_VIDEOGRAPHY }
     * will equal the requested {@link CaptureRequest#CONTROL_ZOOM_RATIO android.control.zoomRatio} multiplied with the requested
     * {@link ExtensionCaptureRequest#EFV_PADDING_ZOOM_FACTOR }. A limit can be set on the padding zoom if wanting
     * to control image quality further using {@link ExtensionCaptureRequest#EFV_MAX_PADDING_ZOOM_FACTOR }.</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     *
     * @see CaptureRequest#CONTROL_ZOOM_RATIO
     * @see ExtensionCaptureRequest#EFV_MAX_PADDING_ZOOM_FACTOR
     * @see ExtensionCaptureRequest#EFV_PADDING_ZOOM_FACTOR
     */
    @PublicKey
    @NonNull
    @ExtensionKey
    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    public static final Key<Boolean> EFV_AUTO_ZOOM = CaptureRequest.EFV_AUTO_ZOOM;

    /**
     * <p>Used to limit the {@link ExtensionCaptureRequest#EFV_PADDING_ZOOM_FACTOR } if
     * {@link ExtensionCaptureRequest#EFV_AUTO_ZOOM } is enabled for the
     * {@link android.hardware.camera2.CameraExtensionCharacteristics#EXTENSION_EYES_FREE_VIDEOGRAPHY }
     * extension in {@link android.hardware.camera2.ExtensionCaptureRequest#EFV_STABILIZATION_MODE_LOCKED } mode.</p>
     * <p>If {@link ExtensionCaptureRequest#EFV_AUTO_ZOOM } is enabled, this key can be used to set a limit
     * on the {@link ExtensionCaptureRequest#EFV_PADDING_ZOOM_FACTOR } chosen by the
     * {@link android.hardware.camera2.CameraExtensionCharacteristics#EXTENSION_EYES_FREE_VIDEOGRAPHY }
     * extension in {@link android.hardware.camera2.ExtensionCaptureRequest#EFV_STABILIZATION_MODE_LOCKED } mode
     * to control image quality.</p>
     * <p><b>Range of valid values:</b><br>
     * The range of {@link CameraExtensionCharacteristics#EFV_PADDING_ZOOM_FACTOR_RANGE Range}. Use a value greater than or equal to
     * the {@link android.hardware.camera2.ExtensionCaptureRequest#EFV_PADDING_ZOOM_FACTOR } to
     * effectively utilize this key.</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     *
     * @see ExtensionCaptureRequest#EFV_AUTO_ZOOM
     * @see ExtensionCaptureRequest#EFV_PADDING_ZOOM_FACTOR
     * @see CameraExtensionCharacteristics#EFV_PADDING_ZOOM_FACTOR_RANGE
     */
    @PublicKey
    @NonNull
    @ExtensionKey
    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    public static final Key<Float> EFV_MAX_PADDING_ZOOM_FACTOR = CaptureRequest.EFV_MAX_PADDING_ZOOM_FACTOR;

    /**
     * <p>Set the stabilization mode for the
     * {@link android.hardware.camera2.CameraExtensionCharacteristics#EXTENSION_EYES_FREE_VIDEOGRAPHY }
     * extension</p>
     * <p>The desired stabilization mode. Gimbal stabilization mode provides simple, non-locked
     * video stabilization. Locked mode uses the
     * {@link android.hardware.camera2.CameraExtensionCharacteristics#EXTENSION_EYES_FREE_VIDEOGRAPHY }
     * stabilization feature to fixate on the current region, utilizing it as the target area for
     * stabilization.</p>
     * <p><b>Possible values:</b></p>
     * <ul>
     *   <li>{@link #EFV_STABILIZATION_MODE_OFF OFF}</li>
     *   <li>{@link #EFV_STABILIZATION_MODE_GIMBAL GIMBAL}</li>
     *   <li>{@link #EFV_STABILIZATION_MODE_LOCKED LOCKED}</li>
     * </ul>
     *
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     * @see #EFV_STABILIZATION_MODE_OFF
     * @see #EFV_STABILIZATION_MODE_GIMBAL
     * @see #EFV_STABILIZATION_MODE_LOCKED
     */
    @PublicKey
    @NonNull
    @ExtensionKey
    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    public static final Key<Integer> EFV_STABILIZATION_MODE = CaptureRequest.EFV_STABILIZATION_MODE;

    /**
     * <p>Used to update the target region for the
     * {@link android.hardware.camera2.CameraExtensionCharacteristics#EXTENSION_EYES_FREE_VIDEOGRAPHY }
     * extension in {@link android.hardware.camera2.ExtensionCaptureRequest#EFV_STABILIZATION_MODE_LOCKED } mode.</p>
     * <p>A android.util.Pair<Integer,Integer> that represents the desired
     * <Horizontal,Vertical> shift of the current locked view (or target region) in
     * pixels. Negative values indicate left and upward shifts, while positive values indicate
     * right and downward shifts in the active array coordinate system.</p>
     * <p><b>Range of valid values:</b><br>
     * android.util.Pair<Integer,Integer> represents the
     * <Horizontal,Vertical> shift. The range for the horizontal shift is
     * [-max({@link ExtensionCaptureResult#EFV_PADDING_REGION }-left), max({@link ExtensionCaptureResult#EFV_PADDING_REGION }-right)].
     * The range for the vertical shift is
     * [-max({@link ExtensionCaptureResult#EFV_PADDING_REGION }-top), max({@link ExtensionCaptureResult#EFV_PADDING_REGION }-bottom)]</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     *
     * @see ExtensionCaptureResult#EFV_PADDING_REGION
     */
    @PublicKey
    @NonNull
    @ExtensionKey
    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    public static final Key<android.util.Pair<Integer,Integer>> EFV_TRANSLATE_VIEWPORT = CaptureRequest.EFV_TRANSLATE_VIEWPORT;

    /**
     * <p>Representing the desired clockwise rotation
     * of the target region in degrees for the
     * {@link android.hardware.camera2.CameraExtensionCharacteristics#EXTENSION_EYES_FREE_VIDEOGRAPHY }
     * extension in {@link android.hardware.camera2.ExtensionCaptureRequest#EFV_STABILIZATION_MODE_LOCKED } mode.</p>
     * <p>Value representing the desired clockwise rotation of the target
     * region in degrees.</p>
     * <p><b>Range of valid values:</b><br>
     * 0 to 360</p>
     * <p><b>Optional</b> - The value for this key may be {@code null} on some devices.</p>
     */
    @PublicKey
    @NonNull
    @ExtensionKey
    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    public static final Key<Float> EFV_ROTATE_VIEWPORT = CaptureRequest.EFV_ROTATE_VIEWPORT;


    //
    // Enumeration values for CaptureRequest#EFV_STABILIZATION_MODE
    //

    /**
     * <p>No stabilization.</p>
     * @see ExtensionCaptureRequest#EFV_STABILIZATION_MODE
     */
    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    public static final int EFV_STABILIZATION_MODE_OFF = CaptureRequest.EFV_STABILIZATION_MODE_OFF;

    /**
     * <p>Gimbal stabilization mode.</p>
     * @see ExtensionCaptureRequest#EFV_STABILIZATION_MODE
     */
    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    public static final int EFV_STABILIZATION_MODE_GIMBAL = CaptureRequest.EFV_STABILIZATION_MODE_GIMBAL;

    /**
     * <p>Locked stabilization mode which uses the
     * {@link android.hardware.camera2.CameraExtensionCharacteristics#EXTENSION_EYES_FREE_VIDEOGRAPHY }
     * stabilization to directionally steady the target region.</p>
     * @see ExtensionCaptureRequest#EFV_STABILIZATION_MODE
     */
    @FlaggedApi(Flags.FLAG_CONCERT_MODE)
    public static final int EFV_STABILIZATION_MODE_LOCKED = CaptureRequest.EFV_STABILIZATION_MODE_LOCKED;

} 