package io.github.workflowtool.application

import io.github.workflowtool.model.CropRegion
import io.github.workflowtool.application.focusRegion

fun AppController.clearRegions() {
    replaceRegions("清空区域", emptyList())
    previewRegionId = null
    log("已清空区域")
}

fun AppController.undo() {
    val before = regions
    history.undo()
    if (before != regions) {
        syncManualEdits()
        rememberWorkspaceSnapshot()
    }
}

fun AppController.redo() {
    val before = regions
    history.redo()
    if (before != regions) {
        syncManualEdits()
        rememberWorkspaceSnapshot()
    }
}

fun AppController.replaceRegions(label: String, updated: List<CropRegion>, trackHistory: Boolean = true) {
    val before = regions
    val normalized = normalizeRegionIds(updated)
    history.replaceRegions(label, normalized, trackHistory)
    if (before != regions) {
        syncManualEdits()
        if (trackHistory) {
            rememberWorkspaceSnapshot()
        }
    }
}

fun AppController.selectRegion(regionId: String, additive: Boolean = false) {
    if (regions.none { it.id == regionId }) return
    val updated = regions.map { region ->
        when {
            region.id == regionId -> region.copy(selected = true)
            additive -> region
            else -> region.copy(selected = false)
        }
    }
    replaceRegions("选择区域", updated, trackHistory = false)
}

fun AppController.selectAndFocusRegion(regionId: String, fit: Boolean = false) {
    selectRegion(regionId)
    focusRegion(regionId, fit)
}

fun AppController.selectAll() = replaceRegions("全选区域", regions.map { it.copy(selected = true) }, trackHistory = false)

fun AppController.invertSelection() =
    replaceRegions("反选区域", regions.map { it.copy(selected = !it.selected) }, trackHistory = false)

fun AppController.clearSelection() =
    replaceRegions("取消选择", regions.map { it.copy(selected = false) }, trackHistory = false)

fun AppController.removeRegion(regionId: String) {
    if (regions.none { it.id == regionId }) return
    replaceRegions("删除区域", regions.filterNot { it.id == regionId })
    if (previewRegionId == regionId) {
        previewRegionId = null
    }
}

fun AppController.toggleVisibility(regionId: String) {
    if (regions.none { it.id == regionId }) return
    val shouldShow = regions.any { it.id == regionId && !it.visible }
    replaceRegions(
        "切换区域显示",
        regions.map { if (it.id == regionId) it.copy(visible = shouldShow) else it }
    )
}
