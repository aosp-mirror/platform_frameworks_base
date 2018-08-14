# Copyright (C) 2016 The Android Open Source Project
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


##############################################################
# FrameworksServicesLib app just for Robolectric test target #
##############################################################
LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_PACKAGE_NAME := FrameworksServicesLib
LOCAL_PRIVATE_PLATFORM_APIS := true
LOCAL_MODULE_TAGS := optional

LOCAL_PRIVILEGED_MODULE := true

LOCAL_STATIC_JAVA_LIBRARIES := \
    services.backup \
    services.core

include $(BUILD_PACKAGE)

##############################################
# FrameworksServices Robolectric test target #
##############################################
include $(CLEAR_VARS)

# Dependency platform-robolectric-android-all-stubs below contains a bunch of Android classes as
# stubs that throw RuntimeExceptions when we use them. The goal is to include hidden APIs that
# weren't included in Robolectric's Android jar files. However, we are testing the framework itself
# here, so if we write stuff that is being used in the tests and exist in
# platform-robolectric-android-all-stubs, the class loader is going to pick up the latter, and thus
# we are going to test what we don't want. To solve this:
#
#   1. If the class being used should be visible to bundled apps:
#      => Bypass the stubs target by including them in LOCAL_SRC_FILES and LOCAL_AIDL_INCLUDES
#         (if aidl).
#
#   2. If it's not visible:
#      => Remove the class from the stubs jar (common/robolectric/android-all/android-all-stubs.jar)
#         and add the class path to
#         common/robolectric/android-all/android-all-stubs_removed_classes.txt.
#

INTERNAL_BACKUP := ../../core/java/com/android/internal/backup

LOCAL_SRC_FILES := \
    $(call all-java-files-under, src) \
    $(call all-Iaidl-files-under, $(INTERNAL_BACKUP)) \
    $(call all-java-files-under, ../../core/java/android/app/backup) \
    $(call all-Iaidl-files-under, ../../core/java/android/app/backup) \
    ../../core/java/android/content/pm/PackageInfo.java \
    ../../core/java/android/app/IBackupAgent.aidl \
    ../../core/java/android/util/KeyValueSettingObserver.java

LOCAL_AIDL_INCLUDES := \
    $(call all-Iaidl-files-under, $(INTERNAL_BACKUP)) \
    $(call all-Iaidl-files-under, ../../core/java/android/app/backup) \
    ../../core/java/android/app/IBackupAgent.aidl

LOCAL_STATIC_JAVA_LIBRARIES := \
    platform-robolectric-android-all-stubs \
    android-support-test \
    mockito-robolectric-prebuilt \
    platform-test-annotations \
    truth-prebuilt \
    testng

LOCAL_JAVA_LIBRARIES := \
    junit \
    platform-robolectric-3.6.1-prebuilt

LOCAL_INSTRUMENTATION_FOR := FrameworksServicesLib
LOCAL_MODULE := FrameworksServicesRoboTests

LOCAL_MODULE_TAGS := optional

include $(BUILD_STATIC_JAVA_LIBRARY)

###############################################################
# FrameworksServices runner target to run the previous target #
###############################################################
include $(CLEAR_VARS)

LOCAL_MODULE := RunFrameworksServicesRoboTests

LOCAL_SDK_VERSION := current

LOCAL_STATIC_JAVA_LIBRARIES := \
    FrameworksServicesRoboTests

LOCAL_TEST_PACKAGE := FrameworksServicesLib

LOCAL_INSTRUMENT_SOURCE_DIRS := $(dir $(LOCAL_PATH))backup/java

include prebuilts/misc/common/robolectric/3.6.1/run_robotests.mk
