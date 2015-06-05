#include "ManifestMerger.h"
#include "Maybe.h"
#include "ResourceParser.h"
#include "Source.h"
#include "Util.h"
#include "XmlPullParser.h"

#include <iostream>
#include <memory>
#include <set>
#include <string>

namespace aapt {

constexpr const char16_t* kSchemaAndroid = u"http://schemas.android.com/apk/res/android";

static xml::Element* findManifest(xml::Node* root) {
    if (!root) {
        return nullptr;
    }

    while (root->type == xml::NodeType::kNamespace) {
        if (root->children.empty()) {
            break;
        }
        root = root->children[0].get();
    }

    if (root && root->type == xml::NodeType::kElement) {
        xml::Element* el = static_cast<xml::Element*>(root);
        if (el->namespaceUri.empty() && el->name == u"manifest") {
            return el;
        }
    }
    return nullptr;
}

static xml::Element* findChildWithSameName(xml::Element* parent, xml::Element* src) {
    xml::Attribute* attrKey = src->findAttribute(kSchemaAndroid, u"name");
    if (!attrKey) {
        return nullptr;
    }
    return parent->findChildWithAttribute(src->namespaceUri, src->name, attrKey);
}

static bool attrLess(const xml::Attribute& lhs, const xml::Attribute& rhs) {
    return std::tie(lhs.namespaceUri, lhs.name, lhs.value)
            < std::tie(rhs.namespaceUri, rhs.name, rhs.value);
}

static int compare(xml::Element* lhs, xml::Element* rhs) {
    int diff = lhs->attributes.size() - rhs->attributes.size();
    if (diff != 0) {
        return diff;
    }

    std::set<xml::Attribute, decltype(&attrLess)> lhsAttrs(&attrLess);
    lhsAttrs.insert(lhs->attributes.begin(), lhs->attributes.end());
    for (auto& attr : rhs->attributes) {
        if (lhsAttrs.erase(attr) == 0) {
            // The rhs attribute is not in the left.
            return -1;
        }
    }

    if (!lhsAttrs.empty()) {
        // The lhs has attributes not in the rhs.
        return 1;
    }
    return 0;
}

ManifestMerger::ManifestMerger(const Options& options) :
        mOptions(options), mAppLogger({}), mLogger({}) {
}

bool ManifestMerger::setAppManifest(const Source& source, const std::u16string& package,
                                    std::unique_ptr<xml::Node> root) {

    mAppLogger = SourceLogger{ source };
    mRoot = std::move(root);
    return true;
}

bool ManifestMerger::checkEqual(xml::Element* elA, xml::Element* elB) {
    if (compare(elA, elB) != 0) {
        mLogger.error(elB->lineNumber)
                << "library tag '" << elB->name << "' conflicts with app tag."
                << std::endl;
        mAppLogger.note(elA->lineNumber)
                << "app tag '" << elA->name << "' defined here."
                << std::endl;
        return false;
    }

    std::vector<xml::Element*> childrenA = elA->getChildElements();
    std::vector<xml::Element*> childrenB = elB->getChildElements();

    if (childrenA.size() != childrenB.size()) {
        mLogger.error(elB->lineNumber)
                << "library tag '" << elB->name << "' children conflict with app tag."
                << std::endl;
        mAppLogger.note(elA->lineNumber)
                << "app tag '" << elA->name << "' defined here."
                << std::endl;
        return false;
    }

    auto cmp = [](xml::Element* lhs, xml::Element* rhs) -> bool {
        return compare(lhs, rhs) < 0;
    };

    std::sort(childrenA.begin(), childrenA.end(), cmp);
    std::sort(childrenB.begin(), childrenB.end(), cmp);

    for (size_t i = 0; i < childrenA.size(); i++) {
        if (!checkEqual(childrenA[i], childrenB[i])) {
            return false;
        }
    }
    return true;
}

bool ManifestMerger::mergeNewOrEqual(xml::Element* parentA, xml::Element* elA, xml::Element* elB) {
    if (!elA) {
        parentA->addChild(elB->clone());
        return true;
    }
    return checkEqual(elA, elB);
}

bool ManifestMerger::mergePreferRequired(xml::Element* parentA, xml::Element* elA,
                                         xml::Element* elB) {
    if (!elA) {
        parentA->addChild(elB->clone());
        return true;
    }

    xml::Attribute* reqA = elA->findAttribute(kSchemaAndroid, u"required");
    xml::Attribute* reqB = elB->findAttribute(kSchemaAndroid, u"required");
    bool requiredA = !reqA || (reqA->value != u"false" && reqA->value != u"FALSE");
    bool requiredB = !reqB || (reqB->value != u"false" && reqB->value != u"FALSE");
    if (!requiredA && requiredB) {
        if (reqA) {
            *reqA = xml::Attribute{ kSchemaAndroid, u"required", u"true" };
        } else {
            elA->attributes.push_back(xml::Attribute{ kSchemaAndroid, u"required", u"true" });
        }
    }
    return true;
}

static int findIntegerValue(xml::Attribute* attr, int defaultValue) {
    if (attr) {
        std::unique_ptr<BinaryPrimitive> integer = ResourceParser::tryParseInt(attr->value);
        if (integer) {
            return integer->value.data;
        }
    }
    return defaultValue;
}

bool ManifestMerger::mergeUsesSdk(xml::Element* elA, xml::Element* elB) {
    bool error = false;
    xml::Attribute* minAttrA = nullptr;
    xml::Attribute* minAttrB = nullptr;
    if (elA) {
        minAttrA = elA->findAttribute(kSchemaAndroid, u"minSdkVersion");
    }

    if (elB) {
        minAttrB = elB->findAttribute(kSchemaAndroid, u"minSdkVersion");
    }

    int minSdkA = findIntegerValue(minAttrA, 1);
    int minSdkB = findIntegerValue(minAttrB, 1);

    if (minSdkA < minSdkB) {
        std::ostream* out;
        if (minAttrA) {
            out = &(mAppLogger.error(elA->lineNumber) << "app declares ");
        } else if (elA) {
            out = &(mAppLogger.error(elA->lineNumber) << "app has implied ");
        } else {
            out = &(mAppLogger.error() << "app has implied ");
        }

        *out << "minSdkVersion=" << minSdkA << " but library expects a higher SDK version."
             << std::endl;

        // elB is valid because minSdkB wouldn't be greater than minSdkA if it wasn't.
        mLogger.note(elB->lineNumber)
                << "library declares minSdkVersion=" << minSdkB << "."
                << std::endl;
        error = true;
    }

    xml::Attribute* targetAttrA = nullptr;
    xml::Attribute* targetAttrB = nullptr;

    if (elA) {
        targetAttrA = elA->findAttribute(kSchemaAndroid, u"targetSdkVersion");
    }

    if (elB) {
        targetAttrB = elB->findAttribute(kSchemaAndroid, u"targetSdkVersion");
    }

    int targetSdkA = findIntegerValue(targetAttrA, minSdkA);
    int targetSdkB = findIntegerValue(targetAttrB, minSdkB);

    if (targetSdkA < targetSdkB) {
        std::ostream* out;
        if (targetAttrA) {
            out = &(mAppLogger.warn(elA->lineNumber) << "app declares ");
        } else if (elA) {
            out = &(mAppLogger.warn(elA->lineNumber) << "app has implied ");
        } else {
            out = &(mAppLogger.warn() << "app has implied ");
        }

        *out << "targetSdkVerion=" << targetSdkA << " but library expects target SDK "
             << targetSdkB << "." << std::endl;

        mLogger.note(elB->lineNumber)
                << "library declares targetSdkVersion=" << targetSdkB << "."
                << std::endl;
        error = true;
    }
    return !error;
}

bool ManifestMerger::mergeApplication(xml::Element* applicationA, xml::Element* applicationB) {
    if (!applicationA || !applicationB) {
        return true;
    }

    bool error = false;

    // First make sure that the names are identical.
    xml::Attribute* nameA = applicationA->findAttribute(kSchemaAndroid, u"name");
    xml::Attribute* nameB = applicationB->findAttribute(kSchemaAndroid, u"name");
    if (nameB) {
        if (!nameA) {
            applicationA->attributes.push_back(*nameB);
        } else if (nameA->value != nameB->value) {
            mLogger.error(applicationB->lineNumber)
                    << "conflicting application name '"
                    << nameB->value
                    << "'." << std::endl;
            mAppLogger.note(applicationA->lineNumber)
                    << "application defines application name '"
                    << nameA->value
                    << "'." << std::endl;
            error = true;
        }
    }

    // Now we descend into the activity/receiver/service/provider tags
    for (xml::Element* elB : applicationB->getChildElements()) {
        if (!elB->namespaceUri.empty()) {
            continue;
        }

        if (elB->name == u"activity" || elB->name == u"activity-alias"
                || elB->name == u"service" || elB->name == u"receiver"
                || elB->name == u"provider" || elB->name == u"meta-data") {
            xml::Element* elA = findChildWithSameName(applicationA, elB);
            error |= !mergeNewOrEqual(applicationA, elA, elB);
        } else if (elB->name == u"uses-library") {
            xml::Element* elA = findChildWithSameName(applicationA, elB);
            error |= !mergePreferRequired(applicationA, elA, elB);
        }
    }
    return !error;
}

bool ManifestMerger::mergeLibraryManifest(const Source& source, const std::u16string& package,
                                          std::unique_ptr<xml::Node> libRoot) {
    mLogger = SourceLogger{ source };
    xml::Element* manifestA = findManifest(mRoot.get());
    xml::Element* manifestB = findManifest(libRoot.get());
    if (!manifestA) {
        mAppLogger.error() << "missing manifest tag." << std::endl;
        return false;
    }

    if (!manifestB) {
        mLogger.error() << "library missing manifest tag." << std::endl;
        return false;
    }

    bool error = false;

    // Do <application> first.
    xml::Element* applicationA = manifestA->findChild({}, u"application");
    xml::Element* applicationB = manifestB->findChild({}, u"application");
    error |= !mergeApplication(applicationA, applicationB);

    // Do <uses-sdk> next.
    xml::Element* usesSdkA = manifestA->findChild({}, u"uses-sdk");
    xml::Element* usesSdkB = manifestB->findChild({}, u"uses-sdk");
    error |= !mergeUsesSdk(usesSdkA, usesSdkB);

    for (xml::Element* elB : manifestB->getChildElements()) {
        if (!elB->namespaceUri.empty()) {
            continue;
        }

        if (elB->name == u"uses-permission" || elB->name == u"permission"
                || elB->name == u"permission-group" || elB->name == u"permission-tree") {
            xml::Element* elA = findChildWithSameName(manifestA, elB);
            error |= !mergeNewOrEqual(manifestA, elA, elB);
        } else if (elB->name == u"uses-feature") {
            xml::Element* elA = findChildWithSameName(manifestA, elB);
            error |= !mergePreferRequired(manifestA, elA, elB);
        } else if (elB->name == u"uses-configuration" || elB->name == u"supports-screen"
                || elB->name == u"compatible-screens" || elB->name == u"supports-gl-texture") {
            xml::Element* elA = findChildWithSameName(manifestA, elB);
            error |= !checkEqual(elA, elB);
        }
    }
    return !error;
}

static void printMerged(xml::Node* node, int depth) {
    std::string indent;
    for (int i = 0; i < depth; i++) {
        indent += "  ";
    }

    switch (node->type) {
        case xml::NodeType::kNamespace:
            std::cerr << indent << "N: "
                      << "xmlns:" << static_cast<xml::Namespace*>(node)->namespacePrefix
                      << "=\"" << static_cast<xml::Namespace*>(node)->namespaceUri
                      << "\"\n";
            break;

        case xml::NodeType::kElement:
            std::cerr << indent << "E: "
                      << static_cast<xml::Element*>(node)->namespaceUri
                      << ":" << static_cast<xml::Element*>(node)->name
                      << "\n";
            for (const auto& attr : static_cast<xml::Element*>(node)->attributes) {
                std::cerr << indent << "  A: "
                          << attr.namespaceUri
                          << ":" << attr.name
                          << "=\"" << attr.value << "\"\n";
            }
            break;

        case xml::NodeType::kText:
            std::cerr << indent << "T: \"" << static_cast<xml::Text*>(node)->text << "\"\n";
            break;
    }

    for (auto& child : node->children) {
        printMerged(child.get(), depth + 1);
    }
}

xml::Node* ManifestMerger::getMergedXml() {
    return mRoot.get();
}

bool ManifestMerger::printMerged() {
    if (!mRoot) {
        return false;
    }

    ::aapt::printMerged(mRoot.get(), 0);
    return true;
}

} // namespace aapt
