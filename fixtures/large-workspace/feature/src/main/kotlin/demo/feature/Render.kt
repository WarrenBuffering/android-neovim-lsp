package demo.feature

fun renderDashboard(): String = DashboardService().titles().joinToString(separator = "\n")
