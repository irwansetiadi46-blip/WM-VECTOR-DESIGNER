package com.example.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asAndroidPath
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Technical representation of a computed Live Corner Curve.
 * Defined by 4 high-precision points form a Cubic Bézier curve (P0, P1, P2, P3).
 * Guarantees G1 continuity at the transition boundaries.
 */
data class LiveCornerCurve(
    val p0: Offset,          // Start Anchor Point
    val p1: Offset,          // Start Bézier Control Handle Point
    val p2: Offset,          // End Bézier Control Handle Point
    val p3: Offset,          // End Target Anchor Point
    val actualRadius: Float  // Safely constrained and scaled corner radius
)

/**
 * Live Corners Solver: Dynamically calculates and rounds intersecting corner lines
 * formed by points A, vertex/intersection corner B, and C.
 * Optimized for any arbitrary angle between 30° and 150°, including orthographic 90°.
 *
 * @param pointA The starting point of Line Segment 1
 * @param pointB The absolute intersection vertex (the corner to round)
 * @param pointC The ending point of Line Segment 2
 * @param radius The desired rounding radius R
 */
fun calculateLiveCorner(
    pointA: Offset,
    pointB: Offset,
    pointC: Offset,
    radius: Float
): LiveCornerCurve? {
    // 1. Vector Derivation: Compute direction vectors from the vertex/intersection point B to adjacent points A and C
    val v1 = pointA - pointB
    val v2 = pointC - pointB

    // 2. High-precision segment lengths calculation to prevent any division-by-zero or NaN propagation
    val L1 = kotlin.math.hypot(v1.x, v1.y)
    val L2 = kotlin.math.hypot(v2.x, v2.y)

    // 3. Degeneracy Check: Reject intersecting lines that lack sufficient geometric length
    if (L1 < 0.1f || L2 < 0.1f) return null

    // 4. Unit Vectors: Derive normalized direction vectors collinear with both intersecting line segments
    val u1 = Offset(v1.x / L1, v1.y / L1)
    val u2 = Offset(v2.x / L2, v2.y / L2)

    // 5. Interior Angle Analysis: Find the angle theta (θ) formed by the two intersecting lines at vertex B
    val cosTheta = (u1.x * u2.x + u1.y * u2.y).coerceIn(-0.99999f, 0.99999f)
    val theta = kotlin.math.acos(cosTheta)

    // 6. Tangent Half-Angle: Solve the trigonometric half-angle tangent relation to find the base circular tangent distance
    val tanHalf = kotlin.math.tan(theta / 2f)
    if (tanHalf < 0.001f) return null
    val dBase = radius / tanHalf

    // 7. Safety Constraints: Clamp the tangent distance to a maximum of 50% of the shorter line segment.
    // This dynamically scales down the corner radius if the available segment lengths are too short,
    // thereby preventing overlapping curves and rendering artifacts at extreme angles.
    val dMax = minOf(L1, L2) * 0.50f
    val D = minOf(dBase, dMax)
    
    // Compute the dynamically scaled actual nominal radius
    val actualRadius = D * tanHalf

    // 8. Offset Anchor Projection: Project start anchor P0 on BA, and target end anchor P3 on BC using unit vectors
    // P0 = B + u1 * D
    val p0 = pointB + u1 * D
    // P3 = B + u2 * D
    val p3 = pointB + u2 * D

    // 9. Bezier Control Handle Derivation with Curvature Smoothing:
    // Calculate bezier handle lengths (p1 and p2) using the standard corner-angle Kappa formula:
    // sweepAngle of the circular arc is (PI - theta).
    // handleLength = radius * (4.0 / 3.0) * tan(sweepAngle / 4.0)
    val sweepAngle = kotlin.math.PI.toFloat() - theta
    val L_handle = actualRadius * (4f / 3f) * kotlin.math.tan(sweepAngle / 4f)

    // 10. Control Points Projection: Project handles collinear along their respective tangents to guarantee G1 visual smoothness
    // P1 extends from P0 towards the vertex B
    val p1 = p0 - u1 * L_handle
    // P2 extends from P3 towards the vertex B
    val p2 = p3 - u2 * L_handle

    return LiveCornerCurve(
        p0 = p0,
        p1 = p1,
        p2 = p2,
        p3 = p3,
        actualRadius = actualRadius
    )
}

// We represent shapes as direct data classes for easy Moshi serialization
data class SerializedPoint(val x: Float, val y: Float) {
    fun toOffset(): Offset = Offset(x, y)
}

data class BezierNode(
    val anchorX: Float,
    val anchorY: Float,
    val isCurve: Boolean = false,
    val control1X: Float = anchorX,
    val control1Y: Float = anchorY,
    val control2X: Float = anchorX,
    val control2Y: Float = anchorY,
    val isMoveTo: Boolean = false,
    val nodeType: String = "BEBAS" // "BEBAS", "ASIMETRIS", "SIMETRIS", "HALUS"
) {
    fun getAnchor(): Offset = Offset(anchorX, anchorY)
    fun getHandleIn(): Offset = Offset(control1X, control1Y)
    fun getHandleOut(): Offset = Offset(control2X, control2Y)
    
    fun copyWithTransform(dx: Float, dy: Float): BezierNode {
        return copy(
            anchorX = anchorX + dx,
            anchorY = anchorY + dy,
            control1X = control1X + dx,
            control1Y = control1Y + dy,
            control2X = control2X + dx,
            control2Y = control2Y + dy
        )
    }

    fun copyWithScale(scaleX: Float, scaleY: Float, px: Float, py: Float): BezierNode {
        return copy(
            anchorX = px + (anchorX - px) * scaleX,
            anchorY = py + (anchorY - py) * scaleY,
            control1X = px + (control1X - px) * scaleX,
            control1Y = py + (control1Y - py) * scaleY,
            control2X = px + (control2X - px) * scaleX,
            control2Y = py + (control2Y - py) * scaleY
        )
    }
}

data class HandleHit(val nodeIndex: Int, val handleType: String) // "in" or "out"

fun checkHandleHit(touchX: Float, touchY: Float, nodes: List<BezierNode>, radius: Float = 40f): HandleHit? {
    for (i in nodes.indices) {
        val node = nodes[i]
        if (node.isCurve) {
            if (hypot(touchX - node.control1X, touchY - node.control1Y) < radius) {
                return HandleHit(i, "in")
            }
            if (hypot(touchX - node.control2X, touchY - node.control2Y) < radius) {
                return HandleHit(i, "out")
            }
        }
    }
    return null
}

fun checkNodeHit(touchX: Float, touchY: Float, nodes: List<BezierNode>, radius: Float = 40f): Int {
    for (i in nodes.indices) {
        if (hypot(touchX - nodes[i].anchorX, touchY - nodes[i].anchorY) < radius) {
            return i
        }
    }
    return -1
}

enum class ShapeType {
    RECTANGLE,
    ELLIPSE,
    LINE,
    FREEHAND,
    BEZIER_PATH,
    TEXT,
    POLYGON,
    STAR
}

data class VectorShape(
    val id: String,
    val name: String,
    val type: ShapeType,
    val isVisible: Boolean = true,
    val isLocked: Boolean = false,
    
    // Position parameters for Rectangle, Ellipse, Line, Text, Polygon, Star
    val x: Float = 0f,
    val y: Float = 0f,
    val width: Float = 0f,
    val height: Float = 0f,
    
    // For Line start/end points
    val startX: Float = 0f,
    val startY: Float = 0f,
    val endX: Float = 0f,
    val endY: Float = 0f,
    
    // List of points for Freehand drawings
    val freehandPoints: List<SerializedPoint> = emptyList(),
    
    // Bezier nodes for the pen vector path tool
    val bezierNodes: List<BezierNode> = emptyList(),
    val isPathClosed: Boolean = false,
    
    // Styling parameters
    val strokeColorHex: String = "#000000",
    val strokeWidth: Float = 4f,
    val strokeAlpha: Float = 1f,
    val hasFill: Boolean = false,
    val fillColorHex: String = "#00FF80",
    val fillAlpha: Float = 1f,
    val strokeJoin: String = "ROUND", // "MITER", "ROUND", "BEVEL"
    val strokeCap: String = "ROUND",  // "ROUND", "BUTT", "SQUARE"
    
    // Text contents for Text shapes
    val textContent: String = "Double Tap to Edit",
    val fontSize: Float = 24f,
    
    // Stack and rotation order
    val layerOrder: Int = 0,
    val rotationAngle: Float = 0f, // in degrees
    
    // Configurable parameters for Pentagon, Hexagon, Star, etc.
    val polygonSides: Int = 5,
    val starPoints: Int = 5,
    val lineStyle: String = "SOLID", // "SOLID", "DASHED", "DOTTED"
    
    // Layer Association
    val layerId: String = "default_layer",
    
    // Individual corner roundness
    val radiusTL: Float = 0f,
    val radiusTR: Float = 0f,
    val radiusBL: Float = 0f,
    val radiusBR: Float = 0f,
    val cornerRadius: Float = 0f,
    val customCornerRadii: List<Float> = emptyList(),
    
    // Selection Group grouping ID
    val groupId: String? = null
) {
    fun getStrokeColor(): Color = try {
        Color(android.graphics.Color.parseColor(strokeColorHex))
    } catch (_: Exception) {
        Color.Black
    }

    fun getFillColor(): Color = try {
        Color(android.graphics.Color.parseColor(fillColorHex))
    } catch (_: Exception) {
        Color.Green
    }

    // Computes the outer rectangular bounds of the shape without rotation
    fun getBoundingBox(): Rect {
        return when (type) {
            ShapeType.RECTANGLE -> {
                Rect(x, y, x + width, y + height)
            }
            ShapeType.ELLIPSE -> {
                Rect(x - width, y - height, x + width, y + height)
            }
            ShapeType.POLYGON, ShapeType.STAR -> {
                val nodes = getCornerPoints()
                if (nodes.isEmpty()) {
                    Rect(x - width, y - height, x + width, y + height)
                } else {
                    val minX = nodes.minOf { it.x }
                    val maxX = nodes.maxOf { it.x }
                    val minY = nodes.minOf { it.y }
                    val maxY = nodes.maxOf { it.y }
                    Rect(minX, minY, maxX, maxY)
                }
            }
            ShapeType.LINE -> {
                Rect(min(startX, endX), min(startY, endY), max(startX, endX), max(startY, endY))
            }
            ShapeType.FREEHAND -> {
                if (freehandPoints.isEmpty()) return Rect(0f, 0f, 0f, 0f)
                val minX = freehandPoints.minOf { it.x }
                val maxX = freehandPoints.maxOf { it.x }
                val minY = freehandPoints.minOf { it.y }
                val maxY = freehandPoints.maxOf { it.y }
                Rect(minX, minY, maxX, maxY)
            }
            ShapeType.BEZIER_PATH -> {
                if (bezierNodes.isEmpty()) return Rect(0f, 0f, 0f, 0f)
                val minX = bezierNodes.minOf { it.anchorX }
                val maxX = bezierNodes.maxOf { it.anchorX }
                val minY = bezierNodes.minOf { it.anchorY }
                val maxY = bezierNodes.maxOf { it.anchorY }
                Rect(minX, minY, maxX, maxY)
            }
            ShapeType.TEXT -> {
                // Estimated size based on character count and font size
                val estWidth = textContent.length * fontSize * 0.6f
                Rect(x, y - fontSize, x + estWidth, y + fontSize * 0.2f)
            }
        }
    }

    fun getRotatedBounds(): Rect {
        if (type == ShapeType.TEXT) {
            val estWidth = textContent.length * fontSize * 0.6f
            return Rect(x, y - fontSize, x + estWidth, y + fontSize * 0.2f)
        }
        return asComposePath().getBounds()
    }

    // Easy collision test: check if a point (mouse/finger touch) strikes the shape
    fun isPointInside(ptX: Float, ptY: Float): Boolean {
        if (!isVisible) return false
        val bounds = getBoundingBox()
        val cx = (bounds.left + bounds.right) / 2f
        val cy = (bounds.top + bounds.bottom) / 2f
        
        var testX = ptX
        var testY = ptY
        if (rotationAngle != 0f) {
            val rad = Math.toRadians(-rotationAngle.toDouble())
            val cos = kotlin.math.cos(rad).toFloat()
            val sin = kotlin.math.sin(rad).toFloat()
            val dx = ptX - cx
            val dy = ptY - cy
            testX = cx + (dx * cos - dy * sin)
            testY = cy + (dx * sin + dy * cos)
        }
        
        val tolerance = max(15f, strokeWidth + 10f)
        
        when (type) {
            ShapeType.RECTANGLE -> {
                return testX in (bounds.left - tolerance)..(bounds.right + tolerance) &&
                       testY in (bounds.top - tolerance)..(bounds.bottom + tolerance)
            }
            ShapeType.ELLIPSE, ShapeType.POLYGON, ShapeType.STAR -> {
                // Check bounds with some breathing room
                return testX in (bounds.left - tolerance)..(bounds.right + tolerance) &&
                       testY in (bounds.top - tolerance)..(bounds.bottom + tolerance)
            }
            ShapeType.LINE -> {
                // Distance from point to line segment
                val l2 = dist2(startX, startY, endX, endY)
                if (l2 == 0f) return hypot(testX - startX, testY - startY) < tolerance
                var t = ((testX - startX) * (endX - startX) + (testY - startY) * (endY - startY)) / l2
                t = max(0f, min(1f, t))
                val projX = startX + t * (endX - startX)
                val projY = startY + t * (endY - startY)
                return hypot(testX - projX, testY - projY) < tolerance
            }
            ShapeType.FREEHAND -> {
                if (freehandPoints.isEmpty()) return false
                // Check if touch is near any point in the path
                for (p in freehandPoints) {
                    if (hypot(testX - p.x, testY - p.y) < tolerance) return true
                }
                return false
            }
            ShapeType.BEZIER_PATH -> {
                if (bezierNodes.isEmpty()) return false
                for (node in bezierNodes) {
                    if (hypot(testX - node.anchorX, testY - node.anchorY) < tolerance + 15f) return true
                }
                // Check boundary outline box as fallback
                return testX in bounds.left..bounds.right && testY in bounds.top..bounds.bottom
            }
            ShapeType.TEXT -> {
                return testX in (bounds.left - tolerance)..(bounds.right + tolerance) &&
                       testY in (bounds.top - tolerance)..(bounds.bottom + tolerance)
            }
        }
    }

    private fun dist2(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        return (x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1)
    }

    // Creates a new shape offset by the delta translation
    fun copyWithTransform(dx: Float, dy: Float): VectorShape {
        return when (type) {
            ShapeType.RECTANGLE, ShapeType.TEXT -> {
                copy(x = x + dx, y = y + dy)
            }
            ShapeType.ELLIPSE, ShapeType.POLYGON, ShapeType.STAR -> {
                copy(x = x + dx, y = y + dy)
            }
            ShapeType.LINE -> {
                copy(startX = startX + dx, startY = startY + dy, endX = endX + dx, endY = endY + dy)
            }
            ShapeType.FREEHAND -> {
                copy(freehandPoints = freehandPoints.map { SerializedPoint(it.x + dx, it.y + dy) })
            }
            ShapeType.BEZIER_PATH -> {
                copy(bezierNodes = bezierNodes.map { it.copyWithTransform(dx, dy) })
            }
        }
    }

    // Creates a scaled version of the shape from a scale factor and anchor pivot
    fun copyWithScale(scaleX: Float, scaleY: Float, px: Float, py: Float): VectorShape {
        return when (type) {
            ShapeType.RECTANGLE -> {
                val newX = px + (x - px) * scaleX
                val newY = py + (y - py) * scaleY
                val newW = width * scaleX
                val newH = height * scaleY
                // Handle negative scaling correctly
                copy(
                    x = if (newW >= 0) newX else newX + newW,
                    y = if (newH >= 0) newY else newY + newH,
                    width = kotlin.math.abs(newW),
                    height = kotlin.math.abs(newH)
                )
            }
            ShapeType.ELLIPSE, ShapeType.POLYGON, ShapeType.STAR -> {
                copy(
                    x = px + (x - px) * scaleX,
                    y = py + (y - py) * scaleY,
                    width = kotlin.math.abs(width * scaleX),
                    height = kotlin.math.abs(height * scaleY)
                )
            }
            ShapeType.LINE -> {
                copy(
                    startX = px + (startX - px) * scaleX,
                    startY = py + (startY - py) * scaleY,
                    endX = px + (endX - px) * scaleX,
                    endY = py + (endY - py) * scaleY
                )
            }
            ShapeType.FREEHAND -> {
                copy(freehandPoints = freehandPoints.map {
                    SerializedPoint(px + (it.x - px) * scaleX, py + (it.y - py) * scaleY)
                })
            }
            ShapeType.BEZIER_PATH -> {
                copy(bezierNodes = bezierNodes.map {
                    it.copyWithScale(scaleX, scaleY, px, py)
                })
            }
            ShapeType.TEXT -> {
                copy(
                    x = px + (x - px) * scaleX,
                    y = py + (y - py) * scaleY,
                    fontSize = max(8f, fontSize * scaleX)
                )
            }
        }
    }

    // Mirrors the shape horizontally or vertically relative to center pivot of the shape
    fun copyWithFlip(horizontal: Boolean, vertical: Boolean, cx: Float, cy: Float): VectorShape {
        return when (type) {
            ShapeType.RECTANGLE -> {
                val newX = if (horizontal) cx - (x - cx + width) else x
                val newY = if (vertical) cy - (y - cy + height) else y
                copy(x = newX, y = newY)
            }
            ShapeType.ELLIPSE, ShapeType.POLYGON, ShapeType.STAR -> {
                val newX = if (horizontal) cx - (x - cx) else x
                val newY = if (vertical) cy - (y - cy) else y
                copy(x = newX, y = newY)
            }
            ShapeType.LINE -> {
                val sX = if (horizontal) cx - (startX - cx) else startX
                val sY = if (vertical) cy - (startY - cy) else startY
                val eX = if (horizontal) cx - (endX - cx) else endX
                val eY = if (vertical) cy - (endY - cy) else endY
                copy(startX = sX, startY = sY, endX = eX, endY = eY)
            }
            ShapeType.FREEHAND -> {
                copy(freehandPoints = freehandPoints.map {
                    SerializedPoint(
                        if (horizontal) cx - (it.x - cx) else it.x,
                        if (vertical) cy - (it.y - cy) else it.y
                    )
                })
            }
            ShapeType.BEZIER_PATH -> {
                copy(bezierNodes = bezierNodes.map {
                    val ax = if (horizontal) cx - (it.anchorX - cx) else it.anchorX
                    val ay = if (vertical) cy - (it.anchorY - cy) else it.anchorY
                    val c1x = if (horizontal) cx - (it.control1X - cx) else it.control1X
                    val c1y = if (vertical) cy - (it.control1Y - cy) else it.control1Y
                    val c2x = if (horizontal) cx - (it.control2X - cx) else it.control2X
                    val c2y = if (vertical) cy - (it.control2Y - cy) else it.control2Y
                    it.copy(
                        anchorX = ax, anchorY = ay,
                        control1X = c1x, control1Y = c1y,
                        control2X = c2x, control2Y = c2y
                    )
                })
            }
            ShapeType.TEXT -> {
                val newX = if (horizontal) cx - (x - cx) else x
                val newY = if (vertical) cy - (y - cy) else y
                copy(x = newX, y = newY)
            }
        }
    }

    fun getCornerPoints(): List<Offset> {
        val list = mutableListOf<Offset>()
        when (type) {
            ShapeType.RECTANGLE -> {
                list.add(Offset(x, y))                  // 0: TL
                list.add(Offset(x + width, y))          // 1: TR
                list.add(Offset(x + width, y + height)) // 2: BR
                list.add(Offset(x, y + height))         // 3: BL
            }
            ShapeType.POLYGON -> {
                if (polygonSides >= 3) {
                    for (i in 0 until polygonSides) {
                        val angle = i * 2 * Math.PI / polygonSides - Math.PI / 2
                        val px = x + width * kotlin.math.cos(angle).toFloat()
                        val py = y + height * kotlin.math.sin(angle).toFloat()
                        list.add(Offset(px, py))
                    }
                }
            }
            ShapeType.STAR -> {
                if (starPoints >= 3) {
                    val totalPoints = starPoints * 2
                    val innerRx = width * 0.4f
                    val innerRy = height * 0.4f
                    for (i in 0 until totalPoints) {
                        val angle = i * Math.PI / starPoints - Math.PI / 2
                        val rXFactor = if (i % 2 == 0) width else innerRx
                        val rYFactor = if (i % 2 == 0) height else innerRy
                        val px = x + rXFactor * kotlin.math.cos(angle).toFloat()
                        val py = y + rYFactor * kotlin.math.sin(angle).toFloat()
                        list.add(Offset(px, py))
                    }
                }
            }
            ShapeType.BEZIER_PATH -> {
                bezierNodes.forEach {
                    list.add(Offset(it.anchorX, it.anchorY))
                }
            }
            else -> {}
        }
        return list
    }

    fun getNodePoints(): List<Offset> {
        return when (type) {
            ShapeType.LINE -> listOf(Offset(startX, startY), Offset(endX, endY))
            ShapeType.BEZIER_PATH -> bezierNodes.map { Offset(it.anchorX, it.anchorY) }
            ShapeType.FREEHAND -> freehandPoints.map { Offset(it.x, it.y) }
            ShapeType.RECTANGLE, ShapeType.POLYGON, ShapeType.STAR -> getCornerPoints()
            else -> emptyList()
        }
    }

    fun getCornerRadius(index: Int): Float {
        if (type == ShapeType.RECTANGLE) {
            val r = when (index) {
                0 -> radiusTL
                1 -> radiusTR
                2 -> radiusBR
                3 -> radiusBL
                else -> 0f
            }
            return if (r == 0f) cornerRadius else r
        }
        return customCornerRadii.getOrNull(index) ?: 0f
    }

    fun getLiveCornerWidgetPositions(): List<Offset> {
        val vertices = getCornerPoints()
        val list = mutableListOf<Offset>()
        if (vertices.isEmpty()) return list
        
        val n = vertices.size
        for (i in 0 until n) {
            val pi = vertices[i]
            val prevIndex = if (i > 0) i - 1 else n - 1
            val nextIndex = if (i < n - 1) i + 1 else 0
            
            val pPrev = vertices[prevIndex]
            val pNext = vertices[nextIndex]
            
            val v1 = pPrev - pi
            val v2 = pNext - pi
            val L1 = kotlin.math.hypot(v1.x, v1.y)
            val L2 = kotlin.math.hypot(v2.x, v2.y)
            
            if (L1 > 0.1f && L2 > 0.1f) {
                val u1 = Offset(v1.x / L1, v1.y / L1)
                val u2 = Offset(v2.x / L2, v2.y / L2)
                
                // Bisector vector
                val bisectX = u1.x + u2.x
                val bisectY = u1.y + u2.y
                val len = kotlin.math.hypot(bisectX, bisectY)
                if (len > 0.001f) {
                    val ubX = bisectX / len
                    val ubY = bisectY / len
                    val uBisect = Offset(ubX, ubY)
                    
                    val r = getCornerRadius(i)
                    // Visual position: place it along the bisector
                    // Distance is proportional to radius, with a minimum inset of 16px to always remain visible
                    val maxD = minOf(L1, L2) * 0.50f
                    val d = minOf(r * 1.1f + 16f, maxD)
                    list.add(pi + uBisect * d)
                } else {
                    list.add(pi)
                }
            } else {
                list.add(pi)
            }
        }
        return list
    }

    fun bakedRoundedCorners(): VectorShape {
        if (type != ShapeType.BEZIER_PATH || customCornerRadii.none { it > 0.1f }) {
            return this
        }
        val vertices = getCornerPoints()
        if (vertices.isEmpty()) return this
        
        val n = vertices.size
        val drawAsArc = BooleanArray(n)
        val arcStarts = Array(n) { Offset.Zero }
        val arcEnds = Array(n) { Offset.Zero }
        val arcC1 = Array(n) { Offset.Zero }
        val arcC2 = Array(n) { Offset.Zero }
        
        for (i in 0 until n) {
            val pi = vertices[i]
            val prevIndex = if (i > 0) i - 1 else n - 1
            val nextIndex = if (i < n - 1) i + 1 else 0
            
            val pPrev = vertices[prevIndex]
            val pNext = vertices[nextIndex]
            
            val r = customCornerRadii.getOrNull(i) ?: 0f
            if (r > 0.1f) {
                // Call our industry-grade Live Corner solver
                val solver = calculateLiveCorner(pPrev, pi, pNext, r)
                if (solver != null) {
                    drawAsArc[i] = true
                    arcStarts[i] = solver.p0
                    arcEnds[i] = solver.p3
                    arcC1[i] = solver.p1
                    arcC2[i] = solver.p2
                    continue
                }
            }
            drawAsArc[i] = false
        }
        
        val newNodes = mutableListOf<BezierNode>()
        for (i in 0 until n) {
            val nodePos = vertices[i]
            val originalNode = bezierNodes.getOrNull(i) ?: BezierNode(
                anchorX = nodePos.x, anchorY = nodePos.y, 
                control1X = nodePos.x, control1Y = nodePos.y, 
                control2X = nodePos.x, control2Y = nodePos.y
            )
            val isFirst = (i == 0)
            if (drawAsArc[i]) {
                newNodes.add(
                    BezierNode(
                        anchorX = arcStarts[i].x,
                        anchorY = arcStarts[i].y,
                        control1X = arcStarts[i].x,
                        control1Y = arcStarts[i].y,
                        control2X = arcStarts[i].x,
                        control2Y = arcStarts[i].y,
                        isCurve = false,
                        isMoveTo = isFirst && (!isPathClosed || !drawAsArc[n-1])
                    )
                )
                newNodes.add(
                    BezierNode(
                        anchorX = arcEnds[i].x,
                        anchorY = arcEnds[i].y,
                        control1X = arcC1[i].x,
                        control1Y = arcC1[i].y,
                        control2X = arcC2[i].x,
                        control2Y = arcC2[i].y,
                        isCurve = true,
                        isMoveTo = false
                    )
                )
            } else {
                newNodes.add(
                    originalNode.copy(
                        isMoveTo = isFirst && (!isPathClosed || !drawAsArc[n-1])
                    )
                )
            }
        }
        
        return copy(
            bezierNodes = newNodes,
            customCornerRadii = emptyList()
        )
    }

    fun convertCornerPointsToEightBezierNodes(): List<BezierNode> {
        val vertices = getCornerPoints()
        val n = vertices.size
        if (n == 0) return emptyList()
        
        val maxW = width / 2f
        val maxH = height / 2f
        
        val solvers = Array<LiveCornerCurve?>(n) { null }
        val drawAsArc = BooleanArray(n)
        val arcStarts = Array(n) { Offset.Zero }
        val arcEnds = Array(n) { Offset.Zero }
        val arcC1 = Array(n) { Offset.Zero }
        val arcC2 = Array(n) { Offset.Zero }
        
        for (i in 0 until n) {
            val pointB = vertices[i]
            val pointA = vertices[(i - 1 + n) % n]
            val pointC = vertices[(i + 1) % n]
            
            val rRaw = getCornerRadius(i)
            val r = if (type == ShapeType.RECTANGLE) minOf(rRaw, maxW, maxH) else rRaw
            val solver = if (r > 0.1f) calculateLiveCorner(pointA, pointB, pointC, r) else null
            if (solver != null) {
                solvers[i] = solver
                drawAsArc[i] = true
                arcStarts[i] = solver.p0
                arcEnds[i] = solver.p3
                arcC1[i] = solver.p1
                arcC2[i] = solver.p2
            } else {
                drawAsArc[i] = false
                arcStarts[i] = pointB
                arcEnds[i] = pointB
                arcC1[i] = pointB
                arcC2[i] = pointB
            }
        }
        
        val nodes = mutableListOf<BezierNode>()
        if (n != 4) {
            // Fallback for non-rectangles (polygons, stars, etc.)
            for (i in 0 until n) {
                val isFirst = (i == 0)
                if (drawAsArc[i]) {
                    nodes.add(
                        BezierNode(
                            anchorX = arcStarts[i].x,
                            anchorY = arcStarts[i].y,
                            isCurve = true,
                            control1X = arcStarts[i].x,
                            control1Y = arcStarts[i].y,
                            control2X = arcC1[i].x,
                            control2Y = arcC1[i].y,
                            isMoveTo = isFirst
                        )
                    )
                    nodes.add(
                        BezierNode(
                            anchorX = arcEnds[i].x,
                            anchorY = arcEnds[i].y,
                            isCurve = true,
                            control1X = arcC2[i].x,
                            control1Y = arcC2[i].y,
                            control2X = arcEnds[i].x,
                            control2Y = arcEnds[i].y,
                            isMoveTo = false
                        )
                    )
                } else {
                    nodes.add(
                        BezierNode(
                            anchorX = vertices[i].x,
                            anchorY = vertices[i].y,
                            isCurve = false,
                            control1X = vertices[i].x,
                            control1Y = vertices[i].y,
                            control2X = vertices[i].x,
                            control2Y = vertices[i].y,
                            isMoveTo = isFirst
                        )
                    )
                }
            }
            return nodes
        }
        
        // Exactly 4 corners (Rectangle) -> 8 precise nodes
        // Node 0: Exit of TL corner
        nodes.add(BezierNode(
            anchorX = arcEnds[0].x,
            anchorY = arcEnds[0].y,
            isCurve = true,
            control1X = arcC2[0].x,
            control1Y = arcC2[0].y,
            control2X = arcEnds[0].x,
            control2Y = arcEnds[0].y,
            isMoveTo = true
        ))
        
        // Node 1: Entry of TR corner
        nodes.add(BezierNode(
            anchorX = arcStarts[1].x,
            anchorY = arcStarts[1].y,
            isCurve = true,
            control1X = arcStarts[1].x,
            control1Y = arcStarts[1].y,
            control2X = arcC1[1].x,
            control2Y = arcC1[1].y
        ))
        
        // Node 2: Exit of TR corner
        nodes.add(BezierNode(
            anchorX = arcEnds[1].x,
            anchorY = arcEnds[1].y,
            isCurve = true,
            control1X = arcC2[1].x,
            control1Y = arcC2[1].y,
            control2X = arcEnds[1].x,
            control2Y = arcEnds[1].y
        ))
        
        // Node 3: Entry of BR corner
        nodes.add(BezierNode(
            anchorX = arcStarts[2].x,
            anchorY = arcStarts[2].y,
            isCurve = true,
            control1X = arcStarts[2].x,
            control1Y = arcStarts[2].y,
            control2X = arcC1[2].x,
            control2Y = arcC1[2].y
        ))
        
        // Node 4: Exit of BR corner
        nodes.add(BezierNode(
            anchorX = arcEnds[2].x,
            anchorY = arcEnds[2].y,
            isCurve = true,
            control1X = arcC2[2].x,
            control1Y = arcC2[2].y,
            control2X = arcEnds[2].x,
            control2Y = arcEnds[2].y
        ))
        
        // Node 5: Entry of BL corner
        nodes.add(BezierNode(
            anchorX = arcStarts[3].x,
            anchorY = arcStarts[3].y,
            isCurve = true,
            control1X = arcStarts[3].x,
            control1Y = arcStarts[3].y,
            control2X = arcC1[3].x,
            control2Y = arcC1[3].y
        ))
        
        // Node 6: Exit of BL corner
        nodes.add(BezierNode(
            anchorX = arcEnds[3].x,
            anchorY = arcEnds[3].y,
            isCurve = true,
            control1X = arcC2[3].x,
            control1Y = arcC2[3].y,
            control2X = arcEnds[3].x,
            control2Y = arcEnds[3].y
        ))
        
        // Node 7: Entry of TL corner
        nodes.add(BezierNode(
            anchorX = arcStarts[0].x,
            anchorY = arcStarts[0].y,
            isCurve = true,
            control1X = arcStarts[0].x,
            control1Y = arcStarts[0].y,
            control2X = arcC1[0].x,
            control2Y = arcC1[0].y
        ))
        
        return nodes
    }

    fun buildRoundedVertexPath(vertices: List<Offset>, radii: List<Float>): Path {
        val path = Path()
        if (vertices.isEmpty()) return path
        
        val n = vertices.size
        val drawAsArc = BooleanArray(n)
        val arcStarts = Array(n) { Offset.Zero }
        val arcEnds = Array(n) { Offset.Zero }
        val arcC1 = Array(n) { Offset.Zero }
        val arcC2 = Array(n) { Offset.Zero }
        
        for (i in 0 until n) {
            val pointB = vertices[i]
            val pointA = vertices[(i - 1 + n) % n]
            val pointC = vertices[(i + 1) % n]
            
            val r = radii.getOrNull(i) ?: 0f
            if (r > 0.1f) {
                // Call our industry-grade Live Corner solver
                val solver = calculateLiveCorner(pointA, pointB, pointC, r)
                if (solver != null) {
                    drawAsArc[i] = true
                    arcStarts[i] = solver.p0
                    arcEnds[i] = solver.p3
                    arcC1[i] = solver.p1
                    arcC2[i] = solver.p2
                    continue
                }
            }
            drawAsArc[i] = false
        }
        
        val startPt = if (drawAsArc[0]) arcEnds[0] else vertices[0]
        path.moveTo(startPt.x, startPt.y)
        
        for (i in 0 until n) {
            val nextIdx = (i + 1) % n
            
            val nextStart = if (drawAsArc[nextIdx]) arcStarts[nextIdx] else vertices[nextIdx]
            
            path.lineTo(nextStart.x, nextStart.y)
            
            if (drawAsArc[nextIdx]) {
                path.cubicTo(
                    arcC1[nextIdx].x, arcC1[nextIdx].y,
                    arcC2[nextIdx].x, arcC2[nextIdx].y,
                    arcEnds[nextIdx].x, arcEnds[nextIdx].y
                )
            }
        }
        path.close()
        return path
    }

    // Builds a path representation of the shape for direct Compose canvas painting
    fun asComposePath(): Path {
        val path = Path()
        when (type) {
            ShapeType.RECTANGLE -> {
                val vertices = getCornerPoints()
                if (vertices.isNotEmpty()) {
                    val r0 = getCornerRadius(0)
                    val r1 = getCornerRadius(1)
                    val r2 = getCornerRadius(2)
                    val r3 = getCornerRadius(3)
                    val maxW = width / 2f
                    val maxH = height / 2f
                    val radii = listOf(
                        minOf(r0, maxW, maxH),
                        minOf(r1, maxW, maxH),
                        minOf(r2, maxW, maxH),
                        minOf(r3, maxW, maxH)
                    )
                    path.addPath(buildRoundedVertexPath(vertices, radii))
                }
            }
            ShapeType.ELLIPSE -> {
                path.addOval(Rect(x - width, y - height, x + width, y + height))
            }
            ShapeType.POLYGON -> {
                val vertices = getCornerPoints()
                if (vertices.isNotEmpty()) {
                    path.addPath(buildRoundedVertexPath(vertices, customCornerRadii))
                }
            }
            ShapeType.STAR -> {
                val vertices = getCornerPoints()
                if (vertices.isNotEmpty()) {
                    path.addPath(buildRoundedVertexPath(vertices, customCornerRadii))
                }
            }
            ShapeType.LINE -> {
                path.moveTo(startX, startY)
                path.lineTo(endX, endY)
            }
            ShapeType.FREEHAND -> {
                if (freehandPoints.isNotEmpty()) {
                    path.moveTo(freehandPoints.first().x, freehandPoints.first().y)
                    for (i in 1 until freehandPoints.size) {
                        path.lineTo(freehandPoints[i].x, freehandPoints[i].y)
                    }
                }
            }
            ShapeType.BEZIER_PATH -> {
                path.fillType = PathFillType.EvenOdd
                if (bezierNodes.isNotEmpty()) {
                    val hasCorners = customCornerRadii.any { it > 0.1f }
                    if (hasCorners) {
                        val vertices = getCornerPoints()
                        path.addPath(buildRoundedVertexPath(vertices, customCornerRadii))
                    } else {
                        val subpaths = mutableListOf<MutableList<BezierNode>>()
                        var currentSubpath = mutableListOf<BezierNode>()
                        for (node in bezierNodes) {
                            if (node.isMoveTo && currentSubpath.isNotEmpty()) {
                                subpaths.add(currentSubpath)
                                currentSubpath = mutableListOf()
                            }
                            currentSubpath.add(node)
                        }
                        if (currentSubpath.isNotEmpty()) {
                            subpaths.add(currentSubpath)
                        }

                        for (sub in subpaths) {
                            if (sub.isEmpty()) continue
                            val startNode = sub.first()
                            path.moveTo(startNode.anchorX, startNode.anchorY)
                            for (j in 1 until sub.size) {
                                val node = sub[j]
                                val prev = sub[j - 1]
                                if (prev.isCurve || node.isCurve) {
                                    val cp1X = if (prev.isCurve) prev.control2X else prev.anchorX
                                    val cp1Y = if (prev.isCurve) prev.control2Y else prev.anchorY
                                    val cp2X = if (node.isCurve) node.control1X else node.anchorX
                                    val cp2Y = if (node.isCurve) node.control1Y else node.anchorY
                                    path.cubicTo(
                                        cp1X, cp1Y,
                                        cp2X, cp2Y,
                                        node.anchorX, node.anchorY
                                    )
                                } else {
                                    path.lineTo(node.anchorX, node.anchorY)
                                }
                            }
                            if (isPathClosed) {
                                val last = sub.last()
                                if (last.isCurve || startNode.isCurve) {
                                    val cp1X = if (last.isCurve) last.control2X else last.anchorX
                                    val cp1Y = if (last.isCurve) last.control2Y else last.anchorY
                                    val cp2X = if (startNode.isCurve) startNode.control1X else startNode.anchorX
                                    val cp2Y = if (startNode.isCurve) startNode.control1Y else startNode.anchorY
                                    path.cubicTo(
                                        cp1X, cp1Y,
                                        cp2X, cp2Y,
                                        startNode.anchorX, startNode.anchorY
                                    )
                                } else {
                                    path.lineTo(startNode.anchorX, startNode.anchorY)
                                }
                                path.close()
                            }
                        }
                    }
                }
            }
            ShapeType.TEXT -> {
                // Text has no standard geometric path representation in simple canvas
            }
        }
        if (rotationAngle != 0f && type != ShapeType.TEXT) {
            val bounds = getBoundingBox()
            val cx = (bounds.left + bounds.right) / 2f
            val cy = (bounds.top + bounds.bottom) / 2f
            val m = android.graphics.Matrix()
            m.postRotate(rotationAngle, cx, cy)
            path.asAndroidPath().transform(m)
        }
        return path
    }
}
