# Include only if the service is required
ifneq ($(findstring backup,$(REQUIRED_SERVICES)),)

SUB_DIR := backup/java

LOCAL_SRC_FILES += \
      $(call all-java-files-under,$(SUB_DIR))

#DEFINED_SERVICES += com.android.server.backup.BackupManagerService

endif
