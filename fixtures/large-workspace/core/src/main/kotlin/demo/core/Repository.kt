package demo.core

class IncidentRepository {
    fun load(): List<Incident> = listOf(
        Incident("1", User("u1", "Alice"), "Smoke Check"),
        Incident("2", User("u2", "Bob"), "Water Drop"),
    )
}

