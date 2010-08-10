#
# Copyright (C) 2008 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
LOCAL_PATH := $(call my-dir)

# We have a special case here where we build the library's resources
# independently from its code, so we need to find where the resource
# class source got placed in the course of building the resources.
# Thus, the magic here.
# Also, this module cannot depend directly on the R.java file; if it
# did, the PRIVATE_* vars for R.java wouldn't be guaranteed to be correct.
# Instead, it depends on the R.stamp file, which lists the corresponding
# R.java file as a prerequisite.
# TODO: find a more appropriate way to do this.
framework_res_source_path := APPS/framework-res_intermediates/src

# the library
# ============================================================
include $(CLEAR_VARS)

# FRAMEWORKS_BASE_SUBDIRS comes from build/core/pathmap.mk
LOCAL_SRC_FILES := $(call find-other-java-files,$(FRAMEWORKS_BASE_SUBDIRS))

# EventLogTags files.
LOCAL_SRC_FILES += \
       core/java/android/content/EventLogTags.logtags \
       core/java/android/webkit/EventLogTags.logtags \
       telephony/java/com/android/internal/telephony/EventLogTags.logtags \

# The following filters out code we are temporarily not including at all.
# TODO: Move AWT and beans (and associated harmony code) back into libcore.
# TODO: Maybe remove javax.microedition entirely?
# TODO: Move SyncML (org.mobilecontrol.*) into its own library.
LOCAL_SRC_FILES := $(filter-out \
			org/mobilecontrol/% \
			,$(LOCAL_SRC_FILES))

# Include a different set of source files when building a debug build.
# TODO: Maybe build these into a separate .jar and put it on the classpath
#       in front of framework.jar.
# NOTE: Do not use this as an example; this is a very special situation.
#       Do not modify LOCAL_SRC_FILES based on any variable other
#       than TARGET_BUILD_TYPE, otherwise builds can become inconsistent.
ifeq ($(TARGET_BUILD_TYPE),debug)
  LOCAL_SRC_FILES += $(call find-other-java-files,core/config/debug)
else
  LOCAL_SRC_FILES += $(call find-other-java-files,core/config/ndebug)
endif

## READ ME: ########################################################
##
## When updating this list of aidl files, consider if that aidl is
## part of the SDK API.  If it is, also add it to the list below that
## is preprocessed and distributed with the SDK.  This list should
## not contain any aidl files for parcelables, but the one below should
## if you intend for 3rd parties to be able to send those objects
## across process boundaries.
##
## READ ME: ########################################################
LOCAL_SRC_FILES += \
	core/java/android/accessibilityservice/IAccessibilityServiceConnection.aidl \
	core/java/android/accessibilityservice/IEventListener.aidl \
	core/java/android/accounts/IAccountManager.aidl \
	core/java/android/accounts/IAccountManagerResponse.aidl \
	core/java/android/accounts/IAccountAuthenticator.aidl \
	core/java/android/accounts/IAccountAuthenticatorResponse.aidl \
	core/java/android/app/IActivityController.aidl \
	core/java/android/app/IActivityPendingResult.aidl \
	core/java/android/app/IActivityWatcher.aidl \
	core/java/android/app/IAlarmManager.aidl \
	core/java/android/app/IBackupAgent.aidl \
	core/java/android/app/IInstrumentationWatcher.aidl \
	core/java/android/app/INotificationManager.aidl \
	core/java/android/app/ISearchManager.aidl \
	core/java/android/app/ISearchManagerCallback.aidl \
	core/java/android/app/IServiceConnection.aidl \
	core/java/android/app/IThumbnailReceiver.aidl \
	core/java/android/app/ITransientNotification.aidl \
	core/java/android/app/IUiModeManager.aidl \
	core/java/android/app/IWallpaperManager.aidl \
	core/java/android/app/IWallpaperManagerCallback.aidl \
	core/java/android/app/admin/IDevicePolicyManager.aidl \
	core/java/android/app/backup/IBackupManager.aidl \
	core/java/android/app/backup/IRestoreObserver.aidl \
	core/java/android/app/backup/IRestoreSession.aidl \
	core/java/android/bluetooth/IBluetooth.aidl \
	core/java/android/bluetooth/IBluetoothA2dp.aidl \
	core/java/android/bluetooth/IBluetoothCallback.aidl \
	core/java/android/bluetooth/IBluetoothHeadset.aidl \
	core/java/android/bluetooth/IBluetoothPbap.aidl \
	core/java/android/content/IClipboard.aidl \
	core/java/android/content/IContentService.aidl \
	core/java/android/content/IIntentReceiver.aidl \
	core/java/android/content/IIntentSender.aidl \
	core/java/android/content/IOnPrimaryClipChangedListener.aidl \
	core/java/android/content/ISyncAdapter.aidl \
	core/java/android/content/ISyncContext.aidl \
	core/java/android/content/ISyncStatusObserver.aidl \
	core/java/android/content/pm/IPackageDataObserver.aidl \
	core/java/android/content/pm/IPackageDeleteObserver.aidl \
	core/java/android/content/pm/IPackageInstallObserver.aidl \
	core/java/android/content/pm/IPackageManager.aidl \
	core/java/android/content/pm/IPackageMoveObserver.aidl \
	core/java/android/content/pm/IPackageStatsObserver.aidl \
	core/java/android/database/IContentObserver.aidl \
	core/java/android/net/IConnectivityManager.aidl \
	core/java/android/net/INetworkManagementEventObserver.aidl \
	core/java/android/net/IThrottleManager.aidl \
	core/java/android/os/IHardwareService.aidl \
	core/java/android/os/IMessenger.aidl \
	core/java/android/os/storage/IMountService.aidl \
	core/java/android/os/storage/IMountServiceListener.aidl \
	core/java/android/os/storage/IMountShutdownObserver.aidl \
	core/java/android/os/INetworkManagementService.aidl \
	core/java/android/os/INetStatService.aidl \
	core/java/android/os/IPermissionController.aidl \
	core/java/android/os/IPowerManager.aidl \
    core/java/android/os/IRemoteCallback.aidl \
	core/java/android/os/IVibratorService.aidl \
	core/java/android/service/urlrenderer/IUrlRendererService.aidl \
	core/java/android/service/urlrenderer/IUrlRendererCallback.aidl \
    core/java/android/service/wallpaper/IWallpaperConnection.aidl \
    core/java/android/service/wallpaper/IWallpaperEngine.aidl \
    core/java/android/service/wallpaper/IWallpaperService.aidl \
	core/java/android/view/accessibility/IAccessibilityManager.aidl \
	core/java/android/view/accessibility/IAccessibilityManagerClient.aidl \
	core/java/android/view/IApplicationToken.aidl \
	core/java/android/view/IOnKeyguardExitResult.aidl \
	core/java/android/view/IRotationWatcher.aidl \
	core/java/android/view/IWindow.aidl \
	core/java/android/view/IWindowManager.aidl \
	core/java/android/view/IWindowSession.aidl \
	core/java/android/speech/IRecognitionListener.aidl \
	core/java/android/speech/IRecognitionService.aidl \
	core/java/android/speech/tts/ITts.aidl \
	core/java/android/speech/tts/ITtsCallback.aidl \
	core/java/com/android/internal/app/IBatteryStats.aidl \
	core/java/com/android/internal/app/IUsageStats.aidl \
	core/java/com/android/internal/app/IMediaContainerService.aidl \
	core/java/com/android/internal/appwidget/IAppWidgetService.aidl \
	core/java/com/android/internal/appwidget/IAppWidgetHost.aidl \
	core/java/com/android/internal/backup/IBackupTransport.aidl \
	core/java/com/android/internal/os/IDropBoxManagerService.aidl \
	core/java/com/android/internal/os/IResultReceiver.aidl \
	core/java/com/android/internal/statusbar/IStatusBar.aidl \
	core/java/com/android/internal/statusbar/IStatusBarService.aidl \
	core/java/com/android/internal/view/IInputContext.aidl \
	core/java/com/android/internal/view/IInputContextCallback.aidl \
	core/java/com/android/internal/view/IInputMethod.aidl \
	core/java/com/android/internal/view/IInputMethodCallback.aidl \
	core/java/com/android/internal/view/IInputMethodClient.aidl \
	core/java/com/android/internal/view/IInputMethodManager.aidl \
	core/java/com/android/internal/view/IInputMethodSession.aidl \
	core/java/com/android/internal/widget/IRemoteViewsFactory.aidl \
	location/java/android/location/ICountryDetector.aidl \
	location/java/android/location/ICountryListener.aidl \
	location/java/android/location/IGeocodeProvider.aidl \
	location/java/android/location/IGpsStatusListener.aidl \
	location/java/android/location/IGpsStatusProvider.aidl \
	location/java/android/location/ILocationListener.aidl \
	location/java/android/location/ILocationManager.aidl \
	location/java/android/location/ILocationProvider.aidl \
	location/java/android/location/INetInitiatedListener.aidl \
	media/java/android/media/IAudioService.aidl \
	media/java/android/media/IAudioFocusDispatcher.aidl \
	media/java/android/media/IMediaScannerListener.aidl \
	media/java/android/media/IMediaScannerService.aidl \
	telephony/java/com/android/internal/telephony/IPhoneStateListener.aidl \
	telephony/java/com/android/internal/telephony/IPhoneSubInfo.aidl \
	telephony/java/com/android/internal/telephony/ITelephony.aidl \
	telephony/java/com/android/internal/telephony/ITelephonyRegistry.aidl \
	telephony/java/com/android/internal/telephony/IIccPhoneBook.aidl \
	telephony/java/com/android/internal/telephony/ISms.aidl \
	wifi/java/android/net/wifi/IWifiManager.aidl \
	telephony/java/com/android/internal/telephony/IExtendedNetworkService.aidl \
	vpn/java/android/net/vpn/IVpnService.aidl \
	voip/java/android/net/sip/ISipSession.aidl \
	voip/java/android/net/sip/ISipSessionListener.aidl \
	voip/java/android/net/sip/ISipService.aidl
#


# FRAMEWORKS_BASE_JAVA_SRC_DIRS comes from build/core/pathmap.mk
LOCAL_AIDL_INCLUDES += $(FRAMEWORKS_BASE_JAVA_SRC_DIRS)

LOCAL_INTERMEDIATE_SOURCES := \
			$(framework_res_source_path)/android/R.java \
			$(framework_res_source_path)/android/Manifest.java \
			$(framework_res_source_path)/com/android/internal/R.java

LOCAL_NO_STANDARD_LIBRARIES := true
LOCAL_JAVA_LIBRARIES := bouncycastle core core-junit ext

LOCAL_MODULE := framework
LOCAL_MODULE_CLASS := JAVA_LIBRARIES

LOCAL_NO_EMMA_INSTRUMENT := true
LOCAL_NO_EMMA_COMPILE := true

# List of classes and interfaces which should be loaded by the Zygote.
LOCAL_JAVA_RESOURCE_FILES += $(LOCAL_PATH)/preloaded-classes

#LOCAL_JARJAR_RULES := $(LOCAL_PATH)/jarjar-rules.txt

LOCAL_DX_FLAGS := --core-library

include $(BUILD_JAVA_LIBRARY)

# Make sure that R.java and Manifest.java are built before we build
# the source for this library.
framework_res_R_stamp := \
	$(call intermediates-dir-for,APPS,framework-res,,COMMON)/src/R.stamp
$(full_classes_compiled_jar): $(framework_res_R_stamp)

# Make sure that framework-res is installed when framework is.
$(LOCAL_INSTALLED_MODULE): | $(dir $(LOCAL_INSTALLED_MODULE))framework-res.apk

framework_built := $(LOCAL_BUILT_MODULE)

# AIDL files to be preprocessed and included in the SDK,
# relative to the root of the build tree.
# ============================================================
aidl_files := \
	frameworks/base/core/java/android/accounts/IAccountManager.aidl \
	frameworks/base/core/java/android/accounts/IAccountManagerResponse.aidl \
	frameworks/base/core/java/android/accounts/IAccountAuthenticator.aidl \
	frameworks/base/core/java/android/accounts/IAccountAuthenticatorResponse.aidl \
	frameworks/base/core/java/android/app/Notification.aidl \
	frameworks/base/core/java/android/app/PendingIntent.aidl \
	frameworks/base/core/java/android/bluetooth/BluetoothDevice.aidl \
	frameworks/base/core/java/android/content/ComponentName.aidl \
	frameworks/base/core/java/android/content/Intent.aidl \
	frameworks/base/core/java/android/content/IntentSender.aidl \
	frameworks/base/core/java/android/content/PeriodicSync.aidl \
	frameworks/base/core/java/android/content/SyncStats.aidl \
	frameworks/base/core/java/android/content/res/Configuration.aidl \
	frameworks/base/core/java/android/appwidget/AppWidgetProviderInfo.aidl \
	frameworks/base/core/java/android/net/Uri.aidl \
	frameworks/base/core/java/android/os/Bundle.aidl \
	frameworks/base/core/java/android/os/DropBoxManager.aidl \
	frameworks/base/core/java/android/os/ParcelFileDescriptor.aidl \
	frameworks/base/core/java/android/os/ParcelUuid.aidl \
	frameworks/base/core/java/android/view/KeyEvent.aidl \
	frameworks/base/core/java/android/view/MotionEvent.aidl \
	frameworks/base/core/java/android/view/Surface.aidl \
	frameworks/base/core/java/android/view/WindowManager.aidl \
	frameworks/base/core/java/android/widget/RemoteViews.aidl \
	frameworks/base/core/java/com/android/internal/view/IInputContext.aidl \
	frameworks/base/core/java/com/android/internal/view/IInputMethod.aidl \
	frameworks/base/core/java/com/android/internal/view/IInputMethodCallback.aidl \
	frameworks/base/core/java/com/android/internal/view/IInputMethodClient.aidl \
	frameworks/base/core/java/com/android/internal/view/IInputMethodManager.aidl \
	frameworks/base/core/java/com/android/internal/view/IInputMethodSession.aidl \
	frameworks/base/graphics/java/android/graphics/Bitmap.aidl \
	frameworks/base/graphics/java/android/graphics/Rect.aidl \
	frameworks/base/graphics/java/android/graphics/Region.aidl \
	frameworks/base/location/java/android/location/Criteria.aidl \
	frameworks/base/location/java/android/location/Location.aidl \
	frameworks/base/telephony/java/android/telephony/ServiceState.aidl \
	frameworks/base/telephony/java/com/android/internal/telephony/IPhoneSubInfo.aidl \
	frameworks/base/telephony/java/com/android/internal/telephony/ITelephony.aidl \
	frameworks/base/vpn/java/android/net/vpn/IVpnService.aidl \

gen := $(TARGET_OUT_COMMON_INTERMEDIATES)/framework.aidl
$(gen): PRIVATE_SRC_FILES := $(aidl_files)
ALL_SDK_FILES += $(gen)
$(gen): $(aidl_files) | $(AIDL)
		@echo Aidl Preprocess: $@
		$(hide) $(AIDL) --preprocess $@ $(PRIVATE_SRC_FILES)

# the documentation
# ============================================================

# TODO: deal with com/google/android/googleapps
packages_to_document := \
	android \
	javax/microedition/khronos

# Search through the base framework dirs for these packages.
# The result will be relative to frameworks/base.
fwbase_dirs_to_document := \
	test-runner/src \
	$(patsubst $(LOCAL_PATH)/%,%, \
	  $(wildcard \
	    $(foreach dir, $(FRAMEWORKS_BASE_JAVA_SRC_DIRS), \
	      $(addprefix $(dir)/, $(packages_to_document)) \
	     ) \
	   ) \
	 )

# Pass a special "fake-out" version of some classes to the doc/API tools.
# ConfigBuildFlags uses this trick to prevent certain fields from appearing
# as "final" in the official SDK APIs.
fwbase_dirs_to_document += core/config/sdk

# These are relative to libcore
# Intentionally not included from libcore:
#     icu openssl suncompat support
libcore_to_document := \
	dalvik/src/main/java/dalvik \
	json/src/main/java \
	junit/src/main/java \
	luni/src/main/java/java \
	luni/src/main/java/javax \
	luni/src/main/java/org/xml/sax \
	luni/src/main/java/org/w3c \
	xml/src/main/java/org/xmlpull/v1 \

non_base_dirs := \
	../../external/apache-http/src/org/apache/http

# These are relative to frameworks/base
dirs_to_document := \
	$(fwbase_dirs_to_document) \
	$(non_base_dirs) \
	$(addprefix ../../libcore/, $(libcore_to_document))

html_dirs := \
	$(FRAMEWORKS_BASE_SUBDIRS) \
	$(non_base_dirs)

# These are relative to frameworks/base
framework_docs_LOCAL_SRC_FILES := \
	$(call find-other-java-files, $(dirs_to_document)) \
	$(call find-other-html-files, $(html_dirs))

# This is used by ide.mk as the list of source files that are
# always included.
INTERNAL_SDK_SOURCE_DIRS := $(addprefix $(LOCAL_PATH)/,$(dirs_to_document))

framework_docs_LOCAL_DROIDDOC_SOURCE_PATH := \
	$(FRAMEWORKS_BASE_JAVA_SRC_DIRS)

framework_docs_LOCAL_INTERMEDIATE_SOURCES := \
			$(framework_res_source_path)/android/R.java \
			$(framework_res_source_path)/android/Manifest.java \
			$(framework_res_source_path)/com/android/internal/R.java

framework_docs_LOCAL_JAVA_LIBRARIES := \
			bouncycastle \
			core \
			ext \
			framework \

framework_docs_LOCAL_MODULE_CLASS := JAVA_LIBRARIES
framework_docs_LOCAL_DROIDDOC_HTML_DIR := docs/html
# The since flag (-since N.xml API_LEVEL) is used to add API Level information
# to the reference documentation. Must be in order of oldest to newest.
framework_docs_LOCAL_DROIDDOC_OPTIONS := \
    -since ./frameworks/base/api/1.xml 1 \
    -since ./frameworks/base/api/2.xml 2 \
    -since ./frameworks/base/api/3.xml 3 \
    -since ./frameworks/base/api/4.xml 4 \
    -since ./frameworks/base/api/5.xml 5 \
    -since ./frameworks/base/api/6.xml 6 \
    -since ./frameworks/base/api/7.xml 7 \
    -since ./frameworks/base/api/8.xml 8 \
    -since ./frameworks/base/api/current.xml HC \
		-error 101 -error 102 -warning 103 -error 104 -error 106 -error 108 \
		-overview $(LOCAL_PATH)/core/java/overview.html

framework_docs_LOCAL_ADDITIONAL_JAVA_DIR:=$(call intermediates-dir-for,JAVA_LIBRARIES,framework)

sample_dir := development/samples

# the list here should match the list of samples included in the sdk samples package
# (see development/build/sdk.atree)
web_docs_sample_code_flags := \
		-hdf android.hasSamples 1 \
		-samplecode $(sample_dir)/ApiDemos \
		            resources/samples/ApiDemos "API Demos" \
		-samplecode $(sample_dir)/BackupRestore \
		            resources/samples/BackupRestore "Backup and Restore" \
		-samplecode $(sample_dir)/BluetoothChat \
		            resources/samples/BluetoothChat "Bluetooth Chat" \
		-samplecode $(sample_dir)/BusinessCard \
		            resources/samples/BusinessCard "Business Card" \
		-samplecode $(sample_dir)/ContactManager \
		            resources/samples/ContactManager "Contact Manager" \
                -samplecode $(sample_dir)/CubeLiveWallpaper \
                            resources/samples/CubeLiveWallpaper "Live Wallpaper" \
		-samplecode $(sample_dir)/Home \
		            resources/samples/Home "Home" \
		-samplecode $(sample_dir)/HeavyWeight \
		            resources/samples/HeavyWeight "Heavy Weight App" \
		-samplecode $(sample_dir)/JetBoy \
		            resources/samples/JetBoy "JetBoy" \
		-samplecode $(sample_dir)/LunarLander \
		            resources/samples/LunarLander "Lunar Lander" \
		-samplecode $(sample_dir)/MultiResolution \
		            resources/samples/MultiResolution "Multiple Resolutions" \
		-samplecode $(sample_dir)/NotePad \
		            resources/samples/NotePad "Note Pad" \
		-samplecode $(sample_dir)/SampleSyncAdapter \
		            resources/samples/SampleSyncAdapter "Sample Sync Adapter" \
		-samplecode $(sample_dir)/SearchableDictionary \
		            resources/samples/SearchableDictionary "Searchable Dictionary v2" \
		-samplecode $(sample_dir)/Snake \
		            resources/samples/Snake "Snake" \
		-samplecode $(sample_dir)/SoftKeyboard \
		            resources/samples/SoftKeyboard "Soft Keyboard" \
		-samplecode $(sample_dir)/Spinner  \
		            resources/samples/Spinner "Spinner" \
		-samplecode $(sample_dir)/SpinnerTest \
		            resources/samples/SpinnerTest "SpinnerTest" \
		-samplecode $(sample_dir)/TicTacToeLib  \
		            resources/samples/TicTacToeLib "TicTacToeLib" \
		-samplecode $(sample_dir)/TicTacToeMain \
		            resources/samples/TicTacToeMain "TicTacToeMain" \
		-samplecode $(sample_dir)/Wiktionary \
		            resources/samples/Wiktionary "Wiktionary" \
		-samplecode $(sample_dir)/WiktionarySimple \
		            resources/samples/WiktionarySimple "Wiktionary (Simplified)" \
		-samplecode $(sample_dir)/VoiceRecognitionService \
		            resources/samples/VoiceRecognitionService "Voice Recognition Service" \
		-samplecode $(sample_dir)/XmlAdapters \
		            resources/samples/XmlAdapters "XML Adapters"

## SDK version identifiers used in the published docs
  # major[.minor] version for current SDK. (full releases only)
framework_docs_SDK_VERSION:=2.2
  # release version (ie "Release x")  (full releases only)
framework_docs_SDK_REL_ID:=1
  # name of current SDK directory (full releases only)
framework_docs_SDK_CURRENT_DIR:=$(framework_docs_SDK_VERSION)_r$(framework_docs_SDK_REL_ID)
  # flag to build offline docs for a preview release
framework_docs_SDK_PREVIEW:=0

## Latest ADT version identifiers, for reference from published docs
framework_docs_ADT_VERSION:=0.9.7
framework_docs_ADT_DOWNLOAD:=ADT-0.9.7.zip
framework_docs_ADT_BYTES:=8033750
framework_docs_ADT_CHECKSUM:=de2431c8d4786d127ae5bfc95b4605df

framework_docs_LOCAL_DROIDDOC_OPTIONS += \
		-hdf sdk.version $(framework_docs_SDK_VERSION) \
		-hdf sdk.rel.id $(framework_docs_SDK_REL_ID) \
		-hdf sdk.current $(framework_docs_SDK_CURRENT_DIR) \
		-hdf adt.zip.version $(framework_docs_ADT_VERSION) \
		-hdf adt.zip.download $(framework_docs_ADT_DOWNLOAD) \
		-hdf adt.zip.bytes $(framework_docs_ADT_BYTES) \
		-hdf adt.zip.checksum $(framework_docs_ADT_CHECKSUM) 

# ====  the api stubs and current.xml ===========================
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=$(framework_docs_LOCAL_SRC_FILES)
LOCAL_INTERMEDIATE_SOURCES:=$(framework_docs_LOCAL_INTERMEDIATE_SOURCES)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_DROIDDOC_HTML_DIR:=$(framework_docs_LOCAL_DROIDDOC_HTML_DIR)
LOCAL_ADDITIONAL_JAVA_DIR:=$(framework_docs_LOCAL_ADDITIONAL_JAVA_DIR)

LOCAL_MODULE := api-stubs

LOCAL_DROIDDOC_OPTIONS:=\
		$(framework_docs_LOCAL_DROIDDOC_OPTIONS) \
		-stubs $(TARGET_OUT_COMMON_INTERMEDIATES)/JAVA_LIBRARIES/android_stubs_current_intermediates/src \
		-apixml $(INTERNAL_PLATFORM_API_FILE) \
		-nodocs

LOCAL_DROIDDOC_CUSTOM_TEMPLATE_DIR:=build/tools/droiddoc/templates-sdk
LOCAL_DROIDDOC_CUSTOM_ASSET_DIR:=assets-sdk

include $(BUILD_DROIDDOC)

# $(gen), i.e. framework.aidl, is also needed while building against the current stub.
$(full_target): $(framework_built) $(gen)
$(INTERNAL_PLATFORM_API_FILE): $(full_target)
$(call dist-for-goals,sdk,$(INTERNAL_PLATFORM_API_FILE))

# ====  static html in the sdk ==================================
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=$(framework_docs_LOCAL_SRC_FILES)
LOCAL_INTERMEDIATE_SOURCES:=$(framework_docs_LOCAL_INTERMEDIATE_SOURCES)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_DROIDDOC_HTML_DIR:=$(framework_docs_LOCAL_DROIDDOC_HTML_DIR)
LOCAL_ADDITIONAL_JAVA_DIR:=$(framework_docs_LOCAL_ADDITIONAL_JAVA_DIR)

LOCAL_MODULE := offline-sdk

LOCAL_DROIDDOC_OPTIONS:=\
		$(framework_docs_LOCAL_DROIDDOC_OPTIONS) \
                $(web_docs_sample_code_flags) \
                -offlinemode \
		-title "Android SDK" \
		-proofread $(OUT_DOCS)/$(LOCAL_MODULE)-proofread.txt \
		-todo $(OUT_DOCS)/$(LOCAL_MODULE)-docs-todo.html \
		-sdkvalues $(OUT_DOCS) \
		-hdf android.whichdoc offline 

ifeq ($(framework_docs_SDK_PREVIEW),true)
  LOCAL_DROIDDOC_OPTIONS += -hdf sdk.current preview 
endif

LOCAL_DROIDDOC_CUSTOM_TEMPLATE_DIR:=build/tools/droiddoc/templates-sdk
LOCAL_DROIDDOC_CUSTOM_ASSET_DIR:=assets-sdk

include $(BUILD_DROIDDOC)

static_doc_index_redirect := $(out_dir)/index.html
$(static_doc_index_redirect): \
	$(LOCAL_PATH)/docs/docs-documentation-redirect.html | $(ACP)
	$(hide) mkdir -p $(dir $@)
	$(hide) $(ACP) $< $@

$(full_target): $(static_doc_index_redirect)
$(full_target): $(framework_built)


# ==== docs for the web (on the google app engine server) =======================
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=$(framework_docs_LOCAL_SRC_FILES)
LOCAL_INTERMEDIATE_SOURCES:=$(framework_docs_LOCAL_INTERMEDIATE_SOURCES)
LOCAL_STATIC_JAVA_LIBRARIES:=$(framework_docs_LOCAL_STATIC_JAVA_LIBRARIES)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_DROIDDOC_HTML_DIR:=$(framework_docs_LOCAL_DROIDDOC_HTML_DIR)
LOCAL_ADDITIONAL_JAVA_DIR:=$(framework_docs_LOCAL_ADDITIONAL_JAVA_DIR)

LOCAL_MODULE := online-sdk

LOCAL_DROIDDOC_OPTIONS:= \
		$(framework_docs_LOCAL_DROIDDOC_OPTIONS) \
		$(web_docs_sample_code_flags) \
		-toroot / \
		-hdf android.whichdoc online \
		-hdf template.showLanguageMenu true

LOCAL_DROIDDOC_CUSTOM_TEMPLATE_DIR:=build/tools/droiddoc/templates-sdk
LOCAL_DROIDDOC_CUSTOM_ASSET_DIR:=assets-sdk

include $(BUILD_DROIDDOC)

# explicitly specify that online-sdk depends on framework-res.
$(full_target): framework-res-package-target

# ==== docs that have all of the stuff that's @hidden =======================
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=$(framework_docs_LOCAL_SRC_FILES)
LOCAL_INTERMEDIATE_SOURCES:=$(framework_docs_LOCAL_INTERMEDIATE_SOURCES)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_JAVA_LIBRARIES) framework
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_DROIDDOC_HTML_DIR:=$(framework_docs_LOCAL_DROIDDOC_HTML_DIR)
LOCAL_ADDITIONAL_JAVA_DIR:=$(call intermediates-dir-for,JAVA_LIBRARIES,framework)

LOCAL_MODULE := hidden
LOCAL_DROIDDOC_OPTIONS:=\
		$(framework_docs_LOCAL_DROIDDOC_OPTIONS) \
        -title "Android SDK - Including hidden APIs."
#        -hidden

LOCAL_DROIDDOC_CUSTOM_TEMPLATE_DIR:=build/tools/droiddoc/templates-sdk
LOCAL_DROIDDOC_CUSTOM_ASSET_DIR:=assets-sdk

include $(BUILD_DROIDDOC)

# Build ext.jar
# ============================================================

ext_dirs := \
	../../external/nist-sip/java \
	../../external/apache-http/src \
	../../external/tagsoup/src \
	../../external/libphonenumber/java/src

ext_src_files := $(call all-java-files-under,$(ext_dirs))

ext_res_dirs := \
	../../external/libphonenumber/java/src

# ====  the library  =========================================
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(ext_src_files)

LOCAL_NO_STANDARD_LIBRARIES := true
LOCAL_JAVA_LIBRARIES := core
LOCAL_JAVA_RESOURCE_DIRS := $(ext_res_dirs)
LOCAL_MODULE := ext

LOCAL_NO_EMMA_INSTRUMENT := true
LOCAL_NO_EMMA_COMPILE := true

include $(BUILD_JAVA_LIBRARY)


# Include subdirectory makefiles
# ============================================================

# If we're building with ONE_SHOT_MAKEFILE (mm, mmm), then what the framework
# team really wants is to build the stuff defined by this makefile.
ifeq (,$(ONE_SHOT_MAKEFILE))
include $(call first-makefiles-under,$(LOCAL_PATH))
endif
