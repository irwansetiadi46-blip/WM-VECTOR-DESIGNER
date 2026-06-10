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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun ColorPickerDialog(
    title: String,
    initialColorHex: String,
    initialAlpha: Float = 1f,
    supportNoneButton: Boolean = false,
    onNoneSelected: (() -> Unit)? = null,
    onColorSelected: (String, Float) -> Unit,
    onDismissRequest: () -> Unit,
    isStrokePanel: Boolean = false,
    viewModel: com.example.viewmodel.VectorViewModel? = null
) {
    // Designer built-in standard palette swatches (can be customized or appended via import)
    var palettesState by remember {
        mutableStateOf(
            listOf(
                "#000000", "#FFFFFF", "#EF4444", "#F97316", "#F59E0B", "#10B981",
                "#06B6D4", "#3B82F6", "#6366F1", "#8B5CF6", "#EC4899", "#6B7280"
            )
        )
    }

    var selectedTab by remember { mutableStateOf("HSV") } // "HSV", "RGB", "PALETTE", "JOIN", "CAP"
    var hexInput by remember { mutableStateOf(initialColorHex) }
    var alphaVal by remember { mutableStateOf(initialAlpha) }
    
    // Dialog state
    var showImportDialog by remember { mutableStateOf(false) }
    var importTextState by remember { mutableStateOf("") }

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
                // Title
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

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
                            color = Color(0xFF00E676),
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
                            focusedBorderColor = Color(0xFF00E676),
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
                                .background(if (isSel) Color(0xFF00E676) else Color.Transparent)
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
                                Text("${(bVal * 100).toInt()}%", color = Color(0xFF00E676), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            Slider(
                                value = bVal,
                                onValueChange = {
                                    bVal = it
                                    hexInput = hsvToHex(hVal, sVal, bVal)
                                },
                                valueRange = 0f..1f,
                                colors = SliderDefaults.colors(
                                    activeTrackColor = Color(0xFF00E676),
                                    thumbColor = Color(0xFF00E676)
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
                                Button(
                                    onClick = { showImportDialog = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "Import", tint = Color.White, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Import", color = Color.White, fontSize = 10.sp)
                                }
                            }

                            Box(modifier = Modifier.height(130.dp)) {
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(5),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(palettesState) { colorHex ->
                                        val colorValue = try {
                                            Color(android.graphics.Color.parseColor(colorHex))
                                        } catch (_: Exception) {
                                            Color.DarkGray
                                        }
                                        val isSel = hexInput.equals(colorHex, ignoreCase = true)

                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .aspectRatio(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(colorValue)
                                                .border(
                                                    width = if (isSel) 3.dp else 1.dp,
                                                    color = if (isSel) Color(0xFF00E676) else Color(0x33FFFFFF),
                                                    shape = RoundedCornerShape(8.dp)
                                                )
                                                .clickable {
                                                    hexInput = colorHex
                                                }
                                        )
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
                                                .background(if (isSel) Color(0xFF00E676) else Color(0xFF0F172A))
                                                .border(1.dp, if (isSel) Color(0xFF00E676) else Color(0xFF475569), RoundedCornerShape(8.dp))
                                                .clickable {
                                                    viewModel.currentStrokeJoin = join
                                                    if (viewModel.selectedShapeId != null || viewModel.selectedShapeIds.isNotEmpty()) {
                                                        viewModel.updateSelectedShapeStyle()
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
                                                .background(if (isSel) Color(0xFF00E676) else Color(0xFF0F172A))
                                                .border(1.dp, if (isSel) Color(0xFF00E676) else Color(0xFF475569), RoundedCornerShape(8.dp))
                                                .clickable {
                                                    viewModel.currentStrokeCap = cap
                                                    if (viewModel.selectedShapeId != null || viewModel.selectedShapeIds.isNotEmpty()) {
                                                        viewModel.updateSelectedShapeStyle()
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
                        Text("${(alphaVal * 100).toInt()}%", color = Color(0xFF00E676), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Slider(
                        value = alphaVal,
                        onValueChange = { alphaVal = it },
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(
                            activeTrackColor = Color(0xFF00E676),
                            thumbColor = Color(0xFF00E676)
                        )
                    )
                }

                // Confirm/Action Row buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (supportNoneButton) {
                        Button(
                            onClick = {
                                onNoneSelected?.invoke()
                                onDismissRequest()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("NONE", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }
                    Button(
                        onClick = {
                            onColorSelected(hexInput, alphaVal)
                            onDismissRequest()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                        modifier = Modifier.weight(if (supportNoneButton) 1.2f else 1f)
                    ) {
                        Text("Confirm", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }

                    Button(
                        onClick = onDismissRequest,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF475569)),
                        modifier = Modifier.weight(if (supportNoneButton) 0.8f else 1f)
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
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
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
