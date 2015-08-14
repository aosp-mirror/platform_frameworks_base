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
	compile/IdAssigner.cpp \
	compile/Png.cpp \
	compile/XmlIdCollector.cpp \
	flatten/Archive.cpp \
	flatten/TableFlattener.cpp \
	flatten/XmlFlattener.cpp \
	link/AutoVersioner.cpp \
	link/PrivateAttributeMover.cpp \
	link/ReferenceLinker.cpp \
	link/TableMerger.cpp \
	link/XmlReferenceLinker.cpp \
	process/SymbolTable.cpp \
	unflatten/BinaryResourceParser.cpp \
	unflatten/ResChunkPullParser.cpp \
	util/BigBuffer.cpp \
	util/Files.cpp \
	util/Util.cpp \
	ConfigDescription.cpp \
	Debug.cpp \
	Flags.cpp \
	JavaClassGenerator.cpp \
	Locale.cpp \
	ProguardRules.cpp \
	Resource.cpp \
	ResourceParser.cpp \
	ResourceTable.cpp \
	ResourceUtils.cpp \
	ResourceValues.cpp \
	SdkConstants.cpp \
	StringPool.cpp \
	XmlDom.cpp \
	XmlPullParser.cpp

testSources := \
	compile/IdAssigner_test.cpp \
	compile/XmlIdCollector_test.cpp \
	flatten/FileExportWriter_test.cpp \
	flatten/TableFlattener_test.cpp \
	flatten/XmlFlattener_test.cpp \
	link/AutoVersioner_test.cpp \
	link/PrivateAttributeMover_test.cpp \
	link/ReferenceLinker_test.cpp \
	link/TableMerger_test.cpp \
	link/XmlReferenceLinker_test.cpp \
	process/SymbolTable_test.cpp \
	unflatten/FileExportHeaderReader_test.cpp \
	util/BigBuffer_test.cpp \
	util/Maybe_test.cpp \
	util/StringPiece_test.cpp \
	util/Util_test.cpp \
	ConfigDescription_test.cpp \
	JavaClassGenerator_test.cpp \
	Locale_test.cpp \
	Resource_test.cpp \
	ResourceParser_test.cpp \
	ResourceTable_test.cpp \
	ResourceUtils_test.cpp \
	StringPool_test.cpp \
	ValueVisitor_test.cpp \
	XmlDom_test.cpp \
	XmlPullParser_test.cpp

toolSources := \
	compile/Compile.cpp \
	link/Link.cpp

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
cppFlags := -std=c++11 -Wno-missing-field-initializers -fno-exceptions

# ==========================================================
# Build the host static library: libaapt2
# ==========================================================
include $(CLEAR_VARS)
LOCAL_MODULE := libaapt2

LOCAL_SRC_FILES := $(sources)
LOCAL_STATIC_LIBRARIES += $(hostStaticLibs)
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

LOCAL_SRC_FILES := $(main) $(toolSources)

LOCAL_STATIC_LIBRARIES += libaapt2 $(hostStaticLibs)
LOCAL_LDLIBS += $(hostLdLibs)
LOCAL_CFLAGS += $(cFlags)
LOCAL_CPPFLAGS += $(cppFlags)

include $(BUILD_HOST_EXECUTABLE)

endif # No TARGET_BUILD_APPS or TARGET_BUILD_PDK
