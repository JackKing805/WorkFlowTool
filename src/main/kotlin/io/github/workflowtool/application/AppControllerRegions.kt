package io.github.workflowtool.application

import io.github.workflowtool.model.CropRegion

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
    val prepared = when (label) {
        "整体贴合选区" -> updated.map { region ->
            if (region.selected) redetectUserRegionAsWhole(region) ?: region else region
        }
        "贴合新增区域" -> updated.map { region ->
            if (region.selected) snapUserRegionToForeground(region) ?: region else region
        }
        else -> updated
    }
    val normalized = normalizeRegionIds(prepared)
    history.replaceRegions(label, normalized, trackHistory)
    if (before != regions) {
        syncManualEdits()
        if (trackHistory) {
            rememberWorkspaceSnapshot()
        }
        if (label == "整体贴合选区") {
            log("选区已按用户精修结果补全并贴合")
            if (trackHistory) {
                recordFineRefineFeedbackAsync()
            }
        } else if (label == "贴合新增区域") {
            log("新增区域已自动贴合图标边缘")
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
    if (updated == regions) return
    replaceRegions("选择区域", updated, trackHistory = false)
}

fun AppController.toggleRegionSelection(regionId: String) {
    if (regions.none { it.id == regionId }) return
    val updated = regions.map { region ->
        if (region.id == regionId) region.copy(selected = !region.selected) else region
    }
    if (updated == regions) return
    replaceRegions("切换选择区域", updated, trackHistory = false)
}

fun AppController.selectRegionRange(anchorRegionId: String, targetRegionId: String, additive: Boolean = false) {
    val anchorIndex = regions.indexOfFirst { it.id == anchorRegionId }
    val targetIndex = regions.indexOfFirst { it.id == targetRegionId }
    if (anchorIndex < 0 || targetIndex < 0) {
        selectRegion(targetRegionId, additive = additive)
        return
    }
    val start = minOf(anchorIndex, targetIndex)
    val end = maxOf(anchorIndex, targetIndex)
    val updated = regions.mapIndexed { index, region ->
        val inRange = index in start..end
        region.copy(selected = if (additive) region.selected || inRange else inRange)
    }
    if (updated == regions) return
    replaceRegions("范围选择区域", updated, trackHistory = false)
}

fun AppController.selectAndFocusRegion(regionId: String, fit: Boolean = false) {
    selectRegion(regionId)
    focusRegion(regionId, fit)
}

fun AppController.selectRegionsInBounds(
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    additive: Boolean = false
) {
    val normalizedLeft = minOf(left, right)
    val normalizedTop = minOf(top, bottom)
    val normalizedRight = maxOf(left, right)
    val normalizedBottom = maxOf(top, bottom)
    if (normalizedRight <= normalizedLeft || normalizedBottom <= normalizedTop) return
    val updated = regions.map { region ->
        val intersects = region.visible &&
            region.right >= normalizedLeft &&
            region.x <= normalizedRight &&
            region.bottom >= normalizedTop &&
            region.y <= normalizedBottom
        region.copy(selected = if (additive) region.selected || intersects else intersects)
    }
    if (updated == regions) return
    replaceRegions("框选区域", updated, trackHistory = false)
}

fun AppController.selectAll() {
    if (regions.isNotEmpty() && regions.all { it.selected }) return
    replaceRegions("全选区域", regions.map { it.copy(selected = true) }, trackHistory = false)
}

fun AppController.invertSelection() =
    replaceRegions("反选区域", regions.map { it.copy(selected = !it.selected) }, trackHistory = false)

fun AppController.clearSelection() {
    if (regions.none { it.selected }) return
    replaceRegions("取消选择", regions.map { it.copy(selected = false) }, trackHistory = false)
}

fun AppController.mergeSelectedRegions() {
    val loaded = image ?: return
    val selected = regions.filter { it.selected && it.visible }
    if (selected.size < 2) {
        log("请先选择至少两个可见区域")
        return
    }
    val merged = mergeRegionsToOuterMask(
        imageWidth = loaded.width,
        imageHeight = loaded.height,
        regions = selected,
        id = selected.first().id
    ) ?: run {
        log("合并选区失败：没有有效的可见区域")
        return
    }
    val selectedIds = selected.map { it.id }.toSet()
    val insertIndex = regions.indexOfFirst { it.id in selectedIds }.coerceAtLeast(0)
    val updated = buildList {
        regions.forEachIndexed { index, region ->
            if (index == insertIndex) add(merged.copy(selected = true))
            if (region.id !in selectedIds) add(region.copy(selected = false))
        }
    }
    replaceRegions("合并选区", updated)
    if (previewRegionId in selectedIds) {
        previewRegionId = merged.id
    }
    log("已合并 ${selected.size} 个选区")
}

fun AppController.removeRegion(regionId: String) {
    if (regions.none { it.id == regionId }) return
    replaceRegions("删除区域", regions.filterNot { it.id == regionId })
    if (previewRegionId == regionId) {
        previewRegionId = null
    }
}

fun AppController.removeSelectedRegions() {
    val selectedIds = regions.filter { it.selected }.map { it.id }.toSet()
    if (selectedIds.isEmpty()) return
    replaceRegions("删除选中区域", regions.filterNot { it.id in selectedIds })
    if (previewRegionId in selectedIds) {
        previewRegionId = null
    }
    log("已删除 ${selectedIds.size} 个选中区域")
}

fun AppController.toggleVisibility(regionId: String) {
    if (regions.none { it.id == regionId }) return
    val shouldShow = regions.any { it.id == regionId && !it.visible }
    replaceRegions(
        "切换区域显示",
        regions.map { if (it.id == regionId) it.copy(visible = shouldShow) else it }
    )
}

fun AppController.toggleSelectedVisibility() {
    val selected = regions.filter { it.selected }
    if (selected.isEmpty()) return
    val selectedIds = selected.map { it.id }.toSet()
    val shouldShow = selected.none { it.visible }
    replaceRegions(
        if (shouldShow) "显示选中区域" else "隐藏选中区域",
        regions.map { region ->
            if (region.id in selectedIds) region.copy(visible = shouldShow) else region
        }
    )
    log(if (shouldShow) "已显示 ${selected.size} 个选中区域" else "已隐藏 ${selected.size} 个选中区域")
}
