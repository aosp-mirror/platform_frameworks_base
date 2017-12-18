/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "configuration/ConfigurationParser.h"

#include <string>

#include "android-base/stringprintf.h"
#include "androidfw/ResourceTypes.h"

#include "SdkConstants.h"
#include "configuration/ConfigurationParser.internal.h"
#include "test/Test.h"
#include "xml/XmlDom.h"

namespace aapt {

namespace configuration {
void PrintTo(const AndroidSdk& sdk, std::ostream* os) {
  *os << "SDK: min=" << sdk.min_sdk_version.value_or_default(-1)
      << ", target=" << sdk.target_sdk_version.value_or_default(-1)
      << ", max=" << sdk.max_sdk_version.value_or_default(-1);
}

namespace handler {

namespace {

using ::aapt::configuration::Abi;
using ::aapt::configuration::AndroidManifest;
using ::aapt::configuration::AndroidSdk;
using ::aapt::configuration::ConfiguredArtifact;
using ::aapt::configuration::DeviceFeature;
using ::aapt::configuration::GlTexture;
using ::aapt::configuration::Locale;
using ::aapt::configuration::PostProcessingConfiguration;
using ::aapt::xml::Element;
using ::aapt::xml::NodeCast;
using ::android::ResTable_config;
using ::android::base::StringPrintf;
using ::testing::ElementsAre;
using ::testing::SizeIs;

constexpr const char* kValidConfig = R"(<?xml version="1.0" encoding="utf-8" ?>
<post-process xmlns="http://schemas.android.com/tools/aapt">
  <groups>
    <abi-group label="arm">
      <abi>armeabi-v7a</abi>
      <abi>arm64-v8a</abi>
    </abi-group>
    <abi-group label="other">
      <abi>x86</abi>
      <abi>mips</abi>
    </abi-group>
    <screen-density-group label="large">
      <screen-density>xhdpi</screen-density>
      <screen-density>xxhdpi</screen-density>
      <screen-density>xxxhdpi</screen-density>
    </screen-density-group>
    <screen-density-group label="alldpi">
      <screen-density>ldpi</screen-density>
      <screen-density>mdpi</screen-density>
      <screen-density>hdpi</screen-density>
      <screen-density>xhdpi</screen-density>
      <screen-density>xxhdpi</screen-density>
      <screen-density>xxxhdpi</screen-density>
    </screen-density-group>
    <locale-group label="europe">
      <locale>en</locale>
      <locale>es</locale>
      <locale>fr</locale>
      <locale>de</locale>
    </locale-group>
    <locale-group label="north-america">
      <locale>en</locale>
      <locale>es-rMX</locale>
      <locale>fr-rCA</locale>
    </locale-group>
    <android-sdk-group label="v19">
      <android-sdk
          minSdkVersion="19"
          targetSdkVersion="24"
          maxSdkVersion="25">
        <manifest>
          <!--- manifest additions here XSLT? TODO -->
        </manifest>
      </android-sdk>
    </android-sdk-group>
    <gl-texture-group label="dxt1">
      <gl-texture name="GL_EXT_texture_compression_dxt1">
        <texture-path>assets/dxt1/*</texture-path>
      </gl-texture>
    </gl-texture-group>
    <device-feature-group label="low-latency">
      <supports-feature>android.hardware.audio.low_latency</supports-feature>
    </device-feature-group>
  </groups>
  <artifacts>
    <artifact-format>
      ${base}.${abi}.${screen-density}.${locale}.${sdk}.${gl}.${feature}.release
    </artifact-format>
    <artifact
        name="art1"
        abi-group="arm"
        screen-density-group="large"
        locale-group="europe"
        android-sdk-group="v19"
        gl-texture-group="dxt1"
        device-feature-group="low-latency"/>
    <artifact
        name="art2"
        abi-group="other"
        screen-density-group="alldpi"
        locale-group="north-america"
        android-sdk-group="v19"
        gl-texture-group="dxt1"
        device-feature-group="low-latency"/>
  </artifacts>
</post-process>
)";

class ConfigurationParserTest : public ConfigurationParser, public ::testing::Test {
 public:
  ConfigurationParserTest() : ConfigurationParser("") {}

 protected:
  StdErrDiagnostics diag_;
};

TEST_F(ConfigurationParserTest, ForPath_NoFile) {
  auto result = ConfigurationParser::ForPath("./does_not_exist.xml");
  EXPECT_FALSE(result);
}

TEST_F(ConfigurationParserTest, ValidateFile) {
  auto parser = ConfigurationParser::ForContents(kValidConfig).WithDiagnostics(&diag_);
  auto result = parser.Parse("test.apk");
  ASSERT_TRUE(result);
  const std::vector<OutputArtifact>& value = result.value();
  EXPECT_THAT(value, SizeIs(2ul));

  const OutputArtifact& a1 = value[0];
  EXPECT_EQ(a1.name, "art1.apk");
  EXPECT_THAT(a1.abis, ElementsAre(Abi::kArmV7a, Abi::kArm64V8a));
  EXPECT_THAT(a1.screen_densities,
              ElementsAre(test::ParseConfigOrDie("xhdpi").CopyWithoutSdkVersion(),
                          test::ParseConfigOrDie("xxhdpi").CopyWithoutSdkVersion(),
                          test::ParseConfigOrDie("xxxhdpi").CopyWithoutSdkVersion()));
  EXPECT_THAT(a1.locales, ElementsAre(test::ParseConfigOrDie("en"), test::ParseConfigOrDie("es"),
                                      test::ParseConfigOrDie("fr"), test::ParseConfigOrDie("de")));
  EXPECT_EQ(a1.android_sdk.value().min_sdk_version.value(), 19l);
  EXPECT_THAT(a1.textures, SizeIs(1ul));
  EXPECT_THAT(a1.features, SizeIs(1ul));

  const OutputArtifact& a2 = value[1];
  EXPECT_EQ(a2.name, "art2.apk");
  EXPECT_THAT(a2.abis, ElementsAre(Abi::kX86, Abi::kMips));
  EXPECT_THAT(a2.screen_densities,
              ElementsAre(test::ParseConfigOrDie("ldpi").CopyWithoutSdkVersion(),
                          test::ParseConfigOrDie("mdpi").CopyWithoutSdkVersion(),
                          test::ParseConfigOrDie("hdpi").CopyWithoutSdkVersion(),
                          test::ParseConfigOrDie("xhdpi").CopyWithoutSdkVersion(),
                          test::ParseConfigOrDie("xxhdpi").CopyWithoutSdkVersion(),
                          test::ParseConfigOrDie("xxxhdpi").CopyWithoutSdkVersion()));
  EXPECT_THAT(a2.locales,
              ElementsAre(test::ParseConfigOrDie("en"), test::ParseConfigOrDie("es-rMX"),
                          test::ParseConfigOrDie("fr-rCA")));
  EXPECT_EQ(a2.android_sdk.value().min_sdk_version.value(), 19l);
  EXPECT_THAT(a2.textures, SizeIs(1ul));
  EXPECT_THAT(a2.features, SizeIs(1ul));
}

TEST_F(ConfigurationParserTest, InvalidNamespace) {
  constexpr const char* invalid_ns = R"(<?xml version="1.0" encoding="utf-8" ?>
  <post-process xmlns="http://schemas.android.com/tools/another-unknown-tool" />)";

  auto result = ConfigurationParser::ForContents(invalid_ns).Parse("test.apk");
  ASSERT_FALSE(result);
}

TEST_F(ConfigurationParserTest, ArtifactAction) {
  PostProcessingConfiguration config;
  {
    const auto doc = test::BuildXmlDom(R"xml(
      <artifact
          abi-group="arm"
          screen-density-group="large"
          locale-group="europe"
          android-sdk-group="v19"
          gl-texture-group="dxt1"
          device-feature-group="low-latency"/>)xml");

    ASSERT_TRUE(ArtifactTagHandler(&config, NodeCast<Element>(doc->root.get()), &diag_));

    EXPECT_THAT(config.artifacts, SizeIs(1ul));

    auto& artifact = config.artifacts.back();
    EXPECT_FALSE(artifact.name);  // TODO: make this fail.
    EXPECT_EQ(1, artifact.version);
    EXPECT_EQ("arm", artifact.abi_group.value());
    EXPECT_EQ("large", artifact.screen_density_group.value());
    EXPECT_EQ("europe", artifact.locale_group.value());
    EXPECT_EQ("v19", artifact.android_sdk_group.value());
    EXPECT_EQ("dxt1", artifact.gl_texture_group.value());
    EXPECT_EQ("low-latency", artifact.device_feature_group.value());
  }

  {
    // Perform a second action to ensure we get 2 artifacts.
    const auto doc = test::BuildXmlDom(R"xml(
      <artifact
          abi-group="other"
          screen-density-group="large"
          locale-group="europe"
          android-sdk-group="v19"
          gl-texture-group="dxt1"
          device-feature-group="low-latency"/>)xml");

    ASSERT_TRUE(ArtifactTagHandler(&config, NodeCast<Element>(doc.get()->root.get()), &diag_));
    EXPECT_THAT(config.artifacts, SizeIs(2ul));
    EXPECT_EQ(2, config.artifacts.back().version);
  }

  {
    // Perform a third action with a set version code.
    const auto doc = test::BuildXmlDom(R"xml(
    <artifact
        version="5"
        abi-group="other"
        screen-density-group="large"
        locale-group="europe"
        android-sdk-group="v19"
        gl-texture-group="dxt1"
        device-feature-group="low-latency"/>)xml");

    ASSERT_TRUE(ArtifactTagHandler(&config, NodeCast<Element>(doc.get()->root.get()), &diag_));
    EXPECT_THAT(config.artifacts, SizeIs(3ul));
    EXPECT_EQ(5, config.artifacts.back().version);
  }

  {
    // Perform a fourth action to ensure the version code still increments.
    const auto doc = test::BuildXmlDom(R"xml(
    <artifact
        abi-group="other"
        screen-density-group="large"
        locale-group="europe"
        android-sdk-group="v19"
        gl-texture-group="dxt1"
        device-feature-group="low-latency"/>)xml");

    ASSERT_TRUE(ArtifactTagHandler(&config, NodeCast<Element>(doc.get()->root.get()), &diag_));
    EXPECT_THAT(config.artifacts, SizeIs(4ul));
    EXPECT_EQ(6, config.artifacts.back().version);
  }
}

TEST_F(ConfigurationParserTest, DuplicateArtifactVersion) {
  static constexpr const char* configuration = R"xml(<?xml version="1.0" encoding="utf-8" ?>
      <pst-process xmlns="http://schemas.android.com/tools/aapt">>
        <artifacts>
          <artifact-format>
            ${base}.${abi}.${screen-density}.${locale}.${sdk}.${gl}.${feature}.release
          </artifact-format>
          <artifact
              name="art1"
              abi-group="arm"
              screen-density-group="large"
              locale-group="europe"
              android-sdk-group="v19"
              gl-texture-group="dxt1"
              device-feature-group="low-latency"/>
          <artifact
              name="art2"
              version = "1"
              abi-group="other"
              screen-density-group="alldpi"
              locale-group="north-america"
              android-sdk-group="v19"
              gl-texture-group="dxt1"
              device-feature-group="low-latency"/>
        </artifacts>
      </post-process>)xml";
  auto result = ConfigurationParser::ForContents(configuration).Parse("test.apk");
  ASSERT_FALSE(result);
}

TEST_F(ConfigurationParserTest, ArtifactFormatAction) {
  const auto doc = test::BuildXmlDom(R"xml(
    <artifact-format>
      ${base}.${abi}.${screen-density}.${locale}.${sdk}.${gl}.${feature}.release
    </artifact-format>)xml");

  PostProcessingConfiguration config;
  bool ok = ArtifactFormatTagHandler(&config, NodeCast<Element>(doc.get()->root.get()), &diag_);
  ASSERT_TRUE(ok);
  ASSERT_TRUE(config.artifact_format);
  EXPECT_EQ(
      "${base}.${abi}.${screen-density}.${locale}.${sdk}.${gl}.${feature}.release",
      static_cast<std::string>(config.artifact_format.value())
  );
}

TEST_F(ConfigurationParserTest, AbiGroupAction) {
  static constexpr const char* xml = R"xml(
    <abi-group label="arm">
      <!-- First comment. -->
      <abi>
        armeabi-v7a
      </abi>
      <!-- Another comment. -->
      <abi>arm64-v8a</abi>
    </abi-group>)xml";

  auto doc = test::BuildXmlDom(xml);

  PostProcessingConfiguration config;
  bool ok = AbiGroupTagHandler(&config, NodeCast<Element>(doc.get()->root.get()), &diag_);
  ASSERT_TRUE(ok);

  EXPECT_THAT(config.abi_groups, SizeIs(1ul));
  ASSERT_EQ(1u, config.abi_groups.count("arm"));

  auto& out = config.abi_groups["arm"];
  ASSERT_THAT(out, ElementsAre(Abi::kArmV7a, Abi::kArm64V8a));
}

TEST_F(ConfigurationParserTest, AbiGroupAction_EmptyGroup) {
  static constexpr const char* xml = R"xml(<abi-group label="arm64-v8a"/>)xml";

  auto doc = test::BuildXmlDom(xml);

  PostProcessingConfiguration config;
  bool ok = AbiGroupTagHandler(&config, NodeCast<Element>(doc.get()->root.get()), &diag_);
  ASSERT_TRUE(ok);

  EXPECT_THAT(config.abi_groups, SizeIs(1ul));
  ASSERT_EQ(1u, config.abi_groups.count("arm64-v8a"));

  auto& out = config.abi_groups["arm64-v8a"];
  ASSERT_THAT(out, ElementsAre(Abi::kArm64V8a));
}

TEST_F(ConfigurationParserTest, AbiGroupAction_InvalidEmptyGroup) {
  static constexpr const char* xml = R"xml(<abi-group label="arm"/>)xml";

  auto doc = test::BuildXmlDom(xml);

  PostProcessingConfiguration config;
  bool ok = AbiGroupTagHandler(&config, NodeCast<Element>(doc.get()->root.get()), &diag_);
  ASSERT_FALSE(ok);
}

TEST_F(ConfigurationParserTest, ScreenDensityGroupAction) {
  static constexpr const char* xml = R"xml(
    <screen-density-group label="large">
      <screen-density>xhdpi</screen-density>
      <screen-density>
        xxhdpi
      </screen-density>
      <screen-density>xxxhdpi</screen-density>
    </screen-density-group>)xml";

  auto doc = test::BuildXmlDom(xml);

  PostProcessingConfiguration config;
  bool ok = ScreenDensityGroupTagHandler(&config, NodeCast<Element>(doc.get()->root.get()), &diag_);
  ASSERT_TRUE(ok);

  EXPECT_THAT(config.screen_density_groups, SizeIs(1ul));
  ASSERT_EQ(1u, config.screen_density_groups.count("large"));

  ConfigDescription xhdpi;
  xhdpi.density = ResTable_config::DENSITY_XHIGH;
  ConfigDescription xxhdpi;
  xxhdpi.density = ResTable_config::DENSITY_XXHIGH;
  ConfigDescription xxxhdpi;
  xxxhdpi.density = ResTable_config::DENSITY_XXXHIGH;

  auto& out = config.screen_density_groups["large"];
  ASSERT_THAT(out, ElementsAre(xhdpi, xxhdpi, xxxhdpi));
}

TEST_F(ConfigurationParserTest, ScreenDensityGroupAction_EmtpyGroup) {
  static constexpr const char* xml = R"xml(<screen-density-group label="xhdpi"/>)xml";

  auto doc = test::BuildXmlDom(xml);

  PostProcessingConfiguration config;
  bool ok = ScreenDensityGroupTagHandler(&config, NodeCast<Element>(doc.get()->root.get()), &diag_);
  ASSERT_TRUE(ok);

  EXPECT_THAT(config.screen_density_groups, SizeIs(1ul));
  ASSERT_EQ(1u, config.screen_density_groups.count("xhdpi"));

  ConfigDescription xhdpi;
  xhdpi.density = ResTable_config::DENSITY_XHIGH;

  auto& out = config.screen_density_groups["xhdpi"];
  ASSERT_THAT(out, ElementsAre(xhdpi));
}

TEST_F(ConfigurationParserTest, ScreenDensityGroupAction_InvalidEmtpyGroup) {
  static constexpr const char* xml = R"xml(<screen-density-group label="really-big-screen"/>)xml";

  auto doc = test::BuildXmlDom(xml);

  PostProcessingConfiguration config;
  bool ok = ScreenDensityGroupTagHandler(&config, NodeCast<Element>(doc.get()->root.get()), &diag_);
  ASSERT_FALSE(ok);
}

TEST_F(ConfigurationParserTest, LocaleGroupAction) {
  static constexpr const char* xml = R"xml(
    <locale-group label="europe">
      <locale>en</locale>
      <locale>es</locale>
      <locale>fr</locale>
      <locale>de</locale>
    </locale-group>)xml";

  auto doc = test::BuildXmlDom(xml);

  PostProcessingConfiguration config;
  bool ok = LocaleGroupTagHandler(&config, NodeCast<Element>(doc.get()->root.get()), &diag_);
  ASSERT_TRUE(ok);

  ASSERT_EQ(1ul, config.locale_groups.size());
  ASSERT_EQ(1u, config.locale_groups.count("europe"));

  const auto& out = config.locale_groups["europe"];

  ConfigDescription en = test::ParseConfigOrDie("en");
  ConfigDescription es = test::ParseConfigOrDie("es");
  ConfigDescription fr = test::ParseConfigOrDie("fr");
  ConfigDescription de = test::ParseConfigOrDie("de");

  ASSERT_THAT(out, ElementsAre(en, es, fr, de));
}

TEST_F(ConfigurationParserTest, LocaleGroupAction_EmtpyGroup) {
  static constexpr const char* xml = R"xml(<locale-group label="en"/>)xml";

  auto doc = test::BuildXmlDom(xml);

  PostProcessingConfiguration config;
  bool ok = LocaleGroupTagHandler(&config, NodeCast<Element>(doc.get()->root.get()), &diag_);
  ASSERT_TRUE(ok);

  ASSERT_EQ(1ul, config.locale_groups.size());
  ASSERT_EQ(1u, config.locale_groups.count("en"));

  const auto& out = config.locale_groups["en"];

  ConfigDescription en = test::ParseConfigOrDie("en");

  ASSERT_THAT(out, ElementsAre(en));
}

TEST_F(ConfigurationParserTest, LocaleGroupAction_InvalidEmtpyGroup) {
  static constexpr const char* xml = R"xml(<locale-group label="arm64"/>)xml";

  auto doc = test::BuildXmlDom(xml);

  PostProcessingConfiguration config;
  bool ok = LocaleGroupTagHandler(&config, NodeCast<Element>(doc.get()->root.get()), &diag_);
  ASSERT_FALSE(ok);
}

TEST_F(ConfigurationParserTest, AndroidSdkGroupAction) {
  static constexpr const char* xml = R"xml(
    <android-sdk-group label="v19">
      <android-sdk
          minSdkVersion="19"
          targetSdkVersion="24"
          maxSdkVersion="25">
        <manifest>
          <!--- manifest additions here XSLT? TODO -->
        </manifest>
      </android-sdk>
    </android-sdk-group>)xml";

  auto doc = test::BuildXmlDom(xml);

  PostProcessingConfiguration config;
  bool ok = AndroidSdkGroupTagHandler(&config, NodeCast<Element>(doc.get()->root.get()), &diag_);
  ASSERT_TRUE(ok);

  ASSERT_EQ(1ul, config.android_sdk_groups.size());
  ASSERT_EQ(1u, config.android_sdk_groups.count("v19"));

  auto& out = config.android_sdk_groups["v19"];

  AndroidSdk sdk;
  sdk.min_sdk_version = 19;
  sdk.target_sdk_version = 24;
  sdk.max_sdk_version = 25;
  sdk.manifest = AndroidManifest();

  ASSERT_EQ(sdk, out);
}

TEST_F(ConfigurationParserTest, AndroidSdkGroupAction_SingleVersion) {
  {
    static constexpr const char* xml = R"xml(
      <android-sdk-group label="v19">
        <android-sdk minSdkVersion="19"></android-sdk>
      </android-sdk-group>)xml";

    auto doc = test::BuildXmlDom(xml);

    PostProcessingConfiguration config;
    bool ok = AndroidSdkGroupTagHandler(&config, NodeCast<Element>(doc.get()->root.get()), &diag_);
    ASSERT_TRUE(ok);

    ASSERT_EQ(1ul, config.android_sdk_groups.size());
    ASSERT_EQ(1u, config.android_sdk_groups.count("v19"));

    auto& out = config.android_sdk_groups["v19"];
    EXPECT_EQ(19, out.min_sdk_version.value());
    EXPECT_FALSE(out.max_sdk_version);
    EXPECT_FALSE(out.target_sdk_version);
  }

  {
    static constexpr const char* xml = R"xml(
      <android-sdk-group label="v19">
        <android-sdk maxSdkVersion="19"></android-sdk>
      </android-sdk-group>)xml";

    auto doc = test::BuildXmlDom(xml);

    PostProcessingConfiguration config;
    bool ok = AndroidSdkGroupTagHandler(&config, NodeCast<Element>(doc.get()->root.get()), &diag_);
    ASSERT_TRUE(ok);

    ASSERT_EQ(1ul, config.android_sdk_groups.size());
    ASSERT_EQ(1u, config.android_sdk_groups.count("v19"));

    auto& out = config.android_sdk_groups["v19"];
    EXPECT_EQ(19, out.max_sdk_version.value());
    EXPECT_FALSE(out.min_sdk_version);
    EXPECT_FALSE(out.target_sdk_version);
  }

  {
    static constexpr const char* xml = R"xml(
      <android-sdk-group label="v19">
        <android-sdk targetSdkVersion="19"></android-sdk>
      </android-sdk-group>)xml";

    auto doc = test::BuildXmlDom(xml);

    PostProcessingConfiguration config;
    bool ok = AndroidSdkGroupTagHandler(&config, NodeCast<Element>(doc.get()->root.get()), &diag_);
    ASSERT_TRUE(ok);

    ASSERT_EQ(1ul, config.android_sdk_groups.size());
    ASSERT_EQ(1u, config.android_sdk_groups.count("v19"));

    auto& out = config.android_sdk_groups["v19"];
    EXPECT_EQ(19, out.target_sdk_version.value());
    EXPECT_FALSE(out.min_sdk_version);
    EXPECT_FALSE(out.max_sdk_version);
  }
}

TEST_F(ConfigurationParserTest, AndroidSdkGroupAction_InvalidVersion) {
  static constexpr const char* xml = R"xml(
    <android-sdk-group label="v19">
      <android-sdk
          minSdkVersion="v19"
          targetSdkVersion="v24"
          maxSdkVersion="v25">
        <manifest>
          <!--- manifest additions here XSLT? TODO -->
        </manifest>
      </android-sdk>
    </android-sdk-group>)xml";

  auto doc = test::BuildXmlDom(xml);

  PostProcessingConfiguration config;
  bool ok = AndroidSdkGroupTagHandler(&config, NodeCast<Element>(doc.get()->root.get()), &diag_);
  ASSERT_FALSE(ok);
}

TEST_F(ConfigurationParserTest, AndroidSdkGroupAction_NonNumeric) {
  static constexpr const char* xml = R"xml(
    <android-sdk-group label="P">
      <android-sdk
          minSdkVersion="25"
          targetSdkVersion="%s"
          maxSdkVersion="%s">
      </android-sdk>
    </android-sdk-group>)xml";

  const auto& dev_sdk = GetDevelopmentSdkCodeNameAndVersion();
  const char* codename = dev_sdk.first.data();
  const ApiVersion& version = dev_sdk.second;

  auto doc = test::BuildXmlDom(StringPrintf(xml, codename, codename));

  PostProcessingConfiguration config;
  bool ok = AndroidSdkGroupTagHandler(&config, NodeCast<Element>(doc.get()->root.get()), &diag_);
  ASSERT_TRUE(ok);

  ASSERT_EQ(1ul, config.android_sdk_groups.size());
  ASSERT_EQ(1u, config.android_sdk_groups.count("P"));

  auto& out = config.android_sdk_groups["P"];

  AndroidSdk sdk;
  sdk.min_sdk_version = 25;
  sdk.target_sdk_version = version;
  sdk.max_sdk_version = version;

  ASSERT_EQ(sdk, out);
}

TEST_F(ConfigurationParserTest, GlTextureGroupAction) {
  static constexpr const char* xml = R"xml(
    <gl-texture-group label="dxt1">
      <gl-texture name="GL_EXT_texture_compression_dxt1">
        <texture-path>assets/dxt1/main/*</texture-path>
        <texture-path>
          assets/dxt1/test/*
        </texture-path>
      </gl-texture>
    </gl-texture-group>)xml";

  auto doc = test::BuildXmlDom(xml);

  PostProcessingConfiguration config;
  bool ok = GlTextureGroupTagHandler(&config, NodeCast<Element>(doc.get()->root.get()), &diag_);
  ASSERT_TRUE(ok);

  EXPECT_THAT(config.gl_texture_groups, SizeIs(1ul));
  ASSERT_EQ(1u, config.gl_texture_groups.count("dxt1"));

  auto& out = config.gl_texture_groups["dxt1"];

  GlTexture texture{
      std::string("GL_EXT_texture_compression_dxt1"),
      {"assets/dxt1/main/*", "assets/dxt1/test/*"}
  };

  ASSERT_EQ(1ul, out.size());
  ASSERT_EQ(texture, out[0]);
}

TEST_F(ConfigurationParserTest, DeviceFeatureGroupAction) {
  static constexpr const char* xml = R"xml(
    <device-feature-group label="low-latency">
      <supports-feature>android.hardware.audio.low_latency</supports-feature>
      <supports-feature>
        android.hardware.audio.pro
      </supports-feature>
    </device-feature-group>)xml";

  auto doc = test::BuildXmlDom(xml);

  PostProcessingConfiguration config;
  bool ok = DeviceFeatureGroupTagHandler(&config, NodeCast<Element>(doc.get()->root.get()), &diag_);
  ASSERT_TRUE(ok);

  EXPECT_THAT(config.device_feature_groups, SizeIs(1ul));
  ASSERT_EQ(1u, config.device_feature_groups.count("low-latency"));

  auto& out = config.device_feature_groups["low-latency"];

  DeviceFeature low_latency = "android.hardware.audio.low_latency";
  DeviceFeature pro = "android.hardware.audio.pro";
  ASSERT_THAT(out, ElementsAre(low_latency, pro));
}

// Artifact name parser test cases.

TEST(ArtifactTest, Simple) {
  StdErrDiagnostics diag;
  ConfiguredArtifact x86;
  x86.abi_group = {"x86"};

  auto x86_result = x86.ToArtifactName("something.${abi}.apk", "", &diag);
  ASSERT_TRUE(x86_result);
  EXPECT_EQ(x86_result.value(), "something.x86.apk");

  ConfiguredArtifact arm;
  arm.abi_group = {"armeabi-v7a"};

  {
    auto arm_result = arm.ToArtifactName("app.${abi}.apk", "", &diag);
    ASSERT_TRUE(arm_result);
    EXPECT_EQ(arm_result.value(), "app.armeabi-v7a.apk");
  }

  {
    auto arm_result = arm.ToArtifactName("app.${abi}.apk", "different_name.apk", &diag);
    ASSERT_TRUE(arm_result);
    EXPECT_EQ(arm_result.value(), "app.armeabi-v7a.apk");
  }

  {
    auto arm_result = arm.ToArtifactName("${basename}.${abi}.apk", "app.apk", &diag);
    ASSERT_TRUE(arm_result);
    EXPECT_EQ(arm_result.value(), "app.armeabi-v7a.apk");
  }

  {
    auto arm_result = arm.ToArtifactName("app.${abi}.${ext}", "app.apk", &diag);
    ASSERT_TRUE(arm_result);
    EXPECT_EQ(arm_result.value(), "app.armeabi-v7a.apk");
  }
}

TEST(ArtifactTest, Complex) {
  StdErrDiagnostics diag;
  ConfiguredArtifact artifact;
  artifact.abi_group = {"mips64"};
  artifact.screen_density_group = {"ldpi"};
  artifact.device_feature_group = {"df1"};
  artifact.gl_texture_group = {"glx1"};
  artifact.locale_group = {"en-AU"};
  artifact.android_sdk_group = {"v26"};

  {
    auto result = artifact.ToArtifactName(
        "app.${density}_${locale}_${feature}_${gl}.${sdk}.${abi}.apk", "", &diag);
    ASSERT_TRUE(result);
    EXPECT_EQ(result.value(), "app.ldpi_en-AU_df1_glx1.v26.mips64.apk");
  }

  {
    auto result = artifact.ToArtifactName(
        "app.${density}_${locale}_${feature}_${gl}.${sdk}.${abi}.apk", "app.apk", &diag);
    ASSERT_TRUE(result);
    EXPECT_EQ(result.value(), "app.ldpi_en-AU_df1_glx1.v26.mips64.apk");
  }

  {
    auto result = artifact.ToArtifactName(
        "${basename}.${density}_${locale}_${feature}_${gl}.${sdk}.${abi}.apk", "app.apk", &diag);
    ASSERT_TRUE(result);
    EXPECT_EQ(result.value(), "app.ldpi_en-AU_df1_glx1.v26.mips64.apk");
  }

  {
    auto result = artifact.ToArtifactName(
        "app.${density}_${locale}_${feature}_${gl}.${sdk}.${abi}.${ext}", "app.apk", &diag);
    ASSERT_TRUE(result);
    EXPECT_EQ(result.value(), "app.ldpi_en-AU_df1_glx1.v26.mips64.apk");
  }

  {
    auto result = artifact.ToArtifactName(
        "${basename}.${density}_${locale}_${feature}_${gl}.${sdk}.${abi}", "app.apk", &diag);
    ASSERT_TRUE(result);
    EXPECT_EQ(result.value(), "app.ldpi_en-AU_df1_glx1.v26.mips64.apk");
  }
}

TEST(ArtifactTest, Missing) {
  StdErrDiagnostics diag;
  ConfiguredArtifact x86;
  x86.abi_group = {"x86"};

  EXPECT_FALSE(x86.ToArtifactName("something.${density}.apk", "", &diag));
  EXPECT_FALSE(x86.ToArtifactName("something.apk", "", &diag));
  EXPECT_FALSE(x86.ToArtifactName("something.${density}.apk", "something.apk", &diag));
  EXPECT_FALSE(x86.ToArtifactName("something.apk", "something.apk", &diag));
}

TEST(ArtifactTest, Empty) {
  StdErrDiagnostics diag;
  ConfiguredArtifact artifact;

  EXPECT_FALSE(artifact.ToArtifactName("something.${density}.apk", "", &diag));
  EXPECT_TRUE(artifact.ToArtifactName("something.apk", "", &diag));
  EXPECT_FALSE(artifact.ToArtifactName("something.${density}.apk", "something.apk", &diag));
  EXPECT_TRUE(artifact.ToArtifactName("something.apk", "something.apk", &diag));
}

TEST(ArtifactTest, Repeated) {
  StdErrDiagnostics diag;
  ConfiguredArtifact artifact;
  artifact.screen_density_group = {"mdpi"};

  ASSERT_TRUE(artifact.ToArtifactName("something.${density}.apk", "", &diag));
  EXPECT_FALSE(artifact.ToArtifactName("something.${density}.${density}.apk", "", &diag));
  ASSERT_TRUE(artifact.ToArtifactName("something.${density}.apk", "something.apk", &diag));
}

TEST(ArtifactTest, Nesting) {
  StdErrDiagnostics diag;
  ConfiguredArtifact x86;
  x86.abi_group = {"x86"};

  EXPECT_FALSE(x86.ToArtifactName("something.${abi${density}}.apk", "", &diag));

  const Maybe<std::string>& name = x86.ToArtifactName("something.${abi${abi}}.apk", "", &diag);
  ASSERT_TRUE(name);
  EXPECT_EQ(name.value(), "something.${abix86}.apk");
}

TEST(ArtifactTest, Recursive) {
  StdErrDiagnostics diag;
  ConfiguredArtifact artifact;
  artifact.device_feature_group = {"${gl}"};
  artifact.gl_texture_group = {"glx1"};

  EXPECT_FALSE(artifact.ToArtifactName("app.${feature}.${gl}.apk", "", &diag));

  artifact.device_feature_group = {"df1"};
  artifact.gl_texture_group = {"${feature}"};
  {
    const auto& result = artifact.ToArtifactName("app.${feature}.${gl}.apk", "", &diag);
    ASSERT_TRUE(result);
    EXPECT_EQ(result.value(), "app.df1.${feature}.apk");
  }

  // This is an invalid case, but should be the only possible case due to the ordering of
  // replacement.
  artifact.device_feature_group = {"${gl}"};
  artifact.gl_texture_group = {"glx1"};
  {
    const auto& result = artifact.ToArtifactName("app.${feature}.apk", "", &diag);
    ASSERT_TRUE(result);
    EXPECT_EQ(result.value(), "app.glx1.apk");
  }
}

}  // namespace
}  // namespace handler
}  // namespace configuration
}  // namespace aapt
