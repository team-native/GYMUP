package com.teamnative.gymup.api

import com.teamnative.gymup.core.JwtAuthenticationInterceptor
import com.teamnative.gymup.db.DomainRecord
import com.teamnative.gymup.db.DomainStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import java.time.Instant

class WorkoutRecoveryControllerTests {
    private val domainStore = Mockito.mock(DomainStore::class.java)
    private val controller = WorkoutController(domainStore)

    @Test
    fun `active workout returns recovery state for the authenticated owner`() {
        val workout = workoutRecord()
        Mockito.`when`(domainStore.list("user-1", "workout")).thenReturn(listOf(workout))

        val response = controller.activeWorkout(authenticatedRequest("user-1"))

        assertEquals("workout-1", response["workoutId"])
        assertEquals("IN_PROGRESS", response["status"])
        assertEquals(workout.payload["routine"], response["routineSnapshot"])
        assertEquals(1, response["currentExerciseIndex"])
        assertEquals(workout.payload["sets"], response["sets"])
        assertEquals("2026-11-09T12:00:00Z", response["startedAt"])
        assertEquals(7, response["version"])
        Mockito.verify(domainStore).list("user-1", "workout")
    }

    @Test
    fun `active workout is not visible to a different authenticated owner`() {
        Mockito.`when`(domainStore.list("user-1", "workout")).thenReturn(emptyList())
        Mockito.`when`(domainStore.list("user-2", "workout")).thenReturn(listOf(workoutRecord("user-2")))

        val error = assertThrows(ApiException::class.java) {
            controller.activeWorkout(authenticatedRequest("user-1"))
        }

        assertEquals(HttpStatus.NOT_FOUND, error.status)
        assertEquals("ACTIVE_WORKOUT_NOT_FOUND", error.code)
        Mockito.verify(domainStore).list("user-1", "workout")
        Mockito.verify(domainStore, Mockito.never()).list("user-2", "workout")
    }

    @Test
    fun `active workout lookup reports when the owner has no session`() {
        Mockito.`when`(domainStore.list("user-1", "workout")).thenReturn(emptyList())

        val error = assertThrows(ApiException::class.java) {
            controller.activeWorkout(authenticatedRequest("user-1"))
        }

        assertEquals(HttpStatus.NOT_FOUND, error.status)
        assertEquals("ACTIVE_WORKOUT_NOT_FOUND", error.code)
    }

    @Test
    fun `active workout lookup requires authentication`() {
        val error = assertThrows(ApiException::class.java) {
            controller.activeWorkout(MockHttpServletRequest())
        }

        assertEquals(HttpStatus.UNAUTHORIZED, error.status)
        assertEquals("UNAUTHORIZED", error.code)
    }

    @Test
    fun `starting a second workout is rejected while an active session exists`() {
        Mockito.`when`(domainStore.get("user-1", "routine", "routine-1", true)).thenReturn(routineRecord())
        Mockito.`when`(domainStore.list("user-1", "workout")).thenReturn(listOf(workoutRecord()))

        val error = assertThrows(ApiException::class.java) {
            controller.startWorkout(mapOf("routineId" to "routine-1"), authenticatedRequest("user-1"))
        }

        assertEquals(HttpStatus.CONFLICT, error.status)
        assertEquals("ACTIVE_WORKOUT_EXISTS", error.code)
        Mockito.verify(domainStore, Mockito.never()).save(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyMap(),
            Mockito.anyString(),
        )
    }

    @Test
    fun `stale expected version returns a conflict without saving`() {
        Mockito.`when`(domainStore.get("user-1", "workout", "workout-1", true)).thenReturn(workoutRecord())

        val error = assertThrows(ApiException::class.java) {
            controller.completeWorkout("workout-1", mapOf("expectedVersion" to 6), authenticatedRequest("user-1"))
        }

        assertEquals(HttpStatus.CONFLICT, error.status)
        assertEquals("WORKOUT_VERSION_CONFLICT", error.code)
        Mockito.verify(domainStore, Mockito.never()).save(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyMap(),
            Mockito.anyString(),
        )
    }

    @Test
    fun `partial workout completion aggregates only recorded sets`() {
        val saved = captureSaveCalls()
        Mockito.`when`(domainStore.get("user-1", "workout", "workout-1", true)).thenReturn(workoutRecord())

        val response = complete(expectedVersion = 7)

        assertEquals("COMPLETED", response["status"])
        assertEquals(1, response["totalSets"])
        assertEquals(640.0, response["volume"])
        assertEquals(60, response["xp"])
        assertEquals(10, response["growthDelta"])
        assertEquals(8, response["version"])
        assertEquals("COMPLETED", saved["workout"]?.get("status"))
        assertEquals(1, saved["workout"]?.get("totalSets"))
        assertEquals(640.0, saved["workout"]?.get("volume"))
        assertEquals(60, saved["reward"]?.get("xp"))
        assertEquals(10, saved["reward"]?.get("growthDelta"))
    }

    @Test
    fun `partial workout summary uses only the recorded sets`() {
        val workout = workoutRecord()
        Mockito.`when`(domainStore.get("user-1", "workout", "workout-1", true)).thenReturn(workout)
        Mockito.`when`(domainStore.list("user-1", "workout")).thenReturn(listOf(workout))

        complete(expectedVersion = 7)
        val summary = controller.workoutSummary("workout-1", authenticatedRequest("user-1"))

        assertEquals(1, summary["totalSets"])
        assertEquals(640.0, summary["volume"])
        assertEquals(60, summary["xp"])
        assertEquals(10, summary["growthDelta"])
        assertEquals(10, summary["avatarGrowth"])
    }

    @Test
    fun `zero-set workout is saved with zero statistics and rewards`() {
        val saved = captureSaveCalls()
        val workout = workoutRecord(recordedSets = emptyList())
        Mockito.`when`(domainStore.get("user-1", "workout", "workout-1", true)).thenReturn(workout)
        Mockito.`when`(domainStore.list("user-1", "workout")).thenReturn(listOf(workout))
        Mockito.`when`(domainStore.list("user-1", "goal")).thenReturn(
            listOf(DomainRecord("goal-1", "user-1", mutableMapOf("status" to "ACTIVE"), Instant.EPOCH, Instant.EPOCH)),
        )

        val response = complete(expectedVersion = 7)

        assertEquals("COMPLETED", response["status"])
        assertEquals(0, response["totalSets"])
        assertEquals(0.0, response["volume"])
        assertEquals(0, response["xp"])
        assertEquals(0, response["growthDelta"])
        assertEquals(600, response["duration"])
        assertEquals("COMPLETED", saved["workout"]?.get("status"))
        assertEquals(0, saved["workout"]?.get("totalSets"))
        assertEquals(0.0, saved["workout"]?.get("volume"))
        assertEquals(0, saved["reward"]?.get("xp"))
        assertEquals(0, saved["reward"]?.get("growthDelta"))

        val summary = controller.workoutSummary("workout-1", authenticatedRequest("user-1"))
        val goalProgress = ProgressController(domainStore).goalProgress(authenticatedRequest("user-1"))
        assertEquals(0, summary["avatarGrowth"])
        assertEquals(0, goalProgress["growth"])
        assertEquals(0, (goalProgress["factors"] as Map<*, *>)["completedWorkouts"])
    }

    @Test
    fun `full workout completion keeps existing set and reward calculations`() {
        val saved = captureSaveCalls()
        Mockito.`when`(domainStore.get("user-1", "workout", "workout-1", true))
            .thenReturn(workoutRecord(recordedSets = fullRecordedSets()))

        val response = complete(expectedVersion = 7)

        assertEquals(6, response["totalSets"])
        assertEquals(3840.0, response["volume"])
        assertEquals(110, response["xp"])
        assertEquals(10, response["growthDelta"])
        assertEquals(110, saved["reward"]?.get("xp"))
        assertEquals(10, saved["reward"]?.get("growthDelta"))
    }

    @Test
    fun `full workout summary keeps the existing set totals`() {
        val workout = workoutRecord(recordedSets = fullRecordedSets())
        Mockito.`when`(domainStore.get("user-1", "workout", "workout-1", true)).thenReturn(workout)
        Mockito.`when`(domainStore.list("user-1", "workout")).thenReturn(listOf(workout))

        complete(expectedVersion = 7)
        val summary = controller.workoutSummary("workout-1", authenticatedRequest("user-1"))

        assertEquals(6, summary["totalSets"])
        assertEquals(3840.0, summary["volume"])
        assertEquals(110, summary["xp"])
        assertEquals(10, summary["growthDelta"])
        assertEquals(10, summary["avatarGrowth"])
    }

    private fun complete(expectedVersion: Int): Map<String, Any?> =
        controller.completeWorkout(
            "workout-1",
            mapOf("expectedVersion" to expectedVersion, "endedAt" to "2026-11-09T12:10:00Z"),
            authenticatedRequest("user-1"),
        )

    private fun authenticatedRequest(userId: String): MockHttpServletRequest =
        MockHttpServletRequest().apply {
            setAttribute(JwtAuthenticationInterceptor.USER_ID_ATTRIBUTE, userId)
        }

    private fun workoutRecord(
        ownerId: String = "user-1",
        recordedSets: List<Map<String, Any?>> = listOf(
            mapOf("setId" to "set-1", "exerciseId" to "bench", "setIndex" to 1, "weight" to 80.0, "reps" to 8, "pr" to true),
        ),
    ): DomainRecord {
        val routine = routineRecord().payload
        val payload = mutableMapOf<String, Any?>(
            "workoutId" to "workout-1",
            "routineId" to "routine-1",
            "routineName" to "Upper body",
            "routine" to routine,
            "status" to "IN_PROGRESS",
            "startedAt" to "2026-11-09T12:00:00Z",
            "currentExerciseIndex" to 1,
            "sets" to recordedSets,
            "version" to 7,
        )
        return DomainRecord("workout-1", ownerId, payload, Instant.EPOCH, Instant.EPOCH)
    }

    private fun routineRecord(): DomainRecord {
        val routine = mutableMapOf<String, Any?>(
            "routineId" to "routine-1",
            "name" to "Upper body",
            "exercises" to listOf(
                mapOf("exerciseId" to "bench", "name" to "Bench", "sets" to 3, "reps" to 8),
                mapOf("exerciseId" to "row", "name" to "Row", "sets" to 3, "reps" to 8),
            ),
        )
        return DomainRecord("routine-1", "user-1", routine, Instant.EPOCH, Instant.EPOCH)
    }

    private fun fullRecordedSets(): List<Map<String, Any?>> =
        listOf("bench", "row").flatMap { exerciseId ->
            (1..3).map { setIndex ->
                mapOf(
                    "setId" to "$exerciseId-$setIndex",
                    "exerciseId" to exerciseId,
                    "setIndex" to setIndex,
                    "weight" to 80.0,
                    "reps" to 8,
                    "pr" to false,
                )
            }
        }

    private fun captureSaveCalls(): MutableMap<String, Map<String, Any?>> {
        val saved = mutableMapOf<String, Map<String, Any?>>()
        Mockito.doAnswer { invocation ->
            val ownerId = invocation.getArgument<String>(0)
            val kind = invocation.getArgument<String>(1)
            val payload = invocation.getArgument<MutableMap<String, Any?>>(2)
            val id = invocation.getArgument<String>(3)
            if (kind == "workout") {
                val version = (payload["version"] as? Number)?.toInt() ?: 1
                payload["version"] = version + 1
            }
            saved[kind] = payload.toMap()
            DomainRecord(id, ownerId, payload, Instant.EPOCH, Instant.EPOCH)
        }.`when`(domainStore).save(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyMap(),
            Mockito.anyString(),
        )
        return saved
    }
}
