package io.github.workflowtool.application

import io.github.workflowtool.model.CropRegion
import io.github.workflowtool.model.groupMemberIdsFor
import io.github.workflowtool.application.focusRegion

fun AppController.clearRegions() {
    replaceRegions("清空区域", emptyList())
    previewRegionId = null
    magicSelectionPreview = null
    log("已清空区域")
}

fun AppController.undo() {
    val before = regions
    history.undo()
    if (before != regions) syncManualEdits()
}

fun AppController.redo() {
    val before = regions
    history.redo()
    if (before != regions) syncManualEdits()
}

fun AppController.replaceRegions(label: String, updated: List<CropRegion>, trackHistory: Boolean = true) {
    val normalized = normalizeRegionIds(updated)
    history.replaceRegions(label, normalized, trackHistory)
    syncManualEdits()
}

fun AppController.selectRegion(regionId: String, additive: Boolean = false) {
    if (regions.none { it.id == regionId }) return
    val groupIds = groupMemberIdsFor(regions, regionId).ifEmpty { setOf(regionId) }
    val updated = regions.map { region ->
        when {
            region.id in groupIds -> region.copy(selected = true)
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
    val groupIds = groupMemberIdsFor(regions, regionId).ifEmpty { setOf(regionId) }
    replaceRegions("删除区域", regions.filterNot { it.id in groupIds })
    if (previewRegionId in groupIds) {
        previewRegionId = null
    }
    if (magicSelectionPreview?.regionId in groupIds) {
        magicSelectionPreview = null
    }
}

fun AppController.toggleVisibility(regionId: String) {
    if (regions.none { it.id == regionId }) return
    val groupIds = groupMemberIdsFor(regions, regionId).ifEmpty { setOf(regionId) }
    val shouldShow = regions.any { it.id in groupIds && !it.visible }
    replaceRegions(
        "切换区域显示",
        regions.map { if (it.id in groupIds) it.copy(visible = shouldShow) else it }
    )
}
