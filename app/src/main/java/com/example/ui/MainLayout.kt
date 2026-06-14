package com.example.ui

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.model.ShapeType
import com.example.model.VectorShape
import com.example.viewmodel.PrimitiveType
import com.example.viewmodel.VectorTool
import com.example.viewmodel.VectorViewModel
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor

val BooleanExcludeIcon: ImageVector
    get() = ImageVector.Builder(
        name = "BooleanExclude",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = SolidColor(Color.Black),
            pathFillType = PathFillType.EvenOdd
        ) {
            moveTo(4f, 4f)
            lineTo(16f, 4f)
            lineTo(16f, 16f)
            lineTo(4f, 16f)
            close()
            moveTo(8f, 8f)
            lineTo(20f, 8f)
            lineTo(20f, 20f)
            lineTo(8f, 20f)
            close()
        }
    }.build()

val ExpandStrokeIcon: ImageVector
    get() = ImageVector.Builder(
        name = "ExpandStroke",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.5f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round
        ) {
            // Top-Left arrow
            moveTo(11.5f, 11.5f)
            lineTo(4.5f, 4.5f)
            moveTo(4.5f, 10f)
            lineTo(4.5f, 4.5f)
            lineTo(10f, 4.5f)

            // Top-Right arrow
            moveTo(12.5f, 11.5f)
            lineTo(19.5f, 4.5f)
            moveTo(14f, 4.5f)
            lineTo(19.5f, 4.5f)
            lineTo(19.5f, 10f)

            // Bottom-Left arrow
            moveTo(11.5f, 12.5f)
            lineTo(4.5f, 19.5f)
            moveTo(4.5f, 14f)
            lineTo(4.5f, 19.5f)
            lineTo(10f, 19.5f)

            // Bottom-Right arrow
            moveTo(12.5f, 12.5f)
            lineTo(19.5f, 19.5f)
            moveTo(14f, 19.5f)
            lineTo(19.5f, 19.5f)
            lineTo(19.5f, 14f)
        }
    }.build()

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainLayout(viewModel: VectorViewModel) {
    val context = LocalContext.current

    LaunchedEffect(viewModel.isSetupCompleted) {
        if (!viewModel.isSetupCompleted) {
            viewModel.refreshSavedProjects()
        }
    }

    var showArtboardColorPicker by remember { mutableStateOf(false) }
    var renameProjectTarget by remember { mutableStateOf<com.example.viewmodel.SavedProject?>(null) }
    var newProjectNameInput by remember { mutableStateOf("") }
    
    var artboardWidthInput by remember { mutableStateOf("") }
    var artboardHeightInput by remember { mutableStateOf("") }

    val fontLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            viewModel.importFontFromFile(context, uri)
            Toast.makeText(context, "Font loaded successfully!", Toast.LENGTH_SHORT).show()
        }
    }

    val importFileLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            viewModel.importFileFromUri(
                context = context,
                uri = uri,
                onSuccess = { msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                },
                onError = { err ->
                    Toast.makeText(context, "Gagal mengimport: $err", Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    // Dialog & overlay toggles
    var showMenuSheet by remember { mutableStateOf(false) }
    var showLayersPanel by remember { mutableStateOf(false) }
    var showColorPickerFill by remember { mutableStateOf(false) }
    var showColorPickerStroke by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showTextDialog by remember { mutableStateOf(false) }
    var showCustomSettingsDialog by remember { mutableStateOf(false) }
    var showArtboardSettingsDialog by remember { mutableStateOf(false) }
    var showSnappingPopup by remember { mutableStateOf(false) }
    var bottomBarExpandedLevel by remember { mutableStateOf(2) } // 0: Hidden, 1: Draw tools, 2: Design and Sliders, 3: Artwork Ops, 4: Boolean Actions
    var showBooleanInBottomScope by remember { mutableStateOf(false) }
    var showExpandOptionsPanel by remember { mutableStateOf(false) }
    var expandFillChecked by remember { mutableStateOf(true) }
    var expandStrokeChecked by remember { mutableStateOf(true) }
    
    // Text tools state
    var textInputState by remember { mutableStateOf("") }

    // Floating toolbar quick states
    var showPrimitiveSelector by remember { mutableStateOf(false) }
    var showAlignmentSelector by remember { mutableStateOf(false) }

    if (!viewModel.isSetupCompleted) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0F172A))
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                .safeDrawingPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Category,
                    contentDescription = "Logo",
                    tint = Color(0xFFFF6D00),
                    modifier = Modifier.size(54.dp)
                )
                Column {
                    Text(
                        text = "WAR MACHINE",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "VECTOR DESIGN STUDIO",
                        color = Color(0xFFFF6D00),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 3.sp
                    )
                }
            }

            Text(
                text = "Studio vektor profesional dengan layout presisi, layer canggih, & " +
                     "sketsa dinamis.",
                color = Color.Gray,
                fontSize = 11.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            // SECTION 1: CREATE NEW PROJECT
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "BUAT PROYEK BARU",
                        color = Color(0xFFFF6D00),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )

                    var newProjectName by remember { mutableStateOf("") }
                    var selectedWidth by remember { mutableStateOf("2000") }
                    var selectedHeight by remember { mutableStateOf("2000") }

                    OutlinedTextField(
                        value = newProjectName,
                        onValueChange = { newProjectName = it },
                        label = { Text("Nama Proyek", color = Color.Gray) },
                        placeholder = { Text("Tulis nama proyek anda...", color = Color.DarkGray) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White, focusedBorderColor = Color(0xFFFF6D00),
                            unfocusedTextColor = Color.White, unfocusedBorderColor = Color(0xFF475569)
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        text = "PILIH UKURAN ARTBOARD",
                        color = Color.LightGray,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            "Square" to (2000 to 2000),
                            "Full HD" to (1920 to 1080),
                            "Vertical" to (1080 to 1920),
                            "Icon" to (512 to 512)
                        ).forEach { (label, dims) ->
                            val isSel = selectedWidth == dims.first.toString() && selectedHeight == dims.second.toString()
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSel) Color(0xFFFF6D00) else Color(0xFF0F172A))
                                    .border(1.dp, if (isSel) Color.White else Color(0xFF334155), RoundedCornerShape(8.dp))
                                    .clickable {
                                        selectedWidth = dims.first.toString()
                                        selectedHeight = dims.second.toString()
                                    }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(label, color = if (isSel) Color.Black else Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    Text("${dims.first}px", color = if (isSel) Color.Black else Color.Gray, fontSize = 8.sp)
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = selectedWidth,
                            onValueChange = { selectedWidth = it },
                            label = { Text("Lebar (px)", color = Color.Gray) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White, focusedBorderColor = Color(0xFFFF6D00),
                                unfocusedTextColor = Color.White, unfocusedBorderColor = Color(0xFF475569)
                            ),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = selectedHeight,
                            onValueChange = { selectedHeight = it },
                            label = { Text("Tinggi (px)", color = Color.Gray) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White, focusedBorderColor = Color(0xFFFF6D00),
                                unfocusedTextColor = Color.White, unfocusedBorderColor = Color(0xFF475569)
                            ),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Button(
                        onClick = {
                            val w = selectedWidth.toFloatOrNull() ?: 2000f
                            val h = selectedHeight.toFloatOrNull() ?: 2000f
                            val name = if (newProjectName.isBlank()) "Proyek Vektor Saya" else newProjectName
                            viewModel.createNewProject(name, w, h)
                            Toast.makeText(context, "Proyek Baru Dibuat!", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6D00)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("BUAT PROYEK & KANVAS", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }

            // SECTION 2: MY PROJECTS FOLDER / PREVIOUS PROJECTS LIST
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "📂 FOLDER MY PROJECTS",
                            color = Color(0xFFFF6D00),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF0F172A))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${viewModel.savedProjectsList.size} Proyek",
                                color = Color.Green,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (viewModel.savedProjectsList.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF0F172A), RoundedCornerShape(12.dp))
                                .border(1.dp, Color(0xFF334155), RoundedCornerShape(12.dp))
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = "Folder Kosong",
                                tint = Color.Gray,
                                modifier = Modifier.size(44.dp)
                            )
                            Text(
                                "Belum Ada Proyek",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Semua proyek anda akan disimpan otomatis di folder ini secara realtime saat mengedit.",
                                color = Color.Gray,
                                fontSize = 10.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
                        }
                    } else {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            viewModel.savedProjectsList.forEach { project ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFF0F172A))
                                        .border(1.dp, Color(0xFF334155), RoundedCornerShape(12.dp))
                                        .clickable {
                                            viewModel.loadProject(project)
                                            Toast.makeText(context, "Proyek \"${project.name}\" Berhasil Dimuat!", Toast.LENGTH_SHORT).show()
                                        }
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ArtTrack,
                                            contentDescription = "Vector Art Icon",
                                            tint = Color(0xFFFF6D00),
                                            modifier = Modifier.size(28.dp)
                                        )
                                        Column {
                                            Text(
                                                text = project.name,
                                                color = Color.White,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text(
                                                    text = "${project.canvasWidth.toInt()}x${project.canvasHeight.toInt()} px",
                                                    color = Color.LightGray,
                                                    fontSize = 9.sp
                                                )
                                                Text(
                                                    text = "(${project.shapes.size} shapes)",
                                                    color = Color.Gray,
                                                    fontSize = 9.sp
                                                )
                                            }
                                        }
                                    }
                                    
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        IconButton(
                                            onClick = {
                                                renameProjectTarget = project
                                                newProjectNameInput = project.name
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Edit,
                                                contentDescription = "Rename Project",
                                                tint = Color(0xFF60A5FA),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        
                                        IconButton(
                                            onClick = {
                                                viewModel.deleteProject(project.id)
                                                Toast.makeText(context, "Proyek dihapus!", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Delete Project",
                                                tint = Color(0xFFEF4444),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    
                                }
                            }
                        }
                    }
                }
            }
            
            Text(
                text = "Designed by Irwan Setiadi • War Machine Vector Studio v02.2",
                color = Color(0xFF64748B),
                fontSize = 8.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A)) // Dark space/editor visual hierarchy background
    ) {
        // TOP APP BAR (Reference layout components)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .background(Color.White) // Styled exactly white like search background
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .height(56.hpx()), // Handled as hpx helper or just 56.dp
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Hamburger Menu
            IconButton(
                onClick = { showMenuSheet = !showMenuSheet },
                modifier = Modifier.testTag("menu_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "Document Settings Menu",
                    tint = Color(0xFF334155)
                )
            }

            // Undo / Redo buttons
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { viewModel.undo() },
                    enabled = viewModel.canUndo(),
                    modifier = Modifier.testTag("undo_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Undo,
                        contentDescription = "Undo Action",
                        tint = if (viewModel.canUndo()) Color(0xFF1E293B) else Color(0xFFCBD5E1)
                    )
                }
                IconButton(
                    onClick = { viewModel.redo() },
                    enabled = viewModel.canRedo(),
                    modifier = Modifier.testTag("redo_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Redo,
                        contentDescription = "Redo Action",
                        tint = if (viewModel.canRedo()) Color(0xFF1E293B) else Color(0xFFCBD5E1)
                    )
                }
            }

            // Delete (Trash can icon)
            val isPenNodeSelected = viewModel.currentTool == VectorTool.PEN && viewModel.activeEditNodeIndex != null
            val isDSNodeSelected = viewModel.currentTool == VectorTool.DIRECT_SELECTION && viewModel.selectedDirectSelectionNodes.isNotEmpty()
            val isShapeSelected = viewModel.selectedShapeId != null || viewModel.selectedShapeIds.isNotEmpty()

            IconButton(
                onClick = {
                    if (isPenNodeSelected || isDSNodeSelected) {
                        viewModel.deleteSelectedNode()
                        Toast.makeText(context, "Node Deleted", Toast.LENGTH_SHORT).show()
                    } else {
                        viewModel.deleteSelectedShape()
                        Toast.makeText(context, "Shape Deleted", Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = isPenNodeSelected || isDSNodeSelected || isShapeSelected,
                modifier = Modifier.testTag("delete_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete Selected",
                    tint = if (isPenNodeSelected || isDSNodeSelected || isShapeSelected) Color(0xFFEF4444) else Color(0xFFCBD5E1)
                )
            }

            // Export / Download (bracket with downward arrow)
            IconButton(
                onClick = { showExportDialog = true },
                modifier = Modifier.testTag("export_button")
            ) {
                Icon(
                    imageVector = Icons.Default.GetApp,
                    contentDescription = "Export Image / SVG",
                    tint = Color(0xFF334155)
                )
            }

            // Save File (Floppy Disk icon)
            IconButton(
                onClick = {
                    viewModel.saveProjectLocally()
                    Toast.makeText(context, "Desain Tersimpan!", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.testTag("save_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Save,
                    contentDescription = "Save Draft",
                    tint = Color(0xFF334155)
                )
            }

            // Color Swatches indicator widgets
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Fill Color (solid circle)
                val fillC = try {
                    Color(android.graphics.Color.parseColor(viewModel.currentFillColorHex))
                } catch (_: Exception) {
                    Color.Green
                }
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(if (viewModel.hasFillEnabled) fillC else Color.Transparent)
                        .border(2.dp, Color(0xFF475569), CircleShape)
                        .clickable { showColorPickerFill = true }
                        .testTag("fill_color_indicator"),
                    contentAlignment = Alignment.Center
                ) {
                    if (!viewModel.hasFillEnabled) {
                        // Drawing diagonal red forbidden bar for transparent
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .background(Color.Red)
                        )
                    }
                }

                // Stroke Color (hollow ring)
                val strokeC = try {
                    Color(android.graphics.Color.parseColor(viewModel.currentStrokeColorHex))
                } catch (_: Exception) {
                    Color.Black
                }
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(4.dp, strokeC, CircleShape)
                        .clickable { showColorPickerStroke = true }
                        .testTag("stroke_color_indicator")
                )
            }

            // Right: Layers stack panel toggle
            IconButton(
                onClick = { showLayersPanel = !showLayersPanel },
                modifier = Modifier.testTag("layers_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Layers,
                    contentDescription = "Layers Manager Panel",
                    tint = if (showLayersPanel) Color(0xFFFF6D00) else Color(0xFF334155)
                )
            }
        }

        // MAIN CANVAS AND VIEWPORT ARENA
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            // --- LEFT SIDEBAR TOOLBAR ---
            Column(
                modifier = Modifier
                    .width(62.dp)
                    .fillMaxHeight()
                    .background(Color(0xFF1E293B))
                    .border(width = 1.dp, color = Color(0xFF334155))
                    .padding(vertical = 12.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 1. Pointer (Selection) Tool
                SidebarToolButton(
                    icon = Icons.Default.NearMe,
                    label = "Select",
                    isSelected = viewModel.currentTool == VectorTool.POINTER,
                    onClick = {
                        viewModel.currentTool = VectorTool.POINTER
                        showBooleanInBottomScope = false
                        showExpandOptionsPanel = false
                    }
                )

                // 2. Direct Selection (Edit Node) Tool
                SidebarToolButton(
                    icon = Icons.Default.Adjust,
                    label = "Nodes",
                    isSelected = viewModel.currentTool == VectorTool.DIRECT_SELECTION,
                    onClick = {
                        viewModel.currentTool = VectorTool.DIRECT_SELECTION
                        showBooleanInBottomScope = false
                        showExpandOptionsPanel = false
                    }
                )

                // 3. Pen Tool
                SidebarToolButton(
                    icon = Icons.Default.Gesture,
                    label = "Pen",
                    isSelected = viewModel.currentTool == VectorTool.PEN,
                    onClick = {
                        viewModel.currentTool = VectorTool.PEN
                        viewModel.selectedShapeId = null
                        showBooleanInBottomScope = false
                        showExpandOptionsPanel = false
                    }
                )

                // 4. Shapes Tool
                SidebarToolButton(
                    icon = Icons.Default.Category,
                    label = "Shapes",
                    isSelected = viewModel.currentTool == VectorTool.SHAPES,
                    onClick = {
                        viewModel.currentTool = VectorTool.SHAPES
                        viewModel.selectedShapeId = null
                        showPrimitiveSelector = !showPrimitiveSelector
                        showBooleanInBottomScope = false
                        showExpandOptionsPanel = false
                    }
                )

                // 5. Paint Bucket Tool
                SidebarToolButton(
                    icon = Icons.Default.FormatColorFill,
                    label = "Bucket",
                    isSelected = viewModel.currentTool == VectorTool.BUCKET,
                    onClick = {
                        viewModel.currentTool = VectorTool.BUCKET
                        viewModel.selectedShapeId = null
                        showColorPickerFill = true
                        showBooleanInBottomScope = false
                        showExpandOptionsPanel = false
                    }
                )

                // 7. Rounded Corner Tool
                SidebarToolButton(
                    icon = Icons.Default.CropFree,
                    label = "Rounded",
                    isSelected = viewModel.currentTool == VectorTool.ROUNDED_CORNER,
                    onClick = {
                        viewModel.currentTool = VectorTool.ROUNDED_CORNER
                        showBooleanInBottomScope = false
                        showExpandOptionsPanel = false
                    }
                )

                // 8. Expand Stroke Tool
                SidebarToolButton(
                    icon = ExpandStrokeIcon,
                    label = "Expand",
                    isSelected = showExpandOptionsPanel,
                    onClick = {
                        val selectedCount = viewModel.selectedShapeIds.size
                        if (selectedCount == 0) {
                            Toast.makeText(context, "Pilih objek terlebih dahulu menggunakan selection tool.", Toast.LENGTH_SHORT).show()
                        } else {
                            showExpandOptionsPanel = !showExpandOptionsPanel
                            showBooleanInBottomScope = false
                        }
                    }
                )
            }

            // --- RIGHT SIDE: MAIN CANVAS AND VIEWPORT ARENA ---
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                // Workspace canvas
                VectorCanvas(
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize().clipToBounds()
                )

                if (viewModel.currentTool == VectorTool.DIRECT_SELECTION) {
                    var expandedEditMode by remember { mutableStateOf(false) }
                    var showNodeTypeMenu by remember { mutableStateOf(false) }
                    
                    val selectedShape = viewModel.shapes.find { it.id == viewModel.selectedShapeId }
                    val activeNodeIndex = viewModel.selectedDirectSelectionNodes.firstOrNull()
                    val selectedNode = if (selectedShape != null && selectedShape.type == com.example.model.ShapeType.BEZIER_PATH && activeNodeIndex != null) {
                        selectedShape.bezierNodes.getOrNull(activeNodeIndex)
                    } else null
                    
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF475569)),
                        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 16.dp)
                            .wrapContentSize()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Section 1: Edit/Tool Mode
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "Edit:",
                                    color = Color.White,
                                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium
                                )
                                Box {
                                    androidx.compose.material3.Button(
                                        onClick = { expandedEditMode = true },
                                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFF334155),
                                            contentColor = Color.White
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        modifier = Modifier.height(32.dp).testTag("node_edit_dropdown_button")
                                    ) {
                                        val modeLabel = when(viewModel.currentNodeEditMode) {
                                            com.example.viewmodel.NodeEditMode.NONE -> "None"
                                            com.example.viewmodel.NodeEditMode.ADD -> "+ Node"
                                            com.example.viewmodel.NodeEditMode.REMOVE -> "- Node"
                                            com.example.viewmodel.NodeEditMode.CUT -> "Cut"
                                            com.example.viewmodel.NodeEditMode.SPLIT -> "Pisah"
                                            com.example.viewmodel.NodeEditMode.CLOSE -> "Close"
                                        }
                                        Text(
                                            text = modeLabel,
                                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                            fontSize = 12.sp
                                        )
                                        Spacer(modifier = Modifier.width(2.dp))
                                        Icon(
                                            imageVector = Icons.Default.ArrowDropDown,
                                            contentDescription = "Dropdown indicator",
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    
                                    androidx.compose.material3.DropdownMenu(
                                        expanded = expandedEditMode,
                                        onDismissRequest = { expandedEditMode = false },
                                        modifier = Modifier.background(Color(0xFF1E293B)).border(width = 0.5.dp, color = Color(0xFF475569))
                                    ) {
                                        val modes = listOf(
                                            com.example.viewmodel.NodeEditMode.NONE to "None",
                                            com.example.viewmodel.NodeEditMode.ADD to "+ Node",
                                            com.example.viewmodel.NodeEditMode.REMOVE to "- Node",
                                            com.example.viewmodel.NodeEditMode.CUT to "Cut",
                                            com.example.viewmodel.NodeEditMode.SPLIT to "Pisah",
                                            com.example.viewmodel.NodeEditMode.CLOSE to "Close Path"
                                        )
                                        modes.forEach { (mode, label) ->
                                            androidx.compose.material3.DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        text = label,
                                                        color = if (viewModel.currentNodeEditMode == mode) Color(0xFFFF6D00) else Color.White,
                                                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
                                                    )
                                                },
                                                onClick = {
                                                    viewModel.currentNodeEditMode = mode
                                                    expandedEditMode = false
                                                    if (mode == com.example.viewmodel.NodeEditMode.CLOSE) {
                                                        val sId = viewModel.selectedShapeId
                                                        if (sId != null) {
                                                            viewModel.closePath(sId)
                                                            Toast.makeText(context, "Path Closed", Toast.LENGTH_SHORT).show()
                                                        } else {
                                                            Toast.makeText(context, "Silakan pilih path terlebih dahulu", Toast.LENGTH_SHORT).show()
                                                        }
                                                        viewModel.currentNodeEditMode = com.example.viewmodel.NodeEditMode.NONE
                                                    }
                                                },
                                                modifier = Modifier.testTag("node_edit_item_${mode.name}")
                                            )
                                        }
                                    }
                                }
                            }
                            
                            // Section 2: Node Type (Show dynamic dropdown only if a node is selected)
                            if (activeNodeIndex != null) {
                                Spacer(
                                    modifier = Modifier
                                        .height(20.dp)
                                        .width(1.dp)
                                        .background(Color(0xFF475569))
                                )
                                
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "Tipe:",
                                        color = Color.White,
                                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium
                                    )
                                    val currentNodeType = selectedNode?.nodeType ?: "BEBAS"
                                    val nodeLabel = when (currentNodeType) {
                                        "BEBAS" -> "Bebas"
                                        "ASIMETRIS" -> "Asimetris"
                                        "SIMETRIS" -> "Simetris"
                                        "HALUS" -> "Halus"
                                        else -> "Bebas"
                                    }
                                    Box {
                                        androidx.compose.material3.Button(
                                            onClick = { showNodeTypeMenu = true },
                                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFF334155),
                                                contentColor = Color.White
                                            ),
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            modifier = Modifier.height(32.dp).testTag("node_type_dropdown_btn")
                                        ) {
                                            Text(
                                                text = nodeLabel,
                                                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                                fontSize = 12.sp
                                            )
                                            Spacer(modifier = Modifier.width(2.dp))
                                            Icon(
                                                imageVector = Icons.Default.ArrowDropDown,
                                                contentDescription = "Dropdown indicators",
                                                tint = Color.White,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                        
                                        androidx.compose.material3.DropdownMenu(
                                            expanded = showNodeTypeMenu,
                                            onDismissRequest = { showNodeTypeMenu = false },
                                            modifier = Modifier.background(Color(0xFF1E293B)).border(width = 0.5.dp, color = Color(0xFF475569))
                                        ) {
                                            val types = listOf(
                                                "BEBAS" to "Bebas",
                                                "ASIMETRIS" to "Asimetris",
                                                "SIMETRIS" to "Simetris",
                                                "HALUS" to "Halus"
                                            )
                                            types.forEach { (typeKey, typeLabel) ->
                                                androidx.compose.material3.DropdownMenuItem(
                                                    text = {
                                                        Text(
                                                            text = typeLabel,
                                                            color = if (currentNodeType == typeKey) Color(0xFFFF6D00) else Color.White,
                                                            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
                                                        )
                                                    },
                                                    onClick = {
                                                        showNodeTypeMenu = false
                                                        val sId = viewModel.selectedShapeId
                                                        val idxs = viewModel.selectedDirectSelectionNodes
                                                        if (sId != null && idxs.isNotEmpty()) {
                                                            idxs.forEach { idx ->
                                                                viewModel.setShapeNodeType(sId, idx, typeKey)
                                                            }
                                                        }
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

            // 1. Primitive Shapes Selection panel
            androidx.compose.animation.AnimatedVisibility(
                visible = viewModel.currentTool == VectorTool.SHAPES && showPrimitiveSelector,
                enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF475569)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    modifier = Modifier
                        .width(340.dp)
                        .wrapContentHeight()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Lock / Unlock aspect ratio selector
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Shape Aspect:",
                                color = Color.White,
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            
                            // Lock Button (default)
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (viewModel.isAspectLocked) Color(0xFFFF6D00) else Color(0xFF0F172A))
                                    .border(1.dp, if (viewModel.isAspectLocked) Color.White else Color(0xFF334155), RoundedCornerShape(8.dp))
                                    .clickable {
                                        viewModel.isAspectLocked = true
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                androidx.compose.material3.Icon(
                                    imageVector = androidx.compose.material.icons.Icons.Default.Lock,
                                    contentDescription = "Lock",
                                    tint = if (viewModel.isAspectLocked) Color.White else Color(0xFF94A3B8),
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "Lock",
                                    color = if (viewModel.isAspectLocked) Color.White else Color(0xFF94A3B8),
                                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall
                                )
                            }

                            // Unlock Button
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (!viewModel.isAspectLocked) Color(0xFFFF6D00) else Color(0xFF0F172A))
                                    .border(1.dp, if (!viewModel.isAspectLocked) Color.White else Color(0xFF334155), RoundedCornerShape(8.dp))
                                    .clickable {
                                        viewModel.isAspectLocked = false
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                androidx.compose.material3.Icon(
                                    imageVector = androidx.compose.material.icons.Icons.Default.LockOpen,
                                    contentDescription = "Unlock",
                                    tint = if (!viewModel.isAspectLocked) Color.White else Color(0xFF94A3B8),
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "Unlock",
                                    color = if (!viewModel.isAspectLocked) Color.White else Color(0xFF94A3B8),
                                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall
                                )
                            }
                        }

                        // Primitive list with previews!
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf(
                                PrimitiveType.RECTANGLE to "Box",
                                PrimitiveType.ELLIPSE to "Circle",
                                PrimitiveType.TRIANGLE to "Tri",
                                PrimitiveType.POLYGON to "Poly",
                                PrimitiveType.STAR to "Star",
                                PrimitiveType.LINE to "Line"
                            ).forEach { (type, shortLabel) ->
                                val isSel = viewModel.activePrimitiveType == type
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSel) Color(0xFFFF6D00) else Color(0xFF0F172A))
                                        .border(1.dp, if (isSel) Color.White else Color(0xFF334155), RoundedCornerShape(8.dp))
                                        .clickable {
                                            viewModel.activePrimitiveType = type
                                        }
                                        .padding(horizontal = 6.dp, vertical = 8.dp)
                                        .width(42.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    // Mini Shape preview
                                    Canvas(modifier = Modifier.size(24.dp)) {
                                        val fillBrush = if (isSel) Color.Black else Color(0xFFFF6D00)
                                        
                                        when (type) {
                                            PrimitiveType.RECTANGLE -> {
                                                drawRect(
                                                    color = fillBrush,
                                                    size = size,
                                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                                                )
                                            }
                                            PrimitiveType.ELLIPSE -> {
                                                drawOval(
                                                    color = fillBrush,
                                                    size = size,
                                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                                                )
                                            }
                                            PrimitiveType.TRIANGLE -> {
                                                val path = Path().apply {
                                                    moveTo(size.width / 2f, 2f)
                                                    lineTo(size.width - 2f, size.height - 2f)
                                                    lineTo(2f, size.height - 2f)
                                                    close()
                                                }
                                                drawPath(
                                                    path = path,
                                                    color = fillBrush,
                                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                                                )
                                            }
                                            PrimitiveType.POLYGON -> {
                                                val path = Path().apply {
                                                    moveTo(size.width / 2f, 2f)
                                                    lineTo(size.width - 2f, size.height * 0.4f)
                                                    lineTo(size.width * 0.8f, size.height - 2f)
                                                    lineTo(size.width * 0.2f, size.height - 2f)
                                                    lineTo(2f, size.height * 0.4f)
                                                    close()
                                                }
                                                drawPath(
                                                    path = path,
                                                    color = fillBrush,
                                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                                                )
                                            }
                                            PrimitiveType.STAR -> {
                                                val path = Path().apply {
                                                    moveTo(size.width / 2f, 2f)
                                                    lineTo(size.width * 0.65f, size.height * 0.35f)
                                                    lineTo(size.width - 2f, size.height * 0.4f)
                                                    lineTo(size.width * 0.7f, size.height * 0.65f)
                                                    lineTo(size.width * 0.8f, size.height - 2f)
                                                    lineTo(size.width / 2f, size.height * 0.75f)
                                                    lineTo(size.width * 0.2f, size.height - 2f)
                                                    lineTo(size.width * 0.3f, size.height * 0.65f)
                                                    lineTo(2f, size.height * 0.4f)
                                                    lineTo(size.width * 0.35f, size.height * 0.35f)
                                                    close()
                                                }
                                                drawPath(
                                                    path = path,
                                                    color = fillBrush,
                                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
                                                )
                                            }
                                            PrimitiveType.LINE -> {
                                                drawLine(
                                                    color = fillBrush,
                                                    start = Offset(2f, size.height - 2f),
                                                    end = Offset(size.width - 2f, 2f),
                                                    strokeWidth = 2.dp.toPx()
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Options if POLYGON or STAR is active!
                        if (viewModel.activePrimitiveType == PrimitiveType.POLYGON) {
                            Divider(color = Color(0xFF334155))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Sisi Poligon (e.g. Hexagon):",
                                    color = Color.White,
                                    fontSize = 11.sp
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    IconButton(
                                        onClick = { viewModel.currentPolygonSides = (viewModel.currentPolygonSides - 1).coerceAtLeast(3) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Remove, "Less sides", tint = Color.White, modifier = Modifier.size(16.dp))
                                    }
                                    Text(
                                        text = "${viewModel.currentPolygonSides}",
                                        color = Color(0xFFFF6D00),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    IconButton(
                                        onClick = { viewModel.currentPolygonSides = (viewModel.currentPolygonSides + 1).coerceAtMost(20) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Add, "More sides", tint = Color.White, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        } else if (viewModel.activePrimitiveType == PrimitiveType.STAR) {
                            Divider(color = Color(0xFF334155))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Sisi Bintang (Points):",
                                    color = Color.White,
                                    fontSize = 11.sp
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    IconButton(
                                        onClick = { viewModel.currentStarPoints = (viewModel.currentStarPoints - 1).coerceAtLeast(3) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Remove, "Less points", tint = Color.White, modifier = Modifier.size(16.dp))
                                    }
                                    Text(
                                        text = "${viewModel.currentStarPoints}",
                                        color = Color(0xFFFF6D00),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    IconButton(
                                        onClick = { viewModel.currentStarPoints = (viewModel.currentStarPoints + 1).coerceAtMost(30) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Add, "More points", tint = Color.White, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 2. Align selection bar
            androidx.compose.animation.AnimatedVisibility(
                visible = showAlignmentSelector,
                enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xF21E293B)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(bottom = 6.dp)
                        ) {
                            Text("Basis Align:", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            FilterChip(
                                selected = viewModel.alignBasisIsCanvas,
                                onClick = { viewModel.alignBasisIsCanvas = true },
                                label = { Text("Canvas", fontSize = 10.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFFFF6D00),
                                    selectedLabelColor = Color.Black,
                                    labelColor = Color.LightGray
                                )
                            )
                            FilterChip(
                                selected = !viewModel.alignBasisIsCanvas,
                                onClick = { viewModel.alignBasisIsCanvas = false },
                                label = { Text("Object", fontSize = 10.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFFFF6D00),
                                    selectedLabelColor = Color.Black,
                                    labelColor = Color.LightGray
                                )
                            )
                        }
                        Text(
                            text = if (viewModel.alignBasisIsCanvas) "Instant Align to Canvas:" else "Instant Align to Closest Object:",
                            color = Color.LightGray,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            AlignButton("LEFT", Icons.Default.AlignHorizontalLeft) { viewModel.alignSelectedShape("LEFT") }
                            AlignButton("C.H", Icons.Default.AlignHorizontalCenter) { viewModel.alignSelectedShape("CENTER_HORIZ") }
                            AlignButton("RIGHT", Icons.Default.AlignHorizontalRight) { viewModel.alignSelectedShape("RIGHT") }
                            AlignButton("TOP", Icons.Default.AlignVerticalTop) { viewModel.alignSelectedShape("TOP") }
                            AlignButton("C.V", Icons.Default.AlignVerticalCenter) { viewModel.alignSelectedShape("CENTER_VERT") }
                            AlignButton("BOTTOM", Icons.Default.AlignVerticalBottom) { viewModel.alignSelectedShape("BOTTOM") }
                        }
                    }
                }
            }




            
            // Pen tool contextual actions (Checklist and Close Path) next to the "dropdown tool edit" place
            if (viewModel.currentTool == VectorTool.PEN && viewModel.activeBezierNodes.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 120.dp, top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(0xE61E293B), shape = CircleShape)
                            .border(1.dp, Color.White, shape = CircleShape)
                            .clickable {
                                viewModel.finalizeBezierPath(isClosed = true)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Loop,
                            contentDescription = "Close & Fill Path",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color(0xE61E293B), shape = CircleShape)
                            .border(1.dp, Color.White, shape = CircleShape)
                            .clickable {
                                viewModel.finalizeBezierPath(isClosed = false)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Finish Open Path",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }

        // HORIZONTAL SLIDERS SECTION (Stroke size and transparent alpha connected directly to selected shapes)
        if (bottomBarExpandedLevel >= 2) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E293B)) // Standard editor toolbar bar
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (showBooleanInBottomScope) {
                    // --- BOOLEAN OPERATIONS PANEL ---
                    val canApplyBoolean = viewModel.selectedShapeIds.size >= 2
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (canApplyBoolean) "OPERASI BOOLEAN PATH" else "Operasi Boolean (Pilih 2+ Objek)",
                                color = if (canApplyBoolean) Color(0xFFFF6D00) else Color.LightGray,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Tutup [X]",
                                color = Color.LightGray,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clickable { showBooleanInBottomScope = false }
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 1. Unite Button
                            Button(
                                onClick = {
                                    if (canApplyBoolean) {
                                        viewModel.applyBooleanOperation("UNITE")
                                        Toast.makeText(context, "Path United Successfully!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Pilih minimal 2 objek!", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                enabled = canApplyBoolean,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFFF6D00),
                                    disabledContainerColor = Color(0xFF334155)
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f).height(32.dp)
                            ) {
                                Text("Unite", color = if (canApplyBoolean) Color.Black else Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }

                            // 2. Minus Front Button
                            Button(
                                onClick = {
                                    if (canApplyBoolean) {
                                        viewModel.applyBooleanOperation("MINUS_FRONT")
                                        Toast.makeText(context, "Front Path Subtracted!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Pilih minimal 2 objek!", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                enabled = canApplyBoolean,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFFF5722),
                                    disabledContainerColor = Color(0xFF334155)
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f).height(32.dp)
                            ) {
                                Text("Minus", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }

                            // 3. Intersect Button
                            Button(
                                onClick = {
                                    if (canApplyBoolean) {
                                        viewModel.applyBooleanOperation("INTERSECT")
                                        Toast.makeText(context, "Path Intersected!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Pilih minimal 2 objek!", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                enabled = canApplyBoolean,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF6366F1),
                                    disabledContainerColor = Color(0xFF334155)
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f).height(32.dp)
                            ) {
                                Text("Intersect", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }

                            // 4. Exclude Button
                            Button(
                                onClick = {
                                    if (canApplyBoolean) {
                                        viewModel.applyBooleanOperation("EXCLUDE")
                                        Toast.makeText(context, "Exclude Xor Completed!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Pilih minimal 2 objek!", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                enabled = canApplyBoolean,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFEC4899),
                                    disabledContainerColor = Color(0xFF334155)
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f).height(32.dp)
                            ) {
                                Text("Exclude", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // ROW 2: Advanced Boolean Operations
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // 5. Divide
                            Button(
                                onClick = {
                                    if (canApplyBoolean) {
                                        viewModel.applyBooleanOperation("DIVIDE")
                                        Toast.makeText(context, "Paths Divided!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Pilih minimal 2 objek!", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                enabled = canApplyBoolean,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF10B981),
                                    disabledContainerColor = Color(0xFF334155)
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f).height(32.dp)
                            ) {
                                Text("Divide", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }

                            // 6. Trim
                            Button(
                                onClick = {
                                    if (canApplyBoolean) {
                                        viewModel.applyBooleanOperation("TRIM")
                                        Toast.makeText(context, "Paths Trimmed!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Pilih minimal 2 objek!", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                enabled = canApplyBoolean,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFF59E0B),
                                    disabledContainerColor = Color(0xFF334155)
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f).height(32.dp)
                            ) {
                                Text("Trim", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else if (showExpandOptionsPanel) {
                    // --- EXPAND OPTIONS PANEL ---
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = ExpandStrokeIcon,
                                    contentDescription = null,
                                    tint = Color(0xFFFF6D00),
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "EXPAND OBJECT OPTIONS",
                                    color = Color(0xFFFF6D00),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = "Tutup [X]",
                                color = Color.LightGray,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clickable { showExpandOptionsPanel = false }
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }

                        // Checkboxes Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Checkbox Fill
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable { expandFillChecked = !expandFillChecked }
                                    .padding(vertical = 2.dp, horizontal = 4.dp)
                            ) {
                                Checkbox(
                                    checked = expandFillChecked,
                                    onCheckedChange = { expandFillChecked = it },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = Color(0xFFFF6D00),
                                        checkmarkColor = Color.Black
                                    )
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Fill", color = Color.White, fontSize = 12.sp)
                            }

                            // Checkbox Stroke
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable { expandStrokeChecked = !expandStrokeChecked }
                                    .padding(vertical = 2.dp, horizontal = 4.dp)
                            ) {
                                Checkbox(
                                    checked = expandStrokeChecked,
                                    onCheckedChange = { expandStrokeChecked = it },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = Color(0xFFFF6D00),
                                        checkmarkColor = Color.Black
                                    )
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Stroke", color = Color.White, fontSize = 12.sp)
                            }
                        }

                        // Submit Expand Button
                        Button(
                            onClick = {
                                if (!expandFillChecked && !expandStrokeChecked) {
                                    Toast.makeText(context, "Pilih setidaknya salah satu (Fill atau Stroke)!", Toast.LENGTH_SHORT).show()
                                } else {
                                    viewModel.expandSelectedShapes(expandFill = expandFillChecked, expandStroke = expandStrokeChecked)
                                    Toast.makeText(context, "Objek berhasil diperluas.", Toast.LENGTH_SHORT).show()
                                    showExpandOptionsPanel = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6D00)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().height(36.dp)
                        ) {
                            Text(
                                "EXPAND",
                                color = Color.Black,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                } else {
                    // --- DYNAMIC/CONTEXTUAL SLIDERS SECTION ---
                    when (viewModel.currentTool) {
                        VectorTool.ROUNDED_CORNER -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CropFree,
                                        contentDescription = null,
                                        tint = Color(0xFFFF5722),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = if (viewModel.selectedRoundedCornerIndex != null) {
                                            "Sudut #${viewModel.selectedRoundedCornerIndex!! + 1}"
                                        } else {
                                            "Semua Sudut"
                                        },
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Row(
                                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    val currentRadiusVal = if (viewModel.selectedRoundedCornerIndex != null) {
                                        val shVal = viewModel.shapes.find { it.id == viewModel.selectedShapeId }
                                        if (shVal != null) {
                                            if (shVal.type == com.example.model.ShapeType.RECTANGLE) {
                                                when (viewModel.selectedRoundedCornerIndex!!) {
                                                    0 -> shVal.radiusTL
                                                    1 -> shVal.radiusTR
                                                    2 -> shVal.radiusBR
                                                    3 -> shVal.radiusBL
                                                    else -> shVal.customCornerRadii.getOrNull(viewModel.selectedRoundedCornerIndex!!) ?: 0f
                                                }
                                            } else {
                                                shVal.customCornerRadii.getOrNull(viewModel.selectedRoundedCornerIndex!!) ?: 0f
                                            }
                                        } else 0f
                                    } else {
                                        val shVal = viewModel.shapes.find { it.id == viewModel.selectedShapeId }
                                        if (shVal != null) {
                                            if (shVal.type == com.example.model.ShapeType.RECTANGLE) {
                                                shVal.radiusTL
                                            } else {
                                                shVal.customCornerRadii.firstOrNull() ?: 0f
                                            }
                                        } else 0f
                                    }

                                    Text(
                                        text = "${currentRadiusVal.toInt()}px",
                                        color = Color(0xFFFF5722),
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.width(42.dp)
                                    )

                                    Slider(
                                        value = currentRadiusVal,
                                        onValueChange = { radius ->
                                            viewModel.manualCornerRadiusText = radius.toInt().toString()
                                            if (viewModel.selectedRoundedCornerIndex != null) {
                                                viewModel.updateSpecificCornerRadius(viewModel.selectedRoundedCornerIndex!!, radius)
                                            } else {
                                                viewModel.updateSelectedShapeCornerRadius(radius)
                                            }
                                        },
                                        onValueChangeFinished = {
                                            viewModel.bakeBezierCorners()
                                        },
                                        valueRange = 0f..250f,
                                        colors = SliderDefaults.colors(
                                            thumbColor = Color(0xFFFF5722),
                                            activeTrackColor = Color(0xFFFF5722),
                                            inactiveTrackColor = Color(0xFF475569)
                                        ),
                                        modifier = Modifier.weight(1f).height(24.dp)
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
                                    Text("Radius:", color = Color.Gray, fontSize = 11.sp)
                                    val textVal = viewModel.manualCornerRadiusText
                                    androidx.compose.foundation.text.BasicTextField(
                                        value = textVal,
                                        onValueChange = { input ->
                                            val cleanInput = input.filter { it.isDigit() }
                                            viewModel.manualCornerRadiusText = cleanInput
                                            val radius = cleanInput.toFloatOrNull() ?: 0f
                                            if (viewModel.selectedRoundedCornerIndex != null) {
                                                viewModel.updateSpecificCornerRadius(viewModel.selectedRoundedCornerIndex!!, radius)
                                            } else {
                                                viewModel.updateSelectedShapeCornerRadius(radius)
                                            }
                                        },
                                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { 
                                            viewModel.bakeBezierCorners()
                                            focusManager.clearFocus()
                                        }),
                                        textStyle = androidx.compose.ui.text.TextStyle(
                                            color = Color(0xFFFF5722),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace,
                                            textAlign = TextAlign.Center
                                        ),
                                        modifier = Modifier
                                            .width(46.dp)
                                            .height(24.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color(0xFF0F172A))
                                            .border(1.dp, Color(0xFF475569), RoundedCornerShape(4.dp))
                                            .padding(top = 2.dp),
                                        singleLine = true
                                    )
                                }
                            }
                        }

                        VectorTool.BUCKET -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "Opacity Cat:",
                                    color = Color.LightGray,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(72.dp)
                                )

                                Text(
                                    text = "${(viewModel.currentFillAlpha * 100).toInt()}%",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.width(36.dp),
                                    textAlign = TextAlign.End
                                )

                                Box(
                                    modifier = Modifier.weight(1f).height(20.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(4.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(
                                                brush = Brush.linearGradient(
                                                    colors = listOf(Color(0x22FFFFFF), Color(0xAAFFFFFF))
                                                )
                                            )
                                    )
                                    Slider(
                                        value = viewModel.currentFillAlpha,
                                        onValueChange = {
                                            viewModel.currentFillAlpha = it
                                            if (viewModel.selectedShapeId != null) {
                                                viewModel.updateSelectedShapeStyle()
                                            }
                                        },
                                        valueRange = 0f..1f,
                                        colors = SliderDefaults.colors(
                                            thumbColor = Color.White,
                                            activeTrackColor = Color.Transparent,
                                            inactiveTrackColor = Color.Transparent
                                        ),
                                        modifier = Modifier.height(20.dp)
                                    )
                                }
                            }
                        }

                        VectorTool.SHAPES -> {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                StrokeWidthAndOpacitySlidersSection(viewModel)
                                
                                if (viewModel.activePrimitiveType == PrimitiveType.POLYGON) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().height(28.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Sisi Poligon:",
                                            color = Color.LightGray,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            IconButton(
                                                onClick = { viewModel.currentPolygonSides = (viewModel.currentPolygonSides - 1).coerceAtLeast(3) },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(Icons.Default.Remove, "Less sides", tint = Color.White, modifier = Modifier.size(16.dp))
                                            }
                                            Text(
                                                text = "${viewModel.currentPolygonSides}",
                                                color = Color(0xFFFF6D00),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            IconButton(
                                                onClick = { viewModel.currentPolygonSides = (viewModel.currentPolygonSides + 1).coerceAtMost(20) },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(Icons.Default.Add, "More sides", tint = Color.White, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    }
                                } else if (viewModel.activePrimitiveType == PrimitiveType.STAR) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().height(28.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Sisi Bintang (Points):",
                                            color = Color.LightGray,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            IconButton(
                                                onClick = { viewModel.currentStarPoints = (viewModel.currentStarPoints - 1).coerceAtLeast(3) },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(Icons.Default.Remove, "Less points", tint = Color.White, modifier = Modifier.size(16.dp))
                                            }
                                            Text(
                                                text = "${viewModel.currentStarPoints}",
                                                color = Color(0xFFFF6D00),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            IconButton(
                                                onClick = { viewModel.currentStarPoints = (viewModel.currentStarPoints + 1).coerceAtMost(30) },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(Icons.Default.Add, "More points", tint = Color.White, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        VectorTool.PEN -> {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                StrokeWidthAndOpacitySlidersSection(viewModel)
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Gaya Garis:", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    listOf("SOLID", "DASHED", "DOTTED").forEach { style ->
                                        val isSel = viewModel.currentLineStyle == style
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(22.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(if (isSel) Color(0xFFFF6D00) else Color(0xFF0F172A))
                                                .border(1.dp, if (isSel) Color(0xFFFF6D00) else Color(0xFF475569), RoundedCornerShape(4.dp))
                                                .clickable {
                                                    viewModel.currentLineStyle = style
                                                    if (viewModel.selectedShapeId != null) {
                                                        viewModel.updateSelectedShapeStyle()
                                                    }
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(style, color = if (isSel) Color.Black else Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }

                        else -> {
                            val isShapeSelected = viewModel.selectedShapeId != null || viewModel.selectedShapeIds.isNotEmpty()
                            if (isShapeSelected) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "Ubah Style Objek Terpilih:",
                                        color = Color(0xFFFF6D00),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    StrokeWidthAndOpacitySlidersSection(viewModel)
                                }
                            }
                        }
                    }
                }
            }
        }

        // MULTI-LEVEL DYNAMIC ROTATABLE AND DRAGGABLE BOTTOM DRAWER TOOLBAR
        val currentLevel = bottomBarExpandedLevel

        // A small pull-up Pill Floating Action Button if the bar is hidden (Level 0)
        if (currentLevel == 0) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Transparent)
                    .padding(bottom = 8.dp)
                    .navigationBarsPadding(),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .width(165.dp)
                        .height(36.dp)
                        .clickable { bottomBarExpandedLevel = 2 }
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowUpward,
                            contentDescription = "Expand Toolbar",
                            tint = Color(0xFFFF6D00),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Tarik Toolbar Atas",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        } else {
            // Level >= 1 bottom toolbar drawer
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E293B))
                    .border(
                        width = 1.dp,
                        color = Color(0xFF475569),
                        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                    )
                    .navigationBarsPadding()
            ) {
                // Wide Touch Pill Handle supporting vertical drags & taps to collapse/expand
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .clickable {
                            // Cycle through levels on tap or simple click between Minimized and Maximized!
                            bottomBarExpandedLevel = if (bottomBarExpandedLevel >= 2) 1 else bottomBarExpandedLevel + 1
                        }
                        .pointerInput(Unit) {
                            var accumulatedDragY = 0f
                            detectDragGestures(
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    accumulatedDragY += dragAmount.y
                                    if (accumulatedDragY > 40f) {
                                        if (bottomBarExpandedLevel > 0) {
                                            bottomBarExpandedLevel--
                                        }
                                        accumulatedDragY = 0f
                                    } else if (accumulatedDragY < -40f) {
                                        if (bottomBarExpandedLevel < 2) {
                                            bottomBarExpandedLevel++
                                        }
                                        accumulatedDragY = 0f
                                    }
                                },
                                onDragEnd = { accumulatedDragY = 0f }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .width(60.dp)
                                .height(5.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color(0xFF64748B))
                        )
                    }
                }

                // ROW: GENERAL ARTWORK OPERATIONS AND MANIPULATIONS (Visible in Level >= 2)
                if (currentLevel >= 2) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0F172A))
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = 12.dp, top = 2.dp, start = 12.dp, end = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. Boolean Operations
                        IconButtonWithLabel(
                            icon = BooleanExcludeIcon,
                            label = "Boolean",
                            onClick = {
                                showBooleanInBottomScope = !showBooleanInBottomScope
                                showExpandOptionsPanel = false
                            }
                        )

                        // 4. Group Selected
                        IconButtonWithLabel(
                            icon = Icons.Default.Layers,
                            label = "Group",
                            onClick = {
                                viewModel.groupSelectedShapes()
                                Toast.makeText(context, "Selected shapes grouped together!", Toast.LENGTH_SHORT).show()
                            }
                        )

                        // 5. Ungroup Selected
                        IconButtonWithLabel(
                            icon = Icons.Default.LayersClear,
                            label = "Ungroup",
                            onClick = {
                                viewModel.ungroupSelectedShapes()
                                Toast.makeText(context, "Group dissolved successfully", Toast.LENGTH_SHORT).show()
                            }
                        )

                        // 6. Unified Rotate/Flip Popup Tool
                        var showTransformPopup by remember { mutableStateOf(false) }
                        Box(modifier = Modifier.wrapContentSize()) {
                            IconButtonWithLabel(
                                icon = Icons.Default.Refresh,
                                label = "Transform",
                                onClick = { showTransformPopup = !showTransformPopup }
                            )
                            if (showTransformPopup) {
                                androidx.compose.ui.window.Popup(
                                    alignment = Alignment.TopCenter,
                                    offset = androidx.compose.ui.unit.IntOffset(0, -90),
                                    onDismissRequest = { showTransformPopup = false }
                                ) {
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF6D00)),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.width(200.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(6.dp),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            IconButton(
                                                onClick = {
                                                    viewModel.rotateSelectedShapes(90f)
                                                    Toast.makeText(context, "Rotasi +90°", Toast.LENGTH_SHORT).show()
                                                }
                                            ) {
                                                Icon(Icons.Default.RotateRight, "Rotate CW", tint = Color.White)
                                            }
                                            IconButton(
                                                onClick = {
                                                    viewModel.rotateSelectedShapes(-90f)
                                                    Toast.makeText(context, "Rotasi -90°", Toast.LENGTH_SHORT).show()
                                                }
                                            ) {
                                                Icon(Icons.Default.RotateLeft, "Rotate CCW", tint = Color.White)
                                            }
                                            IconButton(
                                                onClick = {
                                                    viewModel.flipSelectedShape(horizontal = true, vertical = false)
                                                    Toast.makeText(context, "Mirror Horizontal", Toast.LENGTH_SHORT).show()
                                                }
                                            ) {
                                                Icon(Icons.Default.Flip, "Mirror Horizontal", tint = Color.White)
                                            }
                                            IconButton(
                                                onClick = {
                                                    viewModel.flipSelectedShape(horizontal = false, vertical = true)
                                                    Toast.makeText(context, "Mirror Vertikal", Toast.LENGTH_SHORT).show()
                                                }
                                            ) {
                                                Icon(Icons.Default.SwapVert, "Mirror Vertikal", tint = Color.White)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 7. Clone / Duplicate
                        IconButtonWithLabel(
                            icon = Icons.Default.ContentCopy,
                            label = "Copy",
                            isActive = false,
                            onClick = {
                                viewModel.currentTool = VectorTool.POINTER
                                viewModel.isCloneModeActive = false
                                if (viewModel.selectedShapeIds.isNotEmpty()) {
                                    viewModel.duplicateSelected()
                                    Toast.makeText(context, "Objek tercopy otomatis.", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Pilih objek terlebih dahulu.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )

                        // 8. Add Text
                        IconButtonWithLabel(
                            icon = Icons.Default.TextFields,
                            label = "Add Text",
                            onClick = {
                                textInputState = ""
                                showTextDialog = true
                            }
                        )

                        // 9. Align Layout options
                        IconButtonWithLabel(
                            icon = Icons.Default.GridView,
                            label = "Align",
                            onClick = {
                                if (viewModel.selectedShapeId == null) {
                                    Toast.makeText(context, "Select a shape to align", Toast.LENGTH_SHORT).show()
                                } else {
                                    showAlignmentSelector = !showAlignmentSelector
                                }
                            }
                        )

                        // 10. Grid Config
                        IconButtonWithLabel(
                            icon = Icons.Default.GridOn,
                            label = "Grid",
                            isActive = viewModel.isGridEnabled,
                            onClick = {
                                viewModel.isGridEnabled = !viewModel.isGridEnabled
                                Toast.makeText(context, if (viewModel.isGridEnabled) "Grid Enabled" else "Grid Disabled", Toast.LENGTH_SHORT).show()
                            }
                        )


                        // 11. Magnetic Snapping Popup portal
                        IconButtonWithLabel(
                            icon = if (viewModel.isSnapToGrid || viewModel.isSnapToObjectEnabled || viewModel.isSnapToPointEnabled) Icons.Default.LeakAdd else Icons.Default.FlashOff,
                            label = "Snap Options",
                            onClick = { showSnappingPopup = !showSnappingPopup }
                        )
                    }
                }
            }
        }
    }

    // LAYER VIEW STACKS SIDE DRAWER OVERLAY
    if (showLayersPanel) {
        androidx.compose.ui.window.Popup(
            alignment = Alignment.TopEnd,
            offset = androidx.compose.ui.unit.IntOffset(-12, 100),
            onDismissRequest = { showLayersPanel = false }
        ) {
            Card(
                modifier = Modifier
                    .width(300.dp)
                    .heightIn(min = 200.dp, max = 500.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xCC1E293B)), // Transparan
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Layer Manager",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { viewModel.addNewLayer() }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Add, "Add Layer", tint = Color(0xFFFF6D00))
                            }
                            IconButton(onClick = { showLayersPanel = false }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Close, "Close Panel", tint = Color.LightGray)
                            }
                        }
                    }

                    Divider(color = Color(0xFF475569), modifier = Modifier.padding(vertical = 8.dp))

                    if (viewModel.layers.isEmpty()) {
                        Box(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No layers yet.", color = Color.Gray, fontSize = 13.sp)
                        }
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            items(viewModel.layers.reversed()) { layer -> 
                                val actualIndex = viewModel.layers.indexOf(layer)
                                val isSelected = viewModel.activeLayerId == layer.id
                                val shapeCount = viewModel.shapes.count { it.layerId == layer.id }
                                
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) Color(0x88334155) else Color.Transparent)
                                        .clickable {
                                            viewModel.activeLayerId = layer.id
                                        }
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Layers,
                                            contentDescription = "Layer Icon",
                                            tint = if (isSelected) Color(0xFFFF6D00) else Color.Gray,
                                            modifier = Modifier.size(18.dp)
                                        )

                                        Column {
                                            Text(
                                                text = layer.name,
                                                color = Color.White,
                                                fontSize = 13.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "$shapeCount objects",
                                                color = Color.LightGray,
                                                fontSize = 10.sp
                                            )
                                        }
                                    }

                                    // Controls: Lock toggle & Layer re-order bounds
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        // Visibility toggle
                                        IconButton(
                                            onClick = { 
                                                viewModel.layers = viewModel.layers.map { 
                                                    if (it.id == layer.id) it.copy(isVisible = !it.isVisible) else it 
                                                }
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (layer.isVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                                contentDescription = "Toggle Visibility",
                                                tint = if (layer.isVisible) Color.LightGray else Color.Red,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }

                                        // Lock layer toggle
                                        IconButton(
                                            onClick = { 
                                                viewModel.layers = viewModel.layers.map { 
                                                    if (it.id == layer.id) it.copy(isLocked = !it.isLocked) else it 
                                                }
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (layer.isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                                contentDescription = "Toggle Lock",
                                                tint = if (layer.isLocked) Color.Red else Color.LightGray,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }

                                        // Move layer up
                                        IconButton(
                                            onClick = { viewModel.reorderLayers(actualIndex, actualIndex + 1) },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(Icons.Default.ArrowUpward, "Layer up", tint = Color.White, modifier = Modifier.size(14.dp))
                                        }

                                        // Move layer down
                                        IconButton(
                                            onClick = { viewModel.reorderLayers(actualIndex, actualIndex - 1) },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(Icons.Default.ArrowDownward, "Layer down", tint = Color.White, modifier = Modifier.size(14.dp))
                                        }
                                        
                                        // Delete layer
                                        IconButton(
                                            onClick = { viewModel.deleteLayer(layer.id) },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, "Delete", tint = Color(0xFFEF4444), modifier = Modifier.size(14.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                    if (viewModel.selectedShapeId != null) {
                        Button(
                            onClick = { 
                                viewModel.selectedShapeId?.let { objId ->
                                   viewModel.moveSelectedObjectToLayer(objId, viewModel.activeLayerId) 
                                }
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6D00))
                        ) {
                            Text("Move Selected to Active Layer", color = Color.Black, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    // MAIN DOCUMENT SETTINGS OPTIONS DRAWER (Left Hamburger Dropdown)
    if (showMenuSheet) {
        androidx.compose.ui.window.Popup(
            alignment = Alignment.TopStart,
            offset = androidx.compose.ui.unit.IntOffset(12, 110),
            onDismissRequest = { showMenuSheet = false },
            properties = androidx.compose.ui.window.PopupProperties(focusable = true)
        ) {
            Card(
                modifier = Modifier
                    .width(260.dp)
                    .wrapContentHeight(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF475569))
            ) {
                Column(
                    modifier = Modifier.padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "STUDIO MENU",
                        color = Color.Gray,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                    
                    Divider(color = Color(0xFF334155))
                    
                    // 1. Exit (back to onboarding home page)
                    DropdownMenuItem(
                        text = { Text("Exit Studio", color = Color.White, fontWeight = FontWeight.Bold) },
                        leadingIcon = { Icon(Icons.Default.ExitToApp, contentDescription = "Exit", tint = Color(0xFFEF4444)) },
                        onClick = {
                            viewModel.isSetupCompleted = false
                            showMenuSheet = false
                            Toast.makeText(context, "Kembali ke Beranda", Toast.LENGTH_SHORT).show()
                        }
                    )

                    // 2. Export
                    DropdownMenuItem(
                        text = { Text("Export Graphics...", color = Color.White) },
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = "Export icon", tint = Color(0xFFFF6D00)) },
                        onClick = {
                            showMenuSheet = false
                            showExportDialog = true
                        }
                    )

                    // 3. Import File
                    DropdownMenuItem(
                        text = { Text("Import Image / Path...", color = Color.White) },
                        leadingIcon = { Icon(Icons.Default.Add, contentDescription = "Import icon", tint = Color(0xFF3B82F6)) },
                        onClick = {
                            showMenuSheet = false
                            try {
                                importFileLauncher.launch("*/*")
                            } catch (e: Exception) {
                                Toast.makeText(context, "Tidak dapat membuka berkas: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )

                    // 4. Artboard Settings
                    DropdownMenuItem(
                        text = { Text("Artboard Settings", color = Color.White) },
                        leadingIcon = { Icon(Icons.Default.Settings, contentDescription = "Settings icon", tint = Color(0xFF94A3B8)) },
                        onClick = {
                            showMenuSheet = false
                            artboardWidthInput = viewModel.canvasWidth.toInt().toString()
                            artboardHeightInput = viewModel.canvasHeight.toInt().toString()
                            showArtboardSettingsDialog = true
                        }
                    )
                }
            }
        }
    }



    // COMPREHENSIVE SNAPPING SYSTEM CONFIGURATION POPUP
    if (showSnappingPopup) {
        Dialog(onDismissRequest = { showSnappingPopup = false }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .wrapContentHeight(),
                colors = CardDefaults.cardColors(containerColor = Color(0xEE1E293B)),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.2.dp, Color(0xFFFF6D00))
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Magnetic Snapping Toggles",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Divider(color = Color.DarkGray)

                    // 1. Smart Guide
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Smart Guide", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("Dynamic alignment helper guides", color = Color.Gray, fontSize = 10.sp)
                        }
                        Switch(
                            checked = viewModel.isSmartGuideEnabled,
                            onCheckedChange = { viewModel.isSmartGuideEnabled = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFFF6D00))
                        )
                    }

                    // 2. Snap to Object
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Snap to Object", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("Snap edges to relative designs", color = Color.Gray, fontSize = 10.sp)
                        }
                        Switch(
                            checked = viewModel.isSnapToObjectEnabled,
                            onCheckedChange = { viewModel.isSnapToObjectEnabled = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFFF6D00))
                        )
                    }

                    // 3. Snap to Grid
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Snap to Grid", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("Lock points to grid cells outline", color = Color.Gray, fontSize = 10.sp)
                        }
                        Switch(
                            checked = viewModel.isSnapToGrid,
                            onCheckedChange = { viewModel.isSnapToGrid = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFFF6D00))
                        )
                    }

                    // 4. Snap to Point
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Snap to Point", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("Magnet coordinates to nodes", color = Color.Gray, fontSize = 10.sp)
                        }
                        Switch(
                            checked = viewModel.isSnapToPointEnabled,
                            onCheckedChange = { viewModel.isSnapToPointEnabled = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFFF6D00))
                        )
                    }

                    Button(
                        onClick = { showSnappingPopup = false },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6D00)),
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Save Configurations", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // REVAMPED EXPORT PREVIEW DIALOG
    if (showExportDialog) {
        var exportSelectionOnly by remember { mutableStateOf(false) }
        var chosenFormat by remember { mutableStateOf("SVG") } // "EPS", "SVG", "JPG", "PNG", "PNG transparent"

        val shapesToExport = if (exportSelectionOnly && viewModel.selectedShapeId != null) {
            viewModel.shapes.filter { it.id == viewModel.selectedShapeId }
        } else {
            viewModel.shapes
        }

        val exportCode = remember(shapesToExport, chosenFormat) {
            if (chosenFormat == "SVG") {
                generateSVGCode(shapesToExport, viewModel.canvasWidth, viewModel.canvasHeight)
            } else if (chosenFormat == "EPS") {
                generateEPSCode(shapesToExport, viewModel.canvasWidth, viewModel.canvasHeight)
            } else {
                val svgStr = generateSVGCode(shapesToExport, viewModel.canvasWidth, viewModel.canvasHeight)
                "/* Scalable Vector Binary Header representing $chosenFormat format output */\n" +
                "formatVersion: 1.0\n" +
                "targetCanvasSize: ${viewModel.canvasWidth.toInt()}x${viewModel.canvasHeight.toInt()}\n" +
                "elementsCount: ${shapesToExport.size}\n" +
                "checksum: ${shapesToExport.hashCode()}\n" +
                "svgSourceDataBlock: \n" + svgStr
            }
        }

        Dialog(onDismissRequest = { showExportDialog = false }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .fillMaxHeight(0.85f),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(18.dp),
                border = androidx.compose.foundation.BorderStroke(1.2.dp, Color(0xFF475569))
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Design Export Preview",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.Start)
                    )

                    // EXPORT BASIS SELECTION: DOCUMENT VS SELECTION
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FilterChip(
                            selected = !exportSelectionOnly,
                            onClick = { exportSelectionOnly = false },
                            label = { Text("Export Document", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFFF6D00),
                                selectedLabelColor = Color.Black,
                                labelColor = Color.White
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = exportSelectionOnly,
                            onClick = { 
                                if (viewModel.selectedShapeId == null) {
                                    Toast.makeText(context, "Pilih salah satu objek terlebih dahulu!", Toast.LENGTH_SHORT).show()
                                } else {
                                    exportSelectionOnly = true
                                }
                            },
                            label = { Text("Export Selection Object", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFFF6D00),
                                selectedLabelColor = Color.Black,
                                labelColor = Color.White
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // FILE FORMAT CHIPS
                    Text("Select Export Format:", color = Color.White, fontSize = 11.sp, modifier = Modifier.align(Alignment.Start))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val fs = listOf("EPS", "SVG", "JPG", "PNG", "PNG transparent")
                        fs.forEach { format ->
                            val isSel = chosenFormat == format
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSel) Color(0xFFFF6D00) else Color(0xFF0F172A))
                                    .clickable { chosenFormat = format }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = format,
                                    color = if (isSel) Color.Black else Color.LightGray,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // LIVE DESIGN PREVIEW RENDERING CANVAS Box
                    Text("Design Live Preview:", color = Color.White, fontSize = 11.sp, modifier = Modifier.align(Alignment.Start))
                    Box(
                        modifier = Modifier
                            .size(200.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White)
                            .border(1.5.dp, Color(0xFF475569), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize().padding(10.dp)) {
                            // Find bounds to center selection or full artboard
                            val scaleX = size.width / viewModel.canvasWidth
                            val scaleY = size.height / viewModel.canvasHeight
                            val scale = scaleX.coerceAtMost(scaleY)

                            drawIntoCanvas { canvas ->
                                canvas.save()
                                canvas.scale(scale, scale)

                                shapesToExport.forEach { shape ->
                                    if (shape.isVisible) {
                                        val strokeColor = Color(android.graphics.Color.parseColor(shape.strokeColorHex)).copy(alpha = shape.strokeAlpha)
                                        val fillColor = Color(android.graphics.Color.parseColor(shape.fillColorHex)).copy(alpha = shape.fillAlpha)

                                        if (shape.type == ShapeType.TEXT) {
                                            val paintText = android.graphics.Paint().apply {
                                                color = android.graphics.Color.parseColor(shape.strokeColorHex)
                                                textSize = shape.fontSize
                                                typeface = viewModel.importedTypeface ?: android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                                                isAntiAlias = true
                                            }
                                            canvas.nativeCanvas.drawText(
                                                shape.textContent,
                                                shape.x,
                                                shape.y,
                                                paintText
                                            )
                                        } else {
                                            val path = shape.asComposePath()
                                            if (shape.hasFill && shape.type != ShapeType.LINE) {
                                                drawPath(path = path, color = fillColor)
                                            }
                                            drawPath(path = path, color = strokeColor, style = Stroke(width = shape.strokeWidth))
                                        }
                                    }
                                }
                                canvas.restore()
                            }
                        }
                    }

                    // TRIGGER LOGICAL LOCAL SAVE EXPORTER FILE TO DOWNLOADS
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val currentFormat = chosenFormat
                                val cleanFileName = "artwork_studio_${System.currentTimeMillis()}.${currentFormat.lowercase().replace(" ", "_")}"
                                try {
                                    val dlDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                                    if (!dlDir.exists()) {
                                        dlDir.mkdirs()
                                    }
                                    val out = java.io.File(dlDir, cleanFileName)
                                    if (currentFormat == "JPG" || currentFormat == "PNG" || currentFormat == "PNG transparent") {
                                        val isTransparent = currentFormat == "PNG transparent"
                                        val bitmap = renderShapesToBitmap(
                                            shapes = shapesToExport,
                                            width = viewModel.canvasWidth,
                                            height = viewModel.canvasHeight,
                                            transparent = isTransparent,
                                            importedTypeface = viewModel.importedTypeface
                                        )
                                        val stream = java.io.ByteArrayOutputStream()
                                        val compressFormat = if (currentFormat == "JPG") {
                                            android.graphics.Bitmap.CompressFormat.JPEG
                                        } else {
                                            android.graphics.Bitmap.CompressFormat.PNG
                                        }
                                        bitmap.compress(compressFormat, 100, stream)
                                        val bytes = stream.toByteArray()
                                        println("Saved dynamic image. Format: $currentFormat, Byte size: ${bytes.size} bytes (${bytes.size / 1024f} KB)")
                                        android.util.Log.d("ArtworkStudio", "Saved dynamic image. Format: $currentFormat, Byte size: ${bytes.size} bytes")
                                        out.writeBytes(bytes)
                                    } else {
                                        out.writeText(exportCode)
                                    }
                                    Toast.makeText(context, "Export Sukses!\nTersimpan di Folder Downloads:\n${out.absolutePath}", Toast.LENGTH_LONG).show()
                                    showExportDialog = false
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Gagal export: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6D00)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Export Now", color = Color.Black, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { showExportDialog = false },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF475569)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Cancel", color = Color.White)
                        }
                    }
                }
            }
        }
    }

    // GENERAL WORKSPACE APP SETTINGS DIALOG (Row 2, #1)
    if (showCustomSettingsDialog) {
        Dialog(onDismissRequest = { showCustomSettingsDialog = false }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .wrapContentHeight(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        "Workspace Settings",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )

                    // Grid cell size
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Grid Spacing px:", color = Color.White, fontSize = 13.sp)
                        Text("${viewModel.gridSize.toInt()} px", color = Color(0xFFFF6D00), fontWeight = FontWeight.Bold)
                    }

                    Slider(
                        value = viewModel.gridSize,
                        onValueChange = { viewModel.gridSize = it },
                        valueRange = 10f..100f,
                        colors = SliderDefaults.colors(
                            activeTrackColor = Color(0xFFFF6D00),
                            thumbColor = Color(0xFFFF6D00)
                        )
                    )

                    // Fill Mode default toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Deconstruct Outline Shapes:", color = Color.White, fontSize = 13.sp)
                        Switch(
                            checked = viewModel.hasFillEnabled,
                            onCheckedChange = { viewModel.hasFillEnabled = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFFFF6D00),
                                checkedTrackColor = Color(0xFF064E3B)
                            )
                        )
                    }

                    Button(
                        onClick = { showCustomSettingsDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6D00)),
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Done", color = Color.Black)
                    }
                }
            }
        }
    }

    if (showArtboardSettingsDialog) {
        Dialog(onDismissRequest = { showArtboardSettingsDialog = false }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .wrapContentHeight(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155))
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Artboard Settings",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )

                    // 1. Color Selection Panel
                    Text(
                        text = "UKURAN & WARNA",
                        color = Color.LightGray,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = artboardWidthInput,
                            onValueChange = { artboardWidthInput = it.filter { ch -> ch.isDigit() } },
                            label = { Text("Width (px)", color = Color.LightGray, fontSize = 10.sp) },
                            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFFF6D00),
                                unfocusedBorderColor = Color(0xFF334155),
                                cursorColor = Color(0xFFFF6D00)
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = artboardHeightInput,
                            onValueChange = { artboardHeightInput = it.filter { ch -> ch.isDigit() } },
                            label = { Text("Height (px)", color = Color.LightGray, fontSize = 10.sp) },
                            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFFF6D00),
                                unfocusedBorderColor = Color(0xFF334155),
                                cursorColor = Color(0xFFFF6D00)
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF0F172A))
                            .border(1.dp, Color(0xFF334155), RoundedCornerShape(12.dp))
                            .clickable { showArtboardColorPicker = true }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val parsedColor = try {
                            Color(android.graphics.Color.parseColor(viewModel.artboardColorHex))
                        } catch(_: Exception) {
                            Color.White
                        }
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(parsedColor)
                                .border(1.5.dp, Color.White, CircleShape)
                        )
                        Column {
                            Text(
                                text = "Tekan untuk Pilih Warna",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Hex Code: ${viewModel.artboardColorHex.uppercase()}",
                                color = Color(0xFFFF6D00),
                                fontSize = 11.sp
                            )
                        }
                    }

                    // 2. Opacity / Alpha Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Opacity Latar Belakang:", color = Color.White, fontSize = 12.sp)
                        Text("${(viewModel.artboardAlpha * 100).toInt()}%", color = Color(0xFFFF6D00), fontWeight = FontWeight.Bold)
                    }

                    Slider(
                        value = viewModel.artboardAlpha,
                        onValueChange = { 
                            viewModel.artboardAlpha = it 
                            viewModel.saveCurrentProject()
                        },
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(
                            activeTrackColor = Color(0xFFFF6D00),
                            thumbColor = Color(0xFFFF6D00)
                        )
                    )

                    // 3. Quick preset options
                    Text(
                        text = "PRESET CEPAT",
                        color = Color.LightGray,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            "Putih" to "#FFFFFF",
                            "Hitam" to "#000000",
                            "Abu-abu" to "#808080",
                            "Biru" to "#1E3A8A"
                        ).forEach { (label, hexColor) ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF0F172A))
                                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(8.dp))
                                    .clickable {
                                        viewModel.artboardColorHex = hexColor
                                        viewModel.saveCurrentProject()
                                    }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Box(modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { 
                                val newWidth = artboardWidthInput.toFloatOrNull() ?: viewModel.canvasWidth
                                val newHeight = artboardHeightInput.toFloatOrNull() ?: viewModel.canvasHeight
                                if (newWidth > 0 && newHeight > 0) {
                                    viewModel.canvasWidth = newWidth
                                    viewModel.canvasHeight = newHeight
                                    viewModel.saveCurrentProject()
                                }
                                showArtboardSettingsDialog = false 
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6D00)),
                            modifier = Modifier.align(Alignment.CenterEnd)
                        ) {
                            Text("Selesai", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (renameProjectTarget != null) {
        AlertDialog(
            onDismissRequest = { renameProjectTarget = null },
            title = { Text("Ubah Nama Proyek", color = Color.White) },
            text = {
                OutlinedTextField(
                    value = newProjectNameInput,
                    onValueChange = { newProjectNameInput = it },
                    label = { Text("Nama Proyek", color = Color.LightGray) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFFF6D00),
                        unfocusedBorderColor = Color.Gray,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            containerColor = Color(0xFF1E293B),
            confirmButton = {
                Button(
                    onClick = {
                        if (newProjectNameInput.isNotBlank()) {
                            viewModel.renameProject(renameProjectTarget!!.id, newProjectNameInput)
                            Toast.makeText(context, "Nama proyek diubah", Toast.LENGTH_SHORT).show()
                            renameProjectTarget = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6D00))
                ) {
                    Text("Simpan", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { renameProjectTarget = null }) {
                    Text("Batal", color = Color.LightGray)
                }
            }
        )
    }

    if (showArtboardColorPicker) {
        ColorPickerDialog(
            title = "Pilih Warna Latar Artboard",
            initialColorHex = viewModel.artboardColorHex,
            initialAlpha = viewModel.artboardAlpha,
            onColorSelected = { hex, alpha ->
                viewModel.artboardColorHex = hex
                viewModel.artboardAlpha = alpha
                viewModel.saveCurrentProject()
                showArtboardColorPicker = false
            },
            onDismissRequest = { showArtboardColorPicker = false }
        )
    }

    // TEXT VECTOR INPUT DEFINITIONS MODAL (Row 2, Button 5)
    if (showTextDialog) {
        AlertDialog(
            onDismissRequest = { showTextDialog = false },
            title = { Text("Insert Vector Text") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = textInputState,
                        onValueChange = { textInputState = it },
                        label = { Text("Desain Text") },
                        singleLine = true,
                        placeholder = { Text("Tulis kata...") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = {
                            fontLauncher.launch("font/*")
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Font", tint = Color.White)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (viewModel.importedFontFileName.isNullOrEmpty()) "Import Custom TTF/OTF Font" else "Font: ${viewModel.importedFontFileName}",
                            color = Color.White,
                            fontSize = 11.sp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (textInputState.isNotBlank()) {
                            viewModel.addTextShape(textInputState)
                        }
                        showTextDialog = false
                    }
                ) {
                    Text("Add Vector", color = Color(0xFFFF6D00))
                }
            },
            dismissButton = {
                TextButton(onClick = { showTextDialog = false }) {
                    Text("Back")
                }
            }
        )
    }

    // COLOR PANEL PICKERS
    if (showColorPickerFill) {
        ColorPickerDialog(
            title = "Pilih Warna Fill (Tengah Desain)",
            initialColorHex = viewModel.currentFillColorHex,
            initialAlpha = viewModel.currentFillAlpha,
            supportNoneButton = true,
            onNoneSelected = {
                viewModel.hasFillEnabled = false
                if (viewModel.selectedShapeId != null) {
                    viewModel.updateSelectedShapeStyle()
                }
            },
            onColorSelected = { hex, alpha ->
                viewModel.currentFillColorHex = hex
                viewModel.currentFillAlpha = alpha
                viewModel.hasFillEnabled = true
                if (viewModel.selectedShapeId != null) {
                    viewModel.updateSelectedShapeStyle()
                }
            },
            onDismissRequest = { showColorPickerFill = false }
        )
    }

    if (showColorPickerStroke) {
        ColorPickerDialog(
            title = "Pilih Warna Outline Stroke",
            initialColorHex = viewModel.currentStrokeColorHex,
            initialAlpha = viewModel.currentStrokeAlpha,
            supportNoneButton = true,
            onNoneSelected = {
                viewModel.currentStrokeWidth = 0f
                if (viewModel.selectedShapeId != null) {
                    viewModel.updateSelectedShapeStyle()
                }
            },
            onColorSelected = { hex, alpha ->
                viewModel.currentStrokeColorHex = hex
                viewModel.currentStrokeAlpha = alpha
                if (viewModel.currentStrokeWidth <= 0f) {
                    viewModel.currentStrokeWidth = 4f // Restore outline if previously none
                }
                if (viewModel.selectedShapeId != null) {
                    viewModel.updateSelectedShapeStyle()
                }
            },
            onDismissRequest = { showColorPickerStroke = false },
            isStrokePanel = true,
            viewModel = viewModel
        )
    }
}

// Extension to avoid parsing errors
fun Int.hpx(): androidx.compose.ui.unit.Dp {
    return this.dp
}

// COMPOSABLE COMPONENT DECORATIONS HELPERS
@Composable
fun ToolButton(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) Color(0xFFFF6D00) else Color.Transparent) // Highlight bright neon mint green
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .widthIn(min = 44.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isSelected) Color.Black else Color.White,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = label,
            color = if (isSelected) Color.Black else Color.LightGray,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun IconButtonWithLabel(
    icon: ImageVector,
    label: String,
    isActive: Boolean = false,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (isActive) Color(0xFF6366F1).copy(alpha = 0.35f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .widthIn(min = 44.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isActive) Color(0xFF818CF8) else Color.White,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = label,
            color = if (isActive) Color.White else Color.LightGray,
            fontSize = 10.sp
        )
    }
}

@Composable
fun SidebarToolButton(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) Color(0xFFFF6D00) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 2.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isSelected) Color.Black else Color.White,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
fun StrokeWidthAndOpacitySlidersSection(viewModel: VectorViewModel) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Stroke width slider
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "${viewModel.currentStrokeWidth.toInt()}px",
                color = Color.White,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(36.dp)
            )
            Slider(
                value = viewModel.currentStrokeWidth,
                onValueChange = { width ->
                    viewModel.currentStrokeWidth = width
                    if (viewModel.selectedShapeId != null || viewModel.selectedShapeIds.isNotEmpty()) {
                        viewModel.updateSelectedShapeStyle()
                    }
                },
                valueRange = 0f..100f,
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFFFF6D00),
                    activeTrackColor = Color(0xFFFF6D00),
                    inactiveTrackColor = Color(0xFF475569)
                ),
                modifier = Modifier.weight(1f).height(24.dp)
            )
            Text("Ukuran", color = Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }

        // Opacity slider
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "${(viewModel.currentStrokeAlpha * 100).toInt()}%",
                color = Color.White,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(36.dp)
            )
            Slider(
                value = viewModel.currentStrokeAlpha,
                onValueChange = { opacity ->
                    viewModel.currentStrokeAlpha = opacity
                    if (viewModel.selectedShapeId != null || viewModel.selectedShapeIds.isNotEmpty()) {
                        viewModel.updateSelectedShapeStyle()
                    }
                },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFFFF6D00),
                    activeTrackColor = Color(0xFFFF6D00),
                    inactiveTrackColor = Color(0xFF475569)
                ),
                modifier = Modifier.weight(1f).height(24.dp)
            )
            Text("Opacity", color = Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
    }
}


@Composable
fun AlignButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(36.dp)
            .background(Color(0xFF334155), CircleShape)
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

// Dynamic real-time compliant SVG raw vector text content generator for the export modal
fun generateSVGCode(shapes: List<VectorShape>, width: Float, height: Float): String {
    val sb = StringBuilder()
    sb.append("<!-- Generated by Vector Design Pro Android Compose -->\n")
    sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $width $height\" width=\"100%\" height=\"100%\" style=\"background-color: #ffffff;\">\n")
    
    for (shape in shapes) {
        if (!shape.isVisible) continue
        
        val fillAttr = if (shape.hasFill && shape.type != ShapeType.LINE) {
            "fill=\"${shape.fillColorHex}\" fill-opacity=\"${shape.fillAlpha}\""
        } else {
            "fill=\"none\""
        }
        val strokeAttr = "stroke=\"${shape.strokeColorHex}\" stroke-width=\"${shape.strokeWidth}\" stroke-opacity=\"${shape.strokeAlpha}\" stroke-linecap=\"${shape.strokeCap.lowercase()}\" stroke-linejoin=\"${shape.strokeJoin.lowercase()}\""
        
        when (shape.type) {
            ShapeType.RECTANGLE -> {
                sb.append("  <rect x=\"${shape.x}\" y=\"${shape.y}\" width=\"${shape.width}\" height=\"${shape.height}\" $fillAttr $strokeAttr />\n")
            }
            ShapeType.ELLIPSE -> {
                sb.append("  <ellipse cx=\"${shape.x}\" cy=\"${shape.y}\" rx=\"${shape.width}\" ry=\"${shape.height}\" $fillAttr $strokeAttr />\n")
            }
            ShapeType.LINE -> {
                sb.append("  <line x1=\"${shape.startX}\" y1=\"${shape.startY}\" x2=\"${shape.endX}\" y2=\"${shape.endY}\" $strokeAttr />\n")
            }
            ShapeType.FREEHAND -> {
                if (shape.freehandPoints.isNotEmpty()) {
                    sb.append("  <path d=\"M ${shape.freehandPoints.first().x} ${shape.freehandPoints.first().y} ")
                    for (i in 1 until shape.freehandPoints.size) {
                        val pt = shape.freehandPoints[i]
                        sb.append("L ${pt.x} ${pt.y} ")
                    }
                    sb.append("\" $fillAttr $strokeAttr />\n")
                }
            }
            ShapeType.BEZIER_PATH -> {
                if (shape.bezierNodes.isNotEmpty()) {
                    sb.append("  <path d=\"M ${shape.bezierNodes.first().anchorX} ${shape.bezierNodes.first().anchorY} ")
                    for (i in 1 until shape.bezierNodes.size) {
                        val node = shape.bezierNodes[i]
                        val prev = shape.bezierNodes[i - 1]
                        if (node.isCurve) {
                            sb.append("C ${prev.control2X} ${prev.control2Y}, ${node.control1X} ${node.control1Y}, ${node.anchorX} ${node.anchorY} ")
                        } else {
                            sb.append("L ${node.anchorX} ${node.anchorY} ")
                        }
                    }
                    if (shape.isPathClosed) {
                        val first = shape.bezierNodes.first()
                        val last = shape.bezierNodes.last()
                        if (first.isCurve) {
                            sb.append("C ${last.control2X} ${last.control2Y}, ${first.control1X} ${first.control1Y}, ${first.anchorX} ${first.anchorY} ")
                        } else {
                            sb.append("L ${first.anchorX} ${first.anchorY} ")
                        }
                        sb.append("Z")
                    }
                    sb.append("\" $fillAttr $strokeAttr />\n")
                }
            }
            ShapeType.TEXT -> {
                sb.append("  <text x=\"${shape.x}\" y=\"${shape.y}\" font-family=\"sans-serif\" font-weight=\"bold\" font-size=\"${shape.fontSize}\" fill=\"${shape.strokeColorHex}\" fill-opacity=\"${shape.strokeAlpha}\">${shape.textContent}</text>\n")
            }
            ShapeType.POLYGON -> {
                val sbPts = StringBuilder()
                val sides = shape.polygonSides.coerceAtLeast(3)
                for (i in 0 until sides) {
                    val angle = i * 2 * Math.PI / sides - Math.PI / 2
                    val px = shape.x + shape.width * kotlin.math.cos(angle).toFloat()
                    val py = shape.y + shape.height * kotlin.math.sin(angle).toFloat()
                    sbPts.append("$px,$py ")
                }
                sb.append("  <polygon points=\"${sbPts.toString().trim()}\" $fillAttr $strokeAttr />\n")
            }
            ShapeType.STAR -> {
                val sbPts = StringBuilder()
                val pts = shape.starPoints.coerceAtLeast(3)
                val totalPoints = pts * 2
                val innerRx = shape.width * 0.4f
                val innerRy = shape.height * 0.4f
                for (i in 0 until totalPoints) {
                    val angle = i * Math.PI / pts - Math.PI / 2
                    val rXFactor = if (i % 2 == 0) shape.width else innerRx
                    val rYFactor = if (i % 2 == 0) shape.height else innerRy
                    val px = shape.x + rXFactor * kotlin.math.cos(angle).toFloat()
                    val py = shape.y + rYFactor * kotlin.math.sin(angle).toFloat()
                    sbPts.append("$px,$py ")
                }
                sb.append("  <polygon points=\"${sbPts.toString().trim()}\" $fillAttr $strokeAttr />\n")
            }
        }
    }
    
    sb.append("</svg>")
    return sb.toString()
}

fun generateEPSCode(shapes: List<VectorShape>, width: Float, height: Float): String {
    val sb = java.lang.StringBuilder()
    val roundedW = kotlin.math.ceil(width).toInt()
    val roundedH = kotlin.math.ceil(height).toInt()
    
    // EPS / PostScript Header compliant with Adobe EPS levels and Shutterstock ingestion engine
    sb.append("%!PS-Adobe-3.0 EPSF-3.0\n")
    sb.append("%%Creator: Vector Design Pro\n")
    sb.append("%%Title: Vector Artwork Studio Export\n")
    sb.append("%%BoundingBox: 0 0 $roundedW $roundedH\n")
    sb.append("%%HiResBoundingBox: 0.000000 0.000000 ${width}00000 ${height}00000\n")
    sb.append("%%Pages: 1\n")
    sb.append("%%DocumentData: Clean7Bit\n")
    sb.append("%%LanguageLevel: 2\n")
    sb.append("%%EndComments\n")
    sb.append("%%BeginProlog\n")
    sb.append("%%EndProlog\n")
    sb.append("%%Page: 1 1\n\n")
    
    // Helper to parse hex colors to floats [0.0, 1.0] for setrgbcolor
    val hexToRgb = { hex: String ->
        val clean = hex.removePrefix("#")
        var r = 0f
        var g = 0f
        var b = 0f
        try {
            if (clean.length == 6) {
                r = clean.substring(0, 2).toInt(16) / 255f
                g = clean.substring(2, 4).toInt(16) / 255f
                b = clean.substring(4, 6).toInt(16) / 255f
            } else if (clean.length == 3) {
                r = clean.substring(0, 1).repeat(2).toInt(16) / 255f
                g = clean.substring(1, 2).repeat(2).toInt(16) / 255f
                b = clean.substring(2, 3).repeat(2).toInt(16) / 255f
            } else if (clean.length == 8) {
                r = clean.substring(2, 4).toInt(16) / 255f
                g = clean.substring(4, 6).toInt(16) / 255f
                b = clean.substring(6, 8).toInt(16) / 255f
            }
        } catch (_: Exception) {}
        Triple(r, g, b)
    }

    // Process shapes in order
    for (shape in shapes) {
        if (!shape.isVisible) continue
        
        sb.append("% Shape: ${shape.name} (${shape.type.name})\n")
        
        // Setup stroke parameters matching user preferences
        val lineJoinNum = when (shape.strokeJoin.uppercase()) {
            "MITER" -> 0
            "ROUND" -> 1
            "BEVEL" -> 2
            else -> 1
        }
        val lineCapNum = when (shape.strokeCap.uppercase()) {
            "BUTT" -> 0
            "ROUND" -> 1
            "SQUARE" -> 2
            else -> 1
        }
        
        // Function to write stroke and fill in proper PostScript order
        val applyPathColoring = { pathBuilder: java.lang.StringBuilder, isClosedShape: Boolean ->
            val hasStroke = shape.strokeWidth > 0f
            val hasFill = shape.hasFill && shape.type != ShapeType.LINE
            
            if (hasFill || hasStroke) {
                sb.append("newpath\n")
                sb.append(pathBuilder.toString())
                if (isClosedShape) {
                    sb.append("closepath\n")
                }
                
                if (hasFill && hasStroke) {
                    val (fr, fg, fb) = hexToRgb(shape.fillColorHex)
                    val (sr, sg, sbCol) = hexToRgb(shape.strokeColorHex)
                    sb.append("gsave\n")
                    sb.append("  $fr $fg $fb setrgbcolor\n")
                    sb.append("  fill\n")
                    sb.append("grestore\n")
                    sb.append("  $sr $sg $sbCol setrgbcolor\n")
                    sb.append("  ${shape.strokeWidth} setlinewidth\n")
                    sb.append("  $lineJoinNum setlinejoin\n")
                    sb.append("  $lineCapNum setlinecap\n")
                    sb.append("  stroke\n")
                } else if (hasFill) {
                    val (fr, fg, fb) = hexToRgb(shape.fillColorHex)
                    sb.append("  $fr $fg $fb setrgbcolor\n")
                    sb.append("  fill\n")
                } else {
                    val (sr, sg, sbCol) = hexToRgb(shape.strokeColorHex)
                    sb.append("  $sr $sg $sbCol setrgbcolor\n")
                    sb.append("  ${shape.strokeWidth} setlinewidth\n")
                    sb.append("  $lineJoinNum setlinejoin\n")
                    sb.append("  $lineCapNum setlinecap\n")
                    sb.append("  stroke\n")
                }
            }
        }
        
        val pSb = java.lang.StringBuilder()
        when (shape.type) {
            ShapeType.RECTANGLE -> {
                val rx = shape.x
                val ry = height - shape.y // top of rectangle flipped
                val rw = shape.width
                val rh = shape.height
                pSb.append("  $rx $ry moveto\n")
                pSb.append("  ${rx + rw} $ry lineto\n")
                pSb.append("  ${rx + rw} ${ry - rh} lineto\n")
                pSb.append("  $rx ${ry - rh} lineto\n")
                applyPathColoring(pSb, true)
            }
            ShapeType.ELLIPSE -> {
                val cx = shape.x
                val cy = height - shape.y
                val rx = shape.width
                val ry = shape.height
                val kx = rx * 0.55228475f
                val ky = ry * 0.55228475f
                
                pSb.append("  ${cx + rx} $cy moveto\n")
                pSb.append("  ${cx + rx} ${cy + ky} ${cx + kx} ${cy + ry} $cx ${cy + ry} curveto\n")
                pSb.append("  ${cx - kx} ${cy + ry} ${cx - rx} ${cy + ky} ${cx - rx} $cy curveto\n")
                pSb.append("  ${cx - rx} ${cy - ky} ${cx - kx} ${cy - ry} $cx ${cy - ry} curveto\n")
                pSb.append("  ${cx + kx} ${cy - ry} ${cx + rx} ${cy - ky} ${cx + rx} $cy curveto\n")
                applyPathColoring(pSb, true)
            }
            ShapeType.LINE -> {
                val sx = shape.startX
                val sy = height - shape.startY
                val ex = shape.endX
                val ey = height - shape.endY
                pSb.append("  $sx $sy moveto\n")
                pSb.append("  $ex $ey lineto\n")
                applyPathColoring(pSb, false)
            }
            ShapeType.FREEHAND -> {
                if (shape.freehandPoints.isNotEmpty()) {
                    val fpt0 = shape.freehandPoints.first()
                    pSb.append("  ${fpt0.x} ${height - fpt0.y} moveto\n")
                    for (i in 1 until shape.freehandPoints.size) {
                        val pt = shape.freehandPoints[i]
                        pSb.append("  ${pt.x} ${height - pt.y} lineto\n")
                    }
                    applyPathColoring(pSb, false)
                }
            }
            ShapeType.BEZIER_PATH -> {
                if (shape.bezierNodes.isNotEmpty()) {
                    val firstNode = shape.bezierNodes.first()
                    pSb.append("  ${firstNode.anchorX} ${height - firstNode.anchorY} moveto\n")
                    for (i in 1 until shape.bezierNodes.size) {
                        val node = shape.bezierNodes[i]
                        val prev = shape.bezierNodes[i - 1]
                        if (node.isCurve) {
                            pSb.append("  ${prev.control2X} ${height - prev.control2Y} ${node.control1X} ${height - node.control1Y} ${node.anchorX} ${height - node.anchorY} curveto\n")
                        } else {
                            pSb.append("  ${node.anchorX} ${height - node.anchorY} lineto\n")
                        }
                    }
                    if (shape.isPathClosed) {
                        val first = shape.bezierNodes.first()
                        val last = shape.bezierNodes.last()
                        if (first.isCurve) {
                            pSb.append("  ${last.control2X} ${height - last.control2Y} ${first.control1X} ${height - first.control1Y} ${first.anchorX} ${height - first.anchorY} curveto\n")
                        } else {
                            pSb.append("  ${first.anchorX} ${height - first.anchorY} lineto\n")
                        }
                    }
                    applyPathColoring(pSb, shape.isPathClosed)
                }
            }
            ShapeType.TEXT -> {
                val tx = shape.x
                val ty = height - shape.y
                val (sr, sg, sbCol) = hexToRgb(shape.strokeColorHex)
                
                sb.append("gsave\n")
                sb.append("  /Helvetica-Bold findfont ${shape.fontSize} scalefont setfont\n")
                sb.append("  $tx $ty moveto\n")
                sb.append("  $sr $sg $sbCol setrgbcolor\n")
                val escapedText = shape.textContent.replace("(", "\\(").replace(")", "\\)")
                sb.append("  ($escapedText) show\n")
                sb.append("grestore\n")
            }
            ShapeType.POLYGON -> {
                val sides = shape.polygonSides.coerceAtLeast(3)
                for (i in 0 until sides) {
                    val angle = i * 2 * Math.PI / sides - Math.PI / 2
                    val px = shape.x + shape.width * kotlin.math.cos(angle).toFloat()
                    val py = height - (shape.y + shape.height * kotlin.math.sin(angle).toFloat())
                    if (i == 0) {
                        pSb.append("  $px $py moveto\n")
                    } else {
                        pSb.append("  $px $py lineto\n")
                    }
                }
                applyPathColoring(pSb, true)
            }
            ShapeType.STAR -> {
                val pts = shape.starPoints.coerceAtLeast(3)
                val totalPoints = pts * 2
                val innerRx = shape.width * 0.4f
                val innerRy = shape.height * 0.4f
                for (i in 0 until totalPoints) {
                    val angle = i * Math.PI / pts - Math.PI / 2
                    val rXFactor = if (i % 2 == 0) shape.width else innerRx
                    val rYFactor = if (i % 2 == 0) shape.height else innerRy
                    val px = shape.x + rXFactor * kotlin.math.cos(angle).toFloat()
                    val py = height - (shape.y + rYFactor * kotlin.math.sin(angle).toFloat())
                    if (i == 0) {
                        pSb.append("  $px $py moveto\n")
                    } else {
                        pSb.append("  $px $py lineto\n")
                    }
                }
                applyPathColoring(pSb, true)
            }
        }
        sb.append("\n")
    }
    
    sb.append("showpage\n")
    sb.append("%%EOF\n")
    return sb.toString()
}

fun renderShapesToBitmap(
    shapes: List<com.example.model.VectorShape>,
    width: Float,
    height: Float,
    transparent: Boolean,
    importedTypeface: android.graphics.Typeface?
): android.graphics.Bitmap {
    val w = if (width > 1f) width.toInt() else 1000
    val h = if (height > 1f) height.toInt() else 1000
    val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    
    if (!transparent) {
        canvas.drawColor(android.graphics.Color.WHITE)
    } else {
        canvas.drawColor(android.graphics.Color.TRANSPARENT)
    }
    
    shapes.forEach { shape ->
        if (shape.isVisible) {
            val strokeColor = android.graphics.Color.parseColor(shape.strokeColorHex)
            val strokeAlpha = (shape.strokeAlpha * 255f).toInt().coerceIn(0, 255)
            val strokeColorWithAlpha = (strokeColor and 0x00FFFFFF) or (strokeAlpha shl 24)

            val fillColor = android.graphics.Color.parseColor(shape.fillColorHex)
            val fillAlpha = (shape.fillAlpha * 255f).toInt().coerceIn(0, 255)
            val fillColorWithAlpha = (fillColor and 0x00FFFFFF) or (fillAlpha shl 24)

            if (shape.type == com.example.model.ShapeType.TEXT) {
                val paintText = android.graphics.Paint().apply {
                    color = strokeColorWithAlpha
                    textSize = shape.fontSize
                    typeface = importedTypeface ?: android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                    isAntiAlias = true
                }
                canvas.drawText(
                    shape.textContent,
                    shape.x,
                    shape.y,
                    paintText
                )
            } else {
                val composePath = shape.asComposePath()
                val androidPath = composePath.asAndroidPath()
                
                if (shape.hasFill && shape.type != com.example.model.ShapeType.LINE) {
                    val paintFill = android.graphics.Paint().apply {
                        style = android.graphics.Paint.Style.FILL
                        color = fillColorWithAlpha
                        isAntiAlias = true
                    }
                    canvas.drawPath(androidPath, paintFill)
                }
                
                val paintStroke = android.graphics.Paint().apply {
                    style = android.graphics.Paint.Style.STROKE
                    color = strokeColorWithAlpha
                    strokeWidth = shape.strokeWidth
                    isAntiAlias = true
                    
                    strokeJoin = when (shape.strokeJoin.uppercase()) {
                        "MITER" -> android.graphics.Paint.Join.MITER
                        "BEVEL" -> android.graphics.Paint.Join.BEVEL
                        else -> android.graphics.Paint.Join.ROUND
                    }
                    strokeCap = when (shape.strokeCap.uppercase()) {
                        "BUTT" -> android.graphics.Paint.Cap.BUTT
                        "SQUARE" -> android.graphics.Paint.Cap.SQUARE
                        else -> android.graphics.Paint.Cap.ROUND
                    }
                }
                canvas.drawPath(androidPath, paintStroke)
            }
        }
    }
    return bitmap
}
