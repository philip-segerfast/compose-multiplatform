import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * Demo composable with continuously rotating rectangles to verify rendering correctness.
 *
 * Features exercised:
 *  - Infinite animation via Compose's [rememberInfiniteTransition]
 *  - GPU-accelerated rotation via [graphicsLayer]
 *  - Drop shadows (tests Skia's stencil buffer usage)
 *  - Checkerboard background (makes stale pixels / smearing obvious)
 */
@Composable
fun App() {
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
        // Checkerboard background — makes stale pixels very obvious
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
