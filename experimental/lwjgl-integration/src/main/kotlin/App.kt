import androidx.compose.animation.core.*
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.DelicateCoroutinesApi

@OptIn(DelicateCoroutinesApi::class)
@Composable
fun App() {
    // Continuously rotating rectangles to verify rendering correctness.
    // Uses Compose's animation system (InfiniteTransition) for smooth rotation,
    // and graphicsLayer for GPU-accelerated transforms.

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
            .background(Color.White),
    ) {
        // Draw a checkerboard grid
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
                                .background(if (isDark) Color.LightGray else Color.White)
                        )
                    }
                }
                rowIndex++
            }
        }

        // Two columns side by side for comparison
        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // LEFT: No shadow
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
                    Text("${rotation.toInt()}°", color = Color.White)
                }
            }

            // RIGHT: With shadow
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
                    Text("${rotation.toInt()}°", color = Color.White)
                }
            }
        }
    }
}

@Composable
fun App1() {
    Column(Modifier.fillMaxSize().background(Color.Cyan).border(5.dp, Color.Magenta)) {
        var text by remember { mutableStateOf("Text") }
        val listState = rememberLazyListState()

        Column(Modifier.width(400.dp)) {
            TextField(text, { text = it }, modifier = Modifier.fillMaxWidth())
            Button({}) {
                Text("Hello!")
            }
            Row {
                LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxHeight()) {
                    items(100) {
                        Text("Item $it")
                    }
                }
                VerticalScrollbar(
                    rememberScrollbarAdapter(listState),
                    modifier = Modifier.fillMaxHeight()
                )
            }
        }
    }
}

@Composable
fun RootContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // Todo - this is a temporary fix. Adding a border fixes graphics glitch
    val windowInfo = LocalWindowInfo.current
    val containerSize = windowInfo.containerSize
    val windowFocused = windowInfo.isWindowFocused

    LaunchedEffect(containerSize, windowFocused) {
        NodeLogger.group("RootContainer.LaunchedEffect")
        NodeLogger.log("Container size: $containerSize")
        NodeLogger.log("Window focused: $windowFocused")
        NodeLogger.popGroup()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .border(Dp.Hairline, Color.White.copy(0.01f))
    ) {
        content()
    }
}





