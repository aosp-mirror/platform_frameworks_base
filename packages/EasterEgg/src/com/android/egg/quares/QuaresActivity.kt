/*
 * Copyright 2019 The Android Open Source Project
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

package com.android.egg.quares

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Bundle
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.Button
import android.widget.CompoundButton
import android.widget.GridLayout

import java.util.Random

import com.android.egg.R

const val TAG = "Quares"

class QuaresActivity : Activity() {
    private var q: Quare = Quare(16, 16, 1)
    private var resId = 0
    private var resName = ""
    private var icon: Icon? = null

    private lateinit var label: Button
    private lateinit var grid: GridLayout

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE

        actionBar?.hide()

        setContentView(R.layout.activity_quares)

        grid = findViewById(R.id.grid)
        label = findViewById(R.id.label)

        if (savedInstanceState != null) {
            Log.v(TAG, "restoring puzzle from state")
            q = savedInstanceState.getParcelable("q") ?: q
            resId = savedInstanceState.getInt("resId")
            resName = savedInstanceState.getString("resName", "")
            loadPuzzle()
        }

        label.setOnClickListener { newPuzzle() }
    }

    override fun onResume() {
        super.onResume()
        if (resId == 0) {
            // lazy init from onCreate
            newPuzzle()
        }
        checkVictory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        outState.putParcelable("q", q)
        outState.putInt("resId", resId)
        outState.putString("resName", resName)
    }

    fun newPuzzle() {
        Log.v(TAG, "new puzzle...")

        q.resetUserMarks()
        val oldResId = resId
        resId = android.R.drawable.stat_sys_warning
        try {
            for (tries in 0..3) {
                val ar = resources.obtainTypedArray(R.array.puzzles)
                val newName = ar.getString(Random().nextInt(ar.length()))
                if (newName == null) continue

                Log.v(TAG, "Looking for icon " + newName)

                val pkg = getPackageNameForResourceName(newName)
                val newId = packageManager.getResourcesForApplication(pkg)
                        .getIdentifier(newName, "drawable", pkg)
                if (newId == 0) {
                    Log.v(TAG, "oops, " + newName + " doesn't resolve from pkg " + pkg)
                } else if (newId != oldResId) {
                    // got a good one
                    resId = newId
                    resName = newName
                    break
                }
            }
        } catch (e: RuntimeException) {
            Log.v(TAG, "problem loading puzzle, using fallback", e)
        }
        loadPuzzle()
    }

    fun getPackageNameForResourceName(name: String): String {
        return if (name.contains(":") && !name.startsWith("android:")) {
            name.substring(0, name.indexOf(":"))
        } else {
            packageName
        }
    }

    fun checkVictory() {
        if (q.check()) {
            val dp = resources.displayMetrics.density

            val label: Button = findViewById(R.id.label)
            label.text = resName.replace(Regex("^.*/"), "")
            val drawable = icon?.loadDrawable(this)?.also {
                it.setBounds(0, 0, (32 * dp).toInt(), (32 * dp).toInt())
                it.setTint(label.currentTextColor)
            }
            label.setCompoundDrawables(drawable, null, null, null)

            label.visibility = VISIBLE
        } else {
            label.visibility = GONE
        }
    }

    fun loadPuzzle() {
        Log.v(TAG, "loading " + resName + " at " + q.width + "x" + q.height)

        val dp = resources.displayMetrics.density

        icon = Icon.createWithResource(getPackageNameForResourceName(resName), resId)
        q.load(this, icon!!)

        if (q.isBlank()) {
            // this is a really boring puzzle, let's try again
            resId = 0
            resName = ""
            recreate()
            return
        }

        grid.removeAllViews()
        grid.columnCount = q.width + 1
        grid.rowCount = q.height + 1

        label.visibility = GONE

        val orientation = resources.configuration.orientation

        // clean this up a bit
        val minSide = resources.configuration.smallestScreenWidthDp - 25 // ish
        val size = (minSide / (q.height + 0.5) * dp).toInt()

        val sb = StringBuffer()

        for (j in 0 until grid.rowCount) {
            for (i in 0 until grid.columnCount) {
                val tv: View
                val params = GridLayout.LayoutParams().also {
                    it.width = size
                    it.height = size
                    it.setMargins(1, 1, 1, 1)
                    it.rowSpec = GridLayout.spec(GridLayout.UNDEFINED, GridLayout.TOP) // UGH
                }
                val x = i - 1
                val y = j - 1
                if (i > 0 && j > 0) {
                    if (i == 1 && j > 1) sb.append("\n")
                    sb.append(if (q.getDataAt(x, y) == 0) " " else "X")
                    tv = PixelButton(this)
                    tv.isChecked = q.getUserMark(x, y) != 0
                    tv.setOnClickListener {
                        q.setUserMark(x, y, if (tv.isChecked) 0xFF else 0)
                        val columnCorrect = (grid.getChildAt(i) as? ClueView)?.check(q) ?: false
                        val rowCorrect = (grid.getChildAt(j*(grid.columnCount)) as? ClueView)
                                ?.check(q) ?: false
                        if (columnCorrect && rowCorrect) {
                            checkVictory()
                        } else {
                            label.visibility = GONE
                        }
                    }
                } else if (i == j) { // 0,0
                    tv = View(this)
                    tv.visibility = GONE
                } else {
                    tv = ClueView(this)
                    if (j == 0) {
                        tv.textRotation = 90f
                        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                            params.height /= 2
                            tv.showText = false
                        } else {
                            params.height = (96 * dp).toInt()
                        }
                        if (x >= 0) {
                            tv.setColumn(q, x)
                        }
                    }
                    if (i == 0) {
                        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
                            params.width /= 2
                            tv.showText = false
                        } else {
                            params.width = (96 * dp).toInt()
                        }
                        if (y >= 0) {
                            tv.setRow(q, y)
                        }
                    }
                }
                grid.addView(tv, params)
            }
        }

        Log.v(TAG, "icon: \n" + sb)
    }
}

class PixelButton(context: Context) : CompoundButton(context) {
    init {
        setBackgroundResource(R.drawable.pixel_bg)
        isClickable = true
        isEnabled = true
    }
}

class ClueView(context: Context) : View(context) {
    var row: Int = -1
    var column: Int = -1
    var textRotation: Float = 0f
    var text: CharSequence = ""
    var showText = true
    val paint: TextPaint
    val incorrectColor: Int
    val correctColor: Int

    init {
        setBackgroundColor(0)
        paint = TextPaint().also {
            it.textSize = 14f * context.resources.displayMetrics.density
            it.color = context.getColor(R.color.q_clue_text)
            it.typeface = Typeface.DEFAULT_BOLD
            it.textAlign = Paint.Align.CENTER
        }
        incorrectColor = context.getColor(R.color.q_clue_bg)
        correctColor = context.getColor(R.color.q_clue_bg_correct)
    }

    fun setRow(q: Quare, row: Int): Boolean {
        this.row = row
        this.column = -1
        this.textRotation = 0f
        text = q.getRowClue(row).joinToString("-")
        return check(q)
    }
    fun setColumn(q: Quare, column: Int): Boolean {
        this.column = column
        this.row = -1
        this.textRotation = 90f
        text = q.getColumnClue(column).joinToString("-")
        return check(q)
    }
    fun check(q: Quare): Boolean {
        val correct = q.check(column, row)
        setBackgroundColor(if (correct) correctColor else incorrectColor)
        return correct
    }

    override fun onDraw(canvas: Canvas?) {
        super.onDraw(canvas)
        if (!showText) return
        canvas?.let {
            val x = canvas.width / 2f
            val y = canvas.height / 2f
            var textWidth = canvas.width
            if (textRotation != 0f) {
                canvas.rotate(textRotation, x, y)
                textWidth = canvas.height
            }
            val textLayout = StaticLayout.Builder.obtain(
                    text, 0, text.length, paint, textWidth).build()
            canvas.translate(x, y - textLayout.height / 2)
            textLayout.draw(canvas)
        }
    }
}
