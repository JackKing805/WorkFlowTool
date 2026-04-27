package io.github.workflowtool.model

import kotlin.math.abs

data class RegionGroup(
    val root: CropRegion,
    val members: List<CropRegion>,
    val holes: List<CropRegion>
)

fun resolveRegionGroup(regions: List<CropRegion>, regionId: String): RegionGroup? {
    val indexed = regions.associateBy { it.id }
    val target = indexed[regionId] ?: return null
    val parentById = buildRegionParents(regions)
    val rootId = regionRootId(target.id, parentById)
    val root = indexed[rootId] ?: return null
    val members = regions.filter { regionRootId(it.id, parentById) == rootId }
    return RegionGroup(
        root = root,
        members = members.sortedWith(compareByDescending<CropRegion> { it.id == rootId }.thenBy { it.id }),
        holes = members.filter { it.id != rootId }
    )
}

fun resolveRegionGroups(regions: List<CropRegion>): List<RegionGroup> {
    val parentById = buildRegionParents(regions)
    val indexed = regions.associateBy { it.id }
    return regions
        .filter { parentById[it.id] == null }
        .mapNotNull { root ->
            val members = regions.filter { regionRootId(it.id, parentById) == root.id }
            val resolvedRoot = indexed[root.id] ?: return@mapNotNull null
            RegionGroup(
                root = resolvedRoot,
                members = members.sortedWith(compareByDescending<CropRegion> { it.id == root.id }.thenBy { it.id }),
                holes = members.filter { it.id != root.id }
            )
        }
}

fun groupMemberIdsFor(regions: List<CropRegion>, regionId: String): Set<String> =
    resolveRegionGroup(regions, regionId)?.members?.mapTo(linkedSetOf()) { it.id }.orEmpty()

fun primaryRegionFor(regions: List<CropRegion>, regionId: String): CropRegion? =
    resolveRegionGroup(regions, regionId)?.root

private fun buildRegionParents(regions: List<CropRegion>): Map<String, String?> {
    val sorted = regions.sortedBy { it.width * it.height }
    return sorted.associate { region ->
        region.id to regions
            .asSequence()
            .filter { it.id != region.id }
            .filter { regionContainedBy(it, region) }
            .sortedBy { it.width * it.height }
            .map { it.id }
            .firstOrNull()
    }
}

private fun regionRootId(regionId: String, parentById: Map<String, String?>): String {
    var current = regionId
    val visited = mutableSetOf<String>()
    while (true) {
        if (!visited.add(current)) return current
        val parent = parentById[current] ?: return current
        current = parent
    }
}

private fun regionContainedBy(container: CropRegion, child: CropRegion): Boolean {
    if (child.x < container.x || child.y < container.y || child.right > container.right || child.bottom > container.bottom) {
        return false
    }
    val samples = child.editPoints + listOf(
        RegionPoint(child.x + child.width / 2, child.y + child.height / 2)
    )
    return samples.all { pointInsideOrOnBoundary(container, it.x.toFloat(), it.y.toFloat()) }
}

private fun pointInsideOrOnBoundary(region: CropRegion, x: Float, y: Float): Boolean {
    val points = region.editPoints
    if (points.size < 3) {
        return x >= region.x && x <= region.right && y >= region.y && y <= region.bottom
    }
    if (pointOnPolygonBoundary(points, x, y)) return true
    var inside = false
    var previous = points.last()
    for (current in points) {
        val crosses = (current.y > y) != (previous.y > y)
        if (crosses) {
            val intersectionX = (previous.x - current.x) * (y - current.y) / (previous.y - current.y).toFloat() + current.x
            if (x < intersectionX) inside = !inside
        }
        previous = current
    }
    return inside
}

private fun pointOnPolygonBoundary(points: List<RegionPoint>, x: Float, y: Float): Boolean {
    if (points.size < 2) return false
    val probe = RegionPoint(x.toInt(), y.toInt())
    for (index in points.indices) {
        val next = points[(index + 1) % points.size]
        if (distanceToSegmentSquared(probe, points[index], next) <= 1.2f) return true
    }
    return false
}

private fun distanceToSegmentSquared(point: RegionPoint, start: RegionPoint, end: RegionPoint): Float {
    val dx = (end.x - start.x).toFloat()
    val dy = (end.y - start.y).toFloat()
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared == 0f) {
        return abs(point.x - start.x).toFloat() + abs(point.y - start.y).toFloat()
    }
    val t = (((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared).coerceIn(0f, 1f)
    val projectionX = start.x + t * dx
    val projectionY = start.y + t * dy
    val px = point.x - projectionX
    val py = point.y - projectionY
    return px * px + py * py
}
