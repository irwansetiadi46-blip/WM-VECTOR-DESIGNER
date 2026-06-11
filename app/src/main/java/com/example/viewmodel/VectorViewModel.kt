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

class VectorViewModel(application: Application) : AndroidViewModel(application) {
    private val sharedPrefs = application.getSharedPreferences("vector_prefs", Context.MODE_PRIVATE)

    // Current State
    var shapes by mutableStateOf<List<VectorShape>>(emptyList())
        private set

    // Layer Management System
    var layers by mutableStateOf<List<com.example.model.VectorLayer>>(
        listOf(com.example.model.VectorLayer(id = "default_layer", name = "Layer 1"))
    )
    var activeLayerId by mutableStateOf("default_layer")

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

    var selectedShapeId by mutableStateOf<String?>(null)
    var selectedShapeIds by mutableStateOf<Set<String>>(emptySet())
    var isCloneModeActive by mutableStateOf(false)
    
    // Tools parameters
    var currentPolygonSides by mutableStateOf(6)
    var currentStarPoints by mutableStateOf(5)
    var currentLineStyle by mutableStateOf("SOLID") // "SOLID", "DASHED", "DOTTED"

    private var _currentTool by mutableStateOf(VectorTool.POINTER)
    var currentTool: VectorTool
        get() = _currentTool
        set(value) {
            _currentTool = value
            activeEditNodeIndex = null
            selectedDirectSelectionNodeIndex = null
            isCloneModeActive = false
        }
    var activePrimitiveType by mutableStateOf(PrimitiveType.RECTANGLE)

    // Style Parameters (State connected directly to both bottom sliders and color pickers)
    var currentStrokeWidth by mutableStateOf(8f)
    var currentStrokeColorHex by mutableStateOf("#333333")
    var currentStrokeAlpha by mutableStateOf(1f)
    var currentStrokeJoin by mutableStateOf("ROUND") // "MITER", "ROUND", "BEVEL"
    var currentStrokeCap by mutableStateOf("ROUND")   // "ROUND", "BUTT", "SQUARE"
    var hasFillEnabled by mutableStateOf(true)
    var currentFillColorHex by mutableStateOf("#4caf50")
    var currentFillAlpha by mutableStateOf(1f)

    // Path building states
    var activeFreehandPoints by mutableStateOf<List<SerializedPoint>>(emptyList())
    var activeBezierNodes by mutableStateOf<List<BezierNode>>(emptyList())
    var isPathClosed by mutableStateOf(false)

    // Selection node & corner controllers
    var selectedDirectSelectionNodeIndex by mutableStateOf<Int?>(null)
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
                    ShapeType.POLYGON, ShapeType.STAR, ShapeType.BEZIER_PATH -> {
                        val numCorners = s.getCornerPoints().size
                        val list = MutableList(numCorners) { radius }
                        s.copy(customCornerRadii = list)
                    }
                    else -> s
                }
            } else {
                s
            }
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
                    ShapeType.POLYGON, ShapeType.STAR, ShapeType.BEZIER_PATH -> {
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
                    else -> s
                }
            } else {
                s
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

    // Camera panning/zooming variables
    var zoomScale by mutableStateOf(1f)
    var panOffset by mutableStateOf(Offset.Zero)

    // Undo / Redo history stacks
    private val undoStack = mutableListOf<List<VectorShape>>()
    private val redoStack = mutableListOf<List<VectorShape>>()

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

    // Onboarding config & Canvas custom background state
    var isSetupCompleted by mutableStateOf(false)
    var artboardColorHex by mutableStateOf("#FFFFFF")
    var artboardAlpha by mutableStateOf(1f)

    // Font Importing
    var importedTypeface by mutableStateOf<android.graphics.Typeface?>(null)
    var importedFontFileName by mutableStateOf<String?>(null)

    // Alignment target criteria
    var alignBasisIsCanvas by mutableStateOf(true)

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

    init {
        // Load default shapes on startup or present beautiful preset
        loadDefaultWorkspace()
    }

    fun convertShapeToBezierPath(shapeId: String) {
        shapes = shapes.map { shape ->
            if (shape.id == shapeId && shape.type != ShapeType.BEZIER_PATH) {
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
            } else {
                shape
            }
        }
    }

    fun pushToUndoStack() {
        // Limit undo stack size to prevent out of memory
        if (undoStack.size >= 50) {
            undoStack.removeAt(0)
        }
        undoStack.add(ArrayList(shapes))
        redoStack.clear()
    }

    fun undo() {
        if (undoStack.isNotEmpty()) {
            val prevState = undoStack.removeAt(undoStack.size - 1)
            redoStack.add(ArrayList(shapes))
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
            undoStack.add(ArrayList(shapes))
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
                val left = minOf(snappedStart.x, snappedEnd.x)
                val top = minOf(snappedStart.y, snappedEnd.y)
                val w = maxOf(4f, kotlin.math.abs(snappedEnd.x - snappedStart.x))
                val h = maxOf(4f, kotlin.math.abs(snappedEnd.y - snappedStart.y))
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
                VectorShape(
                    id = id,
                    name = "Ellipse ${shapes.size + 1}",
                    type = ShapeType.ELLIPSE,
                    x = centerX,
                    y = centerY,
                    width = rx,
                    height = ry,
                    strokeColorHex = currentStrokeColorHex,
                    strokeWidth = currentStrokeWidth,
                    strokeAlpha = currentStrokeAlpha,
                    strokeJoin = currentStrokeJoin,
                    strokeCap = currentStrokeCap,
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
                VectorShape(
                    id = id,
                    name = "Polygon (${currentPolygonSides}s) ${shapes.size + 1}",
                    type = ShapeType.POLYGON,
                    x = centerX,
                    y = centerY,
                    width = rx,
                    height = ry,
                    polygonSides = currentPolygonSides,
                    strokeColorHex = currentStrokeColorHex,
                    strokeWidth = currentStrokeWidth,
                    strokeAlpha = currentStrokeAlpha,
                    strokeJoin = currentStrokeJoin,
                    strokeCap = currentStrokeCap,
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
                VectorShape(
                    id = id,
                    name = "Star (${currentStarPoints}p) ${shapes.size + 1}",
                    type = ShapeType.STAR,
                    x = centerX,
                    y = centerY,
                    width = rx,
                    height = ry,
                    starPoints = currentStarPoints,
                    strokeColorHex = currentStrokeColorHex,
                    strokeWidth = currentStrokeWidth,
                    strokeAlpha = currentStrokeAlpha,
                    strokeJoin = currentStrokeJoin,
                    strokeCap = currentStrokeCap,
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
                VectorShape(
                    id = id,
                    name = "Triangle ${shapes.size + 1}",
                    type = ShapeType.POLYGON,
                    x = centerX,
                    y = centerY,
                    width = rx,
                    height = ry,
                    polygonSides = 3,
                    strokeColorHex = currentStrokeColorHex,
                    strokeWidth = currentStrokeWidth,
                    strokeAlpha = currentStrokeAlpha,
                    strokeJoin = currentStrokeJoin,
                    strokeCap = currentStrokeCap,
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
        if (activeBezierNodes.isNotEmpty() &&
            hypot(snap.x - activeBezierNodes.first().anchorX, snap.y - activeBezierNodes.first().anchorY) < 16f
        ) {
            finalizeBezierPath(isClosed = true)
            return
        }
        activeEditNodeIndex = activeBezierNodes.size
        activeBezierNodes = activeBezierNodes + newNode
    }

    // Pen Tool: Adding explicit Bezier curve joints immediately on touch down
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
            val nodeIdx = selectedDirectSelectionNodeIndex
            if (shapeId != null && nodeIdx != null) {
                pushToUndoStack()
                shapes = shapes.map { shape ->
                    if (shape.id == shapeId) {
                        when (shape.type) {
                            ShapeType.BEZIER_PATH -> {
                                val newNodes = shape.bezierNodes.toMutableList().apply {
                                    if (nodeIdx in indices) removeAt(nodeIdx)
                                }
                                shape.copy(bezierNodes = newNodes)
                            }
                            ShapeType.FREEHAND -> {
                                val newPts = shape.freehandPoints.toMutableList().apply {
                                    if (nodeIdx in indices) removeAt(nodeIdx)
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
                selectedDirectSelectionNodeIndex = null
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

    private fun snapAngle(angleDegrees: Float, threshold: Float = 4.0f): Float {
        val targets = listOf(0f, 25f, -25f, 45f, -45f, 50f, -50f, 75f, -75f, 90f, -90f, 100f, -100f, 125f, -125f, 135f, -135f, 150f, -150f, 175f, -175f, 180f, -180f)
        var closestSnap = angleDegrees
        var minDiff = Float.MAX_VALUE
        
        for (step in targets) {
            val diff = kotlin.math.abs(angleDegrees - step)
            if (diff < minDiff) {
                minDiff = diff
                closestSnap = step
            }
        }
        
        return if (minDiff < threshold) {
            closestSnap
        } else {
            angleDegrees
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
                
                var normalized = targetAngle
                if (normalized > 180f) normalized -= 360f
                
                val snapped = snapAngle(normalized, 4.0f)
                
                var finalAngle = snapped
                if (finalAngle < 0f) finalAngle += 360f
                
                startVal.copyWithTransform(dx, dy).copy(rotationAngle = finalAngle)
            } else {
                shape
            }
        }
    }

    fun strokeToPath() {
        val activeId = selectedShapeId ?: return
        val shape = shapes.find { it.id == activeId } ?: return
        pushToUndoStack()
        
        val newShapesList = shapes.toMutableList()
        val index = newShapesList.indexOf(shape)
        if (index == -1) return
        
        val newId = UUID.randomUUID().toString()
        
        // 1. Get the shape's vector path as a Compose path
        val composePath = shape.asComposePath()
        val androidPath = composePath.asAndroidPath()
        
        // 2. Use Android Paint to get the filled stroke outline path
        val strokePaint = android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = shape.strokeWidth
            strokeCap = when (shape.strokeCap) {
                "BUTT" -> android.graphics.Paint.Cap.BUTT
                "SQUARE" -> android.graphics.Paint.Cap.SQUARE
                else -> android.graphics.Paint.Cap.ROUND
            }
            strokeJoin = when (shape.strokeJoin) {
                "MITER" -> android.graphics.Paint.Join.MITER
                "BEVEL" -> android.graphics.Paint.Join.BEVEL
                else -> android.graphics.Paint.Join.ROUND
            }
        }
        val outlinePath = android.graphics.Path()
        strokePaint.getFillPath(androidPath, outlinePath)
        
        // 3. For stroke to path, reconstruct nodes directly from the outline without snapping to the internal skeleton segments
        val nodes = reconstructBezierNodesFromAndroidPath(outlinePath, emptyList())
        
        val strokePathShape = VectorShape(
            id = newId,
            name = "${shape.name} Stroke-Path",
            type = ShapeType.BEZIER_PATH,
            bezierNodes = nodes,
            isPathClosed = true,
            strokeColorHex = "#333333",
            strokeWidth = 0f,
            hasFill = true,
            fillColorHex = shape.strokeColorHex,
            fillAlpha = shape.strokeAlpha,
            layerOrder = shape.layerOrder
        )
        
        newShapesList[index] = strokePathShape
        shapes = newShapesList
        selectedShapeId = newId
        selectedShapeIds = setOf(newId)
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
        
        for (original in sortedActiveShapes) {
            val newId = UUID.randomUUID().toString()
            val duplicated = original.copyWithTransform(30f, 30f).copy(
                id = newId,
                name = "${original.name} Copy"
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
        val target = shapes.find { it.id == id } ?: return
        val bounds = target.getBoundingBox()
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
            // Align based on Object
            val otherShapes = shapes.filter { it.id != id && it.isVisible }
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
            if (shape.id == id) {
                shape.copyWithTransform(dx, dy)
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

        shapes = dragStartShapes.map { s ->
            if (activeIds.contains(s.id)) {
                if (s.isLocked) s else s.copyWithScale(scaleX, scaleY, px, py)
            } else {
                s
            }
        }
    }

    fun finishTransformation() {
        pushToUndoStack()
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
        val isCurve: Boolean = false
    ) {
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
            return BezierSegment(p0, p01, p012, p0123, originalShapeId, isCurve)
        }

        private fun splitRight(t: Float): BezierSegment {
            val p01 = lerp(p0, p1, t)
            val p12 = lerp(p1, p2, t)
            val p23 = lerp(p2, p3, t)
            val p012 = lerp(p01, p12, t)
            val p123 = lerp(p12, p23, t)
            val p0123 = lerp(p012, p123, t)
            return BezierSegment(p0123, p123, p23, p3, originalShapeId, isCurve)
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
                                    isCurve = true
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
                                    isCurve = false
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
                                    isCurve = true
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
                                    isCurve = false
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
            
            contourNodes.add(BezierNode(
                anchorX = currentSeg.p0.x,
                anchorY = currentSeg.p0.y,
                isCurve = prevSeg.isCurve,
                control1X = prevSeg.p2.x,
                control1Y = prevSeg.p2.y,
                control2X = currentSeg.p1.x,
                control2Y = currentSeg.p1.y,
                isMoveTo = (i == 0 && !isFirstContour)
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

        // 1. Check if the segment is a straight line
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

        if (maxDistToChord < 0.2f) {
            return listOf(BezierSegment(pStart, pStart + (pEnd - pStart) / 3f, pStart + (pEnd - pStart) * 2f / 3f, pEnd, "", false))
        }

        // 2. Try fitting a cubic Bezier to the sub-segment
        val step = minOf(3, end - start)
        val qStep = points[start + step]
        val dStart = qStep - pStart
        val lenStart = kotlin.math.hypot(dStart.x, dStart.y)
        val uA = if (lenStart > 0.01f) Offset(dStart.x / lenStart, dStart.y / lenStart) else Offset(dx / chordLen, dy / chordLen)

        val qNSub = points[end - step]
        val dEnd = pEnd - qNSub
        val lenEnd = kotlin.math.hypot(dEnd.x, dEnd.y)
        val uB = if (lenEnd > 0.01f) Offset(dEnd.x / lenEnd, dEnd.y / lenEnd) else Offset(dx / chordLen, dy / chordLen)

        val p1 = pStart + uA * (chordLen / 3f)
        val p2 = pEnd - uB * (chordLen / 3f)

        // Verify suitability of current Bezier fit
        var maxBezierError = 0f
        var worstBezierIdx = -1

        for (i in start + 1 until end) {
            val pt = points[i]
            val t = (i - start).toFloat() / (end - start).toFloat()
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
                val numSamples = (len / 1.5f).toInt().coerceIn(250, 600)
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
                    val activeSegments = fitCurveRecursive(uniquePoints, 0, uniquePoints.size - 1, tolerance = 0.35f, depth = 0)
                    val n = activeSegments.size
                    if (n >= 2) {
                        val finalizedSegments = activeSegments.toMutableList()
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
                        val contourNodes = buildNodesFromSegments(activeSegments, isFirstContour = isFirstMoveTo)
                        nodes.addAll(contourNodes)
                    }
                    isFirstMoveTo = false
                }
            }
            hasContour = pm.nextContour()
        }
        return nodes
    }

    fun applyBooleanOperation(opType: String) {
        val selected = shapes.filter { selectedShapeIds.contains(it.id) }
        if (selected.size < 2) return
        pushToUndoStack()

        // Keeps sorting matching the layout stack order
        val sortedSelected = shapes.filter { selected.any { sel -> sel.id == it.id } }
        
        var compositePath = preparePathForBoolean(sortedSelected[0].asComposePath())
        
        val op = when (opType) {
            "UNITE" -> PathOperation.Union
            "MINUS_FRONT" -> PathOperation.Difference
            "INTERSECT" -> PathOperation.Intersect
            "EXCLUDE" -> PathOperation.Xor
            else -> PathOperation.Union
        }

        for (i in 1 until sortedSelected.size) {
            val nextPath = preparePathForBoolean(sortedSelected[i].asComposePath())
            val result = Path()
            result.op(compositePath, nextPath, op)
            compositePath = result
        }

        // Extract original boundaries
        val origSegments = sortedSelected.flatMap { getOriginalBezierSegments(it) }
        val nodes = reconstructBezierNodesFromAndroidPath(compositePath.asAndroidPath(), origSegments)

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
            hasFill = true,
            fillColorHex = targetStyleShape.fillColorHex,
            fillAlpha = targetStyleShape.fillAlpha,
            layerOrder = targetStyleShape.layerOrder
        )

        shapes = shapes.map { s ->
            if (s.id == firstId) booleanShape else s
        }.filter { s -> s.id == newId || !selectedShapeIds.contains(s.id) }

        selectedShapeId = newId
        selectedShapeIds = setOf(newId)
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
        val serialized = convertShapesToJsonStr(shapes)
        sharedPrefs.edit().putString("saved_canvas_shapes", serialized).apply()
    }

    fun loadSavedProject() {
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
                val hasFill = extractJsonBoolean(itemJson, "hasFill") ?: false
                val fillColorHex = extractJsonString(itemJson, "fillColorHex") ?: "#00FF80"
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
                        hasFill = hasFill, fillColorHex = fillColorHex,
                        fillAlpha = fillAlpha, textContent = textContent, fontSize = fontSize,
                        isPathClosed = isPathClosed, freehandPoints = pts, bezierNodes = bNodes,
                        polygonSides = polygonSides, starPoints = starPoints, lineStyle = lineStyle,
                        radiusTL = radiusTL, radiusTR = radiusTR, radiusBL = radiusBL, radiusBR = radiusBR,
                        customCornerRadii = cRadii,
                        groupId = groupId
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }

    private fun extractJsonString(json: String, key: String): String? {
        val pattern = "\"$key\"\\s*:\\s*\"([^\"]*)\"".toRegex()
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
        val pattern = "\"$key\"\\s*:\\s*\\[(.*?)\\]".toRegex()
        return pattern.find(json)?.groupValues?.get(1)
    }
}
