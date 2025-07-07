import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

@OptIn(DelicateCoroutinesApi::class)
@Composable
fun App() {
    // This composable is unused for now.

    var num by remember { mutableStateOf(0) }
    var rotationTarget by remember { mutableStateOf(0f) }
    val animatedRotation by animateFloatAsState(
        targetValue = rotationTarget,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = 1f,
        ),
    )

    LaunchedEffect(Unit) {
        launch {
            while(true) {
                rotationTarget = 360f
                delay(4.seconds)
                rotationTarget = 0f
                delay(4.seconds)
            }
        }
        launch {
            while(true) {
                delay(1.seconds)
                num++
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Blue.copy(0.3f))
//            .border(5.dp, Color.Green)
            .padding(8.dp)
            .focusable()
            .onKeyEvent { event ->
                when(event.key) {
                    Key.DirectionLeft -> {
                        println("LEFT")
                        rotationTarget -= 1
                        true
                    }
                    Key.DirectionRight -> {
                        println("RIGHT")
                        rotationTarget += 1
                        true
                    }
                    else -> false
                }
            }
        ,
        contentAlignment = Alignment.Center
    ) {
        val shape = RoundedCornerShape(16.dp)
         Box(
             Modifier
                 .rotate(animatedRotation)
                 .size(200.dp)
                 .shadow(10.dp, shape)
                 .background(Color.Red, shape)
                 .border(8.dp, Color.Blue, shape)
             ,
             contentAlignment = Alignment.Center,
         ) {
             Text(num.toString())
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





