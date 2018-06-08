#
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
#

#
# A central place to define mappings to paths used by the framework build, to
# avoid hard-coding them in Android.mk files. Not meant for header file include
# directories, despite the fact that it was historically used for that!
#

#
# A list of all source roots under frameworks/multidex.
#
FRAMEWORKS_MULTIDEX_SUBDIRS := \
    multidex/library/src \
    multidex/instrumentation/src

#
# A version of FRAMEWORKS_SUPPORT_SUBDIRS that is expanded to full paths from
# the root of the tree.
#
FRAMEWORKS_SUPPORT_JAVA_SRC_DIRS += \
    $(addprefix frameworks/,$(FRAMEWORKS_MULTIDEX_SUBDIRS)) \
    frameworks/rs/support

#
# A list of support library modules.
#
FRAMEWORKS_SUPPORT_JAVA_LIBRARIES += \
    android-support-v8-renderscript \
    android-support-multidex \
    android-support-multidex-instrumentation

#
# A list of all documented source roots under frameworks/data-binding.
#
FRAMEWORKS_DATA_BINDING_SUBDIRS := \
    baseLibrary/src/main \
    extensions/library/src/main \
    extensions/library/src/doc

#
# A version of FRAMEWORKS_DATA_BINDING_SUBDIRS that is expanded to full paths from
# the root of the tree.
#
FRAMEWORKS_DATA_BINDING_JAVA_SRC_DIRS := \
    $(addprefix frameworks/data-binding/,$(FRAMEWORKS_DATA_BINDING_SUBDIRS))
