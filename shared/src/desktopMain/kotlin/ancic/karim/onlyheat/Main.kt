package ancic.karim.onlyheat

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() {
    initKoin()
    application {
        // Opens wide enough for the side rail + grid; narrowing the window falls back to phone layout.
        Window(::exitApplication, rememberWindowState(width = 1040.dp, height = 820.dp), title = "OnlyHeat") { App() }
    }
}
