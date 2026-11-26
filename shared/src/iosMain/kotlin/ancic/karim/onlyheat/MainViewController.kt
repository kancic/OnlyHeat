package ancic.karim.onlyheat

import ancic.karim.onlyheat.data.FIRMWARE
import androidx.compose.ui.window.ComposeUIViewController
import com.russhwolf.settings.Settings
import org.koin.mp.KoinPlatform


// Host this from an Xcode SwiftUI/UIKit project via the `Shared` framework.
// iOS can't tell whether local-network access is granted without asking. A firmware version read from the controller's
// own dashboard means a local request already got through, so the AC scan won't pop the prompt.
fun MainViewController() = run {
    initKoin()
    ComposeUIViewController {
        App(hasLocalNetwork = { true.takeIf { KoinPlatform.getKoin().get<Settings>().hasKey(FIRMWARE) } })
    }
}
