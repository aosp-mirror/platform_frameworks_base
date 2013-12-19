# Include only if the service is required
ifneq ($(findstring print,$(REQUIRED_SERVICES)),)

SUB_DIR := print/java

LOCAL_SRC_FILES += \
      $(call all-java-files-under,$(SUB_DIR))

#DEFINED_SERVICES += com.android.server.print.PrintManagerService

endif
