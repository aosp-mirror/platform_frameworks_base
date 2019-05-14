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
my_native_layoutlib_dependencies := \
    libandroid_runtime \
    libandroidicu-host \
    libbase \
    libc++ \
    libcutils \
    libdng_sdk \
    libexpat-host \
    libft2 \
    libharfbuzz_ng \
    libhwui-host \
    libicui18n-host \
    libicuuc-host \
    libjpeg \
    liblog \
    libminikin \
    libnativehelper \
    libpiex \
    libpng \
    libz-host \
    libziparchive
$(call dist-for-goals, layoutlib, \
    $(foreach m,$(my_native_layoutlib_dependencies), \
    $(HOST_LIBRARY_PATH)/$(m)$(HOST_SHLIB_SUFFIX):layoutlib_native/$(m)$(HOST_SHLIB_SUFFIX)))
$(call dist-for-goals, layoutlib, $(HOST_OUT)/com.android.runtime/etc/icu/icudt63l.dat:layoutlib_native/icu/icudt63l.dat)
my_native_layoutlib_dependencies :=