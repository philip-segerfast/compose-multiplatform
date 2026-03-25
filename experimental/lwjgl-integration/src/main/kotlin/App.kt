import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// ─────────────────────────────────────────────────────────────────────────────
// Root
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun App() {
    MaterialTheme(
        colors = lightColors(
            primary = Color(0xFF1976D2),
            secondary = Color(0xFF388E3C),
            surface = Color.White,
            background = Color(0xFFF5F5F5),
        ),
    ) {
        var selectedTab by remember { mutableStateOf(0) }
        val tabs = listOf("Mouse", "Keyboard", "Widgets", "Animation")

        Column(Modifier.fillMaxSize().background(MaterialTheme.colors.background.copy(alpha = 0.85f))) {
            // Tab row
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) },
                    )
                }
            }

            // Tab content
            Box(Modifier.fillMaxSize().padding(16.dp)) {
                when (selectedTab) {
                    0 -> MouseTestTab()
                    1 -> KeyboardTestTab()
                    2 -> WidgetsTestTab()
                    3 -> AnimationTestTab()
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tab 1: Mouse
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MouseTestTab() {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Mouse Input Tests", style = MaterialTheme.typography.h6)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ClickCounterCard(Modifier.weight(1f))
            HoverTestCard(Modifier.weight(1f))
        }

        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            DraggableBoxCard(Modifier.weight(1f).fillMaxHeight())
            ScrollAndCursorCard(Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun ClickCounterCard(modifier: Modifier = Modifier) {
    var leftClicks by remember { mutableStateOf(0) }
    var rightClicks by remember { mutableStateOf(0) }

    Card(modifier, elevation = 2.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Click Counter", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { leftClicks++ }) {
                    Text("Left: $leftClicks")
                }
                OutlinedButton(onClick = { rightClicks++ }) {
                    Text("Right: $rightClicks")
                }
            }
            Text(
                "Click the buttons above",
                style = MaterialTheme.typography.caption,
                color = Color.Gray,
            )
        }
    }
}

@Composable
private fun HoverTestCard(modifier: Modifier = Modifier) {
    Card(modifier, elevation = 2.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Hover Detection", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(4) { i ->
                    val interactionSource = remember { MutableInteractionSource() }
                    val isHovered by interactionSource.collectIsHoveredAsState()
                    val colors = listOf(Color.Red, Color.Blue, Color.Green, Color(0xFFFF9800))
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(if (isHovered) colors[i] else colors[i].copy(alpha = 0.3f))
                            .hoverable(interactionSource),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isHovered) Text("!", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Text(
                "Hover over the circles",
                style = MaterialTheme.typography.caption,
                color = Color.Gray,
            )
        }
    }
}

@Composable
private fun DraggableBoxCard(modifier: Modifier = Modifier) {
    Card(modifier, elevation = 2.dp) {
        var containerSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
        val boxSizeDp = 60.dp

        Box(
            Modifier
                .fillMaxSize()
                .padding(12.dp)
                .onSizeChanged { containerSize = it }
        ) {
            Text("Drag the Box", fontWeight = FontWeight.Bold)

            var offset by remember { mutableStateOf(Offset(80f, 80f)) }
            val boxSizePx = with(LocalDensity.current) { boxSizeDp.toPx() }
            val maxX = (containerSize.width - boxSizePx).coerceAtLeast(0f)
            val maxY = (containerSize.height - boxSizePx).coerceAtLeast(0f)

            Box(
                Modifier
                    .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
                    .size(boxSizeDp)
                    .shadow(4.dp, RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colors.primary, RoundedCornerShape(8.dp))
                    .pointerInput(containerSize) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            offset = Offset(
                                (offset.x + dragAmount.x).coerceIn(0f, maxX),
                                (offset.y + dragAmount.y).coerceIn(0f, maxY),
                            )
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "(${offset.x.toInt()}, ${offset.y.toInt()})",
                    color = Color.White,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun ScrollAndCursorCard(modifier: Modifier = Modifier) {
    Card(modifier, elevation = 2.dp) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Cursor & Scroll", fontWeight = FontWeight.Bold)

            var cursorPos by remember { mutableStateOf(Offset.Zero) }
            var scrollTotal by remember { mutableStateOf(0f) }

            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFFEEEEEE), RoundedCornerShape(8.dp))
                    .border(1.dp, Color.LightGray, RoundedCornerShape(8.dp))
                    .onPointerEvent(PointerEventType.Move) { event ->
                        val pos = event.changes.firstOrNull()?.position
                        if (pos != null) cursorPos = pos
                    }
                    .onPointerEvent(PointerEventType.Scroll) { event ->
                        val scroll = event.changes.firstOrNull()?.scrollDelta
                        if (scroll != null) scrollTotal += scroll.y
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Move mouse here", color = Color.Gray)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Cursor: (${cursorPos.x.toInt()}, ${cursorPos.y.toInt()})",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    )
                    Text(
                        "Scroll Y total: ${scrollTotal.toInt()}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tab 2: Keyboard
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun KeyboardTestTab() {
    val scrollState = rememberScrollState()
    Column(
        Modifier.fillMaxSize().verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Keyboard Input Tests", style = MaterialTheme.typography.h6)

        // Stack cards vertically so they don't clip at small window sizes
        TextFieldsCard(Modifier.fillMaxWidth())
        KeyEventLogCard(Modifier.fillMaxWidth())
    }
}

@Composable
private fun TextFieldsCard(modifier: Modifier = Modifier) {
    Card(modifier, elevation = 2.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Text Fields", fontWeight = FontWeight.Bold)

            // Material TextField
            var text1 by remember { mutableStateOf("") }
            OutlinedTextField(
                value = text1,
                onValueChange = { text1 = it },
                label = { Text("Standard TextField") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            // Password-style
            var text2 by remember { mutableStateOf("") }
            OutlinedTextField(
                value = text2,
                onValueChange = { text2 = it },
                label = { Text("Another TextField") },
                placeholder = { Text("Type something...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            // Multi-line
            var multiText by remember { mutableStateOf("") }
            OutlinedTextField(
                value = multiText,
                onValueChange = { multiText = it },
                label = { Text("Multi-line") },
                modifier = Modifier.fillMaxWidth().height(100.dp),
                maxLines = 5,
            )

            // BasicTextField with custom styling
            var basicText by remember { mutableStateOf("") }
            Text("BasicTextField (custom styled):", fontSize = 12.sp, color = Color.Gray)
            BasicTextField(
                value = basicText,
                onValueChange = { basicText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFE3F2FD), RoundedCornerShape(4.dp))
                    .padding(8.dp),
                textStyle = TextStyle(fontSize = 14.sp, fontFamily = FontFamily.Monospace),
                decorationBox = { innerTextField ->
                    if (basicText.isEmpty()) {
                        Text("Custom styled input...", color = Color.Gray, fontSize = 14.sp)
                    }
                    innerTextField()
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeyEventLogCard(modifier: Modifier = Modifier) {
    Card(modifier, elevation = 2.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Key Event Log", fontWeight = FontWeight.Bold)
            Text(
                "Focus the text field below and press keys. Events are logged here.",
                style = MaterialTheme.typography.caption,
                color = Color.Gray,
            )

            var inputText by remember { mutableStateOf("") }
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                label = { Text("Type here to see events") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(Modifier.height(4.dp))

            Text("Character count: ${inputText.length}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            Text("Last 10 chars: \"${inputText.takeLast(10)}\"", fontFamily = FontFamily.Monospace, fontSize = 13.sp)

            Divider(Modifier.padding(vertical = 4.dp))

            Text("Modifier Keys", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(
                "Hold Shift, Ctrl, or Alt while typing in the fields on the left.\n" +
                "If text selection (Shift+Arrow), copy/paste (Ctrl+C/V),\n" +
                "and cursor movement (Home/End) work, keyboard input is fully functional.",
                style = MaterialTheme.typography.caption,
                color = Color.Gray,
            )

            Spacer(Modifier.height(8.dp))

            // Quick test instructions
            Card(
                Modifier.fillMaxWidth(),
                backgroundColor = Color(0xFFFFF9C4),
                elevation = 0.dp,
            ) {
                Column(Modifier.padding(8.dp)) {
                    Text("Quick Tests:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text("1. Type text in the fields", fontSize = 11.sp)
                    Text("2. Select text with Shift+Arrow", fontSize = 11.sp)
                    Text("3. Ctrl+A to select all", fontSize = 11.sp)
                    Text("4. Ctrl+C / Ctrl+V (copy/paste)", fontSize = 11.sp)
                    Text("5. Home / End keys", fontSize = 11.sp)
                    Text("6. Backspace / Delete", fontSize = 11.sp)
                    Text("7. Tab to move between fields", fontSize = 11.sp)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tab 3: Widgets
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun WidgetsTestTab() {
    val scrollState = rememberScrollState()
    Column(
        Modifier.fillMaxSize().verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Widget Tests", style = MaterialTheme.typography.h6)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ButtonsCard(Modifier.weight(1f))
            CheckboxAndRadioCard(Modifier.weight(1f))
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SliderCard(Modifier.weight(1f))
            SwitchAndProgressCard(Modifier.weight(1f))
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            DropdownCard(Modifier.weight(1f))
            TooltipAndBadgeCard(Modifier.weight(1f))
        }

        // Scrollable list test
        ScrollableListCard(Modifier.fillMaxWidth())
    }
}

@Composable
private fun ButtonsCard(modifier: Modifier = Modifier) {
    var clickLog by remember { mutableStateOf("No clicks yet") }

    Card(modifier, elevation = 2.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Buttons", fontWeight = FontWeight.Bold)

            Button(onClick = { clickLog = "Filled button clicked" }) {
                Text("Filled Button")
            }
            OutlinedButton(onClick = { clickLog = "Outlined button clicked" }) {
                Text("Outlined Button")
            }
            TextButton(onClick = { clickLog = "Text button clicked" }) {
                Text("Text Button")
            }
            Button(onClick = {}, enabled = false) {
                Text("Disabled Button")
            }

            Divider()
            Text(clickLog, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color.Gray)
        }
    }
}

@Composable
private fun CheckboxAndRadioCard(modifier: Modifier = Modifier) {
    Card(modifier, elevation = 2.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Checkboxes & Radio Buttons", fontWeight = FontWeight.Bold)

            // Checkboxes
            val checkStates = remember { mutableStateListOf(false, true, false) }
            val checkLabels = listOf("Option A", "Option B", "Option C")
            checkLabels.forEachIndexed { i, label ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = checkStates[i],
                        onCheckedChange = { checkStates[i] = it },
                    )
                    Text(label, modifier = Modifier.clickable { checkStates[i] = !checkStates[i] })
                }
            }

            Divider(Modifier.padding(vertical = 4.dp))

            // Radio buttons
            var selectedRadio by remember { mutableStateOf(0) }
            val radioLabels = listOf("Small", "Medium", "Large")
            radioLabels.forEachIndexed { i, label ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = selectedRadio == i,
                        onClick = { selectedRadio = i },
                    )
                    Text(label, modifier = Modifier.clickable { selectedRadio = i })
                }
            }

            Text(
                "Checked: ${checkStates.mapIndexed { i, c -> if (c) checkLabels[i] else null }.filterNotNull()}",
                fontSize = 11.sp, color = Color.Gray,
            )
            Text("Radio: ${radioLabels[selectedRadio]}", fontSize = 11.sp, color = Color.Gray)
        }
    }
}

@Composable
private fun SliderCard(modifier: Modifier = Modifier) {
    Card(modifier, elevation = 2.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sliders", fontWeight = FontWeight.Bold)

            var slider1 by remember { mutableStateOf(0.5f) }
            Text("Continuous: ${(slider1 * 100).toInt()}%", fontSize = 13.sp)
            Slider(value = slider1, onValueChange = { slider1 = it })

            var slider2 by remember { mutableStateOf(3f) }
            Text("Stepped (1-10): ${slider2.toInt()}", fontSize = 13.sp)
            Slider(
                value = slider2,
                onValueChange = { slider2 = it },
                valueRange = 1f..10f,
                steps = 8,
            )

            // Color preview driven by sliders
            var r by remember { mutableStateOf(0.5f) }
            var g by remember { mutableStateOf(0.5f) }
            var b by remember { mutableStateOf(0.5f) }
            Text("RGB Color Mixer", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Slider(value = r, onValueChange = { r = it }, colors = SliderDefaults.colors(thumbColor = Color.Red, activeTrackColor = Color.Red))
                    Slider(value = g, onValueChange = { g = it }, colors = SliderDefaults.colors(thumbColor = Color.Green, activeTrackColor = Color.Green))
                    Slider(value = b, onValueChange = { b = it }, colors = SliderDefaults.colors(thumbColor = Color.Blue, activeTrackColor = Color.Blue))
                }
                Box(
                    Modifier
                        .size(48.dp)
                        .background(Color(r, g, b), RoundedCornerShape(8.dp))
                        .border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
                )
            }
        }
    }
}

@Composable
private fun SwitchAndProgressCard(modifier: Modifier = Modifier) {
    Card(modifier, elevation = 2.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Switches & Progress", fontWeight = FontWeight.Bold)

            // Switches
            var switch1 by remember { mutableStateOf(false) }
            var switch2 by remember { mutableStateOf(true) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = switch1, onCheckedChange = { switch1 = it })
                Text("Dark mode: ${if (switch1) "ON" else "OFF"}", fontSize = 13.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = switch2, onCheckedChange = { switch2 = it })
                Text("Notifications: ${if (switch2) "ON" else "OFF"}", fontSize = 13.sp)
            }

            Divider(Modifier.padding(vertical = 4.dp))

            // Progress indicators
            Text("Progress Indicators", fontWeight = FontWeight.Medium, fontSize = 13.sp)

            var progress by remember { mutableStateOf(0.3f) }
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { progress = (progress - 0.1f).coerceIn(0f, 1f) }, modifier = Modifier.height(32.dp)) {
                    Text("-", fontSize = 12.sp)
                }
                Button(onClick = { progress = (progress + 0.1f).coerceIn(0f, 1f) }, modifier = Modifier.height(32.dp)) {
                    Text("+", fontSize = 12.sp)
                }
                Text("${(progress * 100).toInt()}%", fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterVertically))
            }

            Spacer(Modifier.height(4.dp))
            Text("Indeterminate:", fontSize = 12.sp, color = Color.Gray)
            LinearProgressIndicator(Modifier.fillMaxWidth())

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Text("Loading...", fontSize = 12.sp, color = Color.Gray)
            }
        }
    }
}

@Composable
private fun DropdownCard(modifier: Modifier = Modifier) {
    Card(modifier, elevation = 2.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Dropdown Menu", fontWeight = FontWeight.Bold)

            var expanded by remember { mutableStateOf(false) }
            var selected by remember { mutableStateOf("Select an option...") }
            val options = listOf("Option 1", "Option 2", "Option 3", "Option 4", "Option 5")

            Box {
                OutlinedButton(onClick = { expanded = true }) {
                    Text(selected)
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    options.forEach { option ->
                        DropdownMenuItem(onClick = {
                            selected = option
                            expanded = false
                        }) {
                            Text(option)
                        }
                    }
                }
            }

            Text("Selected: $selected", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color.Gray)
        }
    }
}

@Composable
private fun TooltipAndBadgeCard(modifier: Modifier = Modifier) {
    Card(modifier, elevation = 2.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Snackbar & Alert Dialog", fontWeight = FontWeight.Bold)

            // Alert Dialog
            var showDialog by remember { mutableStateOf(false) }
            Button(onClick = { showDialog = true }) {
                Text("Show Dialog")
            }

            if (showDialog) {
                AlertDialog(
                    onDismissRequest = { showDialog = false },
                    title = { Text("Dialog Title") },
                    text = { Text("This is a test dialog. Does it render correctly?") },
                    confirmButton = {
                        Button(onClick = { showDialog = false }) {
                            Text("OK")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDialog = false }) {
                            Text("Cancel")
                        }
                    },
                )
            }

            Divider()

            // Chip-like elements
            Text("Clickable Chips", fontWeight = FontWeight.Medium, fontSize = 13.sp)
            var selectedChips by remember { mutableStateOf(setOf<String>()) }
            val chipLabels = listOf("Kotlin", "Compose", "LWJGL", "OpenGL", "Skia")
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                chipLabels.forEach { label ->
                    val isSelected = label in selectedChips
                    Surface(
                        modifier = Modifier.clickable {
                            selectedChips = if (isSelected) selectedChips - label else selectedChips + label
                        },
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) MaterialTheme.colors.primary else Color(0xFFE0E0E0),
                        elevation = if (isSelected) 2.dp else 0.dp,
                    ) {
                        Text(
                            label,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            color = if (isSelected) Color.White else Color.Black,
                            fontSize = 13.sp,
                        )
                    }
                }
            }
            Text(
                "Selected: ${selectedChips.joinToString(", ").ifEmpty { "none" }}",
                fontSize = 11.sp, color = Color.Gray,
            )
        }
    }
}

@Composable
private fun ScrollableListCard(modifier: Modifier = Modifier) {
    Card(modifier.height(200.dp), elevation = 2.dp) {
        Column(Modifier.padding(12.dp)) {
            Text("Scrollable List", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))

            val scrollState = rememberScrollState()
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    repeat(30) { i ->
                        var isHovered by remember { mutableStateOf(false) }
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .hoverable(remember { MutableInteractionSource() })
                                .clickable { /* could log click */ },
                            color = when {
                                i % 2 == 0 -> Color(0xFFF5F5F5)
                                else -> Color.White
                            },
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Text(
                                "List item #${i + 1}",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                fontSize = 13.sp,
                            )
                        }
                    }
                }

                // Scroll position indicator
                Column(
                    Modifier.width(100.dp).padding(start = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Scroll Info", fontWeight = FontWeight.Medium, fontSize = 12.sp)
                    Text("Position: ${scrollState.value}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text("Max: ${scrollState.maxValue}", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text(
                        "${if (scrollState.maxValue > 0) (scrollState.value * 100 / scrollState.maxValue) else 0}%",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Tab 4: Animation (original demo preserved)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AnimationTestTab() {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Animation & Rendering Tests", style = MaterialTheme.typography.h6)

        val infiniteTransition = rememberInfiniteTransition()
        val rotation by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 4000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        )

        val shape = RoundedCornerShape(12.dp)

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.White, RoundedCornerShape(8.dp))
                .border(1.dp, Color.LightGray, RoundedCornerShape(8.dp)),
        ) {
            // Checkerboard background
            val tileSize = 40.dp
            Column(Modifier.fillMaxSize()) {
                var rowIndex = 0
                repeat(20) {
                    Row(Modifier.fillMaxWidth()) {
                        repeat(20) { colIndex ->
                            val isDark = (rowIndex + colIndex) % 2 == 0
                            Box(
                                Modifier
                                    .size(tileSize)
                                    .background(if (isDark) Color(0xFFEEEEEE) else Color.White)
                            )
                        }
                    }
                    rowIndex++
                }
            }

            // Rotating rectangles
            Row(
                Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No shadow", color = Color.Black)
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .graphicsLayer { rotationZ = rotation }
                            .size(width = 60.dp, height = 180.dp)
                            .background(Color.Red, shape)
                            .border(3.dp, Color.Black, shape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${rotation.toInt()}", color = Color.White)
                    }
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("With shadow", color = Color.Black)
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .graphicsLayer { rotationZ = rotation }
                            .size(width = 60.dp, height = 180.dp)
                            .shadow(12.dp, shape)
                            .background(Color.Blue, shape)
                            .border(3.dp, Color.Black, shape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${rotation.toInt()}", color = Color.White)
                    }
                }
            }
        }
    }
}
