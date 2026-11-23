package ancic.karim.onlyheat

import androidx.compose.ui.window.ComposeUIViewController

// Host this from an Xcode SwiftUI/UIKit project via the `Shared` framework.
fun MainViewController() = run { initKoin(); ComposeUIViewController { App() } }
