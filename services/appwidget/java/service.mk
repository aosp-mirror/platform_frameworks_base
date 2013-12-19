# Include only if the service is required
ifneq ($(findstring appwidget,$(REQUIRED_SERVICES)),)

SUB_DIR := appwidget/java

LOCAL_SRC_FILES += \
      $(call all-java-files-under,$(SUB_DIR))

#DEFINED_SERVICES += com.android.server.appwidget.AppWidgetService

endif
