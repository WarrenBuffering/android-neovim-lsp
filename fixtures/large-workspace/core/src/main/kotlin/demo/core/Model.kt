package demo.core

data class User(val id: String, val displayName: String)

data class Incident(val id: String, val owner: User, val title: String)

