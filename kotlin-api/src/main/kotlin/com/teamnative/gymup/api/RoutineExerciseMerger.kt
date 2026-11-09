package com.teamnative.gymup.api

/**
 * Merges routine exercise edits while making repeated `add` entries idempotent by
 * exerciseId. Existing exercises keep their position and order value; new exercises
 * append until the separate absolute-order endpoint is called.
 */
internal object RoutineExerciseMerger {
    fun merge(
        current: List<Map<String, Any?>>,
        removeExerciseIds: Set<String>,
        updates: List<Map<String, Any?>>,
        additions: List<Map<String, Any?>>,
    ): List<Map<String, Any?>> {
        val updatesByExerciseId = updates.associateBy { it["exerciseId"]?.toString() }
        val exercises = current
            .filterNot { it["exerciseId"]?.toString() in removeExerciseIds }
            .map { exercise ->
                exercise + (updatesByExerciseId[exercise["exerciseId"]?.toString()] ?: emptyMap())
            }
            .toMutableList()

        additions.forEach { addition ->
            val exerciseId = addition["exerciseId"]?.toString()
            val existingIndex = exercises.indexOfFirst { it["exerciseId"]?.toString() == exerciseId }
            if (existingIndex < 0) {
                exercises += addition.withOrder(nextOrder(exercises))
            } else {
                val existing = exercises[existingIndex]
                val updated = existing + addition
                exercises[existingIndex] = if (existing.containsKey("order")) {
                    updated + ("order" to existing["order"])
                } else {
                    updated - "order"
                }
            }
        }

        return exercises
    }

    private fun nextOrder(exercises: List<Map<String, Any?>>): Int =
        exercises.mapIndexed { index, exercise -> exercise.orderIndex(index) }
            .maxOrNull()
            ?.coerceAtLeast(exercises.size - 1)
            ?.plus(1)
            ?: exercises.size

    private fun Map<String, Any?>.orderIndex(default: Int): Int =
        (this["order"] as? Number)?.toInt()
            ?: this["order"]?.toString()?.toIntOrNull()
            ?: default

    private fun Map<String, Any?>.withOrder(order: Int): Map<String, Any?> = this + ("order" to order)
}
