/*
 * Copyright (C) 2005 The Android Open Source Project
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

//
// Templated list class.  Normally we'd use STL, but we don't have that.
// This class mimics STL's interfaces.
//
// Objects are copied into the list with the '=' operator or with copy-
// construction, so if the compiler's auto-generated versions won't work for
// you, define your own.
//
// The only class you want to use from here is "List".  Do not use classes
// starting with "_" directly.
//
#ifndef _LIBS_UTILS_LIST_H
#define _LIBS_UTILS_LIST_H

namespace android {

/*
 * One element in the list.
 */
template<class T> class _ListNode {
public:
    typedef _ListNode<T> _Node;

    _ListNode(const T& val) : mVal(val) {}
    ~_ListNode(void) {}

    T& getRef(void) { return mVal; }
    void setVal(const T& val) { mVal = val; }

    _Node* getPrev(void) const { return mpPrev; }
    void setPrev(_Node* ptr) { mpPrev = ptr; }
    _Node* getNext(void) const { return mpNext; }
    void setNext(_Node* ptr) { mpNext = ptr; }

private:
    T           mVal;
    _Node*      mpPrev;
    _Node*      mpNext;
};

/*
 * Iterator for walking through the list.
 */
template<class T, class Tref> class _ListIterator {
public:
    typedef _ListIterator<T,Tref> _Iter;
    typedef _ListNode<T> _Node;

    _ListIterator(void) {}
    _ListIterator(_Node* ptr) : mpNode(ptr) {}
    ~_ListIterator(void) {}

    /*
     * Dereference operator.  Used to get at the juicy insides.
     */
    Tref operator*() const { return mpNode->getRef(); }

    /*
     * Iterator comparison.
     */
    bool operator==(const _Iter& right) const { return mpNode == right.mpNode; }
    bool operator!=(const _Iter& right) const { return mpNode != right.mpNode; }

    /*
     * Incr/decr, used to move through the list.
     */
    _Iter& operator++(void) {        // pre-increment
        mpNode = mpNode->getNext();
        return *this;
    }
    _Iter operator++(int) {          // post-increment
        _Iter tmp = *this;
        ++*this;
        return tmp;
    }
    _Iter& operator--(void) {        // pre-increment
        mpNode = mpNode->getPrev();
        return *this;
    }
    _Iter operator--(int) {          // post-increment
        _Iter tmp = *this;
        --*this;
        return tmp;
    }

    _Node* getNode(void) const { return mpNode; }

private:
    _Node*      mpNode;
};


/*
 * Doubly-linked list.  Instantiate with "List<MyClass> myList".
 *
 * Objects added to the list are copied using the assignment operator,
 * so this must be defined.
 */
template<class T> class List {
public:
    typedef _ListNode<T> _Node;

    List(void) {
        prep();
    }
    List(const List<T>& src) {      // copy-constructor
        prep();
        insert(begin(), src.begin(), src.end());
    }
    virtual ~List(void) {
        clear();
        delete[] (unsigned char*) mpMiddle;
    }

    typedef _ListIterator<T,T&> iterator;
    typedef _ListIterator<T, const T&> const_iterator;

    List<T>& operator=(const List<T>& right);

    /* returns true if the list is empty */
    bool empty(void) const { return mpMiddle->getNext() == mpMiddle; }

    /* return #of elements in list */
    unsigned int size(void) const {
        return distance(begin(), end());
    }

    /*
     * Return the first element or one past the last element.  The
     * _ListNode* we're returning is converted to an "iterator" by a
     * constructor in _ListIterator.
     */
    iterator begin()                { return mpMiddle->getNext(); }
    const_iterator begin() const    { return mpMiddle->getNext(); }
    iterator end()                  { return mpMiddle; }
    const_iterator end() const      { return mpMiddle; }

    /* add the object to the head or tail of the list */
    void push_front(const T& val) { insert(begin(), val); }
    void push_back(const T& val) { insert(end(), val); }

    /* insert before the current node; returns iterator at new node */
    iterator insert(iterator posn, const T& val) {
        _Node* newNode = new _Node(val);        // alloc & copy-construct
        newNode->setNext(posn.getNode());
        newNode->setPrev(posn.getNode()->getPrev());
        posn.getNode()->getPrev()->setNext(newNode);
        posn.getNode()->setPrev(newNode);
        return newNode;
    }

    /* insert a range of elements before the current node */
    void insert(iterator posn, const_iterator first, const_iterator last) {
        for ( ; first != last; ++first)
            insert(posn, *first);
    }

    /* remove one entry; returns iterator at next node */
    iterator erase(iterator posn) {
        _Node* pNext = posn.getNode()->getNext();
        _Node* pPrev = posn.getNode()->getPrev();
        pPrev->setNext(pNext);
        pNext->setPrev(pPrev);
        delete posn.getNode();
        return pNext;
    }

    /* remove a range of elements */
    iterator erase(iterator first, iterator last) {
        while (first != last)
            erase(first++);     // don't erase than incr later!
        return last;
    }

    /* remove all contents of the list */
    void clear(void) {
        _Node* pCurrent = mpMiddle->getNext();
        _Node* pNext;

        while (pCurrent != mpMiddle) {
            pNext = pCurrent->getNext();
            delete pCurrent;
            pCurrent = pNext;
        }
        mpMiddle->setPrev(mpMiddle);
        mpMiddle->setNext(mpMiddle);
    }

    /*
     * Measure the distance between two iterators.  On exist, "first"
     * will be equal to "last".  The iterators must refer to the same
     * list.
     *
     * (This is actually a generic iterator function.  It should be part
     * of some other class, possibly an iterator base class.  It needs to
     * know the difference between a list, which has to march through,
     * and a vector, which can just do pointer math.)
     */
    unsigned int distance(iterator first, iterator last) {
        unsigned int count = 0;
        while (first != last) {
            ++first;
            ++count;
        }
        return count;
    }
    unsigned int distance(const_iterator first, const_iterator last) const {
        unsigned int count = 0;
        while (first != last) {
            ++first;
            ++count;
        }
        return count;
    }

private:
    /*
     * I want a _ListNode but don't need it to hold valid data.  More
     * to the point, I don't want T's constructor to fire, since it
     * might have side-effects or require arguments.  So, we do this
     * slightly uncouth storage alloc.
     */
    void prep(void) {
        mpMiddle = (_Node*) new unsigned char[sizeof(_Node)];
        mpMiddle->setPrev(mpMiddle);
        mpMiddle->setNext(mpMiddle);
    }

    /*
     * This node plays the role of "pointer to head" and "pointer to tail".
     * It sits in the middle of a circular list of nodes.  The iterator
     * runs around the circle until it encounters this one.
     */
    _Node*      mpMiddle;
};

/*
 * Assignment operator.
 *
 * The simplest way to do this would be to clear out the target list and
 * fill it with the source.  However, we can speed things along by
 * re-using existing elements.
 */
template<class T>
List<T>& List<T>::operator=(const List<T>& right)
{
    if (this == &right)
        return *this;       // self-assignment
    iterator firstDst = begin();
    iterator lastDst = end();
    const_iterator firstSrc = right.begin();
    const_iterator lastSrc = right.end();
    while (firstSrc != lastSrc && firstDst != lastDst)
        *firstDst++ = *firstSrc++;
    if (firstSrc == lastSrc)        // ran out of elements in source?
        erase(firstDst, lastDst);   // yes, erase any extras
    else
        insert(lastDst, firstSrc, lastSrc);     // copy remaining over
    return *this;
}

}; // namespace android

#endif // _LIBS_UTILS_LIST_H
