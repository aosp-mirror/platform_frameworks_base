LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_SRC_FILES := qwerty.kcm
include $(BUILD_KEY_CHAR_MAP)

include $(CLEAR_VARS)
LOCAL_SRC_FILES := qwerty2.kcm
include $(BUILD_KEY_CHAR_MAP)

file := $(TARGET_OUT_KEYLAYOUT)/qwerty.kl
ALL_PREBUILT += $(file)
$(file): $(LOCAL_PATH)/qwerty.kl | $(ACP)
	$(transform-prebuilt-to-target)

file := $(TARGET_OUT_KEYLAYOUT)/AVRCP.kl
ALL_PREBUILT += $(file)
$(file) : $(LOCAL_PATH)/AVRCP.kl | $(ACP)
	$(transform-prebuilt-to-target)
