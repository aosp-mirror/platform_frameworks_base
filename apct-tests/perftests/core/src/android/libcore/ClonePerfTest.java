/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.libcore;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class ClonePerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    static class CloneableObject implements Cloneable {
        public Object clone() throws CloneNotSupportedException {
            return super.clone();
        }
    }

    static class CloneableManyFieldObject implements Cloneable {
        public Object clone() throws CloneNotSupportedException {
            return super.clone();
        }

        Object mO1 = new Object();
        Object mO2 = new Object();
        Object mO3 = new Object();
        Object mO4 = new Object();
        Object mO5 = new Object();
        Object mO6 = new Object();
        Object mO7 = new Object();
        Object mO8 = new Object();
        Object mO9 = new Object();
        Object mO10 = new Object();
        Object mO11 = new Object();
        Object mO12 = new Object();
        Object mO13 = new Object();
        Object mO14 = new Object();
        Object mO15 = new Object();
        Object mO16 = new Object();
        Object mO17 = new Object();
        Object mO18 = new Object();
        Object mO19 = new Object();
        Object mO20 = new Object();
        Object mO21 = new Object();
        Object mO22 = new Object();
        Object mO23 = new Object();
        Object mO24 = new Object();
        Object mO25 = new Object();
        Object mO26 = new Object();
        Object mO27 = new Object();
        Object mO28 = new Object();
        Object mO29 = new Object();
        Object mO30 = new Object();
        Object mO31 = new Object();
        Object mO32 = new Object();
        Object mO33 = new Object();
        Object mO34 = new Object();
        Object mO35 = new Object();
        Object mO36 = new Object();
        Object mO37 = new Object();
        Object mO38 = new Object();
        Object mO39 = new Object();
        Object mO40 = new Object();
        Object mO41 = new Object();
        Object mO42 = new Object();
        Object mO43 = new Object();
        Object mO44 = new Object();
        Object mO45 = new Object();
        Object mO46 = new Object();
        Object mO47 = new Object();
        Object mO48 = new Object();
        Object mO49 = new Object();
        Object mO50 = new Object();
        Object mO51 = new Object();
        Object mO52 = new Object();
        Object mO53 = new Object();
        Object mO54 = new Object();
        Object mO55 = new Object();
        Object mO56 = new Object();
        Object mO57 = new Object();
        Object mO58 = new Object();
        Object mO59 = new Object();
        Object mO60 = new Object();
        Object mO61 = new Object();
        Object mO62 = new Object();
        Object mO63 = new Object();
        Object mO64 = new Object();
        Object mO65 = new Object();
        Object mO66 = new Object();
        Object mO67 = new Object();
        Object mO68 = new Object();
        Object mO69 = new Object();
        Object mO70 = new Object();
        Object mO71 = new Object();
        Object mO72 = new Object();
        Object mO73 = new Object();
        Object mO74 = new Object();
        Object mO75 = new Object();
        Object mO76 = new Object();
        Object mO77 = new Object();
        Object mO78 = new Object();
        Object mO79 = new Object();
        Object mO80 = new Object();
        Object mO81 = new Object();
        Object mO82 = new Object();
        Object mO83 = new Object();
        Object mO84 = new Object();
        Object mO85 = new Object();
        Object mO86 = new Object();
        Object mO87 = new Object();
        Object mO88 = new Object();
        Object mO89 = new Object();
        Object mO90 = new Object();
        Object mO91 = new Object();
        Object mO92 = new Object();
        Object mO93 = new Object();
        Object mO94 = new Object();
        Object mO95 = new Object();
        Object mO96 = new Object();
        Object mO97 = new Object();
        Object mO98 = new Object();
        Object mO99 = new Object();
        Object mO100 = new Object();
        Object mO101 = new Object();
        Object mO102 = new Object();
        Object mO103 = new Object();
        Object mO104 = new Object();
        Object mO105 = new Object();
        Object mO106 = new Object();
        Object mO107 = new Object();
        Object mO108 = new Object();
        Object mO109 = new Object();
        Object mO110 = new Object();
        Object mO111 = new Object();
        Object mO112 = new Object();
        Object mO113 = new Object();
        Object mO114 = new Object();
        Object mO115 = new Object();
        Object mO116 = new Object();
        Object mO117 = new Object();
        Object mO118 = new Object();
        Object mO119 = new Object();
        Object mO120 = new Object();
        Object mO121 = new Object();
        Object mO122 = new Object();
        Object mO123 = new Object();
        Object mO124 = new Object();
        Object mO125 = new Object();
        Object mO126 = new Object();
        Object mO127 = new Object();
        Object mO128 = new Object();
        Object mO129 = new Object();
        Object mO130 = new Object();
        Object mO131 = new Object();
        Object mO132 = new Object();
        Object mO133 = new Object();
        Object mO134 = new Object();
        Object mO135 = new Object();
        Object mO136 = new Object();
        Object mO137 = new Object();
        Object mO138 = new Object();
        Object mO139 = new Object();
        Object mO140 = new Object();
        Object mO141 = new Object();
        Object mO142 = new Object();
        Object mO143 = new Object();
        Object mO144 = new Object();
        Object mO145 = new Object();
        Object mO146 = new Object();
        Object mO147 = new Object();
        Object mO148 = new Object();
        Object mO149 = new Object();
        Object mO150 = new Object();
        Object mO151 = new Object();
        Object mO152 = new Object();
        Object mO153 = new Object();
        Object mO154 = new Object();
        Object mO155 = new Object();
        Object mO156 = new Object();
        Object mO157 = new Object();
        Object mO158 = new Object();
        Object mO159 = new Object();
        Object mO160 = new Object();
        Object mO161 = new Object();
        Object mO162 = new Object();
        Object mO163 = new Object();
        Object mO164 = new Object();
        Object mO165 = new Object();
        Object mO166 = new Object();
        Object mO167 = new Object();
        Object mO168 = new Object();
        Object mO169 = new Object();
        Object mO170 = new Object();
        Object mO171 = new Object();
        Object mO172 = new Object();
        Object mO173 = new Object();
        Object mO174 = new Object();
        Object mO175 = new Object();
        Object mO176 = new Object();
        Object mO177 = new Object();
        Object mO178 = new Object();
        Object mO179 = new Object();
        Object mO180 = new Object();
        Object mO181 = new Object();
        Object mO182 = new Object();
        Object mO183 = new Object();
        Object mO184 = new Object();
        Object mO185 = new Object();
        Object mO186 = new Object();
        Object mO187 = new Object();
        Object mO188 = new Object();
        Object mO189 = new Object();
        Object mO190 = new Object();
        Object mO191 = new Object();
        Object mO192 = new Object();
        Object mO193 = new Object();
        Object mO194 = new Object();
        Object mO195 = new Object();
        Object mO196 = new Object();
        Object mO197 = new Object();
        Object mO198 = new Object();
        Object mO199 = new Object();
        Object mO200 = new Object();
        Object mO201 = new Object();
        Object mO202 = new Object();
        Object mO203 = new Object();
        Object mO204 = new Object();
        Object mO205 = new Object();
        Object mO206 = new Object();
        Object mO207 = new Object();
        Object mO208 = new Object();
        Object mO209 = new Object();
        Object mO210 = new Object();
        Object mO211 = new Object();
        Object mO212 = new Object();
        Object mO213 = new Object();
        Object mO214 = new Object();
        Object mO215 = new Object();
        Object mO216 = new Object();
        Object mO217 = new Object();
        Object mO218 = new Object();
        Object mO219 = new Object();
        Object mO220 = new Object();
        Object mO221 = new Object();
        Object mO222 = new Object();
        Object mO223 = new Object();
        Object mO224 = new Object();
        Object mO225 = new Object();
        Object mO226 = new Object();
        Object mO227 = new Object();
        Object mO228 = new Object();
        Object mO229 = new Object();
        Object mO230 = new Object();
        Object mO231 = new Object();
        Object mO232 = new Object();
        Object mO233 = new Object();
        Object mO234 = new Object();
        Object mO235 = new Object();
        Object mO236 = new Object();
        Object mO237 = new Object();
        Object mO238 = new Object();
        Object mO239 = new Object();
        Object mO240 = new Object();
        Object mO241 = new Object();
        Object mO242 = new Object();
        Object mO243 = new Object();
        Object mO244 = new Object();
        Object mO245 = new Object();
        Object mO246 = new Object();
        Object mO247 = new Object();
        Object mO248 = new Object();
        Object mO249 = new Object();
        Object mO250 = new Object();
        Object mO251 = new Object();
        Object mO252 = new Object();
        Object mO253 = new Object();
        Object mO254 = new Object();
        Object mO255 = new Object();
        Object mO256 = new Object();
        Object mO257 = new Object();
        Object mO258 = new Object();
        Object mO259 = new Object();
        Object mO260 = new Object();
        Object mO261 = new Object();
        Object mO262 = new Object();
        Object mO263 = new Object();
        Object mO264 = new Object();
        Object mO265 = new Object();
        Object mO266 = new Object();
        Object mO267 = new Object();
        Object mO268 = new Object();
        Object mO269 = new Object();
        Object mO270 = new Object();
        Object mO271 = new Object();
        Object mO272 = new Object();
        Object mO273 = new Object();
        Object mO274 = new Object();
        Object mO275 = new Object();
        Object mO276 = new Object();
        Object mO277 = new Object();
        Object mO278 = new Object();
        Object mO279 = new Object();
        Object mO280 = new Object();
        Object mO281 = new Object();
        Object mO282 = new Object();
        Object mO283 = new Object();
        Object mO284 = new Object();
        Object mO285 = new Object();
        Object mO286 = new Object();
        Object mO287 = new Object();
        Object mO288 = new Object();
        Object mO289 = new Object();
        Object mO290 = new Object();
        Object mO291 = new Object();
        Object mO292 = new Object();
        Object mO293 = new Object();
        Object mO294 = new Object();
        Object mO295 = new Object();
        Object mO296 = new Object();
        Object mO297 = new Object();
        Object mO298 = new Object();
        Object mO299 = new Object();
        Object mO300 = new Object();
        Object mO301 = new Object();
        Object mO302 = new Object();
        Object mO303 = new Object();
        Object mO304 = new Object();
        Object mO305 = new Object();
        Object mO306 = new Object();
        Object mO307 = new Object();
        Object mO308 = new Object();
        Object mO309 = new Object();
        Object mO310 = new Object();
        Object mO311 = new Object();
        Object mO312 = new Object();
        Object mO313 = new Object();
        Object mO314 = new Object();
        Object mO315 = new Object();
        Object mO316 = new Object();
        Object mO317 = new Object();
        Object mO318 = new Object();
        Object mO319 = new Object();
        Object mO320 = new Object();
        Object mO321 = new Object();
        Object mO322 = new Object();
        Object mO323 = new Object();
        Object mO324 = new Object();
        Object mO325 = new Object();
        Object mO326 = new Object();
        Object mO327 = new Object();
        Object mO328 = new Object();
        Object mO329 = new Object();
        Object mO330 = new Object();
        Object mO331 = new Object();
        Object mO332 = new Object();
        Object mO333 = new Object();
        Object mO334 = new Object();
        Object mO335 = new Object();
        Object mO336 = new Object();
        Object mO337 = new Object();
        Object mO338 = new Object();
        Object mO339 = new Object();
        Object mO340 = new Object();
        Object mO341 = new Object();
        Object mO342 = new Object();
        Object mO343 = new Object();
        Object mO344 = new Object();
        Object mO345 = new Object();
        Object mO346 = new Object();
        Object mO347 = new Object();
        Object mO348 = new Object();
        Object mO349 = new Object();
        Object mO350 = new Object();
        Object mO351 = new Object();
        Object mO352 = new Object();
        Object mO353 = new Object();
        Object mO354 = new Object();
        Object mO355 = new Object();
        Object mO356 = new Object();
        Object mO357 = new Object();
        Object mO358 = new Object();
        Object mO359 = new Object();
        Object mO360 = new Object();
        Object mO361 = new Object();
        Object mO362 = new Object();
        Object mO363 = new Object();
        Object mO364 = new Object();
        Object mO365 = new Object();
        Object mO366 = new Object();
        Object mO367 = new Object();
        Object mO368 = new Object();
        Object mO369 = new Object();
        Object mO370 = new Object();
        Object mO371 = new Object();
        Object mO372 = new Object();
        Object mO373 = new Object();
        Object mO374 = new Object();
        Object mO375 = new Object();
        Object mO376 = new Object();
        Object mO377 = new Object();
        Object mO378 = new Object();
        Object mO379 = new Object();
        Object mO380 = new Object();
        Object mO381 = new Object();
        Object mO382 = new Object();
        Object mO383 = new Object();
        Object mO384 = new Object();
        Object mO385 = new Object();
        Object mO386 = new Object();
        Object mO387 = new Object();
        Object mO388 = new Object();
        Object mO389 = new Object();
        Object mO390 = new Object();
        Object mO391 = new Object();
        Object mO392 = new Object();
        Object mO393 = new Object();
        Object mO394 = new Object();
        Object mO395 = new Object();
        Object mO396 = new Object();
        Object mO397 = new Object();
        Object mO398 = new Object();
        Object mO399 = new Object();
        Object mO400 = new Object();
        Object mO401 = new Object();
        Object mO402 = new Object();
        Object mO403 = new Object();
        Object mO404 = new Object();
        Object mO405 = new Object();
        Object mO406 = new Object();
        Object mO407 = new Object();
        Object mO408 = new Object();
        Object mO409 = new Object();
        Object mO410 = new Object();
        Object mO411 = new Object();
        Object mO412 = new Object();
        Object mO413 = new Object();
        Object mO414 = new Object();
        Object mO415 = new Object();
        Object mO416 = new Object();
        Object mO417 = new Object();
        Object mO418 = new Object();
        Object mO419 = new Object();
        Object mO420 = new Object();
        Object mO421 = new Object();
        Object mO422 = new Object();
        Object mO423 = new Object();
        Object mO424 = new Object();
        Object mO425 = new Object();
        Object mO426 = new Object();
        Object mO427 = new Object();
        Object mO428 = new Object();
        Object mO429 = new Object();
        Object mO430 = new Object();
        Object mO431 = new Object();
        Object mO432 = new Object();
        Object mO433 = new Object();
        Object mO434 = new Object();
        Object mO435 = new Object();
        Object mO436 = new Object();
        Object mO437 = new Object();
        Object mO438 = new Object();
        Object mO439 = new Object();
        Object mO440 = new Object();
        Object mO441 = new Object();
        Object mO442 = new Object();
        Object mO460 = new Object();
        Object mO461 = new Object();
        Object mO462 = new Object();
        Object mO463 = new Object();
        Object mO464 = new Object();
        Object mO465 = new Object();
        Object mO466 = new Object();
        Object mO467 = new Object();
        Object mO468 = new Object();
        Object mO469 = new Object();
        Object mO470 = new Object();
        Object mO471 = new Object();
        Object mO472 = new Object();
        Object mO473 = new Object();
        Object mO474 = new Object();
        Object mO475 = new Object();
        Object mO476 = new Object();
        Object mO477 = new Object();
        Object mO478 = new Object();
        Object mO479 = new Object();
        Object mO480 = new Object();
        Object mO481 = new Object();
        Object mO482 = new Object();
        Object mO483 = new Object();
        Object mO484 = new Object();
        Object mO485 = new Object();
        Object mO486 = new Object();
        Object mO487 = new Object();
        Object mO488 = new Object();
        Object mO489 = new Object();
        Object mO490 = new Object();
        Object mO491 = new Object();
        Object mO492 = new Object();
        Object mO493 = new Object();
        Object mO494 = new Object();
        Object mO495 = new Object();
        Object mO496 = new Object();
        Object mO497 = new Object();
        Object mO498 = new Object();
        Object mO499 = new Object();
        Object mO500 = new Object();
        Object mO501 = new Object();
        Object mO502 = new Object();
        Object mO503 = new Object();
        Object mO504 = new Object();
        Object mO505 = new Object();
        Object mO506 = new Object();
        Object mO507 = new Object();
        Object mO508 = new Object();
        Object mO509 = new Object();
        Object mO510 = new Object();
        Object mO511 = new Object();
        Object mO512 = new Object();
        Object mO513 = new Object();
        Object mO514 = new Object();
        Object mO515 = new Object();
        Object mO516 = new Object();
        Object mO517 = new Object();
        Object mO518 = new Object();
        Object mO519 = new Object();
        Object mO520 = new Object();
        Object mO521 = new Object();
        Object mO522 = new Object();
        Object mO523 = new Object();
        Object mO556 = new Object();
        Object mO557 = new Object();
        Object mO558 = new Object();
        Object mO559 = new Object();
        Object mO560 = new Object();
        Object mO561 = new Object();
        Object mO562 = new Object();
        Object mO563 = new Object();
        Object mO564 = new Object();
        Object mO565 = new Object();
        Object mO566 = new Object();
        Object mO567 = new Object();
        Object mO568 = new Object();
        Object mO569 = new Object();
        Object mO570 = new Object();
        Object mO571 = new Object();
        Object mO572 = new Object();
        Object mO573 = new Object();
        Object mO574 = new Object();
        Object mO575 = new Object();
        Object mO576 = new Object();
        Object mO577 = new Object();
        Object mO578 = new Object();
        Object mO579 = new Object();
        Object mO580 = new Object();
        Object mO581 = new Object();
        Object mO582 = new Object();
        Object mO583 = new Object();
        Object mO584 = new Object();
        Object mO585 = new Object();
        Object mO586 = new Object();
        Object mO587 = new Object();
        Object mO588 = new Object();
        Object mO589 = new Object();
        Object mO590 = new Object();
        Object mO591 = new Object();
        Object mO592 = new Object();
        Object mO593 = new Object();
        Object mO594 = new Object();
        Object mO595 = new Object();
        Object mO596 = new Object();
        Object mO597 = new Object();
        Object mO598 = new Object();
        Object mO599 = new Object();
        Object mO600 = new Object();
        Object mO601 = new Object();
        Object mO602 = new Object();
        Object mO603 = new Object();
        Object mO604 = new Object();
        Object mO605 = new Object();
        Object mO606 = new Object();
        Object mO607 = new Object();
        Object mO608 = new Object();
        Object mO609 = new Object();
        Object mO610 = new Object();
        Object mO611 = new Object();
        Object mO612 = new Object();
        Object mO613 = new Object();
        Object mO614 = new Object();
        Object mO615 = new Object();
        Object mO616 = new Object();
        Object mO617 = new Object();
        Object mO618 = new Object();
        Object mO619 = new Object();
        Object mO620 = new Object();
        Object mO621 = new Object();
        Object mO622 = new Object();
        Object mO623 = new Object();
        Object mO624 = new Object();
        Object mO625 = new Object();
        Object mO626 = new Object();
        Object mO627 = new Object();
        Object mO628 = new Object();
        Object mO629 = new Object();
        Object mO630 = new Object();
        Object mO631 = new Object();
        Object mO632 = new Object();
        Object mO633 = new Object();
        Object mO634 = new Object();
        Object mO635 = new Object();
        Object mO636 = new Object();
        Object mO637 = new Object();
        Object mO638 = new Object();
        Object mO639 = new Object();
        Object mO640 = new Object();
        Object mO641 = new Object();
        Object mO642 = new Object();
        Object mO643 = new Object();
        Object mO644 = new Object();
        Object mO645 = new Object();
        Object mO646 = new Object();
        Object mO647 = new Object();
        Object mO648 = new Object();
        Object mO649 = new Object();
        Object mO650 = new Object();
        Object mO651 = new Object();
        Object mO652 = new Object();
        Object mO653 = new Object();
        Object mO654 = new Object();
        Object mO655 = new Object();
        Object mO656 = new Object();
        Object mO657 = new Object();
        Object mO658 = new Object();
        Object mO659 = new Object();
        Object mO660 = new Object();
        Object mO661 = new Object();
        Object mO662 = new Object();
        Object mO663 = new Object();
        Object mO664 = new Object();
        Object mO665 = new Object();
        Object mO666 = new Object();
        Object mO667 = new Object();
        Object mO668 = new Object();
        Object mO669 = new Object();
        Object mO670 = new Object();
        Object mO671 = new Object();
        Object mO672 = new Object();
        Object mO673 = new Object();
        Object mO674 = new Object();
        Object mO675 = new Object();
        Object mO676 = new Object();
        Object mO677 = new Object();
        Object mO678 = new Object();
        Object mO679 = new Object();
        Object mO680 = new Object();
        Object mO681 = new Object();
        Object mO682 = new Object();
        Object mO683 = new Object();
        Object mO684 = new Object();
        Object mO685 = new Object();
        Object mO686 = new Object();
        Object mO687 = new Object();
        Object mO688 = new Object();
        Object mO734 = new Object();
        Object mO735 = new Object();
        Object mO736 = new Object();
        Object mO737 = new Object();
        Object mO738 = new Object();
        Object mO739 = new Object();
        Object mO740 = new Object();
        Object mO741 = new Object();
        Object mO742 = new Object();
        Object mO743 = new Object();
        Object mO744 = new Object();
        Object mO745 = new Object();
        Object mO746 = new Object();
        Object mO747 = new Object();
        Object mO748 = new Object();
        Object mO749 = new Object();
        Object mO750 = new Object();
        Object mO751 = new Object();
        Object mO752 = new Object();
        Object mO753 = new Object();
        Object mO754 = new Object();
        Object mO755 = new Object();
        Object mO756 = new Object();
        Object mO757 = new Object();
        Object mO758 = new Object();
        Object mO759 = new Object();
        Object mO760 = new Object();
        Object mO761 = new Object();
        Object mO762 = new Object();
        Object mO763 = new Object();
        Object mO764 = new Object();
        Object mO765 = new Object();
        Object mO766 = new Object();
        Object mO767 = new Object();
        Object mO768 = new Object();
        Object mO769 = new Object();
        Object mO770 = new Object();
        Object mO771 = new Object();
        Object mO772 = new Object();
        Object mO773 = new Object();
        Object mO774 = new Object();
        Object mO775 = new Object();
        Object mO776 = new Object();
        Object mO777 = new Object();
        Object mO778 = new Object();
        Object mO779 = new Object();
        Object mO780 = new Object();
        Object mO781 = new Object();
        Object mO782 = new Object();
        Object mO783 = new Object();
        Object mO784 = new Object();
        Object mO785 = new Object();
        Object mO786 = new Object();
        Object mO787 = new Object();
        Object mO788 = new Object();
        Object mO789 = new Object();
        Object mO790 = new Object();
        Object mO791 = new Object();
        Object mO792 = new Object();
        Object mO793 = new Object();
        Object mO794 = new Object();
        Object mO795 = new Object();
        Object mO796 = new Object();
        Object mO797 = new Object();
        Object mO798 = new Object();
        Object mO799 = new Object();
        Object mO800 = new Object();
        Object mO801 = new Object();
        Object mO802 = new Object();
        Object mO803 = new Object();
        Object mO804 = new Object();
        Object mO805 = new Object();
        Object mO806 = new Object();
        Object mO807 = new Object();
        Object mO808 = new Object();
        Object mO809 = new Object();
        Object mO810 = new Object();
        Object mO811 = new Object();
        Object mO812 = new Object();
        Object mO813 = new Object();
        Object mO848 = new Object();
        Object mO849 = new Object();
        Object mO850 = new Object();
        Object mO851 = new Object();
        Object mO852 = new Object();
        Object mO853 = new Object();
        Object mO854 = new Object();
        Object mO855 = new Object();
        Object mO856 = new Object();
        Object mO857 = new Object();
        Object mO858 = new Object();
        Object mO859 = new Object();
        Object mO860 = new Object();
        Object mO861 = new Object();
        Object mO862 = new Object();
        Object mO863 = new Object();
        Object mO864 = new Object();
        Object mO865 = new Object();
        Object mO866 = new Object();
        Object mO867 = new Object();
        Object mO868 = new Object();
        Object mO869 = new Object();
        Object mO870 = new Object();
        Object mO871 = new Object();
        Object mO872 = new Object();
        Object mO873 = new Object();
        Object mO874 = new Object();
        Object mO875 = new Object();
        Object mO876 = new Object();
        Object mO877 = new Object();
        Object mO878 = new Object();
        Object mO879 = new Object();
        Object mO880 = new Object();
        Object mO881 = new Object();
        Object mO882 = new Object();
        Object mO883 = new Object();
        Object mO884 = new Object();
        Object mO885 = new Object();
        Object mO886 = new Object();
        Object mO887 = new Object();
        Object mO888 = new Object();
        Object mO889 = new Object();
        Object mO890 = new Object();
        Object mO891 = new Object();
        Object mO892 = new Object();
        Object mO893 = new Object();
        Object mO894 = new Object();
        Object mO895 = new Object();
        Object mO896 = new Object();
        Object mO897 = new Object();
        Object mO898 = new Object();
        Object mO899 = new Object();
        Object mO900 = new Object();
        Object mO901 = new Object();
        Object mO902 = new Object();
        Object mO903 = new Object();
        Object mO904 = new Object();
        Object mO905 = new Object();
        Object mO906 = new Object();
        Object mO907 = new Object();
        Object mO908 = new Object();
        Object mO909 = new Object();
        Object mO910 = new Object();
        Object mO911 = new Object();
        Object mO912 = new Object();
        Object mO913 = new Object();
        Object mO914 = new Object();
        Object mO915 = new Object();
        Object mO916 = new Object();
        Object mO917 = new Object();
        Object mO918 = new Object();
        Object mO919 = new Object();
        Object mO920 = new Object();
        Object mO921 = new Object();
        Object mO922 = new Object();
        Object mO923 = new Object();
        Object mO924 = new Object();
        Object mO925 = new Object();
        Object mO926 = new Object();
        Object mO927 = new Object();
        Object mO928 = new Object();
        Object mO929 = new Object();
        Object mO930 = new Object();
        Object mO931 = new Object();
        Object mO932 = new Object();
        Object mO933 = new Object();
        Object mO934 = new Object();
        Object mO935 = new Object();
        Object mO936 = new Object();
        Object mO937 = new Object();
        Object mO938 = new Object();
        Object mO939 = new Object();
        Object mO940 = new Object();
        Object mO941 = new Object();
        Object mO942 = new Object();
        Object mO943 = new Object();
        Object mO944 = new Object();
        Object mO945 = new Object();
        Object mO946 = new Object();
        Object mO947 = new Object();
        Object mO948 = new Object();
        Object mO949 = new Object();
        Object mO950 = new Object();
        Object mO951 = new Object();
        Object mO952 = new Object();
        Object mO953 = new Object();
        Object mO954 = new Object();
        Object mO955 = new Object();
        Object mO956 = new Object();
        Object mO957 = new Object();
        Object mO958 = new Object();
        Object mO959 = new Object();
        Object mO960 = new Object();
        Object mO961 = new Object();
        Object mO962 = new Object();
        Object mO963 = new Object();
        Object mO964 = new Object();
        Object mO965 = new Object();
        Object mO966 = new Object();
        Object mO967 = new Object();
        Object mO968 = new Object();
        Object mO969 = new Object();
        Object mO970 = new Object();
        Object mO971 = new Object();
        Object mO972 = new Object();
        Object mO973 = new Object();
        Object mO974 = new Object();
        Object mO975 = new Object();
        Object mO976 = new Object();
        Object mO977 = new Object();
        Object mO978 = new Object();
        Object mO979 = new Object();
        Object mO980 = new Object();
        Object mO981 = new Object();
        Object mO982 = new Object();
        Object mO983 = new Object();
        Object mO984 = new Object();
        Object mO985 = new Object();
        Object mO986 = new Object();
        Object mO987 = new Object();
        Object mO988 = new Object();
        Object mO989 = new Object();
        Object mO990 = new Object();
        Object mO991 = new Object();
        Object mO992 = new Object();
        Object mO993 = new Object();
        Object mO994 = new Object();
        Object mO995 = new Object();
        Object mO996 = new Object();
        Object mO997 = new Object();
        Object mO998 = new Object();
        Object mO999 = new Object();
    }

    static class Deep0 {}

    static class Deep1 extends Deep0 {}

    static class Deep2 extends Deep1 {}

    static class Deep3 extends Deep2 {}

    static class Deep4 extends Deep3 {}

    static class Deep5 extends Deep4 {}

    static class Deep6 extends Deep5 {}

    static class Deep7 extends Deep6 {}

    static class Deep8 extends Deep7 {}

    static class Deep9 extends Deep8 {}

    static class Deep10 extends Deep9 {}

    static class Deep11 extends Deep10 {}

    static class Deep12 extends Deep11 {}

    static class Deep13 extends Deep12 {}

    static class Deep14 extends Deep13 {}

    static class Deep15 extends Deep14 {}

    static class Deep16 extends Deep15 {}

    static class Deep17 extends Deep16 {}

    static class Deep18 extends Deep17 {}

    static class Deep19 extends Deep18 {}

    static class Deep20 extends Deep19 {}

    static class Deep21 extends Deep20 {}

    static class Deep22 extends Deep21 {}

    static class Deep23 extends Deep22 {}

    static class Deep24 extends Deep23 {}

    static class Deep25 extends Deep24 {}

    static class Deep26 extends Deep25 {}

    static class Deep27 extends Deep26 {}

    static class Deep28 extends Deep27 {}

    static class Deep29 extends Deep28 {}

    static class Deep30 extends Deep29 {}

    static class Deep31 extends Deep30 {}

    static class Deep32 extends Deep31 {}

    static class Deep33 extends Deep32 {}

    static class Deep34 extends Deep33 {}

    static class Deep35 extends Deep34 {}

    static class Deep36 extends Deep35 {}

    static class Deep37 extends Deep36 {}

    static class Deep38 extends Deep37 {}

    static class Deep39 extends Deep38 {}

    static class Deep40 extends Deep39 {}

    static class Deep41 extends Deep40 {}

    static class Deep42 extends Deep41 {}

    static class Deep43 extends Deep42 {}

    static class Deep44 extends Deep43 {}

    static class Deep45 extends Deep44 {}

    static class Deep46 extends Deep45 {}

    static class Deep47 extends Deep46 {}

    static class Deep48 extends Deep47 {}

    static class Deep49 extends Deep48 {}

    static class Deep50 extends Deep49 {}

    static class Deep51 extends Deep50 {}

    static class Deep52 extends Deep51 {}

    static class Deep53 extends Deep52 {}

    static class Deep54 extends Deep53 {}

    static class Deep55 extends Deep54 {}

    static class Deep56 extends Deep55 {}

    static class Deep57 extends Deep56 {}

    static class Deep58 extends Deep57 {}

    static class Deep59 extends Deep58 {}

    static class Deep60 extends Deep59 {}

    static class Deep61 extends Deep60 {}

    static class Deep62 extends Deep61 {}

    static class Deep63 extends Deep62 {}

    static class Deep64 extends Deep63 {}

    static class Deep65 extends Deep64 {}

    static class Deep66 extends Deep65 {}

    static class Deep67 extends Deep66 {}

    static class Deep68 extends Deep67 {}

    static class Deep69 extends Deep68 {}

    static class Deep70 extends Deep69 {}

    static class Deep71 extends Deep70 {}

    static class Deep72 extends Deep71 {}

    static class Deep73 extends Deep72 {}

    static class Deep74 extends Deep73 {}

    static class Deep75 extends Deep74 {}

    static class Deep76 extends Deep75 {}

    static class Deep77 extends Deep76 {}

    static class Deep78 extends Deep77 {}

    static class Deep79 extends Deep78 {}

    static class Deep80 extends Deep79 {}

    static class Deep81 extends Deep80 {}

    static class Deep82 extends Deep81 {}

    static class Deep83 extends Deep82 {}

    static class Deep84 extends Deep83 {}

    static class Deep85 extends Deep84 {}

    static class Deep86 extends Deep85 {}

    static class Deep87 extends Deep86 {}

    static class Deep88 extends Deep87 {}

    static class Deep89 extends Deep88 {}

    static class Deep90 extends Deep89 {}

    static class Deep91 extends Deep90 {}

    static class Deep92 extends Deep91 {}

    static class Deep93 extends Deep92 {}

    static class Deep94 extends Deep93 {}

    static class Deep95 extends Deep94 {}

    static class Deep96 extends Deep95 {}

    static class Deep97 extends Deep96 {}

    static class Deep98 extends Deep97 {}

    static class Deep99 extends Deep98 {}

    static class Deep100 extends Deep99 {}

    static class DeepCloneable extends Deep100 implements Cloneable {
        public Object clone() throws CloneNotSupportedException {
            return super.clone();
        }
    }

    @Test
    public void time_Object_clone() {
        try {
            CloneableObject o = new CloneableObject();
            BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
            while (state.keepRunning()) {
                o.clone();
            }
        } catch (Exception e) {
            throw new AssertionError(e.getMessage());
        }
    }

    @Test
    public void time_Object_manyFieldClone() {
        try {
            CloneableManyFieldObject o = new CloneableManyFieldObject();
            BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
            while (state.keepRunning()) {
                o.clone();
            }
        } catch (Exception e) {
            throw new AssertionError(e.getMessage());
        }
    }

    @Test
    public void time_Object_deepClone() {
        try {
            DeepCloneable o = new DeepCloneable();
            BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
            while (state.keepRunning()) {
                o.clone();
            }
        } catch (Exception e) {
            throw new AssertionError(e.getMessage());
        }
    }

    @Test
    public void time_Array_clone() {
        int[] o = new int[32];
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            o.clone();
        }
    }

    @Test
    public void time_ObjectArray_smallClone() {
        Object[] o = new Object[32];
        for (int i = 0; i < o.length / 2; ++i) {
            o[i] = new Object();
        }
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            o.clone();
        }
    }

    @Test
    public void time_ObjectArray_largeClone() {
        Object[] o = new Object[2048];
        for (int i = 0; i < o.length / 2; ++i) {
            o[i] = new Object();
        }
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            o.clone();
        }
    }
}
