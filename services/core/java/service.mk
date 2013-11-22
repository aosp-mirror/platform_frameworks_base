SUB_DIR := core/java

LOCAL_SRC_FILES += \
      $(call all-java-files-under,$(SUB_DIR)) \
      $(SUB_DIR)/com/android/server/EventLogTags.logtags \
      $(SUB_DIR)/com/android/server/am/EventLogTags.logtags
