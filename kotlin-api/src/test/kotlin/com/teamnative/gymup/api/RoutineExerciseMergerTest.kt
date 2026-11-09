package com.teamnative.gymup.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RoutineExerciseMergerTest {
    @Test
    fun `repeated add updates an existing exercise in place and preserves its order`() {
        val current = listOf(
            exercise("bench", sets = 4, order = 0),
            exercise("row", sets = 3, order = 1),
        )

        val result = RoutineExerciseMerger.merge(
            current = current,
            removeExerciseIds = emptySet(),
            updates = emptyList(),
            additions = listOf(exercise("row", sets = 5, order = 0)),
        )

        assertEquals(listOf("bench", "row"), result.map { it["exerciseId"] })
        assertEquals(5, result[1]["sets"])
        assertEquals(1, result[1]["order"])
    }

    @Test
    fun `new exercise appends after the current order and retry stays idempotent`() {
        val current = listOf(
            exercise("bench", sets = 4, order = 0),
            exercise("row", sets = 3, order = 3),
        )
        val addition = exercise("cable", sets = 3, order = 0)

        val first = RoutineExerciseMerger.merge(
            current = current,
            removeExerciseIds = emptySet(),
            updates = emptyList(),
            additions = listOf(addition),
        )
        val retry = RoutineExerciseMerger.merge(
            current = first,
            removeExerciseIds = emptySet(),
            updates = emptyList(),
            additions = listOf(addition),
        )

        assertEquals(listOf("bench", "row", "cable"), retry.map { it["exerciseId"] })
        assertEquals(4, retry.last()["order"])
        assertEquals(first, retry)
    }

    @Test
    fun `remove and update operations remain in the same merge`() {
        val current = listOf(
            exercise("bench", sets = 4, order = 0),
            exercise("curl", sets = 3, order = 1),
        )

        val result = RoutineExerciseMerger.merge(
            current = current,
            removeExerciseIds = setOf("curl"),
            updates = listOf(exercise("bench", sets = 5, order = 0)),
            additions = emptyList(),
        )

        assertEquals(listOf("bench"), result.map { it["exerciseId"] })
        assertEquals(5, result.single()["sets"])
        assertEquals(0, result.single()["order"])
    }

    private fun exercise(id: String, sets: Int, order: Int): Map<String, Any?> =
        mapOf("exerciseId" to id, "sets" to sets, "reps" to 8, "order" to order)
}
