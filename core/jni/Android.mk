LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_CFLAGS += -DHAVE_CONFIG_H -DKHTML_NO_EXCEPTIONS -DGKWQ_NO_JAVA
LOCAL_CFLAGS += -DNO_SUPPORT_JS_BINDING -DQT_NO_WHEELEVENT -DKHTML_NO_XBL
LOCAL_CFLAGS += -U__APPLE__
LOCAL_CFLAGS += -Wno-unused-parameter
LOCAL_CFLAGS += -Wno-non-virtual-dtor
LOCAL_CFLAGS += -Wno-maybe-uninitialized -Wno-parentheses
LOCAL_CPPFLAGS += -Wno-conversion-null

ifeq ($(TARGET_ARCH), arm)
    LOCAL_CFLAGS += -DPACKED="__attribute__ ((packed))"
else
    LOCAL_CFLAGS += -DPACKED=""
endif

ifneq ($(ENABLE_CPUSETS),)
    LOCAL_CFLAGS += -DENABLE_CPUSETS
endif

# TODO: Linear blending should be enabled by default, but we are
# TODO: making it an opt-in while it's a work in progress
# TODO: The final test should be:
# TODO: ifneq ($(TARGET_ENABLE_LINEAR_BLENDING),false)
ifeq ($(TARGET_ENABLE_LINEAR_BLENDING),true)
    LOCAL_CFLAGS += -DANDROID_ENABLE_LINEAR_BLENDING
endif

LOCAL_CFLAGS += -DGL_GLEXT_PROTOTYPES -DEGL_EGLEXT_PROTOTYPES

LOCAL_CFLAGS += -DU_USING_ICU_NAMESPACE=0

LOCAL_SRC_FILES:= \
    AndroidRuntime.cpp \
    com_android_internal_content_NativeLibraryHelper.cpp \
    com_google_android_gles_jni_EGLImpl.cpp \
    com_google_android_gles_jni_GLImpl.cpp.arm \
    android_app_Activity.cpp \
    android_app_ApplicationLoaders.cpp \
    android_app_NativeActivity.cpp \
    android_app_admin_SecurityLog.cpp \
    android_opengl_EGL14.cpp \
    android_opengl_EGLExt.cpp \
    android_opengl_GLES10.cpp \
    android_opengl_GLES10Ext.cpp \
    android_opengl_GLES11.cpp \
    android_opengl_GLES11Ext.cpp \
    android_opengl_GLES20.cpp \
    android_opengl_GLES30.cpp \
    android_opengl_GLES31.cpp \
    android_opengl_GLES31Ext.cpp \
    android_opengl_GLES32.cpp \
    android_database_CursorWindow.cpp \
    android_database_SQLiteCommon.cpp \
    android_database_SQLiteConnection.cpp \
    android_database_SQLiteGlobal.cpp \
    android_database_SQLiteDebug.cpp \
    android_graphics_drawable_AnimatedVectorDrawable.cpp \
    android_graphics_drawable_VectorDrawable.cpp \
    android_view_DisplayEventReceiver.cpp \
    android_view_DisplayListCanvas.cpp \
    android_view_HardwareLayer.cpp \
    android_view_InputChannel.cpp \
    android_view_InputDevice.cpp \
    android_view_InputEventReceiver.cpp \
    android_view_InputEventSender.cpp \
    android_view_InputQueue.cpp \
    android_view_KeyCharacterMap.cpp \
    android_view_KeyEvent.cpp \
    android_view_MotionEvent.cpp \
    android_view_PointerIcon.cpp \
    android_view_RenderNode.cpp \
    android_view_RenderNodeAnimator.cpp \
    android_view_Surface.cpp \
    android_view_SurfaceControl.cpp \
    android_view_SurfaceSession.cpp \
    android_view_TextureView.cpp \
    android_view_ThreadedRenderer.cpp \
    android_view_VelocityTracker.cpp \
    android_text_AndroidCharacter.cpp \
    android_text_AndroidBidi.cpp \
    android_text_StaticLayout.cpp \
    android_os_Debug.cpp \
    android_os_GraphicsEnvironment.cpp \
    android_os_HwBinder.cpp \
    android_os_HwBlob.cpp \
    android_os_HwParcel.cpp \
    android_os_HwRemoteBinder.cpp \
    android_os_MemoryFile.cpp \
    android_os_MessageQueue.cpp \
    android_os_Parcel.cpp \
    android_os_SELinux.cpp \
    android_os_seccomp.cpp \
    android_os_SystemClock.cpp \
    android_os_SystemProperties.cpp \
    android_os_Trace.cpp \
    android_os_UEventObserver.cpp \
    android_os_VintfObject.cpp \
    android_os_VintfRuntimeInfo.cpp \
    android_net_LocalSocketImpl.cpp \
    android_net_NetUtils.cpp \
    android_net_TrafficStats.cpp \
    android_nio_utils.cpp \
    android_util_AssetManager.cpp \
    android_util_Binder.cpp \
    android_util_EventLog.cpp \
    android_util_MemoryIntArray.cpp \
    android_util_Log.cpp \
    android_util_PathParser.cpp \
    android_util_Process.cpp \
    android_util_StringBlock.cpp \
    android_util_XmlBlock.cpp \
    android_util_jar_StrictJarFile.cpp \
    android_graphics_Canvas.cpp \
    android_graphics_Picture.cpp \
    android/graphics/Bitmap.cpp \
    android/graphics/BitmapFactory.cpp \
    android/graphics/Camera.cpp \
    android/graphics/CanvasProperty.cpp \
    android/graphics/ColorFilter.cpp \
    android/graphics/DrawFilter.cpp \
    android/graphics/FontFamily.cpp \
    android/graphics/FontUtils.cpp \
    android/graphics/CreateJavaOutputStreamAdaptor.cpp \
    android/graphics/GIFMovie.cpp \
    android/graphics/GraphicBuffer.cpp \
    android/graphics/Graphics.cpp \
    android/graphics/HarfBuzzNGFaceSkia.cpp \
    android/graphics/Interpolator.cpp \
    android/graphics/MaskFilter.cpp \
    android/graphics/Matrix.cpp \
    android/graphics/Movie.cpp \
    android/graphics/MovieImpl.cpp \
    android/graphics/NinePatch.cpp \
    android/graphics/NinePatchPeeker.cpp \
    android/graphics/Paint.cpp \
    android/graphics/Path.cpp \
    android/graphics/PathMeasure.cpp \
    android/graphics/PathEffect.cpp \
    android/graphics/Picture.cpp \
    android/graphics/BitmapRegionDecoder.cpp \
    android/graphics/Region.cpp \
    android/graphics/Shader.cpp \
    android/graphics/SurfaceTexture.cpp \
    android/graphics/Typeface.cpp \
    android/graphics/Utils.cpp \
    android/graphics/YuvToJpegEncoder.cpp \
    android/graphics/pdf/PdfDocument.cpp \
    android/graphics/pdf/PdfEditor.cpp \
    android/graphics/pdf/PdfRenderer.cpp \
    android/graphics/pdf/PdfUtils.cpp \
    android_media_AudioRecord.cpp \
    android_media_AudioSystem.cpp \
    android_media_AudioTrack.cpp \
    android_media_DeviceCallback.cpp \
    android_media_JetPlayer.cpp \
    android_media_RemoteDisplay.cpp \
    android_media_ToneGenerator.cpp \
    android_hardware_Camera.cpp \
    android_hardware_camera2_CameraMetadata.cpp \
    android_hardware_camera2_legacy_LegacyCameraDevice.cpp \
    android_hardware_camera2_legacy_PerfMeasurement.cpp \
    android_hardware_camera2_DngCreator.cpp \
    android_hardware_display_DisplayViewport.cpp \
    android_hardware_HardwareBuffer.cpp \
    android_hardware_Radio.cpp \
    android_hardware_SensorManager.cpp \
    android_hardware_SerialPort.cpp \
    android_hardware_SoundTrigger.cpp \
    android_hardware_UsbDevice.cpp \
    android_hardware_UsbDeviceConnection.cpp \
    android_hardware_UsbRequest.cpp \
    android_hardware_location_ActivityRecognitionHardware.cpp \
    android_util_FileObserver.cpp \
    android/opengl/poly_clip.cpp.arm \
    android/opengl/util.cpp \
    android_server_NetworkManagementSocketTagger.cpp \
    android_server_Watchdog.cpp \
    android_ddm_DdmHandleNativeHeap.cpp \
    android_backup_BackupDataInput.cpp \
    android_backup_BackupDataOutput.cpp \
    android_backup_FileBackupHelperBase.cpp \
    android_backup_BackupHelperDispatcher.cpp \
    android_app_backup_FullBackup.cpp \
    android_content_res_ObbScanner.cpp \
    android_content_res_Configuration.cpp \
    android_animation_PropertyValuesHolder.cpp \
    com_android_internal_net_NetworkStatsFactory.cpp \
    com_android_internal_os_FuseAppLoop.cpp \
    com_android_internal_os_PathClassLoaderFactory.cpp \
    com_android_internal_os_Zygote.cpp \
    com_android_internal_util_VirtualRefBasePtr.cpp \
    com_android_internal_view_animation_NativeInterpolatorFactoryHelper.cpp \
    hwbinder/EphemeralStorage.cpp \
    fd_utils.cpp \

LOCAL_C_INCLUDES += \
    $(LOCAL_PATH)/include \
    $(JNI_H_INCLUDE) \
    $(LOCAL_PATH)/android/graphics \
    $(LOCAL_PATH)/../../libs/hwui \
    $(LOCAL_PATH)/../../../native/vulkan/include \
    $(call include-path-for, bluedroid) \
    $(call include-path-for, libhardware)/hardware \
    $(call include-path-for, libhardware_legacy)/hardware_legacy \
    $(TOP)/frameworks/base/media/jni \
    $(TOP)/frameworks/rs/cpp \
    $(TOP)/frameworks/rs \
    $(TOP)/system/core/base/include \
    $(TOP)/system/core/include \
    $(TOP)/system/core/libappfuse/include \
    $(TOP)/system/media/camera/include \
    $(TOP)/system/media/private/camera/include \
    $(TOP)/system/netd/include \
    external/giflib \
    external/pdfium/public \
    external/skia/include/private \
    external/skia/src/codec \
    external/skia/src/core \
    external/skia/src/effects \
    external/skia/src/image \
    external/skia/src/images \
    external/sqlite/dist \
    external/sqlite/android \
    external/tremor/Tremor \
    external/harfbuzz_ng/src \
    libcore/include \
    $(call include-path-for, audio-utils) \
    frameworks/minikin/include \
    external/freetype/include
# TODO: clean up Minikin so it doesn't need the freetype include

LOCAL_STATIC_LIBRARIES := \
    libgif \
    libseccomp_policy \
    libselinux \
    libcrypto \
    libgrallocusage \

LOCAL_SHARED_LIBRARIES := \
    libmemtrack \
    libandroidfw \
    libappfuse \
    libbase \
    libnativehelper \
    liblog \
    libcutils \
    libdebuggerd_client \
    libutils \
    libbinder \
    libui \
    libgui \
    libsensor \
    libinput \
    libcamera_client \
    libcamera_metadata \
    libskia \
    libsqlite \
    libEGL \
    libGLESv1_CM \
    libGLESv2 \
    libvulkan \
    libziparchive \
    libETC1 \
    libhardware \
    libhardware_legacy \
    libselinux \
    libicuuc \
    libmedia \
    libaudioclient \
    libjpeg \
    libusbhost \
    libharfbuzz_ng \
    libz \
    libpdfium \
    libimg_utils \
    libnetd_client \
    libradio \
    libsoundtrigger \
    libminikin \
    libprocessgroup \
    libnativebridge \
    libradio_metadata \
    libnativeloader \
    libmemunreachable \
    libhidlbase \
    libhidltransport \
    libhwbinder \
    libvintf \
    libnativewindow \

LOCAL_SHARED_LIBRARIES += \
    libhwui \
    libdl \

# our headers include libnativewindow's public headers
LOCAL_EXPORT_SHARED_LIBRARY_HEADERS := \
    libnativewindow \

# we need to access the private Bionic header
# <bionic_tls.h> in com_google_android_gles_jni_GLImpl.cpp
LOCAL_C_INCLUDES += bionic/libc/private

LOCAL_EXPORT_C_INCLUDE_DIRS := $(LOCAL_PATH)/include

# AndroidRuntime.h depends on nativehelper/jni.h
LOCAL_EXPORT_C_INCLUDE_DIRS += libnativehelper/include

LOCAL_MODULE:= libandroid_runtime

# -Wno-unknown-pragmas: necessary for Clang as the GL bindings need to turn
#                       off a GCC warning that Clang doesn't know.
LOCAL_CFLAGS += -Wall -Werror -Wno-error=deprecated-declarations -Wunused -Wunreachable-code \
        -Wno-unknown-pragmas

# -Wno-c++11-extensions: Clang warns about Skia using the C++11 override keyword, but this project
#                        is not being compiled with that level. Remove once this has changed.
LOCAL_CLANG_CFLAGS += -Wno-c++11-extensions

ifneq (,$(filter userdebug eng,$(TARGET_BUILD_VARIANT)))
LOCAL_CFLAGS += -D__ANDROID_DEBUGGABLE__
endif
ifneq (,$(filter true, $(PRODUCT_FULL_TREBLE)))
LOCAL_CFLAGS += -D__ANDROID_TREBLE__
endif

include $(BUILD_SHARED_LIBRARY)

include $(call all-makefiles-under,$(LOCAL_PATH))
