package com.solmacro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View

// Rebuilt file: the overlay of the TEST / debug mode. Draws the search area, the zone and the marker.
class DebugView(c: Context) : View(c) {
    val rect = Rect()
    var color: Int = Color.RED
    var label: String = ""
    var zmin: Double = -1.0
    var zmax: Double = -1.0
    var mpos: Double = -1.0
    var vert: Boolean = false

    private val box = Paint(Paint.ANTI_ALIAS_FLAG)
    private val zone = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        box.style = Paint.Style.STROKE
        box.strokeWidth = 4f
        zone.style = Paint.Style.FILL
        zone.color = Color.argb(80, 60, 255, 120)
        mark.style = Paint.Style.STROKE
        mark.strokeWidth = 6f
        mark.color = Color.WHITE
        txt.textSize = 34f
        txt.color = Color.WHITE
        txt.setShadowLayer(5f, 0f, 0f, Color.BLACK)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        box.color = color
        canvas.drawRect(rect, box)
        if (zmax >= 0.0) {
            if (vert) canvas.drawRect(rect.left.toFloat(), zmin.toFloat(), rect.right.toFloat(), zmax.toFloat(), zone)
            else canvas.drawRect(zmin.toFloat(), rect.top.toFloat(), zmax.toFloat(), rect.bottom.toFloat(), zone)
        }
        if (mpos >= 0.0) {
            if (vert) canvas.drawLine(rect.left.toFloat(), mpos.toFloat(), rect.right.toFloat(), mpos.toFloat(), mark)
            else canvas.drawLine(mpos.toFloat(), rect.top.toFloat(), mpos.toFloat(), rect.bottom.toFloat(), mark)
        }
        val ty = if (rect.top > 50) rect.top - 14f else rect.bottom + 40f
        canvas.drawText(label, rect.left.toFloat(), ty, txt)
    }
}
