/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.content.pm;

import android.Manifest;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.RequiresPermission;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Printer;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Information you can retrieve about a particular application
 * service. This corresponds to information collected from the
 * AndroidManifest.xml's &lt;service&gt; tags.
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class ServiceInfo extends ComponentInfo
        implements Parcelable {
    /**
     * Optional name of a permission required to be able to access this
     * Service.  From the "permission" attribute.
     */
    public String permission;

    /**
     * Bit in {@link #flags}: If set, the service will automatically be
     * stopped by the system if the user removes a task that is rooted
     * in one of the application's activities.  Set from the
     * {@link android.R.attr#stopWithTask} attribute.
     */
    public static final int FLAG_STOP_WITH_TASK = 0x0001;

    /**
     * Bit in {@link #flags}: If set, the service will run in its own
     * isolated process.  Set from the
     * {@link android.R.attr#isolatedProcess} attribute.
     */
    public static final int FLAG_ISOLATED_PROCESS = 0x0002;

    /**
     * Bit in {@link #flags}: If set, the service can be bound and run in the
     * calling application's package, rather than the package in which it is
     * declared.  Set from {@link android.R.attr#externalService} attribute.
     */
    public static final int FLAG_EXTERNAL_SERVICE = 0x0004;

    /**
     * Bit in {@link #flags}: If set, the service (which must be isolated)
     * will be spawned from an Application Zygote, instead of the regular Zygote.
     * The Application Zygote will pre-initialize the application's class loader,
     * and call a static callback into the application to allow it to perform
     * application-specific preloads (such as loading a shared library). Therefore,
     * spawning from the Application Zygote will typically reduce the service
     * launch time and reduce its memory usage. The downside of using this flag
     * is that you will have an additional process (the app zygote itself) that
     * is taking up memory. Whether actual memory usage is improved therefore
     * strongly depends on the number of isolated services that an application
     * starts, and how much memory those services save by preloading. Therefore,
     * it is recommended to measure memory usage under typical workloads to
     * determine whether it makes sense to use this flag.
     */
    public static final int FLAG_USE_APP_ZYGOTE = 0x0008;

    /**
     * Bit in {@link #flags}: If set, and this is an {@link android.R.attr#isolatedProcess}
     * service, the service is allowed to be bound in a shared isolated process with other
     * isolated services. Note that these other isolated services can also belong to other
     * apps from different vendors.
     *
     * Shared isolated processes are created when using the
     * {@link android.content.Context#BIND_SHARED_ISOLATED_PROCESS) during service binding.
     *
     * Note that when this flag is used, the {@link android.R.attr#process} attribute is
     * ignored when the process is bound into a shared isolated process by a client.
     */
    public static final int FLAG_ALLOW_SHARED_ISOLATED_PROCESS = 0x0010;

    /**
     * Bit in {@link #flags} indicating if the service is visible to ephemeral applications.
     * @hide
     */
    public static final int FLAG_VISIBLE_TO_INSTANT_APP = 0x100000;

    /**
     * Bit in {@link #flags}: If set, a single instance of the service will
     * run for all users on the device.  Set from the
     * {@link android.R.attr#singleUser} attribute.
     */
    public static final int FLAG_SINGLE_USER = 0x40000000;

    /**
     * Options that have been set in the service declaration in the
     * manifest.
     * These include:
     * {@link #FLAG_STOP_WITH_TASK}, {@link #FLAG_ISOLATED_PROCESS},
     * {@link #FLAG_SINGLE_USER}.
     */
    public int flags;

    /**
     * The default foreground service type if not been set in manifest file.
     *
     * <p>Apps targeting API level {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE} and
     * later should NOT use this type,
     * calling {@link android.app.Service#startForeground(int, android.app.Notification, int)} with
     * this type will get a {@link android.app.InvalidForegroundServiceTypeException}.</p>
     *
     * @deprecated Do not use.
     */
    @Deprecated
    public static final int FOREGROUND_SERVICE_TYPE_NONE = 0;

    /**
     * Constant corresponding to <code>dataSync</code> in
     * the {@link android.R.attr#foregroundServiceType} attribute.
     * Data(photo, file, account) upload/download, backup/restore, import/export, fetch,
     * transfer over network between device and cloud.
     */
    @RequiresPermission(
            value = Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC,
            conditional = true
    )
    public static final int FOREGROUND_SERVICE_TYPE_DATA_SYNC = 1 << 0;

    /**
     * Constant corresponding to <code>mediaPlayback</code> in
     * the {@link android.R.attr#foregroundServiceType} attribute.
     * Music, video, news or other media playback.
     *
     * <p>Starting foreground service with this type from apps targeting API level
     * {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE} and later, will require permission
     * {@link android.Manifest.permission#FOREGROUND_SERVICE_MEDIA_PLAYBACK}.
     */
    @RequiresPermission(
            value = Manifest.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK,
            conditional = true
    )
    public static final int FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK = 1 << 1;

    /**
     * Constant corresponding to <code>phoneCall</code> in
     * the {@link android.R.attr#foregroundServiceType} attribute.
     * Ongoing operations related to phone calls, video conferencing,
     * or similar interactive communication.
     *
     * <p>Starting foreground service with this type from apps targeting API level
     * {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE} and later, will require permission
     * {@link android.Manifest.permission#FOREGROUND_SERVICE_PHONE_CALL} and
     * {@link android.Manifest.permission#MANAGE_OWN_CALLS} or holding the default
     * {@link android.app.role.RoleManager#ROLE_DIALER dialer role}.
     */
    @RequiresPermission(
            allOf = {
                Manifest.permission.FOREGROUND_SERVICE_PHONE_CALL,
            },
            anyOf = {
                Manifest.permission.MANAGE_OWN_CALLS,
            },
            conditional = true
    )
    public static final int FOREGROUND_SERVICE_TYPE_PHONE_CALL = 1 << 2;

    /**
     * Constant corresponding to <code>location</code> in
     * the {@link android.R.attr#foregroundServiceType} attribute.
     * GPS, map, navigation location update.
     *
     * <p>Starting foreground service with this type from apps targeting API level
     * {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE} and later, will require permission
     * {@link android.Manifest.permission#FOREGROUND_SERVICE_LOCATION} and one of the
     * following permissions:
     * {@link android.Manifest.permission#ACCESS_COARSE_LOCATION},
     * {@link android.Manifest.permission#ACCESS_FINE_LOCATION}.
     */
    @RequiresPermission(
            allOf = {
                Manifest.permission.FOREGROUND_SERVICE_LOCATION,
            },
            anyOf = {
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            },
            conditional = true
    )
    public static final int FOREGROUND_SERVICE_TYPE_LOCATION = 1 << 3;

    /**
     * Constant corresponding to <code>connectedDevice</code> in
     * the {@link android.R.attr#foregroundServiceType} attribute.
     * Auto, bluetooth, TV or other devices connection, monitoring and interaction.
     *
     * <p>Starting foreground service with this type from apps targeting API level
     * {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE} and later, will require permission
     * {@link android.Manifest.permission#FOREGROUND_SERVICE_CONNECTED_DEVICE} and one of the
     * following permissions:
     * {@link android.Manifest.permission#BLUETOOTH_ADVERTISE},
     * {@link android.Manifest.permission#BLUETOOTH_CONNECT},
     * {@link android.Manifest.permission#BLUETOOTH_SCAN},
     * {@link android.Manifest.permission#CHANGE_NETWORK_STATE},
     * {@link android.Manifest.permission#CHANGE_WIFI_STATE},
     * {@link android.Manifest.permission#CHANGE_WIFI_MULTICAST_STATE},
     * {@link android.Manifest.permission#NFC},
     * {@link android.Manifest.permission#TRANSMIT_IR},
     * {@link android.Manifest.permission#UWB_RANGING},
     * or has been granted the access to one of the attached USB devices/accessories.
     */
    @RequiresPermission(
            allOf = {
                Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE,
            },
            anyOf = {
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.CHANGE_NETWORK_STATE,
                Manifest.permission.CHANGE_WIFI_STATE,
                Manifest.permission.CHANGE_WIFI_MULTICAST_STATE,
                Manifest.permission.NFC,
                Manifest.permission.TRANSMIT_IR,
                Manifest.permission.UWB_RANGING,
            },
            conditional = true
    )
    public static final int FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE = 1 << 4;

    /**
     * Constant corresponding to {@code mediaProjection} in
     * the {@link android.R.attr#foregroundServiceType foregroundServiceType} attribute.
     *
     * <p>
     * To capture through {@link android.media.projection.MediaProjection}, an app must start a
     * foreground service with the type corresponding to this constant. This type should only be
     * used for {@link android.media.projection.MediaProjection}. Capturing screen contents via
     * {@link android.media.projection.MediaProjection#createVirtualDisplay(String, int, int, int,
     * int, android.view.Surface, android.hardware.display.VirtualDisplay.Callback,
     * android.os.Handler) createVirtualDisplay} conveniently allows recording, presenting screen
     * contents into a meeting, taking screenshots, or several other scenarios.
     * </p>
     *
     * <p>Starting foreground service with this type from apps targeting API level
     * {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE} and later, will require permission
     * {@link android.Manifest.permission#FOREGROUND_SERVICE_MEDIA_PROJECTION}, and the user must
     * have allowed the screen capture request from this app.
     */
    @RequiresPermission(
            value = Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION,
            conditional = true
    )
    public static final int FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION = 1 << 5;

    /**
     * Constant corresponding to {@code camera} in
     * the {@link android.R.attr#foregroundServiceType} attribute.
     * Use the camera device or record video.
     * For apps with <code>targetSdkVersion</code> {@link android.os.Build.VERSION_CODES#R} and
     * above, a foreground service will not be able to access the camera if this type is not
     * specified in the manifest and in
     * {@link android.app.Service#startForeground(int, android.app.Notification, int)}.
     *
     * <p>Starting foreground service with this type from apps targeting API level
     * {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE} and later, will require permission
     * {@link android.Manifest.permission#FOREGROUND_SERVICE_CAMERA} and
     * {@link android.Manifest.permission#CAMERA}.
     */
    @RequiresPermission(
            allOf = {
                Manifest.permission.FOREGROUND_SERVICE_CAMERA,
            },
            anyOf = {
                Manifest.permission.CAMERA,
            },
            conditional = true
    )
    public static final int FOREGROUND_SERVICE_TYPE_CAMERA = 1 << 6;

    /**
     * Constant corresponding to {@code microphone} in
     * the {@link android.R.attr#foregroundServiceType} attribute.
     * Use the microphone device or record audio.
     * For apps with <code>targetSdkVersion</code> {@link android.os.Build.VERSION_CODES#R} and
     * above, a foreground service will not be able to access the microphone if this type is not
     * specified in the manifest and in
     * {@link android.app.Service#startForeground(int, android.app.Notification, int)}.
     *
     * <p>Starting foreground service with this type from apps targeting API level
     * {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE} and later, will require permission
     * {@link android.Manifest.permission#FOREGROUND_SERVICE_MICROPHONE} and one of the following
     * permissions:
     * {@link android.Manifest.permission#CAPTURE_AUDIO_OUTPUT},
     * {@link android.Manifest.permission#RECORD_AUDIO}.
     */
    @RequiresPermission(
            allOf = {
                Manifest.permission.FOREGROUND_SERVICE_MICROPHONE,
            },
            anyOf = {
                Manifest.permission.CAPTURE_AUDIO_OUTPUT,
                Manifest.permission.RECORD_AUDIO,
            },
            conditional = true
    )
    public static final int FOREGROUND_SERVICE_TYPE_MICROPHONE = 1 << 7;

    /**
     * Constant corresponding to {@code health} in
     * the {@link android.R.attr#foregroundServiceType} attribute.
     * Health, wellness and fitness.
     *
     * <p>The caller app is required to have the permissions
     * {@link android.Manifest.permission#FOREGROUND_SERVICE_HEALTH} and one of the following
     * permissions:
     * {@link android.Manifest.permission#ACTIVITY_RECOGNITION},
     * {@link android.Manifest.permission#BODY_SENSORS},
     * {@link android.Manifest.permission#HIGH_SAMPLING_RATE_SENSORS}.
     */
    @RequiresPermission(
            allOf = {
                Manifest.permission.FOREGROUND_SERVICE_HEALTH,
            },
            anyOf = {
                Manifest.permission.ACTIVITY_RECOGNITION,
                Manifest.permission.BODY_SENSORS,
                Manifest.permission.HIGH_SAMPLING_RATE_SENSORS,
            }
    )
    public static final int FOREGROUND_SERVICE_TYPE_HEALTH = 1 << 8;

    /**
     * Constant corresponding to {@code remoteMessaging} in
     * the {@link android.R.attr#foregroundServiceType} attribute.
     * Messaging use cases which host local server to relay messages across devices.
     */
    @RequiresPermission(
            value = Manifest.permission.FOREGROUND_SERVICE_REMOTE_MESSAGING
    )
    public static final int FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING = 1 << 9;

    /**
     * Constant corresponding to {@code systemExempted} in
     * the {@link android.R.attr#foregroundServiceType} attribute.
     * The system exempted foreground service use cases.
     *
     * <p class="note">Note, apps are allowed to use this type only in the following cases:
     * <ul>
     *   <li>App has a UID &lt; {@link android.os.Process#FIRST_APPLICATION_UID}</li>
     *   <li>App is on Doze allowlist</li>
     *   <li>Device is running in <a href="https://android.googlesource.com/platform/frameworks/base/+/master/packages/SystemUI/docs/demo_mode.md">Demo Mode</a></li>
     *   <li><a href="https://source.android.com/devices/tech/admin/provision">Device owner app</a></li>
     *   <li><a href="https://source.android.com/devices/tech/admin/managed-profiles">Profile owner apps</a></li>
     *   <li>Persistent apps</li>
     *   <li><a href="https://source.android.com/docs/core/connect/carrier">Carrier privileged apps</a></li>
     *   <li>Apps that have the {@code android.app.role.RoleManager#ROLE_EMERGENCY} role</li>
     *   <li>Headless system apps</li>
     *   <li><a href="{@docRoot}guide/topics/admin/device-admin">Device admin apps</a></li>
     *   <li>Active VPN apps</li>
     *   <li>Apps holding {@link android.Manifest.permission#SCHEDULE_EXACT_ALARM} or
     *       {@link android.Manifest.permission#USE_EXACT_ALARM} permission.</li>
     * </ul>
     * </p>
     */
    @RequiresPermission(
            value = Manifest.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED
    )
    public static final int FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED = 1 << 10;

    /**
     * A foreground service type for "short-lived" services, which corresponds to
     * {@code shortService} in the {@link android.R.attr#foregroundServiceType} attribute in the
     * manifest.
     *
     * <p>Unlike other foreground service types, this type is not associated with a specific use
     * case, and it will not require any special permissions
     * (besides {@link android.Manifest.permission#FOREGROUND_SERVICE}).
     *
     * However, this type has the following restrictions.
     *
     * <ul>
     *     <li>
     *         The type has a 3 minute timeout.
     *         A foreground service of this type must be stopped within the timeout by
     *         {@link android.app.Service#stopSelf()},
     *         {@link android.content.Context#stopService(android.content.Intent)}
     *         or their overloads).
     *         {@link android.app.Service#stopForeground(int)} will also work,
     *         which will demote the
     *         service to a "background" service, which will soon be stopped by the system.
     *
     *         <p>If the service isn't stopped within the timeout,
     *         {@link android.app.Service#onTimeout(int)} will be called. Note, even when the
     *         system calls this callback, it will not stop the service automatically.
     *         You still need to stop the service using one of the aforementioned
     *         ways even when you get this callback.
     *
     *         <p>If the service is still not stopped after the callback,
     *         the app will be declared an ANR, after a short grace period of several seconds.
     *     <li>
     *         A foreground service of this type cannot be made "sticky"
     *         (see {@link android.app.Service#START_STICKY}). That is, if an app is killed
     *         due to a crash or out-of memory while it's running a short foregorund-service,
     *         the system will not restart the service.
     *     <li>
     *         Other foreground services cannot be started from short foreground services.
     *         Unlike other foreground service types, when an app is running in the background
     *         while only having a "short" foreground service, it's not allowed to start
     *         other foreground services, due to the restriction describe here:
     *         <a href="/guide/components/foreground-services#background-start-restrictions>
     *             Restrictions on background starts
     *         </a>
     *     <li>
     *         You can combine multiple foreground services types with {@code |}s, and you can
     *         combine
     *         {@link android.content.pm.ServiceInfo#FOREGROUND_SERVICE_TYPE_SHORT_SERVICE}.
     *         with other types as well.
     *         However,
     *         {@link android.content.pm.ServiceInfo#FOREGROUND_SERVICE_TYPE_SHORT_SERVICE}
     *         is for situations
     *         where you have no other valid foreground services to use and the timeout is long
     *         enough for the task, and if you can use other types, there's no point using
     *         this type.
     *         For this reason, if
     *         {@link android.content.pm.ServiceInfo#FOREGROUND_SERVICE_TYPE_SHORT_SERVICE}
     *         is combined with other foreground service types, the system will simply ignore
     *         it, and as a result,
     *         none of the above restrictions will apply (e.g. there'll be no timeout).
     * </ul>
     *
     * <p>Also note, even though
     * {@link android.content.pm.ServiceInfo#FOREGROUND_SERVICE_TYPE_SHORT_SERVICE}
     * was added
     * on Android version {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE},
     * it can be also used on
     * on prior android versions (just like other new foreground service types can be used).
     * However, because {@link android.app.Service#onTimeout(int)} did not exist on prior versions,
     * it will never called on such versions.
     * Because of this, developers must make sure to stop the foreground service even if
     * {@link android.app.Service#onTimeout(int)} is not called on such versions.
     *
     * @see android.app.Service#onTimeout(int)
     */
    public static final int FOREGROUND_SERVICE_TYPE_SHORT_SERVICE = 1 << 11;

    /**
     * Constant corresponding to {@code fileManagement} in
     * the {@link android.R.attr#foregroundServiceType} attribute.
     * The file management use case which manages files/directories, often involving file I/O
     * across the file system.
     *
     * @hide
     */
    @RequiresPermission(
            value = Manifest.permission.FOREGROUND_SERVICE_FILE_MANAGEMENT
    )
    public static final int FOREGROUND_SERVICE_TYPE_FILE_MANAGEMENT = 1 << 12;

    /**
     * Constant corresponding to {@code mediaProcessing} in
     * the {@link android.R.attr#foregroundServiceType} attribute.
     * Media processing use cases such as video or photo editing and processing.
     */
    @RequiresPermission(
            value = Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING
    )
    @FlaggedApi(Flags.FLAG_INTRODUCE_MEDIA_PROCESSING_TYPE)
    public static final int FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING = 1 << 13;

    /**
     * Constant corresponding to {@code specialUse} in
     * the {@link android.R.attr#foregroundServiceType} attribute.
     * Use cases that can't be categorized into any other foreground service types, but also
     * can't use {@link android.app.job.JobInfo.Builder} APIs.
     *
     * <p>The use of this foreground service type may be restricted. Additionally, apps must declare
     * a service-level {@link PackageManager#PROPERTY_SPECIAL_USE_FGS_SUBTYPE &lt;property&gt;} in
     * {@code AndroidManifest.xml} as a hint of what the exact use case here is.
     * Here is an example:
     * <pre>
     *  &lt;uses-permission
     *      android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE"
     *  /&gt;
     *  &lt;service
     *      android:name=".MySpecialForegroundService"
     *      android:foregroundServiceType="specialUse"&gt;
     *      &lt;property
     *          android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
     *          android:value="foo"
     *      /&gt;
     * &lt;/service&gt;
     * </pre>
     *
     * In a future release of Android, if the above foreground service type {@code foo} is supported
     * by the platform, to offer the backward compatibility, the app could specify
     * the {@code android:maxSdkVersion} attribute in the &lt;uses-permission&gt; section,
     * and also add the foreground service type {@code foo} into
     * the {@code android:foregroundServiceType}, therefore the same app could be installed
     * in both platforms.
     * <pre>
     *  &lt;uses-permission
     *      android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE"
     *      android:maxSdkVersion="last_sdk_version_without_type_foo"
     *  /&gt;
     *  &lt;service
     *      android:name=".MySpecialForegroundService"
     *      android:foregroundServiceType="specialUse|foo"&gt;
     *      &lt;property
     *          android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
     *          android:value="foo"
     *      /&gt;
     * &lt;/service&gt;
     * </pre>
     */
    @RequiresPermission(
            value = Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE
    )
    public static final int FOREGROUND_SERVICE_TYPE_SPECIAL_USE = 1 << 30;

    /**
     * The max index being used in the definition of foreground service types.
     *
     * @hide
     */
    public static final int FOREGROUND_SERVICE_TYPES_MAX_INDEX = 30;

    /**
     * A special value indicates to use all types set in manifest file.
     */
    public static final int FOREGROUND_SERVICE_TYPE_MANIFEST = -1;

    /**
     * The set of flags for foreground service type.
     * The foreground service type is set in {@link android.R.attr#foregroundServiceType}
     * attribute.
     * @hide
     */
    @IntDef(flag = true, prefix = { "FOREGROUND_SERVICE_TYPE_" }, value = {
            FOREGROUND_SERVICE_TYPE_MANIFEST,
            FOREGROUND_SERVICE_TYPE_NONE,
            FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            FOREGROUND_SERVICE_TYPE_PHONE_CALL,
            FOREGROUND_SERVICE_TYPE_LOCATION,
            FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            FOREGROUND_SERVICE_TYPE_CAMERA,
            FOREGROUND_SERVICE_TYPE_MICROPHONE,
            FOREGROUND_SERVICE_TYPE_HEALTH,
            FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
            FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
            FOREGROUND_SERVICE_TYPE_SHORT_SERVICE,
            FOREGROUND_SERVICE_TYPE_FILE_MANAGEMENT,
            FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ForegroundServiceType {}

    /**
     * The type of foreground service, set in
     * {@link android.R.attr#foregroundServiceType} attribute by ORing flags in
     * {@link ForegroundServiceType}
     * @hide
     */
    public @ForegroundServiceType int mForegroundServiceType = FOREGROUND_SERVICE_TYPE_NONE;

    public ServiceInfo() {
    }

    public ServiceInfo(ServiceInfo orig) {
        super(orig);
        permission = orig.permission;
        flags = orig.flags;
        mForegroundServiceType = orig.mForegroundServiceType;
    }

    /**
     * Return foreground service type specified in the manifest..
     * @return foreground service type specified in the manifest.
     */
    public @ForegroundServiceType int getForegroundServiceType() {
        return mForegroundServiceType;
    }

    public void dump(Printer pw, String prefix) {
        dump(pw, prefix, DUMP_FLAG_ALL);
    }

    /** @hide */
    void dump(Printer pw, String prefix, int dumpFlags) {
        super.dumpFront(pw, prefix);
        pw.println(prefix + "permission=" + permission);
        pw.println(prefix + "flags=0x" + Integer.toHexString(flags));
        super.dumpBack(pw, prefix, dumpFlags);
    }

    public String toString() {
        return "ServiceInfo{"
            + Integer.toHexString(System.identityHashCode(this))
            + " " + name + "}";
    }

    /**
     * @return The label for the given foreground service type.
     *
     * @hide
     */
    public static String foregroundServiceTypeToLabel(@ForegroundServiceType int type) {
        switch (type) {
            case FOREGROUND_SERVICE_TYPE_MANIFEST:
                return "manifest";
            case FOREGROUND_SERVICE_TYPE_NONE:
                return "none";
            case FOREGROUND_SERVICE_TYPE_DATA_SYNC:
                return "dataSync";
            case FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK:
                return "mediaPlayback";
            case FOREGROUND_SERVICE_TYPE_PHONE_CALL:
                return "phoneCall";
            case FOREGROUND_SERVICE_TYPE_LOCATION:
                return "location";
            case FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE:
                return "connectedDevice";
            case FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION:
                return "mediaProjection";
            case FOREGROUND_SERVICE_TYPE_CAMERA:
                return "camera";
            case FOREGROUND_SERVICE_TYPE_MICROPHONE:
                return "microphone";
            case FOREGROUND_SERVICE_TYPE_HEALTH:
                return "health";
            case FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING:
                return "remoteMessaging";
            case FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED:
                return "systemExempted";
            case FOREGROUND_SERVICE_TYPE_SHORT_SERVICE:
                return "shortService";
            case FOREGROUND_SERVICE_TYPE_FILE_MANAGEMENT:
                return "fileManagement";
            case FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING:
                return "mediaProcessing";
            case FOREGROUND_SERVICE_TYPE_SPECIAL_USE:
                return "specialUse";
            default:
                return "unknown";
        }
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int parcelableFlags) {
        super.writeToParcel(dest, parcelableFlags);
        dest.writeString8(permission);
        dest.writeInt(flags);
        dest.writeInt(mForegroundServiceType);
    }

    public static final @android.annotation.NonNull Creator<ServiceInfo> CREATOR =
        new Creator<ServiceInfo>() {
        public ServiceInfo createFromParcel(Parcel source) {
            return new ServiceInfo(source);
        }
        public ServiceInfo[] newArray(int size) {
            return new ServiceInfo[size];
        }
    };

    private ServiceInfo(Parcel source) {
        super(source);
        permission = source.readString8();
        flags = source.readInt();
        mForegroundServiceType = source.readInt();
    }
}
