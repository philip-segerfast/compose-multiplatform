import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
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

    Box(Modifier.fillMaxSize().background(Color.Blue.copy(0.1f)).border(5.dp, Color.Green).padding(8.dp), contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(16.dp)
         Box(
             Modifier
                 .rotate(animatedRotation)
                 .size(200.dp)
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

        Column(Modifier.width(400.dp).graphicsLayer {
            this.renderEffect = BlurEffect(10f, 10f, TileMode.Clamp)
        }) {
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
//    windowInfo: WindowInfo,
    content: @Composable () -> Unit,
) {
    val windowInfo = LocalWindowInfo.current
    val containerSize = windowInfo.containerSize
    val windowFocused = windowInfo.isWindowFocused

    LaunchedEffect(containerSize, windowFocused) {
        NodeLogger.group("RootContainer.LaunchedEffect")
        NodeLogger.log("Container size: $containerSize")
        NodeLogger.log("Window focused: $windowFocused")
        NodeLogger.popGroup()
    }

//    CompositionLocalProvider(LocalWindowInfo provides windowInfo) {
//        content()
//    }

    content()

}





