package io.github.valeronm.breadcrumb.domain

/**
 * One line naming where a coordinate is, from the parts a reverse geocoder hands back — the street
 * and house number where there are both, else the named feature, else the street alone, else the
 * locality. Where the number goes is the **address's** country's convention, not the reader's
 * language: a Berlin address reads "Musterstraße 12" in any UI, a London one "12 Baker Street".
 */
object AddressLine {

    /** Countries whose addresses put the house number before the street. */
    private val NUMBER_FIRST = setOf(
        "US", "CA", "GB", "IE", "AU", "NZ", "FR", "BE", "LU", "MC", "ZA", "IN", "PH", "SG", "MY",
        "IL", "SA", "AE", "HK",
    )

    fun of(
        street: String?,
        houseNumber: String?,
        name: String?,
        locality: String?,
        countryCode: String?,
    ): String? {
        val road = street?.trim()?.ifEmpty { null }
        val number = houseNumber?.trim()?.ifEmpty { null }
        return when {
            road != null && number != null ->
                if (countryCode?.uppercase() in NUMBER_FIRST) "$number $road" else "$road $number"
            else -> name?.trim()?.ifEmpty { null } ?: road ?: locality?.trim()?.ifEmpty { null }
        }
    }
}
