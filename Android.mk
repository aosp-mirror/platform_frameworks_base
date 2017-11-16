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

# Load framework-specific path mappings used later in the build.
include $(LOCAL_PATH)/pathmap.mk

# Build the master framework library.
# The framework contains too many method references (>64K) for poor old DEX.
# So we first build the framework as a monolithic static library then split it
# up into smaller pieces.
# ============================================================

# embedded builds use nothing in frameworks/base
ifneq ($(ANDROID_BUILD_EMBEDDED),true)

# Copy AIDL files to be preprocessed and included in the SDK,
# specified relative to the root of the build tree.
# ============================================================
include $(CLEAR_VARS)

aidl_files := \
        frameworks/base/telephony/java/android/telephony/mbms/DownloadRequest.aidl \
        frameworks/base/telephony/java/android/telephony/mbms/FileInfo.aidl \
        frameworks/base/telephony/java/android/telephony/mbms/FileServiceInfo.aidl \
        frameworks/base/telephony/java/android/telephony/mbms/ServiceInfo.aidl \
        frameworks/base/telephony/java/android/telephony/mbms/StreamingServiceInfo.aidl \
	frameworks/base/telephony/java/android/telephony/ServiceState.aidl \
	frameworks/base/telephony/java/android/telephony/SubscriptionInfo.aidl \
	frameworks/base/telephony/java/android/telephony/CellInfo.aidl \
	frameworks/base/telephony/java/android/telephony/SignalStrength.aidl \
	frameworks/base/telephony/java/android/telephony/IccOpenLogicalChannelResponse.aidl \
	frameworks/base/telephony/java/android/telephony/NeighboringCellInfo.aidl \
	frameworks/base/telephony/java/android/telephony/ModemActivityInfo.aidl \
	frameworks/base/telephony/java/android/telephony/UiccAccessRule.aidl \
	frameworks/base/telephony/java/android/telephony/data/DataProfile.aidl \
	frameworks/base/telephony/java/android/telephony/euicc/DownloadableSubscription.aidl \
	frameworks/base/telephony/java/android/telephony/euicc/EuiccInfo.aidl \
	frameworks/base/location/java/android/location/Location.aidl \
	frameworks/base/location/java/android/location/Address.aidl \
	frameworks/base/location/java/android/location/Criteria.aidl \
	frameworks/base/media/java/android/media/MediaMetadata.aidl \
	frameworks/base/media/java/android/media/MediaDescription.aidl \
	frameworks/base/media/java/android/media/Rating.aidl \
	frameworks/base/media/java/android/media/AudioAttributes.aidl \
	frameworks/base/media/java/android/media/AudioFocusInfo.aidl \
	frameworks/base/media/java/android/media/session/PlaybackState.aidl \
	frameworks/base/media/java/android/media/session/MediaSession.aidl \
	frameworks/base/media/java/android/media/tv/TvInputInfo.aidl \
	frameworks/base/media/java/android/media/tv/TvTrackInfo.aidl \
	frameworks/base/media/java/android/media/browse/MediaBrowser.aidl \
	frameworks/base/wifi/java/android/net/wifi/ScanSettings.aidl \
	frameworks/base/wifi/java/android/net/wifi/aware/ConfigRequest.aidl \
	frameworks/base/wifi/java/android/net/wifi/aware/PublishConfig.aidl \
	frameworks/base/wifi/java/android/net/wifi/aware/SubscribeConfig.aidl \
	frameworks/base/wifi/java/android/net/wifi/p2p/WifiP2pInfo.aidl \
	frameworks/base/wifi/java/android/net/wifi/p2p/WifiP2pDeviceList.aidl \
	frameworks/base/wifi/java/android/net/wifi/p2p/WifiP2pConfig.aidl \
	frameworks/base/wifi/java/android/net/wifi/p2p/WifiP2pDevice.aidl \
	frameworks/base/wifi/java/android/net/wifi/p2p/WifiP2pGroup.aidl \
	frameworks/base/wifi/java/android/net/wifi/p2p/nsd/WifiP2pServiceRequest.aidl \
	frameworks/base/wifi/java/android/net/wifi/p2p/nsd/WifiP2pServiceInfo.aidl \
	frameworks/base/wifi/java/android/net/wifi/WpsInfo.aidl \
	frameworks/base/wifi/java/android/net/wifi/ScanResult.aidl \
	frameworks/base/wifi/java/android/net/wifi/PasspointManagementObjectDefinition.aidl \
	frameworks/base/wifi/java/android/net/wifi/WifiEnterpriseConfig.aidl \
	frameworks/base/wifi/java/android/net/wifi/WifiConfiguration.aidl \
	frameworks/base/wifi/java/android/net/wifi/WifiInfo.aidl \
	frameworks/base/graphics/java/android/graphics/Region.aidl \
	frameworks/base/graphics/java/android/graphics/Bitmap.aidl \
	frameworks/base/graphics/java/android/graphics/Point.aidl \
	frameworks/base/graphics/java/android/graphics/PointF.aidl \
	frameworks/base/graphics/java/android/graphics/RectF.aidl \
	frameworks/base/graphics/java/android/graphics/Rect.aidl \
	frameworks/base/graphics/java/android/graphics/drawable/Icon.aidl \
	frameworks/base/core/java/android/accounts/AuthenticatorDescription.aidl \
	frameworks/base/core/java/android/accounts/Account.aidl \
	frameworks/base/core/java/android/app/admin/ConnectEvent.aidl \
	frameworks/base/core/java/android/app/admin/DnsEvent.aidl \
	frameworks/base/core/java/android/app/admin/NetworkEvent.aidl \
	frameworks/base/core/java/android/app/admin/SystemUpdatePolicy.aidl \
	frameworks/base/core/java/android/app/admin/PasswordMetrics.aidl \
	frameworks/base/core/java/android/print/PrintDocumentInfo.aidl \
	frameworks/base/core/java/android/print/PageRange.aidl \
	frameworks/base/core/java/android/print/PrintAttributes.aidl \
	frameworks/base/core/java/android/print/PrinterCapabilitiesInfo.aidl \
	frameworks/base/core/java/android/print/PrinterId.aidl \
	frameworks/base/core/java/android/print/PrintJobInfo.aidl \
	frameworks/base/core/java/android/print/PrinterInfo.aidl \
	frameworks/base/core/java/android/print/PrintJobId.aidl \
	frameworks/base/core/java/android/printservice/recommendation/RecommendationInfo.aidl \
	frameworks/base/core/java/android/hardware/radio/RadioManager.aidl \
	frameworks/base/core/java/android/hardware/usb/UsbDevice.aidl \
	frameworks/base/core/java/android/hardware/usb/UsbInterface.aidl \
	frameworks/base/core/java/android/hardware/usb/UsbEndpoint.aidl \
	frameworks/base/core/java/android/hardware/usb/UsbAccessory.aidl \
	frameworks/base/core/java/android/os/Messenger.aidl \
	frameworks/base/core/java/android/os/PatternMatcher.aidl \
	frameworks/base/core/java/android/os/Message.aidl \
	frameworks/base/core/java/android/os/UserHandle.aidl \
	frameworks/base/core/java/android/os/ParcelUuid.aidl \
	frameworks/base/core/java/android/os/ParcelFileDescriptor.aidl \
	frameworks/base/core/java/android/os/ResultReceiver.aidl \
	frameworks/base/core/java/android/os/WorkSource.aidl \
	frameworks/base/core/java/android/os/DropBoxManager.aidl \
	frameworks/base/core/java/android/os/Bundle.aidl \
	frameworks/base/core/java/android/os/Debug.aidl \
	frameworks/base/core/java/android/os/StrictMode.aidl \
	frameworks/base/core/java/android/accessibilityservice/AccessibilityServiceInfo.aidl \
	frameworks/base/core/java/android/net/Network.aidl \
	frameworks/base/core/java/android/net/RouteInfo.aidl \
	frameworks/base/core/java/android/net/NetworkInfo.aidl \
	frameworks/base/core/java/android/net/IpPrefix.aidl \
	frameworks/base/core/java/android/net/NetworkCapabilities.aidl \
	frameworks/base/core/java/android/net/DhcpInfo.aidl \
	frameworks/base/core/java/android/net/ProxyInfo.aidl \
	frameworks/base/core/java/android/net/LinkProperties.aidl \
	frameworks/base/core/java/android/net/Uri.aidl \
	frameworks/base/core/java/android/net/NetworkRequest.aidl \
	frameworks/base/core/java/android/net/LinkAddress.aidl \
	frameworks/base/core/java/android/util/MemoryIntArray.aidl \
	frameworks/base/core/java/android/view/Display.aidl \
	frameworks/base/core/java/android/view/InputDevice.aidl \
	frameworks/base/core/java/android/view/InputEvent.aidl \
	frameworks/native/aidl/gui/android/view/Surface.aidl \
	frameworks/base/core/java/android/view/WindowContentFrameStats.aidl \
	frameworks/base/core/java/android/view/inputmethod/InputMethodSubtype.aidl \
	frameworks/base/core/java/android/view/inputmethod/CursorAnchorInfo.aidl \
	frameworks/base/core/java/android/view/inputmethod/CompletionInfo.aidl \
	frameworks/base/core/java/android/view/inputmethod/ExtractedText.aidl \
	frameworks/base/core/java/android/view/inputmethod/EditorInfo.aidl \
	frameworks/base/core/java/android/view/inputmethod/InputMethodInfo.aidl \
	frameworks/base/core/java/android/view/inputmethod/CorrectionInfo.aidl \
	frameworks/base/core/java/android/view/inputmethod/InputBinding.aidl \
	frameworks/base/core/java/android/view/inputmethod/ExtractedTextRequest.aidl \
	frameworks/base/core/java/android/view/DragEvent.aidl \
	frameworks/base/core/java/android/view/KeyEvent.aidl \
	frameworks/base/core/java/android/view/WindowManager.aidl \
	frameworks/base/core/java/android/view/WindowAnimationFrameStats.aidl \
	frameworks/base/core/java/android/view/MotionEvent.aidl \
	frameworks/base/core/java/android/view/accessibility/AccessibilityNodeInfo.aidl \
	frameworks/base/core/java/android/view/accessibility/AccessibilityRecord.aidl \
	frameworks/base/core/java/android/view/accessibility/AccessibilityWindowInfo.aidl \
	frameworks/base/core/java/android/view/accessibility/AccessibilityEvent.aidl \
	frameworks/base/core/java/android/view/textservice/SpellCheckerSubtype.aidl \
	frameworks/base/core/java/android/view/textservice/TextInfo.aidl \
	frameworks/base/core/java/android/view/textservice/SpellCheckerInfo.aidl \
	frameworks/base/core/java/android/view/textservice/SentenceSuggestionsInfo.aidl \
	frameworks/base/core/java/android/view/textservice/SuggestionsInfo.aidl \
	frameworks/base/core/java/android/service/carrier/CarrierIdentifier.aidl \
	frameworks/base/core/java/android/service/carrier/MessagePdu.aidl \
	frameworks/base/core/java/android/service/euicc/GetDefaultDownloadableSubscriptionListResult.aidl \
	frameworks/base/core/java/android/service/euicc/GetDownloadableSubscriptionMetadataResult.aidl \
	frameworks/base/core/java/android/service/euicc/GetEuiccProfileInfoListResult.aidl \
	frameworks/base/core/java/android/service/notification/Adjustment.aidl \
	frameworks/base/core/java/android/service/notification/Condition.aidl \
	frameworks/base/core/java/android/service/notification/SnoozeCriterion.aidl \
	frameworks/base/core/java/android/service/notification/StatusBarNotification.aidl \
	frameworks/base/core/java/android/service/chooser/ChooserTarget.aidl \
	frameworks/base/core/java/android/service/resolver/ResolverTarget.aidl \
	frameworks/base/core/java/android/speech/tts/Voice.aidl \
	frameworks/base/core/java/android/app/usage/CacheQuotaHint.aidl \
	frameworks/base/core/java/android/app/usage/ExternalStorageStats.aidl \
	frameworks/base/core/java/android/app/usage/StorageStats.aidl \
	frameworks/base/core/java/android/app/usage/UsageEvents.aidl \
	frameworks/base/core/java/android/app/Notification.aidl \
	frameworks/base/core/java/android/app/NotificationManager.aidl \
	frameworks/base/core/java/android/app/WallpaperInfo.aidl \
	frameworks/base/core/java/android/app/AppOpsManager.aidl \
	frameworks/base/core/java/android/app/ActivityManager.aidl \
	frameworks/base/core/java/android/app/PendingIntent.aidl \
	frameworks/base/core/java/android/app/AlarmManager.aidl \
	frameworks/base/core/java/android/app/SearchableInfo.aidl \
	frameworks/base/core/java/android/app/VoiceInteractor.aidl \
	frameworks/base/core/java/android/app/assist/AssistContent.aidl \
	frameworks/base/core/java/android/app/assist/AssistStructure.aidl \
	frameworks/base/core/java/android/app/job/JobParameters.aidl \
	frameworks/base/core/java/android/app/job/JobInfo.aidl \
	frameworks/base/core/java/android/appwidget/AppWidgetProviderInfo.aidl \
	frameworks/base/core/java/android/content/ClipDescription.aidl \
	frameworks/base/core/java/android/content/IntentFilter.aidl \
	frameworks/base/core/java/android/content/Intent.aidl \
	frameworks/base/core/java/android/content/res/Configuration.aidl \
	frameworks/base/core/java/android/content/res/ObbInfo.aidl \
	frameworks/base/core/java/android/content/RestrictionEntry.aidl \
	frameworks/base/core/java/android/content/ClipData.aidl \
	frameworks/base/core/java/android/content/SyncAdapterType.aidl \
	frameworks/base/core/java/android/content/SyncRequest.aidl \
	frameworks/base/core/java/android/content/PeriodicSync.aidl \
	frameworks/base/core/java/android/content/SyncResult.aidl \
	frameworks/base/core/java/android/content/pm/FeatureInfo.aidl \
	frameworks/base/core/java/android/content/pm/InstrumentationInfo.aidl \
	frameworks/base/core/java/android/content/pm/PackageInstaller.aidl \
	frameworks/base/core/java/android/content/pm/ServiceInfo.aidl \
	frameworks/base/core/java/android/content/pm/Signature.aidl \
	frameworks/base/core/java/android/content/pm/ApplicationInfo.aidl \
	frameworks/base/core/java/android/content/pm/PermissionInfo.aidl \
	frameworks/base/core/java/android/content/pm/ActivityInfo.aidl \
	frameworks/base/core/java/android/content/pm/ConfigurationInfo.aidl \
	frameworks/base/core/java/android/content/pm/PackageInfo.aidl \
	frameworks/base/core/java/android/content/pm/ResolveInfo.aidl \
	frameworks/base/core/java/android/content/pm/ProviderInfo.aidl \
	frameworks/base/core/java/android/content/pm/PackageStats.aidl \
	frameworks/base/core/java/android/content/pm/PermissionGroupInfo.aidl \
	frameworks/base/core/java/android/content/pm/ShortcutInfo.aidl \
	frameworks/base/core/java/android/content/pm/LabeledIntent.aidl \
	frameworks/base/core/java/android/content/ComponentName.aidl \
	frameworks/base/core/java/android/content/SyncStats.aidl \
	frameworks/base/core/java/android/content/ContentValues.aidl \
	frameworks/base/core/java/android/content/SyncInfo.aidl \
	frameworks/base/core/java/android/content/IntentSender.aidl \
	frameworks/base/core/java/android/widget/RemoteViews.aidl \
	frameworks/base/core/java/android/text/style/SuggestionSpan.aidl \
	frameworks/base/core/java/android/nfc/Tag.aidl \
	frameworks/base/core/java/android/nfc/NdefRecord.aidl \
	frameworks/base/core/java/android/nfc/NdefMessage.aidl \
	frameworks/base/core/java/android/database/CursorWindow.aidl \
	frameworks/base/core/java/android/service/quicksettings/Tile.aidl \
	frameworks/native/aidl/binder/android/os/PersistableBundle.aidl \
	system/bt/binder/android/bluetooth/BluetoothHealthAppConfiguration.aidl \
	system/bt/binder/android/bluetooth/le/AdvertiseSettings.aidl \
	system/bt/binder/android/bluetooth/le/ScanSettings.aidl \
	system/bt/binder/android/bluetooth/le/AdvertiseData.aidl \
	system/bt/binder/android/bluetooth/le/ScanFilter.aidl \
	system/bt/binder/android/bluetooth/le/ScanResult.aidl \
	system/bt/binder/android/bluetooth/BluetoothDevice.aidl \
	system/netd/server/binder/android/net/UidRange.aidl \
	frameworks/base/telephony/java/android/telephony/PcoData.aidl \

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
  javax/microedition/khronos \
  org/apache/http/conn \
  org/apache/http/params \

# include definition of libcore_to_document
include libcore/Docs.mk

non_base_dirs := \
  ../opt/telephony/src/java/android/telephony \
  ../opt/telephony/src/java/android/telephony/gsm \
  ../opt/net/voip/src/java/android/net/rtp \
  ../opt/net/voip/src/java/android/net/sip \

# Find all files in specific directories (relative to frameworks/base)
# to document and check apis
files_to_check_apis := \
  $(call find-other-java-files, \
    legacy-test/src \
    test-runner/src \
    $(non_base_dirs) \
  )

# Find all files in specific packages that were used to compile
# framework.jar to document and check apis
files_to_check_apis += \
  $(addprefix ../../,\
    $(filter \
      $(foreach dir,$(FRAMEWORKS_BASE_JAVA_SRC_DIRS),\
        $(foreach package,$(packages_to_document),\
          $(dir)/$(package)/%.java)),\
      $(SOONG_FRAMEWORK_SRCS)))

# Find all generated files that were used to compile framework.jar
files_to_check_apis += \
  $(addprefix ../../,\
    $(filter $(OUT_DIR)/%,\
      $(SOONG_FRAMEWORK_SRCS)))

# These are relative to frameworks/base
# FRAMEWORKS_BASE_SUBDIRS comes from build/core/pathmap.mk
files_to_document := \
  $(files_to_check_apis) \
  $(call find-other-java-files,$(addprefix ../../, $(FRAMEWORKS_DATA_BINDING_JAVA_SRC_DIRS)))

# These are relative to frameworks/base
html_dirs := \
	$(FRAMEWORKS_BASE_SUBDIRS) \
	$(non_base_dirs) \

# Common sources for doc check and api check
common_src_files := \
	$(call find-other-html-files, $(html_dirs)) \
	$(addprefix ../../, $(libcore_to_document)) \

# These are relative to frameworks/base
framework_docs_LOCAL_SRC_FILES := \
  $(files_to_document) \
  $(common_src_files) \

# These are relative to frameworks/base
framework_docs_LOCAL_API_CHECK_SRC_FILES := \
  $(files_to_check_apis) \
  $(common_src_files) \

# This is used by ide.mk as the list of source files that are
# always included.
INTERNAL_SDK_SOURCE_DIRS := $(addprefix $(LOCAL_PATH)/,$(dirs_to_document))

framework_docs_LOCAL_DROIDDOC_SOURCE_PATH := \
	$(FRAMEWORKS_BASE_JAVA_SRC_DIRS)

framework_docs_LOCAL_SRCJARS := $(SOONG_FRAMEWORK_SRCJARS)

framework_docs_LOCAL_INTERMEDIATE_SOURCES := \
	$(patsubst $(TARGET_OUT_COMMON_INTERMEDIATES)/%,%,$(libcore_to_document_generated))

framework_docs_LOCAL_API_CHECK_JAVA_LIBRARIES := \
	core-oj \
	core-libart \
	conscrypt \
	bouncycastle \
	okhttp \
	ext \
	icu4j \
	framework \
	voip-common

# Platform docs can refer to Support Library APIs, but we don't actually build
# them as part of the docs target, so we need to include them on the classpath.
framework_docs_LOCAL_JAVA_LIBRARIES := \
	$(framework_docs_LOCAL_API_CHECK_JAVA_LIBRARIES) \
	$(FRAMEWORKS_SUPPORT_JAVA_LIBRARIES)

framework_docs_LOCAL_MODULE_CLASS := JAVA_LIBRARIES
framework_docs_LOCAL_DROIDDOC_HTML_DIR := docs/html
# The since flag (-since N.xml API_LEVEL) is used to add API Level information
# to the reference documentation. Must be in order of oldest to newest.
#
# Conscrypt (com.android.org.conscrypt) is an implementation detail and should
# not be referenced in the documentation.
framework_docs_LOCAL_DROIDDOC_OPTIONS := \
    -android \
    -knowntags ./frameworks/base/docs/knowntags.txt \
    -knowntags ./libcore/known_oj_tags.txt \
    -manifest ./frameworks/base/core/res/AndroidManifest.xml \
    -hidePackage com.android.org.conscrypt \
    -since $(SRC_API_DIR)/1.xml 1 \
    -since $(SRC_API_DIR)/2.xml 2 \
    -since $(SRC_API_DIR)/3.xml 3 \
    -since $(SRC_API_DIR)/4.xml 4 \
    -since $(SRC_API_DIR)/5.xml 5 \
    -since $(SRC_API_DIR)/6.xml 6 \
    -since $(SRC_API_DIR)/7.xml 7 \
    -since $(SRC_API_DIR)/8.xml 8 \
    -since $(SRC_API_DIR)/9.xml 9 \
    -since $(SRC_API_DIR)/10.xml 10 \
    -since $(SRC_API_DIR)/11.xml 11 \
    -since $(SRC_API_DIR)/12.xml 12 \
    -since $(SRC_API_DIR)/13.xml 13 \
    -since $(SRC_API_DIR)/14.txt 14 \
    -since $(SRC_API_DIR)/15.txt 15 \
    -since $(SRC_API_DIR)/16.txt 16 \
    -since $(SRC_API_DIR)/17.txt 17 \
    -since $(SRC_API_DIR)/18.txt 18 \
    -since $(SRC_API_DIR)/19.txt 19 \
    -since $(SRC_API_DIR)/20.txt 20 \
    -since $(SRC_API_DIR)/21.txt 21 \
    -since $(SRC_API_DIR)/22.txt 22 \
    -since $(SRC_API_DIR)/23.txt 23 \
    -since $(SRC_API_DIR)/24.txt 24 \
    -since $(SRC_API_DIR)/25.txt 25 \
    -since $(SRC_API_DIR)/26.txt 26 \
    -werror -hide 111 -hide 113 -hide 121 \
    -overview $(LOCAL_PATH)/core/java/overview.html \

framework_docs_LOCAL_API_CHECK_ADDITIONAL_JAVA_DIR:= \
	$(call intermediates-dir-for,JAVA_LIBRARIES,framework,,COMMON)

framework_docs_LOCAL_ADDITIONAL_JAVA_DIR:= \
	$(framework_docs_LOCAL_API_CHECK_ADDITIONAL_JAVA_DIR)

framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES := \
    frameworks/base/docs/knowntags.txt \
    $(libcore_to_document_generated)

samples_dir := development/samples/browseable

# Whitelist of valid groups, used for default TOC grouping. Each sample must
# belong to one (and only one) group. Assign samples to groups by setting
# a sample.group var to one of these groups in the sample's _index.jd.
sample_groups := -samplegroup Admin \
                 -samplegroup Background \
                 -samplegroup Connectivity \
                 -samplegroup Content \
                 -samplegroup Input \
                 -samplegroup Media \
                 -samplegroup Notification \
                 -samplegroup RenderScript \
                 -samplegroup Security \
                 -samplegroup Sensors \
                 -samplegroup System \
                 -samplegroup Testing \
                 -samplegroup UI \
                 -samplegroup Views \
                 -samplegroup Wearable

## SDK version identifiers used in the published docs
  # major[.minor] version for current SDK. (full releases only)
framework_docs_SDK_VERSION:=7.0
  # release version (ie "Release x")  (full releases only)
framework_docs_SDK_REL_ID:=1

framework_docs_LOCAL_DROIDDOC_OPTIONS += \
		-hdf dac true \
		-hdf sdk.codename O \
		-hdf sdk.preview.version 1 \
		-hdf sdk.version $(framework_docs_SDK_VERSION) \
		-hdf sdk.rel.id $(framework_docs_SDK_REL_ID) \
		-hdf sdk.preview 0 \
		-resourcesdir $(LOCAL_PATH)/docs/html/reference/images/ \
		-resourcesoutdir reference/android/images/

# Federate Support Library references against local API file.
framework_docs_LOCAL_DROIDDOC_OPTIONS += \
		-federate SupportLib https://developer.android.com \
		-federationapi SupportLib prebuilts/sdk/current/support-api.txt

# ====  the api stubs and current.xml ===========================
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=$(framework_docs_LOCAL_API_CHECK_SRC_FILES)
LOCAL_INTERMEDIATE_SOURCES:=$(framework_docs_LOCAL_INTERMEDIATE_SOURCES)
LOCAL_SRCJARS:=$(framework_docs_LOCAL_SRCJARS)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_API_CHECK_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_DROIDDOC_HTML_DIR:=$(framework_docs_LOCAL_DROIDDOC_HTML_DIR)
LOCAL_ADDITIONAL_JAVA_DIR:=$(framework_docs_LOCAL_API_CHECK_ADDITIONAL_JAVA_DIR)
LOCAL_ADDITIONAL_DEPENDENCIES:=$(framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES)

LOCAL_MODULE := api-stubs

LOCAL_DROIDDOC_STUB_OUT_DIR := $(TARGET_OUT_COMMON_INTERMEDIATES)/JAVA_LIBRARIES/android_stubs_current_intermediates/src

LOCAL_DROIDDOC_OPTIONS:=\
		$(framework_docs_LOCAL_DROIDDOC_OPTIONS) \
		-referenceonly \
		-api $(INTERNAL_PLATFORM_API_FILE) \
		-removedApi $(INTERNAL_PLATFORM_REMOVED_API_FILE) \
		-nodocs

LOCAL_DROIDDOC_CUSTOM_TEMPLATE_DIR:=external/doclava/res/assets/templates-sdk

LOCAL_UNINSTALLABLE_MODULE := true

include $(BUILD_DROIDDOC)

# $(gen), i.e. framework.aidl, is also needed while building against the current stub.
$(full_target): $(gen)
$(INTERNAL_PLATFORM_API_FILE): $(full_target)
$(call dist-for-goals,sdk,$(INTERNAL_PLATFORM_API_FILE))

# ====  the system api stubs ===================================
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=$(framework_docs_LOCAL_API_CHECK_SRC_FILES)
LOCAL_INTERMEDIATE_SOURCES:=$(framework_docs_LOCAL_INTERMEDIATE_SOURCES)
LOCAL_SRCJARS:=$(framework_docs_LOCAL_SRCJARS)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_API_CHECK_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_DROIDDOC_HTML_DIR:=$(framework_docs_LOCAL_DROIDDOC_HTML_DIR)
LOCAL_ADDITIONAL_JAVA_DIR:=$(framework_docs_LOCAL_API_CHECK_ADDITIONAL_JAVA_DIR)
LOCAL_ADDITIONAL_DEPENDENCIES:=$(framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES)

LOCAL_MODULE := system-api-stubs

LOCAL_DROIDDOC_STUB_OUT_DIR := $(TARGET_OUT_COMMON_INTERMEDIATES)/JAVA_LIBRARIES/android_system_stubs_current_intermediates/src

LOCAL_DROIDDOC_OPTIONS:=\
		$(framework_docs_LOCAL_DROIDDOC_OPTIONS) \
		-referenceonly \
		-showAnnotation android.annotation.SystemApi \
		-api $(INTERNAL_PLATFORM_SYSTEM_API_FILE) \
		-removedApi $(INTERNAL_PLATFORM_SYSTEM_REMOVED_API_FILE) \
		-exactApi $(INTERNAL_PLATFORM_SYSTEM_EXACT_API_FILE) \
		-nodocs

LOCAL_DROIDDOC_CUSTOM_TEMPLATE_DIR:=external/doclava/res/assets/templates-sdk

LOCAL_UNINSTALLABLE_MODULE := true

include $(BUILD_DROIDDOC)

# $(gen), i.e. framework.aidl, is also needed while building against the current stub.
$(full_target): $(gen)
$(INTERNAL_PLATFORM_SYSTEM_API_FILE): $(full_target)
$(call dist-for-goals,sdk,$(INTERNAL_PLATFORM_SYSTEM_API_FILE))

# ====  the test api stubs ===================================
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=$(framework_docs_LOCAL_API_CHECK_SRC_FILES)
LOCAL_INTERMEDIATE_SOURCES:=$(framework_docs_LOCAL_INTERMEDIATE_SOURCES)
LOCAL_SRCJARS:=$(framework_docs_LOCAL_SRCJARS)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_API_CHECK_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_DROIDDOC_HTML_DIR:=$(framework_docs_LOCAL_DROIDDOC_HTML_DIR)
LOCAL_ADDITIONAL_JAVA_DIR:=$(framework_docs_LOCAL_API_CHECK_ADDITIONAL_JAVA_DIR)
LOCAL_ADDITIONAL_DEPENDENCIES:=$(framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES)

LOCAL_MODULE := test-api-stubs

LOCAL_DROIDDOC_STUB_OUT_DIR := $(TARGET_OUT_COMMON_INTERMEDIATES)/JAVA_LIBRARIES/android_test_stubs_current_intermediates/src

LOCAL_DROIDDOC_OPTIONS:=\
               $(framework_docs_LOCAL_DROIDDOC_OPTIONS) \
               -referenceonly \
               -stubs $(TARGET_OUT_COMMON_INTERMEDIATES)/JAVA_LIBRARIES/android_test_stubs_current_intermediates/src \
               -showAnnotation android.annotation.TestApi \
               -api $(INTERNAL_PLATFORM_TEST_API_FILE) \
               -removedApi $(INTERNAL_PLATFORM_TEST_REMOVED_API_FILE) \
               -exactApi $(INTERNAL_PLATFORM_TEST_EXACT_API_FILE) \
               -nodocs

LOCAL_DROIDDOC_CUSTOM_TEMPLATE_DIR:=external/doclava/res/assets/templates-sdk

LOCAL_UNINSTALLABLE_MODULE := true

include $(BUILD_DROIDDOC)

# $(gen), i.e. framework.aidl, is also needed while building against the current stub.
$(full_target): $(gen)
$(INTERNAL_PLATFORM_TEST_API_FILE): $(full_target)
$(call dist-for-goals,sdk,$(INTERNAL_PLATFORM_TEST_API_FILE))

# ====  check javadoc comments but don't generate docs ========
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=$(framework_docs_LOCAL_SRC_FILES)
LOCAL_INTERMEDIATE_SOURCES:=$(framework_docs_LOCAL_INTERMEDIATE_SOURCES)
LOCAL_SRCJARS:=$(framework_docs_LOCAL_SRCJARS)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_DROIDDOC_HTML_DIR:=$(framework_docs_LOCAL_DROIDDOC_HTML_DIR)
LOCAL_ADDITIONAL_JAVA_DIR:=$(framework_docs_LOCAL_ADDITIONAL_JAVA_DIR)
LOCAL_ADDITIONAL_DEPENDENCIES:=$(framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES)

LOCAL_MODULE := doc-comment-check

LOCAL_DROIDDOC_OPTIONS:=\
		$(framework_docs_LOCAL_DROIDDOC_OPTIONS) \
		-referenceonly \
		-parsecomments

LOCAL_DROIDDOC_CUSTOM_TEMPLATE_DIR:=external/doclava/res/assets/templates-sdk

LOCAL_UNINSTALLABLE_MODULE := true

include $(BUILD_DROIDDOC)

# $(gen), i.e. framework.aidl, is also needed while building against the current stub.
$(full_target): $(gen)

# Run this for checkbuild
checkbuild: doc-comment-check-docs
# Check comment when you are updating the API
update-api: doc-comment-check-docs

# ====  static html in the sdk ==================================
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=$(framework_docs_LOCAL_SRC_FILES)
LOCAL_INTERMEDIATE_SOURCES:=$(framework_docs_LOCAL_INTERMEDIATE_SOURCES)
LOCAL_SRCJARS:=$(framework_docs_LOCAL_SRCJARS)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_DROIDDOC_HTML_DIR:=$(framework_docs_LOCAL_DROIDDOC_HTML_DIR)
LOCAL_ADDITIONAL_JAVA_DIR:=$(framework_docs_LOCAL_ADDITIONAL_JAVA_DIR)
LOCAL_ADDITIONAL_DEPENDENCIES:=$(framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES)

LOCAL_MODULE := offline-sdk

LOCAL_DROIDDOC_OPTIONS:=\
		$(framework_docs_LOCAL_DROIDDOC_OPTIONS) \
		-offlinemode \
		-title "Android SDK" \
		-proofread $(OUT_DOCS)/$(LOCAL_MODULE)-proofread.txt \
		-sdkvalues $(OUT_DOCS) \
		-hdf android.whichdoc offline

LOCAL_DROIDDOC_CUSTOM_TEMPLATE_DIR:=external/doclava/res/assets/templates-sdk

include $(BUILD_DROIDDOC)

static_doc_index_redirect := $(out_dir)/index.html
$(static_doc_index_redirect): \
	$(LOCAL_PATH)/docs/docs-preview-index.html | $(ACP)
	$(hide) mkdir -p $(dir $@)
	$(hide) $(ACP) $< $@

$(full_target): $(static_doc_index_redirect)


# ====  static html in the sdk ==================================
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=$(framework_docs_LOCAL_SRC_FILES)
LOCAL_INTERMEDIATE_SOURCES:=$(framework_docs_LOCAL_INTERMEDIATE_SOURCES)
LOCAL_SRCJARS:=$(framework_docs_LOCAL_SRCJARS)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_DROIDDOC_HTML_DIR:=$(framework_docs_LOCAL_DROIDDOC_HTML_DIR)
LOCAL_ADDITIONAL_JAVA_DIR:=$(framework_docs_LOCAL_ADDITIONAL_JAVA_DIR)
LOCAL_ADDITIONAL_DEPENDENCIES:=$(framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES)

LOCAL_MODULE := offline-sdk-referenceonly

LOCAL_DROIDDOC_OPTIONS:=\
		$(framework_docs_LOCAL_DROIDDOC_OPTIONS) \
		-offlinemode \
		-title "Android SDK" \
		-proofread $(OUT_DOCS)/$(LOCAL_MODULE)-proofread.txt \
		-sdkvalues $(OUT_DOCS) \
		-hdf android.whichdoc offline \
		-referenceonly

LOCAL_DROIDDOC_CUSTOM_TEMPLATE_DIR:=external/doclava/res/assets/templates-sdk

include $(BUILD_DROIDDOC)

static_doc_index_redirect := $(out_dir)/index.html
$(static_doc_index_redirect): $(LOCAL_PATH)/docs/docs-documentation-redirect.html
	$(copy-file-to-target)

static_doc_properties := $(out_dir)/source.properties
$(static_doc_properties): \
	$(LOCAL_PATH)/docs/source.properties | $(ACP)
	$(hide) mkdir -p $(dir $@)
	$(hide) $(ACP) $< $@

$(full_target): $(static_doc_index_redirect)
$(full_target): $(static_doc_properties)


# ==== docs for the web (on the androiddevdocs app engine server) =======================
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=$(framework_docs_LOCAL_SRC_FILES)
LOCAL_INTERMEDIATE_SOURCES:=$(framework_docs_LOCAL_INTERMEDIATE_SOURCES)
LOCAL_SRCJARS:=$(framework_docs_LOCAL_SRCJARS)
LOCAL_STATIC_JAVA_LIBRARIES:=$(framework_docs_LOCAL_STATIC_JAVA_LIBRARIES)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_DROIDDOC_HTML_DIR:=$(framework_docs_LOCAL_DROIDDOC_HTML_DIR)
LOCAL_ADDITIONAL_JAVA_DIR:=$(framework_docs_LOCAL_ADDITIONAL_JAVA_DIR)
LOCAL_ADDITIONAL_DEPENDENCIES:=$(framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES)
LOCAL_ADDITIONAL_HTML_DIR:=docs/html-intl /

LOCAL_MODULE := online-sdk

LOCAL_DROIDDOC_OPTIONS:= \
		$(framework_docs_LOCAL_DROIDDOC_OPTIONS) \
		-toroot / \
		-hdf android.whichdoc online \
		$(sample_groups) \
		-hdf android.hasSamples true \
		-samplesdir $(samples_dir)

LOCAL_DROIDDOC_CUSTOM_TEMPLATE_DIR:=external/doclava/res/assets/templates-sdk

include $(BUILD_DROIDDOC)

# ==== docs for the web (on the androiddevdocs app engine server) =======================
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=$(framework_docs_LOCAL_SRC_FILES)
LOCAL_INTERMEDIATE_SOURCES:=$(framework_docs_LOCAL_INTERMEDIATE_SOURCES)
LOCAL_SRCJARS:=$(framework_docs_LOCAL_SRCJARS)
LOCAL_STATIC_JAVA_LIBRARIES:=$(framework_docs_LOCAL_STATIC_JAVA_LIBRARIES)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_DROIDDOC_HTML_DIR:=$(framework_docs_LOCAL_DROIDDOC_HTML_DIR)
LOCAL_ADDITIONAL_JAVA_DIR:=$(framework_docs_LOCAL_ADDITIONAL_JAVA_DIR)
LOCAL_ADDITIONAL_DEPENDENCIES:=$(framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES)
LOCAL_ADDITIONAL_HTML_DIR:=docs/html-intl /

LOCAL_MODULE := online-system-api-sdk

LOCAL_DROIDDOC_OPTIONS:= \
		$(framework_docs_LOCAL_DROIDDOC_OPTIONS) \
		-referenceonly \
		-showAnnotation android.annotation.SystemApi \
		-title "Android SDK - Including system APIs." \
		-toroot / \
		-hide 101 \
		-hide 104 \
		-hide 108 \
		-hdf android.whichdoc online \
		$(sample_groups) \
		-hdf android.hasSamples true \
		-samplesdir $(samples_dir)

LOCAL_DROIDDOC_CUSTOM_TEMPLATE_DIR:=external/doclava/res/assets/templates-sdk

LOCAL_UNINSTALLABLE_MODULE := true

include $(BUILD_DROIDDOC)

# ==== docs for the web (on the devsite app engine server) =======================
include $(CLEAR_VARS)
LOCAL_SRC_FILES:=$(framework_docs_LOCAL_SRC_FILES)
LOCAL_INTERMEDIATE_SOURCES:=$(framework_docs_LOCAL_INTERMEDIATE_SOURCES)
LOCAL_SRCJARS:=$(framework_docs_LOCAL_SRCJARS)
LOCAL_STATIC_JAVA_LIBRARIES:=$(framework_docs_LOCAL_STATIC_JAVA_LIBRARIES)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_DROIDDOC_HTML_DIR:=$(framework_docs_LOCAL_DROIDDOC_HTML_DIR)
LOCAL_ADDITIONAL_JAVA_DIR:=$(framework_docs_LOCAL_ADDITIONAL_JAVA_DIR)
LOCAL_ADDITIONAL_DEPENDENCIES:=$(framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES)
# specify a second html input dir and an output path relative to OUT_DIR)
LOCAL_ADDITIONAL_HTML_DIR:=docs/html-intl /

LOCAL_MODULE := ds

LOCAL_DROIDDOC_OPTIONS:= \
		$(framework_docs_LOCAL_DROIDDOC_OPTIONS) \
		-toroot / \
		-hdf android.whichdoc online \
		-devsite \
		$(sample_groups) \
		-hdf android.hasSamples true \
		-samplesdir $(samples_dir)

LOCAL_DROIDDOC_CUSTOM_TEMPLATE_DIR:=external/doclava/res/assets/templates-sdk

include $(BUILD_DROIDDOC)

# ==== docs for the web (on the devsite app engine server) =======================
include $(CLEAR_VARS)
LOCAL_SRC_FILES:=$(framework_docs_LOCAL_SRC_FILES)
LOCAL_INTERMEDIATE_SOURCES:=$(framework_docs_LOCAL_INTERMEDIATE_SOURCES)
LOCAL_SRCJARS:=$(framework_docs_LOCAL_SRCJARS)
LOCAL_STATIC_JAVA_LIBRARIES:=$(framework_docs_LOCAL_STATIC_JAVA_LIBRARIES)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_DROIDDOC_HTML_DIR:=$(framework_docs_LOCAL_DROIDDOC_HTML_DIR)
LOCAL_ADDITIONAL_JAVA_DIR:=$(framework_docs_LOCAL_ADDITIONAL_JAVA_DIR)
LOCAL_ADDITIONAL_DEPENDENCIES:=$(framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES)
# specify a second html input dir and an output path relative to OUT_DIR)
LOCAL_ADDITIONAL_HTML_DIR:=docs/html-intl /

LOCAL_MODULE := ds-static

LOCAL_DROIDDOC_OPTIONS:= \
		$(framework_docs_LOCAL_DROIDDOC_OPTIONS) \
		-hdf android.whichdoc online \
		-staticonly \
		-toroot / \
		-devsite \
		-ignoreJdLinks

LOCAL_DROIDDOC_CUSTOM_TEMPLATE_DIR:=external/doclava/res/assets/templates-sdk

include $(BUILD_DROIDDOC)

# ==== generates full navtree for resolving @links in ds postprocessing ====
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=$(framework_docs_LOCAL_SRC_FILES)
LOCAL_INTERMEDIATE_SOURCES:=$(framework_docs_LOCAL_INTERMEDIATE_SOURCES)
LOCAL_SRCJARS:=$(framework_docs_LOCAL_SRCJARS)
LOCAL_STATIC_JAVA_LIBRARIES:=$(framework_docs_LOCAL_STATIC_JAVA_LIBRARIES)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_ADDITIONAL_JAVA_DIR:=$(framework_docs_LOCAL_ADDITIONAL_JAVA_DIR)
LOCAL_ADDITIONAL_DEPENDENCIES:=$(framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES)

LOCAL_MODULE := ds-ref-navtree

LOCAL_DROIDDOC_OPTIONS:= \
		$(framework_docs_LOCAL_DROIDDOC_OPTIONS) \
		-hdf android.whichdoc online \
		-toroot / \
		-atLinksNavtree \
		-navtreeonly

LOCAL_DROIDDOC_CUSTOM_TEMPLATE_DIR:=external/doclava/res/assets/templates-sdk

include $(BUILD_DROIDDOC)

# ==== site updates for docs (on the androiddevdocs app engine server) =======================
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=$(framework_docs_LOCAL_SRC_FILES)
LOCAL_INTERMEDIATE_SOURCES:=$(framework_docs_LOCAL_INTERMEDIATE_SOURCES)
LOCAL_SRCJARS:=$(framework_docs_LOCAL_SRCJARS)
LOCAL_STATIC_JAVA_LIBRARIES:=$(framework_docs_LOCAL_STATIC_JAVA_LIBRARIES)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_DROIDDOC_HTML_DIR:=$(framework_docs_LOCAL_DROIDDOC_HTML_DIR)
LOCAL_ADDITIONAL_JAVA_DIR:=$(framework_docs_LOCAL_ADDITIONAL_JAVA_DIR)
LOCAL_ADDITIONAL_DEPENDENCIES:=$(framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES)
LOCAL_ADDITIONAL_HTML_DIR:=docs/html-intl /

LOCAL_MODULE := online-sdk-dev

LOCAL_DROIDDOC_OPTIONS:= \
		$(framework_docs_LOCAL_DROIDDOC_OPTIONS) \
		-toroot / \
		-hdf android.whichdoc online \
		$(sample_groups) \
		-hdf android.hasSamples true \
		-samplesdir $(samples_dir)

LOCAL_DROIDDOC_CUSTOM_TEMPLATE_DIR:=external/doclava/res/assets/templates-sdk

include $(BUILD_DROIDDOC)

# ==== docs that have all of the stuff that's @hidden =======================
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=$(framework_docs_LOCAL_SRC_FILES)
LOCAL_INTERMEDIATE_SOURCES:=$(framework_docs_LOCAL_INTERMEDIATE_SOURCES)
LOCAL_SRCJARS:=$(framework_docs_LOCAL_SRCJARS)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_DROIDDOC_HTML_DIR:=$(framework_docs_LOCAL_DROIDDOC_HTML_DIR)
LOCAL_ADDITIONAL_JAVA_DIR:=$(framework_docs_LOCAL_ADDITIONAL_JAVA_DIR)
LOCAL_ADDITIONAL_DEPENDENCIES:=$(framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES)

LOCAL_MODULE := hidden
LOCAL_DROIDDOC_OPTIONS:=\
		$(framework_docs_LOCAL_DROIDDOC_OPTIONS) \
		-referenceonly \
		-title "Android SDK - Including hidden APIs."
#		-hidden

LOCAL_DROIDDOC_CUSTOM_TEMPLATE_DIR:=external/doclava/res/assets/templates-sdk

include $(BUILD_DROIDDOC)

# ====  java proto host library  ==============================
include $(CLEAR_VARS)
LOCAL_MODULE := platformprotos
LOCAL_PROTOC_OPTIMIZE_TYPE := full
LOCAL_PROTOC_FLAGS := \
    -Iexternal/protobuf/src
LOCAL_SOURCE_FILES_ALL_GENERATED := true
LOCAL_SRC_FILES := \
    $(call all-proto-files-under, core/proto) \
    $(call all-proto-files-under, libs/incident/proto)
include $(BUILD_HOST_JAVA_LIBRARY)


# Include subdirectory makefiles
# ============================================================

# If we're building with ONE_SHOT_MAKEFILE (mm, mmm), then what the framework
# team really wants is to build the stuff defined by this makefile.
ifeq (,$(ONE_SHOT_MAKEFILE))
include $(call first-makefiles-under,$(LOCAL_PATH))
endif

endif # ANDROID_BUILD_EMBEDDED
