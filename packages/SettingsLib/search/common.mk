# Include this file to generate SearchIndexableResourcesImpl

LOCAL_ANNOTATION_PROCESSORS += \
    SettingsLib-annotation-processor

LOCAL_ANNOTATION_PROCESSOR_CLASSES += \
    com.android.settingslib.search.IndexableProcessor

LOCAL_STATIC_JAVA_LIBRARIES += \
    SettingsLib-search
