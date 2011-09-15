LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_CFLAGS += -DHAVE_CONFIG_H -DKHTML_NO_EXCEPTIONS -DGKWQ_NO_JAVA
LOCAL_CFLAGS += -DNO_SUPPORT_JS_BINDING -DQT_NO_WHEELEVENT -DKHTML_NO_XBL
LOCAL_CFLAGS += -U__APPLE__

ifeq ($(TARGET_ARCH), arm)
	LOCAL_CFLAGS += -DPACKED="__attribute__ ((packed))"
else
	LOCAL_CFLAGS += -DPACKED=""
endif

ifeq ($(WITH_JIT),true)
	LOCAL_CFLAGS += -DWITH_JIT
endif

ifneq ($(USE_CUSTOM_RUNTIME_HEAP_MAX),)
  LOCAL_CFLAGS += -DCUSTOM_RUNTIME_HEAP_MAX=$(USE_CUSTOM_RUNTIME_HEAP_MAX)
endif

ifeq ($(USE_OPENGL_RENDERER),true)
	LOCAL_CFLAGS += -DUSE_OPENGL_RENDERER
endif

LOCAL_CFLAGS += -DGL_GLEXT_PROTOTYPES -DEGL_EGLEXT_PROTOTYPES

LOCAL_SRC_FILES:= \
	ActivityManager.cpp \
	AndroidRuntime.cpp \
	Time.cpp \
	com_android_internal_content_NativeLibraryHelper.cpp \
	com_google_android_gles_jni_EGLImpl.cpp \
	com_google_android_gles_jni_GLImpl.cpp.arm \
	android_app_NativeActivity.cpp \
	android_opengl_GLES10.cpp \
	android_opengl_GLES10Ext.cpp \
	android_opengl_GLES11.cpp \
	android_opengl_GLES11Ext.cpp \
	android_opengl_GLES20.cpp \
	android_database_CursorWindow.cpp \
	android_database_SQLiteCompiledSql.cpp \
	android_database_SQLiteDebug.cpp \
	android_database_SQLiteDatabase.cpp \
	android_database_SQLiteProgram.cpp \
	android_database_SQLiteQuery.cpp \
	android_database_SQLiteStatement.cpp \
	android_emoji_EmojiFactory.cpp \
	android_view_Display.cpp \
	android_view_Surface.cpp \
	android_view_TextureView.cpp \
	android_view_InputChannel.cpp \
	android_view_InputQueue.cpp \
	android_view_KeyEvent.cpp \
	android_view_KeyCharacterMap.cpp \
	android_view_GLES20Canvas.cpp \
	android_view_MotionEvent.cpp \
	android_view_PointerIcon.cpp \
	android_view_VelocityTracker.cpp \
	android_text_AndroidCharacter.cpp \
	android_text_AndroidBidi.cpp \
	android_os_Debug.cpp \
	android_os_FileUtils.cpp \
	android_os_MemoryFile.cpp \
	android_os_MessageQueue.cpp \
	android_os_ParcelFileDescriptor.cpp \
	android_os_Power.cpp \
	android_os_StatFs.cpp \
	android_os_SystemClock.cpp \
	android_os_SystemProperties.cpp \
	android_os_UEventObserver.cpp \
	android_net_LocalSocketImpl.cpp \
	android_net_NetUtils.cpp \
	android_net_TrafficStats.cpp \
	android_net_wifi_Wifi.cpp \
	android_nio_utils.cpp \
	android_nfc_NdefMessage.cpp \
	android_nfc_NdefRecord.cpp \
	android_text_format_Time.cpp \
	android_util_AssetManager.cpp \
	android_util_Binder.cpp \
	android_util_EventLog.cpp \
	android_util_Log.cpp \
	android_util_FloatMath.cpp \
	android_util_Process.cpp \
	android_util_StringBlock.cpp \
	android_util_XmlBlock.cpp \
	android/graphics/AutoDecodeCancel.cpp \
	android/graphics/Bitmap.cpp \
	android/graphics/BitmapFactory.cpp \
	android/graphics/Camera.cpp \
	android/graphics/Canvas.cpp \
	android/graphics/ColorFilter.cpp \
	android/graphics/DrawFilter.cpp \
	android/graphics/CreateJavaOutputStreamAdaptor.cpp \
	android/graphics/Graphics.cpp \
	android/graphics/HarfbuzzSkia.cpp \
	android/graphics/Interpolator.cpp \
	android/graphics/LayerRasterizer.cpp \
	android/graphics/MaskFilter.cpp \
	android/graphics/Matrix.cpp \
	android/graphics/Movie.cpp \
	android/graphics/NinePatch.cpp \
	android/graphics/NinePatchImpl.cpp \
	android/graphics/NinePatchPeeker.cpp \
	android/graphics/Paint.cpp \
	android/graphics/Path.cpp \
	android/graphics/PathMeasure.cpp \
	android/graphics/PathEffect.cpp \
	android_graphics_PixelFormat.cpp \
	android/graphics/Picture.cpp \
	android/graphics/PorterDuff.cpp \
	android/graphics/BitmapRegionDecoder.cpp \
	android/graphics/Rasterizer.cpp \
	android/graphics/Region.cpp \
	android/graphics/Shader.cpp \
	android/graphics/SurfaceTexture.cpp \
	android/graphics/TextLayout.cpp \
	android/graphics/TextLayoutCache.cpp \
	android/graphics/Typeface.cpp \
	android/graphics/Utils.cpp \
	android/graphics/Xfermode.cpp \
	android/graphics/YuvToJpegEncoder.cpp \
	android_media_AudioRecord.cpp \
	android_media_AudioSystem.cpp \
	android_media_AudioTrack.cpp \
	android_media_JetPlayer.cpp \
	android_media_ToneGenerator.cpp \
	android_hardware_Camera.cpp \
	android_hardware_SensorManager.cpp \
	android_hardware_UsbDevice.cpp \
	android_hardware_UsbDeviceConnection.cpp \
	android_hardware_UsbRequest.cpp \
	android_debug_JNITest.cpp \
	android_util_FileObserver.cpp \
	android/opengl/poly_clip.cpp.arm \
	android/opengl/util.cpp.arm \
	android_bluetooth_HeadsetBase.cpp \
	android_bluetooth_common.cpp \
	android_bluetooth_BluetoothAudioGateway.cpp \
	android_bluetooth_BluetoothSocket.cpp \
	android_bluetooth_c.c \
	android_server_BluetoothService.cpp \
	android_server_BluetoothEventLoop.cpp \
	android_server_BluetoothA2dpService.cpp \
	android_server_NetworkManagementSocketTagger.cpp \
	android_server_Watchdog.cpp \
	android_ddm_DdmHandleNativeHeap.cpp \
	com_android_internal_os_ZygoteInit.cpp \
	android_backup_BackupDataInput.cpp \
	android_backup_BackupDataOutput.cpp \
	android_backup_FileBackupHelperBase.cpp \
	android_backup_BackupHelperDispatcher.cpp \
	android_app_backup_FullBackup.cpp \
	android_content_res_ObbScanner.cpp \
	android_content_res_Configuration.cpp \
    android_animation_PropertyValuesHolder.cpp

LOCAL_C_INCLUDES += \
	$(JNI_H_INCLUDE) \
	$(LOCAL_PATH)/android/graphics \
	$(LOCAL_PATH)/../../libs/hwui \
	$(call include-path-for, bluedroid) \
	$(call include-path-for, libhardware)/hardware \
	$(call include-path-for, libhardware_legacy)/hardware_legacy \
	$(LOCAL_PATH)/../../include/ui \
	$(LOCAL_PATH)/../../include/utils \
	external/skia/include/core \
	external/skia/include/effects \
	external/skia/include/images \
	external/skia/src/ports \
	external/skia/include/utils \
	external/sqlite/dist \
	external/sqlite/android \
	external/expat/lib \
	external/openssl/include \
	external/tremor/Tremor \
	external/icu4c/i18n \
	external/icu4c/common \
	external/jpeg \
	external/harfbuzz/contrib \
	external/harfbuzz/src \
	external/zlib \
	frameworks/opt/emoji \
	libcore/include

LOCAL_SHARED_LIBRARIES := \
	libexpat \
	libnativehelper \
	libcutils \
	libutils \
	libbinder \
	libnetutils \
	libui \
	libgui \
	libcamera_client \
	libskia \
	libsqlite \
	libdvm \
	libEGL \
	libGLESv1_CM \
	libGLESv2 \
	libETC1 \
	libhardware \
	libhardware_legacy \
	libsonivox \
	libcrypto \
	libssl \
	libicuuc \
	libicui18n \
	libmedia \
	libwpa_client \
	libjpeg \
	libnfc_ndef \
	libusbhost \
	libharfbuzz \
	libz \

ifeq ($(USE_OPENGL_RENDERER),true)
	LOCAL_SHARED_LIBRARIES += libhwui
endif

ifeq ($(BOARD_HAVE_BLUETOOTH),true)
LOCAL_C_INCLUDES += \
	external/dbus \
	system/bluetooth/bluez-clean-headers
LOCAL_CFLAGS += -DHAVE_BLUETOOTH
LOCAL_SHARED_LIBRARIES += libbluedroid libdbus
endif

LOCAL_SHARED_LIBRARIES += \
	libdl
# we need to access the private Bionic header
# <bionic_tls.h> in com_google_android_gles_jni_GLImpl.cpp
LOCAL_CFLAGS += -I$(LOCAL_PATH)/../../../../bionic/libc/private

LOCAL_LDLIBS += -lpthread -ldl

ifeq ($(WITH_MALLOC_LEAK_CHECK),true)
	LOCAL_CFLAGS += -DMALLOC_LEAK_CHECK
endif

LOCAL_MODULE:= libandroid_runtime

include $(BUILD_SHARED_LIBRARY)

include $(call all-makefiles-under,$(LOCAL_PATH))
