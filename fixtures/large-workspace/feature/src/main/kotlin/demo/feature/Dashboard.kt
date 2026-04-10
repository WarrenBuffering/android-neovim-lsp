package demo.feature

import demo.core.IncidentRepository

class DashboardService(
    private val repository: IncidentRepository = IncidentRepository(),
) {
    fun titles(): List<String> = repository.load().map { "${it.owner.displayName}: ${it.title}" }
}

