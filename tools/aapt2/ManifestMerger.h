#ifndef AAPT_MANIFEST_MERGER_H
#define AAPT_MANIFEST_MERGER_H

#include "Logger.h"
#include "Source.h"
#include "XmlDom.h"

#include <memory>
#include <string>

namespace aapt {

class ManifestMerger {
public:
    struct Options {
    };

    ManifestMerger(const Options& options);

    bool setAppManifest(const Source& source, const std::u16string& package,
                        std::unique_ptr<xml::Node> root);

    bool mergeLibraryManifest(const Source& source, const std::u16string& package,
                              std::unique_ptr<xml::Node> libRoot);

    xml::Node* getMergedXml();

    bool printMerged();

private:
    bool mergeNewOrEqual(xml::Element* parentA, xml::Element* elA, xml::Element* elB);
    bool mergePreferRequired(xml::Element* parentA, xml::Element* elA, xml::Element* elB);
    bool checkEqual(xml::Element* elA, xml::Element* elB);
    bool mergeApplication(xml::Element* applicationA, xml::Element* applicationB);
    bool mergeUsesSdk(xml::Element* elA, xml::Element* elB);

    Options mOptions;
    std::unique_ptr<xml::Node> mRoot;
    SourceLogger mAppLogger;
    SourceLogger mLogger;
};

} // namespace aapt

#endif // AAPT_MANIFEST_MERGER_H
