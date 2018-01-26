LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := com.android.test.libs.product
LOCAL_PRODUCT_MODULE := true
LOCAL_SRC_FILES := $(call all-java-files-under, product/java)
LOCAL_REQUIRED_MODULES := com.android.test.libs.product.xml
include $(BUILD_JAVA_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := com.android.test.libs.product.xml
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_PATH := $(TARGET_OUT_PRODUCT_ETC)/permissions
LOCAL_SRC_FILES:= product/com.android.test.libs.product.xml
include $(BUILD_PREBUILT)
