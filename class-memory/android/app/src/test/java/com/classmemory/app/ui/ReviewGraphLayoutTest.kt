package com.classmemory.app.ui

import com.classmemory.app.review.ReviewGraphEdge
import org.junit.Assert.*
import org.junit.Test

class ReviewGraphLayoutTest {
    @Test fun thirteenNodesBranchMergeAndLongTitlesKeepIndependentCoordinates() {
        val nodes = (0..12).map { "node-$it" }
        val edges = listOf(
            ReviewGraphEdge(nodes[0], nodes[1], "derives"),
            ReviewGraphEdge(nodes[0], nodes[2], "derives"),
            ReviewGraphEdge(nodes[1], nodes[3], "depends_on"),
            ReviewGraphEdge(nodes[2], nodes[3], "depends_on"),
            ReviewGraphEdge(nodes[2], nodes[10], "related")
        )
        val layout = graphLayout(nodes, edges)
        assertEquals(13, layout.points.size)
        assertEquals(13, layout.points.values.toSet().size)
        assertEquals(layout.points.getValue(nodes[1]).second, layout.points.getValue(nodes[2]).second)
        assertTrue(layout.points.getValue(nodes[0]).second < layout.points.getValue(nodes[1]).second)
        assertTrue(layout.points.getValue(nodes[3]).second > layout.points.getValue(nodes[2]).second)
        assertTrue(layout.width > 300) // Wide generations stay in the pannable world.
        layout.points.values.forEach { (x, y) ->
            assertTrue(x - 80 >= 0 && x + 80 <= layout.width)
            assertTrue(y - 30 >= 0 && y + 30 <= layout.height)
        }
        // Node dimensions do not depend on title length; drawing and hit areas use these centers.
        assertEquals(layout, graphLayout(nodes, edges))
    }
}
