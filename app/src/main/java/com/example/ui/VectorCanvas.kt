package com.example.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onSizeChanged
import com.example.model.BezierNode
import com.example.model.checkHandleHit
import com.example.model.checkNodeHit
import com.example.model.SerializedPoint
import com.example.model.ShapeType
import com.example.model.VectorShape
import com.example.viewmodel.PrimitiveType
import com.example.viewmodel.VectorTool
import com.example.viewmodel.VectorViewModel
import kotlin.math.abs
import kotlin.math.hypot

private fun mapStrokeJoin(joinStr: String): StrokeJoin {
    return when (joinStr.uppercase()) {
        "MITER" -> StrokeJoin.Miter
        "BEVEL" -> StrokeJoin.Bevel
        else -> StrokeJoin.Round
    }
}

private fun mapStrokeCap(capStr: String): StrokeCap {
    return when (capStr.uppercase()) {
        "BUTT" -> StrokeCap.Butt
        "SQUARE" -> StrokeCap.Square
        else -> StrokeCap.Round
    }
}

private fun mapLineStyle(styleStr: String, strokeWidth: Float): PathEffect? {
    val sw = maxOf(strokeWidth, 1f)
    return when (styleStr.uppercase()) {
        "DASHED" -> PathEffect.dashPathEffect(floatArrayOf(sw * 4f, sw * 4f), 0f)
        "DOTTED" -> PathEffect.dashPathEffect(floatArrayOf(sw, sw * 2f), 0f)
        else -> null
    }
}

private fun rotatePoint(p: Offset, c: Offset, angleDegrees: Float): Offset {
    val rad = Math.toRadians(angleDegrees.toDouble())
    val cos = kotlin.math.cos(rad).toFloat()
    val sin = kotlin.math.sin(rad).toFloat()
    val dx = p.x - c.x
    val dy = p.y - c.y
    return Offset(
        c.x + (dx * cos - dy * sin),
        c.y + (dx * sin + dy * cos)
    )
}

private fun getRotatedCombinedBoundingBox(shapeIds: Set<String>, list: List<VectorShape>): Rect? {
    val selectedShapes = list.filter { shapeIds.contains(it.id) }
    if (selectedShapes.isEmpty()) return null
    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    for (shape in selectedShapes) {
        val b = shape.getRotatedBounds()
        if (b.left < minX) minX = b.left
        if (b.top < minY) minY = b.top
        if (b.right > maxX) maxX = b.right
        if (b.bottom > maxY) maxY = b.bottom
    }
    return Rect(minX, minY, maxX, maxY)
}

private fun getUnrotatedCombinedBoundingBox(shapeIds: Set<String>, list: List<VectorShape>): Rect? {
    val selectedShapes = list.filter { shapeIds.contains(it.id) }
    if (selectedShapes.isEmpty()) return null
    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    for (shape in selectedShapes) {
        val b = shape.getBoundingBox()
        if (b.left < minX) minX = b.left
        if (b.top < minY) minY = b.top
        if (b.right > maxX) maxX = b.right
        if (b.bottom > maxY) maxY = b.bottom
    }
    return Rect(minX, minY, maxX, maxY)
}

@Composable
fun VectorCanvas(
    viewModel: VectorViewModel,
    modifier: Modifier = Modifier
) {
    // Current gesture states
    var startOffset by remember { mutableStateOf<Offset?>(null) }
    var lastDragOffset by remember { mutableStateOf<Offset?>(null) }
    var activeDragHandle by remember { mutableStateOf<String?>(null) } // "TL", "TR", "BL", "BR", "MOVE", "MARQUEE_SELECT"
    
    // Live coordinate preview for drawing
    var currentTouchPos by remember { mutableStateOf<Offset?>(null) }
    var startCornerRadius by remember { mutableStateOf(0f) }

    var dragStartShapes by remember { mutableStateOf<List<VectorShape>?>(null) }
    var initialTouchCanvasPos by remember { mutableStateOf<Offset?>(null) }
    var initialDragPivot by remember { mutableStateOf<Offset?>(null) }

    fun getPrimaryAnchor(shape: VectorShape): Offset {
        return when (shape.type) {
            ShapeType.LINE -> Offset(shape.startX, shape.startY)
            ShapeType.BEZIER_PATH -> {
                if (shape.bezierNodes.isNotEmpty()) {
                    Offset(shape.bezierNodes.first().anchorX, shape.bezierNodes.first().anchorY)
                } else {
                    Offset(shape.x, shape.y)
                }
            }
            ShapeType.FREEHAND -> {
                if (shape.freehandPoints.isNotEmpty()) {
                    Offset(shape.freehandPoints.first().x, shape.freehandPoints.first().y)
                } else {
                    Offset(shape.x, shape.y)
                }
            }
            else -> Offset(shape.x, shape.y)
        }
    }

    fun getHandleStartPos(bounds: Rect, handle: String): Offset {
        return when (handle) {
            "BR" -> Offset(bounds.right, bounds.bottom)
            "TL" -> Offset(bounds.left, bounds.top)
            "TR" -> Offset(bounds.right, bounds.top)
            "BL" -> Offset(bounds.left, bounds.bottom)
            "T" -> Offset((bounds.left + bounds.right)/2f, bounds.top)
            "B" -> Offset((bounds.left + bounds.right)/2f, bounds.bottom)
            "L" -> Offset(bounds.left, (bounds.top + bounds.bottom)/2f)
            "R" -> Offset(bounds.right, (bounds.top + bounds.bottom)/2f)
            else -> Offset.Zero
        }
    }

    // Screen-to-Canvas coord converters
    fun screenToCanvas(screenOffset: Offset): Offset {
        return Offset(
            (screenOffset.x - viewModel.panOffset.x) / viewModel.zoomScale,
            (screenOffset.y - viewModel.panOffset.y) / viewModel.zoomScale
        )
    }

    var isFirstLayout by remember { mutableStateOf(true) }
    var activePenDragMode by remember { mutableStateOf<String?>(null) }
    var activePenIsMovementDetected by remember { mutableStateOf(false) }
    var activePenStartX by remember { mutableStateOf(0f) }
    var activePenStartY by remember { mutableStateOf(0f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFE2E8F0)) // Modern sleek slate background for workspace
            .border(1.dp, Color(0xFFCBD5E1))
            .onSizeChanged { size ->
                if (isFirstLayout && size.width > 0 && size.height > 0) {
                    val startScale = 0.4f
                    viewModel.zoomScale = startScale
                    viewModel.panOffset = Offset(
                        (size.width - viewModel.canvasWidth * startScale) / 2f,
                        (size.height - viewModel.canvasHeight * startScale) / 2f
                    )
                    isFirstLayout = false
                }
            }
            .pointerInput(viewModel.currentTool) {
                awaitPointerEventScope {
                    var isDragging = false
                    var isMultiTouch = false
                    
                    var cloneSourceShape: com.example.model.VectorShape? = null
                    var cloneTouchStart: Offset? = null
                    var hasClonedForDrag = false
                    
                    // State machine variables for Pen Tool
                    var penDragMode: String? = null // "handle", "node", "create"
                    var penActiveHandleHit: com.example.model.HandleHit? = null
                    var penActiveNodeIndex: Int = -1
                    var penIsMovementDetected = false
                    var penTouchStartTime = 0L
                    var penStartX = 0f
                    var penStartY = 0f
                    
                    var penActiveSegmentIndex = -1
                    var penActiveSegmentT = 0f
                    var penSegmentStartControl2X = 0f
                    var penSegmentStartControl2Y = 0f
                    var penSegmentStartControl1X = 0f
                    var penSegmentStartControl1Y = 0f
                    var lastPenDragUpdateTime = 0L
                    
                    var directSelectionActiveSegmentIndex = -1
                    var directSelectionActiveSegmentT = 0f
                    var directSelectionSegmentStartControl2X = 0f
                    var directSelectionSegmentStartControl2Y = 0f
                    var directSelectionSegmentStartControl1X = 0f
                    var directSelectionSegmentStartControl1Y = 0f
                    var directSelectionActiveHandleNodeIndex = -1
                    var directSelectionActiveHandleType = ""
                    var touchedUnselectedShapeOnDown: String? = null

                    while (true) {
                        val event = awaitPointerEvent()
                        val changes = event.changes
                        
                        if (changes.any { it.pressed }) {
                            if (changes.size > 1) {
                                if (!isMultiTouch && viewModel.currentTool == VectorTool.PEN && penDragMode == "create") {
                                    viewModel.removeLastBezierNode()
                                    penDragMode = null
                                }
                                isMultiTouch = true
                                isDragging = false
                                changes.forEach { it.consume() }
                                
                                val change0 = changes[0]
                                val change1 = changes[1]
                                val prevDist = (change0.previousPosition - change1.previousPosition).getDistance()
                                val currDist = (change0.position - change1.position).getDistance()
                                val prevCenter = (change0.previousPosition + change1.previousPosition) / 2f
                                val currCenter = (change0.position + change1.position) / 2f

                                if (prevDist > 0f) {
                                    val scale = currDist / prevDist
                                    val prevScale = viewModel.zoomScale
                                    val newScale = (prevScale * scale).coerceIn(0.01f, 100.0f)
                                    viewModel.zoomScale = newScale

                                    val canvasPivot = (prevCenter - viewModel.panOffset) / prevScale
                                    viewModel.panOffset = currCenter - (canvasPivot * newScale)
                                } else {
                                    val deltaPan = currCenter - prevCenter
                                    viewModel.panOffset = viewModel.panOffset + deltaPan
                                }
                            } else {
                                if (isMultiTouch) {
                                    changes.forEach { it.consume() }
                                } else {
                                    val change = changes[0]
                                    val screenPos = change.position
                                    val rawCanvasPos = screenToCanvas(screenPos)
                                    val canvasPos = viewModel.snapOffsetComprehensive(rawCanvasPos, viewModel.selectedShapeId)

                                    if (!isDragging) {
                                        isDragging = true
                                        startOffset = canvasPos
                                        lastDragOffset = canvasPos
                                        currentTouchPos = canvasPos
                                        dragStartShapes = viewModel.shapes
                                        initialTouchCanvasPos = rawCanvasPos
                                        viewModel.isAutosaveSuspended = true
                                        touchedUnselectedShapeOnDown = null
                                        val lockedLayerIds = viewModel.layers.filter { it.isLocked }.map { it.id }.toSet()
                                        
                                        // 1. TOOL SPECIFIC DOWN LOGIC
                                        if (viewModel.currentTool == VectorTool.PEN) {
                                            val lastNodeVal: com.example.model.BezierNode? = null
                                            var hitFloater = false
                                            if (lastNodeVal != null) {
                                                val lastNodeScreenX = lastNodeVal.anchorX * viewModel.zoomScale + viewModel.panOffset.x
                                                val lastNodeScreenY = lastNodeVal.anchorY * viewModel.zoomScale + viewModel.panOffset.y
                                                
                                                val density = this
                                                val offsetADpX = with(density) { 45.dp.toPx() }
                                                val offsetADpY = with(density) { -20.dp.toPx() }
                                                val offsetBDpX = with(density) { 30.dp.toPx() }
                                                val offsetBDpY = with(density) { 35.dp.toPx() }
                                                val buttonTouchRadius = with(density) { 26.dp.toPx() }
                                                
                                                val buttonACenter = Offset(lastNodeScreenX + offsetADpX, lastNodeScreenY + offsetADpY)
                                                val buttonBCenter = Offset(lastNodeScreenX + offsetBDpX, lastNodeScreenY + offsetBDpY)
                                                
                                                if ((screenPos - buttonACenter).getDistance() < buttonTouchRadius) {
                                                     viewModel.finalizeBezierPath(isClosed = true)
                                                     hitFloater = true
                                                } else if ((screenPos - buttonBCenter).getDistance() < buttonTouchRadius) {
                                                     viewModel.finalizeBezierPath(isClosed = false)
                                                     hitFloater = true
                                                }
                                            }
                                            
                                            if (hitFloater) {
                                                isDragging = false
                                                change.consume()
                                            } else {
                                                penTouchStartTime = System.currentTimeMillis()
                                            penStartX = rawCanvasPos.x
                                            penStartY = rawCanvasPos.y
                                            penIsMovementDetected = false
                                            
                                            // A. Cek apakah menyentuh HANDLE duluan (Prioritas Tertinggi)
                                            val handleHit = com.example.model.checkHandleHit(
                                                rawCanvasPos.x,
                                                rawCanvasPos.y,
                                                viewModel.activeBezierNodes,
                                                20f / viewModel.zoomScale
                                            )
                                            if (handleHit != null) {
                                                penDragMode = "handle"
                                                penActiveHandleHit = handleHit
                                                viewModel.activeEditNodeIndex = handleHit.nodeIndex
                                            } else {
                                                // B. Cek apakah menyentuh TITIK NODE
                                                val nodeHitIndex = com.example.model.checkNodeHit(
                                                    rawCanvasPos.x,
                                                    rawCanvasPos.y,
                                                    viewModel.activeBezierNodes,
                                                    20f / viewModel.zoomScale
                                                )
                                                if (nodeHitIndex != -1) {
                                                    penDragMode = "node"
                                                    penActiveNodeIndex = nodeHitIndex
                                                    viewModel.activeEditNodeIndex = nodeHitIndex
                                                } else {
                                                    // Cek apakah menyentuh garis segmen diantara node pen tool
                                                    val nearestSeg = findNearestSegment(
                                                        rawCanvasPos,
                                                        viewModel.activeBezierNodes,
                                                        isClosed = false,
                                                        tolerance = 25f / viewModel.zoomScale
                                                    )
                                                    if (nearestSeg != null) {
                                                        penDragMode = "segment"
                                                        penActiveSegmentIndex = nearestSeg.segmentIndex
                                                        penActiveSegmentT = nearestSeg.t
                                                        
                                                        val nextIdx = nearestSeg.segmentIndex
                                                        val prevIdx = nearestSeg.segmentIndex - 1
                                                        
                                                        val nPrev = viewModel.activeBezierNodes[prevIdx]
                                                        val nNext = viewModel.activeBezierNodes[nextIdx]
                                                        
                                                        penSegmentStartControl2X = if (nPrev.isCurve) nPrev.control2X else nPrev.anchorX
                                                        penSegmentStartControl2Y = if (nPrev.isCurve) nPrev.control2Y else nPrev.anchorY
                                                        penSegmentStartControl1X = if (nNext.isCurve) nNext.control1X else nNext.anchorX
                                                        penSegmentStartControl1Y = if (nNext.isCurve) nNext.control1Y else nNext.anchorY
                                                        
                                                        viewModel.pushToUndoStack()
                                                    } else {
                                                        // C. Jika area kosong, bersiap BIKIN NODE BARU pada pointerup
                                                        // C. Jika area kosong, langsung bikin node baru di touch down agar bisa ditarik/drag handlesnya
                                                         val snapRaw = viewModel.snapOffset(rawCanvasPos)
                                                         val isCloseToStart = false && viewModel.activeBezierNodes.isNotEmpty() &&
                                                                 hypot(snapRaw.x - viewModel.activeBezierNodes.first().anchorX, snapRaw.y - viewModel.activeBezierNodes.first().anchorY) < 20f / viewModel.zoomScale
                                                         
                                                         if (isCloseToStart) {
                                                             viewModel.finalizeBezierPath(isClosed = true)
                                                             isDragging = false
                                                             change.consume()
                                                         } else {
                                                             viewModel.addBezierPenPointImmediately(snapRaw)
                                                             penDragMode = "create"
                                                             penStartX = snapRaw.x
                                                             penStartY = snapRaw.y
                                                             penActiveNodeIndex = viewModel.activeBezierNodes.size - 1
                                                             viewModel.activeEditNodeIndex = penActiveNodeIndex
                                                         }
                                                    }
                                                }
                                            }
                                            }
                                        } else if (viewModel.currentTool == VectorTool.POINTER) {
                                            if (viewModel.isCloneModeActive) {
                                                val hitShape = viewModel.shapes.findLast { !it.isLocked && !lockedLayerIds.contains(it.layerId) && it.isPointInside(rawCanvasPos.x, rawCanvasPos.y) }
                                                if (hitShape != null) {
                                                    cloneSourceShape = hitShape
                                                    cloneTouchStart = rawCanvasPos
                                                    hasClonedForDrag = false
                                                    viewModel.selectShapeAt(rawCanvasPos)
                                                    activeDragHandle = "CLONE_PENDING"
                                                } else {
                                                    cloneSourceShape = null
                                                    cloneTouchStart = null
                                                    hasClonedForDrag = false
                                                    activeDragHandle = "MARQUEE_SELECT"
                                                }
                                            } else if (true) {
                                            val singleIdTemp = viewModel.selectedShapeIds.firstOrNull()
                                            val bounds = if (viewModel.selectedShapeIds.size == 1 && singleIdTemp != null) {
                                                getUnrotatedCombinedBoundingBox(viewModel.selectedShapeIds, viewModel.shapes)
                                            } else {
                                                getRotatedCombinedBoundingBox(viewModel.selectedShapeIds, viewModel.shapes)
                                            }
                                            val hSize = 30f / viewModel.zoomScale
                                            val clickT = 38f / viewModel.zoomScale

                                            if (bounds != null) {
                                                val b = bounds
                                                val centerX = (b.left + b.right) / 2f
                                                val centerY = (b.top + b.bottom) / 2f
                                                val topB = b.top - 54f / viewModel.zoomScale
                                                val botB = b.bottom + 54f / viewModel.zoomScale
                                                
                                                val delPos = Offset(centerX - 42f / viewModel.zoomScale, topB)
                                                val dupPos = Offset(centerX + 42f / viewModel.zoomScale, topB)
                                                val rotPos = Offset(centerX - 70f / viewModel.zoomScale, botB)
                                                val movPos = Offset(centerX, botB)
                                                val sclPos = Offset(centerX + 70f / viewModel.zoomScale, botB)

                                                val singleId = viewModel.selectedShapeIds.firstOrNull()
                                                val rotationAngle = if (viewModel.selectedShapeIds.size == 1 && singleId != null) {
                                                    viewModel.shapes.find { it.id == singleId }?.rotationAngle ?: 0f
                                                } else {
                                                    0f
                                                }

                                                val localPos = if (rotationAngle != 0f) {
                                                    val rad = Math.toRadians(-rotationAngle.toDouble())
                                                    val cos = kotlin.math.cos(rad).toFloat()
                                                    val sin = kotlin.math.sin(rad).toFloat()
                                                    val dx = rawCanvasPos.x - centerX
                                                    val dy = rawCanvasPos.y - centerY
                                                    Offset(
                                                        centerX + (dx * cos - dy * sin),
                                                        centerY + (dx * sin + dy * cos)
                                                    )
                                                } else {
                                                    rawCanvasPos
                                                }

                                                when {
                                                    hypot(localPos.x - delPos.x, localPos.y - delPos.y) < clickT -> activeDragHandle = "DELETE_HOTSPOT"
                                                    hypot(localPos.x - dupPos.x, localPos.y - dupPos.y) < clickT -> activeDragHandle = "DUPLICATE_HOTSPOT"
                                                    hypot(localPos.x - rotPos.x, localPos.y - rotPos.y) < clickT -> activeDragHandle = "ROTATE_HOTSPOT"
                                                    hypot(localPos.x - movPos.x, localPos.y - movPos.y) < clickT -> activeDragHandle = "MOVE"
                                                    hypot(localPos.x - sclPos.x, localPos.y - sclPos.y) < clickT -> {
                                                        viewModel.isAspectLocked = !viewModel.isAspectLocked
                                                        activeDragHandle = "LOCK_TOGGLE_HOTSPOT"
                                                    }
                                                    
                                                    hypot(localPos.x - b.left, localPos.y - b.top) < hSize * 2 -> activeDragHandle = "TL"
                                                    hypot(localPos.x - b.right, localPos.y - b.top) < hSize * 2 -> activeDragHandle = "TR"
                                                    hypot(localPos.x - b.left, localPos.y - b.bottom) < hSize * 2 -> activeDragHandle = "BL"
                                                    hypot(localPos.x - b.right, localPos.y - b.bottom) < hSize * 2 -> activeDragHandle = "BR"

                                                    hypot(localPos.x - centerX, localPos.y - b.top) < hSize * 2 -> activeDragHandle = "T"
                                                    hypot(localPos.x - centerX, localPos.y - b.bottom) < hSize * 2 -> activeDragHandle = "B"
                                                    hypot(localPos.x - b.left, localPos.y - ((b.top + b.bottom)/2f)) < hSize * 2 -> activeDragHandle = "L"
                                                    hypot(localPos.x - b.right, localPos.y - ((b.top + b.bottom)/2f)) < hSize * 2 -> activeDragHandle = "R"

                                                    viewModel.shapes.any { s -> !s.isLocked && !lockedLayerIds.contains(s.layerId) && viewModel.selectedShapeIds.contains(s.id) && s.isPointInside(rawCanvasPos.x, rawCanvasPos.y) } -> {
                                                        activeDragHandle = "MOVE"
                                                        touchedUnselectedShapeOnDown = viewModel.selectedShapeIds.firstOrNull { id -> viewModel.shapes.find { it.id == id }?.let { !it.isLocked && !lockedLayerIds.contains(it.layerId) && it.isPointInside(rawCanvasPos.x, rawCanvasPos.y) } == true }
                                                    }
                                                    else -> {
                                                        val hitShape = viewModel.shapes.findLast { !it.isLocked && !lockedLayerIds.contains(it.layerId) && it.isPointInside(rawCanvasPos.x, rawCanvasPos.y) }
                                                        if (hitShape != null) {
                                                            activeDragHandle = "MOVE"
                                                            touchedUnselectedShapeOnDown = hitShape.id
                                                            // We do NOT modify selection here. We will check it during drag or tap.
                                                        } else if (viewModel.selectedShapeIds.isNotEmpty()) {
                                                            activeDragHandle = "MOVE"
                                                            viewModel.pushToUndoStack()
                                                        } else {
                                                            activeDragHandle = "MARQUEE_SELECT"
                                                        }
                                                    }
                                                }
                                            } else {
                                                val hitShape = viewModel.shapes.findLast { !it.isLocked && !lockedLayerIds.contains(it.layerId) && it.isPointInside(rawCanvasPos.x, rawCanvasPos.y) }
                                                if (hitShape != null) {
                                                    activeDragHandle = "MOVE"
                                                    touchedUnselectedShapeOnDown = hitShape.id
                                                } else {
                                                    activeDragHandle = "MARQUEE_SELECT"
                                                }
                                            }
                                            }
                                        } else if (viewModel.currentTool == VectorTool.BRUSH) {
                                            viewModel.activeFreehandPoints = listOf(SerializedPoint(canvasPos.x, canvasPos.y))
                                        } else if (viewModel.currentTool == VectorTool.DIRECT_SELECTION) {
                                            val sId = viewModel.selectedShapeId
                                            if (sId != null) {
                                                var shape = viewModel.shapes.find { s -> s.id == sId }
                                                if (shape != null && shape.type != com.example.model.ShapeType.BEZIER_PATH) {
                                                    viewModel.convertShapeToBezierPath(sId)
                                                    shape = viewModel.shapes.find { s -> s.id == sId }
                                                }
                                                 if (shape != null) {
                                                    val bounds = shape.getBoundingBox()
                                                    val cx = (bounds.left + bounds.right) / 2f
                                                    val cy = (bounds.top + bounds.bottom) / 2f
                                                    val centerPt = Offset(cx, cy)
                                                    initialDragPivot = centerPt
                                                    val localClickPos = if (shape.rotationAngle != 0f) {
                                                        rotatePoint(rawCanvasPos, centerPt, -shape.rotationAngle)
                                                    } else {
                                                        rawCanvasPos
                                                     }

                                                    val nodes = shape.getNodePoints()
                                                    val targetTolerance = 20f / viewModel.zoomScale
                                                    var handleHitIdx = -1
                                                    var handleHitType = ""
                                                    val hitTolerance = 24f / viewModel.zoomScale
                                                    if (shape.type == com.example.model.ShapeType.BEZIER_PATH) {
                                                        for (i in shape.bezierNodes.indices) {
                                                            val node = shape.bezierNodes[i]
                                                            if (node.isCurve) {
                                                                if (hypot(localClickPos.x - node.control1X, localClickPos.y - node.control1Y) < hitTolerance) {
                                                                    handleHitIdx = i
                                                                    handleHitType = "in"
                                                                    break
                                                                }
                                                                if (hypot(localClickPos.x - node.control2X, localClickPos.y - node.control2Y) < hitTolerance) {
                                                                    handleHitIdx = i
                                                                    handleHitType = "out"
                                                                    break
                                                                }
                                                            }
                                                        }
                                                    }
                                                    val nodeIdx = nodes.indexOfFirst { hypot(localClickPos.x - it.x, localClickPos.y - it.y) < 24f / viewModel.zoomScale }
                                                    if (handleHitIdx != -1) {
                                                        activeDragHandle = "DIRECT_HANDLE"
                                                        directSelectionActiveHandleNodeIndex = handleHitIdx
                                                        directSelectionActiveHandleType = handleHitType
                                                        viewModel.pushToUndoStack()
                                                    } else if (nodeIdx != -1) {
                                                        viewModel.selectedDirectSelectionNodes = viewModel.selectedDirectSelectionNodes + nodeIdx
                                                        directSelectionActiveHandleNodeIndex = nodeIdx
                                                        activeDragHandle = "DIRECT_NODE"
                                                    } else if (shape.type == com.example.model.ShapeType.BEZIER_PATH) {
                                                        val nearestSeg = findNearestSegment(
                                                            localClickPos,
                                                            shape.bezierNodes,
                                                            isClosed = shape.isPathClosed,
                                                            tolerance = 24f / viewModel.zoomScale
                                                        )
                                                        if (nearestSeg != null) {
                                                            activeDragHandle = "DIRECT_SEGMENT"
                                                            directSelectionActiveSegmentIndex = nearestSeg.segmentIndex
                                                            directSelectionActiveSegmentT = nearestSeg.t
                                                            
                                                            val nextIdx = if (nearestSeg.isClosedSegment) 0 else nearestSeg.segmentIndex
                                                            val prevIdx = if (nearestSeg.isClosedSegment) shape.bezierNodes.size - 1 else nearestSeg.segmentIndex - 1
                                                            
                                                            val nPrev = shape.bezierNodes[prevIdx]
                                                            val nNext = shape.bezierNodes[nextIdx]
                                                            
                                                            directSelectionSegmentStartControl2X = if (nPrev.isCurve) nPrev.control2X else nPrev.anchorX
                                                            directSelectionSegmentStartControl2Y = if (nPrev.isCurve) nPrev.control2Y else nPrev.anchorY
                                                            directSelectionSegmentStartControl1X = if (nNext.isCurve) nNext.control1X else nNext.anchorX
                                                            directSelectionSegmentStartControl1Y = if (nNext.isCurve) nNext.control1Y else nNext.anchorY
                                                            
                                                            viewModel.pushToUndoStack()
                                                        } else {
                                                            if (viewModel.selectedDirectSelectionNodes.isNotEmpty()) {
                                                                activeDragHandle = "DIRECT_DRAG_ANYWHERE"
                                                                viewModel.pushToUndoStack()
                                                            } else {
                                                                val clickedShape = viewModel.shapes.findLast { !it.isLocked && !lockedLayerIds.contains(it.layerId) && it.isPointInside(rawCanvasPos.x, rawCanvasPos.y) }
                                                                if (clickedShape != null) {
                                                                    viewModel.selectedShapeId = clickedShape.id
                                                                    viewModel.clearDirectSelection()
                                                                    activeDragHandle = "NONE"
                                                                } else {
                                                                    viewModel.clearDirectSelection()
                                                                    viewModel.selectedShapeId = null
                                                                    activeDragHandle = "NONE"
                                                                }
                                                            }
                                                        }
                                                    } else {
                                                        val clickedShape = viewModel.shapes.findLast { !it.isLocked && !lockedLayerIds.contains(it.layerId) && it.isPointInside(rawCanvasPos.x, rawCanvasPos.y) }
                                                        if (clickedShape != null) {
                                                            viewModel.selectedShapeId = clickedShape.id
                                                            viewModel.clearDirectSelection()
                                                            activeDragHandle = "NONE"
                                                        } else {
                                                            viewModel.clearDirectSelection()
                                                            viewModel.selectedShapeId = null
                                                            activeDragHandle = "NONE"
                                                        }
                                                    }
                                                }
                                            } else {
                                                val clickedShape = viewModel.shapes.findLast { !it.isLocked && !lockedLayerIds.contains(it.layerId) && it.isPointInside(rawCanvasPos.x, rawCanvasPos.y) }
                                                if (clickedShape != null) {
                                                    viewModel.selectedShapeId = clickedShape.id
                                                }
                                            }
                                        } else if (viewModel.currentTool == VectorTool.ROUNDED_CORNER) {
                                            val sId = viewModel.selectedShapeId
                                            if (sId != null) {
                                                val shape = viewModel.shapes.find { s -> s.id == sId }
                                                if (shape != null) {
                                                    val corners = shape.getLiveCornerWidgetPositions()
                                                    val targetTolerance = 20f / viewModel.zoomScale
                                                    val cornerIdx = corners.indexOfFirst { hypot(rawCanvasPos.x - it.x, rawCanvasPos.y - it.y) < 28f / viewModel.zoomScale }
                                                    if (cornerIdx != -1) {
                                                        viewModel.selectedRoundedCornerIndex = cornerIdx
                                                        activeDragHandle = "ROUNDED_CORNER_NODE"
                                                        startCornerRadius = shape.getCornerRadius(cornerIdx)
                                                        viewModel.manualCornerRadiusText = startCornerRadius.toInt().toString()
                                                    } else {
                                                        viewModel.selectedRoundedCornerIndex = null
                                                        activeDragHandle = "ROUNDED_CORNER_ALL"
                                                        startCornerRadius = shape.getCornerRadius(0)
                                                        viewModel.manualCornerRadiusText = startCornerRadius.toInt().toString()
                                                    }
                                                }
                                            } else {
                                                val clickedShape = viewModel.shapes.findLast { !it.isLocked && !lockedLayerIds.contains(it.layerId) && it.isPointInside(rawCanvasPos.x, rawCanvasPos.y) }
                                                if (clickedShape != null) {
                                                    viewModel.selectedShapeId = clickedShape.id
                                                    startCornerRadius = clickedShape.getCornerRadius(0)
                                                    viewModel.manualCornerRadiusText = startCornerRadius.toInt().toString()
                                                    viewModel.selectedRoundedCornerIndex = null
                                                    activeDragHandle = "ROUNDED_CORNER_ALL"
                                                }
                                            }
                                        }
                                    } else {
                                        // 2. TOOL SPECIFIC MOVE LOGIC
                                        currentTouchPos = canvasPos
                                        
                                        if (viewModel.currentTool == VectorTool.PEN) {
                                            val touchX = rawCanvasPos.x
                                            val touchY = rawCanvasPos.y
                                            
                                            // Hitung apakah jari beneran niat nge-drag atau cuma goyang sedikit
                                            if (hypot(touchX - penStartX, touchY - penStartY) > 8f / viewModel.zoomScale) {
                                                penIsMovementDetected = true
                                            }
                                            
                                            if (penIsMovementDetected && System.currentTimeMillis() - lastPenDragUpdateTime >= 16L) {
                                                lastPenDragUpdateTime = System.currentTimeMillis()
                                                when (penDragMode) {
                                                    "handle" -> {
                                                        // Update koordinat handle yang sedang ditarik dengan snapping
                                                        val snapH = if (viewModel.isSnapToGrid) Offset(kotlin.math.round(rawCanvasPos.x / viewModel.gridSize) * viewModel.gridSize, kotlin.math.round(rawCanvasPos.y / viewModel.gridSize) * viewModel.gridSize) else rawCanvasPos
                                                        viewModel.updateActiveBezierHandle(
                                                            penActiveHandleHit!!.nodeIndex,
                                                            penActiveHandleHit.handleType,
                                                            snapH
                                                        )
                                                    }
                                                    "node" -> {
                                                        // Geser titik utama node dengan snapping
                                                        val snapN = viewModel.snapNodeComprehensive(rawCanvasPos, viewModel.selectedShapeId, penActiveNodeIndex)
                                                        viewModel.updateActiveBezierNode(penActiveNodeIndex, snapN)
                                                    }
                                                    "create" -> {
                                                         viewModel.updateActiveBezierNodeHandlesOnDrag(
                                                             index = penActiveNodeIndex,
                                                             dragPos = rawCanvasPos
                                                         )
                                                     }
                                                     "segment" -> {
                                                        val dx = rawCanvasPos.x - penStartX
                                                        val dy = rawCanvasPos.y - penStartY
                                                        val nextIdx = penActiveSegmentIndex
                                                        val prevIdx = penActiveSegmentIndex - 1
                                                        viewModel.updateActiveBezierSegmentDelta(
                                                            prevIdx = prevIdx,
                                                            nextIdx = nextIdx,
                                                            dx = dx,
                                                            dy = dy,
                                                            t = penActiveSegmentT,
                                                            startC2X = penSegmentStartControl2X,
                                                            startC2Y = penSegmentStartControl2Y,
                                                            startC1X = penSegmentStartControl1X,
                                                            startC1Y = penSegmentStartControl1Y
                                                        )
                                                    }
                                                }
                                            }
                                        } else {
                                            when (viewModel.currentTool) {
                                                VectorTool.BRUSH -> {
                                                    viewModel.activeFreehandPoints = viewModel.activeFreehandPoints + SerializedPoint(canvasPos.x, canvasPos.y)
                                                }
                                                VectorTool.POINTER -> {
                                                    if (activeDragHandle == "CLONE_PENDING" && cloneSourceShape != null && cloneTouchStart != null) {
                                                        val dx = rawCanvasPos.x - cloneTouchStart!!.x
                                                        val dy = rawCanvasPos.y - cloneTouchStart!!.y
                                                        if (hypot(dx, dy) > 8f / viewModel.zoomScale) {
                                                            viewModel.duplicateSpecificShape(cloneSourceShape!!, 0f, 0f)
                                                            dragStartShapes = viewModel.shapes

                                                            initialTouchCanvasPos = cloneTouchStart
                                                            activeDragHandle = "MOVE"
                                                            hasClonedForDrag = true
                                                            viewModel.isCloneModeActive = false
                                                        }
                                                    }
                                                    
                                                    val id = touchedUnselectedShapeOnDown ?: viewModel.selectedShapeId
                                                    val handle = activeDragHandle
                                                    val startShapes = dragStartShapes
                                                    val touchStart = initialTouchCanvasPos
                                                    
                                                    // If we are moving and touched an unselected shape, add it to selection NOW
                                                    if (handle == "MOVE" && touchedUnselectedShapeOnDown != null) {
                                                        if (!viewModel.selectedShapeIds.contains(touchedUnselectedShapeOnDown)) {
                                                            val newSels = viewModel.selectedShapeIds.toMutableSet()
                                                            val groupIds = viewModel.getGroupedShapeIds(touchedUnselectedShapeOnDown!!)
                                                            newSels.addAll(groupIds)
                                                            viewModel.selectedShapeIds = newSels
                                                            viewModel.selectedShapeId = touchedUnselectedShapeOnDown
                                                        }
                                                    }
                                                    
                                                    if (id != null && handle != null && startShapes != null && touchStart != null) {
                                                        val totalDX = rawCanvasPos.x - touchStart.x
                                                        val totalDY = rawCanvasPos.y - touchStart.y
                                                        
                                                        if (handle == "MOVE") {
                                                            // Absolute move snapping logic
                                                            val primaryId = id ?: viewModel.selectedShapeIds.firstOrNull()
                                                            val primaryShape = startShapes.find { it.id == primaryId }
                                                            if (primaryShape != null) {
                                                                val activeIds = viewModel.selectedShapeIds.ifEmpty { setOfNotNull(primaryId) }
                                                                val selBounds = getUnrotatedCombinedBoundingBox(activeIds, startShapes)
                                                                val finalDX: Float
                                                                val finalDY: Float
                                                                if (selBounds != null && !viewModel.isSnapToGrid) {
                                                                    val snappedDisplacement = viewModel.snapSelectionComprehensive(selBounds, totalDX, totalDY, activeIds)
                                                                    finalDX = snappedDisplacement.x
                                                                    finalDY = snappedDisplacement.y
                                                                } else {
                                                                    val originalAnchor = getPrimaryAnchor(primaryShape)
                                                                    val targetUnsnappedX = originalAnchor.x + totalDX
                                                                    val targetUnsnappedY = originalAnchor.y + totalDY
                                                                    val targetSnapped = if (viewModel.isSnapToGrid) {
                                                                        val gridX = kotlin.math.round(targetUnsnappedX / viewModel.gridSize) * viewModel.gridSize
                                                                        val gridY = kotlin.math.round(targetUnsnappedY / viewModel.gridSize) * viewModel.gridSize
                                                                        Offset(gridX, gridY)
                                                                    } else {
                                                                        viewModel.snapOffsetComprehensive(Offset(targetUnsnappedX, targetUnsnappedY), ignoreId = primaryId)
                                                                    }
                                                                    finalDX = targetSnapped.x - originalAnchor.x
                                                                    finalDY = targetSnapped.y - originalAnchor.y
                                                                }
                                                                viewModel.translateShapesAbsolute(startShapes, activeIds, finalDX, finalDY)
                                                            }
                                                        } else if (handle == "ROTATE_HOTSPOT") {
                                                            val bounds = getUnrotatedCombinedBoundingBox(viewModel.selectedShapeIds, dragStartShapes ?: viewModel.shapes)
                                                            if (bounds != null && initialTouchCanvasPos != null && dragStartShapes != null) {
                                                                val center = Offset((bounds.left + bounds.right) / 2f, (bounds.top + bounds.bottom) / 2f)
                                                                val startVec = initialTouchCanvasPos!! - center
                                                                val currentVec = rawCanvasPos - center
                                                                val startAngle = Math.toDegrees(Math.atan2(startVec.y.toDouble(), startVec.x.toDouble())).toFloat()
                                                                val currentAngle = Math.toDegrees(Math.atan2(currentVec.y.toDouble(), currentVec.x.toDouble())).toFloat()
                                                                var diff = currentAngle - startAngle
                                                                if (diff < -180f) diff += 360f
                                                                if (diff > 180f) diff -= 360f
                                                                viewModel.rotateSelectedShapesAbsolute(dragStartShapes!!, diff)
                                                            }
                                                        } else {
                                                            // Absolute resize snapping logic
                                                            val activeIds = if (viewModel.selectedShapeIds.contains(id)) viewModel.selectedShapeIds else setOf(id)
                                                            val bounds = if (activeIds.size == 1) {
                                                                getUnrotatedCombinedBoundingBox(activeIds, startShapes)
                                                            } else {
                                                                getRotatedCombinedBoundingBox(activeIds, startShapes)
                                                            }
                                                            if (bounds != null) {
                                                                val startHandlePos = getHandleStartPos(bounds, handle)
                                                                val singleId = activeIds.firstOrNull()
                                                                val rotationAngle = if (activeIds.size == 1 && singleId != null) {
                                                                    viewModel.shapes.find { it.id == singleId }?.rotationAngle ?: 0f
                                                                } else {
                                                                    0f
                                                                }
                                                                val localTotalDX: Float
                                                                val localTotalDY: Float
                                                                if (rotationAngle != 0f) {
                                                                    val rad = Math.toRadians(-rotationAngle.toDouble())
                                                                    val cos = kotlin.math.cos(rad).toFloat()
                                                                    val sin = kotlin.math.sin(rad).toFloat()
                                                                    localTotalDX = totalDX * cos - totalDY * sin
                                                                    localTotalDY = totalDX * sin + totalDY * cos
                                                                } else {
                                                                    localTotalDX = totalDX
                                                                    localTotalDY = totalDY
                                                                }
                                                                val newHandleUnsnappedX = startHandlePos.x + localTotalDX
                                                                val newHandleUnsnappedY = startHandlePos.y + localTotalDY
                                                                val finalTotalDX: Float
                                                                val finalTotalDY: Float
                                                                if (viewModel.isSnapToGrid) {
                                                                    val snappedHandleX = kotlin.math.round(newHandleUnsnappedX / viewModel.gridSize) * viewModel.gridSize
                                                                    val snappedHandleY = kotlin.math.round(newHandleUnsnappedY / viewModel.gridSize) * viewModel.gridSize
                                                                    finalTotalDX = snappedHandleX - startHandlePos.x
                                                                    finalTotalDY = snappedHandleY - startHandlePos.y
                                                                } else {
                                                                    finalTotalDX = localTotalDX
                                                                    finalTotalDY = localTotalDY
                                                                }
                                                                viewModel.resizeShapeAbsolute(startShapes, id, handle, Offset(finalTotalDX, finalTotalDY))
                                                            }
                                                        }
                                                    }
                                                }
                                                VectorTool.DIRECT_SELECTION -> {
                                                    val id = viewModel.selectedShapeId
                                                    val nodeIdx = if (activeDragHandle == "DIRECT_NODE") directSelectionActiveHandleNodeIndex.takeIf { it != -1 } else if (activeDragHandle == "DIRECT_HANDLE") directSelectionActiveHandleNodeIndex.takeIf { it != -1 } else null
                                                    if (id != null && (activeDragHandle == "DIRECT_NODE" || activeDragHandle == "DIRECT_HANDLE" || activeDragHandle == "DIRECT_DRAG_ANYWHERE") && dragStartShapes != null && initialTouchCanvasPos != null) {
                                                        val startShape = dragStartShapes!!.find { it.id == id }
                                                        if (startShape != null && startShape.type == com.example.model.ShapeType.BEZIER_PATH) {
                                                            val dragAmountX = rawCanvasPos.x - initialTouchCanvasPos!!.x
                                                            val dragAmountY = rawCanvasPos.y - initialTouchCanvasPos!!.y

                                                            val rad = Math.toRadians(-startShape.rotationAngle.toDouble())
                                                            val localDeltaX = (dragAmountX * kotlin.math.cos(rad) - dragAmountY * kotlin.math.sin(rad)).toFloat()
                                                            val localDeltaY = (dragAmountX * kotlin.math.sin(rad) + dragAmountY * kotlin.math.cos(rad)).toFloat()
                                                            val localDelta = Offset(localDeltaX, localDeltaY)

                                                            var deltaX: Float
                                                            var deltaY: Float
                                                            if (activeDragHandle == "DIRECT_NODE" && nodeIdx != null) {
                                                                val origNode = startShape.bezierNodes.getOrNull(nodeIdx)
                                                                if (origNode != null) {
                                                                    val targetLocalX = origNode.anchorX + localDelta.x
                                                                    val targetLocalY = origNode.anchorY + localDelta.y
                                                                    val snappedPos = viewModel.snapNodeComprehensive(Offset(targetLocalX, targetLocalY), id, nodeIdx)
                                                                    deltaX = snappedPos.x - origNode.anchorX
                                                                    deltaY = snappedPos.y - origNode.anchorY
                                                                } else {
                                                                    deltaX = 0f; deltaY = 0f
                                                                }
                                                                viewModel.translateDirectSelection(id, startShape, deltaX, deltaY)
                                                            } else if (activeDragHandle == "DIRECT_HANDLE" && nodeIdx != null) {
                                                                val startNode = startShape.bezierNodes.getOrNull(nodeIdx)
                                                                var targetX = if (directSelectionActiveHandleType == "in") startNode?.control1X ?: 0f else startNode?.control2X ?: 0f
                                                                targetX += localDelta.x
                                                                var targetY = if (directSelectionActiveHandleType == "in") startNode?.control1Y ?: 0f else startNode?.control2Y ?: 0f
                                                                targetY += localDelta.y

                                                                if (startNode != null) {
                                                                    val dxFromAnchor = targetX - startNode.anchorX
                                                                    val dyFromAnchor = targetY - startNode.anchorY
                                                                    if (kotlin.math.abs(dxFromAnchor) < 15f / viewModel.zoomScale) targetX = startNode.anchorX
                                                                    if (kotlin.math.abs(dyFromAnchor) < 15f / viewModel.zoomScale) targetY = startNode.anchorY
                                                                }
                                                                val snappedPos = viewModel.snapNodeComprehensive(Offset(targetX, targetY), id, nodeIdx)
                                                                
                                                                viewModel.updateShapeHandle(
                                                                    shapeId = id,
                                                                    nodeIndex = directSelectionActiveHandleNodeIndex,
                                                                    handleType = directSelectionActiveHandleType,
                                                                    newPos = snappedPos
                                                                )
                                                            } else { // DIRECT_DRAG_ANYWHERE
                                                                val baseDX = rawCanvasPos.x - initialTouchCanvasPos!!.x
                                                                val baseDY = rawCanvasPos.y - initialTouchCanvasPos!!.y
                                                                val rotatedDelta = if (startShape.rotationAngle != 0f) {
                                                                    rotatePoint(Offset(baseDX, baseDY), Offset(0f, 0f), -startShape.rotationAngle)
                                                                } else {
                                                                    Offset(baseDX, baseDY)
                                                                }
                                                                deltaX = rotatedDelta.x
                                                                deltaY = rotatedDelta.y
                                                                viewModel.translateDirectSelection(id, startShape, deltaX, deltaY)
                                                            }
                                                        }
                                                    } else if (id != null && activeDragHandle == "DIRECT_SEGMENT" && initialTouchCanvasPos != null) {
                                                        val shape = viewModel.shapes.find { it.id == id }
                                                        if (shape != null && shape.type == com.example.model.ShapeType.BEZIER_PATH) {
                                                            val baseDX = rawCanvasPos.x - initialTouchCanvasPos!!.x
                                                            val baseDY = rawCanvasPos.y - initialTouchCanvasPos!!.y
                                                            val rotatedDelta = if (shape.rotationAngle != 0f) {
                                                                rotatePoint(Offset(baseDX, baseDY), Offset(0f, 0f), -shape.rotationAngle)
                                                            } else {
                                                                Offset(baseDX, baseDY)
                                                            }
                                                            val deltaX = rotatedDelta.x
                                                            val deltaY = rotatedDelta.y
                                                            val isClosedSeg = directSelectionActiveSegmentIndex == 0
                                                            val nextIdx = if (isClosedSeg) 0 else directSelectionActiveSegmentIndex
                                                            val prevIdx = if (isClosedSeg) shape.bezierNodes.size - 1 else directSelectionActiveSegmentIndex - 1
                                                            
                                                            viewModel.updateShapeSegmentDelta(
                                                                shapeId = id,
                                                                prevIdx = prevIdx,
                                                                nextIdx = nextIdx,
                                                                dx = deltaX,
                                                                dy = deltaY,
                                                                t = directSelectionActiveSegmentT,
                                                                startC2X = directSelectionSegmentStartControl2X,
                                                                startC2Y = directSelectionSegmentStartControl2Y,
                                                                startC1X = directSelectionSegmentStartControl1X,
                                                                startC1Y = directSelectionSegmentStartControl1Y
                                                            )
                                                        }
                                                    } else if (viewModel.currentTool != VectorTool.DIRECT_SELECTION && id != null && dragStartShapes != null && initialTouchCanvasPos != null) {
                                                        val shapeObj = viewModel.shapes.find { it.id == id }
                                                        val isTouchStartedOnShape = shapeObj?.isPointInside(initialTouchCanvasPos!!.x, initialTouchCanvasPos!!.y) == true
                                                        if (activeDragHandle == "MOVE" || isTouchStartedOnShape) {
                                                            val totalDX = rawCanvasPos.x - initialTouchCanvasPos!!.x
                                                            val totalDY = rawCanvasPos.y - initialTouchCanvasPos!!.y
                                                            val primaryShape = dragStartShapes!!.find { it.id == id }
                                                            if (primaryShape != null) {
                                                                val activeIds = if (viewModel.selectedShapeIds.contains(id)) viewModel.selectedShapeIds else setOf(id)
                                                                val selBounds = getUnrotatedCombinedBoundingBox(activeIds, dragStartShapes!!)
                                                                val originalAnchor = getPrimaryAnchor(primaryShape)
                                                                val targetUnsnappedX = originalAnchor.x + totalDX
                                                                val targetUnsnappedY = originalAnchor.y + totalDY
                                                                val finalDX: Float
                                                                val finalDY: Float
                                                                if (selBounds != null && !viewModel.isSnapToGrid) {
                                                                    val snappedDisplacement = viewModel.snapSelectionComprehensive(selBounds, totalDX, totalDY, activeIds)
                                                                    finalDX = snappedDisplacement.x
                                                                    finalDY = snappedDisplacement.y
                                                                } else {
                                                                    val targetSnapped = if (viewModel.isSnapToGrid) {
                                                                        val gridX = kotlin.math.round(targetUnsnappedX / viewModel.gridSize) * viewModel.gridSize
                                                                        val gridY = kotlin.math.round(targetUnsnappedY / viewModel.gridSize) * viewModel.gridSize
                                                                        Offset(gridX, gridY)
                                                                    } else {
                                                                        viewModel.snapOffsetComprehensive(Offset(targetUnsnappedX, targetUnsnappedY), ignoreId = id)
                                                                    }
                                                                    finalDX = targetSnapped.x - originalAnchor.x
                                                                    finalDY = targetSnapped.y - originalAnchor.y
                                                                }
                                                                viewModel.translateShapesAbsolute(dragStartShapes!!, activeIds, finalDX, finalDY)
                                                            }
                                                        }
                                                    }
                                                }
                                                VectorTool.ROUNDED_CORNER -> {
                                                    val id = viewModel.selectedShapeId
                                                    if (id != null) {
                                                        val shape = viewModel.shapes.find { it.id == id }
                                                        val maxR = if (shape != null) {
                                                            val sb = shape.getBoundingBox()
                                                            maxOf(10f, minOf(sb.width, sb.height) / 2f)
                                                        } else 150f
                                                        val dragY = canvasPos.y - startOffset!!.y
                                                        val newRadius = if (viewModel.selectedRoundedCornerIndex != null && shape != null) { val nodeIdx = viewModel.selectedRoundedCornerIndex!!; val vertex = shape.getCornerPoints().getOrNull(nodeIdx) ?: Offset.Zero; val dist = hypot(rawCanvasPos.x - vertex.x, rawCanvasPos.y - vertex.y); ((dist - 16f) / 1.1f).coerceIn(0f, maxR) } else { val dragY = canvasPos.y - startOffset!!.y; (startCornerRadius + dragY * 1.5f).coerceIn(0f, maxR) }
                                                        if (viewModel.selectedRoundedCornerIndex != null) {
                                                            viewModel.updateSpecificCornerRadius(viewModel.selectedRoundedCornerIndex!!, newRadius)
                                                        } else {
                                                            viewModel.updateSelectedShapeCornerRadius(newRadius)
                                                        }
                                                        viewModel.manualCornerRadiusText = newRadius.toInt().toString()
                                                    }
                                                }
                                                else -> {}
                                            }
                                        }
                                        
                                        lastDragOffset = canvasPos
                                        change.consume()
                                    }
                                }
                            }
                        } else {
                            // 3. TOOL SPECIFIC UP LOGIC
                            if (isDragging && !isMultiTouch) {
                                val s = startOffset
                                val e = currentTouchPos ?: s
                                if (s != null && e != null) {
                                    if (viewModel.currentTool == VectorTool.PEN) {
                                        val duration = System.currentTimeMillis() - penTouchStartTime
                                        
                                        if (penDragMode == "node" && !penIsMovementDetected && duration < 250) {
                                            // JIKA sentuhan singkat pada node dan JARI TIDAK BERGESER -> TOGGLE TYPE!
                                            viewModel.toggleNodeCurve(penActiveNodeIndex)
                                        } else if (penDragMode == "create") {
                                             // Node already created on touch down.
                                        }
                                        
                                        // Reset state
                                        penDragMode = null
                                        penActiveHandleHit = null
                                        penActiveNodeIndex = -1
                                        penIsMovementDetected = false
                                    } else {
                                        if (viewModel.currentTool == VectorTool.SHAPES) {
                                            if (hypot(e.x - s.x, e.y - s.y) > 10f / viewModel.zoomScale) viewModel.addPrimitiveShape(s, e)
                                        } else if (viewModel.currentTool == VectorTool.POINTER) {
                                            if (hypot(e.x - s.x, e.y - s.y) < 6f) {
                                                var triggeredTab = false
                                                when (activeDragHandle) {
                                                    "DELETE_HOTSPOT" -> {
                                                        viewModel.deleteSelectedShape()
                                                        triggeredTab = true
                                                    }
                                                    "DUPLICATE_HOTSPOT" -> {
                                                        viewModel.duplicateSelected()
                                                        triggeredTab = true
                                                    }
                                                    "LOCK_TOGGLE_HOTSPOT", "ROTATE_HOTSPOT" -> {
                                                        triggeredTab = true
                                                    }
                                                }
                                                
                                                val bounds = if (viewModel.selectedShapeIds.size == 1) {
                                                    getUnrotatedCombinedBoundingBox(viewModel.selectedShapeIds, viewModel.shapes)
                                                } else {
                                                    getRotatedCombinedBoundingBox(viewModel.selectedShapeIds, viewModel.shapes)
                                                }
                                                
                                                if (!triggeredTab) {
                                                    if (!isMultiTouch) {
                                                        val tapPos = screenToCanvas(changes[0].position)
                                                        val lockedLayerIds = viewModel.layers.filter { it.isLocked }.map { it.id }.toSet()
                                                        val hitShapeTap = viewModel.shapes.findLast { !it.isLocked && !lockedLayerIds.contains(it.layerId) && it.isPointInside(tapPos.x, tapPos.y) }
                                                        
                                                        if (hitShapeTap != null) {
                                                            // Toggle selection state
                                                            val currentSelected = viewModel.selectedShapeIds.toMutableSet()
                                                            val groupIds = viewModel.getGroupedShapeIds(hitShapeTap.id)
                                                            if (currentSelected.contains(hitShapeTap.id)) {
                                                                currentSelected.removeAll(groupIds)
                                                            } else {
                                                                currentSelected.addAll(groupIds)
                                                            }
                                                            viewModel.selectedShapeIds = currentSelected
                                                            if (currentSelected.isNotEmpty()) {
                                                                viewModel.selectedShapeId = hitShapeTap.id
                                                            } else {
                                                                viewModel.selectedShapeId = null
                                                            }
                                                        } else {
                                                            // Tapped empty area, unselect all
                                                            viewModel.selectedShapeIds = emptySet()
                                                            viewModel.selectedShapeId = null
                                                        }
                                                    }
                                                }
                                            } else if (activeDragHandle == "MARQUEE_SELECT") {
                                                val minX = minOf(s.x, e.x)
                                                val minY = minOf(s.y, e.y)
                                                val maxX = maxOf(s.x, e.x)
                                                val maxY = maxOf(s.y, e.y)
                                                val selectBox = Rect(minX, minY, maxX, maxY)
                                                val lockedLayerIds = viewModel.layers.filter { it.isLocked }.map { it.id }.toSet()
                                                val fullyBoundedIds = viewModel.shapes.filter { sShape ->
                                                    if (sShape.isLocked || lockedLayerIds.contains(sShape.layerId)) return@filter false
                                                    val sb = sShape.getBoundingBox()
                                                    selectBox.left <= sb.left && selectBox.top <= sb.top && selectBox.right >= sb.right && selectBox.bottom >= sb.bottom
                                                }.map { it.id }.toSet()
                                                
                                                val matchingShapeIds = fullyBoundedIds.flatMap { id -> viewModel.getGroupedShapeIds(id) }.toSet()
                                                viewModel.selectedShapeIds = matchingShapeIds
                                                viewModel.selectedShapeId = matchingShapeIds.firstOrNull()
                                            } else {
                                                if (activeDragHandle == "ROUNDED_CORNER" || activeDragHandle == "ROUNDED_CORNER_ALL") {
                                                    viewModel.bakeBezierCorners()
                                                }
                                                viewModel.finishTransformation()
                                            }
                                        } else if (viewModel.currentTool == VectorTool.BRUSH) {
                                            viewModel.addFreehandPath(viewModel.activeFreehandPoints.map { it.toOffset() })
                                            viewModel.activeFreehandPoints = emptyList()
                                        } else if (viewModel.currentTool == VectorTool.BUCKET) {
                                            if (hypot(e.x - s.x, e.y - s.y) < 6f) {
                                                viewModel.applyBucketColorAt(e)
                                            }
                                        } else if (viewModel.currentTool == VectorTool.DIRECT_SELECTION) {
                                            if (hypot(e.x - s.x, e.y - s.y) < 6f) {
                                                val sId = viewModel.selectedShapeId
                                                var hitNode = false
                                                if (sId != null) {
                                                    val shape = viewModel.shapes.find { it.id == sId }
                                                    if (shape != null) {
                                                        val bounds = shape.getBoundingBox()
                                                        val cx = (bounds.left + bounds.right) / 2f
                                                        val cy = (bounds.top + bounds.bottom) / 2f
                                                        val centerPt = Offset(cx, cy)
                                                        val localE = if (shape.rotationAngle != 0f) {
                                                            rotatePoint(e, centerPt, -shape.rotationAngle)
                                                        } else {
                                                            e
                                                        }

                                                        if (shape.type == com.example.model.ShapeType.BEZIER_PATH) {
                                                            val nodes = shape.getNodePoints()
                                                            val nodeIdx = nodes.indexOfFirst { hypot(localE.x - it.x, localE.y - it.y) < 24f / viewModel.zoomScale }
                                                        
                                                        when (viewModel.currentNodeEditMode) {
                                                            com.example.viewmodel.NodeEditMode.REMOVE -> {
                                                                if (nodeIdx != -1) {
                                                                    viewModel.removeNodeAt(sId, nodeIdx)
                                                                    hitNode = true
                                                                }
                                                            }
                                                            com.example.viewmodel.NodeEditMode.SPLIT -> {
                                                                if (nodeIdx != -1) {
                                                                    viewModel.splitNodeAt(sId, nodeIdx)
                                                                    hitNode = true
                                                                }
                                                            }
                                                            com.example.viewmodel.NodeEditMode.ADD -> {
                                                                val nearestSeg = findNearestSegment(
                                                                    localE,
                                                                    shape.bezierNodes,
                                                                    isClosed = shape.isPathClosed,
                                                                    tolerance = 24f / viewModel.zoomScale
                                                                )
                                                                if (nearestSeg != null) {
                                                                    viewModel.addNodeOnStroke(sId, nearestSeg.segmentIndex, nearestSeg.t, nearestSeg.isClosedSegment)
                                                                    hitNode = true
                                                                }
                                                            }
                                                            com.example.viewmodel.NodeEditMode.CUT -> {
                                                                val nearestSeg = findNearestSegment(
                                                                    localE,
                                                                    shape.bezierNodes,
                                                                    isClosed = shape.isPathClosed,
                                                                    tolerance = 24f / viewModel.zoomScale
                                                                )
                                                                if (nearestSeg != null) {
                                                                    viewModel.cutStrokeAt(sId, nearestSeg.segmentIndex, nearestSeg.t, nearestSeg.isClosedSegment)
                                                                    hitNode = true
                                                                }
                                                            }
                                                            com.example.viewmodel.NodeEditMode.CLOSE -> {
                                                                if (!shape.isPathClosed) {
                                                                    viewModel.closePath(sId)
                                                                    hitNode = true
                                                                }
                                                            }
                                                            else -> {
                                                                if (nodeIdx != -1) {
                                                                    viewModel.selectedDirectSelectionNodes = setOf(nodeIdx)
                                                                    // removed selection reset
                                                                    hitNode = true
                                                                    val node = shape.bezierNodes.getOrNull(nodeIdx)
                                                                    if (node != null && node.nodeType == "HALUS") {
                                                                        viewModel.toggleShapeNodeCurveType(sId, nodeIdx)
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    } else if (shape != null) {
                                                        val nodes = shape.getNodePoints()
                                                        val nodeIdx = nodes.indexOfFirst { hypot(localE.x - it.x, localE.y - it.y) < 24f / viewModel.zoomScale }
                                                        if (nodeIdx != -1) {
                                                            // Multi-select toggle if holding? We don't have shift key, let's just make it simple.
                                                            viewModel.selectedDirectSelectionNodes = setOf(nodeIdx)
                                                            // removed selection reset
                                                            hitNode = true
                                                        }
                                                    }
                                                    }
                                                }
                                                if (!hitNode) {
                                                    val lockedLayerIds = viewModel.layers.filter { it.isLocked }.map { it.id }.toSet()
                                                    val shapeUnder = viewModel.shapes.findLast { !it.isLocked && !lockedLayerIds.contains(it.layerId) && it.isPointInside(e.x, e.y) }
                                                    if (shapeUnder != null) {
                                                        viewModel.selectShapeAt(e)
                                                    }
                                                    viewModel.clearDirectSelection()
                                                }
                                            }
                                        } else if (viewModel.currentTool == VectorTool.ROUNDED_CORNER) {
                                            if (hypot(e.x - s.x, e.y - s.y) < 6f) {
                                                val lockedLayerIds = viewModel.layers.filter { it.isLocked }.map { it.id }.toSet()
                                                val shapeUnder = viewModel.shapes.findLast { !it.isLocked && !lockedLayerIds.contains(it.layerId) && it.isPointInside(e.x, e.y) }
                                                if (shapeUnder != null && shapeUnder.id != viewModel.selectedShapeId) {
                                                    viewModel.selectedShapeId = shapeUnder.id
                                                    viewModel.selectedRoundedCornerIndex = null
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            
                            viewModel.activeSmartGuideHorizontal = null; viewModel.activeSmartGuidesList = emptyList()
                            viewModel.activeSmartGuideVertical = null
                            
                            if (viewModel.currentTool == VectorTool.PEN) {
                                penDragMode = null
                                penActiveHandleHit = null
                                penActiveNodeIndex = -1
                                penIsMovementDetected = false
                                penActiveSegmentIndex = -1
                            }
                            directSelectionActiveSegmentIndex = -1

                            if (viewModel.isCloneModeActive && activeDragHandle == "CLONE_PENDING" && cloneSourceShape != null && !hasClonedForDrag) {
                                viewModel.duplicateSpecificShape(cloneSourceShape!!, 0f, 0f)
                                viewModel.isCloneModeActive = false
                            }
                            cloneSourceShape = null
                            cloneTouchStart = null
                            hasClonedForDrag = false

                            val initialShapesBeforeDrag = dragStartShapes
                            if (initialShapesBeforeDrag != null && viewModel.shapes != initialShapesBeforeDrag) {
                                viewModel.pushCustomListToUndoStack(initialShapesBeforeDrag)
                            }

                            isDragging = false
                            isMultiTouch = false
                            startOffset = null
                            lastDragOffset = null
                            activeDragHandle = null
                            currentTouchPos = null
                            initialDragPivot = null
                            viewModel.isAutosaveSuspended = false
                            viewModel.saveCurrentProject()
                        }
                        activePenDragMode = penDragMode
                        activePenIsMovementDetected = penIsMovementDetected
                        activePenStartX = penStartX
                        activePenStartY = penStartY
                    }
                }
            }
    ) {
        val canvasWidthVal = viewModel.canvasWidth
        val canvasHeightVal = viewModel.canvasHeight

        // LAYER 1: STATIC BACKGROUND & VECTOR SHAPES CANVAS
        // Using stable parameter references to allow Compose to skip recomposition when drawing active nodes
        val shapesList = viewModel.shapes
        val layersList = viewModel.layers
        val activeTracer = viewModel.activeTracerImageIndex
        val isGridOn = viewModel.isGridEnabled
        val gridSz = viewModel.gridSize
        val gridCol = viewModel.gridColorHex
        val artColor = viewModel.artboardColorHex
        val artAlpha = viewModel.artboardAlpha

        StaticShapesCanvas(
            viewModel = viewModel,
            canvasWidthVal = canvasWidthVal,
            canvasHeightVal = canvasHeightVal,
            panOffset = viewModel.panOffset,
            zoomScale = viewModel.zoomScale,
            isGridEnabled = isGridOn,
            gridSize = gridSz,
            gridColorHex = gridCol,
            activeTracerImageIndex = activeTracer,
            layers = layersList,
            shapes = shapesList,
            artboardColorHex = artColor,
            artboardAlpha = artAlpha
        )

        // LAYER 2: DYNAMIC ACTIVE OVERLAY CANVAS (Takes transparent background, handles high-performance real-time drawing overlays)
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawIntoCanvas { canvas ->
                canvas.save()
                canvas.translate(viewModel.panOffset.x, viewModel.panOffset.y)
                canvas.scale(viewModel.zoomScale, viewModel.zoomScale)

                viewModel.activeSmartGuideHorizontal?.let { y ->
                    drawLine(
                        color = Color(0xFFFF007F),
                        start = Offset(0f, y),
                        end = Offset(canvasWidthVal, y),
                        strokeWidth = 1.5f / viewModel.zoomScale,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f / viewModel.zoomScale, 5f / viewModel.zoomScale), 0f)
                    )
                }

                viewModel.activeSmartGuideVertical?.let { x ->
                    drawLine(
                        color = Color(0xFFFF007F),
                        start = Offset(x, 0f),
                        end = Offset(x, canvasHeightVal),
                        strokeWidth = 1.5f / viewModel.zoomScale,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f / viewModel.zoomScale, 5f / viewModel.zoomScale), 0f)
                    )
                }

                // Draw refined alignment and spacing guides
                viewModel.activeSmartGuidesList.forEach { guide ->
                    if (guide.type == "ALIGN_LINE") {
                        // Figma-style high-clarity alignment lines (magenta dash)
                        drawLine(
                            color = Color(0xFFFF007F), // Pro magenta color
                            start = Offset(guide.x1, guide.y1),
                            end = Offset(guide.x2, guide.y2),
                            strokeWidth = 2f / viewModel.zoomScale,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f / viewModel.zoomScale, 5f / viewModel.zoomScale), 0f)
                        )
                        // Little anchoring squares at the ends to show precision
                        val dotRadius = 3f / viewModel.zoomScale
                        drawRect(
                            color = Color(0xFFFF007F),
                            topLeft = Offset(guide.x1 - dotRadius, guide.y1 - dotRadius),
                            size = Size(dotRadius * 2f, dotRadius * 2f)
                        )
                        drawRect(
                            color = Color(0xFFFF007F),
                            topLeft = Offset(guide.x2 - dotRadius, guide.y2 - dotRadius),
                            size = Size(dotRadius * 2f, dotRadius * 2f)
                        )
                    } else if (guide.type == "SPACING") {
                        // Equal spacing visual guides!
                        // 1. Draw connecting solid line
                        drawLine(
                            color = Color(0xFF00BFA5), // Teal/Cyan for spacing!
                            start = Offset(guide.x1, guide.y1),
                            end = Offset(guide.x2, guide.y2),
                            strokeWidth = 1.5f / viewModel.zoomScale
                        )
                        // 2. Draw T-bar brackets at the ends of the gap
                        val bracketSize = 6f / viewModel.zoomScale
                        val isHorizontal = kotlin.math.abs(guide.y1 - guide.y2) < 1f
                        if (isHorizontal) {
                            drawLine(
                                color = Color(0xFF00BFA5),
                                start = Offset(guide.x1, guide.y1 - bracketSize),
                                end = Offset(guide.x1, guide.y1 + bracketSize),
                                strokeWidth = 1.5f / viewModel.zoomScale
                            )
                            drawLine(
                                color = Color(0xFF00BFA5),
                                start = Offset(guide.x2, guide.y2 - bracketSize),
                                end = Offset(guide.x2, guide.y2 + bracketSize),
                                strokeWidth = 1.5f / viewModel.zoomScale
                            )
                        } else {
                            drawLine(
                                color = Color(0xFF00BFA5),
                                start = Offset(guide.x1 - bracketSize, guide.y1),
                                end = Offset(guide.x1 + bracketSize, guide.y1),
                                strokeWidth = 1.5f / viewModel.zoomScale
                            )
                            drawLine(
                                color = Color(0xFF00BFA5),
                                start = Offset(guide.x2 - bracketSize, guide.y2),
                                end = Offset(guide.x2 + bracketSize, guide.y2),
                                strokeWidth = 1.5f / viewModel.zoomScale
                            )
                        }
                        // 3. Draw spacing label text inside a nice bubble!
                        if (guide.label.isNotEmpty()) {
                            val midX = (guide.x1 + guide.x2) / 2f
                            val midY = (guide.y1 + guide.y2) / 2f
                            
                            val textPaint = android.graphics.Paint().apply {
                                color = android.graphics.Color.WHITE
                                textSize = 11f / viewModel.zoomScale
                                textAlign = android.graphics.Paint.Align.CENTER
                                isAntiAlias = true
                                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                            }
                            
                            val rectBounds = android.graphics.Rect()
                            textPaint.getTextBounds(guide.label, 0, guide.label.length, rectBounds)
                            
                            val paddingX = 6f / viewModel.zoomScale
                            val paddingY = 4f / viewModel.zoomScale
                            val bubbleW = rectBounds.width() / viewModel.zoomScale + paddingX * 2f
                            val bubbleH = rectBounds.height() / viewModel.zoomScale + paddingY * 2f
                            
                            drawRect(
                                color = Color(0xFF00BFA5),
                                topLeft = Offset(midX - bubbleW / 2f, midY - bubbleH / 2f),
                                size = Size(bubbleW, bubbleH),
                                style = androidx.compose.ui.graphics.drawscope.Fill
                            )
                            
                            // Native text drawing
                            drawContext.canvas.nativeCanvas.drawText(
                                guide.label,
                                midX,
                                midY + (rectBounds.height() / 2f) / viewModel.zoomScale,
                                textPaint
                            )
                        }
                    }
                }

                if (viewModel.currentTool == VectorTool.BRUSH && viewModel.activeFreehandPoints.size >= 2) {
                    val brushPath = Path().apply {
                        val first = viewModel.activeFreehandPoints.first()
                        moveTo(first.x, first.y)
                        for (i in 1 until viewModel.activeFreehandPoints.size) {
                            val pt = viewModel.activeFreehandPoints[i]
                            lineTo(pt.x, pt.y)
                        }
                    }
                    drawPath(
                        path = brushPath,
                        color = Color(android.graphics.Color.parseColor(viewModel.currentStrokeColorHex)).copy(alpha = viewModel.currentStrokeAlpha),
                        style = Stroke(
                            width = viewModel.currentStrokeWidth,
                            join = mapStrokeJoin(viewModel.currentStrokeJoin),
                            cap = mapStrokeCap(viewModel.currentStrokeCap),
                            pathEffect = mapLineStyle(viewModel.currentLineStyle, viewModel.currentStrokeWidth)
                        )
                    )
                }

                if (viewModel.currentTool == VectorTool.PEN) {
                    if (viewModel.activeBezierNodes.isNotEmpty()) {
                        val penCurvePath = Path()
                        val pStart = viewModel.activeBezierNodes.first()
                        penCurvePath.moveTo(pStart.anchorX, pStart.anchorY)
                        for (i in 0 until viewModel.activeBezierNodes.size - 1) {
                            val node = viewModel.activeBezierNodes[i]
                            val nextNode = viewModel.activeBezierNodes[i + 1]
                            
                            val cp1X = if (node.isCurve) node.control2X else node.anchorX
                            val cp1Y = if (node.isCurve) node.control2Y else node.anchorY
                            val cp2X = if (nextNode.isCurve) nextNode.control1X else nextNode.anchorX
                            val cp2Y = if (nextNode.isCurve) nextNode.control1Y else nextNode.anchorY
                            
                            penCurvePath.cubicTo(
                                cp1X, cp1Y,
                                cp2X, cp2Y,
                                nextNode.anchorX, nextNode.anchorY
                            )
                        }
                        
                        // Real-time fill preview
                        if (viewModel.hasFillEnabled) {
                            try {
                                drawPath(
                                    path = penCurvePath,
                                    color = Color(android.graphics.Color.parseColor(viewModel.currentFillColorHex)).copy(alpha = viewModel.currentFillAlpha)
                                )
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }

                        // Real-time stroke preview
                        if (viewModel.hasStrokeEnabled) {
                            drawPath(
                                path = penCurvePath,
                                color = Color(android.graphics.Color.parseColor(viewModel.currentStrokeColorHex)).copy(alpha = viewModel.currentStrokeAlpha),
                                style = Stroke(
                                    width = viewModel.currentStrokeWidth,
                                    join = mapStrokeJoin(viewModel.currentStrokeJoin),
                                    cap = mapStrokeCap(viewModel.currentStrokeCap),
                                    pathEffect = mapLineStyle(viewModel.currentLineStyle, viewModel.currentStrokeWidth)
                                )
                            )
                        } else {
                            // If stroke is disabled, draw a thin outline so the user can see the shape boundaries clearly
                            drawPath(
                                path = penCurvePath,
                                color = Color(0xFF6366F1).copy(alpha = 0.5f),
                                style = Stroke(
                                    width = 1.5f / viewModel.zoomScale,
                                    join = androidx.compose.ui.graphics.StrokeJoin.Round,
                                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                                )
                            )
                        }

                        // Draw control handle lines and circles for draft path first
                        viewModel.activeBezierNodes.forEachIndexed { idx, node ->
                            if (node.isCurve && node.nodeType != "HALUS") {
                                val anchorPt = Offset(node.anchorX, node.anchorY)
                                val c1Pt = Offset(node.control1X, node.control1Y)
                                val c2Pt = Offset(node.control2X, node.control2Y)
                                
                                val isSelectedNode = idx == viewModel.activeEditNodeIndex
                                val handleColor = Color(0xFFEF4444)
                                val lineStrokeWidth = 2f / viewModel.zoomScale
                                val circleRadius = 6f / viewModel.zoomScale
                                
                                if (isSelectedNode) {
                                    if (c1Pt != anchorPt) {
                                        drawLine(
                                            color = handleColor.copy(alpha = 0.5f),
                                            start = anchorPt,
                                            end = c1Pt,
                                            strokeWidth = lineStrokeWidth
                                        )
                                        drawRect(
                                            color = handleColor,
                                            topLeft = Offset(c1Pt.x - circleRadius, c1Pt.y - circleRadius),
                                            size = androidx.compose.ui.geometry.Size(circleRadius * 2f, circleRadius * 2f)
                                        )
                                        val innerR = (circleRadius - 1.5f / viewModel.zoomScale).coerceAtLeast(1f / viewModel.zoomScale)
                                        drawRect(
                                            color = Color.White,
                                            topLeft = Offset(c1Pt.x - innerR, c1Pt.y - innerR),
                                            size = androidx.compose.ui.geometry.Size(innerR * 2f, innerR * 2f)
                                        )
                                    }
                                    if (c2Pt != anchorPt) {
                                        drawLine(
                                            color = handleColor.copy(alpha = 0.5f),
                                            start = anchorPt,
                                            end = c2Pt,
                                            strokeWidth = lineStrokeWidth
                                        )
                                        drawRect(
                                            color = handleColor,
                                            topLeft = Offset(c2Pt.x - circleRadius, c2Pt.y - circleRadius),
                                            size = androidx.compose.ui.geometry.Size(circleRadius * 2f, circleRadius * 2f)
                                        )
                                        val innerR = (circleRadius - 1.5f / viewModel.zoomScale).coerceAtLeast(1f / viewModel.zoomScale)
                                        drawRect(
                                            color = Color.White,
                                            topLeft = Offset(c2Pt.x - innerR, c2Pt.y - innerR),
                                            size = androidx.compose.ui.geometry.Size(innerR * 2f, innerR * 2f)
                                        )
                                    }
                                }
                            }
                        }

                        val nodeRadius = 6f / viewModel.zoomScale
                        val innerRadius = 4f / viewModel.zoomScale

                        for (i in 0 until viewModel.activeBezierNodes.size) {
                            val node = viewModel.activeBezierNodes[i]

                            val isNodeSelected = i == viewModel.activeEditNodeIndex
                            val finalNodeRadius = if (isNodeSelected) nodeRadius + 3f / viewModel.zoomScale else nodeRadius
                            val nodeColor = if (isNodeSelected) Color(0xFFEF4444) else Color(0xFF6366F1)
                            val innerNodeColor = if (isNodeSelected) Color(0xFFFEF2F2) else Color.White

                            drawCircle(
                                color = nodeColor,
                                radius = finalNodeRadius,
                                center = Offset(node.anchorX, node.anchorY)
                            )
                            val finalInnerRadius = if (isNodeSelected) innerRadius + 3f / viewModel.zoomScale else innerRadius
                            drawCircle(
                                color = innerNodeColor,
                                radius = finalInnerRadius,
                                center = Offset(node.anchorX, node.anchorY)
                            )
                        }
                    }
                }

                if (viewModel.currentTool == VectorTool.DIRECT_SELECTION) {
                    viewModel.shapes.forEach { shape ->
                        val isShapeSelected = shape.id == viewModel.selectedShapeId
                        val nodes = shape.getNodePoints()
                        val activeNodeIndex = viewModel.selectedDirectSelectionNodes.firstOrNull()

                        if (isShapeSelected && shape.type == com.example.model.ShapeType.BEZIER_PATH) {
                            val bounds = shape.getBoundingBox()
                            val cx = (bounds.left + bounds.right) / 2f
                            val cy = (bounds.top + bounds.bottom) / 2f
                            val centerPt = Offset(cx, cy)
                            shape.bezierNodes.forEachIndexed { idx, node ->
                                if (node.isCurve && node.nodeType != "HALUS") {
                                    val rawAnchor = Offset(node.anchorX, node.anchorY)
                                    val rawC1 = Offset(node.control1X, node.control1Y)
                                    val rawC2 = Offset(node.control2X, node.control2Y)
                                    
                                    val anchorPt = if (shape.rotationAngle != 0f) rotatePoint(rawAnchor, centerPt, shape.rotationAngle) else rawAnchor
                                    val c1Pt = if (shape.rotationAngle != 0f) rotatePoint(rawC1, centerPt, shape.rotationAngle) else rawC1
                                    val c2Pt = if (shape.rotationAngle != 0f) rotatePoint(rawC2, centerPt, shape.rotationAngle) else rawC2
                                    
                                    val isSelectedNode = isShapeSelected && viewModel.selectedDirectSelectionNodes.contains(idx)
                                    val handleColor = Color(0xFFEF4444)
                                    val lineStrokeWidth = 2f / viewModel.zoomScale
                                    val circleRadius = 6f / viewModel.zoomScale
                                    
                                    if (isSelectedNode) {
                                        if (c1Pt != anchorPt) {
                                            val isC1Selected = false
                                            drawLine(
                                                color = handleColor.copy(alpha = 0.5f),
                                                start = anchorPt,
                                                end = c1Pt,
                                                strokeWidth = lineStrokeWidth
                                            )
                                            drawRect(
                                                color = handleColor,
                                                topLeft = Offset(c1Pt.x - circleRadius, c1Pt.y - circleRadius),
                                                size = androidx.compose.ui.geometry.Size(circleRadius * 2f, circleRadius * 2f)
                                            )
                                            val innerR = (circleRadius - 1.5f / viewModel.zoomScale).coerceAtLeast(1f / viewModel.zoomScale)
                                            drawRect(
                                                color = if (isC1Selected) handleColor else Color.White,
                                                topLeft = Offset(c1Pt.x - innerR, c1Pt.y - innerR),
                                                size = androidx.compose.ui.geometry.Size(innerR * 2f, innerR * 2f)
                                            )
                                        }
                                        if (c2Pt != anchorPt) {
                                            val isC2Selected = false
                                            drawLine(
                                                color = handleColor.copy(alpha = 0.5f),
                                                start = anchorPt,
                                                end = c2Pt,
                                                strokeWidth = lineStrokeWidth
                                            )
                                            drawRect(
                                                color = handleColor,
                                                topLeft = Offset(c2Pt.x - circleRadius, c2Pt.y - circleRadius),
                                                size = androidx.compose.ui.geometry.Size(circleRadius * 2f, circleRadius * 2f)
                                            )
                                            val innerR = (circleRadius - 1.5f / viewModel.zoomScale).coerceAtLeast(1f / viewModel.zoomScale)
                                            drawRect(
                                                color = if (isC2Selected) handleColor else Color.White,
                                                topLeft = Offset(c2Pt.x - innerR, c2Pt.y - innerR),
                                                size = androidx.compose.ui.geometry.Size(innerR * 2f, innerR * 2f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        
                        nodes.forEachIndexed { idx, pt ->
                            val bounds = shape.getBoundingBox()
                            val cx = (bounds.left + bounds.right) / 2f
                            val cy = (bounds.top + bounds.bottom) / 2f
                            val rotatedPt = if (shape.rotationAngle != 0f) rotatePoint(pt, Offset(cx, cy), shape.rotationAngle) else pt
                            
                            val isNodeSelected = isShapeSelected && viewModel.selectedDirectSelectionNodes.contains(idx)
                            val handleRad = (if (isNodeSelected) 14f else 10f) / viewModel.zoomScale
                            val outerRad = handleRad + (2f / viewModel.zoomScale)
                            
                            val bezierNode = if (shape.type == com.example.model.ShapeType.BEZIER_PATH) shape.bezierNodes.getOrNull(idx) else null
                            val isSudut = bezierNode != null && bezierNode.nodeType == "HALUS" && !bezierNode.isCurve
                            
                            if (isSudut) {
                                drawRect(
                                    color = Color(0xFF0F172A),
                                    topLeft = Offset(rotatedPt.x - outerRad, rotatedPt.y - outerRad),
                                    size = androidx.compose.ui.geometry.Size(outerRad * 2f, outerRad * 2f)
                                )
                                drawRect(
                                    color = if (isNodeSelected) Color(0xFFFF6D00) else Color(0xFF00B0FF),
                                    topLeft = Offset(rotatedPt.x - handleRad, rotatedPt.y - handleRad),
                                    size = androidx.compose.ui.geometry.Size(handleRad * 2f, handleRad * 2f)
                                )
                                val innerR = handleRad - (3f / viewModel.zoomScale)
                                drawRect(
                                    color = Color.White,
                                    topLeft = Offset(rotatedPt.x - innerR, rotatedPt.y - innerR),
                                    size = androidx.compose.ui.geometry.Size(innerR * 2f, innerR * 2f)
                                )
                            } else {
                                drawCircle(
                                    color = Color(0xFF0F172A),
                                    radius = outerRad,
                                    center = rotatedPt
                                )
                                drawCircle(
                                    color = if (isNodeSelected) Color(0xFFFF6D00) else Color(0xFF00B0FF),
                                    radius = handleRad,
                                    center = rotatedPt
                                )
                                val innerR = handleRad - (3f / viewModel.zoomScale)
                                drawCircle(
                                    color = Color.White,
                                    radius = innerR,
                                    center = rotatedPt
                                )
                            }
                        }
                    }
                }

                val selId = viewModel.selectedShapeId

                if (selId != null && viewModel.currentTool == VectorTool.ROUNDED_CORNER) {
                    val shape = viewModel.shapes.find { s -> s.id == selId }
                    if (shape != null) {
                        val corners = shape.getLiveCornerWidgetPositions()
                        val activeCornerIdx = viewModel.selectedRoundedCornerIndex
                        corners.forEachIndexed { idx, pt ->
                            val bounds = shape.getBoundingBox()
                            val cx = (bounds.left + bounds.right) / 2f
                            val cy = (bounds.top + bounds.bottom) / 2f
                            val rotatedPt = if (shape.rotationAngle != 0f) rotatePoint(pt, Offset(cx, cy), shape.rotationAngle) else pt
                            
                            val handleRad = (if (idx == activeCornerIdx) 10f else 7f) / viewModel.zoomScale
                            
                            drawCircle(
                                color = Color(0xFF0F172A),
                                radius = handleRad + (2f / viewModel.zoomScale),
                                center = rotatedPt
                            )
                            drawCircle(
                                color = if (idx == activeCornerIdx) Color(0xFFFF5722) else Color(0xFFFFB74D),
                                radius = handleRad,
                                center = rotatedPt
                            )
                            drawCircle(
                                color = Color.White,
                                radius = handleRad - (2.5f / viewModel.zoomScale),
                                center = rotatedPt
                            )
                        }
                    }
                }

                if (viewModel.currentTool == VectorTool.SHAPES && startOffset != null && currentTouchPos != null) {
                    val s = startOffset!!
                    val e = currentTouchPos!!
                    
                    val outlineColor = Color(android.graphics.Color.parseColor(viewModel.currentStrokeColorHex))
                    val previewWidth = viewModel.currentStrokeWidth
                    val fillC = Color(android.graphics.Color.parseColor(viewModel.currentFillColorHex)).copy(alpha = 0.4f)
                    val isAspectLocked = viewModel.isAspectLocked

                    when (viewModel.activePrimitiveType) {
                        PrimitiveType.RECTANGLE -> {
                            val rw = abs(e.x - s.x)
                            val rh = abs(e.y - s.y)
                            val w = if (isAspectLocked) maxOf(rw, rh) else rw
                            val h = if (isAspectLocked) maxOf(rw, rh) else rh
                            val rx = if (e.x >= s.x) s.x else s.x - w
                            val ry = if (e.y >= s.y) s.y else s.y - h
                            
                            if (viewModel.hasFillEnabled) {
                                drawRect(
                                    color = fillC,
                                    topLeft = Offset(rx, ry),
                                    size = Size(w, h)
                                )
                            }
                            drawRect(
                                color = outlineColor,
                                topLeft = Offset(rx, ry),
                                size = Size(w, h),
                                style = Stroke(
                                    width = previewWidth
                                )
                            )
                        }
                        PrimitiveType.ELLIPSE -> {
                            val rx = abs(e.x - s.x)
                            val ry = abs(e.y - s.y)
                            val w = if (isAspectLocked) maxOf(rx, ry) else rx
                            val h = if (isAspectLocked) maxOf(rx, ry) else ry
                            if (viewModel.hasFillEnabled) {
                                drawOval(
                                    color = fillC,
                                    topLeft = Offset(s.x - w, s.y - h),
                                    size = Size(w * 2f, h * 2f)
                                )
                            }
                            drawOval(
                                color = outlineColor,
                                topLeft = Offset(s.x - w, s.y - h),
                                size = Size(w * 2f, h * 2f),
                                style = Stroke(
                                    width = previewWidth
                                )
                            )
                        }
                        PrimitiveType.LINE -> {
                            drawLine(
                                color = outlineColor,
                                start = s,
                                end = e,
                                strokeWidth = previewWidth
                            )
                        }
                        PrimitiveType.TRIANGLE -> {
                            val path = Path().apply {
                                val rx = abs(e.x - s.x)
                                val ry = abs(e.y - s.y)
                                val w = if (isAspectLocked) maxOf(rx, ry) else rx
                                val h = if (isAspectLocked) maxOf(rx, ry) else ry
                                moveTo(s.x, s.y - h)
                                lineTo(s.x + w, s.y + h)
                                lineTo(s.x - w, s.y + h)
                                close()
                            }
                            if (viewModel.hasFillEnabled) {
                                drawPath(path, fillC)
                            }
                            drawPath(
                                path = path,
                                color = outlineColor,
                                style = Stroke(
                                    width = previewWidth
                                )
                            )
                        }
                        PrimitiveType.POLYGON -> {
                            val sides = viewModel.currentPolygonSides
                            val path = Path().apply {
                                val rx = abs(e.x - s.x)
                                val ry = abs(e.y - s.y)
                                val w = if (isAspectLocked) maxOf(rx, ry) else rx
                                val h = if (isAspectLocked) maxOf(rx, ry) else ry
                                for (i in 0 until sides) {
                                    val angle = i * 2 * Math.PI / sides - Math.PI / 2
                                    val px = s.x + w * kotlin.math.cos(angle).toFloat()
                                    val py = s.y + h * kotlin.math.sin(angle).toFloat()
                                    if (i == 0) moveTo(px, py)
                                    else lineTo(px, py)
                                }
                                close()
                            }
                            if (viewModel.hasFillEnabled) {
                                drawPath(path, fillC)
                            }
                            drawPath(
                                path = path,
                                color = outlineColor,
                                style = Stroke(
                                    width = previewWidth
                                )
                            )
                        }
                        PrimitiveType.STAR -> {
                            val pts = viewModel.currentStarPoints
                            val path = Path().apply {
                                val rx = abs(e.x - s.x)
                                val ry = abs(e.y - s.y)
                                val w = if (isAspectLocked) maxOf(rx, ry) else rx
                                val h = if (isAspectLocked) maxOf(rx, ry) else ry
                                val innerRx = w * 0.4f
                                val innerRy = h * 0.4f
                                val totalPoints = pts * 2
                                for (i in 0 until totalPoints) {
                                    val angle = i * Math.PI / pts - Math.PI / 2
                                    val rXFactor = if (i % 2 == 0) w else innerRx
                                    val rYFactor = if (i % 2 == 0) h else innerRy
                                    val px = s.x + rXFactor * kotlin.math.cos(angle).toFloat()
                                    val py = s.y + rYFactor * kotlin.math.sin(angle).toFloat()
                                    if (i == 0) moveTo(px, py)
                                    else lineTo(px, py)
                                }
                                close()
                            }
                            if (viewModel.hasFillEnabled) {
                                drawPath(path, fillC)
                            }
                            drawPath(
                                path = path,
                                color = outlineColor,
                                style = Stroke(
                                    width = previewWidth
                                )
                            )
                        }
                    }
                }

                if (viewModel.currentTool == VectorTool.POINTER && activeDragHandle == "MARQUEE_SELECT") {
                    val sPt = startOffset
                    val ePt = currentTouchPos
                    if (sPt != null && ePt != null) {
                        val rectLeft = minOf(sPt.x, ePt.x)
                        val rectTop = minOf(sPt.y, ePt.y)
                        val rectWidth = abs(sPt.x - ePt.x)
                        val rectHeight = abs(sPt.y - ePt.y)

                        drawRect(
                            color = Color(0x225E3D64),
                            topLeft = Offset(rectLeft, rectTop),
                            size = Size(rectWidth, rectHeight)
                        )
                        drawRect(
                            color = Color(0xFF5E3D64),
                            topLeft = Offset(rectLeft, rectTop),
                            size = Size(rectWidth, rectHeight),
                            style = Stroke(
                                width = 1.5f / viewModel.zoomScale,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                            )
                        )
                    }
                }

                if (viewModel.currentTool == VectorTool.POINTER && viewModel.selectedShapeIds.isNotEmpty()) {
                    var bAngle = 0f
                    val bounds = if (viewModel.selectedShapeIds.size == 1) {
                        val singleId = viewModel.selectedShapeIds.firstOrNull()
                        val singleShape = viewModel.shapes.find { it.id == singleId }
                        bAngle = singleShape?.rotationAngle ?: 0f
                        getUnrotatedCombinedBoundingBox(viewModel.selectedShapeIds, viewModel.shapes)
                    } else if (activeDragHandle == "ROTATE_HOTSPOT" && dragStartShapes != null && initialTouchCanvasPos != null && currentTouchPos != null) {
                        val startingBounds = getUnrotatedCombinedBoundingBox(viewModel.selectedShapeIds, dragStartShapes!!)
                        if (startingBounds != null) {
                            val center = Offset((startingBounds.left + startingBounds.right) / 2f, (startingBounds.top + startingBounds.bottom) / 2f)
                            val startVec = initialTouchCanvasPos!! - center
                            val currentVec = currentTouchPos!! - center
                            val startAngle = Math.toDegrees(Math.atan2(startVec.y.toDouble(), startVec.x.toDouble())).toFloat()
                            val currentAngle = Math.toDegrees(Math.atan2(currentVec.y.toDouble(), currentVec.x.toDouble())).toFloat()
                            var diff = currentAngle - startAngle
                            if (diff < -180f) diff += 360f
                            if (diff > 180f) diff -= 360f
                            bAngle = diff
                        }
                        startingBounds
                    } else {
                        getRotatedCombinedBoundingBox(viewModel.selectedShapeIds, viewModel.shapes)
                    }

                    if (bounds != null) {
                        val isRotating = activeDragHandle == "ROTATE_HOTSPOT"
                        val isMoving = activeDragHandle == "MOVE"
                        val borderW = 1.8f / viewModel.zoomScale
                        val cX = (bounds.left + bounds.right) / 2f
                        val cY = (bounds.top + bounds.bottom) / 2f
                        if (bAngle != 0f) {
                            drawContext.transform.rotate(bAngle, Offset(cX, cY))
                        }

                        val hasGroupedObject = viewModel.selectedShapeIds.any { id -> viewModel.shapes.find { it.id == id }?.groupId != null }
                        val highlightColor = if (hasGroupedObject) Color(0xFF7C4C90) else Color(0xFFFF6D00)
                        
                        if (!isRotating && !isMoving) {

                            // 1. Draw solid bounding box outline (slightly thicker for high definition)
                            drawRect(
                                color = highlightColor,
                                topLeft = Offset(bounds.left, bounds.top),
                                size = Size(bounds.width, bounds.height),
                                style = Stroke(
                                    width = 2.5f / viewModel.zoomScale
                                )
                            )

                            // 2. Draw beautifully larger white-filled rounded corner handles with borders
                            val hRad = 12f / viewModel.zoomScale
                            val cornerPoints = listOf(
                                Offset(bounds.left, bounds.top),
                                Offset(bounds.right, bounds.top),
                                Offset(bounds.left, bounds.bottom),
                                Offset(bounds.right, bounds.bottom)
                            )
                            for (pt in cornerPoints) {
                                drawCircle(
                                    color = Color.White,
                                    radius = hRad,
                                    center = pt
                                )
                                drawCircle(
                                    color = highlightColor,
                                    radius = hRad,
                                    center = pt,
                                    style = Stroke(width = borderW)
                                )
                            }

                        // 3. Draw 4 larger elongated side pills (untuk mempermudah resize sisi) dengan fill putih & border
                        val pillW = 28f / viewModel.zoomScale
                        val pillH = 12f / viewModel.zoomScale
                        val centerXLocal = (bounds.left + bounds.right) / 2f
                        val centerYLocal = (bounds.top + bounds.bottom) / 2f

                        // Top pill (horizontal)
                        drawRoundRect(
                            color = Color.White,
                            topLeft = Offset(centerXLocal - pillW / 2f, bounds.top - pillH / 2f),
                            size = Size(pillW, pillH),
                            cornerRadius = CornerRadius(pillH / 2f, pillH / 2f)
                        )
                        drawRoundRect(
                            color = highlightColor,
                            topLeft = Offset(centerXLocal - pillW / 2f, bounds.top - pillH / 2f),
                            size = Size(pillW, pillH),
                            cornerRadius = CornerRadius(pillH / 2f, pillH / 2f),
                            style = Stroke(width = borderW)
                        )
                        // Bottom pill (horizontal)
                        drawRoundRect(
                            color = Color.White,
                            topLeft = Offset(centerXLocal - pillW / 2f, bounds.bottom - pillH / 2f),
                            size = Size(pillW, pillH),
                            cornerRadius = CornerRadius(pillH / 2f, pillH / 2f)
                        )
                        drawRoundRect(
                            color = highlightColor,
                            topLeft = Offset(centerXLocal - pillW / 2f, bounds.bottom - pillH / 2f),
                            size = Size(pillW, pillH),
                            cornerRadius = CornerRadius(pillH / 2f, pillH / 2f),
                            style = Stroke(width = borderW)
                        )
                        // Left pill (vertical)
                        drawRoundRect(
                            color = Color.White,
                            topLeft = Offset(bounds.left - pillH / 2f, centerYLocal - pillW / 2f),
                            size = Size(pillH, pillW),
                            cornerRadius = CornerRadius(pillH / 2f, pillH / 2f)
                        )
                        drawRoundRect(
                            color = highlightColor,
                            topLeft = Offset(bounds.left - pillH / 2f, centerYLocal - pillW / 2f),
                            size = Size(pillH, pillW),
                            cornerRadius = CornerRadius(pillH / 2f, pillH / 2f),
                            style = Stroke(width = borderW)
                        )
                        // Right pill (vertical)
                        drawRoundRect(
                            color = Color.White,
                            topLeft = Offset(bounds.right - pillH / 2f, centerYLocal - pillW / 2f),
                            size = Size(pillH, pillW),
                            cornerRadius = CornerRadius(pillH / 2f, pillH / 2f)
                        )
                        drawRoundRect(
                            color = highlightColor,
                            topLeft = Offset(bounds.right - pillH / 2f, centerYLocal - pillW / 2f),
                            size = Size(pillH, pillW),
                            cornerRadius = CornerRadius(pillH / 2f, pillH / 2f),
                            style = Stroke(width = borderW)
                        )
                        }

                        // 4. Draw action buttons (centered floating further out & much larger with high-contrast borders)
                        val centerX = (bounds.left + bounds.right) / 2f
                        val topB = bounds.top - 54f / viewModel.zoomScale
                        val botB = bounds.bottom + 54f / viewModel.zoomScale

                        val delPos = Offset(centerX - 42f / viewModel.zoomScale, topB)
                        val dupPos = Offset(centerX + 42f / viewModel.zoomScale, topB)
                        val rotPos = Offset(centerX - 70f / viewModel.zoomScale, botB)
                        val movPos = Offset(centerX, botB)
                        val sclPos = Offset(centerX + 70f / viewModel.zoomScale, botB)

                        val bRadius = 24f / viewModel.zoomScale
                        val strokeW = 1.8f / viewModel.zoomScale

                        val drawIconCircle: (Offset, String) -> Unit = { centerPt, iconType ->
                            if (bAngle != 0f) drawContext.transform.rotate(-bAngle, centerPt)
                            
                            val isRotateActive = iconType == "ROTATE" && isRotating
                            val isMoveActive = iconType == "MOVE" && isMoving
                            val isActionActive = isRotateActive || isMoveActive
                            val currentCircleRadius = if (isActionActive) bRadius * 1.35f else bRadius
                            val currentCircleColor = if (isActionActive) Color(0xFF22C55E) else highlightColor

                            drawCircle(
                                color = currentCircleColor,
                                radius = currentCircleRadius,
                                center = centerPt
                            )
                            drawCircle(
                                color = Color.White,
                                radius = currentCircleRadius,
                                center = centerPt,
                                style = Stroke(width = borderW)
                            )
                            when (iconType) {
                                "DELETE" -> {
                                    val w = 12f / viewModel.zoomScale
                                    val h = 14f / viewModel.zoomScale
                                    val lidY = centerPt.y - h/2f + 3f/viewModel.zoomScale
                                    
                                    // 1. Lid handle (top)
                                    drawRoundRect(
                                        color = Color.White,
                                        topLeft = Offset(centerPt.x - 3f/viewModel.zoomScale, centerPt.y - h/2f + 0.5f/viewModel.zoomScale),
                                        size = Size(6f/viewModel.zoomScale, 3f/viewModel.zoomScale),
                                        cornerRadius = CornerRadius(1f/viewModel.zoomScale, 1f/viewModel.zoomScale),
                                        style = Stroke(width = strokeW)
                                    )
                                    
                                    // 2. Lid/rim line
                                    drawLine(
                                        color = Color.White,
                                        start = Offset(centerPt.x - w/2f - 1.5f/viewModel.zoomScale, lidY),
                                        end = Offset(centerPt.x + w/2f + 1.5f/viewModel.zoomScale, lidY),
                                        strokeWidth = strokeW
                                    )
                                    
                                    // 3. Body of the bin
                                    val binLeftTop = centerPt.x - w/2f + 1f/viewModel.zoomScale
                                    val binRightTop = centerPt.x + w/2f - 1f/viewModel.zoomScale
                                    val binLeftBot = centerPt.x - w/2f + 2f/viewModel.zoomScale
                                    val binRightBot = centerPt.x + w/2f - 2f/viewModel.zoomScale
                                    val binBottomY = centerPt.y + h/2f
                                    
                                    // Bottom line
                                    drawLine(
                                        color = Color.White,
                                        start = Offset(binLeftBot, binBottomY),
                                        end = Offset(binRightBot, binBottomY),
                                        strokeWidth = strokeW
                                    )
                                    // Left border
                                    drawLine(
                                        color = Color.White,
                                        start = Offset(binLeftTop, lidY),
                                        end = Offset(binLeftBot, binBottomY),
                                        strokeWidth = strokeW
                                    )
                                    // Right border
                                    drawLine(
                                        color = Color.White,
                                        start = Offset(binRightTop, lidY),
                                        end = Offset(binRightBot, binBottomY),
                                        strokeWidth = strokeW
                                    )
                                    
                                    // 4. Vertical stripes inside the bin
                                    val stripeYStart = lidY + 3f/viewModel.zoomScale
                                    val stripeYEnd = binBottomY - 2.5f/viewModel.zoomScale
                                    drawLine(
                                        color = Color.White,
                                        start = Offset(centerPt.x - 2f/viewModel.zoomScale, stripeYStart),
                                        end = Offset(centerPt.x - 2f/viewModel.zoomScale, stripeYEnd),
                                        strokeWidth = strokeW
                                    )
                                    drawLine(
                                        color = Color.White,
                                        start = Offset(centerPt.x + 2f/viewModel.zoomScale, stripeYStart),
                                        end = Offset(centerPt.x + 2f/viewModel.zoomScale, stripeYEnd),
                                        strokeWidth = strokeW
                                    )
                                }
                                "DUPLICATE" -> {
                                    val w = 10f / viewModel.zoomScale
                                    val h = 13f / viewModel.zoomScale
                                    val rCorner = 1.8f / viewModel.zoomScale
                                    val shift = 3.5f / viewModel.zoomScale
                                    
                                    // Front document (fully drawn stroke)
                                    val frontRect = androidx.compose.ui.geometry.Rect(
                                        offset = Offset(centerPt.x - w/2f + shift/2f, centerPt.y - h/2f + shift/2f),
                                        size = Size(w, h)
                                    )
                                    
                                    // Let's just draw an L-shape for the back document to avoid overlapping the front document
                                    val backPath = androidx.compose.ui.graphics.Path().apply {
                                        val leftX = centerPt.x - w/2f - shift/2f
                                        val topY = centerPt.y - h/2f - shift/2f
                                        val botY = topY + h - rCorner
                                        val rightX = leftX + w - rCorner
                                        
                                        moveTo(leftX, botY - shift) // bottom-left before intersection
                                        lineTo(leftX, topY + rCorner) // up
                                        quadraticBezierTo(leftX, topY, leftX + rCorner, topY) // corner
                                        lineTo(rightX - shift, topY) // right before intersection
                                    }
                                    drawPath(
                                        path = backPath,
                                        color = Color.White,
                                        style = Stroke(width = strokeW, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                                    )
                                    
                                    // Front document stroke
                                    drawRoundRect(
                                        color = Color.White,
                                        topLeft = frontRect.topLeft,
                                        size = Size(w, h),
                                        cornerRadius = CornerRadius(rCorner, rCorner),
                                        style = Stroke(width = strokeW)
                                    )
                                }
                                "ROTATE" -> {
                                    val iconScale = if (isRotating) 1.35f else 1.0f
                                    val r = (6.5f / viewModel.zoomScale) * iconScale
                                    val arrLen = (3.5f / viewModel.zoomScale) * iconScale
                                    val currentStrokeW = strokeW * iconScale
                                    
                                    // Arc 1: Top-Left to Top-Right
                                    drawArc(
                                        color = Color.White,
                                        startAngle = 190f,
                                        sweepAngle = 140f,
                                        useCenter = false,
                                        topLeft = Offset(centerPt.x - r, centerPt.y - r),
                                        size = Size(r*2f, r*2f),
                                        style = Stroke(width = currentStrokeW)
                                    )
                                    
                                    // Arc 2: Bottom-Right to Bottom-Left
                                    drawArc(
                                        color = Color.White,
                                        startAngle = 10f,
                                        sweepAngle = 140f,
                                        useCenter = false,
                                        topLeft = Offset(centerPt.x - r, centerPt.y - r),
                                        size = Size(r*2f, r*2f),
                                        style = Stroke(width = currentStrokeW)
                                    )
                                    
                                    // Arrowhead 1 at tip of Arc 1 (which ends at 190 + 140 = 330 degrees)
                                    val t1Rad = Math.toRadians(330.0)
                                    val cosT1 = kotlin.math.cos(t1Rad).toFloat()
                                    val sinT1 = kotlin.math.sin(t1Rad).toFloat()
                                    val tip1X = centerPt.x + r * cosT1
                                    val tip1Y = centerPt.y + r * sinT1
                                    
                                    drawLine(
                                        color = Color.White,
                                        start = Offset(tip1X, tip1Y),
                                        end = Offset(tip1X - arrLen * cosT1, tip1Y - arrLen * sinT1),
                                        strokeWidth = currentStrokeW
                                    )
                                    drawLine(
                                        color = Color.White,
                                        start = Offset(tip1X, tip1Y),
                                        end = Offset(tip1X + arrLen * sinT1, tip1Y - arrLen * cosT1),
                                        strokeWidth = currentStrokeW
                                    )
                                    
                                    // Arrowhead 2 at tip of Arc 2 (which ends at 10 + 140 = 150 degrees)
                                    val t2Rad = Math.toRadians(150.0)
                                    val cosT2 = kotlin.math.cos(t2Rad).toFloat()
                                    val sinT2 = kotlin.math.sin(t2Rad).toFloat()
                                    val tip2X = centerPt.x + r * cosT2
                                    val tip2Y = centerPt.y + r * sinT2
                                    
                                    drawLine(
                                        color = Color.White,
                                        start = Offset(tip2X, tip2Y),
                                        end = Offset(tip2X - arrLen * cosT2, tip2Y - arrLen * sinT2),
                                        strokeWidth = currentStrokeW
                                    )
                                    drawLine(
                                        color = Color.White,
                                        start = Offset(tip2X, tip2Y),
                                        end = Offset(tip2X + arrLen * sinT2, tip2Y - arrLen * cosT2),
                                        strokeWidth = currentStrokeW
                                    )
                                }
                                "MOVE" -> {
                                    val iconScale = if (isMoving) 1.35f else 1.0f
                                    val len = (8.5f / viewModel.zoomScale) * iconScale
                                    val arrSize = (2.8f / viewModel.zoomScale) * iconScale
                                    val currentStrokeW = strokeW * iconScale
                                    
                                    // Horizontal Line
                                    drawLine(
                                        color = Color.White,
                                        start = Offset(centerPt.x - len, centerPt.y),
                                        end = Offset(centerPt.x + len, centerPt.y),
                                        strokeWidth = currentStrokeW
                                    )
                                    // Vertical Line
                                    drawLine(
                                        color = Color.White,
                                        start = Offset(centerPt.x, centerPt.y - len),
                                        end = Offset(centerPt.x, centerPt.y + len),
                                        strokeWidth = currentStrokeW
                                    )
                                    
                                    // Left arrowhead
                                    drawLine(color = Color.White, start = Offset(centerPt.x - len, centerPt.y), end = Offset(centerPt.x - len + arrSize, centerPt.y - arrSize), strokeWidth = currentStrokeW)
                                    drawLine(color = Color.White, start = Offset(centerPt.x - len, centerPt.y), end = Offset(centerPt.x - len + arrSize, centerPt.y + arrSize), strokeWidth = currentStrokeW)
                                    
                                    // Right arrowhead
                                    drawLine(color = Color.White, start = Offset(centerPt.x + len, centerPt.y), end = Offset(centerPt.x + len - arrSize, centerPt.y - arrSize), strokeWidth = currentStrokeW)
                                    drawLine(color = Color.White, start = Offset(centerPt.x + len, centerPt.y), end = Offset(centerPt.x + len - arrSize, centerPt.y + arrSize), strokeWidth = currentStrokeW)
                                    
                                    // Top arrowhead
                                    drawLine(color = Color.White, start = Offset(centerPt.x, centerPt.y - len), end = Offset(centerPt.x - arrSize, centerPt.y - len + arrSize), strokeWidth = currentStrokeW)
                                    drawLine(color = Color.White, start = Offset(centerPt.x, centerPt.y - len), end = Offset(centerPt.x + arrSize, centerPt.y - len + arrSize), strokeWidth = currentStrokeW)
                                    
                                    // Bottom arrowhead
                                    drawLine(color = Color.White, start = Offset(centerPt.x, centerPt.y + len), end = Offset(centerPt.x - arrSize, centerPt.y + len - arrSize), strokeWidth = currentStrokeW)
                                    drawLine(color = Color.White, start = Offset(centerPt.x, centerPt.y + len), end = Offset(centerPt.x + arrSize, centerPt.y + len - arrSize), strokeWidth = currentStrokeW)
                                }
                                "LOCK_CLOSED" -> {
                                    val w = 11f / viewModel.zoomScale
                                    val h = 9f / viewModel.zoomScale
                                    // Lock body
                                    drawRoundRect(
                                        color = Color.White,
                                        topLeft = Offset(centerPt.x - w / 2f, centerPt.y - h / 2f + 2f / viewModel.zoomScale),
                                        size = Size(w, h),
                                        cornerRadius = CornerRadius(2f / viewModel.zoomScale, 2f / viewModel.zoomScale),
                                        style = Stroke(width = strokeW)
                                    )
                                    // Shackle (gagang)
                                    val r = 3.2f / viewModel.zoomScale
                                    drawArc(
                                        color = Color.White,
                                        startAngle = 180f,
                                        sweepAngle = 180f,
                                        useCenter = false,
                                        topLeft = Offset(centerPt.x - r, centerPt.y - h / 2f - r + 3f / viewModel.zoomScale),
                                        size = Size(r * 2f, r * 2f),
                                        style = Stroke(width = strokeW)
                                    )
                                    drawLine(
                                        color = Color.White,
                                        start = Offset(centerPt.x - r, centerPt.y - h / 2f + 3f / viewModel.zoomScale),
                                        end = Offset(centerPt.x - r, centerPt.y - h / 2f + 1f / viewModel.zoomScale),
                                        strokeWidth = strokeW
                                    )
                                    drawLine(
                                        color = Color.White,
                                        start = Offset(centerPt.x + r, centerPt.y - h / 2f + 3f / viewModel.zoomScale),
                                        end = Offset(centerPt.x + r, centerPt.y - h / 2f + 1f / viewModel.zoomScale),
                                        strokeWidth = strokeW
                                    )
                                }
                                "LOCK_OPEN" -> {
                                    val w = 11f / viewModel.zoomScale
                                    val h = 9f / viewModel.zoomScale
                                    // Lock body
                                    drawRoundRect(
                                        color = Color.White,
                                        topLeft = Offset(centerPt.x - w / 2f, centerPt.y - h / 2f + 2f / viewModel.zoomScale),
                                        size = Size(w, h),
                                        cornerRadius = CornerRadius(2f / viewModel.zoomScale, 2f / viewModel.zoomScale),
                                        style = Stroke(width = strokeW)
                                    )
                                    // Shackle (unlocked/open)
                                    val r = 3.2f / viewModel.zoomScale
                                    drawArc(
                                        color = Color.White,
                                        startAngle = 180f,
                                        sweepAngle = 180f,
                                        useCenter = false,
                                        topLeft = Offset(centerPt.x - r, centerPt.y - h / 2f - r - 1f / viewModel.zoomScale),
                                        size = Size(r * 2f, r * 2f),
                                        style = Stroke(width = strokeW)
                                    )
                                    drawLine(
                                        color = Color.White,
                                        start = Offset(centerPt.x - r, centerPt.y - h / 2f - 1f / viewModel.zoomScale),
                                        end = Offset(centerPt.x - r, centerPt.y - h / 2f + 1f / viewModel.zoomScale),
                                        strokeWidth = strokeW
                                    )
                                }
                            }
                            if (bAngle != 0f) drawContext.transform.rotate(bAngle, centerPt)
                        }

                        if (isRotating) {
                            drawIconCircle(rotPos, "ROTATE")
                        } else if (isMoving) {
                            drawIconCircle(movPos, "MOVE")
                        } else {
                            drawIconCircle(delPos, "DELETE")
                            drawIconCircle(dupPos, "DUPLICATE")
                            drawIconCircle(rotPos, "ROTATE")
                            drawIconCircle(movPos, "MOVE")
                            drawIconCircle(sclPos, if (viewModel.isAspectLocked) "LOCK_CLOSED" else "LOCK_OPEN")

                            if (viewModel.shapes.any { viewModel.selectedShapeIds.contains(it.id) && it.isLocked }) {
                                val paintIndicator = Paint().apply {
                                    color = android.graphics.Color.RED
                                    style = Paint.Style.FILL
                                    textSize = 15f / viewModel.zoomScale
                                    typeface = Typeface.DEFAULT_BOLD
                                }
                                canvas.nativeCanvas.drawText("LOCKED 🔒", bounds.left + 5f, bounds.top - 5f, paintIndicator)
                            }
                        }
                        if (bAngle != 0f) {
                            drawContext.transform.rotate(-bAngle, Offset(cX, cY))
                        }
                    }
                }

                drawRect(
                    color = Color.LightGray,
                    topLeft = Offset.Zero,
                    size = Size(canvasWidthVal, canvasHeightVal),
                    style = Stroke(width = 1f)
                )

                canvas.restore()
            }
        }


    }
}

@Composable
private fun StaticShapesCanvas(
    viewModel: VectorViewModel,
    canvasWidthVal: Float,
    canvasHeightVal: Float,
    panOffset: Offset,
    zoomScale: Float,
    isGridEnabled: Boolean,
    gridSize: Float,
    gridColorHex: String,
    activeTracerImageIndex: Int?,
    layers: List<com.example.model.VectorLayer>,
    shapes: List<VectorShape>,
    artboardColorHex: String,
    artboardAlpha: Float
) {
    val isPenActive = viewModel.currentTool == VectorTool.PEN && viewModel.activeBezierNodes.isNotEmpty()

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer() // Enabled hardware-accelerated layer caching
    ) {
        drawIntoCanvas { canvas ->
            canvas.save()
            canvas.translate(panOffset.x, panOffset.y)
            canvas.scale(zoomScale, zoomScale)

            val bgCol = try {
                Color(android.graphics.Color.parseColor(artboardColorHex))
            } catch (_: Exception) {
                Color.White
            }.copy(alpha = artboardAlpha)

            drawRect(
                color = bgCol,
                topLeft = Offset.Zero,
                size = Size(canvasWidthVal, canvasHeightVal)
            )

            val activeTracer = activeTracerImageIndex
            if (activeTracer != null) {
                drawWorkspacePresets(activeTracer, canvasWidthVal, canvasHeightVal)
            }

            if (isGridEnabled) {
                val gridS = gridSize
                val paintColor = try {
                    val hex = gridColorHex
                    if (hex.startsWith("0x") || hex.startsWith("0X")) {
                        val parseable = "#" + hex.substring(2)
                        android.graphics.Color.parseColor(parseable)
                    } else {
                        android.graphics.Color.parseColor(hex)
                    }
                } catch (e: Exception) {
                    android.graphics.Color.LTGRAY
                }
                val paint = Paint().apply {
                    color = paintColor
                    style = Paint.Style.STROKE
                    strokeWidth = 1f
                }
                var gx = 0f
                while (gx <= canvasWidthVal) {
                    canvas.nativeCanvas.drawLine(gx, 0f, gx, canvasHeightVal, paint)
                    gx += gridS
                }
                var gy = 0f
                while (gy <= canvasHeightVal) {
                    canvas.nativeCanvas.drawLine(0f, gy, canvasWidthVal, gy, paint)
                    gy += gridS
                }
            }

            // Melakukan looping berurutan dari layer indeks paling bawah ke paling atas
            for (layerIdx in 0 until layers.size) {
                val layer = layers[layerIdx]
                if (!layer.isVisible) continue

                val layerOpacity = layer.opacity
                val isOptimizeTracing = layer.optimizeTracing

                // Optimized: direct indexing of shapes list prevents overhead, GC pauses, and allocation lag
                for (i in 0 until shapes.size) {
                    val shape = shapes[i]
                    if (shape.layerId != layer.id || !shape.isVisible) continue

                    val strokeColor = shape.getStrokeColor().copy(alpha = shape.strokeAlpha * layerOpacity)
                    val fillColor = shape.getFillColor().copy(alpha = shape.fillAlpha * layerOpacity)

                    if (shape.type == com.example.model.ShapeType.IMAGE) {
                        val base64 = shape.textContent
                        if (base64.isNotEmpty()) {
                            val androidBitmap = viewModel.getCachedAndroidBitmap(shape.id, base64)
                            if (androidBitmap != null) {
                                val rect = shape.getBoundingBox()
                                val cx = (rect.left + rect.right) / 2f
                                val cy = (rect.top + rect.bottom) / 2f

                                val paint = android.graphics.Paint().apply {
                                    isAntiAlias = !isOptimizeTracing
                                    alpha = (layerOpacity * 255f).toInt().coerceIn(0, 255)
                                    isFilterBitmap = !isOptimizeTracing
                                }

                                val srcRect = android.graphics.Rect(0, 0, androidBitmap.width, androidBitmap.height)
                                val dstRect = android.graphics.RectF(
                                    rect.left,
                                    rect.top,
                                    rect.right,
                                    rect.bottom
                                )

                                if (shape.rotationAngle != 0f) {
                                    canvas.nativeCanvas.save()
                                    canvas.nativeCanvas.rotate(shape.rotationAngle, cx, cy)
                                    canvas.nativeCanvas.drawBitmap(androidBitmap, srcRect, dstRect, paint)
                                    canvas.nativeCanvas.restore()
                                } else {
                                    canvas.nativeCanvas.drawBitmap(androidBitmap, srcRect, dstRect, paint)
                                }
                            }
                        }
                    } else if (shape.type == com.example.model.ShapeType.TEXT) {
                        val paintText = Paint().apply {
                            color = strokeColor.toArgb()
                            textSize = shape.fontSize
                            typeface = viewModel.importedTypeface ?: Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                            isAntiAlias = !isOptimizeTracing
                        }
                        canvas.nativeCanvas.drawText(
                            shape.textContent,
                            shape.x,
                            shape.y,
                            paintText
                        )
                    } else {
                        val composePath = shape.asComposePath()

                        if (shape.hasFill && shape.type != com.example.model.ShapeType.LINE) {
                            drawPath(
                                path = composePath,
                                color = fillColor
                            )
                        }

                        if (shape.hasStroke && shape.strokeWidth > 0f) {
                            drawPath(
                                path = composePath,
                                color = strokeColor,
                                style = Stroke(
                                    width = shape.strokeWidth,
                                    join = mapStrokeJoin(shape.strokeJoin),
                                    cap = mapStrokeCap(shape.strokeCap),
                                    pathEffect = mapLineStyle(shape.lineStyle, shape.strokeWidth)
                                )
                            )
                        }
                    }
                }
            }

            canvas.restore()
        }
    }
}

fun drawIntoCanvasHelper(
    scope: androidx.compose.ui.graphics.drawscope.DrawScope,
    index: Int,
    w: Float,
    h: Float
) {
}

fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWorkspacePresets(index: Int, w: Float, h: Float) {
    val col = when (index) {
        0 -> Color(0xFF94A3B8)
        1 -> Color(0xFFFFB7B2)
        2 -> Color(0xFFCBD5E1)
        else -> Color.Gray
    }

    when (index) {
        0 -> {
            drawRect(
                color = col,
                topLeft = Offset(w * 0.2f, h * 0.5f),
                size = Size(w * 0.6f, h * 0.4f),
                style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f)))
            )
            drawLine(
                color = col,
                start = Offset(w * 0.2f, h * 0.5f),
                end = Offset(w * 0.5f, h * 0.25f),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f))
            )
            drawLine(
                color = col,
                start = Offset(w * 0.5f, h * 0.25f),
                end = Offset(w * 0.8f, h * 0.5f),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f))
            )
            drawRect(
                color = col,
                topLeft = Offset(w * 0.45f, h * 0.7f),
                size = Size(w * 0.1f, h * 0.2f),
                style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f)))
            )
        }
        1 -> {
            drawCircle(
                color = col,
                radius = w * 0.25f,
                center = Offset(w * 0.5f, h * 0.5f),
                style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
            )
            drawLine(
                color = col,
                start = Offset(w * 0.35f, h * 0.3f),
                end = Offset(w * 0.3f, h * 0.15f),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
            )
            drawLine(
                color = col,
                start = Offset(w * 0.3f, h * 0.15f),
                end = Offset(w * 0.45f, h * 0.26f),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
            )
            drawLine(
                color = col,
                start = Offset(w * 0.65f, h * 0.3f),
                end = Offset(w * 0.7f, h * 0.15f),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
            )
            drawLine(
                color = col,
                start = Offset(w * 0.7f, h * 0.15f),
                end = Offset(w * 0.55f, h * 0.26f),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
            )
        }
        2 -> {
            drawCircle(
                color = col,
                radius = w * 0.4f,
                center = Offset(w * 0.5f, h * 0.5f),
                style = Stroke(width = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            )
            drawCircle(
                color = col,
                radius = w * 0.25f,
                center = Offset(w * 0.5f, h * 0.5f),
                style = Stroke(width = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            )
            drawLine(
                color = col,
                start = Offset(0f, h * 0.5f),
                end = Offset(w, h * 0.5f),
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
            )
            drawLine(
                color = col,
                start = Offset(w * 0.5f, 0f),
                end = Offset(w * 0.5f, h),
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
            )
        }
    }
}

data class NearestSegmentResult(
    val segmentIndex: Int, 
    val t: Float,          
    val distance: Float,   
    val isClosedSegment: Boolean = false
)

private fun getCubicBezierPoint(p0: Offset, p1: Offset, p2: Offset, p3: Offset, t: Float): Offset {
    val coeff0 = (1f - t) * (1f - t) * (1f - t)
    val coeff1 = 3f * (1f - t) * (1f - t) * t
    val coeff2 = 3f * (1f - t) * t * t
    val coeff3 = t * t * t
    return Offset(
        p0.x * coeff0 + p1.x * coeff1 + p2.x * coeff2 + p3.x * coeff3,
        p0.y * coeff0 + p1.y * coeff1 + p2.y * coeff2 + p3.y * coeff3
    )
}

private fun findNearestSegment(
    touchPos: Offset,
    nodes: List<com.example.model.BezierNode>,
    isClosed: Boolean,
    tolerance: Float
): NearestSegmentResult? {
    if (nodes.size < 2) return null
    var bestResult: NearestSegmentResult? = null
    var minDistance = Float.MAX_VALUE
    
    for (i in 1 until nodes.size) {
        val prev = nodes[i - 1]
        val curr = nodes[i]
        
        val p0 = prev.getAnchor()
        val p3 = curr.getAnchor()
        val p1 = if (prev.isCurve) prev.getHandleOut() else p0 + (p3 - p0) / 3f
        val p2 = if (curr.isCurve) curr.getHandleIn() else p0 + (p3 - p0) * 2f / 3f
        
        for (step in 0..20) {
            val t = step / 20f
            val pt = getCubicBezierPoint(p0, p1, p2, p3, t)
            val dist = kotlin.math.hypot(touchPos.x - pt.x, touchPos.y - pt.y)
            if (dist < minDistance && dist < tolerance) {
                minDistance = dist
                bestResult = NearestSegmentResult(
                    segmentIndex = i,
                    t = t,
                    distance = dist,
                    isClosedSegment = false
                )
            }
        }
    }
    
    if (isClosed && nodes.size >= 2) {
        val prev = nodes.last()
        val curr = nodes.first()
        
        val p0 = prev.getAnchor()
        val p3 = curr.getAnchor()
        val p1 = if (prev.isCurve) prev.getHandleOut() else p0 + (p3 - p0) / 3f
        val p2 = if (curr.isCurve) curr.getHandleIn() else p1
        
        for (step in 0..20) {
            val t = step / 20f
            val pt = getCubicBezierPoint(p0, p1, p2, p3, t)
            val dist = kotlin.math.hypot(touchPos.x - pt.x, touchPos.y - pt.y)
            if (dist < minDistance && dist < tolerance) {
                minDistance = dist
                bestResult = NearestSegmentResult(
                    segmentIndex = 0,
                    t = t,
                    distance = dist,
                    isClosedSegment = true
                )
            }
        }
    }
    
    return bestResult
}
