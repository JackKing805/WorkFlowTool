package io.github.workflowtool.ui

import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed

fun isPrimaryShortcutPressed(modifiers: PointerKeyboardModifiers): Boolean {
    return if (isMacOs()) modifiers.isMetaPressed else modifiers.isCtrlPressed
}

private fun isMacOs(): Boolean =
    System.getProperty("os.name").contains("Mac", ignoreCase = true)
