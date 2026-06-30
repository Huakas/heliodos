package eu.domob.heliodos

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.preference.PreferenceManager
import io.github.cosinekitty.astronomy.Body
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val PATH_POINTS = 50
private const val HOUR_TICK_LENGTH = 30f
private const val HOUR_TICK_TANGENT_DELTA_MS = 30_000L
private const val HOUR_TICK_LABEL_GAP = 6f

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var cameraFeedView: CameraFeedView? = null
    private var rotationMatrix: FloatArray? = null
    private var bodyPositionSun: BodyPosition? = null
    private var bodyPositionMoon: BodyPosition? = null
    private var referenceTime: Long = 0
    private var observerLatitude: Double = 0.0

    private var prefsLoaded = false
    private val pathEnabled = mutableMapOf<String, Boolean>()
    private val pathColors = mutableMapOf<String, Int>()

    private val paintText = Paint().apply {
        color = Color.WHITE
        textSize = 60f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private val paintBackground = Paint().apply {
        color = Color.parseColor("#80000000")
        style = Paint.Style.FILL
    }

    private val paintLabel = Paint().apply {
        textSize = 30f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private fun drawBodyPath(canvas: Canvas, bp: BodyPosition, time: Long, thickness: Float, color: Int, currentSunPosition: BodyPosition.AzimuthAltitude? = null) {
        val riseSet = bp.getRiseSet(time)

        val startTime: Long
        val endTime: Long
        var isLoop = false
        if (riseSet != null) {
            startTime = riseSet.rise
            endTime = riseSet.set
        } else {
            val pos = bp.getPosition(time)
            if (pos.altitude < 0) {
                return
            }
            startTime = time - 12 * 3600 * 1000
            endTime = time + 12 * 3600 * 1000
            isLoop = true
        }

        val paint = Paint().apply {
            this.color = color
            this.strokeWidth = thickness
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        val points = Array(PATH_POINTS) { i ->
            val t = startTime + (endTime - startTime) * i / (PATH_POINTS - 1)
            val pos = bp.getPositionMagnetic(t)
            project(pos.azimuth, pos.altitude)
        }

        val sunScreenPoint = if (currentSunPosition != null) project(currentSunPosition.azimuth, currentSunPosition.altitude) else null
        val sunBlankRadius = 80f

        if (isLoop) {
            points[points.lastIndex] = points[0]
        }

        var isFirstPointDrawn = false
        var lastPointDrawn: Pair<Float, Float>? = null
        val roundedCornerPaint = Paint().apply {
            this.color = color
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]

            if (!isFirstPointDrawn && p1 != null) {
                isFirstPointDrawn = true

                if (sunScreenPoint != null) {
                    val isP1Overlap = (p1.first - sunScreenPoint.first).pow(2) + (p1.second - sunScreenPoint.second).pow(2) < sunBlankRadius.pow(2)
                    if (!isP1Overlap) {
                        canvas.drawCircle(p1.first, p1.second, thickness/2, roundedCornerPaint)
                    }
                } else {
                    canvas.drawCircle(p1.first, p1.second, thickness/2, roundedCornerPaint)
                }
            }
            if (p2 != null) lastPointDrawn = p2
            else if (p1 != null) lastPointDrawn = p1

            if (p1 != null && p2 != null) {
                if (sunScreenPoint != null) {
                    val isP1Overlap = (p1.first - sunScreenPoint.first).pow(2) + (p1.second - sunScreenPoint.second).pow(2) < sunBlankRadius.pow(2)
                    val isP2Overlap = (p2.first - sunScreenPoint.first).pow(2) + (p2.second - sunScreenPoint.second).pow(2) < sunBlankRadius.pow(2)

                    if (isP1Overlap && !isP2Overlap) {
                        val intersection = findIntersection(p1, p2, sunScreenPoint, sunBlankRadius)
                        canvas.drawLine(intersection.first, intersection.second, p2.first, p2.second, paint)
                        canvas.drawCircle(intersection.first, intersection.second, thickness/2, roundedCornerPaint)
                    } else if (!isP1Overlap && isP2Overlap) {
                        val intersection = findIntersection(p1, p2, sunScreenPoint, sunBlankRadius)
                        canvas.drawLine(p1.first, p1.second, intersection.first, intersection.second, paint)
                        canvas.drawCircle(intersection.first, intersection.second, thickness/2, roundedCornerPaint)
                    } else if (isP1Overlap) continue
                    else {
                        canvas.drawLine(p1.first, p1.second, p2.first, p2.second, paint)
                    }
                } else {
                    canvas.drawLine(p1.first, p1.second, p2.first, p2.second, paint)
                }
            }
        }

        if (lastPointDrawn != null) {
            if (sunScreenPoint != null) {
                val isP2Overlap = (lastPointDrawn.first - sunScreenPoint.first).pow(2) + (lastPointDrawn.second - sunScreenPoint.second).pow(2) < sunBlankRadius.pow(2)
                if (!isP2Overlap) {
                    canvas.drawCircle(lastPointDrawn.first, lastPointDrawn.second, thickness/2, roundedCornerPaint)
                }
            } else {
                canvas.drawCircle(lastPointDrawn.first, lastPointDrawn.second, thickness/2, roundedCornerPaint)
            }
        }
    }

    private fun drawHourTicks(canvas: Canvas, bp: BodyPosition, time: Long, color: Int) {
        val ticks = bp.getFullHourTimestamps(time)
        if (ticks.isEmpty()) return

        val paintTick = Paint().apply {
            this.color = color
            strokeWidth = 3f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        paintLabel.color = color

        val halfLen = HOUR_TICK_LENGTH / 2f
        val fontMetrics = paintLabel.fontMetrics
        val textHeight = fontMetrics.bottom - fontMetrics.top
        val textOffsetY = textHeight / 2 - fontMetrics.bottom
        val padding = 4f
        val boxHalfHeight = textHeight / 2 + padding
        val labelOffset = halfLen + boxHalfHeight + HOUR_TICK_LABEL_GAP

        for (tick in ticks) {
            val center = bp.getPositionMagnetic(tick.millis)
            val centerPt = project(center.azimuth, center.altitude) ?: continue

            val before = bp.getPositionMagnetic(tick.millis - HOUR_TICK_TANGENT_DELTA_MS)
            val beforePt = project(before.azimuth, before.altitude) ?: continue

            val after = bp.getPositionMagnetic(tick.millis + HOUR_TICK_TANGENT_DELTA_MS)
            val afterPt = project(after.azimuth, after.altitude) ?: continue

            val dx = afterPt.first - beforePt.first
            val dy = afterPt.second - beforePt.second
            val len = sqrt(dx * dx + dy * dy)
            if (len < 0.001f) continue

            val nx = -dy / len
            val ny = dx / len

            val x1 = centerPt.first - nx * halfLen
            val y1 = centerPt.second - ny * halfLen
            val x2 = centerPt.first + nx * halfLen
            val y2 = centerPt.second + ny * halfLen

            canvas.drawLine(x1, y1, x2, y2, paintTick)

            val labelX = centerPt.first + nx * labelOffset
            val labelY = centerPt.second + ny * labelOffset

            val labelText = "${tick.localHour}h"
            val textWidth = paintLabel.measureText(labelText)

            canvas.save()
            canvas.translate(labelX, labelY)
            canvas.rotate(-Math.toDegrees(Math.atan2(nx.toDouble(), ny.toDouble())).toFloat())

            canvas.drawRect(
                -textWidth / 2 - padding,
                -boxHalfHeight,
                textWidth / 2 + padding,
                boxHalfHeight,
                paintBackground
            )
            canvas.drawText(labelText, 0f, textOffsetY, paintLabel)

            canvas.restore()
        }
    }

    fun findIntersection(p1: Pair<Float, Float>, p2: Pair<Float, Float>, c1: Pair<Float, Float>, r: Float): Pair<Float, Float> {
        val dx = p2.first - p1.first
        val dy = p2.second - p1.second
        val fx = p1.first - c1.first
        val fy = p1.second - c1.second

        val a = dx * dx + dy * dy
        val b = 2.0f * (fx * dx + fy * dy)
        val c = fx * fx + fy * fy - r * r

        val sqrtDiscriminant = sqrt(b * b - 4 * a * c)

        val t1 = (-b + sqrtDiscriminant) / (2 * a)
        val t2 = (-b - sqrtDiscriminant) / (2 * a)

        return if (t1 in 0.0f..1.0f) {
            Pair(p1.first + t1 * dx, p1.second + t1 * dy)
        } else {
            Pair(p1.first + t2 * dx, p1.second + t2 * dy)
        }
    }

    fun setRotationMatrix(matrix: FloatArray) {
        rotationMatrix = matrix
        invalidate()
    }

    fun updatePosition(latitude: Double, longitude: Double, altitude: Double) {
        bodyPositionSun = BodyPosition(latitude, longitude, altitude, Body.Sun)
        bodyPositionMoon = BodyPosition(latitude, longitude, altitude, Body.Moon)
        observerLatitude = latitude
        invalidate()
    }

    fun updateTime(time: Long) {
        referenceTime = time
        invalidate()
    }

    fun clearLocation() {
        bodyPositionSun = null
        bodyPositionMoon = null
        invalidate()
    }

    fun reloadPreferences() {
        reloadPreferencesInternal()
        invalidate()
    }

    private fun reloadPreferencesInternal() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        pathEnabled["solstice_high"] = prefs.getBoolean("show_solstice_high", true)
        pathEnabled["solstice_low"] = prefs.getBoolean("show_solstice_low", true)
        pathEnabled["equinox"] = prefs.getBoolean("show_equinox", true)
        pathEnabled["sun_current"] = prefs.getBoolean("show_sun_current", true)
        pathEnabled["moon_current"] = prefs.getBoolean("show_moon_current", false)
        pathColors["solstice_high"] = prefs.getInt("color_solstice_high", Color.BLUE)
        pathColors["solstice_low"] = prefs.getInt("color_solstice_low", Color.RED)
        pathColors["equinox"] = prefs.getInt("color_equinox", Color.GREEN)
        pathColors["sun_current"] = prefs.getInt("color_sun_current", Color.YELLOW)
        pathColors["moon_current"] = prefs.getInt("color_moon_current", Color.WHITE)

        pathEnabled["ticks"] = prefs.getBoolean("show_ticks", true)
    }

    private fun project(azimuth: Double, altitude: Double): Pair<Float, Float>? {
        val r = cos(altitude)
        val worldX = (sin(azimuth) * r).toFloat()
        val worldY = (cos(azimuth) * r).toFloat()
        val worldZ = sin(altitude).toFloat()

        val camProjection = cameraFeedView?.projection ?: return null
        val R = rotationMatrix ?: return null

        val deviceX = R[0] * worldX + R[3] * worldY + R[6] * worldZ
        val deviceY = R[1] * worldX + R[4] * worldY + R[7] * worldZ
        val deviceZ = R[2] * worldX + R[5] * worldY + R[8] * worldZ

        val cameraX = deviceX
        val cameraY = -deviceY
        val cameraZ = -deviceZ

        camProjection.setViewSize(width, height)
        return camProjection.project(cameraX, cameraY, cameraZ)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!prefsLoaded) {
            reloadPreferencesInternal()
            prefsLoaded = true
        }

        if (bodyPositionSun == null) {
            val text = "Waiting for device location..."
            val cx = width / 2f
            val cy = height / 2f

            val fontMetrics = paintText.fontMetrics
            val textHeight = fontMetrics.bottom - fontMetrics.top
            val textOffset = textHeight / 2 - fontMetrics.bottom

            val textWidth = paintText.measureText(text)
            val padding = 20f
            canvas.drawRect(
                cx - textWidth / 2 - padding,
                cy - textHeight / 2 - padding,
                cx + textWidth / 2 + padding,
                cy + textHeight / 2 + padding,
                paintBackground
            )

            canvas.drawText(text, cx, cy + textOffset, paintText)
            return
        }

        val sun = bodyPositionSun!!

        val solstices = sun.getSolstices(referenceTime)
        if (observerLatitude >= 0) {
            if (pathEnabled["solstice_high"] == true) {
                drawBodyPath(canvas, sun, solstices.june, 10f,
                    pathColors["solstice_high"] ?: Color.BLUE)
            }
            if (pathEnabled["solstice_low"] == true) {
                drawBodyPath(canvas, sun, solstices.december, 10f,
                    pathColors["solstice_low"] ?: Color.RED)
            }
        } else {
            if (pathEnabled["solstice_high"] == true) {
                drawBodyPath(canvas, sun, solstices.december, 10f,
                    pathColors["solstice_high"] ?: Color.BLUE)
            }
            if (pathEnabled["solstice_low"] == true) {
                drawBodyPath(canvas, sun, solstices.june, 10f,
                    pathColors["solstice_low"] ?: Color.RED)
            }
        }

        if (pathEnabled["equinox"] == true) {
            drawBodyPath(canvas, sun, sun.getMarchEquinox(referenceTime), 10f,
                pathColors["equinox"] ?: Color.GREEN)
        }

        if (pathEnabled["sun_current"] == true) {
            val currentColor = pathColors["sun_current"] ?: Color.YELLOW
            if (pathEnabled["ticks"] == true) {
                drawHourTicks(canvas, sun, referenceTime, currentColor)
            }
            val sunPos = sun.getPositionMagnetic(referenceTime)
            drawBodyPath(canvas, sun, referenceTime, 15f, currentColor, sunPos)
            project(sunPos.azimuth, sunPos.altitude)?.let {
                val paint = Paint().apply {
                    color = currentColor
                    style = Paint.Style.FILL
                    isAntiAlias = true
                }
                canvas.drawCircle(it.first, it.second, 50f, paint)
            }
        }

        if (pathEnabled["moon_current"] == true) {
            bodyPositionMoon?.let { moon ->
                val moonColor = pathColors["moon_current"] ?: Color.WHITE
                if (pathEnabled["ticks"] == true) {
                    drawHourTicks(canvas, moon, referenceTime, moonColor)
                }
                val moonPos = moon.getPositionMagnetic(referenceTime)
                drawBodyPath(canvas, moon, referenceTime, 15f, moonColor, moonPos)
                project(moonPos.azimuth, moonPos.altitude)?.let {
                    val paint = Paint().apply {
                        color = moonColor
                        style = Paint.Style.FILL
                        isAntiAlias = true
                    }
                    canvas.drawCircle(it.first, it.second, 50f, paint)
                }
            }
        }
    }
}
