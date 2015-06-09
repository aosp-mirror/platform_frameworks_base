#
# Copyright (C) 2015 The Android Open Source Project
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

# This tool is prebuilt if we're doing an app-only build.
ifeq ($(TARGET_BUILD_APPS)$(filter true,$(TARGET_BUILD_PDK)),)

# ==========================================================
# Setup some common variables for the different build
# targets here.
# ==========================================================
LOCAL_PATH:= $(call my-dir)

main := Main.cpp
sources := \
	BigBuffer.cpp \
	BinaryResourceParser.cpp \
	BindingXmlPullParser.cpp \
	ConfigDescription.cpp \
	Debug.cpp \
	Files.cpp \
	Flag.cpp \
	JavaClassGenerator.cpp \
	Linker.cpp \
	Locale.cpp \
	Logger.cpp \
	ManifestMerger.cpp \
	ManifestParser.cpp \
	ManifestValidator.cpp \
	Png.cpp \
	ProguardRules.cpp \
	ResChunkPullParser.cpp \
	Resource.cpp \
	ResourceParser.cpp \
	ResourceTable.cpp \
	ResourceTableResolver.cpp \
	ResourceValues.cpp \
	SdkConstants.cpp \
	StringPool.cpp \
	TableFlattener.cpp \
	Util.cpp \
	ScopedXmlPullParser.cpp \
	SourceXmlPullParser.cpp \
	XliffXmlPullParser.cpp \
	XmlDom.cpp \
	XmlFlattener.cpp \
	ZipEntry.cpp \
	ZipFile.cpp

testSources := \
	BigBuffer_test.cpp \
	BindingXmlPullParser_test.cpp \
	Compat_test.cpp \
	ConfigDescription_test.cpp \
	JavaClassGenerator_test.cpp \
	Linker_test.cpp \
	Locale_test.cpp \
	ManifestMerger_test.cpp \
	ManifestParser_test.cpp \
	Maybe_test.cpp \
	NameMangler_test.cpp \
	ResourceParser_test.cpp \
	Resource_test.cpp \
	ResourceTable_test.cpp \
	ScopedXmlPullParser_test.cpp \
	StringPiece_test.cpp \
	StringPool_test.cpp \
	Util_test.cpp \
	XliffXmlPullParser_test.cpp \
	XmlDom_test.cpp \
	XmlFlattener_test.cpp

cIncludes := \
	external/libpng \
	external/libz

hostLdLibs :=

hostStaticLibs := \
	libandroidfw \
	libutils \
	liblog \
	libcutils \
	libexpat \
	libziparchive-host \
	libpng \
	libbase

ifneq ($(strip $(USE_MINGW)),)
	hostStaticLibs += libz
else
	hostLdLibs += -lz
endif

cFlags := -Wall -Werror -Wno-unused-parameter -UNDEBUG
cppFlags := -std=c++11 -Wno-missing-field-initializers -Wno-unused-private-field

# ==========================================================
# Build the host static library: libaapt2
# ==========================================================
include $(CLEAR_VARS)
LOCAL_MODULE := libaapt2

LOCAL_SRC_FILES := $(sources)
LOCAL_C_INCLUDES += $(cIncludes)
LOCAL_CFLAGS += $(cFlags)
LOCAL_CPPFLAGS += $(cppFlags)

include $(BUILD_HOST_STATIC_LIBRARY)


# ==========================================================
# Build the host tests: libaapt2_tests
# ==========================================================
include $(CLEAR_VARS)
LOCAL_MODULE := libaapt2_tests
LOCAL_MODULE_TAGS := tests

LOCAL_SRC_FILES := $(testSources)

LOCAL_C_INCLUDES += $(cIncludes)
LOCAL_STATIC_LIBRARIES += libaapt2 $(hostStaticLibs)
LOCAL_LDLIBS += $(hostLdLibs)
LOCAL_CFLAGS += $(cFlags)
LOCAL_CPPFLAGS += $(cppFlags)

include $(BUILD_HOST_NATIVE_TEST)

# ==========================================================
# Build the host executable: aapt2
# ==========================================================
include $(CLEAR_VARS)
LOCAL_MODULE := aapt2

LOCAL_SRC_FILES := $(main)

LOCAL_C_INCLUDES += $(cIncludes)
LOCAL_STATIC_LIBRARIES += libaapt2 $(hostStaticLibs)
LOCAL_LDLIBS += $(hostLdLibs)
LOCAL_CFLAGS += $(cFlags)
LOCAL_CPPFLAGS += $(cppFlags)

include $(BUILD_HOST_EXECUTABLE)

endif # No TARGET_BUILD_APPS or TARGET_BUILD_PDK
