ifneq ($(TARGET_SIMULATOR),true)

LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= dumpstate.c utils.c

LOCAL_MODULE:= dumpstate

LOCAL_SHARED_LIBRARIES := libcutils

include $(BUILD_EXECUTABLE)

COMMANDS = dumpcrash bugreport
SYMLINKS := $(addprefix $(TARGET_OUT_EXECUTABLES)/,$(COMMANDS))
$(SYMLINKS): DUMPSTATE_BINARY := dumpstate
$(SYMLINKS): $(LOCAL_INSTALLED_MODULE) $(LOCAL_PATH)/Android.mk
	@echo "Symlink: $@ -> $(DUMPSTATE_BINARY)"
	@mkdir -p $(dir $@)
	@rm -rf $@
	$(hide) ln -sf $(DUMPSTATE_BINARY) $@

ALL_DEFAULT_INSTALLED_MODULES += $(SYMLINKS)

endif
