package ancic.karim.onlyheat

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Desktop
import java.net.URI

fun main() {
    initKoin()
    application {
        // Opens wide enough for the side rail + grid; narrowing the window falls back to phone layout.
        Window(::exitApplication, rememberWindowState(width = 1040.dp, height = 820.dp), title = "OnlyHeat") {
            App(
                // Set by the packaged .dmg app only, so `gradlew run` never offers updates.
                appVersion = System.getProperty("jpackage.app-version"),
                // ponytail: the browser downloads the new .dmg and the user drags it over; no in-place replace on macOS.
                installUpdate = { u, _ -> u.urls.find { it.endsWith(".dmg") }?.let { Desktop.getDesktop().browse(URI(it)) } },
            )
        }
    }
}
