package com.example.viewmodel

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import com.example.model.BezierNode
import com.example.model.SerializedPoint
import com.example.model.ShapeType
import com.example.model.VectorShape
import java.io.File
import java.util.UUID

enum class VectorTool {
    BRUSH,       // Row 1, #1: Freehand pen drawing
    BUCKET,      // Row 1, #2: Paint bucket filling
    GRID,        // Row 1, #3: Grid panel & settings toggle
    HAND,        // Row 1, #4: Workspace panning and scrolling
    PEN,         // Row 1, #5: Vector Bezier curve node editor
    POINTER,     // Row 1, #6: Selection and scaling transformations
    DIRECT_SELECTION, // Row 1, #6b: Direct Selection edit nodes
    ROUNDED_CORNER,   // Row 1, #6c: Round individual rect corners
    SHAPES       // Row 1, #7: Standard vector primitives
}

enum class PrimitiveType {
    RECTANGLE,
    ELLIPSE,
    POLYGON,
    STAR,
    TRIANGLE,
    LINE
}

enum class NodeEditMode {
    NONE,
    ADD,
    REMOVE,
    CUT,
    SPLIT,
    CLOSE
}

data class SmartGuideInfo(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val type: String, // "ALIGN_LINE", "SPACING"
    val label: String = ""
)

class VectorViewModel(application: Application) : AndroidViewModel(application) {
    private val sharedPrefs = application.getSharedPreferences("vector_prefs", Context.MODE_PRIVATE)

    // Current State
    private var _shapes by mutableStateOf<List<VectorShape>>(emptyList())
    var shapes: List<VectorShape>
        get() = _shapes
        set(value) {
            _shapes = value
            if (isSetupCompleted && !isAutosaveSuspended) {
                saveCurrentProject()
            }
        }

    var isAutosaveSuspended by mutableStateOf(false)

    // Layer Management System
    private var _layers by mutableStateOf<List<com.example.model.VectorLayer>>(
        listOf(com.example.model.VectorLayer(id = "default_layer", name = "Layer 1"))
    )
    var layers: List<com.example.model.VectorLayer>
        get() = _layers
        set(value) {
            _layers = value
            if (isSetupCompleted && !isAutosaveSuspended) {
                saveCurrentProject()
            }
        }

    private var _activeLayerId by mutableStateOf("default_layer")
    var activeLayerId: String
        get() = _activeLayerId
        set(value) {
            _activeLayerId = value
            if (isSetupCompleted && !isAutosaveSuspended) {
                saveCurrentProject()
            }
        }

    // Operations for Layers
    fun addNewLayer() {
        val newLayer = com.example.model.VectorLayer(name = "Layer ${layers.size + 1}")
        val activeIndex = layers.indexOfFirst { it.id == activeLayerId }
        val insertIndex = if (activeIndex != -1) activeIndex + 1 else layers.size
        
        val newLayers = layers.toMutableList()
        newLayers.add(insertIndex, newLayer)
        layers = newLayers
        activeLayerId = newLayer.id
    }

    fun deleteLayer(layerId: String) {
        if (layers.size <= 1) return // Prevent deleting the last layer
        
        pushToUndoStack()
        
        // Remove layer
        layers = layers.filter { it.id != layerId }
        // Remove objects in layer
        shapes = shapes.filter { it.layerId != layerId }
        
        if (activeLayerId == layerId) {
            activeLayerId = layers.lastOrNull()?.id ?: ""
        }
    }

    fun reorderLayers(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        val newLayers = layers.toMutableList()
        val moveItem = newLayers.removeAt(fromIndex)
        val targetIndex = if (toIndex >= newLayers.size) newLayers.size else toIndex
        newLayers.add(targetIndex, moveItem)
        layers = newLayers
    }

    fun moveSelectedObjectToLayer(objectId: String, targetLayerId: String) {
        pushToUndoStack()
        shapes = shapes.map { if (it.id == objectId) it.copy(layerId = targetLayerId) else it }
    }

    private var _selectedShapeId by mutableStateOf<String?>(null)
    var selectedShapeId: String?
        get() = _selectedShapeId
        set(value) {
            _selectedShapeId = value
            updateFrozenBoundsForSelection()
        }

    private var _selectedShapeIds by mutableStateOf<Set<String>>(emptySet())
    var selectedShapeIds: Set<String>
        get() = _selectedShapeIds
        set(value) {
            _selectedShapeIds = value
            updateFrozenBoundsForSelection()
        }
    var isCloneModeActive by mutableStateOf(false)
    
    // Tools parameters
    var currentPolygonSides by mutableStateOf(6)
    var currentStarPoints by mutableStateOf(5)
    var currentLineStyle by mutableStateOf("SOLID") // "SOLID", "DASHED", "DOTTED"

    private var _currentTool by mutableStateOf(VectorTool.POINTER)
    var currentTool: VectorTool
        get() = _currentTool
        set(value) {
            val oldTool = _currentTool
            _currentTool = value
            activeEditNodeIndex = null
            selectedDirectSelectionNodes = emptySet()
            isCloneModeActive = false
            
            if (value == VectorTool.DIRECT_SELECTION && oldTool != VectorTool.DIRECT_SELECTION) {
                updateFrozenBoundsForSelection()
            } else if (value != VectorTool.DIRECT_SELECTION && oldTool == VectorTool.DIRECT_SELECTION) {
                // Unfreeze bounding boxes and compensate pivot shift
                shapes = shapes.map { shape -> 
                    if (shape.frozenBoundsLeft != null && shape.frozenBoundsTop != null && shape.frozenBoundsRight != null && shape.frozenBoundsBottom != null) {
                        val frozenCx = (shape.frozenBoundsLeft + shape.frozenBoundsRight) / 2f
                        val frozenCy = (shape.frozenBoundsTop + shape.frozenBoundsBottom) / 2f
                        
                        val tempShape = shape.copy(frozenBoundsLeft = null, frozenBoundsTop = null, frozenBoundsRight = null, frozenBoundsBottom = null)
                        val unfrozenBounds = tempShape.getBoundingBox()
                        val unfrozenCx = (unfrozenBounds.left + unfrozenBounds.right) / 2f
                        val unfrozenCy = (unfrozenBounds.top + unfrozenBounds.bottom) / 2f
                        
                        if (shape.rotationAngle != 0f) {
                            val dx = unfrozenCx - frozenCx
                            val dy = unfrozenCy - frozenCy
                            
                            val rad = Math.toRadians(shape.rotationAngle.toDouble())
                            val cos = kotlin.math.cos(rad).toFloat()
                            val sin = kotlin.math.sin(rad).toFloat()
                            
                            val rotX = dx * cos - dy * sin
                            val rotY = dx * sin + dy * cos
                            
                            val deltaPx = rotX - dx
                            val deltaPy = rotY - dy
                            
                            if (tempShape.type == com.example.model.ShapeType.BEZIER_PATH) {
                                tempShape.copy(
                                    bezierNodes = tempShape.bezierNodes.map { node ->
                                        node.copy(
                                            anchorX = node.anchorX + deltaPx,
                                            anchorY = node.anchorY + deltaPy,
                                            control1X = node.control1X + deltaPx,
                                            control1Y = node.control1Y + deltaPy,
                                            control2X = node.control2X + deltaPx,
                                            control2Y = node.control2Y + deltaPy
                                        )
                                    }
                                )
                            } else if (tempShape.type == com.example.model.ShapeType.FREEHAND) {
                                tempShape.copy(
                                    freehandPoints = tempShape.freehandPoints.map {
                                        com.example.model.SerializedPoint(it.x + deltaPx, it.y + deltaPy)
                                    }
                                )
                            } else {
                                tempShape.copy(
                                    x = tempShape.x + deltaPx,
                                    y = tempShape.y + deltaPy,
                                    startX = tempShape.startX + deltaPx,
                                    startY = tempShape.startY + deltaPy,
                                    endX = tempShape.endX + deltaPx,
                                    endY = tempShape.endY + deltaPy
                                )
                            }
                        } else {
                            tempShape
                        }
                    } else {
                        shape
                    }
                }
            }
        }

    private fun updateFrozenBoundsForSelection() {
        if (_currentTool == VectorTool.DIRECT_SELECTION) {
            shapes = shapes.map { shape -> 
                if (_selectedShapeIds.contains(shape.id) || shape.id == _selectedShapeId) {
                    // Temporarily unfreeze to get real bounds
                    val tempShape = shape.copy(frozenBoundsLeft = null, frozenBoundsTop = null, frozenBoundsRight = null, frozenBoundsBottom = null)
                    val bounds = tempShape.getBoundingBox()
                    shape.copy(
                        frozenBoundsLeft = bounds.left,
                        frozenBoundsTop = bounds.top,
                        frozenBoundsRight = bounds.right,
                        frozenBoundsBottom = bounds.bottom
                    )
                } else {
                    shape.copy(
                        frozenBoundsLeft = null,
                        frozenBoundsTop = null,
                        frozenBoundsRight = null,
                        frozenBoundsBottom = null
                    )
                }
            }
        }
    }
    var activePrimitiveType by mutableStateOf(PrimitiveType.RECTANGLE)
    var isAspectLocked by mutableStateOf(true)

    // Style Parameters (State connected directly to both bottom sliders and color pickers)
    var currentStrokeWidth by mutableStateOf(8f)
    var currentStrokeColorHex by mutableStateOf("#333333")
    var currentStrokeAlpha by mutableStateOf(1f)
    var currentStrokeJoin by mutableStateOf("ROUND") // "MITER", "ROUND", "BEVEL"
    var currentStrokeCap by mutableStateOf("ROUND")   // "ROUND", "BUTT", "SQUARE"
    var hasFillEnabled by mutableStateOf(true)
    var hasStrokeEnabled by mutableStateOf(true)
    var currentFillColorHex by mutableStateOf("#E0E0E0")
    var currentFillAlpha by mutableStateOf(1f)

    // Path building states
    var activeFreehandPoints by mutableStateOf<List<SerializedPoint>>(emptyList())
    var activeBezierNodes by mutableStateOf<List<BezierNode>>(emptyList())
    var isPathClosed by mutableStateOf(false)

    // Selection node & corner controllers
    var selectedDirectSelectionNodes by mutableStateOf<Set<Int>>(emptySet())
    
    fun clearDirectSelection() {
        selectedDirectSelectionNodes = emptySet()
    }
    var currentNodeEditMode by mutableStateOf(NodeEditMode.NONE)
    var selectedRoundedCornerIndex by mutableStateOf<Int?>(null)
    var manualCornerRadiusText by mutableStateOf("")

    // Pen tool active edit node tracking
    var activeEditNodeIndex by mutableStateOf<Int?>(null)
    var activePenDragSegmentIndex by mutableStateOf<Int?>(null)
    var isPenEditMode by mutableStateOf(false)

    fun updateSelectedShapeCornerRadius(radius: Float) {
        val sId = selectedShapeId ?: return
        pushToUndoStack()
        shapes = shapes.map { s ->
            if (s.id == sId) {
                when (s.type) {
                    ShapeType.RECTANGLE -> {
                        s.copy(radiusTL = radius, radiusTR = radius, radiusBR = radius, radiusBL = radius)
                    }
                    ShapeType.POLYGON, ShapeType.STAR -> {
                        val numCorners = s.getCornerPoints().size
                        val list = MutableList(numCorners) { radius }
                        s.copy(customCornerRadii = list)
                    }
                    ShapeType.BEZIER_PATH -> {
                        val numCorners = s.getCornerPoints().size
                        val nodes = s.bezierNodes
                        val list = MutableList(numCorners) { i ->
                            val node = nodes.getOrNull(i)
                            if (node?.handleType == "SMOOTH_RENDER_ONLY") {
                                s.customCornerRadii.getOrNull(i) ?: 0f
                            } else {
                                radius
                            }
                        }
                        s.copy(customCornerRadii = list)
                    }
                    else -> s
                }
            } else {
                s
            }
        }
    }

    fun bakeBezierCorners() {
        val sId = selectedShapeId ?: return
        var changed = false
        val oldShapes = ArrayList(shapes)
        val updated = shapes.map { s ->
            if (s.id == sId && s.type == ShapeType.BEZIER_PATH && s.customCornerRadii.any { it > 0.1f }) {
                changed = true
                s.bakedRoundedCorners()
            } else {
                s
            }
        }
        if (changed) {
            pushCustomListToUndoStack(oldShapes)
            shapes = updated
            selectedRoundedCornerIndex = null
            manualCornerRadiusText = "0"
        }
    }

    fun updateSpecificCornerRadius(index: Int, radius: Float) {
        val sId = selectedShapeId ?: return
        pushToUndoStack()
        shapes = shapes.map { s ->
            if (s.id == sId) {
                when (s.type) {
                    ShapeType.RECTANGLE -> {
                        when (index) {
                            0 -> s.copy(radiusTL = radius)
                            1 -> s.copy(radiusTR = radius)
                            2 -> s.copy(radiusBR = radius)
                            3 -> s.copy(radiusBL = radius)
                            else -> s
                        }
                    }
                    ShapeType.POLYGON, ShapeType.STAR -> {
                        val numCorners = s.getCornerPoints().size
                        val currentList = s.customCornerRadii.toMutableList()
                        while (currentList.size < numCorners) {
                            currentList.add(0f)
                        }
                        if (index in 0 until numCorners) {
                            currentList[index] = radius
                        }
                        s.copy(customCornerRadii = currentList)
                    }
                    ShapeType.BEZIER_PATH -> {
                        val numCorners = s.getCornerPoints().size
                        val currentList = s.customCornerRadii.toMutableList()
                        while (currentList.size < numCorners) {
                            currentList.add(0f)
                        }
                        if (index in 0 until numCorners) {
                            val node = s.bezierNodes.getOrNull(index)
                            if (node?.handleType != "SMOOTH_RENDER_ONLY") {
                                currentList[index] = radius
                            }
                        }
                        s.copy(customCornerRadii = currentList)
                    }
                    else -> s
                }
            } else {
                s
            }
        }
    }

    fun translateDirectSelection(shapeId: String, startShape: com.example.model.VectorShape, dx: Float, dy: Float) {
        shapes = shapes.map { shape ->
            if (shape.id == shapeId && shape.type == ShapeType.BEZIER_PATH) {
                val newNodes = shape.bezierNodes.mapIndexed { idx, currentNode ->
                    val startNode = startShape.bezierNodes.getOrNull(idx) ?: currentNode
                    
                    if (selectedDirectSelectionNodes.contains(idx)) {
                        startNode.copy(
                            anchorX = startNode.anchorX + dx,
                            anchorY = startNode.anchorY + dy,
                            control1X = startNode.control1X + dx,
                            control1Y = startNode.control1Y + dy,
                            control2X = startNode.control2X + dx,
                            control2Y = startNode.control2Y + dy
                        )
                    } else {
                        startNode
                    }
                }
                shape.copy(bezierNodes = newNodes)
            } else {
                shape
            }
        }
    }

    fun updateShapeNode(shapeId: String, nodeIndex: Int, newPos: Offset) {
        shapes = shapes.map { shape ->
            if (shape.id == shapeId) {
                when (shape.type) {
                    ShapeType.LINE -> {
                        if (nodeIndex == 0) {
                            shape.copy(startX = newPos.x, startY = newPos.y)
                        } else if (nodeIndex == 1) {
                            shape.copy(endX = newPos.x, endY = newPos.y)
                        } else {
                            shape
                        }
                    }
                    ShapeType.BEZIER_PATH -> {
                        val updatedNodes = shape.bezierNodes.mapIndexed { idx, node ->
                            if (idx == nodeIndex) {
                                val dx = newPos.x - node.anchorX
                                val dy = newPos.y - node.anchorY
                                node.copy(
                                    anchorX = newPos.x,
                                    anchorY = newPos.y,
                                    control1X = node.control1X + dx,
                                    control1Y = node.control1Y + dy,
                                    control2X = node.control2X + dx,
                                    control2Y = node.control2Y + dy
                                )
                            } else {
                                node
                            }
                        }
                        shape.copy(bezierNodes = updatedNodes)
                    }
                    ShapeType.FREEHAND -> {
                        val updatedPts = shape.freehandPoints.mapIndexed { idx, pt ->
                            if (idx == nodeIndex) SerializedPoint(newPos.x, newPos.y) else pt
                        }
                        shape.copy(freehandPoints = updatedPts)
                    }
                    ShapeType.RECTANGLE -> {
                        var nx = shape.x
                        var ny = shape.y
                        var nw = shape.width
                        var nh = shape.height
                        when (nodeIndex) {
                            0 -> {
                                nx = newPos.x
                                ny = newPos.y
                                nw = shape.width + (shape.x - newPos.x)
                                nh = shape.height + (shape.y - newPos.y)
                            }
                            1 -> {
                                ny = newPos.y
                                nw = newPos.x - shape.x
                                nh = shape.height + (shape.y - newPos.y)
                            }
                            2 -> {
                                nw = newPos.x - shape.x
                                nh = newPos.y - shape.y
                            }
                            3 -> {
                                nx = newPos.x
                                nw = shape.width + (shape.x - newPos.x)
                                nh = shape.y - newPos.y
                            }
                        }
                        if (nw < 2f) nw = 2f
                        if (nh < 2f) nh = 2f
                        shape.copy(x = nx, y = ny, width = nw, height = nh)
                    }
                    else -> shape
                }
            } else {
                shape
            }
        }
    }

    fun updateShapeHandle(shapeId: String, nodeIndex: Int, handleType: String, newPos: Offset) {
        shapes = shapes.map { shape ->
            if (shape.id == shapeId && shape.type == ShapeType.BEZIER_PATH) {
                val updatedNodes = shape.bezierNodes.mapIndexed { idx, node ->
                    if (idx == nodeIndex) {
                        val anchorX = node.anchorX
                        val anchorY = node.anchorY
                        val type = node.nodeType
                        
                        if (handleType == "in" || handleType == "control1") {
                            val dx1 = newPos.x - anchorX
                            val dy1 = newPos.y - anchorY
                            val d1 = hypot(dx1, dy1)
                            
                            when (type) {
                                "ASIMETRIS", "HALUS" -> {
                                    if (d1 > 0.1f) {
                                        val ux = dx1 / d1
                                        val uy = dy1 / d1
                                        val dx2 = node.control2X - anchorX
                                        val dy2 = node.control2Y - anchorY
                                        val d2 = hypot(dx2, dy2)
                                        node.copy(
                                            isCurve = true,
                                            control1X = newPos.x,
                                            control1Y = newPos.y,
                                            control2X = anchorX - ux * d2,
                                            control2Y = anchorY - uy * d2
                                        )
                                    } else {
                                        node.copy(isCurve = true, control1X = newPos.x, control1Y = newPos.y)
                                    }
                                }
                                "SIMETRIS" -> {
                                    if (d1 > 0.1f) {
                                        val ux = dx1 / d1
                                        val uy = dy1 / d1
                                        node.copy(
                                            isCurve = true,
                                            control1X = newPos.x,
                                            control1Y = newPos.y,
                                            control2X = anchorX - ux * d1,
                                            control2Y = anchorY - uy * d1
                                        )
                                    } else {
                                        node.copy(isCurve = true, control1X = newPos.x, control1Y = newPos.y)
                                    }
                                }
                                else -> { // "BEBAS"
                                    node.copy(
                                        isCurve = true,
                                        control1X = newPos.x,
                                        control1Y = newPos.y
                                    )
                                }
                            }
                        } else { // "out" or "control2"
                            val dx2 = newPos.x - anchorX
                            val dy2 = newPos.y - anchorY
                            val d2 = hypot(dx2, dy2)
                            
                            when (type) {
                                "ASIMETRIS", "HALUS" -> {
                                    if (d2 > 0.1f) {
                                        val ux = dx2 / d2
                                        val uy = dy2 / d2
                                        val dx1 = node.control1X - anchorX
                                        val dy1 = node.control1Y - anchorY
                                        val d1 = hypot(dx1, dy1)
                                        node.copy(
                                            isCurve = true,
                                            control1X = anchorX - ux * d1,
                                            control1Y = anchorY - uy * d1,
                                            control2X = newPos.x,
                                            control2Y = newPos.y
                                        )
                                    } else {
                                        node.copy(isCurve = true, control2X = newPos.x, control2Y = newPos.y)
                                    }
                                }
                                "SIMETRIS" -> {
                                    if (d2 > 0.1f) {
                                        val ux = dx2 / d2
                                        val uy = dy2 / d2
                                        node.copy(
                                            isCurve = true,
                                            control1X = anchorX - ux * d2,
                                            control1Y = anchorY - uy * d2,
                                            control2X = newPos.x,
                                            control2Y = newPos.y
                                        )
                                    } else {
                                        node.copy(isCurve = true, control2X = newPos.x, control2Y = newPos.y)
                                    }
                                }
                                else -> { // "BEBAS"
                                    node.copy(
                                        isCurve = true,
                                        control2X = newPos.x,
                                        control2Y = newPos.y
                                    )
                                }
                            }
                        }
                    } else {
                        node
                    }
                }
                shape.copy(bezierNodes = updatedNodes)
            } else {
                shape
            }
        }
    }

    fun toggleShapeNodeCurveType(shapeId: String, nodeIndex: Int) {
        pushToUndoStack()
        shapes = shapes.map { shape ->
            if (shape.id == shapeId && shape.type == ShapeType.BEZIER_PATH) {
                val nodes = shape.bezierNodes.toMutableList()
                if (nodeIndex in nodes.indices) {
                    val oldNode = nodes[nodeIndex]
                    val isNowCurve = !oldNode.isCurve
                    
                    if (isNowCurve) {
                        // Restore/regenerate handles based on adjacent nodes
                        val prev = nodes[(nodeIndex - 1 + nodes.size) % nodes.size]
                        val next = nodes[(nodeIndex + 1) % nodes.size]
                        val dx = next.anchorX - prev.anchorX
                        val dy = next.anchorY - prev.anchorY
                        val len = hypot(dx, dy)
                        if (len > 0.1f) {
                            val handleLen = len * 0.25f
                            val ux = dx / len
                            val uy = dy / len
                            nodes[nodeIndex] = oldNode.copy(
                                isCurve = true,
                                control1X = oldNode.anchorX - ux * handleLen,
                                control1Y = oldNode.anchorY - uy * handleLen,
                                control2X = oldNode.anchorX + ux * handleLen,
                                control2Y = oldNode.anchorY + uy * handleLen
                            )
                        } else {
                            nodes[nodeIndex] = oldNode.copy(
                                isCurve = true,
                                control1X = oldNode.anchorX - 30f,
                                control1Y = oldNode.anchorY,
                                control2X = oldNode.anchorX + 30f,
                                control2Y = oldNode.anchorY
                            )
                        }
                    } else {
                        // Collapse handles onto the anchor for a sharp node (sudut)
                        nodes[nodeIndex] = oldNode.copy(
                            isCurve = false,
                            control1X = oldNode.anchorX,
                            control1Y = oldNode.anchorY,
                            control2X = oldNode.anchorX,
                            control2Y = oldNode.anchorY
                        )
                    }
                    shape.copy(bezierNodes = nodes)
                } else {
                    shape
                }
            } else {
                shape
            }
        }
    }

    fun addNodeOnStroke(shapeId: String, segmentIndex: Int, t: Float, isClosedSegment: Boolean) {
        pushToUndoStack()
        shapes = shapes.map { shape ->
            if (shape.id == shapeId && shape.type == ShapeType.BEZIER_PATH) {
                val nodes = shape.bezierNodes.toMutableList()
                val size = nodes.size
                if (size >= 2) {
                    val prevIdx = if (isClosedSegment) size - 1 else segmentIndex - 1
                    val currIdx = if (isClosedSegment) 0 else segmentIndex
                    val prev = nodes[prevIdx]
                    val curr = nodes[currIdx]

                    val p0 = prev.getAnchor()
                    val p3 = curr.getAnchor()
                    val p1 = if (prev.isCurve) prev.getHandleOut() else p0 + (p3 - p0) / 3f
                    val p2 = if (curr.isCurve) curr.getHandleIn() else p0 + (p3 - p0) * 2f / 3f

                    val q0 = p0 + (p1 - p0) * t
                    val q1 = p1 + (p2 - p1) * t
                    val q2 = p2 + (p3 - p2) * t

                    val r0 = q0 + (q1 - q0) * t
                    val r1 = q1 + (q2 - q1) * t

                    val s = r0 + (r1 - r0) * t

                    // Update prev node's handle-out
                    val updatedPrev = prev.copy(
                        isCurve = true,
                        control2X = q0.x,
                        control2Y = q0.y
                    )
                    // Update curr node's handle-in
                    val updatedCurr = curr.copy(
                        isCurve = true,
                        control1X = q2.x,
                        control1Y = q2.y
                    )
                    
                    // Insert the new node
                    val newNode = BezierNode(
                        anchorX = s.x,
                        anchorY = s.y,
                        isCurve = true,
                        control1X = r0.x,
                        control1Y = r0.y,
                        control2X = r1.x,
                        control2Y = r1.y,
                        isMoveTo = false,
                        nodeType = prev.nodeType
                    )

                    nodes[prevIdx] = updatedPrev
                    nodes[currIdx] = updatedCurr
                    
                    if (isClosedSegment) {
                        nodes.add(newNode)
                    } else {
                        nodes.add(segmentIndex, newNode)
                    }
                    shape.copy(bezierNodes = nodes)
                } else {
                    shape
                }
            } else {
                shape
            }
        }
    }

    fun removeNodeAt(shapeId: String, nodeIndex: Int) {
        pushToUndoStack()
        shapes = shapes.map { shape ->
            if (shape.id == shapeId && shape.type == ShapeType.BEZIER_PATH) {
                val nodes = shape.bezierNodes.toMutableList()
                if (nodeIndex in nodes.indices) {
                    nodes.removeAt(nodeIndex)
                    if (nodes.isNotEmpty() && nodeIndex == 0) {
                        nodes[0] = nodes[0].copy(isMoveTo = true)
                    }
                    shape.copy(bezierNodes = nodes)
                } else {
                    shape
                }
            } else {
                shape
            }
        }
        selectedDirectSelectionNodes = emptySet()
    }

    fun cutStrokeAt(shapeId: String, segmentIndex: Int, t: Float, isClosedSegment: Boolean) {
        pushToUndoStack()
        val shape = shapes.find { it.id == shapeId } ?: return
        if (shape.type != ShapeType.BEZIER_PATH) return
        val nodes = shape.bezierNodes
        val size = nodes.size
        if (size < 2) return

        val prevIdx = if (isClosedSegment) size - 1 else segmentIndex - 1
        val currIdx = if (isClosedSegment) 0 else segmentIndex
        val prev = nodes[prevIdx]
        val curr = nodes[currIdx]

        val p0 = prev.getAnchor()
        val p3 = curr.getAnchor()
        val p1 = if (prev.isCurve) prev.getHandleOut() else p0 + (p3 - p0) / 3f
        val p2 = if (curr.isCurve) curr.getHandleIn() else p0 + (p3 - p0) * 2f / 3f

        val q0 = p0 + (p1 - p0) * t
        val q1 = p1 + (p2 - p1) * t
        val q2 = p2 + (p3 - p2) * t

        val r0 = q0 + (q1 - q0) * t
        val r1 = q1 + (q2 - q1) * t

        val s = r0 + (r1 - r0) * t

        if (shape.isPathClosed) {
            val newNodes = mutableListOf<BezierNode>()
            val sStart = BezierNode(
                anchorX = s.x,
                anchorY = s.y,
                isCurve = true,
                control1X = s.x,
                control1Y = s.y,
                control2X = r1.x,
                control2Y = r1.y,
                isMoveTo = true,
                nodeType = prev.nodeType
            )
            newNodes.add(sStart)
            
            val updatedCurr = curr.copy(
                isCurve = true,
                control1X = q2.x,
                control1Y = q2.y
            )
            newNodes.add(updatedCurr.copy(isMoveTo = false))
            
            var count = 1
            while (count < size) {
                val idx = (currIdx + count) % size
                if (idx != prevIdx) {
                    newNodes.add(nodes[idx].copy(isMoveTo = false))
                }
                count++
            }
            
            val updatedPrev = prev.copy(
                isCurve = true,
                control2X = q0.x,
                control2Y = q0.y
            )
            if (prevIdx != currIdx) {
                newNodes.add(updatedPrev.copy(isMoveTo = false))
            }
            
            val sEnd = BezierNode(
                anchorX = s.x,
                anchorY = s.y,
                isCurve = true,
                control1X = r0.x,
                control1Y = r0.y,
                control2X = s.x,
                control2Y = s.y,
                isMoveTo = false,
                nodeType = prev.nodeType
            )
            newNodes.add(sEnd)
            
            shapes = shapes.map {
                if (it.id == shapeId) {
                    it.copy(bezierNodes = newNodes, isPathClosed = false)
                } else {
                    it
                }
            }
        } else {
            val shape1Nodes = mutableListOf<BezierNode>()
            for (i in 0 until segmentIndex) {
                if (i == prevIdx) {
                    shape1Nodes.add(prev.copy(
                        isCurve = true,
                        control2X = q0.x,
                        control2Y = q0.y
                    ))
                } else {
                    shape1Nodes.add(nodes[i])
                }
            }
            shape1Nodes.add(BezierNode(
                anchorX = s.x,
                anchorY = s.y,
                isCurve = true,
                control1X = r0.x,
                control1Y = r0.y,
                control2X = s.x,
                control2Y = s.y,
                isMoveTo = false,
                nodeType = prev.nodeType
            ))
            
            val shape2Nodes = mutableListOf<BezierNode>()
            shape2Nodes.add(BezierNode(
                anchorX = s.x,
                anchorY = s.y,
                isCurve = true,
                control1X = s.x,
                control1Y = s.y,
                control2X = r1.x,
                control2Y = r1.y,
                isMoveTo = true,
                nodeType = prev.nodeType
            ))
            for (i in segmentIndex until size) {
                if (i == currIdx) {
                    shape2Nodes.add(curr.copy(
                        isCurve = true,
                        control1X = q2.x,
                        control1Y = q2.y,
                        isMoveTo = false
                    ))
                } else {
                    shape2Nodes.add(nodes[i].copy(isMoveTo = false))
                }
            }
            
            val shape1 = shape.copy(id = java.util.UUID.randomUUID().toString(), bezierNodes = shape1Nodes)
            val shape2 = shape.copy(id = java.util.UUID.randomUUID().toString(), bezierNodes = shape2Nodes)
            
            val mutableShapes = shapes.toMutableList()
            val originalIndex = mutableShapes.indexOfFirst { it.id == shapeId }
            if (originalIndex != -1) {
                mutableShapes.removeAt(originalIndex)
                mutableShapes.add(originalIndex, shape1)
                mutableShapes.add(originalIndex + 1, shape2)
            }
            shapes = mutableShapes
            selectedShapeId = shape1.id
        }
    }

    fun splitNodeAt(shapeId: String, nodeIndex: Int) {
        pushToUndoStack()
        val shape = shapes.find { it.id == shapeId } ?: return
        if (shape.type != ShapeType.BEZIER_PATH) return
        val nodes = shape.bezierNodes
        val size = nodes.size
        if (size < 2) return
        if (nodeIndex !in nodes.indices) return

        if (shape.isPathClosed) {
            val newNodes = mutableListOf<BezierNode>()
            val startNode = nodes[nodeIndex].copy(isMoveTo = true)
            newNodes.add(startNode)
            
            for (count in 1 until size) {
                val idx = (nodeIndex + count) % size
                newNodes.add(nodes[idx].copy(isMoveTo = false))
            }
            val endNode = nodes[nodeIndex].copy(isMoveTo = false)
            newNodes.add(endNode)
            
            shapes = shapes.map {
                if (it.id == shapeId) {
                    it.copy(bezierNodes = newNodes, isPathClosed = false)
                } else {
                    it
                }
            }
        } else {
            if (nodeIndex == 0 || nodeIndex == size - 1) {
                return 
            }
            
            val shape1Nodes = nodes.subList(0, nodeIndex + 1).map { it.copy() }
            val shape2Nodes = mutableListOf<BezierNode>()
            shape2Nodes.add(nodes[nodeIndex].copy(isMoveTo = true))
            for (i in (nodeIndex + 1) until size) {
                shape2Nodes.add(nodes[i].copy(isMoveTo = false))
            }
            
            val shape1 = shape.copy(id = java.util.UUID.randomUUID().toString(), bezierNodes = shape1Nodes)
            val shape2 = shape.copy(id = java.util.UUID.randomUUID().toString(), bezierNodes = shape2Nodes)
            
            val mutableShapes = shapes.toMutableList()
            val originalIndex = mutableShapes.indexOfFirst { it.id == shapeId }
            if (originalIndex != -1) {
                mutableShapes.removeAt(originalIndex)
                mutableShapes.add(originalIndex, shape1)
                mutableShapes.add(originalIndex + 1, shape2)
            }
            shapes = mutableShapes
            selectedShapeId = shape1.id
        }
        selectedDirectSelectionNodes = emptySet()
    }

    fun closePath(shapeId: String) {
        pushToUndoStack()
        shapes = shapes.map {
            if (it.id == shapeId && it.type == ShapeType.BEZIER_PATH) {
                it.copy(isPathClosed = true)
            } else {
                it
            }
        }
    }

    fun setShapeNodeType(shapeId: String, nodeIndex: Int, typeStr: String) {
        pushToUndoStack()
        shapes = shapes.map { shape ->
            if (shape.id == shapeId && shape.type == ShapeType.BEZIER_PATH) {
                val nodes = shape.bezierNodes.toMutableList()
                if (nodeIndex in nodes.indices) {
                    val node = nodes[nodeIndex]
                    
                    val wasCurve = node.isCurve
                    val anchorX = node.anchorX
                    val anchorY = node.anchorY
                    
                    var c1X = node.control1X
                    var c1Y = node.control1Y
                    var c2X = node.control2X
                    var c2Y = node.control2Y
                    
                    val isSharpNow = !wasCurve || (c1X == anchorX && c1Y == anchorY && c2X == anchorX && c2Y == anchorY)
                    
                    if (isSharpNow && typeStr != "BEBAS") {
                        val prev = nodes[(nodeIndex - 1 + nodes.size) % nodes.size]
                        val next = nodes[(nodeIndex + 1) % nodes.size]
                        val dx = next.anchorX - prev.anchorX
                        val dy = next.anchorY - prev.anchorY
                        val len = hypot(dx, dy)
                        if (len > 0.1f) {
                            val handleLen = len * 0.25f
                            val ux = dx / len
                            val uy = dy / len
                            c1X = anchorX - ux * handleLen
                            c1Y = anchorY - uy * handleLen
                            c2X = anchorX + ux * handleLen
                            c2Y = anchorY + uy * handleLen
                        } else {
                            c1X = anchorX - 30f
                            c1Y = anchorY
                            c2X = anchorX + 30f
                            c2Y = anchorY
                        }
                    }
                    
                    var finalNode = node.copy(
                        isCurve = if (typeStr == "BEBAS" && isSharpNow) false else true,
                        nodeType = typeStr,
                        control1X = c1X,
                        control1Y = c1Y,
                        control2X = c2X,
                        control2Y = c2Y
                    )
                    
                    val finalAnchorX = finalNode.anchorX
                    val finalAnchorY = finalNode.anchorY
                    
                    if (typeStr == "ASIMETRIS" || typeStr == "HALUS") {
                        val dx1 = c1X - finalAnchorX
                        val dy1 = c1Y - finalAnchorY
                        val d1 = hypot(dx1, dy1)
                        val dx2 = c2X - finalAnchorX
                        val dy2 = c2Y - finalAnchorY
                        val d2 = hypot(dx2, dy2)
                        
                        if (d1 < 0.1f && d2 < 0.1f) {
                            finalNode = finalNode.copy(
                                control1X = finalAnchorX - 30f,
                                control1Y = finalAnchorY,
                                control2X = finalAnchorX + 30f,
                                control2Y = finalAnchorY
                            )
                        } else {
                            val refDx = if (d1 >= 0.1f) dx1 else -dx2
                            val refDy = if (d1 >= 0.1f) dy1 else -dy2
                            val refD = hypot(refDx, refDy)
                            val ux = refDx / refD
                            val uy = refDy / refD
                            
                            val len1 = if (d1 >= 0.1f) d1 else 30f
                            val len2 = if (d2 >= 0.1f) d2 else 30f
                            
                            finalNode = finalNode.copy(
                                control1X = finalAnchorX + ux * len1,
                                control1Y = finalAnchorY + uy * len1,
                                control2X = finalAnchorX - ux * len2,
                                control2Y = finalAnchorY - uy * len2
                            )
                        }
                    } else if (typeStr == "SIMETRIS") {
                        val dx1 = c1X - finalAnchorX
                        val dy1 = c1Y - finalAnchorY
                        val d1 = hypot(dx1, dy1)
                        val dx2 = c2X - finalAnchorX
                        val dy2 = c2Y - finalAnchorY
                        val d2 = hypot(dx2, dy2)
                        
                        val refDx = if (d1 >= 0.1f) dx1 else -dx2
                        val refDy = if (d1 >= 0.1f) dy1 else -dy2
                        val refD = hypot(refDx, refDy)
                        
                        val finalLen = maxOf(d1, d2, 30f)
                        
                        if (refD < 0.1f) {
                            finalNode = finalNode.copy(
                                control1X = finalAnchorX - finalLen,
                                control1Y = finalAnchorY,
                                control2X = finalAnchorX + finalLen,
                                control2Y = finalAnchorY
                            )
                        } else {
                            val ux = refDx / refD
                            val uy = refDy / refD
                            finalNode = finalNode.copy(
                                control1X = finalAnchorX + ux * finalLen,
                                control1Y = finalAnchorY + uy * finalLen,
                                control2X = finalAnchorX - ux * finalLen,
                                control2Y = finalAnchorY - uy * finalLen
                            )
                        }
                    }
                    
                    nodes[nodeIndex] = finalNode
                }
                shape.copy(bezierNodes = nodes)
            } else {
                shape
            }
        }
    }

    fun toggleShapeNodeCurve(shapeId: String, nodeIndex: Int) {
        pushToUndoStack()
        shapes = shapes.map { shape ->
            if (shape.id == shapeId && shape.type == ShapeType.BEZIER_PATH) {
                val nodes = shape.bezierNodes.toMutableList()
                if (nodeIndex in nodes.indices) {
                    val node = nodes[nodeIndex]
                    val isNowCurve = !node.isCurve
                    if (isNowCurve) {
                        val prev = nodes[(nodeIndex - 1 + nodes.size) % nodes.size]
                        val next = nodes[(nodeIndex + 1) % nodes.size]
                        val dx = next.anchorX - prev.anchorX
                        val dy = next.anchorY - prev.anchorY
                        val len = hypot(dx, dy)
                        if (len > 0.1f) {
                            val smoothing = 0.3f
                            val handleLen = len * smoothing
                            val ux = dx / len
                            val uy = dy / len
                            nodes[nodeIndex] = node.copy(
                                isCurve = true,
                                control1X = node.anchorX - ux * handleLen,
                                control1Y = node.anchorY - uy * handleLen,
                                control2X = node.anchorX + ux * handleLen,
                                control2Y = node.anchorY + uy * handleLen
                            )
                        } else {
                            nodes[nodeIndex] = node.copy(
                                isCurve = true,
                                control1X = node.anchorX - 30f,
                                control1Y = node.anchorY,
                                control2X = node.anchorX + 30f,
                                control2Y = node.anchorY
                            )
                        }
                    } else {
                        nodes[nodeIndex] = node.copy(
                            isCurve = false,
                            control1X = node.anchorX,
                            control1Y = node.anchorY,
                            control2X = node.anchorX,
                            control2Y = node.anchorY
                        )
                    }
                }
                shape.copy(bezierNodes = nodes)
            } else {
                shape
            }
        }
    }

    // Grid details
    var isGridEnabled by mutableStateOf(false)
    var gridSize by mutableStateOf(40f)
    var isSnapToGrid by mutableStateOf(false)
    var gridColorHex by mutableStateOf("#CCCCCC")

    // Camera panning/zooming variables
    var zoomScale by mutableStateOf(1f)
    var panOffset by mutableStateOf(Offset.Zero)

    // Undo / Redo history stacks
    private val undoStack = androidx.compose.runtime.mutableStateListOf<List<VectorShape>>()
    private val redoStack = androidx.compose.runtime.mutableStateListOf<List<VectorShape>>()

    // Document / Canvas size
    var canvasWidth by mutableStateOf(2000f)
    var canvasHeight by mutableStateOf(2000f)

    // Sample tracer reference image toggle
    var activeTracerImageIndex by mutableStateOf<Int?>(null)

    // Advanced Snapping & Smart Guide properties
    var isSmartGuideEnabled by mutableStateOf(false)
    var isSnapToObjectEnabled by mutableStateOf(false)
    var isSnapToPointEnabled by mutableStateOf(false)
    var activeSmartGuideHorizontal by mutableStateOf<Float?>(null)
    var activeSmartGuideVertical by mutableStateOf<Float?>(null)
    var activeSmartGuidesList by mutableStateOf<List<SmartGuideInfo>>(emptyList())

    // Onboarding config & Canvas custom background state
    var isSetupCompleted by mutableStateOf(false)
    var artboardColorHex by mutableStateOf("#FFFFFF")
    var artboardAlpha by mutableStateOf(1f)

    // Font Importing
    var importedTypeface by mutableStateOf<android.graphics.Typeface?>(null)
    var importedFontFileName by mutableStateOf<String?>(null)

    // Alignment target criteria
    var alignBasisIsCanvas by mutableStateOf(true)

    // Image/Bitmap caches to avoid base64 decoding on every render
    private val imageBitmapCache = mutableMapOf<String, androidx.compose.ui.graphics.ImageBitmap>()
    private val androidBitmapCache = mutableMapOf<String, android.graphics.Bitmap>()

    fun getCachedImageBitmap(shapeId: String, base64Str: String, lowRes: Boolean = false): androidx.compose.ui.graphics.ImageBitmap? {
        val cacheKey = if (lowRes) "${shapeId}_low" else shapeId
        val cached = imageBitmapCache[cacheKey]
        if (cached != null) return cached
        return try {
            val decodedBytes = android.util.Base64.decode(base64Str, android.util.Base64.DEFAULT)
            var bitmap = android.graphics.BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
            if (bitmap != null) {
                if (lowRes) {
                    val maxLowRes = 512
                    if (maxOf(bitmap.width, bitmap.height) > maxLowRes) {
                        val factor = maxLowRes.toFloat() / maxOf(bitmap.width, bitmap.height).toFloat()
                        val w = (bitmap.width * factor).toInt().coerceAtLeast(1)
                        val h = (bitmap.height * factor).toInt().coerceAtLeast(1)
                        val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, w, h, true)
                        if (scaled != bitmap) {
                            try {
                                bitmap.recycle()
                            } catch (re: Exception) {}
                            bitmap = scaled
                        }
                    }
                }
                val imageBitmap = bitmap.asImageBitmap()
                imageBitmapCache[cacheKey] = imageBitmap
                imageBitmap
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun getCachedAndroidBitmap(shapeId: String, base64Str: String): android.graphics.Bitmap? {
        val cached = androidBitmapCache[shapeId]
        if (cached != null) return cached
        return try {
            val decodedBytes = android.util.Base64.decode(base64Str, android.util.Base64.DEFAULT)
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
            if (bitmap != null) {
                androidBitmapCache[shapeId] = bitmap
                bitmap
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun importFontFromFile(context: Context, fileUri: android.net.Uri) {
        try {
            val inputStream = context.contentResolver.openInputStream(fileUri)
            if (inputStream != null) {
                val tempFile = File.createTempFile("imported_font", ".ttf", context.cacheDir)
                tempFile.outputStream().use { output ->
                    inputStream.copyTo(output)
                }
                val loadedTypeface = android.graphics.Typeface.createFromFile(tempFile)
                if (loadedTypeface != null) {
                    importedTypeface = loadedTypeface
                    importedFontFileName = "Custom Font (${tempFile.name})"
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun snapSelectionComprehensive(
        originalCombinedBounds: Rect,
        totalDX: Float,
        totalDY: Float,
        ignoredIds: Set<String>
    ): Offset {
        var finalDX = totalDX
        var finalDY = totalDY

        activeSmartGuidesList = emptyList()
        activeSmartGuideHorizontal = null
        activeSmartGuideVertical = null

        val tolerance = 15f // Snapping distance threshold in pixels
        val canvasTolerance = tolerance / zoomScale

        if (!isSnapToObjectEnabled && !isSmartGuideEnabled) {
            return Offset(totalDX, totalDY)
        }

        val left1 = originalCombinedBounds.left + totalDX
        val right1 = originalCombinedBounds.right + totalDX
        val top1 = originalCombinedBounds.top + totalDY
        val bottom1 = originalCombinedBounds.bottom + totalDY
        val cx1 = (left1 + right1) / 2f
        val cy1 = (top1 + bottom1) / 2f

        val otherShapes = shapes.filter { !ignoredIds.contains(it.id) && it.isVisible }

        var bestDiffX = Float.MAX_VALUE
        var bestTargetX: Float? = null
        var matchedSourceX: Float? = null // left1, cx1, or right1
        var refShapeX: VectorShape? = null

        // Canvas X candidates: Left, Center, Right
        val canvasXCandidates = listOf(
            0f to "CANVAS_LEFT",
            canvasWidth / 2f to "CANVAS_CENTER",
            canvasWidth to "CANVAS_RIGHT"
        )
        for (cand in canvasXCandidates) {
            val cxVal = cand.first
            val diffL = kotlin.math.abs(left1 - cxVal)
            if (diffL < bestDiffX && diffL < canvasTolerance) {
                bestDiffX = diffL
                bestTargetX = cxVal
                matchedSourceX = left1
                refShapeX = null
            }
            val diffC = kotlin.math.abs(cx1 - cxVal)
            if (diffC < bestDiffX && diffC < canvasTolerance) {
                bestDiffX = diffC
                bestTargetX = cxVal
                matchedSourceX = cx1
                refShapeX = null
            }
            val diffR = kotlin.math.abs(right1 - cxVal)
            if (diffR < bestDiffX && diffR < canvasTolerance) {
                bestDiffX = diffR
                bestTargetX = cxVal
                matchedSourceX = right1
                refShapeX = null
            }
        }

        // Other shapes X candidates
        for (shape in otherShapes) {
            val b = shape.getBoundingBox()
            val shapeXCandidates = listOf(b.left, (b.left + b.right) / 2f, b.right)
            for (cxVal in shapeXCandidates) {
                val diffL = kotlin.math.abs(left1 - cxVal)
                if (diffL < bestDiffX && diffL < canvasTolerance) {
                    bestDiffX = diffL
                    bestTargetX = cxVal
                    matchedSourceX = left1
                    refShapeX = shape
                }
                val diffC = kotlin.math.abs(cx1 - cxVal)
                if (diffC < bestDiffX && diffC < canvasTolerance) {
                    bestDiffX = diffC
                    bestTargetX = cxVal
                    matchedSourceX = cx1
                    refShapeX = shape
                }
                val diffR = kotlin.math.abs(right1 - cxVal)
                if (diffR < bestDiffX && diffR < canvasTolerance) {
                    bestDiffX = diffR
                    bestTargetX = cxVal
                    matchedSourceX = right1
                    refShapeX = shape
                }
            }
        }

        if (bestTargetX != null && matchedSourceX != null) {
            val shiftX = bestTargetX - matchedSourceX
            finalDX += shiftX
            activeSmartGuideVertical = bestTargetX
        }

        var bestDiffY = Float.MAX_VALUE
        var bestTargetY: Float? = null
        var matchedSourceY: Float? = null // top1, cy1, or bottom1
        var refShapeY: VectorShape? = null

        // Canvas Y candidates: Top, Center, Bottom
        val canvasYCandidates = listOf(
            0f to "CANVAS_TOP",
            canvasHeight / 2f to "CANVAS_CENTER",
            canvasHeight to "CANVAS_BOTTOM"
        )
        for (cand in canvasYCandidates) {
            val cyVal = cand.first
            val diffT = kotlin.math.abs(top1 - cyVal)
            if (diffT < bestDiffY && diffT < canvasTolerance) {
                bestDiffY = diffT
                bestTargetY = cyVal
                matchedSourceY = top1
                refShapeY = null
            }
            val diffC = kotlin.math.abs(cy1 - cyVal)
            if (diffC < bestDiffY && diffC < canvasTolerance) {
                bestDiffY = diffC
                bestTargetY = cyVal
                matchedSourceY = cy1
                refShapeY = null
            }
            val diffB = kotlin.math.abs(bottom1 - cyVal)
            if (diffB < bestDiffY && diffB < canvasTolerance) {
                bestDiffY = diffB
                bestTargetY = cyVal
                matchedSourceY = bottom1
                refShapeY = null
            }
        }

        // Other shapes Y candidates
        for (shape in otherShapes) {
            val b = shape.getBoundingBox()
            val shapeYCandidates = listOf(b.top, (b.top + b.bottom) / 2f, b.bottom)
            for (cyVal in shapeYCandidates) {
                val diffT = kotlin.math.abs(top1 - cyVal)
                if (diffT < bestDiffY && diffT < canvasTolerance) {
                    bestDiffY = diffT
                    bestTargetY = cyVal
                    matchedSourceY = top1
                    refShapeY = shape
                }
                val diffC = kotlin.math.abs(cy1 - cyVal)
                if (diffC < bestDiffY && diffC < canvasTolerance) {
                    bestDiffY = diffC
                    bestTargetY = cyVal
                    matchedSourceY = cy1
                    refShapeY = shape
                }
                val diffB = kotlin.math.abs(bottom1 - cyVal)
                if (diffB < bestDiffY && diffB < canvasTolerance) {
                    bestDiffY = diffB
                    bestTargetY = cyVal
                    matchedSourceY = bottom1
                    refShapeY = shape
                }
            }
        }

        if (bestTargetY != null && matchedSourceY != null) {
            val shiftY = bestTargetY - matchedSourceY
            finalDY += shiftY
            activeSmartGuideHorizontal = bestTargetY
        }

        val guides = mutableListOf<SmartGuideInfo>()

        if (isSmartGuideEnabled) {
            if (bestTargetY != null) {
                val yVal = bestTargetY
                var minXVal = 0f
                var maxXVal = canvasWidth
                if (refShapeY != null) {
                    val rb = refShapeY.getBoundingBox()
                    minXVal = minOf(rb.left, originalCombinedBounds.left + finalDX)
                    maxXVal = maxOf(rb.right, originalCombinedBounds.right + finalDX)
                }
                guides.add(SmartGuideInfo(minXVal, yVal, maxXVal, yVal, "ALIGN_LINE"))
            }

            if (bestTargetX != null) {
                val xVal = bestTargetX
                var minYVal = 0f
                var maxYVal = canvasHeight
                if (refShapeX != null) {
                    val rb = refShapeX.getBoundingBox()
                    minYVal = minOf(rb.top, originalCombinedBounds.top + finalDY)
                    maxYVal = maxOf(rb.bottom, originalCombinedBounds.bottom + finalDY)
                }
                guides.add(SmartGuideInfo(xVal, minYVal, xVal, maxYVal, "ALIGN_LINE"))
            }

            // Spacing guides
            if (otherShapes.size >= 2) {
                val currentLeft = originalCombinedBounds.left + finalDX
                val currentRight = originalCombinedBounds.right + finalDX
                val currentTop = originalCombinedBounds.top + finalDY
                val currentBottom = originalCombinedBounds.bottom + finalDY
                val currentWidth = originalCombinedBounds.width
                val currentHeight = originalCombinedBounds.height

                val sortedH = otherShapes.map { it.getBoundingBox() }.sortedBy { it.left }
                for (i in 0 until sortedH.size - 1) {
                    val s1 = sortedH[i]
                    val s2 = sortedH[i + 1]
                    val gap = s2.left - s1.right
                    if (gap > 4f) {
                        val targetLeft = s2.right + gap
                        if (kotlin.math.abs(currentLeft - targetLeft) < canvasTolerance) {
                            val snapShiftX = targetLeft - currentLeft
                            finalDX += snapShiftX
                            guides.add(SmartGuideInfo(s1.right, (s1.top + s1.bottom) / 2f, s2.left, (s2.top + s2.bottom) / 2f, "SPACING", "${gap.toInt()} px"))
                            guides.add(SmartGuideInfo(s2.right, (s2.top + s2.bottom) / 2f, targetLeft, (originalCombinedBounds.top + originalCombinedBounds.bottom) / 2f + finalDY, "SPACING", "${gap.toInt()} px"))
                            break
                        }
                        val targetLeft2 = s1.left - gap - currentWidth
                        if (kotlin.math.abs(currentLeft - targetLeft2) < canvasTolerance) {
                            val snapShiftX = targetLeft2 - currentLeft
                            finalDX += snapShiftX
                            guides.add(SmartGuideInfo(s1.right, (s1.top + s1.bottom) / 2f, s2.left, (s2.top + s2.bottom) / 2f, "SPACING", "${gap.toInt()} px"))
                            guides.add(SmartGuideInfo(targetLeft2 + currentWidth, (originalCombinedBounds.top + originalCombinedBounds.bottom) / 2f + finalDY, s1.left, (s1.top + s1.bottom) / 2f, "SPACING", "${gap.toInt()} px"))
                            break
                        }
                    }
                }

                val sortedV = otherShapes.map { it.getBoundingBox() }.sortedBy { it.top }
                for (i in 0 until sortedV.size - 1) {
                    val s1 = sortedV[i]
                    val s2 = sortedV[i + 1]
                    val gap = s2.top - s1.bottom
                    if (gap > 4f) {
                        val targetTop = s2.bottom + gap
                        if (kotlin.math.abs(currentTop - targetTop) < canvasTolerance) {
                            val snapShiftY = targetTop - currentTop
                            finalDY += snapShiftY
                            guides.add(SmartGuideInfo((s1.left + s1.right) / 2f, s1.bottom, (s2.left + s2.right) / 2f, s2.top, "SPACING", "${gap.toInt()} px"))
                            guides.add(SmartGuideInfo((s2.left + s2.right) / 2f, s2.bottom, (originalCombinedBounds.left + originalCombinedBounds.right) / 2f + finalDX, targetTop, "SPACING", "${gap.toInt()} px"))
                            break
                        }
                        val targetTop2 = s1.top - gap - currentHeight
                        if (kotlin.math.abs(currentTop - targetTop2) < canvasTolerance) {
                            val snapShiftY = targetTop2 - currentTop
                            finalDY += snapShiftY
                            guides.add(SmartGuideInfo((s1.left + s1.right) / 2f, s1.bottom, (s2.left + s2.right) / 2f, s2.top, "SPACING", "${gap.toInt()} px"))
                            guides.add(SmartGuideInfo((originalCombinedBounds.left + originalCombinedBounds.right) / 2f + finalDX, targetTop2 + currentHeight, (s1.left + s1.right) / 2f, s1.top, "SPACING", "${gap.toInt()} px"))
                            break
                        }
                    }
                }
            }
        }

        activeSmartGuidesList = guides
        return Offset(finalDX, finalDY)
    }

    fun snapOffsetComprehensive(pos: Offset, ignoreId: String? = null): Offset {
        var snappedX = pos.x
        var snappedY = pos.y
        activeSmartGuideHorizontal = null
        activeSmartGuideVertical = null

        val tolerance = 15f // Snapping distance threshold in pixels

        // 1. Snap to Point (nodes of other shapes)
        if (isSnapToPointEnabled) {
            var closestPoint: Offset? = null
            var minDistance = tolerance

            for (shape in shapes) {
                if (shape.id == ignoreId || !shape.isVisible) continue
                val pointsToTry = mutableListOf<Offset>()
                val bounds = shape.getBoundingBox()
                pointsToTry.add(Offset(bounds.left, bounds.top))
                pointsToTry.add(Offset(bounds.right, bounds.top))
                pointsToTry.add(Offset(bounds.left, bounds.bottom))
                pointsToTry.add(Offset(bounds.right, bounds.bottom))
                pointsToTry.add(Offset(bounds.left + bounds.width / 2f, bounds.top + bounds.height / 2f))

                if (shape.type == ShapeType.LINE) {
                    pointsToTry.add(Offset(shape.startX, shape.startY))
                    pointsToTry.add(Offset(shape.endX, shape.endY))
                }
                shape.bezierNodes.forEach {
                    pointsToTry.add(Offset(it.anchorX, it.anchorY))
                }

                for (p in pointsToTry) {
                    val dist = hypot(pos.x - p.x, pos.y - p.y)
                    if (dist < minDistance) {
                        minDistance = dist
                        closestPoint = p
                    }
                }
            }

            if (closestPoint != null) {
                snappedX = closestPoint.x
                snappedY = closestPoint.y
                if (isSmartGuideEnabled) {
                    activeSmartGuideHorizontal = snappedY
                    activeSmartGuideVertical = snappedX
                }
                return Offset(snappedX, snappedY)
            }
        }

        // 2. Snap to Object (boundaries)
        if (isSnapToObjectEnabled) {
            var snappedSomeX = false
            var snappedSomeY = false

            for (shape in shapes) {
                if (shape.id == ignoreId || !shape.isVisible) continue
                val bounds = shape.getBoundingBox()
                val xCandidates = listOf(bounds.left, bounds.left + bounds.width / 2f, bounds.right)
                for (cx in xCandidates) {
                    if (kotlin.math.abs(pos.x - cx) < tolerance) {
                        snappedX = cx
                        snappedSomeX = true
                        if (isSmartGuideEnabled) {
                            activeSmartGuideVertical = cx
                        }
                        break
                    }
                }
                val yCandidates = listOf(bounds.top, bounds.top + bounds.height / 2f, bounds.bottom)
                for (cy in yCandidates) {
                    if (kotlin.math.abs(pos.y - cy) < tolerance) {
                        snappedY = cy
                        snappedSomeY = true
                        if (isSmartGuideEnabled) {
                            activeSmartGuideHorizontal = cy
                        }
                        break
                    }
                }
            }
            if (snappedSomeX || snappedSomeY) {
                return Offset(snappedX, snappedY)
            }
        }

        // 3. Snap to Grid
        if (isSnapToGrid) {
            val gridX = kotlin.math.round(pos.x / gridSize) * gridSize
            val gridY = kotlin.math.round(pos.y / gridSize) * gridSize
            if (isSmartGuideEnabled) {
                activeSmartGuideVertical = gridX
                activeSmartGuideHorizontal = gridY
            }
            return Offset(gridX, gridY)
        }

        return pos
    }

    fun snapOffset(original: Offset): Offset {
        return snapOffsetComprehensive(original)
    }

    fun snapNodeComprehensive(pos: Offset, shapeId: String?, nodeIndex: Int?): Offset {
        activeSmartGuideHorizontal = null
        activeSmartGuideVertical = null
        activeSmartGuidesList = emptyList()

        val refPoints = mutableListOf<Offset>()
        for (s in shapes) {
            if (!s.isVisible) continue
            val bounds = s.getBoundingBox()
            refPoints.add(Offset(bounds.left, bounds.top))
            refPoints.add(Offset(bounds.right, bounds.top))
            refPoints.add(Offset(bounds.left, bounds.bottom))
            refPoints.add(Offset(bounds.right, bounds.bottom))
            refPoints.add(Offset(bounds.left + bounds.width / 2f, bounds.top + bounds.height / 2f))

            if (s.type == ShapeType.LINE) {
                refPoints.add(Offset(s.startX, s.startY))
                refPoints.add(Offset(s.endX, s.endY))
            }
            s.bezierNodes.forEachIndexed { idx, node ->
                if (s.id == shapeId && idx == nodeIndex) {
                    // ignore
                } else {
                    refPoints.add(Offset(node.anchorX, node.anchorY))
                }
            }
        }

        activeBezierNodes.forEachIndexed { idx, node ->
            if (idx != nodeIndex) {
                refPoints.add(Offset(node.anchorX, node.anchorY))
            }
        }

        val tolerance = 15f
        var snappedX = pos.x
        var snappedY = pos.y
        var pointSnapped = false
        val guides = mutableListOf<SmartGuideInfo>()

        // 1. Snap to Point (exact matching / close distance)
        if (isSnapToPointEnabled) {
            var closestPoint: Offset? = null
            var minDistance = tolerance
            for (p in refPoints) {
                val dist = hypot(pos.x - p.x, pos.y - p.y)
                if (dist < minDistance) {
                    minDistance = dist
                    closestPoint = p
                }
            }
            if (closestPoint != null) {
                snappedX = closestPoint.x
                snappedY = closestPoint.y
                pointSnapped = true
                if (isSmartGuideEnabled) {
                    activeSmartGuideHorizontal = snappedY
                    activeSmartGuideVertical = snappedX
                    guides.add(SmartGuideInfo(snappedX, snappedY, closestPoint.x, closestPoint.y, "ALIGN_LINE"))
                }
            }
        }

        // 2. Alignment Snaps (Vertical, Horizontal, 45-degree angle)
        if (!pointSnapped) {
            var hasVertSnap = false
            var hasHorizSnap = false

            // Vertical Snap
            if (isSnapToObjectEnabled || isSnapToPointEnabled) {
                for (p in refPoints) {
                    if (kotlin.math.abs(pos.x - p.x) < tolerance) {
                        snappedX = p.x
                        hasVertSnap = true
                        if (isSmartGuideEnabled) {
                            activeSmartGuideVertical = p.x
                            guides.add(SmartGuideInfo(p.x, p.y, p.x, pos.y, "ALIGN_LINE"))
                        }
                        break
                    }
                }
            }

            // Horizontal Snap
            if (isSnapToObjectEnabled || isSnapToPointEnabled) {
                for (p in refPoints) {
                    if (kotlin.math.abs(pos.y - p.y) < tolerance) {
                        snappedY = p.y
                        hasHorizSnap = true
                        if (isSmartGuideEnabled) {
                            activeSmartGuideHorizontal = p.y
                            guides.add(SmartGuideInfo(p.x, p.y, pos.x, p.y, "ALIGN_LINE"))
                        }
                        break
                    }
                }
            }

            // 45-degree angle Snap
            if (!hasVertSnap && !hasHorizSnap && (isSnapToObjectEnabled || isSnapToPointEnabled)) {
                for (p in refPoints) {
                    val dx = pos.x - p.x
                    val dy = pos.y - p.y
                    if (kotlin.math.abs(kotlin.math.abs(dx) - kotlin.math.abs(dy)) < tolerance) {
                        val signX = if (dx >= 0f) 1f else -1f
                        val signY = if (dy >= 0f) 1f else -1f
                        val avgDist = (kotlin.math.abs(dx) + kotlin.math.abs(dy)) / 2f
                        snappedX = p.x + signX * avgDist
                        snappedY = p.y + signY * avgDist
                        if (isSmartGuideEnabled) {
                            guides.add(SmartGuideInfo(p.x, p.y, snappedX, snappedY, "ALIGN_LINE"))
                        }
                        break
                    }
                }
            }
        }

        // 3. Grid Snapping as fallback if enabled
        if (isSnapToGrid) {
            snappedX = kotlin.math.round(snappedX / gridSize) * gridSize
            snappedY = kotlin.math.round(snappedY / gridSize) * gridSize
            if (isSmartGuideEnabled) {
                activeSmartGuideVertical = snappedX
                activeSmartGuideHorizontal = snappedY
            }
        }

        activeSmartGuidesList = guides
        return Offset(snappedX, snappedY)
    }

    init {
        // Load default shapes on startup or present beautiful preset
        loadDefaultWorkspace()
    }

    fun convertShapeToBezierPath(shapeId: String) {
        shapes = shapes.map { shape ->
            if (shape.id == shapeId && shape.type != ShapeType.BEZIER_PATH) {
                if (shape.type == ShapeType.RECTANGLE || shape.type == ShapeType.POLYGON || shape.type == ShapeType.STAR) {
                    val bezierNodes = shape.convertCornerPointsToEightBezierNodes()
                    shape.copy(
                        type = ShapeType.BEZIER_PATH,
                        bezierNodes = bezierNodes,
                        customCornerRadii = emptyList(),
                        isPathClosed = true
                    )
                } else {
                    val corners = shape.getNodePoints()
                    if (corners.isNotEmpty()) {
                        val bezierNodes = corners.map { pt ->
                            com.example.model.BezierNode(
                                anchorX = pt.x,
                                anchorY = pt.y,
                                isCurve = false,
                                control1X = pt.x,
                                control1Y = pt.y,
                                control2X = pt.x,
                                control2Y = pt.y
                            )
                        }
                        shape.copy(
                            type = ShapeType.BEZIER_PATH,
                            bezierNodes = bezierNodes,
                            isPathClosed = (shape.type == ShapeType.RECTANGLE || shape.type == ShapeType.POLYGON || shape.type == ShapeType.STAR)
                        )
                    } else {
                        shape
                    }
                }
            } else {
                shape
            }
        }
    }

    fun pushToUndoStack() {
        val currentShapes = ArrayList(shapes)
        if (undoStack.isEmpty() || undoStack.last() != currentShapes) {
            undoStack.add(currentShapes)
            redoStack.clear()
        }
    }

    fun pushCustomListToUndoStack(customList: List<VectorShape>) {
        val listCopy = ArrayList(customList)
        if (undoStack.isEmpty() || undoStack.last() != listCopy) {
            undoStack.add(listCopy)
            redoStack.clear()
        }
    }

    fun undo() {
        if (undoStack.isNotEmpty()) {
            val prevState = undoStack.removeAt(undoStack.size - 1)
            val currentState = ArrayList(shapes)
            if (redoStack.isEmpty() || redoStack.last() != currentState) {
                redoStack.add(currentState)
            }
            shapes = prevState
            // Clear invalid select
            if (shapes.none { it.id == selectedShapeId }) {
                selectedShapeId = null
            }
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            val nextState = redoStack.removeAt(redoStack.size - 1)
            val currentState = ArrayList(shapes)
            if (undoStack.isEmpty() || undoStack.last() != currentState) {
                undoStack.add(currentState)
            }
            shapes = nextState
        }
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    // Shape selection logic
    fun selectShapeAt(pos: Offset, toggle: Boolean = false) {
        val lockedLayerIds = layers.filter { it.isLocked }.map { it.id }.toSet()
        val clickableShapes = shapes.filter { !it.isLocked && !lockedLayerIds.contains(it.layerId) }
        val clickedShape = clickableShapes.reversed().firstOrNull { it.isPointInside(pos.x, pos.y) }
        if (clickedShape != null) {
            val cid = clickedShape.id
            if (toggle) {
                val currentSelected = selectedShapeIds.toMutableSet()
                if (currentSelected.contains(cid)) {
                    currentSelected.remove(cid)
                    selectedShapeIds = currentSelected
                    selectedShapeId = currentSelected.firstOrNull()
                } else {
                    val group = clickedShape.groupId
                    if (group != null) {
                        val groupShapeIds = shapes.filter { it.groupId == group }.map { it.id }
                        currentSelected.addAll(groupShapeIds)
                    } else {
                        currentSelected.add(cid)
                    }
                    selectedShapeIds = currentSelected
                    selectedShapeId = cid
                }
            } else {
                val group = clickedShape.groupId
                if (group != null) {
                    val groupShapeIds = shapes.filter { it.groupId == group }.map { it.id }.toSet()
                    selectedShapeIds = groupShapeIds
                } else {
                    selectedShapeIds = setOf(cid)
                }
                selectedShapeId = cid
            }
            
            val active = shapes.find { it.id == selectedShapeId }
            if (active != null) {
                currentStrokeWidth = active.strokeWidth
                currentStrokeColorHex = active.strokeColorHex
                currentStrokeAlpha = active.strokeAlpha
                currentStrokeJoin = active.strokeJoin
                currentStrokeCap = active.strokeCap
                hasStrokeEnabled = active.hasStroke
                hasFillEnabled = active.hasFill
                currentFillColorHex = active.fillColorHex
                currentFillAlpha = active.fillAlpha
                currentLineStyle = active.lineStyle
            }
        } else {
            selectedShapeId = null
            selectedShapeIds = emptySet()
        }
    }

    // Interactive element updating
    fun updateSelectedShapeStyle() {
        val id = selectedShapeId ?: return
        val targetIds = if (selectedShapeIds.contains(id)) selectedShapeIds else setOf(id)
        pushToUndoStack()
        shapes = shapes.map { shape ->
            if (targetIds.contains(shape.id) || (shape.groupId != null && shapes.find { targetIds.contains(it.id) }?.groupId == shape.groupId)) {
                shape.copy(
                    strokeWidth = currentStrokeWidth,
                    strokeColorHex = currentStrokeColorHex,
                    strokeAlpha = currentStrokeAlpha,
                    strokeJoin = currentStrokeJoin,
                    strokeCap = currentStrokeCap,
                    hasStroke = hasStrokeEnabled,
                    hasFill = hasFillEnabled,
                    fillColorHex = currentFillColorHex,
                    fillAlpha = currentFillAlpha,
                    lineStyle = currentLineStyle
                )
            } else {
                shape
            }
        }
    }

    // Adding primitive shapes
    fun addPrimitiveShape(start: Offset, end: Offset) {
        pushToUndoStack()
        val id = UUID.randomUUID().toString()
        val snappedStart = snapOffset(start)
        val snappedEnd = snapOffset(end)
        
        val newShape = when (activePrimitiveType) {
            PrimitiveType.RECTANGLE -> {
                val rw = maxOf(4f, kotlin.math.abs(snappedEnd.x - snappedStart.x))
                val rh = maxOf(4f, kotlin.math.abs(snappedEnd.y - snappedStart.y))
                val w = if (isAspectLocked) maxOf(rw, rh) else rw
                val h = if (isAspectLocked) maxOf(rw, rh) else rh
                val left = if (snappedEnd.x >= snappedStart.x) snappedStart.x else snappedStart.x - w
                val top = if (snappedEnd.y >= snappedStart.y) snappedStart.y else snappedStart.y - h
                VectorShape(
                    id = id,
                    name = "Rectangle ${shapes.size + 1}",
                    type = ShapeType.RECTANGLE,
                    x = left,
                    y = top,
                    width = w,
                    height = h,
                    strokeColorHex = currentStrokeColorHex,
                    strokeWidth = currentStrokeWidth,
                    strokeAlpha = currentStrokeAlpha,
                    strokeJoin = currentStrokeJoin,
                    strokeCap = currentStrokeCap,
                    hasStroke = hasStrokeEnabled,
                    hasFill = hasFillEnabled,
                    fillColorHex = currentFillColorHex,
                    fillAlpha = currentFillAlpha,
                    lineStyle = currentLineStyle,
                    layerOrder = shapes.size
                )
            }
            PrimitiveType.ELLIPSE -> {
                val centerX = snappedStart.x
                val centerY = snappedStart.y
                val rx = maxOf(4f, kotlin.math.abs(snappedEnd.x - snappedStart.x))
                val ry = maxOf(4f, kotlin.math.abs(snappedEnd.y - snappedStart.y))
                val w = if (isAspectLocked) maxOf(rx, ry) else rx
                val h = if (isAspectLocked) maxOf(rx, ry) else ry
                VectorShape(
                    id = id,
                    name = "Ellipse ${shapes.size + 1}",
                    type = ShapeType.ELLIPSE,
                    x = centerX,
                    y = centerY,
                    width = w,
                    height = h,
                    strokeColorHex = currentStrokeColorHex,
                    strokeWidth = currentStrokeWidth,
                    strokeAlpha = currentStrokeAlpha,
                    strokeJoin = currentStrokeJoin,
                    strokeCap = currentStrokeCap,
                    hasStroke = hasStrokeEnabled,
                    hasFill = hasFillEnabled,
                    fillColorHex = currentFillColorHex,
                    fillAlpha = currentFillAlpha,
                    lineStyle = currentLineStyle,
                    layerOrder = shapes.size
                )
            }
            PrimitiveType.POLYGON -> {
                val centerX = snappedStart.x
                val centerY = snappedStart.y
                val rx = maxOf(4f, kotlin.math.abs(snappedEnd.x - snappedStart.x))
                val ry = maxOf(4f, kotlin.math.abs(snappedEnd.y - snappedStart.y))
                val w = if (isAspectLocked) maxOf(rx, ry) else rx
                val h = if (isAspectLocked) maxOf(rx, ry) else ry
                VectorShape(
                    id = id,
                    name = "Polygon (${currentPolygonSides}s) ${shapes.size + 1}",
                    type = ShapeType.POLYGON,
                    x = centerX,
                    y = centerY,
                    width = w,
                    height = h,
                    polygonSides = currentPolygonSides,
                    strokeColorHex = currentStrokeColorHex,
                    strokeWidth = currentStrokeWidth,
                    strokeAlpha = currentStrokeAlpha,
                    strokeJoin = currentStrokeJoin,
                    strokeCap = currentStrokeCap,
                    hasStroke = hasStrokeEnabled,
                    hasFill = hasFillEnabled,
                    fillColorHex = currentFillColorHex,
                    fillAlpha = currentFillAlpha,
                    lineStyle = currentLineStyle,
                    layerOrder = shapes.size
                )
            }
            PrimitiveType.STAR -> {
                val centerX = snappedStart.x
                val centerY = snappedStart.y
                val rx = maxOf(4f, kotlin.math.abs(snappedEnd.x - snappedStart.x))
                val ry = maxOf(4f, kotlin.math.abs(snappedEnd.y - snappedStart.y))
                val w = if (isAspectLocked) maxOf(rx, ry) else rx
                val h = if (isAspectLocked) maxOf(rx, ry) else ry
                VectorShape(
                    id = id,
                    name = "Star (${currentStarPoints}p) ${shapes.size + 1}",
                    type = ShapeType.STAR,
                    x = centerX,
                    y = centerY,
                    width = w,
                    height = h,
                    starPoints = currentStarPoints,
                    strokeColorHex = currentStrokeColorHex,
                    strokeWidth = currentStrokeWidth,
                    strokeAlpha = currentStrokeAlpha,
                    strokeJoin = currentStrokeJoin,
                    strokeCap = currentStrokeCap,
                    hasStroke = hasStrokeEnabled,
                    hasFill = hasFillEnabled,
                    fillColorHex = currentFillColorHex,
                    fillAlpha = currentFillAlpha,
                    lineStyle = currentLineStyle,
                    layerOrder = shapes.size
                )
            }
            PrimitiveType.TRIANGLE -> {
                val centerX = snappedStart.x
                val centerY = snappedStart.y
                val rx = maxOf(4f, kotlin.math.abs(snappedEnd.x - snappedStart.x))
                val ry = maxOf(4f, kotlin.math.abs(snappedEnd.y - snappedStart.y))
                val w = if (isAspectLocked) maxOf(rx, ry) else rx
                val h = if (isAspectLocked) maxOf(rx, ry) else ry
                VectorShape(
                    id = id,
                    name = "Triangle ${shapes.size + 1}",
                    type = ShapeType.POLYGON,
                    x = centerX,
                    y = centerY,
                    width = w,
                    height = h,
                    polygonSides = 3,
                    strokeColorHex = currentStrokeColorHex,
                    strokeWidth = currentStrokeWidth,
                    strokeAlpha = currentStrokeAlpha,
                    strokeJoin = currentStrokeJoin,
                    strokeCap = currentStrokeCap,
                    hasStroke = hasStrokeEnabled,
                    hasFill = hasFillEnabled,
                    fillColorHex = currentFillColorHex,
                    fillAlpha = currentFillAlpha,
                    lineStyle = currentLineStyle,
                    layerOrder = shapes.size
                )
            }
            PrimitiveType.LINE -> {
                VectorShape(
                    id = id,
                    name = "Line ${shapes.size + 1}",
                    type = ShapeType.LINE,
                    startX = snappedStart.x,
                    startY = snappedStart.y,
                    endX = snappedEnd.x,
                    endY = snappedEnd.y,
                    strokeColorHex = currentStrokeColorHex,
                    strokeWidth = currentStrokeWidth,
                    strokeAlpha = currentStrokeAlpha,
                    strokeJoin = currentStrokeJoin,
                    strokeCap = currentStrokeCap,
                    hasStroke = hasStrokeEnabled,
                    lineStyle = currentLineStyle,
                    hasFill = false,
                    layerOrder = shapes.size
                )
            }
        }
        val shapeWithLayer = newShape.copy(layerId = activeLayerId)
        shapes = shapes + shapeWithLayer
        selectedShapeId = id
        selectedShapeIds = setOf(id)
    }

    // Brush Tool: Freehand smooth drawings
    fun addFreehandPath(points: List<Offset>) {
        if (points.size < 2) return
        pushToUndoStack()
        val id = UUID.randomUUID().toString()
        val serialized = points.map { SerializedPoint(it.x, it.y) }
        val newShape = VectorShape(
            id = id,
            name = "Brush ${shapes.size + 1}",
            type = ShapeType.FREEHAND,
            freehandPoints = serialized,
            strokeColorHex = currentStrokeColorHex,
            strokeWidth = currentStrokeWidth,
            strokeAlpha = currentStrokeAlpha,
            strokeJoin = currentStrokeJoin,
            strokeCap = currentStrokeCap,
            hasStroke = hasStrokeEnabled,
            hasFill = false,
            layerOrder = shapes.size
        )
        val shapeWithLayer = newShape.copy(layerId = activeLayerId)
        shapes = shapes + shapeWithLayer
        selectedShapeId = id
    }

    // Pen Tool: Adding explicit Bezier curve joints
    fun addBezierPenPoint(anchor: Offset) {
        val snap = snapOffset(anchor)
        val newNode = BezierNode(
            anchorX = snap.x,
            anchorY = snap.y,
            isCurve = false,
            control1X = snap.x,
            control1Y = snap.y,
            control2X = snap.x,
            control2Y = snap.y
        )
        // If they click on the very first point and there are elements, close the path!
        if (false && activeBezierNodes.isNotEmpty() &&
            hypot(snap.x - activeBezierNodes.first().anchorX, snap.y - activeBezierNodes.first().anchorY) < 16f
        ) {
            finalizeBezierPath(isClosed = true)
            return
        }
        activeEditNodeIndex = activeBezierNodes.size
        activeBezierNodes = activeBezierNodes + newNode
    }

    // Pen Tool: Adding explicit Bezier curve joints immediately on touch down
    fun removeLastBezierNode() {
        if (activeBezierNodes.isNotEmpty()) {
            activeBezierNodes = activeBezierNodes.dropLast(1)
            activeEditNodeIndex = if (activeBezierNodes.isNotEmpty()) activeBezierNodes.size - 1 else null
        }
    }

    fun addBezierPenPointImmediately(anchor: Offset) {
        val snap = snapOffset(anchor)
        val newNode = BezierNode(
            anchorX = snap.x,
            anchorY = snap.y,
            isCurve = false,
            control1X = snap.x,
            control1Y = snap.y,
            control2X = snap.x,
            control2Y = snap.y
        )
        activeEditNodeIndex = activeBezierNodes.size
        activeBezierNodes = activeBezierNodes + newNode
    }

    // Move anchor and controls by the same offset
    fun updateActiveBezierNode(index: Int, newPos: Offset) {
        if (index in activeBezierNodes.indices) {
            val oldNode = activeBezierNodes[index]
            val dx = newPos.x - oldNode.anchorX
            val dy = newPos.y - oldNode.anchorY
            val updated = oldNode.copy(
                anchorX = newPos.x,
                anchorY = newPos.y,
                control1X = oldNode.control1X + dx,
                control1Y = oldNode.control1Y + dy,
                control2X = oldNode.control2X + dx,
                control2Y = oldNode.control2Y + dy
            )
            activeBezierNodes = activeBezierNodes.toMutableList().apply {
                this[index] = updated
            }
        }
    }

    // Pen Tool: Update handle coordinate dynamically during live creation dragging
    fun updateActiveBezierNodeHandlesOnDrag(index: Int, dragPos: Offset) {
        if (index in activeBezierNodes.indices) {
            val node = activeBezierNodes[index]
            val anchorX = node.anchorX
            val anchorY = node.anchorY
            val dx = dragPos.x - anchorX
            val dy = dragPos.y - anchorY
            
            // Outgoing control handle (control2) follows the dragging finger directly,
            // while the incoming control handle (control1) is mirrored symmetrically
            val updated = node.copy(
                isCurve = true,
                nodeType = "SIMETRIS",
                control2X = dragPos.x,
                control2Y = dragPos.y,
                control1X = anchorX - dx,
                control1Y = anchorY - dy
            )
            activeBezierNodes = activeBezierNodes.toMutableList().apply {
                this[index] = updated
            }
        }
    }

    // Update single Bezier handle coordinate directly
    fun updateActiveBezierHandle(index: Int, handleType: String, newPos: Offset) {
        if (index in activeBezierNodes.indices) {
            val oldNode = activeBezierNodes[index]
            val anchorX = oldNode.anchorX
            val anchorY = oldNode.anchorY
            val type = oldNode.nodeType
            
            val updated = if (handleType == "in" || handleType == "control1" || handleType == "control1") {
                val dx1 = newPos.x - anchorX
                val dy1 = newPos.y - anchorY
                val d1 = hypot(dx1, dy1)
                
                when (type) {
                    "ASIMETRIS", "HALUS" -> {
                        if (d1 > 0.1f) {
                            val ux = dx1 / d1
                            val uy = dy1 / d1
                            val dx2 = oldNode.control2X - anchorX
                            val dy2 = oldNode.control2Y - anchorY
                            val d2 = hypot(dx2, dy2)
                            oldNode.copy(
                                isCurve = true,
                                control1X = newPos.x,
                                control1Y = newPos.y,
                                control2X = anchorX - ux * d2,
                                control2Y = anchorY - uy * d2
                            )
                        } else {
                            oldNode.copy(isCurve = true, control1X = newPos.x, control1Y = newPos.y)
                        }
                    }
                    "SIMETRIS" -> {
                        if (d1 > 0.1f) {
                            val ux = dx1 / d1
                            val uy = dy1 / d1
                            oldNode.copy(
                                isCurve = true,
                                control1X = newPos.x,
                                control1Y = newPos.y,
                                control2X = anchorX - ux * d1,
                                control2Y = anchorY - uy * d1
                            )
                        } else {
                            oldNode.copy(isCurve = true, control1X = newPos.x, control1Y = newPos.y)
                        }
                    }
                    else -> { // "BEBAS"
                        oldNode.copy(
                            isCurve = true,
                            control1X = newPos.x,
                            control1Y = newPos.y
                        )
                    }
                }
            } else { // "out" or "control2"
                val dx2 = newPos.x - anchorX
                val dy2 = newPos.y - anchorY
                val d2 = hypot(dx2, dy2)
                
                when (type) {
                    "ASIMETRIS", "HALUS" -> {
                        if (d2 > 0.1f) {
                            val ux = dx2 / d2
                            val uy = dy2 / d2
                            val dx1 = oldNode.control1X - anchorX
                            val dy1 = oldNode.control1Y - anchorY
                            val d1 = hypot(dx1, dy1)
                            oldNode.copy(
                                isCurve = true,
                                control1X = anchorX - ux * d1,
                                control1Y = anchorY - uy * d1,
                                control2X = newPos.x,
                                control2Y = newPos.y
                            )
                        } else {
                            oldNode.copy(isCurve = true, control2X = newPos.x, control2Y = newPos.y)
                        }
                    }
                    "SIMETRIS" -> {
                        if (d2 > 0.1f) {
                            val ux = dx2 / d2
                            val uy = dy2 / d2
                            oldNode.copy(
                                isCurve = true,
                                control1X = anchorX - ux * d2,
                                control1Y = anchorY - uy * d2,
                                control2X = newPos.x,
                                control2Y = newPos.y
                            )
                        } else {
                            oldNode.copy(isCurve = true, control2X = newPos.x, control2Y = newPos.y)
                        }
                    }
                    else -> { // "BEBAS"
                        oldNode.copy(
                            isCurve = true,
                            control2X = newPos.x,
                            control2Y = newPos.y
                        )
                    }
                }
            }
            activeBezierNodes = activeBezierNodes.toMutableList().apply {
                this[index] = updated
            }
        }
    }

    // Pen Tool: Toggle node curve
    fun toggleNodeCurve(index: Int) {
        if (index !in activeBezierNodes.indices) return
        val nodes = activeBezierNodes.toMutableList()
        val node = nodes[index]
        val isNowCurve = !node.isCurve
        
        if (isNowCurve) {
            // Symmetrically generate handles based on neighbors
            val prev = nodes[(index - 1 + nodes.size) % nodes.size]
            val next = nodes[(index + 1) % nodes.size]
            
            val dx = next.anchorX - prev.anchorX
            val dy = next.anchorY - prev.anchorY
            val len = hypot(dx, dy)
            if (len > 0.1f) {
                val smoothing = 0.3f
                val handleLen = len * smoothing
                val ux = dx / len
                val uy = dy / len
                
                nodes[index] = node.copy(
                    isCurve = true,
                    control1X = node.anchorX - ux * handleLen,
                    control1Y = node.anchorY - uy * handleLen,
                    control2X = node.anchorX + ux * handleLen,
                    control2Y = node.anchorY + uy * handleLen
                )
            } else {
                nodes[index] = node.copy(
                    isCurve = true,
                    control1X = node.anchorX - 30f,
                    control1Y = node.anchorY,
                    control2X = node.anchorX + 30f,
                    control2Y = node.anchorY
                )
            }
        } else {
            // Revert sharp
            nodes[index] = node.copy(
                isCurve = false,
                control1X = node.anchorX,
                control1Y = node.anchorY,
                control2X = node.anchorX,
                control2Y = node.anchorY
            )
        }
        activeBezierNodes = nodes
    }

    // Pen Tool: Drag segment to curve
    fun dragSegment(index: Int, dragDelta: Offset) {
        val nextIdx = (index + 1) % activeBezierNodes.size
        
        // Ensure neighbors are round
        if (!activeBezierNodes[index].isCurve) toggleNodeCurve(index)
        if (!activeBezierNodes[nextIdx].isCurve) toggleNodeCurve(nextIdx)

        val nodes = activeBezierNodes.toMutableList()
        
        // Adjust control points based on dragDelta
        val n1 = nodes[index]
        val n2 = nodes[nextIdx]
        
        nodes[index] = n1.copy(control2X = n1.control2X + dragDelta.x, control2Y = n1.control2Y + dragDelta.y)
        nodes[nextIdx] = n2.copy(control1X = n2.control1X + dragDelta.x, control1Y = n2.control1Y + dragDelta.y)
        
        activeBezierNodes = nodes
    }

    // New logic: Direct canvas Bezier segment dragging with weight based on parameter t
    fun updateActiveBezierSegmentDelta(
        prevIdx: Int, 
        nextIdx: Int, 
        dx: Float, 
        dy: Float, 
        t: Float,
        startC2X: Float,
        startC2Y: Float,
        startC1X: Float,
        startC1Y: Float
    ) {
        if (prevIdx in activeBezierNodes.indices && nextIdx in activeBezierNodes.indices) {
            val list = activeBezierNodes.toMutableList()
            
            val nPrev = list[prevIdx]
            val nNext = list[nextIdx]
            
            // Weight control point offsets: (1 - t) for prev control2, t for next control1
            val weightPrev = (1f - t).coerceIn(0.1f, 0.9f)
            val weightNext = t.coerceIn(0.1f, 0.9f)
            
            list[prevIdx] = nPrev.copy(
                isCurve = true,
                control2X = startC2X + dx * weightPrev * 1.5f,
                control2Y = startC2Y + dy * weightPrev * 1.5f
            )
            list[nextIdx] = nNext.copy(
                isCurve = true,
                control1X = startC1X + dx * weightNext * 1.5f,
                control1Y = startC1Y + dy * weightNext * 1.5f
            )
            
            activeBezierNodes = list
        }
    }

    fun updateShapeSegmentDelta(
        shapeId: String,
        prevIdx: Int,
        nextIdx: Int,
        dx: Float,
        dy: Float,
        t: Float,
        startC2X: Float,
        startC2Y: Float,
        startC1X: Float,
        startC1Y: Float
    ) {
        shapes = shapes.map { shape ->
            if (shape.id == shapeId && shape.type == ShapeType.BEZIER_PATH) {
                val nodes = shape.bezierNodes.toMutableList()
                if (prevIdx in nodes.indices && nextIdx in nodes.indices) {
                    val nPrev = nodes[prevIdx]
                    val nNext = nodes[nextIdx]
                    
                    val weightPrev = (1f - t).coerceIn(0.1f, 0.9f)
                    val weightNext = t.coerceIn(0.1f, 0.9f)
                    
                    nodes[prevIdx] = nPrev.copy(
                        isCurve = true,
                        control2X = startC2X + dx * weightPrev * 1.5f,
                        control2Y = startC2Y + dy * weightPrev * 1.5f
                    )
                    nodes[nextIdx] = nNext.copy(
                        isCurve = true,
                        control1X = startC1X + dx * weightNext * 1.5f,
                        control1Y = startC1Y + dy * weightNext * 1.5f
                    )
                }
                shape.copy(bezierNodes = nodes)
            } else {
                shape
            }
        }
    }

    private fun hypot(dx: Float, dy: Float) = kotlin.math.hypot(dx, dy)

    fun finalizeBezierPath(isClosed: Boolean) {
        if (activeBezierNodes.size < 2) {
            activeBezierNodes = emptyList()
            activeEditNodeIndex = null
            return
        }
        pushToUndoStack()
        val id = UUID.randomUUID().toString()
        val shape = VectorShape(
            id = id,
            name = "Bezier Path ${shapes.size + 1}",
            type = ShapeType.BEZIER_PATH,
            bezierNodes = activeBezierNodes,
            isPathClosed = isClosed,
            strokeColorHex = currentStrokeColorHex,
            strokeWidth = currentStrokeWidth,
            strokeAlpha = currentStrokeAlpha,
            strokeJoin = currentStrokeJoin,
            strokeCap = currentStrokeCap,
            hasStroke = hasStrokeEnabled,
            hasFill = hasFillEnabled && isClosed,
            fillColorHex = currentFillColorHex,
            fillAlpha = currentFillAlpha,
            layerOrder = shapes.size
        )
        val shapeWithLayer = shape.copy(layerId = activeLayerId)
        shapes = shapes + shapeWithLayer
        activeBezierNodes = emptyList()
        activeEditNodeIndex = null
        selectedShapeId = id
    }

    // Clear active pen path creation
    fun clearDraftPath() {
        activeBezierNodes = emptyList()
        activeEditNodeIndex = null
    }

    // Delete currently selected node from path/shape
    fun deleteSelectedNode() {
        if (currentTool == VectorTool.PEN) {
            val idx = activeEditNodeIndex
            if (idx != null && idx in activeBezierNodes.indices) {
                pushToUndoStack()
                activeBezierNodes = activeBezierNodes.toMutableList().apply {
                    removeAt(idx)
                }
                if (activeBezierNodes.isEmpty()) {
                    activeEditNodeIndex = null
                } else {
                    activeEditNodeIndex = (idx - 1).coerceAtLeast(0)
                }
            }
        } else if (currentTool == VectorTool.DIRECT_SELECTION) {
            val shapeId = selectedShapeId
            if (shapeId != null && selectedDirectSelectionNodes.isNotEmpty()) {
                pushToUndoStack()
                shapes = shapes.map { shape ->
                    if (shape.id == shapeId) {
                        when (shape.type) {
                            ShapeType.BEZIER_PATH -> {
                                val newNodes = shape.bezierNodes.toMutableList().apply {
                                    for (i in selectedDirectSelectionNodes.sortedDescending()) {
                                        if (i in indices) removeAt(i)
                                    }
                                }
                                shape.copy(bezierNodes = newNodes)
                            }
                            ShapeType.FREEHAND -> {
                                val newPts = shape.freehandPoints.toMutableList().apply {
                                    for (i in selectedDirectSelectionNodes.sortedDescending()) {
                                        if (i in indices) removeAt(i)
                                    }
                                }
                                shape.copy(freehandPoints = newPts)
                            }
                            else -> shape
                        }
                    } else {
                        shape
                    }
                }.filter { 
                    !(it.type == ShapeType.BEZIER_PATH && it.bezierNodes.isEmpty()) && 
                    !(it.type == ShapeType.FREEHAND && it.freehandPoints.isEmpty())
                }
                selectedDirectSelectionNodes = emptySet()
            }
        }
    }

    // Tool bucket mode: sets colors directly
    fun applyBucketColorAt(pos: Offset) {
        val clickedShape = shapes.reversed().firstOrNull { it.isPointInside(pos.x, pos.y) }
        if (clickedShape != null) {
            pushToUndoStack()
            shapes = shapes.map { m ->
                if (m.id == clickedShape.id) {
                    m.copy(
                        hasFill = true,
                        fillColorHex = currentFillColorHex,
                        fillAlpha = currentFillAlpha
                    )
                } else {
                    m
                }
            }
        }
    }

    // Delete items
    fun deleteSelectedShape() {
        if (selectedShapeIds.isEmpty()) return
        pushToUndoStack()
        shapes = shapes.filter { !selectedShapeIds.contains(it.id) }
        selectedShapeId = null
        selectedShapeIds = emptySet()
    }

    fun deleteShapeById(shapeId: String) {
        pushToUndoStack()
        shapes = shapes.filter { it.id != shapeId }
        if (selectedShapeId == shapeId) selectedShapeId = null
        if (selectedShapeIds.contains(shapeId)) {
            selectedShapeIds = selectedShapeIds - shapeId
        }
    }

    // Mirror / Flip tools (Row 2, Button 3)
    fun flipSelectedShape(horizontal: Boolean, vertical: Boolean) {
        if (selectedShapeIds.isEmpty()) return
        pushToUndoStack()
        
        // Find center of the total bounding box representing selected shapes
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        
        val targets = shapes.filter { selectedShapeIds.contains(it.id) }
        if (targets.isEmpty()) return
        
        for (target in targets) {
            val bounds = target.getBoundingBox()
            if (bounds.left < minX) minX = bounds.left
            if (bounds.top < minY) minY = bounds.top
            if (bounds.right > maxX) maxX = bounds.right
            if (bounds.bottom > maxY) maxY = bounds.bottom
        }
        
        val cx = (minX + maxX) / 2f
        val cy = (minY + maxY) / 2f
        
        shapes = shapes.map { shape ->
            if (selectedShapeIds.contains(shape.id)) {
                shape.copyWithFlip(horizontal, vertical, cx, cy)
            } else {
                shape
            }
        }
    }

    fun groupSelectedShapes() {
        if (selectedShapeIds.size < 2) return
        pushToUndoStack()
        val newGroupId = UUID.randomUUID().toString()
        shapes = shapes.map { shape ->
            if (selectedShapeIds.contains(shape.id)) {
                shape.copy(groupId = newGroupId)
            } else {
                shape
            }
        }
    }

    fun ungroupSelectedShapes() {
        pushToUndoStack()
        val groupsToClear = shapes.filter { selectedShapeIds.contains(it.id) && it.groupId != null }.mapNotNull { it.groupId }.toSet()
        shapes = shapes.map { shape ->
            if (shape.groupId != null && groupsToClear.contains(shape.groupId)) {
                shape.copy(groupId = null)
            } else {
                shape
            }
        }
    }

    fun rotateSelectedShapes(angleDegrees: Float) {
        if (selectedShapeIds.isEmpty()) return
        pushToUndoStack()

        val activeShapes = shapes.filter { selectedShapeIds.contains(it.id) }
        val minX = activeShapes.minOfOrNull { it.getBoundingBox().left } ?: 0f
        val maxX = activeShapes.maxOfOrNull { it.getBoundingBox().right } ?: 0f
        val minY = activeShapes.minOfOrNull { it.getBoundingBox().top } ?: 0f
        val maxY = activeShapes.maxOfOrNull { it.getBoundingBox().bottom } ?: 0f
        val combinedCenter = Offset((minX + maxX) / 2f, (minY + maxY) / 2f)

        shapes = shapes.map { shape ->
            if (selectedShapeIds.contains(shape.id)) {
                val sBounds = shape.getBoundingBox()
                val scx = (sBounds.left + sBounds.right) / 2f
                val scy = (sBounds.top + sBounds.bottom) / 2f
                val sCenter = Offset(scx, scy)
                
                val sCenterRotated = rotatePoint(sCenter, combinedCenter, angleDegrees)
                val dx = sCenterRotated.x - sCenter.x
                val dy = sCenterRotated.y - sCenter.y
                
                val newAngle = (shape.rotationAngle + angleDegrees) % 360f
                shape.copyWithTransform(dx, dy).copy(rotationAngle = if (newAngle < 0f) newAngle + 360f else newAngle)
            } else {
                shape
            }
        }
    }

    fun setSelectedShapesSize(newWidth: Float, newHeight: Float) {
        if (selectedShapeIds.isEmpty() || newWidth <= 0.1f || newHeight <= 0.1f) return
        pushToUndoStack()

        val activeShapes = shapes.filter { selectedShapeIds.contains(it.id) }
        if (activeShapes.isEmpty()) return

        val minX = activeShapes.minOfOrNull { it.getBoundingBox().left } ?: 0f
        val maxX = activeShapes.maxOfOrNull { it.getBoundingBox().right } ?: 0f
        val minY = activeShapes.minOfOrNull { it.getBoundingBox().top } ?: 0f
        val maxY = activeShapes.maxOfOrNull { it.getBoundingBox().bottom } ?: 0f

        val currentW = maxX - minX
        val currentH = maxY - minY

        if (currentW > 0.01f && currentH > 0.01f) {
            val scaleX = newWidth / currentW
            val scaleY = newHeight / currentH

            shapes = shapes.map { shape ->
                if (selectedShapeIds.contains(shape.id)) {
                    shape.copyWithScale(scaleX, scaleY, minX, minY)
                } else {
                    shape
                }
            }
        }
    }

    fun rotateSelectedShapesToAngle(angleDegrees: Float) {
        if (selectedShapeIds.isEmpty()) return
        pushToUndoStack()
        
        shapes = shapes.map { shape ->
            if (selectedShapeIds.contains(shape.id)) {
                var targetAngle = angleDegrees % 360f
                if (targetAngle < 0f) targetAngle += 360f
                shape.copy(rotationAngle = targetAngle)
            } else {
                shape
            }
        }
    }

    fun bringSelectedToFront() {
        if (selectedShapeIds.isEmpty()) return
        pushToUndoStack()
        
        val layerShapes = shapes.filter { it.layerId == activeLayerId }
        val selected = layerShapes.filter { selectedShapeIds.contains(it.id) }
        val unselected = layerShapes.filter { !selectedShapeIds.contains(it.id) }
        val newLayerShapes = unselected + selected
        
        var activeShapeIdx = 0
        shapes = shapes.map { shape ->
            if (shape.layerId == activeLayerId) {
                newLayerShapes[activeShapeIdx++]
            } else {
                shape
            }
        }
    }

    fun bringSelectedForward() {
        if (selectedShapeIds.isEmpty()) return
        pushToUndoStack()
        
        val layerShapes = shapes.filter { it.layerId == activeLayerId }
        val newLayerShapes = layerShapes.toMutableList()
        for (i in newLayerShapes.size - 2 downTo 0) {
            val current = newLayerShapes[i]
            if (selectedShapeIds.contains(current.id)) {
                val next = newLayerShapes[i + 1]
                if (!selectedShapeIds.contains(next.id)) {
                    newLayerShapes[i] = next
                    newLayerShapes[i + 1] = current
                }
            }
        }
        
        var activeShapeIdx = 0
        shapes = shapes.map { shape ->
            if (shape.layerId == activeLayerId) {
                newLayerShapes[activeShapeIdx++]
            } else {
                shape
            }
        }
    }

    fun sendSelectedBackward() {
        if (selectedShapeIds.isEmpty()) return
        pushToUndoStack()
        
        val layerShapes = shapes.filter { it.layerId == activeLayerId }
        val newLayerShapes = layerShapes.toMutableList()
        for (i in 1 until newLayerShapes.size) {
            val current = newLayerShapes[i]
            if (selectedShapeIds.contains(current.id)) {
                val prev = newLayerShapes[i - 1]
                if (!selectedShapeIds.contains(prev.id)) {
                    newLayerShapes[i] = prev
                    newLayerShapes[i - 1] = current
                }
            }
        }
        
        var activeShapeIdx = 0
        shapes = shapes.map { shape ->
            if (shape.layerId == activeLayerId) {
                newLayerShapes[activeShapeIdx++]
            } else {
                shape
            }
        }
    }

    fun sendSelectedToBack() {
        if (selectedShapeIds.isEmpty()) return
        pushToUndoStack()
        
        val layerShapes = shapes.filter { it.layerId == activeLayerId }
        val selected = layerShapes.filter { selectedShapeIds.contains(it.id) }
        val unselected = layerShapes.filter { !selectedShapeIds.contains(it.id) }
        val newLayerShapes = selected + unselected
        
        var activeShapeIdx = 0
        shapes = shapes.map { shape ->
            if (shape.layerId == activeLayerId) {
                newLayerShapes[activeShapeIdx++]
            } else {
                shape
            }
        }
    }

    fun moveShapeUpWithinLayer(shapeId: String) {
        pushToUndoStack()
        val sList = shapes.toMutableList()
        val idx = sList.indexOfFirst { it.id == shapeId }
        if (idx == -1) return
        val currentShape = sList[idx]
        
        var swapIdx = -1
        for (i in idx + 1 until sList.size) {
            if (sList[i].layerId == currentShape.layerId) {
                swapIdx = i
                break
            }
        }
        if (swapIdx != -1) {
            sList[idx] = sList[swapIdx]
            sList[swapIdx] = currentShape
            shapes = sList
        }
    }

    fun moveShapeDownWithinLayer(shapeId: String) {
        pushToUndoStack()
        val sList = shapes.toMutableList()
        val idx = sList.indexOfFirst { it.id == shapeId }
        if (idx == -1) return
        val currentShape = sList[idx]
        
        var swapIdx = -1
        for (i in idx - 1 downTo 0) {
            if (sList[i].layerId == currentShape.layerId) {
                swapIdx = i
                break
            }
        }
        if (swapIdx != -1) {
            sList[idx] = sList[swapIdx]
            sList[swapIdx] = currentShape
            shapes = sList
        }
    }

    fun rotateSelectedShapesAbsolute(startShapes: List<VectorShape>, angleOffset: Float) {
        if (selectedShapeIds.isEmpty()) return

        val activeStartShapes = startShapes.filter { selectedShapeIds.contains(it.id) }
        val minX = activeStartShapes.minOfOrNull { it.getBoundingBox().left } ?: 0f
        val maxX = activeStartShapes.maxOfOrNull { it.getBoundingBox().right } ?: 0f
        val minY = activeStartShapes.minOfOrNull { it.getBoundingBox().top } ?: 0f
        val maxY = activeStartShapes.maxOfOrNull { it.getBoundingBox().bottom } ?: 0f
        val combinedCenter = Offset((minX + maxX) / 2f, (minY + maxY) / 2f)

        shapes = shapes.map { shape ->
            val startVal = startShapes.find { it.id == shape.id }
            if (startVal != null && selectedShapeIds.contains(shape.id)) {
                val sBounds = startVal.getBoundingBox()
                val scx = (sBounds.left + sBounds.right) / 2f
                val scy = (sBounds.top + sBounds.bottom) / 2f
                val sCenter = Offset(scx, scy)
                
                val sCenterRotated = rotatePoint(sCenter, combinedCenter, angleOffset)
                val dx = sCenterRotated.x - sCenter.x
                val dy = sCenterRotated.y - sCenter.y
                
                var targetAngle = (startVal.rotationAngle + angleOffset) % 360f
                if (targetAngle < 0f) targetAngle += 360f
                
                startVal.copyWithTransform(dx, dy).copy(rotationAngle = targetAngle)
            } else {
                shape
            }
        }
    }

    // Layer Stack Management (Row 2, Button 4: Layer ordering & Duplication)
    fun bringToFront() {
        val id = selectedShapeId ?: return
        val item = shapes.find { it.id == id } ?: return
        pushToUndoStack()
        // Re-generate list moving this item to the end
        shapes = shapes.filter { it.id != id } + item
    }

    fun sendToBack() {
        val id = selectedShapeId ?: return
        val item = shapes.find { it.id == id } ?: return
        pushToUndoStack()
        // Re-generate list prepending this item
        shapes = listOf(item) + shapes.filter { it.id != id }
    }

    fun duplicateSelected() {
        val activeIds = if (selectedShapeIds.isNotEmpty()) selectedShapeIds else setOfNotNull(selectedShapeId)
        if (activeIds.isEmpty()) return
        pushToUndoStack()
        
        val newIds = mutableSetOf<String>()
        val newDuplicatedShapes = mutableListOf<VectorShape>()
        
        val sortedActiveShapes = shapes.filter { activeIds.contains(it.id) }
        
        // Map old group ID -> new group ID to separate cloned groups from original groups
        val groupIdMap = mutableMapOf<String, String>()
        
        for (original in sortedActiveShapes) {
            val originalGroupId = original.groupId
            val newGroupId = if (originalGroupId != null) {
                groupIdMap.getOrPut(originalGroupId) { UUID.randomUUID().toString() }
            } else {
                null
            }
            
            val newId = UUID.randomUUID().toString()
            val duplicated = original.copyWithTransform(30f, 30f).copy(
                id = newId,
                name = "${original.name} Copy",
                groupId = newGroupId
            )
            val shapeWithLayer = duplicated.copy(layerId = activeLayerId)
            newDuplicatedShapes.add(shapeWithLayer)
            newIds.add(newId)
        }
        
        shapes = shapes + newDuplicatedShapes
        
        if (newIds.size == 1) {
            selectedShapeId = newIds.first()
            selectedShapeIds = newIds
        } else {
            selectedShapeId = newIds.firstOrNull()
            selectedShapeIds = newIds
        }
    }

    fun duplicateSpecificShape(original: VectorShape, dx: Float, dy: Float): VectorShape {
        pushToUndoStack()
        val newId = UUID.randomUUID().toString()
        val duplicated = original.copyWithTransform(dx, dy).copy(
            id = newId,
            name = "${original.name} Copy"
        )
        val shapeWithLayer = duplicated.copy(layerId = activeLayerId)
        shapes = shapes + shapeWithLayer
        selectedShapeId = newId
        selectedShapeIds = setOf(newId)
        return duplicated
    }

    // Alignment operations (Row 2, Button 6)
    fun alignSelectedShape(alignment: String) {
        val id = selectedShapeId ?: return
        val targets = if (selectedShapeIds.isNotEmpty()) selectedShapeIds else setOf(id)
        val groupsToMove = shapes.filter { targets.contains(it.id) && it.groupId != null }.mapNotNull { it.groupId }.toSet()
        val shapesToMoveIds = shapes.filter { targets.contains(it.id) || (it.groupId != null && groupsToMove.contains(it.groupId)) }.map { it.id }.toSet()
        
        val bounds = getCombinedBoundingBox(shapesToMoveIds, shapes) ?: return
        pushToUndoStack()

        val dx: Float
        val dy: Float

        if (alignBasisIsCanvas) {
            when (alignment) {
                "LEFT" -> {
                    dx = -bounds.left
                    dy = 0f
                }
                "RIGHT" -> {
                    dx = canvasWidth - bounds.right
                    dy = 0f
                }
                "TOP" -> {
                    dx = 0f
                    dy = -bounds.top
                }
                "BOTTOM" -> {
                    dx = 0f
                    dy = canvasHeight - bounds.bottom
                }
                "CENTER_HORIZ" -> {
                    val boundsCenterX = bounds.left + bounds.width / 2f
                    val canvasCenterX = canvasWidth / 2f
                    dx = canvasCenterX - boundsCenterX
                    dy = 0f
                }
                "CENTER_VERT" -> {
                    val boundsCenterY = bounds.top + bounds.height / 2f
                    val canvasCenterY = canvasHeight / 2f
                    dx = 0f
                    dy = canvasCenterY - boundsCenterY
                }
                else -> {
                    dx = 0f
                    dy = 0f
                }
            }
        } else {
            // Align based on Object - compare against the closest object NOT in the moving selection
            val otherShapes = shapes.filter { !shapesToMoveIds.contains(it.id) && it.isVisible }
            if (otherShapes.isEmpty()) {
                dx = 0f
                dy = 0f
            } else {
                val targetCenter = Offset(bounds.left + bounds.width / 2f, bounds.top + bounds.height / 2f)
                val reference = otherShapes.minByOrNull { s ->
                    val sb = s.getBoundingBox()
                    val sc = Offset(sb.left + sb.width / 2f, sb.top + sb.height / 2f)
                    kotlin.math.hypot(targetCenter.x - sc.x, targetCenter.y - sc.y)
                } ?: otherShapes.first()

                val refBounds = reference.getBoundingBox()
                when (alignment) {
                    "LEFT" -> {
                        dx = refBounds.left - bounds.left
                        dy = 0f
                    }
                    "RIGHT" -> {
                        dx = refBounds.right - bounds.right
                        dy = 0f
                    }
                    "TOP" -> {
                        dx = 0f
                        dy = refBounds.top - bounds.top
                    }
                    "BOTTOM" -> {
                        dx = 0f
                        dy = refBounds.bottom - bounds.bottom
                    }
                    "CENTER_HORIZ" -> {
                        val tcX = bounds.left + bounds.width / 2f
                        val rcX = refBounds.left + refBounds.width / 2f
                        dx = rcX - tcX
                        dy = 0f
                    }
                    "CENTER_VERT" -> {
                        val tcY = bounds.top + bounds.height / 2f
                        val rcY = refBounds.top + refBounds.height / 2f
                        dx = 0f
                        dy = rcY - tcY
                    }
                    else -> {
                        dx = 0f
                        dy = 0f
                    }
                }
            }
        }

        shapes = shapes.map { shape ->
            if (shapesToMoveIds.contains(shape.id)) {
                if (shape.isLocked) shape else shape.copyWithTransform(dx, dy)
            } else {
                shape
            }
        }
    }

    // Text tools (Row 2, Button 5)
    fun addTextShape(text: String) {
        pushToUndoStack()
        val id = UUID.randomUUID().toString()
        val shape = VectorShape(
            id = id,
            name = "Text ${shapes.size + 1}",
            type = ShapeType.TEXT,
            x = canvasWidth / 2f - 40f,
            y = canvasHeight / 2f,
            textContent = text,
            fontSize = 32f,
            strokeColorHex = currentStrokeColorHex,
            layerOrder = shapes.size
        )
        val shapeWithLayer = shape.copy(layerId = activeLayerId)
        shapes = shapes + shapeWithLayer
        selectedShapeId = id
    }

    fun updateTextContent(text: String) {
        val id = selectedShapeId ?: return
        pushToUndoStack()
        shapes = shapes.map { shape ->
            if (shape.id == id && shape.type == ShapeType.TEXT) {
                shape.copy(textContent = text)
            } else {
                shape
            }
        }
    }

    // Resize and Translate on canvas drag gestures (For transform/Pointer mode)
    fun translateShape(id: String, dx: Float, dy: Float) {
        val targets = if (selectedShapeIds.contains(id)) selectedShapeIds else setOf(id)
        val groupsToMove = shapes.filter { targets.contains(it.id) && it.groupId != null }.mapNotNull { it.groupId }.toSet()
        
        shapes = shapes.map { shape ->
            if (targets.contains(shape.id) || (shape.groupId != null && groupsToMove.contains(shape.groupId))) {
                if (shape.isLocked) shape else shape.copyWithTransform(dx, dy)
            } else {
                shape
            }
        }
    }

    fun translateShapesAbsolute(dragStartShapes: List<VectorShape>, targets: Set<String>, dx: Float, dy: Float) {
        val groupsToMove = dragStartShapes.filter { targets.contains(it.id) && it.groupId != null }.mapNotNull { it.groupId }.toSet()
        shapes = dragStartShapes.map { shape ->
            if (targets.contains(shape.id) || (shape.groupId != null && groupsToMove.contains(shape.groupId))) {
                if (shape.isLocked) shape else shape.copyWithTransform(dx, dy)
            } else {
                shape
            }
        }
    }

    fun resizeShapeAbsolute(dragStartShapes: List<VectorShape>, id: String, handleName: String, totalDrag: Offset) {
        val activeIds = if (selectedShapeIds.contains(id)) selectedShapeIds else setOf(id)
        val bounds = getCombinedBoundingBox(activeIds, dragStartShapes) ?: return
        val px: Float
        val py: Float
        var scaleX = 1f
        var scaleY = 1f

        when (handleName) {
            "BR" -> { // Bottom Right
                px = bounds.left
                py = bounds.top
                val newW = bounds.width + totalDrag.x
                val newH = bounds.height + totalDrag.y
                if (bounds.width > 0) scaleX = newW / bounds.width
                if (bounds.height > 0) scaleY = newH / bounds.height
            }
            "TL" -> { // Top Left
                px = bounds.right
                py = bounds.bottom
                val newW = bounds.width - totalDrag.x
                val newH = bounds.height - totalDrag.y
                if (bounds.width > 0) scaleX = newW / bounds.width
                if (bounds.height > 0) scaleY = newH / bounds.height
            }
            "TR" -> { // Top Right
                px = bounds.left
                py = bounds.bottom
                val newW = bounds.width + totalDrag.x
                val newH = bounds.height - totalDrag.y
                if (bounds.width > 0) scaleX = newW / bounds.width
                if (bounds.height > 0) scaleY = newH / bounds.height
            }
            "BL" -> { // Bottom Left
                px = bounds.right
                py = bounds.top
                val newW = bounds.width - totalDrag.x
                val newH = bounds.height + totalDrag.y
                if (bounds.width > 0) scaleX = newW / bounds.width
                if (bounds.height > 0) scaleY = newH / bounds.height
            }
            "T" -> { // Top edge center
                px = bounds.left
                py = bounds.bottom
                val newH = bounds.height - totalDrag.y
                scaleX = 1f
                if (bounds.height > 0) scaleY = newH / bounds.height
            }
            "B" -> { // Bottom edge center
                px = bounds.left
                py = bounds.top
                val newH = bounds.height + totalDrag.y
                scaleX = 1f
                if (bounds.height > 0) scaleY = newH / bounds.height
            }
            "L" -> { // Left edge center
                px = bounds.right
                py = bounds.top
                val newW = bounds.width - totalDrag.x
                if (bounds.width > 0) scaleX = newW / bounds.width
                scaleY = 1f
            }
            "R" -> { // Right edge center
                px = bounds.left
                py = bounds.top
                val newW = bounds.width + totalDrag.x
                if (bounds.width > 0) scaleX = newW / bounds.width
                scaleY = 1f
            }
            else -> {
                px = bounds.left
                py = bounds.top
            }
        }

        if (isAspectLocked) {
            val scale = if (handleName == "T" || handleName == "B") scaleY else if (handleName == "L" || handleName == "R") scaleX else {
                if (kotlin.math.abs(scaleX - 1f) > kotlin.math.abs(scaleY - 1f)) scaleX else scaleY
            }
            scaleX = scale
            scaleY = scale
        }

        shapes = dragStartShapes.map { s ->
            if (activeIds.contains(s.id)) {
                if (s.isLocked) s else s.copyWithScale(scaleX, scaleY, px, py)
            } else {
                s
            }
        }
    }

    fun finishTransformation() {
        // No-op. Unified pointerup handler handles pushing the original state to the undo stack.
    }

    fun getCombinedBoundingBox(shapeIds: Set<String>, list: List<VectorShape>): Rect? {
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

    fun resizeShape(id: String, handleName: String, dragAmount: Offset) {
        val activeIds = if (selectedShapeIds.contains(id)) selectedShapeIds else setOf(id)
        val bounds = getCombinedBoundingBox(activeIds, shapes) ?: return
        val px: Float
        val py: Float
        var scaleX = 1f
        var scaleY = 1f

        when (handleName) {
            "BR" -> { // Bottom Right
                px = bounds.left
                py = bounds.top
                val newW = bounds.width + dragAmount.x
                val newH = bounds.height + dragAmount.y
                if (bounds.width > 0) scaleX = newW / bounds.width
                if (bounds.height > 0) scaleY = newH / bounds.height
            }
            "TL" -> { // Top Left
                px = bounds.right
                py = bounds.bottom
                val newW = bounds.width - dragAmount.x
                val newH = bounds.height - dragAmount.y
                if (bounds.width > 0) scaleX = newW / bounds.width
                if (bounds.height > 0) scaleY = newH / bounds.height
            }
            "TR" -> { // Top Right
                px = bounds.left
                py = bounds.bottom
                val newW = bounds.width + dragAmount.x
                val newH = bounds.height - dragAmount.y
                if (bounds.width > 0) scaleX = newW / bounds.width
                if (bounds.height > 0) scaleY = newH / bounds.height
            }
            "BL" -> { // Bottom Left
                px = bounds.right
                py = bounds.top
                val newW = bounds.width - dragAmount.x
                val newH = bounds.height + dragAmount.y
                if (bounds.width > 0) scaleX = newW / bounds.width
                if (bounds.height > 0) scaleY = newH / bounds.height
            }
            "T" -> { // Top edge center
                px = bounds.left
                py = bounds.bottom
                val newH = bounds.height - dragAmount.y
                scaleX = 1f
                if (bounds.height > 0) scaleY = newH / bounds.height
            }
            "B" -> { // Bottom edge center
                px = bounds.left
                py = bounds.top
                val newH = bounds.height + dragAmount.y
                scaleX = 1f
                if (bounds.height > 0) scaleY = newH / bounds.height
            }
            "L" -> { // Left edge center
                px = bounds.right
                py = bounds.top
                val newW = bounds.width - dragAmount.x
                if (bounds.width > 0) scaleX = newW / bounds.width
                scaleY = 1f
            }
            "R" -> { // Right edge center
                px = bounds.left
                py = bounds.top
                val newW = bounds.width + dragAmount.x
                if (bounds.width > 0) scaleX = newW / bounds.width
                scaleY = 1f
            }
            else -> {
                return
            }
        }

        if (isAspectLocked) {
            val scale = if (handleName == "T" || handleName == "B") scaleY else if (handleName == "L" || handleName == "R") scaleX else {
                if (kotlin.math.abs(scaleX - 1f) > kotlin.math.abs(scaleY - 1f)) scaleX else scaleY
            }
            scaleX = scale
            scaleY = scale
        }

        shapes = shapes.map { s ->
            if (activeIds.contains(s.id)) {
                s.copyWithScale(scaleX, scaleY, px, py)
            } else {
                s
            }
        }
    }

    private data class BezierSegment(
        val p0: Offset,
        val p1: Offset,
        val p2: Offset,
        val p3: Offset,
        val originalShapeId: String,
        val isCurve: Boolean = false,
        val handleType: String = "SHARP_INTERSECTION"
    ) {
        fun getArcLength(t0: Float = 0f, t1: Float = 1f): Float {
            return com.example.model.CubicBezierHelper.calculateArcLength(p0, p1, p2, p3, t0, t1)
        }

        fun getCurvature(t: Float): Float {
            return com.example.model.CubicBezierHelper.calculateCurvature(p0, p1, p2, p3, t)
        }

        fun getInflectionPoints(): List<Float> {
            return com.example.model.CubicBezierHelper.calculateInflectionPoints(p0, p1, p2, p3)
        }

        fun getOptimalKeyPoints(angleThresholdDegrees: Float = 60f): List<Float> {
            return com.example.model.CubicBezierHelper.findOptimalKeyPoints(p0, p1, p2, p3, angleThresholdDegrees)
        }

        fun getPoint(t: Float): Offset {
            val mt = 1f - t
            val mt2 = mt * mt
            val mt3 = mt2 * mt
            val t2 = t * t
            val t3 = t2 * t
            val x = mt3 * p0.x + 3f * mt2 * t * p1.x + 3f * mt * t2 * p2.x + t3 * p3.x
            val y = mt3 * p0.y + 3f * mt2 * t * p1.y + 3f * mt * t2 * p2.y + t3 * p3.y
            return Offset(x, y)
        }

        fun findClosestT(q: Offset): Pair<Float, Float> {
            var bestT = 0f
            var minDist = Float.MAX_VALUE
            val n = 10
            for (i in 0..n) {
                val t = i.toFloat() / n
                val pt = getPoint(t)
                val dist = kotlin.math.hypot(pt.x - q.x, pt.y - q.y)
                if (dist < minDist) {
                    minDist = dist
                    bestT = t
                }
            }
            var tMin = maxOf(0f, bestT - 0.1f)
            var tMax = minOf(1f, bestT + 0.1f)
            for (step in 0..8) {
                val tMid = (tMin + tMax) / 2f
                val ptMid = getPoint(tMid)
                val distMid = kotlin.math.hypot(ptMid.x - q.x, ptMid.y - q.y)
                
                val tLeft = (tMin + tMid) / 2f
                val ptLeft = getPoint(tLeft)
                val distLeft = kotlin.math.hypot(ptLeft.x - q.x, ptLeft.y - q.y)

                val tRight = (tMid + tMax) / 2f
                val ptRight = getPoint(tRight)
                val distRight = kotlin.math.hypot(ptRight.x - q.x, ptRight.y - q.y)

                if (distLeft < distMid && distLeft < distRight) {
                    tMax = tMid
                    minDist = distLeft
                    bestT = tLeft
                } else if (distRight < distMid && distRight < distLeft) {
                    tMin = tMid
                    minDist = distRight
                    bestT = tRight
                } else {
                    tMin = tLeft
                    tMax = tRight
                    minDist = distMid
                    bestT = tMid
                }
            }
            return Pair(bestT, minDist)
        }

        fun split(t0: Float, t1: Float): BezierSegment {
            val left1 = splitLeft(t1)
            val t0Prime = if (t1 > 1e-5f) t0 / t1 else 0f
            return left1.splitRight(t0Prime)
        }

        private fun splitLeft(t: Float): BezierSegment {
            val p01 = lerp(p0, p1, t)
            val p12 = lerp(p1, p2, t)
            val p23 = lerp(p2, p3, t)
            val p012 = lerp(p01, p12, t)
            val p123 = lerp(p12, p23, t)
            val p0123 = lerp(p012, p123, t)
            return BezierSegment(p0, p01, p012, p0123, originalShapeId, isCurve, handleType)
        }

        private fun splitRight(t: Float): BezierSegment {
            val p01 = lerp(p0, p1, t)
            val p12 = lerp(p1, p2, t)
            val p23 = lerp(p2, p3, t)
            val p012 = lerp(p01, p12, t)
            val p123 = lerp(p12, p23, t)
            val p0123 = lerp(p012, p123, t)
            return BezierSegment(p0123, p123, p23, p3, originalShapeId, isCurve, handleType)
        }

        private fun lerp(start: Offset, end: Offset, fraction: Float): Offset {
            return Offset(
                start.x + (end.x - start.x) * fraction,
                start.y + (end.y - start.y) * fraction
            )
        }
    }

    private data class ProcessedPoint(
        val point: Offset,
        val segmentIndex: Int,
        val t: Float,
        val dist: Float
    )

    private fun getOriginalBezierSegments(shape: VectorShape): List<BezierSegment> {
        val segments = mutableListOf<BezierSegment>()
        when (shape.type) {
            ShapeType.RECTANGLE -> {
                val left = shape.x
                val top = shape.y
                val right = shape.x + shape.width
                val bottom = shape.y + shape.height
                val rTL = minOf(shape.radiusTL, shape.width / 2f, shape.height / 2f)
                val rTR = minOf(shape.radiusTR, shape.width / 2f, shape.height / 2f)
                val rBR = minOf(shape.radiusBR, shape.width / 2f, shape.height / 2f)
                val rBL = minOf(shape.radiusBL, shape.width / 2f, shape.height / 2f)

                fun addLine(start: Offset, end: Offset) {
                    if (kotlin.math.hypot(end.x - start.x, end.y - start.y) > 0.05f) {
                        segments.add(BezierSegment(
                            start, 
                            start + (end - start) / 3f, 
                            start + (end - start) * 2f / 3f, 
                            end, 
                            shape.id,
                            isCurve = false
                        ))
                    }
                }

                fun addCorner(pStart: Offset, pControl: Offset, pEnd: Offset) {
                    val p1 = pStart + (pControl - pStart) * (2f / 3f)
                    val p2 = pEnd + (pControl - pEnd) * (2f / 3f)
                    segments.add(BezierSegment(
                        pStart,
                        p1,
                        p2,
                        pEnd,
                        shape.id,
                        isCurve = true
                    ))
                }

                // Top line
                addLine(Offset(left + rTL, top), Offset(right - rTR, top))
                // Top-Right corner
                if (rTR > 0f) {
                    addCorner(Offset(right - rTR, top), Offset(right, top), Offset(right, top + rTR))
                }
                // Right line
                addLine(Offset(right, top + rTR), Offset(right, bottom - rBR))
                // Bottom-Right corner
                if (rBR > 0f) {
                    addCorner(Offset(right, bottom - rBR), Offset(right, bottom), Offset(right - rBR, bottom))
                }
                // Bottom line
                addLine(Offset(right - rBR, bottom), Offset(left + rBL, bottom))
                // Bottom-Left corner
                if (rBL > 0f) {
                    addCorner(Offset(left + rBL, bottom), Offset(left, bottom), Offset(left, bottom - rBL))
                }
                // Left line
                addLine(Offset(left, bottom - rBL), Offset(left, top + rTL))
                // Top-Left corner
                if (rTL > 0f) {
                    addCorner(Offset(left, top + rTL), Offset(left, top), Offset(left + rTL, top))
                }
            }
            ShapeType.ELLIPSE -> {
                val cx = shape.x
                val cy = shape.y
                val rx = shape.width
                val ry = shape.height
                val k = 0.55228475f
                
                segments.add(BezierSegment(
                    Offset(cx, cy - ry),
                    Offset(cx + k * rx, cy - ry),
                    Offset(cx + rx, cy - k * ry),
                    Offset(cx + rx, cy),
                    shape.id,
                    isCurve = true
                ))
                segments.add(BezierSegment(
                    Offset(cx + rx, cy),
                    Offset(cx + rx, cy + k * ry),
                    Offset(cx + k * rx, cy + ry),
                    Offset(cx, cy + ry),
                    shape.id,
                    isCurve = true
                ))
                segments.add(BezierSegment(
                    Offset(cx, cy + ry),
                    Offset(cx - k * rx, cy + ry),
                    Offset(cx - rx, cy + k * ry),
                    Offset(cx - rx, cy),
                    shape.id,
                    isCurve = true
                ))
                segments.add(BezierSegment(
                    Offset(cx - rx, cy),
                    Offset(cx - rx, cy - k * ry),
                    Offset(cx - k * rx, cy - ry),
                    Offset(cx, cy - ry),
                    shape.id,
                    isCurve = true
                ))
            }
            ShapeType.POLYGON, ShapeType.STAR -> {
                val vertices = shape.getCornerPoints()
                if (vertices.isNotEmpty()) {
                    val n = vertices.size
                    val drawAsArc = BooleanArray(n)
                    val arcStarts = Array(n) { Offset.Zero }
                    val arcEnds = Array(n) { Offset.Zero }
                    val radii = shape.customCornerRadii

                    for (i in 0 until n) {
                        val pi = vertices[i]
                        val prevIndex = if (i > 0) i - 1 else n - 1
                        val nextIndex = if (i < n - 1) i + 1 else 0
                        
                        val pPrev = vertices[prevIndex]
                        val pNext = vertices[nextIndex]
                        
                        val r = radii.getOrNull(i) ?: 0f
                        if (r > 0.1f) {
                            val v1 = pPrev - pi
                            val v2 = pNext - pi
                            val L1 = kotlin.math.hypot(v1.x, v1.y)
                            val L2 = kotlin.math.hypot(v2.x, v2.y)
                            if (L1 > 0.1f && L2 > 0.1f) {
                                val u1 = Offset(v1.x / L1, v1.y / L1)
                                val u2 = Offset(v2.x / L2, v2.y / L2)
                                
                                val rMax = minOf(L1, L2) / 2.2f
                                val rClamped = minOf(r, rMax)
                                
                                if (rClamped > 0.1f) {
                                    drawAsArc[i] = true
                                    arcStarts[i] = pi + u1 * rClamped
                                    arcEnds[i] = pi + u2 * rClamped
                                    continue
                                }
                            }
                        }
                        drawAsArc[i] = false
                    }

                    fun addLine(start: Offset, end: Offset) {
                        if (kotlin.math.hypot(end.x - start.x, end.y - start.y) > 0.05f) {
                            segments.add(BezierSegment(
                                start, 
                                start + (end - start) / 3f, 
                                start + (end - start) * 2f / 3f, 
                                end, 
                                shape.id,
                                isCurve = false
                            ))
                        }
                    }

                    fun addCorner(pStart: Offset, pControl: Offset, pEnd: Offset) {
                        val p1 = pStart + (pControl - pStart) * (2f / 3f)
                        val p2 = pEnd + (pControl - pEnd) * (2f / 3f)
                        segments.add(BezierSegment(
                            pStart,
                            p1,
                            p2,
                            pEnd,
                            shape.id,
                            isCurve = true
                        ))
                    }

                    for (i in 0 until n) {
                        val nextIdx = (i + 1) % n
                        
                        val currentEnd = if (drawAsArc[i]) arcEnds[i] else vertices[i]
                        val nextStart = if (drawAsArc[nextIdx]) arcStarts[nextIdx] else vertices[nextIdx]
                        
                        addLine(currentEnd, nextStart)
                        
                        if (drawAsArc[nextIdx]) {
                            addCorner(nextStart, vertices[nextIdx], arcEnds[nextIdx])
                        }
                    }
                }
            }
            ShapeType.LINE -> {
                val start = Offset(shape.startX, shape.startY)
                val end = Offset(shape.endX, shape.endY)
                segments.add(BezierSegment(
                    start, 
                    start + (end - start) / 3f, 
                    start + (end - start) * 2f / 3f, 
                    end, 
                    shape.id,
                    isCurve = false
                ))
            }
            ShapeType.FREEHAND -> {
                if (shape.freehandPoints.size >= 2) {
                    for (i in 0 until shape.freehandPoints.size - 1) {
                        val start = Offset(shape.freehandPoints[i].x, shape.freehandPoints[i].y)
                        val end = Offset(shape.freehandPoints[i+1].x, shape.freehandPoints[i+1].y)
                        segments.add(BezierSegment(
                            start, 
                            start + (end - start) / 3f, 
                            start + (end - start) * 2f / 3f, 
                            end, 
                            shape.id,
                            isCurve = false
                        ))
                    }
                }
            }
            ShapeType.BEZIER_PATH -> {
                if (shape.bezierNodes.isNotEmpty()) {
                    val subpaths = mutableListOf<MutableList<BezierNode>>()
                    var currentSubpath = mutableListOf<BezierNode>()
                    for (node in shape.bezierNodes) {
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
                        if (sub.size < 2) continue
                        for (i in 1 until sub.size) {
                            val prev = sub[i - 1]
                            val node = sub[i]
                            if (node.isCurve) {
                                segments.add(BezierSegment(
                                    prev.getAnchor(),
                                    Offset(prev.control2X, prev.control2Y),
                                    Offset(node.control1X, node.control1Y),
                                    node.getAnchor(),
                                    shape.id,
                                    isCurve = true,
                                    handleType = node.handleType
                                ))
                            } else {
                                val start = prev.getAnchor()
                                val end = node.getAnchor()
                                segments.add(BezierSegment(
                                    start, 
                                    start + (end - start) / 3f, 
                                    start + (end - start) * 2f / 3f, 
                                    end, 
                                    shape.id,
                                    isCurve = false,
                                    handleType = node.handleType
                                ))
                            }
                        }
                        if (shape.isPathClosed) {
                            val prev = sub.last()
                            val node = sub.first()
                            if (node.isCurve) {
                                segments.add(BezierSegment(
                                    prev.getAnchor(),
                                    Offset(prev.control2X, prev.control2Y),
                                    Offset(node.control1X, node.control1Y),
                                    node.getAnchor(),
                                    shape.id,
                                    isCurve = true,
                                    handleType = node.handleType
                                ))
                            } else {
                                val start = prev.getAnchor()
                                val end = node.getAnchor()
                                segments.add(BezierSegment(
                                    start, 
                                    start + (end - start) / 3f, 
                                    start + (end - start) * 2f / 3f, 
                                    end, 
                                    shape.id,
                                    isCurve = false,
                                    handleType = node.handleType
                                ))
                            }
                        }
                    }
                }
            }
            else -> {}
        }
        val rotationAngle = shape.rotationAngle
        if (rotationAngle != 0f) {
            val bounds = shape.getBoundingBox()
            val cx = (bounds.left + bounds.right) / 2f
            val cy = (bounds.top + bounds.bottom) / 2f
            
            fun rotatePoint(p: Offset): Offset {
                val rad = Math.toRadians(rotationAngle.toDouble())
                val cos = kotlin.math.cos(rad).toFloat()
                val sin = kotlin.math.sin(rad).toFloat()
                val dx = p.x - cx
                val dy = p.y - cy
                return Offset(
                    cx + (dx * cos - dy * sin),
                    cy + (dx * sin + dy * cos)
                )
            }
            
            return segments.map { seg ->
                seg.copy(
                    p0 = rotatePoint(seg.p0),
                    p1 = rotatePoint(seg.p1),
                    p2 = rotatePoint(seg.p2),
                    p3 = rotatePoint(seg.p3)
                )
            }
        }
        return segments
    }

    private fun buildNodesFromSegments(reconstructedSegments: List<BezierSegment>, isFirstContour: Boolean): List<BezierNode> {
        val n = reconstructedSegments.size
        if (n == 0) return emptyList()
        
        val contourNodes = mutableListOf<BezierNode>()
        for (i in 0 until n) {
            val currentSeg = reconstructedSegments[i]
            val prevSeg = reconstructedSegments[(i - 1 + n) % n]
            
            var tIn = prevSeg.p3 - prevSeg.p2
            if (kotlin.math.hypot(tIn.x, tIn.y) < 0.1f) {
                tIn = prevSeg.p3 - prevSeg.p0
            }
            var tOut = currentSeg.p1 - currentSeg.p0
            if (kotlin.math.hypot(tOut.x, tOut.y) < 0.1f) {
                tOut = currentSeg.p3 - currentSeg.p0
            }
            
            val lenIn = kotlin.math.hypot(tIn.x, tIn.y)
            val lenOut = kotlin.math.hypot(tOut.x, tOut.y)
            
            var isCorner = false
            if (!prevSeg.isCurve || !currentSeg.isCurve) {
                isCorner = true
            } else if (lenIn > 0.1f && lenOut > 0.1f) {
                val uIn = Offset(tIn.x / lenIn, tIn.y / lenIn)
                val uOut = Offset(tOut.x / lenOut, tOut.y / lenOut)
                val dot = uIn.x * uOut.x + uIn.y * uOut.y
                if (dot < 0.95f) { // Sharp corner (> ~18 degrees direction change)
                    isCorner = true
                }
            } else {
                isCorner = true
            }
            
            val isCurveJunction = prevSeg.isCurve || currentSeg.isCurve
            val nodeType = if (isCorner) "BEBAS" else "HALUS"
            
            val c1 = if (prevSeg.isCurve) Offset(prevSeg.p2.x, prevSeg.p2.y) else Offset(currentSeg.p0.x, currentSeg.p0.y)
            val c2 = if (currentSeg.isCurve) Offset(currentSeg.p1.x, currentSeg.p1.y) else Offset(currentSeg.p0.x, currentSeg.p0.y)
            
            contourNodes.add(BezierNode(
                anchorX = currentSeg.p0.x,
                anchorY = currentSeg.p0.y,
                isCurve = isCurveJunction,
                control1X = c1.x,
                control1Y = c1.y,
                control2X = c2.x,
                control2Y = c2.y,
                isMoveTo = (i == 0 && !isFirstContour),
                nodeType = nodeType
            ))
        }
        return contourNodes
    }

    /**
     * Mempersiapkan path (Bake Path) agar kurva visual seperti border radius atau lengkungan 
     * diubah jadi instruksi path murni. Hal ini mencegah hilangnya sudut lengkung (squircle) 
     * karena bug Path.op() Android pada bentuk primitif round rect.
     */
    private fun preparePathForBoolean(sourcePath: Path): Path {
        val androidPath = sourcePath.asAndroidPath()
        val flattenedPath = android.graphics.Path()
        val pm = android.graphics.PathMeasure(androidPath, false)
        flattenedPath.addPath(androidPath)
        return flattenedPath.asComposePath()
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

    private fun fitCurveRecursive(
        points: List<Offset>,
        start: Int,
        end: Int,
        tolerance: Float = 0.35f,
        depth: Int = 0
    ): List<BezierSegment> {
        if (end - start < 1) return emptyList()
        if (end - start == 1) {
            val pStart = points[start]
            val pEnd = points[end]
            return listOf(BezierSegment(pStart, pStart + (pEnd - pStart) / 3f, pStart + (pEnd - pStart) * 2f / 3f, pEnd, "", false))
        }

        val pStart = points[start]
        val pEnd = points[end]
        val dx = pEnd.x - pStart.x
        val dy = pEnd.y - pStart.y
        val chordLen = kotlin.math.hypot(dx, dy)

        // Prevent infinite fitting/NaN instability on closed loops or degenerated start/end coordinates
        if (chordLen < 2.0f && (end - start) > 4) {
            val midIdx = (start + end) / 2
            val leftResult = fitCurveRecursive(points, start, midIdx, tolerance, depth + 1)
            val rightResult = fitCurveRecursive(points, midIdx, end, tolerance, depth + 1)
            return leftResult + rightResult
        }

        // 1. Check if the segment is a straight line - slightly wider tolerance for collinearity
        var maxDistToChord = 0f
        var worstIdx = -1

        for (i in start + 1 until end) {
            val pt = points[i]
            val dist = if (chordLen > 0.01f) {
                val tProj = (((pt.x - pStart.x) * dx + (pt.y - pStart.y) * dy) / (chordLen * chordLen)).coerceIn(0f, 1f)
                val projPt = Offset(pStart.x + tProj * dx, pStart.y + tProj * dy)
                kotlin.math.hypot(pt.x - projPt.x, pt.y - projPt.y)
            } else {
                kotlin.math.hypot(pt.x - pStart.x, pt.y - pStart.y)
            }
            if (dist > maxDistToChord) {
                maxDistToChord = dist
                worstIdx = i
            }
        }

        if (maxDistToChord < 0.4f) {
            return listOf(BezierSegment(pStart, pStart + (pEnd - pStart) / 3f, pStart + (pEnd - pStart) * 2f / 3f, pEnd, "", false))
        }

        // 2. Try fitting an optimized cubic Bezier using mathematical Least-Squares Handle solver with Chord Length parameterization
        val nPoints = end - start + 1
        
        // Accurate chord length calculation for high geometric compatibility
        val chordLengths = FloatArray(nPoints)
        chordLengths[0] = 0f
        for (i in 1 until nPoints) {
            val d = kotlin.math.hypot(points[start + i].x - points[start + i - 1].x, points[start + i].y - points[start + i - 1].y)
            chordLengths[i] = chordLengths[i - 1] + d
        }
        val totalLength = chordLengths[nPoints - 1]

        // Better tangent estimations using points closer to ends to reduce multi-node oversplit bias
        val step = minOf((end - start) / 10 + 1, 3)
        val dStart = points[start + step] - pStart
        val lenStart = kotlin.math.hypot(dStart.x, dStart.y)
        val uA = if (lenStart > 0.01f) Offset(dStart.x / lenStart, dStart.y / lenStart) else {
            if (chordLen > 0.01f) Offset(dx / chordLen, dy / chordLen) else Offset(1f, 0f)
        }
        
        val dEnd = pEnd - points[end - step]
        val lenEnd = kotlin.math.hypot(dEnd.x, dEnd.y)
        val uB = if (lenEnd > 0.01f) Offset(dEnd.x / lenEnd, dEnd.y / lenEnd) else {
            if (chordLen > 0.01f) Offset(dx / chordLen, dy / chordLen) else Offset(1f, 0f)
        }

        var A11 = 0f
        var A12 = 0f
        var A22 = 0f
        var B1 = 0f
        var B2 = 0f
        
        val cosTangents = uA.x * uB.x + uA.y * uB.y
        
        for (i in 0 until nPoints) {
            val idx = start + i
            val pt = points[idx]
            val t = if (totalLength > 0.1f) chordLengths[i] / totalLength else i.toFloat() / (nPoints - 1).toFloat()
            val mt = 1f - t
            
        val b0 = mt * mt * mt
        val b1 = 3f * mt * mt * t
        val b2 = 3f * mt * t * t
        val b3 = t * t * t

        val a1x = b1 * uA.x;  val a1y = b1 * uA.y
        val a2x = b2 * uB.x;  val a2y = b2 * uB.y

        val ex = pt.x - (b0 * pStart.x + b3 * pEnd.x)
        val ey = pt.y - (b0 * pStart.y + b3 * pEnd.y)

        A11  += a1x * a1x + a1y * a1y
        A12  += a1x * a2x + a1y * a2y
        A22  += a2x * a2x + a2y * a2y
        B1   += a1x * ex  + a1y * ey
        B2   += a2x * ex  + a2y * ey
        }
        
        val det = A11 * A22 - A12 * A12
        var k1 = chordLen / 3f
        var k2 = chordLen / 3f
        
        val minH = 0.001f * chordLen
        val maxH = 3.0f * chordLen  // ← was 1.5f
        if (det > 1e-5f) {
            val sol1 = (B1 * A22 - B2 * A12) / det
            val sol2 = (A11 * B2 - A12 * B1) / det
            if (sol1 in minH..maxH && sol2 in minH..maxH) {
                k1 = sol1
                k2 = sol2
            }
        }
        
        val p1 = pStart + uA * k1
        val p2 = pEnd - uB * k2

        // Verify suitability of current Bezier fit using chord-length parameter value for precision
        var maxBezierError = 0f
        var worstBezierIdx = -1

        for (i in start + 1 until end) {
            val pt = points[i]
            val t = if (totalLength > 0.1f) chordLengths[i - start] / totalLength else (i - start).toFloat() / (end - start).toFloat()
            val mt = 1f - t
            val mt2 = mt * mt
            val mt3 = mt2 * mt
            val t2 = t * t
            val t3 = t2 * t

            val bezPt = Offset(
                mt3 * pStart.x + 3f * mt2 * t * p1.x + 3f * mt * t2 * p2.x + t3 * pEnd.x,
                mt3 * pStart.y + 3f * mt2 * t * p1.y + 3f * mt * t2 * p2.y + t3 * pEnd.y
            )

            val error = kotlin.math.hypot(pt.x - bezPt.x, pt.y - bezPt.y)
            if (error > maxBezierError) {
                maxBezierError = error
                worstBezierIdx = i
            }
        }

        val maxAllowedError = tolerance * (1f + depth * 0.15f)
        if (maxBezierError < maxAllowedError) {
            return listOf(BezierSegment(pStart, p1, p2, pEnd, "", true))
        }

        val finalSplitIdx = if (worstBezierIdx != -1) worstBezierIdx else (start + end) / 2
        val leftResult = fitCurveRecursive(points, start, finalSplitIdx, tolerance, depth + 1)
        val rightResult = fitCurveRecursive(points, finalSplitIdx, end, tolerance, depth + 1)

        return leftResult + rightResult
    }

    /**
     * Reconstructs custom BezierNode lists from any input Android Path.
     * Uses highly optimized recursive curve fitting to preserve sharp geometric corners and smooth arcs.
     */
    private fun reconstructBezierNodesFromAndroidPath(androidPath: android.graphics.Path, origSegments: List<BezierSegment>): List<BezierNode> {
        val pm = android.graphics.PathMeasure(androidPath, false)
        val nodes = mutableListOf<BezierNode>()

        var isFirstMoveTo = true
        var hasContour = true
        while (hasContour) {
            val len = pm.length
            if (len > 0.1f) {
                val numSamples = (len / 1.0f).toInt().coerceIn(300, 700)
                val step = len / numSamples
                val rawPoints = mutableListOf<Offset>()
                val pos = FloatArray(2)
                val tan = FloatArray(2)
                
                for (j in 0..numSamples) {
                    if (pm.getPosTan(j * step, pos, tan)) {
                        rawPoints.add(Offset(pos[0], pos[1]))
                    }
                }

                val uniquePoints = mutableListOf<Offset>()
                for (p in rawPoints) {
                    if (uniquePoints.isEmpty() || kotlin.math.hypot(p.x - uniquePoints.last().x, p.y - uniquePoints.last().y) > 0.05f) {
                        uniquePoints.add(p)
                    }
                }

                if (uniquePoints.size >= 2) {
                    // Match each point to original boundaries with tolerance to preserve geometric perfect curves
                    val processedPoints = uniquePoints.map { q ->
                        var bestSegIdx = -1
                        var bestT = 0f
                        var minDist = Float.MAX_VALUE
                        for (idx in origSegments.indices) {
                            val seg = origSegments[idx]
                            
                            val minX = minOf(seg.p0.x, seg.p1.x, seg.p2.x, seg.p3.x) - 4f
                            val maxX = maxOf(seg.p0.x, seg.p1.x, seg.p2.x, seg.p3.x) + 4f
                            val minY = minOf(seg.p0.y, seg.p1.y, seg.p2.y, seg.p3.y) - 4f
                            val maxY = maxOf(seg.p0.y, seg.p1.y, seg.p2.y, seg.p3.y) + 4f
                            
                            if (q.x in minX..maxX && q.y in minY..maxY) {
                                val (t, dist) = seg.findClosestT(q)
                                if (dist < minDist) {
                                    minDist = dist
                                    bestSegIdx = idx
                                    bestT = t
                                }
                            }
                        }
                        if (minDist < 4.0f && bestSegIdx != -1) {
                            ProcessedPoint(q, bestSegIdx, bestT, minDist)
                        } else {
                            ProcessedPoint(q, -1, 0f, 1000f)
                        }
                    }

                    // Group sequential points into blocks with sharp corner detection
                    val blocks = mutableListOf<MutableList<ProcessedPoint>>()
                    if (processedPoints.isNotEmpty()) {
                        var currentBlock = mutableListOf<ProcessedPoint>()
                        currentBlock.add(processedPoints.first())
                        for (i in 1 until processedPoints.size) {
                            val prev = processedPoints[i - 1]
                            val curr = processedPoints[i]
                            
                            val isSameSegment = curr.segmentIndex != -1 && prev.segmentIndex != -1 && curr.segmentIndex == prev.segmentIndex
                            
                            // Check for sharp corner at index i in processedPoints
                            var isSharpCorner = false
                            if (!isSameSegment) {
                                val kStep = 5
                                if (i in kStep until processedPoints.size - kStep) {
                                    val pPrev = processedPoints[i - kStep].point
                                    val pCurr = processedPoints[i].point
                                    val pNext = processedPoints[i + kStep].point
                                    val v1 = pCurr - pPrev
                                    val v2 = pNext - pCurr
                                    val len1 = kotlin.math.hypot(v1.x, v1.y)
                                    val len2 = kotlin.math.hypot(v2.x, v2.y)
                                    if (len1 > 0.5f && len2 > 0.5f) {
                                        val dot = (v1.x * v2.x + v1.y * v2.y) / (len1 * len2)
                                        // Direction changes sharply (more than ~60 degrees)
                                        if (dot < 0.5f) {
                                            isSharpCorner = true
                                        }
                                    }
                                }
                            }
                            
                            val canContinue = if (isSharpCorner) {
                                false
                            } else if (isSameSegment) {
                                kotlin.math.abs(curr.t - prev.t) < 0.85f
                            } else if (curr.segmentIndex == -1 && prev.segmentIndex == -1) {
                                true
                            } else {
                                false
                            }
                            
                            if (canContinue) {
                                currentBlock.add(curr)
                            } else {
                                blocks.add(currentBlock)
                                currentBlock = mutableListOf(curr)
                            }
                        }
                        if (currentBlock.isNotEmpty()) {
                            blocks.add(currentBlock)
                        }
                        
                        // Merge wrap-around start/end blocks
                        if (blocks.size > 1) {
                            val firstBlock = blocks.first()
                            val lastBlock = blocks.last()
                            if (firstBlock.first().segmentIndex != -1 && firstBlock.first().segmentIndex == lastBlock.last().segmentIndex) {
                                val tFirstFirst = firstBlock.first().t
                                val tLastLast = lastBlock.last().t
                                if (kotlin.math.abs(tFirstFirst - tLastLast) < 0.85f) {
                                    firstBlock.addAll(0, lastBlock)
                                    blocks.removeAt(blocks.size - 1)
                                }
                            }
                        }
                    }

                    // Reconstruct perfect curves/lines from blocks
                    val reconstructedSegments = mutableListOf<BezierSegment>()
                    for (block in blocks) {
                        if (block.isEmpty()) continue
                        val first = block.first()
                        val last = block.last()
                        
                        if (first.segmentIndex != -1 && block.size >= 2) {
                            val origSeg = origSegments[first.segmentIndex]
                            val tStart = first.t
                            val tEnd = last.t
                            
                            if (kotlin.math.abs(tStart - tEnd) > 0.01f) {
                                val splitSeg = if (tStart > tEnd) {
                                    val spl = origSeg.split(tEnd, tStart)
                                    BezierSegment(spl.p3, spl.p2, spl.p1, spl.p0, spl.originalShapeId, spl.isCurve, spl.handleType)
                                } else {
                                    origSeg.split(tStart, tEnd)
                                }
                                reconstructedSegments.add(splitSeg)
                            } else {
                                val pStart = first.point
                                val pEnd = last.point
                                reconstructedSegments.add(BezierSegment(
                                    pStart,
                                    pStart + (pEnd - pStart) / 3f,
                                    pStart + (pEnd - pStart) * 2f / 3f,
                                    pEnd,
                                    origSeg.originalShapeId,
                                    origSeg.isCurve,
                                    origSeg.handleType
                                ))
                            }
                        } else {
                            val pointsInBlock = block.map { it.point }
                            if (pointsInBlock.size >= 2) {
                                val fitted = fitCurveRecursive(pointsInBlock, 0, pointsInBlock.size - 1, tolerance = 1.8f, depth = 0)
                                reconstructedSegments.addAll(fitted)
                            }
                        }
                    }

                    // Node Cleaning: Filter out redundant overlapping/very short segments near junctions
                    val activeSegments = reconstructedSegments.filter { seg ->
                        kotlin.math.hypot(seg.p3.x - seg.p0.x, seg.p3.y - seg.p0.y) > 0.15f
                    }.toMutableList()

                    // Simplify and merge adjacent segments to enforce node minimization, preventing clustering
                    val simplifiedSegments = mutableListOf<BezierSegment>()
                    if (activeSegments.isNotEmpty()) {
                        var current = activeSegments[0]
                        var i = 1
                        while (i < activeSegments.size) {
                            val next = activeSegments[i]
                            
                            val lenCurr = kotlin.math.hypot(current.p3.x - current.p0.x, current.p3.y - current.p0.y)
                            val lenNext = kotlin.math.hypot(next.p3.x - next.p0.x, next.p3.y - next.p0.y)
                            
                            val isCurrTooShort = lenCurr < 2.5f
                            val isNextTooShort = lenNext < 2.5f
                            
                            var isCollinear = false
                            if (!current.isCurve && !next.isCurve) {
                                val d1 = current.p3 - current.p0
                                val d2 = next.p3 - next.p0
                                val len1 = kotlin.math.hypot(d1.x, d1.y)
                                val len2 = kotlin.math.hypot(d2.x, d2.y)
                                if (len1 > 0.1f && len2 > 0.1f) {
                                    val dot = (d1.x * d2.x + d1.y * d2.y) / (len1 * len2)
                                    if (dot > 0.995f) {
                                        isCollinear = true
                                    }
                                }
                            }
                            
                            var isMergeableCurve = false
                            if (current.isCurve && next.isCurve) {
                                // Instead of merging all curves from the same originalShapeId,
                                // we only merge if the tangents match perfectly (very smooth join)
                                val tIn = current.p3 - current.p2
                                val tOut = next.p1 - next.p0
                                val lenTIn = kotlin.math.hypot(tIn.x, tIn.y)
                                val lenTOut = kotlin.math.hypot(tOut.x, tOut.y)
                                if (lenTIn > 0.1f && lenTOut > 0.1f) {
                                    val uIn = Offset(tIn.x / lenTIn, tIn.y / lenTIn)
                                    val uOut = Offset(tOut.x / lenTOut, tOut.y / lenTOut)
                                    val dot = uIn.x * uOut.x + uIn.y * uOut.y
                                    // Only merge if extremely collinear and dot product is > 0.99
                                    if (dot > 0.995f && isCurrTooShort && isNextTooShort) {
                                        isMergeableCurve = true
                                    }
                                }
                            }
                            
                            if (isCurrTooShort || isNextTooShort || isCollinear || isMergeableCurve) {
                                val mergedP0 = current.p0
                                val mergedP3 = next.p3
                                
                                if (!current.isCurve && !next.isCurve && isCollinear) {
                                    val mergedP1 = mergedP0 + (mergedP3 - mergedP0) / 3f
                                    val mergedP2 = mergedP0 + (mergedP3 - mergedP0) * 2f / 3f
                                    current = BezierSegment(mergedP0, mergedP1, mergedP2, mergedP3, current.originalShapeId, false)
                                } else {
                                    // Use least-squares fitting on sampled points to merge curves smoothly and perfectly
                                    val kPoints = mutableListOf<Offset>()
                                    val samples = 12
                                    for (j in 0..samples) {
                                        val t = j.toFloat() / samples.toFloat()
                                        kPoints.add(current.getPoint(t))
                                    }
                                    for (j in 1..samples) {
                                        val t = j.toFloat() / samples.toFloat()
                                        kPoints.add(next.getPoint(t))
                                    }
                                    val fitted = fitCurveRecursive(kPoints, 0, kPoints.size - 1, tolerance = 10.0f, depth = 0)
                                    if (fitted.size == 1) {
                                        val bestFit = fitted[0]
                                        current = BezierSegment(mergedP0, bestFit.p1, bestFit.p2, mergedP3, current.originalShapeId, true)
                                    } else {
                                        val tIn = current.p1 - current.p0
                                        val tOut = next.p3 - next.p2
                                        val lenTIn = kotlin.math.hypot(tIn.x, tIn.y)
                                        val lenTOut = kotlin.math.hypot(tOut.x, tOut.y)
                                        val uIn = if (lenTIn > 0.1f) Offset(tIn.x / lenTIn, tIn.y / lenTIn) else (mergedP3 - mergedP0) / 3f
                                        val uOut = if (lenTOut > 0.1f) Offset(tOut.x / lenTOut, tOut.y / lenTOut) else (mergedP3 - mergedP0) / 3f
                                        val chord = kotlin.math.hypot(mergedP3.x - mergedP0.x, mergedP3.y - mergedP0.y)
                                        val mergedP1 = mergedP0 + uIn * (chord / 3f)
                                        val mergedP2 = mergedP3 - uOut * (chord / 3f)
                                        current = BezierSegment(mergedP0, mergedP1, mergedP2, mergedP3, current.originalShapeId, true)
                                    }
                                }
                                i++
                            } else {
                                simplifiedSegments.add(current)
                                current = next
                                i++
                            }
                        }
                        simplifiedSegments.add(current)
                    }

                    // Closed loop wrap-around simplify pass
                    val finalSegments = simplifiedSegments.toMutableList()
                    if (finalSegments.size > 1) {
                        var changed = true
                        while (changed && finalSegments.size > 1) {
                            changed = false
                            val last = finalSegments.last()
                            val first = finalSegments.first()
                            
                            val lenLast = kotlin.math.hypot(last.p3.x - last.p0.x, last.p3.y - last.p0.y)
                            val lenFirst = kotlin.math.hypot(first.p3.x - first.p0.x, first.p3.y - first.p0.y)
                            
                            val isLastTooShort = lenLast < 2.5f
                            val isFirstTooShort = lenFirst < 2.5f
                            
                            var isCollinear = false
                            if (!last.isCurve && !first.isCurve) {
                                val d1 = last.p3 - last.p0
                                val d2 = first.p3 - first.p0
                                val len1 = kotlin.math.hypot(d1.x, d1.y)
                                val len2 = kotlin.math.hypot(d2.x, d2.y)
                                if (len1 > 0.1f && len2 > 0.1f) {
                                    val dot = (d1.x * d2.x + d1.y * d2.y) / (len1 * len2)
                                    if (dot > 0.995f) {
                                        isCollinear = true
                                    }
                                }
                            }
                            
                            var isMergeableCurve = false
                            if (last.isCurve && first.isCurve) {
                                val tIn = last.p3 - last.p2
                                val tOut = first.p1 - first.p0
                                val lenTIn = kotlin.math.hypot(tIn.x, tIn.y)
                                val lenTOut = kotlin.math.hypot(tOut.x, tOut.y)
                                if (lenTIn > 0.1f && lenTOut > 0.1f) {
                                    val uIn = Offset(tIn.x / lenTIn, tIn.y / lenTIn)
                                    val uOut = Offset(tOut.x / lenTOut, tOut.y / lenTOut)
                                    val dot = uIn.x * uOut.x + uIn.y * uOut.y
                                    if (dot > 0.995f && isLastTooShort && isFirstTooShort) {
                                        isMergeableCurve = true
                                    }
                                }
                            }
                            
                            if (isLastTooShort || isFirstTooShort || isCollinear || isMergeableCurve) {
                                val mergedP0 = last.p0
                                val mergedP3 = first.p3
                                
                                if (!last.isCurve && !first.isCurve && isCollinear) {
                                    val mergedP1 = mergedP0 + (mergedP3 - mergedP0) / 3f
                                    val mergedP2 = mergedP0 + (mergedP3 - mergedP0) * 2f / 3f
                                    finalSegments[0] = BezierSegment(mergedP0, mergedP1, mergedP2, mergedP3, last.originalShapeId, false)
                                } else {
                                    // Use least-squares fitting on sampled points
                                    val kPoints = mutableListOf<Offset>()
                                    val samples = 12
                                    for (j in 0..samples) {
                                        val t = j.toFloat() / samples.toFloat()
                                        kPoints.add(last.getPoint(t))
                                    }
                                    for (j in 1..samples) {
                                        val t = j.toFloat() / samples.toFloat()
                                        kPoints.add(first.getPoint(t))
                                    }
                                    val fitted = fitCurveRecursive(kPoints, 0, kPoints.size - 1, tolerance = 10.0f, depth = 0)
                                    if (fitted.size == 1) {
                                        val bestFit = fitted[0]
                                        finalSegments[0] = BezierSegment(mergedP0, bestFit.p1, bestFit.p2, mergedP3, last.originalShapeId, true)
                                    } else {
                                        val tIn = last.p1 - last.p0
                                        val tOut = first.p3 - first.p2
                                        val lenTIn = kotlin.math.hypot(tIn.x, tIn.y)
                                        val lenTOut = kotlin.math.hypot(tOut.x, tOut.y)
                                        val uIn = if (lenTIn > 0.1f) Offset(tIn.x / lenTIn, tIn.y / lenTIn) else (mergedP3 - mergedP0) / 3f
                                        val uOut = if (lenTOut > 0.1f) Offset(tOut.x / lenTOut, tOut.y / lenTOut) else (mergedP3 - mergedP0) / 3f
                                        val chord = kotlin.math.hypot(mergedP3.x - mergedP0.x, mergedP3.y - mergedP0.y)
                                        val mergedP1 = mergedP0 + uIn * (chord / 3f)
                                        val mergedP2 = mergedP3 - uOut * (chord / 3f)
                                        finalSegments[0] = BezierSegment(mergedP0, mergedP1, mergedP2, mergedP3, last.originalShapeId, true)
                                    }
                                }
                                finalSegments.removeAt(finalSegments.size - 1)
                                changed = true
                            }
                        }
                    }

                    val n = finalSegments.size
                    if (n >= 2) {
                        val finalizedSegments = finalSegments.toMutableList()
                        for (i in 0 until n) {
                            val current = finalizedSegments[i]
                            val next = finalizedSegments[(i + 1) % n]
                            val mid = Offset((current.p3.x + next.p0.x) / 2f, (current.p3.y + next.p0.y) / 2f)
                            finalizedSegments[i] = current.copy(p3 = mid)
                            finalizedSegments[(i + 1) % n] = next.copy(p0 = mid)
                        }
                        val contourNodes = buildNodesFromSegments(finalizedSegments, isFirstContour = isFirstMoveTo)
                        nodes.addAll(contourNodes)
                    } else if (n == 1) {
                        val contourNodes = buildNodesFromSegments(finalSegments, isFirstContour = isFirstMoveTo)
                        nodes.addAll(contourNodes)
                    }
                    isFirstMoveTo = false
                }
            }
            hasContour = pm.nextContour()
        }
        return nodes
    }

    private fun segmentsToClosedNodes(segments: List<BezierSegment>): List<BezierNode> {
        if (segments.isEmpty()) return emptyList()
        val nodes = mutableListOf<BezierNode>()
        for (i in segments.indices) {
            val seg = segments[i]
            val prevSeg = if (i > 0) segments[i-1] else segments.last()
            
            nodes.add(BezierNode(
                anchorX = seg.p0.x, anchorY = seg.p0.y,
                isCurve = true,
                control1X = prevSeg.p2.x, control1Y = prevSeg.p2.y,
                control2X = seg.p1.x, control2Y = seg.p1.y,
                isMoveTo = if (i == 0) true else false
            ))
        }
        return nodes
    }

    fun performStrokeExpansion(shape: VectorShape): VectorShape? {
        val composePath = shape.asComposePath()
        val androidPath = composePath.asAndroidPath()
        val paint = android.graphics.Paint().apply {
            style       = android.graphics.Paint.Style.STROKE
            strokeWidth = shape.strokeWidth
            strokeCap   = android.graphics.Paint.Cap.ROUND
            strokeJoin  = android.graphics.Paint.Join.ROUND
        }
        val dstPath = android.graphics.Path()
        paint.getFillPath(androidPath, dstPath)
        val nodes = reconstructBezierNodesFromAndroidPath(dstPath, emptyList())
        if (nodes.isEmpty()) return null
        return shape.copy(
            type              = ShapeType.BEZIER_PATH,
            bezierNodes       = nodes,
            hasFill           = true,
            fillColorHex      = shape.strokeColorHex,
            fillAlpha         = shape.strokeAlpha,
            strokeWidth       = 0f,
            strokeAlpha       = 0f,
            rotationAngle     = 0f,
            isPathClosed      = true,
            customCornerRadii = emptyList(),
            radiusTL = 0f, radiusTR = 0f, radiusBR = 0f, radiusBL = 0f
        )
    }

    fun expandSelectedShapes(expandFill: Boolean, expandStroke: Boolean) {
        val selected = shapes.filter { selectedShapeIds.contains(it.id) }
        if (selected.isEmpty()) return
        pushToUndoStack()

        val newShapesList = mutableListOf<VectorShape>()
        val resultingSelectedIds = mutableSetOf<String>()

        for (shape in shapes) {
            if (selectedShapeIds.contains(shape.id)) {
                val hasStroke = shape.strokeWidth > 0f && shape.strokeAlpha > 0f
                val hasFill = shape.hasFill

                if (expandStroke && hasStroke) {
                    val strokeShape = performStrokeExpansion(shape)
                    if (strokeShape != null) {
                        if (hasFill) {
                            // Split: keep original fill as a separate stroke-free shape, and add the expanded stroke
                            val fillShape = shape.copy(
                                id = java.util.UUID.randomUUID().toString(),
                                strokeWidth = 0f,
                                strokeAlpha = 0f
                            )
                            newShapesList.add(fillShape)
                            resultingSelectedIds.add(fillShape.id)

                            val finalStrokeShape = strokeShape.copy(
                                id = java.util.UUID.randomUUID().toString()
                            )
                            newShapesList.add(finalStrokeShape)
                            resultingSelectedIds.add(finalStrokeShape.id)
                        } else {
                            newShapesList.add(strokeShape)
                            resultingSelectedIds.add(strokeShape.id)
                        }
                    } else {
                        newShapesList.add(shape)
                        resultingSelectedIds.add(shape.id)
                    }
                } else if (expandFill && hasFill) {
                    val fillShape = shape.copy(
                        strokeWidth = 0f,
                        strokeAlpha = 0f
                    )
                    newShapesList.add(fillShape)
                    resultingSelectedIds.add(fillShape.id)
                } else {
                    newShapesList.add(shape)
                    resultingSelectedIds.add(shape.id)
                }
            } else {
                newShapesList.add(shape)
            }
        }

        shapes = newShapesList.mapIndexed { idx, s -> s.copy(layerOrder = idx) }
        selectedShapeIds = resultingSelectedIds
        if (resultingSelectedIds.size == 1) {
            selectedShapeId = resultingSelectedIds.first()
        } else {
            selectedShapeId = null
        }
    }

    fun expandStroke() {
        expandSelectedShapes(expandFill = false, expandStroke = true)
    }

    private fun safePathOp(path1: Path, path2: Path, op: PathOperation): Path {
        val p1 = Path().apply { addPath(path1) }
        val p2 = Path().apply { addPath(path2) }
        val scaleUp = android.graphics.Matrix().apply { setScale(1024f, 1024f) }
        p1.asAndroidPath().transform(scaleUp)
        p2.asAndroidPath().transform(scaleUp)
        
        val nextPath = Path()
        val success = nextPath.op(p1, p2, op)
        if (success) {
            val scaleDown = android.graphics.Matrix().apply { setScale(1f/1024f, 1f/1024f) }
            nextPath.asAndroidPath().transform(scaleDown)
            return nextPath
        }
        return Path().apply { addPath(path1) }
    }

    private fun getTransformedPath(shape: com.example.model.VectorShape): Path {
        val rawPath = shape.copy(rotationAngle = 0f).asComposePath()
        val matrix = android.graphics.Matrix().apply {
            if (shape.rotationAngle != 0f) {
                val bounds = shape.getBoundingBox()
                val cx = (bounds.left + bounds.right) / 2f
                val cy = (bounds.top + bounds.bottom) / 2f
                postRotate(shape.rotationAngle, cx, cy)
            }
        }
        val path = Path().apply { addPath(rawPath) }
        path.asAndroidPath().transform(matrix)
        return path
    }

    private fun performDivideBooleanOperation(sortedSelected: List<com.example.model.VectorShape>, allWorldSegments: List<BezierSegment>) {
        var currentFragments = mutableListOf(getTransformedPath(sortedSelected[0]))
        
        for (i in 1 until sortedSelected.size) {
            val shapePath = getTransformedPath(sortedSelected[i])
            val newFragments = mutableListOf<Path>()
            
            for (fragment in currentFragments) {
                val diff = safePathOp(fragment, shapePath, PathOperation.Difference)
                if (!diff.isEmpty) newFragments.add(diff)
                
                val intersect = safePathOp(fragment, shapePath, PathOperation.Intersect)
                if (!intersect.isEmpty) newFragments.add(intersect)
            }
            
            // Subtractor leftover
            var currentShapeLeftover = Path().apply { addPath(shapePath) }
            for (fragment in currentFragments) {
                currentShapeLeftover = safePathOp(currentShapeLeftover, fragment, PathOperation.Difference)
            }
            if (!currentShapeLeftover.isEmpty) newFragments.add(currentShapeLeftover)
            
            currentFragments = newFragments
        }
        
        val newShapes = mutableListOf<com.example.model.VectorShape>()
        for (fragment in currentFragments) {
            val nodes = reconstructBezierNodesFromAndroidPath(fragment.asAndroidPath(), allWorldSegments)
            if (nodes.isNotEmpty()) {
                val targetStyleShape = sortedSelected.last()
                newShapes.add(VectorShape(
                    id = java.util.UUID.randomUUID().toString(),
                    name = "Divide Fragment",
                    type = ShapeType.BEZIER_PATH,
                    bezierNodes = nodes,
                    isPathClosed = true,
                    strokeColorHex = targetStyleShape.strokeColorHex,
                    strokeWidth = targetStyleShape.strokeWidth,
                    strokeAlpha = targetStyleShape.strokeAlpha,
                    hasFill = targetStyleShape.hasFill,
                    fillColorHex = targetStyleShape.fillColorHex,
                    fillAlpha = targetStyleShape.fillAlpha,
                    layerOrder = targetStyleShape.layerOrder,
                    rotationAngle = 0f
                ))
            }
        }
        
        val selectedIds = sortedSelected.map { it.id }.toSet()
        shapes = shapes.filter { !selectedIds.contains(it.id) } + newShapes
        selectedShapeId = null
        selectedShapeIds = emptySet()
    }

    private fun performTrimBooleanOperation(sortedSelected: List<com.example.model.VectorShape>, allWorldSegments: List<BezierSegment>) {
        val newShapes = mutableListOf<com.example.model.VectorShape>()
        var accumulator = Path() // Union of shapes above
        for (i in sortedSelected.indices.reversed()) {
            val shapePath = getTransformedPath(sortedSelected[i])
            val trimmed = if (accumulator.isEmpty) {
                shapePath
            } else {
                safePathOp(shapePath, accumulator, PathOperation.Difference)
            }
            
            if (!trimmed.isEmpty) {
                val nodes = reconstructBezierNodesFromAndroidPath(trimmed.asAndroidPath(), allWorldSegments)
                if (nodes.isNotEmpty()) {
                    val targetStyleShape = sortedSelected[i]
                    newShapes.add(VectorShape(
                        id = java.util.UUID.randomUUID().toString(),
                        name = "Trim Fragment",
                        type = ShapeType.BEZIER_PATH,
                        bezierNodes = nodes,
                        isPathClosed = true,
                        strokeColorHex = targetStyleShape.strokeColorHex,
                        strokeWidth = targetStyleShape.strokeWidth,
                        strokeAlpha = targetStyleShape.strokeAlpha,
                        hasFill = targetStyleShape.hasFill,
                        fillColorHex = targetStyleShape.fillColorHex,
                        fillAlpha = targetStyleShape.fillAlpha,
                        layerOrder = targetStyleShape.layerOrder,
                        rotationAngle = 0f
                    ))
                }
            }
            
            if (!shapePath.isEmpty) {
                accumulator = if (accumulator.isEmpty) shapePath else safePathOp(accumulator, shapePath, PathOperation.Union)
            }
        }
        
        val selectedIds = sortedSelected.map { it.id }.toSet()
        shapes = shapes.filter { !selectedIds.contains(it.id) } + newShapes.reversed()
        selectedShapeId = null
        selectedShapeIds = emptySet()
    }

    fun applyBooleanOperation(opType: String) {
        val selected = shapes.filter { selectedShapeIds.contains(it.id) }
        if (selected.size < 2) return
        pushToUndoStack()

        // 1. Force permanent conversion to BEZIER_PATH using 8-point/multi-point node expansion
        shapes = shapes.map { shape ->
            if (selectedShapeIds.contains(shape.id)) {
                if (shape.type == ShapeType.RECTANGLE || shape.type == ShapeType.POLYGON || shape.type == ShapeType.STAR) {
                    val bezierNodes = shape.convertCornerPointsToEightBezierNodes()
                    shape.copy(
                        type = ShapeType.BEZIER_PATH,
                        bezierNodes = bezierNodes,
                        customCornerRadii = emptyList(),
                        isPathClosed = true,
                        radiusTL = 0f,
                        radiusTR = 0f,
                        radiusBR = 0f,
                        radiusBL = 0f
                    ).bakedRoundedCorners()
                } else if (shape.type == ShapeType.BEZIER_PATH) {
                    shape.bakedRoundedCorners()
                } else {
                    shape
                }
            } else {
                shape
            }
        }

        // Now, fetch updated sorted selection
        val updatedSelected = shapes.filter { selectedShapeIds.contains(it.id) }
        val sortedSelected = shapes.filter { updatedSelected.any { sel -> sel.id == it.id } }
        if (sortedSelected.size < 2) return

        // Extract all original segments rotated/transformed to absolute Canvas World Space
        val allWorldSegments = mutableListOf<BezierSegment>()
        fun getRotatedSegments(shape: VectorShape): List<BezierSegment> {
            val sList = getOriginalBezierSegments(shape)
            if (shape.rotationAngle == 0f) return sList
            val bounds = shape.getBoundingBox()
            val cx = (bounds.left + bounds.right) / 2f
            val cy = (bounds.top + bounds.bottom) / 2f
            return sList.map { s ->
                BezierSegment(
                    p0 = rotatePoint(s.p0, Offset(cx, cy), shape.rotationAngle),
                    p1 = rotatePoint(s.p1, Offset(cx, cy), shape.rotationAngle),
                    p2 = rotatePoint(s.p2, Offset(cx, cy), shape.rotationAngle),
                    p3 = rotatePoint(s.p3, Offset(cx, cy), shape.rotationAngle),
                    originalShapeId = s.originalShapeId,
                    isCurve = s.isCurve
                )
            }
        }

        allWorldSegments.addAll(getRotatedSegments(sortedSelected[0]))

        // Set up first shape's absolute path
        val rawPath0 = sortedSelected[0].copy(rotationAngle = 0f).asComposePath()
        val matrix0 = android.graphics.Matrix().apply {
            if (sortedSelected[0].rotationAngle != 0f) {
                val bounds = sortedSelected[0].getBoundingBox()
                val cx = (bounds.left + bounds.right) / 2f
                val cy = (bounds.top + bounds.bottom) / 2f
                postRotate(sortedSelected[0].rotationAngle, cx, cy)
            }
        }
        var currentPath = Path().apply { addPath(rawPath0) }
        currentPath.asAndroidPath().transform(matrix0)

        // Sequentially execute Boolean operations using native Path.op
        for (i in 1 until sortedSelected.size) {
            val shapeB = sortedSelected[i]
            allWorldSegments.addAll(getRotatedSegments(shapeB))

            val rawPathB = shapeB.copy(rotationAngle = 0f).asComposePath()
            val matrixB = android.graphics.Matrix().apply {
                if (shapeB.rotationAngle != 0f) {
                    val bounds = shapeB.getBoundingBox()
                    val cx = (bounds.left + bounds.right) / 2f
                    val cy = (bounds.top + bounds.bottom) / 2f
                    postRotate(shapeB.rotationAngle, cx, cy)
                }
            }

            // Create transformed path copies in absolute Canvas World Space
            val transformedPathB = Path().apply { addPath(rawPathB) }
            transformedPathB.asAndroidPath().transform(matrixB)

            val op = when (opType) {
                "UNITE" -> PathOperation.Union
                "INTERSECT" -> PathOperation.Intersect
                "MINUS_FRONT" -> PathOperation.Difference
                "EXCLUDE" -> PathOperation.Xor
                else -> PathOperation.Union
            }

            if (opType == "DIVIDE") {
                // DIVIDE implementation comes later in the file, handled via custom loop!
            } else {
                // High precision scale to avoid coincident edge clipping artifacts in Skia
                val p1 = Path().apply { addPath(currentPath) }
                val p2 = Path().apply { addPath(transformedPathB) }
                val scaleUp = android.graphics.Matrix().apply { setScale(1024f, 1024f) }
                p1.asAndroidPath().transform(scaleUp)
                p2.asAndroidPath().transform(scaleUp)
                
                val nextPath = Path()
                val success = nextPath.op(p1, p2, op)
                if (success) {
                    val scaleDown = android.graphics.Matrix().apply { setScale(1f/1024f, 1f/1024f) }
                    nextPath.asAndroidPath().transform(scaleDown)
                    currentPath = nextPath
                }
            }
        }

        if (opType == "DIVIDE") {
            performDivideBooleanOperation(sortedSelected, allWorldSegments)
            return
        }

        if (opType == "TRIM") {
            performTrimBooleanOperation(sortedSelected, allWorldSegments)
            return
        }

        // Extract and reconstruct perfect bezier nodes from final world-space path
        val nodes = reconstructBezierNodesFromAndroidPath(currentPath.asAndroidPath(), allWorldSegments)

        if (nodes.isEmpty()) {
            shapes = shapes.filter { !selectedShapeIds.contains(it.id) }
            selectedShapeId = null
            selectedShapeIds = emptySet()
            return
        }

        val targetStyleShape = sortedSelected[0]
        val firstId = targetStyleShape.id
        val newId = UUID.randomUUID().toString()
        
        val booleanShape = VectorShape(
            id = newId,
            name = "Boolean ${opType.lowercase().replaceFirstChar { it.uppercase() }}",
            type = ShapeType.BEZIER_PATH,
            bezierNodes = nodes,
            isPathClosed = true,
            strokeColorHex = targetStyleShape.strokeColorHex,
            strokeWidth = targetStyleShape.strokeWidth,
            strokeAlpha = targetStyleShape.strokeAlpha,
            hasFill = targetStyleShape.hasFill,
            fillColorHex = targetStyleShape.fillColorHex,
            fillAlpha = targetStyleShape.fillAlpha,
            layerOrder = targetStyleShape.layerOrder,
            rotationAngle = 0f // reset rotation matrix since all coordinates are baked to world coordinates
        )

        shapes = shapes.map { s ->
            if (s.id == firstId) booleanShape else s
        }.filter { s -> s.id == newId || !selectedShapeIds.contains(s.id) }

        selectedShapeId = newId
        selectedShapeIds = setOf(newId)
    }

    private fun findIntersections(
        segA: BezierSegment, tA0: Float, tA1: Float,
        segB: BezierSegment, tB0: Float, tB1: Float,
        results: MutableList<Pair<Float, Float>>,
        depth: Int = 0
    ) {
        val subA = segA.split(tA0, tA1)
        val minXa = minOf(subA.p0.x, subA.p1.x, subA.p2.x, subA.p3.x)
        val maxXa = maxOf(subA.p0.x, subA.p1.x, subA.p2.x, subA.p3.x)
        val minYa = minOf(subA.p0.y, subA.p1.y, subA.p2.y, subA.p3.y)
        val maxYa = maxOf(subA.p0.y, subA.p1.y, subA.p2.y, subA.p3.y)

        val subB = segB.split(tB0, tB1)
        val minXb = minOf(subB.p0.x, subB.p1.x, subB.p2.x, subB.p3.x)
        val maxXb = maxOf(subB.p0.x, subB.p1.x, subB.p2.x, subB.p3.x)
        val minYb = minOf(subB.p0.y, subB.p1.y, subB.p2.y, subB.p3.y)
        val maxYb = maxOf(subB.p0.y, subB.p1.y, subB.p2.y, subB.p3.y)

        if (subA.originalShapeId == subB.originalShapeId) {
            val d00 = kotlin.math.hypot(subA.p0.x - subB.p0.x, subA.p0.y - subB.p0.y)
            val d33 = kotlin.math.hypot(subA.p3.x - subB.p3.x, subA.p3.y - subB.p3.y)
            val d03 = kotlin.math.hypot(subA.p0.x - subB.p3.x, subA.p0.y - subB.p3.y)
            val d30 = kotlin.math.hypot(subA.p3.x - subB.p0.x, subA.p3.y - subB.p0.y)
            if (d00 < 0.2f || d33 < 0.2f || d03 < 0.2f || d30 < 0.2f) {
                val lenA = maxOf(maxXa - minXa, maxYa - minYa)
                val lenB = maxOf(maxXb - minXb, maxYb - minYb)
                if (lenA < 1.0f && lenB < 1.0f) {
                    return
                }
            }
        }

        if (maxXa < minXb - 0.01f || minXa > maxXb + 0.01f || maxYa < minYb - 0.01f || minYa > maxYb + 0.01f) {
            return
        }

        val sizeA = maxOf(maxXa - minXa, maxYa - minYa)
        val sizeB = maxOf(maxXb - minXb, maxYb - minYb)

        if (sizeA < 0.15f && sizeB < 0.15f) {
            val midA = (tA0 + tA1) / 2f
            val midB = (tB0 + tB1) / 2f
            results.add(Pair(midA, midB))
            return
        }

        if (depth > 12) {
            val midA = (tA0 + tA1) / 2f
            val midB = (tB0 + tB1) / 2f
            results.add(Pair(midA, midB))
            return
        }

        if (sizeA >= sizeB) {
            val midA = (tA0 + tA1) / 2f
            findIntersections(segA, tA0, midA, segB, tB0, tB1, results, depth + 1)
            findIntersections(segA, midA, tA1, segB, tB0, tB1, results, depth + 1)
        } else {
            val midB = (tB0 + tB1) / 2f
            findIntersections(segA, tA0, tA1, segB, tB0, midB, results, depth + 1)
            findIntersections(segA, tA0, tA1, segB, midB, tB1, results, depth + 1)
        }
    }

    private fun splitAtMultipleTs(seg: BezierSegment, ts: List<Float>): List<BezierSegment> {
        if (ts.isEmpty()) return listOf(seg)
        val sortedTs = ts.filter { it in 0.001f..0.999f }.distinct().sorted()
        if (sortedTs.isEmpty()) return listOf(seg)

        val results = mutableListOf<BezierSegment>()
        var prevT = 0f
        for (t in sortedTs) {
            results.add(seg.split(prevT, t))
            prevT = t
        }
        results.add(seg.split(prevT, 1f))
        return results
    }

    private fun isPointInsideSegments(px: Float, py: Float, segments: List<BezierSegment>): Boolean {
        val testY = py + 0.00013f
        var crossings = 0
        for (seg in segments) {
            val minY = minOf(seg.p0.y, seg.p1.y, seg.p2.y, seg.p3.y)
            val maxY = maxOf(seg.p0.y, seg.p1.y, seg.p2.y, seg.p3.y)
            if (testY < minY || testY > maxY) continue
            val maxX = maxOf(seg.p0.x, seg.p1.x, seg.p2.x, seg.p3.x)
            if (px > maxX) continue

            val steps = 30
            var prev = seg.p0
            for (i in 1..steps) {
                val t = i.toFloat() / steps
                val curr = seg.getPoint(t)
                if ((prev.y > testY) != (curr.y > testY)) {
                    val intersectX = prev.x + (curr.x - prev.x) * (testY - prev.y) / (curr.y - prev.y)
                    if (px < intersectX) crossings++
                }
                prev = curr
            }
        }
        return (crossings % 2) != 0
    }

    private fun stitchSegments(pool: MutableList<BezierSegment>): List<BezierNode> {
        val nodes = mutableListOf<BezierNode>()
        if (pool.isEmpty()) return nodes

        while (pool.isNotEmpty()) {
            val subpath = mutableListOf<BezierSegment>()
            var curr = pool.removeAt(0)
            subpath.add(curr)
            
            while (true) {
                val p3 = curr.p3
                var nextIdx = -1
                var minDist = 1.5f // generous tolerance for float precision
                for (i in pool.indices) {
                    val candidate = pool[i]
                    val d = kotlin.math.hypot(candidate.p0.x - p3.x, candidate.p0.y - p3.y)
                    if (d < minDist) {
                        minDist = d
                        nextIdx = i
                    }
                }
                
                if (nextIdx != -1) {
                    curr = pool.removeAt(nextIdx)
                    subpath.add(curr)
                } else {
                    // Try to find connected backwards? If the shape is fully closed, it should be a loop.
                    break
                }
            }
            
            // Build Nodes from subpath segments
            for (i in subpath.indices) {
                val seg = subpath[i]
                val prevSeg = if (i > 0) subpath[i - 1] else subpath.last()
                
                val isCurveSegmentNext = subpath[(i + 1) % subpath.size].let { nextSeg ->
                    kotlin.math.hypot(nextSeg.p1.x - nextSeg.p0.x, nextSeg.p1.y - nextSeg.p0.y) > 0.1f ||
                    kotlin.math.hypot(nextSeg.p2.x - nextSeg.p3.x, nextSeg.p2.y - nextSeg.p3.y) > 0.1f ||
                    nextSeg.isCurve
                }
                
                val isCurveSegmentCurrent = kotlin.math.hypot(seg.p1.x - seg.p0.x, seg.p1.y - seg.p0.y) > 0.1f ||
                                            kotlin.math.hypot(seg.p2.x - seg.p3.x, seg.p2.y - seg.p3.y) > 0.1f ||
                                            seg.isCurve
                
                val isIntersection = seg.originalShapeId != prevSeg.originalShapeId
                val nodeHandleType = if (isIntersection) "SHARP_INTERSECTION" else seg.handleType
                
                val node = BezierNode(
                    anchorX = seg.p0.x,
                    anchorY = seg.p0.y,
                    isCurve = isCurveSegmentCurrent, 
                    control1X = prevSeg.p2.x,
                    control1Y = prevSeg.p2.y,
                    control2X = seg.p1.x,
                    control2Y = seg.p1.y,
                    isMoveTo = (i == 0),
                    nodeType = "BEBAS",
                    handleType = nodeHandleType
                )
                nodes.add(node)
                
                if (i == subpath.size - 1) {
                    val endNode = BezierNode(
                        anchorX = seg.p3.x,
                        anchorY = seg.p3.y,
                        isCurve = false,
                        control1X = seg.p2.x,
                        control1Y = seg.p2.y,
                        control2X = seg.p3.x, // will be overridden if loop connects
                        control2Y = seg.p3.y,
                        isMoveTo = false,
                        nodeType = "BEBAS",
                        handleType = if (isIntersection) "SHARP_INTERSECTION" else seg.handleType
                    )
                    // Let's only add the closing point if it doesn't match the very first point!
                    val dStart = kotlin.math.hypot(seg.p3.x - subpath[0].p0.x, seg.p3.y - subpath[0].p0.y)
                    if (dStart > 1.5f) {
                        nodes.add(endNode)
                    }
                }
            }
        }
        return nodes
    }

    // Toggle items layering visibility and locks
    fun toggleShapeVisibility(id: String) {
        pushToUndoStack()
        shapes = shapes.map { shape ->
            if (shape.id == id) {
                shape.copy(isVisible = !shape.isVisible)
            } else {
                shape
            }
        }
    }

    fun toggleShapeLock(id: String) {
        pushToUndoStack()
        shapes = shapes.map { shape ->
            if (shape.id == id) {
                shape.copy(isLocked = !shape.isLocked)
            } else {
                shape
            }
        }
    }

    fun moveShapeUp(id: String) {
        val index = shapes.indexOfFirst { it.id == id }
        if (index != -1 && index < shapes.size - 1) {
            pushToUndoStack()
            val list = shapes.toMutableList()
            val temp = list[index]
            list[index] = list[index + 1]
            list[index + 1] = temp
            shapes = list
        }
    }

    fun moveShapeDown(id: String) {
        val index = shapes.indexOfFirst { it.id == id }
        if (index > 0) {
            pushToUndoStack()
            val list = shapes.toMutableList()
            val temp = list[index]
            list[index] = list[index - 1]
            list[index - 1] = temp
            shapes = list
        }
    }

    fun renameShape(id: String, newName: String) {
        pushToUndoStack()
        shapes = shapes.map { s ->
            if (s.id == id) s.copy(name = newName) else s
        }
    }

    fun updateShapeOpacity(id: String, opacity: Float) {
        pushToUndoStack()
        shapes = shapes.map { s ->
            if (s.id == id) {
                s.copy(strokeAlpha = opacity, fillAlpha = opacity)
            } else {
                s
            }
        }
    }

    fun clearWorkspace() {
        pushToUndoStack()
        shapes = emptyList()
        selectedShapeId = null
        panOffset = Offset.Zero
        zoomScale = 1f
    }

    // Project state storage methods
    fun saveProjectLocally() {
        saveCurrentProject()
    }

    fun loadSavedProject() {
        val list = getAllSavedProjects()
        if (list.isNotEmpty()) {
            loadProject(list.first())
        } else {
            val data = sharedPrefs.getString("saved_canvas_shapes", null)
            if (!data.isNullOrBlank()) {
                pushToUndoStack()
                val loaded = parseJsonToShapes(data)
                if (loaded.isNotEmpty()) {
                    shapes = loaded
                    selectedShapeId = null
                }
            }
        }
    }

    private fun loadDefaultWorkspace() {
        // Build a beautiful startup sample (vector logo of AI Studio / Google with modern star/gemini and stylish shapes)
        val sampleShapes = mutableListOf<VectorShape>()
        
        // Background branding/grid design
        sampleShapes.add(
            VectorShape(
                id = "start_bg_star",
                name = "Stylized Star",
                type = ShapeType.ELLIPSE,
                x = 250f,
                y = 250f,
                width = 160f,
                height = 160f,
                strokeColorHex = "#a855f7",
                strokeWidth = 2f,
                strokeAlpha = 0.5f,
                hasFill = true,
                fillColorHex = "#faf5ff",
                fillAlpha = 0.9f
            )
        )

        // Gemini/Sparkle star inside (Drawn as a Pen Path!)
        sampleShapes.add(
            VectorShape(
                id = "gemini_sparkle",
                name = "Gemini Logo Vector",
                type = ShapeType.BEZIER_PATH,
                bezierNodes = listOf(
                    BezierNode(250f, 130f, false),
                    BezierNode(270f, 210f, true, 260f, 185f, 265f, 195f),
                    BezierNode(350f, 250f, true, 310f, 230f, 330f, 245f),
                    BezierNode(270f, 290f, true, 310f, 270f, 285f, 280f),
                    BezierNode(250f, 370f, true, 260f, 330f, 255f, 350f),
                    BezierNode(230f, 290f, true, 240f, 330f, 235f, 310f),
                    BezierNode(150f, 250f, true, 190f, 270f, 170f, 255f),
                    BezierNode(230f, 210f, true, 190f, 230f, 215f, 220f)
                ),
                isPathClosed = true,
                strokeColorHex = "#a855f7",
                strokeWidth = 5f,
                hasFill = true,
                fillColorHex = "#e9d5ff"
            )
        )

        // Beautiful centered circle
        sampleShapes.add(
            VectorShape(
                id = "center_ring",
                name = "Aura Frame",
                type = ShapeType.ELLIPSE,
                x = 250f,
                y = 250f,
                width = 110f,
                height = 110f,
                strokeColorHex = "#3b82f6",
                strokeWidth = 4f,
                hasFill = false
            )
        )

        // Welcome text
        sampleShapes.add(
            VectorShape(
                id = "title_text",
                name = "Welcome Title Text",
                type = ShapeType.TEXT,
                x = 100f,
                y = 440f,
                textContent = "VECTOR CREATOR PRO",
                fontSize = 28f,
                strokeColorHex = "#0f172a"
            )
        )

        shapes = emptyList()
    }

    // Robust custom parsing mechanism to avoid complex dependencies issues
    private fun convertShapesToJsonStr(shapesList: List<VectorShape>): String {
        val sb = java.lang.StringBuilder()
        sb.append("[")
        for (i in shapesList.indices) {
            val s = shapesList[i]
            sb.append("{")
            sb.append("\"id\":\"${s.id}\",")
            sb.append("\"name\":\"${s.name}\",")
            sb.append("\"type\":\"${s.type.name}\",")
            sb.append("\"layerId\":\"${s.layerId}\",")
            sb.append("\"isVisible\":${s.isVisible},")
            sb.append("\"isLocked\":${s.isLocked},")
            sb.append("\"x\":${s.x},")
            sb.append("\"y\":${s.y},")
            sb.append("\"width\":${s.width},")
            sb.append("\"height\":${s.height},")
            sb.append("\"startX\":${s.startX},")
            sb.append("\"startY\":${s.startY},")
            sb.append("\"endX\":${s.endX},")
            sb.append("\"endY\":${s.endY},")
            sb.append("\"strokeColorHex\":\"${s.strokeColorHex}\",")
            sb.append("\"strokeWidth\":${s.strokeWidth},")
            sb.append("\"strokeAlpha\":${s.strokeAlpha},")
            sb.append("\"strokeJoin\":\"${s.strokeJoin}\",")
            sb.append("\"strokeCap\":\"${s.strokeCap}\",")
            sb.append("\"hasStroke\":${s.hasStroke},")
            sb.append("\"hasFill\":${s.hasFill},")
            sb.append("\"fillColorHex\":\"${s.fillColorHex}\",")
            sb.append("\"fillAlpha\":${s.fillAlpha},")
            sb.append("\"textContent\":\"${s.textContent.replace("\"", "\\\"")}\",")
            sb.append("\"fontSize\":${s.fontSize},")
            sb.append("\"isPathClosed\":${s.isPathClosed},")
            sb.append("\"polygonSides\":${s.polygonSides},")
            sb.append("\"starPoints\":${s.starPoints},")
            sb.append("\"lineStyle\":\"${s.lineStyle}\",")
            sb.append("\"radiusTL\":${s.radiusTL},")
            sb.append("\"radiusTR\":${s.radiusTR},")
            sb.append("\"radiusBL\":${s.radiusBL},")
            sb.append("\"radiusBR\":${s.radiusBR},")
            sb.append("\"customCornerRadii\":[${s.customCornerRadii.joinToString(",")}],")
            if (s.groupId != null) {
                sb.append("\"groupId\":\"${s.groupId}\",")
            } else {
                sb.append("\"groupId\":null,")
            }
            
            // Serialize freehand points
            sb.append("\"freehandPoints\":[")
            for (j in s.freehandPoints.indices) {
                val pt = s.freehandPoints[j]
                sb.append("{\"x\":${pt.x},\"y\":${pt.y}}")
                if (j < s.freehandPoints.size - 1) sb.append(",")
            }
            sb.append("],")

            // Serialize bezier nodes
            sb.append("\"bezierNodes\":[")
            for (j in s.bezierNodes.indices) {
                val node = s.bezierNodes[j]
                sb.append("{\"anchorX\":${node.anchorX},\"anchorY\":${node.anchorY},\"isCurve\":${node.isCurve},\"control1X\":${node.control1X},\"control1Y\":${node.control1Y},\"control2X\":${node.control2X},\"control2Y\":${node.control2Y},\"isMoveTo\":${node.isMoveTo},\"nodeType\":\"${node.nodeType}\"}")
                if (j < s.bezierNodes.size - 1) sb.append(",")
            }
            sb.append("]")
            
            sb.append("}")
            if (i < shapesList.size - 1) sb.append(",")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun parseJsonToShapes(json: String): List<VectorShape> {
        val result = mutableListOf<VectorShape>()
        try {
            // Very simple parser for local preferences storage
            val clean = json.trim()
            if (!clean.startsWith("[")) return emptyList()
            var idx = 1
            while (idx < clean.lastIndex) {
                val itemStart = clean.indexOf("{", idx)
                if (itemStart == -1) break
                var bracketCount = 0
                var itemEnd = -1
                for (j in itemStart until clean.length) {
                    if (clean[j] == '{') bracketCount++
                    if (clean[j] == '}') {
                        bracketCount--
                        if (bracketCount == 0) {
                            itemEnd = j
                            break
                        }
                    }
                }
                if (itemEnd == -1) break
                val itemJson = clean.substring(itemStart, itemEnd + 1)
                idx = itemEnd + 1

                // Extract flat properties from simple regex
                val id = extractJsonString(itemJson, "id") ?: UUID.randomUUID().toString()
                val name = extractJsonString(itemJson, "name") ?: "Layer"
                val typeStr = extractJsonString(itemJson, "type") ?: "RECTANGLE"
                val layerId = extractJsonString(itemJson, "layerId") ?: "default_layer"
                val type = try { ShapeType.valueOf(typeStr) } catch(_: Exception) { ShapeType.RECTANGLE }
                val isVisible = extractJsonBoolean(itemJson, "isVisible") ?: true
                val isLocked = extractJsonBoolean(itemJson, "isLocked") ?: false
                val x = extractJsonFloat(itemJson, "x") ?: 0f
                val y = extractJsonFloat(itemJson, "y") ?: 0f
                val width = extractJsonFloat(itemJson, "width") ?: 0f
                val height = extractJsonFloat(itemJson, "height") ?: 0f
                val startX = extractJsonFloat(itemJson, "startX") ?: 0f
                val startY = extractJsonFloat(itemJson, "startY") ?: 0f
                val endX = extractJsonFloat(itemJson, "endX") ?: 0f
                val endY = extractJsonFloat(itemJson, "endY") ?: 0f
                val strokeColorHex = extractJsonString(itemJson, "strokeColorHex") ?: "#000000"
                val strokeWidth = extractJsonFloat(itemJson, "strokeWidth") ?: 4f
                val strokeAlpha = extractJsonFloat(itemJson, "strokeAlpha") ?: 1f
                val strokeJoin = extractJsonString(itemJson, "strokeJoin") ?: "ROUND"
                val strokeCap = extractJsonString(itemJson, "strokeCap") ?: "ROUND"
                val hasStroke = extractJsonBoolean(itemJson, "hasStroke") ?: true
                val hasFill = extractJsonBoolean(itemJson, "hasFill") ?: false
                val fillColorHex = extractJsonString(itemJson, "fillColorHex") ?: "#E0E0E0"
                val fillAlpha = extractJsonFloat(itemJson, "fillAlpha") ?: 1f
                val textContent = extractJsonString(itemJson, "textContent") ?: "Text"
                val fontSize = extractJsonFloat(itemJson, "fontSize") ?: 24f
                val isPathClosed = extractJsonBoolean(itemJson, "isPathClosed") ?: false
                val polygonSides = extractJsonFloat(itemJson, "polygonSides")?.toInt() ?: 5
                val starPoints = extractJsonFloat(itemJson, "starPoints")?.toInt() ?: 5
                val lineStyle = extractJsonString(itemJson, "lineStyle") ?: "SOLID"
                val radiusTL = extractJsonFloat(itemJson, "radiusTL") ?: 0f
                val radiusTR = extractJsonFloat(itemJson, "radiusTR") ?: 0f
                val radiusBL = extractJsonFloat(itemJson, "radiusBL") ?: 0f
                val radiusBR = extractJsonFloat(itemJson, "radiusBR") ?: 0f
                
                val cRadii = mutableListOf<Float>()
                val radiiSub = extractJsonBlock(itemJson, "customCornerRadii")
                if (radiiSub != null && radiiSub.isNotBlank()) {
                    radiiSub.split(",").forEach {
                        it.trim().toFloatOrNull()?.let { f -> cRadii.add(f) }
                    }
                }

                val groupIdStr = extractJsonString(itemJson, "groupId")
                val groupId = if (groupIdStr == "null" || groupIdStr.isNullOrEmpty()) null else groupIdStr

                // Parse freehand point arrays
                val pts = mutableListOf<SerializedPoint>()
                val pointsSub = extractJsonBlock(itemJson, "freehandPoints")
                if (pointsSub != null) {
                    var pIdx = 0
                    while (pIdx < pointsSub.length) {
                        val oStart = pointsSub.indexOf("{", pIdx)
                        if (oStart == -1) break
                        val oEnd = pointsSub.indexOf("}", oStart)
                        if (oEnd == -1) break
                        val oStr = pointsSub.substring(oStart, oEnd + 1)
                        pIdx = oEnd + 1
                        val px = extractJsonFloat(oStr, "x") ?: 0f
                        val py = extractJsonFloat(oStr, "y") ?: 0f
                        pts.add(SerializedPoint(px, py))
                    }
                }

                // Parse bezier node arrays
                val bNodes = mutableListOf<BezierNode>()
                val nodesSub = extractJsonBlock(itemJson, "bezierNodes")
                if (nodesSub != null) {
                    var nIdx = 0
                    while (nIdx < nodesSub.length) {
                        val oStart = nodesSub.indexOf("{", nIdx)
                        if (oStart == -1) break
                        val oEnd = nodesSub.indexOf("}", oStart)
                        if (oEnd == -1) break
                        val oStr = nodesSub.substring(oStart, oEnd + 1)
                        nIdx = oEnd + 1
                        val ax = extractJsonFloat(oStr, "anchorX") ?: 0f
                        val ay = extractJsonFloat(oStr, "anchorY") ?: 0f
                        val isCurve = extractJsonBoolean(oStr, "isCurve") ?: false
                        val c1x = extractJsonFloat(oStr, "control1X") ?: ax
                        val c1y = extractJsonFloat(oStr, "control1Y") ?: ay
                        val c2x = extractJsonFloat(oStr, "control2X") ?: ax
                        val c2y = extractJsonFloat(oStr, "control2Y") ?: ay
                        val isMoveTo = extractJsonBoolean(oStr, "isMoveTo") ?: false
                        val nodeType = extractJsonString(oStr, "nodeType") ?: "BEBAS"
                        bNodes.add(BezierNode(ax, ay, isCurve, c1x, c1y, c2x, c2y, isMoveTo, nodeType))
                    }
                }

                result.add(
                    VectorShape(
                        id = id, name = name, type = type, isVisible = isVisible, isLocked = isLocked,
                        x = x, y = y, width = width, height = height, startX = startX, startY = startY,
                        endX = endX, endY = endY, strokeColorHex = strokeColorHex, strokeWidth = strokeWidth,
                        strokeAlpha = strokeAlpha, strokeJoin = strokeJoin, strokeCap = strokeCap,
                        hasStroke = hasStroke,
                        hasFill = hasFill, fillColorHex = fillColorHex,
                        fillAlpha = fillAlpha, textContent = textContent, fontSize = fontSize,
                        isPathClosed = isPathClosed, freehandPoints = pts, bezierNodes = bNodes,
                        polygonSides = polygonSides, starPoints = starPoints, lineStyle = lineStyle,
                        radiusTL = radiusTL, radiusTR = radiusTR, radiusBL = radiusBL, radiusBR = radiusBR,
                        customCornerRadii = cRadii,
                        groupId = groupId,
                        layerId = layerId
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }

    private fun convertLayersToJsonStr(layersList: List<com.example.model.VectorLayer>): String {
        val sb = java.lang.StringBuilder()
        sb.append("[")
        for (i in layersList.indices) {
            val l = layersList[i]
            sb.append("{")
            sb.append("\"id\":\"${l.id}\",")
            sb.append("\"name\":\"${l.name.replace("\"", "\\\"")}\",")
            sb.append("\"isVisible\":${l.isVisible},")
            sb.append("\"isLocked\":${l.isLocked},")
            sb.append("\"opacity\":${l.opacity},")
            sb.append("\"optimizeTracing\":${l.optimizeTracing}")
            sb.append("}")
            if (i < layersList.size - 1) sb.append(",")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun parseJsonToLayers(json: String): List<com.example.model.VectorLayer> {
        val result = mutableListOf<com.example.model.VectorLayer>()
        try {
            val clean = json.trim()
            if (!clean.startsWith("[")) return listOf(com.example.model.VectorLayer(id = "default_layer", name = "Layer 1"))
            var idx = 1
            while (idx < clean.lastIndex) {
                val itemStart = clean.indexOf("{", idx)
                if (itemStart == -1) break
                var bracketCount = 0
                var itemEnd = -1
                for (j in itemStart until clean.length) {
                    if (clean[j] == '{') bracketCount++
                    if (clean[j] == '}') {
                        bracketCount--
                        if (bracketCount == 0) {
                            itemEnd = j
                            break
                        }
                    }
                }
                if (itemEnd == -1) break
                val itemJson = clean.substring(itemStart, itemEnd + 1)
                idx = itemEnd + 1

                val id = extractJsonString(itemJson, "id") ?: "default_layer"
                val name = extractJsonString(itemJson, "name") ?: "Layer"
                val isVisible = extractJsonBoolean(itemJson, "isVisible") ?: true
                val isLocked = extractJsonBoolean(itemJson, "isLocked") ?: false
                val opacity = extractJsonFloat(itemJson, "opacity") ?: 1f
                val optimizeTracing = extractJsonBoolean(itemJson, "optimizeTracing") ?: false

                result.add(com.example.model.VectorLayer(id, name, isVisible, isLocked, opacity, optimizeTracing))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (result.isEmpty()) {
            return listOf(com.example.model.VectorLayer(id = "default_layer", name = "Layer 1"))
        }
        return result
    }

    private fun extractJsonString(json: String, key: String): String? {
        val pattern = "\"$key\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"".toRegex()
        return pattern.find(json)?.groupValues?.get(1)
    }

    private fun extractJsonFloat(json: String, key: String): Float? {
        val pattern = "\"$key\"\\s*:\\s*(-?[0-9.]+)[,\\s}]".toRegex()
        return pattern.find(json)?.groupValues?.get(1)?.toFloatOrNull()
    }

    private fun extractJsonBoolean(json: String, key: String): Boolean? {
        val pattern = "\"$key\"\\s*:\\s*(true|false)".toRegex()
        return pattern.find(json)?.groupValues?.get(1)?.toBoolean()
    }

    private fun extractJsonBlock(json: String, key: String): String? {
        val pattern = "\"$key\"\\s*:".toRegex()
        val matchResult = pattern.find(json) ?: return null
        val colonIndex = matchResult.range.last
        val bracketStart = json.indexOf("[", colonIndex)
        if (bracketStart == -1) return null
        
        var bracketCount = 1
        var inQuotes = false
        var escaped = false
        for (i in (bracketStart + 1) until json.length) {
            val char = json[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (char == '\\') {
                escaped = true
                continue
            }
            if (char == '"') {
                inQuotes = !inQuotes
                continue
            }
            if (!inQuotes) {
                if (char == '[') {
                    bracketCount++
                } else if (char == ']') {
                    bracketCount--
                    if (bracketCount == 0) {
                        return json.substring(bracketStart + 1, i)
                    }
                }
            }
        }
        return null
    }

    fun importFileFromUri(
        context: Context,
        uri: android.net.Uri,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val contentResolver = context.contentResolver
            var fileName = "imported_file"
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    fileName = cursor.getString(nameIndex)
                }
            }

            val inputStream = contentResolver.openInputStream(uri)
            if (inputStream == null) {
                onError("Gagal membuka file stream.")
                return
            }

            val extension = fileName.substringAfterLast('.', "").lowercase()
            
            if (extension == "json") {
                val jsonText = inputStream.bufferedReader().use { it.readText() }
                val importedShapes = parseJsonToShapes(jsonText)
                if (importedShapes.isNotEmpty()) {
                    pushToUndoStack()
                    val remapped = importedShapes.map { shape ->
                        shape.copy(id = UUID.randomUUID().toString())
                    }
                    shapes = shapes + remapped
                    onSuccess("Berhasil mengimpor ${remapped.size} objek dari JSON!")
                } else {
                    onError("Format JSON tidak valid atau kosong.")
                }
            } else if (extension == "svg") {
                val svgText = inputStream.bufferedReader().use { it.readText() }
                val importedShapes = parseSvgStringContents(svgText)
                if (importedShapes.isNotEmpty()) {
                    pushToUndoStack()
                    shapes = shapes + importedShapes
                    onSuccess("Berhasil mengimpor ${importedShapes.size} objek dari SVG!")
                } else {
                    onError("Format SVG tidak valid atau tidak ada path yang didukung.")
                }
            } else if (extension in listOf("png", "jpg", "jpeg", "webp")) {
                val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                if (bitmap != null) {
                    val maxResolution = 1600
                    val originalW = bitmap.width
                    val originalH = bitmap.height
                    
                    val processedBitmap = if (maxOf(originalW, originalH) > maxResolution) {
                        val scaleFactor = maxResolution.toFloat() / maxOf(originalW, originalH).toFloat()
                        val newW = (originalW * scaleFactor).toInt().coerceAtLeast(1)
                        val newH = (originalH * scaleFactor).toInt().coerceAtLeast(1)
                        android.graphics.Bitmap.createScaledBitmap(bitmap, newW, newH, true)
                    } else {
                        bitmap
                    }

                    val byteArrayOutputStream = java.io.ByteArrayOutputStream()
                    processedBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, byteArrayOutputStream)
                    val byteArray = byteArrayOutputStream.toByteArray()
                    val base64String = android.util.Base64.encodeToString(byteArray, android.util.Base64.NO_WRAP)

                    val aspect = processedBitmap.width.toFloat() / processedBitmap.height.toFloat()
                    val fitWidth = canvasWidth * 0.8f
                    val fitHeight = canvasHeight * 0.8f
                    
                    var targetW = fitWidth
                    var targetH = fitWidth / aspect
                    
                    if (targetH > fitHeight) {
                        targetH = fitHeight
                        targetW = fitHeight * aspect
                    }

                    val startCanvasX = (canvasWidth - targetW) / 2f
                    val startCanvasY = (canvasHeight - targetH) / 2f

                    val imageShape = VectorShape(
                        id = java.util.UUID.randomUUID().toString(),
                        layerId = activeLayerId,
                        name = "Gambar ${shapes.count { it.type == ShapeType.IMAGE } + 1}",
                        type = ShapeType.IMAGE,
                        x = startCanvasX,
                        y = startCanvasY,
                        width = targetW,
                        height = targetH,
                        hasFill = false,
                        textContent = base64String,
                        strokeWidth = 0f,
                        strokeAlpha = 0f
                    )

                    pushToUndoStack()
                    shapes = shapes + imageShape
                    
                    // Otomatis aktifkan akselerasi tracing pada layer aktif tempat gambar di-import
                    layers = layers.map {
                        if (it.id == activeLayerId) {
                            it.copy(optimizeTracing = true)
                        } else {
                            it
                        }
                    }
                    onSuccess("Berhasil mengimpor gambar raster!")
                } else {
                    onError("Gagal men-decode file gambar.")
                }
            } else {
                onError("Tipe file tidak didukung (.${extension}). Silakan upload file JSON, SVG, PNG, atau JPG.")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            onError("Kesalahan saat mengimport file: ${e.message}")
        }
    }

    private fun parseSvgStringContents(svgContent: String): List<VectorShape> {
        val result = mutableListOf<VectorShape>()
        val tagRegex = Regex("<(path|rect|circle|ellipse|line|polygon|polyline)[^>]*>")
        val matches = tagRegex.findAll(svgContent)
        
        for (m in matches) {
            val fullTag = m.value
            val tagName = m.groupValues[1]
            
            val fillHex = extractAttribute(fullTag, "fill") ?: "#D1D5DB"
            val hasFill = fillHex != "none"
            
            val strokeHex = extractAttribute(fullTag, "stroke") ?: "#374151"
            val strokeW = extractAttribute(fullTag, "stroke-width")?.toFloatOrNull() ?: 0f
            val hasStroke = strokeW > 0f && strokeHex != "none"
            
            when (tagName) {
                "rect" -> {
                    val x = extractAttribute(fullTag, "x")?.toFloatOrNull() ?: 0f
                    val y = extractAttribute(fullTag, "y")?.toFloatOrNull() ?: 0f
                    val w = extractAttribute(fullTag, "width")?.toFloatOrNull() ?: 100f
                    val h = extractAttribute(fullTag, "height")?.toFloatOrNull() ?: 100f
                    
                    result.add(VectorShape(
                        id = java.util.UUID.randomUUID().toString(),
                        name = "Imported Rect",
                        type = ShapeType.RECTANGLE,
                        x = x,
                        y = y,
                        width = w,
                        height = h,
                        hasFill = hasFill,
                        fillColorHex = if (fillHex.startsWith("#")) fillHex else "#D1D5DB",
                        strokeColorHex = if (strokeHex.startsWith("#")) strokeHex else "#374151",
                        strokeWidth = strokeW,
                        strokeAlpha = if (hasStroke) 1f else 0f
                    ))
                }
                "circle" -> {
                    val cx = extractAttribute(fullTag, "cx")?.toFloatOrNull() ?: 0f
                    val cy = extractAttribute(fullTag, "cy")?.toFloatOrNull() ?: 0f
                    val r = extractAttribute(fullTag, "r")?.toFloatOrNull() ?: 50f
                    
                    result.add(VectorShape(
                        id = java.util.UUID.randomUUID().toString(),
                        name = "Imported Circle",
                        type = ShapeType.ELLIPSE,
                        x = cx - r,
                        y = cy - r,
                        width = r * 2f,
                        height = r * 2f,
                        hasFill = hasFill,
                        fillColorHex = if (fillHex.startsWith("#")) fillHex else "#D1D5DB",
                        strokeColorHex = if (strokeHex.startsWith("#")) strokeHex else "#374151",
                        strokeWidth = strokeW,
                        strokeAlpha = if (hasStroke) 1f else 0f
                    ))
                }
                "ellipse" -> {
                    val cx = extractAttribute(fullTag, "cx")?.toFloatOrNull() ?: 0f
                    val cy = extractAttribute(fullTag, "cy")?.toFloatOrNull() ?: 0f
                    val rx = extractAttribute(fullTag, "rx")?.toFloatOrNull() ?: 50f
                    val ry = extractAttribute(fullTag, "ry")?.toFloatOrNull() ?: 50f
                    
                    result.add(VectorShape(
                        id = java.util.UUID.randomUUID().toString(),
                        name = "Imported Ellipse",
                        type = ShapeType.ELLIPSE,
                        x = cx - rx,
                        y = cy - ry,
                        width = rx * 2f,
                        height = ry * 2f,
                        hasFill = hasFill,
                        fillColorHex = if (fillHex.startsWith("#")) fillHex else "#D1D5DB",
                        strokeColorHex = if (strokeHex.startsWith("#")) strokeHex else "#374151",
                        strokeWidth = strokeW,
                        strokeAlpha = if (hasStroke) 1f else 0f
                    ))
                }
                "line" -> {
                    val x1 = extractAttribute(fullTag, "x1")?.toFloatOrNull() ?: 0f
                    val y1 = extractAttribute(fullTag, "y1")?.toFloatOrNull() ?: 0f
                    val x2 = extractAttribute(fullTag, "x2")?.toFloatOrNull() ?: 100f
                    val y2 = extractAttribute(fullTag, "y2")?.toFloatOrNull() ?: 100f
                    
                    result.add(VectorShape(
                        id = java.util.UUID.randomUUID().toString(),
                        name = "Imported Line",
                        type = ShapeType.LINE,
                        startX = x1,
                        startY = y1,
                        endX = x2,
                        endY = y2,
                        strokeColorHex = if (strokeHex.startsWith("#")) strokeHex else "#374151",
                        strokeWidth = if (strokeW > 0f) strokeW else 4f,
                        strokeAlpha = 1f
                    ))
                }
                "path" -> {
                    val d = extractAttribute(fullTag, "d") ?: ""
                    val isClosed = d.trim().endsWith("z", ignoreCase = true)
                    val nodes = parseSvgPathData(d)
                    if (nodes.isNotEmpty()) {
                        result.add(VectorShape(
                            id = java.util.UUID.randomUUID().toString(),
                            name = "Imported Path",
                            type = ShapeType.BEZIER_PATH,
                            bezierNodes = nodes,
                            isPathClosed = isClosed,
                            hasFill = hasFill,
                            fillColorHex = if (fillHex.startsWith("#")) fillHex else "#D1D5DB",
                            strokeColorHex = if (strokeHex.startsWith("#")) strokeHex else "#374151",
                            strokeWidth = strokeW,
                            strokeAlpha = if (hasStroke) 1f else 0f
                        ))
                    }
                }
                "polygon", "polyline" -> {
                    val pointsStr = extractAttribute(fullTag, "points") ?: ""
                    val coordTokens = pointsStr.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
                    val nodes = mutableListOf<BezierNode>()
                    var first = true
                    var idx = 0
                    while (idx + 1 < coordTokens.size) {
                        val x = coordTokens[idx].toFloatOrNull() ?: 0f
                        val y = coordTokens[idx+1].toFloatOrNull() ?: 0f
                        nodes.add(BezierNode(anchorX = x, anchorY = y, isMoveTo = first, isCurve = false))
                        first = false
                        idx += 2
                    }
                    
                    if (nodes.isNotEmpty()) {
                        result.add(VectorShape(
                            id = java.util.UUID.randomUUID().toString(),
                            name = "Imported Shape",
                            type = ShapeType.BEZIER_PATH,
                            bezierNodes = nodes,
                            isPathClosed = tagName == "polygon",
                            hasFill = hasFill && tagName == "polygon",
                            fillColorHex = if (fillHex.startsWith("#")) fillHex else "#D1D5DB",
                            strokeColorHex = if (strokeHex.startsWith("#")) strokeHex else "#374151",
                            strokeWidth = strokeW,
                            strokeAlpha = if (hasStroke) 1f else 0f
                        ))
                    }
                }
            }
        }
        return result
    }

    private fun extractAttribute(tag: String, attr: String): String? {
        val pattern1 = "$attr\\s*=\\s*\"([^\"]*)\"".toRegex(RegexOption.IGNORE_CASE)
        val pattern2 = "$attr\\s*=\\s*'([^']*)'".toRegex(RegexOption.IGNORE_CASE)
        val m1 = pattern1.find(tag)
        if (m1 != null) return m1.groupValues[1]
        val m2 = pattern2.find(tag)
        if (m2 != null) return m2.groupValues[1]
        return null
    }

    private fun parseSvgPathData(d: String): List<BezierNode> {
        val nodes = mutableListOf<BezierNode>()
        val formatted = d.replace("([a-df-zM-Z])".toRegex(), " $1 ")
                         .replace(",", " ")
                         .trim()
        val tokens = formatted.split("\\s+".toRegex()).filter { it.isNotEmpty() }
        
        var i = 0
        var currentX = 0f
        var currentY = 0f
        var startX = 0f
        var startY = 0f
        
        while (i < tokens.size) {
            val cmd = tokens[cmdIndiceWorkaround(i)]
            i++
            when (cmd) {
                "M", "m" -> {
                    val isRelative = cmd == "m"
                    if (i + 1 < tokens.size) {
                        val x = tokens[i].toFloatOrNull() ?: 0f
                        val y = tokens[i+1].toFloatOrNull() ?: 0f
                        i += 2
                        currentX = if (isRelative) currentX + x else x
                        currentY = if (isRelative) currentY + y else y
                        startX = currentX
                        startY = currentY
                        nodes.add(BezierNode(anchorX = currentX, anchorY = currentY, isMoveTo = true, isCurve = false))
                    }
                }
                "L", "l" -> {
                    val isRelative = cmd == "l"
                    while (i + 1 < tokens.size && tokens[i].toFloatOrNull() != null) {
                        val x = tokens[i].toFloatOrNull() ?: 0f
                        val y = tokens[i+1].toFloatOrNull() ?: 0f
                        i += 2
                        currentX = if (isRelative) currentX + x else x
                        currentY = if (isRelative) currentY + y else y
                        nodes.add(BezierNode(anchorX = currentX, anchorY = currentY, isMoveTo = false, isCurve = false))
                    }
                }
                "H", "h" -> {
                    val isRelative = cmd == "h"
                    if (i < tokens.size) {
                        val x = tokens[i].toFloatOrNull() ?: 0f
                        i += 1
                        currentX = if (isRelative) currentX + x else x
                        nodes.add(BezierNode(anchorX = currentX, anchorY = currentY, isMoveTo = false, isCurve = false))
                    }
                }
                "V", "v" -> {
                    val isRelative = cmd == "v"
                    if (i < tokens.size) {
                        val y = tokens[i].toFloatOrNull() ?: 0f
                        i += 1
                        currentY = if (isRelative) currentY + y else y
                        nodes.add(BezierNode(anchorX = currentX, anchorY = currentY, isMoveTo = false, isCurve = false))
                    }
                }
                "C", "c" -> {
                    val isRelative = cmd == "c"
                    while (i + 5 < tokens.size && tokens[i].toFloatOrNull() != null) {
                        val c1x = tokens[i].toFloatOrNull() ?: 0f
                        val c1y = tokens[i+1].toFloatOrNull() ?: 0f
                        val c2x = tokens[i+2].toFloatOrNull() ?: 0f
                        val c2y = tokens[i+3].toFloatOrNull() ?: 0f
                        val x = tokens[i+4].toFloatOrNull() ?: 0f
                        val y = tokens[i+5].toFloatOrNull() ?: 0f
                        i += 6
                        
                        val absC1X = if (isRelative) currentX + c1x else c1x
                        val absC1Y = if (isRelative) currentY + c1y else c1y
                        val absC2X = if (isRelative) currentX + c2x else c2x
                        val absC2Y = if (isRelative) currentY + c2y else c2y
                        val absX = if (isRelative) currentX + x else x
                        val absY = if (isRelative) currentY + y else y
                        
                        nodes.add(BezierNode(
                            anchorX = absX,
                            anchorY = absY,
                            isCurve = true,
                            control1X = absC1X,
                            control1Y = absC1Y,
                            control2X = absC2X,
                            control2Y = absC2Y,
                            isMoveTo = false
                        ))
                        currentX = absX
                        currentY = absY
                    }
                }
                "Q", "q" -> {
                    val isRelative = cmd == "q"
                    while (i + 3 < tokens.size && tokens[i].toFloatOrNull() != null) {
                        val cx = tokens[i].toFloatOrNull() ?: 0f
                        val cy = tokens[i+1].toFloatOrNull() ?: 0f
                        val x = tokens[i+2].toFloatOrNull() ?: 0f
                        val y = tokens[i+3].toFloatOrNull() ?: 0f
                        i += 4
                        
                        val absCX = if (isRelative) currentX + cx else cx
                        val absCY = if (isRelative) currentY + cy else cy
                        val absX = if (isRelative) currentX + x else x
                        val absY = if (isRelative) currentY + y else y
                        
                        val c1x = currentX + (2f / 3f) * (absCX - currentX)
                        val c1y = currentY + (2f / 3f) * (absCY - currentY)
                        val c2x = absX + (2f / 3f) * (absCX - absX)
                        val c2y = absY + (2f / 3f) * (absCY - absY)
                        
                        nodes.add(BezierNode(
                            anchorX = absX,
                            anchorY = absY,
                            isCurve = true,
                            control1X = c1x,
                            control1Y = c1y,
                            control2X = c2x,
                            control2Y = c2y,
                            isMoveTo = false
                        ))
                        currentX = absX
                        currentY = absY
                    }
                }
                "Z", "z" -> {
                    currentX = startX
                    currentY = startY
                }
            }
        }
        return nodes
    }

    private fun cmdIndiceWorkaround(i: Int): Int = i

    // --- MULTIPLE PROJECTS PERSISTENCE ENGINE ---
    var currentProjectId by mutableStateOf<String?>(null)
    var currentProjectName by mutableStateOf("")
    var savedProjectsList by mutableStateOf<List<SavedProject>>(emptyList())

    fun refreshSavedProjects() {
        savedProjectsList = getAllSavedProjects()
    }

    private fun extractJsonLong(json: String, key: String): Long? {
        val pattern = "\"$key\"\\s*:\\s*(-?[0-9]+)[,\\s}]".toRegex()
        return pattern.find(json)?.groupValues?.get(1)?.toLongOrNull()
    }

    fun parseSavedProject(json: String): SavedProject? {
        try {
            val clean = json.trim()
            val id = extractJsonString(clean, "id") ?: return null
            val name = extractJsonString(clean, "name") ?: "Project"
            val canvasWidth = extractJsonFloat(clean, "canvasWidth") ?: 2000f
            val canvasHeight = extractJsonFloat(clean, "canvasHeight") ?: 2000f
            val artboardColorHex = extractJsonString(clean, "artboardColorHex") ?: "#FFFFFF"
            val artboardAlpha = extractJsonFloat(clean, "artboardAlpha") ?: 1f
            val lastModified = extractJsonLong(clean, "lastModified") ?: System.currentTimeMillis()
            
            val shapesBlock = extractJsonBlock(clean, "shapes") ?: ""
            val shapesList = parseJsonToShapes("[$shapesBlock]")
            
            val layersBlock = extractJsonBlock(clean, "layers")
            val layersList = if (layersBlock != null) parseJsonToLayers("[$layersBlock]") else listOf(com.example.model.VectorLayer(id = "default_layer", name = "Layer 1"))
            val activeLayerId = extractJsonString(clean, "activeLayerId") ?: "default_layer"
            
            return SavedProject(id, name, canvasWidth, canvasHeight, artboardColorHex, artboardAlpha, lastModified, shapesList, layersList, activeLayerId)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    fun serializeProject(project: SavedProject): String {
        val sb = java.lang.StringBuilder()
        sb.append("{")
        sb.append("\"id\":\"${project.id}\",")
        sb.append("\"name\":\"${project.name.replace("\"", "\\\"")}\",")
        sb.append("\"canvasWidth\":${project.canvasWidth},")
        sb.append("\"canvasHeight\":${project.canvasHeight},")
        sb.append("\"artboardColorHex\":\"${project.artboardColorHex}\",")
        sb.append("\"artboardAlpha\":${project.artboardAlpha},")
        sb.append("\"lastModified\":${project.lastModified},")
        sb.append("\"activeLayerId\":\"${project.activeLayerId}\",")
        sb.append("\"layers\":${convertLayersToJsonStr(project.layers)},")
        sb.append("\"shapes\":${convertShapesToJsonStr(project.shapes)}")
        sb.append("}")
        return sb.toString()
    }

    fun getAllSavedProjects(): List<SavedProject> {
        val plist = mutableListOf<SavedProject>()
        try {
            val allPrefs = sharedPrefs.all
            for ((key, value) in allPrefs) {
                if (key.startsWith("project_") && value is String) {
                    val proj = parseSavedProject(value)
                    if (proj != null) {
                        plist.add(proj)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return plist.sortedByDescending { it.lastModified }
    }

    fun createNewProject(name: String, width: Float, height: Float) {
        pushToUndoStack()
        val newId = "project_" + java.util.UUID.randomUUID().toString()
        
        // Reset layers to defaults for new project
        _layers = listOf(com.example.model.VectorLayer(id = "default_layer", name = "Layer 1"))
        _activeLayerId = "default_layer"
        
        shapes = emptyList()
        selectedShapeId = null
        panOffset = Offset.Zero
        zoomScale = 1f
        
        currentProjectId = newId
        currentProjectName = if (name.isBlank()) "Proyek Baru" else name
        canvasWidth = width
        canvasHeight = height
        artboardColorHex = "#FFFFFF"
        artboardAlpha = 1f
        isSetupCompleted = true
        
        saveCurrentProject()
    }

    fun saveCurrentProject() {
        val id = currentProjectId ?: run {
            val newId = "project_" + java.util.UUID.randomUUID().toString()
            currentProjectId = newId
            newId
        }
        if (currentProjectName.isBlank()) {
            currentProjectName = "Untitled Project"
        }
        val proj = SavedProject(
            id = id,
            name = currentProjectName,
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight,
            artboardColorHex = artboardColorHex,
            artboardAlpha = artboardAlpha,
            lastModified = System.currentTimeMillis(),
            shapes = shapes,
            layers = layers,
            activeLayerId = activeLayerId
        )
        val serialized = serializeProject(proj)
        sharedPrefs.edit().putString(id, serialized).apply()
        
        // Backward compatibility
        val oldSerialized = convertShapesToJsonStr(shapes)
        sharedPrefs.edit().putString("saved_canvas_shapes", oldSerialized).apply()
        
        refreshSavedProjects()
    }

    fun loadProject(project: SavedProject) {
        pushToUndoStack()
        currentProjectId = project.id
        currentProjectName = project.name
        canvasWidth = project.canvasWidth
        canvasHeight = project.canvasHeight
        artboardColorHex = project.artboardColorHex
        artboardAlpha = project.artboardAlpha
        
        // Restore layers first before shapes
        _layers = project.layers
        _activeLayerId = project.activeLayerId
        
        shapes = project.shapes
        selectedShapeId = null
        panOffset = Offset.Zero
        zoomScale = 1f
        isSetupCompleted = true
    }

    fun deleteProject(projectId: String) {
        sharedPrefs.edit().remove(projectId).apply()
        if (currentProjectId == projectId) {
            currentProjectId = null
            currentProjectName = ""
            shapes = emptyList()
            isSetupCompleted = false
        }
        refreshSavedProjects()
    }

    fun renameProject(projectId: String, newName: String) {
        val json = sharedPrefs.getString(projectId, null) ?: return
        try {
            val project = parseSavedProject(json)
            if (project != null) {
                val updatedProject = project.copy(name = newName, lastModified = System.currentTimeMillis())
                val newJson = serializeProject(updatedProject)
                sharedPrefs.edit().putString(projectId, newJson).apply()
                if (currentProjectId == projectId) {
                    currentProjectName = newName
                }
                refreshSavedProjects()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

data class SavedProject(
    val id: String,
    val name: String,
    val canvasWidth: Float,
    val canvasHeight: Float,
    val artboardColorHex: String,
    val artboardAlpha: Float,
    val lastModified: Long,
    val shapes: List<VectorShape>,
    val layers: List<com.example.model.VectorLayer>,
    val activeLayerId: String
)

