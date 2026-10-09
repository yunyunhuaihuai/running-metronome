package com.metronome.app

import com.metronome.app.core.BlockRenderer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportGenerationTest {
    @Test
    fun `one foot import does not invalidate the other foot`() {
        val requests = ImportGeneration()
        val left = requests.next(BlockRenderer.Foot.LEFT)
        val right = requests.next(BlockRenderer.Foot.RIGHT)

        assertTrue(requests.isCurrent(BlockRenderer.Foot.LEFT, left))
        assertTrue(requests.isCurrent(BlockRenderer.Foot.RIGHT, right))

        requests.next(BlockRenderer.Foot.RIGHT)
        assertTrue(requests.isCurrent(BlockRenderer.Foot.LEFT, left))
        assertFalse(requests.isCurrent(BlockRenderer.Foot.RIGHT, right))
    }

    @Test
    fun `switching one foot to built in invalidates only its pending import`() {
        val requests = ImportGeneration()
        val left = requests.next(BlockRenderer.Foot.LEFT)
        val right = requests.next(BlockRenderer.Foot.RIGHT)

        requests.invalidate(BlockRenderer.Foot.LEFT)

        assertFalse(requests.isCurrent(BlockRenderer.Foot.LEFT, left))
        assertTrue(requests.isCurrent(BlockRenderer.Foot.RIGHT, right))
    }
}
