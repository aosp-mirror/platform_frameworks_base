// Copyright 2022 Google LLC

// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at

//     http://www.apache.org/licenses/LICENSE-2.0

// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

type IElementComment =
    | { commentNode: undefined; textContent: undefined; hidden: undefined }
    | { commentNode: Node; textContent: string; hidden: boolean };

interface ITag {
    attrs?: Record<string, string | number>;
    tagName: string;
}

export interface INewTag extends ITag {
    content?: string | number;
    comment?: string;
}

export type IUpdateTag = Partial<Omit<INewTag, 'tagName'>>;

export default class DOM {
    static addEntry(containerElement: Element, tagOptions: INewTag) {
        const doc = containerElement.ownerDocument;
        const exists = this.alreadyHasEntry(containerElement, tagOptions);

        if (exists) {
            console.log('Ignored adding entry already available: ', exists.outerHTML);
            return;
        }

        let insertPoint: Node | null = containerElement.lastElementChild; //.childNodes[containerElement.childNodes.length - 1];

        if (!insertPoint) {
            console.log('Ignored adding entry in empity parent: ', containerElement.outerHTML);
            return;
        }

        const { attrs, comment, content, tagName } = tagOptions;

        if (comment) {
            const commentNode = doc.createComment(comment);
            this.insertAfterIdented(commentNode, insertPoint);
            insertPoint = commentNode;
        }

        const newEl = doc.createElement(tagName);
        if (content) newEl.innerHTML = content.toString();
        if (attrs)
            Object.entries(attrs).forEach(([attr, value]) =>
                newEl.setAttribute(attr, value.toString())
            );
        this.insertAfterIdented(newEl, insertPoint);

        return true;
    }

    static insertBeforeIndented(newNode: Node, referenceNode: Node) {
        const paddingNode = referenceNode.previousSibling;
        const ownerDoc = referenceNode.ownerDocument;
        const containerNode = referenceNode.parentNode;

        if (!paddingNode || !ownerDoc || !containerNode) return;

        const currentPadding = paddingNode.textContent || '';
        const textNode = referenceNode.ownerDocument.createTextNode(currentPadding);

        containerNode.insertBefore(newNode, referenceNode);
        containerNode.insertBefore(textNode, newNode);
    }

    static insertAfterIdented(newNode: Node, referenceNode: Node) {
        const paddingNode = referenceNode.previousSibling;
        const ownerDoc = referenceNode.ownerDocument;
        const containerNode = referenceNode.parentNode;

        if (!paddingNode || !ownerDoc || !containerNode) return;

        const currentPadding = paddingNode.textContent || '';
        const textNode = ownerDoc.createTextNode(currentPadding);

        containerNode.insertBefore(newNode, referenceNode.nextSibling);
        containerNode.insertBefore(textNode, newNode);
    }

    static getElementComment(el: Element): IElementComment {
        const commentNode = el.previousSibling?.previousSibling;

        const out = { commentNode: undefined, textContent: undefined, hidden: undefined };

        if (!commentNode) return out;

        const textContent = commentNode.textContent || '';
        const hidden = textContent.substring(textContent.length - 6) == '@hide ';

        if (!(commentNode && commentNode.nodeName == '#comment')) return out;

        return { commentNode, textContent, hidden: hidden };
    }

    static duplicateEntryWithChange(
        templateElement: Element,
        options: Omit<IUpdateTag, 'content'>
    ) {
        const exists = this.futureEntryAlreadyExist(templateElement, options);
        if (exists) {
            console.log('Ignored duplicating entry already available: ', exists.outerHTML);
            return;
        }

        const { commentNode } = this.getElementComment(templateElement);
        let insertPoint: Node = templateElement;

        if (commentNode) {
            const newComment = commentNode.cloneNode();
            this.insertAfterIdented(newComment, insertPoint);
            insertPoint = newComment;
        }

        const newEl = templateElement.cloneNode(true) as Element;
        this.insertAfterIdented(newEl, insertPoint);

        this.updateElement(newEl, options);
        return true;
    }

    static replaceStringInAttributeValueOnQueried(
        root: Element,
        query: string,
        attrArray: string[],
        replaceMap: Map<string, string>
    ): boolean {
        let updated = false;
        const queried = [...Array.from(root.querySelectorAll(query)), root];

        queried.forEach((el) => {
            attrArray.forEach((attr) => {
                if (el.hasAttribute(attr)) {
                    const currentAttrValue = el.getAttribute(attr);

                    if (!currentAttrValue) return;

                    [...replaceMap.entries()].some(([oldStr, newStr]) => {
                        if (
                            currentAttrValue.length >= oldStr.length &&
                            currentAttrValue.indexOf(oldStr) ==
                                currentAttrValue.length - oldStr.length
                        ) {
                            el.setAttribute(attr, currentAttrValue.replace(oldStr, newStr));
                            updated = true;
                            return true;
                        }
                        return false;
                    });
                }
            });
        });

        return updated;
    }

    static updateElement(el: Element, updateOptions: IUpdateTag) {
        const exists = this.futureEntryAlreadyExist(el, updateOptions);
        if (exists) {
            console.log('Ignored updating entry already available: ', exists.outerHTML);
            return;
        }

        const { comment, attrs, content } = updateOptions;

        if (comment) {
            const { commentNode } = this.getElementComment(el);
            if (commentNode) {
                commentNode.textContent = comment;
            }
        }

        if (attrs) {
            for (const attr in attrs) {
                const value = attrs[attr];

                if (value != undefined) {
                    el.setAttribute(attr, `${value}`);
                } else {
                    el.removeAttribute(attr);
                }
            }
        }

        if (content != undefined) {
            el.innerHTML = `${content}`;
        }

        return true;
    }

    static elementToOptions(el: Element): ITag {
        return {
            attrs: this.getAllElementAttributes(el),
            tagName: el.tagName,
        };
    }

    static getAllElementAttributes(el: Element): Record<string, string> {
        return el
            .getAttributeNames()
            .reduce(
                (acc, attr) => ({ ...acc, [attr]: el.getAttribute(attr) || '' }),
                {} as Record<string, string>
            );
    }

    static futureEntryAlreadyExist(el: Element, updateOptions: IUpdateTag) {
        const currentElOptions = this.elementToOptions(el);

        if (!el.parentElement) {
            console.log('Checked el has no parent');
            process.exit();
        }

        return this.alreadyHasEntry(el.parentElement, {
            ...currentElOptions,
            ...updateOptions,
            attrs: { ...currentElOptions.attrs, ...updateOptions.attrs },
        });
    }

    static alreadyHasEntry(
        containerElement: Element,
        { attrs, tagName }: Pick<INewTag, 'attrs' | 'tagName'>
    ) {
        const qAttrs = attrs
            ? Object.entries(attrs)
                  .map(([a, v]) => `[${a}="${v}"]`)
                  .join('')
            : '';

        return containerElement.querySelector(tagName + qAttrs);
    }

    static replaceContentTextOnQueried(
        root: Element,
        query: string,
        replacePairs: Array<[string, string]>
    ) {
        let updated = false;
        let queried = Array.from(root.querySelectorAll(query));

        if (queried.length == 0) queried = [...Array.from(root.querySelectorAll(query)), root];

        queried.forEach((el) => {
            replacePairs.forEach(([oldStr, newStr]) => {
                if (el.innerHTML == oldStr) {
                    el.innerHTML = newStr;
                    updated = true;
                }
            });
        });

        return updated;
    }

    static XMLDocToString(doc: XMLDocument) {
        let str = '';

        doc.childNodes.forEach((node) => {
            switch (node.nodeType) {
                case 8: // comment
                    str += `<!--${node.nodeValue}-->\n`;
                    break;

                case 3: // text
                    str += node.textContent;
                    break;

                case 1: // element
                    str += (node as Element).outerHTML;
                    break;

                default:
                    console.log('Unhandled node type: ' + node.nodeType);
                    break;
            }
        });

        return str;
    }
}
