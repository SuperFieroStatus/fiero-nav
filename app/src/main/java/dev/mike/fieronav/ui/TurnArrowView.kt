package dev.mike.fieronav.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import dev.mike.fieronav.Arrow
import dev.mike.fieronav.R

/** Big maneuver arrow drawn as a thick stroke with a head; no icon assets. */
class TurnArrowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var arrow: Arrow? = null
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.highlight)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.highlight)
        style = Paint.Style.FILL
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val a = arrow ?: return
        val s = minOf(width, height).toFloat()
        stroke.strokeWidth = s * 0.12f
        canvas.save()
        canvas.translate((width - s) / 2, (height - s) / 2)
        // Draw right-hand variants; mirror for left.
        val left = a == Arrow.LEFT || a == Arrow.SLIGHT_LEFT || a == Arrow.SHARP_LEFT
        if (left) canvas.scale(-1f, 1f, s / 2, s / 2)
        path.reset()
        when (a) {
            Arrow.STRAIGHT -> {
                line(s, 0.5f, 0.92f, 0.5f, 0.3f)
                head(canvas, s, 0.5f, 0.12f, 0f)
            }
            Arrow.SLIGHT_LEFT, Arrow.SLIGHT_RIGHT -> {
                path.moveTo(s * 0.42f, s * 0.92f)
                path.lineTo(s * 0.42f, s * 0.58f)
                path.lineTo(s * 0.62f, s * 0.36f)
                head(canvas, s, 0.74f, 0.22f, 45f)
            }
            Arrow.LEFT, Arrow.RIGHT -> {
                path.moveTo(s * 0.3f, s * 0.92f)
                path.lineTo(s * 0.3f, s * 0.5f)
                path.lineTo(s * 0.66f, s * 0.5f)
                head(canvas, s, 0.86f, 0.5f, 90f)
            }
            Arrow.SHARP_LEFT, Arrow.SHARP_RIGHT -> {
                path.moveTo(s * 0.3f, s * 0.92f)
                path.lineTo(s * 0.3f, s * 0.3f)
                path.lineTo(s * 0.6f, s * 0.6f)
                head(canvas, s, 0.74f, 0.74f, 135f)
            }
            Arrow.UTURN -> {
                // Up the right side, over the top, down the left: a left U-turn (US roads).
                path.moveTo(s * 0.68f, s * 0.92f)
                path.lineTo(s * 0.68f, s * 0.38f)
                path.arcTo(s * 0.32f, s * 0.2f, s * 0.68f, s * 0.56f, 0f, -180f, false)
                path.lineTo(s * 0.32f, s * 0.62f)
                head(canvas, s, 0.32f, 0.8f, 180f)
            }
            Arrow.ROUNDABOUT -> {
                canvas.drawCircle(s * 0.5f, s * 0.42f, s * 0.2f, stroke)
                line(s, 0.5f, 0.92f, 0.5f, 0.62f)
                head(canvas, s, 0.5f, 0.04f, 0f)
            }
            Arrow.ARRIVE -> {
                line(s, 0.5f, 0.92f, 0.5f, 0.55f)
                canvas.drawCircle(s * 0.5f, s * 0.35f, s * 0.17f, fill)
            }
        }
        canvas.drawPath(path, stroke)
        canvas.restore()
    }

    private fun line(s: Float, x1: Float, y1: Float, x2: Float, y2: Float) {
        path.moveTo(s * x1, s * y1)
        path.lineTo(s * x2, s * y2)
    }

    /** Triangle head with its tip at (x, y), pointing [deg] clockwise from up. */
    private fun head(canvas: Canvas, s: Float, x: Float, y: Float, deg: Float) {
        val h = Path().apply {
            moveTo(0f, 0f)
            lineTo(-s * 0.17f, s * 0.2f)
            lineTo(s * 0.17f, s * 0.2f)
            close()
        }
        canvas.save()
        canvas.translate(s * x, s * y)
        canvas.rotate(deg)
        canvas.drawPath(h, fill)
        canvas.restore()
    }
}
