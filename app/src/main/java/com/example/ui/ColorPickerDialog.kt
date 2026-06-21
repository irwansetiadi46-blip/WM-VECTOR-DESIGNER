package com.example.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

fun extractColorsFromBitmap(bitmap: Bitmap, maxColors: Int = 24): List<String> {
    val width = bitmap.width
    val height = bitmap.height
    val sampledColors = mutableListOf<Int>()
    
    val xSteps = 15
    val ySteps = 15
    for (i in 0 until xSteps) {
        for (j in 0 until ySteps) {
            val x = (width * (i + 0.5f) / xSteps).toInt().coerceIn(0, width - 1)
            val y = (height * (j + 0.5f) / ySteps).toInt().coerceIn(0, height - 1)
            val pixel = bitmap.getPixel(x, y)
            val alpha = android.graphics.Color.alpha(pixel)
            if (alpha > 128) {
                sampledColors.add(pixel)
            }
        }
    }

    val freqMap = mutableMapOf<Int, Int>()
    for (color in sampledColors) {
        freqMap[color] = (freqMap[color] ?: 0) + 1
    }

    val sortedColors = freqMap.entries.sortedByDescending { it.value }.map { it.key }

    val distinctColors = mutableListOf<Int>()
    for (color in sortedColors) {
        val r1 = android.graphics.Color.red(color)
        val g1 = android.graphics.Color.green(color)
        val b1 = android.graphics.Color.blue(color)
        
        var isDifferentEnough = true
        for (existing in distinctColors) {
            val r2 = android.graphics.Color.red(existing)
            val g2 = android.graphics.Color.green(existing)
            val b2 = android.graphics.Color.blue(existing)
            
            val distance = sqrt(
                ((r1 - r2) * (r1 - r2) + (g1 - g2) * (g1 - g2) + (b1 - b2) * (b1 - b2)).toDouble()
            )
            if (distance < 35.0) {
                isDifferentEnough = false
                break
            }
        }
        if (isDifferentEnough) {
            distinctColors.add(color)
            if (distinctColors.size >= maxColors) break
        }
    }

    return distinctColors.map { color ->
        val r = android.graphics.Color.red(color)
        val g = android.graphics.Color.green(color)
        val b = android.graphics.Color.blue(color)
        String.format(java.util.Locale.US, "#%02X%02X%02X", r, g, b)
    }
}

@Composable
fun ColorPickerDialog(
    title: String,
    initialColorHex: String,
    initialAlpha: Float = 1f,
    supportNoneButton: Boolean = false,
    initialEnabled: Boolean = true,
    onNoneSelected: (() -> Unit)? = null,
    onColorSelected: (String, Float) -> Unit,
    onDismissRequest: () -> Unit,
    isStrokePanel: Boolean = false,
    viewModel: com.example.viewmodel.VectorViewModel? = null
) {
    var isEnabledState by remember { mutableStateOf(initialEnabled) }
    
    // Designer built-in standard palette swatches (can be customized or appended via import)
    var palettesState by remember {
        mutableStateOf(
            listOf(
                // Grayscale / Basics
                "#000000", "#1A1A1A", "#333333", "#4D4D4D", "#666666", "#808080", "#999999", "#B3B3B3", "#CCCCCC", "#E6E6E6", "#F5F5F5", "#FFFFFF",
                // Reds / Pinks
                "#FFEBEE", "#FFCDD2", "#EF9A9A", "#E57373", "#EF5350", "#F44336", "#E53935", "#D32F2F", "#C62828", "#B71C1C", "#FF4081", "#F50057",
                // Oranges / Browns
                "#FFF3E0", "#FFE0B2", "#FFCC80", "#FFB74D", "#FFA726", "#FF9800", "#FB8C00", "#F57C00", "#EF6C00", "#E65100", "#8D6E63", "#5D4037",
                // Yellows / Amber
                "#FFFDE7", "#FFF9C4", "#FFF59D", "#FFF176", "#FFEE58", "#FFEB3B", "#FDD835", "#FBC02D", "#F9A825", "#F57F17", "#FFE125", "#FFD54F",
                // Greens
                "#E8F5E9", "#C8E6C9", "#A5D6A7", "#81C784", "#66BB6A", "#4CAF50", "#43A047", "#388E3C", "#2E7D32", "#1B5E20", "#76FF03", "#00E676",
                // Teals / Cyans
                "#E0F2F1", "#B2DFDB", "#80CBC4", "#4DB6AC", "#26A69A", "#009688", "#00897B", "#00796B", "#00695C", "#004D40", "#1DE9B6", "#00E5FF",
                // Blues / Indigo
                "#E3F2FD", "#BBDEFB", "#90CAF9", "#64B5F6", "#42A5F5", "#2196F3", "#1E88E5", "#1976D2", "#1565C0", "#0D47A1", "#2979FF", "#3D5AFE",
                // Purples / Violets
                "#F3E5F5", "#E1BEE7", "#CE93D8", "#BA68C8", "#AB47BC", "#9C27B0", "#8E24AA", "#7B1FA2", "#6A1B9A", "#4A148C", "#D500F9", "#AA00FF"
            )
        )
    }

    var selectedTab by remember { mutableStateOf("HSV") } // "HSV", "RGB", "PALETTE", "JOIN", "CAP"
    var hexInput by remember { mutableStateOf(initialColorHex) }
    var alphaVal by remember { mutableStateOf(initialAlpha) }
    
    // Dialog state
    var showImportDialog by remember { mutableStateOf(false) }
    var importTextState by remember { mutableStateOf("") }

    var localImportedPalettes by remember { mutableStateOf<List<List<String>>>(emptyList()) }
    val currentImportedPalettes = if (viewModel != null) {
        viewModel.importedPalettes
    } else {
        localImportedPalettes
    }
    val addImportedPalette: (List<String>) -> Unit = { newColors ->
        val newList = currentImportedPalettes + listOf(newColors)
        if (viewModel != null) {
            viewModel.saveImportedPalettes(newList)
        } else {
            localImportedPalettes = newList
        }
    }
    val deleteImportedPaletteGroup: (Int) -> Unit = { index ->
        val newList = currentImportedPalettes.toMutableList()
        if (index in newList.indices) {
            newList.removeAt(index)
            if (viewModel != null) {
                viewModel.saveImportedPalettes(newList)
            } else {
                localImportedPalettes = newList
            }
        }
    }
    val clearAllImportedPalettes: () -> Unit = {
        if (viewModel != null) {
            viewModel.saveImportedPalettes(emptyList())
        } else {
            localImportedPalettes = emptyList()
        }
    }

    val context = LocalContext.current
    val imageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri).use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream)
                    if (bitmap != null) {
                        val extracted = extractColorsFromBitmap(bitmap, maxColors = 24)
                        if (extracted.isNotEmpty()) {
                            addImportedPalette(extracted)
                            Toast.makeText(context, "Berhasil mengimport ${extracted.size} warna!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Tidak dapat mendeteksi warna dari gambar ini.", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(context, "Gambar tidak dapat dibaca.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Gagal mengimport gambar: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Helpers to convert hex
    fun updateHex(newHex: String) {
        var clean = newHex.trim()
        if (!clean.startsWith("#")) {
            clean = "#$clean"
        }
        if (clean.length == 7 || clean.length == 9) {
            hexInput = clean
        }
    }

    Dialog(onDismissRequest = onDismissRequest) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .wrapContentHeight(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)), // High contrast deep slate
            shape = RoundedCornerShape(20.dp),
            border = borderStrokeHelper()
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Title and Toggle Switch Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    
                    if (supportNoneButton) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = if (isEnabledState) "Aktif" else "Nonaktif",
                                color = if (isEnabledState) Color(0xFFFF6D00) else Color.LightGray,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Switch(
                                checked = isEnabledState,
                                onCheckedChange = { isEnabledState = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.Black,
                                    checkedTrackColor = Color(0xFFFF6D00),
                                    uncheckedThumbColor = Color.LightGray,
                                    uncheckedTrackColor = Color(0xFF334155)
                                )
                            )
                        }
                    }
                }

                // Color configuration block (dimmed and disabled if not active)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (isEnabledState) Modifier else Modifier.alpha(0.4f).pointerInput(Unit) {}),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Current chosen color preview banner panel
                    val parsedColor = try {
                        Color(android.graphics.Color.parseColor(hexInput))
                    } catch (_: Exception) {
                        Color.Gray
                    }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0F172A))
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(parsedColor)
                            .border(1.5.dp, Color(0xFF475569), RoundedCornerShape(10.dp))
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = hexInput.uppercase(),
                            color = Color(0xFFFF6D00),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Hex Code Format Selection",
                            color = Color.LightGray,
                            fontSize = 11.sp
                        )
                    }

                    // Direct Hex field edits
                    OutlinedTextField(
                        value = hexInput,
                        onValueChange = { input ->
                            var clean = input.trim()
                            if (!clean.startsWith("#")) {
                                clean = "#$clean"
                            }
                            if (clean.length <= 9) {
                                hexInput = clean
                            }
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFFF6D00),
                            unfocusedBorderColor = Color(0xFF475569),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                        modifier = Modifier.width(110.dp)
                    )
                }

                // Modes Navigation tabs
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF0F172A))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    val tabs = if (isStrokePanel) listOf("HSV", "RGB", "PALETTE", "JOIN", "CAP") else listOf("HSV", "RGB", "PALETTE")
                    tabs.forEach { tabName ->
                        val isSel = selectedTab == tabName
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSel) Color(0xFFFF6D00) else Color.Transparent)
                                .clickable { selectedTab = tabName }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = tabName,
                                color = if (isSel) Color.Black else Color.LightGray,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Content Areas depending on active tab
                when (selectedTab) {

                    "RGB" -> {
                        val rgb = hexToRgb(hexInput)
                        var rVal by remember(hexInput) { mutableStateOf(rgb.first) }
                        var gVal by remember(hexInput) { mutableStateOf(rgb.second) }
                        var bVal by remember(hexInput) { mutableStateOf(rgb.third) }

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ImportedPaletteContainer(
                                importedPalettes = currentImportedPalettes,
                                selectedHex = hexInput,
                                onColorClick = { hexInput = it },
                                onUploadClick = { imageLauncher.launch("image/*") },
                                onDeleteGroupClick = deleteImportedPaletteGroup,
                                onDeleteAllClick = clearAllImportedPalettes
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            // Red Slider
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Red Channel:", color = Color.White, fontSize = 12.sp)
                                Text("$rVal", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            Slider(
                                value = rVal.toFloat(),
                                onValueChange = {
                                    rVal = it.toInt()
                                    hexInput = rgbToHex(rVal, gVal, bVal)
                                },
                                valueRange = 0f..255f,
                                colors = SliderDefaults.colors(
                                    activeTrackColor = Color(0xFFEF4444),
                                    thumbColor = Color(0xFFEF4444)
                                )
                            )

                            // Green Slider
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Green Channel:", color = Color.White, fontSize = 12.sp)
                                Text("$gVal", color = Color(0xFF10B981), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            Slider(
                                value = gVal.toFloat(),
                                onValueChange = {
                                    gVal = it.toInt()
                                    hexInput = rgbToHex(rVal, gVal, bVal)
                                },
                                valueRange = 0f..255f,
                                colors = SliderDefaults.colors(
                                    activeTrackColor = Color(0xFF10B981),
                                    thumbColor = Color(0xFF10B981)
                                )
                            )

                            // Blue Slider
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Blue Channel:", color = Color.White, fontSize = 12.sp)
                                Text("$bVal", color = Color(0xFF3B82F6), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            Slider(
                                value = bVal.toFloat(),
                                onValueChange = {
                                    bVal = it.toInt()
                                    hexInput = rgbToHex(rVal, gVal, bVal)
                                },
                                valueRange = 0f..255f,
                                colors = SliderDefaults.colors(
                                    activeTrackColor = Color(0xFF3B82F6),
                                    thumbColor = Color(0xFF3B82F6)
                                )
                            )
                        }
                    }

                    "HSV" -> {
                        val hsv = hexToHsv(hexInput)
                        var hVal by remember(hexInput) { mutableStateOf(hsv[0]) }
                        var sVal by remember(hexInput) { mutableStateOf(hsv[1]) }
                        var bVal by remember(hexInput) { mutableStateOf(hsv[2]) }

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ImportedPaletteContainer(
                                importedPalettes = currentImportedPalettes,
                                selectedHex = hexInput,
                                onColorClick = { hexInput = it },
                                onUploadClick = { imageLauncher.launch("image/*") },
                                onDeleteGroupClick = deleteImportedPaletteGroup,
                                onDeleteAllClick = clearAllImportedPalettes
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            // Hue
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Hue (Degree):", color = Color.White, fontSize = 12.sp)
                                Text("${hVal.toInt()}°", color = Color(0xFFF59E0B), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            Slider(
                                value = hVal,
                                onValueChange = {
                                    hVal = it
                                    hexInput = hsvToHex(hVal, sVal, bVal)
                                },
                                valueRange = 0f..360f,
                                colors = SliderDefaults.colors(
                                    activeTrackColor = Color(0xFFF59E0B),
                                    thumbColor = Color(0xFFF59E0B)
                                )
                            )

                            // Saturation
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Saturation %:", color = Color.White, fontSize = 12.sp)
                                Text("${(sVal * 100).toInt()}%", color = Color(0xFFEC4899), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            Slider(
                                value = sVal,
                                onValueChange = {
                                    sVal = it
                                    hexInput = hsvToHex(hVal, sVal, bVal)
                                },
                                valueRange = 0f..1f,
                                colors = SliderDefaults.colors(
                                    activeTrackColor = Color(0xFFEC4899),
                                    thumbColor = Color(0xFFEC4899)
                                )
                            )

                            // Brightness
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Brightness (Value) %:", color = Color.White, fontSize = 12.sp)
                                Text("${(bVal * 100).toInt()}%", color = Color(0xFFFF6D00), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            Slider(
                                value = bVal,
                                onValueChange = {
                                    bVal = it
                                    hexInput = hsvToHex(hVal, sVal, bVal)
                                },
                                valueRange = 0f..1f,
                                colors = SliderDefaults.colors(
                                    activeTrackColor = Color(0xFFFF6D00),
                                    thumbColor = Color(0xFFFF6D00)
                                )
                            )
                        }
                    }

                    "PALETTE" -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Custom Swatches Palette", color = Color.White, fontSize = 12.sp)
                            }

                            val numCols = 12
                            val numRows = (palettesState.size + numCols - 1) / numCols

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                for (c in 0 until numCols) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        for (r in 0 until numRows) {
                                            val index = c * numRows + r
                                            if (index < palettesState.size) {
                                                val colorHex = palettesState[index]
                                                val colorValue = try {
                                                    Color(android.graphics.Color.parseColor(colorHex))
                                                } catch (_: Exception) {
                                                    Color.DarkGray
                                                }
                                                val isSel = hexInput.equals(colorHex, ignoreCase = true)

                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .aspectRatio(1f)
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(colorValue)
                                                        .border(
                                                            width = if (isSel) 2.dp else 1.dp,
                                                            color = if (isSel) Color(0xFFFF6D00) else Color(0x33FFFFFF),
                                                            shape = RoundedCornerShape(4.dp)
                                                        )
                                                        .clickable {
                                                            hexInput = colorHex
                                                        }
                                                )
                                            } else {
                                                Spacer(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .aspectRatio(1f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    "JOIN" -> {
                        if (viewModel != null) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text("Line Join:", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    listOf("MITER", "ROUND", "BEVEL").forEach { join ->
                                        val isSel = viewModel.currentStrokeJoin == join
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(36.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (isSel) Color(0xFFFF6D00) else Color(0xFF0F172A))
                                                .border(1.dp, if (isSel) Color(0xFFFF6D00) else Color(0xFF475569), RoundedCornerShape(8.dp))
                                                .clickable {
                                                    viewModel.currentStrokeJoin = join
                                                    if (viewModel.selectedShapeId != null || viewModel.selectedShapeIds.isNotEmpty()) {
                                                        viewModel.updateSelectedShapeProperties(strokeJoin = join)
                                                    }
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(join, color = if (isSel) Color.Black else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    "CAP" -> {
                        if (viewModel != null) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text("Line Cap:", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    listOf("ROUND", "BUTT", "SQUARE").forEach { cap ->
                                        val isSel = viewModel.currentStrokeCap == cap
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(36.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (isSel) Color(0xFFFF6D00) else Color(0xFF0F172A))
                                                .border(1.dp, if (isSel) Color(0xFFFF6D00) else Color(0xFF475569), RoundedCornerShape(8.dp))
                                                .clickable {
                                                    viewModel.currentStrokeCap = cap
                                                    if (viewModel.selectedShapeId != null || viewModel.selectedShapeIds.isNotEmpty()) {
                                                        viewModel.updateSelectedShapeProperties(strokeCap = cap)
                                                    }
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(cap, color = if (isSel) Color.Black else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Opacity Adjustment Slider inside Color Picker Popup Dialog
                if (selectedTab != "PALETTE") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Opacity (Transparency):", color = Color.White, fontSize = 12.sp)
                            Text("${(alphaVal * 100).toInt()}%", color = Color(0xFFFF6D00), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Slider(
                            value = alphaVal,
                            onValueChange = { alphaVal = it },
                            valueRange = 0f..1f,
                            colors = SliderDefaults.colors(
                                activeTrackColor = Color(0xFFFF6D00),
                                thumbColor = Color(0xFFFF6D00)
                            )
                        )
                    } // End of Opacity Column
                }
                } // End of Color configuration block

                // Confirm/Action Row buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            if (supportNoneButton && !isEnabledState) {
                                onNoneSelected?.invoke()
                            } else {
                                onColorSelected(hexInput, alphaVal)
                            }
                            onDismissRequest()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6D00)),
                        modifier = Modifier.weight(1.2f)
                    ) {
                        Text("Confirm", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }

                    Button(
                        onClick = onDismissRequest,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF475569)),
                        modifier = Modifier.weight(0.8f)
                    ) {
                        Text("Cancel", color = Color.White, fontSize = 11.sp)
                    }
                }
            }
        }
    }

    // Sub Modal to Import custom Palettes from comma separated list
    if (showImportDialog) {
        Dialog(onDismissRequest = { showImportDialog = false }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .wrapContentHeight(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                shape = RoundedCornerShape(14.dp),
                border = borderStrokeHelper()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Import Color Swatches",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        "Enter hex codes separated by commas (example: #FF0000, #00FF00, #0000FF):",
                        color = Color.LightGray,
                        fontSize = 11.sp
                    )

                    OutlinedTextField(
                        value = importTextState,
                        onValueChange = { importTextState = it },
                        placeholder = { Text("#EF44EF, #3E4E8E") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (importTextState.isNotBlank()) {
                                    val decodedHexGroup = importTextState
                                        .split(",")
                                        .map { it.trim() }
                                        .filter { it.startsWith("#") && (it.length == 7 || it.length == 9) }
                                    if (decodedHexGroup.isNotEmpty()) {
                                        palettesState = decodedHexGroup
                                        // Pick first element
                                        hexInput = decodedHexGroup.first()
                                    }
                                }
                                showImportDialog = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6D00)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Convert", color = Color.Black)
                        }

                        Button(
                            onClick = { showImportDialog = false },
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
}

@Composable
private fun borderStrokeHelper(): androidx.compose.foundation.BorderStroke {
    return androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF475569))
}

private fun hexToRgb(hex: String): Triple<Int, Int, Int> {
    val clean = hex.removePrefix("#")
    return try {
        if (clean.length >= 6) {
            val r = clean.substring(0, 2).toInt(16)
            val g = clean.substring(2, 4).toInt(16)
            val b = clean.substring(4, 6).toInt(16)
            Triple(r, g, b)
        } else {
            Triple(255, 255, 255)
        }
    } catch (_: Exception) {
        Triple(255, 255, 255)
    }
}

private fun rgbToHex(r: Int, g: Int, b: Int): String {
    return String.format("#%02X%02X%02X", r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
}

private fun hexToHsv(hex: String): FloatArray {
    val rgb = hexToRgb(hex)
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV(rgb.first, rgb.second, rgb.third, hsv)
    return hsv
}

private fun hsvToHex(h: Float, s: Float, v: Float): String {
    val hsv = floatArrayOf(h, s, v)
    val colorInt = android.graphics.Color.HSVToColor(hsv)
    return String.format("#%02X%02X%02X", 
        android.graphics.Color.red(colorInt),
        android.graphics.Color.green(colorInt),
        android.graphics.Color.blue(colorInt)
    )
}

@Composable
fun ImportedPaletteContainer(
    importedPalettes: List<List<String>>,
    selectedHex: String,
    onColorClick: (String) -> Unit,
    onUploadClick: () -> Unit,
    onDeleteGroupClick: (Int) -> Unit,
    onDeleteAllClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0F172A))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
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
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFF6D00))
                )
                Text(
                    text = "Imported Image Palette",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onUploadClick,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6D00)),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    modifier = Modifier.height(24.dp)
                ) {
                    Text(text = "Upload JPG", color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }

                if (importedPalettes.isNotEmpty()) {
                    TextButton(
                        onClick = onDeleteAllClick,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text(
                            text = "Hapus Semua",
                            color = Color(0xFFEF4444),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        if (importedPalettes.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Belum ada warna. Klik 'Upload JPG' untuk mengimport gambar palette.",
                    color = Color.Gray,
                    fontSize = 11.sp,
                    style = androidx.compose.ui.text.TextStyle(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                importedPalettes.forEachIndexed { groupIndex, colors ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(8.dp))
                            .background(Color(0xFF1E293B).copy(alpha = 0.3f))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Kelompok Palette ${groupIndex + 1}",
                                color = Color.Gray,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium
                            )

                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Hapus Kelompok",
                                tint = Color(0xFFEF4444),
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable {
                                        onDeleteGroupClick(groupIndex)
                                    }
                            )
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                colors.forEach { colorHex ->
                                    val colorVal = try {
                                        Color(android.graphics.Color.parseColor(colorHex))
                                    } catch (_: Exception) {
                                        Color.Gray
                                    }
                                    
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(colorVal)
                                            .border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(6.dp))
                                            .clickable {
                                                onColorClick(colorHex)
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
}
