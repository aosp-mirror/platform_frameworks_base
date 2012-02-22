# Copyright 2012 The Android Open Source Project

LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_MODULE := content

include $(BUILD_JAVA_LIBRARY)

include $(CLEAR_VARS)
ALL_PREBUILT += $(TARGET_OUT)/bin/content
$(TARGET_OUT)/bin/content : $(LOCAL_PATH)/content | $(ACP)
	$(transform-prebuilt-to-target)

NOTICE_FILE := NOTICE
files_noticed := bin/content

# Generate rules for a single file. The argument is the file path relative to
# the installation root
define make-notice-file

$(TARGET_OUT_NOTICE_FILES)/src/$(1).txt: $(LOCAL_PATH)/$(NOTICE_FILE)
	@echo Notice file: $$< -- $$@
	@mkdir -p $$(dir $$@)
	@cat $$< >> $$@

$(TARGET_OUT_NOTICE_FILES)/hash-timestamp: $(TARGET_OUT_NOTICE_FILES)/src/$(1).txt

endef

$(foreach file,$(files_noticed),$(eval $(call make-notice-file,$(file))))
