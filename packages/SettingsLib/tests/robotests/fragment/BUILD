load("//third_party/java/android/android_sdk_linux/extras/android/compatibility/jetify:jetify.bzl", "jetify_android_library", "jetify_android_local_test")

package(
    default_applicable_licenses = ["//third_party/java_src/robolectric:license"],
    default_visibility = ["//third_party/java_src/robolectric:__subpackages__"],
)

licenses(["notice"])

#==============================================================================
# Test resources library
#==============================================================================
jetify_android_library(
    name = "test_resources",
    custom_package = "org.robolectric.shadows.androidx.fragment",
    manifest = "src/test/AndroidManifest.xml",
    resource_files = glob(
        ["src/test/resources/**/*"],
    ),
)

#==============================================================================
# AndroidX fragment module library
#==============================================================================
jetify_android_library(
    name = "androidx_fragment",
    testonly = 1,
    srcs = glob(
        ["src/main/java/**"],
    ),
    custom_package = "org.robolectric.shadows.androidx.fragment",
    javacopts = [
        "-Aorg.robolectric.annotation.processing.shadowPackage=org.robolectric.shadows.androidx.fragment",
    ],
    jetify_sources = True,
    plugins = [
        "//java/com/google/thirdparty/robolectric/processor",
    ],
    deps = [
        "//third_party/java/androidx/core",
        "//third_party/java/androidx/fragment",
        "//third_party/java/androidx/lifecycle",
        "//third_party/java_src/robolectric/shadowapi",
        "//third_party/java_src/robolectric/shadows/framework",
    ],
)

[
    jetify_android_local_test(
        name = "test_" + src.rstrip(".java"),
        size = "small",
        srcs = glob(
            ["src/test/java/**/*.java"],
        ),
        jetify_sources = True,
        deps = [
            ":androidx_fragment",
            ":test_resources",
            "//third_party/java/androidx/fragment",
            "//third_party/java/androidx/loader",
            "//third_party/java/mockito",
            "//third_party/java/robolectric",
            "//third_party/java/truth",
        ],
    )
    for src in glob(
        ["src/test/java/**/*Test.java"],
    )
]
