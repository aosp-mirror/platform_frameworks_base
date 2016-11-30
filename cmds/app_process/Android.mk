LOCAL_PATH:= $(call my-dir)

app_process_common_shared_libs := \
    libandroid_runtime \
    libbinder \
    libcutils \
    libdl \
    liblog \
    libnativeloader \
    libutils \

# This is a list of libraries that need to be included in order to avoid
# bad apps. This prevents a library from having a mismatch when resolving
# new/delete from an app shared library.
# See b/21032018 for more details.
app_process_common_shared_libs += \
    libwilhelm \

app_process_common_static_libs := \
    libsigchain \

app_process_src_files := \
    app_main.cpp \

app_process_cflags := \
    -Wall -Werror -Wunused -Wunreachable-code

app_process_ldflags_32 := \
    -Wl,--version-script,art/sigchainlib/version-script32.txt -Wl,--export-dynamic
app_process_ldflags_64 := \
    -Wl,--version-script,art/sigchainlib/version-script64.txt -Wl,--export-dynamic

include $(CLEAR_VARS)

LOCAL_SRC_FILES:= $(app_process_src_files)

LOCAL_LDFLAGS_32 := $(app_process_ldflags_32)
LOCAL_LDFLAGS_64 := $(app_process_ldflags_64)

LOCAL_SHARED_LIBRARIES := $(app_process_common_shared_libs)

LOCAL_WHOLE_STATIC_LIBRARIES := $(app_process_common_static_libs)

LOCAL_MODULE:= app_process
LOCAL_MULTILIB := both
LOCAL_MODULE_STEM_32 := app_process32
LOCAL_MODULE_STEM_64 := app_process64

LOCAL_CFLAGS += $(app_process_cflags)

# In SANITIZE_LITE mode, the main app_process is not sanitized - special
# binaries below do that.
ifeq ($(SANITIZE_LITE),true)
LOCAL_SANITIZE := never
endif

include $(BUILD_EXECUTABLE)

# Create a symlink from app_process to app_process32 or 64
# depending on the target configuration.
include  $(BUILD_SYSTEM)/executable_prefer_symlink.mk

# Build a variant of app_process binary linked with ASan runtime.
# Built when SANITIZE_LITE is enabled.
ifeq ($(SANITIZE_LITE),true)

include $(CLEAR_VARS)

LOCAL_SRC_FILES:= $(app_process_src_files)

LOCAL_LDFLAGS_32 := $(app_process_ldflags_32)
LOCAL_LDFLAGS_64 := $(app_process_ldflags_64)

LOCAL_SHARED_LIBRARIES := $(app_process_common_shared_libs)

LOCAL_WHOLE_STATIC_LIBRARIES := $(app_process_common_static_libs)

LOCAL_MODULE := app_process__asan
LOCAL_MULTILIB := both
LOCAL_MODULE_STEM_32 := app_process32
LOCAL_MODULE_STEM_64 := app_process64
LOCAL_MODULE_PATH := $(TARGET_OUT_EXECUTABLES)/asan

LOCAL_CFLAGS += $(app_process_cflags)

include $(BUILD_EXECUTABLE)

endif # SANITIZE_LITE
