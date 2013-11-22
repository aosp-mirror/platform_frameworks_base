# Include only if the service is required
ifneq ($(findstring devicepolicy,$(REQUIRED_SERVICES)),)

SUB_DIR := devicepolicy/java

LOCAL_SRC_FILES += \
      $(call all-java-files-under,$(SUB_DIR))

#DEFINED_SERVICES += com.android.server.devicepolicy.DevicePolicyManagerService

endif
