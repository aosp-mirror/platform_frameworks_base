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
LOCAL_PATH:= $(call my-dir)

# ==========================================================
# Setup some common variables for the different build
# targets here.
# ==========================================================

main := Main.cpp
sources := \
	compile/IdAssigner.cpp \
	compile/Png.cpp \
	compile/PseudolocaleGenerator.cpp \
	compile/Pseudolocalizer.cpp \
	compile/XmlIdCollector.cpp \
	filter/ConfigFilter.cpp \
	flatten/Archive.cpp \
	flatten/TableFlattener.cpp \
	flatten/XmlFlattener.cpp \
	io/FileSystem.cpp \
	io/ZipArchive.cpp \
	link/AutoVersioner.cpp \
	link/ManifestFixer.cpp \
	link/ProductFilter.cpp \
	link/PrivateAttributeMover.cpp \
	link/ReferenceLinker.cpp \
	link/TableMerger.cpp \
	link/XmlReferenceLinker.cpp \
	process/SymbolTable.cpp \
	proto/ProtoHelpers.cpp \
	proto/TableProtoDeserializer.cpp \
	proto/TableProtoSerializer.cpp \
	split/TableSplitter.cpp \
	unflatten/BinaryResourceParser.cpp \
	unflatten/ResChunkPullParser.cpp \
	util/BigBuffer.cpp \
	util/Files.cpp \
	util/Util.cpp \
	ConfigDescription.cpp \
	Debug.cpp \
	Flags.cpp \
	java/AnnotationProcessor.cpp \
	java/ClassDefinition.cpp \
	java/JavaClassGenerator.cpp \
	java/ManifestClassGenerator.cpp \
	java/ProguardRules.cpp \
	Locale.cpp \
	Resource.cpp \
	ResourceParser.cpp \
	ResourceTable.cpp \
	ResourceUtils.cpp \
	ResourceValues.cpp \
	SdkConstants.cpp \
	StringPool.cpp \
	xml/XmlActionExecutor.cpp \
	xml/XmlDom.cpp \
	xml/XmlPullParser.cpp \
	xml/XmlUtil.cpp

sources += Format.proto

testSources := \
	compile/IdAssigner_test.cpp \
	compile/PseudolocaleGenerator_test.cpp \
	compile/Pseudolocalizer_test.cpp \
	compile/XmlIdCollector_test.cpp \
	filter/ConfigFilter_test.cpp \
	flatten/TableFlattener_test.cpp \
	flatten/XmlFlattener_test.cpp \
	link/AutoVersioner_test.cpp \
	link/ManifestFixer_test.cpp \
	link/PrivateAttributeMover_test.cpp \
	link/ProductFilter_test.cpp \
	link/ReferenceLinker_test.cpp \
	link/TableMerger_test.cpp \
	link/XmlReferenceLinker_test.cpp \
	process/SymbolTable_test.cpp \
	proto/TableProtoSerializer_test.cpp \
	split/TableSplitter_test.cpp \
	util/BigBuffer_test.cpp \
	util/Files_test.cpp \
	util/Maybe_test.cpp \
	util/StringPiece_test.cpp \
	util/Util_test.cpp \
	ConfigDescription_test.cpp \
	java/AnnotationProcessor_test.cpp \
	java/JavaClassGenerator_test.cpp \
	java/ManifestClassGenerator_test.cpp \
	Locale_test.cpp \
	Resource_test.cpp \
	ResourceParser_test.cpp \
	ResourceTable_test.cpp \
	ResourceUtils_test.cpp \
	SdkConstants_test.cpp \
	StringPool_test.cpp \
	ValueVisitor_test.cpp \
	xml/XmlActionExecutor_test.cpp \
	xml/XmlDom_test.cpp \
	xml/XmlPullParser_test.cpp \
	xml/XmlUtil_test.cpp

toolSources := \
	compile/Compile.cpp \
	diff/Diff.cpp \
	dump/Dump.cpp \
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
	libbase \
	libprotobuf-cpp-lite_static


# Statically link libz for MinGW (Win SDK under Linux),
# and dynamically link for all others.
hostStaticLibs_windows := libz
hostLdLibs_linux := -lz
hostLdLibs_darwin := -lz

cFlags := -Wall -Werror -Wno-unused-parameter -UNDEBUG
cFlags_darwin := -D_DARWIN_UNLIMITED_STREAMS
cFlags_windows := -Wno-maybe-uninitialized # Incorrectly marking use of Maybe.value() as error.
cppFlags := -std=c++11 -Wno-missing-field-initializers -fno-exceptions -fno-rtti
protoIncludes := $(call generated-sources-dir-for,STATIC_LIBRARIES,libaapt2,HOST)

# ==========================================================
# NOTE: Do not add any shared libraries.
# AAPT2 is built to run on many environments
# that may not have the required dependencies.
# ==========================================================

# ==========================================================
# Build the host static library: libaapt2
# ==========================================================
include $(CLEAR_VARS)
LOCAL_MODULE := libaapt2
LOCAL_MODULE_CLASS := STATIC_LIBRARIES
LOCAL_MODULE_HOST_OS := darwin linux windows
LOCAL_CFLAGS := $(cFlags)
LOCAL_CFLAGS_darwin := $(cFlags_darwin)
LOCAL_CFLAGS_windows := $(cFlags_windows)
LOCAL_CPPFLAGS := $(cppFlags)
LOCAL_C_INCLUDES := $(protoIncludes)
LOCAL_SRC_FILES := $(sources)
LOCAL_STATIC_LIBRARIES := $(hostStaticLibs)
LOCAL_STATIC_LIBRARIES_windows := $(hostStaticLibs_windows)
include $(BUILD_HOST_STATIC_LIBRARY)

# ==========================================================
# Build the host tests: libaapt2_tests
# ==========================================================
include $(CLEAR_VARS)
LOCAL_MODULE := libaapt2_tests
LOCAL_MODULE_TAGS := tests
LOCAL_MODULE_HOST_OS := darwin linux windows
LOCAL_CFLAGS := $(cFlags)
LOCAL_CFLAGS_darwin := $(cFlags_darwin)
LOCAL_CFLAGS_windows := $(cFlags_windows)
LOCAL_CPPFLAGS := $(cppFlags)
LOCAL_C_INCLUDES := $(protoIncludes)
LOCAL_SRC_FILES := $(testSources)
LOCAL_STATIC_LIBRARIES := libaapt2 $(hostStaticLibs)
LOCAL_STATIC_LIBRARIES_windows := $(hostStaticLibs_windows)
LOCAL_LDLIBS := $(hostLdLibs)
LOCAL_LDLIBS_darwin := $(hostLdLibs_darwin)
LOCAL_LDLIBS_linux := $(hostLdLibs_linux)
include $(BUILD_HOST_NATIVE_TEST)

# ==========================================================
# Build the host executable: aapt2
# ==========================================================
include $(CLEAR_VARS)
LOCAL_MODULE := aapt2
LOCAL_MODULE_HOST_OS := darwin linux windows
LOCAL_CFLAGS := $(cFlags)
LOCAL_CFLAGS_darwin := $(cFlags_darwin)
LOCAL_CFLAGS_windows := $(cFlags_windows)
LOCAL_CPPFLAGS := $(cppFlags)
LOCAL_C_INCLUDES := $(protoIncludes)
LOCAL_SRC_FILES := $(main) $(toolSources)
LOCAL_STATIC_LIBRARIES := libaapt2 $(hostStaticLibs)
LOCAL_STATIC_LIBRARIES_windows := $(hostStaticLibs_windows)
LOCAL_LDLIBS := $(hostLdLibs)
LOCAL_LDLIBS_darwin := $(hostLdLibs_darwin)
LOCAL_LDLIBS_linux := $(hostLdLibs_linux)
include $(BUILD_HOST_EXECUTABLE)

ifeq ($(ONE_SHOT_MAKEFILE),)
include $(call all-makefiles-under,$(LOCAL_PATH))
endif
