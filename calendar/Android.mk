# Copyright 2009 Google, Inc.

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := calendar
LOCAL_SRC_FILES := \
	../core/java/android/provider/Calendar.java \
	../core/java/android/pim/EventRecurrence.java \
	../core/java/android/pim/ICalendar.java \
	../core/java/android/pim/RecurrenceSet.java \
	../core/java/android/pim/ContactsAsyncHelper.java \
	../core/java/android/pim/DateException.java

include $(BUILD_STATIC_JAVA_LIBRARY)

# Include this library in the build server's output directory
$(call dist-for-goals, droid, $(LOCAL_BUILT_MODULE):calendar.jar)
