package io.github.workflowtool

import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.darkColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.vinceglb.filekit.FileKit
import io.github.workflowtool.application.AppController
import io.github.workflowtool.application.DefaultLayoutConstraintPolicy
import io.github.workflowtool.application.DefaultLocalizationProvider
import io.github.workflowtool.application.LayoutSpec
import io.github.workflowtool.application.PythonImageEngine
import io.github.workflowtool.application.ServiceFactory
import io.github.workflowtool.application.loadFilesAsync
import io.github.workflowtool.domain.StringKey
import io.github.workflowtool.domain.WindowController
import io.github.workflowtool.ui.IconCropperApp
import io.github.workflowtool.ui.theme.Accent
import io.github.workflowtool.ui.theme.AppBg
import io.github.workflowtool.ui.theme.Panel
import java.awt.AWTEvent
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.Point
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.io.File

fun main() {
    installClipboardExceptionGuard()
    FileKit.init(appId = "io.github.workflowtool")
    application {
        val layoutSpec = remember { LayoutSpec() }
        val windowState = rememberWindowState(width = 1536.dp, height = 1024.dp)
        Window(
            onCloseRequest = ::exitApplication,
            undecorated = true,
            state = windowState,
            title = DefaultLocalizationProvider.text(StringKey.AppTitle)
        ) {
            val controller = rememberAppController(layoutSpec)
            val density = LocalDensity.current

            LaunchedEffect(Unit) {
                window.minimumSize = with(density) {
                    Dimension(
                        layoutSpec.minWindowWidth.roundToPx(),
                        layoutSpec.minWindowHeight.roundToPx()
                    )
                }
            }

            DisposableEffect(window, controller) {
                DropTarget(window, object : DropTargetAdapter() {
                    override fun drop(event: DropTargetDropEvent) {
                        runCatching {
                            if (!event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                                event.rejectDrop()
                                return
                            }
                            event.acceptDrop(DnDConstants.ACTION_COPY)
                            @Suppress("UNCHECKED_CAST")
                            val files =
                                event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                            controller.loadFilesAsync(files)
                            event.dropComplete(true)
                        }.onFailure {
                            event.dropComplete(false)
                        }
                    }
                })
                onDispose {
                    window.dropTarget = null
                }
            }

            val windowController = remember(windowState) {
                object : WindowController {
                    private var dragStartMouse: Point? = null
                    private var dragStartWindow: Point? = null

                    override fun minimize() {
                        windowState.isMinimized = true
                    }

                    override fun toggleMaximize() {
                        windowState.placement =
                            if (windowState.placement == WindowPlacement.Maximized) {
                                WindowPlacement.Floating
                            } else {
                                WindowPlacement.Maximized
                            }
                    }

                    override fun close() {
                        exitApplication()
                    }

                    override fun beginDrag(mouseScreenX: Int, mouseScreenY: Int) {
                        if (windowState.placement != WindowPlacement.Floating) return
                        dragStartMouse = Point(mouseScreenX, mouseScreenY)
                        dragStartWindow = Point(window.x, window.y)
                    }

                    override fun dragTo(mouseScreenX: Int, mouseScreenY: Int) {
                        if (windowState.placement != WindowPlacement.Floating) return
                        val startMouse = dragStartMouse ?: return
                        val startWindow = dragStartWindow ?: return
                        window.location = Point(
                            startWindow.x + (mouseScreenX - startMouse.x),
                            startWindow.y + (mouseScreenY - startMouse.y)
                        )
                    }

                    override fun endDrag() {
                        dragStartMouse = null
                        dragStartWindow = null
                    }
                }
            }

            MaterialTheme(
                colors = darkColors(
                    primary = Accent,
                    background = AppBg,
                    surface = Panel
                )
            ) {
                Surface(color = AppBg) {
                    IconCropperApp(controller, windowController)
                }
            }
        }
    }
}

private fun installClipboardExceptionGuard() {
    Toolkit.getDefaultToolkit().systemEventQueue.push(object : EventQueue() {
        override fun dispatchEvent(event: AWTEvent) {
            try {
                super.dispatchEvent(event)
            } catch (error: IllegalStateException) {
                if (error.message?.contains("cannot open system clipboard", ignoreCase = true) != true) {
                    throw error
                }
            }
        }
    })
}

@Composable
private fun rememberAppController(layoutSpec: LayoutSpec): AppController {
    return remember {
        AppController(
            detector = ServiceFactory.detector(),
            splitter = ServiceFactory.splitter(),
            exporter = ServiceFactory.exporter(),
            layoutSpec = layoutSpec,
            localization = DefaultLocalizationProvider,
            layoutPolicy = DefaultLayoutConstraintPolicy(),
            nativeEngine = PythonImageEngine
        )
    }
}
