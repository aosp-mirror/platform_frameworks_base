# Include only if the service is required
ifneq ($(findstring accessibility,$(REQUIRED_SERVICES)),)

SUB_DIR := accessibility/java

LOCAL_SRC_FILES += \
      $(call all-java-files-under,$(SUB_DIR))

#DEFINED_SERVICES += com.android.server.accessibility.AccessibilityManagerService

endif
