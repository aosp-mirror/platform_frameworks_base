LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

ALL_PREBUILT += $(TARGET_OUT)/bin/dumpstate
$(TARGET_OUT)/bin/dumpstate : $(LOCAL_PATH)/dumpstate | $(ACP)
	$(transform-prebuilt-to-target)

SYMLINKS := $(TARGET_OUT_EXECUTABLES)/dumpcrash
$(SYMLINKS): DUMPSTATE_BINARY := dumpstate
$(SYMLINKS): $(LOCAL_INSTALLED_MODULE) $(LOCAL_PATH)/Android.mk
	@echo "Symlink: $@ -> $(DUMPSTATE_BINARY)"
	@mkdir -p $(dir $@)
	@rm -rf $@
	$(hide) ln -sf $(DUMPSTATE_BINARY) $@

ALL_DEFAULT_INSTALLED_MODULES += $(SYMLINKS)
